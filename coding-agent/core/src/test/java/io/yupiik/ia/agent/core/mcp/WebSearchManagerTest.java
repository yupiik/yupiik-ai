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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
@FusionSupport(modules = ConfigurationTestModule.class)
class WebSearchManagerTest {
    private CodingAgentConfiguration baseline;

    @BeforeEach
    void configuration(@Fusion final CodingAgentConfiguration configuration) {
        baseline = configuration;
    }

    @TempDir
    Path tempDir;

    private JsonMapper mapper;
    // shared per test class: each per-test client spawned/stopped a selector thread, and the
    // server is recreated per test anyway (ms-scale), so only the client is worth sharing
    private static HttpClient delegate;
    private static ExtendedHttpClient client;

    @BeforeAll
    static void bootClient() {
        delegate = HttpClient.newBuilder().build();
        client = new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate));
    }

    @AfterAll
    static void stopClient() {
        client.close();
        // HttpResponse/completion is done at that point: the graceful shutdown can wait forever on a
        // lingering (SSE/keep-alive) connection of the test server, so abort the selector first.
        delegate.shutdownNow();
        delegate.close();
    }

    @BeforeEach
    void boot(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Test
    void parsesExaTextBlocks() throws Exception {
        try (final var mock = Mock.start(mapper).payload(exaPayload())) {
            final var manager = manager(mapper, singleProvider(WebSearchProvider.EXA, mock.url()));
            final var results = manager.search("apache maven build tool", 8);
            assertEquals(2, results.size());
            final var first = results.get(0);
            assertEquals("Apache Maven", first.title());
            assertEquals("https://maven.apache.org/", first.url());
            assertEquals("Maven is a build tool.", first.snippet());
            assertNull(first.published());
            final var second = results.get(1);
            assertEquals("Maven Central", second.title());
            assertEquals("https://central.sonatype.com/", second.url());
            assertEquals("Repository search.", second.snippet());
            assertEquals("2026-01-02", second.published());
        }
    }

    @Test
    void parsesParallelPayload() throws Exception {
        try (final var mock = Mock.start(mapper).payload(parallelPayload())) {
            final var manager = manager(mapper, singleProvider(WebSearchProvider.PARALLEL, mock.url()));
            final var results = manager.search("apache maven build tool", 8);
            assertEquals(2, results.size());
            assertEquals("First", results.get(0).title());
            assertEquals("https://example.com/a", results.get(0).url());
            assertEquals("part one\n\npart two", results.get(0).snippet());
            assertEquals("2026-06-30", results.get(0).published());
            assertEquals("Second", results.get(1).title());
            assertEquals("https://example.com/b", results.get(1).url());
            assertEquals("", results.get(1).snippet());
            assertNull(results.get(1).published());
        }
    }

    @Test
    void parsesFirecrawlPayload() throws Exception {
        try (final var mock = Mock.start(mapper).payload(firecrawlPayload())) {
            final var manager = manager(mapper, singleProvider(WebSearchProvider.FIRECRAWL, mock.url()));
            final var results = manager.search("apache maven build tool", 8);
            assertEquals(1, results.size());
            assertEquals("Example", results.get(0).title());
            assertEquals("https://example.com", results.get(0).url());
            assertEquals("Example domain", results.get(0).snippet());
            assertNull(results.get(0).published());
        }
    }

    @Test
    void parsesTinyfishPayload() throws Exception {
        try (final var mock = Mock.start(mapper).payload(tinyfishPayload())) {
            final var manager = manager(mapper, singleProvider(WebSearchProvider.TINYFISH, mock.url()));
            final var results = manager.search("apache maven build tool", 8);
            assertEquals(1, results.size());
            assertEquals("Example Title", results.get(0).title());
            assertEquals("https://example.com", results.get(0).url());
            assertEquals("Snippet text", results.get(0).snippet());
            assertNull(results.get(0).published());
        }
    }

    @Test
    void rawTextIsKeptWhenPayloadIsNotJson() throws Exception {
        try (final var mock = Mock.start(mapper).payload("definitely not json")) {
            final var manager = manager(mapper, singleProvider(WebSearchProvider.PARALLEL, mock.url()));
            final var results = manager.search("apache maven build tool", 8);
            assertEquals(1, results.size());
            assertEquals("", results.get(0).title());
            assertEquals("", results.get(0).url());
            assertEquals("definitely not json", results.get(0).snippet());
        }
    }

    @Test
    void fallsBackToNextProviderOnError() throws Exception {
        try (final var failing = Mock.start(mapper).error(500, null);
                final var healthy = Mock.start(mapper).payload(parallelPayload())) {
            final var manager = manager(
                    mapper,
                    twoProviders(WebSearchProvider.EXA, failing.url(), WebSearchProvider.PARALLEL, healthy.url()));
            assertEquals(2, manager.search("apache maven build tool", 8).size());
            assertEquals(1, failing.calls.size());
            assertEquals(1, healthy.calls.size());
        }
    }

    @Test
    void fallsBackWhenFirecrawlReportsFailure() throws Exception {
        try (final var failing = Mock.start(mapper).payload("{\"success\":false,\"data\":null}");
                final var healthy = Mock.start(mapper).payload(tinyfishPayload())) {
            final var manager = manager(
                    mapper,
                    twoProviders(
                            WebSearchProvider.FIRECRAWL, failing.url(), WebSearchProvider.TINYFISH, healthy.url()));
            assertEquals(1, manager.search("apache maven build tool", 8).size());
            assertEquals(1, failing.calls.size());
            assertEquals(1, healthy.calls.size());
        }
    }

    @Test
    void rateLimitedProviderIsCooledDownAndRetried() throws Exception {
        try (final var limited = Mock.start(mapper).error(429, "2");
                final var healthy = Mock.start(mapper).payload(parallelPayload())) {
            final var now = new long[] {1_000_000};
            final var manager = new WebSearchManager(
                    mapper,
                    twoProviders(WebSearchProvider.EXA, limited.url(), WebSearchProvider.PARALLEL, healthy.url()),
                    client,
                    name -> null,
                    () -> now[0]);
            assertEquals(2, manager.search("apache maven build tool", 8).size());
            assertEquals(1, limited.calls.size());
            // cooldown active: the rate limited provider is not called again
            assertEquals(2, manager.search("apache maven build tool", 8).size());
            assertEquals(1, limited.calls.size());
            assertEquals(2, healthy.calls.size());
            // cooldown expired (retry-after: 2s): the provider is retried and re-limited
            now[0] += 3_000;
            assertEquals(2, manager.search("apache maven build tool", 8).size());
            assertEquals(2, limited.calls.size());
            assertEquals(3, healthy.calls.size());
        }
    }

    @Test
    void disabledProvidersAreSkipped() throws Exception {
        try (final var disabled = Mock.start(mapper).payload(parallelPayload());
                final var active = Mock.start(mapper).payload(tinyfishPayload())) {
            final var manager = manager(
                    mapper,
                    providers(List.of(
                            new WebSearchProviderConfiguration(
                                    WebSearchProvider.EXA, false, disabled.url(), null, List.of()),
                            new WebSearchProviderConfiguration(
                                    WebSearchProvider.TINYFISH, true, active.url(), null, List.of()))));
            assertEquals(1, manager.search("apache maven build tool", 8).size());
            assertTrue(disabled.headers.isEmpty());
            assertEquals(1, active.calls.size());
        }
    }

    @Test
    void tinyfishAnonymousRequestsAreKeyless() throws Exception {
        try (final var mock = Mock.start(mapper).payload(tinyfishPayload())) {
            final var manager = manager(mapper, singleProvider(WebSearchProvider.TINYFISH, mock.url()));
            assertEquals(1, manager.search("apache maven build tool", 8).size());
            assertEquals("keyless", mock.header("X-TinyFish-Access-Mode"));
            assertNull(mock.header("X-API-Key"));
            assertNull(mock.header("Authorization"));
        }
    }

    @Test
    void tinyfishApiKeyUsesHeaderAndNoKeyless() throws Exception {
        try (final var mock = Mock.start(mapper).payload(tinyfishPayload())) {
            final var manager = manager(
                    mapper,
                    singleProvider(WebSearchProvider.TINYFISH, mock.url()),
                    name -> "TINYFISH_API_KEY".equals(name) ? "secret" : null);
            assertEquals(1, manager.search("apache maven build tool", 8).size());
            assertEquals("secret", mock.header("X-API-Key"));
            assertNull(mock.header("X-TinyFish-Access-Mode"));
            assertNull(mock.header("Authorization"));
        }
    }

    @Test
    void exaApiKeyIsUrlParameterNotBearer() throws Exception {
        try (final var mock = Mock.start(mapper).payload(exaPayload())) {
            final var manager = manager(mapper, singleProvider(WebSearchProvider.EXA, mock.url(), "secret"));
            assertEquals(2, manager.search("apache maven build tool", 8).size());
            assertTrue(mock.paths.stream().anyMatch(p -> p.contains("exaApiKey=secret")));
            assertNull(mock.header("Authorization"));
        }
    }

    @Test
    void parallelApiKeyUsesBearer() throws Exception {
        try (final var mock = Mock.start(mapper).payload(parallelPayload())) {
            final var env = new AtomicInteger();
            final Function<String, String> envFunction = name -> {
                env.incrementAndGet();
                return "PARALLEL_API_KEY".equals(name) ? "secret" : null;
            };
            final var manager = new WebSearchManager(
                    mapper,
                    singleProvider(WebSearchProvider.PARALLEL, mock.url()),
                    client,
                    envFunction,
                    System::currentTimeMillis);
            assertEquals(2, manager.search("apache maven build tool", 8).size());
            assertEquals("Bearer secret", mock.header("Authorization"));
            // the environment is read once, when the client is built, not on every search
            assertEquals(2, manager.search("apache maven build tool", 8).size());
            assertEquals(1, env.get());
        }
    }

    @Test
    void firecrawlApiKeyUsesBearer() throws Exception {
        try (final var mock = Mock.start(mapper).payload(firecrawlPayload())) {
            final var manager = manager(
                    mapper,
                    singleProvider(WebSearchProvider.FIRECRAWL, mock.url()),
                    name -> "FIRECRAWL_API_KEY".equals(name) ? "secret" : null);
            assertEquals(1, manager.search("apache maven build tool", 8).size());
            assertEquals("Bearer secret", mock.header("Authorization"));
        }
    }

    @Test
    void numResultsMapToProviderArguments() throws Exception {
        try (final var exa = Mock.start(mapper).payload(exaPayload());
                final var firecrawl = Mock.start(mapper).payload(firecrawlPayload())) {
            manager(mapper, singleProvider(WebSearchProvider.EXA, exa.url())).search("maven", 5);
            manager(mapper, singleProvider(WebSearchProvider.FIRECRAWL, firecrawl.url()))
                    .search("maven", 5);
            assertTrue(exa.call().contains("\"numResults\":5"));
            assertTrue(firecrawl.call().contains("\"limit\":5"));
        }
    }

    @Test
    void noProviderEnabledFailsFast() throws Exception {
        final var manager = manager(mapper, providers(List.of()));
        assertFalse(manager.hasEnabledProviders());
        final var error = assertThrows(IOException.class, () -> manager.search("apache maven build tool", 8));
        assertTrue(error.getMessage().contains("No web search provider is enabled"));
    }

    private WebSearchManager manager(final JsonMapper mapper, final CodingAgentConfiguration configuration) {
        return manager(mapper, configuration, name -> null);
    }

    private WebSearchManager manager(
            final JsonMapper mapper,
            final CodingAgentConfiguration configuration,
            final Function<String, String> environment) {
        return new WebSearchManager(mapper, configuration, client, environment, System::currentTimeMillis);
    }

    private CodingAgentConfiguration singleProvider(final WebSearchProvider provider, final String url) {
        return singleProvider(provider, url, null);
    }

    private CodingAgentConfiguration singleProvider(
            final WebSearchProvider provider, final String url, final String apiKey) {
        return providers(List.of(new WebSearchProviderConfiguration(provider, true, url, apiKey, List.of())));
    }

    private CodingAgentConfiguration twoProviders(
            final WebSearchProvider first,
            final String firstUrl,
            final WebSearchProvider second,
            final String secondUrl) {
        return providers(List.of(
                new WebSearchProviderConfiguration(first, true, firstUrl, null, List.of()),
                new WebSearchProviderConfiguration(second, true, secondUrl, null, List.of())));
    }

    private CodingAgentConfiguration providers(final List<WebSearchProviderConfiguration> providers) {
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
                                providers,
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

    private static String exaPayload() {
        return "Title: Apache Maven\n"
                + "URL: https://maven.apache.org/\n"
                + "Published: N/A\n"
                + "Author: Someone\n"
                + "Highlights:\n"
                + "Maven is a build tool.\n"
                + "\n"
                + "---\n"
                + "\n"
                + "Title: Maven Central\n"
                + "URL: https://central.sonatype.com/\n"
                + "Published: 2026-01-02\n"
                + "Author: Other\n"
                + "Text:\n"
                + "Repository search.";
    }

    private static String parallelPayload() {
        return "{\"search_id\":\"s1\",\"results\":[{\"url\":\"https://example.com/a\",\"title\":\"First\",\"publish_date\":\"2026-06-30\",\"excerpts\":[\"part"
                + " one\",\"part two\"]},"
                + "{\"url\":\"https://example.com/b\",\"title\":\"Second\",\"publish_date\":null,\"excerpts\":[]}]}";
    }

    private static String firecrawlPayload() {
        return "{\"success\":true,\"data\":{\"web\":[{\"url\":\"https://example.com\",\"title\":\"Example\",\"description\":\"Example"
                + " domain\",\"position\":1}]}}";
    }

    private static String tinyfishPayload() {
        return "{\"query\":\"apache"
                + " maven\",\"results\":[{\"position\":1,\"site_name\":\"Example\",\"snippet\":\"Snippet"
                + " text\",\"title\":\"Example Title\",\"url\":\"https://example.com\"}],\"total_results\":1,\"page\":1}";
    }

    /** Minimal MCP endpoint: initialize + notifications succeed, tools/call answer is configurable. */
    private static final class Mock implements AutoCloseable {
        private final HttpServer server;
        private final JsonMapper mapper;
        private final List<Map<String, List<String>>> headers = new CopyOnWriteArrayList<>();
        private final List<String> paths = new CopyOnWriteArrayList<>();
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private volatile String payload = "{}";
        private volatile int status = 200;
        private volatile String retryAfter;

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

        Mock error(final int status, final String retryAfter) {
            this.status = status;
            this.retryAfter = retryAfter;
            return this;
        }

        String header(final String name) {
            return headers.stream()
                    .flatMap(h -> h.entrySet().stream())
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .flatMap(e -> e.getValue().stream())
                    .findFirst()
                    .orElse(null);
        }

        String call() {
            return calls.isEmpty() ? "" : calls.get(calls.size() - 1);
        }

        private void handle(final HttpExchange exchange) throws IOException {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            headers.add(exchange.getRequestHeaders());
            paths.add(exchange.getRequestURI().toString());
            if (body.contains("\"method\":\"initialize\"")) {
                respond(
                        exchange,
                        200,
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"serverInfo\":{\"name\":\"mock\",\"version\":\"1\"}}}",
                        null);
            } else if (body.contains("\"method\":\"notifications/initialized\"")) {
                exchange.getResponseHeaders().set("content-type", "application/json");
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
            } else { // tools/call: the only other method the search manager sends
                calls.add(body);
                if (status >= 400) {
                    respond(exchange, status, "{\"error\":\"boom\"}", retryAfter);
                } else {
                    respond(
                            exchange,
                            200,
                            mapper.toString(Map.of(
                                    "jsonrpc",
                                    "2.0",
                                    "id",
                                    ((Map<?, ?>) mapper.fromString(Object.class, body)).get("id"),
                                    "result",
                                    Map.of("content", List.of(Map.of("type", "text", "text", payload))))),
                            null);
                }
            }
        }

        private static void respond(
                final HttpExchange exchange, final int status, final String body, final String retryAfter)
                throws IOException {
            if (retryAfter != null) {
                exchange.getResponseHeaders().set("retry-after", retryAfter);
            }
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
}
