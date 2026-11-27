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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.http.server.api.Response;
import io.yupiik.fusion.http.server.spi.Endpoint;
import io.yupiik.fusion.httpserver.jre.JreHttpServer;
import io.yupiik.fusion.httpserver.jre.JreHttpServerConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link JreAccessLogger} emits one Common Log Format line per request on the
 * {@code io.yupiik.ia.agent.web.JreAccessLogger} logger, with status, the byte count read from the response
 * {@code Content-Length}, the {@code Host} header as the client field, {@code -} bytes for streaming
 * responses and the synthesized 404 lines for unmatched requests.
 */
class JreAccessLoggerTest {
    private static final Pattern LOG_PREFIX =
            Pattern.compile("^[^ ]+ - - \\[[0-9]{2}/[A-Za-z]{3}/[0-9]{4}:[0-9]{2}:[0-9]{2}:[0-9]{2} [+-][0-9]{4}\\] $");

    private static final String LOGGER_NAME = JreAccessLogger.class.getName();

    private static HttpClient client;

    private final List<String> records = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(final LogRecord record) {
            if (record.getMessage() != null) {
                records.add(record.getMessage());
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };

    private JreHttpServer server;
    private Logger logger;

    @BeforeAll
    static void warm() {
        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterAll
    static void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
        if (logger != null) {
            logger.setLevel(null);
            logger.setUseParentHandlers(true);
            logger.removeHandler(handler);
        }
        records.clear();
    }

    private void start(final Endpoint... endpoints) {
        logger = Logger.getLogger(LOGGER_NAME);
        logger.setLevel(Level.INFO);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        final var instance = new JreHttpServer(
                new JreHttpServerConfiguration(null, 0, true), List.of(new JreAccessLogger(List.of(endpoints))));
        instance.start();
        server = instance;
    }

    private static String url(final JreHttpServer s, final String path) {
        return "http://127.0.0.1:" + s.port() + path;
    }

    private static HttpResponse<String> get(final String url) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(url))
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** A tiny endpoint returning a fixed body with a matching Content-Length so the byte count is predictable. */
    private static Endpoint echo(final String path, final String body) {
        return new Endpoint() {
            @Override
            public boolean matches(final Request request) {
                return path.equals(request.path());
            }

            @Override
            public CompletionStage<Response> handle(final Request request) {
                return CompletableFuture.completedFuture(Response.of()
                        .status(200)
                        .header("content-length", body.getBytes(StandardCharsets.UTF_8).length)
                        .body(body)
                        .build());
            }
        };
    }

    /** An SSE endpoint so the byte count is expected to be {@code -}. */
    private static Endpoint sse(final String path) {
        return new Endpoint() {
            @Override
            public boolean matches(final Request request) {
                return path.equals(request.path());
            }

            @Override
            public CompletionStage<Response> handle(final Request request) {
                return CompletableFuture.completedFuture(Response.of()
                        .status(200)
                        .header("content-type", "text/event-stream")
                        .body("data: ping\n\n")
                        .build());
            }
        };
    }

    @Test
    void logsCommonLogFormatLineWithHostStatusAndBytes() throws Exception {
        start(echo("/hello", "hi"));
        get(url(server, "/hello"));

        assertEquals(1, records.size());
        final var line = records.get(0);
        // <host> - - [dd/MMM/yyyy:HH:mm:ss +ZZZZ] "GET /hello HTTP/1.1" 200 2
        final var requestLineIndex = line.indexOf("\"GET /hello");
        assertTrue(requestLineIndex > 0, () -> "missing request line: " + line);
        assertTrue(line.endsWith("\"GET /hello HTTP/1.1\" 200 2"), () -> "unexpected: " + line);
        // host is the HTTP/1.1 Host header, identd/authuser always '-', timestamp is bracketed CLF
        assertTrue(LOG_PREFIX.matcher(line.substring(0, requestLineIndex)).matches(), () -> "bad prefix: " + line);
    }

    @Test
    void withoutExplicitHostHeaderTheUriAuthorityIsUsed() throws Exception {
        start(echo("/hello", "hi"));
        get(url(server, "/hello")); // java.net.http always sends Host = authority (127.0.0.1:<port>)

        assertEquals(1, records.size());
        final var line = records.get(0);
        assertTrue(
                line.startsWith("127.0.0.1:" + server.port() + " - - ["),
                () -> "expected the URI authority as host, got: " + line);
    }

    @Test
    void logsQueryString() throws Exception {
        start(echo("/search", "[]"));
        get(url(server, "/search?q=abc"));

        assertEquals(1, records.size());
        final var line = records.get(0);
        assertTrue(
                line.contains("\"GET /search?q=abc HTTP/1.1\" 200 2"),
                () -> "expected query in request line, got: " + line);
    }

    @Test
    void streamingEndpointsLogDashBytes() throws Exception {
        start(sse("/stream"));
        get(url(server, "/stream"));

        assertEquals(1, records.size());
        final var line = records.get(0);
        assertTrue(line.endsWith("\"GET /stream HTTP/1.1\" 200 -"), () -> "unexpected: " + line);
    }

    @Test
    void unmatchedRequestsLogTheSynthesized404() throws Exception {
        start(echo("/hello", "hi"));
        get(url(server, "/unknown"));

        assertEquals(1, records.size());
        final var line = records.get(0);
        assertTrue(line.endsWith("\"GET /unknown HTTP/1.1\" 404 -"), () -> "unexpected: " + line);
    }
}
