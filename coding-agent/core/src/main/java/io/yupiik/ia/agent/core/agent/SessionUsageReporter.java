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

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.usage.QuotaSnapshotStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Session usage/account-quota reporting: emits {@link io.yupiik.ia.agent.core.agent.AgentEvent.UsageUpdated}
 * events (the frontends' single source for the sidebar gauge), caches the last published context-gauge
 * values and reads the per-session quota snapshot. Kept separate from {@link ContextGuidance} (which
 * renders the volatile-tail usage line and nudges).
 */
public class SessionUsageReporter {
    private final Path workdir;
    private final UsageTracker usageTracker;
    private final QuotaSnapshotStore quotaStore;
    private final Emitter emitter;
    private final Supplier<String> currentSessionId;
    private final String sessionId;
    private final Supplier<ChatSettings> chatSettings;
    private final String subagentId;
    private final String subagentName;

    private volatile int lastUsageContextTokens = -1;
    private volatile double lastUsageContextPercent = -1;
    private volatile String lastUsageModel;
    private volatile String quotaSessionId;
    private volatile boolean closed;
    private final ScopedValue<Boolean> attachingQuota = ScopedValue.newInstance();

    public SessionUsageReporter(
            final UsageTracker usageTracker,
            final QuotaSnapshotStore quotaStore,
            final Emitter emitter,
            final Supplier<String> currentSessionId,
            final String sessionId,
            final Supplier<ChatSettings> chatSettings,
            final String subagentId,
            final String subagentName,
            final Path workdir) {
        this.workdir = Objects.requireNonNull(workdir, "workdir");
        this.usageTracker = usageTracker;
        this.quotaStore = quotaStore;
        this.emitter = emitter;
        this.currentSessionId = currentSessionId;
        this.sessionId = sessionId;
        this.chatSettings = chatSettings;
        this.subagentId = subagentId;
        this.subagentName = subagentName;
    }

    public void emitUsage(final List<Message> messages, final int toolsTokens, final String model) {
        lastUsageModel = model;
        final var effective = usageTracker.effectiveContext(messages, toolsTokens);
        lastUsageContextTokens = effective;
        lastUsageContextPercent = usageTracker.contextPercent(effective);
        emit(usageUpdated(
                currentUsage(),
                effective,
                lastUsageContextPercent,
                usageTracker.lastRealPromptTokens(),
                usageTracker.calibrationRatio(),
                model,
                currentQuota()));
    }

    /**
     * Publishes a {@link io.yupiik.ia.agent.core.agent.AgentEvent.UsageUpdated} carrying the PROVIDED
     * context estimate (never recomputed from the message list), so the caller can align the
     * frontends' context gauge with a value computed elsewhere - the compaction-progress notice uses
     * this to flip the sidebar to the exact same calibrated estimate (including the wire tool tokens)
     * that the notice prints, instead of leaving a stale pre-turn snapshot on screen.
     */
    public void pushUsageContext(final int estimatedContextTokens, final String model) {
        lastUsageModel = model;
        if (emitter == null) {
            return;
        }
        lastUsageContextTokens = estimatedContextTokens;
        lastUsageContextPercent = usageTracker == null ? 0 : usageTracker.contextPercent(estimatedContextTokens);
        emit(usageUpdated(
                currentUsage(),
                estimatedContextTokens,
                lastUsageContextPercent,
                usageTracker == null ? 0 : usageTracker.lastRealPromptTokens(),
                usageTracker == null ? -1 : usageTracker.calibrationRatio(),
                model,
                currentQuota()));
    }

    /**
     * Emits a quota-only {@link io.yupiik.ia.agent.core.agent.AgentEvent.UsageUpdated} (the frontends'
     * single source for the sidebar gauge) with the session's current token metrics. Used at
     * provider/model switch so the TUI and web reflect the new provider's account usage immediately,
     * and by {@code /usage} so a forced refresh reaches the sidebar instead of only the command's
     * table. The context fields are the last values a real turn published (never recomputed from an
     * empty list), so the push refreshes the quota without clobbering the live context gauge.
     */
    public void pushUsageQuota(final String model) {
        lastUsageModel = model;
        pushUsageQuota(model, currentQuota());
    }

    private void pushUsageQuota(final String model, final AgentEvent.UsageUpdated.Quota quota) {
        if (emitter == null) {
            return;
        }
        final var effective = lastUsageContextTokens >= 0 ? lastUsageContextTokens : 0;
        emit(usageUpdated(
                currentUsage(),
                effective,
                lastUsageContextPercent >= 0 ? lastUsageContextPercent : 0,
                usageTracker == null ? 0 : usageTracker.lastRealPromptTokens(),
                usageTracker == null ? -1 : usageTracker.calibrationRatio(),
                model,
                quota));
    }

    /** Subscribes before attach so even the first asynchronous fetch reaches the UI. */
    public void attachQuota(final ChatSettings settings) {
        if (quotaStore == null || closed) {
            return;
        }
        final var id = currentSessionId.get() != null ? currentSessionId.get() : sessionId;
        if (id == null || id.isBlank()) {
            return;
        }
        if (!id.equals(quotaSessionId)) {
            if (quotaSessionId != null) {
                quotaStore.close(quotaSessionId);
            }
            quotaSessionId = id;
            quotaStore.listen(id, () -> {
                if (!closed && id.equals(quotaSessionId) && !attachingQuota.isBound()) {
                    // A completion must read only the cache: never start another refresh from an event.
                    pushUsageQuota(lastUsageModel, cachedQuotaSnapshot());
                }
            });
        }
        // Legacy stores can complete inline; the caller already pushes that result. Only the
        // asynchronous completion needs an extra event, avoiding duplicate startup/switch pushes.
        ScopedValue.where(attachingQuota, true).run(() -> quotaStore.attach(id, settings));
    }

    /** Releases the callback and any pending refresh along with the owning session. */
    public void close() {
        closed = true;
        if (quotaStore != null && quotaSessionId != null) {
            quotaStore.close(quotaSessionId);
        }
    }

    /**
     * Attaches the session's current provider to the quota store (forcing a live usage fetch when the
     * provider enables Codex usage) and immediately pushes a {@code UsageUpdated}. Unlike the
     * provider-switch path, this also runs when the provider did not change, so a freshly opened
     * tab/session shows the sidebar gauge before any turn ran. Pre-first-turn safe: the store is
     * keyed on the loop's stable session id and the pushed event carries the same id so the shell
     * registry routes it to the owning shell.
     */
    public void refreshUsage(final String model) {
        lastUsageModel = model;
        attachQuota(chatSettings.get());
        pushUsageQuota(model);
    }

    private Usage currentUsage() {
        if (usageTracker == null) {
            return Usage.EMPTY;
        }
        return new Usage(
                (int) usageTracker.promptTokens(),
                (int) usageTracker.completionTokens(),
                (int) (usageTracker.promptTokens() + usageTracker.completionTokens()),
                usageTracker.cacheReadTokens(),
                0,
                (int) usageTracker.reasoningTokens(),
                0,
                (int) usageTracker.contextInputTokens());
    }

    /** Reads the per-session cached account quota without causing a refresh. */
    public AgentEvent.UsageUpdated.Quota cachedQuotaSnapshot() {
        if (quotaStore == null) {
            return AgentEvent.UsageUpdated.Quota.NONE;
        }
        // currentSessionId is only set once a turn actually runs; before the first turn it is null, and
        // feeding a null key into the quota store's ConcurrentHashMap.get would NPE (surfacing on the
        // web snapshot path as a silently-hung session restore). Prefer the live id, else the stable one.
        final var id = currentSessionId.get() != null ? currentSessionId.get() : sessionId;
        final var view = quotaStore.cachedSnapshot(id);
        return AgentEvent.UsageUpdated.Quota.of(view.enabled(), view.supported(), view.snapshot());
    }

    /** The current account quota for this session, refreshed at most once per {@code QuotaSnapshotStore}
     * TTL. Returns {@link AgentEvent.UsageUpdated.Quota#NONE} when the current provider has no usage
     * capability (so UIs hide the gauge).
     */
    private AgentEvent.UsageUpdated.Quota currentQuota() {
        if (quotaStore == null) {
            return AgentEvent.UsageUpdated.Quota.NONE;
        }
        // currentSessionId is only set once a turn runs: a provider switch (or /usage) issued before
        // the first turn must still read the quota attached under the loop's stable session id
        final var id = currentSessionId.get() != null ? currentSessionId.get() : sessionId;
        final var view = quotaStore.snapshot(id, false);
        return AgentEvent.UsageUpdated.Quota.of(view.enabled(), view.supported(), view.snapshot());
    }

    private AgentEvent.UsageUpdated usageUpdated(
            final Usage usage,
            final int estimatedContextTokens,
            final double contextPercent,
            final int lastRealPromptTokens,
            final double calibrationRatio,
            final String model,
            final AgentEvent.UsageUpdated.Quota quota) {
        return new AgentEvent.UsageUpdated(
                usage,
                estimatedContextTokens,
                contextPercent,
                lastRealPromptTokens,
                calibrationRatio,
                model,
                // pre-first-turn pushes (provider/model switch, /usage) must carry the loop's stable
                // id: ShellRegistry routes strictly by event sessionId and drops untagged events.
                currentSessionId.get() != null ? currentSessionId.get() : sessionId,
                subagentId,
                subagentName,
                quota,
                usageTracker == null ? null : usageTracker.lastContextMeasurement(),
                workdir);
    }

    private void emit(final AgentEvent.UsageUpdated event) {
        if (emitter != null) {
            emitter.emit(event);
        }
    }
}
