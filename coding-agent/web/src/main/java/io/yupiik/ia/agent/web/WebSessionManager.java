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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.notifications.NotificationService;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.ConversationTranscript;
import io.yupiik.ia.agent.core.shell.DiffViewMode;
import io.yupiik.ia.agent.core.shell.SessionLifecycle;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.shell.SessionRuntimeFactory;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellIO;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellRegistry;
import io.yupiik.ia.agent.core.shell.ShellSession;
import io.yupiik.ia.agent.core.shell.SpeakerLabels;
import io.yupiik.ia.agent.core.shell.TurnRunner;
import io.yupiik.ia.agent.core.shell.btw.BtwAnswerer;
import io.yupiik.ia.agent.core.shell.slash.ShellInputRouting;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Owns the sessions the web UI is driving. Attaching a session builds its
 * {@link SessionRuntime} (via the shared {@link SessionRuntimeFactory}) and
 * registers its {@link ShellEnv} in the shared {@link ShellRegistry}, so the
 * agent event routing works exactly like the TUI: each session's events reach
 * its {@link WebShellFrontend} and fan out to the SSE broker. Turns are
 * executed through the shared {@link TurnRunner}. Interactive gates
 * (approvals, ask_user questions, maven-index confirm) are pushed over SSE as
 * JSON-RPC requests and resolved by JSON-RPC responses arriving on
 * {@code POST /api/rpc}.
 */
@ApplicationScoped
public class WebSessionManager implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(WebSessionManager.class.getName());
    private static final Pattern BTW_COMMAND = Pattern.compile("(?is)^/btw(?:\\s.*)?$");

    /**
     * Upper bound on how long an interactive request (approval/question) waits for the browser
     * before it self-recovers as a rejected/cancelled answer. A lost or unanswered modal (browser
     * closed, SSE reconnect, missed resolve) then unblocks the turn promptly instead of pinning it
     * for many minutes.
     */
    private static final int RPC_TIMEOUT_MINUTES = 2;

    private final SessionRuntimeFactory sessionRuntimeFactory;
    private final ShellRegistry shellRegistry;
    private final WebSlashCommands slashRegistry;
    private final TurnRunner turnRunner;
    private final SessionStore sessionStore;
    private final SseBroker broker;
    private final JsonMapper mapper;
    private final SessionLifecycle sessionLifecycle;
    private final BtwAnswerer btwAnswerer;
    private final SpeakerLabels speakerLabels;
    private final ChatClient chatClient;
    private final NotificationService notifications;
    private final CodingAgentConfiguration configuration;

    private final ConcurrentHashMap<String, WebSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong rpcId = new AtomicLong();
    private volatile Boolean sandboxOverride;

    public WebSessionManager(
            final CodingAgentConfiguration configuration,
            final SessionRuntimeFactory sessionRuntimeFactory,
            final ShellRegistry shellRegistry,
            final WebSlashCommands slashRegistry,
            final TurnRunner turnRunner,
            final SessionStore sessionStore,
            final SessionLifecycle sessionLifecycle,
            final SseBroker broker,
            final JsonMapper mapper,
            final BtwAnswerer btwAnswerer) {
        this(
                configuration,
                sessionRuntimeFactory,
                shellRegistry,
                slashRegistry,
                turnRunner,
                sessionStore,
                sessionLifecycle,
                broker,
                mapper,
                btwAnswerer,
                null,
                null);
    }

    public WebSessionManager(
            final CodingAgentConfiguration configuration,
            final SessionRuntimeFactory sessionRuntimeFactory,
            final ShellRegistry shellRegistry,
            final WebSlashCommands slashRegistry,
            final TurnRunner turnRunner,
            final SessionStore sessionStore,
            final SessionLifecycle sessionLifecycle,
            final SseBroker broker,
            final JsonMapper mapper,
            final BtwAnswerer btwAnswerer,
            final SpeakerLabels speakerLabels,
            final ChatClient chatClient) {
        this(
                configuration,
                sessionRuntimeFactory,
                shellRegistry,
                slashRegistry,
                turnRunner,
                sessionStore,
                sessionLifecycle,
                broker,
                mapper,
                btwAnswerer,
                speakerLabels,
                chatClient,
                null);
    }

    public WebSessionManager(
            final CodingAgentConfiguration configuration,
            final SessionRuntimeFactory sessionRuntimeFactory,
            final ShellRegistry shellRegistry,
            final WebSlashCommands slashRegistry,
            final TurnRunner turnRunner,
            final SessionStore sessionStore,
            final SessionLifecycle sessionLifecycle,
            final SseBroker broker,
            final JsonMapper mapper,
            final BtwAnswerer btwAnswerer,
            final SpeakerLabels speakerLabels,
            final ChatClient chatClient,
            final NotificationService notifications) {
        this.notifications = notifications;
        this.configuration = configuration;
        this.sessionRuntimeFactory = sessionRuntimeFactory;
        this.shellRegistry = shellRegistry;
        this.slashRegistry = slashRegistry;
        this.turnRunner = turnRunner;
        this.sessionStore = sessionStore;
        this.sessionLifecycle = sessionLifecycle;
        this.broker = broker;
        this.mapper = mapper;
        this.btwAnswerer = btwAnswerer;
        this.speakerLabels = speakerLabels;
        this.chatClient = chatClient;
    }

    /** Sets the command-bound override before the web server accepts sessions. */
    public void sandboxOverride(final Boolean sandboxOverride) {
        this.sandboxOverride = sandboxOverride;
    }

    /** Whether a session is currently driven by the web server. */
    public boolean isAttached(final String sessionId) {
        return sessions.containsKey(sessionId);
    }

    /**
     * Attaches a session to the web UI when it is not already attached: creates its
     * runtime, registers its shell (so {@code ShellRegistry} routes its agent events
     * to the web frontend) and stores the per-session state. Idempotent - an already
     * attached session is returned as-is - so per-session state (agent mode, approval
     * level, model/provider selection, queued prompts, session grants) survives the
     * per-RPC attach calls of the prompt/slash endpoints instead of being wiped on
     * every round-trip (which made mode fall back to PLAN and the sidebar dropdowns
     * desync after each prompt or change).
     */
    public WebSession attach(final String sessionId) {
        return sessions.computeIfAbsent(sessionId, ignored -> createAttached(sessionId));
    }

    /**
     * Destructively rebuilds a session's runtime (closing the previous one). Only for
     * cases that intentionally reset per-session state, e.g. {@code session.setProject}
     * must re-resolve tools and system prompt against the new working directory.
     */
    public WebSession replace(final String sessionId) {
        detach(sessionId);
        return attach(sessionId);
    }

    private WebSession createAttached(final String sessionId) {
        final var workdir = sessionRuntimeFactory.resolveWorkdir(null, sessionId);
        final var runtime = sessionRuntimeFactory.create(sessionId, workdir, sandboxOverride);
        final var transcript = new ConversationTranscript();
        final var frontend = new WebShellFrontend(sessionId, broker, transcript, mapper);
        final var session = new ShellSession()
                .sessionId(sessionId)
                .runtime(runtime)
                .diffViewMode(
                        configuration.view() == null || configuration.view().diffViewMode() == null
                                ? DiffViewMode.AUTO
                                : configuration.view().diffViewMode());
        if (speakerLabels != null) {
            transcript.setFeedbackLabelResolver((identity, user, at) -> {
                final var settings = session.chatSettings();
                final var ctx = new SpeakerLabels.Ctx(
                                identity == null ? chatClient.model(settings) : identity.model(),
                                identity == null ? chatClient.providerId(settings) : identity.provider(),
                                identity == null ? session.activeAgent() : identity.agent(),
                                String.valueOf(runtime.permissions().mode()),
                                sessionId,
                                null)
                        .at(at);
                return user ? speakerLabels.userLabel(ctx) : speakerLabels.assistantLabel(ctx);
            });
        }
        final var io = new WebShellIO(session);
        final var env = new ShellEnv(frontend, io, session, transcript);
        sessionLifecycle.installGates(io.approvalGate(), io.feedbackGate(), runtime);
        shellRegistry.register(sessionId, env);
        return new WebSession(sessionId, runtime, env, transcript, io);
    }

    /** Detaches a session: unregisters its shell and closes its runtime. */
    public void detach(final String sessionId) {
        final var session = sessions.remove(sessionId);
        if (session != null) {
            session.close(shellRegistry);
        }
    }

    /** Submits a prompt for the session; {@code null} prompt means resume. */
    public CompletableFuture<Boolean> submit(final String sessionId, final String prompt, final String agentName) {
        final var session = sessions.get(sessionId);
        if (session == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("session not attached: " + sessionId));
        }
        if (prompt == null) {
            acknowledgeInteraction(sessionId);
            return submitPrompt(session, null, agentName);
        }
        acknowledgeInteraction(sessionId);
        return ShellInputRouting.resolve(prompt, slashRegistry.registry(), session.env.io())
                .thenCompose(decision -> switch (decision) {
                    case COMMAND -> slash(session, prompt.strip());
                    case PROMPT -> submitPrompt(session, prompt, agentName);
                    case CORRECT, CANCEL -> CompletableFuture.completedFuture(false);
                })
                .toCompletableFuture();
    }

    /** Routes composer input before dispatching a command or accepting a prompt. */
    public CompletableFuture<String> input(final String sessionId, final String text) {
        final var session = sessions.get(sessionId);
        if (session == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("session not attached: " + sessionId));
        }
        acknowledgeInteraction(sessionId);
        return ShellInputRouting.resolve(text, slashRegistry.registry(), session.env.io())
                .thenCompose(decision -> switch (decision) {
                    case COMMAND -> slash(session, text.strip()).thenApply(ignored -> "accepted");
                    case PROMPT -> {
                        // Do not keep the composer RPC open for the duration of the agent turn.
                        submitPrompt(session, text, null);
                        yield CompletableFuture.completedFuture("accepted");
                    }
                    case CORRECT -> CompletableFuture.completedFuture("correct");
                    case CANCEL -> CompletableFuture.completedFuture("cancel");
                })
                .toCompletableFuture();
    }

    private CompletableFuture<Boolean> submitPrompt(
            final WebSession session, final String prompt, final String agentName) {
        final var sessionId = session.sessionId;
        // a prompt arriving while a turn is running is queued (never starts a concurrent turn): the
        // running loop picks it up at its next checkpoint via the queuedPrompt supplier, mirroring
        // the TUI's pending-prompts queue. Slash commands above always run immediately.
        if (prompt != null && !prompt.isBlank() && session.env.session().busy()) {
            // surface the prompt immediately as a user turn in the UI, exactly like the persisted
            // replay does, so the browser transcript shows it live instead of waiting for the next
            // checkpoint to consume the queue (and, without it, never showing it until a reload).
            broadcastUserPrompt(sessionId, prompt.strip());
            session.enqueuePrompt(prompt.strip());
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }
        // the fresh-submit path surfaces the user turn the same way (see the comment above).
        if (prompt != null && !prompt.isBlank()) {
            broadcastUserPrompt(sessionId, prompt.strip());
        }
        session.cancelled.set(false);
        return turnRunner
                .submit(new TurnRunner.TurnRequest(
                        session.runtime.loop(),
                        session.env,
                        sessionId,
                        agentName,
                        prompt,
                        () -> sessionStore.load(sessionId),
                        session.cancelled::get,
                        session::pollPrompt))
                .toCompletableFuture();
    }

    /** Number of prompts currently queued for a session (0 when none); used to show a queue strip. */
    public int queuedPromptCount(final String sessionId) {
        final var session = sessions.get(sessionId);
        return session == null ? 0 : session.pendingPrompts.size();
    }

    /** The prompts currently queued for a running turn, oldest first (view; may be empty). */
    public List<String> queuedPrompts(final String sessionId) {
        final var session = sessions.get(sessionId);
        return session == null ? List.of() : session.pendingPromptsSnapshot();
    }

    /**
     * Removes the queued prompt at {@code index} (0 = oldest, the next one the turn would pick up).
     * Returns {@code false} when the session has no queue or the index is out of range.
     */
    public boolean removeQueuedPrompt(final String sessionId, final int index) {
        final var session = sessions.get(sessionId);
        return session != null && session.removePendingPrompt(index);
    }

    /**
     * Dispatches one slash command against a session and applies the resulting
     * {@link ShellOutcome} to the session's frontend, mirroring the CLI
     * {@code slash()} helper. Runs off-thread because interactive actions chain on
     * {@link ShellIO} stages (pickers/confirm) that complete when the browser answers
     * the pushed JSON-RPC modal.
     */
    private CompletableFuture<Boolean> slash(final WebSession session, final String text) {
        // /btw is handled by the web itself (async, non-blocking, in a latest-wins modal) instead of
        // the shared BtwSlashCommands which would block this RPC for the whole answer and render into
        // a "transcript" SSE event the browser never folds into blocks. Mirrors the TUI intercepting
        // /btw before the generic registry.
        final var trimmed = text.strip();
        if (BTW_COMMAND.matcher(trimmed).matches()) {
            return btw(session, trimmed.substring(4).strip());
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                final var outcome = slashRegistry
                        .dispatch(text, session.env)
                        .toCompletableFuture()
                        .join();
                outcome.applyTo(session.env.frontend());
                return true;
            } catch (final RuntimeException e) {
                final var cause = e.getCause() == null ? e : e.getCause();
                session.env
                        .frontend()
                        .error(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
                return false;
            }
        });
    }

    /**
     * Dispatches a /btw side question for a session: replaces any current btw (latest-wins slot -
     * a running one is cancelled, an answered one is overwritten) and answers it asynchronously so
     * this RPC returns immediately. The question/answer lifecycle is pushed to the browser as
     * {@code btw} events (running state first, answer/error when the answerer completes).
     */
    private CompletableFuture<Boolean> btw(final WebSession session, final String question) {
        if (question.isBlank()) {
            session.env.frontend().status("usage: /btw <question>");
            return CompletableFuture.completedFuture(false);
        }
        final var runtime = session.runtime;
        final var id = session.nextBtwId();
        final var cancelled = new AtomicBoolean(false);
        // override: cancel any in-flight answerer loop from a previous /btw of this session
        final var previous = session.btw;
        if (previous != null) {
            previous.cancelled().set(true);
            session.io.cancelPending(previous.id());
        }
        final var state = new BtwState(id, question, true, null, null, false, cancelled);
        synchronized (session) {
            session.btw = state;
        }
        broadcastBtw(session.sessionId, state);
        btwAnswerer
                .ask(
                        question,
                        session.sessionId(),
                        runtime.usage(),
                        () -> cancelled.get() || session.closed || session.runtime != runtime,
                        session.io.approvalGate(id, cancelled::get),
                        runtime.chatSettings(),
                        runtime.systemPromptBuilder(),
                        session.io.feedback(id, cancelled::get),
                        runtime.workdir(),
                        runtime.loop().effectiveToolRegistry(),
                        runtime.loop().availableToolNames(),
                        runtime.sandbox())
                .whenComplete((answer, error) -> onBtwAnswer(session, state, answer, error));
        return CompletableFuture.completedFuture(true);
    }

    private void onBtwAnswer(
            final WebSession session, final BtwState state, final BtwAnswerer.BtwAnswer answer, final Throwable error) {
        // stale: a newer /btw replaced this one, or the user cleared/closed it before it finished
        synchronized (session) {
            if (session.btw == null
                    || session.btw.id() != state.id()
                    || state.cancelled().get()) {
                return;
            }
            if (error != null) {
                final var cause = error.getCause() == null ? error : error.getCause();
                final var message =
                        cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                session.btw =
                        new BtwState(state.id(), state.question(), false, null, message, false, state.cancelled());
                broadcastBtw(session.sessionId, session.btw);
                return;
            }
            final var answerText =
                    answer == null || answer.text() == null || answer.text().isBlank() ? "(no answer)" : answer.text();
            final var retryable = answer != null && answer.retryable();
            session.btw =
                    new BtwState(state.id(), state.question(), false, answerText, null, retryable, state.cancelled());
            broadcastBtw(session.sessionId, session.btw);
        }
    }

    /** Clears the current btw of a session (the StatusBar "x"): cancels an in-flight answer too. */
    public void clearBtw(final String sessionId) {
        final var session = sessions.get(sessionId);
        if (session == null) {
            return;
        }
        BtwState cleared = null;
        synchronized (session) {
            final var current = session.btw;
            if (current != null) {
                current.cancelled().set(true);
                session.btw = null;
                cleared = current;
            }
        }
        // tell other tabs (and a snapshot-replay) that the pill/modal should disappear
        if (cleared != null) {
            session.io.cancelPending(cleared.id());
            final var data = new LinkedHashMap<String, Object>();
            data.put("id", cleared.id());
            data.put("cleared", true);
            broker.broadcast(
                    sessionId,
                    "event: agent\ndata: "
                            + mapper.toString(new WebEventDto("btw", sessionId, null, null, cleared.question(), data))
                            + "\n\n");
        }
    }

    /** The current /btw state of a session, or null when no btw is active/open (for session.btwClear). */
    public BtwState btwState(final String sessionId) {
        final var session = sessions.get(sessionId);
        return session == null ? null : session.btw;
    }

    private void broadcastBtw(final String sessionId, final BtwState state) {
        final var data = new LinkedHashMap<String, Object>();
        data.put("id", state.id());
        data.put("running", state.running());
        data.put("question", state.question());
        if (state.answer() != null) {
            data.put("answer", state.answer());
        }
        if (state.error() != null) {
            data.put("error", state.error());
        }
        if (state.retryable()) {
            data.put("retryable", true);
        }
        broker.broadcast(
                sessionId,
                "event: agent\ndata: "
                        + mapper.toString(new WebEventDto("btw", sessionId, null, null, state.question(), data))
                        + "\n\n");
    }

    /**
     * Broadcasts a submitted prompt to the session's live SSE subscribers as a {@code user} agent
     * event - the exact type {@link WebReplay} emits for a persisted user message - so the browser
     * transcript renders the prompt live (both when it starts a fresh turn and when it is queued
     * behind a running turn that absorbs it at its next checkpoint). The structural (never-droppable)
     * broadcast is a no-op when no tab is connected: the same prompt is replayed by the snapshot on
     * the next connect, so nothing is ever lost.
     */
    private void broadcastUserPrompt(final String sessionId, final String text) {
        broker.broadcast(
                sessionId,
                "event: agent\ndata: "
                        + mapper.toString(new WebEventDto("user", sessionId, null, null, text, Map.of()))
                        + "\n\n");
    }

    /**
     * Dispatches a bare slash command from the sidebar controls (e.g.
     * {@code /permissions yolo}, {@code /mode build}); never starts a turn.
     */
    public CompletableFuture<Boolean> slash(final String sessionId, final String text) {
        final var session = sessions.get(sessionId);
        if (session == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("session not attached: " + sessionId));
        }
        acknowledgeInteraction(sessionId);
        return slash(session, text);
    }

    /** Cancels the running turn of a session (TUI-Esc equivalent: also unblocks modal waits). */
    public void cancel(final String sessionId) {
        final var session = sessions.get(sessionId);
        if (session != null) {
            session.cancelled.set(true);
            // unblock any pending picker/approval/ask RPC the turn is awaiting as if the modal
            // was dismissed, so a Stop never leaves the turn hanging on an unanswered modal.
            session.io.cancelAllPending();
        }
    }

    /**
     * Cancels the running turn of a session AND drops its queued prompts, keeping the session
     * attached and resumable. This is the web's "close tab" equivalent of the TUI's closeTab, which
     * interrupts the in-flight turn (stop stream, kill tools/sub-agents) and clears the pending
     * queue so no absorbed follow-up resurrects the interrupted work. The cancelled flag is reset on
     * the next {@link #submit}, so a later reopen + new prompt is not spuriously cancelled.
     */
    public void cancelAll(final String sessionId) {
        cancel(sessionId);
        final var session = sessions.get(sessionId);
        if (session != null) {
            session.clearPendingPrompts();
        }
    }

    /** Resumes the last turn of a session (no new prompt). */
    public CompletableFuture<Boolean> resume(final String sessionId) {
        return submit(sessionId, null, null);
    }

    /** Resolves a pending interactive request (approval/question) over JSON-RPC. */
    public boolean resolve(final String sessionId, final String id, final Object result) {
        final var session = sessions.get(sessionId);
        if (session == null || session.io == null) {
            return false;
        }
        session.io.pendingLock.lock();
        try {
            final var pending = session.io.pendingRequests.get(id);
            if (pending != null && !pending.future().isDone()) {
                // Keep validation, acknowledgement and completion atomic with cancellation and
                // other answers. Completing the future can immediately publish the next request.
                acknowledgeInteraction(sessionId);
                return session.io.resolve(id, result);
            }
            // already resolved/completed (e.g. the turn was cancelled or the request timed out):
            // not a hard error, but log it so a spurious late resolve is diagnosable.
            LOGGER.fine(() -> "resolve for unknown pending request " + id + " on session " + sessionId);
            return false;
        } finally {
            session.io.pendingLock.unlock();
        }
    }

    private void acknowledgeInteraction(final String sessionId) {
        if (notifications != null) {
            notifications.onSessionInteraction(sessionId);
        }
    }

    /**
     * Registers before replaying outstanding interactive requests, so a concurrent publication is
     * delivered live or replayed (possibly both, with the same id). Never attaches another runtime
     * or broadcasts the replay to other tabs. The caller must enqueue its snapshot first.
     */
    public void subscribe(final String sessionId, final SseBroker.SseSubscription subscription) {
        broker.subscribe(sessionId, subscription);
        final var session = sessions.get(sessionId);
        if (session != null) {
            session.io.replayPending(subscription);
        }
    }

    @Override
    public void close() {
        sessions.values().forEach(s -> s.close(shellRegistry));
        sessions.clear();
    }

    /** Returns the attached session's runtime, attaching it first if needed. */
    public SessionRuntime runtime(final String sessionId) {
        final var existing = sessions.get(sessionId);
        if (existing != null) {
            return existing.runtime;
        }
        return attach(sessionId).runtime;
    }

    /**
     * Replays a session's persisted conversation as the {@code events} list of a {@code snapshot}
     * frame, so a fresh SSE connection seeds the browser transcript with its history. System-role
     * marker/notice messages are skipped (they are display-only and never part of the LLM wire);
     * {@code user} is surfaced as a {@code user} event, assistant text as {@code text-delta}, and
     * tool calls/results as {@code tool-started}/{@code tool-finished} blocks the frontend reducer
     * understands.
     *
     * @param sessionId the session whose persisted messages to replay
     * @return the replayed {@link WebEventDto}s in conversation order
     */
    public List<WebEventDto> replay(final String sessionId, final List<Message> messages) {
        return WebReplay.replay(sessionId, messages, speakerLabels);
    }

    public List<WebEventDto> snapshotFor(final String sessionId) {
        final var messages = sessionStore.load(sessionId);
        final var replay = replay(sessionId, messages);
        // a tab that connects mid-turn must learn the session is already busy (the persistable history
        // carries no live busy state): prepend a synthetic status frame the frontend's foldStatus folds
        // in, so the status bar shows "working" and the Stop button appears without re-running the turn.
        final var webSession = sessions.get(sessionId);
        // The per-session enrich (busy status, /btw reseed, usage/quota reseed) is best-effort and must
        // NEVER be allowed to throw into the SSE snapshot path: a failure here used to propagate out of
        // SseExchangeHandler.subscribe(), left the exchange open-but-silent and made the browser show
        // "Loading conversation…" forever. Surface any such failure loudly (SEVERE + session id + stack)
        // but always return the persisted replay so the snapshot still reaches the browser.
        try {
            if (webSession != null && webSession.env.session().busy()) {
                replay.add(0, new WebEventDto("status", sessionId, null, null, "working", Map.of("busy", true)));
            }
            // re-seed any live /btw (pending or answered) so a reload/reconnect keeps the pill + modal,
            // exactly like the synthetic busy status above carries the live turn state.
            if (webSession != null) {
                replay.add(new WebEventDto(
                        "settings",
                        sessionId,
                        null,
                        null,
                        null,
                        WebSlashCommands.displaySettings(webSession.env.session())));
                final var btw = webSession.btw;
                if (btw != null) {
                    replay.add(btwEvent(sessionId, btw));
                }
                // Persisted history carries no usage telemetry (WebReplay replays only user/text/tool
                // events), so a reloaded/reconnected tab would otherwise fold a log with NO token source
                // and the metrics bar renders empty until the next live usage-updated frame. Re-seed the
                // session's cumulative token totals as a synthetic usage-updated so the reloaded metrics
                // show the exact "in N · out N" (and don't freeze) immediately. Only emitted while the
                // session recorded any usage, so a genuinely fresh session shows nothing spurious.
                final var usage = webSession.runtime.usage();
                final var quota = webSession.runtime.quotaSnapshot();
                if (usage.completionTokens() > 0
                        || usage.reasoningTokens() > 0
                        || usage.promptTokens() > 0
                        || usage.contextInputTokens() > 0
                        || usage.cacheReadTokens() > 0
                        || quota.visible()) {
                    final var effective = usage.effectiveContext(messages, 0);
                    final var usagePayload = WebEventDto.of(new AgentEvent.UsageUpdated(
                            new Usage(
                                    (int) usage.promptTokens(),
                                    (int) usage.completionTokens(),
                                    (int) (usage.promptTokens() + usage.completionTokens()),
                                    usage.cacheReadTokens(),
                                    0,
                                    (int) usage.reasoningTokens(),
                                    0),
                            effective,
                            usage.contextPercent(effective),
                            0,
                            -1,
                            null,
                            sessionId,
                            null,
                            null,
                            quota,
                            usage.lastContextMeasurement(),
                            webSession.runtime.workdir()));
                    final var data = new LinkedHashMap<String, Object>(usagePayload.data());
                    // Keep the explicit cumulative full-input total, without narrowing it to Usage's int.
                    data.put("contextInputTokens", usage.contextInputTokens());
                    replay.add(new WebEventDto("usage-updated", sessionId, null, null, null, data));
                }
            }
        } catch (final RuntimeException | Error e) {
            LOGGER.log(
                    Level.SEVERE,
                    "snapshot enrich failed for session " + sessionId
                            + "; returning persisted replay only (the tab may miss live status/btw/usage)",
                    e);
        }
        return replay;
    }

    private WebEventDto btwEvent(final String sessionId, final BtwState state) {
        final var data = new LinkedHashMap<String, Object>();
        data.put("id", state.id());
        data.put("running", state.running());
        data.put("question", state.question());
        if (state.answer() != null) {
            data.put("answer", state.answer());
        }
        if (state.error() != null) {
            data.put("error", state.error());
        }
        if (state.retryable()) {
            data.put("retryable", true);
        }
        return new WebEventDto("btw", sessionId, null, null, state.question(), data);
    }

    /** Per-session state: runtime, env, transcript and live cancellation flag. */
    public static final class WebSession {
        private final String sessionId;
        private final SessionRuntime runtime;
        private final ShellEnv env;
        private final ConversationTranscript transcript;
        private final WebShellIO io;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        // prompts typed while a turn runs, drained by the running loop at its next checkpoint
        // (mirrors the TUI's pending-prompts queue). Guarded by its own monitor: RPC threads may
        // list/remove queued prompts while the running turn's loop polls it.
        private final Deque<String> pendingPrompts = new ArrayDeque<>();
        // the current /btw side question (latest-wins single slot), or null when none is active/open.
        // Guarded by the WebSession instance lock; never written from a turn thread without it.
        private volatile BtwState btw;
        // monotonic counter for btw slots, so a stale answerer completion can be discarded.
        private final AtomicLong btwOrdinal = new AtomicLong();
        private volatile boolean closed;

        private WebSession(
                final String sessionId,
                final SessionRuntime runtime,
                final ShellEnv env,
                final ConversationTranscript transcript,
                final WebShellIO io) {
            this.sessionId = sessionId;
            this.runtime = runtime;
            this.env = env;
            this.transcript = transcript;
            this.io = io;
        }

        public String sessionId() {
            return sessionId;
        }

        public SessionRuntime runtime() {
            return runtime;
        }

        public ShellEnv env() {
            return env;
        }

        public ConversationTranscript transcript() {
            return transcript;
        }

        /** The next /btw slot id for this session (monotonic; a new question always supersedes). */
        private long nextBtwId() {
            return btwOrdinal.incrementAndGet();
        }

        /** Enqueues a prompt to be absorbed by the running turn at its next checkpoint. */
        private void enqueuePrompt(final String prompt) {
            synchronized (pendingPrompts) {
                pendingPrompts.addLast(prompt);
            }
        }

        /** Polls the next queued prompt for the running turn to absorb, or {@code null}. */
        private String pollPrompt() {
            synchronized (pendingPrompts) {
                return pendingPrompts.pollFirst();
            }
        }

        /** Snapshot of the queued prompts, oldest first (for the UI queue manager). */
        private List<String> pendingPromptsSnapshot() {
            synchronized (pendingPrompts) {
                return List.copyOf(pendingPrompts);
            }
        }

        /** Removes the queued prompt at {@code index} (oldest first); {@code false} when out of range. */
        private boolean removePendingPrompt(final int index) {
            synchronized (pendingPrompts) {
                if (index < 0 || index >= pendingPrompts.size()) {
                    return false;
                }
                final var it = pendingPrompts.iterator();
                // arrive ON the element at `index` (next() must be called at least once before
                // remove() -- ArrayDeque$DeqIterator.remove() throws IllegalStateException otherwise,
                // which broke removing the oldest queued prompt, index 0)
                for (int i = 0; i <= index; i++) {
                    it.next();
                }
                it.remove();
                return true;
            }
        }

        /** Drops every queued prompt (mirrors the TUI's interruptTurn clearing its pending queue). */
        private void clearPendingPrompts() {
            synchronized (pendingPrompts) {
                pendingPrompts.clear();
            }
        }

        private void close(final ShellRegistry registry) {
            if (closed) {
                return;
            }
            closed = true;
            registry.unregister(sessionId, env);
            // stop the running turn FIRST: the turn unwinds within ~100ms once the probe reads true
            // (LlmWire.pump polls the probe on every frame and in a 100ms bounded-wait loop), which
            // releases the shared executor's virtual thread so its graceful close at container shutdown
            // cannot block on a turn that never ends.
            cancelled.set(true);
            // stop any in-flight /btw answer too, so the shared executor's btw thread unwinds instead
            // of keeping a dangling reference to this closing session.
            final var btw = this.btw;
            if (btw != null) {
                btw.cancelled().set(true);
            }
            io.cancelAllPending();
            runtime.close();
        }
    }

    /**
     * Immutable snapshot of a /btw side question slot: {@code running} is true from the moment the
     * question is asked until the answerer completes; then either {@code answer} or {@code error}
     * carries the outcome. {@code cancelled} lets a newer /btw, a clear, or a session close stop the
     * in-flight answerer loop (its completion is then discarded by the stale-id/cancelled guard).
     */
    record BtwState(
            long id,
            String question,
            boolean running,
            String answer,
            String error,
            boolean retryable,
            AtomicBoolean cancelled) {}

    /** {@link ShellIO} bridging interactive requests (approval/question) to JSON-RPC over SSE. */
    private final class WebShellIO implements ShellIO {
        private final ShellSession session;
        private final ReentrantLock pendingLock = new ReentrantLock();
        private final Map<String, PendingRequest> pendingRequests = new LinkedHashMap<>();

        private record PendingRequest(Map<String, Object> payload, CompletableFuture<Object> future, long owner) {}

        private WebShellIO(final ShellSession session) {
            this.session = session;
        }

        @Override
        public CompletableFuture<Optional<String>> readLine(final String prompt) {
            return askRpc(Map.of(
                            "questions",
                            List.of(Map.of(
                                    "id",
                                    "input",
                                    "prompt",
                                    prompt == null ? "" : prompt,
                                    "options",
                                    List.of(),
                                    "multiple",
                                    false))))
                    .thenApply(value -> value instanceof String text ? Optional.of(text) : Optional.empty());
        }

        @Override
        public CompletableFuture<Boolean> confirm(final String question) {
            return askRpc(Map.of("prompt", question)).thenApply(value -> Boolean.TRUE.equals(value));
        }

        @Override
        public CompletableFuture<Optional<String>> pick(final String title, final List<String> options) {
            final var params = new LinkedHashMap<String, Object>();
            params.put("prompt", title);
            params.put("options", new ArrayList<>(options));
            return askRpc(params).thenApply(v -> v instanceof String s ? Optional.of(s) : Optional.empty());
        }

        @Override
        public ApprovalGate approvalGate() {
            return approvalGate(0, () -> false);
        }

        private ApprovalGate approvalGate(final long owner, final BooleanSupplier cancelled) {
            return request -> {
                final var title = request.title();
                final var detail = request.detail();
                final var toolName = request.toolName();
                final var args = request.args();
                final var result = awaitRpc(
                        Map.of(
                                "method",
                                "session.approval",
                                "params",
                                Map.of(
                                        "origin", request.origin().name(),
                                        "tool", toolName == null ? "" : toolName,
                                        "args", args == null ? Map.of() : args,
                                        "title", title == null ? "" : title,
                                        "detail", detail == null ? "" : detail)),
                        owner,
                        cancelled);
                if (result instanceof String s) {
                    return switch (s.toLowerCase(Locale.ROOT)) {
                        case "allow_once" -> ApprovalGate.Result.ALLOW_ONCE;
                        case "allow_session" -> ApprovalGate.Result.ALLOW_SESSION;
                        default -> ApprovalGate.Result.DENY;
                    };
                }
                return ApprovalGate.Result.DENY;
            };
        }

        @Override
        public FeedbackGate feedbackGate() {
            return feedbackGate(0, () -> false);
        }

        private UserFeedbackService feedback(final long owner, final BooleanSupplier cancelled) {
            final var service = new UserFeedbackService();
            service.setGate(feedbackGate(owner, cancelled));
            return service;
        }

        private FeedbackGate feedbackGate(final long owner, final BooleanSupplier cancelled) {
            return questions -> {
                final var serialized = questions.stream()
                        .map(q -> Map.of(
                                "id", q.id(),
                                "prompt", q.prompt(),
                                "options", q.options() == null ? List.of() : q.options(),
                                "multiple", q.multiple()))
                        .collect(Collectors.toList());
                final var result = awaitRpc(
                        Map.of("method", "session.ask", "params", Map.of("questions", serialized)), owner, cancelled);
                if (result == null) {
                    // the user rejected/cancelled the ask (modal Cancel or Stop): report a clean
                    // "cancelled" answer so the tool round is a success the model can act on
                    // instead of collapsing to a null/headless error that makes the turn spin.
                    return Map.of("cancelled", true);
                }
                @SuppressWarnings("unchecked")
                final var answers = result instanceof Map<?, ?> m
                        ? (Map<String, Object>) m
                        : Collections.<String, Object>emptyMap();
                return answers.isEmpty() ? null : answers;
            };
        }

        private Object awaitRpc(final Map<String, Object> request, final long owner, final BooleanSupplier cancelled) {
            final var id = rpcId.incrementAndGet();
            final var future = registerRpc(
                    String.valueOf(id),
                    Map.of(
                            "jsonrpc",
                            "2.0",
                            "id",
                            id,
                            "method",
                            request.get("method"),
                            "params",
                            request.get("params")),
                    owner,
                    cancelled);
            try {
                return future.get(RPC_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.fine(() -> "interrupted awaiting " + request.get("method") + " (" + id + ")");
                return null;
            } catch (final ExecutionException | TimeoutException e) {
                LOGGER.fine(() -> request.get("method") + " (" + id + ") unresolved after " + RPC_TIMEOUT_MINUTES
                        + " min; treating as rejected");
                return null;
            } finally {
                resolve(String.valueOf(id), null);
            }
        }

        private CompletableFuture<Object> askRpc(final Map<String, Object> params) {
            final var id = rpcId.incrementAndGet();
            final var future = registerRpc(
                    String.valueOf(id),
                    Map.of("jsonrpc", "2.0", "id", id, "method", "session.question", "params", params),
                    0,
                    () -> false);
            return future;
        }

        private CompletableFuture<Object> registerRpc(
                final String id, final Map<String, Object> payload, final long owner, final BooleanSupplier cancelled) {
            final var future = new CompletableFuture<Object>();
            pendingLock.lock();
            try {
                final var attached = sessions.get(session.sessionId());
                if (cancelled.getAsBoolean() || (attached != null && attached.closed)) {
                    future.complete(null);
                    return future;
                }
                pendingRequests.put(id, new PendingRequest(payload, future, owner));
                future.whenComplete((_, _) -> {
                    pendingLock.lock();
                    try {
                        pendingRequests.remove(id);
                    } finally {
                        pendingLock.unlock();
                    }
                });
                broker.pushRpc(session.sessionId(), payload);
                // Stamp the pending notification before an answer can acknowledge it.
                if (notifications != null && !future.isDone() && !cancelled.getAsBoolean()) {
                    final var title = sessionStore == null ? "" : sessionStore.summary(session.sessionId());
                    final var source = owner == 0 ? "main" : "btw";
                    final var params = (Map<?, ?>) payload.get("params");
                    if ("session.approval".equals(payload.get("method"))) {
                        notifications.onApprovalRequired(
                                session.sessionId(),
                                title,
                                (String) params.get("tool"),
                                source,
                                ApprovalGate.Origin.valueOf((String) params.get("origin")));
                    } else {
                        final var questions = params.get("questions");
                        notifications.onInputRequired(
                                session.sessionId(),
                                title,
                                questions instanceof List<?> list ? list.size() : 1,
                                source);
                    }
                }
            } finally {
                pendingLock.unlock();
            }
            return future;
        }

        private void cancelPending(final long owner) {
            final var cancelled = new ArrayList<Map<String, Object>>();
            pendingLock.lock();
            try {
                for (final var pending : List.copyOf(pendingRequests.values())) {
                    if (pending.owner() == owner) {
                        pending.future().complete(null);
                        cancelled.add(Map.of(
                                "jsonrpc",
                                "2.0",
                                "id",
                                pending.payload().get("id"),
                                "method",
                                "session.requestCancelled",
                                "params",
                                Map.of()));
                    }
                }
            } finally {
                pendingLock.unlock();
            }
            cancelled.forEach(payload -> broker.pushRpc(session.sessionId(), payload));
        }

        private boolean resolve(final String id, final Object result) {
            pendingLock.lock();
            try {
                final var pending = pendingRequests.remove(id);
                return pending != null && pending.future().complete(result);
            } finally {
                pendingLock.unlock();
            }
        }

        private void replayPending(final SseBroker.SseSubscription subscription) {
            pendingLock.lock();
            try {
                for (final var pending : List.copyOf(pendingRequests.values())) {
                    if (!pending.future().isDone()) {
                        subscription.push(broker.rpcFrame(pending.payload()));
                    }
                }
            } finally {
                pendingLock.unlock();
            }
        }

        private void cancelAllPending() {
            pendingLock.lock();
            try {
                final var pending = List.copyOf(pendingRequests.values());
                pendingRequests.clear();
                pending.forEach(p -> p.future().complete(null));
            } finally {
                pendingLock.unlock();
            }
        }
    }
}
