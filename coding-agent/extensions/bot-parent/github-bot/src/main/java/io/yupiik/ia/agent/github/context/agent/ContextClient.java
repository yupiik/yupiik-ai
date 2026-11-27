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
package io.yupiik.ia.agent.github.context.agent;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.github.configuration.AgentContextConfiguration;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** No redirects, ambient proxies, remote diagnostics or unbounded body buffering. */
public final class ContextClient {
    private static final Pattern CONTEXT_RPC_PATH =
            Pattern.compile("/context/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/rpc");

    private static final int MAX_RESPONSE = 512 * 1024;
    private final URI endpoint;
    private final JsonMapper json;
    private final Supplier<String> token;

    public ContextClient(
            final AgentContextConfiguration configuration, final JsonMapper json, final Configuration config) {
        this(
                validate(URI.create(configuration.endpoint()), false),
                json,
                () -> configuration.tokenEnv() == null
                        ? null
                        : config.get(configuration.tokenEnv()).orElse(null));
        if (!configuration.enabled()) {
            throw new IllegalArgumentException("Context capability is disabled.");
        }
    }

    /** Explicit transport injection for local protocol tests; HTTP is restricted to loopback hosts. */
    public ContextClient(final URI endpoint, final JsonMapper json, final Supplier<String> token) {
        this.endpoint = validate(endpoint, true);
        this.json = json;
        this.token = token;
    }

    private static URI validate(final URI uri, final boolean loopback) {
        final boolean local = loopback
                && "http".equals(uri.getScheme())
                && uri.getHost() != null
                && List.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
        if ((!"https".equals(uri.getScheme()) && !local)
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getRawPath() == null
                || !CONTEXT_RPC_PATH.matcher(uri.getRawPath()).matches()
                || uri.getPort() == 0
                || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Invalid context endpoint.");
        }
        return uri;
    }

    public Map<String, Object> call(final Map<String, Object> request) throws IOException {
        return call(request, () -> false);
    }

    public Map<String, Object> call(final Map<String, Object> request, final BooleanSupplier cancelled)
            throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new IOException("Context request cancelled.");
        }
        try {
            final var secret = token.get();
            if (secret == null || secret.isBlank() || secret.indexOf('\r') >= 0 || secret.indexOf('\n') >= 0) {
                throw new IOException("Context credentials unavailable.");
            }
            final var payload = json.toString(request);
            if (payload.getBytes(StandardCharsets.UTF_8).length > 16 * 1024) {
                throw new IOException("Context request rejected.");
            }
            // The JDK's built-in diagnostic logger can log request headers. Fail closed rather than log auth.
            if (!System.getProperty("jdk.httpclient.HttpClient.log", "").isBlank()
                    || !System.getProperty("jdk.internal.httpclient.debug", "").isBlank()
                    || !System.getProperty("jdk.internal.httpclient.hpack.debug", "")
                            .isBlank()) {
                throw new IOException("Context transport unavailable.");
            }
            try (final var client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(20))
                    .proxy(new ProxySelector() {
                        @Override
                        public List<Proxy> select(final URI ignored) {
                            return List.of(Proxy.NO_PROXY);
                        }

                        @Override
                        public void connectFailed(
                                final URI ignored, final SocketAddress address, final IOException failure) {}
                    })
                    .build()) {
                final var http = HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(90))
                        .header("Authorization", "Bearer " + secret)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();
                final var future = client.sendAsync(http, ignored -> new LimitedBody());
                final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(95);
                try {
                    while (true) {
                        if (cancelled.getAsBoolean() || System.nanoTime() >= deadline) {
                            throw new IOException("Context request cancelled or timed out.");
                        }
                        try {
                            final var response = future.get(100, TimeUnit.MILLISECONDS);
                            if (response.statusCode() != 200) {
                                throw new IOException("Context request rejected.");
                            }
                            final var value = json.fromString(
                                    RpcResponse.class, new String(response.body(), StandardCharsets.UTF_8));
                            if (value == null || !Boolean.TRUE.equals(value.ok())) {
                                throw new IOException("Context response unavailable.");
                            }
                            if (value.body() instanceof List<?> list) {
                                return Map.of("data", list);
                            }
                            if (!(value.body() instanceof Map<?, ?> body)
                                    || body.keySet().stream().anyMatch(it -> !(it instanceof String))) {
                                throw new IOException("Context response unavailable.");
                            }
                            @SuppressWarnings("unchecked")
                            final var result = (Map<String, Object>) body;
                            return result;
                        } catch (final TimeoutException waiting) {
                            // Poll cancellation while the entire response, not just headers, is in flight.
                        }
                    }
                } finally {
                    future.cancel(true);
                }
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Context request cancelled.");
        } catch (final Exception failure) {
            // No causes: transport exceptions can contain endpoints, headers or remote payloads.
            throw new IOException("Context request unavailable.");
        }
    }

    // Shared tool invocation policy, scoped exclusively to the explicitly supplied call context.
    static ToolResult execute(
            final String name,
            final AgentContextConfiguration configuration,
            final JsonMapper json,
            final Map<String, Object> request,
            final ToolContext context,
            final Configuration config) {
        if (context.isCancelled() || context.callCancelled().getAsBoolean()) {
            return ToolResult.error("Context request cancelled.");
        }
        if (context.allowedTools() != null && !context.allowedTools().contains(name)) {
            return ToolResult.error("Context tool is not allowed.");
        }
        if (!usable(configuration, config)) {
            return ToolResult.error("Context capability unavailable.");
        }
        try {
            final var body = new ContextClient(configuration, json, config)
                    .call(
                            request,
                            () -> context.isCancelled()
                                    || context.callCancelled().getAsBoolean());
            final var output = json.toString(body);
            return switch (String.valueOf(body.get("status"))) {
                case "pending", "SOURCE_REQUIRED" -> ToolResult.pending(output);
                case "failed", "cancelled", "denied" -> ToolResult.error(output);
                default -> ToolResult.ok(output);
            };
        } catch (final IOException | RuntimeException failure) {
            return ToolResult.error("Context request unavailable.");
        }
    }

    static boolean usable(final AgentContextConfiguration configuration, final Configuration config) {
        try {
            if (!configuration.enabled() || configuration.tokenEnv() == null) {
                return false;
            }
            validate(URI.create(configuration.endpoint()), false);
            final var secret = config.get(configuration.tokenEnv()).orElse(null);
            return secret != null && !secret.isBlank() && secret.indexOf('\r') < 0 && secret.indexOf('\n') < 0;
        } catch (final RuntimeException invalid) {
            return false;
        }
    }

    /** Only the transport envelope is fixed; each context operation defines its own object or list body. */
    @JsonModel
    public record RpcResponse(Boolean ok, Object body) {}

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int size;

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(final Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(final List<ByteBuffer> buffers) {
            for (final var buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE - size) {
                    subscription.cancel();
                    delegate.onError(new IOException("Context response too large."));
                    return;
                }
                size += buffer.remaining();
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(final Throwable failure) {
            delegate.onError(failure);
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
        }
    }
}
