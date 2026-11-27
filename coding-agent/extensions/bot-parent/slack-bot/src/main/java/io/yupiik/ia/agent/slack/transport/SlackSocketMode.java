/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.ia.agent.slack.transport;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.slack.model.SlackJson;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.DoubleSupplier;
import java.util.logging.Logger;

/**
 * Outbound Socket Mode with durable-before-ACK admission delegated to the parent through typed envelopes.
 * The parent constructs this object; mapper, API, HTTP client, scheduler and suppliers are borrowed, never closed.
 * Close this transport before closing its dependencies. The WebSocket client must be redirect-never,
 * authenticator-free and cookie-free; each reconnect discovers a fresh URL and attaches no API token to the handshake.
 * Text messages and ACKs are bounded by {@link Settings#maxMessageBytes()} in UTF-8 bytes.
 *
 * <p>Refresh/disconnect, malformed/oversized messages, callback/ACK failures and socket errors reconnect using
 * the supplied scheduler and bounded exponential jitter (supplier in {@code [0,1)}), without sleeps or a dedicated
 * executor. Discovery Retry-After is a minimum delay, even above the backoff maximum; {@code hello} resets backoff
 * and {@code warning} is informational. Disconnect reasons {@code disabled}/{@code link_disabled} and terminal
 * credential failures enter {@link State#DISABLED} without reconnecting. Recreate after credentials/settings are repaired.
 */
public final class SlackSocketMode implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(SlackSocketMode.class.getName());
    private static final Set<String> TERMINAL_ERRORS = Set.of(
            "invalid_token",
            "invalid_auth",
            "not_authed",
            "token_revoked",
            "account_inactive",
            "not_allowed_token_type",
            "missing_scope",
            "link_disabled",
            "disabled",
            "not_enabled");
    private final JsonMapper json;
    private final SlackWebApi api;
    private final HttpClient client;
    private final ScheduledExecutorService scheduler;
    private final Settings settings;
    private final EnvelopeHandler handler;
    private final DoubleSupplier jitter;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile State state = State.NEW;
    private long generation;
    private int failures;
    private ScheduledFuture<?> reconnect;
    private CompletableFuture<?> discovery;
    private CompletableFuture<WebSocket> connecting;
    private WebSocket socket;

    public SlackSocketMode(
            final JsonMapper json,
            final SlackWebApi api,
            final HttpClient client,
            final ScheduledExecutorService scheduler,
            final Settings settings,
            final EnvelopeHandler handler,
            final DoubleSupplier jitter) {
        this.json = Objects.requireNonNull(json);
        this.api = Objects.requireNonNull(api);
        this.client = Objects.requireNonNull(client);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.settings = Objects.requireNonNull(settings);
        this.handler = Objects.requireNonNull(handler);
        this.jitter = Objects.requireNonNull(jitter);
        if (client.followRedirects() != HttpClient.Redirect.NEVER
                || client.authenticator().isPresent()
                || client.cookieHandler().isPresent()) {
            throw new IllegalArgumentException(
                    "Slack requires a redirect-never, authenticator-free, cookie-free client");
        }
    }

    public record Settings(
            Duration connectTimeout, Duration minReconnectDelay, Duration maxReconnectDelay, int maxMessageBytes) {
        public Settings {
            Objects.requireNonNull(connectTimeout);
            Objects.requireNonNull(minReconnectDelay);
            Objects.requireNonNull(maxReconnectDelay);
            if (connectTimeout.isNegative()
                    || connectTimeout.isZero()
                    || minReconnectDelay.isNegative()
                    || minReconnectDelay.isZero()
                    || maxReconnectDelay.compareTo(minReconnectDelay) < 0
                    || maxReconnectDelay.compareTo(Duration.ofDays(1)) > 0
                    || maxMessageBytes < 1) {
                throw new IllegalArgumentException("Invalid Socket Mode timeouts or message limit");
            }
        }
    }

    @FunctionalInterface
    public interface EnvelopeHandler {
        /**
         * Receives the complete typed envelope, serially on WebSocket delivery. Authorize and durably admit/deduplicate
         * before returning; return promptly for Slack's three-second ACK deadline, never run the agent here.
         * Empty means successful admission with a bare ACK, not rejection; throw on failed admission to withhold ACK
         * and reconnect for redelivery. ACKs always include the envelope ID; a reply is included only when the envelope
         * accepts response payloads. The parent owns deduplication and interaction logic; transport caches no envelopes.
         */
        Optional<SlackJson.Reply> handle(SlackJson.Envelope envelope) throws Exception;
    }

    public enum State {
        NEW,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        DISABLED,
        CLOSED
    }

    public State state() {
        return state;
    }

    /** Idempotently schedules the initial outbound connection; disabled/closed instances cannot restart. */
    public void start() {
        lock.lock();
        try {
            if (state != State.NEW) {
                return;
            }
            state = State.CONNECTING;
            schedule(Duration.ZERO);
        } finally {
            lock.unlock();
        }
    }

    // Called with the lifecycle lock held. Rejected scheduling is terminal, never a tight retry loop.
    private void schedule(final Duration delay) {
        try {
            final var nanos = delay.compareTo(Duration.ofNanos(Long.MAX_VALUE)) > 0 ? Long.MAX_VALUE : delay.toNanos();
            reconnect = scheduler.schedule(this::connect, nanos, TimeUnit.NANOSECONDS);
        } catch (final RuntimeException rejected) {
            state = State.DISABLED;
            LOGGER.warning("Slack Socket Mode disabled: scheduling_unavailable");
        }
    }

    private void connect() {
        final long attempt;
        lock.lock();
        try {
            if (state != State.CONNECTING && state != State.RECONNECTING) {
                return;
            }
            reconnect = null;
            state = State.CONNECTING;
            attempt = ++generation;
            final var opening = api.openConnection().toCompletableFuture();
            discovery = opening;
            opening.whenComplete((url, error) -> {
                lock.lock();
                try {
                    if (!active(attempt)) {
                        return;
                    }
                    discovery = null;
                    if (error != null) {
                        failed(attempt, error);
                        return;
                    }
                    // The URL contains a short-lived ticket; no Authorization header is ever attached here.
                    try {
                        final var future = client.newWebSocketBuilder()
                                .connectTimeout(settings.connectTimeout())
                                .buildAsync(url, new SocketListener(attempt));
                        connecting = future;
                        future.whenComplete((opened, failure) -> {
                            lock.lock();
                            try {
                                if (!active(attempt)) {
                                    if (opened != null) {
                                        opened.abort();
                                    }
                                    return;
                                }
                                connecting = null;
                                if (failure != null) {
                                    failed(attempt, failure);
                                }
                            } finally {
                                lock.unlock();
                            }
                        });
                    } catch (final RuntimeException failure) {
                        failed(attempt, failure);
                    }
                } finally {
                    lock.unlock();
                }
            });
        } finally {
            lock.unlock();
        }
    }

    private boolean active(final long attempt) {
        return generation == attempt && state != State.CLOSED && state != State.DISABLED;
    }

    private void failed(final long attempt, final Throwable failure) {
        var cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        final var apiError = cause instanceof SlackWebApi.SlackApiException error ? error : null;
        retire(
                attempt,
                apiError != null && TERMINAL_ERRORS.contains(apiError.error()),
                apiError == null ? Duration.ZERO : apiError.retryAfter().orElse(Duration.ZERO));
    }

    private void retire(final long attempt, final boolean disabled, final Duration retryAfter) {
        lock.lock();
        try {
            if (!active(attempt)) {
                return;
            }
            ++generation; // Invalidate callbacks before cancelling any futures or aborting the socket.
            state = disabled ? State.DISABLED : State.RECONNECTING;
            if (disabled) {
                LOGGER.warning("Slack Socket Mode disabled: remote_policy_or_credentials");
            } else {
                LOGGER.info("Slack Socket Mode reconnecting");
            }
            release();
            if (disabled) {
                return;
            }
            final var delay = backoff();
            schedule(retryAfter.compareTo(delay) > 0 ? retryAfter : delay);
        } finally {
            lock.unlock();
        }
    }

    private Duration backoff() {
        final var minimum = settings.minReconnectDelay().toNanos();
        final var maximum = settings.maxReconnectDelay().toNanos();
        var cap = minimum;
        for (int i = 0; i < failures && cap < maximum; i++) {
            cap = cap > maximum / 2 ? maximum : Math.min(maximum, cap * 2);
        }
        failures = Math.min(63, failures + 1);
        double sample;
        try {
            sample = jitter.getAsDouble();
        } catch (final RuntimeException invalidSupplier) {
            sample = 0.5;
        }
        if (!Double.isFinite(sample) || sample < 0 || sample >= 1) {
            sample = 0.5;
        }
        // Equal jitter, bounded below by the configured minimum and above by the exponential cap.
        final var floor = Math.max(minimum, cap / 2);
        return Duration.ofNanos(floor + (long) ((cap - floor) * sample));
    }

    private void release() {
        if (reconnect != null) {
            reconnect.cancel(false);
            reconnect = null;
        }
        if (discovery != null) {
            discovery.cancel(true);
            discovery = null;
        }
        if (connecting != null) {
            connecting.cancel(true);
            connecting = null;
        }
        if (socket != null) {
            socket.abort();
            socket = null;
        }
    }

    /**
     * Terminal and idempotent: cancels connection/reconnect work and aborts the owned socket without waiting for
     * a peer close handshake. Does not shut down borrowed dependencies.
     */
    @Override
    public void close() {
        lock.lock();
        try {
            if (state == State.CLOSED) {
                return;
            }
            state = State.CLOSED;
            LOGGER.info("Slack Socket Mode closed");
            ++generation;
            release();
        } finally {
            lock.unlock();
        }
    }

    private final class SocketListener implements WebSocket.Listener {
        private final long attempt;
        private final StringBuilder text = new StringBuilder();
        private int bytes;
        private boolean highSurrogate;
        private boolean helloReceived;

        private SocketListener(final long attempt) {
            this.attempt = attempt;
        }

        @Override
        public void onOpen(final WebSocket webSocket) {
            lock.lock();
            try {
                if (!active(attempt)) {
                    webSocket.abort();
                    return;
                }
                socket = webSocket;
                state = State.CONNECTED;
                LOGGER.info("Slack Socket Mode connection open");
                webSocket.request(1);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public CompletionStage<?> onText(final WebSocket webSocket, final CharSequence data, final boolean last) {
            if (!current()) {
                return CompletableFuture.completedFuture(null);
            }
            // Count UTF-8 incrementally, including a surrogate pair split across frames/callbacks.
            // Unpaired surrogates are conservatively charged three bytes rather than JDK replacement's
            // one.
            for (int i = 0; i < data.length(); i++) {
                final var c = data.charAt(i);
                final var size = Character.isLowSurrogate(c) && highSurrogate ? 1 : c <= 0x7f ? 1 : c <= 0x7ff ? 2 : 3;
                if (size > settings.maxMessageBytes() - bytes) {
                    retire(attempt, false, Duration.ZERO);
                    return CompletableFuture.completedFuture(null);
                }
                bytes += size;
                highSurrogate = Character.isHighSurrogate(c);
            }
            text.append(data);
            if (!last) {
                webSocket.request(1);
                return CompletableFuture.completedFuture(null);
            }
            final var message = text.toString();
            text.setLength(0);
            bytes = 0;
            highSurrogate = false;
            try {
                return dispatch(webSocket, message);
            } catch (final Exception failure) {
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                retire(attempt, false, Duration.ZERO);
                return CompletableFuture.completedFuture(null);
            }
        }

        private boolean current() {
            lock.lock();
            try {
                return active(attempt);
            } finally {
                lock.unlock();
            }
        }

        private CompletionStage<?> dispatch(final WebSocket webSocket, final String message) throws Exception {
            final var envelope = json.fromString(SlackJson.Envelope.class, message);
            if (envelope == null) {
                throw new IllegalArgumentException("Expected Socket Mode object");
            }
            final var type = envelope.type();
            if ("hello".equals(type)) {
                lock.lock();
                try {
                    if (active(attempt)) {
                        failures = 0;
                        if (!helloReceived) {
                            helloReceived = true;
                            LOGGER.info("Slack Socket Mode hello received");
                        }
                    }
                } finally {
                    lock.unlock();
                }
            } else if ("disconnect".equals(type)) {
                final var reason = envelope.reason();
                retire(attempt, "disabled".equals(reason) || "link_disabled".equals(reason), Duration.ZERO);
                return CompletableFuture.completedFuture(null);
            } else if (envelope.envelope_id() != null && !envelope.envelope_id().isBlank()) {
                final var payload = Objects.requireNonNull(handler.handle(envelope));
                final var ack = new SlackJson.Ack(
                        envelope.envelope_id(),
                        Boolean.TRUE.equals(envelope.accepts_response_payload()) ? payload.orElse(null) : null);
                final var serialized = json.toString(ack);
                if (serialized.getBytes(StandardCharsets.UTF_8).length > settings.maxMessageBytes()) {
                    throw new IllegalArgumentException("Socket Mode ACK too large");
                }
                if (!current()) {
                    return CompletableFuture.completedFuture(null);
                }
                // No next envelope is requested until ACK send completion: bounded queue, no publication
                // replay.
                return webSocket.sendText(serialized, true).handle((sent, failure) -> {
                    if (failure != null) {
                        retire(attempt, false, Duration.ZERO);
                    } else if (current()) {
                        webSocket.request(1);
                    }
                    return null;
                });
            } else if (!"warning".equals(type)) {
                throw new IllegalArgumentException("Unknown Socket Mode message without envelope_id");
            }
            if (current()) {
                webSocket.request(1);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(final WebSocket webSocket, final ByteBuffer data, final boolean last) {
            retire(attempt, false, Duration.ZERO);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onPing(final WebSocket webSocket, final ByteBuffer message) {
            return webSocket.sendPong(message).whenComplete((sent, failure) -> {
                if (failure != null) {
                    retire(attempt, false, Duration.ZERO);
                } else if (current()) {
                    webSocket.request(1);
                }
            });
        }

        @Override
        public CompletionStage<?> onPong(final WebSocket webSocket, final ByteBuffer message) {
            if (current()) {
                webSocket.request(1);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(final WebSocket webSocket, final int statusCode, final String reason) {
            retire(attempt, false, Duration.ZERO);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(final WebSocket webSocket, final Throwable error) {
            retire(attempt, false, Duration.ZERO);
        }
    }
}
