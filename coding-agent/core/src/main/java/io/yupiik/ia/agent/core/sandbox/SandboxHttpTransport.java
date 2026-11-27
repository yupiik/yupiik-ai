/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.sandbox;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

/**
 * Session-bound HTTP/1.1 transport. No proxies, pooling, cookies or ambient HTTP client are used.
 * Every hop is authorized, all DNS answers must be public, and connect uses only a validated address.
 * TLS still authenticates the URL hostname (including SNI), not the numeric socket destination.
 */
public class SandboxHttpTransport extends HttpClient {
    private static final int MAX_HEADERS = 65_536;
    private static final Set<String> OWN_HEADERS = Set.of(
            "host", "connection", "content-length", "transfer-encoding", "expect", "upgrade", "proxy-authorization");
    private final Sandbox sandbox;
    private final BooleanSupplier cancelled;
    private final int maxBytes;
    private final SSLContext ssl;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    private final Set<Exchange> exchanges = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<SecurityException> denial = new AtomicReference<>();

    public SandboxHttpTransport(final Sandbox sandbox, final BooleanSupplier cancelled, final int maxBytes) {
        this.sandbox = java.util.Objects.requireNonNull(sandbox);
        this.cancelled = java.util.Objects.requireNonNull(cancelled);
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
        try {
            ssl = SSLContext.getDefault();
        } catch (final java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
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
            if (error.getCause() instanceof SecurityException denied) {
                throw denied;
            }
            if (error.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException("Sandbox HTTP request failed", error.getCause());
        }
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
        final var result = new CompletableFuture<HttpResponse<T>>();
        final var worker = new AtomicReference<Thread>();
        final var exchange = new Exchange();
        exchanges.add(exchange);
        final var timeout = request.timeout().orElse(Duration.ofSeconds(30));
        final var deadline = System.nanoTime() + timeout.toNanos();
        if (closed.get()) {
            exchanges.remove(exchange);
            return CompletableFuture.failedFuture(new IOException("Sandbox HTTP transport is closed"));
        }
        final var watchdog = deadlines.scheduleAtFixedRate(
                () -> {
                    if (cancelled.getAsBoolean() || System.nanoTime() >= deadline || result.isCancelled()) {
                        result.completeExceptionally(new IOException("Sandbox HTTP request cancelled or timed out"));
                        exchange.close();
                    }
                },
                0,
                50,
                TimeUnit.MILLISECONDS);
        result.whenComplete((value, error) -> {
            if (error != null) {
                exchange.close();
                final var thread = worker.get();
                if (thread != null && thread != Thread.currentThread()) {
                    thread.interrupt();
                }
            }
        });
        workers.execute(() -> {
            worker.set(Thread.currentThread());
            HttpResponse.BodySubscriber<T> subscriber = null;
            try {
                if (result.isCompletedExceptionally()) {
                    return;
                }
                check(exchange);
                final var body = request.bodyPublisher().isPresent()
                        ? requestBody(request.bodyPublisher().orElseThrow())
                        : new byte[0];
                var uri = request.uri();
                var method = request.method();
                var payload = body;
                var forwardHeaders = true;
                for (var redirects = 0; ; redirects++) {
                    check(exchange);
                    final var host = SandboxDestinations.host(uri);
                    final var port = SandboxDestinations.port(uri);
                    sandbox.requireNetwork(uri);
                    // requireNetwork owns policy/approvals. This resolution is independently validated
                    // and is the only answer used by connect, even if policy resolution returned earlier.
                    final var addresses = resolve(host);
                    if (addresses.length == 0) {
                        throw new IOException("No public destination address");
                    }
                    for (final var address : addresses) {
                        if (!SandboxDestinations.isPublic(address)) {
                            throw new SecurityException("Sandbox rejects non-public destination: " + host);
                        }
                    }
                    check(exchange);
                    final var socket = newSocket();
                    exchange.install(socket);
                    socket.connect(new InetSocketAddress(addresses[0], port), timeoutMillis(timeout));
                    socket.setSoTimeout(timeoutMillis(timeout));
                    Socket connection = socket;
                    if ("https".equalsIgnoreCase(uri.getScheme())) {
                        final var tls = (SSLSocket) ssl.getSocketFactory().createSocket(socket, host, port, true);
                        exchange.install(tls);
                        final var parameters = tls.getSSLParameters();
                        parameters.setEndpointIdentificationAlgorithm("HTTPS");
                        tls.setSSLParameters(parameters);
                        tls.startHandshake();
                        connection = tls;
                    }
                    check(exchange);
                    writeRequest(connection, request, uri, method, payload, forwardHeaders);
                    final var input = new BufferedInputStream(connection.getInputStream());
                    var head = readHead(input);
                    var interim = 0;
                    while (head.status() >= 100 && head.status() < 200) {
                        if (head.status() == 101 || ++interim > 8) {
                            throw new IOException("Unsupported HTTP upgrade or too many interim responses");
                        }
                        head = readHead(input);
                    }
                    if (Set.of(301, 302, 303, 307, 308).contains(head.status())
                            && head.headers().firstValue("location").isPresent()) {
                        if (redirects >= 5) {
                            throw new IOException("Too many sandbox HTTP redirects");
                        }
                        final var next = uri.resolve(
                                head.headers().firstValue("location").orElseThrow());
                        SandboxDestinations.host(next);
                        if ("https".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(next.getScheme())) {
                            throw new SecurityException("Sandbox rejects HTTPS downgrade redirects");
                        }
                        // Never replay credentials, MCP session headers or POST bodies to another origin.
                        if (!sameOrigin(uri, next)) {
                            if (payload.length > 0) {
                                throw new SecurityException("Sandbox rejects cross-origin redirects with request body");
                            }
                            forwardHeaders = false;
                        }
                        if (head.status() == 303
                                || ((head.status() == 301 || head.status() == 302) && "POST".equals(method))) {
                            method = "GET";
                            payload = new byte[0];
                        }
                        exchange.disconnect();
                        uri = next;
                        continue;
                    }
                    final var responseHead = head;
                    subscriber = handler.apply(new HttpResponse.ResponseInfo() {
                        @Override
                        public int statusCode() {
                            return responseHead.status();
                        }

                        @Override
                        public HttpHeaders headers() {
                            return responseHead.headers();
                        }

                        @Override
                        public Version version() {
                            return Version.HTTP_1_1;
                        }
                    });
                    final var responseUri = uri;
                    final var session = connection instanceof SSLSocket tls
                            ? Optional.of(tls.getSession())
                            : Optional.<SSLSession>empty();
                    subscriber.getBody().whenComplete((value, error) -> {
                        if (error == null) {
                            result.complete(new Response<>(
                                    request,
                                    responseUri,
                                    responseHead.status(),
                                    responseHead.headers(),
                                    value,
                                    session));
                        } else {
                            result.completeExceptionally(error);
                            exchange.close();
                        }
                    });
                    subscriber.onSubscribe(new Flow.Subscription() {
                        @Override
                        public void request(final long n) {
                            if (n <= 0) {
                                exchange.close();
                            } else {
                                exchange.demand.release((int) Math.min(n, 1_000_000));
                            }
                        }

                        @Override
                        public void cancel() {
                            exchange.close();
                        }
                    });
                    pump(input, head, method, subscriber, exchange);
                    exchange.disconnect();
                    subscriber.onComplete();
                    break;
                }
            } catch (final Throwable error) {
                if (error instanceof SecurityException rejected) {
                    denial.set(rejected);
                }
                exchange.close();
                if (subscriber != null) {
                    subscriber.onError(error);
                }
                result.completeExceptionally(error);
            } finally {
                worker.set(null);
                exchange.close();
                exchanges.remove(exchange);
                watchdog.cancel(false);
            }
        });
        return result;
    }

    /** Preserves policy diagnostics when an integration deliberately redacts transport exceptions. */
    public void throwIfDenied() {
        final var rejected = denial.get();
        if (rejected != null) {
            throw rejected;
        }
    }

    /** Resolution hook for alternate host resolvers; the transport always validates every answer. */
    protected InetAddress[] resolve(final String host) throws IOException {
        return InetAddress.getAllByName(host);
    }

    /** Socket creation hook; destination selection, validation and connect remain transport-owned. */
    protected Socket newSocket() {
        return new Socket(Proxy.NO_PROXY);
    }

    private byte[] requestBody(final HttpRequest.BodyPublisher publisher) throws IOException, InterruptedException {
        final var content = new ByteArrayOutputStream();
        final var result = new CompletableFuture<byte[]>();
        publisher.subscribe(new Flow.Subscriber<ByteBuffer>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(final Flow.Subscription value) {
                subscription = value;
                value.request(1);
            }

            @Override
            public void onNext(final ByteBuffer value) {
                if (value.remaining() > maxBytes - content.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("Sandbox HTTP request body too large"));
                    return;
                }
                final var bytes = new byte[value.remaining()];
                value.get(bytes);
                content.writeBytes(bytes);
                subscription.request(1);
            }

            @Override
            public void onError(final Throwable error) {
                result.completeExceptionally(error);
            }

            @Override
            public void onComplete() {
                result.complete(content.toByteArray());
            }
        });
        try {
            return result.get();
        } catch (final ExecutionException error) {
            throw new IOException("Cannot read HTTP request body", error.getCause());
        }
    }

    private void writeRequest(
            final Socket socket,
            final HttpRequest request,
            final URI uri,
            final String method,
            final byte[] body,
            final boolean forwardHeaders)
            throws IOException {
        if (!Set.of("GET", "POST", "DELETE", "HEAD").contains(method)) {
            throw new IOException("Unsupported sandbox HTTP method");
        }
        final var path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        final var target = path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        if (target.chars().anyMatch(c -> c <= 32 || c >= 127)) {
            throw new IOException("HTTP request target must be ASCII encoded");
        }
        final var out = new StringBuilder(method)
                .append(' ')
                .append(target)
                .append(" HTTP/1.1\r\nHost: ")
                .append(SandboxDestinations.host(uri))
                .append(':')
                .append(SandboxDestinations.port(uri))
                .append("\r\nConnection: close\r\n");
        request.headers().map().forEach((name, values) -> {
            if (!forwardHeaders) {
                return;
            }
            if (OWN_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                throw new SecurityException("Sandbox HTTP transport owns header: " + name);
            }
            for (final var value : values) {
                if (value.chars().anyMatch(c -> c < 32 || c >= 127)) {
                    throw new SecurityException("Invalid sandbox HTTP header");
                }
                out.append(name).append(": ").append(value).append("\r\n");
            }
        });
        out.append("Content-Length: ").append(body.length).append("\r\n\r\n");
        socket.getOutputStream().write(out.toString().getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(body);
        socket.getOutputStream().flush();
    }

    private Head readHead(final InputStream input) throws IOException {
        final var budget = new int[] {MAX_HEADERS};
        final var status = line(input, budget).split(" ", 3);
        if (status.length < 2
                || !("HTTP/1.1".equals(status[0]) || "HTTP/1.0".equals(status[0]))
                || !status[1].matches("[1-5][0-9][0-9]")) {
            throw new IOException("Invalid HTTP response status");
        }
        final var headers = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
        String line;
        while (!(line = line(input, budget)).isEmpty()) {
            final var colon = line.indexOf(':');
            if (colon < 1 || Character.isWhitespace(line.charAt(0))) {
                throw new IOException("Invalid HTTP response header");
            }
            headers.computeIfAbsent(line.substring(0, colon), key -> new ArrayList<>())
                    .add(line.substring(colon + 1).strip());
        }
        return new Head(Integer.parseInt(status[1]), HttpHeaders.of(headers, (key, value) -> true));
    }

    private void pump(
            final InputStream input,
            final Head head,
            final String method,
            final HttpResponse.BodySubscriber<?> subscriber,
            final Exchange exchange)
            throws IOException {
        if ("HEAD".equals(method) || head.status() == 204 || head.status() == 304) {
            return;
        }
        final var transfer = head.headers().allValues("transfer-encoding");
        final var lengths = head.headers().allValues("content-length");
        if (!transfer.isEmpty() && !lengths.isEmpty()) {
            throw new IOException("Ambiguous HTTP response framing");
        }
        var total = 0L;
        if (!transfer.isEmpty()) {
            if (transfer.size() != 1 || !"chunked".equalsIgnoreCase(transfer.getFirst())) {
                throw new IOException("Unsupported HTTP transfer encoding");
            }
            final var budget = new int[] {MAX_HEADERS};
            while (true) {
                final var size = line(input, budget).split(";", 2)[0];
                final long count;
                try {
                    count = Long.parseLong(size, 16);
                } catch (final NumberFormatException error) {
                    throw new IOException("Invalid HTTP chunk size", error);
                }
                if (count < 0 || count > maxBytes - total) {
                    throw new IOException("Sandbox HTTP response body exceeds limit of " + maxBytes + " bytes");
                }
                if (count == 0) {
                    while (!line(input, budget).isEmpty()) {
                        /* bounded trailers */
                    }
                    return;
                }
                copy(input, count, subscriber, exchange);
                total += count;
                if (!line(input, budget).isEmpty()) {
                    throw new IOException("Invalid HTTP chunk terminator");
                }
            }
        }
        if (!lengths.isEmpty()) {
            final long count;
            try {
                if (lengths.size() != 1) {
                    throw new NumberFormatException("Duplicate length");
                }
                count = Long.parseLong(lengths.getFirst());
            } catch (final NumberFormatException error) {
                throw new IOException("Invalid HTTP content length", error);
            }
            if (count < 0 || count > maxBytes) {
                throw new IOException("Sandbox HTTP response body exceeds limit of " + maxBytes + " bytes");
            }
            copy(input, count, subscriber, exchange);
            return;
        }
        final var buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            check(exchange);
            total += read;
            if (total > maxBytes) {
                throw new IOException("Sandbox HTTP response body exceeds limit of " + maxBytes + " bytes");
            }
            emit(subscriber, exchange, buffer, read);
        }
    }

    private void copy(
            final InputStream input,
            final long size,
            final HttpResponse.BodySubscriber<?> subscriber,
            final Exchange exchange)
            throws IOException {
        var remaining = size;
        final var buffer = new byte[8192];
        while (remaining > 0) {
            check(exchange);
            final var read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new EOFException("Incomplete HTTP response body");
            }
            emit(subscriber, exchange, buffer, read);
            remaining -= read;
        }
    }

    private void emit(
            final HttpResponse.BodySubscriber<?> subscriber,
            final Exchange exchange,
            final byte[] buffer,
            final int count)
            throws IOException {
        try {
            exchange.demand.acquire();
        } catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Sandbox HTTP response interrupted", error);
        }
        check(exchange);
        subscriber.onNext(List.of(ByteBuffer.wrap(java.util.Arrays.copyOf(buffer, count))));
    }

    private static String line(final InputStream input, final int[] budget) throws IOException {
        final var out = new ByteArrayOutputStream();
        while (--budget[0] >= 0) {
            final var value = input.read();
            if (value < 0) {
                throw new EOFException("Incomplete HTTP headers");
            }
            if (value == '\r') {
                if (--budget[0] < 0 || input.read() != '\n') {
                    throw new IOException("Invalid HTTP line ending");
                }
                return out.toString(StandardCharsets.ISO_8859_1);
            }
            if (value == '\n') {
                throw new IOException("Invalid HTTP line ending");
            }
            out.write(value);
        }
        throw new IOException("HTTP headers too large");
    }

    private void check(final Exchange exchange) throws IOException {
        if (closed.get()
                || exchange.stopped.get()
                || cancelled.getAsBoolean()
                || Thread.currentThread().isInterrupted()) {
            throw new IOException("Sandbox HTTP request cancelled");
        }
    }

    private static boolean sameOrigin(final URI left, final URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && SandboxDestinations.host(left).equals(SandboxDestinations.host(right))
                && SandboxDestinations.port(left) == SandboxDestinations.port(right);
    }

    private static int timeoutMillis(final Duration duration) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, duration.toMillis()));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            final HttpRequest request,
            final HttpResponse.BodyHandler<T> handler,
            final HttpResponse.PushPromiseHandler<T> push) {
        return sendAsync(request, handler);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            exchanges.forEach(Exchange::close);
            deadlines.shutdownNow();
            workers.shutdownNow();
        }
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.of(Duration.ofSeconds(30));
    }

    @Override
    public Redirect followRedirects() {
        return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        return ssl;
    }

    @Override
    public SSLParameters sslParameters() {
        return ssl.getDefaultSSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.of(workers);
    }

    private record Head(int status, HttpHeaders headers) {}

    private record Response<T>(
            HttpRequest request, URI uri, int statusCode, HttpHeaders headers, T body, Optional<SSLSession> sslSession)
            implements HttpResponse<T> {
        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }
    }

    private static final class Exchange implements AutoCloseable {
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final Semaphore demand = new Semaphore(0);
        private volatile Socket socket;

        private void install(final Socket next) throws IOException {
            socket = next;
            if (stopped.get()) {
                next.close();
                throw new IOException("Sandbox HTTP request cancelled");
            }
        }

        private void disconnect() throws IOException {
            if (socket != null) {
                socket.close();
            }
        }

        @Override
        public void close() {
            if (stopped.compareAndSet(false, true)) {
                demand.release();
            }
            try {
                disconnect();
            } catch (final IOException ignored) {
                // Best effort during cancellation/transport cleanup.
            }
        }
    }
}
