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
package io.yupiik.llm.client.opencode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/**
 * HTTP/1.1 over the module's dedicated TLS 1.3 implementation. Does not follow redirects,
 * share cookies, or weaken certificate validation. Each exchange owns one connection.
 * Streaming bodies use the standard BodySubscriber contract, including backpressure.
 */
public final class OpenCodeHttpClient extends HttpClient {
    private static final int MAX_REQUEST = 16 * 1024 * 1024;
    private final Duration connectTimeout;
    private final KeyStore trustStore;
    private final List<String> suites;
    private final ProxySelector proxySelector;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("opencode-deadlines").factory());
    private final Set<Exchange<?>> exchanges = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public OpenCodeHttpClient() {
        this(Duration.ofSeconds(30), null, null, null);
    }

    public OpenCodeHttpClient(
            final Duration connectTimeout,
            final KeyStore trustStore,
            final List<String> suites,
            final ProxySelector proxySelector) {
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("Positive connect timeout required");
        }
        this.connectTimeout = connectTimeout;
        this.trustStore = trustStore;
        this.suites = suites == null ? OpenCodeCipherProfile.DEFAULT : List.copyOf(suites);
        if (this.suites.isEmpty()
                || this.suites.stream().distinct().count() != this.suites.size()
                || !OpenCodeCipherProfile.DEFAULT.containsAll(this.suites)
                || this.suites.stream().noneMatch(s -> OpenCodeCipherProfile.DEFAULT.indexOf(s) < 3)) {
            throw new IllegalArgumentException("Unsupported OpenCode cipher profile: " + this.suites + " (expected: "
                    + OpenCodeCipherProfile.DEFAULT + ")");
        }
        this.proxySelector = proxySelector;
    }

    @Override
    public <T> HttpResponse<T> send(final HttpRequest request, final HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        final var future = sendAsync(request, handler);
        try {
            return future.get();
        } catch (final InterruptedException error) {
            future.cancel(true);
            throw error;
        } catch (final ExecutionException error) {
            final var cause = error.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException(cause);
        }
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
        return sendAsync(request, handler, null);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            final HttpRequest request,
            final HttpResponse.BodyHandler<T> handler,
            final HttpResponse.PushPromiseHandler<T> push) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(handler, "handler");
        if (push != null) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("HTTP/2 push is not supported"));
        }
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IOException("OpenCode HTTP client is closed"));
        }
        final var exchange = new Exchange<T>(request, handler);
        exchanges.add(exchange);
        if (closed.get()) {
            exchange.abort(new IOException("OpenCode HTTP client is closed"));
        }
        try {
            final var timeout = request.timeout().orElse(Duration.ofMinutes(2));
            exchange.deadline = deadlines.schedule(
                    () -> exchange.abort(new HttpTimeoutException("OpenCode request deadline exceeded")),
                    timeout.toNanos(),
                    TimeUnit.NANOSECONDS);
            executor.execute(exchange::run);
        } catch (final RuntimeException error) {
            exchange.abort(new IOException("Unable to start OpenCode request", error));
            exchange.finish();
        }
        exchange.result.whenComplete((value, error) -> {
            if (exchange.result.isCancelled()) {
                exchange.abort(new IOException("OpenCode request cancelled"));
            }
        });
        return exchange.result;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (final var exchange : exchanges) {
                exchange.abort(new IOException("OpenCode HTTP client is closed"));
            }
            executor.shutdownNow();
            deadlines.shutdownNow();
        }
    }

    @Override
    public void shutdown() {
        close();
    }

    @Override
    public void shutdownNow() {
        close();
    }

    @Override
    public boolean isTerminated() {
        return executor.isTerminated() && deadlines.isTerminated();
    }

    @Override
    public boolean awaitTermination(final Duration duration) throws InterruptedException {
        return executor.awaitTermination(duration.toNanos(), TimeUnit.NANOSECONDS) && deadlines.isTerminated();
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.of(connectTimeout);
    }

    @Override
    public Redirect followRedirects() {
        return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.ofNullable(proxySelector);
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        try {
            return SSLContext.getDefault(); // API metadata only; never used for network TLS.
        } catch (final NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    @Override
    public SSLParameters sslParameters() {
        return new SSLParameters(suites.toArray(String[]::new), new String[] {"TLSv1.3"});
    }

    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.of(executor);
    }

    private final class Exchange<T> implements Flow.Subscription {
        private final HttpRequest request;
        private final HttpResponse.BodyHandler<T> handler;
        private final CompletableFuture<HttpResponse<T>> result = new CompletableFuture<>();
        private final CompletableFuture<byte[]> payload = new CompletableFuture<>();
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();
        private final Socket socket = new Socket();
        private volatile IOException failure;
        private volatile Flow.Subscription upload;
        private volatile ScheduledFuture<?> deadline;
        private long demand;
        private boolean done;
        private boolean cancelled;

        private Exchange(final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
            this.request = request;
            this.handler = handler;
        }

        private void abort(final IOException error) {
            lock.lock();
            try {
                if (done || failure != null) {
                    return;
                }
                failure = error;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
            payload.completeExceptionally(error);
            final var subscription = upload;
            if (subscription != null) {
                subscription.cancel();
            }
            result.completeExceptionally(error);
            closeSocket();
        }

        private void closeSocket() {
            try {
                socket.close();
            } catch (final IOException ignored) {
                // The exchange is already terminal.
            }
        }

        private void finish() {
            lock.lock();
            try {
                done = true;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
            final var timer = deadline;
            if (timer != null) {
                timer.cancel(false);
            }
            closeSocket();
            exchanges.remove(this);
        }

        private void check() throws IOException {
            if (failure != null) {
                throw failure;
            }
        }

        private void run() {
            HttpResponse.BodySubscriber<T> subscriber = null;
            try {
                check();
                final var uri = request.uri();
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                    throw new IOException("OpenCode transport requires an HTTPS URL without user information");
                }
                if (proxySelector != null) {
                    final var proxies = proxySelector.select(uri);
                    if (proxies != null
                            && !proxies.isEmpty()
                            && proxies.getFirst().type() != Proxy.Type.DIRECT) {
                        throw new IOException("OpenCode transport does not support proxy tunnelling");
                    }
                }
                collectPayload();
                final var bytes = payload.get();
                check();
                socket.connect(
                        new InetSocketAddress(uri.getHost(), uri.getPort() < 0 ? 443 : uri.getPort()),
                        Math.clamp(connectTimeout.toMillis(), 1, Integer.MAX_VALUE));
                try (final var tls = OpenCodeTlsConnection.connect(socket, uri.getHost(), trustStore, suites)) {
                    check();
                    writeRequest(tls.output(), request, bytes);
                    final var parsed = OpenCodeHttpResponse.read(tls.input(), request.method());
                    subscriber = handler.apply(parsed.info());
                    final var bodySubscriber = subscriber;
                    subscriber.onSubscribe(this);
                    subscriber.getBody().whenComplete((body, error) -> {
                        if (error == null) {
                            result.complete(new Response<>(
                                    request,
                                    parsed.info().statusCode(),
                                    parsed.info().headers(),
                                    body));
                        } else {
                            final var cause = error instanceof CompletionException && error.getCause() != null
                                    ? error.getCause()
                                    : error;
                            result.completeExceptionally(cause);
                            abort(new IOException("Body subscriber failed", cause));
                        }
                    });
                    while (true) {
                        lock.lockInterruptibly();
                        try {
                            while (demand == 0 && failure == null && !cancelled) {
                                changed.await();
                            }
                            check();
                            if (cancelled) {
                                return;
                            }
                            if (demand != Long.MAX_VALUE) {
                                demand--;
                            }
                        } finally {
                            lock.unlock();
                        }
                        final var buffer = new byte[8192];
                        final int count = parsed.body().read(buffer);
                        check();
                        if (count < 0) {
                            lock.lock();
                            try {
                                if (cancelled) {
                                    return;
                                }
                            } finally {
                                lock.unlock();
                            }
                            bodySubscriber.onComplete();
                            return;
                        }
                        bodySubscriber.onNext(
                                List.of(ByteBuffer.wrap(buffer, 0, count).asReadOnlyBuffer()));
                    }
                }
            } catch (final Exception error) {
                final var cause = failure != null
                        ? failure
                        : error instanceof ExecutionException && error.getCause() != null ? error.getCause() : error;
                if (error instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                result.completeExceptionally(cause);
                if (subscriber != null && !cancelled) {
                    subscriber.onError(cause);
                }
            } finally {
                finish();
            }
        }

        private void collectPayload() {
            if (request.bodyPublisher().isEmpty()) {
                payload.complete(new byte[0]);
                return;
            }
            request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
                private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

                @Override
                public void onSubscribe(final Flow.Subscription subscription) {
                    upload = subscription;
                    if (failure == null) {
                        subscription.request(Long.MAX_VALUE);
                    } else {
                        subscription.cancel();
                    }
                }

                @Override
                public void onNext(final ByteBuffer item) {
                    if (bytes.size() + (long) item.remaining() > MAX_REQUEST) {
                        abort(new IOException("OpenCode request body exceeds 16 MiB"));
                        return;
                    }
                    final var data = new byte[item.remaining()];
                    item.get(data);
                    bytes.writeBytes(data);
                }

                @Override
                public void onError(final Throwable error) {
                    payload.completeExceptionally(error);
                }

                @Override
                public void onComplete() {
                    payload.complete(bytes.toByteArray());
                }
            });
        }

        @Override
        public void request(final long count) {
            if (count <= 0) {
                abort(new IOException("Non-positive body demand"));
                return;
            }
            lock.lock();
            try {
                demand = demand > Long.MAX_VALUE - count ? Long.MAX_VALUE : demand + count;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void cancel() {
            lock.lock();
            try {
                cancelled = true;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
            abort(new IOException("Response body cancelled"));
        }
    }

    private static void writeRequest(final OutputStream output, final HttpRequest request, final byte[] payload)
            throws IOException {
        final var uri = request.uri();
        final var path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        final var target = path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        final var headers = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(request.headers().map());
        if (headers.containsKey("transfer-encoding") || headers.containsKey("expect")) {
            throw new IOException("Request transfer coding and Expect are not supported");
        }
        final var head =
                new StringBuilder(request.method()).append(' ').append(target).append(" HTTP/1.1\r\n");
        final var generated = Set.of("host", "content-length", "connection", "accept", "accept-encoding");
        // HttpRequest does not preserve insertion order. Reproduce the observed
        // OpenCode/Bun application-header order explicitly instead of sorting it.
        final var applicationOrder = List.of(
                "authorization",
                "content-type",
                "user-agent",
                "x-opencode-client",
                "x-opencode-project",
                "x-opencode-session",
                "x-opencode-session-id",
                "x-session-affinity",
                "x-session-id");
        if (!headers.containsKey("user-agent")) {
            headers.put("user-agent", List.of("yca/opencode-free"));
        }
        headers.keySet().stream()
                .sorted(Comparator.<String>comparingInt(name -> {
                            final var index = applicationOrder.indexOf(name.toLowerCase(Locale.ROOT));
                            return index < 0 ? applicationOrder.size() : index;
                        })
                        .thenComparing(String.CASE_INSENSITIVE_ORDER))
                .forEach(name -> {
                    final var values = headers.get(name);
                    final var lower = name.toLowerCase(Locale.ROOT);
                    if (!generated.contains(lower)) {
                        final var canonical =
                                switch (lower) {
                                    case "authorization" -> "Authorization";
                                    case "content-type" -> "Content-Type";
                                    case "user-agent" -> "User-Agent";
                                    default -> lower;
                                };
                        head.append(canonical)
                                .append(": ")
                                .append(String.join(", ", values))
                                .append("\r\n");
                    }
                });
        head.append("Connection: keep-alive\r\nAccept: ")
                .append(String.join(", ", headers.getOrDefault("accept", List.of("*/*"))))
                .append("\r\nHost: ")
                .append(uri.getHost())
                .append(uri.getPort() < 0 || uri.getPort() == 443 ? "" : ":" + uri.getPort())
                .append("\r\nAccept-Encoding: gzip, deflate\r\n");
        if (request.bodyPublisher().isPresent()) {
            head.append("Content-Length: ").append(payload.length).append("\r\n");
        }
        head.append("\r\n");
        output.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.write(payload);
        output.flush();
    }

    private record Response<T>(HttpRequest request, int statusCode, HttpHeaders headers, T body)
            implements HttpResponse<T> {
        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }
    }
}
