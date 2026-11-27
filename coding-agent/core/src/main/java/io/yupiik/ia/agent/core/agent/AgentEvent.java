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

import io.yupiik.ia.agent.core.context.CompactionResult.Mode;
import io.yupiik.ia.agent.core.context.ContextMeasurement;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.tools.FileMutation;
import io.yupiik.ia.agent.core.usage.CodexUsageSnapshot;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Events emitted on the global bus by agent loops and session operations. Every event is tagged
 * with the owning session (tab)'s {@code sessionId} plus any invocation subagent coordinates: this lets
 * {@code ShellRegistry} route a given event stream to the right shell when
 * several sessions run concurrently in the same process. Producers must always
 * set the session id - there is no implicit fallback - so convenience
 * constructors take it and the immutable session workdir explicitly.
 * Workdirs are resolved at the session/invocation boundary, never inferred by observers.
 */
public sealed interface AgentEvent {
    /** Ephemeral frontend-only detail. Never use in model messages, history, hooks, or logs. */
    record ToolDiagnostic(
            String name,
            String detail,
            long ordinal,
            String sessionId,
            String subagentId,
            String subagentName,
            Path workdir)
            implements AgentEvent {
        @Override
        public String toString() {
            return "ToolDiagnostic[name=" + name + ", ordinal=" + ordinal + ", sessionId=" + sessionId + "]";
        }
    }

    /**
     * @return the session (tab) that owns the turn this event belongs to. Always
     *         set by the producers: the registry routes strictly on it and drops
     *         events without a session.
     */
    String sessionId();

    /** The required, absolute, normalized root of the owning session or invocation. */
    Path workdir();

    /**
     * @return the subagent id when the event was produced inside a subagent turn,
     *         {@code null} (the default) for parent-turn events. All record
     *         implementations carry this component and override the default.
     */
    default String subagentId() {
        return null;
    }

    /**
     * @return the subagent display name when the event was produced inside a
     *         subagent turn, {@code null} (the default) for parent-turn events.
     */
    default String subagentName() {
        return null;
    }

    /**
     * Authoritative compaction operation lifecycle on the normal AgentEvent bus, not another
     * transcript line. Each admitted compact invocation emits STARTED and exactly one terminal
     * status with the same session-unique id. Retries/chunks are parts of that one pass.
     * No model continuation digest, Throwable, invented token counts or public summary is carried.
     * Consumers must not derive outcomes from legacy Notice or merged Compacted events.
     */
    record CompactionPass(
            String id,
            Status status,
            Mode mode,
            String detail,
            String sessionId,
            String subagentId,
            String subagentName,
            Path workdir)
            implements AgentEvent {
        public enum Status {
            STARTED,
            SUCCEEDED,
            SKIPPED,
            FAILED,
            CANCELLED
        }

        public CompactionPass {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(workdir, "workdir");
            if (id.isBlank()) {
                throw new IllegalArgumentException("a compaction id is required");
            }
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
        }
    }

    /** First visible feedback in a structural block, never a token or repaint timestamp. */
    record FeedbackStarted(
            String kind, long startedAt, String sessionId, String subagentId, String subagentName, Path workdir)
            implements AgentEvent {
        public FeedbackStarted {
            Objects.requireNonNull(workdir, "workdir");
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
        }
    }

    record TextDelta(String delta, String sessionId, String subagentId, String subagentName, Path workdir)
            implements AgentEvent {
        public TextDelta {
            Objects.requireNonNull(workdir, "workdir");
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
        }

        public TextDelta(String delta, String sessionId, final Path workdir) {
            this(delta, sessionId, null, null, workdir);
        }
    }

    /**
     * The streaming buffers must be discarded: the interrupted stream is retried from scratch and
     * the fragments received before the failure would otherwise duplicate the retried reply prefix.
     */
    record TextReset(String sessionId, String subagentId, String subagentName, Path workdir) implements AgentEvent {
        public TextReset {
            Objects.requireNonNull(workdir, "workdir");
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
        }

        public TextReset(String sessionId, final Path workdir) {
            this(sessionId, null, null, workdir);
        }
    }

    record ThinkingDelta(String delta, String sessionId, String subagentId, String subagentName, Path workdir)
            implements AgentEvent {
        public ThinkingDelta {
            Objects.requireNonNull(workdir, "workdir");
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
        }

        public ThinkingDelta(String delta, String sessionId, final Path workdir) {
            this(delta, sessionId, null, null, workdir);
        }
    }

    record ToolStarted(
            String name,
            String args,
            long ordinal,
            String sessionId,
            String subagentId,
            String subagentName,
            Path workdir)
            implements AgentEvent {
        public ToolStarted {
            Objects.requireNonNull(workdir, "workdir");
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
        }

        public ToolStarted(String name, String args, String sessionId, final Path workdir) {
            this(name, args, -1, sessionId, null, null, workdir);
        }
    }

    /** Execution-time UI data; emitted before ToolFinished, including completed writes of a failed patch. */
    record ToolFilesChanged(
            String name,
            List<FileMutation> files,
            long ordinal,
            String sessionId,
            String subagentId,
            String subagentName,
            Path workdir)
            implements AgentEvent {
        public ToolFilesChanged {
            files = List.copyOf(files);
            Objects.requireNonNull(workdir, "workdir");
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
        }
    }

    record ToolFinished(
            String name,
            String output,
            boolean success,
            boolean pending,
            long ordinal,
            String sessionId,
            String subagentId,
            String subagentName,
            Path workdir)
            implements AgentEvent {
        public ToolFinished {
            Objects.requireNonNull(workdir, "workdir");
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
        }

        public ToolFinished(String name, String output, boolean success, String sessionId, final Path workdir) {
            this(name, output, success, false, -1, sessionId, null, null, workdir);
        }

        public ToolFinished(
                String name,
                String output,
                boolean success,
                String sessionId,
                String subagentId,
                String subagentName,
                final Path workdir) {
            this(name, output, success, false, -1, sessionId, subagentId, subagentName, workdir);
        }
    }

    record ToolStream(
            String name,
            String fragment,
            long ordinal,
            String sessionId,
            String subagentId,
            String subagentName,
            Path workdir)
            implements AgentEvent {
        public ToolStream {
            Objects.requireNonNull(workdir, "workdir");
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
        }

        public ToolStream(String name, String fragment, String sessionId, final Path workdir) {
            this(name, fragment, -1, sessionId, null, null, workdir);
        }

        public ToolStream(
                String name,
                String fragment,
                String sessionId,
                String subagentId,
                String subagentName,
                final Path workdir) {
            this(name, fragment, -1, sessionId, subagentId, subagentName, workdir);
        }
    }

    record UsageUpdated(
            Usage usage,
            int estimatedContextTokens,
            double contextPercent,
            int lastRealPromptTokens,
            double calibrationRatio,
            String model,
            String sessionId,
            String subagentId,
            String subagentName,
            Quota quota,
            ContextMeasurement.Snapshot contextMeasurement,
            Path workdir)
            implements AgentEvent {
        public UsageUpdated {
            Objects.requireNonNull(workdir, "workdir");
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
        }

        public UsageUpdated(
                final Usage usage,
                final int estimatedContextTokens,
                final double contextPercent,
                final int lastRealPromptTokens,
                final double calibrationRatio,
                final String model,
                final String sessionId,
                final String subagentId,
                final String subagentName,
                final Quota quota,
                final Path workdir) {
            this(
                    usage,
                    estimatedContextTokens,
                    contextPercent,
                    lastRealPromptTokens,
                    calibrationRatio,
                    model,
                    sessionId,
                    subagentId,
                    subagentName,
                    quota,
                    null,
                    workdir);
        }

        public UsageUpdated(
                Usage usage, int estimatedContextTokens, double contextPercent, String sessionId, final Path workdir) {
            this(
                    usage,
                    estimatedContextTokens,
                    contextPercent,
                    0,
                    -1,
                    null,
                    sessionId,
                    null,
                    null,
                    Quota.NONE,
                    workdir);
        }

        /** The account quota carried by a usage event, {@link #NONE} when the provider does not expose usage. */
        public record Quota(
                boolean enabled,
                boolean supported,
                String planType,
                Double usedPercent,
                long windowSeconds,
                long resetAtEpochSeconds,
                String windowLabel,
                String creditsBalance,
                boolean creditsUnlimited,
                List<Window> windows) {

            public static final Quota NONE = new Quota(false, false, null, null, 0, 0, null, null, false, List.of());

            public Quota(
                    final boolean enabled,
                    final boolean supported,
                    final String planType,
                    final double usedPercent,
                    final long windowSeconds,
                    final long resetAtEpochSeconds,
                    final String windowLabel,
                    final String creditsBalance,
                    final boolean creditsUnlimited) {
                this(
                        enabled,
                        supported,
                        planType,
                        usedPercent,
                        windowSeconds,
                        resetAtEpochSeconds,
                        windowLabel,
                        creditsBalance,
                        creditsUnlimited,
                        List.of(new Window("primary", windowLabel, usedPercent, windowSeconds, resetAtEpochSeconds)));
            }

            public record Window(
                    String kind, String label, Double usedPercent, Long windowSeconds, Long resetAtEpochSeconds) {
                public String displayLabel() {
                    if (windowSeconds == null || windowSeconds <= 0) {
                        return label == null || label.isBlank() ? "quota" : label;
                    }
                    if (windowSeconds == 604800) {
                        return "weekly";
                    }
                    if (windowSeconds % 86400 == 0) {
                        return windowSeconds / 86400 + "d";
                    }
                    if (windowSeconds % 3600 == 0) {
                        return windowSeconds / 3600 + "h";
                    }
                    return windowSeconds % 60 == 0 ? windowSeconds / 60 + "m" : windowSeconds + "s";
                }
            }

            public static Quota of(final boolean enabled, final boolean supported, final CodexUsageSnapshot snapshot) {
                if (snapshot == null) {
                    return new Quota(false, false, null, null, 0, 0, null, null, false, List.of());
                }
                final var primary = snapshot.rateLimits() == null
                        ? null
                        : snapshot.rateLimits().stream()
                                .filter(l -> CodexUsageSnapshot.RateLimit.PRIMARY.equals(l.kind()))
                                .findFirst()
                                .orElse(null);
                final boolean unlimited = snapshot.credits() != null
                        && Boolean.TRUE.equals(snapshot.credits().unlimited());
                final var windows = snapshot.rateLimits() == null
                        ? List.<Window>of()
                        : snapshot.rateLimits().stream()
                                .map(limit -> new Window(
                                        limit.kind(),
                                        limit.label(),
                                        limit.usedPercent(),
                                        limit.limitWindowSeconds(),
                                        limit.resetAt()))
                                .toList();
                return new Quota(
                        enabled,
                        supported,
                        snapshot.planType(),
                        primary == null ? null : primary.usedPercent(),
                        primary == null || primary.limitWindowSeconds() == null ? 0 : primary.limitWindowSeconds(),
                        primary == null || primary.resetAt() == null ? 0 : primary.resetAt(),
                        primary == null ? null : primary.label(),
                        snapshot.credits() == null ? null : snapshot.credits().balance(),
                        unlimited,
                        windows);
            }

            public boolean visible() {
                return supported && planType != null;
            }
        }
    }

    /**
     * Logical (main) LLM request completion, emitted exactly once per {@code modelRoundProcessor.stream}
     * invocation regardless of how the dialect fragments the wire into {@code End} events (a usage-only
     * {@code End} plus a separate finish-reason {@code End}, or a single combined one). It is the single
     * authoritative count of real model requests per turn (used for {@code llmCalls} and per-request token
     * aggregation) and is intentionally distinct from transport attempts/retries, which are internal to the
     * stream call and never surface here. When the provider reports no usage, {@link Usage#EMPTY} is carried
     * and the request still counts - a real zero-usage request must never be skipped.
     *
     * <p>It is not emitted by the display-only quota/context refresh path ({@code pushUsageQuota} /
     * {@code refreshUsage}), so those synthetic {@link UsageUpdated}s never inflate the request or token
     * aggregates.
     *
     * @param usage the per-request usage as reported by the provider, {@link Usage#EMPTY} when none
     * @param model the resolved model that served the request
     * @param index 1-based ordinal of this logical request within the current turn (successive rounds count
     *        independently, 1, 2, 3, ...)
     */
    record LlmRequestCompleted(
            Usage usage,
            String model,
            int index,
            String sessionId,
            String subagentId,
            String subagentName,
            Path workdir)
            implements AgentEvent {
        public LlmRequestCompleted {
            Objects.requireNonNull(workdir, "workdir");
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
        }

        public LlmRequestCompleted(
                final Usage usage, final String model, final int index, final String sessionId, final Path workdir) {
            this(usage, model, index, sessionId, null, null, workdir);
        }
    }

    record Compacted(
            List<String> kinds,
            String mode,
            String sessionId,
            String subagentId,
            String subagentName,
            CompactionTokens tokens,
            Path workdir)
            implements AgentEvent {
        public Compacted {
            Objects.requireNonNull(workdir, "workdir");
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
        }

        /**
         * Estimated raw context size before/after the applied reductions, in tokens (never the
         * anchor-calibrated estimation, so the two sides compare the same currency). {@code null}
         * when the emitting path could not measure (e.g. events built by tests or legacy producers).
         */
        public record CompactionTokens(int before, int after) {}

        /** Convenience for the single-strategy case (explicit tool request, LLM-only compaction...). */
        public Compacted(String kind, String mode, String sessionId, final Path workdir) {
            this(List.of(kind), mode, sessionId, null, null, null, workdir);
        }

        /** Convenience for the merged-strategy case (several reductions of the same checkpoint). */
        public Compacted(List<String> kinds, String mode, String sessionId, final Path workdir) {
            this(kinds, mode, sessionId, null, null, null, workdir);
        }

        /** Convenience carrying the measured tokens (before/after, raw estimates). */
        public Compacted(
                List<String> kinds,
                String mode,
                String sessionId,
                String subagentId,
                String subagentName,
                final Path workdir) {
            this(kinds, mode, sessionId, subagentId, subagentName, null, workdir);
        }
    }

    record Status(String message, String sessionId, String subagentId, String subagentName, Path workdir)
            implements AgentEvent {
        public Status {
            Objects.requireNonNull(workdir, "workdir");
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
        }

        public Status(String message, final String sessionId, final Path workdir) {
            this(message, sessionId, null, null, workdir);
        }
    }

    /**
     * A transient provider-imposed delay: rate-limit throttling or a retry backoff
     * surfaced by the chat resilience layer. Unlike {@link Status}, a pause must
     * never replace the leading agent state (it is a side fact about the current
     * call, not the turn's state); frontends render it in their trailing
     * notification slot - the TUI's right-side {@code ⓘ} slot - and the next
     * pause overwrites it. It is not persisted: like {@link Status} it is
     * transient feedback, the caller reports the failure itself if retries
     * eventually exhaust.
     */
    record Pause(
            Kind kind,
            String message,
            long durationMs,
            String sessionId,
            String subagentId,
            String subagentName,
            Path workdir)
            implements AgentEvent {
        public Pause {
            Objects.requireNonNull(workdir, "workdir");
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
        }

        public enum Kind {
            THROTTLE,
            RETRY
        }

        public Pause(
                final Kind kind,
                final String message,
                final long durationMs,
                final String sessionId,
                final Path workdir) {
            this(kind, message, durationMs, sessionId, null, null, workdir);
        }

        public static Pause throttle(
                final String message, final long durationMs, final String sessionId, final Path workdir) {
            return new Pause(Kind.THROTTLE, message, durationMs, sessionId, workdir);
        }

        public static Pause retry(
                final String message, final long durationMs, final String sessionId, final Path workdir) {
            return new Pause(Kind.RETRY, message, durationMs, sessionId, workdir);
        }
    }

    /**
     * A durable, user-visible non-conversation notice. Unlike {@link Status},
     * this is rendered as a distinct transcript line (not only the transient
     * status bar) so feedback survives and is understandable in all cases,
     * including failures that would otherwise be silent.
     */
    record Notice(
            Level level,
            String message,
            String finishReason,
            String sessionId,
            String subagentId,
            String subagentName,
            Throwable cause,
            CompactionLifecycle compaction,
            Path workdir)
            implements AgentEvent {

        public enum Level {
            INFO,
            WARN,
            ERROR
        }

        /**
         * Compaction lifecycle stage of this notice, for the UI to pin/clear the compacting
         * indicator without parsing the message text. Always non-null: the reduced convenience
         * constructors pass {@link #NONE}; the canonical constructor requires an explicit value.
         */
        public enum CompactionLifecycle {
            /** Not a compaction lifecycle notice. */
            NONE,
            /** A compaction pass is starting (arm the indicator). */
            START,
            /** A compaction pass completed (clear the indicator). */
            COMPLETED,
            /** The compaction gate escalated (clear the indicator; the message carries the guidance). */
            GATE_ESCALATED
        }

        public Notice {
            Objects.requireNonNull(workdir, "workdir");
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
            Objects.requireNonNull(compaction, "compaction lifecycle is required");
        }

        public Notice(final Level level, final String message, final String sessionId, final Path workdir) {
            this(level, message, null, sessionId, null, null, null, CompactionLifecycle.NONE, workdir);
        }

        public Notice(
                final Level level,
                final String message,
                final String finishReason,
                final String sessionId,
                final Path workdir) {
            this(level, message, finishReason, sessionId, null, null, null, CompactionLifecycle.NONE, workdir);
        }

        /** Cause-less construction; {@code cause} defaults to {@code null}. */
        public Notice(
                final Level level,
                final String message,
                final String finishReason,
                final String sessionId,
                final String subagentId,
                final String subagentName,
                final Path workdir) {
            this(
                    level,
                    message,
                    finishReason,
                    sessionId,
                    subagentId,
                    subagentName,
                    null,
                    CompactionLifecycle.NONE,
                    workdir);
        }

        /** Construction with a cause and no compaction lifecycle. */
        public Notice(
                final Level level,
                final String message,
                final String finishReason,
                final String sessionId,
                final String subagentId,
                final String subagentName,
                final Throwable cause,
                final Path workdir) {
            this(
                    level,
                    message,
                    finishReason,
                    sessionId,
                    subagentId,
                    subagentName,
                    cause,
                    CompactionLifecycle.NONE,
                    workdir);
        }

        public static Notice info(final String message, final String sessionId, final Path workdir) {
            return new Notice(Level.INFO, message, sessionId, workdir);
        }

        public static Notice warn(final String message, final String sessionId, final Path workdir) {
            return new Notice(Level.WARN, message, sessionId, workdir);
        }

        public static Notice error(final String message, final String sessionId, final Path workdir) {
            return new Notice(Level.ERROR, message, sessionId, workdir);
        }

        /** Info lifecycle notice arming the compacting indicator. */
        public static Notice compactionStart(final String message, final String sessionId, final Path workdir) {
            return new Notice(
                    Level.INFO, message, null, sessionId, null, null, null, CompactionLifecycle.START, workdir);
        }

        /** Info lifecycle notice clearing the compacting indicator after a successful pass. */
        public static Notice compacted(final String message, final String sessionId, final Path workdir) {
            return new Notice(
                    Level.INFO, message, null, sessionId, null, null, null, CompactionLifecycle.COMPLETED, workdir);
        }

        /** Error lifecycle notice clearing the compacting indicator when the compaction gate escalates. */
        public static Notice compactionGateEscalated(final String message, final String sessionId, final Path workdir) {
            return new Notice(
                    Level.ERROR,
                    message,
                    null,
                    sessionId,
                    null,
                    null,
                    null,
                    CompactionLifecycle.GATE_ESCALATED,
                    workdir);
        }
    }

    record TurnDone(String content, String sessionId, String subagentId, String subagentName, Path workdir)
            implements AgentEvent {
        public TurnDone {
            Objects.requireNonNull(workdir, "workdir");
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
        }

        public TurnDone(String content, final String sessionId, final Path workdir) {
            this(content, sessionId, null, null, workdir);
        }
    }

    record TurnCancelled(String sessionId, String subagentId, String subagentName, Path workdir) implements AgentEvent {
        public TurnCancelled {
            Objects.requireNonNull(workdir, "workdir");
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
        }

        public TurnCancelled(final String sessionId, final Path workdir) {
            this(sessionId, null, null, workdir);
        }
    }

    record Error(String message, String sessionId, String subagentId, String subagentName, Path workdir)
            implements AgentEvent {
        public Error {
            Objects.requireNonNull(workdir, "workdir");
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
        }

        public Error(String message, final String sessionId, final Path workdir) {
            this(message, sessionId, null, null, workdir);
        }
    }

    /**
     * A sub-agent was spawned under the parent session: the parent transcript
     * records a marker so the user knows work is progressing off-screen
     * (details are visible through {@code /subagents}).
     *
     * @param model the effective model name when already resolved, {@code null} otherwise (the routing
     *              is usually resolved after the spawn, so started events carry {@code null}; the
     *              completion carries the resolved model)
     */
    record SubagentStarted(
            String task, String sessionId, String subagentId, String subagentName, String model, Path workdir)
            implements AgentEvent {
        public SubagentStarted {
            Objects.requireNonNull(workdir, "workdir");
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
        }

        public SubagentStarted(
                final String task,
                final String sessionId,
                final String subagentId,
                final String subagentName,
                final Path workdir) {
            this(task, sessionId, subagentId, subagentName, null, workdir);
        }
    }

    /**
     * A sub-agent finished (success, failure or cancellation). The parent transcript records a
     * marker; the detailed conversation stays reachable through {@code /subagents}.
     *
     * @param cancelled the sub-agent stopped on a cancel (parent interrupt or an explicit
     *                  {@code cancel_subagent}), not on a failure: reported separately so a
     *                  cancelled child never renders as a red error card
     */
    record SubagentCompleted(
            String summary,
            boolean success,
            boolean cancelled,
            String sessionId,
            String subagentId,
            String subagentName,
            String model,
            Path workdir)
            implements AgentEvent {
        public SubagentCompleted {
            Objects.requireNonNull(workdir, "workdir");
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
        }

        public SubagentCompleted(
                final String summary,
                final boolean success,
                final String sessionId,
                final String subagentId,
                final String subagentName,
                final Path workdir) {
            this(summary, success, false, sessionId, subagentId, subagentName, null, workdir);
        }

        public SubagentCompleted(
                final String summary,
                final boolean success,
                final boolean cancelled,
                final String sessionId,
                final String subagentId,
                final String subagentName,
                final Path workdir) {
            this(summary, success, cancelled, sessionId, subagentId, subagentName, null, workdir);
        }
    }

    /**
     * A finished sub-agent's result was injected into the parent turn's context as a model-visible
     * message. Unlike {@link SubagentCompleted} (a transcript marker), this signals the push delivery
     * so frontends can surface that the parent will now see the child's outcome on its next round.
     */
    record SubagentDelivered(
            String summary,
            boolean success,
            boolean cancelled,
            String sessionId,
            String subagentId,
            String subagentName,
            Path workdir)
            implements AgentEvent {
        public SubagentDelivered {
            Objects.requireNonNull(workdir, "workdir");
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
        }

        public SubagentDelivered(
                final String summary,
                final boolean success,
                final boolean cancelled,
                final String sessionId,
                final Path workdir) {
            this(summary, success, cancelled, sessionId, null, null, workdir);
        }
    }

    /**
     * A queued prompt was injected into the running turn before it completed, so
     * the user does not have to wait for the current turn to finish.
     */
    record PromptIntegrated(String text, String sessionId, String subagentId, String subagentName, Path workdir)
            implements AgentEvent {
        public PromptIntegrated {
            Objects.requireNonNull(workdir, "workdir");
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
        }

        public PromptIntegrated(String text, String sessionId, final Path workdir) {
            this(text, sessionId, null, null, workdir);
        }
    }

    /**
     * Request and provider-reported throughput with matched token/time coverage.
     *
     * @param outputTokens output tokens produced in the turn, as reported by the provider or estimated
     *        when the provider reports no usage
     * @param reasoningTokens output tokens spent on internal reasoning within {@code outputTokens}, 0
     *        when the provider does not report a breakdown
     * @param activeMs time spent inside LLM requests, excluding provider throttles, retry backoffs and
     *        everything outside of the LLM calls (tool executions, compaction, user typing, ...)
     * @param reportedMs generation time as reported by the provider itself, {@code 0} when the provider
     *        does not publish any
     * @param reportedOutputTokens completion tokens from the same requests as {@code reportedMs}
     * @param completedRequests completed logical main-model requests
     * @param reportedRequests requests with positive completion usage and provider timing
     * @param outputEstimated whether any positive output contribution was estimated
     */
    record Throughput(
            int outputTokens,
            int reasoningTokens,
            long activeMs,
            long reportedMs,
            int reportedOutputTokens,
            int completedRequests,
            int reportedRequests,
            boolean outputEstimated) {
        /** Zeroed throughput, used when a turn produced nothing. */
        public static final Throughput EMPTY = new Throughput(0, 0, 0, 0, 0, 0, 0, false);

        /**
         * Delivered output over logical request time, excluding tracked throttle/backoff waits.
         * Includes prefill, queueing, transport and failed attempts in a successful retry sequence,
         * but excludes processing after stream return. It is not a decode-only rate.
         *
         * @return the average output tokens per second, {@code 0} when it cannot be computed.
         */
        public double tokensPerSecond() {
            return activeMs > 0 && outputTokens > 0 ? outputTokens * 1000.0 / activeMs : 0;
        }

        /**
         * The output tokens that were not spent reasoning: {@code outputTokens} minus
         * {@code reasoningTokens}, never negative.
         *
         * @return the visible output token count.
         */
        public int outputTokensOnly() {
            return Math.max(0, outputTokens - reasoningTokens);
        }

        /**
         * Reasoning tokens over the full request interval, not a reasoning-only generation interval.
         *
         * @return the average reasoning tokens per second, {@code 0} when it cannot be computed.
         */
        public double reasoningTokensPerSecond() {
            return activeMs > 0 && reasoningTokens > 0 ? reasoningTokens * 1000.0 / activeMs : 0;
        }

        /**
         * The provider's view using only tokens from requests with usable provider timing.
         * Timing scope is provider-defined and need not be decode-only; returns {@code 0} when unknown.
         *
         * @return the average output tokens per second as seen by the server, {@code 0} when unknown.
         */
        public double reportedTokensPerSecond() {
            return reportedMs > 0 && reportedOutputTokens > 0 ? reportedOutputTokens * 1000.0 / reportedMs : 0;
        }

        /**
         * @return {@code true} when at least one of the two rates can be displayed.
         */
        public boolean measurable() {
            return tokensPerSecond() > 0 || reportedTokensPerSecond() > 0;
        }
    }

    /**
     * Per-turn aggregate metrics emitted at turn completion.
     *
     * @param llmCalls              number of (main) LLM requests in this turn, one per {@link LlmRequestCompleted}
     * @param thinkingDurationMs    sum of wall-clock durations of all thinking sequences in this turn
     * @param toolDurationSumMs     sum of individual tool durations (linear, e.g. 3 tools x 100ms = 300ms)
     * @param toolDurationWallMs    wall-clock batch duration: first tool start to last tool finish in parallel batches
     * @param toolsRun              total number of tool executions in this turn
     * @param turnDurationMs        total turn wall time: turn start to TurnDone
     * @param throughput            output tokens of the turn versus the time spent generating them
     */
    record TurnMetrics(
            int llmCalls,
            long thinkingDurationMs,
            long toolDurationSumMs,
            long toolDurationWallMs,
            int toolsRun,
            long turnDurationMs,
            Throughput throughput,
            String sessionId,
            String subagentId,
            String subagentName,
            Path workdir)
            implements AgentEvent {
        public TurnMetrics {
            Objects.requireNonNull(workdir, "workdir");
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
        }

        public TurnMetrics(
                int llmCalls,
                long thinkingDurationMs,
                long toolDurationSumMs,
                long toolDurationWallMs,
                int toolsRun,
                long turnDurationMs,
                String sessionId,
                final Path workdir) {
            this(
                    llmCalls,
                    thinkingDurationMs,
                    toolDurationSumMs,
                    toolDurationWallMs,
                    toolsRun,
                    turnDurationMs,
                    Throughput.EMPTY,
                    sessionId,
                    null,
                    null,
                    workdir);
        }

        public TurnMetrics(
                int llmCalls,
                long thinkingDurationMs,
                long toolDurationSumMs,
                long toolDurationWallMs,
                int toolsRun,
                long turnDurationMs,
                Throughput throughput,
                String sessionId,
                final Path workdir) {
            this(
                    llmCalls,
                    thinkingDurationMs,
                    toolDurationSumMs,
                    toolDurationWallMs,
                    toolsRun,
                    turnDurationMs,
                    throughput,
                    sessionId,
                    null,
                    null,
                    workdir);
        }
    }

    /**
     * Individual tool timing event emitted from ToolExecutionService.
     *
     * @param toolName         name of the tool that executed
     * @param durationMs       individual tool execution duration
     * @param wallDurationMs   for parallel batches: batch elapsed time (first start to last finish); for sequential: equals durationMs
     * @param parallelBatch    true if this tool was part of a parallel batch
     */
    record ToolTimingEvent(
            String toolName,
            long durationMs,
            long wallDurationMs,
            boolean parallelBatch,
            String sessionId,
            String subagentId,
            String subagentName,
            Path workdir)
            implements AgentEvent {
        public ToolTimingEvent {
            Objects.requireNonNull(workdir, "workdir");
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
        }

        public ToolTimingEvent(
                String toolName,
                long durationMs,
                long wallDurationMs,
                boolean parallelBatch,
                String sessionId,
                final Path workdir) {
            this(toolName, durationMs, wallDurationMs, parallelBatch, sessionId, null, null, workdir);
        }
    }

    /**
     * Emitted at well-defined phase boundaries of the task-solving loop so bench consumers can reconstruct
     * the critical path (model request sent -> stream complete -> tool execution -> next request) without
     * inferring it from wall-deltas. Carries no payload beyond the phase name; the timing is the event's
     * arrival time relative to {@code detail.log} ticks. Benchmark/observability marker: UIs may ignore it.
     *
     * @param name one of {@code request-sent}, {@code stream-complete}, {@code tool-batch-complete},
     *     {@code iteration-complete}.
     */
    record Phase(String name, String sessionId, String subagentId, String subagentName, Path workdir)
            implements AgentEvent {
        public Phase {
            Objects.requireNonNull(workdir, "workdir");
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
        }

        public static final String REQUEST_SENT = "request-sent";
        public static final String STREAM_COMPLETE = "stream-complete";
        public static final String TOOL_BATCH_COMPLETE = "tool-batch-complete";
        public static final String ITERATION_COMPLETE = "iteration-complete";

        // BOUNDED initialization telemetry: paired start/end markers that decompose the wall-clock
        // between process/run entry and the first REQUEST_SENT, so a slow pre-first-request startup
        // (JVM boot, DI bootstrap, runtime creation, model resolution, prompt build, initial-prompt
        // hook, provider hand-off) can be attributed without speculation. REQUEST_SENT itself is the
        // pre-processor marker: it is emitted immediately before the modelRoundProcessor.stream()
        // hand-off, i.e. it measures the request entering the provider layer, not the wire I/O that
        // happens inside stream(). Any time between the run/call entry and RUNTIME_CREATE_START is
        // NOT covered by these paired markers - it must be attributed externally. That pre-runtime
        // interval may include in-command setup (metrics/jump wiring) and is not necessarily JVM
        // boot: the run detail clock starts inside RunCommand, so nothing here claims process start.
        public static final String RUNTIME_CREATE_START = "runtime-create-start";
        public static final String RUNTIME_CREATE_END = "runtime-create-end";
        public static final String MODEL_RESOLUTION_START = "model-resolution-start";
        public static final String MODEL_RESOLUTION_END = "model-resolution-end";
        public static final String PROMPT_BUILD_START = "prompt-build-start";
        public static final String PROMPT_BUILD_END = "prompt-build-end";
        public static final String INITIAL_PROMPT_START = "initial-prompt-start";
        public static final String INITIAL_PROMPT_END = "initial-prompt-end";

        public Phase(final String name, final String sessionId, final Path workdir) {
            this(name, sessionId, null, null, workdir);
        }
    }
}
