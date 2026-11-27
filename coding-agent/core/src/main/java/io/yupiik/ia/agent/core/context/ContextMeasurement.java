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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Session-local input accounting. Request shape, not estimated size, determines anchor validity. */
public final class ContextMeasurement {
    private final Map<String, Double> corrections = new ConcurrentHashMap<>();
    private volatile Observation observation;
    private volatile Snapshot latest = new Snapshot(0, 0, "heuristic", -1, 0, -1);

    /** Immutable fingerprints exclude local transcript metadata and include only model-visible data. */
    public record Payload(String modelKey, String toolsKey, List<String> messages, int estimatedTokens) {
        public Payload {
            messages = List.copyOf(messages);
        }
    }

    /** Budget includes uncertainty only for estimated content; it never pads a measured unchanged input. */
    public record Snapshot(
            int tokens, int budgetTokens, String source, int lastInputTokens, int pendingTokens, double correction) {}

    private record Observation(Payload payload, int inputTokens) {}

    public void observe(final Payload payload, final int inputTokens) {
        if (inputTokens <= 0 || payload.estimatedTokens() <= 0) {
            // Missing usage must not destroy previously learned tokenizer information.
            invalidate();
            return;
        }
        corrections.put(payload.modelKey(), Math.clamp((double) inputTokens / payload.estimatedTokens(), 0.1, 10.0));
        observation = new Observation(payload, inputTokens);
        measure(payload);
    }

    /** A reduction invalidates the exact request, not the learned model-specific correction. */
    public void invalidate() {
        observation = null;
        final var current = latest;
        latest = new Snapshot(
                current.tokens(),
                count(current.tokens() * 1.2),
                current.correction() > 0 ? "calibrated" : "heuristic",
                -1,
                0,
                current.correction());
    }

    public Snapshot latest() {
        return latest;
    }

    public Snapshot measure(final Payload payload) {
        final var correction = corrections.get(payload.modelKey());
        final var ratio = correction == null ? 1.0 : correction;
        final var anchor = observation;
        if (anchor != null && extendsPayload(payload, anchor.payload())) {
            final var tail =
                    Math.max(0, payload.estimatedTokens() - anchor.payload().estimatedTokens());
            final var pending = count(tail * ratio);
            final var tokens = count((double) anchor.inputTokens() + pending);
            return latest = new Snapshot(
                    tokens,
                    count((double) anchor.inputTokens() + pending * 1.2),
                    payload.messages().size() == anchor.payload().messages().size()
                            ? "provider-reported"
                            : "provider-anchored",
                    anchor.inputTokens(),
                    pending,
                    ratio);
        }
        final var tokens = count(payload.estimatedTokens() * ratio);
        return latest = new Snapshot(
                tokens,
                count(tokens * 1.2),
                correction == null ? "heuristic" : "calibrated",
                -1,
                0,
                correction == null ? -1 : ratio);
    }

    private boolean extendsPayload(final Payload payload, final Payload base) {
        return payload.modelKey().equals(base.modelKey())
                && payload.toolsKey().equals(base.toolsKey())
                && payload.messages().size() >= base.messages().size()
                && payload.messages().subList(0, base.messages().size()).equals(base.messages());
    }

    private static int count(final double value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, Math.round(value)));
    }
}
