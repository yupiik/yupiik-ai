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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.FusionModule;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.serialization.JsonCodec;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.NativeProviderConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.Tool;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.wiring.HttpClientProducer;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.github.configuration.AgentContextConfiguration;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.ParameterizedType;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Offline wire contracts, not model-quality tests. Real ChatClient/provider dialects send requests to
 * loopback HTTP and decode scripted tool calls. No external provider, Git process or shell is used.
 * Ordinary tool declarations and structured-output schema adaptation are deliberately tested separately:
 * ChatClient currently only strictifies/sanitizes the latter, not ordinary tool declarations.
 */
@Execution(ExecutionMode.SAME_THREAD) // The class owns one stable configuration and its fixture paths/transport.
@FusionSupport(modules = ContextToolWireTest.Source.class)
class ContextToolWireTest {
    @Fusion
    private CodingAgentConfiguration injectedConfiguration;

    @TempDir
    static Path home;

    private static HttpServer modelServer;

    @ParameterizedTest(name = "{0}: {1}, {2}")
    @CsvSource({
        "openai, completions, gpt-4.1",
        "openai-responses, responses, gpt-4.1",
        "codex, codex, gpt-5-codex",
        "claude, anthropic, claude-sonnet-4-5",
        "deepseek, completions, deepseek-v4-flash",
        "glm, completions, glm-4.7",
        "gemini-flash, completions, gemini-2.5-flash",
        "qwen, completions, qwen3-coder-plus",
        "qwen-27b, completions, Qwen/Qwen3.5-27B"
    })
    @Timeout(30)
    void realProviderWirePreservesToolSchemasAndValidatesDecodedArguments(
            final String provider, final String api, final String model) throws Exception {
        try (final var container = jsonContainer();
                final var lookup = container.lookup(JsonMapper.class)) {
            final var json = lookup.instance();
            final var tools = tools(json);
            final var cases = cases();
            final var requests = new CopyOnWriteArrayList<Map<String, Object>>();
            final var paths = new CopyOnWriteArrayList<String>();
            final var server = modelServer;
            server.createContext(
                    "/v1/models",
                    exchange -> respond(exchange, json.toString(Map.of("data", List.of(Map.of("id", model))))));
            final var stubFailure = new AtomicReference<Throwable>();
            server.createContext("/", exchange -> {
                try {
                    paths.add(exchange.getRequestURI().getPath());
                    requests.add(object(json.fromString(
                            Object.class,
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))));
                    respondModel(exchange, json, api, toolResponse(json, api, model, cases));
                } catch (final RuntimeException | AssertionError failure) {
                    stubFailure.set(failure);
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                }
            });
            try {
                final var config = injectedConfiguration;
                try (final var http = new HttpClientProducer()
                                .httpClient(config, new NativeProviderConfiguration(null, null));
                        final var client = new ChatClient(
                                config, json, new ProviderManager(config, json), Clock.systemUTC(), http)) {
                    final var response = client.chat(request(provider, model, tools, home));
                    assertEquals(List.of(route(api)), paths, "explicit API must not probe or fall back");
                    assertEquals(1, requests.size());
                    final var wire = requests.getFirst();
                    assertEquals(model, wire.get("model"), "exact model ID, including 27B, must reach the wire");
                    final List<?> declarations = assertInstanceOf(List.class, wire.get("tools"));
                    assertEquals(2, declarations.size());
                    for (final var tool : tools) {
                        final var declaration = declarations.stream()
                                .map(ContextToolWireTest::object)
                                .map(value -> value.containsKey("function") ? object(value.get("function")) : value)
                                .filter(value -> tool.name().equals(value.get("name")))
                                .findFirst()
                                .orElseThrow();
                        final var schema =
                                object(declaration.get("anthropic".equals(api) ? "input_schema" : "parameters"));
                        // No provider-specific relaxation or test-built replacement of the production schema.
                        assertEquals(roundTrip(json, tool.schema()), schema);
                        assertEquals(List.of(tool.name().equals("git") ? "argv" : "operation"), schema.get("required"));
                        assertEquals(false, schema.get("additionalProperties"));
                        assertEquals(
                                List.of("string", "null"),
                                object(object(schema.get("properties")).get("repository"))
                                        .get("type"));
                    }
                    final var calls = response.message().toolCalls();
                    assertNotNull(calls);
                    assertEquals(cases.size(), calls.size());
                    for (int i = 0; i < cases.size(); i++) {
                        final var fixture = cases.get(i);
                        final var call = calls.get(i);
                        assertEquals("call-" + i, call.id());
                        assertEquals(fixture.tool(), call.function().name());
                        final Map<String, Object> arguments;
                        if (call.function().arguments() == null) {
                            // The Anthropic adapter fails closed while decoding malformed input. Core
                            // tool dispatch treats missing arguments as an empty object, which must be
                            // rejected by the tool too, never interpreted as a shell fallback.
                            assertFalse(fixture.valid(), "Valid model arguments must survive decoding");
                            assertEquals("claude", provider);
                            arguments = Map.of();
                        } else {
                            arguments = object(json.fromString(
                                    Object.class, call.function().arguments()));
                            assertEquals(roundTrip(json, fixture.arguments()), arguments);
                        }
                        if (fixture.tool().equals("git") && fixture.valid()) {
                            final List<?> argv = assertInstanceOf(List.class, arguments.get("argv"));
                            assertEquals(
                                    List.of("log", "-1", "--format=%s", "HEAD", "--", "a file;$(not-a-shell).java"),
                                    argv);
                            assertTrue(argv.stream().allMatch(String.class::isInstance));
                        }
                        final var runtime = tools.stream()
                                .filter(tool -> tool.name().equals(fixture.tool()))
                                .findFirst()
                                .orElseThrow();
                        final var result =
                                runtime.execute(arguments, ToolContext.empty(home, new DisabledSandbox(home)));
                        // Capability is intentionally disabled: valid arguments pass runtime validation,
                        // while invalid model output must be rejected before any transport/fallback.
                        assertEquals(
                                fixture.valid()
                                        ? "Context capability unavailable."
                                        : fixture.tool().equals("git")
                                                ? "Invalid Git arguments."
                                                : "Invalid context arguments.",
                                result.output());
                        assertTrue(result.error());
                    }
                }
            } finally {
                server.removeContext("/");
                server.removeContext("/v1/models");
                if (stubFailure.get() != null) {
                    throw new AssertionError("Local model fixture failed", stubFailure.get());
                }
            }
        }
    }

    @ParameterizedTest(name = "structured schema adapter: {0}")
    @CsvSource({"completions", "responses", "codex", "anthropic"})
    @Timeout(30)
    void realStructuredSchemaAdapterKeepsStringArraysAndNullableOptionalFields(final String api) throws Exception {
        try (final var container = jsonContainer();
                final var lookup = container.lookup(JsonMapper.class)) {
            final var json = lookup.instance();
            final var requests = new CopyOnWriteArrayList<Map<String, Object>>();
            final var server = modelServer;
            server.createContext(
                    "/v1/models",
                    exchange ->
                            respond(exchange, json.toString(Map.of("data", List.of(Map.of("id", "schema-fixture"))))));
            server.createContext("/", exchange -> {
                requests.add(object(json.fromString(
                        Object.class, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))));
                respondModel(exchange, json, api, textResponse(api, "schema-fixture"));
            });
            try {
                final var config = injectedConfiguration;
                try (final var http = new HttpClientProducer()
                                .httpClient(config, new NativeProviderConfiguration(null, null));
                        final var client = new ChatClient(
                                config, json, new ProviderManager(config, json), Clock.systemUTC(), http)) {
                    for (final var tool : tools(json)) {
                        final var schema = new LinkedHashMap<>(tool.schema());
                        final var properties = new LinkedHashMap<>(object(schema.get("properties")));
                        final var constrained = new LinkedHashMap<>(
                                object(properties.get(tool.name().equals("git") ? "argv" : "depth")));
                        constrained.put(tool.name().equals("git") ? "minItems" : "minimum", 1);
                        properties.put(tool.name().equals("git") ? "argv" : "depth", constrained);
                        schema.put("properties", properties);
                        final var before = json.toString(schema);
                        client.chatStructured(
                                new ChatCompletionRequest(
                                        "$fixture-" + api + "/schema-fixture",
                                        List.of(Message.user("Return fixture JSON")),
                                        null,
                                        null,
                                        false,
                                        null,
                                        null,
                                        128,
                                        null,
                                        null),
                                tool.name(),
                                schema);
                        assertEquals(before, json.toString(schema), "adapter must not mutate the tool's schema");
                        final var wire = requests.getLast();
                        final Map<String, Object> adapted;
                        if (api.equals("anthropic")) {
                            adapted = object(
                                    object(object(wire.get("output_config")).get("format"))
                                            .get("schema"));
                        } else if (api.equals("completions")) {
                            final var format =
                                    object(object(wire.get("response_format")).get("json_schema"));
                            assertEquals(true, format.get("strict"));
                            adapted = object(format.get("schema"));
                        } else {
                            final var format = object(object(wire.get("text")).get("format"));
                            assertEquals(true, format.get("strict"));
                            adapted = object(format.get("schema"));
                        }
                        final var adaptedProperties = object(adapted.get("properties"));
                        assertEquals(
                                List.of("string", "null"),
                                object(adaptedProperties.get("repository")).get("type"));
                        assertEquals(false, adapted.get("additionalProperties"));
                        if (api.equals("anthropic")) {
                            assertEquals(schema.get("required"), adapted.get("required"));
                            final var property =
                                    object(adaptedProperties.get(tool.name().equals("git") ? "argv" : "depth"));
                            assertFalse(property.containsKey("minItems"));
                            assertFalse(property.containsKey("minimum"));
                            assertTrue(
                                    String.valueOf(property.get("description")).contains("Constraints:"));
                        } else {
                            final List<?> required = assertInstanceOf(List.class, adapted.get("required"));
                            assertEquals(properties.keySet(), Set.copyOf(required));
                        }
                        if (tool.name().equals("git")) {
                            final var argv = object(adaptedProperties.get("argv"));
                            assertEquals("array", argv.get("type"));
                            assertEquals(Map.of("type", "string"), argv.get("items"));
                        } else {
                            assertEquals(
                                    object(properties.get("operation")).get("enum"),
                                    object(adaptedProperties.get("operation")).get("enum"));
                        }
                    }
                    assertEquals(2, requests.size(), "one bounded structured-output request per context tool");
                }
            } finally {
                server.removeContext("/");
                server.removeContext("/v1/models");
            }
        }
    }

    private static List<EmbeddedTool> tools(final JsonMapper json) {
        final var disabled = new AgentContextConfiguration(false, "", "", "", "fixture/primary");
        return List.of(
                new GitTool(disabled, json, Configuration.of(Map.of())),
                new GithubContextTool(disabled, json, Configuration.of(Map.of())));
    }

    private static ChatCompletionRequest request(
            final String provider, final String model, final List<EmbeddedTool> tools, final Path root) {
        return new ChatCompletionRequest(
                "$" + provider + "/" + model,
                List.of(Message.user("Read the pinned context using only these tools.")),
                null,
                null,
                false,
                tools.stream()
                        .map(tool -> Tool.function(tool.name(), tool.description(root), tool.schema()))
                        .toList(),
                null,
                512,
                null,
                null);
    }

    private static List<Fixture> cases() {
        final var argv = List.of("log", "-1", "--format=%s", "HEAD", "--", "a file;$(not-a-shell).java");
        final var gitNull = new LinkedHashMap<String, Object>();
        gitNull.put("argv", argv);
        gitNull.put("repository", null);
        final var summaryNull = new LinkedHashMap<String, Object>();
        summaryNull.put("operation", "summary");
        for (final var field : List.of("repository", "page", "depth", "tags", "revision", "operationId")) {
            summaryNull.put(field, null);
        }
        return List.of(
                new Fixture("git", Map.of("argv", argv), true),
                new Fixture("git", gitNull, true),
                new Fixture("github_context", Map.of("operation", "summary"), true),
                new Fixture("github_context", summaryNull, true),
                new Fixture("git", Map.of("argv", "log -1"), false),
                new Fixture("git", Map.of("argv", List.of(1)), false),
                new Fixture("git", Map.of("argv", List.of("log"), "shell", "sh"), false),
                new Fixture("github_context", Map.of("operation", "exec"), false),
                new Fixture("github_context", Map.of("operation", List.of("summary")), false),
                new Fixture("github_context", Map.of("operation", "summary", "repository", 42), false),
                new Fixture("github_context", Map.of("operation", "prepare_source", "depth", 1.5), false),
                new Fixture("github_context", Map.of("operation", "prepare_source", "tags", "true"), false),
                new Fixture("github_context", Map.of("operation", "prepare_source", "revision", "main"), false));
    }

    private static Map<String, Object> toolResponse(
            final JsonMapper json, final String api, final String model, final List<Fixture> cases) {
        final var calls = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < cases.size(); i++) {
            final var fixture = cases.get(i);
            calls.add(
                    api.equals("anthropic")
                            ? Map.of(
                                    "type",
                                    "tool_use",
                                    "id",
                                    "call-" + i,
                                    "name",
                                    fixture.tool(),
                                    "input",
                                    fixture.arguments())
                            : api.equals("responses") || api.equals("codex")
                                    ? Map.of(
                                            "type",
                                            "function_call",
                                            "id",
                                            "item-" + i,
                                            "call_id",
                                            "call-" + i,
                                            "name",
                                            fixture.tool(),
                                            "arguments",
                                            json.toString(fixture.arguments()))
                                    : Map.of(
                                            "id",
                                            "call-" + i,
                                            "type",
                                            "function",
                                            "function",
                                            Map.of(
                                                    "name",
                                                    fixture.tool(),
                                                    "arguments",
                                                    json.toString(fixture.arguments()))));
        }
        if (api.equals("anthropic")) {
            return Map.of(
                    "id",
                    "m1",
                    "type",
                    "message",
                    "role",
                    "assistant",
                    "model",
                    model,
                    "content",
                    calls,
                    "stop_reason",
                    "tool_use",
                    "usage",
                    Map.of("input_tokens", 3, "output_tokens", 2));
        }
        if (api.equals("responses") || api.equals("codex")) {
            return Map.of(
                    "id",
                    "r1",
                    "model",
                    model,
                    "output",
                    calls,
                    "usage",
                    Map.of("input_tokens", 3, "output_tokens", 2));
        }
        return Map.of(
                "id",
                "c1",
                "model",
                model,
                "choices",
                List.of(Map.of(
                        "index",
                        0,
                        "message",
                        Map.of("role", "assistant", "tool_calls", calls),
                        "finish_reason",
                        "tool_calls")));
    }

    private static Map<String, Object> textResponse(final String api, final String model) {
        if (api.equals("anthropic")) {
            return Map.of(
                    "id",
                    "m1",
                    "type",
                    "message",
                    "role",
                    "assistant",
                    "model",
                    model,
                    "content",
                    List.of(Map.of("type", "text", "text", "{}")),
                    "stop_reason",
                    "end_turn",
                    "usage",
                    Map.of("input_tokens", 3, "output_tokens", 2));
        }
        if (api.equals("responses") || api.equals("codex")) {
            return Map.of(
                    "id",
                    "r1",
                    "model",
                    model,
                    "output",
                    List.of(Map.of(
                            "type",
                            "message",
                            "role",
                            "assistant",
                            "content",
                            List.of(Map.of("type", "output_text", "text", "{}")))));
        }
        return Map.of(
                "id",
                "c1",
                "model",
                model,
                "choices",
                List.of(Map.of(
                        "index", 0, "message", Map.of("role", "assistant", "content", "{}"), "finish_reason", "stop")));
    }

    private static String route(final String api) {
        return "/v1/"
                + (api.equals("anthropic")
                        ? "messages"
                        : api.equals("responses") || api.equals("codex") ? "responses" : "chat/completions");
    }

    private static void respond(final HttpExchange exchange, final String body) throws IOException {
        final var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (exchange;
                final var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void respondModel(
            final HttpExchange exchange, final JsonMapper json, final String api, final Map<String, Object> response)
            throws IOException {
        if (!api.equals("codex")) {
            respond(exchange, json.toString(response));
            return;
        }
        // Codex always requests SSE, even through the non-streaming ChatClient entry point.
        final var body = new StringBuilder();
        final List<?> output = assertInstanceOf(List.class, response.get("output"));
        for (int i = 0; i < output.size(); i++) {
            body.append("event: response.output_item.done\ndata: ")
                    .append(json.toString(
                            Map.of("type", "response.output_item.done", "output_index", i, "item", output.get(i))))
                    .append("\n\n");
        }
        body.append("event: response.completed\ndata: ")
                .append(json.toString(Map.of("type", "response.completed", "response", response)))
                .append("\n\n");
        final var bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        try (exchange;
                final var outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(final Object value) {
        return (Map<String, Object>) assertInstanceOf(Map.class, value);
    }

    private static Object roundTrip(final JsonMapper json, final Object value) {
        return json.fromString(Object.class, json.toString(value));
    }

    private static RuntimeContainer jsonContainer() {
        // Same JSON-only bootstrap as core TestJsonMapper: no CLI, worker, lifecycle or network services.
        return ConfiguringContainer.of()
                .register(new FusionModule() {
                    @Override
                    public BiPredicate<RuntimeContainer, FusionBean<?>> beanFilter() {
                        return (container, bean) -> {
                            final var type = bean.type() instanceof ParameterizedType parameterized
                                    ? (Class<?>) parameterized.getRawType()
                                    : (Class<?>) bean.type();
                            return JsonCodec.class.isAssignableFrom(type)
                                    || JsonMapper.class.isAssignableFrom(type)
                                    || Configuration.class.isAssignableFrom(type)
                                    || Emitter.class.isAssignableFrom(type)
                                    || RuntimeContainer.class.isAssignableFrom(type);
                        };
                    }

                    @Override
                    public BiPredicate<RuntimeContainer, FusionListener<?>> listenerFilter() {
                        return (container, listener) -> false;
                    }
                })
                .start();
    }

    private record Fixture(String tool, Map<String, Object> arguments, boolean valid) {}

    @AfterAll
    static void stopModelServer() {
        if (modelServer != null) {
            modelServer.stop(0);
        }
    }

    public static class Source extends GithubConfigurationTestModule {
        public Source() {
            super(settings());
        }

        private static Map<String, String> settings() {
            try {
                modelServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
            modelServer.start();
            final var providers = List.of(
                    List.of("openai", "completions", "gpt-4.1"),
                    List.of("openai-responses", "responses", "gpt-4.1"),
                    List.of("codex", "codex", "gpt-5-codex"),
                    List.of("claude", "anthropic", "claude-sonnet-4-5"),
                    List.of("deepseek", "completions", "deepseek-v4-flash"),
                    List.of("glm", "completions", "glm-4.7"),
                    List.of("gemini-flash", "completions", "gemini-2.5-flash"),
                    List.of("qwen", "completions", "qwen3-coder-plus"),
                    List.of("qwen-27b", "completions", "Qwen/Qwen3.5-27B"),
                    List.of("fixture-completions", "completions", "schema-fixture"),
                    List.of("fixture-responses", "responses", "schema-fixture"),
                    List.of("fixture-codex", "codex", "schema-fixture"),
                    List.of("fixture-anthropic", "anthropic", "schema-fixture"));
            final var values = new HashMap<String, String>();
            values.put("yca.storageHome", home.toString());
            values.put("yca.provider.providers.length", Integer.toString(providers.size()));
            for (int i = 0; i < providers.size(); i++) {
                final var provider = providers.get(i);
                final var prefix = "yca.provider.providers." + i + ".";
                values.put(prefix + "id", provider.get(0));
                values.put(prefix + "api", provider.get(1));
                values.put(prefix + "model", provider.get(2));
                values.put(
                        prefix + "baseUrl",
                        "http://127.0.0.1:" + modelServer.getAddress().getPort() + route(provider.get(1)));
                values.put(prefix + "apiKey", "offline-fixture-key");
                values.put(prefix + "contextWindow", "128000");
                values.put(prefix + "timeoutSeconds", "5");
            }
            values.put("yca.llm.retriesMaxAttempts", "1");
            values.put("yca.llm.rateLimitRequestsPerMinute", "0");
            return values;
        }
    }
}
