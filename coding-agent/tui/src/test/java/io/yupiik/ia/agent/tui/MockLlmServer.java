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
package io.yupiik.ia.agent.tui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.internal.JsonStrings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

/**
 * Minimal OpenAI flavored LLM double: serves /v1/chat/completions (SSE when the request
 * streams, plain JSON otherwise), /v1/models and a 404 /v1/responses.
 */
public final class MockLlmServer implements AutoCloseable {
    private final HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final ExecutorService executor;
    private volatile String answer = "hello from mock";
    private final List<String> models;

    public MockLlmServer() throws IOException {
        this(List.of());
    }

    public MockLlmServer(final List<String> models) throws IOException {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        this.models = models;
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/responses", exchange -> respond(exchange, 404, "not found", "text/plain"));
        server.createContext("/v1/chat/completions", exchange -> {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(body);
            if (body.contains("\"stream\":true")) {
                final var text = answer();
                final var cut = Math.max(1, text.length() / 2);
                final var sse = String.join(
                        "\n",
                        chunk(text.substring(0, cut)),
                        "",
                        chunk(text.substring(cut)),
                        "",
                        "data: {\"id\":\"1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}",
                        "",
                        "data: [DONE]",
                        "",
                        "");
                respond(exchange, 200, sse, "text/event-stream");
            } else {
                respond(
                        exchange,
                        200,
                        "{\"id\":\"c1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":"
                                + JsonStrings.escape(answer())
                                + "},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}",
                        "application/json");
            }
        });
        server.createContext("/v1/models", exchange -> {
            // 30 models by default so the /model picker overflows its window and scrolls;
            // a custom id list (per-provider tests) is served verbatim instead.
            final var defaultModels = models == null || models.isEmpty();
            final var ids = defaultModels
                    ? IntStream.rangeClosed(1, 30)
                            .mapToObj(i -> "m" + (i < 10 ? "0" + i : String.valueOf(i)))
                            .toList()
                    : models;
            final var sb = new StringBuilder("{\"data\":[");
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"id\":\"").append(ids.get(i)).append("\",\"owned_by\":\"x\"}");
            }
            sb.append("]}");
            respond(exchange, 200, sb.toString(), "application/json");
        });
        server.setExecutor(executor);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://localhost:" + port() + "/v1/chat/completions";
    }

    public String answer() {
        return answer;
    }

    public void answer(final String value) {
        this.answer = value;
    }

    public List<String> requests() {
        return requests;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static String chunk(final String content) {
        return "data: {\"id\":\"1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
                + content + "\"},\"finish_reason\":null}]}";
    }

    private static void respond(
            final HttpExchange exchange, final int status, final String body, final String contentType) {
        try {
            exchange.getRequestBody().readAllBytes();
            final var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (final var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
