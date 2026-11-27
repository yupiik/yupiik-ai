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
package io.yupiik.ia.agent.core.llm;

import static java.nio.charset.StandardCharsets.US_ASCII;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Optional, explicitly owned relay for one deployment attempt. Create a new instance for each attempt
 * and give clients only {@link #address()} and {@link #accessToken()}, never the upstream credential.
 * This is not an egress sandbox: callers must separately control network access and bind exposure.
 * Only POST to the pinned /chat/completions or /responses route is accepted; its body is sent to the exact configured upstream URI.
 * No caller headers are forwarded. Transport-generated headers are supplied by the JDK client.
 * Responses forward status, an allowlisted content type, and bounded bytes, including SSE.
 * A timeout, disconnect or oversized streaming response aborts the exchange; once response headers
 * have been sent its status cannot be replaced. Closing cancels all active attempts.
 */
public final class LlmRelay implements AutoCloseable {
    private static final Pattern BEARER_SECRET = Pattern.compile("[A-Za-z0-9._~+/-]+=*");
    private static final Pattern LOOPBACK_IPV4 = Pattern.compile("127\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}");

    private static final int MAX_REQUEST_BYTES = 8 * 1024 * 1024;
    private static final long MAX_RESPONSE_BYTES = 128L * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private final URI upstream;
    private final String route;
    private final String bearerSecret;
    private final String accessToken;
    private final byte[] authorizationDigest;
    private final Semaphore permits;
    private final ProviderRequestBudget budget;
    private final HttpServer server;
    private final ExecutorService executor;
    private final ScheduledExecutorService timer;
    private final HttpClient client;
    private final Set<Attempt> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Starts immediately on the supplied address (port zero is supported). HTTP upstreams must use
     * a numeric loopback address, exclusively for local deployments/tests; all others require HTTPS.
     * The URI must have an absolute, unencoded path and no user info, query, fragment or dot segments.
     * The secret must be a nonempty bearer-token value. Validation errors never include its value.
     * The concurrency limit covers upload, upstream work and streaming; excess requests get 429
     * without entering a blocking work queue. The 120-second deadline covers that entire lifecycle.
     * The caller supplies trusted provider/account settings through {@link ProviderRequestBudget#fromConfiguration}
     * to apply an additional shared limit immediately before dispatch. Unknown
     * upstream outcomes retain durable budget debt, including cancellation on this relay's deadline.
     */
    public LlmRelay(
            final InetSocketAddress bindAddress,
            final URI upstream,
            final String bearerSecret,
            final int maxConcurrency,
            final ProviderRequestBudget budget)
            throws IOException {
        validateUpstream(upstream);
        final var api = ChatRoutes.detectFlavor(upstream.toString());
        if ((api != ApiFlavor.COMPLETIONS && api != ApiFlavor.RESPONSES)
                || !upstream.getRawPath().endsWith(api == ApiFlavor.RESPONSES ? "/responses" : "/chat/completions")) {
            throw new IllegalArgumentException("Explicit completions or responses upstream required");
        }
        this.route = api == ApiFlavor.RESPONSES ? "/responses" : "/chat/completions";
        if (bearerSecret == null || !BEARER_SECRET.matcher(bearerSecret).matches()) {
            throw new IllegalArgumentException("Invalid upstream bearer credential");
        }
        if (bindAddress == null || maxConcurrency < 1) {
            throw new IllegalArgumentException("A bind address and positive concurrency limit are required");
        }
        this.upstream = upstream;
        this.budget = Objects.requireNonNull(budget, "Trusted provider budget required");
        this.bearerSecret = bearerSecret;
        this.permits = new Semaphore(maxConcurrency);
        final var random = new byte[32];
        new SecureRandom().nextBytes(random);
        this.accessToken = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        this.authorizationDigest = digest("Bearer " + accessToken);
        this.server = HttpServer.create(bindAddress, 0);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.timer = Executors.newSingleThreadScheduledExecutor();
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(TIMEOUT)
                .executor(executor)
                .build();
        try {
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        } catch (final RuntimeException failure) {
            close();
            throw new IllegalStateException("Unable to start LLM relay");
        }
    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    /** Sensitive per-attempt credential; do not log or persist it. */
    public String accessToken() {
        return accessToken;
    }

    private static void validateUpstream(final URI uri) {
        if (uri == null
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getRawPath() == null
                || !uri.getRawPath().startsWith("/")
                || uri.getRawPath().contains("%")
                || uri.getRawPath().contains("\\")
                || Arrays.stream(uri.getRawPath().split("/", -1))
                        .anyMatch(segment -> segment.equals(".") || segment.equals(".."))
                || !uri.normalize().equals(uri)
                || uri.getPort() == 0
                || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Invalid fixed upstream URI");
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return;
        }
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            final var host = uri.getHost();
            if (host.equals("[::1]") || host.equals("::1")) {
                return;
            }
            if (LOOPBACK_IPV4.matcher(host).matches()) {
                try {
                    if (InetAddress.getByName(host).isLoopbackAddress()) {
                        return;
                    }
                } catch (final IOException ignored) {
                    // Never expose URI or credential material in diagnostics.
                }
            }
        }
        throw new IllegalArgumentException("Upstream requires HTTPS or numeric loopback HTTP");
    }

    private static byte[] digest(final String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(US_ASCII));
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private void handle(final HttpExchange exchange) throws IOException {
        final var uri = exchange.getRequestURI();
        if (uri.isAbsolute()
                || uri.getRawAuthority() != null
                || !route.equals(uri.getRawPath())
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            reject(exchange, 404);
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            reject(exchange, 405);
            return;
        }
        final var auth = exchange.getRequestHeaders().get("Authorization");
        if (auth == null || auth.size() != 1 || !MessageDigest.isEqual(authorizationDigest, digest(auth.getFirst()))) {
            reject(exchange, 401);
            return;
        }
        if (closed.get() || !permits.tryAcquire()) {
            reject(exchange, 429);
            return;
        }
        final var attempt = new Attempt(exchange);
        active.add(attempt);
        try (attempt) {
            if (closed.get()) {
                return;
            }
            final var deadline = timer.schedule(attempt::close, TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            try {
                forward(exchange, attempt);
            } catch (final IOException | ExecutionException | RuntimeException failure) {
                // Never return upstream diagnostics, URIs or credentials.
                if (!attempt.done.get() && exchange.getResponseCode() == -1) {
                    reject(exchange, 502);
                }
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                deadline.cancel(false);
            }
        } finally {
            active.remove(attempt);
            permits.release();
        }
    }

    private void forward(final HttpExchange exchange, final Attempt attempt)
            throws IOException, ExecutionException, InterruptedException {
        // Read at most the limit plus one before rejection. The JDK server may already have
        // acknowledged Expect: 100-continue; closing mid-upload can hide a 413 from clients.
        final var body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            reject(exchange, 413);
            return;
        }
        if (attempt.done.get()) {
            return;
        }
        final var request = HttpRequest.newBuilder(upstream)
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + bearerSecret)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        final var acquired = budget.tryAcquire();
        if (acquired.isEmpty()) {
            reject(exchange, 429);
            return;
        }
        try (final var reservation = acquired.orElseThrow()) {
            if (attempt.done.get()) {
                return;
            }
            reservation.dispatched();
            final var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            attempt.pending.set(pending);
            if (attempt.done.get()) {
                pending.cancel(true);
                pending.thenAccept(response -> closeStream(response.body()));
                return;
            }
            final var response = pending.get();
            boolean upstreamComplete = false;
            try (final var input = response.body()) {
                attempt.input.set(input);
                if (attempt.done.get()) {
                    return;
                }
                final var contentType =
                        response.headers().firstValue("Content-Type").orElse("");
                final var mediaType = contentType.split(";", 2)[0].trim();
                exchange.getResponseHeaders()
                        .set(
                                "Content-Type",
                                "text/event-stream".equalsIgnoreCase(mediaType)
                                        ? "text/event-stream"
                                        : "application/json".equalsIgnoreCase(mediaType)
                                                ? "application/json"
                                                : "application/octet-stream");
                if (response.statusCode() == 204 || response.statusCode() == 304) {
                    upstreamComplete = input.read() == -1;
                    exchange.sendResponseHeaders(response.statusCode(), -1);
                } else {
                    exchange.sendResponseHeaders(response.statusCode(), 0);
                    final var output = exchange.getResponseBody();
                    final var buffer = new byte[16 * 1024];
                    long remaining = MAX_RESPONSE_BYTES;
                    while (!attempt.done.get()) {
                        final var count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining + 1));
                        if (count < 0) {
                            upstreamComplete = true;
                            break;
                        }
                        if (count > remaining) {
                            return;
                        }
                        output.write(buffer, 0, count);
                        output.flush();
                        remaining -= count;
                    }
                }
            }
            if (upstreamComplete && !attempt.done.get()) {
                reservation.completed();
            }
        }
    }

    private static void reject(final HttpExchange exchange, final int status) throws IOException {
        try (exchange) {
            exchange.sendResponseHeaders(status, -1);
        }
    }

    private static void closeStream(final InputStream stream) {
        try {
            stream.close();
        } catch (final IOException ignored) {
            // Cancellation must not expose upstream diagnostics.
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            server.stop(0);
            active.forEach(Attempt::close);
            timer.shutdownNow();
            client.shutdownNow();
            executor.shutdownNow();
            client.close();
        }
    }

    private static final class Attempt implements AutoCloseable {
        private final HttpExchange exchange;
        private final AtomicBoolean done = new AtomicBoolean();
        private final AtomicReference<CompletableFuture<HttpResponse<InputStream>>> pending = new AtomicReference<>();
        private final AtomicReference<InputStream> input = new AtomicReference<>();

        private Attempt(final HttpExchange exchange) {
            this.exchange = exchange;
        }

        @Override
        public void close() {
            if (done.compareAndSet(false, true)) {
                final var future = pending.get();
                if (future != null) {
                    future.cancel(true);
                    // A completed headers future cannot be cancelled but still owns a live body.
                    future.thenAccept(response -> closeStream(response.body()));
                }
                exchange.close();
                final var stream = input.get();
                if (stream != null) {
                    closeStream(stream);
                }
            }
        }
    }
}
