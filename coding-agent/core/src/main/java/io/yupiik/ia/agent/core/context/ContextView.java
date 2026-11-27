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
package io.yupiik.ia.agent.core.context;

import io.yupiik.ia.agent.core.configuration.CompressConfiguration;
import io.yupiik.ia.agent.core.llm.Message;
import java.util.List;

/**
 * Bridge between the agent loop and context-aware tools: the loop publishes a live usage snapshot
 * every iteration, and tools (eg. {@code manage_context}) can drop a compaction/prune directive
 * that the loop applies after the current tool round. Single-turn, single-threaded by design.
 */
public class ContextView {

    /**
     * Immutable usage snapshot of the running conversation.
     *
     * @param messageCount size of the live message list (system prompt included)
     * @param effectiveTokens hybrid usage estimate (real-usage anchored when available)
     * @param contextWindow the provider context window in tokens
     * @param usableWindow the window minus the reserved output headroom
     * @param percent effective usage against {@code usableWindow}
     * @param pruneThresholdPercent configured pruning trigger threshold
     * @param compactThresholdPercent configured compaction trigger threshold
     */
    public record Snapshot(
            int messageCount,
            int effectiveTokens,
            int contextWindow,
            int usableWindow,
            double percent,
            int pruneThresholdPercent,
            int compactThresholdPercent) {}

    /**
     * A tool-requested or user-requested context operation.
     *
     * @param action {@code compact} or {@code prune}
     * @param focus optional hint folded into the summarizer prompt
     * @param keepLast optional minimum number of recent messages to keep verbatim (0 = default)
     * @param requestedBy tool name that requested the operation, or {@code "user"} for shell-triggered operations
     * @param force bypass the within-budget guard so compaction runs even below the thresholds
     * @param urgent user-requested compaction that falls back to destructive local truncation when
     *               the summarizer fails transiently instead of skipping the pass (opt-in)
     */
    public record Directive(
            String action, String focus, int keepLast, String requestedBy, boolean force, boolean urgent) {
        public Directive(String action, String focus, int keepLast, String requestedBy) {
            this(action, focus, keepLast, requestedBy, false, false);
        }

        public Directive(String action, String focus, int keepLast, String requestedBy, boolean force) {
            this(action, focus, keepLast, requestedBy, force, false);
        }
    }

    private final ContextCompression compression;
    private volatile Snapshot snapshot;
    private volatile Directive directive;
    private volatile List<Message> messages = List.of();

    public ContextView() {
        this(null);
    }

    public ContextView(final CompressConfiguration compression) {
        this.compression = new ContextCompression(compression);
    }

    public void publish(final Snapshot snapshot) {
        this.snapshot = snapshot;
    }

    /** Publishes the live working list so compression tools can resolve the refs the model saw. */
    public void publishMessages(final List<Message> messages) {
        this.messages = messages == null ? List.of() : messages;
        if (compression != null) {
            compression.publish(this.messages);
        }
    }

    public List<Message> messages() {
        return messages;
    }

    public ContextCompression compression() {
        return compression;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    /**
     * Queues a directive; rejected when one is already pending so a single turn can never stack
     * multiple model-initiated compactions.
     */
    public boolean request(final Directive directive) {
        if (this.directive != null) {
            return false;
        }
        this.directive = directive;
        return true;
    }

    public Directive poll() {
        final var d = directive;
        directive = null;
        return d;
    }

    /** Marks the digest user message of a compaction, used by status rendering and tests. */
    public static boolean isCompactionMarker(final Message message) {
        return message != null
                && "user".equals(message.role())
                && message.content() instanceof String s
                && (s.startsWith("<compacted-context>") || s.startsWith("<pruned-context>"));
    }
}
