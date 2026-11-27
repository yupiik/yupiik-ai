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
package io.yupiik.ia.agent.core.codegraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.codegraph.index.CodeGraphIndexWriter;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.codegraph.index.CodeGraphIndex;
import io.yupiik.ia.agent.core.codegraph.index.GraphToIndex;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.NativeProviderConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.optimize.AfterToolExecutionEvent;
import io.yupiik.ia.agent.core.wiring.HttpClientProducer;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

@ExtendWith(TestJsonMapper.class)
@ResourceLock("java.util.logging")
class StructuralCodeGraphTest {
    @TempDir
    private static Path testWorkdir;

    private static final long TIMEOUT_MS = Duration.ofSeconds(5).toMillis();

    @TempDir
    Path temp;

    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    private final List<ChatClient> chatClients = new ArrayList<>();
    private final List<StructuralCodeGraph> graphs = new ArrayList<>();
    private final List<ScheduledExecutorService> schedulers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (var g : graphs) {
            try {
                g.close();
            } catch (final Exception ignored) {
                // no-op
            }
        }
        graphs.clear();
        schedulers.forEach(ScheduledExecutorService::shutdownNow);
        schedulers.clear();
        chatClients.forEach(ChatClient::close);
        chatClients.clear();
        httpClients.forEach(ExtendedHttpClient::close);
        httpClients.clear();
    }

    private StructuralCodeGraph newGraph(final CodingAgentConfiguration config, final JsonMapper mapper) {
        return newGraph(config, mapper, List.of(new JavaGraphParser()));
    }

    private StructuralCodeGraph newGraph(
            final CodingAgentConfiguration config, final JsonMapper mapper, final List<GraphParser> parsers) {
        schedulers.add(Executors.newSingleThreadScheduledExecutor(r -> {
            final var t = new Thread(r, "yca-graph-test");
            t.setDaemon(true);
            return t;
        }));
        final var graph = new StructuralCodeGraph(
                config,
                parsers,
                mapper,
                chatClient(config, mapper),
                providerManager(config, mapper),
                Clock.systemUTC(),
                schedulers.get(schedulers.size() - 1));
        graphs.add(graph);
        return graph;
    }

    /** Reads the completed build for the explicit project root, never its surrounding temporary directory. */
    private GraphStore awaitNonEmpty(final StructuralCodeGraph graph, final Path root) {
        graph.ensureReady(root);
        return graph.graph(root);
    }

    private final List<ExtendedHttpClient> httpClients = new ArrayList<>();

    private ChatClient chatClient(final CodingAgentConfiguration config, final JsonMapper mapper) {
        final var httpClient = new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null));
        httpClients.add(httpClient);
        final var client =
                new ChatClient(config, mapper, providerManager(config, mapper), Clock.systemUTC(), httpClient);
        chatClients.add(client);
        return client;
    }

    @Test
    void perRootCacheIsBoundedByLru() {
        final var cache = new StructuralCodeGraph.RootIndexCache(2);
        final var a = new StructuralCodeGraph.RootCacheEntry(null, Map.of("a", 1L), 1L);
        final var b = new StructuralCodeGraph.RootCacheEntry(null, Map.of("b", 2L), 2L);
        final var c = new StructuralCodeGraph.RootCacheEntry(null, Map.of("c", 3L), 3L);
        cache.put(Path.of("/r/a"), a);
        cache.put(Path.of("/r/b"), b);
        // access /a so /b becomes the least-recently used
        assertEquals(a, cache.get(Path.of("/r/a")));
        cache.put(Path.of("/r/c"), c);
        assertEquals(2, cache.size(), "cache must stay within its bound");
        assertNotNull(cache.get(Path.of("/r/a")));
        assertNotNull(cache.get(Path.of("/r/c")));
        assertNull(cache.get(Path.of("/r/b")), "the least-recently used entry must be evicted");
    }

    /**
     * A non-javac, batch-capable parser stand-in (not {@code instanceof JavaGraphParser}) so {@code useBatchRefresh()}
     * is true and edits are routed through the debounced batch path. It records every {@code parseBatch} input so a test
     * can assert non-Java files never reach the java batch.
     */
    private static final class CapturingBatchParser implements GraphParser {
        private final List<Path> batchInputs = new CopyOnWriteArrayList<>();

        @Override
        public Set<String> extensions() {
            return Set.of("java");
        }

        @Override
        public GraphModel parse(final Path file) {
            return modelFor(file);
        }

        @Override
        public Map<Path, GraphModel> parseBatch(final List<Path> files) {
            batchInputs.addAll(files);
            final var out = new HashMap<Path, GraphModel>(files.size());
            for (final var file : files) {
                out.put(file, modelFor(file));
            }
            return out;
        }

        /** Deterministic stand-in model: one TYPE symbol per file so the graph build is non-empty. */
        private static GraphModel modelFor(final Path file) {
            final var name = file.getFileName().toString();
            final var className = name.endsWith(".java") ? name.substring(0, name.length() - ".java".length()) : name;
            return new GraphModel(
                    "java",
                    List.of(new GraphModel.Symbol(className, GraphModel.Kind.TYPE, name, 1, 1, className)),
                    List.of());
        }
    }

    /**
     * Deterministic LLM double: no network, scripted per prompt marker.
     */
    private static final class FakeChatClient extends ChatClient {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResult chat(final ChatCompletionRequest request) {
            calls.incrementAndGet();
            final var prompt = request.messages() == null || request.messages().isEmpty()
                    ? ""
                    : String.valueOf(request.messages().get(0).content());
            if (prompt.startsWith("You give a fellow developer one-line notes")) {
                return new ChatResult(
                        Message.assistant("summary: does addition\ncrux: careful with overflow", null), null);
            }
            if (prompt.contains("Return ONLY concise JSON")) {
                return new ChatResult(
                        Message.assistant(
                                "{\"concepts\":[{\"name\":\"Parsing\",\"slug\":\"parsing\",\"type\":\"feature\",\"summary\":\"Groups"
                                        + " the demo file into a parsing node.\",\"sources\":[\"docs/Foo.java.md\"],"
                                        + "\"links\":[{\"to\":\"docs\",\"relation\":\"DEPENDS_ON\",\"description\":\"related\"}]}]}",
                                null),
                        null);
            }
            return new ChatResult(Message.assistant("One file summary text.", null), null);
        }
    }

    private static Map<String, String> graphSettings(final Map<String, String> structuralOverrides) {
        final var map = new HashMap<String, String>();
        map.put("yca.provider.providers.length", "1");
        map.put("yca.provider.providers.0.id", "default");
        map.put("yca.provider.providers.0.baseUrl", "https://api.openai.com/v1/chat/completions");
        map.put("yca.provider.providers.0.apiKey", "");
        map.put("yca.provider.providers.0.model", "test-model");
        map.put("yca.provider.providers.0.api", "auto");
        map.put("yca.provider.providers.0.contextWindow", "128000");
        map.put("yca.provider.providers.0.timeoutSeconds", "120");
        map.put("yca.llm.retriesMaxAttempts", "3");
        map.put("yca.llm.retriesBaseDelayMs", "500");
        map.put("yca.llm.rateLimitRequestsPerMinute", "0");
        map.put("yca.llm.maxConcurrent", "4");
        map.put("yca.llm.retryOnContextOverflow", "true");
        map.put("yca.llm.maxIterations", "250");
        map.put("yca.structuralCodeGraph.maxFileBytes", String.valueOf(10_000_000));
        // no background WatchService in tests: its keys would still pin the @TempDir when JUnit
        // deletes it, and these tests do not need live external file watching
        map.put("yca.structuralCodeGraph.watch", "false");
        map.putAll(structuralOverrides);
        return map;
    }

    private static ProviderManager providerManager(final CodingAgentConfiguration cfg, final JsonMapper mapper) {
        return new ProviderManager(cfg, mapper);
    }

    private static CodingAgentConfiguration withProject(final CodingAgentConfiguration baseline, final Path project) {
        return new CodingAgentConfiguration(
                project.toString(),
                baseline.home(),
                project.toString(),
                project.toString(),
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
                baseline.tools(),
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

    @Nested
    @FusionSupport(modules = DefaultGraphModule.class)
    class DefaultGraph {
        @Test
        void readinessStartsInBackgroundAndGraphQueryWaitsForParserAvailability(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var project = Files.createDirectory(temp.resolve("blocked-parser"));
            Files.writeString(project.resolve("pom.xml"), "<project/>");
            Files.writeString(project.resolve("Foo.java"), "package demo; public class Foo {}\n");
            final var entered = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            final var delegate = new JavaGraphParser();
            final GraphParser blocked = new GraphParser() {
                @Override
                public Set<String> extensions() {
                    return delegate.extensions();
                }

                @Override
                public boolean available() {
                    entered.countDown();
                    try {
                        return release.await(10, TimeUnit.SECONDS) && delegate.available();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }

                @Override
                public GraphModel parse(final Path file) {
                    return delegate.parse(file);
                }

                @Override
                public Map<Path, GraphModel> parseBatch(final List<Path> files) {
                    return delegate.parseBatch(files);
                }
            };
            final var graph = newGraph(withProject(configuration, project), mapper, List.of(blocked));
            final var started = System.nanoTime();
            graph.ensureReadyAsync(project);
            assertTrue(
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500,
                    "readiness must not probe inline");
            assertTrue(entered.await(2, TimeUnit.SECONDS), "background availability check did not start");
            final var query = Executors.newSingleThreadExecutor();
            try {
                final Future<GraphStore> result = query.submit(() -> graph.graph(project));
                Thread.sleep(100);
                assertFalse(result.isDone(), "graph query must wait for readiness");
                release.countDown();
                assertTrue(result.get(15, TimeUnit.SECONDS).symbols().stream()
                        .anyMatch(s -> s.fqn().equals("demo.Foo")));
            } finally {
                release.countDown();
                query.shutdownNow();
            }
        }

        @Test
        void queriesPerSessionRootIndependently(@Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var project = Files.createDirectory(temp.resolve("project"));
            Files.writeString(project.resolve("pom.xml"), "<project/>");
            Files.writeString(project.resolve("Foo.java"), """
                package demo;
                public class Foo {
                    public void a() {}
                }
                """);

            // a second, independent project the web may point another session at
            final var second = Files.createDirectory(temp.resolve("second"));
            Files.writeString(second.resolve("pom.xml"), "<project/>");
            Files.writeString(second.resolve("Baz.java"), """
                package other;
                public class Baz {
                    public void b() {}
                }
                """);

            final var config = withProject(configuration, project);
            final var graph = newGraph(config, mapper);

            // the default root serves its own graph
            graph.ensureReady(config.resolveWorkingDir());
            final var defaultIdx = graph.queryIndex(config.resolveWorkingDir());
            assertTrue(defaultIdx != null && defaultIdx.resolve("demo.Foo") != null);
            assertNull(defaultIdx.resolve("other.Baz"), "default root must not know the second project");

            // a per-session root resolves its own symbols lazily, independently of the default graph
            final var secondIdx = graph.queryIndex(second);
            assertNotNull(secondIdx, "expected a lazily built per-root index");
            assertNotNull(secondIdx.resolve("other.Baz"), "second root should resolve its own symbol");
            assertNull(secondIdx.resolve("demo.Foo"), "second root must not contain the default project's symbol");

            assertEquals("Baz.java", graph.rel(second, second.resolve("Baz.java")));
        }

        @Test
        void perSessionRootReflectsEditsTouchedFilesOnNextQuery(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var project = Files.createDirectory(temp.resolve("project"));
            Files.writeString(project.resolve("pom.xml"), "<project/>");
            Files.writeString(
                    project.resolve("root-only.java"),
                    "package demo;\npublic class RootOnly { public void run() {} }\n");

            final var config = withProject(configuration, project);
            final var graph = newGraph(config, mapper);

            // a distinct per-session root (web could point a session here)
            final var work = Files.createDirectory(temp.resolve("work"));
            Files.writeString(work.resolve("pom.xml"), "<project/>");
            Files.writeString(work.resolve("Foo.java"), """
                package work;
                public class Foo {
                    public void a() {}
                }
                """);

            // first query builds the per-root index lazily
            final var idx1 = graph.queryIndex(work);
            assertNotNull(idx1, "expected a lazily built per-root index");
            assertNotNull(idx1.resolve("work.Foo"), "first query should resolve the freshly built symbol");
            assertNull(idx1.resolve("work.Baz"), "Baz not yet present");

            // a later edit under the root must be visible on the NEXT query (refresh-on-access),
            // not served from an ever-stale snapshot. Force a distinct mtime to be filesystem-agnostic.
            final var foo = work.resolve("Foo.java");
            Files.writeString(foo, """
                package work;
                public class Foo {
                    public void a() {}
                    public void baz() {}
                }
                """);
            Files.setLastModifiedTime(foo, FileTime.fromMillis(System.currentTimeMillis() + 5000));
            final var idx2 = graph.queryIndex(work);
            assertNotNull(idx2.resolve("work.Foo.baz"), "edited symbol must be visible after refresh-on-access");
        }

        @Test
        void perSessionRootTracksAddedAndDeletedFiles(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var project = Files.createDirectory(temp.resolve("project"));
            Files.writeString(project.resolve("pom.xml"), "<project/>");
            Files.writeString(
                    project.resolve("Foo.java"), "package demo;\npublic class Foo { public void run() {} }\n");

            final var config = withProject(configuration, project);
            final var graph = newGraph(config, mapper);

            final var work = Files.createDirectory(temp.resolve("work"));
            Files.writeString(work.resolve("Bar.java"), "package work;\npublic class Bar { public void run() {} }\n");
            Files.writeString(work.resolve("pom.xml"), "<project/>");

            final var idx1 = graph.queryIndex(work);
            assertNotNull(idx1.resolve("work.Bar"));
            assertNull(idx1.resolve("work.NewBar"));

            // adding an indexed file offline changes the file count -> next query re-indexes
            Files.writeString(
                    work.resolve("NewBar.java"), "package work;\npublic class NewBar { public void run() {} }\n");
            final var idx2 = graph.queryIndex(work);
            assertNotNull(idx2.resolve("work.NewBar"), "a file added offline must be indexed on the next query");

            // deleting an indexed file offline changes the count -> next query drops it
            Files.delete(work.resolve("Bar.java"));
            final var idx3 = graph.queryIndex(work);
            assertNull(idx3.resolve("work.Bar"), "a deleted file must be dropped on the next query");
            assertNotNull(idx3.resolve("work.NewBar"), "the surviving file must still be indexed");
        }

        @Test
        void perSessionRootResyncForcesFreshIndex(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var project = Files.createDirectory(temp.resolve("project"));
            Files.writeString(project.resolve("pom.xml"), "<project/>");
            Files.writeString(
                    project.resolve("Foo.java"), "package demo;\npublic class Foo { public void run() {} }\n");

            final var config = withProject(configuration, project);
            final var graph = newGraph(config, mapper);

            final var work = Files.createDirectory(temp.resolve("work"));
            Files.writeString(work.resolve("pom.xml"), "<project/>");
            Files.writeString(work.resolve("A.java"), "package work;\npublic class A { public void run() {} }\n");

            final var idx1 = graph.queryIndex(work);
            assertNotNull(idx1.resolve("work.A"));
            assertNull(idx1.resolve("work.B"));

            // resync(root) must re-index the per-session root regardless of the mtime freshness gate
            Files.writeString(work.resolve("B.java"), "package work;\npublic class B { public void run() {} }\n");
            final var status = graph.resync(work);
            assertTrue(status.contains("symbols="), "resync(root) should report a symbol count");
            final var idx2 = graph.queryIndex(work);
            assertTrue(idx2.symbolCount() >= 2, "resync(root) must surface newly added files");
            assertNotNull(idx2.resolve("work.B"));
        }

        @Test
        void buildsRefreshAndPersists(@Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var project = Files.createDirectory(temp.resolve("project"));
            Files.writeString(project.resolve("pom.xml"), "<project/>");
            Files.writeString(project.resolve("Foo.java"), """
                package demo;
                public class Foo {
                    public int add(int a, int b) { return a + b; }
                }
                """);
            Files.createDirectory(project.resolve("other"));
            Files.writeString(project.resolve("other/Bar.java"), """
                package demo.sub;
                public class Bar {
                    public void go() { new demo.Foo().add(1, 2); }
                }
                """);

            final var config = withProject(configuration, project);

            final var graph = newGraph(config, mapper);

            graph.ensureReady(config.resolveWorkingDir());
            graph.flushPersist(config.resolveWorkingDir());
            final var built = awaitNonEmpty(graph, project);
            assertEquals(4, built.symbols().size(), "expected Foo + Foo.add + Bar + Bar.go symbols");
            final var types = built.symbols().stream()
                    .filter(s -> s.kind() == GraphModel.Kind.TYPE)
                    .toList();
            assertEquals(2, types.size());
            assertTrue(built.edges().stream().anyMatch(e -> e.type() == GraphModel.EdgeType.CALLS));

            // symbols must carry populated start/end line values
            assertTrue(
                    built.symbols().stream().allMatch(s -> s.startLine() > 0),
                    "expected each symbol to carry a start line");
            assertTrue(
                    built.symbols().stream().allMatch(s -> s.endLine() >= s.startLine()),
                    "expected each symbol end line to not precede its start line");

            // store must have been persisted as the canonical compact binary index
            final var indexFile = project.resolve(".yca/generated/graph/index.bin");
            assertTrue(Files.isRegularFile(indexFile));

            // the hidden per-symbol wiring graph must be written alongside, byte-stable and sorted,
            // with graft-style path-scoped node ids and enrichment state
            final var wiringFile = project.resolve(".yca/generated/graph/.graph/wiring.json");
            assertTrue(Files.isRegularFile(wiringFile), "expected hidden .graph/wiring.json");
            final var wiring = Files.readString(wiringFile);
            assertTrue(wiring.contains("\"nodes\""), "expected wiring nodes array");
            assertTrue(wiring.contains("\"edges\""), "expected wiring edges array");
            assertTrue(wiring.contains("Foo.java#add"), "expected a path-scoped wiring node id");
            assertTrue(wiring.contains("\"summary_state\":\"pending\""), "expected a pending summary state");
            assertTrue(wiring.contains("CALLS"), "expected an edge relation in wiring graph");
            // deterministic: a no-change resync must not rewrite (and thus not churn) the wiring graph
            graph.resync(config.resolveWorkingDir());
            assertEquals(wiring, Files.readString(wiringFile), "wiring.json must be byte-stable across builds");

            // per-source-file doc tree must mirror the source paths, at the store root, extension stripped
            final var docsFoo = project.resolve(".yca/generated/graph/Foo.md");
            assertTrue(Files.isRegularFile(docsFoo), "expected doc tree to mirror Foo.java");
            final var fooDoc = Files.readString(docsFoo);
            assertTrue(fooDoc.contains("# Foo.java"), "expected source-path header in Foo doc");
            assertTrue(
                    fooDoc.contains("demo.Foo.add") || fooDoc.contains("- add · method"),
                    "expected method symbol in Foo doc");
            final var docsBar = project.resolve(".yca/generated/graph/other/Bar.md");
            assertTrue(Files.isRegularFile(docsBar), "expected doc tree to mirror other/Bar.java");
            assertTrue(Files.readString(docsBar).contains("# other/Bar.java"));

            // refresh: mutating a file removes its old symbols and adds new ones
            Files.writeString(project.resolve("Foo.java"), """
                package demo;
                public class Foo {
                    public int add(int a, int b) { return a + b; }
                    public int sub(int a, int b) { return a - b; }
                }
                """);
            graph.refreshFile(project.resolve("Foo.java"));
            final var after = graph.graph(config.resolveWorkingDir());
            assertTrue(
                    after.symbols().stream().anyMatch(s -> s.fqn().equals("demo.Foo.sub")),
                    "expected the added method symbol after refresh");
            assertTrue(
                    Files.readString(docsFoo).contains("- sub · method"),
                    "expected the refreshed doc to contain the added method");

            // deleting a source file removes its doc and its symbols
            Files.delete(project.resolve("Foo.java"));
            graph.refreshFile(project.resolve("Foo.java"));
            assertFalse(Files.exists(docsFoo), "expected doc removed when source file deleted");
            final var afterDelete = graph.graph(config.resolveWorkingDir());
            assertTrue(
                    afterDelete.symbols().stream().noneMatch(s -> s.fqn().startsWith("demo.Foo")),
                    "deleted file symbols must be dropped by the refresh");

            // reload from disk must match in-memory state
            final var reloaded = newGraph(config, mapper);
            reloaded.ensureReady(project);
            assertEquals(
                    afterDelete.symbols().size(),
                    awaitNonEmpty(reloaded, project).symbols().size());
        }

        @Test
        void refreshIgnoresFilesOutsideProjectDir(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var project = Files.createDirectory(temp.resolve("project"));
            Files.writeString(project.resolve("pom.xml"), "<project/>");
            Files.writeString(
                    project.resolve("Foo.java"), "package demo;\npublic class Foo { public void run() {} }\n");
            final var outside = Files.createDirectory(temp.resolve("outside"));
            Files.writeString(
                    outside.resolve("Bar.java"), "package outside;\npublic class Bar { public void run() {} }\n");

            final var config = withProject(configuration, project);
            final var graph = newGraph(config, mapper);
            graph.ensureReady(config.resolveWorkingDir());
            assertEquals(2, awaitNonEmpty(graph, project).symbols().size());

            graph.refreshFile(outside.resolve("Bar.java"));
            assertTrue(
                    graph.graph(config.resolveWorkingDir()).symbols().stream()
                            .noneMatch(s -> s.fqn().startsWith("outside")),
                    "a file outside the project dir must not be indexed");
            assertEquals(
                    2,
                    graph.graph(config.resolveWorkingDir()).symbols().size(),
                    "refreshing an outside file must not change the graph");

            // any nested level inside the project dir is still indexed
            Files.createDirectory(project.resolve("nested"));
            Files.writeString(
                    project.resolve("nested/Baz.java"), "package nested;\npublic class Baz { public void run() {} }\n");
            graph.refreshFile(project.resolve("nested/Baz.java"));
            assertTrue(
                    graph.graph(config.resolveWorkingDir()).symbols().stream()
                            .anyMatch(s -> s.fqn().equals("nested.Baz")),
                    "a nested file inside the project dir must be indexed");
        }

        @Test
        void skipsIndexingForUnrecognizedProjectDir(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            // A directory with indexable sources but no project marker (pom.xml, local configuration, README, ...)
            // is not a recognized project: it must not be scanned/indexed.
            final var project = Files.createDirectory(temp.resolve("plain"));
            Files.writeString(
                    project.resolve("Foo.java"), "package demo;\npublic class Foo { public void run() {} }\n");
            final var config = withProject(configuration, project);
            final var graph = newGraph(config, mapper);
            graph.ensureReady(config.resolveWorkingDir());
            assertTrue(
                    graph.graph(config.resolveWorkingDir()).symbols().isEmpty(),
                    "a directory without a project marker must not be indexed into a structural code graph");
        }

        @Test
        void metadataDoesNotOptParentWorkspaceIntoIndexing(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var parent = Files.createDirectory(temp.resolve("workspace"));
            final var metadata = Files.createDirectories(parent.resolve(".yca/memory"));
            Files.writeString(metadata.resolve("notes.md"), "keep this memory");
            Files.writeString(parent.resolve(".yca/java-location"), "probe result");
            final var child = Files.createDirectory(parent.resolve("child"));
            Files.writeString(child.resolve("pom.xml"), "<project/>");
            Files.createDirectories(child.resolve(".yca"));
            Files.writeString(child.resolve(".yca/configuration.json"), "{}");
            final var source = Files.writeString(child.resolve("Foo.java"), "public class Foo {}\n");
            final var parser = new CapturingBatchParser();
            final var graph = newGraph(withProject(configuration, parent), mapper, List.of(parser));
            graph.ensureReadyAsync(parent);
            graph.ensureReady(parent);
            graph.ensureRefreshedReady(parent);
            graph.refreshFile(source);
            graph.onToolExecuted(new AfterToolExecutionEvent(
                    "write_file", true, "ok", "test", null, null, Map.of("path", "child/Foo.java"), parent));
            graph.ensureRefreshedReady(parent);
            assertTrue(graph.graph(parent).symbols().isEmpty());
            assertEquals(0, graph.queryIndex(parent).symbolCount());
            assertTrue(graph.resync(parent).startsWith("not a recognized project:"));
            graph.flushPersist(parent);
            graph.close();
            assertTrue(parser.batchInputs.isEmpty());
            assertFalse(Files.exists(parent.resolve(".yca/generated/graph")));
            assertEquals("keep this memory", Files.readString(metadata.resolve("notes.md")));
            assertEquals("probe result", Files.readString(parent.resolve(".yca/java-location")));

            final var otherGraph = newGraph(withProject(configuration, child), mapper, List.of(parser));
            assertEquals(0, otherGraph.queryIndex(parent).symbolCount());
            assertTrue(otherGraph.resync(parent).startsWith("not a recognized project:"));
            assertTrue(parser.batchInputs.isEmpty());
            assertEquals(1, otherGraph.queryIndex(child).symbolCount());
        }

        @Test
        void localConfigurationExplicitlyOptsIntoIndexing(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var project = Files.createDirectory(temp.resolve("configured"));
            Files.createDirectories(project.resolve(".yca/configuration.json"));
            Files.writeString(project.resolve("Foo.java"), "public class Foo {}\n");
            final var parser = new CapturingBatchParser();
            final var graph = newGraph(withProject(configuration, project), mapper, List.of(parser));
            assertEquals(0, graph.queryIndex(project).symbolCount(), "a configuration directory is not a marker");
            Files.delete(project.resolve(".yca/configuration.json"));
            Files.writeString(project.resolve(".yca/configuration.json"), "{}");
            assertEquals(1, graph.queryIndex(project).symbolCount());
            graph.flushPersist(project);
            assertTrue(Files.isRegularFile(project.resolve(".yca/generated/graph/index.bin")));

            final var otherGraph = newGraph(withProject(configuration, temp), mapper, List.of(parser));
            assertEquals(1, otherGraph.queryIndex(project).symbolCount());
        }

        @Test
        void cachedGraphDoesNotReplaceProjectMarker(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            assertCacheRequiresProjectMarker(configuration);
        }

        @Test
        void skipsIndexingForEmptyTargetProjectDir(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var project = Files.createDirectory(temp.resolve("scaffold"));
            final var config = withProject(configuration, project);
            final var graph = newGraph(config, mapper);
            graph.ensureReady(config.resolveWorkingDir());
            assertTrue(
                    graph.graph(config.resolveWorkingDir()).symbols().isEmpty(),
                    "an empty target project dir must not be indexed into a structural code graph");
        }

        @Test
        void ignoreGlobsAndGitignoreExcludeFiles(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var project = Files.createDirectory(temp.resolve("proj-ignore"));
            Files.writeString(project.resolve("pom.xml"), "<project/>");
            Files.createDirectories(project.resolve("src/main/java/demo"));
            Files.writeString(project.resolve("src/main/java/demo/A.java"), """
                package demo;
                public class A {
                    public void run() {}
                }
                """);
            // files under default-ignored dirs (pruned via SKIP_SUBTREE) must not be indexed
            Files.createDirectories(project.resolve(".yca/secret"));
            Files.writeString(
                    project.resolve(".yca/secret/B.java"), "package demo;\npublic class B { public void run() {} }\n");
            Files.createDirectories(project.resolve("target"));
            Files.writeString(
                    project.resolve("target/C.java"), "package demo;\npublic class C { public void run() {} }\n");
            Files.createDirectories(project.resolve("node_modules"));
            Files.writeString(
                    project.resolve("node_modules/D.java"), "package demo;\npublic class D { public void run() {} }\n");
            // gitignore negation re-includes a file NOT under a dir-pruned subtree
            Files.writeString(project.resolve(".gitignore"), "**/*.VEN.java\n!src/main/java/demo/VEN.java\n");
            Files.writeString(project.resolve("src/main/java/demo/VEN.java"), """
                package demo;
                public class VEN {
                    public void run() {}
                }
                """);

            final var config = withProject(configuration, project);
            final var graph = newGraph(config, mapper);
            graph.ensureReady(config.resolveWorkingDir());

            final var symbols = awaitNonEmpty(graph, project).symbols();
            assertTrue(
                    symbols.stream().anyMatch(s -> s.fqn().equals("demo.A")),
                    "expected the source under src/main/java to be indexed");
            assertTrue(
                    symbols.stream().anyMatch(s -> s.fqn().equals("demo.VEN")),
                    "expected a .gitignore-negated file to be re-included");
            assertTrue(symbols.stream().noneMatch(s -> s.fqn().equals("demo.B")), "expected .yca/** to be ignored");
            assertTrue(symbols.stream().noneMatch(s -> s.fqn().equals("demo.C")), "expected target/** to be ignored");
            assertTrue(
                    symbols.stream().noneMatch(s -> s.fqn().equals("demo.D")),
                    "expected node_modules/** to be ignored");
        }

        @Test
        void docTreeSkipsUnchangedAndWritesSummaryInjection(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var project = Files.createDirectory(temp.resolve("proj"));
            Files.writeString(project.resolve("pom.xml"), "<project/>");
            Files.writeString(
                    project.resolve("Foo.java"), "package demo;\npublic class Foo { public void run() {} }\n");
            final var config = withProject(configuration, project);
            final var graph = newGraph(config, mapper);
            graph.ensureReady(config.resolveWorkingDir());

            final var docRoot = Path.of(config.structuralCodeGraph().resolvedStoreDir(project.toString()))
                    .toAbsolutePath()
                    .normalize();
            final var built = awaitNonEmpty(graph, project);
            final var model = new GraphModel("java", built.symbols(), built.edges());
            final var rel = "Foo.java";
            final var doc = docRoot.resolve("Foo.md");

            // writer emits the structural card (graft format: header + bullet per symbol, no
            // frontmatter/sections)
            assertTrue(Files.isRegularFile(doc));
            final var card = Files.readString(doc);
            assertTrue(card.startsWith("# Foo.java"), "expected source-path header, got:\n" + card);
            assertTrue(card.contains("- Foo · type"), "expected a type bullet with line span");
            assertTrue(card.contains("- run · method"), "expected a method bullet");
            assertFalse(card.contains("## Symbols"), "wiring cards must not carry a ## Symbols section");

            // idempotent: re-writing the unchanged structure must not churn the card
            final var writer = new DocTreeWriter(config, docRoot);
            writer.write(rel, model);
            assertEquals(card, Files.readString(doc), "unchanged card must stay byte-stable");

            // a file-level summary one-liner flows inline under the header when provided by enrichment
            final var enriched = new DocTreeWriter(config, docRoot).enrichment(new DocTreeWriter.Enrichment() {
                @Override
                public String fileSummary(final String relSource) {
                    return "Represents a demo winner.\n";
                }

                @Override
                public String symbolOneLiner(final String relSource, final String fqn) {
                    return null;
                }
            });
            enriched.write(rel, model);
            final var after = Files.readString(doc);
            assertTrue(after.contains("Represents a demo winner."), "expected the file one-liner inline");
            assertTrue(after.contains("- Foo · type"), "expected structural bullets preserved");
        }

        @Test
        void resyncPersistsEnumsWithGeneratedCodecBeans(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var project = Files.createDirectory(temp.resolve("proj-enum"));
            Files.writeString(project.resolve("pom.xml"), "<project/>");
            Files.writeString(project.resolve("Color.java"), """
                package demo;
                public enum Color { RED, GREEN, BLUE }
                """);

            final var config = withProject(configuration, project);

            // the generated Kind/EdgeType enum json codecs are registered by YcaCoreModule and picked up by
            // the shared TestJsonMapper container, so enum-backed graphs work without manual codecs.
            final var graph = newGraph(config, mapper);

            final String status = graph.resync(config.resolveWorkingDir());
            graph.flushPersist(config.resolveWorkingDir());

            assertTrue(status.startsWith("symbols="), "expected status to report symbols, got: " + status);
            assertTrue(status.contains("files="), "expected status to report parsed files, got: " + status);

            // the canonical compact binary index must be persisted
            final var indexFile = project.resolve(".yca/generated/graph/index.bin");
            assertTrue(Files.isRegularFile(indexFile), "expected binary code graph index to be persisted");

            // the enum-backed graph must round-trip through the binary index (no codec / graph.json
            // involved)
            final var idx = CodeGraphIndex.load(indexFile);
            final var stored = idx.toStore();
            assertTrue(
                    stored.symbols().stream().anyMatch(s -> s.kind() == GraphModel.Kind.ENUM),
                    "expected an enum symbol to round-trip through the binary index");
            assertEquals(
                    graph.graph(config.resolveWorkingDir()).symbols().size(),
                    stored.symbols().size());

            // a fresh instance re-loaded from the same binary index must reproduce the same symbols
            final var reloaded = newGraph(config, mapper);
            reloaded.ensureReady(project);
            assertEquals(
                    stored.symbols().size(),
                    awaitNonEmpty(reloaded, project).symbols().size());
        }

        @Test
        void patchReconcilesOfflineDeletedFiles(@Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var project = Files.createDirectory(temp.resolve("proj-del"));
            Files.writeString(project.resolve("pom.xml"), "<project/>");
            Files.writeString(
                    project.resolve("Foo.java"), "package demo;\npublic class Foo { public void run() {} }\n");
            Files.createDirectory(project.resolve("other"));
            Files.writeString(
                    project.resolve("other/Bar.java"), "package demo.sub;\npublic class Bar { public void go() {} }\n");

            final var config = withProject(configuration, project);
            final var graph = newGraph(config, mapper);
            graph.ensureReady(config.resolveWorkingDir());
            graph.flushPersist(config.resolveWorkingDir());
            assertTrue(
                    awaitNonEmpty(graph, project).symbols().stream()
                            .anyMatch(s -> s.fqn().equals("demo.sub.Bar")),
                    "expected Bar symbols before the deletion");

            // delete a file offline (no refreshFile event): a resync must fully reconcile the store
            Files.delete(project.resolve("other/Bar.java"));
            graph.resync(config.resolveWorkingDir());
            graph.flushPersist(config.resolveWorkingDir());

            final var after = graph.graph(config.resolveWorkingDir());
            assertTrue(
                    after.symbols().stream().noneMatch(s -> s.fqn().startsWith("demo.sub")),
                    "deleted file symbols must be dropped by patch");
            assertFalse(
                    after.fingerprints().containsKey("other/Bar.java"),
                    "deleted file fingerprint must be dropped by patch");
            assertFalse(
                    Files.exists(project.resolve(".yca/generated/graph/other/Bar.md")),
                    "deleted file doc must be removed by patch");
            final var meta = Files.readString(project.resolve(".yca/generated/graph/index.meta"));
            assertEquals(
                    "#count=" + after.fingerprints().size(),
                    meta.substring(0, meta.indexOf('\n')),
                    "sidecar must record the indexed file count");
            assertFalse(meta.contains("other/Bar.java"), "sidecar must forget the deleted file, got:\n" + meta);

            // a fresh session must reload the reconciled index: no ghost symbols survive
            final var reloaded = newGraph(config, mapper);
            reloaded.ensureReady(project);
            assertEquals(
                    after.symbols().size(),
                    awaitNonEmpty(reloaded, project).symbols().size(),
                    "reloaded index must not carry ghost symbols for the deleted file");
        }

        @Test
        void gateDetectsOfflineAddedFilesOnSessionStart(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var project = Files.createDirectory(temp.resolve("proj-add"));
            Files.writeString(project.resolve("pom.xml"), "<project/>");
            Files.writeString(
                    project.resolve("Foo.java"), "package demo;\npublic class Foo { public void run() {} }\n");

            final var config = withProject(configuration, project);
            final var built = newGraph(config, mapper);
            built.ensureReady(project);

            // add a file offline: every known mtime still matches, only the #count gate can notice the
            // addition
            Files.writeString(
                    project.resolve("Baz.java"), "package demo;\npublic class Baz { public void stop() {} }\n");
            final var reloaded = newGraph(config, mapper);
            reloaded.ensureReady(project);

            final var symbols = awaitNonEmpty(reloaded, project).symbols();
            assertTrue(
                    symbols.stream().anyMatch(s -> s.fqn().equals("demo.Baz")),
                    "a file added offline must be indexed on session start");
            assertTrue(
                    symbols.stream().anyMatch(s -> s.fqn().equals("demo.Foo")),
                    "existing symbols must be preserved by the incremental patch");
            assertTrue(
                    Files.isRegularFile(project.resolve(".yca/generated/graph/Baz.md")),
                    "expected a doc card for the offline-added file");
        }

        @Test
        void patchRefreshesSidecarAfterMtimeDriftWithoutContentChange(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var project = Files.createDirectory(temp.resolve("proj-drift"));
            Files.writeString(project.resolve("pom.xml"), "<project/>");
            Files.writeString(
                    project.resolve("Foo.java"), "package demo;\npublic class Foo { public void run() {} }\n");

            final var config = withProject(configuration, project);
            final var built = newGraph(config, mapper);
            built.ensureReady(project);
            built.flushPersist(project);

            // touch without a content change: the gate must fail once, the hash-identical patch must
            // refresh
            // the sidecar mtimes (snapshot taken while hashing, never after the whole build) ...
            final var newMtime =
                    Files.getLastModifiedTime(project.resolve("Foo.java")).toMillis() + 60_000;
            Files.setLastModifiedTime(project.resolve("Foo.java"), FileTime.fromMillis(newMtime));
            final var reloaded = newGraph(config, mapper);
            reloaded.ensureReady(project);
            final var meta = Files.readString(project.resolve(".yca/generated/graph/index.meta"));
            assertTrue(
                    meta.contains("Foo.java\t" + newMtime),
                    "sidecar must carry the new mtime after a hash-identical patch, got:\n" + meta);

            // ... so the next session hits the fresh fast path and serves the same symbols
            final var third = newGraph(config, mapper);
            third.ensureReady(project);
            assertEquals(
                    awaitNonEmpty(reloaded, project).symbols().size(),
                    awaitNonEmpty(third, project).symbols().size());
        }
    }

    public static class DefaultGraphModule extends ConfigurationTestModule {
        public DefaultGraphModule() {
            super(graphSettings(Map.of()));
        }
    }

    private void assertCacheRequiresProjectMarker(final CodingAgentConfiguration configuration) throws Exception {
        final var project = Files.createDirectory(temp.resolve("cached"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        final var source = Files.writeString(project.resolve("Foo.java"), "public class Foo {}\n");
        final var config = withProject(configuration, project);
        final var original = newGraph(config, mapper);
        assertNotNull(original.queryIndex(project).resolve("Foo"));
        original.flushPersist(project);
        original.close();
        final var cache = project.resolve(".yca/generated/graph/index.bin");
        if (config.structuralCodeGraph().noRefresh()) {
            // noRefresh does not persist builds: seed an existing cache to exercise its load fast path.
            Files.createDirectories(cache.getParent());
            CodeGraphIndexWriter.write(GraphToIndex.toIndex(original.graph(project)), cache);
        }
        assertTrue(Files.isRegularFile(cache));
        final var cachedBytes = Files.readAllBytes(cache);
        Files.delete(project.resolve("pom.xml"));
        final var reloaded = newGraph(config, mapper);
        reloaded.ensureReady(project);
        assertEquals(0, reloaded.queryIndex(project).symbolCount());
        assertTrue(reloaded.graph(project).symbols().isEmpty());
        assertTrue(reloaded.resync(project).startsWith("not a recognized project:"));
        reloaded.refreshFile(source);
        reloaded.close();
        Assertions.assertArrayEquals(cachedBytes, Files.readAllBytes(cache));
    }

    @Nested
    @FusionSupport(modules = NoRefreshGraphModule.class)
    class NoRefreshGraph {
        @Test
        void cachedGraphDoesNotReplaceProjectMarker(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            assertCacheRequiresProjectMarker(configuration);
        }
    }

    public static class NoRefreshGraphModule extends ConfigurationTestModule {
        public NoRefreshGraphModule() {
            super(graphSettings(Map.of("yca.structuralCodeGraph.noRefresh", "true")));
        }
    }

    @Nested
    @FusionSupport(modules = BatchRefreshNeverReceivesNonJavaFilesFromFileToolsModule.class)
    class BatchRefreshNeverReceivesNonJavaFilesFromFileTools {
        @Test
        void batchRefreshNeverReceivesNonJavaFilesFromFileTools(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var project = Files.createDirectory(temp.resolve("project"));
            Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
            final var foo = project.resolve("Foo.java");
            Files.writeString(foo, "package demo;\npublic class Foo { public void run() {} }\n");

            final var config = withProject(configuration, project);
            final var batchParser = new CapturingBatchParser();
            final var graph = newGraph(config, mapper, List.of(batchParser));
            graph.ensureReady(config.resolveWorkingDir());
            // sanity: the batch-capable parser owns .java and produced the initial graph through its
            // parseBatch
            assertEquals(
                    1,
                    graph.graph(config.resolveWorkingDir()).symbols().size(),
                    "expected the initial graph to index Foo.java");
            assertTrue(
                    batchParser.batchInputs.contains(foo),
                    "the initial build should route Foo.java through the batch parser's parseBatch");

            // write_file/edit_file/apply_patch on a non-Java file (pom.xml) must not reach the java batch
            // parse
            graph.onToolExecuted(new AfterToolExecutionEvent(
                    "write_file", true, "ok", null, null, null, Map.of("path", "pom.xml"), project));
            graph.ensureRefreshedReady(config.resolveWorkingDir());
            assertTrue(
                    batchParser.batchInputs.stream().noneMatch(p -> p.endsWith("pom.xml")),
                    "a pom.xml written by a file tool must never reach the java batch parse");

            // a Java file edit still flows through the batch path
            graph.onToolExecuted(new AfterToolExecutionEvent(
                    "write_file", true, "ok", null, null, null, Map.of("path", "Foo.java"), project));
            graph.ensureRefreshedReady(config.resolveWorkingDir());
            assertTrue(
                    batchParser.batchInputs.stream().anyMatch(foo::equals),
                    "a Java file edit should still be batched for re-indexing");
        }
    }

    public static class BatchRefreshNeverReceivesNonJavaFilesFromFileToolsModule extends ConfigurationTestModule {
        public BatchRefreshNeverReceivesNonJavaFilesFromFileToolsModule() {
            super(graphSettings(Map.of("yca.structuralCodeGraph.batchRefreshDebounceMillis", "50")));
        }
    }

    @Nested
    @FusionSupport(modules = ConfigIgnoreGlobsAreAddedToDefaultsModule.class)
    class ConfigIgnoreGlobsAreAddedToDefaults {
        @Test
        void configIgnoreGlobsAreAddedToDefaults(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var project = Files.createDirectory(temp.resolve("proj-ignorecfg"));
            Files.writeString(project.resolve("pom.xml"), "<project/>");
            Files.createDirectories(project.resolve("src/main/java/demo"));
            Files.writeString(project.resolve("src/main/java/demo/A.java"), """
                package demo;
                public class A {
                    public void run() {}
                }
                """);
            // a custom glob not covered by the defaults: config override adds "custom/**"
            Files.createDirectories(project.resolve("custom"));
            Files.writeString(
                    project.resolve("custom/E.java"), "package demo;\npublic class E { public void run() {} }\n");
            Files.writeString(project.resolve("src/main/java/demo/F.java"), """
                package demo;
                public class F {
                    public void run() {}
                }
                """);

            final var config = withProject(configuration, project);

            final var graph = newGraph(config, mapper);
            graph.ensureReady(config.resolveWorkingDir());

            final var symbols = awaitNonEmpty(graph, project).symbols();
            assertTrue(symbols.stream().anyMatch(s -> s.fqn().equals("demo.A")), "expected demo.A indexed");
            assertTrue(symbols.stream().anyMatch(s -> s.fqn().equals("demo.F")), "expected demo.F indexed");
            assertTrue(
                    symbols.stream().noneMatch(s -> s.fqn().equals("demo.E")),
                    "expected a config-added ignoreGlob (custom/**) to exclude custom/E.java");
        }
    }

    public static class ConfigIgnoreGlobsAreAddedToDefaultsModule extends ConfigurationTestModule {
        public ConfigIgnoreGlobsAreAddedToDefaultsModule() {
            super(graphSettings(Map.of("yca.structuralCodeGraph.ignoreGlobs", "custom/**")));
        }
    }

    @Nested
    @FusionSupport(modules = DeepEnrichmentPersistsPerSymbolStatsAndConceptsModule.class)
    class DeepEnrichmentPersistsPerSymbolStatsAndConcepts {
        @Test
        void deepEnrichmentPersistsPerSymbolStatsAndConcepts(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var project = Files.createDirectory(temp.resolve("proj-deep"));
            Files.writeString(project.resolve("pom.xml"), "<project/>");
            Files.writeString(project.resolve("Foo.java"), """
                package demo;
                public class Foo {
                    public int add(int a, int b) { return a + b; }
                }
                """);

            final var config = withProject(configuration, project);

            final var storeRoot = Path.of(config.structuralCodeGraph().resolvedStoreDir(project.toString()))
                    .toAbsolutePath()
                    .normalize();
            final var model = new GraphModel(
                    "java",
                    List.of(
                            new GraphModel.Symbol("demo.Foo", GraphModel.Kind.TYPE, "Foo.java", 1, 5, "demo.Foo"),
                            new GraphModel.Symbol(
                                    "demo.Foo.add", GraphModel.Kind.METHOD, "Foo.java", 3, 4, "int add(int,int)")),
                    List.of());

            final var fake = new FakeChatClient();
            final var writer = new DocTreeWriter(config, storeRoot);
            try (final var enricher =
                    new DocEnricher(config, fake, writer, mapper, storeRoot, providerManager(config, mapper))) {
                writer.enrichment(enricher);
                enricher.onRewire(() -> WiringGraphWriter.write(
                        mapper,
                        storeRoot,
                        new GraphStore(
                                List.copyOf(model.symbols()),
                                List.copyOf(model.edges()),
                                Map.of(),
                                System.currentTimeMillis()),
                        enricher.cache()));

                // per-symbol deep + file summary are scheduled asynchronously
                enricher.maybeEnrich("Foo.java", model);
                enricher.awaitIdle().get(5, TimeUnit.SECONDS);

                final var fileEntry = enricher.cache().file("Foo.java");
                assertNotNull(fileEntry);
                assertEquals(DocTreeWriter.structuralDigest(model), fileEntry.digest(), "cache keyed by content hash");
                assertTrue(fileEntry.summary().contains("One file"));

                // deep per-symbol entries present and keyed by fqn (await both writes flushed to disk)
                final var symKey = enricher.cache().symbolKey("Foo.java", "demo.Foo.add");
                enricher.awaitIdle().get(5, TimeUnit.SECONDS);
                final var symEntry = enricher.cache().symbol(symKey);
                assertNotNull(symEntry);
                assertEquals("does addition", symEntry.summary());
                assertEquals("careful with overflow", symEntry.crux());

                // the enriched card carries the file one-liner and the per-symbol one-liner inline, no
                // sections
                enricher.awaitIdle().get(5, TimeUnit.SECONDS);
                final var card = Files.readString(storeRoot.resolve("Foo.md"));
                assertTrue(card.startsWith("# Foo.java"), "expected source-path header");
                assertTrue(card.contains("One file summary text."), "expected the file one-liner inline");
                assertTrue(card.contains("does addition"), "expected per-symbol one-liner inline");
                assertFalse(card.contains("## Summary"), "wiring cards must not carry a ## Summary section");

                // the wiring graph re-emits with summary, crux and a ready state once enriched
                enricher.awaitIdle().get(5, TimeUnit.SECONDS);
                final var wiring = Files.readString(storeRoot.resolve(".graph/wiring.json"));
                assertTrue(wiring.contains("does addition"), "expected summary pushed into wiring.json");
                assertTrue(wiring.contains("careful with overflow"), "expected crux pushed into wiring.json");
                assertTrue(wiring.contains("\"summary_state\":\"ready\""), "expected a ready summary_state");

                // a second enricher over the same store must hit the persistent cache (no new LLM calls)
                final var secondFake = new FakeChatClient();
                try (final var enricher2 = new DocEnricher(
                        config, secondFake, writer, mapper, storeRoot, providerManager(config, mapper))) {
                    enricher2.maybeEnrich("Foo.java", model);
                    enricher2.awaitIdle().get(5, TimeUnit.SECONDS);
                    assertNotNull(enricher2.cache().file("Foo.java"));
                    assertEquals(0, secondFake.calls.get(), "persistent cache must avoid redundant LLM calls");
                }

                // the concept pass groups summaries into curated cards at the store root
                final var written = enricher.enrichConcepts();
                assertTrue(written >= 1, "expected at least one concept card");
                final var conceptCard = storeRoot.resolve("parsing.md");
                assertTrue(Files.isRegularFile(conceptCard), "expected concept card at store root");
                final var concept = Files.readString(conceptCard);
                assertTrue(concept.contains("slug:parsing"), "expected slug frontmatter");
                assertTrue(concept.contains("type:"), "expected type frontmatter");
                assertTrue(concept.contains("## Summary"), "expected concept summary body");
                assertTrue(Files.isRegularFile(storeRoot.resolve("INDEX.md")), "expected a repo-map index");
            }
        }
    }

    public static class DeepEnrichmentPersistsPerSymbolStatsAndConceptsModule extends ConfigurationTestModule {
        public DeepEnrichmentPersistsPerSymbolStatsAndConceptsModule() {
            super(graphSettings(Map.of(
                    "yca.structuralCodeGraph.enrich", "true",
                    "yca.structuralCodeGraph.model", "test-model",
                    "yca.structuralCodeGraph.deep", "true")));
        }
    }
}
