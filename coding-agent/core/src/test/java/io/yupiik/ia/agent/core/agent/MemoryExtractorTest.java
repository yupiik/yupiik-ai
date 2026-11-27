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
package io.yupiik.ia.agent.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.MemoryConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatClient.StructuredResult;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.llm.ToolCall.FunctionCall;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.memory.MemoryCompactor;
import io.yupiik.ia.agent.core.memory.MemoryProductionPolicy;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class MemoryExtractorTest {
    // FIFO execution lets a bounded barrier wait for an entire background pass, including its watermark update.
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @TempDir
    Path home;

    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
        this.mapper = mapper;
        this.configuration =
                withStorageHomeWorkingDirProjectDir(configuration, home.toString(), home.toString(), home.toString());
    }

    /** Mirrors the extractor's internal excerpt cap (12k). */
    private static final int MAX_EXCERPT_CHARS_FOR_TEST = 12_000;

    /**
     * Polls until the asynchronous extraction of {@code factText} has been committed to an on-disk
     * memory file. Extraction runs on a background executor and writes the store directly, so this
     * poll-re-reads until the fact shows up (or the deadline passes).
     */
    private void awaitFlushedFact(final MemoryStore store, final String factText) throws Exception {
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (store.readGlobal().contains(factText) || store.readProject(home).contains(factText)) {
                return;
            }
            Thread.sleep(20);
        }
    }

    @AfterAll
    static void closeExecutor() {
        EXECUTOR.shutdownNow();
    }

    private CodingAgentConfiguration configuration;

    @Nested
    @FusionSupport(modules = CloseExtractionIsolatesRootsForStructuredAndBothPlainFallbacksModule.class)
    class CloseExtractionIsolatesRootsForStructuredAndBothPlainFallbacksTests {
        @TempDir
        static Path root;

        @Test
        void closeExtractionIsolatesRootsForStructuredAndBothPlainFallbacks() {
            final var config = configuration;
            final var store = new MemoryStore(config);
            final var alpha = home.resolve("alpha");
            final var beta = home.resolve("beta");
            for (final var mode : List.of("structured", "unsupported", "wire-failure")) {
                for (final var root : List.of(alpha, beta, home)) {
                    final var label = mode + " " + root.getFileName();
                    final var chat = new ChatClient() {
                        @Override
                        public StructuredResult chatStructured(
                                final ChatCompletionRequest request,
                                final String schemaName,
                                final Map<String, Object> schema)
                                throws IOException {
                            if ("wire-failure".equals(mode)) {
                                throw new IOException("structured response failed");
                            }
                            return new StructuredResult(
                                    new ChatResult(
                                            Message.assistant(
                                                    "{\"facts\":[{\"text\":\"" + label
                                                            + " project decision\",\"scope\":\"project\"},"
                                                            + "{\"text\":\""
                                                            + label + " global preference\",\"scope\":\"global\"}]}",
                                                    null),
                                            new Usage(0, 0, 0)),
                                    "structured".equals(mode));
                        }

                        @Override
                        public ChatResult chat(final ChatCompletionRequest request) {
                            return new ChatResult(
                                    Message.assistant(label + " fallback decision", null), new Usage(0, 0, 0));
                        }
                    };
                    final var extractor = new MemoryExtractor(
                            chat,
                            config,
                            null,
                            store,
                            mapper,
                            EXECUTOR,
                            null,
                            Clock.systemUTC(),
                            null,
                            label,
                            null,
                            null,
                            null,
                            root);
                    assertTrue(extractor.forceExtractNow(List.of(Message.user("a durable project decision"))));
                    final var text = store.readProject(root);
                    assertTrue(text.contains(label), text);
                    assertFalse(
                            store.readProject(root.equals(alpha) ? beta : alpha).contains(label));
                    if (!root.equals(home)) {
                        assertFalse(
                                store.readProject(config.resolveWorkingDir()).contains(label));
                    }
                    if ("structured".equals(mode)) {
                        assertTrue(store.readGlobal().contains(label + " global preference"));
                        assertFalse(text.contains("global preference"));
                    }
                }
            }
        }

        @Test
        void asyncExtractionPropagatesRootToCompactionAndCloseKeepsThatRoot() throws Exception {
            final var config = configuration;
            final var store = new MemoryStore(config);
            final var root = home.resolve("session-project");
            final var file = MemoryStore.projectFile(root);
            final var original = MemoryStore.HEADER + "- old durable project observation\n".repeat(2400);
            Files.createDirectories(file.getParent());
            Files.writeString(file, original);
            final var extractionCalls = new AtomicInteger();
            final var chat = new ChatClient() {
                @Override
                public StructuredResult chatStructured(
                        final ChatCompletionRequest request,
                        final String schemaName,
                        final Map<String, Object> schema) {
                    final var facts = extractionCalls.getAndIncrement() == 0
                            ? "{\"facts\":[]}"
                            : "{\"facts\":[{\"text\":\"close-time deployment rule\",\"scope\":\"project\"}]}";
                    return new StructuredResult(
                            new ChatResult(Message.assistant(facts, null), new Usage(0, 0, 0)), true);
                }

                @Override
                public ChatResult chat(final ChatCompletionRequest request) {
                    return new ChatResult(Message.assistant("- session architecture", null), new Usage(0, 0, 0));
                }
            };
            final var compactor = new MemoryCompactor(chat, config, store, null, null);
            final var extractor = new MemoryExtractor(
                    chat,
                    config,
                    null,
                    store,
                    mapper,
                    EXECUTOR,
                    null,
                    Clock.systemUTC(),
                    null,
                    "root-test",
                    compactor,
                    null,
                    null,
                    root);

            extractor.asyncExtractNow(List.of(Message.user("a durable project decision")));
            EXECUTOR.submit(() -> {}).get();
            assertTrue(Files.exists(file.resolveSibling("MEMORY.md.bak")));
            assertNotNull(compactor.state(root));
            assertTrue(store.readProject(config.resolveWorkingDir()).isBlank());
            assertTrue(store.readProject(root).contains("session architecture"));
            assertTrue(extractor.forceExtractNow(List.of(
                    Message.user("a durable project decision"), Message.user("another durable decision at close"))));
            assertTrue(store.readProject(root).contains("close-time deployment rule"));
            assertTrue(store.readProject(config.resolveWorkingDir()).isBlank());
        }

        @Test
        void structuredFailureFallsBackToSnippetAndWarnsWithContext() throws Exception {
            final var config = configuration;
            final var store = new MemoryStore(config);
            final var events = new ArrayList<AgentEvent>();
            final var chat = new ChatClient() {
                @Override
                public StructuredResult chatStructured(
                        final ChatCompletionRequest request, final String schemaName, final Map<String, Object> schema)
                        throws IOException {
                    // provider rejected the JSON Schema: ChatClient falls back to a plain call and reports
                    // structured=false; the extractor must then use the snippet fallback
                    return new StructuredResult(
                            new ChatResult(Message.assistant("plain fallback answer", null), new Usage(1, 1, 2)),
                            false);
                }

                @Override
                public ChatResult chat(final ChatCompletionRequest request) throws IOException, InterruptedException {
                    return new ChatResult(
                            Message.assistant("- a durable fact about the project that survives the fallback\n", null),
                            new Usage(1, 1, 2));
                }
            };
            final var done = new CountDownLatch(1);
            final var extractor = new MemoryExtractor(
                    chat,
                    config,
                    null, // provider manager: the global memory model chain applies
                    store,
                    null, // mapper unused by the fallback path
                    EXECUTOR,
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            if (event instanceof AgentEvent agentEvent) {
                                events.add(agentEvent);
                            }
                            done.countDown();
                        }
                    },
                    Clock.systemUTC(),
                    null,
                    "s-test",
                    null,
                    null, // memory production policy (default: enabled)
                    null,
                    (config).resolveWorkingDir());
            extractor.asyncExtractNow(
                    List.of(Message.user("user said: the project now uses tabs")), ChatSettings.EMPTY);
            assertTrue(done.await(5, TimeUnit.SECONDS), "the extractor must emit a notice");
            // extraction is buffered on the background executor (never touches the live on-disk store):
            // poll for it to land in the pending buffer, flushing to MEMORY.md so it can be asserted.
            awaitFlushedFact(store, "a durable fact");

            assertEquals(1, events.size(), "one warning expected");
            final var notice = (AgentEvent.Notice) events.get(0);
            assertEquals(AgentEvent.Notice.Level.WARN, notice.level());
            assertEquals("s-test", notice.sessionId());
            assertTrue(
                    notice.message().contains("structured facts"),
                    "the notice must name the failing phase: " + notice.message());
            assertTrue(
                    notice.message().contains("rejected by the provider"),
                    "the notice must explain the rejection: " + notice.message());
            assertTrue(
                    notice.message().contains("saving a plain snippet instead"),
                    "the notice must mention the fallback: " + notice.message());
            // the fallback snippet path must still save memory (after the flush boundary)
            final var project = store.readProject(config.resolveWorkingDir());
            assertTrue(project.contains("a durable fact"), "the fallback snippet must be saved: " + project);
        }

        @Test
        void persistentStructuredLackWarnsOnceAcrossConsecutivePasses() throws Exception {
            // a provider that persistently lacks structured output (eg. yupiik's NInfer gateway) returns
            // structured=false on every pass. The 'rejected by the provider; saving a plain snippet instead'
            // WARN must fire exactly once for the whole consecutive streak - not on every memory pass - so a
            // permanently non-structured backend does not spam the user each turn. A later structured success
            // (resetFailures on the structured branch) would re-arm the notice.
            final var config = configuration;
            final var store = new MemoryStore(config);
            final var events = new ArrayList<AgentEvent>();
            final var chat = new ChatClient() {
                @Override
                public StructuredResult chatStructured(
                        final ChatCompletionRequest request, final String schemaName, final Map<String, Object> schema)
                        throws IOException {
                    // always degrade to the plain path: the provider cannot do constrained output
                    return new StructuredResult(
                            new ChatResult(Message.assistant("plain fallback answer", null), new Usage(1, 1, 2)),
                            false);
                }

                @Override
                public ChatResult chat(final ChatCompletionRequest request) throws IOException, InterruptedException {
                    return new ChatResult(
                            Message.assistant("- a durable fact about the project\n", null), new Usage(1, 1, 2));
                }
            };
            final var extractor = new MemoryExtractor(
                    chat,
                    config,
                    null, // provider manager: the global memory model chain applies
                    store,
                    mapper,
                    EXECUTOR,
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            if (event instanceof AgentEvent agentEvent) {
                                events.add(agentEvent);
                            }
                        }
                    },
                    Clock.systemUTC(),
                    null,
                    "s-test",
                    null,
                    null, // memory production policy (default: enabled)
                    null,
                    (config).resolveWorkingDir());
            // two consecutive passes over a growing transcript (the watermark advances after each pass, so a
            // fresh delta is required - mirroring deltaExtractsOnlyNewMessages), both ending in the
            // structured=false plain-snippet fallback
            final var messages = new ArrayList<Message>();
            messages.add(Message.user("user said: project uses tabs now"));
            extractor.forceExtractNow(messages, ChatSettings.EMPTY);
            messages.add(Message.user("user said: we standardize on tabs"));
            extractor.forceExtractNow(messages, ChatSettings.EMPTY);

            final var warns = events.stream()
                    .filter(e ->
                            e instanceof AgentEvent.Notice notice && notice.level() == AgentEvent.Notice.Level.WARN)
                    .count();
            assertEquals(
                    1, warns, "the consistent structured=false must warn exactly once across the streak: " + events);
        }

        @Test
        void persistentWireFailureSalvageWarnsOnceAcrossConsecutivePasses() throws Exception {
            // a provider that consistently throws on the structured call (e.g. a gateway that streams
            // unconditionally) but whose plain-snippet fallback still salvages memory. Like the
            // structured=false case, the WARN must fire exactly once across the consecutive streak - not on
            // every pass - and the snippet must still be persisted every pass. Only a genuine structured
            // success re-arms the notice.
            final var config = configuration;
            final var store = new MemoryStore(config);
            final var events = new ArrayList<AgentEvent>();
            final var chat = new ChatClient() {
                @Override
                public StructuredResult chatStructured(
                        final ChatCompletionRequest request, final String schemaName, final Map<String, Object> schema)
                        throws IOException {
                    throw new IOException("anthropic-messages: unparseable response (HTTP 200, text/event-stream)");
                }

                @Override
                public ChatResult chat(final ChatCompletionRequest request) throws IOException, InterruptedException {
                    return new ChatResult(
                            Message.assistant("- a durable fact about the project\n", null), new Usage(1, 1, 2));
                }
            };
            final var extractor = new MemoryExtractor(
                    chat,
                    config,
                    null, // provider manager: the global memory model chain applies
                    store,
                    mapper,
                    EXECUTOR,
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            if (event instanceof AgentEvent agentEvent) {
                                events.add(agentEvent);
                            }
                        }
                    },
                    Clock.systemUTC(),
                    null,
                    "s-test",
                    null,
                    null, // memory production policy (default: enabled)
                    null,
                    (config).resolveWorkingDir());
            // two consecutive passes over a growing transcript, both salvaged via the plain snippet
            final var messages = new ArrayList<Message>();
            messages.add(Message.user("user said: project uses tabs now"));
            extractor.forceExtractNow(messages, ChatSettings.EMPTY);
            messages.add(Message.user("user said: we standardize on tabs"));
            extractor.forceExtractNow(messages, ChatSettings.EMPTY);

            final var warns = events.stream()
                    .filter(e ->
                            e instanceof AgentEvent.Notice notice && notice.level() == AgentEvent.Notice.Level.WARN)
                    .count();
            assertEquals(
                    1, warns, "a persistent wire-failure salvage must warn exactly once across the streak: " + events);
            final var project = store.readProject(config.resolveWorkingDir());
            assertTrue(
                    project.contains("a durable fact about the project"),
                    "the fallback snippet must still be saved on every pass: " + project);
        }

        @Test
        void structuredExceptionFallsBackToSnippetAndWarns() throws Exception {
            // P0-D: a wire failure of the structured call (e.g. a gateway that streams unconditionally) used
            // to lose the whole memory pass; now it must still save a plain snippet and name the phase
            final var config = configuration;
            final var store = new MemoryStore(config);
            final var events = new ArrayList<AgentEvent>();
            final var chat = new ChatClient() {
                @Override
                public StructuredResult chatStructured(
                        final ChatCompletionRequest request, final String schemaName, final Map<String, Object> schema)
                        throws IOException {
                    throw new IOException(
                            "anthropic-messages: unparseable response from https://gw/v1/messages (HTTP 200, "
                                    + "text/event-stream): Unexpected character 'e'");
                }

                @Override
                public ChatResult chat(final ChatCompletionRequest request) throws IOException, InterruptedException {
                    return new ChatResult(
                            Message.assistant("- durable: the team standard is tabs\n", null), new Usage(1, 1, 2));
                }
            };
            final var done = new CountDownLatch(1);
            final var extractor = new MemoryExtractor(
                    chat,
                    config,
                    null, // provider manager: the global memory model chain applies
                    store,
                    mapper,
                    EXECUTOR,
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            if (event instanceof AgentEvent agentEvent) {
                                events.add(agentEvent);
                            }
                            done.countDown();
                        }
                    },
                    Clock.systemUTC(),
                    null,
                    "s-test",
                    null,
                    null, // memory production policy (default: enabled)
                    null,
                    (config).resolveWorkingDir());
            extractor.asyncExtractNow(List.of(Message.user("user said: we use tabs now")), ChatSettings.EMPTY);
            assertTrue(done.await(5, TimeUnit.SECONDS), "the extractor must emit a notice");
            awaitFlushedFact(store, "the team standard is tabs");

            assertEquals(1, events.size(), "one warning expected");
            final var notice = (AgentEvent.Notice) events.get(0);
            assertTrue(
                    notice.message().contains("structured facts"),
                    "the notice must name the failing phase: " + notice.message());
            assertTrue(
                    notice.message().contains("saving a plain snippet instead"),
                    "the notice must mention the fallback: " + notice.message());
            assertTrue(
                    notice.message().contains("Unexpected character 'e'"),
                    "the notice must carry the contextual detail: " + notice.message());
            assertNotNull(notice.cause(), "the notice must carry the failing exception for the Alt+E stack");
            assertTrue(
                    notice.cause().getMessage().contains("unparseable response"),
                    "the cause must be the thrown IOException: " + notice.cause());
            final var project = store.readProject(config.resolveWorkingDir());
            assertTrue(project.contains("the team standard is tabs"), "the fallback snippet must be saved: " + project);
        }

        @Test
        void deltaExtractsOnlyNewMessages() throws Exception {
            final var config = configuration;
            final var store = new MemoryStore(config);
            final var requests = new ArrayList<ChatCompletionRequest>();
            final var chat = new ChatClient() {
                @Override
                public StructuredResult chatStructured(
                        final ChatCompletionRequest request, final String schemaName, final Map<String, Object> schema)
                        throws IOException {
                    requests.add(request);
                    return new StructuredResult(
                            new ChatResult(
                                    Message.assistant(
                                            "{\"facts\":[{\"text\":\"prefer tabs\",\"scope\":\"global\"}]}", null),
                                    new Usage(1, 1, 2)),
                            true);
                }
            };
            final var extractor = new MemoryExtractor(
                    chat,
                    config,
                    null,
                    store,
                    mapper,
                    EXECUTOR,
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            // no-op
                        }
                    },
                    Clock.systemUTC(),
                    null,
                    "s-delta",
                    null,
                    null, // memory production policy (default: enabled)
                    null,
                    (config).resolveWorkingDir());
            final var messages = new ArrayList<Message>();
            messages.add(Message.user("first message: use tabs"));
            extractor.forceExtractNow(messages, ChatSettings.EMPTY);
            // second user message: only that one must be extracted (the excerpt must not re-feed the first)
            messages.add(Message.user("second message: use spaces"));
            extractor.forceExtractNow(messages, ChatSettings.EMPTY);
            assertEquals(2, requests.size(), "one call per extraction");
            final var secondExcerpt =
                    String.valueOf(requests.get(1).messages().get(1).content());
            assertTrue(
                    secondExcerpt.contains("second message"),
                    "the delta must contain the new message: " + secondExcerpt);
            assertFalse(
                    secondExcerpt.contains("first message"),
                    "the delta must not re-feed extracted messages: " + secondExcerpt);
        }

        @Test
        void structuredFactsParseIntoMetadataEnvelope() throws Exception {
            // The COMPLETIONS-flavor fix (strictSchema normalization) restores structured extraction on
            // backends that reject the raw schema (eg. Azure / litellm 'Missing p'). A structured response
            // with the full field set must parse into a properly-enveloped fact (p|date|path|topic|text)
            // rather than a single plain snippet.
            final var config = configuration;
            final var store = new MemoryStore(config);
            final var chat = new ChatClient() {
                @Override
                public StructuredResult chatStructured(
                        final ChatCompletionRequest request, final String schemaName, final Map<String, Object> schema)
                        throws IOException {
                    return new StructuredResult(
                            new ChatResult(
                                    Message.assistant(
                                            "{\"facts\":[{\"text\":\"always use tabs\",\"scope\":\"project\",\"p\":3,"
                                                    + "\"path\":\"src/App.java\",\"topic\":\"code style\"}]}",
                                            null),
                                    new Usage(1, 1, 2)),
                            true);
                }
            };
            final var extractor = new MemoryExtractor(
                    chat,
                    config,
                    null,
                    store,
                    mapper,
                    EXECUTOR,
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            // no-op
                        }
                    },
                    Clock.systemUTC(),
                    null,
                    "s-meta",
                    null,
                    null, // memory production policy (default: enabled)
                    null,
                    (config).resolveWorkingDir());
            extractor.forceExtractNow(List.of(Message.user("the team decided: always use tabs")), ChatSettings.EMPTY);
            final var project = store.readProject(config.resolveWorkingDir());
            // stored as a metadata envelope: <p>|<date>|@<path>|<topic>|<text>
            assertTrue(
                    project.contains("3|" + LocalDate.now() + "|@src/App.java|code style|always use tabs"),
                    "the structured fact must keep its envelope: " + project);
        }

        @Test
        void disabledProductionPolicyBlocksExtraction() throws Exception {
            final var config = configuration;
            final var store = new MemoryStore(config);
            final var calls = new AtomicInteger();
            final var chat = new ChatClient() {
                @Override
                public StructuredResult chatStructured(
                        final ChatCompletionRequest request, final String schemaName, final Map<String, Object> schema)
                        throws IOException {
                    calls.incrementAndGet();
                    return new StructuredResult(
                            new ChatResult(
                                    Message.assistant(
                                            "{\"facts\":[{\"text\":\"should not persist\",\"scope\":\"project\"}]}",
                                            null),
                                    new Usage(1, 1, 2)),
                            true);
                }
            };
            final var policy = new MemoryProductionPolicy();
            policy.set("s-disabled", false);
            final var extractor = new MemoryExtractor(
                    chat,
                    config,
                    null,
                    store,
                    mapper,
                    EXECUTOR,
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            // no-op
                        }
                    },
                    Clock.systemUTC(),
                    null,
                    "s-disabled",
                    null,
                    policy,
                    null,
                    (config).resolveWorkingDir());
            // neither the end-of-turn hook nor the session-close forced pass may mine anything
            extractor.asyncExtractNow(List.of(Message.user("a durable fact appears here")), ChatSettings.EMPTY, true);
            EXECUTOR.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(0, calls.get(), "a disabled session must not fire an extraction call");
            assertFalse(
                    extractor.forceExtractNow(List.of(Message.user("a durable fact appears here"))),
                    "forced pass is a no-op when disabled");
            assertEquals(0, calls.get(), "the forced pass must not call the LLM either");
            assertTrue(
                    store.readProject(config.resolveWorkingDir()).isBlank(),
                    "nothing may reach the store while disabled");
        }

        @Test
        void asyncExtractRunsImmediatelyButTheWatermarkDedups() throws Exception {
            final var config = configuration;
            final var store = new MemoryStore(config);
            final var calls = new AtomicInteger();
            final var chat = new ChatClient() {
                @Override
                public StructuredResult chatStructured(
                        final ChatCompletionRequest request, final String schemaName, final Map<String, Object> schema)
                        throws IOException {
                    calls.incrementAndGet();
                    return new StructuredResult(
                            new ChatResult(Message.assistant("{\"facts\":[]}", null), new Usage(1, 1, 2)), true);
                }
            };
            final var extractor = new MemoryExtractor(
                    chat,
                    config,
                    null,
                    store,
                    mapper,
                    EXECUTOR,
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            // no-op
                        }
                    },
                    Clock.systemUTC(),
                    null,
                    "s-async",
                    null,
                    null, // memory production policy (default: enabled)
                    null,
                    (config).resolveWorkingDir());
            // the end-of-turn hook is not debounced: the first call must run right away
            extractor.asyncExtractNow(List.of(Message.user("one")), ChatSettings.EMPTY);
            EXECUTOR.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(1, calls.get(), "the async pass must run immediately (no debounce)");
            // a second hook on the same messages is a watermark no-op: the delta was already extracted
            extractor.asyncExtractNow(List.of(Message.user("one")), ChatSettings.EMPTY);
            EXECUTOR.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(1, calls.get(), "the watermark must dedup the already-extracted delta");
        }

        @Test
        void queuedExtractionUsesSnapshotAndLeavesLaterMessagesPending() throws Exception {
            final var config = configuration;
            final var requests = new ArrayList<ChatCompletionRequest>();
            final var chat = new ChatClient() {
                @Override
                public StructuredResult chatStructured(
                        final ChatCompletionRequest request,
                        final String schemaName,
                        final Map<String, Object> schema) {
                    requests.add(request);
                    return new StructuredResult(
                            new ChatResult(Message.assistant("{\"facts\":[]}", null), new Usage(1, 1, 2)), true);
                }
            };
            final var release = new CountDownLatch(1);
            try (final var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> {
                    release.await();
                    return null;
                });
                final var extractor = new MemoryExtractor(
                        chat,
                        config,
                        null,
                        new MemoryStore(config),
                        mapper,
                        executor,
                        null,
                        Clock.systemUTC(),
                        null,
                        "snapshot",
                        null,
                        null,
                        null,
                        (config).resolveWorkingDir());
                final var messages = new ArrayList<>(List.of(Message.user("prefer tabs")));
                try {
                    extractor.asyncExtractNow(messages);
                    messages.add(Message.user("prefer small commits"));
                } finally {
                    release.countDown();
                }
                executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
                assertEquals(1, requests.size());
                assertFalse(String.valueOf(requests.getFirst().messages().get(1).content())
                        .contains("small commits"));
                assertTrue(extractor.forceExtractNow(messages));
                assertEquals(2, requests.size());
                final var excerpt =
                        String.valueOf(requests.getLast().messages().get(1).content());
                assertTrue(excerpt.contains("small commits"));
                assertFalse(excerpt.contains("prefer tabs"));
            }
        }

        @Test
        void forcedExtractionWaitsForBackgroundPassInsteadOfDuplicatingIt() throws Exception {
            final var config = configuration;
            final var entered = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            final var calls = new AtomicInteger();
            final var chat = new ChatClient() {
                @Override
                public StructuredResult chatStructured(
                        final ChatCompletionRequest request, final String schemaName, final Map<String, Object> schema)
                        throws InterruptedException {
                    calls.incrementAndGet();
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return new StructuredResult(
                            new ChatResult(Message.assistant("{\"facts\":[]}", null), new Usage(1, 1, 2)), true);
                }
            };
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var extractor = new MemoryExtractor(
                        chat,
                        config,
                        null,
                        new MemoryStore(config),
                        mapper,
                        executor,
                        null,
                        Clock.systemUTC(),
                        null,
                        "overlap",
                        null,
                        null,
                        null,
                        (config).resolveWorkingDir());
                final var messages = List.of(Message.user("prefer tabs"));
                extractor.asyncExtractNow(messages);
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    final var forced = executor.submit(() -> extractor.forceExtractNow(messages));
                    assertThrows(TimeoutException.class, () -> forced.get(100, TimeUnit.MILLISECONDS));
                    release.countDown();
                    assertFalse(forced.get(5, TimeUnit.SECONDS));
                    assertEquals(1, calls.get(), "overlapping close must reuse the completed background extraction");
                } finally {
                    release.countDown();
                }
            }
        }

        @Test
        void newerForcedPassCoversOlderQueuedSnapshot() throws Exception {
            final var config = configuration;
            final var calls = new AtomicInteger();
            final var chat = new ChatClient() {
                @Override
                public StructuredResult chatStructured(
                        final ChatCompletionRequest request,
                        final String schemaName,
                        final Map<String, Object> schema) {
                    calls.incrementAndGet();
                    return new StructuredResult(
                            new ChatResult(Message.assistant("{\"facts\":[]}", null), new Usage(1, 1, 2)), true);
                }
            };
            final var release = new CountDownLatch(1);
            try (final var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> {
                    release.await();
                    return null;
                });
                final var extractor = new MemoryExtractor(
                        chat,
                        config,
                        null,
                        new MemoryStore(config),
                        mapper,
                        executor,
                        null,
                        Clock.systemUTC(),
                        null,
                        "stale",
                        null,
                        null,
                        null,
                        (config).resolveWorkingDir());
                final var first = Message.user("prefer tabs");
                try {
                    extractor.asyncExtractNow(List.of(first));
                    assertTrue(extractor.forceExtractNow(List.of(first, Message.user("prefer small commits"))));
                } finally {
                    release.countDown();
                }
                executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
                assertEquals(1, calls.get(), "an older queued prefix must not repeat a successful extraction");
            }
        }

        @Test
        void failedExtractionRemainsRetryable() throws Exception {
            final var config = configuration;
            final var calls = new AtomicInteger();
            final var chat = new ChatClient() {
                @Override
                public StructuredResult chatStructured(
                        final ChatCompletionRequest request,
                        final String schemaName,
                        final Map<String, Object> schema) {
                    if (calls.incrementAndGet() == 1) {
                        throw new IllegalStateException("temporary failure");
                    }
                    return new StructuredResult(
                            new ChatResult(Message.assistant("{\"facts\":[]}", null), new Usage(1, 1, 2)), true);
                }
            };
            final var extractor = new MemoryExtractor(
                    chat,
                    config,
                    null,
                    new MemoryStore(config),
                    mapper,
                    EXECUTOR,
                    null,
                    Clock.systemUTC(),
                    null,
                    "retry",
                    null,
                    null,
                    null,
                    (config).resolveWorkingDir());
            final var messages = List.of(Message.user("prefer tabs"));
            assertFalse(extractor.forceExtractNow(messages));
            assertTrue(extractor.forceExtractNow(messages));
            assertFalse(extractor.forceExtractNow(messages));
            assertEquals(2, calls.get());
        }

        @Test
        void replacedHistoryOfSameSizeIsNotSkipped() throws Exception {
            final var config = configuration;
            final var calls = new AtomicInteger();
            final var chat = new ChatClient() {
                @Override
                public StructuredResult chatStructured(
                        final ChatCompletionRequest request,
                        final String schemaName,
                        final Map<String, Object> schema) {
                    calls.incrementAndGet();
                    return new StructuredResult(
                            new ChatResult(Message.assistant("{\"facts\":[]}", null), new Usage(1, 1, 2)), true);
                }
            };
            final var extractor = new MemoryExtractor(
                    chat,
                    config,
                    null,
                    new MemoryStore(config),
                    mapper,
                    EXECUTOR,
                    null,
                    Clock.systemUTC(),
                    null,
                    "replacement",
                    null,
                    null,
                    null,
                    (config).resolveWorkingDir());
            assertTrue(extractor.forceExtractNow(List.of(Message.user("prefer tabs"))));
            assertTrue(extractor.forceExtractNow(List.of(Message.user("prefer spaces"))));
            assertEquals(2, calls.get());
        }

        @Test
        void asyncExtractNowHonorsEligibilityWithoutAdvancingWatermark() throws Exception {
            // the cadence gate (memory.extractEveryTurns) must not fire the LLM pass when
            // AgentLoop says not eligible, and must NOT advance the watermark: the delta stays
            // pending for the next eligible turn or the forced close-time pass.
            final var config = configuration;
            final var store = new MemoryStore(config);
            final var calls = new AtomicInteger();
            final var chat = new ChatClient() {
                @Override
                public StructuredResult chatStructured(
                        final ChatCompletionRequest request, final String schemaName, final Map<String, Object> schema)
                        throws IOException {
                    calls.incrementAndGet();
                    return new StructuredResult(
                            new ChatResult(Message.assistant("{\"facts\":[]}", null), new Usage(1, 1, 2)), true);
                }
            };
            final var extractor = new MemoryExtractor(
                    chat,
                    config,
                    null,
                    store,
                    mapper,
                    EXECUTOR,
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            // no-op
                        }
                    },
                    Clock.systemUTC(),
                    null,
                    "s-gating",
                    null,
                    null, // memory production policy (default: enabled)
                    null,
                    (config).resolveWorkingDir());
            final var one = List.of(Message.user("one"));
            final var two = List.of(Message.user("one"), Message.user("two"));
            // not eligible: nothing runs, watermark must stay put
            extractor.asyncExtractNow(one, ChatSettings.EMPTY, false);
            EXECUTOR.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(0, calls.get(), "ineligible pass must not call the LLM");
            // eligible: the pending delta (both messages) is extracted once
            extractor.asyncExtractNow(two, ChatSettings.EMPTY, true);
            EXECUTOR.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(1, calls.get(), "eligible pass must extract the pending delta");
            // a repeat eligible pass on the same messages is a watermark no-op
            extractor.asyncExtractNow(two, ChatSettings.EMPTY, true);
            EXECUTOR.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(1, calls.get(), "watermark must still dedup after the eligible pass");
        }

        @Test
        void excerptFiltersTransientAndKeepsUserSignal() throws Exception {
            // the excerpt fed to the model must contain only user/assistant text, never
            // tool results, tool-call frames, digests or the volatile tail
            final var messages = List.of(
                    Message.system("system prompt"),
                    Message.user("prefer tabs"),
                    Message.tool("tc-1", "huge JSON tool output"),
                    Message.assistant(
                            "I'll use tabs",
                            List.of(new ToolCall(
                                    "tc-2", "function", new FunctionCall("read", "{\"path\":\"/tmp/x\"}"), null))),
                    Message.assistant("the function reads a config file", null),
                    Message.user("<compacted-context> the previous conversation was summarized </compacted-context>"),
                    new Message("user", "volatile tail", null, null, null, Map.of("volatileTail", true)));
            final var excerpt = MemoryExtractor.buildExcerpt(messages);
            assertTrue(excerpt.contains("prefer tabs"), "the user preference must be kept: " + excerpt);
            assertTrue(
                    excerpt.contains("the function reads a config file"),
                    "the plain assistant answer must be kept: " + excerpt);
            assertFalse(excerpt.contains("system prompt"), "the system prompt must be filtered: " + excerpt);
            assertFalse(excerpt.contains("huge JSON"), "tool results must be filtered: " + excerpt);
            assertTrue(
                    !excerpt.contains("tc-2") && !excerpt.contains("/tmp/x"),
                    "assistant tool-call frames must be filtered: " + excerpt);
            assertFalse(excerpt.contains("compacted-context"), "compaction digests must be filtered: " + excerpt);
            assertFalse(excerpt.contains("volatile tail"), "the volatile tail must be filtered: " + excerpt);
        }

        @Test
        void excerptCapDropsAssistantBeforeUserOldestFirst() throws Exception {
            // an over-cap delta must shed assistant messages first (oldest first), never the user's
            // own words; the newest message is always kept
            final var filler = "a".repeat(3200);
            final var messages = List.of(
                    Message.user("user-old " + filler),
                    Message.assistant("assistant-a " + filler, null),
                    Message.assistant("assistant-b " + filler, null),
                    Message.user("user-new " + filler));
            final var excerpt = MemoryExtractor.buildExcerpt(messages);
            final var assistantAFound = excerpt.contains("assistant-a");
            final var assistantBFound = excerpt.contains("assistant-b");
            final var userOldFound = excerpt.contains("user-old");
            final var userNewFound = excerpt.contains("user-new");
            // the excerpt must be under the cap (with a mild slack for the role prefix)
            assertTrue(excerpt.length() <= MAX_EXCERPT_CHARS_FOR_TEST + 200, "excerpt too long: " + excerpt.length());
            // user messages are never dropped while an assistant can still go: if a user survived, all
            // assistants that could be shed must have been; and the newest user message always survives
            assertTrue(userNewFound, "the newest user message must always survive: " + excerpt);
            if (userOldFound) {
                // both users kept means all assistants were under the cap or already dropped before any user
                assertTrue(excerpt.length() <= MAX_EXCERPT_CHARS_FOR_TEST + 200, "unexpected: " + excerpt);
            } else {
                // user-old got dropped only because assistants were exhausted or would exceed alone
                assertTrue(!assistantAFound && !assistantBFound, "assistants must go before any user: " + excerpt);
            }
        }

        @Test
        void recallIntentIsDetectedInUserProse() {
            assertTrue(MemoryExtractor.hasRecallIntent("[user] remember that I prefer tabs\n"), "remember-that");
            assertTrue(MemoryExtractor.hasRecallIntent("[user] don't forget to run mvn -o\n"), "don't forget");
            assertTrue(MemoryExtractor.hasRecallIntent("please keep in mind the discount rule\n"), "keep in mind");
            assertFalse(MemoryExtractor.hasRecallIntent("[user] the build now uses Maven\n"), "no recall phrasings");
            assertFalse(MemoryExtractor.hasRecallIntent(null), "null excerpt never triggers recall");
            assertFalse(MemoryExtractor.hasRecallIntent(""), "blank excerpt never triggers recall");
        }
    }

    public static class CloseExtractionIsolatesRootsForStructuredAndBothPlainFallbacksModule
            extends ConfigurationTestModule {
        public CloseExtractionIsolatesRootsForStructuredAndBothPlainFallbacksModule() {
            super(Map.ofEntries(
                    Map.entry(
                            "yca.storageHome",
                            CloseExtractionIsolatesRootsForStructuredAndBothPlainFallbacksTests.root.toString()),
                    Map.entry(
                            "yca.workingDir",
                            CloseExtractionIsolatesRootsForStructuredAndBothPlainFallbacksTests.root.toString()),
                    Map.entry(
                            "yca.projectDir",
                            CloseExtractionIsolatesRootsForStructuredAndBothPlainFallbacksTests.root.toString()),
                    Map.entry("yca.memory.enabled", "true"),
                    Map.entry("yca.memory.maxChars", "8000"),
                    Map.entry("yca.memory.maxRatio", "0.2"),
                    Map.entry("yca.memory.extractMinChars", "0")));
        }
    }

    @Nested
    @FusionSupport(modules = MinCharsGateSkipsTinyDeltasModule.class)
    class MinCharsGateSkipsTinyDeltasTests {
        @TempDir
        static Path root;

        @Test
        void minCharsGateSkipsTinyDeltas() throws Exception {
            // end-of-turn extraction (asyncExtractNow) honors memory.extractMinChars: a tiny delta is not
            // worth an LLM call. The forced path (forceExtractNow, session close) bypasses the gate,
            // so this only exercises the end-of-turn path.
            final var config = withMemory(configuration, withExtractMinChars(configuration.memory(), 0));
            final var store = new MemoryStore(config);
            final var calls = new AtomicInteger();
            final var chat = new ChatClient() {
                @Override
                public StructuredResult chatStructured(
                        final ChatCompletionRequest request, final String schemaName, final Map<String, Object> schema)
                        throws IOException {
                    calls.incrementAndGet();
                    return new StructuredResult(
                            new ChatResult(Message.assistant("{\"facts\":[]}", null), new Usage(1, 1, 2)), true);
                }
            };
            final var extractor = new MemoryExtractor(
                    chat,
                    config,
                    null,
                    store,
                    mapper,
                    EXECUTOR,
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            // no-op
                        }
                    },
                    Clock.systemUTC(),
                    null,
                    "s-min",
                    null,
                    null, // memory production policy (default: enabled)
                    null,
                    (config).resolveWorkingDir());
            // re-enable the default 1000-char gate for this test: the ungated fixture uses 0 so the
            // async extraction tests fire, but this test wants the gate active
            final var gated = new MemoryExtractor(
                    chat,
                    configuration,
                    null,
                    store,
                    mapper,
                    EXECUTOR,
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            // no-op
                        }
                    },
                    Clock.systemUTC(),
                    null,
                    "s-min",
                    null,
                    null, // memory production policy (default: enabled)
                    null,
                    (configuration).resolveWorkingDir());
            // tiny delta below the default 1000-char gate: no call (end-of-turn path only)
            gated.asyncExtractNow(List.of(Message.user("hi")), ChatSettings.EMPTY);
            EXECUTOR.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(0, calls.get(), "a tiny delta must not fire an end-of-turn extraction");
        }

        @Test
        void forcedExtractionBypassesMinCharsButNotTheWatermark() throws Exception {
            final var config = withMemory(configuration, withExtractMinChars(configuration.memory(), 0));
            // re-enable the min-chars gate for this test (the ungated fixture uses 0)
            final var gatedConfig = configuration;
            final var store = new MemoryStore(gatedConfig);
            final var events = new ArrayList<AgentEvent>();
            final var chat = new ChatClient() {
                @Override
                public StructuredResult chatStructured(
                        final ChatCompletionRequest request, final String schemaName, final Map<String, Object> schema)
                        throws IOException {
                    return new StructuredResult(
                            new ChatResult(
                                    Message.assistant(
                                            "{\"facts\":[{\"text\":\"short preference\",\"scope\":\"project\"}]}",
                                            null),
                                    new Usage(1, 1, 2)),
                            true);
                }
            };
            final var extractor = new MemoryExtractor(
                    chat,
                    gatedConfig,
                    null,
                    store,
                    mapper,
                    EXECUTOR,
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            if (event instanceof AgentEvent agentEvent) {
                                events.add(agentEvent);
                            }
                        }
                    },
                    Clock.systemUTC(),
                    null,
                    "s-forced",
                    null,
                    null, // memory production policy (default: enabled)
                    null,
                    (gatedConfig).resolveWorkingDir());
            // a below-threshold delta must still be extracted when forced (session close)
            final var messages = new ArrayList<Message>();
            messages.add(Message.user("short"));
            // Drain a below-threshold background attempt before close: it must leave the fact pending.
            try (final var executor = Executors.newSingleThreadExecutor()) {
                final var gated = new MemoryExtractor(
                        chat,
                        gatedConfig,
                        null,
                        store,
                        mapper,
                        executor,
                        null,
                        Clock.systemUTC(),
                        null,
                        "s-short-async",
                        null,
                        null,
                        null,
                        (gatedConfig).resolveWorkingDir());
                gated.asyncExtractNow(messages);
                executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
                assertTrue(
                        store.readProject(config.resolveWorkingDir()).isBlank(),
                        "the background pass must honor the threshold");
                assertTrue(gated.forceExtractNow(messages), "the skipped delta must remain eligible at close");
                assertTrue(store.readProject(config.resolveWorkingDir()).contains("short preference"));
                assertFalse(gated.forceExtractNow(messages));
            }
            final var ran = extractor.forceExtractNow(messages, ChatSettings.EMPTY);
            assertTrue(ran, "the forced pass must run on a non-empty delta");
            assertTrue(
                    store.readProject(config.resolveWorkingDir()).contains("short preference"),
                    "the forced pass must save the fact");
            // a second forced pass on the same messages must be a no-op (watermark)
            final var ran2 = extractor.forceExtractNow(messages, ChatSettings.EMPTY);
            assertFalse(ran2, "the forced pass must be a no-op when nothing new was added");
        }
    }

    public static class MinCharsGateSkipsTinyDeltasModule extends ConfigurationTestModule {
        public MinCharsGateSkipsTinyDeltasModule() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", MinCharsGateSkipsTinyDeltasTests.root.toString()),
                    Map.entry("yca.workingDir", MinCharsGateSkipsTinyDeltasTests.root.toString()),
                    Map.entry("yca.projectDir", MinCharsGateSkipsTinyDeltasTests.root.toString()),
                    Map.entry("yca.memory.enabled", "true"),
                    Map.entry("yca.memory.maxChars", "8000"),
                    Map.entry("yca.memory.maxRatio", "0.2"),
                    Map.entry("yca.memory.extractMinChars", "1000")));
        }
    }

    private static MemoryConfiguration withExtractMinChars(
            final MemoryConfiguration baseline, final int extractMinChars) {
        return new MemoryConfiguration(
                baseline.enabled(),
                baseline.mode(),
                baseline.maxChars(),
                baseline.maxRatio(),
                baseline.compactEnabled(),
                extractMinChars,
                baseline.maxLines(),
                baseline.maxKeepLines(),
                baseline.defaultPriority(),
                baseline.extractEveryTurns(),
                baseline.extractOnTodosDone());
    }

    private static CodingAgentConfiguration withMemory(
            final CodingAgentConfiguration baseline, final MemoryConfiguration memory) {
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
                memory,
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

    private static CodingAgentConfiguration withStorageHomeWorkingDirProjectDir(
            final CodingAgentConfiguration baseline,
            final String storageHome,
            final String workingDir,
            final String projectDir) {
        return new CodingAgentConfiguration(
                storageHome,
                baseline.home(),
                workingDir,
                projectDir,
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
}
