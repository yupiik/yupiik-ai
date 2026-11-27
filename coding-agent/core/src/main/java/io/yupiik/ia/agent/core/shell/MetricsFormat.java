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

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.configuration.MetricsConfiguration;
import io.yupiik.ia.agent.core.configuration.MetricsDisplayMode;
import io.yupiik.ia.agent.core.context.UsageTracker;
import java.util.Locale;

/**
 * Formatting helpers shared by the turn metrics displays: the CLI status line, the TUI status strip
 * and the headless benchmark output all render the same metrics, in the same shape.
 */
public final class MetricsFormat {
    private MetricsFormat() {
        // no-op.
    }

    /**
     * Formats the output throughput segment, the request rate and, when the provider publishes its
     * own generation time, the server view next to it. A turn whose throughput cannot be measured
     * (no output token, no known duration) yields an empty string so the caller can omit the segment.
     *
     * @param throughput the turn throughput, {@code null} tolerated.
     * @return for example {@code "request 42.5 tok/s · server 40.0 tok/s"},
     *         {@code "request ~42.5 tok/s"}, {@code "server 40.0 tok/s (partial)"}
     *         or {@code ""} when there is nothing to show.
     */
    public static String tokPerSec(final AgentEvent.Throughput throughput) {
        if (throughput == null) {
            return "";
        }
        final var measured = throughput.tokensPerSecond();
        final var reported = throughput.reportedTokensPerSecond();
        final var request = measured > 0
                ? String.format(Locale.ROOT, "request %s%.1f tok/s", throughput.outputEstimated() ? "~" : "", measured)
                : "";
        final var server = reported > 0
                ? String.format(
                        Locale.ROOT,
                        "server %.1f tok/s%s",
                        reported,
                        throughput.reportedRequests() < throughput.completedRequests() ? " (partial)" : "")
                : "";
        return request.isEmpty() ? server : server.isEmpty() ? request : request + " · " + server;
    }

    /**
     * Formats the provider-cache read segment for a prompt/token pair: {@code cached N (P%)}
     * where {@code P} is the share of full input served from cache. Empty when there is
     * nothing meaningful to show ({@code cacheRead <= 0} or {@code fullInput <= 0}, so no divide
     * by zero and nothing to report for a provider that never publishes cache metrics).
     *
     * @param cacheRead the cache-read prompt tokens.
     * @param fullInput the full input tokens, including cache reads and writes.
     * @return e.g. {@code "cached 1.2k (30%)"} or {@code ""}.
     */
    public static String cachedTokens(final long cacheRead, final long fullInput) {
        if (cacheRead <= 0 || fullInput <= 0) {
            return "";
        }
        final var percent = (int) Math.round(cacheRead * 100.0 / fullInput);
        return "cached " + tokens(cacheRead) + " (" + percent + "%)";
    }

    /**
     * Compact human form of a token count: {@code M} for millions above 1M, {@code k} for
     * thousands, at most one decimal place (whole when over 9).
     */
    public static String tokens(final long value) {
        if (value < 1_000) {
            return String.valueOf(value);
        }
        if (value < 1_000_000) {
            final var k = value / 1000.0;
            return (k > 9 ? String.format("%.0fk", k) : String.format("%.1fk", k)).replace(',', '.');
        }
        final var m = value / 1_000_000.0;
        return (m > 9 ? String.format("%.0fM", m) : String.format("%.1fM", m)).replace(',', '.');
    }

    /**
     * Compact "before -> after (reclaim %)" segment for a compaction notice, e.g.
     * {@code "513K -> 87K (83%)"} for {@code (513_000, 87_000)}. Empty when the pair is not
     * usable ({@code before <= 0}) or nothing was reclaimed ({@code after >= before}).
     *
     * @param before estimated context tokens before the reduction
     * @param after  estimated context tokens after the reduction
     * @return the segment, or {@code ""} when there is nothing meaningful to show.
     */
    public static String shrink(final int before, final int after) {
        if (before <= 0 || after < 0 || after >= before) {
            return "";
        }
        final var reclaimed = Math.min(after, before);
        final var percent = (int) Math.round((before - reclaimed) * 100.0 / before);
        return tokens(before) + " -> " + tokens(after) + " (" + percent + "%)";
    }

    /**
     * The {@code /metrics} report, always exactly two lines: the current display
     * configuration, then the last turn values (and the session totals when any
     * request ran). The CLI prints it as-is and the TUI shows it through its
     * notification channel, so both keep the same shape. When the display mode is
     * {@link MetricsDisplayMode#FULL} the second line adds the output/reasoning
     * token breakdown and a reasoning-specific throughput.
     *
     * @param metrics   the display configuration.
     * @param transcript the conversation transcript holding the last turn metrics,
     *                   {@code null} tolerated.
     * @param usage     the session usage totals, {@code null} tolerated.
     */
    public static String report(
            final MetricsConfiguration metrics, final ConversationTranscript transcript, final UsageTracker usage) {
        final var sb = new StringBuilder();
        sb.append("metrics: showPerTurn=")
                .append(metrics.showPerTurn())
                .append(" showSessionTotals=")
                .append(metrics.showSessionTotals())
                .append(" displayMode=")
                .append(metrics.displayMode().name());
        final var full = metrics.displayMode() == MetricsDisplayMode.FULL;
        final var hasTurn = transcript != null
                && (transcript.turnLlmCalls() > 0 || transcript.turnToolsRun() > 0 || transcript.turnDurationMs() > 0);
        sb.append('\n');
        if (hasTurn) {
            final var throughput = transcript.turnThroughput();
            final var reasoning = throughput == null ? 0 : throughput.reasoningTokens();
            sb.append(transcript.turnLlmCalls()).append(" LLM");
            if (transcript.turnThinkingMs() > 0) {
                sb.append(" (")
                        .append(DurationFormat.humanize(transcript.turnThinkingMs()))
                        .append(" think)");
            }
            if (full && reasoning > 0) {
                sb.append(" · out ")
                        .append(throughput.outputTokensOnly())
                        .append(" tok · reasoning ")
                        .append(reasoning)
                        .append(" tok");
            }
            sb.append(" · ")
                    .append(transcript.turnToolsRun())
                    .append(" tools (")
                    .append(DurationFormat.humanize(transcript.turnToolSumMs()))
                    .append('/')
                    .append(DurationFormat.humanize(transcript.turnToolWallMs()))
                    .append(")");
            final var throughputSegment = tokPerSec(throughput);
            if (!throughputSegment.isEmpty()) {
                sb.append(" · ").append(throughputSegment);
            }
            if (full && throughput != null && throughput.reasoningTokensPerSecond() > 0) {
                sb.append(" · reasoning/request ")
                        .append(String.format(Locale.ROOT, "%.1f tok/s", throughput.reasoningTokensPerSecond()));
            }
            final var cachedSegment = cachedTokens(transcript.cacheReadTokens(), transcript.contextInputTokens());
            if (!cachedSegment.isEmpty()) {
                sb.append(" · ").append(cachedSegment);
            }
            sb.append(" · ").append(DurationFormat.humanize(transcript.turnDurationMs()));
        } else {
            sb.append("(no turn yet)");
        }
        if (usage != null && usage.requests() > 0) {
            sb.append(" · session: ").append(usage.requests()).append(" LLM calls");
            if (full && usage.reasoningTokens() > 0) {
                sb.append(" · reasoning ").append(usage.reasoningTokens()).append(" tok");
            }
        }
        if (usage != null && usage.optimizedOutputs() > 0) {
            sb.append(" · optimized ").append(usage.optimizedOutputs()).append(" outputs");
            sb.append(" ~").append(tokens(usage.optimizedTokensSaved())).append(" tokens saved");
            if (full) {
                sb.append(" (").append(usage.optimizedCharsSaved()).append(" chars)");
            }
        }
        return sb.toString();
    }
}
