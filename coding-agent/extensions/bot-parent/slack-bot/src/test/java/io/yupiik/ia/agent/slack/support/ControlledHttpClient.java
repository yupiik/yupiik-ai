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
package io.yupiik.ia.agent.slack.support;

import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/** Single in-memory exchange, with explicit control at the HTTP future returned to SlackWebApi. */
public final class ControlledHttpClient extends HttpClient {
    public final CountDownLatch invoked = new CountDownLatch(1);
    public final CountDownLatch cancelled = new CountDownLatch(1);
    private final boolean cancellable;
    private CompletableFuture<?> exchange;
    private HttpResponse.BodySubscriber<?> subscriber;

    public ControlledHttpClient(final boolean cancellable) {
        this.cancellable = cancellable;
    }

    public CompletableFuture<?> exchange() {
        return exchange;
    }

    public void completeBody(final String body) {
        subscriber.onNext(List.of(ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8))));
        subscriber.onComplete();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
        final var result = new CompletableFuture<HttpResponse<T>>() {
            @Override
            public boolean cancel(final boolean interrupt) {
                final var accepted = cancellable && super.cancel(interrupt);
                cancelled.countDown();
                return accepted;
            }
        };
        final var headers = HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (key, value) -> true);
        final var body = handler.apply(new HttpResponse.ResponseInfo() {
            @Override
            public int statusCode() {
                return 200;
            }

            @Override
            public HttpHeaders headers() {
                return headers;
            }

            @Override
            public Version version() {
                return Version.HTTP_1_1;
            }
        });
        body.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(final long count) {}

            @Override
            public void cancel() {}
        });
        body.getBody().whenComplete((value, error) -> {
            if (error == null) {
                result.complete(new Response<>(request, headers, value));
            } else {
                result.completeExceptionally(error);
            }
        });
        subscriber = body;
        exchange = result;
        invoked.countDown();
        return result;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            final HttpRequest request,
            final HttpResponse.BodyHandler<T> handler,
            final HttpResponse.PushPromiseHandler<T> push) {
        throw new AssertionError("No push expected");
    }

    @Override
    public <T> HttpResponse<T> send(final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
        throw new AssertionError("No blocking HTTP expected");
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.empty();
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
        throw new AssertionError("No TLS expected");
    }

    @Override
    public SSLParameters sslParameters() {
        return new SSLParameters();
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
        return Optional.empty();
    }

    private record Response<T>(HttpRequest request, HttpHeaders headers, T body) implements HttpResponse<T> {
        @Override
        public int statusCode() {
            return 200;
        }

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
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
