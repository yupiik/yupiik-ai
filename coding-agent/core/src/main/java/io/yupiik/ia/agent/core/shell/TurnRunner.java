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
package io.yupiik.ia.agent.core.shell;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import io.yupiik.ia.agent.core.llm.CodexLoginService;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.llm.client.codex.CodexNotAuthenticatedException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The single turn execution path shared by every shell: maven-index gating
 * through {@link ShellIO#confirm}, history loading, begin/end turn
 * notifications, busy tracking and the agent loop invocation. Shells only
 * build a {@link TurnRequest} and chain on the returned stage - CLI joins it,
 * TUI chains it on the UI thread. The loop to run is carried by the request
 * (not injected): each session owns its own {@link AgentLoop} so concurrent
 * tabs keep their per-turn state, permissions and metrics apart.
 */
@ApplicationScoped
public class TurnRunner {
    private final MavenRepositoryKnowledgeService mavenKnowledgeService;
    private final ExecutorService executor;
    private final AgentSessionService agentSessionService;
    private final SubagentRegistry subagentRegistry;
    private final CodexLoginService codexLoginService;

    public TurnRunner(
            final MavenRepositoryKnowledgeService mavenKnowledgeService,
            final ExecutorService executor,
            final AgentSessionService agentSessionService,
            final SubagentRegistry subagentRegistry,
            final CodexLoginService codexLoginService) {
        this.mavenKnowledgeService = mavenKnowledgeService;
        this.executor = executor;
        this.agentSessionService = agentSessionService;
        this.subagentRegistry = subagentRegistry;
        this.codexLoginService = codexLoginService;
    }

    public TurnRunner(
            final MavenRepositoryKnowledgeService mavenKnowledgeService,
            final ExecutorService executor,
            final AgentSessionService agentSessionService,
            final SubagentRegistry subagentRegistry) {
        this(mavenKnowledgeService, executor, agentSessionService, subagentRegistry, null);
    }

    public TurnRunner(
            final MavenRepositoryKnowledgeService mavenKnowledgeService,
            final ExecutorService executor,
            final AgentSessionService agentSessionService) {
        this(mavenKnowledgeService, executor, agentSessionService, null, null);
    }

    protected TurnRunner() {
        this.mavenKnowledgeService = null;
        this.executor = null;
        this.agentSessionService = null;
        this.subagentRegistry = null;
        this.codexLoginService = null;
    }

    /**
     * A turn to run: {@code prompt == null} means a resume (re-runs the agent
     * loop on the persisted history without sending any message).
     *
     * @param queuedPrompt when non-null the running loop polls this source between iterations and
     *                     absorbs each returned prompt into the current turn instead of starting a
     *                     new one; the shell owns the queue backing it.
     */
    public record TurnRequest(
            AgentLoop loop,
            ShellEnv env,
            String sessionId,
            String agentName,
            String prompt,
            Supplier<List<Message>> history,
            BooleanSupplier cancelled,
            Supplier<String> queuedPrompt) {

        public TurnRequest(
                final AgentLoop loop,
                final ShellEnv env,
                final String sessionId,
                final String agentName,
                final String prompt,
                final Supplier<List<Message>> history,
                final BooleanSupplier cancelled) {
            this(loop, env, sessionId, agentName, prompt, history, cancelled, null);
        }
    }

    /**
     * Runs the turn after the maven-index gate. Returns {@code false} without
     * executing anything when the user declined to send while indexing is
     * still in flight; the caller owns the wording of that notice.
     */
    public CompletionStage<Boolean> submit(final TurnRequest request) {
        final var runtime = request.env().session().runtime();
        final var root = runtime.workdir();
        final var run = mavenKnowledgeService.currentIndex(root);
        if (run == null || !run.inFlight()) {
            return execute(request);
        }
        final var env = request.env();
        return env.io()
                .confirm("maven knowledge index still running (" + run.doneCount() + "/" + run.total()
                        + ") - send the prompt anyway?")
                .thenCompose(sendAnyway -> Boolean.TRUE.equals(sendAnyway)
                        ? execute(request)
                        : CompletableFuture.completedStage(Boolean.FALSE));
    }

    private CompletionStage<Boolean> execute(final TurnRequest request) {
        final var env = request.env();
        final var loop = request.loop();
        // a turn always carries a real probe (a shell with no interrupt flag passes
        // Cancellations.NEVER): asserted here, at the boundary between the shell and the loop
        final var cancelled = Objects.requireNonNull(request.cancelled(), "cancelled");
        final var history = request.history().get();
        env.frontend().beginTurn();
        env.session().busy(true);
        final var done = new CompletableFuture<Boolean>();
        executor.execute(() -> {
            final var cancellation = new TurnCancellation(cancelled, request.sessionId());
            Throwable failure = null;
            try {
                runTurnOrLogin(request, loop, history, cancellation, env);
            } catch (final Throwable t) {
                failure = t;
            } finally {
                cancellation.close();
                // an interrupt also stops the sub-agents this session spawned: their probe is
                // latched here, while the flag still reads true - a child polling after the next
                // turn reset it would otherwise never see the cancel
                if (subagentRegistry != null && cancellation.getAsBoolean()) {
                    subagentRegistry.cancelByParentSession(request.sessionId());
                }
                // a mode switch queued while the turn was running is applied now, before the next turn
                // can start: the current turn kept its own mode and permissions throughout
                final var pending = env.session().pendingMode();
                env.session().pendingMode(null);
                if (pending != null && !pending.isBlank() && agentSessionService != null) {
                    try {
                        final var result = agentSessionService.setMode(
                                pending,
                                env.session().sessionId(),
                                env.session().runtime());
                        env.frontend().notice(AgentEvent.Notice.Level.INFO, result.message());
                    } catch (final RuntimeException e) {
                        env.frontend().notice(AgentEvent.Notice.Level.ERROR, "mode switch failed: " + e.getMessage());
                    }
                }
                env.session().busy(false);
                env.frontend().endTurn();
                // completing last so awaiters observe the turn fully finished
                if (failure == null) {
                    done.complete(Boolean.TRUE);
                } else {
                    done.completeExceptionally(failure);
                }
            }
        });
        return done;
    }

    /** A scoped watcher wakes blocking tools as well as code which polls the turn probe. */
    private final class TurnCancellation implements BooleanSupplier, AutoCloseable {
        private final BooleanSupplier source;
        private final AtomicBoolean latched = new AtomicBoolean();
        private final CountDownLatch finished = new CountDownLatch(1);
        private final Thread watcher;

        private TurnCancellation(final BooleanSupplier source, final String sessionId) {
            this.source = source;
            final var worker = Thread.currentThread();
            watcher = Thread.ofVirtual().name("turn-cancellation").start(() -> {
                try {
                    while (!finished.await(50, TimeUnit.MILLISECONDS)) {
                        if (getAsBoolean()) {
                            if (subagentRegistry != null) {
                                subagentRegistry.cancelByParentSession(sessionId);
                            }
                            worker.interrupt();
                            return;
                        }
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        @Override
        public boolean getAsBoolean() {
            if (source.getAsBoolean()) {
                latched.set(true);
            }
            return latched.get();
        }

        @Override
        public void close() {
            finished.countDown();
            var interrupted = Thread.interrupted();
            // Join before returning the worker to its executor: a late watcher must never
            // interrupt another task, nor interrupt the endTurn/next-turn callbacks.
            while (watcher.isAlive()) {
                try {
                    watcher.join();
                } catch (final InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted && !latched.get()) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Runs a turn, auto-completing the interactive Codex login on first use and retrying once. */
    private void runTurnOrLogin(
            final TurnRequest request,
            final AgentLoop loop,
            final List<Message> history,
            final BooleanSupplier cancelled,
            final ShellEnv env) {
        var codexRetried = false;
        while (true) {
            try {
                runTurnBody(request, loop, history, cancelled);
                return;
            } catch (final CodexNotAuthenticatedException notAuthed) {
                if (codexRetried || codexLoginService == null) {
                    throw notAuthed;
                }
                codexRetried = true;
                codexLoginService.signIn(
                        notAuthed.provider(),
                        false,
                        url -> {
                            env.frontend().openExternalUrl(url);
                            env.frontend()
                                    .transcript("Sign in to use this Codex provider: authorize at\n\n<" + url
                                            + ">\n\nWaiting for authorization; you can close the tab when done...");
                        },
                        _ -> cancelled.getAsBoolean());
                // login completed: retry the turn body once
            }
        }
    }

    /** The raw agent-loop invocation for one turn (prompt or resume). */
    private void runTurnBody(
            final TurnRequest request,
            final AgentLoop loop,
            final List<Message> history,
            final BooleanSupplier cancelled)
            throws CodexNotAuthenticatedException {
        if (request.prompt() == null) {
            loop.resume(request.agentName(), request.sessionId(), history, cancelled, request.queuedPrompt());
        } else {
            loop.run(
                    request.prompt(),
                    request.agentName(),
                    request.sessionId(),
                    history,
                    cancelled,
                    request.queuedPrompt());
        }
    }
}
