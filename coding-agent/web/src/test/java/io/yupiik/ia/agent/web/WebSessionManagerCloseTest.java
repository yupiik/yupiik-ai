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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.context.BackoffSleeper;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.SessionLifecycle;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.shell.SessionRuntimeFactory;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellRegistry;
import io.yupiik.ia.agent.core.shell.TurnRunner;
import io.yupiik.ia.agent.core.shell.btw.BtwAnswerer;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies the web's "close tab" semantics ({@code session.cancelAll}) align on the TUI's
 * closeTab: the running turn is interrupted (the {@code cancelled} supplier the {@code TurnRunner}
 * polls flips to true), the queued prompts are dropped so no absorbed follow-up resurrects the
 * interrupted work, and the session itself stays attached and resumable. Also guards the contrast
 * with the plain {@code session.cancel} (Stop button), which interrupts the turn but keeps the
 * queue intact.
 */
@ExtendWith(TestWebJsonMapper.class)
@FusionSupport(modules = WebSessionManagerCloseTest.SessionModule.class)
@Timeout(30)
class WebSessionManagerCloseTest {

    @TempDir
    static Path storageHome;

    public static class SessionModule extends WebConfigurationTestModule {
        public SessionModule() {
            super(Map.of("yca.storageHome", storageHome.toString()));
        }
    }

    private JsonMapper mapper;
    private WebSessionManager manager;
    private SseBroker broker;
    private CancelCapturingTurnRunner turnRunner;
    private final List<Boolean> sandboxOverrides = new ArrayList<>();

    @BeforeEach
    void setup(final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
        this.mapper = mapper;
        final var sessionStore = new SessionStore() {};
        final var runtimeFactory =
                new SessionRuntimeFactory(
                        null,
                        null,
                        null,
                        configuration,
                        null,
                        sessionStore,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        this.mapper,
                        null,
                        Clock.systemUTC(),
                        new PrecisionDurationMarkerProvider(),
                        new BackoffSleeper(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null) {
                    @Override
                    public SessionRuntime create(
                            final String sessionId, final Path workdir, final Boolean sandboxOverride) {
                        sandboxOverrides.add(sandboxOverride);
                        return super.create(sessionId, workdir, sandboxOverride);
                    }
                };
        this.turnRunner = new CancelCapturingTurnRunner();
        this.broker = new SseBroker(this.mapper);
        this.manager = new WebSessionManager(
                configuration,
                runtimeFactory,
                new NoopShellRegistry(),
                new WebSlashCommands(new SlashRegistry() {}, null, sessionStore),
                turnRunner,
                sessionStore,
                new SessionLifecycle(sessionStore),
                broker,
                this.mapper,
                new BtwAnswerer(
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
    }

    @AfterEach
    void cleanup() {
        try {
            if (manager != null) {
                manager.close();
            }
        } finally {
            if (broker != null) {
                broker.close();
            }
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(booleans = {false, true})
    void commandOverrideReachesEveryAttachedAndReplacedRuntime(final Boolean sandbox) {
        // Stop startup after command configuration is applied, before opening any HTTP server.
        final var stopped = new IllegalStateException("stop before HTTP startup");
        final var mcp = new McpManager(null, mapper, List.of(), null, null) {
            @Override
            public void start() {
                throw stopped;
            }
        };
        final var command = new WebCommand(
                new WebCommand.WebConf("127.0.0.1", 0, false, true, false, List.of(), 1024, sandbox),
                broker,
                manager,
                null,
                null,
                mapper,
                null,
                mcp);
        assertEquals(stopped, assertThrows(IllegalStateException.class, command::run));

        manager.attach("s1");
        manager.attach("s1"); // idempotent attachment must not allocate another runtime
        manager.replace("s1");
        manager.attach("s2");

        assertEquals(Arrays.asList(sandbox, sandbox, sandbox), sandboxOverrides);
    }

    /** Idle submit starts a turn; busy()=true then queues follow-ups behind it. */
    private void startTurnWithQueued(String... queued) {
        manager.attach("s1");
        manager.submit("s1", "main", null); // idle -> starts a turn, captured by turnRunner
        manager.attach("s1").env().session().busy(true); // simulate the running turn
        for (final var q : queued) {
            manager.submit("s1", q, null); // busy -> queued
        }
    }

    @Test
    void cancelAllInterruptsTheTurnAndClearsTheQueueKeepingTheSessionAttached() {
        startTurnWithQueued("q1", "q2");
        assertNotNull(turnRunner.cancelled, "the running turn must expose a cancelled supplier");
        assertEquals(2, manager.queuedPromptCount("s1"));
        assertTrue(manager.isAttached("s1"));

        manager.cancelAll("s1");

        // the running turn received the interrupt signal (TUI closeTab equivalent)
        assertTrue(turnRunner.cancelled.getAsBoolean(), "cancelAll must interrupt the running turn");
        // every queued follow-up was dropped, so nothing can resurrect the interrupted work
        assertEquals(0, manager.queuedPromptCount("s1"), "cancelAll must clear the queued prompts");
        // the session is still attached and resumable, unlike a destructive delete/detach
        assertTrue(manager.isAttached("s1"), "cancelAll must not detach the session");
    }

    @Test
    void plainCancelInterruptsTheTurnButKeepsTheQueue() {
        startTurnWithQueued("q1", "q2");
        assertEquals(2, manager.queuedPromptCount("s1"));
        assertTrue(manager.isAttached("s1"));

        manager.cancel("s1");

        // Stop button semantics: interrupts the running turn but preserves the queued prompts
        assertTrue(turnRunner.cancelled.getAsBoolean(), "cancel must interrupt the running turn");
        assertEquals(2, manager.queuedPromptCount("s1"), "plain cancel must NOT clear the queued prompts");
        assertTrue(manager.isAttached("s1"));
    }

    @Test
    void cancelAllOnAnIdleSessionIsSafeAndKeepsItAttached() {
        manager.attach("s1");
        assertEquals(0, manager.queuedPromptCount("s1"));
        assertTrue(manager.isAttached("s1"));

        manager.cancelAll("s1");

        // no turn, no queue: still a no-op that leaves the session resumable (flag resets on submit)
        assertTrue(manager.isAttached("s1"));
        assertEquals(0, manager.queuedPromptCount("s1"));
    }

    private static final class NoopShellRegistry extends ShellRegistry {
        private NoopShellRegistry() {
            super(null);
        }

        @Override
        public void register(final String sessionId, final ShellEnv env) {
            // no-op: routing is not exercised by these units
        }

        @Override
        public void unregister(final String sessionId, final ShellEnv env) {
            // no-op
        }
    }

    /** Records the {@code cancelled} supplier of the running turn without executing it. */
    private static final class CancelCapturingTurnRunner extends TurnRunner {
        private volatile BooleanSupplier cancelled;

        private CancelCapturingTurnRunner() {
            super();
        }

        @Override
        public CompletionStage<Boolean> submit(final TurnRequest request) {
            this.cancelled = request.cancelled();
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }
    }
}
