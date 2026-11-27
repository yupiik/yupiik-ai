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

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Flat, JSON-safe projection of an {@link AgentEvent} streamed to the browser over SSE. */
@JsonModel
public record WebEventDto(
        String type,
        String sessionId,
        String subagentId,
        String subagentName,
        String message,
        Map<String, Object> data) {

    public static WebEventDto of(final AgentEvent event) {
        return of(event, UnaryOperator.identity());
    }

    public static WebEventDto of(final AgentEvent event, final UnaryOperator<String> displayPolicy) {
        return new WebEventDto(
                typeOf(event),
                event.sessionId(),
                event.subagentId(),
                event.subagentName(),
                event instanceof AgentEvent.ToolFilesChanged t ? displayPolicy.apply(t.name()) : messageOf(event),
                event instanceof AgentEvent.ToolFilesChanged t ? filesData(t, displayPolicy) : dataOf(event));
    }

    /** Live-only captured data: redacted text must never be presented as an exact applied diff. */
    private static Map<String, Object> filesData(
            final AgentEvent.ToolFilesChanged event, final UnaryOperator<String> displayPolicy) {
        return Map.of(
                "ordinal", event.ordinal(),
                "files",
                        event.files().stream()
                                .map(file -> {
                                    final var data = new LinkedHashMap<String, Object>();
                                    data.put(
                                            "path",
                                            displayPolicy.apply(file.path().toString()));
                                    data.put("kind", file.kind().name().toLowerCase(Locale.ROOT));
                                    final var content = file.content();
                                    if (content != null
                                            && (content.oldText() == null
                                                    || content.oldText().equals(displayPolicy.apply(content.oldText())))
                                            && content.newText().equals(displayPolicy.apply(content.newText()))) {
                                        data.put("oldText", content.oldText());
                                        data.put("newText", content.newText());
                                    }
                                    return data;
                                })
                                .toList());
    }

    private static String typeOf(final AgentEvent event) {
        return switch (event) {
            case AgentEvent.FeedbackStarted _ -> "feedback-started";
            case AgentEvent.TextDelta _ -> "text-delta";
            case AgentEvent.TextReset _ -> "text-reset";
            case AgentEvent.ThinkingDelta _ -> "thinking-delta";
            case AgentEvent.ToolStarted _ -> "tool-started";
            case AgentEvent.ToolStream _ -> "tool-stream";
            case AgentEvent.ToolDiagnostic _ -> "tool-diagnostic";
            case AgentEvent.ToolFinished _ -> "tool-finished";
            case AgentEvent.ToolFilesChanged _ -> "tool-files-changed";
            case AgentEvent.UsageUpdated _ -> "usage-updated";
            case AgentEvent.LlmRequestCompleted _ -> "llm-request-completed";
            case AgentEvent.Status _ -> "status";
            case AgentEvent.Pause _ -> "pause";
            case AgentEvent.Notice _ -> "notice";
            case AgentEvent.TurnMetrics _ -> "turn-metrics";
            case AgentEvent.TurnDone _ -> "turn-done";
            case AgentEvent.TurnCancelled _ -> "turn-cancelled";
            case AgentEvent.CompactionPass _ -> "compaction-pass";
            case AgentEvent.Compacted _ -> "compacted";
            case AgentEvent.Error _ -> "error";
            case AgentEvent.SubagentStarted _ -> "subagent-started";
            case AgentEvent.SubagentCompleted _ -> "subagent-completed";
            case AgentEvent.SubagentDelivered _ -> "subagent-delivered";
            case AgentEvent.PromptIntegrated _ -> "prompt-integrated";
            case AgentEvent.ToolTimingEvent _ -> "tool-timing";
            case AgentEvent.Phase _ -> "phase";
        };
    }

    private static String messageOf(final AgentEvent event) {
        return switch (event) {
            case AgentEvent.FeedbackStarted t -> t.kind();
            case AgentEvent.TextDelta t -> t.delta();
            case AgentEvent.TextReset t -> null;
            case AgentEvent.ThinkingDelta t -> t.delta();
            case AgentEvent.ToolStarted t -> t.name();
            case AgentEvent.ToolStream t -> t.name();
            case AgentEvent.ToolDiagnostic t -> t.name();
            case AgentEvent.ToolFinished t -> t.name();
            case AgentEvent.ToolFilesChanged t -> t.name();
            case AgentEvent.UsageUpdated t -> t.model();
            case AgentEvent.LlmRequestCompleted t -> t.model();
            case AgentEvent.Status t -> t.message();
            case AgentEvent.Pause t -> t.message();
            case AgentEvent.Notice t -> t.message();
            case AgentEvent.TurnMetrics _ -> null;
            case AgentEvent.TurnDone t -> t.content();
            case AgentEvent.TurnCancelled _ -> null;
            case AgentEvent.CompactionPass _ -> null; // Legacy notices still drive this UI; no operation payload here.
            case AgentEvent.Compacted t -> t.kinds().isEmpty() ? "" : String.join(",", t.kinds()) + '|' + t.mode();
            case AgentEvent.Error t -> t.message();
            case AgentEvent.SubagentStarted t -> t.task();
            case AgentEvent.SubagentCompleted t -> t.summary();
            case AgentEvent.SubagentDelivered t -> t.summary();
            case AgentEvent.PromptIntegrated t -> t.text();
            case AgentEvent.ToolTimingEvent t -> t.toolName();
            case AgentEvent.Phase t -> t.name();
        };
    }

    private static Map<String, Object> dataOf(final AgentEvent event) {
        return switch (event) {
            case AgentEvent.FeedbackStarted t -> Map.of("startedAt", t.startedAt(), "kind", t.kind());
            case AgentEvent.Phase t -> Map.of("phase", t.name());
            case AgentEvent.ToolStarted t -> Map.of("args", t.args(), "ordinal", t.ordinal());
            case AgentEvent.ToolDiagnostic t ->
                Map.of("detail", t.detail() == null ? "" : t.detail(), "ordinal", t.ordinal());
            case AgentEvent.ToolStream t ->
                Map.of("stream", true, "fragment", t.fragment() == null ? "" : t.fragment(), "ordinal", t.ordinal());
            case AgentEvent.ToolFinished t ->
                Map.of(
                        "output",
                        t.output() == null ? "" : t.output(),
                        "success",
                        t.success(),
                        "pending",
                        t.pending(),
                        "ordinal",
                        t.ordinal());
            case AgentEvent.UsageUpdated t -> {
                final var data = new LinkedHashMap<String, Object>();
                data.put("promptTokens", t.usage() == null ? 0 : t.usage().promptTokens());
                data.put("contextInputTokens", t.usage() == null ? 0 : t.usage().contextInputTokens());
                data.put("completionTokens", t.usage() == null ? 0 : t.usage().completionTokens());
                data.put("reasoningTokens", t.usage() == null ? 0 : t.usage().reasoningTokens());
                data.put("cacheReadTokens", t.usage() == null ? 0 : t.usage().cacheReadTokens());
                data.put("estimatedContextTokens", t.estimatedContextTokens());
                if (t.contextMeasurement() != null) {
                    data.put("contextSource", t.contextMeasurement().source());
                    data.put("pendingContextTokens", t.contextMeasurement().pendingTokens());
                    data.put("contextBudgetTokens", t.contextMeasurement().budgetTokens());
                }
                data.put("contextPercent", t.contextPercent());
                final var quota = new LinkedHashMap<String, Object>();
                if (t.quota() != null) {
                    quota.put("enabled", t.quota().enabled());
                    quota.put("supported", t.quota().supported());
                    quota.put("planType", t.quota().planType());
                    quota.put("usedPercent", t.quota().usedPercent());
                    quota.put("windowSeconds", t.quota().windowSeconds());
                    quota.put("resetAtEpochSeconds", t.quota().resetAtEpochSeconds());
                    quota.put("windowLabel", t.quota().windowLabel());
                    quota.put("creditsBalance", t.quota().creditsBalance());
                    quota.put("creditsUnlimited", t.quota().creditsUnlimited());
                    quota.put(
                            "windows",
                            t.quota().windows().stream()
                                    .map(w -> {
                                        final var window = new LinkedHashMap<String, Object>();
                                        window.put("kind", w.kind());
                                        window.put("label", w.label());
                                        window.put("usedPercent", w.usedPercent());
                                        window.put("windowSeconds", w.windowSeconds());
                                        window.put("resetAtEpochSeconds", w.resetAtEpochSeconds());
                                        return window;
                                    })
                                    .toList());
                }
                data.put("quota", t.quota() == null ? null : quota);
                yield data;
            }
            case AgentEvent.LlmRequestCompleted t -> {
                final var data = new LinkedHashMap<String, Object>();
                data.put("index", t.index());
                data.put("model", t.model() == null ? null : t.model());
                data.put("promptTokens", t.usage() == null ? 0 : t.usage().promptTokens());
                data.put("contextInputTokens", t.usage() == null ? 0 : t.usage().contextInputTokens());
                data.put("completionTokens", t.usage() == null ? 0 : t.usage().completionTokens());
                data.put("reasoningTokens", t.usage() == null ? 0 : t.usage().reasoningTokens());
                data.put("cacheReadTokens", t.usage() == null ? 0 : t.usage().cacheReadTokens());
                data.put("cacheWriteTokens", t.usage() == null ? 0 : t.usage().cacheWriteTokens());
                data.put("totalTokens", t.usage() == null ? 0 : t.usage().totalTokens());
                yield data;
            }
            case AgentEvent.Pause t ->
                Map.of("durationMs", t.durationMs(), "kind", t.kind().name());
            case AgentEvent.Notice t -> {
                final var data = new LinkedHashMap<String, Object>();
                data.put("level", t.level().name());
                if (t.finishReason() != null) {
                    data.put("finishReason", t.finishReason());
                }
                // the compaction lifecycle marker: the start notice arms the compacting flag, the
                // result notices (compacted/gate) clear it; NONE leaves it untouched so a
                // non-compaction notice can never flip the UI state.
                switch (t.compaction()) {
                    case START -> data.put("compacting", true);
                    case COMPLETED, GATE_ESCALATED -> data.put("compacting", false);
                    case NONE -> {
                        // untouched
                    }
                }
                yield data;
            }
            case AgentEvent.TurnMetrics t -> {
                final var data = new LinkedHashMap<String, Object>();
                final var throughput = t.throughput() == null ? AgentEvent.Throughput.EMPTY : t.throughput();
                data.put("llmCalls", t.llmCalls());
                data.put("thinkingDurationMs", t.thinkingDurationMs());
                data.put("toolDurationSumMs", t.toolDurationSumMs());
                data.put("toolDurationWallMs", t.toolDurationWallMs());
                data.put("toolsRun", t.toolsRun());
                data.put("turnDurationMs", t.turnDurationMs());
                data.put("outputTokens", throughput.outputTokens());
                data.put("reasoningTokens", throughput.reasoningTokens());
                data.put("tokensPerSecond", throughput.tokensPerSecond());
                data.put("requestTokensPerSecond", throughput.tokensPerSecond());
                data.put("serverTokensPerSecond", throughput.reportedTokensPerSecond());
                data.put("activeMs", throughput.activeMs());
                data.put("reportedMs", throughput.reportedMs());
                data.put("reportedOutputTokens", throughput.reportedOutputTokens());
                data.put("completedRequests", throughput.completedRequests());
                data.put("reportedRequests", throughput.reportedRequests());
                data.put("outputEstimated", throughput.outputEstimated());
                yield data;
            }
            case AgentEvent.SubagentStarted t -> {
                final var data = new LinkedHashMap<String, Object>();
                data.put("subagentId", t.subagentId() == null ? "" : t.subagentId());
                data.put("subagentName", t.subagentName() == null ? "" : t.subagentName());
                if (t.model() != null) {
                    data.put("model", t.model());
                }
                yield data;
            }
            case AgentEvent.SubagentCompleted t -> {
                final var data = new LinkedHashMap<String, Object>();
                data.put("success", t.success());
                data.put("cancelled", t.cancelled());
                data.put("subagentId", t.subagentId() == null ? "" : t.subagentId());
                data.put("subagentName", t.subagentName() == null ? "" : t.subagentName());
                if (t.model() != null) {
                    data.put("model", t.model());
                }
                yield data;
            }
            case AgentEvent.SubagentDelivered t ->
                Map.of(
                        "success", t.success(),
                        "cancelled", t.cancelled(),
                        "subagentId", t.subagentId() == null ? "" : t.subagentId());
            case AgentEvent.Compacted t -> {
                final var data = new LinkedHashMap<String, Object>();
                if (t.tokens() != null) {
                    data.put("tokensBefore", t.tokens().before());
                    data.put("tokensAfter", t.tokens().after());
                }
                yield data;
            }
            default -> Map.of();
        };
    }
}
