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
package io.yupiik.ia.agent.core.mcp;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.httpclient.core.listener.RequestListener;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Non-owning HTTP view for session-supplied MCP endpoints, created by the injected
 * {@link io.yupiik.ia.agent.core.wiring.HttpClientProducer.SessionCapableClient}.
 * The caller still puts the IDE headers on each request. Session request headers override configured
 * defaults case-insensitively; application requests keep their existing precedence.
 *
 * <p>No redirects are followed (including same-origin redirects). The application owns the transport,
 * including its shared cookie jar; closing a view never closes that transport. Views are not cached.
 * No provider-specific transport/credential routing is inherited.
 *
 * <p>Debug logging is opt-in as configured on the application client. All header values and the whole
 * URL are omitted, not only known credential headers. Bodies and exception messages are also omitted
 * since servers can echo credentials. No unredacted request is passed to a curl logger.
 */
public final class SessionMcpHttpClient extends ExtendedHttpClient {
    /**
     * Producer integration point. The transport must already disable redirects and remain owned by
     * the application. Listeners must contain only safe default-header transformations, not loggers
     * or provider credential injection. Header names are accepted for API consistency; logging masks
     * every value, a conservative superset of the supplied names.
     */
    public SessionMcpHttpClient(
            final HttpClient transport,
            final List<RequestListener<?>> defaults,
            final boolean debug,
            final Set<String> secretHeaderNames) {
        super(configuration(transport, defaults, debug, secretHeaderNames));
    }

    private static ExtendedHttpClientConfiguration configuration(
            final HttpClient transport,
            final List<RequestListener<?>> defaults,
            final boolean debug,
            final Set<String> secretHeaderNames) {
        Objects.requireNonNull(secretHeaderNames, "secretHeaderNames");
        if (transport.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Session MCP transport must disable redirects");
        }
        final var listeners = new ArrayList<RequestListener<?>>(defaults);
        if (debug) {
            listeners.add(new SafeDebugLogger());
        }
        return new ExtendedHttpClientConfiguration().setDelegate(transport).setRequestListeners(listeners);
    }

    private static final class SafeDebugLogger implements RequestListener<String> {
        @Override
        public State<String> before(final long count, final HttpRequest request) {
            final var message = new StringBuilder("Session MCP HTTP ")
                    .append(request.method())
                    .append(" [URL redacted]");
            request.headers()
                    .map()
                    .keySet()
                    .forEach(name -> message.append('\n').append(name).append(": ******"));
            return new State<>(request, message.toString());
        }

        @Override
        public void after(
                final String state, final HttpRequest request, final Throwable error, final HttpResponse<?> response) {
            Logger.getLogger("yca.http")
                    .info(() -> state
                            + '\n'
                            + (error != null ? "HTTP exchange failed" : "HTTP status " + response.statusCode()));
        }
    }
}
