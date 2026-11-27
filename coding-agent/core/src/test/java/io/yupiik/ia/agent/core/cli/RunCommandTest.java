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
package io.yupiik.ia.agent.core.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.cli.CliAwaiter;
import io.yupiik.fusion.cli.internal.CliCommand;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.FusionModule;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.framework.api.main.Args;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.question.Question;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.RunShellFrontend;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.shell.SessionRuntimeFactory;
import io.yupiik.ia.agent.core.shell.ShellRegistry;
import io.yupiik.ia.agent.core.todo.InMemoryTodoStore;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.builtin.AskUserTool;
import io.yupiik.ia.agent.core.wiring.FeedbackGateProducer;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs isolated from all other classes: it swaps the JVM-global {@code System.out}/{@code System.err}
 * and attaches handlers to the shared {@code java.util.logging} hierarchy, which is a JVM-wide contract
 * no {@code @ResourceLock} can guard against classes that merely <em>write</em> to those streams.
 */
@Isolated
@ExtendWith(TestJsonMapper.class)
@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
@ResourceLock("java.util.logging")
class RunCommandTest {
    private static final Pattern STREAMED_LINE = Pattern.compile("streamed line");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    @TempDir
    private static Path eventWorkdir;

    @TempDir
    private static Path testWorkdir;

    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(
            final JsonMapper mapper,
            @Fusion final CodingAgentConfiguration configuration,
            @Fusion final RunCommand.RunConf runConf) {
        this.mapper = mapper;
        this.configuration = configuration;
        this.runConf = runConf;
    }

    private CodingAgentConfiguration configuration;
    private RunCommand.RunConf runConf;

    @TempDir
    Path temp;

    private PrintStream originalOut;
    private PrintStream originalErr;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    /** Concurrent: records are published by whichever thread logs, not always the test one. */
    private final Queue<String> logLines = new ConcurrentLinkedQueue<>();
    /**
     * Strong references to the loggers the handler is attached to. {@code LogManager} only keeps
     * loggers weakly, and a class literal ({@code RunShellFrontend.class}) does <em>not</em> trigger
     * static initialization: without these fields the logger created by {@link #capture()} can be
     * collected before the class under test initializes its own {@code LOGGER} static, which then
     * registers on a fresh logger the handler was never attached to - the records silently vanish,
     * intermittently and only under the GC pressure of a full suite.
     */
    private final List<Logger> capturedLoggers = new ArrayList<>();

    private Handler logHandler;

    @BeforeEach
    void capture() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        logHandler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                logLines.add(record.getMessage());
            }

            @Override
            public void flush() {
                // no-op
            }

            @Override
            public void close() {
                // no-op
            }
        };
        logLines.clear();
        capturedLoggers.clear();
        for (final var name : List.of(RunShellFrontend.class.getName(), RunCommand.class.getName())) {
            final var logger = Logger.getLogger(name);
            logger.addHandler(logHandler);
            capturedLoggers.add(logger);
        }
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        capturedLoggers.forEach(logger -> logger.removeHandler(logHandler));
        capturedLoggers.clear();
    }

    private String logs() {
        return String.join("\n", logLines);
    }

    /**
     * Waits for a log record carrying {@code needle} instead of asserting immediately: a record is
     * published by the logger caller, which is not guaranteed to be this thread, so the assertion has
     * to race nothing.
     *
     * @param needle substring the expected record must carry
     */
    private void awaitLog(final String needle) {
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!logs().contains(needle) && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        assertTrue(logs().contains(needle), "expected a '" + needle + "' log record, got: [" + logs() + "]");
    }

    private final AtomicReference<String> lastSessionIdUsed = new AtomicReference<>();

    private AgentLoop stubAgentLoop() {
        return new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled) {
                lastSessionIdUsed.set(sessionId);
                return "ok";
            }
        };
    }

    private String defaultCorrectionPrompt() {
        return runConf.resultCorrectionPrompt();
    }

    private static RunCommand.RunConf promptConfiguration(
            final RunCommand.RunConf runConf, final String prompt, final String file) {
        return new RunCommand.RunConf(
                prompt,
                runConf.session(),
                runConf.agent(),
                runConf.showThinking(),
                runConf.mode(),
                runConf.showToolOutput(),
                runConf.metricsOutputFile(),
                runConf.metricsDetailFile(),
                runConf.resultFile(),
                runConf.resultRequiredFields(),
                runConf.resultMaxBytes(),
                runConf.resultCorrectionPrompt(),
                file,
                runConf.sandbox());
    }

    private void verifyFusionFeedback(final boolean override, final boolean fromFile) {

        final var questions = List.of(new Question("choice", "Pick?", List.of("yes", "no"), false));
        final var otherRuntime = runtimeFactory(
                        stubAgentLoop(), new PermissionService(configuration, (configuration).resolveWorkingDir()))
                .create("other", temp);
        otherRuntime.feedback().setGate(asked -> Map.of("choice", "other-session"));
        final var active = new AtomicReference<SessionRuntime>();
        final var overrideCalls = new AtomicInteger();
        final var turns = new AtomicInteger();
        final var loop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled) {
                turns.incrementAndGet();
                final var runtime = active.get();
                final var result = new AskUserTool(mapper)
                        .execute(
                                Map.of(
                                        "questions",
                                        List.of(Map.of(
                                                "id", "choice", "prompt", "Pick?", "options", List.of("yes", "no")))),
                                new ToolContext(
                                        sessionId,
                                        null,
                                        null,
                                        runtime.permissions(),
                                        runtime.feedback(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        cancelled,
                                        null,
                                        null,
                                        testWorkdir,
                                        new DisabledSandbox(testWorkdir)));
                assertTrue(result.success());
                if (override) {
                    assertEquals("{\"choice\":\"yes\"}", result.output());
                } else {
                    assertTrue(result.output().contains("\"cancelled\":true"));
                    assertTrue(result.output().contains("Do not call"));
                }
                assertEquals(
                        Map.of("choice", "other-session"),
                        otherRuntime.feedback().ask(questions));
                assertFalse(
                        runtime.permissions()
                                .check("bash", ToolCategory.BASH, Map.of("command", "rm -rf /"))
                                .allowed(),
                        "feedback answers must never grant tool approval");
                return "done";
            }
        };
        final var factory = new SessionRuntimeFactory() {
            @Override
            public Path resolveWorkdir(final Path explicitWorkdir, final String resumedSessionId) {
                return explicitWorkdir == null ? testWorkdir : explicitWorkdir;
            }

            @Override
            public SessionRuntime create(final String sessionId, final Path workdir, final Boolean sandbox) {
                final var runtime = runtimeFactory(
                                loop, new PermissionService(configuration, (configuration).resolveWorkingDir()))
                        .create(sessionId, workdir, sandbox);
                active.set(runtime);
                return runtime;
            }
        };
        final var conf = runConf;
        final var configuring = ConfiguringContainer.of()
                .register(new ProvidedInstanceBean<>(DefaultScoped.class, RunCommand.RunConf.class, () -> conf))
                .register(new ProvidedInstanceBean<>(
                        DefaultScoped.class, ShellRegistry.class, RunCommandTest::testRegistry))
                .register(new ProvidedInstanceBean<>(DefaultScoped.class, SessionRuntimeFactory.class, () -> factory))
                .register(new ProvidedInstanceBean<>(DefaultScoped.class, SessionStore.class, this::sessionStore))
                .register(new ProvidedInstanceBean<>(
                        DefaultScoped.class, AgentProvider.class, () -> new AgentProvider(configuration)))
                .register(new ProvidedInstanceBean<>(DefaultScoped.class, McpManager.class, this::mcpManager))
                .register(new ProvidedInstanceBean<>(DefaultScoped.class, JsonMapper.class, () -> mapper))
                .register(new FusionModule() {
                    @Override
                    public BiPredicate<RuntimeContainer, FusionBean<?>> beanFilter() {
                        return (container, bean) -> bean instanceof ProvidedInstanceBean<?>
                                || bean.type() == RunCommand.class
                                || bean.type() == FeedbackGateProducer.class
                                || bean.type() == FeedbackGate.class
                                || bean.type() instanceof ParameterizedType type
                                        && type.getRawType() == CliCommand.class;
                    }

                    @Override
                    public BiPredicate<RuntimeContainer, FusionListener<?>> listenerFilter() {
                        return (container, listener) -> false;
                    }
                });
        if (override) {
            configuring.register(
                    new ProvidedInstanceBean<>(DefaultScoped.class, FeedbackGate.class, () -> asked -> {
                        overrideCalls.incrementAndGet();
                        assertEquals(questions, asked);
                        return Map.of("choice", "yes");
                    }) {
                        @Override
                        public int priority() {
                            return 2000;
                        }
                    });
        }
        try (final var container = configuring.start();
                final var command = container.lookup(RunCommand.class)) {
            assertEquals(
                    override ? 2 : 1,
                    container.getBeans().getBeans().get(FeedbackGate.class).size());
            if (fromFile) {
                try (final var commands = container.lookups(CliCommand.class)) {
                    new CliAwaiter(
                                    new Args(List.of(
                                            "run",
                                            "--prompt-file",
                                            temp.resolve("prompt.txt").toString())),
                                    Configuration.of(Map.of()),
                                    commands.instance().stream()
                                            .<CliCommand<?>>map(commandMetadata -> commandMetadata)
                                            .toList())
                            .await();
                }
            } else {
                command.instance().run();
            }
            assertEquals(1, turns.get());
            assertEquals(override ? 1 : 0, overrideCalls.get());
            // Changing the other session's gate must not change the run's feedback service either.
            otherRuntime.feedback().setGate(asked -> Map.of("choice", "changed-other-session"));
            assertEquals(
                    override ? Map.of("choice", "yes") : null,
                    active.get().feedback().ask(questions));
            assertEquals(
                    Map.of("choice", "changed-other-session"),
                    otherRuntime.feedback().ask(questions));
        } finally {
            otherRuntime.close();
        }
    }

    private static SessionRuntimeFactory runtimeFactory(final AgentLoop loop, final PermissionService permissions) {
        return new SessionRuntimeFactory() {
            @Override
            public Path resolveWorkdir(final Path explicitWorkdir, final String resumedSessionId) {
                return explicitWorkdir == null ? testWorkdir : explicitWorkdir;
            }

            @Override
            public SessionRuntime create(final String sessionId, final Path workdir, final Boolean sandbox) {
                return new SessionRuntime(
                        loop,
                        permissions,
                        new UserFeedbackService(),
                        new UsageTracker(null, null, null, Clock.systemUTC()),
                        new InMemoryTodoStore(),
                        new StatusReporter(),
                        null,
                        null,
                        workdir,
                        new DisabledSandbox(workdir));
            }
        };
    }

    private SessionStore sessionStore() {
        return new SessionStore() {
            @Override
            public String create() {
                return "s-run";
            }

            @Override
            public List<Message> load(final String id) {
                return List.of();
            }
        };
    }

    private static Map<String, String> metricFields(final String line) {
        final var fields = new HashMap<String, String>();
        for (final var field : WHITESPACE.split(line)) {
            final var separator = field.indexOf('=');
            if (separator > 0) {
                assertNull(
                        fields.put(field.substring(0, separator), field.substring(separator + 1)),
                        "duplicate metric: " + field);
            }
        }
        return fields;
    }

    private final AtomicBoolean mcpStarted = new AtomicBoolean();

    private McpManager mcpManager() {
        return new McpManager() {
            @Override
            public void start() {
                mcpStarted.set(true);
            }
        };
    }

    private static ShellRegistry testRegistry() {
        return new ShellRegistry(new Emitter() {
            @Override
            public <T> void emit(final T event) {
                // events are forwarded synchronously to the registered frontend
            }
        });
    }

    @Nested
    @FusionSupport(modules = DefaultsModule.class)
    class Defaults {
        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        void throughputMetricsFilesRetainLegacyRatesAndExposeCoverage(final boolean providerTiming) throws IOException {
            final var shellRegistry = testRegistry();
            // Deliberately use different request/server coverage: the server rate must use 60, not 240 tokens.
            final var throughput = new AgentEvent.Throughput(
                    240,
                    40,
                    2000,
                    providerTiming ? 400 : 0,
                    providerTiming ? 60 : 0,
                    3,
                    providerTiming ? 1 : 0,
                    !providerTiming);
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    shellRegistry.onEvent(new AgentEvent.TurnMetrics(
                            3, 100, 200, 150, 2, 2500, throughput, sessionId, null, null, eventWorkdir));
                    shellRegistry.onEvent(new AgentEvent.TurnDone("done", sessionId, eventWorkdir));
                    return "done";
                }
            };
            final var summaryFile = temp.resolve("throughput-summary.txt");
            final var detailFile = temp.resolve("throughput-events.txt");
            final var command = new RunCommand(
                    new RunCommand.RunConf(
                            "say hi",
                            "",
                            "",
                            false,
                            "",
                            "collapsed",
                            summaryFile.toString(),
                            detailFile.toString(),
                            "",
                            "",
                            1000000,
                            defaultCorrectionPrompt(),
                            null,
                            null),
                    shellRegistry,
                    runtimeFactory(agentLoop, new PermissionService(null, testWorkdir)),
                    sessionStore(),
                    null,
                    mcpManager(),
                    mapper,
                    new FeedbackGateProducer().feedbackGate());

            command.run();

            final var summaryLines = Files.readAllLines(summaryFile, StandardCharsets.UTF_8);
            assertEquals(1, summaryLines.size());
            final var eventLines = Files.readAllLines(detailFile, StandardCharsets.UTF_8).stream()
                    .filter(line -> line.startsWith("turnMetrics "))
                    .toList();
            assertEquals(1, eventLines.size());
            final var summary = metricFields(summaryLines.getFirst());
            final var event = metricFields(eventLines.getFirst());
            assertEquals("120.0", summary.get("outTokPerSec"));
            assertEquals("120.0", event.get("tokPerSec"));
            for (final var fields : List.of(summary, event)) {
                assertEquals("240", fields.get("outputTokens"));
                assertEquals("2000", fields.get("llmActiveMs"));
                assertEquals(providerTiming ? "400" : "0", fields.get("llmReportedMs"));
                assertEquals(providerTiming ? "150.0" : "0.0", fields.get("realTokPerSec"));
                assertEquals("120.0", fields.get("requestTokPerSec"));
                assertEquals(providerTiming ? "150.0" : "0.0", fields.get("serverTokPerSec"));
                assertEquals(providerTiming ? "60" : "0", fields.get("reportedOutputTokens"));
                assertEquals("3", fields.get("completedRequests"));
                assertEquals(providerTiming ? "1" : "0", fields.get("reportedRequests"));
                assertEquals(providerTiming ? "false" : "true", fields.get("outputEstimated"));
            }
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "both",
                    "neither",
                    "blank",
                    "directory",
                    "link",
                    "parent-link",
                    "public",
                    "oversize",
                    "utf8",
                    "missing"
                })
        void rejectsInvalidPromptBeforeStartingRuntime(final String invalid) throws IOException {
            final var file = Files.createFile(
                    temp.resolve("prompt.txt"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(file, "private canary");
            var supplied = file;
            switch (invalid) {
                case "blank" -> Files.writeString(file, " \n");
                case "directory" -> supplied = temp;
                case "link" -> supplied = Files.createSymbolicLink(temp.resolve("link"), file);
                case "parent-link" ->
                    supplied = Files.createSymbolicLink(temp.resolve("parent-link"), temp)
                            .resolve("prompt.txt");
                case "public" -> Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
                case "oversize" -> Files.write(file, new byte[8 * 1024 * 1024 + 1]);
                case "utf8" -> Files.write(file, new byte[] {(byte) 0xc3, (byte) 0x28});
                case "missing" -> Files.delete(file);
                default -> {
                    /* exclusivity cases need no file change */
                }
            }
            final var conf = promptConfiguration(
                    runConf,
                    "both".equals(invalid) ? "inline canary" : "",
                    "neither".equals(invalid) ? "" : supplied.toString());
            final var failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> new RunCommand(conf, null, null, null, null, mcpManager(), mapper, null).run());
            assertFalse(mcpStarted.get());
            assertFalse(failure.toString().contains("canary"));
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        void happyPathPrintsDeltas(final boolean fromFile) throws IOException {
            final var prompt = "say hi\nUnicode: \u00e9; literal $(do-not-execute)";
            final var file = temp.resolve("prompt.txt");
            if (fromFile) {
                Files.createFile(
                        file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.writeString(file, prompt);
            }
            final var shellRegistry = testRegistry();
            final var calls = new ArrayList<String[]>();
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    assertTrue(mcpStarted.get(), "MCP warm-up must begin before the first agent request");
                    calls.add(new String[] {prompt, agentName, sessionId});
                    assertNull(history);
                    shellRegistry.onEvent(new AgentEvent.TextDelta("hel", sessionId, eventWorkdir));
                    shellRegistry.onEvent(new AgentEvent.TextDelta("lo", sessionId, eventWorkdir));
                    shellRegistry.onEvent(new AgentEvent.TurnDone("hello", sessionId, eventWorkdir));
                    return "ok";
                }
            };
            final var command = new RunCommand(
                    new RunCommand.RunConf(
                            fromFile ? "" : prompt,
                            "",
                            "",
                            false,
                            "",
                            "collapsed",
                            "",
                            "",
                            "",
                            "",
                            1000000,
                            defaultCorrectionPrompt(),
                            fromFile ? file.toString() : null,
                            null),
                    shellRegistry,
                    runtimeFactory(agentLoop, new PermissionService(null, testWorkdir)),
                    sessionStore(),
                    null,
                    mcpManager(),
                    mapper,
                    new FeedbackGateProducer().feedbackGate());

            assertFalse(mcpStarted.get(), "Constructing a command must not start MCP servers");
            command.run();

            assertEquals(1, calls.size());
            assertEquals(prompt, calls.get(0)[0]);
            assertNull(calls.get(0)[1]);
            assertEquals("s-run", calls.get(0)[2]);
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("hello"));
        }

        @Test
        void acceptsPromptFileAtExactByteLimit() throws IOException {
            final var file = Files.createFile(
                    temp.resolve("prompt.txt"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r--------")));
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            final var prompt = "x".repeat(8 * 1024 * 1024);
            Files.writeString(file, prompt);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
            final var conf = promptConfiguration(runConf, "", file.toString());
            final var received = new AtomicReference<String>();
            final var loop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String value,
                        final String agent,
                        final String session,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    received.set(value);
                    return "ok";
                }
            };
            new RunCommand(
                            conf,
                            testRegistry(),
                            runtimeFactory(loop, new PermissionService(null, testWorkdir)),
                            sessionStore(),
                            null,
                            mcpManager(),
                            mapper,
                            new FeedbackGateProducer().feedbackGate())
                    .run();
            assertEquals(prompt, received.get());
        }

        @Test
        void resultCorrectionUsesSameSessionHistoryAndCancellation() throws Exception {
            final var result = Files.writeString(temp.resolve("result.json"), "{}");
            final var history = List.of(Message.system("saved task context"));
            final var prompts = new ArrayList<String>();
            final var cancellation = new AtomicReference<BooleanSupplier>();
            final var cap = new AtomicInteger();
            final var loop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public void maxIterationsCap(final int value) {
                    cap.set(value);
                }

                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> messages,
                        final BooleanSupplier cancelled) {
                    prompts.add(prompt);
                    assertEquals("s-run", sessionId);
                    if (prompts.size() == 1) {
                        assertNull(messages);
                        cancellation.set(cancelled);
                    } else {
                        assertEquals(2, prompts.size());
                        assertEquals(history, messages);
                        assertTrue(cancelled == cancellation.get());
                        assertEquals(3, cap.get());
                        assertTrue(prompt.contains("summary|failure"));
                        try {
                            Files.writeString(result, "{\"summary\":\"corrected using task evidence\"}");
                        } catch (final IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                    return "done";
                }
            };
            final var store = new SessionStore() {
                @Override
                public String create() {
                    return "s-run";
                }

                @Override
                public List<Message> load(final String id) {
                    assertEquals("s-run", id);
                    return history;
                }
            };
            new RunCommand(
                            new RunCommand.RunConf(
                                    "task",
                                    "",
                                    "",
                                    false,
                                    "",
                                    "hidden",
                                    "",
                                    "",
                                    result.toString(),
                                    "summary|failure",
                                    1000000,
                                    defaultCorrectionPrompt(),
                                    null,
                                    null),
                            testRegistry(),
                            runtimeFactory(loop, new PermissionService(null, testWorkdir)),
                            store,
                            null,
                            mcpManager(),
                            mapper,
                            new FeedbackGateProducer().feedbackGate())
                    .run();
            assertEquals(2, prompts.size());
            assertEquals("{\"summary\":\"corrected using task evidence\"}", Files.readString(result));
        }

        @Test
        void validResultDoesNotTriggerCorrection() throws Exception {
            final var result = Files.writeString(temp.resolve("valid.json"), "{\"failure\":\"blocked\"}");
            final var calls = new AtomicInteger();
            final var loop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    calls.incrementAndGet();
                    return "done";
                }
            };
            new RunCommand(
                            new RunCommand.RunConf(
                                    "task",
                                    "",
                                    "",
                                    false,
                                    "",
                                    "hidden",
                                    "",
                                    "",
                                    result.toString(),
                                    "summary|failure",
                                    1000000,
                                    defaultCorrectionPrompt(),
                                    null,
                                    null),
                            testRegistry(),
                            runtimeFactory(loop, new PermissionService(null, testWorkdir)),
                            sessionStore(),
                            null,
                            mcpManager(),
                            mapper,
                            new FeedbackGateProducer().feedbackGate())
                    .run();
            assertEquals(1, calls.get());
        }

        @Test
        void failedTurnDoesNotTriggerResultCorrection() {
            final var registry = testRegistry();
            final var calls = new AtomicInteger();
            final var loop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    calls.incrementAndGet();
                    registry.onEvent(new AgentEvent.Error("provider failed", sessionId, eventWorkdir));
                    return "";
                }
            };
            final var command = new RunCommand(
                    new RunCommand.RunConf(
                            "task",
                            "",
                            "",
                            false,
                            "",
                            "hidden",
                            "",
                            "",
                            temp.resolve("missing.json").toString(),
                            "summary|failure",
                            1000000,
                            defaultCorrectionPrompt(),
                            null,
                            null),
                    registry,
                    runtimeFactory(loop, new PermissionService(null, testWorkdir)),
                    sessionStore(),
                    null,
                    mcpManager(),
                    mapper,
                    new FeedbackGateProducer().feedbackGate());
            assertTrue(assertThrows(IllegalStateException.class, command::run)
                    .getMessage()
                    .contains("provider failed"));
            assertEquals(1, calls.get());
        }

        @Test
        void errorEventFails() {
            final var shellRegistry = testRegistry();
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    lastSessionIdUsed.set(sessionId);
                    shellRegistry.onEvent(new AgentEvent.Error("boom", sessionId, eventWorkdir));
                    return "ko";
                }
            };
            final var command = new RunCommand(
                    new RunCommand.RunConf(
                            "say hi",
                            "s-2",
                            "",
                            false,
                            "",
                            "collapsed",
                            "",
                            "",
                            "",
                            "",
                            1000000,
                            defaultCorrectionPrompt(),
                            null,
                            null),
                    shellRegistry,
                    runtimeFactory(agentLoop, new PermissionService(null, testWorkdir)),
                    sessionStore(),
                    null,
                    mcpManager(),
                    mapper,
                    new FeedbackGateProducer().feedbackGate());

            final var error = assertThrows(IllegalStateException.class, command::run);

            assertEquals("s-2", lastSessionIdUsed.get());
            assertTrue(error.getMessage().contains("boom"));
            awaitLog("boom");
        }

        @Test
        void deniedApprovalExplainsHeadlessBuildMode() {

            final var permissions = new PermissionService(configuration, (configuration).resolveWorkingDir());
            permissions.setMode(AgentMode.BUILD);
            final var allowed = new AtomicBoolean(true);
            final var shellRegistry = testRegistry();
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    final var decision = permissions.check("bash", ToolCategory.BASH, Map.of("command", "rm -rf /"));
                    allowed.set(decision.allowed());
                    return "ok";
                }
            };
            final var command = new RunCommand(
                    new RunCommand.RunConf(
                            "say hi",
                            "",
                            "",
                            false,
                            "build",
                            "collapsed",
                            "",
                            "",
                            "",
                            "",
                            1000000,
                            defaultCorrectionPrompt(),
                            null,
                            null),
                    shellRegistry,
                    runtimeFactory(agentLoop, permissions),
                    sessionStore(),
                    new AgentProvider(configuration),
                    mcpManager(),
                    mapper,
                    new FeedbackGateProducer().feedbackGate());

            command.run();

            assertFalse(allowed.get(), "headless gate must deny the tool call");
            awaitLog("denied");
            awaitLog("headless mode");
        }

        @Test
        void planModeHeadlessDeniesStateChangingToolInPermissionService() {

            final var permissions = new PermissionService(configuration, (configuration).resolveWorkingDir());
            // default mode, falls back to PLAN
            final var allowed = new AtomicBoolean(true);
            final var shellRegistry = testRegistry();
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    final var decision = permissions.check("bash", ToolCategory.BASH, Map.of("command", "rm -rf /"));
                    allowed.set(decision.allowed());
                    return "ok";
                }
            };
            final var command = new RunCommand(
                    new RunCommand.RunConf(
                            "say hi",
                            "",
                            "",
                            false,
                            "",
                            "collapsed",
                            "",
                            "",
                            "",
                            "",
                            1000000,
                            defaultCorrectionPrompt(),
                            null,
                            null),
                    shellRegistry,
                    runtimeFactory(agentLoop, permissions),
                    sessionStore(),
                    new AgentProvider(configuration),
                    mcpManager(),
                    mapper,
                    new FeedbackGateProducer().feedbackGate());

            command.run();

            assertFalse(allowed.get(), "state-changing bash must be refused in headless mode via the gate");
        }

        @Test
        void planModeFlagPutsRunInPlanMode() {

            final var permissions = new PermissionService(configuration, (configuration).resolveWorkingDir());
            final var modeSeen = new AtomicReference<AgentMode>();
            final var shellRegistry = testRegistry();
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    modeSeen.set(permissions.mode());
                    return "ok";
                }
            };
            final var command = new RunCommand(
                    new RunCommand.RunConf(
                            "say hi",
                            "",
                            "",
                            false,
                            "plan",
                            "collapsed",
                            "",
                            "",
                            "",
                            "",
                            1000000,
                            defaultCorrectionPrompt(),
                            null,
                            null),
                    shellRegistry,
                    runtimeFactory(agentLoop, permissions),
                    sessionStore(),
                    new AgentProvider(configuration),
                    mcpManager(),
                    mapper,
                    new FeedbackGateProducer().feedbackGate());

            command.run();

            assertEquals(AgentMode.PLAN, modeSeen.get(), "the run --mode plan flag must switch to plan mode");
        }

        @Test
        void planModeStateChangingToolReachesGate() {

            final var permissions = new PermissionService(configuration, (configuration).resolveWorkingDir());
            final var reason = new AtomicReference<String>();
            final var shellRegistry = testRegistry();
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    final var decision = permissions.check("bash", ToolCategory.BASH, Map.of("command", "rm -rf /"));
                    reason.set(decision.reason());
                    return "ok";
                }
            };
            final var command = new RunCommand(
                    new RunCommand.RunConf(
                            "say hi",
                            "",
                            "",
                            false,
                            "plan",
                            "collapsed",
                            "",
                            "",
                            "",
                            "",
                            1000000,
                            defaultCorrectionPrompt(),
                            null,
                            null),
                    shellRegistry,
                    runtimeFactory(agentLoop, permissions),
                    sessionStore(),
                    new AgentProvider(configuration),
                    mcpManager(),
                    mapper,
                    new FeedbackGateProducer().feedbackGate());

            command.run();

            // plan mode no longer hard-denies a state-changing command up front: it is routed
            // to the headless gate, which refuses it with the generic gate denial reason
            assertFalse(reason.get().contains("[denied]"));
            assertTrue(
                    reason.get().contains("refused by the user"),
                    "plan-mode state-changing tool must be refused by the gate: " + reason.get());
        }

        @Test
        void showToolOutputPrintsNonStreamedSuccess() {
            final var shellRegistry = testRegistry();
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    shellRegistry.onEvent(
                            new AgentEvent.ToolStarted("read", "{\"path\":\"a.txt\"}", sessionId, eventWorkdir));
                    shellRegistry.onEvent(
                            new AgentEvent.ToolFinished("read", "line1\nline2", true, sessionId, eventWorkdir));
                    shellRegistry.onEvent(new AgentEvent.TurnDone("done", sessionId, eventWorkdir));
                    return "ok";
                }
            };
            final var command = new RunCommand(
                    new RunCommand.RunConf(
                            "say hi",
                            "",
                            "",
                            false,
                            "",
                            "shown",
                            "",
                            "",
                            "",
                            "",
                            1000000,
                            defaultCorrectionPrompt(),
                            null,
                            null),
                    shellRegistry,
                    runtimeFactory(agentLoop, new PermissionService(null, testWorkdir)),
                    sessionStore(),
                    null,
                    mcpManager(),
                    mapper,
                    new FeedbackGateProducer().feedbackGate());

            command.run();

            awaitLog("line1");
            awaitLog("line2");
        }

        @Test
        void showToolOutputSkipsStreamedTools() {
            final var shellRegistry = testRegistry();
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    shellRegistry.onEvent(
                            new AgentEvent.ToolStarted("bash", "{\"command\":\"echo hi\"}", sessionId, eventWorkdir));
                    shellRegistry.onEvent(new AgentEvent.ToolStream("bash", "streamed line", sessionId, eventWorkdir));
                    shellRegistry.onEvent(
                            new AgentEvent.ToolFinished("bash", "streamed line", true, sessionId, eventWorkdir));
                    shellRegistry.onEvent(new AgentEvent.TurnDone("done", sessionId, eventWorkdir));
                    return "ok";
                }
            };
            final var command = new RunCommand(
                    new RunCommand.RunConf(
                            "say hi",
                            "",
                            "",
                            false,
                            "",
                            "shown",
                            "",
                            "",
                            "",
                            "",
                            1000000,
                            defaultCorrectionPrompt(),
                            null,
                            null),
                    shellRegistry,
                    runtimeFactory(agentLoop, new PermissionService(null, testWorkdir)),
                    sessionStore(),
                    null,
                    mcpManager(),
                    mapper,
                    new FeedbackGateProducer().feedbackGate());

            command.run();

            // streamed tool output must not be re-printed from ToolFinished.output
            awaitLog("streamed line");
            assertEquals(1, STREAMED_LINE.split(logs(), -1).length - 1);
        }

        @Test
        void unknownAgentIsRejected() {

            final var shellRegistry = testRegistry();
            final var command = new RunCommand(
                    new RunCommand.RunConf(
                            "say hi",
                            "",
                            "nope",
                            false,
                            "",
                            "collapsed",
                            "",
                            "",
                            "",
                            "",
                            1000000,
                            defaultCorrectionPrompt(),
                            null,
                            null),
                    shellRegistry,
                    runtimeFactory(stubAgentLoop(), new PermissionService(null, testWorkdir)),
                    sessionStore(),
                    new AgentProvider(configuration),
                    mcpManager(),
                    mapper,
                    new FeedbackGateProducer().feedbackGate());

            final var error = assertThrows(IllegalArgumentException.class, command::run);
            assertTrue(error.getMessage().contains("Unknown agent"));
        }

        @Test
        void fusionDefaultFeedbackCancelsWithoutChangingOtherSessions() {
            verifyFusionFeedback(false, false);
        }

        @Test
        void fusionExactContractOverrideAnswersWithoutApprovingToolsOrChangingOtherSessions() {
            verifyFusionFeedback(true, false);
        }

        @Test
        void shortPromptFileCliOptionRunsWithFusionBindings() throws IOException {
            final var file = Files.createFile(
                    temp.resolve("prompt.txt"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(file, "task");
            verifyFusionFeedback(false, true);
        }

        @Test
        void displayOnlyUsageRefreshDoesNotCountAsRequest() throws Exception {
            final var shellRegistry = testRegistry();
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    // synthetic display-only quota/context refresh (refreshUsage/pushUsageQuota at t0): even a
                    // NON-zero cumulative usage here must neither increment llmCalls nor add any token.
                    shellRegistry.onEvent(new AgentEvent.UsageUpdated(
                            new Usage(10, 20, 30, 1, 0, 0, 0), 100, 5.0, sessionId, eventWorkdir));
                    // a real request with NO usage reported must still count exactly once (skipping a
                    // zero-usage request on account of "no tokens" would be wrong).
                    shellRegistry.onEvent(
                            new AgentEvent.LlmRequestCompleted(Usage.EMPTY, "m", 1, sessionId, eventWorkdir));
                    return "ok";
                }
            };
            final var metricsFile = temp.resolve("metrics1.txt");
            final var command = new RunCommand(
                    new RunCommand.RunConf(
                            "say hi",
                            "",
                            "",
                            false,
                            "",
                            "collapsed",
                            metricsFile.toString(),
                            "",
                            "",
                            "",
                            1000000,
                            defaultCorrectionPrompt(),
                            null,
                            null),
                    shellRegistry,
                    runtimeFactory(agentLoop, new PermissionService(null, testWorkdir)),
                    sessionStore(),
                    null,
                    mcpManager(),
                    mapper,
                    new FeedbackGateProducer().feedbackGate());
            command.run();
            final var line = Files.readString(metricsFile, StandardCharsets.UTF_8);
            assertTrue(line.contains("llmCalls=1"), "display refresh must not count as a request: " + line);
            assertTrue(
                    line.contains("promptTokens=0 completionTokens=0 totalTokens=0"),
                    "display-only usage must not be aggregated: " + line);
        }

        @Test
        void nonZeroDisplayRefreshDoesNotDoubleCountAndCumulativeUsageAggregatesOnce() throws Exception {
            final var shellRegistry = testRegistry();
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    // two real logical requests carrying per-request usage...
                    shellRegistry.onEvent(new AgentEvent.LlmRequestCompleted(
                            new Usage(10, 20, 30, 1, 2, 3, 4), "m", 1, sessionId, eventWorkdir));
                    // ...with a display-only refresh re-publishing the same cumulative usage in between: this
                    // must add nothing (the OLD code added totalTokens=30 again as absolute + counted a call).
                    shellRegistry.onEvent(new AgentEvent.UsageUpdated(
                            new Usage(10, 20, 30, 1, 2, 3, 4), 100, 5.0, sessionId, eventWorkdir));
                    shellRegistry.onEvent(new AgentEvent.LlmRequestCompleted(
                            new Usage(5, 5, 10, 0, 0, 0, 0), "m", 2, sessionId, eventWorkdir));
                    return "ok";
                }
            };
            final var metricsFile = temp.resolve("metrics2.txt");
            final var command = new RunCommand(
                    new RunCommand.RunConf(
                            "say hi",
                            "",
                            "",
                            false,
                            "",
                            "collapsed",
                            metricsFile.toString(),
                            "",
                            "",
                            "",
                            1000000,
                            defaultCorrectionPrompt(),
                            null,
                            null),
                    shellRegistry,
                    runtimeFactory(agentLoop, new PermissionService(null, testWorkdir)),
                    sessionStore(),
                    null,
                    mcpManager(),
                    mapper,
                    new FeedbackGateProducer().feedbackGate());
            command.run();
            final var line = Files.readString(metricsFile, StandardCharsets.UTF_8);
            assertTrue(line.contains("llmCalls=2"), "only the real requests count, refresh excluded: " + line);
            assertTrue(
                    line.contains("promptTokens=15 completionTokens=25 totalTokens=40"),
                    "per-request usage aggregated once, the display refresh must not double-count: " + line);
        }
    }

    @Nested
    @FusionSupport(modules = CustomCorrectionModule.class)
    class CustomCorrection {
        @Test
        void invalidResultFailsAfterExactlyOneCorrection() throws Exception {
            final var result = Files.writeString(temp.resolve("invalid.json"), "{}");
            final var calls = new AtomicInteger();
            final var loop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    if (calls.incrementAndGet() == 2) {
                        assertEquals(
                                "custom correction: result requires a nonblank string field: summary|failure", prompt);
                    }
                    return "claimed success without artifact";
                }
            };
            final var command = new RunCommand(
                    new RunCommand.RunConf(
                            "task",
                            "",
                            "",
                            false,
                            "",
                            "hidden",
                            "",
                            "",
                            result.toString(),
                            "summary|failure",
                            1000000,
                            runConf.resultCorrectionPrompt(),
                            null,
                            null),
                    testRegistry(),
                    runtimeFactory(loop, new PermissionService(null, testWorkdir)),
                    sessionStore(),
                    null,
                    mcpManager(),
                    mapper,
                    new FeedbackGateProducer().feedbackGate());
            assertTrue(assertThrows(IllegalStateException.class, command::run)
                    .getMessage()
                    .contains("invalid after correction"));
            assertEquals(2, calls.get());
            assertEquals("{}", Files.readString(result), "the command must not fabricate result fields");
        }
    }

    public static class DefaultsModule extends ConfigurationTestModule {
        public DefaultsModule() {
            this(Map.of());
        }

        protected DefaultsModule(final Map<String, String> overrides) {
            super(fixtureValues(overrides));
        }

        private static Map<String, String> fixtureValues(final Map<String, String> overrides) {
            final var values = new HashMap<String, String>();
            values.put("yca.storageHome", testWorkdir.toString());
            values.put("yca.workingDir", testWorkdir.toString());
            values.put("yca.provider.baseUrl", "http://localhost:1/v1/chat/completions");
            values.put("yca.permissions.mode", "ask");
            values.put("yca.permissions.bash.maxCombined", "1024");
            values.put("run.prompt", "task");
            values.putAll(overrides);
            return values;
        }
    }

    public static class CustomCorrectionModule extends DefaultsModule {
        public CustomCorrectionModule() {
            super(Map.of("run.result-correction-prompt", "custom correction: {{{validationError}}}"));
        }
    }
}
