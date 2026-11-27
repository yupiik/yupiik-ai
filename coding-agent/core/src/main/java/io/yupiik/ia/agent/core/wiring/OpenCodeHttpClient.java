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
package io.yupiik.ia.agent.core.wiring;

import io.yupiik.fusion.httpclient.core.DelegatingHttpClient;
import io.yupiik.llm.client.opencode.OpenCodeCipherProfile;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** Selects an isolated transport below the shared request listener boundary. Owns all delegates. */
public final class OpenCodeHttpClient extends DelegatingHttpClient {
    private static final String MARKER = "x-yca-internal-opencode-transport";
    private final Map<List<String>, String> profiles = new HashMap<>();
    private final Map<String, HttpClient> clients = new ConcurrentHashMap<>();
    private final Lock lock = new ReentrantLock();
    private boolean closed;
    private final KeyStore trustStore;

    public OpenCodeHttpClient(final HttpClient delegate) {
        this(delegate, null);
    }

    public OpenCodeHttpClient(final HttpClient delegate, final KeyStore trustStore) {
        super(delegate);
        this.trustStore = trustStore;
    }

    /** Registers a profile and returns a request-local selector, never forwarded to the network. */
    public Consumer<HttpRequest.Builder> profile(final List<String> configured) {
        final var suites = configured == null ? OpenCodeCipherProfile.DEFAULT : List.copyOf(configured);
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("HTTP transport is closed");
            }
            final var key = profiles.computeIfAbsent(suites, profile -> {
                if (profile.isEmpty() || profile.stream().distinct().count() != profile.size()) {
                    throw new IllegalArgumentException("OpenCode cipher suites must be nonempty and unique");
                }
                final var id = UUID.randomUUID().toString();
                clients.put(
                        id,
                        new io.yupiik.llm.client.opencode.OpenCodeHttpClient(
                                delegate.connectTimeout().orElse(Duration.ofSeconds(30)),
                                trustStore,
                                profile,
                                delegate.proxy().orElse(null)));
                return id;
            });
            return builder -> builder.setHeader(MARKER, key);
        } finally {
            lock.unlock();
        }
    }

    private HttpClient selected(final HttpRequest request) {
        final var id = request.headers().firstValue(MARKER);
        if (id.isPresent() && !"https".equalsIgnoreCase(request.uri().getScheme())) {
            throw new IllegalArgumentException("OpenCode transport requires HTTPS");
        }
        return id.isEmpty()
                ? delegate
                : Optional.ofNullable(clients.get(id.get()))
                        .orElseThrow(() -> new IllegalArgumentException("Unknown OpenCode transport"));
    }

    private HttpRequest clean(final HttpRequest request) {
        return request.headers().firstValue(MARKER).isEmpty()
                ? request
                : HttpRequest.newBuilder(request, (name, value) -> !MARKER.equalsIgnoreCase(name))
                        .build();
    }

    @Override
    public <T> HttpResponse<T> send(final HttpRequest request, final HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        return selected(request).send(clean(request), handler);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
        return selected(request).sendAsync(clean(request), handler);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            final HttpRequest request,
            final HttpResponse.BodyHandler<T> handler,
            final HttpResponse.PushPromiseHandler<T> push) {
        return selected(request).sendAsync(clean(request), handler, push);
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            try {
                clients.values().forEach(HttpClient::close);
            } finally {
                delegate.close();
            }
        } finally {
            lock.unlock();
        }
    }
}
