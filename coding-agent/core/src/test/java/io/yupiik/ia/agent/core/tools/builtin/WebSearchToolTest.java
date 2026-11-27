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
package io.yupiik.ia.agent.core.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.ToolsConfiguration;
import io.yupiik.ia.agent.core.configuration.WebSearchConfiguration;
import io.yupiik.ia.agent.core.configuration.WebSearchProvider;
import io.yupiik.ia.agent.core.configuration.WebSearchProviderConfiguration;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.mcp.WebSearchManager;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.maven.knowledge.process.ProcessService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class WebSearchToolTest {

    private static final String NO_RESULTS = "No search results found. Please try a different query.";

    private JsonMapper mapper;
    private HttpClient delegate;
    private ExtendedHttpClient client;

    @BeforeEach
    void boot(final JsonMapper mapper) {
        this.mapper = mapper;
        this.delegate = HttpClient.newBuilder().build();
        this.client = new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate));
    }

    @AfterEach
    void stop() {
        client.close();
        // HttpResponse/completion is done at that point: the graceful shutdown can wait forever on a
        // lingering (SSE/keep-alive) connection of the test server, so abort the selector first.
        delegate.shutdownNow();
        delegate.close();
        client = null;
        delegate = null;
    }

    @Nested
    @FusionSupport(modules = FormatsResultsWithPublishDateScenarioModule.class)
    class FormatsResultsWithPublishDateScenario {
        @TempDir
        static Path tempDir;

        @Test
        void formatsResultsWithPublishDate(@Fusion final CodingAgentConfiguration config) throws Exception {
            try (final var mock = Mock.start(mapper).payload(parallelPayload())) {
                final var configured = singleProvider(config, WebSearchProvider.PARALLEL, mock.url());
                final var tool =
                        new WebSearchTool(new WebSearchManager(mapper, configured, client), configured, mapper);
                final var result = tool.execute(
                        Map.of("query", "apache maven build tool"),
                        ToolContext.empty(config.resolveWorkingDir(), new DisabledSandbox(config.resolveWorkingDir())));
                assertTrue(result.success(), result.output());
                assertTrue(result.output().contains("- First (https://example.com/a, published 2026-06-30)"));
                assertTrue(result.output().contains("\n  part one\n\n  part two"));
                assertTrue(result.output().contains("- Second (https://example.com/b)"));
            }
        }
    }

    @Nested
    @FusionSupport(modules = NoResultsMessageOnEmptyAnswerScenarioModule.class)
    class NoResultsMessageOnEmptyAnswerScenario {
        @TempDir
        static Path tempDir;

        @Test
        void noResultsMessageOnEmptyAnswer(@Fusion final CodingAgentConfiguration config) throws Exception {
            try (final var mock = Mock.start(mapper).payload("{\"search_id\":\"s1\",\"results\":[]}")) {
                final var configured = singleProvider(config, WebSearchProvider.PARALLEL, mock.url());
                final var tool =
                        new WebSearchTool(new WebSearchManager(mapper, configured, client), configured, mapper);
                final var result = tool.execute(
                        Map.of("query", "apache maven build tool"),
                        ToolContext.empty(config.resolveWorkingDir(), new DisabledSandbox(config.resolveWorkingDir())));
                assertTrue(result.success(), result.output());
                assertEquals(NO_RESULTS, result.output());
            }
        }
    }

    @Nested
    @FusionSupport(modules = QueryIsRequiredScenarioModule.class)
    class QueryIsRequiredScenario {
        @TempDir
        static Path tempDir;

        @Test
        void queryIsRequired(@Fusion final CodingAgentConfiguration config) {
            final var tool = new WebSearchTool(new WebSearchManager(mapper, config, client), config, mapper);
            final var result = tool.execute(
                    Map.of(),
                    ToolContext.empty(config.resolveWorkingDir(), new DisabledSandbox(config.resolveWorkingDir())));
            assertFalse(result.success());
            assertEquals("query is required", result.output());
        }
    }

    @Nested
    @FusionSupport(modules = ToolIsHiddenWhenNoProviderIsEnabledScenarioModule.class)
    class ToolIsHiddenWhenNoProviderIsEnabledScenario {
        @TempDir
        static Path tempDir;

        @Test
        void toolIsHiddenWhenNoProviderIsEnabled(@Fusion final CodingAgentConfiguration config) {

            final var tool = new WebSearchTool(new WebSearchManager(mapper, config, client), config, mapper);
            assertFalse(tool.usable(config.resolveWorkingDir()));
            final var mcpManager = new McpManager(config, mapper, List.of(tool), null, new ProcessService());
            mcpManager.start();
            final var registry = new ToolRegistry(mcpManager);
            assertFalse(registry.describe(config.resolveWorkingDir()).contains("websearch"));
            assertTrue(registry.toWireTools(config.resolveWorkingDir()).stream()
                    .noneMatch(t -> "websearch".equals(t.function().name())));
            final var executed = tool.execute(
                    Map.of("query", "anything"),
                    ToolContext.empty(config.resolveWorkingDir(), new DisabledSandbox(config.resolveWorkingDir())));
            assertFalse(executed.success());
            assertTrue(executed.output().contains("disabled"));
        }
    }

    @Nested
    @FusionSupport(modules = ToolIsVisibleWithDefaultProvidersScenarioModule.class)
    class ToolIsVisibleWithDefaultProvidersScenario {
        @TempDir
        static Path tempDir;

        @Test
        void toolIsVisibleWithDefaultProviders(@Fusion final CodingAgentConfiguration config) {
            final var tool = new WebSearchTool(new WebSearchManager(mapper, config, client), config, mapper);
            assertTrue(tool.usable(config.resolveWorkingDir()));
            final var mcpManager = new McpManager(config, mapper, List.of(tool), null, new ProcessService());
            mcpManager.start();
            final var registry = new ToolRegistry(mcpManager);
            assertTrue(registry.describe(config.resolveWorkingDir()).contains("websearch"));
            assertTrue(registry.toWireTools(config.resolveWorkingDir()).stream()
                    .anyMatch(t -> "websearch".equals(t.function().name())));
        }
    }

    @Nested
    @FusionSupport(modules = ConfigDefaultsIncludeAllProvidersScenarioModule.class)
    class ConfigDefaultsIncludeAllProvidersScenario {
        @TempDir
        static Path tempDir;

        @Test
        void configDefaultsIncludeAllProviders(@Fusion final CodingAgentConfiguration config) {
            final var websearch = config.tools().websearch();
            assertEquals(4, websearch.providers().size());
            for (final var provider : websearch.providers()) {
                assertTrue(provider.enabled());
                assertNull(provider.url());
                assertNull(provider.apiKey());
            }
            assertEquals(WebSearchProvider.EXA, websearch.providers().get(0).provider());
            assertEquals(
                    WebSearchProvider.PARALLEL, websearch.providers().get(1).provider());
            assertEquals(
                    WebSearchProvider.FIRECRAWL, websearch.providers().get(2).provider());
            assertEquals(
                    WebSearchProvider.TINYFISH, websearch.providers().get(3).provider());
            assertEquals(8, websearch.numResults());
            assertEquals(25, websearch.timeoutSeconds());
        }
    }

    @Nested
    @FusionSupport(modules = LegacySingleProviderKeysAreInertScenarioModule.class)
    class LegacySingleProviderKeysAreInertScenario {
        @TempDir
        static Path tempDir;

        @Test
        void legacySingleProviderKeysAreInert(@Fusion final CodingAgentConfiguration config) {
            final var websearch = config.tools().websearch();
            assertEquals(4, websearch.providers().size());
            assertEquals(WebSearchProvider.EXA, websearch.providers().get(0).provider());
            assertNull(websearch.providers().get(0).url());
        }
    }

    private CodingAgentConfiguration singleProvider(
            final CodingAgentConfiguration baseline, final WebSearchProvider provider, final String url) {
        final var tools = baseline.tools();
        return new CodingAgentConfiguration(
                baseline.storageHome(),
                baseline.home(),
                baseline.workingDir(),
                baseline.projectDir(),
                baseline.provider(),
                baseline.model(),
                baseline.llm(),
                baseline.compaction(),
                baseline.pruning(),
                baseline.compression(),
                baseline.memory(),
                baseline.todos(),
                baseline.optimize(),
                baseline.permissions(),
                baseline.sandbox(),
                baseline.mcp(),
                baseline.mavenRepositoryKnowledge(),
                baseline.structuralCodeGraph(),
                baseline.sessions(),
                baseline.skills(),
                baseline.agents(),
                baseline.mode(),
                baseline.httpClient(),
                new ToolsConfiguration(
                        tools.builtin(),
                        tools.spawnSubagent(),
                        new WebSearchConfiguration(
                                List.of(new WebSearchProviderConfiguration(provider, true, url, null, List.of())),
                                tools.websearch().numResults(),
                                tools.websearch().timeoutSeconds()),
                        tools.manageContext(),
                        tools.parallelReadonly(),
                        tools.maxOutputChars(),
                        tools.repeatSuccessThreshold(),
                        tools.alwaysSuggestMavenKnowledge(),
                        tools.shell(),
                        tools.bashOverrides()),
                baseline.subagents(),
                baseline.schedulerThreads(),
                baseline.metrics(),
                baseline.maxLineLength(),
                baseline.resumeAliases(),
                baseline.slashcommands(),
                baseline.prompt(),
                baseline.labels(),
                baseline.logging(),
                baseline.hooks(),
                baseline.notifications(),
                baseline.secrets(),
                null);
    }

    private static String parallelPayload() {
        return "{\"search_id\":\"s1\",\"results\":[{\"url\":\"https://example.com/a\",\"title\":\"First\",\"publish_date\":\"2026-06-30\",\"excerpts\":[\"part"
                + " one\",\"part two\"]},"
                + "{\"url\":\"https://example.com/b\",\"title\":\"Second\",\"publish_date\":null,\"excerpts\":[]}]}";
    }

    /** Minimal MCP endpoint: initialize + notifications succeed, tools/call returns a configurable text. */
    private static final class Mock implements AutoCloseable {
        private final HttpServer server;
        private final JsonMapper mapper;
        private volatile String payload = "{}";

        private Mock(final JsonMapper mapper) throws IOException {
            this.mapper = mapper;
            this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        static Mock start(final JsonMapper mapper) throws IOException {
            return new Mock(mapper);
        }

        String url() {
            return "http://localhost:" + server.getAddress().getPort() + "/mcp";
        }

        Mock payload(final String payload) {
            this.payload = payload;
            return this;
        }

        private void handle(final HttpExchange exchange) throws IOException {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"method\":\"initialize\"")) {
                respond(
                        exchange,
                        200,
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"serverInfo\":{\"name\":\"mock\",\"version\":\"1\"}}}");
            } else if (body.contains("\"method\":\"notifications/initialized\"")) {
                exchange.getResponseHeaders().set("content-type", "application/json");
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
            } else { // tools/call
                respond(
                        exchange,
                        200,
                        mapper.toString(Map.of(
                                "jsonrpc",
                                "2.0",
                                "id",
                                ((Map<?, ?>) mapper.fromString(Object.class, body)).get("id"),
                                "result",
                                Map.of("content", List.of(Map.of("type", "text", "text", payload))))));
            }
        }

        private static void respond(final HttpExchange exchange, final int status, final String body)
                throws IOException {
            exchange.getResponseHeaders().set("content-type", "application/json");
            final var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (final var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    public static class FormatsResultsWithPublishDateScenarioModule extends ConfigurationTestModule {
        public FormatsResultsWithPublishDateScenarioModule() {
            super(Map.of("yca.storageHome", FormatsResultsWithPublishDateScenario.tempDir.toString()));
        }
    }

    public static class NoResultsMessageOnEmptyAnswerScenarioModule extends ConfigurationTestModule {
        public NoResultsMessageOnEmptyAnswerScenarioModule() {
            super(Map.of("yca.storageHome", NoResultsMessageOnEmptyAnswerScenario.tempDir.toString()));
        }
    }

    public static class QueryIsRequiredScenarioModule extends ConfigurationTestModule {
        public QueryIsRequiredScenarioModule() {
            super(Map.of("yca.storageHome", QueryIsRequiredScenario.tempDir.toString()));
        }
    }

    public static class ToolIsHiddenWhenNoProviderIsEnabledScenarioModule extends ConfigurationTestModule {
        public ToolIsHiddenWhenNoProviderIsEnabledScenarioModule() {
            super(Map.of(
                    "yca.storageHome",
                    ToolIsHiddenWhenNoProviderIsEnabledScenario.tempDir.toString(),
                    "yca.tools.websearch.providers.length",
                    "0"));
        }
    }

    public static class ToolIsVisibleWithDefaultProvidersScenarioModule extends ConfigurationTestModule {
        public ToolIsVisibleWithDefaultProvidersScenarioModule() {
            super(Map.of("yca.storageHome", ToolIsVisibleWithDefaultProvidersScenario.tempDir.toString()));
        }
    }

    public static class ConfigDefaultsIncludeAllProvidersScenarioModule extends ConfigurationTestModule {
        public ConfigDefaultsIncludeAllProvidersScenarioModule() {
            super(Map.of("yca.storageHome", ConfigDefaultsIncludeAllProvidersScenario.tempDir.toString()));
        }
    }

    public static class LegacySingleProviderKeysAreInertScenarioModule extends ConfigurationTestModule {
        public LegacySingleProviderKeysAreInertScenarioModule() {
            super(Map.of(
                    "yca.storageHome",
                    LegacySingleProviderKeysAreInertScenario.tempDir.toString(),
                    "yca.tools.websearch.provider",
                    "parallel",
                    "yca.tools.websearch.apiKey",
                    "legacy",
                    "yca.tools.websearch.exaUrl",
                    "https://legacy.example.com/mcp",
                    "yca.tools.websearch.parallelUrl",
                    "https://legacy2.example.com/mcp"));
        }
    }
}
