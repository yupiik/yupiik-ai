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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.PauseEvent;
import io.yupiik.ia.agent.core.llm.Tool;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.tools.builtin.ReadFileTool;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.model.LlmStreamEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** Public-loop regressions: coverage is what the model received, not what it requested. */
@ExtendWith(TestJsonMapper.class)
class ExplorationPlateauRegressionTest {
    @TempDir
    private static Path testWorkdir;

    @TempDir
    Path home;

    private JsonMapper mapper;
    private Path source;
    private int nextCall;

    @BeforeEach
    void prepare(final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) throws IOException {
        this.mapper = mapper;
        this.configuration = withStorageHomeHome(
                configuration, home.toString(), home.resolve("home").toString());
        source = home.resolve("source.txt");
        Files.write(
                source,
                IntStream.rangeClosed(1, 500).mapToObj(i -> "source line " + i).toList());
    }

    private void assertMixedReset(
            final String name, final ToolCategory category, final boolean readonly, final ToolResult result) {
        final var fixture = fixture(configuration);
        fixture.extraTools.add(tool(name, category, readonly, result));
        final var args = "bash".equals(name)
                ? Map.<String, Object>of("command", "touch generated.txt")
                : Map.<String, Object>of();
        assertEquals(
                "done",
                fixture.run(read(1, 30), read(2, 10), batch(readCall(3, 10), call(name, args)), read(4, 10), answer()));
        assertTrue(
                fixture.events.stream()
                        .anyMatch(e -> e instanceof AgentEvent.ToolFinished finished
                                && finished.output().contains(result.output())),
                "the mixed call must actually finish");
        fixture.assertNoErrors();
    }

    private Fixture fixture(final CodingAgentConfiguration configuration) {
        return new Fixture(configuration);
    }

    private Message read(final int offset, final int limit) {
        return batch(readCall(offset, limit));
    }

    private ToolCall readCall(final int offset, final int limit) {
        return call("read", Map.of("path", source.toString(), "offset", offset, "limit", limit));
    }

    private ToolCall call(final String name, final Map<String, Object> args) {
        return new ToolCall(
                "call-" + nextCall++, "function", new ToolCall.FunctionCall(name, mapper.toString(args)), 0);
    }

    private static Message batch(final ToolCall... calls) {
        return Message.assistant(null, List.of(calls));
    }

    private static Message answer() {
        return Message.assistant("done", null);
    }

    private static EmbeddedTool tool(
            final String name, final ToolCategory category, final boolean readonly, final ToolResult result) {
        return new EmbeddedTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description(final Path workdir) {
                return "scripted " + name;
            }

            @Override
            public ToolCategory category() {
                return category;
            }

            @Override
            public boolean readonly() {
                return readonly;
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of("type", "object");
            }

            @Override
            public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                return result;
            }
        };
    }

    private static final class MutableTodos extends TodoStore {
        private List<TodoItem> items = List.of();

        @Override
        public List<TodoItem> all() {
            return items;
        }
    }

    private final class Fixture {
        private final CodingAgentConfiguration config;
        private final List<AgentEvent> events = new ArrayList<>();
        private final List<List<Message>> requests = new ArrayList<>();
        private final List<EmbeddedTool> extraTools = new ArrayList<>();
        private final MutableTodos todos = new MutableTodos();
        private EmbeddedTool readOverride;
        private Consumer<Integer> beforeReply = index -> {};

        private Fixture(final CodingAgentConfiguration config) {
            this.config = config;
        }

        private String run(final Message... script) {
            final var replies = new ArrayDeque<>(List.of(script));
            final ChatClient client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled) {
                    beforeReply.accept(requests.size());
                    requests.add(List.copyOf(request.messages()));
                    // AssertionError must escape the loop's normal provider-error handling: running
                    // out of script is a regression, never an alternative successful stop condition.
                    assertTrue(!replies.isEmpty(), "unexpected extra model request: " + requests.size());
                    final var reply = replies.removeFirst();
                    onDelta.accept(new LlmStreamEvent.End(reply.toolCalls() == null ? "stop" : "tool_calls", null));
                    return reply;
                }
            };
            final var tools = new ArrayList<EmbeddedTool>();
            tools.add(readOverride == null ? new ReadFileTool(config) : readOverride);
            tools.addAll(extraTools);
            final var registry = new ToolRegistry(null) {
                @Override
                public List<Tool> toWireTools(final Path workdir) {
                    return toWireTools(workdir, tool -> true);
                }

                @Override
                public List<Tool> toWireTools(final Path workdir, final Predicate<EmbeddedTool> filter) {
                    return tools.stream()
                            .filter(filter)
                            .map(tool -> Tool.function(tool.name(), tool.description(home), tool.schema()))
                            .toList();
                }

                @Override
                public EmbeddedTool tool(final String name) {
                    return tools.stream()
                            .filter(tool -> tool.name().equals(name))
                            .findFirst()
                            .orElse(null);
                }
            };
            final var executions = new ToolExecutionService(null, null, null, null, null, null) {
                @Override
                public ToolResult execute(
                        final EmbeddedTool tool, final Map<String, Object> args, final ToolContext context) {
                    return tool.execute(args, context);
                }
            };
            final var permissions = new PermissionService(config, (config).resolveWorkingDir());
            permissions.setMode(AgentMode.BUILD);
            final var loop = new AgentLoop(
                    client,
                    registry,
                    executions,
                    config,
                    permissions,
                    new UsageTracker() {
                        @Override
                        public void record(final Usage usage) {}

                        @Override
                        public int estimateContext(final List<Message> messages, final int extraToolTokens) {
                            return 100;
                        }

                        @Override
                        public double contextPercent(final int estimatedTokens) {
                            return 1;
                        }

                        @Override
                        public boolean lastResolutionWasAuto() {
                            return false;
                        }
                    },
                    null,
                    new ContextManager() {
                        @Override
                        public boolean pruneNeeded(final List<Message> messages, final int extraToolTokens) {
                            return false;
                        }

                        @Override
                        public boolean compactNeeded(final List<Message> messages, final int extraToolTokens) {
                            return false;
                        }
                    },
                    null,
                    new SessionStore() {
                        @Override
                        public void append(final String id, final SessionEntry entry) {}
                    },
                    new SystemPromptBuilder(testWorkdir) {
                        @Override
                        public String build(final String prompt, final AgentConfiguration agent) {
                            return "system prompt";
                        }
                    },
                    new MemoryExtractor(testWorkdir) {
                        @Override
                        public void asyncExtractNow(final List<Message> messages) {}

                        @Override
                        public void asyncExtractNow(final List<Message> messages, final ChatSettings settings) {}

                        @Override
                        public void asyncExtractNow(
                                final List<Message> messages, final ChatSettings settings, final boolean eligible) {}
                    },
                    new StatusReporter(),
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            if (event instanceof AgentEvent agentEvent) {
                                events.add(agentEvent);
                            }
                        }
                    },
                    mapper,
                    new StructuralCodeGraph() {
                        @Override
                        public void ensureReadyAsync(final Path root) {}
                    },
                    new ProviderManager(config, mapper),
                    null,
                    Clock.systemUTC(),
                    null,
                    todos,
                    null,
                    null,
                    (config).resolveWorkingDir(),
                    new DisabledSandbox((config).resolveWorkingDir()));
            return loop.run("inspect the source and complete the work", null, "plateau-regression", null, () -> false);
        }

        private long notices(final String text) {
            return events.stream()
                    .filter(e -> e instanceof AgentEvent.Notice notice
                            && notice.message().contains(text))
                    .count();
        }

        private void assertNoErrors() {
            assertTrue(
                    events.stream().noneMatch(AgentEvent.Error.class::isInstance),
                    () -> "unexpected errors: " + events);
        }

        private void assertError(final String text) {
            assertTrue(
                    events.stream()
                            .anyMatch(e -> e instanceof AgentEvent.Error error
                                    && error.message().contains(text)),
                    () -> "missing error '" + text + "': " + events);
        }

        private void assertCappedAt(final int requestsAllowed) {
            assertEquals(
                    requestsAllowed,
                    requests.size(),
                    "nudges/round gates must consume the current window, not replace it");
            assertError("Maximum iterations reached");
        }
    }

    private CodingAgentConfiguration configuration;

    @Nested
    @FusionSupport(modules = NegativeOneNudgesAtFirstRedundantRoundWithoutStoppingModule.class)
    class NegativeOneNudgesAtFirstRedundantRoundWithoutStoppingTests {
        @TempDir
        static Path root;

        @Test
        void negativeOneNudgesAtFirstRedundantRoundWithoutStopping() {
            final var fixture = fixture(configuration);
            assertEquals("done", fixture.run(read(1, 20), read(2, 10), answer()));
            assertEquals(1, fixture.notices("exploration-plateau nudge"));
            assertEquals(3, fixture.requests.size());
            fixture.assertNoErrors();
        }

        @Test
        void plateauNudgeRemainsOneShotAfterNovelCoverageResetsTheStreak() {
            final var fixture = fixture(configuration);
            assertEquals("done", fixture.run(read(1, 20), read(2, 10), read(100, 20), read(101, 10), answer()));
            assertEquals(1, fixture.notices("exploration-plateau nudge"));
            fixture.assertNoErrors();
        }
    }

    public static class NegativeOneNudgesAtFirstRedundantRoundWithoutStoppingModule extends ConfigurationTestModule {
        public NegativeOneNudgesAtFirstRedundantRoundWithoutStoppingModule() {
            super(Map.ofEntries(
                    Map.entry(
                            "yca.storageHome",
                            NegativeOneNudgesAtFirstRedundantRoundWithoutStoppingTests.root.toString()),
                    Map.entry(
                            "yca.home",
                            NegativeOneNudgesAtFirstRedundantRoundWithoutStoppingTests.root
                                    .resolve("home")
                                    .toString()),
                    Map.entry("yca.mode", "build"),
                    Map.entry("yca.permissions.mode", "yolo"),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "default"),
                    Map.entry("yca.provider.providers.0.baseUrl", "http://localhost:1/v1/chat/completions"),
                    Map.entry("yca.provider.providers.0.apiKey", "test-key"),
                    Map.entry("yca.provider.providers.0.model", "test-model"),
                    Map.entry("yca.provider.providers.0.models.length", "1"),
                    Map.entry("yca.provider.providers.0.models.0.model", "test-model"),
                    Map.entry("yca.llm.maxIterations", "20"),
                    Map.entry("yca.llm.explorationNudgeRounds", "0"),
                    Map.entry("yca.llm.retriesMaxAttempts", "1"),
                    Map.entry("yca.todos.continueOnUnfinished", "false"),
                    Map.entry("yca.llm.plateauRounds", "-1")));
        }
    }

    @Nested
    @FusionSupport(modules = NegativeOneWithFeedbackNoneNeverNudgesModule.class)
    class NegativeOneWithFeedbackNoneNeverNudgesTests {
        @TempDir
        static Path root;

        @Test
        void negativeOneWithFeedbackNoneNeverNudges() {
            final var fixture = fixture(configuration);
            assertEquals("done", fixture.run(read(1, 20), read(2, 10), read(3, 5), answer()));
            assertEquals(0, fixture.notices("exploration-plateau nudge"));
            fixture.assertNoErrors();
        }
    }

    public static class NegativeOneWithFeedbackNoneNeverNudgesModule extends ConfigurationTestModule {
        public NegativeOneWithFeedbackNoneNeverNudgesModule() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", NegativeOneWithFeedbackNoneNeverNudgesTests.root.toString()),
                    Map.entry(
                            "yca.home",
                            NegativeOneWithFeedbackNoneNeverNudgesTests.root
                                    .resolve("home")
                                    .toString()),
                    Map.entry("yca.mode", "build"),
                    Map.entry("yca.permissions.mode", "yolo"),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "default"),
                    Map.entry("yca.provider.providers.0.baseUrl", "http://localhost:1/v1/chat/completions"),
                    Map.entry("yca.provider.providers.0.apiKey", "test-key"),
                    Map.entry("yca.provider.providers.0.model", "test-model"),
                    Map.entry("yca.provider.providers.0.models.length", "1"),
                    Map.entry("yca.provider.providers.0.models.0.model", "test-model"),
                    Map.entry("yca.llm.maxIterations", "20"),
                    Map.entry("yca.llm.explorationNudgeRounds", "0"),
                    Map.entry("yca.llm.retriesMaxAttempts", "1"),
                    Map.entry("yca.todos.continueOnUnfinished", "false"),
                    Map.entry("yca.llm.plateauRounds", "-1"),
                    Map.entry("yca.provider.providers.0.models.0.behavior.plateauFeedback", "none")));
        }
    }

    @Nested
    @FusionSupport(modules = PositiveLimitKeepsHardStopWithFeedbackNoneModule.class)
    class PositiveLimitKeepsHardStopWithFeedbackNoneTests {
        @TempDir
        static Path root;

        @Test
        void positiveLimitKeepsHardStopWithFeedbackNone() {
            final var fixture = fixture(configuration);
            assertNull(fixture.run(read(1, 30), read(2, 10), read(3, 10), answer()));
            assertEquals(3, fixture.requests.size());
            assertEquals(0, fixture.notices("exploration-plateau nudge"));
            fixture.assertError("Exploration plateau");
        }

        @Test
        void mixedRedundantReadAndFailureResetsPlateau() {
            assertMixedReset("failing", ToolCategory.READ, true, ToolResult.error("temporary read failure"));
        }

        @Test
        void mixedRedundantReadAndPendingWorkResetsPlateau() {
            assertMixedReset("pending_work", ToolCategory.READ, true, ToolResult.pending("worker is still running"));
        }

        @Test
        void mixedRedundantReadAndMutatingBashResetsPlateau() {
            assertMixedReset("bash", ToolCategory.BASH, false, ToolResult.ok("updated generated file"));
        }

        @Test
        void mixedRedundantReadAndUnclassifiedSuccessfulWorkResetsPlateau() {
            assertMixedReset("inspect_remote", ToolCategory.READ, true, ToolResult.ok("remote inspection complete"));
        }

        @Test
        void mixedRedundantReadAndRejectedUnknownToolResetsPlateau() {
            final var fixture = fixture(configuration);
            assertEquals(
                    "done",
                    fixture.run(
                            read(1, 30),
                            read(2, 10),
                            batch(readCall(3, 10), call("not_registered", Map.of())),
                            read(4, 10),
                            answer()));
            assertTrue(fixture.events.stream()
                    .anyMatch(e -> e instanceof AgentEvent.ToolFinished finished
                            && finished.output().contains("Unknown tool")));
            fixture.assertNoErrors();
        }
    }

    public static class PositiveLimitKeepsHardStopWithFeedbackNoneModule extends ConfigurationTestModule {
        public PositiveLimitKeepsHardStopWithFeedbackNoneModule() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", PositiveLimitKeepsHardStopWithFeedbackNoneTests.root.toString()),
                    Map.entry(
                            "yca.home",
                            PositiveLimitKeepsHardStopWithFeedbackNoneTests.root
                                    .resolve("home")
                                    .toString()),
                    Map.entry("yca.mode", "build"),
                    Map.entry("yca.permissions.mode", "yolo"),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "default"),
                    Map.entry("yca.provider.providers.0.baseUrl", "http://localhost:1/v1/chat/completions"),
                    Map.entry("yca.provider.providers.0.apiKey", "test-key"),
                    Map.entry("yca.provider.providers.0.model", "test-model"),
                    Map.entry("yca.provider.providers.0.models.length", "1"),
                    Map.entry("yca.provider.providers.0.models.0.model", "test-model"),
                    Map.entry("yca.llm.maxIterations", "20"),
                    Map.entry("yca.llm.explorationNudgeRounds", "0"),
                    Map.entry("yca.llm.retriesMaxAttempts", "1"),
                    Map.entry("yca.todos.continueOnUnfinished", "false"),
                    Map.entry("yca.llm.plateauRounds", "2"),
                    Map.entry("yca.provider.providers.0.models.0.behavior.plateauFeedback", "none")));
        }
    }

    @Nested
    @FusionSupport(modules = PositiveLimitNudgesOnceBeforeStoppingModule.class)
    class PositiveLimitNudgesOnceBeforeStoppingTests {
        @TempDir
        static Path root;

        @Test
        void positiveLimitNudgesOnceBeforeStopping() {
            final var fixture = fixture(configuration);
            assertNull(fixture.run(read(1, 30), read(2, 10), read(3, 10), read(4, 10), read(5, 10), answer()));
            assertEquals(5, fixture.requests.size());
            assertEquals(1, fixture.notices("exploration-plateau nudge"));
            fixture.assertError("Exploration plateau");
        }
    }

    public static class PositiveLimitNudgesOnceBeforeStoppingModule extends ConfigurationTestModule {
        public PositiveLimitNudgesOnceBeforeStoppingModule() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", PositiveLimitNudgesOnceBeforeStoppingTests.root.toString()),
                    Map.entry(
                            "yca.home",
                            PositiveLimitNudgesOnceBeforeStoppingTests.root
                                    .resolve("home")
                                    .toString()),
                    Map.entry("yca.mode", "build"),
                    Map.entry("yca.permissions.mode", "yolo"),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "default"),
                    Map.entry("yca.provider.providers.0.baseUrl", "http://localhost:1/v1/chat/completions"),
                    Map.entry("yca.provider.providers.0.apiKey", "test-key"),
                    Map.entry("yca.provider.providers.0.model", "test-model"),
                    Map.entry("yca.provider.providers.0.models.length", "1"),
                    Map.entry("yca.provider.providers.0.models.0.model", "test-model"),
                    Map.entry("yca.llm.maxIterations", "20"),
                    Map.entry("yca.llm.explorationNudgeRounds", "0"),
                    Map.entry("yca.llm.retriesMaxAttempts", "1"),
                    Map.entry("yca.todos.continueOnUnfinished", "false"),
                    Map.entry("yca.llm.plateauRounds", "4"),
                    Map.entry("yca.provider.providers.0.models.0.behavior.plateauFeedback", "nudge_2")));
        }
    }

    @Nested
    @FusionSupport(modules = SuccessfulWriteInvalidatesPreviouslyDeliveredReadCoverageModule.class)
    class SuccessfulWriteInvalidatesPreviouslyDeliveredReadCoverageTests {
        @TempDir
        static Path root;

        @Test
        void successfulWriteInvalidatesPreviouslyDeliveredReadCoverage() {
            final var fixture = fixture(configuration);
            fixture.extraTools.add(tool("write_file", ToolCategory.EDIT, false, ToolResult.ok("updated")));
            assertEquals(
                    "done",
                    fixture.run(
                            read(1, 30),
                                    batch(call("write_file", Map.of("path", source.toString(), "content", "updated"))),
                            read(2, 10), answer()));
            fixture.assertNoErrors();
        }

        @Test
        void adjacentSmallReadsInsideTheSameFormerBucketAreNovel() {
            final var fixture = fixture(configuration);
            assertEquals("done", fixture.run(read(1, 10), read(11, 10), read(21, 10), answer()));
            fixture.assertNoErrors();
        }

        @Test
        void partiallyOverlappingReadIsNovelWhenItDeliversAnUnseenTail() {
            final var fixture = fixture(configuration);
            assertEquals("done", fixture.run(read(1, 20), read(15, 20), answer()));
            fixture.assertNoErrors();
        }

        @Test
        void containedReadIsRedundantEvenWhenItsRequestedBucketDiffers() {
            final var fixture = fixture(configuration);
            assertNull(fixture.run(read(1, 400), read(201, 10), answer()));
            assertEquals(2, fixture.requests.size());
            fixture.assertError("Exploration plateau");
        }

        @Test
        void adjacentDeliveredIntervalsMergeForLaterContainedRead() {
            final var fixture = fixture(configuration);
            assertNull(fixture.run(read(1, 20), read(21, 20), read(10, 20), answer()));
            assertEquals(3, fixture.requests.size());
            fixture.assertError("Exploration plateau");
        }

        @Test
        void requestedLimitDoesNotInventCoverageBeyondEof() {
            // The first read asks for 1000 lines but only receives 500. Appending and reading the
            // new tail must remain novel, even though that tail lay inside the old requested range.
            final var fixture = fixture(configuration);
            // A scripted client callback changes the file, not a tool/write marker that could reset
            // the coverage tracker and hide a requested-versus-delivered regression.
            fixture.beforeReply = index -> {
                if (index == 1) {
                    try {
                        Files.writeString(source, "new line\n", StandardOpenOption.APPEND);
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            };
            assertEquals("done", fixture.run(read(1, 1000), read(501, 1), answer()));
            fixture.assertNoErrors();
        }

        @Test
        void unknownReadOutputIsNovelRatherThanGuessedFromArguments() {
            final var fixture = fixture(configuration);
            fixture.readOverride = tool("read", ToolCategory.READ, true, ToolResult.ok("opaque read payload"));
            assertEquals("done", fixture.run(read(1, 20), read(2, 10), read(3, 5), answer()));
            fixture.assertNoErrors();
        }
    }

    public static class SuccessfulWriteInvalidatesPreviouslyDeliveredReadCoverageModule
            extends ConfigurationTestModule {
        public SuccessfulWriteInvalidatesPreviouslyDeliveredReadCoverageModule() {
            super(Map.ofEntries(
                    Map.entry(
                            "yca.storageHome",
                            SuccessfulWriteInvalidatesPreviouslyDeliveredReadCoverageTests.root.toString()),
                    Map.entry(
                            "yca.home",
                            SuccessfulWriteInvalidatesPreviouslyDeliveredReadCoverageTests.root
                                    .resolve("home")
                                    .toString()),
                    Map.entry("yca.mode", "build"),
                    Map.entry("yca.permissions.mode", "yolo"),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "default"),
                    Map.entry("yca.provider.providers.0.baseUrl", "http://localhost:1/v1/chat/completions"),
                    Map.entry("yca.provider.providers.0.apiKey", "test-key"),
                    Map.entry("yca.provider.providers.0.model", "test-model"),
                    Map.entry("yca.provider.providers.0.models.length", "1"),
                    Map.entry("yca.provider.providers.0.models.0.model", "test-model"),
                    Map.entry("yca.llm.maxIterations", "20"),
                    Map.entry("yca.llm.explorationNudgeRounds", "0"),
                    Map.entry("yca.llm.retriesMaxAttempts", "1"),
                    Map.entry("yca.todos.continueOnUnfinished", "false"),
                    Map.entry("yca.llm.plateauRounds", "1"),
                    Map.entry("yca.provider.providers.0.models.0.behavior.plateauFeedback", "none")));
        }
    }

    @Nested
    @FusionSupport(modules = MixedRedundantReadAndDoomRefusalResetsPlateauModule.class)
    class MixedRedundantReadAndDoomRefusalResetsPlateauTests {
        @TempDir
        static Path root;

        @Test
        void mixedRedundantReadAndDoomRefusalResetsPlateau() {
            final var fixture = fixture(configuration);
            fixture.extraTools.add(tool("bash", ToolCategory.BASH, false, ToolResult.ok("stable command output")));
            final var args = Map.<String, Object>of("command", "touch generated.txt");
            assertEquals(
                    "done",
                    fixture.run(
                            batch(call("bash", args)),
                            read(1, 30),
                            read(2, 10),
                            batch(readCall(3, 10), call("bash", args)),
                            read(4, 10),
                            answer()));
            assertEquals(1, fixture.notices("doom-loop refusal"));
            fixture.assertNoErrors();
        }
    }

    public static class MixedRedundantReadAndDoomRefusalResetsPlateauModule extends ConfigurationTestModule {
        public MixedRedundantReadAndDoomRefusalResetsPlateauModule() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", MixedRedundantReadAndDoomRefusalResetsPlateauTests.root.toString()),
                    Map.entry(
                            "yca.home",
                            MixedRedundantReadAndDoomRefusalResetsPlateauTests.root
                                    .resolve("home")
                                    .toString()),
                    Map.entry("yca.mode", "build"),
                    Map.entry("yca.permissions.mode", "yolo"),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "default"),
                    Map.entry("yca.provider.providers.0.baseUrl", "http://localhost:1/v1/chat/completions"),
                    Map.entry("yca.provider.providers.0.apiKey", "test-key"),
                    Map.entry("yca.provider.providers.0.model", "test-model"),
                    Map.entry("yca.provider.providers.0.models.length", "1"),
                    Map.entry("yca.provider.providers.0.models.0.model", "test-model"),
                    Map.entry("yca.llm.maxIterations", "20"),
                    Map.entry("yca.llm.explorationNudgeRounds", "0"),
                    Map.entry("yca.llm.retriesMaxAttempts", "1"),
                    Map.entry("yca.todos.continueOnUnfinished", "false"),
                    Map.entry("yca.llm.plateauRounds", "2"),
                    Map.entry("yca.provider.providers.0.models.0.behavior.plateauFeedback", "none"),
                    Map.entry("yca.tools.repeatSuccessThreshold", "1")));
        }
    }

    @Nested
    @FusionSupport(modules = PlateauNudgeDoesNotRenewTheIterationWindowModule.class)
    class PlateauNudgeDoesNotRenewTheIterationWindowTests {
        @TempDir
        static Path root;

        @Test
        void plateauNudgeDoesNotRenewTheIterationWindow() {
            final var fixture = fixture(configuration);
            assertNull(fixture.run(read(1, 30), read(2, 10), read(3, 10), answer()));
            assertEquals(1, fixture.notices("exploration-plateau nudge"));
            fixture.assertCappedAt(3);
        }
    }

    public static class PlateauNudgeDoesNotRenewTheIterationWindowModule extends ConfigurationTestModule {
        public PlateauNudgeDoesNotRenewTheIterationWindowModule() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", PlateauNudgeDoesNotRenewTheIterationWindowTests.root.toString()),
                    Map.entry(
                            "yca.home",
                            PlateauNudgeDoesNotRenewTheIterationWindowTests.root
                                    .resolve("home")
                                    .toString()),
                    Map.entry("yca.mode", "build"),
                    Map.entry("yca.permissions.mode", "yolo"),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "default"),
                    Map.entry("yca.provider.providers.0.baseUrl", "http://localhost:1/v1/chat/completions"),
                    Map.entry("yca.provider.providers.0.apiKey", "test-key"),
                    Map.entry("yca.provider.providers.0.model", "test-model"),
                    Map.entry("yca.provider.providers.0.models.length", "1"),
                    Map.entry("yca.provider.providers.0.models.0.model", "test-model"),
                    Map.entry("yca.llm.maxIterations", "3"),
                    Map.entry("yca.llm.explorationNudgeRounds", "0"),
                    Map.entry("yca.llm.retriesMaxAttempts", "1"),
                    Map.entry("yca.todos.continueOnUnfinished", "false"),
                    Map.entry("yca.llm.plateauRounds", "-1")));
        }
    }

    @Nested
    @FusionSupport(modules = ExplorationNudgeDoesNotRenewTheIterationWindowModule.class)
    class ExplorationNudgeDoesNotRenewTheIterationWindowTests {
        @TempDir
        static Path root;

        @Test
        void explorationNudgeDoesNotRenewTheIterationWindow() {
            final var fixture = fixture(configuration);
            assertNull(fixture.run(read(1, 10), read(11, 10), read(21, 10), answer()));
            assertEquals(1, fixture.notices("exploration nudge"));
            fixture.assertCappedAt(3);
        }
    }

    public static class ExplorationNudgeDoesNotRenewTheIterationWindowModule extends ConfigurationTestModule {
        public ExplorationNudgeDoesNotRenewTheIterationWindowModule() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", ExplorationNudgeDoesNotRenewTheIterationWindowTests.root.toString()),
                    Map.entry(
                            "yca.home",
                            ExplorationNudgeDoesNotRenewTheIterationWindowTests.root
                                    .resolve("home")
                                    .toString()),
                    Map.entry("yca.mode", "build"),
                    Map.entry("yca.permissions.mode", "yolo"),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "default"),
                    Map.entry("yca.provider.providers.0.baseUrl", "http://localhost:1/v1/chat/completions"),
                    Map.entry("yca.provider.providers.0.apiKey", "test-key"),
                    Map.entry("yca.provider.providers.0.model", "test-model"),
                    Map.entry("yca.provider.providers.0.models.length", "1"),
                    Map.entry("yca.provider.providers.0.models.0.model", "test-model"),
                    Map.entry("yca.llm.maxIterations", "3"),
                    Map.entry("yca.llm.explorationNudgeRounds", "2"),
                    Map.entry("yca.llm.retriesMaxAttempts", "1"),
                    Map.entry("yca.todos.continueOnUnfinished", "false")));
        }
    }

    @Nested
    @FusionSupport(modules = DoomLoopNudgeDoesNotRenewTheIterationWindowModule.class)
    class DoomLoopNudgeDoesNotRenewTheIterationWindowTests {
        @TempDir
        static Path root;

        @Test
        void doomLoopNudgeDoesNotRenewTheIterationWindow() {
            final var fixture = fixture(configuration);
            fixture.extraTools.add(tool("bash", ToolCategory.BASH, false, ToolResult.ok("stable command output")));
            final var args = Map.<String, Object>of("command", "touch generated.txt");
            assertNull(fixture.run(batch(call("bash", args)), batch(call("bash", args)), read(1, 10), answer()));
            assertEquals(1, fixture.notices("doom-loop refusal"));
            fixture.assertCappedAt(3);
        }
    }

    public static class DoomLoopNudgeDoesNotRenewTheIterationWindowModule extends ConfigurationTestModule {
        public DoomLoopNudgeDoesNotRenewTheIterationWindowModule() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", DoomLoopNudgeDoesNotRenewTheIterationWindowTests.root.toString()),
                    Map.entry(
                            "yca.home",
                            DoomLoopNudgeDoesNotRenewTheIterationWindowTests.root
                                    .resolve("home")
                                    .toString()),
                    Map.entry("yca.mode", "build"),
                    Map.entry("yca.permissions.mode", "yolo"),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "default"),
                    Map.entry("yca.provider.providers.0.baseUrl", "http://localhost:1/v1/chat/completions"),
                    Map.entry("yca.provider.providers.0.apiKey", "test-key"),
                    Map.entry("yca.provider.providers.0.model", "test-model"),
                    Map.entry("yca.provider.providers.0.models.length", "1"),
                    Map.entry("yca.provider.providers.0.models.0.model", "test-model"),
                    Map.entry("yca.llm.maxIterations", "3"),
                    Map.entry("yca.llm.explorationNudgeRounds", "0"),
                    Map.entry("yca.llm.retriesMaxAttempts", "1"),
                    Map.entry("yca.todos.continueOnUnfinished", "false"),
                    Map.entry("yca.tools.repeatSuccessThreshold", "1")));
        }
    }

    @Nested
    @FusionSupport(modules = TextEndRoundGateDoesNotRenewTheIterationWindowModule.class)
    class TextEndRoundGateDoesNotRenewTheIterationWindowTests {
        @TempDir
        static Path root;

        @Test
        void textEndRoundGateDoesNotRenewTheIterationWindow() {
            final var fixture = fixture(configuration);
            fixture.todos.items = List.of(new TodoItem("finish the work", "in_progress"));
            assertNull(fixture.run(
                    read(1, 10),
                    Message.assistant("still working", null),
                    Message.assistant("not finished yet", null),
                    answer()));
            assertEquals(2, fixture.notices("continuing the round automatically"));
            fixture.assertCappedAt(3);
        }
    }

    public static class TextEndRoundGateDoesNotRenewTheIterationWindowModule extends ConfigurationTestModule {
        public TextEndRoundGateDoesNotRenewTheIterationWindowModule() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", TextEndRoundGateDoesNotRenewTheIterationWindowTests.root.toString()),
                    Map.entry(
                            "yca.home",
                            TextEndRoundGateDoesNotRenewTheIterationWindowTests.root
                                    .resolve("home")
                                    .toString()),
                    Map.entry("yca.mode", "build"),
                    Map.entry("yca.permissions.mode", "yolo"),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "default"),
                    Map.entry("yca.provider.providers.0.baseUrl", "http://localhost:1/v1/chat/completions"),
                    Map.entry("yca.provider.providers.0.apiKey", "test-key"),
                    Map.entry("yca.provider.providers.0.model", "test-model"),
                    Map.entry("yca.provider.providers.0.models.length", "1"),
                    Map.entry("yca.provider.providers.0.models.0.model", "test-model"),
                    Map.entry("yca.llm.maxIterations", "3"),
                    Map.entry("yca.llm.explorationNudgeRounds", "0"),
                    Map.entry("yca.llm.retriesMaxAttempts", "1"),
                    Map.entry("yca.todos.continueOnUnfinished", "false"),
                    Map.entry("yca.provider.providers.0.models.0.behavior.turnContinuesOnTextEnd", "true")));
        }
    }

    @Nested
    @FusionSupport(modules = UnboundedWindowStillAllowsAnAnswerAfterBothNudgesModule.class)
    class UnboundedWindowStillAllowsAnAnswerAfterBothNudgesTests {
        @TempDir
        static Path root;

        @Test
        void unboundedWindowStillAllowsAnAnswerAfterBothNudges() {
            final var fixture = fixture(configuration);
            assertEquals("done", fixture.run(read(1, 30), read(2, 10), read(100, 10), read(101, 5), answer()));
            assertEquals(1, fixture.notices("exploration nudge"));
            assertEquals(1, fixture.notices("exploration-plateau nudge"));
            assertEquals(5, fixture.requests.size());
            fixture.assertNoErrors();
        }
    }

    public static class UnboundedWindowStillAllowsAnAnswerAfterBothNudgesModule extends ConfigurationTestModule {
        public UnboundedWindowStillAllowsAnAnswerAfterBothNudgesModule() {
            super(Map.ofEntries(
                    Map.entry(
                            "yca.storageHome", UnboundedWindowStillAllowsAnAnswerAfterBothNudgesTests.root.toString()),
                    Map.entry(
                            "yca.home",
                            UnboundedWindowStillAllowsAnAnswerAfterBothNudgesTests.root
                                    .resolve("home")
                                    .toString()),
                    Map.entry("yca.mode", "build"),
                    Map.entry("yca.permissions.mode", "yolo"),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "default"),
                    Map.entry("yca.provider.providers.0.baseUrl", "http://localhost:1/v1/chat/completions"),
                    Map.entry("yca.provider.providers.0.apiKey", "test-key"),
                    Map.entry("yca.provider.providers.0.model", "test-model"),
                    Map.entry("yca.provider.providers.0.models.length", "1"),
                    Map.entry("yca.provider.providers.0.models.0.model", "test-model"),
                    Map.entry("yca.llm.maxIterations", "-1"),
                    Map.entry("yca.llm.explorationNudgeRounds", "1"),
                    Map.entry("yca.llm.retriesMaxAttempts", "1"),
                    Map.entry("yca.todos.continueOnUnfinished", "false"),
                    Map.entry("yca.llm.plateauRounds", "-1")));
        }
    }

    @Nested
    @FusionSupport(modules = TodoAutoContinuationStillStartsAFreshWindowAtTheCapModule.class)
    class TodoAutoContinuationStillStartsAFreshWindowAtTheCapTests {
        @TempDir
        static Path root;

        @Test
        void todoAutoContinuationStillStartsAFreshWindowAtTheCap() {
            final var fixture = fixture(configuration);
            fixture.todos.items = List.of(new TodoItem("finish the work", "in_progress"));
            fixture.extraTools.add(new EmbeddedTool() {
                @Override
                public String name() {
                    return "close_todos";
                }

                @Override
                public String description(final Path workdir) {
                    return "complete the work";
                }

                @Override
                public ToolCategory category() {
                    return ToolCategory.EDIT;
                }

                @Override
                public Map<String, Object> schema() {
                    return Map.of("type", "object");
                }

                @Override
                public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                    fixture.todos.items = List.of(new TodoItem("finish the work", "completed"));
                    return ToolResult.ok("todo completed");
                }
            });
            assertEquals(
                    "done", fixture.run(read(1, 10), read(11, 10), batch(call("close_todos", Map.of())), answer()));
            assertEquals(4, fixture.requests.size());
            assertEquals(1, fixture.notices("continuing automatically"));
            fixture.assertNoErrors();
        }
    }

    public static class TodoAutoContinuationStillStartsAFreshWindowAtTheCapModule extends ConfigurationTestModule {
        public TodoAutoContinuationStillStartsAFreshWindowAtTheCapModule() {
            super(Map.ofEntries(
                    Map.entry(
                            "yca.storageHome",
                            TodoAutoContinuationStillStartsAFreshWindowAtTheCapTests.root.toString()),
                    Map.entry(
                            "yca.home",
                            TodoAutoContinuationStillStartsAFreshWindowAtTheCapTests.root
                                    .resolve("home")
                                    .toString()),
                    Map.entry("yca.mode", "build"),
                    Map.entry("yca.permissions.mode", "yolo"),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "default"),
                    Map.entry("yca.provider.providers.0.baseUrl", "http://localhost:1/v1/chat/completions"),
                    Map.entry("yca.provider.providers.0.apiKey", "test-key"),
                    Map.entry("yca.provider.providers.0.model", "test-model"),
                    Map.entry("yca.provider.providers.0.models.length", "1"),
                    Map.entry("yca.provider.providers.0.models.0.model", "test-model"),
                    Map.entry("yca.llm.maxIterations", "2"),
                    Map.entry("yca.llm.explorationNudgeRounds", "0"),
                    Map.entry("yca.llm.retriesMaxAttempts", "1"),
                    Map.entry("yca.todos.continueOnUnfinished", "true"),
                    Map.entry("yca.todos.maxContinues", "1")));
        }
    }

    private static CodingAgentConfiguration withStorageHomeHome(
            final CodingAgentConfiguration baseline, final String storageHome, final String home) {
        return new CodingAgentConfiguration(
                storageHome,
                home,
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
