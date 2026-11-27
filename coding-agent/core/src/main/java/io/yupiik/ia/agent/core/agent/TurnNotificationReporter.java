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

import io.yupiik.ia.agent.core.context.TokenEstimator;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.notifications.NotificationService;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Turn-finalization reporting: per-turn metrics emission, the OS turn-end notification and the
 * output-throughput accounting (accumulated on {@link TurnState}). Pure emission/accounting - the
 * caller owns the messages list and the decision to end the turn; this class only reads the turn
 * state, builds the events and hands them to the configured {@link Consumer}.
 */
public final class TurnNotificationReporter {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final Path workdir;
    private final TurnState turnState;
    private final PrecisionDurationMarkerProvider precisionDurationMarker;
    private final NotificationService notificationService;
    private final SessionStore sessionStore;
    private final Supplier<ProviderManager.ResolvedModel> resolvedModel;
    private final Supplier<String> currentSessionId;
    private final Supplier<String> subagentId;
    private final Supplier<String> subagentName;
    private final BooleanSupplier lastTurnEndedWithContent;
    private final Consumer<Boolean> lastTurnEndedWithContentSetter;
    private final Consumer<AgentEvent> emit;

    public TurnNotificationReporter(
            final TurnState turnState,
            final PrecisionDurationMarkerProvider precisionDurationMarker,
            final NotificationService notificationService,
            final SessionStore sessionStore,
            final Supplier<ProviderManager.ResolvedModel> resolvedModel,
            final Supplier<String> currentSessionId,
            final Supplier<String> subagentId,
            final Supplier<String> subagentName,
            final BooleanSupplier lastTurnEndedWithContent,
            final Consumer<Boolean> lastTurnEndedWithContentSetter,
            final Consumer<AgentEvent> emit,
            final Path workdir) {
        this.workdir = Objects.requireNonNull(workdir, "workdir");
        this.turnState = turnState;
        this.precisionDurationMarker = precisionDurationMarker;
        this.notificationService = notificationService;
        this.sessionStore = sessionStore;
        this.resolvedModel = resolvedModel;
        this.currentSessionId = currentSessionId;
        this.subagentId = subagentId;
        this.subagentName = subagentName;
        this.lastTurnEndedWithContent = lastTurnEndedWithContent;
        this.lastTurnEndedWithContentSetter = lastTurnEndedWithContentSetter;
        this.emit = emit;
    }

    /**
     * Emits the per-turn metrics event (LLM calls, thinking/tool wall time, tokens) and, when a final
     * assistant content is present, the turn-done event. Also records whether the turn ended with
     * content - used by the turn-end notification and the memory excerpt gate.
     */
    public void emitTurnMetrics(final String content, final long turnStartNanos) {
        final var turnDurationMs = (precisionDurationMarker.now() - turnStartNanos) / 1_000_000;
        if (turnState.thinkingActive) {
            turnState.thinkingMs += (precisionDurationMarker.now() - turnState.thinkingStartNanos) / 1_000_000;
        }
        emit.accept(turnMetrics(
                turnState.llmCalls,
                turnState.thinkingMs,
                turnState.toolSumMs,
                turnState.toolWallMs,
                turnState.toolsRun,
                turnDurationMs));
        if (content != null) {
            lastTurnEndedWithContentSetter.accept(!content.isBlank());
            emit.accept(turnDone(content));
        }
    }

    /**
     * Builds and delivers the OS turn-end notification (top-level turns only, see the {@code doRun} finally).
     * The notification is best-effort and asynchronous inside {@link NotificationService}, so gathering the
     * fields here never blocks or fails the turn - it only reads session state.
     */
    public void emitTurnEndNotification(final String sessionId, final List<Message> messages) {
        if (notificationService == null) {
            return;
        }
        final var sessionTitle = sessionStore == null ? "" : sessionStore.summary(sessionId);
        final var title =
                (sessionTitle == null || sessionTitle.isBlank()) ? sessionId : sessionId + " - " + sessionTitle;
        final var resolved = resolvedModel.get();
        final var provider = resolved == null || resolved.provider() == null
                ? ""
                : resolved.provider().id();
        final var model = resolved == null || resolved.model() == null ? "" : resolved.model();
        final var success = lastTurnEndedWithContent.getAsBoolean();
        final var lastReply = lastAssistantText(messages);
        final var body =
                (success ? "✔ " : "✖ ") + (provider.isBlank() ? "" : provider + " / ") + model + "\n\n" + lastReply;
        notificationService.onTurnEnd(sessionId, title, model, provider, success, body);
    }

    /**
     * The text of the most recent real assistant reply in {@code messages} (a plain string content), collapsed
     * to a single line and truncated so a notification body stays compact. Empty string when none is found.
     */
    static String lastAssistantText(final List<Message> messages) {
        if (messages == null) {
            return "";
        }
        for (var i = messages.size() - 1; i >= 0; i--) {
            final var message = messages.get(i);
            if (message == null
                    || !"assistant".equals(message.role())
                    || !(message.content() instanceof String content)) {
                continue;
            }
            final var singleLine = WHITESPACE.matcher(content).replaceAll(" ").strip();
            return singleLine.length() <= 400 ? singleLine : singleLine.substring(0, 397) + "...";
        }
        return "";
    }

    /**
     * Accumulates delivered output and logical request time. Provider timing uses matched usage only.
     *
     * @param assistant the message produced by the request.
     * @param usage the usage reported by the provider, {@link Usage#EMPTY} when none.
     * @param activeMs duration captured at stream return, with tracked waits already excluded.
     */
    public void recordThroughput(final Message assistant, final Usage usage, final long activeMs) {
        turnState.llmActiveMs += Math.max(0L, activeMs);
        if (usage != null && usage.completionTokens() > 0) {
            turnState.outputTokens += usage.completionTokens();
            if (usage.reasoningTokens() > 0) {
                turnState.reasoningTokens += usage.reasoningTokens();
            }
            if (usage.generationTimeMs() > 0) {
                turnState.llmReportedMs += usage.generationTimeMs();
                turnState.reportedOutputTokens += usage.completionTokens();
                turnState.reportedRequests++;
            }
        } else {
            // no usage from the provider: estimate the produced tokens from what came back
            final var estimated = estimateOutputTokens(assistant);
            turnState.outputTokens += estimated;
            turnState.outputEstimated |= estimated > 0;
        }
    }

    /**
     * Estimates the tokens a response produced when the provider reports no usage: the visible
     * content plus the arguments of every tool call.
     *
     * @param assistant the produced message, may be {@code null}.
     * @return the estimated token count, {@code 0} when there is nothing to estimate.
     */
    private int estimateOutputTokens(final Message assistant) {
        if (assistant == null) {
            return 0;
        }
        var tokens = TokenEstimator.estimate(str(assistant.content()));
        if (assistant.toolCalls() != null) {
            for (final var call : assistant.toolCalls()) {
                if (call != null && call.function() != null) {
                    tokens += TokenEstimator.estimate(call.function().arguments());
                }
            }
        }
        return tokens;
    }

    /**
     * Whether a normalized stream finish reason means the reply was cut by the output token budget:
     * the dialects map Anthropic {@code max_tokens} and OpenAI {@code length} to {@code length}.
     */
    static boolean isTruncation(final String finishReason) {
        return "length".equalsIgnoreCase(finishReason) || "max_tokens".equalsIgnoreCase(finishReason);
    }

    private AgentEvent.TurnMetrics turnMetrics(
            final int llmCalls,
            final long thinkingDurationMs,
            final long toolDurationSumMs,
            final long toolDurationWallMs,
            final int toolsRun,
            final long turnDurationMs) {
        return new AgentEvent.TurnMetrics(
                llmCalls,
                thinkingDurationMs,
                toolDurationSumMs,
                toolDurationWallMs,
                toolsRun,
                turnDurationMs,
                new AgentEvent.Throughput(
                        turnState.outputTokens,
                        turnState.reasoningTokens,
                        turnState.llmActiveMs,
                        turnState.llmReportedMs,
                        turnState.reportedOutputTokens,
                        turnState.llmCalls,
                        turnState.reportedRequests,
                        turnState.outputEstimated),
                currentSessionId.get(),
                subagentId.get(),
                subagentName.get(),
                workdir);
    }

    private AgentEvent.TurnDone turnDone(final String content) {
        return new AgentEvent.TurnDone(content, currentSessionId.get(), subagentId.get(), subagentName.get(), workdir);
    }

    private static String str(final Object content) {
        return content instanceof String s ? s : content == null ? "" : content.toString();
    }
}
