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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.PruningConfiguration;
import io.yupiik.ia.agent.core.context.CompactionCoordinator;
import io.yupiik.ia.agent.core.context.CompactionResult;
import io.yupiik.ia.agent.core.context.ContextCompression;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.ContextView;
import io.yupiik.ia.agent.core.context.TokenEstimator;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.events.ModelSwitchedEvent;
import io.yupiik.ia.agent.core.events.ModelSwitchingEvent;
import io.yupiik.ia.agent.core.events.SessionEndedEvent;
import io.yupiik.ia.agent.core.events.UserPromptSubmitEvent;
import io.yupiik.ia.agent.core.events.YcaEvent;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.CodexAuthTemplateHelper;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.MidStreamFailureException;
import io.yupiik.ia.agent.core.llm.PauseEvent;
import io.yupiik.ia.agent.core.llm.Tool;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.notifications.NotificationService;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.runtime.Cancellations;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.shell.ThinkTagSplitter;
import io.yupiik.ia.agent.core.todo.PlanTodoSeeder;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.FileMutation;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.tools.builtin.ManageContextTool;
import io.yupiik.ia.agent.core.tools.builtin.MemoryWriteTool;
import io.yupiik.ia.agent.core.usage.QuotaSnapshotStore;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.LlmHttpFailureException;
import io.yupiik.llm.client.codex.CodexOAuthConfig;
import io.yupiik.llm.client.codex.model.CodexTokenStore;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmStreamEvent.End;
import io.yupiik.llm.client.model.LlmStreamEvent.TextDelta;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@ExtendWith(TestJsonMapper.class)
class AgentLoopTest {
    @TempDir
    private static Path testWorkdir;

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private Path storageHome;

    private CodingAgentConfiguration baseline;

    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    private final List<AgentEvent> events = new ArrayList<>();
    private final AtomicReference<Consumer<PauseEvent>> pauseListenerReference = new AtomicReference<>();
    private final AtomicReference<io.yupiik.ia.agent.core.llm.Usage> recordedUsage = new AtomicReference<>();
    private PermissionService permissionServiceOverride;
    private ProviderManager providerManagerOverride;
    private FakeClock throughputClock;
    private Consumer<AgentEvent> eventObserver;
    private Consumer<Object> emittedObserver;
    private Consumer<ChatSettings> usageAttachmentObserver;
    private NotificationService notifications;

    private EmbeddedTool testTool(final String name, final List<String> executed) {
        return new EmbeddedTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description(final Path workdir) {
                return "test tool";
            }

            @Override
            public ToolCategory category() {
                return ToolCategory.READ;
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of();
            }

            @Override
            public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                executed.add(name);
                return ToolResult.ok("ran " + name);
            }
        };
    }

    private long pauseEvents() {
        return events.stream()
                .filter(e -> (e instanceof AgentEvent.Pause p && p.message().contains("retrying"))
                        || (e instanceof AgentEvent.Pause retry
                                && retry.message().contains("Rate limit")))
                .count();
    }

    private AgentEvent.TurnMetrics lastTurnMetrics() {
        final var published =
                events.stream().filter(AgentEvent.TurnMetrics.class::isInstance).toList();
        assertFalse(published.isEmpty(), "the turn must publish its metrics");
        return (AgentEvent.TurnMetrics) published.getLast();
    }

    private AgentEvent.Throughput throughputAcrossRequests(
            final io.yupiik.llm.client.model.Usage first, final io.yupiik.llm.client.model.Usage second) {
        throughputClock = new FakeClock();
        final var replies = new ArrayDeque<>(List.of(
                new ScriptedReply(null, "tool_calls", false, first) {
                    @Override
                    Message toMessage() {
                        return toolCallReply("t1", "noop").toMessage();
                    }
                },
                new ScriptedReply("done", "stop", false, second)));
        final var delegate = chatClient(replies, new ArrayList<>());
        final ChatClient client = new ChatClient() {
            @Override
            public Message stream(
                    final ChatCompletionRequest request,
                    final Consumer<PauseEvent> pauseListener,
                    final Consumer<LlmStreamEvent> onDelta,
                    final BooleanSupplier cancelled)
                    throws IOException, InterruptedException {
                throughputClock.advance(100);
                return delegate.stream(request, pauseListener, onDelta, cancelled);
            }
        };
        loop(client, null, baseline).run("do it", null, "s1", null, () -> false);
        return lastTurnMetrics().throughput();
    }

    /**
     * A ChatClient stub replaying a single reply while reproducing the timing of a real generation:
     * how long the call takes and which pause the resilience layer reports before it.
     *
     * @param reply the reply to replay.
     * @param workMs how long the generation actually takes.
     * @param pauseMs the idle wait reported through the pause listener.
     * @return the stub client.
     */
    private ChatClient timedChatClient(final ScriptedReply reply, final long workMs, final long pauseMs) {
        throughputClock = new FakeClock();
        return new ChatClient() {
            @Override
            public Message stream(
                    final ChatCompletionRequest request,
                    final Consumer<PauseEvent> pauseListener,
                    final Consumer<LlmStreamEvent> onDelta,
                    final BooleanSupplier cancelled)
                    throws IOException {
                pauseListenerReference.set(pauseListener);
                if (pauseListener != null && pauseMs > 0) {
                    pauseListener.accept(
                            PauseEvent.throttle("Rate limit reached, waiting ~" + pauseMs + "ms", pauseMs));
                }
                throughputClock.advance(workMs + pauseMs);
                if (reply.usage() != null) {
                    onDelta.accept(new End(null, reply.usage()));
                }
                if (reply.finishReason() != null) {
                    onDelta.accept(new End(reply.finishReason(), null));
                }
                return reply.toMessage();
            }
        };
    }

    private static final class FakeClock extends Clock {
        private long nanos;

        private void advance(final long ms) {
            nanos += ms * 1_000_000L;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            if (!getZone().equals(zone)) {
                throw new IllegalArgumentException("Only UTC is needed by this fixture");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.EPOCH.plusNanos(nanos);
        }
    }

    private static ScriptedReply toolCallReply(final String callId, final String tool) {
        return toolCallReply(callId, tool, "{}");
    }

    private static ScriptedReply toolCallReply(final String callId, final String tool, final String args) {
        return new ScriptedReply(null, "tool_calls", false) {
            @Override
            Message toMessage() {
                return new Message(
                        "assistant",
                        null,
                        null,
                        List.of(new ToolCall(callId, "function", new ToolCall.FunctionCall(tool, args), 0)),
                        null,
                        null);
            }
        };
    }

    /**
     * Asserts the Anthropic pairing invariant on a request's neutral messages: every assistant tool
     * call is immediately answered by a {@code tool}-role result carrying the same id, before any
     * non-{@code tool} message intervenes. A violation would make Anthropic/Azure AI Gateway reject
     * the request ("tool_use ids were found without tool_result blocks immediately after").
     */
    private static void assertAllToolCallsAreAnsweredByContiguousResults(final List<Message> messages) {
        final var pendingIds = new HashSet<String>();
        for (final var message : messages) {
            if ("assistant".equals(message.role())) {
                pendingIds.clear();
                if (message.toolCalls() != null) {
                    for (final var call : message.toolCalls()) {
                        if (call != null && call.id() != null) {
                            pendingIds.add(call.id());
                        }
                    }
                }
            } else if ("tool".equals(message.role())) {
                pendingIds.remove(message.toolCallId());
            } else {
                pendingIds.clear();
            }
        }
        assertTrue(
                pendingIds.isEmpty(),
                "some assistant tool calls were never answered contiguously by their tool_result: " + pendingIds
                        + " in request " + messages);
    }

    /** Index of the first assistant message whose tool calls include {@code callId}, or {@code -1}. */
    private static int indexOfAssistantCall(final List<Message> messages, final String callId) {
        for (var i = 0; i < messages.size(); i++) {
            final var message = messages.get(i);
            if (!"assistant".equals(message.role()) || message.toolCalls() == null) {
                continue;
            }
            for (final var call : message.toolCalls()) {
                if (call != null && callId.equals(call.id())) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** A registry exposing a numbered, paged {@code read} tool for the plateau guard. */
    private ToolRegistry readFileRegistry() {
        return registryOf(new EmbeddedTool() {
            @Override
            public String name() {
                return "read";
            }

            @Override
            public String description(final Path workdir) {
                return "read a file";
            }

            @Override
            public ToolCategory category() {
                return ToolCategory.READ;
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of("path", Map.of("type", "string"), "offset", Map.of("type", "integer"));
            }

            @Override
            public boolean readonly() {
                return true;
            }

            @Override
            public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                return fixtureRead(args);
            }
        });
    }

    /** A small finite file with the same line-numbered paging contract as the built-in read tool. */
    private ToolResult fixtureRead(final Map<String, Object> args) {
        final var path = args.get("path") instanceof final String p ? p : "?";
        final var offset = args.get("offset") instanceof final Number n ? Math.max(1, n.intValue()) : 1;
        final var limit = args.get("limit") instanceof final Number n ? n.intValue() : 2000;
        final var output = new StringBuilder();
        for (var line = offset; line <= Math.min(30L, (long) offset + limit - 1); line++) {
            output.append(String.format("%6d\tcontent of %s, line %d%n", line, path, line));
        }
        return ToolResult.ok(output.toString());
    }

    /** A registry exposing read + write_file (writes reset the plateau streak). */
    private ToolRegistry readWriteRegistry() {
        return registryOf(
                new EmbeddedTool() {
                    @Override
                    public String name() {
                        return "read";
                    }

                    @Override
                    public String description(final Path workdir) {
                        return "read a file";
                    }

                    @Override
                    public ToolCategory category() {
                        return ToolCategory.READ;
                    }

                    @Override
                    public Map<String, Object> schema() {
                        return Map.of("path", Map.of("type", "string"), "offset", Map.of("type", "integer"));
                    }

                    @Override
                    public boolean readonly() {
                        return true;
                    }

                    @Override
                    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                        return fixtureRead(args);
                    }
                },
                new EmbeddedTool() {
                    @Override
                    public String name() {
                        return "write_file";
                    }

                    @Override
                    public String description(final Path workdir) {
                        return "write a file";
                    }

                    @Override
                    public ToolCategory category() {
                        return ToolCategory.EDIT;
                    }

                    @Override
                    public Map<String, Object> schema() {
                        return Map.of(
                                "path", Map.of("type", "string"),
                                "content", Map.of("type", "string"));
                    }

                    @Override
                    public boolean readonly() {
                        return false;
                    }

                    @Override
                    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                        return ToolResult.ok("written");
                    }
                });
    }

    private static MutableTodoStore mutableTodoStore(final TodoItem... initial) {
        return new MutableTodoStore(List.of(initial));
    }

    private static final class MutableTodoStore extends TodoStore {
        private List<TodoItem> todos;

        private MutableTodoStore(final List<TodoItem> todos) {
            this.todos = List.copyOf(todos);
        }

        @Override
        public List<TodoItem> all() {
            return todos;
        }

        @Override
        public synchronized List<TodoItem> replace(final List<TodoItem> items) {
            todos = List.copyOf(items);
            return all();
        }
    }

    /** {@code noop} changes nothing, {@code close_todos} completes the whole list like a done write_todo. */
    private static ToolRegistry todoToolsRegistry(final TodoStore todoStore) {
        return new ToolRegistry(null) {
            @Override
            public List<EmbeddedTool> tools() {
                return List.of(tool("noop"), tool("close_todos"));
            }

            @Override
            public EmbeddedTool tool(final String name) {
                if (!"noop".equals(name) && !"close_todos".equals(name)) {
                    return null;
                }
                return new EmbeddedTool() {
                    @Override
                    public String name() {
                        return name;
                    }

                    @Override
                    public String description(final Path workdir) {
                        return "test tool";
                    }

                    @Override
                    public ToolCategory category() {
                        // READ so these stand-in tools are exempt from the doom-loop guard: the
                        // iteration/todo tests drive them repeatedly purely to exhaust the window budget,
                        // not to exercise the repeat breaker. (Reads have no coverage key, so the plateau
                        // guard never fires for them either.)
                        return ToolCategory.READ;
                    }

                    @Override
                    public Map<String, Object> schema() {
                        return Map.of();
                    }

                    @Override
                    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                        if ("close_todos".equals(name)) {
                            todoStore.replace(List.of(new TodoItem("done step", "completed")));
                        }
                        return ToolResult.ok("done");
                    }
                };
            }
        };
    }

    private List<AgentEvent.Compacted> compactedEvents() {
        return events.stream()
                .filter(AgentEvent.Compacted.class::isInstance)
                .map(AgentEvent.Compacted.class::cast)
                .toList();
    }

    private static boolean hasHardCompressionNudge(final List<Message> request) {
        final var tail = request.getLast();
        return String.valueOf(tail.content()).toLowerCase(Locale.ROOT).contains("working context reached");
    }

    /** A registry serving {@code read} (READ/readonly) and {@code write_file} (EDIT) fakes. */
    private ToolRegistry fileToolsRegistry() {
        return new ToolRegistry(null) {
            @Override
            public List<EmbeddedTool> tools() {
                return List.of(tool("read"), tool("write_file"), tool("apply_patch"));
            }

            @Override
            public EmbeddedTool tool(final String name) {
                final var read = "read".equals(name);
                if (!read && !"write_file".equals(name) && !"apply_patch".equals(name)) {
                    return null;
                }
                return new EmbeddedTool() {
                    @Override
                    public String name() {
                        return name;
                    }

                    @Override
                    public String description(final Path workdir) {
                        return "test tool";
                    }

                    @Override
                    public ToolCategory category() {
                        return read ? ToolCategory.READ : ToolCategory.EDIT;
                    }

                    @Override
                    public Map<String, Object> schema() {
                        return Map.of();
                    }

                    @Override
                    public boolean readonly() {
                        return read;
                    }

                    @Override
                    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                        if (read) {
                            return ToolResult.ok("old content");
                        }
                        final var path = context.resolvePath(String.valueOf(args.get("path")))
                                .toAbsolutePath()
                                .normalize();
                        final var kind = String.valueOf(args.getOrDefault("mutation", "UPDATE"));
                        final var mutations = "RENAME".equals(kind)
                                ? List.of(
                                        new FileMutation(path, FileMutation.Kind.DELETE, null),
                                        new FileMutation(
                                                path.resolveSibling("renamed.txt"), FileMutation.Kind.CREATE, null))
                                : List.of(new FileMutation(path, FileMutation.Kind.valueOf(kind), null));
                        return new ToolResult(
                                "partial".equals(args.get("content")) ? ToolResult.Status.ERROR : ToolResult.Status.OK,
                                "written",
                                mutations);
                    }
                };
            }
        };
    }

    /** Two readonly tools meeting on a barrier: the barrier only passes when both are in flight together. */
    private ToolRegistry barrierRegistry(final CyclicBarrier barrier, final String... names) {
        return new ToolRegistry(null) {
            @Override
            public List<EmbeddedTool> tools() {
                return Arrays.stream(names).map(this::tool).toList();
            }

            @Override
            public EmbeddedTool tool(final String name) {
                if (!List.of(names).contains(name)) {
                    return null;
                }
                return new EmbeddedTool() {
                    @Override
                    public String name() {
                        return name;
                    }

                    @Override
                    public String description(final Path workdir) {
                        return "test tool";
                    }

                    @Override
                    public ToolCategory category() {
                        return ToolCategory.READ;
                    }

                    @Override
                    public Map<String, Object> schema() {
                        return Map.of();
                    }

                    @Override
                    public boolean readonly() {
                        return true;
                    }

                    @Override
                    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                        try {
                            barrier.await(250, TimeUnit.MILLISECONDS);
                        } catch (final Exception e) {
                            return ToolResult.error("not concurrent: " + e);
                        }
                        return ToolResult.ok("done-" + name.substring(name.length() - 1));
                    }
                };
            }
        };
    }

    private ToolExecutionService directExecutions() {
        return new ToolExecutionService(null, null, null, null, null, null) {
            @Override
            public ToolResult execute(
                    final EmbeddedTool tool, final Map<String, Object> args, final ToolContext context) {
                return tool.execute(args, context);
            }
        };
    }

    /** A registry exposing one wire tool, so a captured payload has a non-empty tools list. */
    private ToolRegistry wireToolsRegistry() {
        return new ToolRegistry(null) {
            @Override
            public List<Tool> toWireTools(final Path workdir) {
                return List.of(Tool.function(
                        "read",
                        "read a file from disk",
                        Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string")))));
            }

            @Override
            public EmbeddedTool tool(final String name) {
                return null;
            }
        };
    }

    /** A registry exposing {@code read} (READ) and {@code write_file} (EDIT) wire tools. */
    private ToolRegistry readWriteWireToolsRegistry() {
        return new ToolRegistry(null) {
            @Override
            public List<EmbeddedTool> tools() {
                return List.of(tool("read"), tool("write_file"));
            }

            @Override
            public List<Tool> toWireTools(final Path workdir) {
                return List.of(
                        Tool.function(
                                "read",
                                "read a file from disk",
                                Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string")))),
                        Tool.function(
                                "write_file",
                                "write a file to disk",
                                Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string")))));
            }

            @Override
            public EmbeddedTool tool(final String name) {
                final var read = "read".equals(name);
                if (!read && !"write_file".equals(name)) {
                    return null;
                }
                return new EmbeddedTool() {
                    @Override
                    public String name() {
                        return name;
                    }

                    @Override
                    public String description(final Path workdir) {
                        return "test tool";
                    }

                    @Override
                    public ToolCategory category() {
                        return read ? ToolCategory.READ : ToolCategory.EDIT;
                    }

                    @Override
                    public Map<String, Object> schema() {
                        return Map.of();
                    }

                    @Override
                    public boolean readonly() {
                        return read;
                    }

                    @Override
                    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                        return ToolResult.ok(read ? "old content" : "written");
                    }
                };
            }
        };
    }

    private ToolRegistry registryOf(final EmbeddedTool... tools) {
        return new ToolRegistry(null) {
            @Override
            public List<EmbeddedTool> tools() {
                return List.of(tools);
            }

            @Override
            public EmbeddedTool tool(final String name) {
                for (final var t : tools) {
                    if (t.name().equals(name)) {
                        return t;
                    }
                }
                return null;
            }
        };
    }

    /** A registry exposing a stateless read-only {@code echo} tool returning {@code done}. */
    private ToolRegistry echoRegistry() {
        return registryOf(new EmbeddedTool() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description(final Path workdir) {
                return "echoes";
            }

            @Override
            public ToolCategory category() {
                return ToolCategory.READ;
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of("path", Map.of("type", "string"));
            }

            @Override
            public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                return new ToolResult(true, "done");
            }
        });
    }

    /** A STATE-CHANGING (non-read) {@code echo}-like tool: subject to the doom-loop guard, unlike reads. */
    private ToolRegistry stateChangingRegistry() {
        return registryOf(new EmbeddedTool() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description(final Path workdir) {
                return "state-changing echo";
            }

            @Override
            public ToolCategory category() {
                return ToolCategory.EDIT;
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of("path", Map.of("type", "string"));
            }

            @Override
            public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                return new ToolResult(true, "done");
            }
        });
    }

    /**
     * A registry exposing both a {@code read} tool and the {@code bash} tool, so exploration-spiral
     * tests can interleave novel reads with bash commands (read-only for coverage, mutating for reset).
     */
    private ToolRegistry readBashRegistry() {
        return registryOf(
                new EmbeddedTool() {
                    @Override
                    public String name() {
                        return "read";
                    }

                    @Override
                    public String description(final Path workdir) {
                        return "read a file";
                    }

                    @Override
                    public ToolCategory category() {
                        return ToolCategory.READ;
                    }

                    @Override
                    public Map<String, Object> schema() {
                        return Map.of("path", Map.of("type", "string"), "offset", Map.of("type", "integer"));
                    }

                    private int readCall;

                    @Override
                    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                        final var path = args.get("path") instanceof final String p ? p : "?";
                        final var offset = args.get("offset") instanceof final Number n ? n.intValue() : 0;
                        return ToolResult.ok("content of " + path + " @" + offset + " #" + (readCall++));
                    }
                },
                new EmbeddedTool() {
                    @Override
                    public String name() {
                        return "bash";
                    }

                    @Override
                    public String description(final Path workdir) {
                        return "run a command";
                    }

                    @Override
                    public ToolCategory category() {
                        return ToolCategory.BASH;
                    }

                    @Override
                    public Map<String, Object> schema() {
                        return Map.of("command", Map.of("type", "string"));
                    }

                    private int bashCall;

                    @Override
                    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                        return ToolResult.ok("ran " + args.get("command") + " #" + (bashCall++));
                    }
                });
    }

    /** Whether a request sequence carries the injected doom-loop strategy nudge (refusal surfaced to the model). */
    private boolean hasDoomLoopNudge(final List<List<Message>> requests) {
        return requests.stream()
                .flatMap(List::stream)
                .anyMatch(m -> "user".equals(m.role())
                        && m.content() != null
                        && m.content().toString().contains("same already-known result"));
    }

    private ChatClient scriptedToolClient(final List<List<Message>> requests, final List<ToolCall> firstReplyCalls) {
        final var calls = new AtomicInteger();
        return new ChatClient() {
            @Override
            public Message stream(
                    final ChatCompletionRequest request,
                    final Consumer<PauseEvent> pauseListener,
                    final Consumer<LlmStreamEvent> onDelta,
                    final BooleanSupplier cancelled) {
                requests.add(List.copyOf(request.messages()));
                if (calls.incrementAndGet() == 1) {
                    return Message.assistant(null, firstReplyCalls);
                }
                onDelta.accept(new End("stop", null));
                return Message.assistant("all done", null);
            }
        };
    }

    private ContextManager noPruningManager() {
        return new ContextManager() {
            @Override
            public boolean pruneNeeded(final List<Message> messages, final int extraToolTokens) {
                return false;
            }

            @Override
            public boolean compactNeeded(final List<Message> messages, final int extraToolTokens) {
                return false;
            }
        };
    }

    private SessionStore noopStore() {
        return new SessionStore() {
            @Override
            public void append(final String id, final SessionEntry entry) {}
        };
    }

    private AgentLoop loop(final ChatClient client, final TodoStore todoStore, final CodingAgentConfiguration config) {
        return loop(client, todoStore, config, noContextManager(), noSessionStore(), null);
    }

    /** A context manager that never prunes nor compacts, for tests that do not exercise context work. */
    private static ContextManager noContextManager() {
        return new ContextManager() {
            @Override
            public boolean pruneNeeded(final List<Message> messages, final int extraToolTokens) {
                return false;
            }

            @Override
            public boolean compactNeeded(final List<Message> messages, final int extraToolTokens) {
                return false;
            }
        };
    }

    /** A session store dropping every persisted entry, for tests that do not assert on the transcript. */
    private static SessionStore noSessionStore() {
        return new SessionStore() {
            @Override
            public void append(final String id, final SessionEntry entry) {}
        };
    }

    private AgentLoop loop(
            final ChatClient client,
            final TodoStore todoStore,
            final CodingAgentConfiguration config,
            final ContextManager contextManager,
            final SessionStore sessionStore,
            final ContextView contextView) {
        return loop(client, todoStore, config, contextManager, sessionStore, contextView, null, null, null);
    }

    private AgentLoop loop(
            final ChatClient client,
            final TodoStore todoStore,
            final CodingAgentConfiguration config,
            final ContextManager contextManager,
            final SessionStore sessionStore,
            final ContextView contextView,
            final ToolRegistry registry,
            final ToolExecutionService executions,
            final ExecutorService executor) {
        return loop(
                client,
                todoStore,
                config,
                contextManager,
                sessionStore,
                contextView,
                registry,
                executions,
                executor,
                null);
    }

    private AgentLoop loop(
            final ChatClient client,
            final TodoStore todoStore,
            final CodingAgentConfiguration config,
            final ContextManager contextManager,
            final SessionStore sessionStore,
            final ContextView contextView,
            final ToolRegistry registry,
            final ToolExecutionService executions,
            final ExecutorService executor,
            final SystemPromptBuilder promptBuilder) {
        return loop(
                client,
                todoStore,
                config,
                contextManager,
                sessionStore,
                contextView,
                registry,
                executions,
                executor,
                promptBuilder,
                null);
    }

    private AgentLoop loop(
            final ChatClient client,
            final TodoStore todoStore,
            final CodingAgentConfiguration config,
            final ContextManager contextManager,
            final SessionStore sessionStore,
            final ContextView contextView,
            final ToolRegistry registry,
            final ToolExecutionService executions,
            final ExecutorService executor,
            final SystemPromptBuilder promptBuilder,
            final QuotaSnapshotStore quotaStore) {
        return loop(
                client,
                todoStore,
                config,
                contextManager,
                sessionStore,
                contextView,
                registry,
                executions,
                executor,
                promptBuilder,
                quotaStore,
                null);
    }

    private AgentLoop loop(
            final ChatClient client,
            final TodoStore todoStore,
            final CodingAgentConfiguration config,
            final ContextManager contextManager,
            final SessionStore sessionStore,
            final ContextView contextView,
            final ToolRegistry registry,
            final ToolExecutionService executions,
            final ExecutorService executor,
            final SystemPromptBuilder promptBuilder,
            final QuotaSnapshotStore quotaStore,
            final String sessionId) {
        events.clear();
        final var resolvedRegistry = registry == null
                ? new ToolRegistry(null) {
                    @Override
                    public List<EmbeddedTool> tools() {
                        return List.of();
                    }

                    @Override
                    public EmbeddedTool tool(final String name) {
                        return null;
                    }
                }
                : registry;
        final var resolvedExecutions =
                executions == null ? new ToolExecutionService(null, null, null, null, null, null) {} : executions;
        return new AgentLoop(
                client,
                resolvedRegistry,
                resolvedExecutions,
                config,
                permissionServiceOverride != null
                        ? permissionServiceOverride
                        : new PermissionService(config, (config).resolveWorkingDir()),
                new UsageTracker() {
                    @Override
                    public void attach(final ChatSettings settings) {
                        if (usageAttachmentObserver != null) {
                            usageAttachmentObserver.accept(settings);
                        }
                        super.attach(settings);
                    }

                    @Override
                    public void record(final io.yupiik.ia.agent.core.llm.Usage usage) {
                        recordedUsage.set(usage);
                    }

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

                    @Override
                    public int rememberShrinkOnOverflow() {
                        return 1000;
                    }
                },
                quotaStore,
                // quota store
                contextManager,
                contextView,
                sessionStore,
                promptBuilder == null
                        ? new SystemPromptBuilder(testWorkdir) {
                            @Override
                            public String build(final String prompt, final AgentConfiguration agent) {
                                return "system prompt";
                            }
                        }
                        : promptBuilder,
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
                        if (emittedObserver != null) {
                            emittedObserver.accept(event);
                        }
                        synchronized (events) {
                            if (event instanceof AgentEvent agentEvent) {
                                events.add(agentEvent);
                                if (eventObserver != null) {
                                    eventObserver.accept(agentEvent);
                                }
                            }
                        }
                    }
                },
                mapper,
                new StructuralCodeGraph() {
                    @Override
                    public void ensureReadyAsync(final Path root) {}
                },
                new ProviderManager() {
                    @Override
                    public ResolvedModel resolveDefault() {
                        return providerManagerOverride == null
                                ? new ResolvedModel(null, "test-model")
                                : providerManagerOverride.resolveDefault();
                    }

                    @Override
                    public ResolvedModel resolve(final String modelReference) {
                        // the bare fixture has no configuration: only a test-provided override can
                        // resolve a reference; a null override conservatively uses the test default model
                        return providerManagerOverride == null
                                ? new ResolvedModel(null, "test-model")
                                : providerManagerOverride.resolve(modelReference);
                    }

                    @Override
                    public String toRoutedReference(final String modelReference) {
                        // the bare fixture has no configuration: only a test-provided override can
                        // pin a bare name to a declaring provider; without one the bare name is kept
                        return providerManagerOverride == null
                                ? modelReference
                                : providerManagerOverride.toRoutedReference(modelReference);
                    }

                    @Override
                    public boolean memoryUsesMainModel(final ChatSettings settings) {
                        // the bare fixture has no configuration: only a test-provided override can
                        // resolve the models; a null override conservatively reads as same-model (LAZY)
                        return providerManagerOverride == null || providerManagerOverride.memoryUsesMainModel(settings);
                    }

                    @Override
                    public ProviderConfiguration provider(final String id) {
                        // the bare fixture has no configuration: only a test-provided override can
                        // answer a per-tab provider lookup (null keeps the default-provider path)
                        return providerManagerOverride == null ? null : providerManagerOverride.provider(id);
                    }

                    @Override
                    public int plateauRounds(final ProviderConfiguration provider, final String model) {
                        return providerManagerOverride == null
                                ? super.plateauRounds(provider, model)
                                : providerManagerOverride.plateauRounds(provider, model);
                    }

                    @Override
                    public int maxIterationsOverride(final ProviderConfiguration provider, final String model) {
                        return providerManagerOverride == null
                                ? super.maxIterationsOverride(provider, model)
                                : providerManagerOverride.maxIterationsOverride(provider, model);
                    }
                },
                null,
                throughputClock == null ? Clock.systemUTC() : throughputClock,
                null,
                todoStore,
                executor,
                sessionId,
                null,
                null,
                null,
                throughputClock == null
                        ? new PrecisionDurationMarkerProvider()
                        : new PrecisionDurationMarkerProvider() {
                            @Override
                            public long now() {
                                return throughputClock.nanos;
                            }
                        },
                null,
                notifications,
                null,
                null,
                null,
                (config).resolveWorkingDir(),
                new DisabledSandbox((config).resolveWorkingDir()));
    }

    /**
     * A ChatClient stub replaying scripted replies: each stream() call polls the next one, emits its
     * finish reason (if any) through the delta stream and, when set, fires the registered pause
     * listener once as the resilience layer would during a retry backoff.
     */
    private ChatClient chatClient(final Deque<ScriptedReply> replies, final List<List<Message>> requests) {
        return chatClient(replies, requests, null);
    }

    private ChatClient chatClient(
            final Deque<ScriptedReply> replies,
            final List<List<Message>> requests,
            final List<ChatSettings> settingsList) {
        return new ChatClient() {
            @Override
            public Message stream(
                    final ChatCompletionRequest request,
                    final Consumer<PauseEvent> pauseListener,
                    final Consumer<LlmStreamEvent> onDelta,
                    final BooleanSupplier cancelled)
                    throws IOException {
                pauseListenerReference.set(pauseListener);
                final var reply = replies.poll();
                if (reply == null) {
                    throw new IllegalStateException("no scripted reply left");
                }
                requests.add(List.copyOf(request.messages()));
                if (settingsList != null) {
                    settingsList.add(request.settings());
                }
                if (pauseListener != null && reply.firePauses()) {
                    pauseListener.accept(PauseEvent.retry("LLM call failed, retrying in ~2s (attempt 2)", 2000));
                    pauseListener.accept(PauseEvent.throttle("Rate limit reached, waiting ~5s", 5000));
                }
                if (reply.error() != null) {
                    throw reply.error();
                }
                if (reply.ioError() != null) {
                    throw reply.ioError();
                }
                if (reply.usage() != null) {
                    onDelta.accept(new End(null, reply.usage()));
                }
                if (reply.finishReason() != null) {
                    onDelta.accept(new End(reply.finishReason(), null));
                }
                return reply.toMessage();
            }
        };
    }

    private static MidStreamFailureException failure(final String message) {
        return new MidStreamFailureException(message, new IOException("broken pipe"), "partial", null);
    }

    private static class ScriptedReply {
        private final String content;
        private final String finishReason;
        private final boolean firePauses;
        private final RuntimeException error;
        private final IOException ioError;
        private final io.yupiik.llm.client.model.Usage usage;

        private ScriptedReply(
                final String content,
                final String finishReason,
                final boolean firePauses,
                final RuntimeException error,
                final IOException ioError,
                final io.yupiik.llm.client.model.Usage usage) {
            this.content = content;
            this.finishReason = finishReason;
            this.firePauses = firePauses;
            this.error = error;
            this.ioError = ioError;
            this.usage = usage;
        }

        ScriptedReply(final String content, final String finishReason, final boolean firePauses) {
            this(content, finishReason, firePauses, null, null, null);
        }

        ScriptedReply(
                final String content,
                final String finishReason,
                final boolean firePauses,
                final io.yupiik.llm.client.model.Usage usage) {
            this(content, finishReason, firePauses, null, null, usage);
        }

        ScriptedReply(final String content, final String finishReason, final RuntimeException error) {
            this(content, finishReason, false, error, null, null);
        }

        ScriptedReply(final IOException ioError) {
            this(null, null, false, null, ioError, null);
        }

        String content() {
            return content;
        }

        String finishReason() {
            return finishReason;
        }

        boolean firePauses() {
            return firePauses;
        }

        RuntimeException error() {
            return error;
        }

        IOException ioError() {
            return ioError;
        }

        io.yupiik.llm.client.model.Usage usage() {
            return usage;
        }

        Message toMessage() {
            return new Message("assistant", content, null, null, null, null);
        }
    }

    private static String diagnostic(final String name) throws Exception {
        final Method m = AgentLoop.class.getDeclaredMethod("unknownToolDiagnostic", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, name);
    }

    @AfterAll
    static void closeExecutor() {
        EXECUTOR.shutdownNow();
    }

    private void bindConfiguration(final CodingAgentConfiguration configuration) {
        baseline = configuration;
        storageHome = Path.of(configuration.storageHome());
    }

    private static Map<String, String> sourceValues(
            final Path storageHome,
            final boolean configured,
            final boolean continueOnTruncation,
            final Map<String, String> overrides) {
        final var values = new HashMap<String, String>();
        values.put("yca.storageHome", storageHome.toString());
        values.put("yca.home", storageHome.resolve("home").toString());
        values.put("yca.workingDir", testWorkdir.toString());
        if (configured) {
            values.put("yca.provider.providers.length", "1");
            values.put("yca.provider.providers.0.id", "default");
            values.put("yca.provider.providers.0.baseUrl", "http://localhost:1/v1/chat/completions");
            values.put("yca.provider.providers.0.apiKey", "test-key");
            values.put("yca.provider.providers.0.model", "test-model");
            values.put("yca.provider.providers.0.api", "auto");
            values.put("yca.provider.providers.0.timeoutSeconds", "5");
            values.put("yca.llm.retriesMaxAttempts", "1");
            values.put("yca.llm.retriesBaseDelayMs", "1");
            values.put("yca.llm.maxIterations", "10");
            values.put("yca.llm.continueOnTruncation", Boolean.toString(continueOnTruncation));
            values.put("yca.mode", "build");
        } else {
            values.put("yca.contextWindow", "1000000");
        }
        values.putAll(overrides);
        return values;
    }

    @Nested
    @FusionSupport(modules = LifecycleSource.class)
    class LifecycleScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void userTurnAcknowledgesSessionBeforePublishingTurnEnd() {
            final var calls = new ArrayList<String>();
            notifications = new NotificationService(baseline, null) {
                @Override
                public void onSessionInteraction(final String sessionId) {
                    calls.add("ack:" + sessionId);
                }

                @Override
                public void onTurnEnd(
                        final String sessionId,
                        final String title,
                        final String model,
                        final String provider,
                        final boolean success,
                        final String body) {
                    calls.add("turn_end:" + sessionId);
                }
            };
            final var replies = new ArrayDeque<>(List.of(new ScriptedReply("done", "stop", false)));
            final var loop = loop(
                    chatClient(replies, new ArrayList<>()),
                    null,
                    baseline,
                    noContextManager(),
                    noSessionStore(),
                    null,
                    registryOf(),
                    null,
                    null);
            try {
                assertEquals("done", loop.run("check", null, "session", null, () -> false));
            } finally {
                loop.close();
            }
            assertEquals(List.of("ack:session", "turn_end:session"), calls);
        }

        @Test
        void cancellationWhileModelSwitchingHookIsBlockedPreventsCommitButNotAnAdmittedSwitch() throws Exception {
            final var loop = loop(null, null, baseline);
            final var original = ChatSettings.EMPTY.withModel("original");
            loop.updateChatSettings(original);
            final var entered = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            final var cancelled = new AtomicBoolean();
            final var switched = new AtomicInteger();
            final var attached = new AtomicInteger();
            usageAttachmentObserver = ignored -> attached.incrementAndGet();
            emittedObserver = event -> {
                if (event instanceof ModelSwitchingEvent) {
                    entered.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                } else if (event instanceof ModelSwitchedEvent) {
                    switched.incrementAndGet();
                }
            };
            final var pending =
                    EXECUTOR.submit(() -> loop.updateChatSettings(original.withModel("rejected"), cancelled::get));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                cancelled.set(true);
            } finally {
                release.countDown();
            }
            assertFalse(pending.get(5, TimeUnit.SECONDS));
            assertEquals(original, loop.chatSettings());
            assertEquals(0, attached.get());
            assertEquals(0, switched.get());
            cancelled.set(false);
            assertTrue(loop.updateChatSettings(original.withModel("committed"), cancelled::get));
            cancelled.set(true);
            assertEquals("committed", loop.chatSettings().model());
            assertEquals(1, attached.get());
            assertEquals(1, switched.get());
            loop.close();
        }

        @Test
        void cancellationDuringVetoedModelSwitchDoesNotPublishRejectionOrCommitSettings() {
            final var loop = loop(null, null, baseline);
            final var original = ChatSettings.EMPTY.withModel("original");
            loop.updateChatSettings(original);
            final var cancelled = new AtomicBoolean();
            final var emitted = new ArrayList<Object>();
            final var attached = new AtomicInteger();
            usageAttachmentObserver = ignored -> attached.incrementAndGet();
            emittedObserver = event -> {
                emitted.add(event);
                if (event instanceof ModelSwitchingEvent switching) {
                    cancelled.set(true);
                    switching.result().forbid("late rejection");
                }
            };
            try {
                assertFalse(loop.updateChatSettings(original.withModel("rejected"), cancelled::get));
                assertEquals(original, loop.chatSettings());
                assertEquals(0, attached.get());
                assertEquals(1, emitted.size());
                assertInstanceOf(ModelSwitchingEvent.class, emitted.getFirst());
            } finally {
                loop.close();
            }
        }

        @Test
        void closeWhileModelSwitchingHookIsBlockedPreventsSettingsCommit() throws Exception {
            final var loop = loop(null, null, baseline);
            final var original = ChatSettings.EMPTY.withModel("original");
            loop.updateChatSettings(original);
            final var switching = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            final var switched = new AtomicInteger();
            final var ended = new AtomicInteger();
            final var attachments = new AtomicInteger();
            usageAttachmentObserver = ignored -> attachments.incrementAndGet();
            emittedObserver = event -> {
                if (event instanceof ModelSwitchingEvent) {
                    switching.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS), "switch hook was not released");
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                } else if (event instanceof ModelSwitchedEvent) {
                    switched.incrementAndGet();
                } else if (event instanceof SessionEndedEvent) {
                    ended.incrementAndGet();
                }
            };

            final var update = EXECUTOR.submit(() -> loop.updateChatSettings(original.withModel("replacement")));
            try {
                assertTrue(switching.await(5, TimeUnit.SECONDS), "switch hook was not entered");
                // close must return without waiting for the model-switch hook.
                EXECUTOR.submit(loop::close).get(5, TimeUnit.SECONDS);
                assertEquals(1, ended.get());
            } finally {
                release.countDown();
            }
            update.get(5, TimeUnit.SECONDS);
            loop.close();

            assertEquals(original, loop.chatSettings());
            assertEquals(0, attachments.get(), "a closed session must not reattach usage");
            assertEquals(0, switched.get(), "an abandoned switch must not fire ModelSwitched");
            assertEquals(1, ended.get());
        }

        @Test
        void settingsUpdateAfterCloseDoesNotRunHooksOrAttachUsage() {
            final var loop = loop(null, null, baseline);
            final var original = loop.chatSettings();
            loop.close();
            final var emitted = new ArrayList<Object>();
            emittedObserver = emitted::add;
            final var attachments = new AtomicInteger();
            usageAttachmentObserver = ignored -> attachments.incrementAndGet();

            loop.updateChatSettings(ChatSettings.EMPTY.withModel("replacement"));
            loop.updateChatSettings(null);

            assertEquals(original, loop.chatSettings());
            assertEquals(0, attachments.get());
            assertTrue(emitted.isEmpty(), "closed settings updates must not fire any hooks or events");
        }

        @Test
        void concurrentCloseFiresSessionEndedOnlyOnceWithoutHoldingLifecycleLockInHook() throws Exception {
            final var loop = loop(null, null, baseline);
            final var ending = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            final var ended = new AtomicInteger();
            emittedObserver = event -> {
                if (event instanceof SessionEndedEvent) {
                    ended.incrementAndGet();
                    ending.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS), "end hook was not released");
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
            };
            final var firstClose = EXECUTOR.submit(loop::close);
            try {
                assertTrue(ending.await(5, TimeUnit.SECONDS), "end hook was not entered");
                EXECUTOR.submit(loop::close).get(5, TimeUnit.SECONDS);
                assertEquals(1, ended.get());
            } finally {
                release.countDown();
            }
            firstClose.get(5, TimeUnit.SECONDS);
            assertEquals(1, ended.get());
        }

        @Test
        void publishUsageContextEmitsTheProvidedEstimateWithTheResolvedModel() {
            // the /compact manual path calls runtime.loop().publishUsageContext(effective): it must carry
            // the exact effective-context int (not a recomputed one) plus the resolved model, so the
            // frontends' gauge flips to the identical figure the compaction-progress notice prints
            final var loop = loop(
                    chatClient(new ArrayDeque<>(List.of(new ScriptedReply("ok", "stop", false))), new ArrayList<>()),
                    null,
                    baseline,
                    noContextManager(),
                    noSessionStore(),
                    null);
            loop.run("hello", null, "s1", null, () -> false);
            events.clear();

            loop.publishUsageContext(12_345);

            final var pushed = events.stream()
                    .filter(AgentEvent.UsageUpdated.class::isInstance)
                    .map(AgentEvent.UsageUpdated.class::cast)
                    .toList();
            assertEquals(1, pushed.size(), "publishUsageContext must emit one UsageUpdated: " + events);
            assertEquals(
                    12_345, pushed.get(0).estimatedContextTokens(), "the provided estimate must be carried verbatim");
            assertEquals("test-model", pushed.get(0).model(), "the resolved model must be attached");
            assertEquals("s1", pushed.get(0).sessionId(), "the event must be routed to the session");
        }
    }

    public static class LifecycleSource extends ConfigurationTestModule {
        public LifecycleSource() {
            super(sourceValues(LifecycleScenario.storageHome, false, true, Map.of()));
        }
    }

    @Nested
    @FusionSupport(modules = DefaultSource.class)
    class DefaultScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void hookContextsFollowSessionRootsForSwitchPromptAndClose(@TempDir final Path tmp) {
            final var config = withProjectDir(baseline, tmp.resolve("default").toString());
            final var hooks = new ArrayList<YcaEvent>();
            emittedObserver = event -> {
                if (event instanceof final YcaEvent hook) {
                    hooks.add(hook);
                }
            };
            for (final var root : List.of(tmp.resolve("b"), tmp.resolve("a"))) {
                hooks.clear();
                final var sessionConfig = withWorkingDirProjectDir(
                        baseline, root.toString(), tmp.resolve("default").toString());
                final var replies = new ArrayDeque<>(List.of(new ScriptedReply("done", "stop", false)));
                final var loop = loop(
                        chatClient(replies, new ArrayList<>()),
                        null,
                        sessionConfig,
                        noContextManager(),
                        noSessionStore(),
                        null,
                        registryOf(),
                        null,
                        null,
                        new SystemPromptBuilder(sessionConfig, null, null, null, null, null, null, null, null, root));

                loop.updateChatSettings(ChatSettings.EMPTY.withModel("replacement"));
                assertEquals("done", loop.run("check", null, "session", null, () -> false));
                loop.close();

                assertTrue(hooks.stream().anyMatch(ModelSwitchingEvent.class::isInstance));
                assertTrue(hooks.stream().anyMatch(ModelSwitchedEvent.class::isInstance));
                assertTrue(hooks.stream().anyMatch(UserPromptSubmitEvent.class::isInstance));
                assertTrue(hooks.stream().anyMatch(SessionEndedEvent.class::isInstance));
                hooks.forEach(hook ->
                        assertEquals(root.toString(), hook.eventContext().cwd(), hook.id()));
            }
            hooks.clear();
            loop(null, null, config).close();
            assertEquals(
                    config.resolveWorkingDir().toString(),
                    hooks.getLast().eventContext().cwd());
        }

        @Test
        void unknownToolDiagnosticNamesOffendingToolAndPointsToTools() throws Exception {
            final var diagnostic = diagnostic("github_review");
            assertTrue(diagnostic.contains("\"github_review\""), "should name the offending tool");
            assertTrue(diagnostic.contains("not in this session's toolset"), "should explain why it is rejected");
            assertTrue(diagnostic.contains("/tools"), "should point at the way to list valid tools");
        }

        @Test
        void unknownToolDiagnosticIsStableText() throws Exception {
            assertEquals(
                    "Unknown tool \"foo\": not in this session's toolset (built-in or enabled MCP). Run /tools to list"
                            + " the valid tools; only exact names from the request's tools field are accepted.",
                    diagnostic("foo"));
        }

        @Test
        void inlineSplitterNullSegmentIsSkippedInsteadOfAbortingTheStream() throws Exception {
            final var loop = loop(scriptedToolClient(new ArrayList<>(), List.of()), null, baseline);
            events.clear();
            final var method = AgentLoop.class.getDeclaredMethod("emitInlineSegments", List.class);
            method.setAccessible(true);
            // a stray null segment (an unexpected splitter edge case) must be skipped, never NPE mid-stream,
            // so it cannot be misreported as a transport failure and retried pointlessly
            method.invoke(
                    loop, Arrays.asList(null, new ThinkTagSplitter.Segment(ThinkTagSplitter.Channel.VISIBLE, "hello")));
            final var textDeltas = events.stream()
                    .filter(e -> e instanceof AgentEvent.TextDelta)
                    .map(AgentEvent.TextDelta.class::cast)
                    .toList();
            assertEquals(1, textDeltas.size(), "only the non-null segment should emit a TextDelta");
            assertEquals("hello", textDeltas.get(0).delta());
        }

        @Test
        void toolCeilingFiltersWireSchemasAndRejectsDirectCalls() throws Exception {
            final var executed = new ArrayList<String>();
            final var advertised = new ArrayList<List<String>>();
            final var allowedTool = testTool("allowed", executed);
            final var blockedTool = testTool("blocked", executed);
            final var candidates = List.of(allowedTool, blockedTool);
            final var registry = new ToolRegistry(null) {
                @Override
                public List<Tool> toWireTools(final Path workdir) {
                    return candidates.stream().map(this::wireTool).toList();
                }

                @Override
                public List<Tool> toWireTools(final Path workdir, final Predicate<EmbeddedTool> filter) {
                    return candidates.stream()
                            .filter(filter)
                            .map(this::wireTool)
                            .toList();
                }

                private Tool wireTool(final EmbeddedTool tool) {
                    return Tool.function(tool.name(), tool.description(storageHome), tool.schema());
                }

                @Override
                public EmbeddedTool tool(final String name) {
                    return candidates.stream()
                            .filter(tool -> tool.name().equals(name))
                            .findFirst()
                            .orElse(null);
                }
            };
            final var replies = new ArrayDeque<>(
                    List.of(toolCallReply("blocked-call", "blocked"), new ScriptedReply("done", "stop", false)));
            final ChatClient client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled)
                        throws IOException {
                    advertised.add(request.tools().stream()
                            .map(tool -> tool.function().name())
                            .toList());
                    final var next = replies.poll();
                    if (next.finishReason() != null) {
                        onDelta.accept(new End(next.finishReason(), null));
                    }
                    return next.toMessage();
                }
            };

            final var agent =
                    loop(client, null, baseline, noContextManager(), noSessionStore(), null, registry, null, null);
            agent.allowedTools(Set.of("allowed"));
            assertEquals("done", agent.run("try a blocked tool", null, "s1", null, () -> false));
            assertEquals(List.of("allowed"), advertised.getFirst(), "the blocked tool must not be advertised");
            assertTrue(executed.isEmpty(), "a direct call outside the ceiling must not execute");
            assertTrue(events.stream()
                    .filter(AgentEvent.ToolFinished.class::isInstance)
                    .map(AgentEvent.ToolFinished.class::cast)
                    .anyMatch(result -> result.output().contains("not allowed for this agent run")));
        }

        @Test
        void crashingToolExecutionStillEmitsToolFinished() {
            // regression: if ToolExecutionService.execute throws (a crashing tool, or a throwing post-execution event
            // observer such as a ToolUsePost hook), runTool must STILL emit ToolFinished - otherwise the transcript
            // keeps the tool in its running-tools map and the TUI card is stuck "working" forever. The crash is
            // normalized into a failed diagnostic and the card closes normally.
            final var executed = new ArrayList<String>();
            final var tool = testTool("crashy", executed);
            final var registry = new ToolRegistry(null) {
                @Override
                public List<Tool> toWireTools(final Path workdir) {
                    return List.of(Tool.function(tool.name(), tool.description(storageHome), tool.schema()));
                }

                @Override
                public List<Tool> toWireTools(final Path workdir, final Predicate<EmbeddedTool> filter) {
                    return List.of(Tool.function(tool.name(), tool.description(storageHome), tool.schema()));
                }

                @Override
                public EmbeddedTool tool(final String name) {
                    return tool.name().equals(name) ? tool : null;
                }
            };
            final var throwingExecutions = new ToolExecutionService(null, null, null, null, null, null) {
                @Override
                public ToolResult execute(
                        final EmbeddedTool ignored, final Map<String, Object> args, final ToolContext context) {
                    throw new IllegalStateException("simulated tool crash");
                }
            };
            final var replies =
                    new ArrayDeque<>(List.of(toolCallReply("t1", "crashy"), new ScriptedReply("done", "stop", false)));
            final var requests = new ArrayList<List<Message>>();
            final ChatClient client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled) {
                    requests.add(List.copyOf(request.messages()));
                    final var next = replies.poll();
                    if (next.finishReason() != null) {
                        onDelta.accept(new End(next.finishReason(), null));
                    }
                    return next.toMessage();
                }
            };
            final var result = loop(
                            client,
                            null,
                            baseline,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            registry,
                            throwingExecutions,
                            EXECUTOR)
                    .run("do it", null, "s1", null, () -> false);

            assertEquals("done", result);
            // the crashing tool must still be closed by exactly one ToolFinished (success=false) so no card is left
            // running
            final var finishes = events.stream()
                    .filter(AgentEvent.ToolFinished.class::isInstance)
                    .map(AgentEvent.ToolFinished.class::cast)
                    .toList();
            assertEquals(1, finishes.size(), "a crashing tool must still emit exactly one ToolFinished: " + events);
            assertFalse(finishes.get(0).success(), "the crashing finished event must not claim success");
            assertEquals("Tool crashy failed.", finishes.get(0).output());
            assertFalse(requests.toString().contains("simulated tool crash"));
            assertTrue(events.stream()
                    .filter(AgentEvent.ToolDiagnostic.class::isInstance)
                    .map(AgentEvent.ToolDiagnostic.class::cast)
                    .anyMatch(event -> event.detail().contains("simulated tool crash")));
        }

        @Test
        void truncatedReplyIsAutoContinuedThenTurnEndsCleanly() {
            final var replies = new ArrayDeque<>(List.of(
                    new ScriptedReply("let me star", "length", false),
                    new ScriptedReply("t the implementation", "stop", false)));
            final var requests = new ArrayList<List<Message>>();
            final var result =
                    loop(chatClient(replies, requests), null, baseline).run("do it", null, "s1", null, () -> false);

            assertEquals("t the implementation", result);
            assertEquals(2, requests.size(), "truncated reply must trigger exactly one more LLM call");
            final var nudge = requests.get(1).getLast();
            assertEquals("user", nudge.role());
            assertTrue(
                    nudge.content().toString().contains("cut off by the output token limit"),
                    "the continuation nudge must tell the model to resume");
            final var notices =
                    events.stream().filter(AgentEvent.Notice.class::isInstance).toList();
            assertEquals(1, notices.size());
            assertEquals(AgentEvent.Notice.Level.WARN, ((AgentEvent.Notice) notices.getFirst()).level());
            assertTrue(((AgentEvent.Notice) notices.getFirst()).message().contains("(1/3)"));
            assertInstanceOf(AgentEvent.TurnDone.class, events.getLast());
        }

        @Test
        void subagentCompletionIsPushedIntoParentContext() {
            // a finished sub-agent's result delivered through the bus must land in the parent's next LLM
            // request (as a user-role <subagent> message), so the parent never has to poll for it.
            final var replies = new ArrayDeque<>(List.of(new ScriptedReply("ok done", "stop", false)));
            final var requests = new ArrayList<List<Message>>();
            SubagentDeliveryBus.get()
                    .deliver(
                            "s1", new SubagentDeliveryBus.Delivery("child-1", "childName", "the summary", true, false));
            try {
                final var result =
                        loop(chatClient(replies, requests), null, baseline).run("do it", null, "s1", null, () -> false);
                assertEquals("ok done", result);
                assertFalse(requests.isEmpty(), "a request must have been sent");
                final var injected = requests.getFirst().stream()
                        .filter(m -> m.content() instanceof String s && s.startsWith("<subagent "))
                        .findFirst();
                assertTrue(injected.isPresent(), "the parent request must carry the injected <subagent> block");
                assertTrue(injected.get().content().toString().contains("the summary"));
                assertTrue(injected.get().content().toString().contains("child-1"));
            } finally {
                SubagentDeliveryBus.get().drain("s1"); // keep the static bus clean for other tests
            }
        }

        @Test
        void deliveryMessageShapeAndIdempotency() {
            final var d = SubagentDeliveryBus.get();
            final var normal = new SubagentDeliveryBus.Delivery("c", "name", "sum", true, false).message();
            assertTrue(normal.contains("<subagent sessionID=\"c\" state=\"completed\""));
            assertTrue(normal.contains("sum"));
            final var cancelled = new SubagentDeliveryBus.Delivery("c", "n", "sum", false, true).message();
            assertTrue(cancelled.contains("state=\"cancelled\""));
            // same subagent id delivered twice stays once
            d.deliver("sX", new SubagentDeliveryBus.Delivery("dupe", "n", "one", true, false));
            d.deliver("sX", new SubagentDeliveryBus.Delivery("dupe", "n", "two", true, false));
            assertEquals(1, d.drain("sX").size(), "a duplicate delivery for the same child is deduplicated");
        }

        @Test
        void truncatedReplyGivesUpAfterCapWithAnErrorNotice() {
            final var replies = new ArrayDeque<ScriptedReply>();
            for (var i = 0; i < 5; i++) {
                replies.add(new ScriptedReply("truncated " + i, "length", false));
            }
            final var requests = new ArrayList<List<Message>>();
            final var result =
                    loop(chatClient(replies, requests), null, baseline).run("do it", null, "s1", null, () -> false);

            assertEquals(4, requests.size(), "3 continuations then a final call");
            final var notices =
                    events.stream().filter(AgentEvent.Notice.class::isInstance).toList();
            assertEquals(4, notices.size(), "3 continuation warnings then the give-up error");
            assertEquals(AgentEvent.Notice.Level.ERROR, ((AgentEvent.Notice) notices.getLast()).level());
            assertEquals("length", ((AgentEvent.Notice) notices.getLast()).finishReason());
            assertTrue(((AgentEvent.Notice) notices.getLast()).message().contains("Gave up after 3 continuations"));
            assertInstanceOf(AgentEvent.TurnDone.class, events.getLast());
        }
        /**
         * Esc pressed while the model streams a plain text reply (no tool calls): the pump surfaces the
         * cancel as a partial message and the loop must emit TurnCancelled - exactly the terminal event
         * the other cancel exits emit - so shells reset their busy state and flush the buffered
         * thinking. The partial content is still returned so callers can render/continue it.
         */
        @Test
        void midStreamCancelEmitsTurnCancelledAndReturnsPartialContent() {
            final var replies = new ArrayDeque<>(List.of(new ScriptedReply("partial thought", "stop", false)));
            final var requests = new ArrayList<List<Message>>();
            // the probe starts false so the first iteration runs, then flips once the model is asked
            final var cancelled = new boolean[] {false};
            final var result = loop(chatClient(replies, requests), null, baseline)
                    .run("do it", null, "s1", null, () -> {
                        cancelled[0] = !requests.isEmpty() && !cancelled[0];
                        return cancelled[0];
                    });

            assertEquals("partial thought", result, "the partial streamed content must still be returned");
            assertEquals(1, requests.size(), "a cancel must not spawn a retry/continuation request");
            final var cancellations = events.stream()
                    .filter(AgentEvent.TurnCancelled.class::isInstance)
                    .toList();
            assertEquals(1, cancellations.size(), "exactly one TurnCancelled must be emitted");
            assertTrue(
                    events.stream().noneMatch(AgentEvent.TurnDone.class::isInstance),
                    "a cancelled turn must not emit TurnDone");
        }

        @Test
        void settingsChangeMidTurnAppliesToTheNextRequestOnly() {
            final var requests = new ArrayList<List<Message>>();
            final var capturedSettings = new ArrayList<ChatSettings>();
            final var capturedModels = new ArrayList<String>();
            final var loopRef = new AtomicReference<AgentLoop>();
            final var replies = new ArrayDeque<>(
                    List.of(toolCallReply("t1", "noop"), new ScriptedReply("final answer", "stop", false)));
            final var client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled) {
                    requests.add(List.copyOf(request.messages()));
                    capturedSettings.add(request.settings());
                    capturedModels.add(request.model());
                    // mid-turn: the /model or /provider command runs on another thread; the running
                    // request already carries the old selection, only the next one must pick it up
                    if (capturedSettings.size() == 1) {
                        loopRef.get().updateChatSettings(ChatSettings.EMPTY.withModel("next-model"));
                    }
                    final var reply = replies.poll();
                    if (reply.finishReason() != null) {
                        onDelta.accept(new End(reply.finishReason(), null));
                    }
                    return reply.toMessage();
                }
            };
            final var loop = loop(client, null, baseline);
            loopRef.set(loop);
            final var result = loop.run("do it", null, "s1", null, () -> false);

            assertEquals("final answer", result);
            assertEquals(2, requests.size(), "a tool round trip must issue exactly two requests");
            assertEquals(
                    ChatSettings.EMPTY,
                    capturedSettings.get(0),
                    "the in-flight request keeps the pre-change selection");
            assertEquals(
                    ChatSettings.EMPTY.withModel("next-model"),
                    capturedSettings.get(1),
                    "the next request picks up the mid-turn selection change");
            assertEquals(
                    List.of("test-model", "next-model"),
                    capturedModels,
                    "the model is re-resolved per request: the round after the switch carries the new model");
        }
        /**
         * The reported regression: a plain {@code /model} switch (no per-tab provider) used to only
         * change the name the UI reads from the settings while the wire kept the process default model,
         * because the loop pinned the model resolved at turn start and {@code ChatClient} ranks an
         * explicit request model above {@code settings.model()}.
         */
        @Test
        void modelSwitchBetweenTurnsWithoutProviderOverrideUsesTheNewModel() {
            final var capturedModels = new ArrayList<String>();
            final var replies = new ArrayDeque<>(
                    List.of(new ScriptedReply("first", "stop", false), new ScriptedReply("second", "stop", false)));
            final var client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled) {
                    capturedModels.add(request.model());
                    final var reply = replies.poll();
                    if (reply.finishReason() != null) {
                        onDelta.accept(new End(reply.finishReason(), null));
                    }
                    return reply.toMessage();
                }
            };
            final var loop = loop(client, null, baseline);

            assertEquals("first", loop.run("do it", null, "s1", null, () -> false));
            // no per-tab provider: the plain /model switch must still reach the wire
            loop.updateChatSettings(ChatSettings.EMPTY.withModel("other-model"));
            assertEquals("second", loop.run("and now", null, "s1", null, () -> false));

            assertEquals(
                    List.of("test-model", "other-model"),
                    capturedModels,
                    "the next turn must send the switched model, not the process default one");
        }

        @Test
        void secondContinuationSwitchesToSplitStrategy() {
            final var replies = new ArrayDeque<>(List.of(
                    new ScriptedReply("truncated 0", "length", false),
                    new ScriptedReply("truncated 1", "length", false),
                    new ScriptedReply("truncated 2", "length", false),
                    new ScriptedReply("finally done", "stop", false)));
            final var requests = new ArrayList<List<Message>>();
            final var result =
                    loop(chatClient(replies, requests), null, baseline).run("do it", null, "s1", null, () -> false);

            assertEquals("finally done", result);
            assertEquals(4, requests.size());
            final var firstNudge = requests.get(1).getLast().content().toString();
            assertTrue(firstNudge.contains("Continue exactly where you stopped"), firstNudge);
            final var secondNudge = requests.get(2).getLast().content().toString();
            assertTrue(secondNudge.contains("Change strategy"), secondNudge);
            assertTrue(secondNudge.contains("Do not continue the same output verbatim"), secondNudge);
            final var thirdNudge = requests.get(3).getLast().content().toString();
            assertTrue(thirdNudge.contains("Change strategy"), thirdNudge);
        }

        @Test
        void midStreamFailureIsRetriedWithATextReset() {
            final var replies = new ArrayDeque<>(List.of(
                    new ScriptedReply(failure("stream interrupted after 12 chars of partial content: broken pipe")),
                    new ScriptedReply("recovered", "stop", false)));
            final var requests = new ArrayList<List<Message>>();
            final var result =
                    loop(chatClient(replies, requests), null, baseline).run("do it", null, "s1", null, () -> false);

            assertEquals("recovered", result);
            assertEquals(2, requests.size(), "the interrupted stream must be replayed once");
            assertTrue(
                    events.stream().anyMatch(AgentEvent.TextReset.class::isInstance),
                    "frontends must be told to drop the partial buffer");
            final var notices =
                    events.stream().filter(AgentEvent.Notice.class::isInstance).toList();
            assertEquals(1, notices.size());
            final var notice = (AgentEvent.Notice) notices.getFirst();
            assertEquals(AgentEvent.Notice.Level.WARN, notice.level());
            assertTrue(notice.message().contains("Stream interrupted (broken pipe)"), notice.message());
            assertTrue(notice.message().contains("retrying (1/3)"), notice.message());
            assertNotNull(
                    notice.cause(),
                    "the retry notice must carry the underlying failure so the TUI can show/copy its stack trace");
            assertInstanceOf(AgentEvent.TurnDone.class, events.getLast());
        }

        @Test
        void runtimeFailureIsSurfacedAsAnErrorEvent() {
            final var replies = new ArrayDeque<>(
                    List.of(new ScriptedReply(null, null, new IllegalStateException("provider exploded"))));
            final var result = loop(chatClient(replies, new ArrayList<>()), null, baseline)
                    .run("do it", null, "s1", null, () -> false);

            assertNull(result, "the turn must end gracefully instead of throwing into the frontend");
            final var errors =
                    events.stream().filter(AgentEvent.Error.class::isInstance).toList();
            assertEquals(1, errors.size());
            assertTrue(((AgentEvent.Error) errors.getFirst()).message().contains("provider exploded"));
        }

        @Test
        void interruptedCancelledTurnEmitsCancellationInsteadOfError() {
            final var cancelled = new AtomicBoolean();
            final var client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier probe)
                        throws InterruptedException {
                    cancelled.set(true);
                    throw new InterruptedException("cancelled blocking wait");
                }
            };
            try {
                assertNull(loop(client, null, baseline).run("go", null, "s1", null, cancelled::get));
                assertEquals(
                        1,
                        events.stream()
                                .filter(AgentEvent.TurnCancelled.class::isInstance)
                                .count());
                assertTrue(events.stream().noneMatch(AgentEvent.Error.class::isInstance));
            } finally {
                Thread.interrupted();
            }
        }

        @Test
        void resiliencePausesAreSurfacedAndTheListenerNeverLeaksToTheNextTurn() {
            final var replies = new ArrayDeque<>(List.of(new ScriptedReply("done", "stop", true)));
            final var client = chatClient(replies, new ArrayList<>());
            final var agentLoop = loop(client, null, baseline);
            final var result = agentLoop.run("do it", null, "s1", null, () -> false);

            assertEquals("done", result);
            assertTrue(
                    events.stream()
                            .anyMatch(e -> e instanceof AgentEvent.Pause p
                                    && p.kind() == AgentEvent.Pause.Kind.RETRY
                                    && p.message().contains("retrying")),
                    "retry pauses must surface as a dedicated Pause event");
            assertTrue(
                    events.stream()
                            .anyMatch(e -> e instanceof AgentEvent.Pause p
                                    && p.kind() == AgentEvent.Pause.Kind.THROTTLE
                                    && p.message().contains("Rate limit")),
                    "throttle pauses must surface as a dedicated Pause event");
            // pauses are transient: never a durable notice or leading-status replacement
            assertTrue(
                    events.stream()
                            .noneMatch(e -> (e instanceof AgentEvent.Notice n
                                            && n.message().contains("retrying"))
                                    || (e instanceof AgentEvent.Status s
                                            && s.message().contains("Rate limit"))),
                    "pauses must not leak into notices/status: "
                            + events.stream()
                                    .filter(e -> e instanceof AgentEvent.Notice || e instanceof AgentEvent.Status)
                                    .toList());

            // each call carries its own listener, so a later turn on the same loop can never re-fire the previous one
            final var pausesAfterFirstTurn = pauseEvents();
            replies.add(new ScriptedReply("again", "stop", false));
            agentLoop.run("do it again", null, "s2", null, () -> false);

            assertEquals(pausesAfterFirstTurn, pauseEvents(), "a later turn must not replay the previous pauses");
        }

        @Test
        void turnThroughputCountsTokensOverTheTimeActuallySpentGenerating() {
            final var reply =
                    new ScriptedReply("done", "stop", false, new io.yupiik.llm.client.model.Usage(10L, 100L, 110L));
            loop(timedChatClient(reply, 60, 0), null, baseline).run("do it", null, "s1", null, () -> false);

            final var throughput = lastTurnMetrics().throughput();
            assertEquals(100, throughput.outputTokens(), "output tokens come from the provider usage");
            assertEquals(0, throughput.reportedMs(), "no server side generation time was reported");
            assertEquals(60, throughput.activeMs());
            assertEquals(throughput.outputTokens() * 1000.0 / throughput.activeMs(), throughput.tokensPerSecond());
            assertEquals(0, throughput.reportedTokensPerSecond());
        }

        @Test
        void turnThroughputIgnoresTheIdleTimeOfThrottlesAndRetries() {
            // a 10s throttle on a 20ms generation: waiting for the provider is not generating
            final var reply =
                    new ScriptedReply("done", "stop", false, new io.yupiik.llm.client.model.Usage(10L, 100L, 110L));
            loop(timedChatClient(reply, 20, 10_000), null, baseline).run("do it", null, "s1", null, () -> false);

            final var throughput = lastTurnMetrics().throughput();
            assertEquals(100, throughput.outputTokens());
            assertEquals(20, throughput.activeMs(), "the throttle must be discounted, not counted as request time");
            assertEquals(5_000, throughput.tokensPerSecond());
            assertTrue(throughput.measurable());
        }

        @Test
        void turnThroughputExposesTheServerSideRateWhenTheProviderReportsOne() {
            // 100 output tokens, 2s of generation as reported by the server itself
            final var reply = new ScriptedReply(
                    "done",
                    "stop",
                    false,
                    new io.yupiik.llm.client.model.Usage(10L, 100L, 110L, null, null, null, 2_000L));
            loop(chatClient(new ArrayDeque<>(List.of(reply)), new ArrayList<>()), null, baseline)
                    .run("do it", null, "s1", null, () -> false);

            final var throughput = lastTurnMetrics().throughput();
            assertEquals(2_000, throughput.reportedMs());
            assertEquals(
                    50d, throughput.reportedTokensPerSecond(), 0.001d, "100 tokens over the 2s the server reported");
            assertTrue(throughput.measurable());
        }

        @Test
        void turnThroughputEstimatesTokensWhenTheProviderReportsNoUsage() {
            final var content = "a short answer with a handful of words";
            loop(
                            chatClient(
                                    new ArrayDeque<>(List.of(new ScriptedReply(content, "stop", false))),
                                    new ArrayList<>()),
                            null,
                            baseline)
                    .run("do it", null, "s1", null, () -> false);

            assertEquals(
                    TokenEstimator.estimate(content),
                    lastTurnMetrics().throughput().outputTokens(),
                    "without provider usage the produced tokens are estimated from what came back");
        }

        @Test
        void turnThroughputKeepsTheReasoningBreakdownWhenTheProviderReportsOne() {
            // 100 output tokens including 40 spent on internal reasoning
            final var reply = new ScriptedReply(
                    "done", "stop", false, new io.yupiik.llm.client.model.Usage(10L, 100L, 110L, null, null, 40L, 0L));
            loop(timedChatClient(reply, 0, 1_000), null, baseline).run("do it", null, "s1", null, () -> false);

            final var throughput = lastTurnMetrics().throughput();
            assertEquals(
                    100, throughput.outputTokens(), "the aggregate stays the completion tokens, reasoning included");
            assertEquals(40, throughput.reasoningTokens());
            assertEquals(60, throughput.outputTokensOnly(), "visible output is the aggregate minus the reasoning");
            assertEquals(0, throughput.reasoningTokensPerSecond(), "no generation time was measured by the stub");
        }

        @Test
        void partialServerTimingUsesOnlyMatchingTokens() {
            final var throughput = throughputAcrossRequests(
                    new io.yupiik.llm.client.model.Usage(10L, 100L, 110L, null, null, null, 2_000L),
                    new io.yupiik.llm.client.model.Usage(10L, 100L, 110L));
            assertEquals(200, throughput.outputTokens());
            assertEquals(100, throughput.reportedOutputTokens());
            assertEquals(2_000, throughput.reportedMs());
            assertEquals(50d, throughput.reportedTokensPerSecond());
            assertEquals(2, throughput.completedRequests());
            assertEquals(1, throughput.reportedRequests());
            assertEquals(200, throughput.activeMs());
            assertFalse(throughput.outputEstimated());
        }

        @Test
        void serverTimingIsTokenWeightedRatherThanAnAverageOfRates() {
            final var throughput = throughputAcrossRequests(
                    new io.yupiik.llm.client.model.Usage(10L, 100L, 110L, null, null, null, 1_000L),
                    new io.yupiik.llm.client.model.Usage(10L, 300L, 310L, null, null, null, 6_000L));
            assertEquals(400, throughput.reportedOutputTokens());
            assertEquals(7_000, throughput.reportedMs());
            assertEquals(400_000d / 7_000, throughput.reportedTokensPerSecond());
            assertEquals(2, throughput.reportedRequests());
        }

        @Test
        void mixedUsageMarksTheRequestRateAsEstimatedWithoutAddingServerCoverage() {
            final var throughput = throughputAcrossRequests(
                    new io.yupiik.llm.client.model.Usage(10L, 100L, 110L, null, null, null, 2_000L), null);
            assertEquals(100 + TokenEstimator.estimate("done"), throughput.outputTokens());
            assertTrue(throughput.outputEstimated());
            assertEquals(100, throughput.reportedOutputTokens());
            assertEquals(1, throughput.reportedRequests());
        }

        @Test
        void requestTimingExcludesRequestSentObserversAndPostStreamProcessing() {
            final var client = timedChatClient(
                    new ScriptedReply("done", "stop", false, new io.yupiik.llm.client.model.Usage(10L, 100L, 110L)),
                    50,
                    0);
            eventObserver = event -> {
                if (event instanceof AgentEvent.Phase || event instanceof AgentEvent.UsageUpdated) {
                    throughputClock.advance(1_000);
                }
            };
            loop(client, null, baseline).run("do it", null, "s1", null, () -> false);
            assertEquals(50, lastTurnMetrics().throughput().activeMs());
            assertTrue(lastTurnMetrics().turnDurationMs() > 50);
        }

        @Test
        void throughputResetsOnNextTurn() {
            final var client = timedChatClient(
                    new ScriptedReply(
                            "done",
                            "stop",
                            false,
                            new io.yupiik.llm.client.model.Usage(10L, 100L, 110L, null, null, 40L, 2_000L)),
                    50,
                    0);
            final var agent = loop(client, null, baseline);
            agent.run("first", null, "s1", null, () -> false);
            final var first = lastTurnMetrics().throughput();
            agent.run("second", null, "s1", null, () -> false);
            assertEquals(first, lastTurnMetrics().throughput());
            agent.run("cancel", null, "s1", null, () -> true);
            assertEquals(AgentEvent.Throughput.EMPTY, lastTurnMetrics().throughput());
        }

        @Test
        void turnEndingWithPendingTodosIsWarnedAbout() {
            // the default (unconfigured) permission mode is PLAN where pending todos are expected:
            // opt into BUILD explicitly so the warning still applies
            permissionServiceOverride = new PermissionService(baseline, (baseline).resolveWorkingDir());
            permissionServiceOverride.setMode(AgentMode.BUILD);
            final var todoStore = new TodoStore() {
                private List<TodoItem> todos = List.of(
                        new TodoItem("done step", "completed"),
                        new TodoItem("running step", "in_progress"),
                        new TodoItem("later step", "pending"));

                @Override
                public List<TodoItem> all() {
                    return todos;
                }

                @Override
                public synchronized List<TodoItem> replace(final List<TodoItem> items) {
                    todos = List.copyOf(items);
                    return all();
                }
            };
            final var replies = new ArrayDeque<>(List.of(
                    new ScriptedReply(null, "tool_calls", false) {
                        @Override
                        Message toMessage() {
                            return new Message(
                                    "assistant",
                                    null,
                                    null,
                                    List.of(new ToolCall("t1", "function", new ToolCall.FunctionCall("bash", "{}"), 0)),
                                    null,
                                    null);
                        }
                    },
                    new ScriptedReply("made progress", "stop", false)));
            try {
                final var result = loop(chatClient(replies, new ArrayList<>()), todoStore, baseline)
                        .run("do it", null, "s1", null, () -> false);

                assertEquals("made progress", result);
                final var notices = events.stream()
                        .filter(AgentEvent.Notice.class::isInstance)
                        .toList();
                assertEquals(1, notices.size());
                final var notice = (AgentEvent.Notice) notices.getFirst();
                assertEquals(AgentEvent.Notice.Level.WARN, notice.level());
                assertEquals(
                        "model ended its turn (finish_reason=stop) with 2/3 todos still unfinished - send 'continue'"
                                + " to resume, or review why it stopped",
                        notice.message());
                assertEquals("stop", notice.finishReason());
            } finally {
                permissionServiceOverride = null;
            }
        }
        /**
         * Inline reasoning (models that stream {@code think} tags inside the text {@code content} channel,
         * GLM/Qwen/deepseek-flash style) must be classified by the ONE shared backend splitter and emitted
         * as {@link AgentEvent.ThinkingDelta} events BEFORE the visible answer - so every frontend (TUI,
         * web SSE, subagent transcript) receives the same pre-split stream and no frontend re-implements the
         * parser. The persisted assistant message must carry the thinking under the reserved {@code others}
         * key so a later web replay can re-render the thought blocks.
         */
        @Test
        void inlineThinkTagsAreSplitIntoThinkingDeltaAndPersistedAsThinking() {
            final var requests = new ArrayList<List<Message>>();
            final var persisted = new ArrayList<SessionEntry>();
            final var client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled)
                        throws IOException {
                    requests.add(List.copyOf(request.messages()));
                    // the close tag arrives in a separate chunk from the open tag, exercising the
                    // splitter's chunk-boundary state machine
                    onDelta.accept(new TextDelta("hello "));
                    onDelta.accept(new TextDelta("my name is \u003cthink\u003ele"));
                    onDelta.accept(new TextDelta("t me reason "));
                    onDelta.accept(new TextDelta("carefully\u003c/think\u003eworld!"));
                    onDelta.accept(new End("stop", null));
                    return Message.assistant("world!", null);
                }
            };
            final var sessionStore = new SessionStore() {
                @Override
                public void append(final String id, final SessionEntry entry) {
                    persisted.add(entry);
                }
            };

            final var result = loop(client, null, baseline, noContextManager(), sessionStore, null)
                    .run("do it", null, "s1", null, () -> false);

            assertEquals("world!", result);
            // a ThinkingDelta event must have been emitted to the shared stream (what frontends consume),
            // with the thinking portion, and a visible TextDelta for the answer alone
            final var thinking = events.stream()
                    .filter(AgentEvent.ThinkingDelta.class::isInstance)
                    .map(AgentEvent.ThinkingDelta.class::cast)
                    .map(AgentEvent.ThinkingDelta::delta)
                    .reduce("", String::concat);
            assertEquals("let me reason carefully", thinking, "inline think must be split out as ThinkingDelta events");
            final var text = events.stream()
                    .filter(AgentEvent.TextDelta.class::isInstance)
                    .map(AgentEvent.TextDelta.class::cast)
                    .map(AgentEvent.TextDelta::delta)
                    .reduce("", String::concat);
            assertEquals("hello my name is world!", text, "visible stream must have the think tags stripped");
            // the persisted assistant entry must carry the thinking so WebReplay can re-emit it
            final var assistant = persisted.stream()
                    .filter(e -> "assistant".equals(e.role()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(assistant, "an assistant entry must be persisted");
            final var persistedThinking =
                    assistant.others() == null ? null : assistant.others().get("thinking");
            assertEquals("let me reason carefully", persistedThinking, "thinking must be persisted for replay");
            final var feedback = events.stream()
                    .filter(AgentEvent.FeedbackStarted.class::isInstance)
                    .map(AgentEvent.FeedbackStarted.class::cast)
                    .toList();
            assertEquals(
                    List.of("text", "thinking", "text"),
                    feedback.stream().map(AgentEvent.FeedbackStarted::kind).toList());
            final var persistedMessage =
                    new Message("assistant", assistant.content(), null, null, null, assistant.others());
            assertEquals(feedback.getLast().startedAt(), FeedbackTiming.startedAt(persistedMessage, "text"));
            assertEquals(feedback.get(1).startedAt(), FeedbackTiming.startedAt(persistedMessage, "thinking"));
            assertFalse(
                    String.valueOf(assistant.content()).contains("think"),
                    "the persisted content must not contain the thinking tags");
        }
        /**
         * A model echoing the injected compact seam ("@N@" ref tags) inside its reasoning must not
         * persist that metadata under {@code THINKING_KEY}: the web replay rebuilds thinking-delta
         * from that key, so an unclean value would leak straight into a fresh tab. The live
         * ThinkingDelta events keep the raw stream (frontends scrub at their render chokepoint);
         * the persisted copy is stripped exactly like the answer content is.
         */
        @Test
        void echoedSeamInThinkingIsStrippedFromPersistedThinking() {
            final var persisted = new ArrayList<SessionEntry>();
            final var client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled)
                        throws IOException {
                    onDelta.accept(new TextDelta("<think>let me check @42@"));
                    onDelta.accept(new TextDelta(" [high] carefully</think>answer"));
                    onDelta.accept(new End("stop", null));
                    return Message.assistant("answer", null);
                }
            };
            final var sessionStore = new SessionStore() {
                @Override
                public void append(final String id, final SessionEntry entry) {
                    persisted.add(entry);
                }
            };

            final var result = loop(client, null, baseline, noContextManager(), sessionStore, null)
                    .run("do it", null, "s1", null, () -> false);

            assertEquals("answer", result);
            // the live ThinkingDelta events keep the raw seam: frontends scrub at their render
            // chokepoint, exactly like the visible deltas
            final var thinking = events.stream()
                    .filter(AgentEvent.ThinkingDelta.class::isInstance)
                    .map(AgentEvent.ThinkingDelta.class::cast)
                    .map(AgentEvent.ThinkingDelta::delta)
                    .reduce("", String::concat);
            assertEquals("let me check @42@ [high] carefully", thinking);
            final var assistant = persisted.stream()
                    .filter(e -> "assistant".equals(e.role()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(assistant, "an assistant entry must be persisted");
            final var persistedThinking =
                    assistant.others() == null ? null : assistant.others().get("thinking");
            // a seam embedded in PROSE is intended content and survives the persistence scrub;
            // only a complete seam ALONE on a line is removed
            assertEquals(
                    "let me check @42@ [high] carefully",
                    persistedThinking,
                    "the persisted thinking must keep the prose-glued seam");
        }
        /**
         * P1: the doom-loop breaker is per signature and the arguments are normalized (JSON keys sorted)
         * so cosmetic spelling variants of the same call do not bypass it:
         * {@code {"path":"a","offset":1}} and {@code {"offset":1,"path":"a"}} are the same signature, so
         * alternating spellings across rounds still accumulate the streak and get refused.
         */
        @Test
        void repeatedCallNormalizedArgsAreRefusedAcrossSpellingVariants() {
            final var requests = new ArrayList<List<Message>>();
            final var calls = new AtomicInteger();
            final var client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled) {
                    requests.add(List.copyOf(request.messages()));
                    final var round = calls.incrementAndGet();
                    if (round <= 5) {
                        // alternate the JSON key ordering: still the same normalized signature
                        final String args;
                        if (round == 1 || round == 3 || round == 5) {
                            args = "{\"path\":\"/a\",\"offset\":1}";
                        } else {
                            args = "{\"offset\":1,\"path\":\"/a\"}";
                        }
                        return Message.assistant(
                                null,
                                List.of(new ToolCall(
                                        "t" + round, "function", new ToolCall.FunctionCall("echo", args), 0)));
                    }
                    onDelta.accept(new End("stop", null));
                    return Message.assistant("all done", null);
                }
            };

            loop(
                            client,
                            null,
                            baseline,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            stateChangingRegistry(),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertTrue(
                    hasDoomLoopNudge(requests),
                    "spelling variants of the same call must be refused (as a strategy nudge): " + requests);
        }
        /**
         * P1: an interleaved call of a DIFFERENT signature does not reset a signature's success streak:
         * read A, read B, read A, read A accumulates A's streak across B (B never touched A's state), so
         * a fifth identical A is refused even though B happened in between.
         */
        @Test
        void interleavedDifferentSignatureDoesNotResetSameSignatureStreak() {
            final var requests = new ArrayList<List<Message>>();
            final var calls = new AtomicInteger();
            final var client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled) {
                    requests.add(List.copyOf(request.messages()));
                    final var round = calls.incrementAndGet();
                    if (round <= 6) {
                        final var target = (round == 2) ? "/b" : "/a"; // round 2 reads a DIFFERENT path
                        return Message.assistant(
                                null,
                                List.of(new ToolCall(
                                        "t" + round,
                                        "function",
                                        new ToolCall.FunctionCall("echo", "{\"path\":\"" + target + "\"}"),
                                        0)));
                    }
                    onDelta.accept(new End("stop", null));
                    return Message.assistant("all done", null);
                }
            };

            loop(
                            client,
                            null,
                            baseline,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            stateChangingRegistry(),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertTrue(
                    hasDoomLoopNudge(requests),
                    "a different signature in between must not reset the same-signature streak: " + requests);
        }
        /**
         * P1: a genuinely FAILING call is a legitimate retry and is never refused by the doom-loop breaker.
         * Repeated identical failing calls all execute; only the separate no-progress guard could stop the
         * turn, never a doom-loop refusal.
         */
        @Test
        void repeatedFailingCallsAreRetriedNotRefused() {
            final var executions = new AtomicInteger();
            final var replies = new ArrayDeque<ScriptedReply>();
            for (var i = 0; i < 4; i++) {
                replies.add(toolCallReply("t" + i, "fail"));
            }
            replies.add(new ScriptedReply("all done", "stop", false));
            final var requests = new ArrayList<List<Message>>();
            final var client = chatClient(replies, requests);

            loop(
                            client,
                            null,
                            baseline,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            registryOf(new EmbeddedTool() {
                                @Override
                                public String name() {
                                    return "fail";
                                }

                                @Override
                                public String description(final Path workdir) {
                                    return "always fails";
                                }

                                @Override
                                public ToolCategory category() {
                                    return ToolCategory.READ;
                                }

                                @Override
                                public Map<String, Object> schema() {
                                    return Map.of();
                                }

                                @Override
                                public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                                    executions.incrementAndGet();
                                    return new ToolResult(false, "boom");
                                }
                            }),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertEquals(4, executions.get(), "every failing retry must actually run");
            assertFalse(
                    hasDoomLoopNudge(requests),
                    "a failing call is a legitimate retry, never a doom-loop refusal: " + requests);
        }
        /**
         * P1: a call that keeps succeeding with FRESH content (polling) is progress, not a loop: the
         * same signature returning novel output each round resets the success streak, so it is never refused.
         */
        @Test
        void sameCallReturningFreshContentIsNotRefused() {
            final var counter = new AtomicInteger();
            final var replies = new ArrayDeque<ScriptedReply>();
            for (var i = 0; i < 6; i++) {
                replies.add(toolCallReply("t" + i, "poll"));
            }
            replies.add(new ScriptedReply("all done", "stop", false));
            final var requests = new ArrayList<List<Message>>();
            final var client = chatClient(replies, requests);

            loop(
                            client,
                            null,
                            baseline,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            registryOf(new EmbeddedTool() {
                                @Override
                                public String name() {
                                    return "poll";
                                }

                                @Override
                                public String description(final Path workdir) {
                                    return "returns fresh content";
                                }

                                @Override
                                public ToolCategory category() {
                                    return ToolCategory.READ;
                                }

                                @Override
                                public Map<String, Object> schema() {
                                    return Map.of();
                                }

                                @Override
                                public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                                    return ToolResult.ok("value#" + counter.incrementAndGet());
                                }
                            }),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertEquals(6, counter.get(), "every poll round must run");
            assertFalse(
                    hasDoomLoopNudge(requests), "polling a changing state is progress, not a doom loop: " + requests);
        }
        /**
         * P1: outputs that differ only by an embedded timestamp/epoch are treated as identical for the
         * doom-loop decision, so a command that re-runs every time but only differs in its log time is
         * still a duplicate and gets refused.
         */
        @Test
        void outputsDifferingOnlyInTimestampAreStillRefused() {
            final var executions = new AtomicInteger();
            final var replies = new ArrayDeque<ScriptedReply>();
            for (var i = 0; i < 5; i++) {
                replies.add(toolCallReply("t" + i, "dated"));
            }
            replies.add(new ScriptedReply("all done", "stop", false));
            final var requests = new ArrayList<List<Message>>();
            final var client = chatClient(replies, requests);

            loop(
                            client,
                            null,
                            baseline,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            registryOf(new EmbeddedTool() {
                                @Override
                                public String name() {
                                    return "dated";
                                }

                                @Override
                                public String description(final Path workdir) {
                                    return "output with a changing timestamp";
                                }

                                @Override
                                public ToolCategory category() {
                                    return ToolCategory.EDIT;
                                }

                                @Override
                                public Map<String, Object> schema() {
                                    return Map.of();
                                }

                                @Override
                                public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                                    executions.incrementAndGet();
                                    // an ISO-8601 date+time that the date-aware comparison strips, so every
                                    // output differs only by its embedded timestamp
                                    return ToolResult.ok("status at 2026-10-%02d 12:34:56".formatted(executions.get()));
                                }
                            }),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertTrue(
                    hasDoomLoopNudge(requests),
                    "outputs differing only by a timestamp must still be recognized as duplicates: " + requests);
            assertEquals(3, executions.get(), "the fourth timestamp-only-diff call must be refused before it runs");
        }
        /**
         * P1 + P6: build-tool durations maven/gradle emit (e.g. gradle "BUILD SUCCESSFUL in 3s", maven
         * "Total time: 1.234 s") are stripped by the date-aware comparison, so a state-changing build
         * command re-run that only changes its elapsed time is still a duplicate and gets refused.
         */
        @Test
        void buildToolDurationOutputsAreStillRefused() {
            final var executions = new AtomicInteger();
            final var replies = new ArrayDeque<ScriptedReply>();
            for (var i = 0; i < 5; i++) {
                replies.add(toolCallReply("t" + i, "build"));
            }
            replies.add(new ScriptedReply("all done", "stop", false));
            final var requests = new ArrayList<List<Message>>();
            final var client = chatClient(replies, requests);

            loop(
                            client,
                            null,
                            baseline,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            registryOf(new EmbeddedTool() {
                                @Override
                                public String name() {
                                    return "build";
                                }

                                @Override
                                public String description(final Path workdir) {
                                    return "a build-like state-changing tool";
                                }

                                @Override
                                public ToolCategory category() {
                                    return ToolCategory.BASH;
                                }

                                @Override
                                public Map<String, Object> schema() {
                                    return Map.of();
                                }

                                @Override
                                public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                                    executions.incrementAndGet();
                                    // only the elapsed duration differs between runs
                                    return ToolResult.ok("BUILD SUCCESSFUL in " + executions.get() + "s");
                                }
                            }),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertTrue(
                    hasDoomLoopNudge(requests),
                    "outputs differing only by an elapsed duration must still be recognized as duplicates: "
                            + requests);
            assertEquals(3, executions.get(), "the fourth duration-only-diff call must be refused before it runs");
        }
        /**
         * P6: the date-strip regex must NOT equate bare digit runs (versions, build ids, checksums, ports),
         * so a state-changing call whose output carries genuinely different numbers is never a false duplicate.
         */
        @Test
        void regexDoesNotStripBareDigitRuns() {
            final var executions = new AtomicInteger();
            final var replies = new ArrayDeque<ScriptedReply>();
            for (var i = 0; i < 5; i++) {
                replies.add(toolCallReply("t" + i, "versions", "{\"i\":" + i + "}"));
            }
            replies.add(new ScriptedReply("all done", "stop", false));
            final var requests = new ArrayList<List<Message>>();
            final var client = chatClient(replies, requests);

            loop(
                            client,
                            null,
                            baseline,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            registryOf(new EmbeddedTool() {
                                @Override
                                public String name() {
                                    return "versions";
                                }

                                @Override
                                public String description(final Path workdir) {
                                    return "returns incrementing version-like digits";
                                }

                                @Override
                                public ToolCategory category() {
                                    return ToolCategory.EDIT;
                                }

                                @Override
                                public Map<String, Object> schema() {
                                    return Map.of();
                                }

                                @Override
                                public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                                    executions.incrementAndGet();
                                    // semantically different digits each run - must NOT be conflated
                                    return ToolResult.ok(
                                            "com.mycorp:artifact:" + executions.get() + ".0.0 sha " + executions.get());
                                }
                            }),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertFalse(
                    hasDoomLoopNudge(requests),
                    "bare digit runs (versions/checksums) must never be treated as identical: " + requests);
            assertEquals(5, executions.get(), "all distinct-number outputs must run (never refused)");
        }
        /**
         * P7: the turn-level output tombstone flags a call whose normalized output was already produced by a
         * DIFFERENT tool/signature this turn as non-informative (it feeds the no-progress decision), but never
         * produces a doom-loop refusal/nudge.
         */
        @Test
        void sameOutputAcrossDifferentToolsIsTombstonedButNotRefused() {
            final var replies = new ArrayDeque<ScriptedReply>();
            // alternate two DIFFERENT tools returning the SAME output: no exact-signature repeat (so no
            // doom-loop refusal), but the tombstone makes round 2+ non-informative
            for (var i = 0; i < 6; i++) {
                replies.add(toolCallReply("t" + i, i % 2 == 0 ? "probe_a" : "probe_b"));
            }
            final var requests = new ArrayList<List<Message>>();
            final var client = chatClient(replies, requests);

            final var result = loop(
                            client,
                            null,
                            baseline,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            registryOf(
                                    new EmbeddedTool() {
                                        @Override
                                        public String name() {
                                            return "probe_a";
                                        }

                                        @Override
                                        public String description(final Path workdir) {
                                            return "a";
                                        }

                                        @Override
                                        public ToolCategory category() {
                                            return ToolCategory.EDIT;
                                        }

                                        @Override
                                        public Map<String, Object> schema() {
                                            return Map.of();
                                        }

                                        @Override
                                        public ToolResult execute(
                                                final Map<String, Object> args, final ToolContext context) {
                                            return ToolResult.ok("shared result");
                                        }
                                    },
                                    new EmbeddedTool() {
                                        @Override
                                        public String name() {
                                            return "probe_b";
                                        }

                                        @Override
                                        public String description(final Path workdir) {
                                            return "b";
                                        }

                                        @Override
                                        public ToolCategory category() {
                                            return ToolCategory.EDIT;
                                        }

                                        @Override
                                        public Map<String, Object> schema() {
                                            return Map.of();
                                        }

                                        @Override
                                        public ToolResult execute(
                                                final Map<String, Object> args, final ToolContext context) {
                                            return ToolResult.ok("shared result");
                                        }
                                    }),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            // no doom-loop refusal: the calls have different signatures; the tombstone only steers the
            // no-progress guard, which stops the turn after repeated identical output
            assertNull(result, "the repeated identical output across tools must trip the no-progress guard");
            final var errors = events.stream()
                    .filter(AgentEvent.Error.class::isInstance)
                    .map(AgentEvent.Error.class::cast)
                    .toList();
            assertTrue(errors.stream().anyMatch(e -> e.message().contains("No progress")), "expect a no-progress stop");
        }
        /**
         * P1: a (state-changing) call repeated fewer than three times is never refused - even the
         * doom-loop breaker lets genuine short repeats through; only the configured threshold trips it.
         */
        @Test
        void repeatedBelowThresholdIsNotRefused() {
            final var calls = List.of(
                    new ToolCall("t1", "function", new ToolCall.FunctionCall("echo", "{\"path\":\"/a\"}"), 0),
                    new ToolCall("t2", "function", new ToolCall.FunctionCall("echo", "{\"path\":\"/a\"}"), 0));
            final var requests = new ArrayList<List<Message>>();
            final var client = scriptedToolClient(requests, calls);

            loop(
                            client,
                            null,
                            baseline,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            stateChangingRegistry(),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            final var refused = events.stream()
                    .filter(AgentEvent.ToolFinished.class::isInstance)
                    .map(AgentEvent.ToolFinished.class::cast)
                    .filter(t -> !t.success())
                    .toList();
            assertTrue(
                    refused.isEmpty(),
                    "two identical state-changing calls must stay legal (below threshold): " + refused);
        }
        /**
         * P2: five consecutive tool rounds whose outputs are byte-identical (same signature, no write)
         * terminate the turn with a clear error instead of spinning until maxIterations.
         */
        @Test
        void noProgressAfterRepeatedIdenticalOutputsStopsTheTurn() {
            final var replies = new ArrayDeque<ScriptedReply>();
            // 6 identical tool-call rounds: the first is novel, then 5 duplicates
            for (var i = 0; i < 6; i++) {
                replies.add(toolCallReply("t" + i, "echo"));
            }
            final var requests = new ArrayList<List<Message>>();
            final var client = chatClient(replies, requests);

            final var result = loop(
                            client,
                            null,
                            baseline,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            echoRegistry(),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertNull(result, "a no-progress turn must not return a normal answer");
            final var errors = events.stream()
                    .filter(AgentEvent.Error.class::isInstance)
                    .map(AgentEvent.Error.class::cast)
                    .toList();
            assertFalse(errors.isEmpty(), "a no-progress turn must emit an error");
            assertTrue(
                    errors.getLast().message().contains("No progress"),
                    "the error must explain the no-progress stop: "
                            + errors.getLast().message());
            assertTrue(requests.size() < 10, "the turn must stop way before maxIterations: " + requests.size());
        }
        /**
         * P2b: the exploration-plateau guard stops a turn whose read-only rounds re-cover only
         * already-seen files/results, even with novel call signatures (the doom-loop breaker would miss
         * them: different offsets/patterns are not identical signatures). Uses the global
         * llm.plateauRounds default (8) via the fallback (no provider-level override).
         */
        @Test
        void explorationPlateauStopsTheTurn() {
            final var replies = new ArrayDeque<ScriptedReply>();
            // The first read delivers all 30 fixture lines; eight contained reads have distinct
            // signatures and outputs (ToolRepeats stays silent), but redundant line coverage.
            final int[] offsets = {1, 2, 3, 4, 5, 6, 7, 8, 9};
            for (var i = 0; i < offsets.length; i++) {
                replies.add(toolCallReply(
                        "t" + i,
                        "read",
                        "{\"path\":\"/a.txt\",\"offset\":" + offsets[i] + ",\"limit\":" + (30 + i) + "}"));
            }
            final var requests = new ArrayList<List<Message>>();
            final var client = chatClient(replies, requests);

            final var result = loop(
                            client,
                            null,
                            baseline,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            readFileRegistry(),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertNull(result, "a plateau turn must not return a normal answer");
            final var errors = events.stream()
                    .filter(AgentEvent.Error.class::isInstance)
                    .map(AgentEvent.Error.class::cast)
                    .toList();
            assertFalse(errors.isEmpty(), "a plateau turn must emit an error");
            assertTrue(
                    errors.getLast().message().contains("Exploration plateau"),
                    "the error must explain the plateau stop: "
                            + errors.getLast().message());
            assertTrue(
                    requests.size() < 12,
                    "the turn must stop around the plateau limit, not maxIterations: " + requests.size());
        }
        /**
         * P2d: a write resets the plateau streak - the model may legitimately re-read files after
         * editing them. 2 redundant reads, a write, then 2 more redundant reads must NOT stop (the
         * write clears the counter).
         */
        @Test
        void plateauResetByWrite() {
            final var replies = new ArrayDeque<ScriptedReply>();
            replies.add(toolCallReply("r1", "read", "{\"path\":\"/a.txt\",\"offset\":0,\"limit\":30}"));
            replies.add(toolCallReply("r2", "read", "{\"path\":\"/a.txt\",\"offset\":2,\"limit\":10}"));
            replies.add(toolCallReply("w1", "write_file", "{\"path\":\"/a.txt\",\"content\":\"new\"}"));
            replies.add(toolCallReply("r3", "read", "{\"path\":\"/a.txt\",\"offset\":0,\"limit\":30}"));
            replies.add(toolCallReply("r4", "read", "{\"path\":\"/a.txt\",\"offset\":2,\"limit\":10}"));
            replies.add(new ScriptedReply("done", "stop", false));
            final var requests = new ArrayList<List<Message>>();
            final var client = chatClient(replies, requests);

            final var result = loop(
                            client,
                            null,
                            baseline,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            readWriteRegistry(),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertNotNull(result, "a write between redundant reads must keep the turn alive");
            final var errors = events.stream()
                    .filter(AgentEvent.Error.class::isInstance)
                    .map(AgentEvent.Error.class::cast)
                    .toList();
            assertTrue(errors.isEmpty(), "no plateau error expected: " + errors);
        }
        /**
         * P6: the volatile tail rounds the context usage to the nearest 5% so tiny drifts between tool
         * rounds do not re-append a new session-state message at every iteration.
         */
        @Test
        void contextUsageTailRoundsToFivePercent() throws Exception {
            final var loopInstance = loop(chatClient(new ArrayDeque<>(), new ArrayList<>()), null, baseline);
            // replace the stub UsageTracker (percent=1) with one whose percent drifts slightly
            final var field = AgentLoop.class.getDeclaredField("usageTracker");
            field.setAccessible(true);
            final var usage = new UsageTracker() {
                private int calls;

                @Override
                public double contextPercent(final int estimatedTokens) {
                    // 53.1% then 53.4%: within the same 5% bucket, the tail must stay byte-identical
                    return calls++ == 0 ? 53.1 : 53.4;
                }

                @Override
                public int estimateContext(final List<Message> messages, final int extraToolTokens) {
                    return 100;
                }

                @Override
                public boolean lastResolutionWasAuto() {
                    return false;
                }

                @Override
                public int rememberShrinkOnOverflow() {
                    return 1000;
                }
            };
            field.set(loopInstance, usage);

            final var first = loopInstance.contextUsageLine(List.of(Message.user("hi")), 0);
            final var second = loopInstance.contextUsageLine(List.of(Message.user("hi")), 0);
            assertEquals(first, second, "a sub-5% drift must not change the volatile tail (rounded to 55%): " + second);
            assertTrue(second.contains("~55%"), "the rounded value must be the nearest 5% bucket: " + second);
        }

        @Test
        void noTodosWarningInPlanMode() {
            final var todoStore = new TodoStore() {
                private List<TodoItem> todos = List.of(
                        new TodoItem("done step", "completed"),
                        new TodoItem("running step", "in_progress"),
                        new TodoItem("later step", "pending"));

                @Override
                public List<TodoItem> all() {
                    return todos;
                }

                @Override
                public synchronized List<TodoItem> replace(final List<TodoItem> items) {
                    todos = List.copyOf(items);
                    return all();
                }
            };
            final var replies = new ArrayDeque<>(List.of(
                    new ScriptedReply(null, "tool_calls", false) {
                        @Override
                        Message toMessage() {
                            return new Message(
                                    "assistant",
                                    null,
                                    null,
                                    List.of(new ToolCall("t1", "function", new ToolCall.FunctionCall("bash", "{}"), 0)),
                                    null,
                                    null);
                        }
                    },
                    new ScriptedReply("made progress", "stop", false)));
            final var config = baseline;
            permissionServiceOverride = new PermissionService(config, (config).resolveWorkingDir());
            permissionServiceOverride.setMode(AgentMode.PLAN);
            try {
                loop(chatClient(replies, new ArrayList<>()), todoStore, config)
                        .run("do it", null, "s1", null, () -> false);
            } finally {
                permissionServiceOverride = null;
            }

            assertEquals(
                    0,
                    events.stream().filter(AgentEvent.Notice.class::isInstance).count(),
                    "plan mode must not warn about pending todos: that is the goal of the mode");
        }

        @Test
        void noTodosWarningWithoutPendingItemsOrToolUsage() {
            final var todoStore = new TodoStore() {
                private List<TodoItem> todos = List.of(new TodoItem("done step", "completed"));

                @Override
                public List<TodoItem> all() {
                    return todos;
                }

                @Override
                public synchronized List<TodoItem> replace(final List<TodoItem> items) {
                    todos = List.copyOf(items);
                    return all();
                }
            };
            final var replies = new ArrayDeque<>(List.of(new ScriptedReply("all done", "stop", false)));
            loop(chatClient(replies, new ArrayList<>()), todoStore, baseline)
                    .run("do it", null, "s1", null, () -> false);

            assertEquals(
                    0,
                    events.stream().filter(AgentEvent.Notice.class::isInstance).count(),
                    "completed todos and a no-tool turn must stay silent");
        }

        @Test
        void noTodosWarningWhenOnlyCancelledItemsRemain() {
            final var todoStore = new TodoStore() {
                private List<TodoItem> todos = List.of(new TodoItem("dropped step", "cancelled"));

                @Override
                public List<TodoItem> all() {
                    return todos;
                }

                @Override
                public synchronized List<TodoItem> replace(final List<TodoItem> items) {
                    todos = List.copyOf(items);
                    return all();
                }
            };
            final var replies = new ArrayDeque<>(List.of(
                    new ScriptedReply(null, "tool_calls", false) {
                        @Override
                        Message toMessage() {
                            return new Message(
                                    "assistant",
                                    null,
                                    null,
                                    List.of(new ToolCall("t1", "function", new ToolCall.FunctionCall("bash", "{}"), 0)),
                                    null,
                                    null);
                        }
                    },
                    new ScriptedReply("adjusted", "stop", false)));
            loop(chatClient(replies, new ArrayList<>()), todoStore, baseline)
                    .run("do it", null, "s1", null, () -> false);

            assertEquals(
                    0,
                    events.stream().filter(AgentEvent.Notice.class::isInstance).count(),
                    "cancelled todos count as closed: no unfinished-todos warning");
        }

        @Test
        void compactionInsideTheLoopIsPersistedToTheSessionStore() {
            final var compactRequested = new AtomicBoolean();
            final List<List<Message>> replaced = new ArrayList<>();
            final var manager = new ContextManager() {
                @Override
                public boolean pruneNeeded(final List<Message> messages, final int extraToolTokens) {
                    return false;
                }

                @Override
                public boolean compactNeeded(final List<Message> messages, final int extraToolTokens) {
                    return compactRequested.compareAndSet(false, true);
                }

                @Override
                public CompactionResult compact(
                        final List<Message> messages,
                        final boolean force,
                        final String focus,
                        final int keepLastOverride,
                        final BooleanSupplier cancelled) {
                    return compact(messages, force, focus, keepLastOverride, cancelled, ChatSettings.EMPTY);
                }

                @Override
                public CompactionResult compact(
                        final List<Message> messages,
                        final boolean force,
                        final String focus,
                        final int keepLastOverride,
                        final boolean urgent,
                        final BooleanSupplier cancelled,
                        final ChatSettings settings,
                        final CompactionCoordinator.CompactionProgress progress) {
                    return new CompactionResult(
                            List.of(
                                    messages.get(0),
                                    Message.user("<compacted-context>\n"
                                            + "digest\n"
                                            + "</compacted-context>\n\n"
                                            + "When a compacted context is provided, treat it as historical"
                                            + " state.\n"
                                            + "Do not reload, reconstruct, or repeat actions already described in"
                                            + " it.\n"
                                            + "Continue from the state it represents; only call tools when needed"
                                            + " for the current task."),
                                    messages.get(2)),
                            CompactionResult.Mode.SUMMARY,
                            "");
                }
            };
            final var store = new SessionStore() {
                @Override
                public void append(final String id, final SessionEntry entry) {}

                @Override
                public void replace(final String id, final List<Message> messages) {
                    replaced.add(List.copyOf(messages));
                }
            };
            final var replies = new ArrayDeque<>(List.of(new ScriptedReply("all done", "stop", false)));
            loop(chatClient(replies, new ArrayList<>()), null, baseline, manager, store, null)
                    .run("do it", null, "s1", List.of(Message.user("old")), () -> false);

            assertEquals(1, replaced.size(), "the compacted context must be persisted exactly once");
            final var persisted = replaced.get(0);
            assertTrue(
                    persisted.stream().noneMatch(m -> "system".equals(m.role())),
                    "the system prompt is rebuilt every turn and must not be stored");
            assertTrue(
                    String.valueOf(persisted.get(0).content()).contains("<compacted-context>"),
                    "the digest must open the persisted history");
            assertEquals(
                    "do it", String.valueOf(persisted.get(persisted.size() - 1).content()));
            final var compacted = compactedEvents();
            assertEquals(1, compacted.size());
            assertEquals(List.of("compact"), compacted.get(0).kinds());
            assertEquals("SUMMARY", compacted.get(0).mode());
        }

        @Test
        void repeatedProductiveCompactionsDoNotEndTheTurn() {
            final var compactRuns = new AtomicInteger();
            final var forcedCompactRuns = new AtomicInteger();
            final var manager = new ContextManager() {
                @Override
                public boolean pruneNeeded(final List<Message> messages, final int extraToolTokens) {
                    return false;
                }

                @Override
                public boolean compactNeeded(final List<Message> messages, final int extraToolTokens) {
                    return true;
                }

                @Override
                public int usableWindowTokens() {
                    return 10_000;
                }

                @Override
                public CompactionResult compactForRecovery(
                        final List<Message> messages,
                        final BooleanSupplier cancelled,
                        final ChatSettings settings,
                        final CompactionCoordinator.CompactionProgress progress) {
                    forcedCompactRuns.incrementAndGet();
                    return new CompactionResult(messages, CompactionResult.Mode.NONE, "no further reduction");
                }

                @Override
                public int compactionTriggerTokens() {
                    return 1;
                }

                @Override
                public CompactionResult compact(
                        final List<Message> messages,
                        final boolean force,
                        final String focus,
                        final int keepLastOverride,
                        final boolean urgent,
                        final BooleanSupplier cancelled,
                        final ChatSettings settings,
                        final CompactionCoordinator.CompactionProgress progress) {
                    compactRuns.incrementAndGet();
                    final var reduced = new ArrayList<>(messages);
                    reduced.add(Message.user("<compacted-context>digest</compacted-context>"));
                    return new CompactionResult(reduced, CompactionResult.Mode.SUMMARY, "");
                }
            };
            final var requests = new ArrayList<List<Message>>();
            final var replies = new ArrayDeque<ScriptedReply>();
            for (var round = 0; round < 6; round++) {
                replies.add(toolCallReply("compact-tick-" + round, "tick", "{\"round\":" + round + "}"));
            }
            replies.add(new ScriptedReply("all done", "stop", false));
            final var tick = new EmbeddedTool() {
                private final AtomicInteger calls = new AtomicInteger();

                @Override
                public String name() {
                    return "tick";
                }

                @Override
                public String description(final Path workdir) {
                    return "returns a distinct result on every call";
                }

                @Override
                public ToolCategory category() {
                    return ToolCategory.READ;
                }

                @Override
                public Map<String, Object> schema() {
                    return Map.of();
                }

                @Override
                public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                    return ToolResult.ok("tick " + calls.incrementAndGet());
                }
            };

            final var result = loop(
                            chatClient(replies, requests),
                            null,
                            baseline,
                            manager,
                            noSessionStore(),
                            null,
                            registryOf(tick),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertEquals("all done", result, "events: " + events);
            assertEquals(7, requests.size(), "all six tool rounds and the final answer must run");
            assertEquals(13, compactRuns.get(), "normal compaction runs at both checkpoints");
            assertEquals(1, forcedCompactRuns.get(), "forced recovery is attempted at most once per turn");
            assertTrue(
                    events.stream()
                            .noneMatch(e -> e instanceof AgentEvent.Notice notice
                                    && notice.level() == AgentEvent.Notice.Level.ERROR
                                    && notice.message().contains("Context keeps filling up")),
                    "productive compaction must not terminate the turn");
        }

        @Test
        void usageIsNotClobberedWhenUsageAndFinishShareTheFinalStreamLine() {
            // a single SSE line carrying both usage and a finish reason is decoded by the dialect into a
            // usage End followed by a finish-reason-only End; the trailing End must not reset the
            // captured usage back to empty (regression: tokens reported 0 for every streamed turn)
            recordedUsage.set(null);
            final var usage = new io.yupiik.llm.client.model.Usage(12L, 7L, 19L, 3L, 1L);
            final var replies = new ArrayDeque<>(List.of(new ScriptedReply("done", "stop", false, usage)));
            final var result = loop(chatClient(replies, new ArrayList<>()), null, baseline)
                    .run("do it", null, "s1", null, () -> false);

            assertEquals("done", result);
            final var recorded = recordedUsage.get();
            assertNotNull(recorded, "usage must be recorded for the finished turn");
            assertEquals(12, recorded.promptTokens(), "prompt tokens must survive the trailing finish End");
            assertEquals(7, recorded.completionTokens(), "completion tokens must survive the trailing finish End");
        }

        @Test
        void manageContextDirectiveIsAppliedOnceAfterTheToolRound() {
            final var view = new ContextView();
            assertTrue(view.request(new ContextView.Directive("compact", null, 0, "manage_context")));
            final var compactRuns = new AtomicInteger();
            final var manager = new ContextManager() {
                @Override
                public boolean pruneNeeded(final List<Message> messages, final int extraToolTokens) {
                    return false;
                }

                @Override
                public boolean compactNeeded(final List<Message> messages, final int extraToolTokens) {
                    return false;
                }

                @Override
                public CompactionResult compact(final List<Message> messages, final String focus, final int keepLast) {
                    return compact(messages, false, focus, keepLast);
                }

                @Override
                public CompactionResult compact(
                        final List<Message> messages, final boolean force, final String focus, final int keepLast) {
                    return compact(messages, force, focus, keepLast, () -> false, ChatSettings.EMPTY);
                }

                @Override
                public CompactionResult compact(
                        final List<Message> messages,
                        final boolean force,
                        final String focus,
                        final int keepLast,
                        final BooleanSupplier cancelled,
                        final ChatSettings settings) {
                    return compact(messages, force, focus, keepLast, false, cancelled, settings);
                }

                @Override
                public CompactionResult compact(
                        final List<Message> messages,
                        final boolean force,
                        final String focus,
                        final int keepLast,
                        final boolean urgent,
                        final BooleanSupplier cancelled,
                        final ChatSettings settings,
                        final CompactionCoordinator.CompactionProgress progress) {
                    compactRuns.incrementAndGet();
                    return new CompactionResult(
                            List.of(
                                    messages.get(0),
                                    Message.user("<compacted-context>\ndigest\n</compacted-context>"),
                                    messages.get(messages.size() - 1)),
                            CompactionResult.Mode.SUMMARY,
                            "");
                }
            };
            final var store = new SessionStore() {
                @Override
                public void append(final String id, final SessionEntry entry) {}

                @Override
                public void replace(final String id, final List<Message> messages) {}
            };
            final var calls = new AtomicInteger();
            final var client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled) {
                    if (calls.incrementAndGet() == 1) {
                        return Message.assistant(
                                null,
                                List.of(new ToolCall("t1", "function", new ToolCall.FunctionCall("bash", "{}"), 0)));
                    }
                    onDelta.accept(new End("stop", null));
                    return Message.assistant("all done", null);
                }
            };
            final var result =
                    loop(client, null, baseline, manager, store, view).run("do it", null, "s1", null, () -> false);

            assertEquals("all done", result);
            assertEquals(1, compactRuns.get(), "the directive must be applied exactly once");
            final var compacted = compactedEvents();
            assertEquals(1, compacted.size());
            assertEquals(List.of("tool"), compacted.get(0).kinds());
            assertNull(view.poll(), "the directive must be consumed");
        }

        @Test
        void automaticPruneCheckpointMergesZeroLlmPruneAndCompactIntoOneEvent() {
            // dedup + prune + LLM compact all run at the same checkpoint in a single round: they must
            // collapse into ONE Compacted event carrying the applied kinds (single "context applied"
            // line), with the most informative mode (SUMMARY wins over CLEARED).
            final var manager = new ContextManager() {
                @Override
                public boolean pruneNeeded(final List<Message> messages, final int extraToolTokens) {
                    return true;
                }

                @Override
                public boolean compactNeeded(final List<Message> messages, final int extraToolTokens) {
                    return true;
                }

                @Override
                public CompactionResult prune(final List<Message> messages) {
                    return new CompactionResult(
                            List.of(messages.get(0), Message.user("<pruned-context>\ndigest\n</pruned-context>")),
                            CompactionResult.Mode.CLEARED,
                            "");
                }

                @Override
                public CompactionResult compact(
                        final List<Message> messages,
                        final boolean force,
                        final String focus,
                        final int keepLast,
                        final boolean urgent,
                        final BooleanSupplier cancelled,
                        final ChatSettings settings,
                        final CompactionCoordinator.CompactionProgress progress) {
                    return new CompactionResult(
                            List.of(
                                    messages.get(0),
                                    Message.user("<compacted-context>\ndigest\n</compacted-context>"),
                                    messages.get(messages.size() - 1)),
                            CompactionResult.Mode.SUMMARY,
                            "");
                }
            };
            final var store = new SessionStore() {
                @Override
                public void append(final String id, final SessionEntry entry) {}

                @Override
                public void replace(final String id, final List<Message> messages) {}
            };
            final var replies = new ArrayDeque<>(List.of(new ScriptedReply("all done", "stop", false)));
            // build a history with a duplicate tool call so the dedup zero-LLM strategy fires
            final var messages = new ArrayList<Message>();
            messages.add(Message.assistant(
                    null,
                    List.of(new ToolCall("c1", "function", new ToolCall.FunctionCall("same_tool", "{\"a\":1}"), 0))));
            messages.add(Message.tool("c1", "out-1"));
            messages.add(Message.assistant(
                    null,
                    List.of(new ToolCall("c2", "function", new ToolCall.FunctionCall("same_tool", "{\"a\":1}"), 0))));
            messages.add(Message.tool("c2", "out-2"));
            messages.add(Message.user("do it"));
            loop(chatClient(replies, new ArrayList<>()), null, baseline, manager, store, null)
                    .run("do it", null, "s1", messages, () -> false);

            final var compacted = compactedEvents();
            assertEquals(1, compacted.size(), "all reductions of the same checkpoint must be merged: " + compacted);
            assertEquals(List.of("dedup", "prune", "compact"), compacted.get(0).kinds());
            assertEquals(
                    "SUMMARY",
                    compacted.get(0).mode(),
                    "the LLM compaction mode must win over the prune/zero-LLM CLEARED");
            assertNotNull(compacted.get(0).tokens(), "the merged event must carry the raw before/after estimates");
            assertTrue(
                    compacted.get(0).tokens().after() >= 0
                            && compacted.get(0).tokens().before()
                                    >= compacted.get(0).tokens().after(),
                    "the after estimate must not exceed the before one: "
                            + compacted.get(0).tokens());
        }

        @Test
        void identicalToolCallsAreDeduplicatedKeepingTheLatest() {
            final var loop = loop(scriptedToolClient(new ArrayList<>(), List.of()), null, baseline);
            final var messages = new ArrayList<Message>();
            messages.add(Message.assistant(
                    null,
                    List.of(new ToolCall("c1", "function", new ToolCall.FunctionCall("same_tool", "{\"a\":1}"), 0))));
            messages.add(Message.tool("c1", "out-1"));
            messages.add(Message.assistant(
                    null,
                    List.of(new ToolCall("c2", "function", new ToolCall.FunctionCall("same_tool", "{\"a\":1}"), 0))));
            messages.add(Message.tool("c2", "out-2"));

            assertEquals(1, loop.deduplicateToolCalls(messages));
            assertTrue(
                    String.valueOf(messages.get(1).content()).startsWith("[duplicate tool call pruned"),
                    "the older identical result must be replaced by a marker");
            assertEquals("out-2", String.valueOf(messages.get(3).content()), "the latest output must survive");
        }

        @Test
        void protectedToolsAreNotDeduplicated() {
            final var loop = loop(scriptedToolClient(new ArrayList<>(), List.of()), null, baseline);
            final var messages = new ArrayList<Message>();
            messages.add(Message.assistant(
                    null,
                    List.of(new ToolCall(
                            "w1", "function", new ToolCall.FunctionCall("write_file", "{\"path\":\"a\"}"), 0))));
            messages.add(Message.tool("w1", "written"));
            messages.add(Message.assistant(
                    null,
                    List.of(new ToolCall(
                            "w2", "function", new ToolCall.FunctionCall("write_file", "{\"path\":\"a\"}"), 0))));
            messages.add(Message.tool("w2", "written"));

            assertEquals(0, loop.deduplicateToolCalls(messages), "write_file must be protected from dedup");
        }

        @Test
        void staleReadsAppendANoteInsteadOfRewritingPriorReads() {
            // After a write_file invalidates an earlier read of the same path, the prior
            // read message must stay byte-identical (append-only, so the provider prompt-prefix
            // cache survives); the caller folds the stale paths into the write tool's own result
            // message, so NO extra message enters the conversation at all.
            final var staleReads = new AgentLoop.StaleReads(Map.of("r1", Path.of("/a.txt")));
            final var messages = new ArrayList<Message>();
            messages.add(Message.tool("r1", "original read content of /a.txt"));
            // appendToolResult appends the write result AFTER computing the stale paths, so onLanded
            // runs against the conversation WITHOUT the write result message.
            final var write = ToolOutcome.executed(
                    "w1", "write_file", ToolResult.ok("written"), List.of("/a.txt"), 10L, List.of());

            final var stalePaths = staleReads.onLanded(messages, write);

            // the prior read message is NOT rewritten (append-only => provider prefix cache survives)
            assertEquals(List.of(Path.of("/a.txt")), stalePaths, "the stale path must be reported");
            assertEquals(
                    "original read content of /a.txt",
                    String.valueOf(messages.get(0).content()));
            // no message is appended by onLanded itself (the note rides in the write result)
            assertEquals(
                    1, messages.size(), "onLanded must not append messages: the note rides in the write tool result");
        }

        @Test
        void consecutiveReadonlyToolsRunConcurrentlyAndResultsKeepCallOrder() {
            final var barrier = new CyclicBarrier(2);
            final var requests = new ArrayList<List<Message>>();
            final var client = scriptedToolClient(
                    requests,
                    List.of(
                            new ToolCall("t1", "function", new ToolCall.FunctionCall("slow_read_a", "{}"), 0),
                            new ToolCall("t2", "function", new ToolCall.FunctionCall("slow_read_b", "{}"), 1)));
            final var result = loop(
                            client,
                            null,
                            baseline,
                            noPruningManager(),
                            noopStore(),
                            null,
                            barrierRegistry(barrier, "slow_read_a", "slow_read_b"),
                            directExecutions(),
                            EXECUTOR)
                    .run("do it", null, "s1", null, () -> false);

            assertEquals("all done", result);
            final var tools = requests.get(1).stream()
                    .filter(m -> "tool".equals(m.role()))
                    .toList();
            assertEquals(2, tools.size(), "both tool results must reach the next request");
            assertEquals("t1", tools.get(0).toolCallId());
            assertEquals("done-a", tools.get(0).content());
            assertEquals("t2", tools.get(1).toolCallId());
            assertEquals(
                    "done-b", tools.get(1).content(), "the tool result stays intact without any session-state tail");
            assertEquals(6, requests.get(1).size(), "system + user + tail + assistant + both tool results");
            final var tail = requests.get(1).get(2);
            assertEquals("user", tail.role(), "the session state rides its own trailing user message");
            assertTrue(
                    String.valueOf(tail.content()).startsWith("<session-state>"),
                    "the session state is a standalone trailing message: " + tail.content());
            assertEquals(Boolean.TRUE, tail.others().get("volatileTail"), "the tail is flagged volatile");
        }

        @Test
        void stateChangingToolKeepsSurroundingCallsSequentialInOrder() {
            final var order = Collections.synchronizedList(new ArrayList<String>());
            final var requests = new ArrayList<List<Message>>();
            final var client = scriptedToolClient(
                    requests,
                    List.of(
                            new ToolCall("t1", "function", new ToolCall.FunctionCall("read_a", "{}"), 0),
                            new ToolCall("t2", "function", new ToolCall.FunctionCall("agent_x", "{}"), 1),
                            new ToolCall("t3", "function", new ToolCall.FunctionCall("read_b", "{}"), 2)));
            final var registry = new ToolRegistry(null) {
                @Override
                public List<EmbeddedTool> tools() {
                    return List.of(tool("read_a"), tool("agent_x"), tool("read_b"));
                }

                @Override
                public EmbeddedTool tool(final String name) {
                    if (!List.of("read_a", "agent_x", "read_b").contains(name)) {
                        return null;
                    }
                    final var readonly = !"agent_x".equals(name);
                    return new EmbeddedTool() {
                        @Override
                        public String name() {
                            return name;
                        }

                        @Override
                        public String description(final Path workdir) {
                            return "test tool";
                        }

                        @Override
                        public ToolCategory category() {
                            // agent-category and not readonly: allowed by the permission service but
                            // never eligible for a parallel run
                            return readonly ? ToolCategory.READ : ToolCategory.AGENT;
                        }

                        @Override
                        public Map<String, Object> schema() {
                            return Map.of();
                        }

                        @Override
                        public boolean readonly() {
                            return readonly;
                        }

                        @Override
                        public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                            order.add(name);
                            return ToolResult.ok("ran " + name);
                        }
                    };
                }
            };
            final var result = loop(
                            client,
                            null,
                            baseline,
                            noPruningManager(),
                            noopStore(),
                            null,
                            registry,
                            directExecutions(),
                            EXECUTOR)
                    .run("do it", null, "s1", null, () -> false);

            assertEquals("all done", result);
            assertEquals(
                    List.of("read_a", "agent_x", "read_b"), order, "a state-changing call must never be overtaken");
            final var tools = requests.get(1).stream()
                    .filter(m -> "tool".equals(m.role()))
                    .toList();
            assertEquals(
                    List.of("t1", "t2", "t3"),
                    tools.stream().map(Message::toolCallId).toList());
        }

        @Test
        void repeatedIdenticalSuccessfulCallsAreRefusedWithGuidance() {
            final var executions = new AtomicInteger();
            final var requests = new ArrayList<List<Message>>();
            final var calls = new AtomicInteger();
            final var client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled) {
                    requests.add(List.copyOf(request.messages()));
                    final var round = calls.incrementAndGet();
                    if (round <= 5) {
                        return Message.assistant(
                                null,
                                List.of(new ToolCall(
                                        "t" + round,
                                        "function",
                                        new ToolCall.FunctionCall("probe", "{\"q\":\"same\"}"),
                                        0)));
                    }
                    onDelta.accept(new End("stop", null));
                    return Message.assistant("gave up looping", null);
                }
            };
            final var registry = new ToolRegistry(null) {
                @Override
                public List<EmbeddedTool> tools() {
                    return List.of(tool("probe"));
                }

                @Override
                public EmbeddedTool tool(final String name) {
                    if (!"probe".equals(name)) {
                        return null;
                    }
                    return new EmbeddedTool() {
                        @Override
                        public String name() {
                            return "probe";
                        }

                        @Override
                        public String description(final Path workdir) {
                            return "test tool";
                        }

                        @Override
                        public ToolCategory category() {
                            return ToolCategory.EDIT;
                        }

                        @Override
                        public Map<String, Object> schema() {
                            return Map.of();
                        }

                        @Override
                        public boolean readonly() {
                            return false;
                        }

                        @Override
                        public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                            executions.incrementAndGet();
                            return ToolResult.ok("probe result");
                        }
                    };
                }
            };
            final var result = loop(
                            client,
                            null,
                            baseline,
                            noPruningManager(),
                            noopStore(),
                            null,
                            registry,
                            directExecutions(),
                            EXECUTOR)
                    .run("do it", null, "s1", null, () -> false);

            assertEquals("gave up looping", result);
            // threshold 3 means the first THREE identical-output successes run, then the fourth identical
            // call is refused (never executed)
            assertEquals(3, executions.get(), "only the first three identical calls must execute");
            // the refusal is now surfaced to the model as a strategy nudge (not a failed tool result):
            // a user-role message carrying the these-are-the-same-again guidance appears in the conversation
            assertTrue(
                    hasDoomLoopNudge(requests),
                    "a doom-loop strategy nudge must be injected for the model: " + requests);
            // the refused call carries the stable threshold count via its finished event (never escalated)
            final var refusedEvent = events.stream()
                    .filter(AgentEvent.ToolFinished.class::isInstance)
                    .map(AgentEvent.ToolFinished.class::cast)
                    .filter(t -> !t.success())
                    .findFirst();
            assertTrue(
                    refusedEvent.isPresent() && refusedEvent.get().output().contains("3 times"),
                    "the refusal must report the stable threshold count (3), not escalate: " + refusedEvent);
        }

        @Test
        void sessionStateRidesItsOwnTrailingUserMessage() {
            final var requests = new ArrayList<List<Message>>();
            final var replies = new ArrayDeque<>(List.of(new ScriptedReply("answer", "stop", false)));
            final var client = chatClient(replies, requests);
            loop(client, new TodoStore() {}, baseline).run("my prompt", null, "s1", null, () -> false);
            assertEquals(1, requests.size());
            final var sent = requests.get(0);
            assertEquals(3, sent.size(), "system + the user prompt + the standalone session-state message");
            assertEquals("system prompt", sent.get(0).content());
            assertEquals("user", sent.get(1).role());
            assertEquals("my prompt", sent.get(1).content(), "the user prompt must not be mutated by the tail");
            assertEquals("user", sent.get(2).role());
            assertTrue(
                    String.valueOf(sent.get(2).content()).startsWith("<session-state>"),
                    "the refreshed session state is its own trailing message: "
                            + sent.get(2).content());
            assertTrue(
                    sent.get(2).others() != null
                            && Boolean.TRUE.equals(sent.get(2).others().get("volatileTail")),
                    "the session-state message must be flagged volatileTail for cache breakpoint dialects");
        }

        @Test
        void resumeRerunsTheLoopWithoutAddingAnyUserMessage() {
            final var requests = new ArrayList<List<Message>>();
            final var replies = new ArrayDeque<>(List.of(new ScriptedReply("resumed answer", "stop", false)));
            final var client = chatClient(replies, requests);
            final var appended = new ArrayList<SessionEntry>();
            final var store = new SessionStore() {
                @Override
                public void append(final String id, final SessionEntry entry) {
                    appended.add(entry);
                }
            };
            final var history = new ArrayList<Message>();
            history.add(Message.user("original question"));
            history.add(Message.assistant("partial answer", null));
            final var loop = loop(client, new TodoStore() {}, baseline, noPruningManager(), store, null);
            loop.resume(null, "s1", history, () -> false);
            assertEquals(1, requests.size());
            final var sent = requests.get(0);
            assertEquals(4, sent.size(), "system + history verbatim + trailing session-state user message");
            assertEquals("system", sent.get(0).role());
            assertEquals("original question", sent.get(1).content());
            assertEquals("partial answer", sent.get(2).content());
            assertEquals("user", sent.get(3).role());
            assertTrue(
                    String.valueOf(sent.get(3).content()).startsWith("<session-state>"),
                    "the trailing wire message only carries the refreshed session state: "
                            + sent.get(3).content());
            assertTrue(
                    sent.get(3).others() != null
                            && Boolean.TRUE.equals(sent.get(3).others().get("volatileTail")),
                    "the session-state message must be flagged volatileTail for dialects using cache breakpoints");
            assertTrue(
                    appended.stream().noneMatch(e -> "user".equals(e.role())),
                    "resume must not persist any new input (only the assistant reply is appended)");
        }

        @Test
        void queuedPromptIsIntegratedMidToolLoop() {
            final var requests = new ArrayList<List<Message>>();
            final var appended = new ArrayList<SessionEntry>();
            final var store = new SessionStore() {
                @Override
                public void append(final String id, final SessionEntry entry) {
                    appended.add(entry);
                }
            };
            final var client = scriptedToolClient(
                    requests,
                    List.of(new ToolCall("call-1", "function", new ToolCall.FunctionCall("grep", "{}"), null)));
            final var polls = new AtomicInteger();
            final var loop = loop(client, new TodoStore() {}, baseline, noPruningManager(), store, null);
            loop.run("do it", null, "s1", null, () -> false, () -> polls.getAndIncrement() == 1 ? "follow up!" : null);
            assertEquals(2, requests.size(), "tool round then final answer carrying the injected prompt");
            final var injected = requests.get(1);
            assertEquals("user", injected.get(injected.size() - 1).role());
            assertEquals("follow up!", injected.get(injected.size() - 1).content());
            // the deferred queued prompt must never break the tool_use -> tool_result pairing: every
            // assistant tool call of the previous round has its result landed contiguously in this request,
            // otherwise Anthropic/Azure AI Gateway rejects it ("tool_use ids ... without tool_result blocks
            // immediately after")
            assertAllToolCallsAreAnsweredByContiguousResults(injected);
            assertTrue(
                    injected.stream()
                            .anyMatch(m -> m.others() != null
                                    && Boolean.TRUE.equals(m.others().get("volatileTail"))),
                    "the previous session-state tail is still in the request (state did not change, no duplicate): "
                            + injected);
            assertTrue(
                    events.stream().anyMatch(candidate -> candidate instanceof AgentEvent.PromptIntegrated),
                    "the queued prompt must surface as a PromptIntegrated event");
            assertTrue(
                    appended.stream().anyMatch(e -> "user".equals(e.role()) && "follow up!".equals(e.content())),
                    "the injected prompt must be persisted as a user entry");
        }

        @Test
        void repairsInterruptedToolCallFromHistoryBeforeSending() {
            // a previous turn ended mid-tool (cancel/interrupt): history holds an assistant tool_use with
            // no result. Before the first request, the loop must answer it with a lost-result marker so
            // Anthropic's tool_use -> tool_result contiguity rule holds on the wire, and must persist it.
            final var requests = new ArrayList<List<Message>>();
            final var persisted = new ArrayList<SessionEntry>();
            final var replied = new ArrayDeque<ScriptedReply>(List.of(new ScriptedReply("done", "stop", false)));
            final var client = chatClient(replied, requests);
            final var store = new SessionStore() {
                @Override
                public void append(final String id, final SessionEntry entry) {
                    persisted.add(entry);
                }
            };
            final var history = new ArrayList<Message>();
            history.add(new Message(
                    "assistant",
                    null,
                    null,
                    List.of(new ToolCall("lost-1", "function", new ToolCall.FunctionCall("grep", "{}"), 0)),
                    null,
                    null));
            final var loop = loop(client, new TodoStore() {}, baseline, noPruningManager(), store, null);
            loop.run("do it", null, "s1", history, () -> false);

            // the request must carry the assistant tool_use immediately followed by its synthetic result
            final var request = requests.getFirst();
            assertEquals("assistant", request.get(1).role());
            assertFalse(request.get(1).toolCalls().isEmpty(), "the assistant tool_use is preserved");
            assertEquals("tool", request.get(2).role(), "the synthetic result lands immediately after the tool_use");
            assertEquals("lost-1", request.get(2).toolCallId());
            assertEquals(
                    "[tool result lost: the model's tool call was interrupted before it could complete this turn]",
                    request.get(2).content(),
                    "the synthetic result must carry the lost-result message");
            assertAllToolCallsAreAnsweredByContiguousResults(request);
            // the repair is persisted as an error tool entry
            assertTrue(
                    persisted.stream()
                            .anyMatch(e -> "tool".equals(e.role())
                                    && "lost-1".equals(e.toolCallId())
                                    && e.others() != null
                                    && Boolean.TRUE.equals(e.others().get("is_error"))),
                    "the synthetic lost-result marker must be persisted as an is_error tool entry: " + persisted);
            assertTrue(
                    events.stream()
                            .anyMatch(candidate -> candidate instanceof AgentEvent.Status s
                                    && s.message().contains("repaired 1 interrupted tool call")),
                    "a repair notice must be emitted");
        }

        @Test
        void doesNotDoubleRepairAlreadyAnsweredHistory() {
            // history where the tool_use already has a contiguous result: the repair must NOT add another,
            // and the session store must not grow with a duplicate marker.
            final var requests = new ArrayList<List<Message>>();
            final var persisted = new ArrayList<SessionEntry>();
            final var replied = new ArrayDeque<ScriptedReply>(List.of(new ScriptedReply("done", "stop", false)));
            final var client = chatClient(replied, requests);
            final var store = new SessionStore() {
                @Override
                public void append(final String id, final SessionEntry entry) {
                    persisted.add(entry);
                }
            };
            final var history = new ArrayList<Message>();
            history.add(new Message(
                    "assistant",
                    null,
                    null,
                    List.of(new ToolCall("ok-1", "function", new ToolCall.FunctionCall("grep", "{}"), 0)),
                    null,
                    null));
            history.add(new Message("tool", "matched", null, null, "ok-1", null));
            final var loop = loop(client, new TodoStore() {}, baseline, noPruningManager(), store, null);
            loop.run("do it", null, "s1", history, () -> false);

            final var request = requests.getFirst();
            assertEquals(
                    1,
                    request.stream().filter(m -> "tool".equals(m.role())).count(),
                    "only the original result, no synthetic duplicate: " + request);
            assertAllToolCallsAreAnsweredByContiguousResults(request);
            assertTrue(
                    persisted.stream()
                            .noneMatch(e -> "tool".equals(e.role())
                                    && e.content().toString().contains("tool result lost")),
                    "no synthetic marker may be persisted for an already-answered history: " + persisted);
        }

        @Test
        void queuedPromptAtFinalAnswerKeepsTheTurnRunning() {
            final var requests = new ArrayList<List<Message>>();
            final var replies = new ArrayDeque<>(List.of(
                    new ScriptedReply("first answer", "stop", false),
                    new ScriptedReply("second answer", "stop", false)));
            final var client = chatClient(replies, requests);
            final var polls = new AtomicInteger();
            final var loop =
                    loop(client, new TodoStore() {}, baseline, noPruningManager(), new SessionStore() {}, null);
            loop.run("do it", null, "s1", null, () -> false, () -> polls.getAndIncrement() == 1 ? "one more" : null);
            assertEquals(2, requests.size(), "the queued prompt must trigger a second LLM call");
            assertTrue(
                    events.stream().anyMatch(candidate -> candidate instanceof AgentEvent.PromptIntegrated),
                    "the queued prompt must surface as a PromptIntegrated event");
            assertEquals(
                    1,
                    events.stream()
                            .filter(candidate -> candidate instanceof AgentEvent.TurnDone)
                            .count(),
                    "TurnDone must be emitted exactly once, when the turn actually ends");
        }

        @Test
        void memoryIsNotResentInEveryRoundTail() {
            final var cfg = baseline;
            final var memory = new MemoryStore(cfg);
            memory.append(true, List.of("a durable fact"), cfg.resolveWorkingDir());
            final var requests = new ArrayList<List<Message>>();
            final var client = scriptedToolClient(
                    requests,
                    List.of(new ToolCall(
                            "c1", "function", new ToolCall.FunctionCall("read", "{\"path\":\"a.txt\"}"), 0)));
            final var registry = registryOf(new EmbeddedTool() {
                @Override
                public String name() {
                    return "read";
                }

                @Override
                public String description(final Path workdir) {
                    return "read a file";
                }

                @Override
                public ToolCategory category() {
                    return ToolCategory.READ;
                }

                @Override
                public Map<String, Object> schema() {
                    return Map.of();
                }

                @Override
                public boolean readonly() {
                    return true;
                }

                @Override
                public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                    return ToolResult.ok("file content");
                }
            });
            final var builder =
                    new SystemPromptBuilder(
                            cfg, memory, null, null, null, null, null, null, null, (cfg).resolveWorkingDir()) {
                        @Override
                        public String build(final String prompt, final AgentConfiguration agent) {
                            return "system\n\n" + memorySection(prompt);
                        }
                    };
            final var loop = loop(
                    client,
                    null,
                    cfg,
                    noPruningManager(),
                    new SessionStore() {},
                    null,
                    registry,
                    directExecutions(),
                    null,
                    builder);
            loop.run("do the work", null, "s1", null, () -> false);
            assertEquals(2, requests.size(), "prompt round + 1 tool round: " + requests);
            final var firstSystem = String.valueOf(requests.get(0).get(0).content());
            assertTrue(firstSystem.contains("a durable fact"), "memory must be in the stable prefix: " + firstSystem);
            final var secondTail = String.valueOf(
                    requests.get(1).get(requests.get(1).size() - 1).content());
            assertFalse(secondTail.contains("<memory>"), "round 2 must not re-send memory in the tail: " + secondTail);
            assertFalse(
                    secondTail.contains("a durable fact"), "round 2 tail must not carry memory facts: " + secondTail);
        }

        @Test
        void memoryWriteMidTurnPersistsToDiskButDoesNotRefreshTheTail() {
            final var cfg = baseline;
            final var memory = new MemoryStore(cfg);
            memory.append(true, List.of("fact before"), cfg.resolveWorkingDir());
            final var requests = new ArrayList<List<Message>>();
            final var client = scriptedToolClient(
                    requests,
                    List.of(new ToolCall(
                            "c1",
                            "function",
                            new ToolCall.FunctionCall("memory_write", "{\"fact\":\"fact after\",\"scope\":\"global\"}"),
                            0)));
            final var registry = registryOf(new MemoryWriteTool(memory));
            final var builder =
                    new SystemPromptBuilder(
                            cfg, memory, null, null, null, null, null, null, null, (cfg).resolveWorkingDir()) {
                        @Override
                        public String build(final String prompt, final AgentConfiguration agent) {
                            return "system\n\n" + memorySection(prompt);
                        }
                    };
            final var loop = loop(
                    client,
                    null,
                    cfg,
                    noPruningManager(),
                    new SessionStore() {},
                    null,
                    registry,
                    directExecutions(),
                    null,
                    builder);
            loop.run("work", null, "s1", null, () -> false);
            assertEquals(2, requests.size(), "prompt round + 1 tool round: " + requests);
            final var secondRequest = requests.get(1);
            // the frozen-prefix design: the memory prefix is built once per session and never re-appended
            // mid-session, so a mid-turn memory_write (which persists directly to disk) must NOT refresh
            // the volatile tail (no <memory-diff> / no memory section) - the current session already has
            // the fact if it is relevant, the next session reads it from disk.
            final var tail = secondRequest.stream()
                    .filter(m ->
                            m.others() != null && Boolean.TRUE.equals(m.others().get("volatileTail")))
                    .map(m -> String.valueOf(m.content()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(tail, "the second request must carry the volatile session-state tail");
            assertFalse(tail.contains("<memory-diff>"), "no memory refresh in the tail: " + tail);
            assertFalse(tail.contains("<memory>"), "the tail must not re-send the memory section: " + tail);
            assertFalse(tail.contains("fact after"), "the tail must not carry the mid-turn fact: " + tail);
            // no message at all may carry the memory-diff refresh marker
            assertTrue(
                    secondRequest.stream()
                            .noneMatch(m -> String.valueOf(m.content()).contains("<memory-diff>")),
                    "no request message may carry a memory-diff refresh");
            // the fact IS durable on disk for the next session
            assertTrue(memory.readGlobal().contains("fact after"), "memory_write must persist to disk");
        }

        @Test
        void sessionRootSelectsWireDeclarationsAndServedTools(@TempDir final Path root) {
            final var registry = new ToolRegistry(null) {

                @Override
                public List<Tool> toWireTools(final Path projectRoot) {
                    assertEquals(root, projectRoot);
                    return List.of(Tool.function("session_tool", "session project", Map.of()));
                }

                @Override
                public EmbeddedTool tool(final String name) {
                    return null;
                }
            };
            final var replies = new ArrayDeque<>(List.of(new ScriptedReply("done", "stop", false)));
            final var loop = loop(
                    chatClient(replies, new ArrayList<>()),
                    null,
                    withWorkingDir(baseline, root.toString()),
                    noContextManager(),
                    noSessionStore(),
                    null,
                    registry,
                    null,
                    null);

            assertEquals(
                    List.of("session_tool"),
                    loop.wireTools().stream().map(t -> t.function().name()).toList());
            assertEquals("done", loop.run("work here", null, "session", null, () -> false));
            assertEquals(Set.of("session_tool"), loop.servedToolNames);
        }

        @Test
        void lastLlmPayloadIsCapturedAndThePreviewRebuildsTheRequest() {
            final var replies = new ArrayDeque<>(List.of(new ScriptedReply("done", "stop", false)));
            final var requests = new ArrayList<List<Message>>();
            final var loop = loop(
                    chatClient(replies, requests),
                    null,
                    baseline,
                    noContextManager(),
                    noSessionStore(),
                    null,
                    wireToolsRegistry(),
                    null,
                    null);
            assertNull(loop.lastLlmPayload(), "nothing is captured before the first request");

            assertEquals("done", loop.run("do it", null, "s1", null, () -> false));

            final var payload = loop.lastLlmPayload();
            assertNotNull(payload, "a request was sent, the payload must be captured");
            assertEquals("s1", payload.sessionId());
            assertEquals("test-model", payload.model());
            assertEquals("auto", payload.toolChoice());
            assertEquals(1, payload.tools().size());
            assertTrue(
                    payload.toolsTokens() > 0, "the wire tool schemas cost context tokens: " + payload.toolsTokens());
            assertEquals("system", payload.messages().getFirst().role());
            assertEquals(
                    "do it",
                    payload.messages().stream()
                            .filter(m -> "user".equals(m.role()))
                            .map(m -> String.valueOf(m.content()))
                            .findFirst()
                            .orElseThrow(),
                    "the user prompt is part of the snapshot");
            assertEquals(
                    requests.getFirst(),
                    payload.messages(),
                    "the snapshot must be the exact list handed to the provider");
            assertTrue(
                    payload.messages().stream()
                            .anyMatch(m -> m.others() != null
                                    && Boolean.TRUE.equals(m.others().get("volatileTail"))),
                    "the volatile tail the store strips must still be visible: " + payload.messages());

            // the preview is rebuilt from the persisted transcript (empty here) plus a fresh tail
            final var preview = loop.previewLlmPayload("s1");
            assertEquals(payload.model(), preview.model());
            assertEquals(payload.tools(), preview.tools());
            assertEquals(payload.toolsTokens(), preview.toolsTokens());
            assertEquals("system", preview.messages().getFirst().role());
            assertTrue(
                    preview.messages().stream()
                            .anyMatch(m -> String.valueOf(m.content()).contains("<session-state>")),
                    "the preview must carry the volatile tail the store never persists: " + preview.messages());
        }

        @Test
        void planModeHidesEditToolsFromTheWireToolList() {
            final var payloads = new ArrayList<List<Tool>>();
            final var client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled) {
                    payloads.add(List.copyOf(request.tools() == null ? List.of() : request.tools()));
                    onDelta.accept(new End("stop", null));
                    return Message.assistant("done", null);
                }
            };
            final var config = baseline;
            final var planPermissions = new PermissionService(config, (config).resolveWorkingDir());
            planPermissions.setMode(AgentMode.PLAN);
            permissionServiceOverride = planPermissions;
            try {
                loop(
                                client,
                                null,
                                config,
                                noContextManager(),
                                noSessionStore(),
                                null,
                                readWriteWireToolsRegistry(),
                                null,
                                null)
                        .run("plan it", null, "s1", null, () -> false);
            } finally {
                permissionServiceOverride = null;
            }
            final var planTools =
                    payloads.getFirst().stream().map(t -> t.function().name()).toList();
            assertFalse(planTools.contains("write_file"), "plan mode must hide EDIT tools: " + planTools);
            assertTrue(planTools.contains("read"), "plan mode must keep READ tools: " + planTools);

            payloads.clear();
            final var buildPermissions = new PermissionService(config, (config).resolveWorkingDir());
            buildPermissions.setMode(AgentMode.BUILD);
            permissionServiceOverride = buildPermissions;
            try {
                loop(
                                client,
                                null,
                                config,
                                noContextManager(),
                                noSessionStore(),
                                null,
                                readWriteWireToolsRegistry(),
                                null,
                                null)
                        .run("build it", null, "s1", null, () -> false);
            } finally {
                permissionServiceOverride = null;
            }
            final var buildTools =
                    payloads.getFirst().stream().map(t -> t.function().name()).toList();
            assertTrue(buildTools.contains("write_file"), "build mode must expose EDIT tools: " + buildTools);
            assertTrue(buildTools.contains("read"), "build mode must keep READ tools: " + buildTools);
        }

        @Test
        void fullYoloExposesAndExecutesEditToolsInPlanMode() {
            final var replies = new ArrayDeque<>(List.of(
                    toolCallReply("w1", "write_file", "{\"path\":\"/a.txt\",\"content\":\"new\"}"),
                    new ScriptedReply("done", "stop", false)));
            final var requests = new ArrayList<List<Message>>();
            final var config = baseline;
            final var permissions = new PermissionService(config, config.resolveWorkingDir());
            permissions.setMode(AgentMode.PLAN);
            permissions.setPermissionMode(PermissionMode.FULL_YOLO);
            permissionServiceOverride = permissions;
            try {
                final var loop = loop(
                        chatClient(replies, requests),
                        null,
                        config,
                        noContextManager(),
                        noSessionStore(),
                        null,
                        readWriteWireToolsRegistry(),
                        directExecutions(),
                        null);
                assertTrue(loop.availableToolNames().contains("write_file"));
                try {
                    loop.run("implement it", null, "s1", null, () -> false);
                } finally {
                    loop.close();
                }
            } finally {
                permissionServiceOverride = null;
            }
            assertTrue(requests.stream()
                    .flatMap(List::stream)
                    .filter(message -> "tool".equals(message.role()))
                    .anyMatch(message -> String.valueOf(message.content()).contains("written")));
        }

        @Test
        void planModeAutoRejectsHiddenEditToolCallFeedback() {
            // script the model to call write_file even though plan mode hides it from the wire list - the
            // append-only build-mode history can push a model to do exactly that after plan -> build -> plan
            final var replies = new ArrayDeque<>(List.of(
                    toolCallReply("w1", "write_file", "{\"path\":\"/a.txt\",\"content\":\"new\"}"),
                    new ScriptedReply("here is the plan", "stop", false)));
            final var planRequests = new ArrayList<List<Message>>();
            final var config = baseline;
            final var planPermissions = new PermissionService(config, (config).resolveWorkingDir());
            planPermissions.setMode(AgentMode.PLAN);
            permissionServiceOverride = planPermissions;
            try {
                loop(
                                chatClient(replies, planRequests),
                                null,
                                config,
                                noContextManager(),
                                noSessionStore(),
                                null,
                                readWriteWireToolsRegistry(),
                                directExecutions(),
                                null)
                        .run("plan it", null, "s1", null, () -> false);
            } finally {
                permissionServiceOverride = null;
            }
            // the hidden write_file call must be auto-rejected with the plan-mode feedback diagnostic and
            // NEVER executed (no "written" tool output) nor routed to the approval gate
            final var toolResults = planRequests.stream()
                    .flatMap(List::stream)
                    .filter(m -> "tool".equals(m.role()))
                    .map(m -> String.valueOf(m.content()))
                    .toList();
            assertFalse(toolResults.isEmpty(), "a tool result for the rejected call is expected: " + planRequests);
            final var denied = toolResults.getLast();
            assertTrue(
                    denied.contains("write_file\" is not available in plan mode"),
                    "plan mode must auto-reject the hidden EDIT call with mode-aware feedback: " + denied);
            assertTrue(
                    denied.contains("only offered in build mode"),
                    "the feedback must steer the model to build mode: " + denied);
            assertFalse(
                    denied.contains("written"), "the write_file tool must NOT have executed in plan mode: " + denied);

            // the same call in build mode executes normally
            final var buildReplies = new ArrayDeque<>(List.of(
                    toolCallReply("w1", "write_file", "{\"path\":\"/a.txt\",\"content\":\"new\"}"),
                    new ScriptedReply("done", "stop", false)));
            final var buildRequests = new ArrayList<List<Message>>();
            final var buildPermissions = new PermissionService(config, (config).resolveWorkingDir());
            buildPermissions.setMode(AgentMode.BUILD);
            permissionServiceOverride = buildPermissions;
            try {
                loop(
                                chatClient(buildReplies, buildRequests),
                                null,
                                config,
                                noContextManager(),
                                noSessionStore(),
                                null,
                                readWriteWireToolsRegistry(),
                                directExecutions(),
                                null)
                        .run("build it", null, "s1", null, () -> false);
            } finally {
                permissionServiceOverride = null;
            }
            final var buildToolResults = buildRequests.stream()
                    .flatMap(List::stream)
                    .filter(m -> "tool".equals(m.role()))
                    .map(m -> String.valueOf(m.content()))
                    .toList();
            assertTrue(
                    buildToolResults.stream().anyMatch(o -> o.contains("written")),
                    "build mode must run write_file: " + buildToolResults);
        }
    }

    public static class DefaultSource extends ConfigurationTestModule {
        public DefaultSource() {
            super(sourceValues(DefaultScenario.storageHome, true, true, Map.of()));
        }
    }

    @Nested
    @FusionSupport(modules = RealIdleModeChangesEmitSessionTaggedNoticesOnlyForEffectiveChangesSource.class)
    class RealIdleModeChangesEmitSessionTaggedNoticesOnlyForEffectiveChangesScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void realIdleModeChangesEmitSessionTaggedNoticesOnlyForEffectiveChanges() {
            final var configuration = baseline;
            final var loop = loop(
                    null,
                    null,
                    configuration,
                    noContextManager(),
                    noSessionStore(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "idle-session");
            final var runtime = new SessionRuntime(
                    loop,
                    loop.permissionService,
                    null,
                    loop.usageTracker,
                    null,
                    null,
                    null,
                    null,
                    testWorkdir,
                    new DisabledSandbox(testWorkdir));
            final var service = new AgentSessionService(
                    null, null, null, null, null, null, null, new PlanTodoSeeder(null), configuration, null, null);
            eventObserver = event -> {
                if (event instanceof AgentEvent.Notice notice) {
                    assertEquals(
                            "mode: " + runtime.permissions().mode().name().toLowerCase(Locale.ROOT), notice.message());
                }
            };

            service.setMode("build", "idle-session", runtime);
            service.setMode("build", "idle-session", runtime);
            service.setMode("plan", "idle-session", runtime);
            service.setMode("plan", "idle-session", runtime);

            final var notices = events.stream()
                    .filter(AgentEvent.Notice.class::isInstance)
                    .map(AgentEvent.Notice.class::cast)
                    .toList();
            assertEquals(
                    List.of("mode: build", "mode: plan"),
                    notices.stream().map(AgentEvent.Notice::message).toList());
            assertTrue(notices.stream().allMatch(notice -> "idle-session".equals(notice.sessionId())));
            assertEquals(AgentMode.PLAN, runtime.permissions().mode());
        }
    }

    public static class RealIdleModeChangesEmitSessionTaggedNoticesOnlyForEffectiveChangesSource
            extends ConfigurationTestModule {
        public RealIdleModeChangesEmitSessionTaggedNoticesOnlyForEffectiveChangesSource() {
            super(sourceValues(
                    RealIdleModeChangesEmitSessionTaggedNoticesOnlyForEffectiveChangesScenario.storageHome,
                    false,
                    true,
                    Map.of("yca.mode", "plan")));
        }
    }

    @Nested
    @FusionSupport(modules = TruncationIsSurfacedWithoutAutoContinueWhenDisabledSource.class)
    class TruncationIsSurfacedWithoutAutoContinueWhenDisabledScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void truncationIsSurfacedWithoutAutoContinueWhenDisabled() {
            final var replies = new ArrayDeque<>(List.of(new ScriptedReply("partial answer", "length", false)));
            final var requests = new ArrayList<List<Message>>();
            final var result =
                    loop(chatClient(replies, requests), null, baseline).run("do it", null, "s1", null, () -> false);

            assertEquals("partial answer", result);
            assertEquals(1, requests.size(), "no extra LLM call when continueOnTruncation is off");
            final var notices =
                    events.stream().filter(AgentEvent.Notice.class::isInstance).toList();
            assertEquals(1, notices.size());
            assertEquals(AgentEvent.Notice.Level.ERROR, ((AgentEvent.Notice) notices.getFirst()).level());
            assertEquals("length", ((AgentEvent.Notice) notices.getFirst()).finishReason());
            assertTrue(((AgentEvent.Notice) notices.getFirst()).message().contains("continueOnTruncation"));
        }
    }

    public static class TruncationIsSurfacedWithoutAutoContinueWhenDisabledSource extends ConfigurationTestModule {
        public TruncationIsSurfacedWithoutAutoContinueWhenDisabledSource() {
            super(sourceValues(
                    TruncationIsSurfacedWithoutAutoContinueWhenDisabledScenario.storageHome, true, false, Map.of()));
        }
    }

    @Nested
    @FusionSupport(modules = TruncationCapComesFromConfigurationSource.class)
    class TruncationCapComesFromConfigurationScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void truncationCapComesFromConfiguration() {
            final var replies = new ArrayDeque<ScriptedReply>();
            for (var i = 0; i < 4; i++) {
                replies.add(new ScriptedReply("truncated " + i, "length", false));
            }
            final var requests = new ArrayList<List<Message>>();
            final var result =
                    loop(chatClient(replies, requests), null, baseline).run("do it", null, "s1", null, () -> false);

            assertEquals("truncated 1", result);
            assertEquals(2, requests.size(), "1 continuation then a final call");
            final var notices =
                    events.stream().filter(AgentEvent.Notice.class::isInstance).toList();
            assertEquals(2, notices.size(), "1 continuation warning then the give-up error");
            assertEquals(AgentEvent.Notice.Level.ERROR, ((AgentEvent.Notice) notices.getLast()).level());
            assertTrue(((AgentEvent.Notice) notices.getLast()).message().contains("Gave up after 1 continuation."));
            assertInstanceOf(AgentEvent.TurnDone.class, events.getLast());
        }
    }

    public static class TruncationCapComesFromConfigurationSource extends ConfigurationTestModule {
        public TruncationCapComesFromConfigurationSource() {
            super(sourceValues(
                    TruncationCapComesFromConfigurationScenario.storageHome,
                    true,
                    true,
                    Map.of("yca.llm.truncationContinuesMax", "1")));
        }
    }

    @Nested
    @FusionSupport(modules = ProviderOverrideKeepsItsModelUntilTheSelectionOverridesItSource.class)
    class ProviderOverrideKeepsItsModelUntilTheSelectionOverridesItScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /**
         * The provider branch of the same resolution: a per-tab provider carries its configured model
         * until an explicit model override replaces it.
         */
        @Test
        void providerOverrideKeepsItsModelUntilTheSelectionOverridesIt() {
            final var capturedModels = new ArrayList<String>();
            final var replies = new ArrayDeque<>(
                    List.of(new ScriptedReply("first", "stop", false), new ScriptedReply("second", "stop", false)));
            final var client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled) {
                    capturedModels.add(request.model());
                    final var reply = replies.poll();
                    if (reply.finishReason() != null) {
                        onDelta.accept(new End(reply.finishReason(), null));
                    }
                    return reply.toMessage();
                }
            };
            final var cfg = baseline;
            providerManagerOverride = new ProviderManager(cfg, mapper);
            final var loop = loop(client, null, cfg);

            loop.updateChatSettings(ChatSettings.EMPTY.withProviderId("other"));
            assertEquals("first", loop.run("do it", null, "s1", null, () -> false));
            loop.updateChatSettings(ChatSettings.EMPTY.withProviderId("other").withModel("switched"));
            assertEquals("second", loop.run("and now", null, "s1", null, () -> false));

            assertEquals(
                    List.of("other-model", "switched"),
                    capturedModels,
                    "the per-tab provider's model applies alone, then the model override wins");
        }
    }

    public static class ProviderOverrideKeepsItsModelUntilTheSelectionOverridesItSource
            extends ConfigurationTestModule {
        public ProviderOverrideKeepsItsModelUntilTheSelectionOverridesItSource() {
            super(sourceValues(
                    ProviderOverrideKeepsItsModelUntilTheSelectionOverridesItScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.provider.providers.length",
                            "2",
                            "yca.provider.providers.1.id",
                            "other",
                            "yca.provider.providers.1.baseUrl",
                            "http://localhost:1/v1",
                            "yca.provider.providers.1.apiKey",
                            "test-key",
                            "yca.provider.providers.1.model",
                            "other-model",
                            "yca.provider.providers.1.api",
                            "auto")));
        }
    }

    @Nested
    @FusionSupport(modules = ProviderSwitchPushesQuotaAndModelOnlySwitchDoesNotSource.class)
    class ProviderSwitchPushesQuotaAndModelOnlySwitchDoesNotScenario {
        @TempDir
        private static Path storageHome;

        private static HttpServer server;

        private static String serverUrl() {
            if (server == null) {
                try {
                    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return "http://localhost:" + server.getAddress().getPort();
        }

        @AfterAll
        static void stopServer() {
            if (server != null) {
                server.stop(0);
            }
        }

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /**
         * Switching to a codec (usage-enabled) provider must push an immediate {@code UsageUpdated} with
         * the new provider's quota so the TUI/web gauge shows it at switch time (then TTL-governed),
         * rather than keeping the old provider's cached quota until the next turn. A model-only switch
         * on the same provider must NOT emit a quota event (it would clobber the live context gauge).
         */
        @Test
        void providerSwitchPushesQuotaAndModelOnlySwitchDoesNot() throws Exception {
            final var fetches = new AtomicInteger();
            final var server = ProviderSwitchPushesQuotaAndModelOnlySwitchDoesNotScenario.server;
            final var serverUrl = "http://localhost:" + server.getAddress().getPort();
            server.createContext("/wham/usage", exchange -> {
                fetches.incrementAndGet();
                final var body = """
                    {"plan_type":"plus",
                     "rate_limit":{"primary_window":{"used_percent":100,"limit_window_seconds":18000,"reset_at":1700000000}},
                     "credits":{"has_credits":true,"balance":"0"}}
                    """;
                final var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            final var delegate = HttpClient.newBuilder().build();
            final var httpClient = new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate));
            try {
                final var cfg = baseline;
                providerManagerOverride = new ProviderManager(cfg, mapper);
                final var auth = new CodexAuthTemplateHelper(mapper, httpClient, cfg);
                auth.setOAuthConfig(new CodexOAuthConfig(serverUrl, "test", "openid", serverUrl + "/device", 1455));
                final var tokenFile = storageHome.resolve("providers/codex/codex.auth.json");
                Files.createDirectories(tokenFile.getParent());
                Files.write(
                        tokenFile,
                        mapper.toBytes(new CodexTokenStore("access", "refresh", "acc", Long.MAX_VALUE / 1000L, "id")));
                final var quotaStore =
                        new QuotaSnapshotStore(mapper, httpClient, auth, providerManagerOverride, Clock.systemUTC());

                final var client = new ChatClient() {
                    @Override
                    public Message stream(
                            final ChatCompletionRequest request,
                            final Consumer<PauseEvent> pauseListener,
                            final Consumer<LlmStreamEvent> onDelta,
                            final BooleanSupplier cancelled) {
                        onDelta.accept(new End("stop", null));
                        return new Message("assistant", "ok", null, List.of(), null, null);
                    }
                };
                final var loop = loop(
                        client,
                        null,
                        cfg,
                        noContextManager(),
                        noSessionStore(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        quotaStore);
                // establish this session's id (a live session) before testing the switch behavior;
                // the first turn attaches the process-default (non-codex) provider with no fetch
                loop.run("hello", null, "s1", null, () -> false);
                events.clear();

                // model-only switch on the process-default provider: no quota event, no clobbering
                loop.updateChatSettings(ChatSettings.EMPTY.withModel("other-model"));
                assertTrue(
                        events.stream().noneMatch(AgentEvent.UsageUpdated.class::isInstance),
                        "a model-only switch on the same provider must not emit a quota UsageUpdated: " + events);

                // switch to the codec provider: an immediate UsageUpdated must carry its (reached) quota
                events.clear();
                loop.updateChatSettings(ChatSettings.EMPTY.withProviderId("codex"));
                final var pushed = events.stream()
                        .filter(AgentEvent.UsageUpdated.class::isInstance)
                        .map(AgentEvent.UsageUpdated.class::cast)
                        .toList();
                assertEquals(1, pushed.size(), "provider switch must push exactly one quota UsageUpdated: " + events);
                final var quota = pushed.get(0).quota();
                assertNotNull(quota, "the pushed quota must be present");
                assertTrue(quota.enabled(), "the codex provider enables usage");
                assertTrue(quota.supported(), "the codex provider is supported");
                assertEquals(100.0, quota.usedPercent(), "a reached quota stays visible at switch time");
                assertEquals("primary", quota.windows().get(0).kind());
                assertEquals(1, fetches.get(), "attach on switch must force one fetch");
                // the push must preserve the live context gauge (cached after the first turn),
                // never resetting it to zero just because no message list is at hand
                assertTrue(
                        pushed.get(0).estimatedContextTokens() > 0,
                        "the pushed UsageUpdated must carry the cached context tokens: " + pushed.get(0));

                // a direct push (the /usage path) emits the cached quota and respects the TTL (no refetch)
                events.clear();
                final var priorFetches = fetches.get();
                loop.pushUsageQuota();
                final var direct = events.stream()
                        .filter(AgentEvent.UsageUpdated.class::isInstance)
                        .map(AgentEvent.UsageUpdated.class::cast)
                        .toList();
                assertEquals(1, direct.size(), "pushUsageQuota must emit one UsageUpdated: " + events);
                assertTrue(
                        direct.get(0).quota().supported()
                                && direct.get(0).quota().usedPercent() == 100.0,
                        "the pushed quota must carry the cached (reached) quota");
                assertEquals(
                        priorFetches, fetches.get(), "a direct push within the TTL must not refetch the usage backend");
            } finally {
                httpClient.close();
                delegate.shutdownNow();
                delegate.close();
            }
        }
    }

    public static class ProviderSwitchPushesQuotaAndModelOnlySwitchDoesNotSource extends ConfigurationTestModule {
        public ProviderSwitchPushesQuotaAndModelOnlySwitchDoesNotSource() {
            super(sourceValues(
                    ProviderSwitchPushesQuotaAndModelOnlySwitchDoesNotScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.provider.providers.length",
                            "2",
                            "yca.provider.providers.1.id",
                            "codex",
                            "yca.provider.providers.1.baseUrl",
                            ProviderSwitchPushesQuotaAndModelOnlySwitchDoesNotScenario.serverUrl() + "/codex",
                            "yca.provider.providers.1.apiKey",
                            "",
                            "yca.provider.providers.1.api",
                            "auto",
                            "yca.provider.providers.1.capabilities.codex.enableUsage",
                            "true")));
        }
    }

    @Nested
    @FusionSupport(modules = ProviderSwitchBeforeFirstTurnPushesQuotaSource.class)
    class ProviderSwitchBeforeFirstTurnPushesQuotaScenario {
        @TempDir
        private static Path storageHome;

        private static HttpServer server;

        private static String serverUrl() {
            if (server == null) {
                try {
                    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return "http://localhost:" + server.getAddress().getPort();
        }

        @AfterAll
        static void stopServer() {
            if (server != null) {
                server.stop(0);
            }
        }

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /**
         * A provider switch issued as the very first command of a session (before any turn ran, so the
         * loop has no live session id yet) must attach the quota under the loop's stable session id and
         * push the new provider's usage. It used to NPE inside the quota store and surface as a failed
         * {@code /provider}, leaving the TUI/web gauge empty until the first turn.
         */
        @Test
        void providerSwitchBeforeFirstTurnPushesQuota() throws Exception {
            final var fetches = new AtomicInteger();
            final var server = ProviderSwitchBeforeFirstTurnPushesQuotaScenario.server;
            final var serverUrl = "http://localhost:" + server.getAddress().getPort();
            server.createContext("/wham/usage", exchange -> {
                fetches.incrementAndGet();
                final var body = """
                    {"plan_type":"plus",
                     "rate_limit":{"primary_window":{"used_percent":42.0,"limit_window_seconds":18000,"reset_at":1700000000}},
                     "credits":{"has_credits":true,"balance":"10"}}
                    """;
                final var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            final var delegate = HttpClient.newBuilder().build();
            final var httpClient = new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate));
            try {
                final var cfg = baseline;
                providerManagerOverride = new ProviderManager(cfg, mapper);
                final var auth = new CodexAuthTemplateHelper(mapper, httpClient, cfg);
                auth.setOAuthConfig(new CodexOAuthConfig(serverUrl, "test", "openid", serverUrl + "/device", 1455));
                final var tokenFile = storageHome.resolve("providers/codex/codex.auth.json");
                Files.createDirectories(tokenFile.getParent());
                Files.write(
                        tokenFile,
                        mapper.toBytes(new CodexTokenStore("access", "refresh", "acc", Long.MAX_VALUE / 1000L, "id")));
                final var quotaStore =
                        new QuotaSnapshotStore(mapper, httpClient, auth, providerManagerOverride, Clock.systemUTC());

                final var client = new ChatClient() {
                    @Override
                    public Message stream(
                            final ChatCompletionRequest request,
                            final Consumer<PauseEvent> pauseListener,
                            final Consumer<LlmStreamEvent> onDelta,
                            final BooleanSupplier cancelled) {
                        onDelta.accept(new End("stop", null));
                        return new Message("assistant", "ok", null, List.of(), null, null);
                    }
                };
                // no run() before the switch on purpose: currentSessionId is null, so only the loop's
                // stable session id can key the quota (exactly what the TUI/web hit on a fresh tab)
                final var loop = loop(
                        client,
                        null,
                        cfg,
                        noContextManager(),
                        noSessionStore(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        quotaStore,
                        "s1");

                loop.updateChatSettings(ChatSettings.EMPTY.withProviderId("codex"));

                final var pushed = events.stream()
                        .filter(AgentEvent.UsageUpdated.class::isInstance)
                        .map(AgentEvent.UsageUpdated.class::cast)
                        .toList();
                assertEquals(1, pushed.size(), "provider switch must push exactly one quota UsageUpdated: " + events);
                final var quota = pushed.get(0).quota();
                assertNotNull(quota, "the pushed quota must be present");
                assertTrue(quota.enabled(), "the codex provider enables usage");
                assertTrue(quota.supported(), "the codex provider is supported");
                assertEquals(42.0, quota.usedPercent(), "the switch-time fetch carries the live usage");
                assertEquals(
                        "s1",
                        pushed.get(0).sessionId(),
                        "the pre-turn push must be tagged with the loop's stable session id so ShellRegistry routes"
                                + " it");
                assertEquals(1, fetches.get(), "attach on switch must force one fetch");
                assertTrue(
                        quotaStore.cachedSnapshot("s1").supported(),
                        "the quota is keyed on the loop's stable session id, not a null key");
            } finally {
                httpClient.close();
                delegate.shutdownNow();
                delegate.close();
            }
        }
    }

    public static class ProviderSwitchBeforeFirstTurnPushesQuotaSource extends ConfigurationTestModule {
        public ProviderSwitchBeforeFirstTurnPushesQuotaSource() {
            super(sourceValues(
                    ProviderSwitchBeforeFirstTurnPushesQuotaScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.provider.providers.length",
                            "2",
                            "yca.provider.providers.1.id",
                            "codex",
                            "yca.provider.providers.1.baseUrl",
                            ProviderSwitchBeforeFirstTurnPushesQuotaScenario.serverUrl() + "/codex",
                            "yca.provider.providers.1.apiKey",
                            "",
                            "yca.provider.providers.1.api",
                            "auto",
                            "yca.provider.providers.1.capabilities.codex.enableUsage",
                            "true")));
        }
    }

    @Nested
    @FusionSupport(modules = RefreshUsageBeforeFirstTurnPushesQuotaSource.class)
    class RefreshUsageBeforeFirstTurnPushesQuotaScenario {
        @TempDir
        private static Path storageHome;

        private static HttpServer server;

        private static String serverUrl() {
            if (server == null) {
                try {
                    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return "http://localhost:" + server.getAddress().getPort();
        }

        @AfterAll
        static void stopServer() {
            if (server != null) {
                server.stop(0);
            }
        }

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /**
         * A fresh session opened on a Codex provider must show its usage before any turn ran even when no
         * provider switch happened (the default provider is already the codex one): {@code refreshUsage}
         * attaches the current provider and pushes the quota without relying on a provider change, which is
         * what the tab-shell registration pushes at startup and an explicit {@code /provider <active-id>}
         * runs.
         */
        @Test
        void refreshUsageBeforeFirstTurnPushesQuota() throws Exception {
            final var fetches = new AtomicInteger();
            final var server = RefreshUsageBeforeFirstTurnPushesQuotaScenario.server;
            final var serverUrl = "http://localhost:" + server.getAddress().getPort();
            server.createContext("/wham/usage", exchange -> {
                fetches.incrementAndGet();
                final var body = """
                    {"plan_type":"plus",
                     "rate_limit":{"primary_window":{"used_percent":42.0,"limit_window_seconds":18000,"reset_at":1700000000}},
                     "credits":{"has_credits":true,"balance":"10"}}
                    """;
                final var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            final var delegate = HttpClient.newBuilder().build();
            final var httpClient = new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate));
            try {
                final var cfg = baseline;
                providerManagerOverride = new ProviderManager(cfg, mapper);
                final var auth = new CodexAuthTemplateHelper(mapper, httpClient, cfg);
                auth.setOAuthConfig(new CodexOAuthConfig(serverUrl, "test", "openid", serverUrl + "/device", 1455));
                final var tokenFile = storageHome.resolve("providers/codex/codex.auth.json");
                Files.createDirectories(tokenFile.getParent());
                Files.write(
                        tokenFile,
                        mapper.toBytes(new CodexTokenStore("access", "refresh", "acc", Long.MAX_VALUE / 1000L, "id")));
                final var quotaStore =
                        new QuotaSnapshotStore(mapper, httpClient, auth, providerManagerOverride, Clock.systemUTC());

                final var client = new ChatClient() {
                    @Override
                    public Message stream(
                            final ChatCompletionRequest request,
                            final Consumer<PauseEvent> pauseListener,
                            final Consumer<LlmStreamEvent> onDelta,
                            final BooleanSupplier cancelled) {
                        onDelta.accept(new End("stop", null));
                        return new Message("assistant", "ok", null, List.of(), null, null);
                    }
                };
                // no run() and no provider switch on purpose: currentSessionId is null and the provider
                // stays the default one (codex), exactly the "fresh tab already on codex" TUI/web scenario
                final var loop = loop(
                        client,
                        null,
                        cfg,
                        noContextManager(),
                        noSessionStore(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        quotaStore,
                        "s1");

                loop.refreshUsage();

                final var pushed = events.stream()
                        .filter(AgentEvent.UsageUpdated.class::isInstance)
                        .map(AgentEvent.UsageUpdated.class::cast)
                        .toList();
                assertEquals(1, pushed.size(), "a startup refresh must push exactly one quota UsageUpdated");
                assertEquals(
                        "s1",
                        pushed.get(0).sessionId(),
                        "the pre-turn push must be tagged with the loop's stable session id so ShellRegistry routes"
                                + " it");
                final var quota = pushed.get(0).quota();
                assertNotNull(quota, "the pushed quota must be present");
                assertTrue(quota.enabled(), "the codex provider enables usage");
                assertTrue(quota.supported(), "the codex provider is supported");
                assertEquals(42.0, quota.usedPercent(), "the refresh-time fetch carries the live usage");
                assertEquals(1, fetches.get(), "the startup attach must force exactly one fetch");
                assertTrue(
                        quotaStore.cachedSnapshot("s1").supported(),
                        "the quota is keyed on the loop's stable session id, not a null key");
            } finally {
                httpClient.close();
                delegate.shutdownNow();
                delegate.close();
            }
        }
    }

    public static class RefreshUsageBeforeFirstTurnPushesQuotaSource extends ConfigurationTestModule {
        public RefreshUsageBeforeFirstTurnPushesQuotaSource() {
            super(sourceValues(
                    RefreshUsageBeforeFirstTurnPushesQuotaScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.provider.providers.length",
                            "1",
                            "yca.provider.providers.0.id",
                            "codex",
                            "yca.provider.providers.0.baseUrl",
                            RefreshUsageBeforeFirstTurnPushesQuotaScenario.serverUrl() + "/codex",
                            "yca.provider.providers.0.apiKey",
                            "",
                            "yca.provider.providers.0.api",
                            "auto",
                            "yca.provider.providers.0.capabilities.codex.enableUsage",
                            "true")));
        }
    }

    @Nested
    @FusionSupport(modules = TruncationGiveUpFallsThroughToTodoAutoContinueSource.class)
    class TruncationGiveUpFallsThroughToTodoAutoContinueScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /**
         * A truncation give-up with pending todos falls through to the todo auto-continue instead of ending
         * the turn: hitting the output limit is an accident, not a reason to abandon unfinished work.
         */
        @Test
        void truncationGiveUpFallsThroughToTodoAutoContinue() {
            final var config = baseline;
            permissionServiceOverride = new PermissionService(config, (config).resolveWorkingDir());
            permissionServiceOverride.setMode(AgentMode.BUILD);
            try {
                final var todoStore =
                        mutableTodoStore(new TodoItem("done step", "completed"), new TodoItem("later step", "pending"));
                final var requests = new ArrayList<List<Message>>();
                final var replies = new ArrayDeque<>(List.of(
                        toolCallReply("t1", "noop"),
                        new ScriptedReply("truncated 0", "length", false),
                        new ScriptedReply("truncated 1", "length", false),
                        toolCallReply("t2", "close_todos"),
                        new ScriptedReply("all done", "stop", false)));
                final var result = loop(
                                chatClient(replies, requests),
                                todoStore,
                                config,
                                noContextManager(),
                                noSessionStore(),
                                null,
                                todoToolsRegistry(todoStore),
                                directExecutions(),
                                null)
                        .run("do it", null, "s1", null, () -> false);

                assertEquals("all done", result, "the truncation give-up must continue the pending todos");
                final var notices = events.stream()
                        .filter(AgentEvent.Notice.class::isInstance)
                        .map(AgentEvent.Notice.class::cast)
                        .toList();
                assertTrue(
                        notices.stream()
                                .anyMatch(n -> n.level() == AgentEvent.Notice.Level.INFO
                                        && n.message().contains("continuing automatically (1/25)")),
                        "the truncation give-up must trigger a todo continuation: " + notices);
                assertTrue(
                        notices.stream()
                                .noneMatch(n -> n.level() == AgentEvent.Notice.Level.ERROR
                                        && n.message().contains("Gave up after")),
                        "no truncation give-up error when pending todos can continue: " + notices);
            } finally {
                permissionServiceOverride = null;
            }
        }
    }

    public static class TruncationGiveUpFallsThroughToTodoAutoContinueSource extends ConfigurationTestModule {
        public TruncationGiveUpFallsThroughToTodoAutoContinueSource() {
            super(sourceValues(
                    TruncationGiveUpFallsThroughToTodoAutoContinueScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.mode",
                            "build",
                            "yca.permissions.mode",
                            "yolo",
                            "yca.todos.continueOnUnfinished",
                            "true",
                            "yca.llm.truncationContinuesMax",
                            "1")));
        }
    }

    @Nested
    @FusionSupport(modules = MidStreamFailureExhaustsRetriesAndEndsWithAnErrorSource.class)
    class MidStreamFailureExhaustsRetriesAndEndsWithAnErrorScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void midStreamFailureExhaustsRetriesAndEndsWithAnError() {
            final var replies = new ArrayDeque<>(List.of(
                    new ScriptedReply(failure("stream interrupted after 3 chars of partial content: reset")),
                    new ScriptedReply(failure("stream interrupted after 3 chars of partial content: reset"))));
            final var requests = new ArrayList<List<Message>>();
            final var result =
                    loop(chatClient(replies, requests), null, baseline).run("do it", null, "s1", null, () -> false);

            assertNull(result, "the turn must end gracefully when retries are exhausted");
            assertEquals(2, requests.size(), "initial attempt plus one retry");
            final var errors =
                    events.stream().filter(AgentEvent.Error.class::isInstance).toList();
            assertEquals(1, errors.size());
            assertTrue(((AgentEvent.Error) errors.getFirst()).message().contains("stream interrupted"));
        }
    }

    public static class MidStreamFailureExhaustsRetriesAndEndsWithAnErrorSource extends ConfigurationTestModule {
        public MidStreamFailureExhaustsRetriesAndEndsWithAnErrorSource() {
            super(sourceValues(
                    MidStreamFailureExhaustsRetriesAndEndsWithAnErrorScenario.storageHome,
                    true,
                    true,
                    Map.of("yca.llm.streamRetriesMaxAttempts", "1")));
        }
    }

    @Nested
    @FusionSupport(modules = RetryDiscardsUsageAndFinishReasonFromFailedAttemptWithoutSleepingSource.class)
    class RetryDiscardsUsageAndFinishReasonFromFailedAttemptWithoutSleepingScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void retryDiscardsUsageAndFinishReasonFromFailedAttemptWithoutSleeping() {
            throughputClock = new FakeClock();
            final var calls = new AtomicInteger();
            final ChatClient client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled)
                        throws IOException {
                    throughputClock.advance(50);
                    if (calls.getAndIncrement() == 0) {
                        onDelta.accept(new End(
                                "length",
                                new io.yupiik.llm.client.model.Usage(10L, 900L, 910L, null, null, 400L, 1_000L)));
                        throw new IOException("retry me");
                    }
                    return new Message("assistant", "done", null, null, null, null);
                }
            };
            loop(client, null, baseline).run("do it", null, "s1", null, () -> false);
            final var throughput = lastTurnMetrics().throughput();
            assertEquals(2, calls.get(), "stale length must not trigger another model round");
            assertEquals(TokenEstimator.estimate("done"), throughput.outputTokens());
            assertTrue(throughput.outputEstimated());
            assertEquals(100, throughput.activeMs(), "failed attempt time belongs to the logical request");
            assertEquals(0, throughput.reasoningTokens());
            assertEquals(0, throughput.reportedMs());
            assertEquals(0, throughput.reportedOutputTokens());
            assertEquals(1, throughput.completedRequests());
            assertEquals(0, throughput.reportedRequests());
        }
    }

    public static class RetryDiscardsUsageAndFinishReasonFromFailedAttemptWithoutSleepingSource
            extends ConfigurationTestModule {
        public RetryDiscardsUsageAndFinishReasonFromFailedAttemptWithoutSleepingSource() {
            super(sourceValues(
                    RetryDiscardsUsageAndFinishReasonFromFailedAttemptWithoutSleepingScenario.storageHome,
                    true,
                    true,
                    Map.of("yca.llm.streamRetriesMaxAttempts", "1", "yca.llm.retriesBaseDelayMs", "0")));
        }
    }

    @Nested
    @FusionSupport(modules = TurnContinuesOnTextEndKeepsGoingUntilTodosCloseSource.class)
    class TurnContinuesOnTextEndKeepsGoingUntilTodosCloseScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /**
         * Codex-style round gate: a model opted in via behavior.turnContinuesOnTextEnd=true keeps driving
         * a turn that ends text-only while the todo list is unfinished, instead of ending the turn and
         * waiting for the user to send 'continue'. This is independent of yca.todos.continueOnUnfinished
         * (which stays off here) and is bounded by the iteration window, not the todo-continuation budget.
         */
        @Test
        void turnContinuesOnTextEndKeepsGoingUntilTodosClose() {
            final var config = baseline;
            permissionServiceOverride = new PermissionService(config, (config).resolveWorkingDir());
            permissionServiceOverride.setMode(AgentMode.BUILD);
            providerManagerOverride = new ProviderManager(config, mapper);
            try {
                final var todoStore = mutableTodoStore(new TodoItem("running step", "in_progress"));
                final var requests = new ArrayList<List<Message>>();
                final var replies = new ArrayDeque<>(List.of(
                        toolCallReply("t1", "noop"),
                        new ScriptedReply("made progress", "stop", false),
                        toolCallReply("t2", "close_todos"),
                        new ScriptedReply("everything is done", "stop", false)));
                final var result = loop(
                                chatClient(replies, requests),
                                todoStore,
                                config,
                                noContextManager(),
                                noSessionStore(),
                                null,
                                todoToolsRegistry(todoStore),
                                directExecutions(),
                                null)
                        .run("do it", null, "s1", null, () -> false);

                assertEquals("everything is done", result);
                assertEquals(
                        4, requests.size(), "the round gate must re-ask once after the mid-turn stop: " + requests);
                assertTrue(
                        requests.get(2).stream()
                                .anyMatch(m -> "user".equals(m.role())
                                        && String.valueOf(m.content())
                                                .contains("todo list still has unfinished items (pending or"
                                                        + " in_progress)")),
                        "the continuation must reach the model as an explicit instruction: " + requests.get(2));
                final var notices = events.stream()
                        .filter(AgentEvent.Notice.class::isInstance)
                        .map(AgentEvent.Notice.class::cast)
                        .toList();
                assertEquals(1, notices.size(), "no warning banner: the round gate keeps the turn open: " + notices);
                assertEquals(AgentEvent.Notice.Level.INFO, notices.getFirst().level());
                assertTrue(
                        notices.getFirst().message().contains("continuing the round automatically"),
                        "the round-gate continuation must surface as an info notice: "
                                + notices.getFirst().message());
            } finally {
                providerManagerOverride = null;
                permissionServiceOverride = null;
            }
        }
    }

    public static class TurnContinuesOnTextEndKeepsGoingUntilTodosCloseSource extends ConfigurationTestModule {
        public TurnContinuesOnTextEndKeepsGoingUntilTodosCloseSource() {
            super(sourceValues(
                    TurnContinuesOnTextEndKeepsGoingUntilTodosCloseScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.mode",
                            "build",
                            "yca.permissions.mode",
                            "yolo",
                            "yca.provider.providers.length",
                            "1",
                            "yca.provider.providers.0.id",
                            "p0",
                            "yca.provider.providers.0.model",
                            "test-model",
                            "yca.provider.providers.0.models.length",
                            "1",
                            "yca.provider.providers.0.models.0.model",
                            "test-model",
                            "yca.provider.providers.0.models.0.behavior.turnContinuesOnTextEnd",
                            "true")));
        }
    }

    @Nested
    @FusionSupport(modules = TurnContinuesOnTextEndDefaultsToOffSource.class)
    class TurnContinuesOnTextEndDefaultsToOffScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /**
         * The round gate only applies to models that opt in explicitly via
         * behavior.turnContinuesOnTextEnd=true. The default (absent/false) keeps today's behavior: a
         * text-only end with unfinished todos surfaces the warning banner and pauses for 'continue'.
         */
        @Test
        void turnContinuesOnTextEndDefaultsToOff() {
            final var config = baseline;
            permissionServiceOverride = new PermissionService(config, (config).resolveWorkingDir());
            permissionServiceOverride.setMode(AgentMode.BUILD);
            providerManagerOverride = new ProviderManager(config, mapper);
            try {
                final var todoStore = mutableTodoStore(new TodoItem("running step", "in_progress"));
                final var replies = new ArrayDeque<>(
                        List.of(toolCallReply("t1", "noop"), new ScriptedReply("made progress", "stop", false)));
                final var result = loop(
                                chatClient(replies, new ArrayList<>()),
                                todoStore,
                                config,
                                noContextManager(),
                                noSessionStore(),
                                null,
                                todoToolsRegistry(todoStore),
                                directExecutions(),
                                null)
                        .run("do it", null, "s1", null, () -> false);

                assertEquals("made progress", result);
                final var notices = events.stream()
                        .filter(AgentEvent.Notice.class::isInstance)
                        .map(AgentEvent.Notice.class::cast)
                        .toList();
                assertEquals(1, notices.size());
                assertEquals(AgentEvent.Notice.Level.WARN, notices.getFirst().level());
                assertTrue(notices.getFirst().message().contains("send 'continue' to resume"));
            } finally {
                providerManagerOverride = null;
                permissionServiceOverride = null;
            }
        }
    }

    public static class TurnContinuesOnTextEndDefaultsToOffSource extends ConfigurationTestModule {
        public TurnContinuesOnTextEndDefaultsToOffSource() {
            super(sourceValues(
                    TurnContinuesOnTextEndDefaultsToOffScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.mode",
                            "build",
                            "yca.permissions.mode",
                            "yolo",
                            "yca.provider.providers.length",
                            "1",
                            "yca.provider.providers.0.id",
                            "p0",
                            "yca.provider.providers.0.model",
                            "test-model",
                            "yca.provider.providers.0.models.length",
                            "1",
                            "yca.provider.providers.0.models.0.model",
                            "test-model")));
        }
    }

    @Nested
    @FusionSupport(modules = UnfinishedTodosAutoContinueReAsksTheModelUntilTheListIsClosedSource.class)
    class UnfinishedTodosAutoContinueReAsksTheModelUntilTheListIsClosedScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /**
         * A model stopping mid-implementation with pending todos is exactly what
         * {@code yca.todos.continueOnUnfinished} exists for: instead of returning, the loop re-asks the
         * model to act, and the turn only ends once the list is closed (here by the tool that completes
         * the todos).
         */
        @Test
        void unfinishedTodosAutoContinueReAsksTheModelUntilTheListIsClosed() {
            final var config = baseline;
            permissionServiceOverride = new PermissionService(config, (config).resolveWorkingDir());
            permissionServiceOverride.setMode(AgentMode.BUILD);
            try {
                final var todoStore = mutableTodoStore(
                        new TodoItem("done step", "completed"),
                        new TodoItem("running step", "in_progress"),
                        new TodoItem("later step", "pending"));
                final var requests = new ArrayList<List<Message>>();
                final var replies = new ArrayDeque<>(List.of(
                        toolCallReply("t1", "noop"),
                        new ScriptedReply("made progress", "stop", false),
                        toolCallReply("t2", "close_todos"),
                        new ScriptedReply("everything is done", "stop", false)));
                final var result = loop(
                                chatClient(replies, requests),
                                todoStore,
                                config,
                                noContextManager(),
                                noSessionStore(),
                                null,
                                todoToolsRegistry(todoStore),
                                directExecutions(),
                                null)
                        .run("do it", null, "s1", null, () -> false);

                assertEquals("everything is done", result);
                assertEquals(4, requests.size(), "the loop re-asked the model once after the mid-turn stop");
                assertTrue(
                        requests.get(2).stream()
                                .anyMatch(m -> "user".equals(m.role())
                                        && String.valueOf(m.content())
                                                .contains("todo list still has unfinished items (pending or"
                                                        + " in_progress)")),
                        "the continuation must be sent as an explicit instruction, not a silent retry: "
                                + requests.get(2));
                final var notices = events.stream()
                        .filter(AgentEvent.Notice.class::isInstance)
                        .map(AgentEvent.Notice.class::cast)
                        .toList();
                assertEquals(1, notices.size(), "no warning notice: the turn ended on a closed list: " + notices);
                assertEquals(AgentEvent.Notice.Level.INFO, notices.getFirst().level());
                assertEquals(
                        "model ended its turn (finish_reason=stop) with 2/3 todos still unfinished; continuing"
                                + " automatically (1/25).",
                        notices.getFirst().message());
            } finally {
                permissionServiceOverride = null;
            }
        }
        /**
         * A single in_progress todo (no pending item at all) also holds the turn open: an item is
         * unfinished until it is completed or cancelled, so a text-only end with only in_progress
         * items must trigger the continuation too.
         */
        @Test
        void unfinishedTodosAutoContinueFiresWithOnlyInProgressItems() {
            final var config = baseline;
            permissionServiceOverride = new PermissionService(config, (config).resolveWorkingDir());
            permissionServiceOverride.setMode(AgentMode.BUILD);
            try {
                final var todoStore = mutableTodoStore(new TodoItem("running step", "in_progress"));
                final var requests = new ArrayList<List<Message>>();
                final var replies = new ArrayDeque<>(List.of(
                        toolCallReply("t1", "noop"),
                        new ScriptedReply("made progress", "stop", false),
                        toolCallReply("t2", "close_todos"),
                        new ScriptedReply("everything is done", "stop", false)));
                final var result = loop(
                                chatClient(replies, requests),
                                todoStore,
                                config,
                                noContextManager(),
                                noSessionStore(),
                                null,
                                todoToolsRegistry(todoStore),
                                directExecutions(),
                                null)
                        .run("do it", null, "s1", null, () -> false);

                assertEquals("everything is done", result);
                assertEquals(4, requests.size(), "the loop must re-ask once after the mid-turn stop: " + requests);
                assertTrue(
                        requests.get(2).stream()
                                .anyMatch(m -> "user".equals(m.role())
                                        && String.valueOf(m.content())
                                                .contains("todo list still has unfinished items (pending or"
                                                        + " in_progress)")),
                        "the continuation must tell the model about the unfinished in_progress item: "
                                + requests.get(2));
            } finally {
                permissionServiceOverride = null;
            }
        }
        /**
         * A prompt typed while the loop was running wins over the auto-continuation: the model just
         * answered that message, so its text-only end is not the "stopped mid-implementation" case.
         */
        @Test
        void unfinishedTodosAutoContinueIsSkippedAfterAQueuedPromptWasInjected() {
            final var config = baseline;
            permissionServiceOverride = new PermissionService(config, (config).resolveWorkingDir());
            permissionServiceOverride.setMode(AgentMode.BUILD);
            try {
                final var todoStore = mutableTodoStore(new TodoItem("later step", "pending"));
                final var requests = new ArrayList<List<Message>>();
                final var replies = new ArrayDeque<>(
                        List.of(toolCallReply("t1", "noop"), new ScriptedReply("here is the answer", "stop", false)));
                final var queued = new AtomicBoolean(true);
                final var result = loop(
                                chatClient(replies, requests),
                                todoStore,
                                config,
                                noContextManager(),
                                noSessionStore(),
                                null,
                                todoToolsRegistry(todoStore),
                                directExecutions(),
                                null)
                        .run(
                                "do it",
                                null,
                                "s1",
                                null,
                                () -> false,
                                () -> queued.compareAndSet(true, false) ? "and what does the test cover?" : null);

                assertEquals("here is the answer", result);
                assertEquals(2, requests.size(), "the injected prompt is absorbed by the same turn: " + requests);
                final var notices = events.stream()
                        .filter(AgentEvent.Notice.class::isInstance)
                        .map(AgentEvent.Notice.class::cast)
                        .toList();
                assertEquals(1, notices.size(), "only the warning notice, no continuation: " + notices);
                assertEquals(AgentEvent.Notice.Level.WARN, notices.getFirst().level());
            } finally {
                permissionServiceOverride = null;
            }
        }
        /**
         * The empty-completion retry runs before the todo auto-continue: a blank reply is a provider
         * hiccup, not a deliberate "stopped mid-implementation" stop the auto-continue is made for.
         */
        @Test
        void emptyCompletionRetryWinsOverTodoAutoContinue() {
            final var config = baseline;
            permissionServiceOverride = new PermissionService(config, (config).resolveWorkingDir());
            permissionServiceOverride.setMode(AgentMode.BUILD);
            try {
                final var todoStore =
                        mutableTodoStore(new TodoItem("done step", "completed"), new TodoItem("later step", "pending"));
                final var requests = new ArrayList<List<Message>>();
                final var replies = new ArrayDeque<>(List.of(
                        toolCallReply("t1", "noop"),
                        new ScriptedReply("", "stop", false),
                        toolCallReply("t2", "close_todos"),
                        new ScriptedReply("done", "stop", false)));
                final var result = loop(
                                chatClient(replies, requests),
                                todoStore,
                                config,
                                noContextManager(),
                                noSessionStore(),
                                null,
                                todoToolsRegistry(todoStore),
                                directExecutions(),
                                null)
                        .run("do it", null, "s1", null, () -> false);

                assertEquals("done", result);
                assertEquals(4, requests.size(), "the empty completion goes through the retry, not the auto-continue");
                final var notices = events.stream()
                        .filter(AgentEvent.Notice.class::isInstance)
                        .map(AgentEvent.Notice.class::cast)
                        .toList();
                assertEquals(1, notices.size(), "only the retry warning, no todo auto-continue notice: " + notices);
                assertEquals(AgentEvent.Notice.Level.WARN, notices.getFirst().level());
                assertTrue(
                        notices.getFirst().message().contains("empty completion"),
                        notices.getFirst().message());
                assertTrue(
                        notices.stream().noneMatch(n -> n.message().contains("continuing automatically")),
                        "the empty completion must not be reported as a deliberate todo stop: " + notices);
            } finally {
                permissionServiceOverride = null;
            }
        }
        /**
         * A window that burns its whole iteration budget on tool calls does not end the turn while todos
         * are pending: the todo auto-continue starts a fresh budget, so a large --todos-maxContinues is
         * actually reachable instead of being capped by yca.llm.maxIterations (10 in this test's config).
         */
        @Test
        void iterationBudgetExhaustionRestartsViaTodoAutoContinue() {
            final var config = baseline;
            permissionServiceOverride = new PermissionService(config, (config).resolveWorkingDir());
            permissionServiceOverride.setMode(AgentMode.BUILD);
            try {
                final var todoStore =
                        mutableTodoStore(new TodoItem("done step", "completed"), new TodoItem("later step", "pending"));
                final var requests = new ArrayList<List<Message>>();
                final var replies = new ArrayDeque<ScriptedReply>();
                // maxIterations is 10: exhaust the first window with tool-only rounds, then let the restart close
                for (var i = 0; i < 11; i++) {
                    replies.add(toolCallReply("t" + i, "noop"));
                }
                replies.add(toolCallReply("close", "close_todos"));
                replies.add(new ScriptedReply("all done", "stop", false));
                final var result = loop(
                                chatClient(replies, requests),
                                todoStore,
                                config,
                                noContextManager(),
                                noSessionStore(),
                                null,
                                todoToolsRegistry(todoStore),
                                directExecutions(),
                                null)
                        .run("do it", null, "s1", null, () -> false);

                assertEquals("all done", result, "iteration exhaustion must not end the turn with pending todos");
                assertEquals(13, requests.size(), "10 rounds, then a fresh window of 3 more calls");
                final var notices = events.stream()
                        .filter(AgentEvent.Notice.class::isInstance)
                        .map(AgentEvent.Notice.class::cast)
                        .toList();
                assertTrue(
                        notices.stream()
                                .anyMatch(n -> n.level() == AgentEvent.Notice.Level.INFO
                                        && n.message().contains("finish_reason=max_iterations")
                                        && n.message().contains("continuing automatically (1/25)")),
                        "iteration exhaustion must be reported as a todo continuation: " + notices);
                assertTrue(
                        notices.stream().noneMatch(n -> n.message().contains("Maximum iterations reached")),
                        "no hard iteration error while a todo continuation remains: " + notices);
            } finally {
                permissionServiceOverride = null;
            }
        }
    }

    public static class UnfinishedTodosAutoContinueReAsksTheModelUntilTheListIsClosedSource
            extends ConfigurationTestModule {
        public UnfinishedTodosAutoContinueReAsksTheModelUntilTheListIsClosedSource() {
            super(sourceValues(
                    UnfinishedTodosAutoContinueReAsksTheModelUntilTheListIsClosedScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.mode",
                            "build",
                            "yca.permissions.mode",
                            "yolo",
                            "yca.todos.continueOnUnfinished",
                            "true")));
        }
    }

    @Nested
    @FusionSupport(modules = UnfinishedTodosAutoContinueStopsAtTheConfiguredCapSource.class)
    class UnfinishedTodosAutoContinueStopsAtTheConfiguredCapScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /** The continuation budget is bounded per turn: once exhausted, the warning notice is emitted again. */
        @Test
        void unfinishedTodosAutoContinueStopsAtTheConfiguredCap() {
            final var config = baseline;
            permissionServiceOverride = new PermissionService(config, (config).resolveWorkingDir());
            permissionServiceOverride.setMode(AgentMode.BUILD);
            try {
                final var todoStore = mutableTodoStore(
                        new TodoItem("running step", "in_progress"), new TodoItem("later step", "pending"));
                final var requests = new ArrayList<List<Message>>();
                final var replies = new ArrayDeque<>(List.of(
                        toolCallReply("t1", "noop"),
                        new ScriptedReply("progress 1", "stop", false),
                        toolCallReply("t2", "noop"),
                        new ScriptedReply("progress 2", "stop", false),
                        toolCallReply("t3", "noop"),
                        new ScriptedReply("still not done", "stop", false)));
                final var result = loop(chatClient(replies, requests), todoStore, config)
                        .run("do it", null, "s1", null, () -> false);

                assertEquals("still not done", result, "the turn ends once the continuation budget is spent");
                assertEquals(6, requests.size(), "2 continuations, each re-asking the model once");
                final var notices = events.stream()
                        .filter(AgentEvent.Notice.class::isInstance)
                        .map(AgentEvent.Notice.class::cast)
                        .toList();
                assertEquals(3, notices.size(), notices.toString());
                assertEquals(
                        "model ended its turn (finish_reason=stop) with 2/2 todos still unfinished; continuing"
                                + " automatically (1/2).",
                        notices.get(0).message());
                assertEquals(
                        "model ended its turn (finish_reason=stop) with 2/2 todos still unfinished; continuing"
                                + " automatically (2/2).",
                        notices.get(1).message());
                assertEquals(AgentEvent.Notice.Level.WARN, notices.get(2).level());
                assertEquals(
                        "model ended its turn (finish_reason=stop) with 2/2 todos still unfinished - send 'continue'"
                                + " to resume, or review why it stopped",
                        notices.get(2).message());
                assertTrue(
                        requests.get(4).stream()
                                .anyMatch(m -> "user".equals(m.role())
                                        && String.valueOf(m.content()).contains("You already continued several times")),
                        "from the second continuation on the instruction must change strategy: " + requests.get(4));
            } finally {
                permissionServiceOverride = null;
            }
        }
    }

    public static class UnfinishedTodosAutoContinueStopsAtTheConfiguredCapSource extends ConfigurationTestModule {
        public UnfinishedTodosAutoContinueStopsAtTheConfiguredCapSource() {
            super(sourceValues(
                    UnfinishedTodosAutoContinueStopsAtTheConfiguredCapScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.mode",
                            "build",
                            "yca.permissions.mode",
                            "yolo",
                            "yca.todos.continueOnUnfinished",
                            "true",
                            "yca.todos.maxContinues",
                            "2")));
        }
    }

    @Nested
    @FusionSupport(modules = EmptyCompletionIsRetriedUpToTheBudgetThenSucceedsSource.class)
    class EmptyCompletionIsRetriedUpToTheBudgetThenSucceedsScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /** An empty completion (no content, no tool call) must be retried, not silently turned into a stop. */
        @Test
        void emptyCompletionIsRetriedUpToTheBudgetThenSucceeds() {
            final var replies = new ArrayDeque<>(
                    List.of(new ScriptedReply("", "stop", false), new ScriptedReply("actually done", "stop", false)));
            final var requests = new ArrayList<List<Message>>();
            final var result =
                    loop(chatClient(replies, requests), null, baseline).run("do it", null, "s1", null, () -> false);

            assertEquals("actually done", result);
            assertEquals(2, requests.size(), "the empty completion must re-ask the model once");
            final var notices =
                    events.stream().filter(AgentEvent.Notice.class::isInstance).toList();
            assertEquals(1, notices.size(), notices.toString());
            assertEquals(AgentEvent.Notice.Level.WARN, ((AgentEvent.Notice) notices.getFirst()).level());
            assertTrue(((AgentEvent.Notice) notices.getFirst()).message().contains("empty completion"));
            assertTrue(((AgentEvent.Notice) notices.getFirst()).message().contains("retrying (1/2)"));
            assertInstanceOf(AgentEvent.TurnDone.class, events.getLast());
        }
        /** After the configured budget the empty-completion retry gives up with an error notice, not a blank stop. */
        @Test
        void emptyCompletionGivesUpAfterTheBudgetWithAnErrorNotice() {
            final var replies = new ArrayDeque<>(List.of(
                    new ScriptedReply("", "stop", false),
                    new ScriptedReply("", "stop", false),
                    new ScriptedReply("", "stop", false)));
            final var requests = new ArrayList<List<Message>>();
            final var result =
                    loop(chatClient(replies, requests), null, baseline).run("do it", null, "s1", null, () -> false);

            assertEquals("", result, "the turn ends once the empty-completion budget is spent");
            assertEquals(3, requests.size(), "2 retries then a final call");
            final var notices =
                    events.stream().filter(AgentEvent.Notice.class::isInstance).toList();
            assertEquals(3, notices.size(), "2 retry warnings then the give-up error");
            assertEquals(AgentEvent.Notice.Level.WARN, ((AgentEvent.Notice) notices.get(0)).level());
            assertEquals(AgentEvent.Notice.Level.ERROR, ((AgentEvent.Notice) notices.getLast()).level());
            assertTrue(((AgentEvent.Notice) notices.getLast()).message().contains("gave up after 2 retries"));
            assertInstanceOf(AgentEvent.TurnDone.class, events.getLast());
        }
        /** P3: an empty completion that followed reasoning (the model burned its whole output budget
         * thinking) must NOT be retried with the identical context - that would re-derive the same
         * reasoning and hit the same wall. The retry nudge must tell the model to answer directly /
         * skip reasoning. The reasoning flag is observed through the stream (ReasoningDelta before End).
         */
        @Test
        void emptyCompletionAfterReasoningAsksForDirectAnswerInsteadOfIdenticalRetry() {
            // first reply: the stream emits reasoning then ends empty
            final var first = new ScriptedReply(null, "stop", false) {
                @Override
                Message toMessage() {
                    return Message.assistant("", null);
                }
            };
            // second reply: a normal answer (the loop asks again after the reasoning-aware nudge)
            final var replies = new ArrayDeque<ScriptedReply>();
            replies.add(first);
            replies.add(new ScriptedReply("done after reasoning nudge", "stop", false));
            final var requests = new ArrayList<List<Message>>();

            final var client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled)
                        throws IOException {
                    final var reply = replies.poll();
                    if (reply == null) {
                        throw new IllegalStateException("no scripted reply left");
                    }
                    requests.add(List.copyOf(request.messages()));
                    // the first request emits a ReasoningDelta: we assert the loop records it and
                    // adapts the retry nudge accordingly
                    if (requests.size() == 1) {
                        onDelta.accept(new LlmStreamEvent.ReasoningDelta("let me think about this"));
                    }
                    if (reply.finishReason() != null) {
                        onDelta.accept(new End(reply.finishReason(), null));
                    }
                    return reply.toMessage();
                }
            };

            final var result = loop(client, null, baseline).run("do it", null, "s1", null, () -> false);

            assertEquals("done after reasoning nudge", result);
            assertEquals(2, requests.size(), "one reasoning-aware retry then the answer");
            final var retainedAssistant = requests.get(1).stream()
                    .filter(message -> "assistant".equals(message.role()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(
                    "let me think about this",
                    retainedAssistant.others().get("thinking"),
                    "live follow-up requests must retain the same thinking as resumed history");
            // the retry request must carry the direct-answer nudge (not a blind identical retry)
            final var retryNudge = requests.get(1).getLast();
            assertEquals("user", retryNudge.role());
            final var nudgeText = String.valueOf(retryNudge.content());
            assertTrue(
                    nudgeText.contains("no answer at all") && nudgeText.contains("Answer directly"),
                    "the reasoning-aware empty retry must ask for a direct answer: " + nudgeText);
            final var notices = events.stream()
                    .filter(AgentEvent.Notice.class::isInstance)
                    .map(AgentEvent.Notice.class::cast)
                    .toList();
            assertTrue(
                    notices.stream()
                            .anyMatch(n -> n.level() == AgentEvent.Notice.Level.WARN
                                    && n.message().contains("empty completion")
                                    && n.message().contains("retrying")),
                    "the retry notice must still be emitted: " + notices);
        }
    }

    public static class EmptyCompletionIsRetriedUpToTheBudgetThenSucceedsSource extends ConfigurationTestModule {
        public EmptyCompletionIsRetriedUpToTheBudgetThenSucceedsSource() {
            super(sourceValues(
                    EmptyCompletionIsRetriedUpToTheBudgetThenSucceedsScenario.storageHome,
                    true,
                    true,
                    Map.of("yca.llm.emptyCompletionRetriesMax", "2")));
        }
    }

    @Nested
    @FusionSupport(modules = EmptyCompletionNoticesPreserveOptionalProviderErrorSource.class)
    class EmptyCompletionNoticesPreserveOptionalProviderErrorScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void emptyCompletionNoticesPreserveOptionalProviderError() {
            final var calls = new AtomicInteger();
            final var client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled) {
                    calls.incrementAndGet();
                    onDelta.accept(new End("error", null, "unsupported_value: reasoning effort is not supported"));
                    // A trailing usage-only event must not erase the cause.
                    onDelta.accept(new End(null, null));
                    return Message.assistant("", null);
                }
            };
            assertEquals("", loop(client, null, baseline).run("do it", null, "s1", null, () -> false));
            assertEquals(2, calls.get());
            final var notices = events.stream()
                    .filter(AgentEvent.Notice.class::isInstance)
                    .map(AgentEvent.Notice.class::cast)
                    .toList();
            assertEquals(2, notices.size());
            assertEquals(AgentEvent.Notice.Level.WARN, notices.getFirst().level());
            assertEquals(AgentEvent.Notice.Level.ERROR, notices.getLast().level());
            for (final var notice : notices) {
                assertTrue(notice.message().contains("finish_reason=error"));
                assertTrue(notice.message()
                        .contains("provider error: unsupported_value: reasoning effort is not supported"));
            }
        }

        @Test
        void emptyCompletionDoesNotReusePreviousProviderError() {
            final var calls = new AtomicInteger();
            final var client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled) {
                    onDelta.accept(new End("error", null, calls.incrementAndGet() == 1 ? "first failure" : " "));
                    return Message.assistant("", null);
                }
            };
            assertEquals("", loop(client, null, baseline).run("do it", null, "s1", null, () -> false));
            final var notices = events.stream()
                    .filter(AgentEvent.Notice.class::isInstance)
                    .map(AgentEvent.Notice.class::cast)
                    .toList();
            assertEquals(2, notices.size());
            assertTrue(notices.getFirst().message().contains("first failure"));
            assertFalse(notices.getLast().message().contains("provider error"));
            assertTrue(notices.getLast().message().contains("gave up after 1 retries"));
        }
        /**
         * The empty-completion retry budget is per stuck streak, not per turn: a tool round in between
         * resets it, so a long turn no longer dies after scattered empty replies (the production failure).
         */
        @Test
        void emptyCompletionRetryBudgetResetsAfterToolProgress() {
            final var replies = new ArrayDeque<>(List.of(
                    new ScriptedReply("", "stop", false),
                    toolCallReply("t1", "noop"),
                    new ScriptedReply("", "stop", false),
                    new ScriptedReply("actually done", "stop", false)));
            final var requests = new ArrayList<List<Message>>();
            final var result = loop(
                            chatClient(replies, requests),
                            null,
                            baseline,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            todoToolsRegistry(null),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertEquals("actually done", result, "each stuck streak gets its own retry budget");
            assertEquals(4, requests.size(), "empty (retry) -> tool -> empty (retry, budget reset) -> done");
            final var notices = events.stream()
                    .filter(AgentEvent.Notice.class::isInstance)
                    .map(AgentEvent.Notice.class::cast)
                    .toList();
            assertEquals(2, notices.size(), "one retry warning per stuck streak: " + notices);
            assertTrue(
                    notices.stream().allMatch(n -> n.level() == AgentEvent.Notice.Level.WARN),
                    "both notices are retries, no give-up error: " + notices);
        }
    }

    public static class EmptyCompletionNoticesPreserveOptionalProviderErrorSource extends ConfigurationTestModule {
        public EmptyCompletionNoticesPreserveOptionalProviderErrorSource() {
            super(sourceValues(
                    EmptyCompletionNoticesPreserveOptionalProviderErrorScenario.storageHome,
                    true,
                    true,
                    Map.of("yca.llm.emptyCompletionRetriesMax", "1")));
        }
    }

    @Nested
    @FusionSupport(modules = EmptyCompletionGiveUpFallsThroughToTodoAutoContinueSource.class)
    class EmptyCompletionGiveUpFallsThroughToTodoAutoContinueScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /**
         * An empty-completion give-up with pending todos falls through to the todo auto-continue: a blank
         * reply is a provider hiccup, so when work is unfinished the loop keeps going rather than stopping.
         */
        @Test
        void emptyCompletionGiveUpFallsThroughToTodoAutoContinue() {
            final var config = baseline;
            permissionServiceOverride = new PermissionService(config, (config).resolveWorkingDir());
            permissionServiceOverride.setMode(AgentMode.BUILD);
            try {
                final var todoStore =
                        mutableTodoStore(new TodoItem("done step", "completed"), new TodoItem("later step", "pending"));
                final var requests = new ArrayList<List<Message>>();
                final var replies = new ArrayDeque<>(List.of(
                        toolCallReply("t1", "noop"),
                        new ScriptedReply("", "stop", false),
                        new ScriptedReply("", "stop", false),
                        toolCallReply("t2", "close_todos"),
                        new ScriptedReply("all done", "stop", false)));
                final var result = loop(
                                chatClient(replies, requests),
                                todoStore,
                                config,
                                noContextManager(),
                                noSessionStore(),
                                null,
                                todoToolsRegistry(todoStore),
                                directExecutions(),
                                null)
                        .run("do it", null, "s1", null, () -> false);

                assertEquals("all done", result, "the empty-completion give-up must continue the pending todos");
                final var notices = events.stream()
                        .filter(AgentEvent.Notice.class::isInstance)
                        .map(AgentEvent.Notice.class::cast)
                        .toList();
                assertTrue(
                        notices.stream()
                                .anyMatch(n -> n.level() == AgentEvent.Notice.Level.INFO
                                        && n.message().contains("continuing automatically (1/25)")),
                        "the empty-completion give-up must trigger a todo continuation: " + notices);
                assertTrue(
                        notices.stream()
                                .noneMatch(n -> n.level() == AgentEvent.Notice.Level.ERROR
                                        && n.message().contains("gave up after")),
                        "no empty-completion give-up error when pending todos can continue: " + notices);
            } finally {
                permissionServiceOverride = null;
            }
        }
    }

    public static class EmptyCompletionGiveUpFallsThroughToTodoAutoContinueSource extends ConfigurationTestModule {
        public EmptyCompletionGiveUpFallsThroughToTodoAutoContinueSource() {
            super(sourceValues(
                    EmptyCompletionGiveUpFallsThroughToTodoAutoContinueScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.mode",
                            "build",
                            "yca.permissions.mode",
                            "yolo",
                            "yca.todos.continueOnUnfinished",
                            "true",
                            "yca.llm.emptyCompletionRetriesMax",
                            "1")));
        }
    }

    @Nested
    @FusionSupport(modules = EmptyCompletionRetryCanBeDisabledSource.class)
    class EmptyCompletionRetryCanBeDisabledScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /** Setting the knob to 0 disables the retry: the error notice is emitted on the first empty completion. */
        @Test
        void emptyCompletionRetryCanBeDisabled() {
            final var replies = new ArrayDeque<>(List.of(new ScriptedReply("", "stop", false)));
            final var requests = new ArrayList<List<Message>>();
            final var result =
                    loop(chatClient(replies, requests), null, baseline).run("do it", null, "s1", null, () -> false);

            assertEquals("", result);
            assertEquals(1, requests.size(), "no re-ask when the knob is 0");
            final var notices =
                    events.stream().filter(AgentEvent.Notice.class::isInstance).toList();
            assertEquals(1, notices.size());
            assertEquals(AgentEvent.Notice.Level.ERROR, ((AgentEvent.Notice) notices.getFirst()).level());
            assertTrue(((AgentEvent.Notice) notices.getFirst()).message().contains("emptyCompletionRetriesMax is 0"));
            assertInstanceOf(AgentEvent.TurnDone.class, events.getLast());
        }
    }

    public static class EmptyCompletionRetryCanBeDisabledSource extends ConfigurationTestModule {
        public EmptyCompletionRetryCanBeDisabledSource() {
            super(sourceValues(
                    EmptyCompletionRetryCanBeDisabledScenario.storageHome,
                    true,
                    true,
                    Map.of("yca.llm.emptyCompletionRetriesMax", "0")));
        }
    }

    @Nested
    @FusionSupport(modules = RepeatSuccessThresholdZeroDisablesTheGuardSource.class)
    class RepeatSuccessThresholdZeroDisablesTheGuardScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /**
         * P1: a global repeatSuccessThreshold of 0 disables the doom-loop breaker entirely: even many
         * identical-output calls are never refused (the no-progress guard remains the only backstop).
         */
        @Test
        void repeatSuccessThresholdZeroDisablesTheGuard() {
            final var replies = new ArrayDeque<ScriptedReply>();
            for (var i = 0; i < 6; i++) {
                replies.add(toolCallReply("t" + i, "echo"));
            }
            final var requests = new ArrayList<List<Message>>();
            final var client = chatClient(replies, requests);
            final var cfg = baseline;

            final var result = loop(
                            client,
                            null,
                            cfg,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            stateChangingRegistry(),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertNull(result, "threshold 0 has no doom-loop guard, so only the no-progress guard stops the turn");
            assertFalse(
                    hasDoomLoopNudge(requests),
                    "with the threshold at 0 the doom-loop breaker must never refuse/nudge: " + requests);
        }
    }

    public static class RepeatSuccessThresholdZeroDisablesTheGuardSource extends ConfigurationTestModule {
        public RepeatSuccessThresholdZeroDisablesTheGuardSource() {
            super(sourceValues(
                    RepeatSuccessThresholdZeroDisablesTheGuardScenario.storageHome,
                    true,
                    true,
                    Map.of("yca.tools.repeatSuccessThreshold", "0")));
        }
    }

    @Nested
    @FusionSupport(modules = PerModelBehaviorRepeatThresholdOverridesGlobalSource.class)
    class PerModelBehaviorRepeatThresholdOverridesGlobalScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /**
         * P1: the per-model behavior repeatSuccessThreshold override wins over the global default, so a
         * small/flash model configured with a lower threshold is caught sooner.
         */
        @Test
        void perModelBehaviorRepeatThresholdOverridesGlobal() {
            final var executions = new AtomicInteger();
            final var replies = new ArrayDeque<ScriptedReply>();
            for (var i = 0; i < 4; i++) {
                replies.add(toolCallReply("t" + i, "echo"));
            }
            replies.add(new ScriptedReply("all done", "stop", false));
            final var requests = new ArrayList<List<Message>>();
            final var client = chatClient(replies, requests);
            final var cfg = baseline;
            providerManagerOverride = new ProviderManager(cfg, mapper);

            loop(
                            client,
                            null,
                            cfg,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            registryOf(new EmbeddedTool() {
                                @Override
                                public String name() {
                                    return "echo";
                                }

                                @Override
                                public String description(final Path workdir) {
                                    return "echoes";
                                }

                                @Override
                                public ToolCategory category() {
                                    return ToolCategory.EDIT;
                                }

                                @Override
                                public Map<String, Object> schema() {
                                    return Map.of();
                                }

                                @Override
                                public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                                    executions.incrementAndGet();
                                    return ToolResult.ok("done");
                                }
                            }),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertEquals(
                    1,
                    executions.get(),
                    "behavior repeatSuccessThreshold=1 must refuse the second identical call (only the first runs)");
            assertTrue(
                    hasDoomLoopNudge(requests),
                    "the per-model lower threshold must actually trigger a refusal (strategy nudge): " + requests);
        }
    }

    public static class PerModelBehaviorRepeatThresholdOverridesGlobalSource extends ConfigurationTestModule {
        public PerModelBehaviorRepeatThresholdOverridesGlobalSource() {
            super(sourceValues(
                    PerModelBehaviorRepeatThresholdOverridesGlobalScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.provider.providers.0.models.length",
                            "1",
                            "yca.provider.providers.0.models.0.model",
                            "test-model",
                            "yca.provider.providers.0.models.0.behavior.repeatSuccessThreshold",
                            "1")));
        }
    }

    @Nested
    @FusionSupport(modules = BehaviorPlateauRoundsOverridesGlobalSource.class)
    class BehaviorPlateauRoundsOverridesGlobalScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /**
         * P2c: a per-model behavior.plateauRounds override wins over the global default and stops the
         * turn earlier (cascade: behavior > provider tuning > global).
         */
        @Test
        void behaviorPlateauRoundsOverridesGlobal() {
            final var replies = new ArrayDeque<ScriptedReply>();
            // One full read followed by two contained reads: plateauRounds=2 stops after the 3rd.
            final int[] offsets = {1, 2, 3};
            for (var i = 0; i < offsets.length; i++) {
                replies.add(toolCallReply(
                        "t" + i,
                        "read",
                        "{\"path\":\"/a.txt\",\"offset\":" + offsets[i] + ",\"limit\":" + (30 + i) + "}"));
            }
            final var requests = new ArrayList<List<Message>>();
            final var client = chatClient(replies, requests);

            final var cfg = baseline;
            providerManagerOverride = new ProviderManager(cfg, mapper);

            final var result = loop(
                            client,
                            null,
                            cfg,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            readFileRegistry(),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertNull(result, "behavior plateau override must stop the turn");
            final var errors = events.stream()
                    .filter(AgentEvent.Error.class::isInstance)
                    .map(AgentEvent.Error.class::cast)
                    .toList();
            assertFalse(errors.isEmpty());
            assertTrue(errors.getLast().message().contains("Exploration plateau"));
            assertTrue(
                    requests.size() <= 4,
                    "behavior plateauRounds=2 must stop after ~3 rounds, not the global 8: " + requests.size());
        }
    }

    public static class BehaviorPlateauRoundsOverridesGlobalSource extends ConfigurationTestModule {
        public BehaviorPlateauRoundsOverridesGlobalSource() {
            super(sourceValues(
                    BehaviorPlateauRoundsOverridesGlobalScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.provider.providers.0.models.length",
                            "1",
                            "yca.provider.providers.0.models.0.model",
                            "test-model",
                            "yca.provider.providers.0.models.0.behavior.plateauRounds",
                            "2")));
        }
    }

    @Nested
    @FusionSupport(modules = NegativePlateauRoundsIsNudgeOnlyNeverStopsSource.class)
    class NegativePlateauRoundsIsNudgeOnlyNeverStopsScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /**
         * P2e: a negative behavior.plateauRounds is nudge-only - the plateau guard nudges the model
         * (one-shot wrap-up directive) but never hard-stops the turn, so the model can keep reading and
         * eventually answer instead of losing the whole turn. This is the Codex-compatible mode for
         * capable models (they decide when the turn is truly over).
         */
        @Test
        void negativePlateauRoundsIsNudgeOnlyNeverStops() {
            final var replies = new ArrayDeque<ScriptedReply>();
            // One full read followed by eight contained reads (would hard-stop with a positive
            // plateau), then an answer.
            final int[] offsets = {1, 2, 3, 4, 5, 6, 7, 8, 9};
            for (var i = 0; i < offsets.length; i++) {
                replies.add(toolCallReply(
                        "t" + i,
                        "read",
                        "{\"path\":\"/a.txt\",\"offset\":" + offsets[i] + ",\"limit\":" + (30 + i) + "}"));
            }
            replies.add(new ScriptedReply("done answering", "stop", false));
            final var requests = new ArrayList<List<Message>>();
            final var client = chatClient(replies, requests);

            final var cfg = baseline;
            providerManagerOverride = new ProviderManager(cfg, mapper);

            final var result = loop(
                            client,
                            null,
                            cfg,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            readFileRegistry(),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertNotNull(result, "a nudge-only plateau must NOT stop the turn - the model answers");
            assertEquals("done answering", result);
            // the nudge fired once but the turn kept going past the redundant reads
            final var nudges = events.stream()
                    .filter(AgentEvent.Notice.class::isInstance)
                    .map(AgentEvent.Notice.class::cast)
                    .filter(n -> n.message().contains("exploration-plateau nudge"))
                    .toList();
            assertFalse(nudges.isEmpty(), "the plateau nudge must fire in nudge-only mode");
            final var errors =
                    events.stream().filter(AgentEvent.Error.class::isInstance).toList();
            assertTrue(errors.isEmpty(), "a nudge-only plateau must never hard-stop: " + errors);
            assertTrue(
                    requests.size() >= 10,
                    "the turn must keep running through the redundant reads, not stop: " + requests.size());
        }
    }

    public static class NegativePlateauRoundsIsNudgeOnlyNeverStopsSource extends ConfigurationTestModule {
        public NegativePlateauRoundsIsNudgeOnlyNeverStopsSource() {
            super(sourceValues(
                    NegativePlateauRoundsIsNudgeOnlyNeverStopsScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.provider.providers.0.models.length",
                            "1",
                            "yca.provider.providers.0.models.0.model",
                            "test-model",
                            "yca.provider.providers.0.models.0.behavior.plateauRounds",
                            "-1")));
        }
    }

    @Nested
    @FusionSupport(modules = PlateauResetByNewFileSource.class)
    class PlateauResetByNewFileScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /**
         * P2e: reading a NEW file resets the plateau streak - legitimate multi-file exploration stays
         * legal even when each file is re-read a few times.
         */
        @Test
        void plateauResetByNewFile() {
            final var replies = new ArrayDeque<ScriptedReply>();
            // 10 DISTINCT files read once each: every round is a fresh coverage key, so the plateau
            // streak never accumulates (re-reading an older file is what the plateau guard is for)
            for (var i = 0; i < 10; i++) {
                replies.add(toolCallReply("t" + i, "read", "{\"path\":\"/f" + i + ".txt\",\"offset\":0,\"limit\":30}"));
            }
            replies.add(new ScriptedReply("done", "stop", false));
            final var requests = new ArrayList<List<Message>>();
            final var client = chatClient(replies, requests);

            final var result = loop(
                            client,
                            null,
                            baseline,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            readFileRegistry(),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);
            assertNotNull(result, "distinct new files must keep the turn alive");
            final var errors = events.stream()
                    .filter(AgentEvent.Error.class::isInstance)
                    .map(AgentEvent.Error.class::cast)
                    .toList();
            assertTrue(errors.isEmpty(), "no plateau error expected: " + errors);
        }
    }

    public static class PlateauResetByNewFileSource extends ConfigurationTestModule {
        public PlateauResetByNewFileSource() {
            super(sourceValues(
                    PlateauResetByNewFileScenario.storageHome, true, true, Map.of("yca.llm.maxIterations", "50")));
        }
    }

    @Nested
    @FusionSupport(modules = ExplorationNudgeFiresAfterConsecutiveNovelReadRoundsSource.class)
    class ExplorationNudgeFiresAfterConsecutiveNovelReadRoundsScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /**
         * Exploration nudge: a spiral of NOVEL read-only rounds (fresh files every time - never redundant,
         * so the plateau/doom-loop guards stay silent) gets a one-shot wrap-up nudge injected as a user
         * message after llm.explorationNudgeRounds (default 20) consecutive informative rounds. The turn
         * keeps running: the nudge directs, it does not stop.
         */
        @Test
        void explorationNudgeFiresAfterConsecutiveNovelReadRounds() {
            final var replies = new ArrayDeque<ScriptedReply>();
            // 14 distinct files (novel coverage, informative outputs) then an answer
            for (var i = 0; i < 14; i++) {
                replies.add(toolCallReply("t" + i, "read", "{\"path\":\"/f" + i + ".txt\",\"offset\":0,\"limit\":30}"));
            }
            replies.add(new ScriptedReply("done", "stop", false));
            final var requests = new ArrayList<List<Message>>();
            final var client = chatClient(replies, requests);

            final var result = loop(
                            client,
                            null,
                            // set an explicit low threshold so the test exercises the nudge mechanism instead of
                            // depending on the global explorationNudgeRounds default (which it does not own)
                            baseline,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            readFileRegistry(),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertNotNull(result, "the nudge must not stop the turn - the model still answers");
            final var nudged = requests.stream()
                    .flatMap(List::stream)
                    .filter(m -> "user".equals(m.role()))
                    .anyMatch(m -> m.content().toString().contains("stop expanding the survey"));
            assertTrue(nudged, "the wrap-up nudge must reach the model as a user message");
            final var notices = events.stream()
                    .filter(AgentEvent.Notice.class::isInstance)
                    .map(AgentEvent.Notice.class::cast)
                    .toList();
            assertTrue(
                    notices.stream().anyMatch(n -> n.message().contains("exploration nudge")),
                    "an info notice must surface the nudge: " + notices);
            // one-shot: exactly one fire event per turn (the injected message then persists in history,
            // so it shows up in later request snapshots - that is expected, not a re-fire)
            final var fires = notices.stream()
                    .filter(n -> n.message().contains("exploration nudge"))
                    .count();
            assertEquals(1, fires, "the nudge must fire exactly once per turn");
        }
    }

    public static class ExplorationNudgeFiresAfterConsecutiveNovelReadRoundsSource extends ConfigurationTestModule {
        public ExplorationNudgeFiresAfterConsecutiveNovelReadRoundsSource() {
            super(sourceValues(
                    ExplorationNudgeFiresAfterConsecutiveNovelReadRoundsScenario.storageHome,
                    true,
                    true,
                    Map.of("yca.llm.maxIterations", "50", "yca.llm.explorationNudgeRounds", "14")));
        }
    }

    @Nested
    @FusionSupport(modules = ExplorationNudgeDisabledByConfigSource.class)
    class ExplorationNudgeDisabledByConfigScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /**
         * Exploration nudge disabled (llm.explorationNudgeRounds=0): a novel-read spiral gets no wrap-up
         * message - the guard is opt-out like the plateau guard.
         */
        @Test
        void explorationNudgeDisabledByConfig() {
            final var replies = new ArrayDeque<ScriptedReply>();
            for (var i = 0; i < 8; i++) {
                replies.add(toolCallReply("t" + i, "read", "{\"path\":\"/f" + i + ".txt\",\"offset\":0,\"limit\":30}"));
            }
            replies.add(new ScriptedReply("done", "stop", false));
            final var requests = new ArrayList<List<Message>>();
            final var client = chatClient(replies, requests);

            final var result = loop(
                            client,
                            null,
                            baseline,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            readFileRegistry(),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertNotNull(result);
            final var nudged = requests.stream()
                    .flatMap(List::stream)
                    .anyMatch(m ->
                            "user".equals(m.role()) && m.content().toString().contains("stop expanding the survey"));
            assertFalse(nudged, "explorationNudgeRounds=0 must suppress the nudge entirely");
        }
    }

    public static class ExplorationNudgeDisabledByConfigSource extends ConfigurationTestModule {
        public ExplorationNudgeDisabledByConfigSource() {
            super(sourceValues(
                    ExplorationNudgeDisabledByConfigScenario.storageHome,
                    true,
                    true,
                    Map.of("yca.llm.maxIterations", "50", "yca.llm.explorationNudgeRounds", "0")));
        }
    }

    @Nested
    @FusionSupport(modules = MutatingBashResetsExplorationSpiralSource.class)
    class MutatingBashResetsExplorationSpiralScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /**
         * Exploration nudge reset by mutating bash: a bash command that is not a read-only family (empty
         * coverage - a build like mvn) is REAL WORK, so it must reset the novel-spiral counter. Here reads
         * build to 3, then an mvn build resets, then only 3 more reads follow -> the streak never reaches
         * the threshold (4): no wrap-up nudge. Without the reset the streak would be 7 and the nudge would
         * fire - the build-mode false positive this change removes.
         */
        @Test
        void mutatingBashResetsExplorationSpiral() {
            final var replies = new ArrayDeque<ScriptedReply>();
            replies.addAll(List.of(
                    toolCallReply("r0", "read", "{\"path\":\"/f0.txt\",\"offset\":0,\"limit\":30}"),
                    toolCallReply("r1", "read", "{\"path\":\"/f1.txt\",\"offset\":0,\"limit\":30}"),
                    toolCallReply("r2", "read", "{\"path\":\"/f2.txt\",\"offset\":0,\"limit\":30}"),
                    toolCallReply("b0", "bash", "{\"command\":\"mvn compile\"}"),
                    toolCallReply("r3", "read", "{\"path\":\"/f3.txt\",\"offset\":0,\"limit\":30}"),
                    toolCallReply("r4", "read", "{\"path\":\"/f4.txt\",\"offset\":0,\"limit\":30}"),
                    toolCallReply("r5", "read", "{\"path\":\"/f5.txt\",\"offset\":0,\"limit\":30}"),
                    new ScriptedReply("done", "stop", false)));
            final var requests = new ArrayList<List<Message>>();
            final var client = chatClient(replies, requests);
            final var config = baseline;
            permissionServiceOverride = new PermissionService(config, (config).resolveWorkingDir());
            permissionServiceOverride.setMode(AgentMode.BUILD);
            try {
                final var result = loop(
                                client,
                                null,
                                config,
                                noContextManager(),
                                noSessionStore(),
                                null,
                                readBashRegistry(),
                                directExecutions(),
                                null)
                        .run("do it", null, "s1", null, () -> false);
                assertNotNull(result, "the turn must still complete");
                final var nudged = requests.stream()
                        .flatMap(List::stream)
                        .anyMatch(m -> "user".equals(m.role())
                                && m.content() != null
                                && m.content().toString().contains("stop expanding the survey"));
                assertFalse(
                        nudged,
                        "a mutating bash (mvn) round must reset the exploration spiral, so no nudge fires: "
                                + requests);
            } finally {
                permissionServiceOverride = null;
            }
        }
        /**
         * Exploration nudge NOT reset by read-only bash: a git log / cat / ls round carries coverage keys,
         * so it still counts as novel exploration and keeps the spiral running. Here the reads plus the
         * read-only bash cross the threshold (4): the wrap-up nudge still fires.
         */
        @Test
        void readOnlyBashKeepsExplorationSpiral() {
            final var replies = new ArrayDeque<ScriptedReply>();
            replies.addAll(List.of(
                    toolCallReply("r0", "read", "{\"path\":\"/f0.txt\",\"offset\":0,\"limit\":30}"),
                    toolCallReply("r1", "read", "{\"path\":\"/f1.txt\",\"offset\":0,\"limit\":30}"),
                    toolCallReply("r2", "read", "{\"path\":\"/f2.txt\",\"offset\":0,\"limit\":30}"),
                    toolCallReply("b0", "bash", "{\"command\":\"git log --oneline\"}"),
                    toolCallReply("r3", "read", "{\"path\":\"/f3.txt\",\"offset\":0,\"limit\":30}"),
                    toolCallReply("r4", "read", "{\"path\":\"/f4.txt\",\"offset\":0,\"limit\":30}"),
                    toolCallReply("r5", "read", "{\"path\":\"/f5.txt\",\"offset\":0,\"limit\":30}"),
                    new ScriptedReply("done", "stop", false)));
            final var requests = new ArrayList<List<Message>>();
            final var client = chatClient(replies, requests);
            final var config = baseline;
            permissionServiceOverride = new PermissionService(config, (config).resolveWorkingDir());
            permissionServiceOverride.setMode(AgentMode.BUILD);
            try {
                final var result = loop(
                                client,
                                null,
                                config,
                                noContextManager(),
                                noSessionStore(),
                                null,
                                readBashRegistry(),
                                directExecutions(),
                                null)
                        .run("do it", null, "s1", null, () -> false);
                assertNotNull(result, "the nudge must not stop the turn");
                final var nudged = requests.stream()
                        .flatMap(List::stream)
                        .anyMatch(m -> "user".equals(m.role())
                                && m.content() != null
                                && m.content().toString().contains("stop expanding the survey"));
                assertTrue(
                        nudged,
                        "a read-only bash (git log) round must keep counting toward the spiral, so the nudge fires: "
                                + requests);
            } finally {
                permissionServiceOverride = null;
            }
        }
    }

    public static class MutatingBashResetsExplorationSpiralSource extends ConfigurationTestModule {
        public MutatingBashResetsExplorationSpiralSource() {
            super(sourceValues(
                    MutatingBashResetsExplorationSpiralScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.mode",
                            "build",
                            "yca.permissions.mode",
                            "yolo",
                            "yca.llm.maxIterations",
                            "50",
                            "yca.llm.explorationNudgeRounds",
                            "4")));
        }
    }

    @Nested
    @FusionSupport(modules = PlateauNudge2FiresThenStopsAtLimitSource.class)
    class PlateauNudge2FiresThenStopsAtLimitScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /**
         * Plateau feedback: with nudge_2 (default) the model gets a one-shot strategy nudge when the
         * streak reaches limit-2 (injected as a user message, so it reaches the LLM), then still
         * hard-stops at the limit if it keeps re-reading.
         */
        @Test
        void plateauNudge2FiresThenStopsAtLimit() {
            final var replies = new ArrayDeque<ScriptedReply>();
            // behavior plateauRounds=3, nudge_2: nudge at streak 1 (3-2), hard stop at streak 3
            for (var i = 0; i < 4; i++) {
                replies.add(toolCallReply(
                        "t" + i,
                        "read",
                        "{\"path\":\"/a.txt\",\"offset\":" + (i + 1) + ",\"limit\":" + (30 + i) + "}"));
            }
            final var requests = new ArrayList<List<Message>>();
            final var client = chatClient(replies, requests);
            final var cfg = baseline;
            providerManagerOverride = new ProviderManager(cfg, mapper);

            final var result = loop(
                            client,
                            null,
                            cfg,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            readFileRegistry(),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);
            assertNull(result, "after the nudge the turn must still hard-stop at the limit");
            // the nudge was injected as a user message; it stays in the working list, so it appears in
            // every request after injection (assert at least once, not exactly once)
            final var nudgeOnWire = requests.stream()
                    .anyMatch(r -> r.stream()
                            .anyMatch(m -> "user".equals(m.role())
                                    && String.valueOf(m.content()).contains("Stop re-reading")));
            assertTrue(nudgeOnWire, "the nudge must be sent on the wire: " + requests);
            final var errors = events.stream()
                    .filter(AgentEvent.Error.class::isInstance)
                    .map(AgentEvent.Error.class::cast)
                    .toList();
            assertFalse(errors.isEmpty());
            assertTrue(
                    errors.getLast().message().contains("Exploration plateau"),
                    "the turn must end with the plateau stop: "
                            + errors.getLast().message());
        }
        /**
         * Plateau feedback: with nudge the model is allowed to recover - a new file read after the
         * nudge resets the streak, so the turn completes normally.
         */
        @Test
        void plateauNudgeLetsModelRecover() {
            final var replies = new ArrayDeque<ScriptedReply>();
            // plateauRounds=3, nudge_2: the contained second read nudges; then a NEW file (b) resets.
            replies.add(toolCallReply("r1", "read", "{\"path\":\"/a.txt\",\"offset\":0,\"limit\":30}"));
            replies.add(toolCallReply("r2", "read", "{\"path\":\"/a.txt\",\"offset\":2,\"limit\":10}"));
            replies.add(toolCallReply("r3", "read", "{\"path\":\"/b.txt\",\"offset\":0,\"limit\":50}"));
            replies.add(toolCallReply("r4", "read", "{\"path\":\"/c.txt\",\"offset\":0,\"limit\":60}"));
            replies.add(new ScriptedReply("done", "stop", false));
            final var requests = new ArrayList<List<Message>>();
            final var client = chatClient(replies, requests);
            final var cfg = baseline;
            providerManagerOverride = new ProviderManager(cfg, mapper);

            final var result = loop(
                            client,
                            null,
                            cfg,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            readFileRegistry(),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertNotNull(result, "a new file after the nudge resets the streak, the turn survives");
            final var errors = events.stream()
                    .filter(AgentEvent.Error.class::isInstance)
                    .map(AgentEvent.Error.class::cast)
                    .toList();
            assertTrue(errors.isEmpty(), "no plateau error when the model recovers: " + errors);
        }
    }

    public static class PlateauNudge2FiresThenStopsAtLimitSource extends ConfigurationTestModule {
        public PlateauNudge2FiresThenStopsAtLimitSource() {
            super(sourceValues(
                    PlateauNudge2FiresThenStopsAtLimitScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.provider.providers.0.models.length",
                            "1",
                            "yca.provider.providers.0.models.0.model",
                            "test-model",
                            "yca.provider.providers.0.models.0.behavior.plateauRounds",
                            "3",
                            "yca.provider.providers.0.models.0.behavior.plateauFeedback",
                            "nudge_2")));
        }
    }

    @Nested
    @FusionSupport(modules = PlateauNudge1FiresLaterThanNudge2Source.class)
    class PlateauNudge1FiresLaterThanNudge2Scenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /**
         * Plateau feedback: nudge_1 fires later than nudge_2 (one round before the limit) but still
         * before the hard stop.
         */
        @Test
        void plateauNudge1FiresLaterThanNudge2() {
            final var replies = new ArrayDeque<ScriptedReply>();
            // behavior plateauRounds=4, nudge_1: nudge at streak 3 (4-1), hard stop at streak 4
            for (var i = 0; i < 5; i++) {
                replies.add(toolCallReply(
                        "t" + i,
                        "read",
                        "{\"path\":\"/a.txt\",\"offset\":" + (i + 1) + ",\"limit\":" + (30 + i) + "}"));
            }
            final var requests = new ArrayList<List<Message>>();
            final var client = chatClient(replies, requests);
            final var cfg = baseline;
            providerManagerOverride = new ProviderManager(cfg, mapper);

            final var result = loop(
                            client,
                            null,
                            cfg,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            readFileRegistry(),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertNull(result, "the turn must still hard-stop at the limit");
            // the nudge request appears only on the second+ wire request (after round 3) - not at round 1-2
            final var nudgeFirstSeen = requests.stream()
                    .filter(r -> r.stream()
                            .anyMatch(m -> "user".equals(m.role())
                                    && String.valueOf(m.content()).contains("Stop re-reading")))
                    .findFirst()
                    .map(requests::indexOf)
                    .orElse(-1);
            assertTrue(
                    nudgeFirstSeen >= 3,
                    "nudge_1 must fire only near the limit (round 3 of 4), not early: " + requests);
        }
    }

    public static class PlateauNudge1FiresLaterThanNudge2Source extends ConfigurationTestModule {
        public PlateauNudge1FiresLaterThanNudge2Source() {
            super(sourceValues(
                    PlateauNudge1FiresLaterThanNudge2Scenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.provider.providers.0.models.length",
                            "1",
                            "yca.provider.providers.0.models.0.model",
                            "test-model",
                            "yca.provider.providers.0.models.0.behavior.plateauRounds",
                            "4",
                            "yca.provider.providers.0.models.0.behavior.plateauFeedback",
                            "nudge_1")));
        }
    }

    @Nested
    @FusionSupport(modules = PlateauFeedbackNoneKeepsHardStopWithoutNudgeSource.class)
    class PlateauFeedbackNoneKeepsHardStopWithoutNudgeScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /**
         * Plateau feedback: with plateauFeedback=none no nudge is ever injected - the turn hard-stops
         * at the limit exactly as before this feature.
         */
        @Test
        void plateauFeedbackNoneKeepsHardStopWithoutNudge() {
            final var replies = new ArrayDeque<ScriptedReply>();
            for (var i = 0; i < 4; i++) {
                replies.add(toolCallReply(
                        "t" + i,
                        "read",
                        "{\"path\":\"/a.txt\",\"offset\":" + (i + 1) + ",\"limit\":" + (30 + i) + "}"));
            }
            final var requests = new ArrayList<List<Message>>();
            final var client = chatClient(replies, requests);
            final var cfg = baseline;
            providerManagerOverride = new ProviderManager(cfg, mapper);

            final var result = loop(
                            client,
                            null,
                            cfg,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            readFileRegistry(),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertNull(result, "none must still hard-stop");
            final var nudgeCount = requests.stream()
                    .filter(r -> r.stream()
                            .anyMatch(m -> "user".equals(m.role())
                                    && String.valueOf(m.content()).contains("Stop re-reading")))
                    .count();
            assertEquals(0, nudgeCount, "no nudge may be sent with plateauFeedback=none");
            final var errors = events.stream()
                    .filter(AgentEvent.Error.class::isInstance)
                    .map(AgentEvent.Error.class::cast)
                    .toList();
            assertFalse(errors.isEmpty());
            assertTrue(errors.getLast().message().contains("Exploration plateau"));
        }
    }

    public static class PlateauFeedbackNoneKeepsHardStopWithoutNudgeSource extends ConfigurationTestModule {
        public PlateauFeedbackNoneKeepsHardStopWithoutNudgeSource() {
            super(sourceValues(
                    PlateauFeedbackNoneKeepsHardStopWithoutNudgeScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.provider.providers.0.models.length",
                            "1",
                            "yca.provider.providers.0.models.0.model",
                            "test-model",
                            "yca.provider.providers.0.models.0.behavior.plateauRounds",
                            "3",
                            "yca.provider.providers.0.models.0.behavior.plateauFeedback",
                            "none")));
        }
    }

    @Nested
    @FusionSupport(modules = BehaviorMaxIterationsOverridesGlobalWindowCapSource.class)
    class BehaviorMaxIterationsOverridesGlobalWindowCapScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /**
         * A-bis: behavior.maxIterations caps the turn window below the global default (cascade:
         * behavior > provider tuning > global llm.maxIterations). A model making tool rounds is
         * stopped once the capped window is exhausted.
         */
        @Test
        void behaviorMaxIterationsOverridesGlobalWindowCap() {
            final var replies = new ArrayDeque<ScriptedReply>();
            // 6 tool rounds: behavior.maxIterations=3 stops the window after 3 rounds
            for (var i = 0; i < 6; i++) {
                replies.add(toolCallReply(
                        "t" + i, "read", "{\"path\":\"/f" + i + ".txt\",\"offset\":0,\"limit\":" + (30 + i) + "}"));
            }
            final var requests = new ArrayList<List<Message>>();
            final var client = chatClient(replies, requests);

            final var cfg = baseline;
            providerManagerOverride = new ProviderManager(cfg, mapper);

            final var result = loop(
                            client,
                            null,
                            cfg,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            readFileRegistry(),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertNull(result, "a capped window must not return a normal answer");
            assertTrue(
                    requests.size() <= 4,
                    "behavior maxIterations=3 must cap near 3 rounds, not the global 10: " + requests.size());
            final var errors = events.stream()
                    .filter(AgentEvent.Error.class::isInstance)
                    .map(AgentEvent.Error.class::cast)
                    .toList();
            assertFalse(errors.isEmpty());
            assertTrue(
                    errors.getLast().message().contains("Maximum iterations reached"),
                    "the error must explain the capped window: "
                            + errors.getLast().message());
        }
    }

    public static class BehaviorMaxIterationsOverridesGlobalWindowCapSource extends ConfigurationTestModule {
        public BehaviorMaxIterationsOverridesGlobalWindowCapSource() {
            super(sourceValues(
                    BehaviorMaxIterationsOverridesGlobalWindowCapScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.provider.providers.0.models.length",
                            "1",
                            "yca.provider.providers.0.models.0.model",
                            "test-model",
                            "yca.provider.providers.0.models.0.behavior.maxIterations",
                            "3")));
        }
    }

    @Nested
    @FusionSupport(modules = NegativeMaxIterationsIsUnboundedRunsUntilTheModelFinishesSource.class)
    class NegativeMaxIterationsIsUnboundedRunsUntilTheModelFinishesScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /**
         * A-tris: a negative behavior.maxIterations makes the window UNBOUNDED - the turn runs until the
         * model genuinely finishes (the final text reply), instead of being cut off by an iteration cap.
         * The loop reads many distinct files and only ends when the model answers.
         */
        @Test
        void negativeMaxIterationsIsUnboundedRunsUntilTheModelFinishes() {
            final var replies = new ArrayDeque<ScriptedReply>();
            // 8 tool rounds then a text answer: a positive cap of e.g. 3 would stop the window early
            for (var i = 0; i < 8; i++) {
                replies.add(toolCallReply(
                        "t" + i, "read", "{\"path\":\"/f" + i + ".txt\",\"offset\":0,\"limit\":" + (30 + i) + "}"));
            }
            replies.add(new ScriptedReply("all done, here is the answer", "stop", false));
            final var requests = new ArrayList<List<Message>>();
            final var client = chatClient(replies, requests);

            final var cfg = baseline;
            providerManagerOverride = new ProviderManager(cfg, mapper);

            final var result = loop(
                            client,
                            null,
                            cfg,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            readFileRegistry(),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertEquals("all done, here is the answer", result, "the unbounded window must end on the model's answer");
            assertTrue(
                    requests.size() >= 9,
                    "the unbounded window must run through all reads and the answer: " + requests.size());
            final var errors =
                    events.stream().filter(AgentEvent.Error.class::isInstance).toList();
            assertTrue(errors.isEmpty(), "an unbounded window must never hit an iteration-cap error: " + errors);
        }
    }

    public static class NegativeMaxIterationsIsUnboundedRunsUntilTheModelFinishesSource
            extends ConfigurationTestModule {
        public NegativeMaxIterationsIsUnboundedRunsUntilTheModelFinishesSource() {
            super(sourceValues(
                    NegativeMaxIterationsIsUnboundedRunsUntilTheModelFinishesScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.provider.providers.0.models.length",
                            "1",
                            "yca.provider.providers.0.models.0.model",
                            "test-model",
                            "yca.provider.providers.0.models.0.behavior.maxIterations",
                            "-1")));
        }
    }

    @Nested
    @FusionSupport(modules = CapStopPersistsSystemNoticeAndResumeKeepsItOffTheWireSource.class)
    class CapStopPersistsSystemNoticeAndResumeKeepsItOffTheWireScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }
        /**
         * C: a window-cap stop persists a display-only "system" notice; on resume that entry must NOT
         * reach the LLM wire (filtered in doRun) while the transcript replay can still render it.
         */
        @Test
        void capStopPersistsSystemNoticeAndResumeKeepsItOffTheWire() {
            final var persisted = new ArrayList<SessionEntry>();
            final var store = new SessionStore() {
                @Override
                public void append(final String id, final SessionEntry entry) {
                    persisted.add(entry);
                }
            };
            // 5 tool rounds: global maxIterations=10 never reached here; use a tiny behavior cap = 2
            final var replies = new ArrayDeque<ScriptedReply>();
            for (var i = 0; i < 4; i++) {
                replies.add(toolCallReply(
                        "t" + i, "read", "{\"path\":\"/f" + i + ".txt\",\"offset\":0,\"limit\":" + (30 + i) + "}"));
            }
            final var requests = new ArrayList<List<Message>>();
            final var client = chatClient(replies, requests);
            final var cfg = baseline;
            providerManagerOverride = new ProviderManager(cfg, mapper);

            final var loopInstance = loop(
                    client, null, cfg, noContextManager(), store, null, readFileRegistry(), directExecutions(), null);
            final var result = loopInstance.run("do it", null, "s1", null, () -> false);
            assertNull(result, "capped window must stop");

            final var notices =
                    persisted.stream().filter(e -> "system".equals(e.role())).toList();
            assertEquals(1, notices.size(), "exactly one persisted stop notice: " + persisted);
            assertTrue(
                    String.valueOf(notices.get(0).content()).contains("Maximum iterations reached"),
                    "notice content must explain the cap: " + notices.get(0).content());

            // resume with the persisted notices in history: they must not be added to the working
            // messages (the wire), and no extra notice is persisted while no turn runs
            final var history = persisted.stream()
                    .map(e -> new Message(e.role(), e.content(), e.name(), e.toolCalls(), e.toolCallId(), e.others()))
                    .toList();
            final var resumed = loopInstance.resume(
                    "test-agent", "s1", List.of(Message.user("first"), history.get(history.size() - 1)), () -> false);
            // no scripted replies left -> the resume's LLM call is refused; assert the request carrying
            // the system notice never happened by checking requests only contain the first turn.
            // NOTE: index 0 of every request is the real system prompt; only mid-list "system" entries
            // are the display-only stop notices that must not reach the wire.
            assertTrue(
                    requests.stream().noneMatch(r -> r.stream().skip(1).anyMatch(m -> "system".equals(m.role()))),
                    "the system notice must never reach the wire: " + requests);
            // the resume emitted the info notice (before failing on missing reply); accept null result
            assertNull(resumed);
            assertTrue(
                    events.stream()
                            .anyMatch(e -> e instanceof AgentEvent.Notice n
                                    && String.valueOf(n.message()).contains("resuming after an iteration-cap stop")),
                    "resume after cap must emit the info notice: " + events);
        }
    }

    public static class CapStopPersistsSystemNoticeAndResumeKeepsItOffTheWireSource extends ConfigurationTestModule {
        public CapStopPersistsSystemNoticeAndResumeKeepsItOffTheWireSource() {
            super(sourceValues(
                    CapStopPersistsSystemNoticeAndResumeKeepsItOffTheWireScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.provider.providers.0.models.length",
                            "1",
                            "yca.provider.providers.0.models.0.model",
                            "test-model",
                            "yca.provider.providers.0.models.0.behavior.maxIterations",
                            "2")));
        }
    }

    @Nested
    @FusionSupport(modules = CancellationBeforeToolExecutionSkipsTheToolsSource.class)
    class CancellationBeforeToolExecutionSkipsTheToolsScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void cancellationBeforeToolExecutionSkipsTheTools() {
            final var executions = new AtomicInteger();
            final var calls = new AtomicInteger();
            final var client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled) {
                    if (calls.incrementAndGet() == 1) {
                        return Message.assistant(
                                null,
                                List.of(new ToolCall(
                                        "t1",
                                        "function",
                                        new ToolCall.FunctionCall("read", "{\"path\":\"/tmp/x\"}"),
                                        0)));
                    }
                    onDelta.accept(new End("stop", null));
                    return Message.assistant("all done", null);
                }
            };
            final var tool = new EmbeddedTool() {
                @Override
                public String name() {
                    return "read";
                }

                @Override
                public String description(final Path workdir) {
                    return "counted test tool";
                }

                @Override
                public ToolCategory category() {
                    return ToolCategory.READ;
                }

                @Override
                public Map<String, Object> schema() {
                    return Map.of();
                }

                @Override
                public boolean readonly() {
                    return true;
                }

                @Override
                public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                    executions.incrementAndGet();
                    return ToolResult.ok("ran");
                }
            };
            final var result = loop(
                            client,
                            null,
                            baseline,
                            noPruningManager(),
                            noopStore(),
                            null,
                            registryOf(tool),
                            directExecutions(),
                            EXECUTOR)
                    .run(
                            "do it",
                            null,
                            "s1",
                            null,
                            () -> calls.get() >= 1); // cancelled right after the tool-call reply lands
            assertEquals(0, executions.get(), "the tool must not run when cancelled before its execution: " + result);
        }

        @ParameterizedTest
        @CsvSource({
            "false,write_file,UPDATE",
            "true,write_file,UPDATE",
            "false,apply_patch,UPDATE",
            "true,apply_patch,UPDATE",
            "false,apply_patch,DELETE",
            "true,apply_patch,DELETE",
            "false,apply_patch,RENAME",
            "true,apply_patch,RENAME"
        })
        void staleReadsAreInvalidatedAfterAWriteToTheSamePath(
                final boolean partialFailure, final String writer, final String mutation) {
            final var requests = new ArrayList<List<Message>>();
            final var calls = new AtomicInteger();
            final var file = "stale-target.txt";
            final var client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled) {
                    requests.add(List.copyOf(request.messages()));
                    return switch (calls.incrementAndGet()) {
                        case 1 ->
                            Message.assistant(
                                    null,
                                    List.of(new ToolCall(
                                            "t1",
                                            "function",
                                            new ToolCall.FunctionCall("read", "{\"path\":\"" + file + "\"}"),
                                            0)));
                        case 2 ->
                            Message.assistant(
                                    null,
                                    List.of(new ToolCall(
                                            "t2",
                                            "function",
                                            new ToolCall.FunctionCall(
                                                    writer,
                                                    "{\"path\":\"" + file + "\",\"content\":\""
                                                            + (partialFailure ? "partial" : "new")
                                                            + "\",\"mutation\":\""
                                                            + mutation + "\"}"),
                                            0)));
                        default -> {
                            onDelta.accept(new End("stop", null));
                            yield Message.assistant("written", null);
                        }
                    };
                }
            };
            final var result = loop(
                            client,
                            null,
                            baseline,
                            noPruningManager(),
                            noopStore(),
                            null,
                            fileToolsRegistry(),
                            directExecutions(),
                            EXECUTOR)
                    .run("edit the file", null, "s1", null, () -> false);

            assertEquals("written", result);
            final var beforeWrite = requests.get(1).stream()
                    .filter(m -> "tool".equals(m.role()) && "t1".equals(m.toolCallId()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(
                    String.valueOf(beforeWrite.content()).startsWith("old content"),
                    "the read result must be intact before the write lands");
            // the write does NOT rewrite the prior read (append-only => prompt-prefix cache
            // survives); instead a compact system note is appended telling the model to re-read.
            final var afterWrite = requests.get(requests.size() - 1);
            final var readAfterWrite = afterWrite.stream()
                    .filter(m -> "tool".equals(m.role()) && "t1".equals(m.toolCallId()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(
                    String.valueOf(readAfterWrite.content()).startsWith("old content"),
                    "the prior read result must be kept intact (append-only), not rewritten: "
                            + readAfterWrite.content());
            // the stale note rides INSIDE the write tool's own result (new content that round, zero
            // prefix-cache cost); no extra message (user/system) is injected mid-conversation.
            assertTrue(
                    afterWrite.stream()
                            .filter(m -> "tool".equals(m.role()) && "t1".equals(m.toolCallId()))
                            .noneMatch(m -> String.valueOf(m.content()).contains("stale read cleared")),
                    "the read result itself must not gain the note");
            final var writeResult = afterWrite.stream()
                    .filter(m -> "tool".equals(m.role()) && "t2".equals(m.toolCallId()))
                    .filter(m -> String.valueOf(m.content()).contains("stale read cleared"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the write tool result must carry the stale-read note"));
            assertTrue(
                    String.valueOf(writeResult.content()).contains("stale-target.txt"),
                    "the note must name the stale path");
            assertTrue(
                    afterWrite.stream().skip(1).noneMatch(m -> "system".equals(m.role())),
                    "no system message may appear mid-conversation (breaks the provider prefix cache)");
        }

        @Test
        void staleReadsFromPreviousTurnsAreIndexedFromHistory() {
            final var requests = new ArrayList<List<Message>>();
            final var calls = new AtomicInteger();
            final var file = "stale-history.txt";
            final var client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled) {
                    requests.add(List.copyOf(request.messages()));
                    if (calls.incrementAndGet() == 1) {
                        return Message.assistant(
                                null,
                                List.of(new ToolCall(
                                        "t2",
                                        "function",
                                        new ToolCall.FunctionCall(
                                                "write_file", "{\"path\":\"" + file + "\",\"content\":\"new\"}"),
                                        0)));
                    }
                    onDelta.accept(new End("stop", null));
                    return Message.assistant("written", null);
                }
            };
            final var history = List.of(
                    Message.user("show me the file"),
                    Message.assistant(
                            null,
                            List.of(new ToolCall(
                                    "h1",
                                    "function",
                                    new ToolCall.FunctionCall("read", "{\"path\":\"" + file + "\"}"),
                                    0))),
                    Message.tool("h1", "old content"));
            final var result = loop(
                            client,
                            null,
                            baseline,
                            noPruningManager(),
                            noopStore(),
                            null,
                            fileToolsRegistry(),
                            directExecutions(),
                            EXECUTOR)
                    .run("rewrite it", null, "s1", history, () -> false);

            assertEquals("written", result);
            final var afterWriteRequest = requests.get(requests.size() - 1);
            final var previousRead = afterWriteRequest.stream()
                    .filter(m -> "tool".equals(m.role()) && "h1".equals(m.toolCallId()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(
                    String.valueOf(previousRead.content()).startsWith("old content"),
                    "reads from previous turns must be kept intact (append-only): " + previousRead.content());
            assertTrue(
                    afterWriteRequest.stream()
                            .filter(m -> "tool".equals(m.role()))
                            .anyMatch(m -> String.valueOf(m.content()).contains("stale read cleared")
                                    && String.valueOf(m.content()).contains(file)),
                    "reads from previous turns must be invalidated too: the stale note rides in the write tool result");
            assertTrue(
                    afterWriteRequest.stream().skip(1).noneMatch(m -> "system".equals(m.role())),
                    "no system message may appear mid-conversation (breaks the provider prefix cache)");
        }
        /**
         * Regression for the "this.cancelled is null" NPE: two turns running on the same
         * {@code AgentLoop} instance must not share a mutable cancellation field (the probe is now a
         * per-call parameter threaded down to the tools). The first turn blocks inside a tool while a
         * second {@code run} starts on the same instance; after the first unwinds, the second completes
         * its own tool without a NPE and with its own probe.
         */
        @Test
        void concurrentTurnsOnSameLoopKeepIndependentCancellation() throws Exception {
            final var config = baseline;
            final var started = new CountDownLatch(1);
            final var secondStarted = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            final var chatCalls = new AtomicInteger();
            final var blockingExecutions = new AtomicInteger();
            final var secondExecutions = new AtomicInteger();
            final var registry = new ToolRegistry(null) {
                @Override
                public List<EmbeddedTool> tools() {
                    return List.of(tool("blocking"), tool("simple"));
                }

                @Override
                public EmbeddedTool tool(final String name) {
                    return switch (name) {
                        case "blocking" ->
                            new EmbeddedTool() {
                                @Override
                                public String name() {
                                    return "blocking";
                                }

                                @Override
                                public String description(final Path workdir) {
                                    return "blocks";
                                }

                                @Override
                                public Map<String, Object> schema() {
                                    return Map.of();
                                }

                                @Override
                                public ToolCategory category() {
                                    return ToolCategory.AGENT;
                                }

                                @Override
                                public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                                    blockingExecutions.incrementAndGet();
                                    started.countDown();
                                    try {
                                        release.await();
                                    } catch (final InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return ToolResult.ok("blocking done");
                                }
                            };
                        case "simple" ->
                            new EmbeddedTool() {
                                @Override
                                public String name() {
                                    return "simple";
                                }

                                @Override
                                public String description(final Path workdir) {
                                    return "simple";
                                }

                                @Override
                                public Map<String, Object> schema() {
                                    return Map.of();
                                }

                                @Override
                                public ToolCategory category() {
                                    return ToolCategory.AGENT;
                                }

                                @Override
                                public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                                    secondExecutions.incrementAndGet();
                                    secondStarted.countDown();
                                    return ToolResult.ok("simple done");
                                }
                            };
                        default -> throw new IllegalArgumentException(name);
                    };
                }
            };
            // turn 1 asks for "blocking", turn 2 asks for "simple" (see scripted replies below)
            final var client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled) {
                    chatCalls.incrementAndGet();
                    final var userMessage = request.messages().get(1) == null
                            ? ""
                            : String.valueOf(request.messages().get(1).content());
                    final var firstTurn = userMessage.contains("first");
                    // the first LLM call of a turn carries the tool call; later calls (after the tool
                    // result lands) return a plain answer so the turn ends
                    final var toolCall = firstTurn ? "blocking" : "simple";
                    if (request.messages().stream()
                            .noneMatch(m -> "tool".equals(m.role())
                                    && String.valueOf(m.content()).contains(toolCall + " done"))) {
                        return new Message(
                                "assistant",
                                null,
                                null,
                                List.of(new ToolCall(
                                        firstTurn ? "1" : "2",
                                        "function",
                                        new ToolCall.FunctionCall(toolCall, "{}"),
                                        0)),
                                null,
                                null);
                    }
                    return new Message("assistant", "done", null, null, null, null);
                }
            };
            final var loop = loop(
                    client,
                    new TodoStore() {},
                    config,
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
                    new SessionStore() {
                        @Override
                        public void append(final String id, final SessionEntry entry) {}
                    },
                    null,
                    registry,
                    new ToolExecutionService(
                            config,
                            new Emitter() {
                                @Override
                                public <T> void emit(final T event) {}
                            },
                            List.of()) {
                        @Override
                        public ToolResult execute(
                                final EmbeddedTool tool, final Map<String, Object> args, final ToolContext context) {
                            return tool.execute(args, context);
                        }
                    },
                    EXECUTOR,
                    null);

            final var firstFailure = new AtomicReference<Throwable>();
            final var secondFailure = new AtomicReference<Throwable>();
            final var first = Thread.ofVirtual().unstarted(() -> {
                try {
                    loop.run("first", null, "s1", null, () -> false);
                } catch (final Throwable t) {
                    firstFailure.set(t);
                }
            });
            final var second = Thread.ofVirtual().unstarted(() -> {
                try {
                    loop.run("second", null, "s1", null, () -> false);
                } catch (final Throwable t) {
                    secondFailure.set(t);
                }
            });
            try {
                first.start();
                assertTrue(
                        started.await(5, TimeUnit.SECONDS),
                        "the first turn must reach its blocking tool; chatCalls=" + chatCalls);
                second.start();
                assertTrue(
                        secondStarted.await(5, TimeUnit.SECONDS),
                        "the second turn must execute its own tool while the first is blocked");
            } finally {
                release.countDown();
                first.join(10_000);
                second.join(10_000);
            }

            assertFalse(first.isAlive(), "the first turn must complete");
            assertFalse(second.isAlive(), "the second turn must complete");
            assertNull(firstFailure.get(), "the first turn must not fail");
            assertNull(secondFailure.get(), "the second turn must not fail");
            assertEquals(1, blockingExecutions.get(), "the blocking tool must run exactly once");
            assertEquals(1, secondExecutions.get(), "the second turn's tool must run exactly once");
        }
        /**
         * A per-tool cancel (TUI Esc on the running-tool overlay) flips only that call's probe: the
         * tool aborts, the result is fed back to the model as a failure and the turn keeps running
         * (no whole-turn interruption).
         */
        @Test
        void cancelToolAbortsOnlyTheRunningCallAndTheTurnContinues() throws Exception {
            final var config = baseline;
            final var started = new CountDownLatch(1);
            final var executions = new AtomicInteger();
            final var seenCancelled = new AtomicBoolean();
            final var registry = new ToolRegistry(null) {
                @Override
                public List<EmbeddedTool> tools() {
                    return List.of(tool("hanging"));
                }

                @Override
                public EmbeddedTool tool(final String name) {
                    return new EmbeddedTool() {
                        @Override
                        public String name() {
                            return "hanging";
                        }

                        @Override
                        public String description(final Path workdir) {
                            return "hangs until its per-call probe flips";
                        }

                        @Override
                        public Map<String, Object> schema() {
                            return Map.of();
                        }

                        @Override
                        public ToolCategory category() {
                            return ToolCategory.AGENT;
                        }

                        @Override
                        public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                            executions.incrementAndGet();
                            started.countDown();
                            while (!context.isCallCancelled() && !context.isCancelled()) {
                                LockSupport.parkNanos(1_000_000);
                            }
                            seenCancelled.set(context.isCallCancelled());
                            return ToolResult.error("cancelled by user");
                        }
                    };
                }
            };
            final var chatCalls = new AtomicInteger();
            final var client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled) {
                    chatCalls.incrementAndGet();
                    final var toolSeen = request.messages().stream()
                            .anyMatch(m -> "tool".equals(m.role())
                                    && String.valueOf(m.content()).contains("cancelled by user"));
                    if (!toolSeen) {
                        return new Message(
                                "assistant",
                                null,
                                null,
                                List.of(new ToolCall("1", "function", new ToolCall.FunctionCall("hanging", "{}"), 0)),
                                null,
                                null);
                    }
                    return new Message("assistant", "done after cancel", null, null, null, null);
                }
            };
            final var loop = loop(
                    client,
                    new TodoStore() {},
                    config,
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
                    new SessionStore() {
                        @Override
                        public void append(final String id, final SessionEntry entry) {}
                    },
                    null,
                    registry,
                    new ToolExecutionService(
                            config,
                            new Emitter() {
                                @Override
                                public <T> void emit(final T event) {}
                            },
                            List.of()) {
                        @Override
                        public ToolResult execute(
                                final EmbeddedTool tool, final Map<String, Object> args, final ToolContext context) {
                            return tool.execute(args, context);
                        }
                    },
                    EXECUTOR,
                    null);
            final var future = Thread.ofVirtual().start(() -> {
                try {
                    loop.run("run", null, "s1", null, () -> false);
                } catch (final Throwable t) {
                    throw new RuntimeException(t);
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS), "the tool must start");
            assertTrue(loop.isToolRunning(1), "the running tool must be registered under ordinal 1");
            loop.cancelTool(1); // the TUI Esc path
            future.join(10_000);
            assertFalse(future.isAlive(), "the turn must complete after the per-tool cancel");
            assertEquals(1, executions.get(), "the tool must run exactly once");
            assertTrue(seenCancelled.get(), "the abort must come from the per-call probe, not the turn");
            assertTrue(chatCalls.get() >= 2, "the turn must keep going (round 2 sees the cancelled result)");
            assertFalse(loop.isToolRunning(1), "the finished call must be unregistered");
        }
        /**
         * A turn always carries a real cancellation probe: a caller with no interrupt flag passes
         * {@link io.yupiik.ia.agent.core.runtime.Cancellations#NEVER}, and a {@code null} is a caller
         * bug which must fail at the boundary - named, and not swallowed into the mid-turn
         * {@code unexpected failure: java.lang.NullPointerException ... this.cancelled is null}.
         */
        @Test
        void nullCancellationProbeIsRejectedAtTheBoundary() {
            final var executions = new AtomicInteger();
            final var seenContext = new AtomicReference<ToolContext>();
            final var config = baseline;
            final var registry = new ToolRegistry(null) {
                @Override
                public List<EmbeddedTool> tools() {
                    return List.of(tool("ping"));
                }

                @Override
                public EmbeddedTool tool(final String name) {
                    if (!"ping".equals(name)) {
                        throw new IllegalArgumentException(name);
                    }
                    return new EmbeddedTool() {
                        @Override
                        public String name() {
                            return "ping";
                        }

                        @Override
                        public String description(final Path workdir) {
                            return "ping";
                        }

                        @Override
                        public Map<String, Object> schema() {
                            return Map.of();
                        }

                        @Override
                        public ToolCategory category() {
                            return ToolCategory.AGENT;
                        }

                        @Override
                        public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                            executions.incrementAndGet();
                            seenContext.set(context);
                            return ToolResult.ok("pong");
                        }
                    };
                }
            };
            final var client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled) {
                    // the probe handed to the client must be usable too, not null
                    assertNotNull(cancelled, "the LLM client must receive a usable cancellation probe");
                    final var toolAlreadyRan = request.messages().stream().anyMatch(m -> "tool".equals(m.role()));
                    return toolAlreadyRan
                            ? new Message("assistant", "all done", null, null, null, null)
                            : new Message(
                                    "assistant",
                                    null,
                                    null,
                                    List.of(new ToolCall("1", "function", new ToolCall.FunctionCall("ping", "{}"), 0)),
                                    null,
                                    null);
                }
            };
            final var loop = loop(
                    client,
                    new TodoStore() {},
                    config,
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
                    noopStore(),
                    null,
                    registry,
                    new ToolExecutionService(null, null, null, null, null, null) {
                        @Override
                        public ToolResult execute(
                                final EmbeddedTool tool, final Map<String, Object> args, final ToolContext context) {
                            return tool.execute(args, context);
                        }
                    },
                    EXECUTOR);

            final var answer = loop.run("hello", null, "s1", null, Cancellations.NEVER);

            assertEquals("all done", answer, "the turn must run to its end");
            assertEquals(1, executions.get(), "the tool must run exactly once");
            assertNotNull(seenContext.get(), "the tool must receive a context");
            assertFalse(seenContext.get().isCancelled(), "a never-cancelled probe reports false");
            final var errors =
                    events.stream().filter(AgentEvent.Error.class::isInstance).toList();
            assertTrue(errors.isEmpty(), "no error event, got: " + errors);

            // a null probe fails fast at the boundary, naming the offending argument: the turn never
            // starts, so nothing is rendered as an "unexpected failure"
            events.clear();
            final var thrown =
                    assertThrows(NullPointerException.class, () -> loop.run("hello", null, "s1", null, null));
            assertTrue(
                    thrown.getMessage().contains("cancelled"),
                    "the failure must name the probe, got: " + thrown.getMessage());
            assertTrue(
                    events.stream().noneMatch(AgentEvent.Error.class::isInstance),
                    "a rejected probe must not be reported as a turn failure: " + events);
        }
    }

    public static class CancellationBeforeToolExecutionSkipsTheToolsSource extends ConfigurationTestModule {
        public CancellationBeforeToolExecutionSkipsTheToolsSource() {
            super(sourceValues(
                    CancellationBeforeToolExecutionSkipsTheToolsScenario.storageHome,
                    true,
                    true,
                    Map.of("yca.mode", "build", "yca.permissions.mode", "yolo")));
        }
    }

    @Nested
    @FusionSupport(modules = ToolOutputsAreCappedAtIngestionBeforeEnteringTheConversationSource.class)
    class ToolOutputsAreCappedAtIngestionBeforeEnteringTheConversationScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void toolOutputsAreCappedAtIngestionBeforeEnteringTheConversation() {
            final var loop = loop(scriptedToolClient(new ArrayList<>(), List.of()), null, baseline);

            final var capped = loop.capIngestionOutput("x".repeat(50_000));
            assertTrue(capped.contains("[... output truncated ("), "the oversized output must carry the marker");
            assertTrue(capped.length() <= 2_200, "capped output must stay near the cap: " + capped.length());
            assertTrue(capped.startsWith("xxxx"), "the head must be preserved");
            assertTrue(capped.endsWith("xxxx"), "the tail must be preserved");

            assertEquals("short", loop.capIngestionOutput("short"), "short outputs must stay untouched");

            final var noCap = loop(
                    scriptedToolClient(new ArrayList<>(), List.of()),
                    null,
                    withPruning(baseline, withIngestionOutputCapChars(baseline.pruning(), 0)));
            assertEquals("y".repeat(5_000), noCap.capIngestionOutput("y".repeat(5_000)), "cap=0 must disable the cap");
        }
    }

    public static class ToolOutputsAreCappedAtIngestionBeforeEnteringTheConversationSource
            extends ConfigurationTestModule {
        public ToolOutputsAreCappedAtIngestionBeforeEnteringTheConversationSource() {
            super(sourceValues(
                    ToolOutputsAreCappedAtIngestionBeforeEnteringTheConversationScenario.storageHome,
                    true,
                    true,
                    Map.of("yca.pruning.ingestionOutputCapChars", "2000")));
        }
    }

    @Nested
    @FusionSupport(modules = FailedToolInputsArePurgedAfterTheConfiguredTurnsSource.class)
    class FailedToolInputsArePurgedAfterTheConfiguredTurnsScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void failedToolInputsArePurgedAfterTheConfiguredTurns() {
            final var loop = loop(scriptedToolClient(new ArrayList<>(), List.of()), null, baseline);
            final var messages = new ArrayList<Message>();
            messages.add(Message.assistant(
                    null,
                    List.of(new ToolCall(
                            "t1", "function", new ToolCall.FunctionCall("fail_tool", "{\"big\":\"x\"}"), 0))));
            messages.add(Message.tool("t1", "boom", true));

            assertEquals(1, loop.purgeFailedToolInputs(messages));
            assertEquals("{}", messages.get(0).toolCalls().get(0).function().arguments());
            assertEquals("boom", String.valueOf(messages.get(1).content()), "the error result must survive");
        }
    }

    public static class FailedToolInputsArePurgedAfterTheConfiguredTurnsSource extends ConfigurationTestModule {
        public FailedToolInputsArePurgedAfterTheConfiguredTurnsSource() {
            super(sourceValues(
                    FailedToolInputsArePurgedAfterTheConfiguredTurnsScenario.storageHome,
                    true,
                    true,
                    Map.of("yca.pruning.purgeFailedToolInputsTurns", "0")));
        }
    }

    @Nested
    @FusionSupport(modules = OldDeliveredSubagentBlocksAreTruncatedButRecentKeptSource.class)
    class OldDeliveredSubagentBlocksAreTruncatedButRecentKeptScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void oldDeliveredSubagentBlocksAreTruncatedButRecentKept() {
            final var loop = loop(scriptedToolClient(new ArrayList<>(), List.of()), null, baseline);
            final var messages = new ArrayList<Message>();
            for (int i = 0; i < 4; i++) {
                messages.add(
                        Message.user("<subagent sessionID=\"c" + i + "\" state=\"completed\" description=\"name\">\n"
                                + "long summary body " + i + "\n</subagent>"));
            }
            // 4 deliveries, last 2 kept verbatim -> 2 truncated
            assertEquals(2, loop.purgeDeliveredSubagents(messages));
            assertTrue(messages.get(0).content().toString().contains("[delivered sub-agent content pruned"));
            assertTrue(messages.get(1).content().toString().contains("[delivered sub-agent content pruned"));
            assertTrue(messages.get(2).content().toString().contains("long summary body 2"), "third (recent) kept");
            assertTrue(messages.get(3).content().toString().contains("long summary body 3"), "fourth (recent) kept");
        }
    }

    public static class OldDeliveredSubagentBlocksAreTruncatedButRecentKeptSource extends ConfigurationTestModule {
        public OldDeliveredSubagentBlocksAreTruncatedButRecentKeptSource() {
            super(sourceValues(
                    OldDeliveredSubagentBlocksAreTruncatedButRecentKeptScenario.storageHome,
                    true,
                    true,
                    Map.of("yca.pruning.purgeDeliveredSubagentsTurns", "0")));
        }
    }

    @Nested
    @FusionSupport(modules = WritesOverwrittenByLaterReadsAreSupersededWhenEnabledSource.class)
    class WritesOverwrittenByLaterReadsAreSupersededWhenEnabledScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void writesOverwrittenByLaterReadsAreSupersededWhenEnabled() {
            final var loopOn = loop(scriptedToolClient(new ArrayList<>(), List.of()), null, baseline);
            final var messages = new ArrayList<Message>();
            messages.add(Message.assistant(
                    null,
                    List.of(new ToolCall(
                            "w", "function", new ToolCall.FunctionCall("write_file", "{\"path\":\"a.txt\"}"), 0))));
            messages.add(Message.tool("w", "written"));
            messages.add(Message.assistant(
                    null,
                    List.of(new ToolCall(
                            "r", "function", new ToolCall.FunctionCall("read", "{\"path\":\"a.txt\"}"), 0))));
            messages.add(Message.tool("r", "content"));

            assertEquals(1, loopOn.supersedeOverwrittenWrites(messages), "a write overwritten by a read is superseded");
            assertTrue(
                    String.valueOf(messages.get(0).toolCalls().get(0).function().arguments())
                            .startsWith("[write superseded"),
                    "the write input must be superseded");
            assertEquals("content", String.valueOf(messages.get(3).content()), "the read output must survive");

            // a write with no later read stays untouched
            final var untouched = new ArrayList<Message>();
            untouched.add(Message.assistant(
                    null,
                    List.of(new ToolCall(
                            "w2", "function", new ToolCall.FunctionCall("write_file", "{\"path\":\"b.txt\"}"), 0))));
            untouched.add(Message.tool("w2", "written"));
            assertEquals(0, loopOn.supersedeOverwrittenWrites(untouched));
            assertEquals(
                    "{\"path\":\"b.txt\"}",
                    untouched.get(0).toolCalls().get(0).function().arguments());
        }
    }

    public static class WritesOverwrittenByLaterReadsAreSupersededWhenEnabledSource extends ConfigurationTestModule {
        public WritesOverwrittenByLaterReadsAreSupersededWhenEnabledSource() {
            super(sourceValues(
                    WritesOverwrittenByLaterReadsAreSupersededWhenEnabledScenario.storageHome,
                    true,
                    true,
                    Map.of("yca.pruning.supersedeOverwrittenWrites", "true")));
        }
    }

    @Nested
    @FusionSupport(modules = SoftNudgeIsSuppressedWhenThresholdIsZeroSource.class)
    class SoftNudgeIsSuppressedWhenThresholdIsZeroScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void softNudgeIsSuppressedWhenThresholdIsZero() {
            final var loop = loop(scriptedToolClient(new ArrayList<>(), List.of()), null, baseline);
            final var messages = new ArrayList<Message>();
            messages.add(Message.user("u"));
            // threshold 0 => no nudge regardless of iterations
            assertEquals("", loop.nudgeBlock(messages, 0));
        }
    }

    public static class SoftNudgeIsSuppressedWhenThresholdIsZeroSource extends ConfigurationTestModule {
        public SoftNudgeIsSuppressedWhenThresholdIsZeroSource() {
            super(sourceValues(
                    SoftNudgeIsSuppressedWhenThresholdIsZeroScenario.storageHome,
                    true,
                    true,
                    Map.of("yca.pruning.nudgeThresholdPercent", "0")));
        }
    }

    @Nested
    @FusionSupport(modules = SoftNudgeFiresInTheSoftBandWithUsageAndThresholdsSource.class)
    class SoftNudgeFiresInTheSoftBandWithUsageAndThresholdsScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void softNudgeFiresInTheSoftBandWithUsageAndThresholds() {
            // the loop() helper injects a stub UsageTracker (contextPercent=1, estimateContext=100):
            // threshold 1 fires the nudge, prune threshold 65 keeps it in the soft band
            final var loop = loop(scriptedToolClient(new ArrayList<>(), List.of()), null, baseline);
            final var messages = new ArrayList<Message>();
            messages.add(Message.user("u"));
            messages.add(Message.tool("t1", "some result"));
            final var nudge = loop.nudgeBlock(messages, 0);
            assertTrue(nudge.startsWith("\n<nudge>"), nudge);
            assertTrue(nudge.endsWith("</nudge>"), nudge);
            assertTrue(nudge.contains("~1"), "must carry the usage percent, got " + nudge);
            assertFalse(
                    nudge.contains("Do not start new work"), "usage below the prune threshold must keep the soft band");
        }
    }

    public static class SoftNudgeFiresInTheSoftBandWithUsageAndThresholdsSource extends ConfigurationTestModule {
        public SoftNudgeFiresInTheSoftBandWithUsageAndThresholdsSource() {
            super(sourceValues(
                    SoftNudgeFiresInTheSoftBandWithUsageAndThresholdsScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.pruning.nudgeThresholdPercent",
                            "1",
                            "yca.pruning.nudgeIterationThreshold",
                            "1",
                            "yca.pruning.nudgeMinTokens",
                            "0")));
        }
    }

    @Nested
    @FusionSupport(modules = UrgentNudgeFiresOnceUsageReachesThePruneThresholdSource.class)
    class UrgentNudgeFiresOnceUsageReachesThePruneThresholdScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void urgentNudgeFiresOnceUsageReachesThePruneThreshold() {
            final var loop = loop(scriptedToolClient(new ArrayList<>(), List.of()), null, baseline);
            final var messages = new ArrayList<Message>();
            messages.add(Message.user("u"));
            messages.add(Message.tool("t1", "some result"));
            final var nudge = loop.nudgeBlock(messages, 0);
            assertTrue(nudge.contains("Do not start new work"), nudge);
            assertTrue(nudge.contains("above the prune threshold"), nudge);
            assertTrue(
                    nudge.contains("run compress with action=prune or action=compact"),
                    "the %ACTIONS% placeholder must be resolved to the tool suggestion when enabled, got: " + nudge);
        }
    }

    public static class UrgentNudgeFiresOnceUsageReachesThePruneThresholdSource extends ConfigurationTestModule {
        public UrgentNudgeFiresOnceUsageReachesThePruneThresholdSource() {
            super(sourceValues(
                    UrgentNudgeFiresOnceUsageReachesThePruneThresholdScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.pruning.nudgeThresholdPercent",
                            "1",
                            "yca.pruning.nudgeIterationThreshold",
                            "1",
                            "yca.pruning.nudgeMinTokens",
                            "0",
                            "yca.pruning.thresholdPercent",
                            "1",
                            "yca.tools.manageContext.enabled",
                            "true")));
        }
    }

    @Nested
    @FusionSupport(modules = NudgeActionsFallbackWhenManageContextIsDisabledSource.class)
    class NudgeActionsFallbackWhenManageContextIsDisabledScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void nudgeActionsFallbackWhenManageContextIsDisabled() {
            final var loop = loop(scriptedToolClient(new ArrayList<>(), List.of()), null, baseline);
            final var messages = new ArrayList<Message>();
            messages.add(Message.user("u"));
            messages.add(Message.tool("t1", "some result"));
            final var nudge = loop.nudgeBlock(messages, 0);
            assertTrue(nudge.contains("interrupt with a short summary"), nudge);
            assertFalse(nudge.contains("manage_context"), "must not reference a disabled tool, got: " + nudge);
        }
    }

    public static class NudgeActionsFallbackWhenManageContextIsDisabledSource extends ConfigurationTestModule {
        public NudgeActionsFallbackWhenManageContextIsDisabledSource() {
            super(sourceValues(
                    NudgeActionsFallbackWhenManageContextIsDisabledScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.pruning.nudgeThresholdPercent",
                            "1",
                            "yca.pruning.nudgeIterationThreshold",
                            "1",
                            "yca.pruning.nudgeMinTokens",
                            "0",
                            "yca.pruning.thresholdPercent",
                            "1",
                            "yca.tools.manageContext.enabled",
                            "false")));
        }
    }

    @Nested
    @FusionSupport(modules = NudgeIsSuppressedUntilTheIterationThresholdIsReachedSource.class)
    class NudgeIsSuppressedUntilTheIterationThresholdIsReachedScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void nudgeIsSuppressedUntilTheIterationThresholdIsReached() {
            final var loop = loop(scriptedToolClient(new ArrayList<>(), List.of()), null, baseline);
            final var messages = new ArrayList<Message>();
            messages.add(Message.user("u"));
            messages.add(Message.tool("t1", "some result"));
            assertEquals("", loop.nudgeBlock(messages, 0));
        }
    }

    public static class NudgeIsSuppressedUntilTheIterationThresholdIsReachedSource extends ConfigurationTestModule {
        public NudgeIsSuppressedUntilTheIterationThresholdIsReachedSource() {
            super(sourceValues(
                    NudgeIsSuppressedUntilTheIterationThresholdIsReachedScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.pruning.nudgeThresholdPercent",
                            "1",
                            "yca.pruning.nudgeIterationThreshold",
                            "3",
                            "yca.pruning.nudgeMinTokens",
                            "0")));
        }
    }

    @Nested
    @FusionSupport(modules = NudgeIsSuppressedBelowTheMinTokenFloorSource.class)
    class NudgeIsSuppressedBelowTheMinTokenFloorScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void nudgeIsSuppressedBelowTheMinTokenFloor() {
            // stub estimateContext is 100 tokens: the default floor (64000, capped at 90% of the usable
            // window) is far above it, so even with a triggering percent the nudge stays silent
            final var floored = loop(scriptedToolClient(new ArrayList<>(), List.of()), null, baseline);
            final var messages = new ArrayList<Message>();
            messages.add(Message.user("u"));
            messages.add(Message.tool("t1", "some result"));
            assertEquals(
                    "",
                    floored.nudgeBlock(messages, 0),
                    "the default floor must suppress the nudge on a small context");

            // a low floor (below the 100-token estimate) lets the percentage alone gate it
            final var lowFloor = loop(
                    scriptedToolClient(new ArrayList<>(), List.of()),
                    null,
                    withPruning(baseline, withNudgeMinTokens(baseline.pruning(), 50)));
            assertTrue(lowFloor.nudgeBlock(messages, 0).startsWith("\n<nudge>"));
        }
    }

    public static class NudgeIsSuppressedBelowTheMinTokenFloorSource extends ConfigurationTestModule {
        public NudgeIsSuppressedBelowTheMinTokenFloorSource() {
            super(sourceValues(
                    NudgeIsSuppressedBelowTheMinTokenFloorScenario.storageHome,
                    true,
                    true,
                    Map.of("yca.pruning.nudgeThresholdPercent", "1", "yca.pruning.nudgeIterationThreshold", "1")));
        }
    }

    @Nested
    @FusionSupport(modules = ContextUsageLineIsAlwaysRenderedInTheSessionStateSource.class)
    class ContextUsageLineIsAlwaysRenderedInTheSessionStateScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void contextUsageLineIsAlwaysRenderedInTheSessionState() {
            final var loop = loop(scriptedToolClient(new ArrayList<>(), List.of()), null, baseline);
            final var messages = new ArrayList<Message>();
            messages.add(Message.user("u"));
            messages.add(Message.tool("t1", "x".repeat(2500)));
            final var line = loop.contextUsageLine(messages, 0);
            assertTrue(line.startsWith("Context usage: ~"), line);
            assertTrue(line.contains("(prune at 90%, compaction at 80%)"), line);
            // the line is unconditional: rendered even when the nudge stays silent (iteration guard)
            assertTrue(loop.nudgeBlock(messages, 15).isEmpty());
        }
    }

    public static class ContextUsageLineIsAlwaysRenderedInTheSessionStateSource extends ConfigurationTestModule {
        public ContextUsageLineIsAlwaysRenderedInTheSessionStateSource() {
            super(sourceValues(
                    ContextUsageLineIsAlwaysRenderedInTheSessionStateScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.provider.providers.0.contextWindow",
                            "2000",
                            "yca.pruning.thresholdPercent",
                            "90",
                            "yca.compaction.thresholdPercent",
                            "80")));
        }
    }

    @Nested
    @FusionSupport(modules = CompressionHardNudgeCadenceAdvancesOnRealRequestRoundsSource.class)
    class CompressionHardNudgeCadenceAdvancesOnRealRequestRoundsScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void compressionHardNudgeCadenceAdvancesOnRealRequestRounds() {
            final var requests = new ArrayList<List<Message>>();
            final var replies = new ArrayDeque<ScriptedReply>();
            for (var round = 0; round < 5; round++) {
                replies.add(toolCallReply("tick-" + round, "tick", "{\"round\":" + round + "}"));
            }
            replies.add(new ScriptedReply("all done", "stop", false));
            final var config = baseline;
            final var contextView = new ContextView(config.compression());
            final var tool = testTool("tick", new ArrayList<>());
            loop(
                            chatClient(replies, requests),
                            null,
                            config,
                            noContextManager(),
                            noSessionStore(),
                            contextView,
                            registryOf(tool),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            final var hardNudges = requests.stream()
                    .map(AgentLoopTest::hasHardCompressionNudge)
                    .toList();
            assertEquals(6, requests.size(), "five tool-call rounds plus the final response");
            assertEquals(
                    List.of(true, false, true, false, true, false),
                    hardNudges,
                    "the hard reminder should be emitted on request rounds 1, 3 and 5 only");
        }
    }

    public static class CompressionHardNudgeCadenceAdvancesOnRealRequestRoundsSource extends ConfigurationTestModule {
        public CompressionHardNudgeCadenceAdvancesOnRealRequestRoundsSource() {
            super(sourceValues(
                    CompressionHardNudgeCadenceAdvancesOnRealRequestRoundsScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.compression.enabled",
                            "true",
                            "yca.compression.contextLimit.min",
                            "0",
                            "yca.compression.contextLimit.max",
                            "50",
                            "yca.compression.nudgeFrequency",
                            "2",
                            "yca.llm.explorationNudgeRounds",
                            "0")));
        }
    }

    @Nested
    @FusionSupport(modules = SuccessfulCompressionSuppressesHardNudgesForCooldownThenResumesSource.class)
    class SuccessfulCompressionSuppressesHardNudgesForCooldownThenResumesScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void successfulCompressionSuppressesHardNudgesForCooldownThenResumes() {
            final var requests = new ArrayList<List<Message>>();
            final var replies = new ArrayDeque<>(List.of(
                    toolCallReply(
                            "compress-1",
                            "compress",
                            "{\"action\":\"compress\",\"topic\":\"closed work\",\"content\":["
                                    + "{\"startId\":\"@1@\",\"endId\":\"@2@\","
                                    + "\"summary\":\"A compact summary of the completed work.\"}] }"),
                    toolCallReply("tick-1", "tick", "{\"round\":1}"),
                    toolCallReply("tick-2", "tick", "{\"round\":2}"),
                    toolCallReply("tick-3", "tick", "{\"round\":3}"),
                    toolCallReply("tick-4", "tick", "{\"round\":4}"),
                    new ScriptedReply("all done", "stop", false)));
            final var config = baseline;
            final var contextView = new ContextView(config.compression());
            final var manager = new ManageContextTool(config, mapper, null);
            final var history =
                    List.of(Message.user("closed work with result"), Message.assistant("completed successfully", null));
            loop(
                            chatClient(replies, requests),
                            null,
                            config,
                            noContextManager(),
                            noSessionStore(),
                            contextView,
                            registryOf(manager, testTool("tick", new ArrayList<>())),
                            directExecutions(),
                            null)
                    .run("continue with the next task", null, "s1", history, () -> false);

            final var hardNudges = requests.stream()
                    .map(AgentLoopTest::hasHardCompressionNudge)
                    .toList();
            assertEquals(
                    1, contextView.compression().blocks().size(), "the dispatched compress call must create a block");
            assertEquals(
                    ContextCompression.Status.ACTIVE,
                    contextView.compression().blocks().getFirst().status());
            assertTrue(hardNudges.getFirst(), "the initial request is above the configured target");
            assertEquals(
                    List.of(false, false, false),
                    hardNudges.subList(1, 4),
                    "the three actual requests after compression are inside the cooldown");
            assertTrue(hardNudges.get(4), "the hard reminder resumes after the bounded cooldown");
        }
    }

    public static class SuccessfulCompressionSuppressesHardNudgesForCooldownThenResumesSource
            extends ConfigurationTestModule {
        public SuccessfulCompressionSuppressesHardNudgesForCooldownThenResumesSource() {
            super(sourceValues(
                    SuccessfulCompressionSuppressesHardNudgesForCooldownThenResumesScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.compression.enabled",
                            "true",
                            "yca.compression.contextLimit.min",
                            "0",
                            "yca.compression.contextLimit.max",
                            "50",
                            "yca.compression.nudgeFrequency",
                            "3",
                            "yca.compression.summaryBuffer",
                            "false",
                            "yca.llm.explorationNudgeRounds",
                            "0")));
        }
    }

    @Nested
    @FusionSupport(modules = AppendStateIfChangedKeepsTheContextUsageLineInTheTailSource.class)
    class AppendStateIfChangedKeepsTheContextUsageLineInTheTailScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void appendStateIfChangedKeepsTheContextUsageLineInTheTail() {
            final var requests = new ArrayList<List<Message>>();
            final var loop = loop(
                    scriptedToolClient(
                            requests,
                            List.of(new ToolCall("t1", "function", new ToolCall.FunctionCall("inline_read", "{}"), 0))),
                    null,
                    baseline);
            final var result = loop.run("do it", null, "s1", null, () -> false);
            assertEquals("all done", result);
            final var tail = requests.get(1).get(2);
            assertTrue(
                    String.valueOf(tail.content()).contains("Context usage: ~"),
                    "the volatile tail must always carry the usage line, got: " + tail.content());
        }
    }

    public static class AppendStateIfChangedKeepsTheContextUsageLineInTheTailSource extends ConfigurationTestModule {
        public AppendStateIfChangedKeepsTheContextUsageLineInTheTailSource() {
            super(sourceValues(
                    AppendStateIfChangedKeepsTheContextUsageLineInTheTailScenario.storageHome,
                    true,
                    true,
                    Map.of(
                            "yca.provider.providers.0.contextWindow",
                            "100000",
                            "yca.pruning.nudgeThresholdPercent",
                            "0")));
        }
    }

    @Nested
    @FusionSupport(modules = ParallelReadonlyCanBeDisabledByConfigurationSource.class)
    class ParallelReadonlyCanBeDisabledByConfigurationScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void parallelReadonlyCanBeDisabledByConfiguration() {
            final var barrier = new CyclicBarrier(2);
            final var requests = new ArrayList<List<Message>>();
            final var client = scriptedToolClient(
                    requests,
                    List.of(
                            new ToolCall("t1", "function", new ToolCall.FunctionCall("slow_read_a", "{}"), 0),
                            new ToolCall("t2", "function", new ToolCall.FunctionCall("slow_read_b", "{}"), 1)));
            final var config = baseline;
            loop(
                            client,
                            null,
                            config,
                            noPruningManager(),
                            noopStore(),
                            null,
                            barrierRegistry(barrier, "slow_read_a", "slow_read_b"),
                            directExecutions(),
                            EXECUTOR)
                    .run("do it", null, "s1", null, () -> false);

            final var tools = requests.get(1).stream()
                    .filter(m -> "tool".equals(m.role()))
                    .toList();
            assertEquals(2, tools.size());
            assertTrue(
                    String.valueOf(tools.get(0).content()).contains("not concurrent"),
                    "with the feature off the barrier is never satisfied: "
                            + tools.get(0).content());
        }
    }

    public static class ParallelReadonlyCanBeDisabledByConfigurationSource extends ConfigurationTestModule {
        public ParallelReadonlyCanBeDisabledByConfigurationSource() {
            super(sourceValues(
                    ParallelReadonlyCanBeDisabledByConfigurationScenario.storageHome,
                    true,
                    true,
                    Map.of("yca.tools.parallelReadonly", "false")));
        }
    }

    @Nested
    @FusionSupport(modules = ToolPairing400IsRepairedAndRetriedOnceSource.class)
    class ToolPairing400IsRepairedAndRetriedOnceScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void toolPairing400IsRepairedAndRetriedOnce() {
            // Azure/litellm strict validation rejects an assistant message whose tool_calls have no matching
            // tool response ("An assistant message with 'tool_calls' must be followed by tool messages
            // responding to each 'tool_call_id'. The following tool_call_ids did not have response messages: ...").
            // When that hard 400 hits a history with a dangling (interrupted/cancelled) tool call, the loop must
            // notice it, heal the history, and retry once instead of killing the turn. streamRetriesMaxAttempts=0
            // so the 400 propagates to the loop (ModelRoundProcessor would otherwise swallow it internally).
            final var requests = new ArrayList<List<Message>>();
            final var persisted = new ArrayList<SessionEntry>();
            final var replied = new ArrayDeque<ScriptedReply>(List.of(
                    new ScriptedReply(new IOException(
                            "LLM request failed (400): {\"detail\":\"litellm.BadRequestError: AzureException "
                                    + "BadRequestError - An assistant message with 'tool_calls' must be followed by "
                                    + "tool messages responding to each 'tool_call_id'. The following tool_call_ids "
                                    + "did not have response messages: call_1.\"}")),
                    new ScriptedReply("done", "stop", false)));
            final var client = chatClient(replied, requests);
            final var store = new SessionStore() {
                @Override
                public void append(final String id, final SessionEntry entry) {
                    persisted.add(entry);
                }
            };
            // a previous turn ended mid-tool, leaving assistant tool call call_1 with no result in history
            final var history = new ArrayList<Message>();
            history.add(new Message(
                    "assistant",
                    null,
                    null,
                    List.of(new ToolCall("call_1", "function", new ToolCall.FunctionCall("grep", "{}"), 0)),
                    null,
                    null));
            final var loop = loop(client, new TodoStore() {}, baseline, noPruningManager(), store, null);
            final var result = loop.run("do it", null, "s1", history, () -> false);

            assertEquals("done", result, "the repaired retry must complete the turn instead of failing it");
            assertEquals(2, requests.size(), "initial request (400) plus the repaired retry");
            // the retried request carries valid pairing: call_1 is answered by its lost-result marker
            assertAllToolCallsAreAnsweredByContiguousResults(requests.get(1));
            assertTrue(
                    requests.get(1).stream()
                            .anyMatch(m -> "tool".equals(m.role())
                                    && "call_1".equals(m.toolCallId())
                                    && m.content() != null
                                    && String.valueOf(m.content()).contains("tool result lost")),
                    "the retried request must answer call_1 with the lost-result marker: " + requests.get(1));
            // the repair is persisted as an error tool entry for the interrupted call
            assertTrue(
                    persisted.stream()
                            .anyMatch(e -> "tool".equals(e.role())
                                    && "call_1".equals(e.toolCallId())
                                    && e.others() != null
                                    && Boolean.TRUE.equals(e.others().get("is_error"))),
                    "the lost-result marker must be persisted as an is_error tool entry: " + persisted);
            // a repair/retry notice must be surfaced on the 400
            assertTrue(
                    events.stream()
                            .anyMatch(candidate -> candidate instanceof AgentEvent.Notice n
                                    && AgentEvent.Notice.Level.INFO == n.level()
                                    && n.message().contains("no matching tool result")),
                    "an INFO notice explaining the tool-pairing repair must be emitted: "
                            + events.stream()
                                    .filter(AgentEvent.Notice.class::isInstance)
                                    .toList());
        }

        @Test
        void nonContiguousResumeHistoryIsHealedAtLoopEntry() {
            // Regression for a RESUMED session: a persisted history can carry an assistant tool_calls whose
            // result landed in history but NOT contiguously - a queued user prompt / notice was interposed
            // between the call and its result. Azure/litellm strict validation rejects exactly this ("An
            // assistant message with 'tool_calls' must be followed by tool messages responding to each
            // 'tool_call_id'. The following tool_call_ids did not have response messages: ..."). The pre-fix
            // repair considered the id "answered" because a tool result existed ANYWHERE in history, so it left
            // the pairing broken, and the wire backstop (which trusts history before verificationStart) shipped
            // it to the provider for a 400. The fix repairs by the CONTIGUOUS rule via the shared
            // LlmMessage.unmatchedToolCallIds matcher, inserting a lost-result marker at loop entry, so the
            // first request is already valid and never 400s.
            final var requests = new ArrayList<List<Message>>();
            final var persisted = new ArrayList<SessionEntry>();
            final var replied = new ArrayDeque<ScriptedReply>(List.of(new ScriptedReply("done", "stop", false)));
            final var client = chatClient(replied, requests);
            final var store = new SessionStore() {
                @Override
                public void append(final String id, final SessionEntry entry) {
                    persisted.add(entry);
                }
            };
            // a persisted (resumed) history: call_1's result landed, but a queued/user prompt sits between the
            // assistant tool call and its result, breaking contiguity on the wire.
            final var history = new ArrayList<Message>();
            history.add(new Message(
                    "assistant",
                    null,
                    null,
                    List.of(new ToolCall("call_1", "function", new ToolCall.FunctionCall("grep", "{}"), 0)),
                    null,
                    null));
            history.add(Message.user("a queued prompt interposed between the call and its result"));
            history.add(new Message("tool", "found it", null, null, "call_1", null));
            final var loop = loop(client, new TodoStore() {}, baseline, noPruningManager(), store, null);
            final var result = loop.run("do it", null, "s1", history, () -> false);

            assertEquals("done", result, "a non-contiguously-paired resume history must be healed and complete");
            assertEquals(1, requests.size(), "the healed history must succeed on the first request, no 400 retry");
            final var request = requests.getFirst();
            // every assistant tool call is answered by a contiguous result - the pre-fix behavior left call_1
            // dangling after the interposed prompt and Azure would 400
            assertAllToolCallsAreAnsweredByContiguousResults(request);
            // the synthetic lost-result marker answers call_1 immediately after its assistant message
            final var call1Index = indexOfAssistantCall(request, "call_1");
            assertTrue(call1Index >= 0, "call_1's assistant message must be present: " + request);
            assertTrue(call1Index + 1 < request.size(), "a result must immediately follow call_1: " + request);
            assertEquals("tool", request.get(call1Index + 1).role());
            assertEquals("call_1", request.get(call1Index + 1).toolCallId());
            assertTrue(
                    String.valueOf(request.get(call1Index + 1).content()).contains("tool result lost"),
                    "the first request must answer call_1 with the lost-result marker: " + request);
            // the repair must NOT be persisted: call_1 already carried a response ("found it") in the
            // pre-repair history, so the heal is in-memory only - persisting would add a new tail entry to
            // the append-only session on every resume (the turn's own user/assistant entries are expected).
            assertEquals(
                    0,
                    persisted.stream()
                            .filter(e -> "tool".equals(e.role()) && "call_1".equals(e.toolCallId()))
                            .count(),
                    "an id that already carried a response in history must not be re-persisted: " + persisted);
            // the repaired history must be idempotent: running the repair again (a later post-400 path) must not
            // insert a second marker for the same id - subscribe a fresh loop over the now-healed messages.
            final var requests2 = new ArrayList<List<Message>>();
            final var replied2 = new ArrayDeque<ScriptedReply>(List.of(new ScriptedReply("done", "stop", false)));
            final var loop2 = loop(
                    chatClient(replied2, requests2), new TodoStore() {}, baseline, noPruningManager(), store, null);
            // rebuild the history as it would be persisted after the first turn's repair
            final var healed = new ArrayList<Message>(request);
            final var result2 = loop2.run("do it again", null, "s2", healed, () -> false);
            assertEquals("done", result2, "re-healing an already-healed history must still complete");
            assertAllToolCallsAreAnsweredByContiguousResults(requests2.getFirst());
            assertEquals(
                    0,
                    persisted.stream()
                            .filter(e -> "tool".equals(e.role()) && "call_1".equals(e.toolCallId()))
                            .count(),
                    "running the repair over an already-healed history must not persist a lost-result " + "marker: "
                            + persisted);
        }

        @Test
        void orphanMarkerNotInContiguousPositionIsRepositionedOnResume() {
            // Regression from a real resumed session (s1a167cd41db): the persisted history had TWO consecutive
            // assistant tool_use blocks - A(edit_file call_1) followed by a second assistant B(read call_2), a
            // tool result for B(call_2), and only THEN a lost-result marker for A(call_1). So call_1's response
            // existed but was NOT contiguous to its assistant A (B + call_2's result interposed), which Azure
            // rejects ("tool_calls must be followed by tool messages responding to each 'tool_call_id'"). The
            // pre-fix repair saw a marker for call_1 somewhere in history and (fooled by the per-id guard)
            // skipped healing, leaving the non-contiguous pair to reach Azure. The fix must reposition the
            // marker to sit immediately after assistant A.
            final var requests = new ArrayList<List<Message>>();
            final var persisted = new ArrayList<SessionEntry>();
            final var replied = new ArrayDeque<ScriptedReply>(List.of(new ScriptedReply("done", "stop", false)));
            final var client = chatClient(replied, requests);
            final var store = new SessionStore() {
                @Override
                public void append(final String id, final SessionEntry entry) {
                    persisted.add(entry);
                }
            };
            final var history = new ArrayList<Message>();
            // A: edit_file call_1
            history.add(new Message(
                    "assistant",
                    null,
                    null,
                    List.of(new ToolCall("call_1", "function", new ToolCall.FunctionCall("edit_file", "{}"), 0)),
                    null,
                    null));
            // B: a second assistant block interposed before call_1's response
            history.add(new Message(
                    "assistant",
                    null,
                    null,
                    List.of(new ToolCall("call_2", "function", new ToolCall.FunctionCall("read", "{}"), 0)),
                    null,
                    null));
            // call_2's result lands contiguously after B
            history.add(new Message("tool", "read output", null, null, "call_2", null));
            // call_1's lost-result marker exists but is NOT contiguous to its assistant A
            history.add(new Message(
                    "tool", "[tool result lost: interrupted]", null, null, "call_1", Map.of("is_error", true)));
            final var loop = loop(client, new TodoStore() {}, baseline, noPruningManager(), store, null);
            final var result = loop.run("do it", null, "s1", history, () -> false);

            assertEquals("done", result, "the non-contiguous marker must be repositioned and the turn completes");
            assertEquals(1, requests.size(), "the healed history must succeed on the first request");
            final var request = requests.getFirst();
            assertAllToolCallsAreAnsweredByContiguousResults(request);
            // assistant A (call_1) must be immediately followed by a tool message answering call_1
            final var aIndex = indexOfAssistantCall(request, "call_1");
            assertTrue(aIndex >= 0, "assistant A carrying call_1 must be present: " + request);
            assertTrue(aIndex + 1 < request.size(), "call_1 must have an immediately-following result: " + request);
            assertEquals("tool", request.get(aIndex + 1).role());
            assertEquals("call_1", request.get(aIndex + 1).toolCallId());
            assertTrue(
                    String.valueOf(request.get(aIndex + 1).content()).contains("tool result lost"),
                    "call_1's immediate result must be the lost-result marker: " + request);
            // the same id must not be persisted twice: call_1 already carried a (stray) response in the
            // pre-repair history, so the repositioning is in-memory only - no fresh session entry
            assertEquals(
                    0,
                    persisted.stream()
                            .filter(e -> "tool".equals(e.role()) && "call_1".equals(e.toolCallId()))
                            .count(),
                    "call_1 already carried a response in history, so it must not be re-persisted: " + persisted);
        }

        @Test
        void orphanMarkerAfterUserMessagesIsDroppedAndNotRepersisted() {
            // Regression from the real resumed session (s1a167cd41db) second failure: an id whose lost-result
            // marker was persisted by an earlier repair at the TAIL of the append-only session - AFTER several
            // user messages - reached Azure as an orphan tool response ("messages with role 'tool' must be a
            // response to a tool call"). The repair must (a) heal the call at its assistant block, (b) drop the
            // stray tail marker, and (c) NOT persist a fresh tail entry for the already-persisted id (or every
            // resume would accumulate another orphan marker).
            final var requests = new ArrayList<List<Message>>();
            final var persisted = new ArrayList<SessionEntry>();
            final var replied = new ArrayDeque<ScriptedReply>(List.of(new ScriptedReply("done", "stop", false)));
            final var client = chatClient(replied, requests);
            final var store = new SessionStore() {
                @Override
                public void append(final String id, final SessionEntry entry) {
                    persisted.add(entry);
                }
            };
            final var history = new ArrayList<Message>();
            // the assistant call whose result never landed contiguously
            history.add(new Message(
                    "assistant",
                    null,
                    null,
                    List.of(new ToolCall("call_1", "function", new ToolCall.FunctionCall("edit_file", "{}"), 0)),
                    null,
                    null));
            // a second assistant block interposed (its own call answered contiguously)
            history.add(new Message(
                    "assistant",
                    null,
                    null,
                    List.of(new ToolCall("call_2", "function", new ToolCall.FunctionCall("read", "{}"), 0)),
                    null,
                    null));
            history.add(new Message("tool", "read output", null, null, "call_2", null));
            // fresh user prompts after which the stray tail marker was persisted by an earlier repair
            history.add(Message.user("while im awaiting the extraction result we can move on"));
            history.add(new Message(
                    "tool", "[tool result lost: interrupted]", null, null, "call_1", Map.of("is_error", true)));
            final var loop = loop(client, new TodoStore() {}, baseline, noPruningManager(), store, null);
            final var result = loop.run("do it", null, "s1", history, () -> false);

            assertEquals("done", result, "the stray tail marker must be dropped and the turn completes");
            assertEquals(1, requests.size(), "the healed history must succeed on the first request");
            final var request = requests.getFirst();
            assertAllToolCallsAreAnsweredByContiguousResults(request);
            // call_1 is healed right after its assistant block
            final var aIndex = indexOfAssistantCall(request, "call_1");
            assertEquals("tool", request.get(aIndex + 1).role());
            assertEquals("call_1", request.get(aIndex + 1).toolCallId());
            // exactly ONE tool response carries call_1 on the wire: the stray tail one is dropped
            assertEquals(
                    1,
                    request.stream()
                            .filter(m -> "tool".equals(m.role()) && "call_1".equals(m.toolCallId()))
                            .count(),
                    "exactly one contiguous response for call_1, no orphan: " + request);
            // the tail marker that followed the user prompts must NOT have survived
            for (var i = 0; i < request.size(); i++) {
                if ("user".equals(request.get(i).role())) {
                    assertTrue(
                            i + 1 >= request.size()
                                    || !"tool".equals(request.get(i + 1).role()),
                            "no orphan tool response may follow a user prompt: " + request);
                }
            }
            // the id was already persisted by the earlier repair: no NEW tail entry for it (the turn's own
            // user/assistant entries are expected - only a tool entry for call_1 would be a re-persist)
            assertEquals(
                    0,
                    persisted.stream()
                            .filter(e -> "tool".equals(e.role()) && "call_1".equals(e.toolCallId()))
                            .count(),
                    "an already-persisted id must not get a fresh tail entry on resume: " + persisted);
        }

        @Test
        void nonTool400IsNotRepairedOrRetried() {
            // a 400 unrelated to tool pairing (eg. auth, schema, token budget) must NOT trigger the
            // tool-pairing repair/retry loop: it surfaces as an error so the real cause is never masked.
            final var requests = new ArrayList<List<Message>>();
            final var replied = new ArrayDeque<ScriptedReply>(List.of(new ScriptedReply(
                    new IOException("LLM request failed (400): {\"error\":{\"message\":\"invalid api key\"}}"))));
            final var client = chatClient(replied, requests);
            final var loop = loop(
                    client,
                    new TodoStore() {},
                    baseline,
                    noPruningManager(),
                    new SessionStore() {
                        @Override
                        public void append(final String id, final SessionEntry entry) {
                            // nothing
                        }
                    },
                    null);
            final var result = loop.run("do it", null, "s1", new ArrayList<Message>(), () -> false);

            assertNull(result, "a non-tool 400 must fail the turn (no repair retry)");
            assertEquals(1, requests.size(), "no repair retry for a non-tool 400");
            final var infos = events.stream()
                    .filter(AgentEvent.Notice.class::isInstance)
                    .map(AgentEvent.Notice.class::cast)
                    .anyMatch(n -> n.message().contains("no matching tool result"));
            assertFalse(infos, "the tool-pairing repair notice must NOT fire for a non-tool 400");
        }
    }

    public static class ToolPairing400IsRepairedAndRetriedOnceSource extends ConfigurationTestModule {
        public ToolPairing400IsRepairedAndRetriedOnceSource() {
            super(sourceValues(
                    ToolPairing400IsRepairedAndRetriedOnceScenario.storageHome,
                    true,
                    true,
                    Map.of("yca.llm.streamRetriesMaxAttempts", "0")));
        }
    }

    @Nested
    @FusionSupport(modules = ToolPairing400DoesNotRetryStormUnderDefaultStreamRetryBudgetSource.class)
    class ToolPairing400DoesNotRetryStormUnderDefaultStreamRetryBudgetScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void toolPairing400DoesNotRetryStormUnderDefaultStreamRetryBudget() {
            // Regression: previously a tool-pairing 400 only reached the AgentLoop repair after ModelRoundProcessor
            // had burned its whole generic retry budget re-sending the SAME broken history ("retrying (1/3)",
            // "(2/3)", "(3/3)") with backoff sleeps. With a positive streamRetriesMaxAttempts the pairing 400 must
            // short-circuit on the first attempt, so exactly two requests reach the client: the failing 400 and the
            // single restored retry. Distinguishes this from the existing toolPairing400IsRepairedAndRetriedOnce,
            // which forces streamRetriesMaxAttempts=0 to bypass the retry storm.
            final var requests = new ArrayList<List<Message>>();
            // Boolean gate: null = not yet attempted, FALSE = the pairing-400 was thrown, TRUE = answered.
            final var replied = new AtomicReference<Boolean>(null);
            final var client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled)
                        throws IOException {
                    requests.add(List.copyOf(request.messages()));
                    if (replied.compareAndSet(null, Boolean.FALSE)) {
                        throw new LlmHttpFailureException(
                                400,
                                "{\"detail\":\"litellm.BadRequestError: AzureException BadRequestError - An assistant "
                                        + "message with 'tool_calls' must be followed by tool messages responding to "
                                        + "each 'tool_call_id'. The following tool_call_ids did not have response "
                                        + "messages: call_1.\"}",
                                null);
                    }
                    replied.set(Boolean.TRUE);
                    return new ScriptedReply("done", "stop", false).toMessage();
                }
            };
            final var store = new SessionStore() {
                @Override
                public void append(final String id, final SessionEntry entry) {
                    // nothing
                }
            };
            // a previous turn ended mid-tool, leaving assistant tool call call_1 with no result in history
            final var history = new ArrayList<Message>();
            history.add(new Message(
                    "assistant",
                    null,
                    null,
                    List.of(new ToolCall("call_1", "function", new ToolCall.FunctionCall("grep", "{}"), 0)),
                    null,
                    null));
            // positive streamRetriesMaxAttempts on purpose: the pre-fix code would otherwise storm (1/3, 2/3, 3/3)
            // and only repair after exhausting the budget.
            final var loop = loop(client, new TodoStore() {}, baseline, noPruningManager(), store, null);
            final var result = loop.run("do it", null, "s1", history, () -> false);

            assertEquals("done", result, "the repaired retry must complete the turn instead of failing it");
            assertEquals(2, requests.size(), "pairing 400 must short-circuit and retry exactly once, no (1/N) storm");
            assertAllToolCallsAreAnsweredByContiguousResults(requests.get(1));
        }
    }

    public static class ToolPairing400DoesNotRetryStormUnderDefaultStreamRetryBudgetSource
            extends ConfigurationTestModule {
        public ToolPairing400DoesNotRetryStormUnderDefaultStreamRetryBudgetSource() {
            super(sourceValues(
                    ToolPairing400DoesNotRetryStormUnderDefaultStreamRetryBudgetScenario.storageHome,
                    true,
                    true,
                    Map.of("yca.llm.streamRetriesMaxAttempts", "2")));
        }
    }

    @Nested
    @FusionSupport(modules = ResumeAliasesAreConfigurableAndCaseInsensitiveSource.class)
    class ResumeAliasesAreConfigurableAndCaseInsensitiveScenario {
        @TempDir
        private static Path storageHome;

        @BeforeEach
        void configure(@Fusion final CodingAgentConfiguration configuration) {
            bindConfiguration(configuration);
        }

        @Test
        void resumeAliasesAreConfigurableAndCaseInsensitive() {
            final var cfg = baseline;
            assertTrue(cfg.isResumeAlias("  Continue "));
            assertTrue(cfg.isResumeAlias("VAS-Y"));
            assertFalse(cfg.isResumeAlias("go"), "defaults are replaced, not merged");
            assertFalse(cfg.isResumeAlias(""));
            assertFalse(cfg.isResumeAlias(null));
        }
    }

    public static class ResumeAliasesAreConfigurableAndCaseInsensitiveSource extends ConfigurationTestModule {
        public ResumeAliasesAreConfigurableAndCaseInsensitiveSource() {
            super(sourceValues(
                    ResumeAliasesAreConfigurableAndCaseInsensitiveScenario.storageHome,
                    true,
                    true,
                    Map.of("yca.resumeAliases", "continue,vas-y")));
        }
    }

    private static CodingAgentConfiguration withWorkingDirProjectDir(
            final CodingAgentConfiguration baseline, final String workingDir, final String projectDir) {
        return new CodingAgentConfiguration(
                baseline.storageHome(),
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

    private static CodingAgentConfiguration withProjectDir(
            final CodingAgentConfiguration baseline, final String projectDir) {
        return new CodingAgentConfiguration(
                baseline.storageHome(),
                baseline.home(),
                baseline.workingDir(),
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

    private static CodingAgentConfiguration withWorkingDir(
            final CodingAgentConfiguration baseline, final String workingDir) {
        return new CodingAgentConfiguration(
                baseline.storageHome(),
                baseline.home(),
                workingDir,
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

    private static PruningConfiguration withIngestionOutputCapChars(
            final PruningConfiguration baseline, final int ingestionOutputCapChars) {
        return new PruningConfiguration(
                baseline.enabled(),
                baseline.thresholdPercent(),
                baseline.keepRecent(),
                baseline.protectTurns(),
                baseline.protectRecentTokens(),
                baseline.minPruneTokens(),
                baseline.shrinkOutputChars(),
                baseline.toolOutputMaxChars(),
                ingestionOutputCapChars,
                baseline.deduplicateToolCalls(),
                baseline.supersedeOverwrittenWrites(),
                baseline.purgeFailedToolInputs(),
                baseline.purgeFailedToolInputsTurns(),
                baseline.purgeDeliveredSubagents(),
                baseline.purgeDeliveredSubagentsTurns(),
                baseline.nudgeThresholdPercent(),
                baseline.nudgeIterationThreshold(),
                baseline.nudgeMinTokens(),
                baseline.nudgeTemplate(),
                baseline.nudgeUrgentTemplate());
    }

    private static CodingAgentConfiguration withPruning(
            final CodingAgentConfiguration baseline, final PruningConfiguration pruning) {
        return new CodingAgentConfiguration(
                baseline.storageHome(),
                baseline.home(),
                baseline.workingDir(),
                baseline.projectDir(),
                baseline.provider(),
                baseline.model(),
                baseline.llm(),
                baseline.compaction(),
                pruning,
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

    private static PruningConfiguration withNudgeMinTokens(
            final PruningConfiguration baseline, final int nudgeMinTokens) {
        return new PruningConfiguration(
                baseline.enabled(),
                baseline.thresholdPercent(),
                baseline.keepRecent(),
                baseline.protectTurns(),
                baseline.protectRecentTokens(),
                baseline.minPruneTokens(),
                baseline.shrinkOutputChars(),
                baseline.toolOutputMaxChars(),
                baseline.ingestionOutputCapChars(),
                baseline.deduplicateToolCalls(),
                baseline.supersedeOverwrittenWrites(),
                baseline.purgeFailedToolInputs(),
                baseline.purgeFailedToolInputsTurns(),
                baseline.purgeDeliveredSubagents(),
                baseline.purgeDeliveredSubagentsTurns(),
                baseline.nudgeThresholdPercent(),
                baseline.nudgeIterationThreshold(),
                nudgeMinTokens,
                baseline.nudgeTemplate(),
                baseline.nudgeUrgentTemplate());
    }
}
