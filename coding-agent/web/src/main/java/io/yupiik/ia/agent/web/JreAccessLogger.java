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
package io.yupiik.ia.agent.web;

import static java.util.concurrent.CompletableFuture.completedFuture;

import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.http.server.api.Response;
import io.yupiik.fusion.http.server.spi.Endpoint;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Small access logger standing in front of a list of fusion {@link Endpoint}s,
 * emitting one <a href="https://www.w3.org/Daemon/User/Config/Logging.html#common-logfile-format">Common Log
 * Format</a> line per request at {@link Level#INFO} on the logger named after this class
 * ({@code io.yupiik.ia.agent.web.JreAccessLogger}).
 *
 * <p>It is installed as the <em>single</em> endpoint handed to the JRE {@code JreHttpServer} (priority
 * {@link Integer#MAX_VALUE}, {@link #matches(Request)} always true) and replicates the dispatch the JRE
 * adapter would otherwise perform: endpoints are matched by descending {@link Endpoint#priority()}, the
 * first {@link Endpoint#matches(Request)} wins and is delegated to; when none matches a 404 is synthesized.
 * Doing the dispatch here (instead of wrapping each real endpoint) means the log also covers the requests
 * that would never reach any endpoint - the 404s and the headless paths - leaving one obvious logging point.</p>
 *
 * <p>Only the {@code fusion-http-server-api} surface ({@link Request}, {@link Response} and {@link Endpoint})
 * is used - no server-implementation (JRE/Tomcat) type is touched, so the adapter stays portable and the
 * client identity is derived from the {@code Host} header (the fusion api exposes no remote address). The
 * byte count is read from the {@code Content-Length} response header when a single value is present, and
 * reported as {@code -} otherwise: the response body publisher cannot be safely subscribed to just to count
 * it (single-use publishers such as JSON-RPC's bytesBody would then yield an empty body to the server), and
 * streaming (SSE) bodies are flushed per frame anyway.</p>
 */
public class JreAccessLogger implements Endpoint {
    private static final Logger LOGGER = Logger.getLogger(JreAccessLogger.class.getName());

    private static final DateTimeFormatter CLF_TIME = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss", Locale.US);

    private final List<Endpoint> endpoints;

    public JreAccessLogger(final List<Endpoint> endpoints) {
        this.endpoints = endpoints.stream()
                .sorted(Comparator.comparingInt(Endpoint::priority).reversed())
                .toList();
    }

    @Override
    public int priority() {
        // the logger must always win the JRE dispatch so it is the single entry point
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean matches(final Request request) {
        return true; // catch-all: the real matching happens in handle()
    }

    @Override
    public CompletionStage<Response> handle(final Request request) {
        return endpoints.stream()
                .filter(e -> e.matches(request))
                .findFirst()
                .map(e -> e.handle(request))
                .orElseGet(() -> completedFuture(
                        Response.of().status(404).body("Not Found").build()))
                .whenComplete((response, error) -> log(request, response, error));
    }

    private void log(final Request request, final Response response, final Throwable error) {
        try {
            final var status = error != null ? 500 : response == null ? 204 : response.status();
            final var bytes = error != null || response == null ? "-" : contentLength(response);
            LOGGER.info(clf(request, status, bytes));
        } catch (final RuntimeException re) {
            // never let a logging failure break the request handling path
            LOGGER.log(
                    Level.WARNING,
                    re,
                    () -> "failed to write access log for " + request.method() + ' ' + request.path());
        }
    }

    /**
     * Builds the Common Log Format line:
     * {@code host - - [dd/MMM/yyyy:HH:mm:ss +ZZZZ] "METHOD path[?query] HTTP/1.1" status bytes}.
     * identd and authuser are always {@code -} (no authentication in this server).
     */
    private static String clf(final Request request, final int status, final String bytes) {
        final var host = firstNonBlank(request.header("host"));
        final var now = ZonedDateTime.now(ZoneId.systemDefault());
        final var timestamp = CLF_TIME.format(now) + " " + zoneOffset(now);
        final var requestLine = '"' + escape(request.method()) + ' ' + escape(target(request)) + " HTTP/1.1\"";
        return host + " - - [" + timestamp + "] " + requestLine + ' ' + status + ' ' + bytes;
    }

    private static String target(final Request request) {
        final var path = request.path();
        final var query = request.query();
        return query == null || query.isBlank() ? path : path + '?' + query;
    }

    private static String zoneOffset(final ZonedDateTime now) {
        // CLF uses an offset like +0000 / +0200 (no colon), keep it short and stable
        final var offset = now.getOffset().getId();
        return offset.equals("Z") ? "+0000" : offset.replace(":", "");
    }

    /** The {@code Content-Length} when present, else {@code -} (body not countable without consuming it). */
    private static String contentLength(final Response response) {
        final var values = response.headers().get("content-length");
        if (values == null || values.isEmpty()) {
            return "-";
        }
        return firstNonBlank(values.getLast());
    }

    private static String firstNonBlank(final String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /** Escapes {@code "} and {@code \} inside the request line fields. */
    private static String escape(final String value) {
        if (value == null || (value.indexOf('"') < 0 && value.indexOf('\\') < 0)) {
            return value == null ? "-" : value;
        }
        final var sb = new StringBuilder(value.length());
        for (final var c : value.toCharArray()) {
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
