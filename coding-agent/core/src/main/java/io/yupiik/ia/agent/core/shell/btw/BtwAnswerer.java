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
package io.yupiik.ia.agent.core.shell.btw;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.SystemPromptBuilder;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.BtwConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.events.EventContext;
import io.yupiik.ia.agent.core.events.HookResult;
import io.yupiik.ia.agent.core.events.ToolUsePreEvent;
import io.yupiik.ia.agent.core.events.hooks.EventContextBuilder;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.InBandToolCallExtractor;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ProviderConcurrencyGates;
import io.yupiik.ia.agent.core.llm.Tool;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.llm.ToolCallPairingRepair;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.sandbox.Sandbox;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Answers /btw side questions out of band: a bounded read-only tool loop over the inherited
 * session history, gated by the plan-mode permissions and the session approval gate. Tools
 * are limited to the read-only category plus the bash tool evaluated against the plan-mode
 * command rules (never inheriting the session permission mode). Nothing is written to the session store,
 * so the main conversation and its persistence are never affected.
 */
@ApplicationScoped
public class BtwAnswerer {
    static final int MAX_RECALL_EXCHANGES = 20;

    /** Bound on the model round-trips of a single /btw answer, tool round-trips included. */
    static final int MAX_TOOL_ROUNDS = 6;

    /**
     * Tool-less prompt used when {@code yca.slashcommands.btw.tools} is off (the default) and no session
     * {@link SystemPromptBuilder} is available (headless/unit paths): answer from context only. When a
     * session system prompt is provided the request reuses it for the cache prefix instead and the same
     * direction travels in the final user message via {@link #USER_DIRECTION}.
     */
    static final String TOOL_LESS_SYSTEM_PROMPT =
            "You answer quick side questions ('by the way') asked while a coding agent session is running. "
                    + "The session conversation is provided for context; the reply being streamed, if any, is not part of it. "
                    + "Answer directly and concisely in markdown from what you already know. "
                    + "Do not call any tools and do not take any actions. "
                    + "Never write 'Let me check...', 'I'll look it up...', or announce a verification step. "
                    + "If answering would need reading a file or an external source, say it cannot be checked from a side "
                    + "question and answer with what you know (or say you do not know); do not offer to investigate.";

    /**
     * Answering direction appended to the /btw user prompt when the request runs with the session's own
     * system prompt (cache-consistent path): the system prompt does not carry /btw intent, so the rule
     * lives in the changing tail where {@code applyCaching} already places a breakpoint.
     */
    static final String USER_DIRECTION =
            "Answer this quick side question directly in a single response, from what you already know. "
                    + "You are NOT to call any tool, read any file, run any command, or take any action; nothing you attempt "
                    + "here is executed. Never write 'Let me check...', 'I'll look it up...', or announce a verification step. "
                    + "If answering would need reading a file or an external source, say it cannot be checked from a side "
                    + "question and answer with what you know (or say you do not know); do not offer to investigate. "
                    + "Do not write tool-call markup as text.";

    /** Shown when the model spent the answer trying to call a tool it was never allowed to execute. */
    static final String DANGLING_TOOL_ANSWER_NOTE =
            "> The model tried to call a tool instead of answering this side question directly; no tool was executed and the "
                    + "placeholder above is what it left behind. Ask in the main conversation or retry.";

    /** Tooled prompt used when {@code yca.slashcommands.btw.tools} is on: may inspect the project like plan mode. */
    static final String TOOLED_SYSTEM_PROMPT =
            "You answer quick side questions ('by the way') asked while a coding agent session is running. "
                    + "The session conversation is provided for context; the reply being streamed, if any, is not part of it. "
                    + "You can use the declared read-only tools (file/command readers, searches, and the read-only shell "
                    + "commands allowed in plan mode) to inspect the project when it helps answering, exactly like plan mode. "
                    + "You cannot modify anything: write/edit tools are never available and shell commands are restricted to "
                    + "the plan-mode read-only allow-list - a refused or failed tool call just means it is not allowed, so "
                    + "fall back to what you can read or to your own knowledge. Answer concisely and in markdown, and never "
                    + "claim to have executed anything you did not. You may use ask_user when you need clarification.";

    private final ChatClient chatClient;
    private final ExtendedHttpClient httpClient;
    private final SessionStore sessionStore;
    private final CodingAgentConfiguration configuration;
    private final ExecutorService executor;
    private final InBandToolCallExtractor inBandToolCallExtractor;
    private final ToolRegistry toolRegistry;
    private final ToolExecutionService toolExecutionService;
    private final JsonMapper mapper;
    private final ProviderManager providerManager;
    private final Clock clock;
    private final Emitter emitter;
    private final EventContextBuilder eventContextBuilder;
    private final ToolCallPairingRepair pairingRepair;
    private final Deque<BtwExchange> exchanges = new ConcurrentLinkedDeque<>();
    /**
     * Concurrency gates shared with the main client: a /btw question is issued while the main turn may
     * still be streaming, so giving the dedicated client its own budget would double the provider
     * limit. {@code null} (direct test construction) makes the dedicated client fall back to its own.
     */
    private final ProviderConcurrencyGates concurrencyGates;

    private volatile ChatClient dedicatedClient;
    private volatile ChatSettings dedicatedSettings;

    public BtwAnswerer(
            final ChatClient chatClient,
            final SessionStore sessionStore,
            final CodingAgentConfiguration configuration,
            final ExecutorService executor,
            final InBandToolCallExtractor inBandToolCallExtractor,
            final ToolRegistry toolRegistry,
            final ToolExecutionService toolExecutionService,
            final JsonMapper mapper,
            final ProviderManager providerManager,
            final Clock clock,
            final Emitter emitter,
            final EventContextBuilder eventContextBuilder,
            final ToolCallPairingRepair pairingRepair,
            final ExtendedHttpClient httpClient,
            final ProviderConcurrencyGates concurrencyGates) {
        this.chatClient = chatClient;
        this.httpClient = httpClient;
        this.sessionStore = sessionStore;
        this.configuration = configuration;
        this.executor = executor;
        this.inBandToolCallExtractor = inBandToolCallExtractor;
        this.toolRegistry = toolRegistry;
        this.toolExecutionService = toolExecutionService;
        this.mapper = mapper;
        this.providerManager = providerManager;
        this.clock = clock;
        this.emitter = emitter;
        this.eventContextBuilder = eventContextBuilder;
        this.pairingRepair = pairingRepair;
        this.concurrencyGates = concurrencyGates;
    }

    public record BtwExchange(String question, String answer) {}

    /**
     * Result of a /btw side question. {@code retryable} is true when the answerer could not produce a
     * real answer (empty output or the tool round cap was reached in tooled mode) and re-asking the same
     * question may succeed, so a UI should offer a Retry action instead of treating it as a hard error.
     */
    public record BtwAnswer(String text, boolean retryable) {
        static BtwAnswer of(final String text) {
            return new BtwAnswer(text, false);
        }
    }

    public CompletionStage<BtwAnswer> ask(
            final String question,
            final String sessionId,
            final UsageTracker usage,
            final BooleanSupplier cancelled,
            final ApprovalGate sessionGate,
            final Path root,
            final Sandbox sandbox) {
        return ask(question, sessionId, usage, cancelled, sessionGate, null, null, root, sandbox);
    }

    /** @param sessionSettings the requesting tab's selection; null means the process default provider/model */
    public CompletionStage<BtwAnswer> ask(
            final String question,
            final String sessionId,
            final UsageTracker usage,
            final BooleanSupplier cancelled,
            final ApprovalGate sessionGate,
            final ChatSettings sessionSettings,
            final Path root,
            final Sandbox sandbox) {
        return ask(question, sessionId, usage, cancelled, sessionGate, sessionSettings, null, root, sandbox);
    }

    /**
     * @param sessionSettings the requesting tab's selection; null means the process default provider/model
     * @param systemPrompt the session's {@link SystemPromptBuilder}, when available, so the /btw request
     *                     rebuilds the exact system-prompt prefix the main agent loop sends (byte-identical
     *                     across turns) and the provider prompt cache keeps hitting. Null keeps the compact
     *                     tool-less fallback used by the headless/unit paths.
     */
    public CompletionStage<BtwAnswer> ask(
            final String question,
            final String sessionId,
            final UsageTracker usage,
            final BooleanSupplier cancelled,
            final ApprovalGate sessionGate,
            final ChatSettings sessionSettings,
            final SystemPromptBuilder systemPrompt,
            final Path root,
            final Sandbox sandbox) {
        return ask(
                question, sessionId, usage, cancelled, sessionGate, sessionSettings, systemPrompt, null, root, sandbox);
    }

    /** Answers with a feedback service scoped to the owning side question. */
    public CompletionStage<BtwAnswer> ask(
            final String question,
            final String sessionId,
            final UsageTracker usage,
            final BooleanSupplier cancelled,
            final ApprovalGate sessionGate,
            final ChatSettings sessionSettings,
            final SystemPromptBuilder systemPrompt,
            final UserFeedbackService feedback,
            final Path root,
            final Sandbox sandbox) {
        return ask(
                question,
                sessionId,
                usage,
                cancelled,
                sessionGate,
                sessionSettings,
                systemPrompt,
                feedback,
                root,
                toolRegistry,
                wireBtwTools(root, toolRegistry, null).stream()
                        .map(tool -> tool.function().name())
                        .collect(Collectors.toUnmodifiableSet()),
                sandbox);
    }

    /**
     * Answers using the owning loop's borrowed registry and captured tool projection.
     * Session callers pass {@code loop.effectiveToolRegistry()} and {@code loop.availableToolNames()}.
     * A null registry exposes no tools, never another session's or the application's registry.
     * The tool-name ceiling is required, so a missing parent projection cannot silently widen access.
     * The caller's cancellation probe must include session disposal as well as side-question cancellation.
     */
    public CompletionStage<BtwAnswer> ask(
            final String question,
            final String sessionId,
            final UsageTracker usage,
            final BooleanSupplier cancelled,
            final ApprovalGate sessionGate,
            final ChatSettings sessionSettings,
            final SystemPromptBuilder systemPrompt,
            final UserFeedbackService feedback,
            final Path root,
            final ToolRegistry effectiveToolRegistry,
            final Set<String> allowedTools,
            final Sandbox sandbox) {
        Objects.requireNonNull(root, "workdir");
        Objects.requireNonNull(sandbox, "sandbox");
        if (!root.isAbsolute() || !root.equals(root.normalize())) {
            throw new IllegalArgumentException("workdir must be absolute and normalized: " + root);
        }
        final var ceiling = Set.copyOf(Objects.requireNonNull(allowedTools, "allowedTools"));
        final var future = new CompletableFuture<BtwAnswer>();
        executor.execute(() -> {
            try {
                final var answer = doAsk(
                        question,
                        sessionId,
                        usage,
                        cancelled,
                        sessionGate,
                        sessionSettings,
                        systemPrompt,
                        feedback,
                        root,
                        effectiveToolRegistry,
                        ceiling,
                        sandbox);
                future.complete(answer);
            } catch (final IOException e) {
                future.completeExceptionally(e);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(e);
            } catch (final RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    BtwAnswer doAsk(
            final String question,
            final String sessionId,
            final UsageTracker usage,
            final BooleanSupplier cancelled,
            final ApprovalGate sessionGate,
            final ChatSettings sessionSettings,
            final SystemPromptBuilder systemPrompt,
            final Path root,
            final Sandbox sandbox)
            throws IOException, InterruptedException {
        return doAsk(
                question,
                sessionId,
                usage,
                cancelled,
                sessionGate,
                sessionSettings,
                systemPrompt,
                null,
                root,
                toolRegistry,
                null,
                sandbox);
    }

    private BtwAnswer doAsk(
            final String question,
            final String sessionId,
            final UsageTracker usage,
            final BooleanSupplier cancelled,
            final ApprovalGate sessionGate,
            final ChatSettings sessionSettings,
            final SystemPromptBuilder systemPrompt,
            final UserFeedbackService feedback,
            final Path root,
            final ToolRegistry effectiveToolRegistry,
            final Set<String> allowedTools,
            final Sandbox sandbox)
            throws IOException, InterruptedException {
        final var conf = configuration();
        // Snapshot once: neither later marker changes nor tool/model arguments may change this side request's root.
        Objects.requireNonNull(root, "workdir");
        Objects.requireNonNull(sandbox, "sandbox");
        final var bounded = tail(sessionStore.load(sessionId), conf.maxHistoryMessages());
        // The persisted session has the agent loop's pairing repair applied per resume, but a /btw slice
        // can start mid-tool-round (orphan tool result at head, or an unpaired assistant tool_calls block
        // before a later paired block): the wire backstops trust everything before their verification
        // start, so a broken pairing in the slice reaches Azure/litellm as a hard 400. Heal the slice
        // in memory (never persist - /btw is a read-only side channel into the session) before sending.
        final var repairedTail =
                pairingRepair != null ? pairingRepair.repair(bounded).messages() : bounded;
        final var messages = new ArrayList<Message>(repairedTail.size() + 2);
        final var systemText = systemPrompt != null
                ? systemPrompt.build(null, null)
                : conf.tools() ? TOOLED_SYSTEM_PROMPT : TOOL_LESS_SYSTEM_PROMPT;
        messages.add(Message.system(systemText));
        messages.addAll(repairedTail);
        final var usingSessionPrompt = systemPrompt != null;
        messages.add(Message.user(userPrompt(conf, question, usingSessionPrompt)));
        final var model = conf.model() == null || conf.model().isBlank() ? null : conf.model();
        final var target = client(model);
        if (!conf.tools()) {
            // Tool-less default: a single generation that never executes anything. The request keeps the
            // session's system prompt (when provided) and the full wire tool schema so the provider prompt
            // cache prefix stays byte-identical with the main agent loop; the model is told through the
            // final user message to answer directly and never act, and stray in-band tool-call markup a
            // model emits anyway is never executed. A content that was consumed by a tool attempt - the
            // model reached for a tool and left only an intro - is surfaced as a retryable note, never a
            // silent truncated success.
            var requestSettings = target.settings();
            if (sessionSettings != null) {
                requestSettings =
                        requestSettings == null ? sessionSettings : requestSettings.inheritFrom(sessionSettings);
            }
            var retried = false;
            var pairRetried = false;
            String raw = "";
            for (var round = 0; round < 2; round++) {
                if (cancelled.getAsBoolean()) {
                    throw new InterruptedException("cancelled");
                }
                final ChatResult res;
                try {
                    res = target.client()
                            .chat(
                                    new ChatCompletionRequest(
                                                    model,
                                                    messages,
                                                    null,
                                                    null,
                                                    false,
                                                    wireBtwTools(root, effectiveToolRegistry, allowedTools),
                                                    null,
                                                    conf.maxTokens() > 0 ? conf.maxTokens() : null,
                                                    null,
                                                    null)
                                            .withSessionId(sessionId)
                                            .withSettings(requestSettings),
                                    cancelled);
                } catch (final IOException e) {
                    // Azure / litellm strict validators reject an assistant tool_calls with no contiguous
                    // tool result - the exact shape a /btw slice can carry when it starts mid-tool-round.
                    // Repair the outgoing messages in memory and retry EXACTLY ONCE with the healed
                    // payload; a second pairing failure (or any other failure) propagates as before.
                    if (!pairRetried && ToolCallPairingRepair.isPairingRejection(e)) {
                        pairRetried = true;
                        final var repaired = pairingRepair.repair(messages);
                        messages.clear();
                        messages.addAll(repaired.messages());
                        continue;
                    }
                    throw e;
                }
                if (usage != null) {
                    usage.recordAuxiliary(res.usage());
                }
                final var content = res.message() == null ? null : res.message().content();
                raw = content == null ? "" : String.valueOf(content);
                // A model that reached for a tool - natively or through in-band text markup that ChatClient
                // hoisted into toolCalls - leaves only this stripped intro, never a real answer. Detect it via
                // the declared calls (primary) or any leftover markup (backstop) and surface it as retryable
                // instead of a silent success; a tool-call result can even arrive with blank content.
                final var nativeCalls =
                        res.message() == null ? null : res.message().toolCalls();
                final var attemptedToolCall =
                        (nativeCalls != null && !nativeCalls.isEmpty()) || inBandToolCallExtractor.containsMarkup(raw);
                final var answer = inBandToolCallExtractor.strip(raw);
                if (!answer.isBlank() && !attemptedToolCall) {
                    remember(conf, new BtwExchange(question, answer));
                    return BtwAnswer.of(answer);
                }
                if (!answer.isBlank()) {
                    // the model reached for a tool and left only this intro; it must not look like a
                    // clean success (arms the Retry affordance), and the broken exchange is never
                    // replayed into a follow-up side question.
                    return new BtwAnswer(danglingNote(answer), true);
                }
                // truncated-while-thinking signature: tokens were spent but nothing came out. Retry once.
                if (!retried && res.usage() != null && res.usage().completionTokens() > 0) {
                    retried = true;
                    continue;
                }
                return new BtwAnswer(
                        "The model returned an empty answer. Check the output budget or retry the side question.",
                        true);
            }
        }
        // Tooled mode (yca.slashcommands.btw.tools): bounded read-only tool loop over the inherited history.
        // /btw never inherits the session permission mode: plan-mode rules only, and the caller's
        // approval gate (the tabs share it, so an approval pops above the btw modal) decides the rest.
        final var permissions = new PermissionService(configuration, emitter, root);
        permissions.setMode(AgentMode.PLAN);
        permissions.setPermissionMode(PermissionMode.ASK);
        permissions.setGate(sessionGate);
        final var approvalSandbox = sandbox.approvalView(permissions);
        final var tools = effectiveToolRegistry == null
                ? List.<Tool>of()
                : effectiveToolRegistry.toWireTools(
                        root,
                        tool -> allowedTool(tool) && (allowedTools == null || allowedTools.contains(tool.name())));
        final var offeredTools =
                tools.stream().map(tool -> tool.function().name()).collect(Collectors.toUnmodifiableSet());
        var retried = false;
        var pairRetried = false;
        String raw = "";
        final var attemptedTools = new LinkedHashSet<String>();
        for (var round = 0; round < MAX_TOOL_ROUNDS; round++) {
            if (cancelled.getAsBoolean()) {
                throw new InterruptedException("cancelled");
            }
            var requestSettings = target.settings();
            if (sessionSettings != null) {
                requestSettings =
                        requestSettings == null ? sessionSettings : requestSettings.inheritFrom(sessionSettings);
            }
            final ChatResult res;
            try {
                res = target.client()
                        .chat(
                                new ChatCompletionRequest(
                                                model,
                                                messages,
                                                null,
                                                null,
                                                false,
                                                tools,
                                                "auto",
                                                conf.maxTokens() > 0 ? conf.maxTokens() : null,
                                                null,
                                                null)
                                        .withSessionId(sessionId)
                                        .withSettings(requestSettings),
                                cancelled);
            } catch (final IOException e) {
                // same pairing rejection as the tool-less path: the tooled loop appends assistant
                // tool_calls + results, but a broken pairing can also arrive from the inherited slice
                // (orphan tool result at head). Repair and retry exactly once.
                if (!pairRetried && ToolCallPairingRepair.isPairingRejection(e)) {
                    pairRetried = true;
                    final var repaired = pairingRepair.repair(messages);
                    messages.clear();
                    messages.addAll(repaired.messages());
                    continue;
                }
                throw e;
            }
            if (usage != null) {
                usage.recordAuxiliary(res.usage());
            }
            final var content = res.message() == null ? null : res.message().content();
            raw = content == null ? "" : String.valueOf(content);
            final var nativeCalls = res.message() == null ? null : res.message().toolCalls();
            final var calls =
                    nativeCalls != null && !nativeCalls.isEmpty() ? nativeCalls : inBandToolCallExtractor.parse(raw);
            if (calls.isEmpty()) {
                final var answer = inBandToolCallExtractor.strip(raw);
                if (!answer.isBlank()) {
                    remember(conf, new BtwExchange(question, answer));
                    return BtwAnswer.of(answer);
                }
                // truncated-while-thinking signature: tokens were spent but nothing came out.
                // Retry once, then fall back to a note instead of an exception.
                if (!retried && res.usage() != null && res.usage().completionTokens() > 0) {
                    retried = true;
                    continue;
                }
                return new BtwAnswer(
                        "The model returned an empty answer. Check the output budget or retry the side question.",
                        true);
            }
            final var prose = inBandToolCallExtractor.strip(raw);
            messages.add(Message.assistant(prose.isBlank() ? null : prose, calls));
            for (final var call : calls) {
                if (call != null && call.function() != null && call.function().name() != null) {
                    attemptedTools.add(call.function().name());
                }
                final var outcome = runTool(
                        target,
                        call,
                        sessionId,
                        usage,
                        cancelled,
                        permissions,
                        feedback,
                        root,
                        effectiveToolRegistry,
                        offeredTools,
                        approvalSandbox);
                messages.add(Message.tool(call.id(), outcome.output(), !outcome.success()));
            }
        }
        // round cap reached while the model kept calling tools: surface the glitch as a retryable note.
        return new BtwAnswer(glitchNote(raw, attemptedTools), true);
    }

    /** Executes a single /btw tool call, mirroring the main loop's authorisation and bounding. */
    private ToolOutcome runTool(
            final Target target,
            final ToolCall call,
            final String sessionId,
            final UsageTracker usage,
            final BooleanSupplier cancelled,
            final PermissionService permissions,
            final UserFeedbackService feedback,
            final Path root,
            final ToolRegistry effectiveToolRegistry,
            final Set<String> offeredTools,
            final Sandbox sandbox) {
        final var function = call.function();
        final var name = function == null || function.name() == null ? "" : function.name();
        final var argsJson = function == null || function.arguments() == null ? "{}" : function.arguments();
        final var tool = effectiveToolRegistry == null ? null : effectiveToolRegistry.tool(name);
        if (tool == null) {
            return new ToolOutcome(call.id(), "unknown tool", false);
        }
        if (!offeredTools.contains(name) || !allowedTool(tool)) {
            return new ToolOutcome(call.id(), "Permission denied by user: tool not available in /btw", false);
        }
        if (cancelled.getAsBoolean()) {
            return new ToolOutcome(call.id(), "cancelled", false);
        }
        final Map<String, Object> args;
        try {
            // mirror the main loop's parsing: never trust the model, garbage in -> invalid args
            final var parsed = mapper.fromString(Object.class, argsJson.isBlank() ? "{}" : argsJson);
            args = parsed instanceof final Map<?, ?> map ? castArgs(map) : new LinkedHashMap<>();
        } catch (final RuntimeException invalid) {
            return new ToolOutcome(call.id(), "(invalid arguments: " + invalid.getMessage() + ")", false);
        }
        final var inline = permissions.check(name, tool.category(), args, tool.readonly());
        if (!inline.allowed()) {
            return new ToolOutcome(
                    call.id(),
                    "Permission denied by user" + (inline.reason() == null ? "" : ": " + inline.reason()),
                    false);
        }
        // the built-in plan-mode check is authoritative inline (so a /btw denial is never dependent on the global
        // observer wiring); the ToolUsePreEvent is still emitted so configured yca.hooks.ToolUsePreEvent callbacks
        // run for allowed calls. The built-in observer re-checking the same permissionService is a harmless fast path.
        final var toolUseResult = new HookResult();
        final var eventContext = eventContextBuilder == null
                ? new EventContext(sessionId, null, null, root.toString(), null, null, null, null)
                : eventContextBuilder.build(permissions, target.settings(), sessionId, null, null, root);
        emitter.emit(new ToolUsePreEvent(
                eventContext, name, args, call.id(), toolUseResult, permissions, tool.category(), tool.readonly()));
        if (toolUseResult.forbidden()) {
            return new ToolOutcome(
                    call.id(),
                    "Permission denied by user" + (toolUseResult.reason() == null ? "" : ": " + toolUseResult.reason()),
                    false);
        }
        final var context = new ToolContext(
                sessionId,
                null,
                null,
                permissions,
                feedback,
                null,
                null,
                usage,
                null,
                cancelled,
                target.settings(),
                null,
                root,
                sandbox,
                -1,
                offeredTools,
                null,
                effectiveToolRegistry);
        final var result = toolExecutionService.execute(tool, args, context);
        final var output = result.output() == null || result.output().isBlank() ? "(no output)" : result.output();
        return new ToolOutcome(call.id(), capIngestionOutput(output), result.success());
    }

    private record ToolOutcome(String callId, String output, boolean success) {}

    private static boolean allowedTool(final EmbeddedTool tool) {
        return tool.category() == ToolCategory.READ
                || (tool.category() == ToolCategory.MCP && tool.readonly())
                || tool.category() == ToolCategory.BASH
                || "ask_user".equals(tool.name());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castArgs(final Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private String capIngestionOutput(final String output) {
        final var cap = configuration.pruning().ingestionOutputCapChars();
        if (cap <= 0 || output == null || output.length() <= cap) {
            return output;
        }
        final var half = cap / 2;
        return output.substring(0, half)
                + "\n[... output truncated (" + output.length() + " chars), kept head and tail ...]\n"
                + output.substring(output.length() - half);
    }

    /** Renders a tool-call-only answer as a note plus the raw output, in markdown. */
    private String glitchNote(final String raw, final Set<String> attemptedTools) {
        final var toolsList =
                attemptedTools.isEmpty() ? "" : " Attempted tools: " + String.join(", ", attemptedTools) + ".";
        return "> The model kept calling tools instead of answering this side question." + toolsList
                + " Raw output:\n\n"
                + "```\n"
                + raw
                + "\n```";
    }

    /** The client to use plus the settings riding the call: the shared one with no override, or a dedicated one. */
    private record Target(ChatClient client, ChatSettings settings) {}

    private Target client(final String model) {
        if (model == null) {
            return new Target(chatClient, null);
        }
        final var dedicated = dedicatedClient;
        if (dedicated != null) {
            return new Target(dedicated, dedicatedSettings);
        }
        synchronized (this) {
            if (dedicatedClient == null) {
                final var created = new ChatClient(
                        configuration, mapper, providerManager, clock, httpClient, null, concurrencyGates);
                final var settings = ChatSettings.EMPTY.withModel(model);
                dedicatedClient = created;
                dedicatedSettings = settings;
            }
            return new Target(dedicatedClient, dedicatedSettings);
        }
    }

    @Destroy
    public void release() {
        if (dedicatedClient != null) {
            dedicatedClient.close();
        }
    }

    private BtwConfiguration configuration() {
        final var slashcommands = configuration.slashcommands();
        if (slashcommands != null && slashcommands.btw() != null) {
            return slashcommands.btw();
        }
        // factory does not inject a nested default (direct test construction): inline the property defaults.
        return new BtwConfiguration("", 0, 200, 5, false);
    }

    private List<Message> tail(final List<Message> history, final int max) {
        if (max <= 0 || history.size() <= max) {
            return history;
        }
        return history.subList(history.size() - max, history.size());
    }

    private String userPrompt(final BtwConfiguration conf, final String question, final boolean usingSessionPrompt) {
        final var sb = new StringBuilder();
        if (usingSessionPrompt) {
            // the request reuses the session system prompt for the cache prefix, so the /btw "answer
            // directly, never act" rule lives here, in the changing tail where the cache already breaks.
            sb.append(conf.tools() ? TOOLED_SYSTEM_PROMPT : USER_DIRECTION).append("\n\n");
        }
        final List<BtwExchange> recent = recent(conf.recall());
        if (!recent.isEmpty()) {
            sb.append("Earlier side questions in this session (context only):\n\n");
            for (final var exchange : recent) {
                sb.append("Q: ").append(exchange.question()).append('\n');
                sb.append("A: ").append(exchange.answer()).append("\n\n");
            }
        }
        sb.append("Side question: ").append(question);
        return sb.toString();
    }

    /** The tools the main loop declares for the session: the full wire schema, never filtered to a btw-only set. */
    private List<Tool> wireBtwTools(
            final Path root, final ToolRegistry effectiveToolRegistry, final Set<String> allowedTools) {
        return effectiveToolRegistry == null
                ? List.of()
                : effectiveToolRegistry.toWireTools(
                        root, tool -> allowedTools == null || allowedTools.contains(tool.name()));
    }

    /** Renders the tool-attempt fallback: the leftover intro plus an explicit, honest note (Claude-Code style). */
    private String danglingNote(final String stripped) {
        return (stripped == null || stripped.isBlank() ? "" : stripped + "\n\n") + DANGLING_TOOL_ANSWER_NOTE;
    }

    private List<BtwExchange> recent(final int recall) {
        synchronized (exchanges) {
            final var limit = Math.min(recall, MAX_RECALL_EXCHANGES);
            if (limit <= 0 || exchanges.isEmpty()) {
                return List.of();
            }
            return exchanges.stream()
                    .skip(Math.max(0, exchanges.size() - limit))
                    .toList();
        }
    }

    private void remember(final BtwConfiguration conf, final BtwExchange exchange) {
        final var limit = Math.min(conf.recall(), MAX_RECALL_EXCHANGES);
        if (limit <= 0) {
            return;
        }
        synchronized (exchanges) {
            exchanges.addLast(exchange);
            while (exchanges.size() > limit) {
                exchanges.pollFirst();
            }
        }
    }
}
