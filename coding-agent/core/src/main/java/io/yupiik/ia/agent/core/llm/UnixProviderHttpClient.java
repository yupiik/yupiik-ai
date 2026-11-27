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

import io.yupiik.fusion.httpclient.core.DelegatingHttpClient;
import io.yupiik.ia.agent.core.configuration.NativeProviderConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSession;

/**
 * Routes only the reserved native provider endpoint to a launcher-supplied Unix socket.
 * No request header, URI, configuration document or body can select a socket or upstream.
 * Normal HTTP traffic still uses the delegate (and remains subject to the sandbox's network policy).
 */
public final class UnixProviderHttpClient extends DelegatingHttpClient {
    private final Path socket;
    private final ApiFlavor api;
    private final URI endpoint;
    private final Set<SocketChannel> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public UnixProviderHttpClient(final HttpClient delegate, final Path socket, final ApiFlavor api) {
        super(delegate);
        if (socket == null || !socket.isAbsolute() || !socket.normalize().equals(socket)) {
            throw new IllegalArgumentException("Absolute prepared provider socket required");
        }
        this.socket = socket;
        this.endpoint = UnixProviderProtocol.endpoint(api);
        this.api = api;
    }

    public static HttpClient fromConfiguration(
            final HttpClient delegate, final NativeProviderConfiguration configuration) {
        return configuration.socket() == null || configuration.socket().isBlank()
                ? delegate
                : new UnixProviderHttpClient(delegate, Path.of(configuration.socket()), configuration.api());
    }

    @Override
    public <T> HttpResponse<T> send(final HttpRequest request, final HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        final var result = sendAsync(request, handler);
        try {
            return result.get();
        } catch (final InterruptedException e) {
            result.cancel(true);
            throw e;
        } catch (final ExecutionException e) {
            throw new IOException("Native provider exchange failed", e.getCause());
        }
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
        if (!"yca-native.invalid".equalsIgnoreCase(request.uri().getHost())) {
            return delegate.sendAsync(request, handler);
        }
        final var future = new CompletableFuture<HttpResponse<T>>();
        if (closed || !endpoint.equals(request.uri()) || !"POST".equals(request.method())) {
            future.completeExceptionally(new IOException("Invalid native provider route"));
            return future;
        }
        final var worker = Thread.ofVirtual().unstarted(() -> exchange(request, handler, future));
        future.whenComplete((ignored, failure) -> {
            if (future.isCancelled()) {
                worker.interrupt();
            }
        });
        worker.start();
        return future;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            final HttpRequest request,
            final HttpResponse.BodyHandler<T> handler,
            final HttpResponse.PushPromiseHandler<T> push) {
        // The private protocol has no server push.
        return sendAsync(request, handler);
    }

    private <T> void exchange(
            final HttpRequest request,
            final HttpResponse.BodyHandler<T> handler,
            final CompletableFuture<HttpResponse<T>> future) {
        final var subscriber = new AtomicReference<HttpResponse.BodySubscriber<T>>();
        final var timeout = request.timeout().orElse(UnixProviderProtocol.MAX_DURATION);
        final var budget =
                timeout.compareTo(UnixProviderProtocol.MAX_DURATION) > 0 ? UnixProviderProtocol.MAX_DURATION : timeout;
        final long end = System.nanoTime() + budget.toNanos();
        try (final var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            active.add(channel);
            final var owner = Thread.currentThread();
            final var timer = UnixProviderProtocol.deadline(budget, () -> {
                UnixProviderProtocol.close(channel);
                owner.interrupt();
            });
            try {
                if (closed || future.isCancelled()) {
                    throw new IOException("Native provider transport closed");
                }
                final var bytes = requestBody(request, budget);
                channel.connect(UnixDomainSocketAddress.of(socket));
                final var out = UnixProviderProtocol.output(channel);
                final var authorization =
                        request.headers().firstValue("Authorization").orElse("");
                if (!authorization.startsWith("Bearer ") || authorization.length() > 512) {
                    throw new IOException("Native provider capability required");
                }
                out.writeInt(UnixProviderProtocol.MAGIC);
                out.writeUTF(authorization.substring(7));
                out.writeUTF(api.name());
                out.writeInt(bytes.length);
                out.write(bytes);
                final var in = UnixProviderProtocol.input(channel);
                final int status = in.readInt();
                final var type = in.readUTF();
                if (status < 100 || status > 599 || type.length() > 128) {
                    throw new IOException("Invalid native provider response");
                }
                final var headers = HttpHeaders.of(Map.of("content-type", List.of(type)), (key, value) -> true);
                final var info = new HttpResponse.ResponseInfo() {
                    public int statusCode() {
                        return status;
                    }

                    public HttpHeaders headers() {
                        return headers;
                    }

                    public HttpClient.Version version() {
                        return HttpClient.Version.HTTP_1_1;
                    }
                };
                final var body = handler.apply(info);
                subscriber.set(body);
                final var demand = new Semaphore(0);
                final var requested = new AtomicLong();
                body.onSubscribe(new Flow.Subscription() {
                    public void request(final long count) {
                        if (count <= 0) {
                            UnixProviderProtocol.close(channel);
                        } else {
                            requested.getAndUpdate(
                                    previous -> previous > Long.MAX_VALUE - count ? Long.MAX_VALUE : previous + count);
                        }
                        demand.release();
                    }

                    public void cancel() {
                        UnixProviderProtocol.close(channel);
                        demand.release();
                    }
                });
                body.getBody().whenComplete((value, error) -> {
                    if (error != null) {
                        future.completeExceptionally(error);
                        UnixProviderProtocol.close(channel);
                    } else {
                        future.complete(new Response<>(request, status, headers, value));
                    }
                });
                long received = 0;
                while (true) {
                    final int length = in.readInt();
                    if (length == 0) {
                        body.onComplete();
                        break;
                    }
                    received += length;
                    if (length < 0
                            || length > UnixProviderProtocol.FRAME_SIZE
                            || received > UnixProviderProtocol.MAX_RESPONSE) {
                        throw new IOException("Native provider response limit");
                    }
                    final var frame = new byte[length];
                    in.readFully(frame);
                    while (requested.get() == 0 && channel.isOpen()) {
                        if (!demand.tryAcquire(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                            throw new IOException("Native provider subscriber deadline");
                        }
                    }
                    if (!channel.isOpen()) {
                        throw new IOException("Native provider response cancelled");
                    }
                    requested.getAndUpdate(previous -> previous == Long.MAX_VALUE ? previous : previous - 1);
                    body.onNext(List.of(ByteBuffer.wrap(frame)));
                }
            } finally {
                timer.cancel(false);
                active.remove(channel);
            }
        } catch (final Exception e) {
            final var body = subscriber.get();
            if (body != null) {
                body.onError(e);
            }
            future.completeExceptionally(new IOException("Native provider exchange failed", e));
        }
    }

    private byte[] requestBody(final HttpRequest request, final Duration timeout) throws Exception {
        final var result = new CompletableFuture<byte[]>();
        final var subscription = new AtomicReference<Flow.Subscription>();
        request.bodyPublisher()
                .orElseThrow(() -> new IOException("Provider body required"))
                .subscribe(new Flow.Subscriber<>() {
                    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

                    public void onSubscribe(final Flow.Subscription value) {
                        subscription.set(value);
                        value.request(1);
                    }

                    public void onNext(final ByteBuffer value) {
                        if (value.remaining() > UnixProviderProtocol.MAX_REQUEST - bytes.size()) {
                            subscription.get().cancel();
                            result.completeExceptionally(new IOException("Native provider request limit"));
                            return;
                        }
                        final var chunk = new byte[value.remaining()];
                        value.get(chunk);
                        bytes.writeBytes(chunk);
                        subscription.get().request(1);
                    }

                    public void onError(final Throwable error) {
                        result.completeExceptionally(error);
                    }

                    public void onComplete() {
                        result.complete(bytes.toByteArray());
                    }
                });
        try {
            return result.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } finally {
            final var value = subscription.get();
            if (value != null) {
                value.cancel();
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        active.forEach(UnixProviderProtocol::close);
        delegate.close();
    }

    private record Response<T>(HttpRequest request, int statusCode, HttpHeaders headers, T body)
            implements HttpResponse<T> {
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        public URI uri() {
            return request.uri();
        }

        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
