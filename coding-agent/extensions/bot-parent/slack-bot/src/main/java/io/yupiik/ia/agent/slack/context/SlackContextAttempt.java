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
package io.yupiik.ia.agent.slack.context;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.bot.runtime.model.AgentConfigurationDocument;
import io.yupiik.ia.agent.slack.configuration.SlackContextConfiguration;
import io.yupiik.ia.agent.slack.transport.SlackWebApi;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Host-only attempt owner. Polling never waits for HTTP; revocation precedes runtime cleanup. */
public final class SlackContextAttempt implements AutoCloseable {
    private final SlackContextMailbox mailbox;
    private final SlackContextService service;
    private final SlackActionRegistry.Grant grant;
    private final Dispatcher dispatcher;
    private final Clock clock;
    private final ReentrantLock state = new ReentrantLock();
    private final int maxCalls;
    private final SlackContextConfiguration configuration;
    private int calls;
    private boolean closed;
    private String terminalStatus;
    private SlackContextMailbox.Request current;
    private Map<String, Object> last;
    private CompletableFuture<Map<String, Object>> pending;
    private Future<?> work;
    private CompletableFuture<Map<String, Object>> initialResult;
    private Future<?> initialWork;
    private final Set<String> seen = new HashSet<>();

    public SlackContextAttempt(
            final Path attempt,
            final JsonMapper json,
            final SlackContextConfiguration configuration,
            final SlackWebApi api,
            final SlackActionRegistry.Grant grant,
            final Clock clock,
            final Dispatcher dispatcher)
            throws IOException {
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.grant = grant;
        this.configuration = configuration;
        if (!grant.active(clock.millis())) {
            grant.close();
            throw new IllegalStateException("Slack context authorization is unavailable");
        }
        maxCalls = configuration.maxRequests() + 16;
        try {
            service = new SlackContextService(configuration, api, grant, clock);
        } catch (final RuntimeException failure) {
            grant.close();
            throw failure;
        }
        // Transport channel is a unique attempt binding, not a Slack channel or bearer credential.
        final var binding = "C"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 31).toUpperCase(Locale.ROOT);
        try {
            final var directory = Files.createDirectory(
                    attempt.resolve("storage/slack-context"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            mailbox = new SlackContextMailbox(directory, json, binding);
        } catch (final IOException | RuntimeException failure) {
            service.close();
            grant.close();
            throw failure;
        }
        try {
            final var file = attempt.resolve("configuration.json");
            final var document = json.fromString(AgentConfigurationDocument.class, Files.readString(file));
            final var sanitized = new LinkedHashMap<String, Object>(document.configuration());
            sanitized.put(
                    "slackContext",
                    Map.of(
                            "enabled",
                            true,
                            "directory",
                            "/attempt/storage/slack-context",
                            "channel",
                            binding,
                            "timeoutSeconds",
                            Math.min(300, configuration.timeoutSeconds() + 5)));
            Files.writeString(
                    file, json.toString(new AgentConfigurationDocument(sanitized, document.completionSettings())));
        } catch (final IOException | RuntimeException error) {
            close();
            throw error;
        }
        grant.onRevoke(this::revoke);
    }

    /** Initial transient snapshot uses the same dispatcher, authority and budgets as tool calls. */
    public Map<String, Object> initialContext(final BooleanSupplier cancelled) {
        requireInitialOwnership(cancelled);
        if (!configuration.thread().enabled()) {
            return initialScope(status("disabled"));
        }
        final var result = new CompletableFuture<Map<String, Object>>();
        state.lock();
        try {
            if (closed || terminalStatus != null || initialResult != null) {
                throw new IllegalStateException("Slack context preparation is unavailable");
            }
            initialResult = result;
            initialWork = dispatcher.submit(() -> service.call(Map.of("operation", "thread")), result);
            if (initialWork == null) {
                result.complete(status(dispatcher.coolingDown() ? "rate_limited" : "busy"));
            }
        } finally {
            state.unlock();
        }
        try {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(configuration.timeoutSeconds() + 1L);
            Map<String, Object> snapshot;
            while (true) {
                requireInitialOwnership(cancelled);
                try {
                    snapshot = result.get(25, TimeUnit.MILLISECONDS);
                    break;
                } catch (final TimeoutException waiting) {
                    if (System.nanoTime() >= deadline) {
                        snapshot = status("unavailable");
                        break;
                    }
                }
            }
            requireInitialOwnership(cancelled);
            state.lock();
            try {
                if (closed || terminalStatus != null || initialResult != result) {
                    throw new IllegalStateException("Slack context preparation lost ownership");
                }
                // The handoff and revocation are serialized; no network work runs here.
                final var handoff = new AtomicReference<Map<String, Object>>();
                final var body = snapshot;
                grant.whileActive(() -> handoff.set(initialScope(body)));
                if (handoff.get() == null) {
                    throw new IllegalStateException("Slack context authorization is unavailable");
                }
                return handoff.get();
            } finally {
                state.unlock();
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            close();
            throw new IllegalStateException("Slack context preparation interrupted");
        } catch (final ExecutionException | CancellationException unavailable) {
            close();
            throw new IllegalStateException("Slack context preparation is unavailable");
        } finally {
            state.lock();
            try {
                if (initialResult == result) {
                    if (initialWork != null) {
                        initialWork.cancel(true);
                    }
                    initialResult = null;
                    initialWork = null;
                }
            } finally {
                state.unlock();
            }
        }
    }

    private void requireInitialOwnership(final BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || !authorized()) {
            close();
            throw new IllegalStateException("Slack context preparation cancelled or unauthorized");
        }
    }

    /** Expiry is a whole-attempt terminal condition, not just a denial of further reads. */
    public boolean authorized() {
        if (clock.millis() >= grant.deadline()) {
            grant.revoke(SlackActionRegistry.Terminal.EXPIRED);
        }
        state.lock();
        try {
            return !closed && terminalStatus == null && grant.active(clock.millis());
        } finally {
            state.unlock();
        }
    }

    private Map<String, Object> initialScope(final Map<String, Object> snapshot) {
        final var scoped = new LinkedHashMap<String, Object>(snapshot);
        scoped.put("channelId", grant.channel());
        scoped.put("threadTs", grant.threadRoot());
        scoped.put("cutoffTs", grant.triggerTs());
        return Map.copyOf(scoped);
    }

    public void poll() throws IOException {
        if (clock.millis() >= grant.deadline()) {
            grant.revoke(SlackActionRegistry.Terminal.EXPIRED);
        }
        state.lock();
        try {
            pollActive();
        } finally {
            state.unlock();
        }
    }

    private void pollActive() throws IOException {
        if (closed) {
            return;
        }
        if (terminalStatus != null) {
            try {
                final var request = mailbox.pending();
                if (request != null && request.expires() > clock.millis()) {
                    mailbox.respond(request, status(terminalStatus));
                }
            } catch (final IOException expiredOrMalformed) {
                // Only fixed terminal status is served after authority ends. No service/cache access.
            }
            return;
        }
        if (pending != null && current.expires() <= clock.millis()) {
            if (work != null) {
                work.cancel(true);
            }
            pending = null;
            work = null;
            last = status("unavailable");
        }
        final SlackContextMailbox.Request request;
        try {
            request = mailbox.pending();
        } catch (final IOException malformed) {
            // An untrusted malformed/expired slot grants nothing. It must not stop cancellation polling.
            return;
        }
        if (request == null) {
            return;
        }
        if (pending != null) {
            if (!pending.isDone()) {
                return;
            }
            last = pending.join();
            pending = null;
            work = null;
            if (current.equals(request)) {
                respond(current, last);
            }
            return;
        }
        if (current != null && current.id().equals(request.id())) {
            if (current.equals(request) && last != null) {
                respond(request, last);
            }
            return;
        }
        final var replay = seen.contains(request.id());
        current = request;
        last = null;
        if (replay || ++calls > maxCalls) {
            last = status(replay ? "denied" : "budget_exhausted");
            respond(request, last);
            return;
        }
        seen.add(request.id());
        if ("search".equals(request.arguments().get("operation")) && !"ok".equals(grant.status())) {
            last = status(grant.status());
            respond(request, last);
            return;
        }
        pending = new CompletableFuture<>();
        final var result = pending;
        work = dispatcher.submit(
                () -> {
                    final var answer = new LinkedHashMap<String, Object>(service.call(request.arguments()));
                    answer.put(
                            "status",
                            answer.get("messages") instanceof List<?> messages && messages.isEmpty()
                                    ? "no_results"
                                    : "ok");
                    if ("summary".equals(request.arguments().get("operation"))) {
                        answer.put("searchAuthorization", grant.status());
                    }
                    return Map.copyOf(answer);
                },
                result);
        if (work == null) {
            pending = null;
            last = status(dispatcher.coolingDown() ? "rate_limited" : "busy");
            respond(request, last);
        }
    }

    private static Map<String, Object> status(final String value) {
        return Map.of("status", value);
    }

    private void respond(final SlackContextMailbox.Request request, final Map<String, Object> body) {
        if (closed || !grant.active(clock.millis()) || request.expires() <= clock.millis()) {
            return;
        }
        grant.whileActive(() -> {
            try {
                mailbox.respond(request, body);
            } catch (final IOException staleOrMalformed) {
                // The client may have timed out, cancelled or replaced its slot. Never fail the task for that race.
            }
        });
    }

    @Override
    public void close() {
        state.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            cancelWork();
            service.close();
            grant.close();
            try {
                mailbox.close();
            } catch (final IOException ignored) {
                // No authority remains; no path-based cleanup is attempted here.
            }
        } finally {
            state.unlock();
        }
    }

    /** Revoke authority immediately, retaining only a status-only transport until worker teardown. */
    private void revoke() {
        state.lock();
        try {
            if (closed || terminalStatus != null) {
                return;
            }
            terminalStatus = grant.status();
            cancelWork();
            service.close();
        } finally {
            state.unlock();
        }
    }

    private void cancelWork() {
        if (initialWork != null) {
            initialWork.cancel(true);
        }
        if (initialResult != null) {
            initialResult.cancel(true);
        }
        initialWork = null;
        initialResult = null;
        if (work != null) {
            work.cancel(true);
        }
        if (pending != null) {
            pending.cancel(true);
        }
        pending = null;
        work = null;
        current = null;
        last = null;
        seen.clear();
    }

    /** One per controller: bounded admission, no queued unlimited work, and a shared upstream cooldown. */
    public static final class Dispatcher implements AutoCloseable {
        private final ExecutorService executor;
        private final AtomicLong cooldownUntil = new AtomicLong();

        public Dispatcher(final SlackContextConfiguration configuration) {
            executor = new ThreadPoolExecutor(
                    0,
                    configuration.maxConcurrent(),
                    10,
                    TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    Thread.ofVirtual().factory());
        }

        private boolean coolingDown() {
            final var deadline = cooldownUntil.get();
            return deadline != 0 && deadline - System.nanoTime() > 0;
        }

        private Future<?> submit(
                final Supplier<Map<String, Object>> invocation, final CompletableFuture<Map<String, Object>> result) {
            if (coolingDown()) {
                return null;
            }
            try {
                return executor.submit(() -> {
                    try {
                        result.complete(invocation.get());
                    } catch (final SlackWebApi.SlackApiException error) {
                        final var rateLimited = error.status() == 429 || "ratelimited".equals(error.error());
                        if (rateLimited) {
                            final var retry = error.retryAfter().orElse(Duration.ofSeconds(30));
                            final var cap = Duration.ofNanos(Long.MAX_VALUE / 4);
                            final var deadline = System.nanoTime() + (retry.compareTo(cap) > 0 ? cap : retry).toNanos();
                            cooldownUntil.updateAndGet(
                                    current -> current == 0 || deadline - current > 0 ? deadline : current);
                        }
                        result.complete(status(
                                rateLimited
                                        ? "rate_limited"
                                        : switch (error.error()) {
                                            case "invalid_action_token",
                                                    "action_token_expired",
                                                    "expired_action_token" -> "authorization_expired";
                                            case "missing_scope", "invalid_auth", "not_enabled", "restricted_action" ->
                                                "authorization_unavailable";
                                            default -> "unavailable";
                                        }));
                    } catch (final IllegalArgumentException denied) {
                        result.complete(status("denied"));
                    } catch (final IllegalStateException failure) {
                        // Only fixed service messages are inspected, never returned or logged.
                        final var message = failure.getMessage();
                        result.complete(status(
                                message != null && message.contains("budget")
                                        ? "budget_exhausted"
                                        : message != null && message.contains("expired")
                                                ? "authorization_expired"
                                                : message != null && message.contains("disabled")
                                                        ? "denied"
                                                        : "unavailable"));
                    } catch (final RuntimeException failure) {
                        result.complete(status("unavailable"));
                    }
                });
            } catch (final RuntimeException stopped) {
                return null;
            }
        }

        @Override
        public void close() {
            executor.shutdownNow();
            boolean interrupted = false;
            while (!executor.isTerminated()) {
                try {
                    executor.awaitTermination(1, TimeUnit.SECONDS);
                } catch (final InterruptedException stopping) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
