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

import io.yupiik.ia.agent.core.shell.ThinkTagSplitter;
import io.yupiik.llm.client.model.LlmStreamEvent.ReasoningItem;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-LLM-request inline-reasoning splitter state: the shared {@link ThinkTagSplitter.Parser}, the
 * accumulated thinking text and the opaque reasoning items captured for multi-turn replay. One
 * holder per {@link AgentLoop}, reset per stream attempt (a retry is a fresh generation). Never
 * returns the mutable internals - {@link #thinking()} and {@link #reasoningItems()} return copies.
 */
public class StreamReasoningState {
    private ThinkTagSplitter.Parser inlineSplitter;
    private final StringBuilder accumulatedThinking = new StringBuilder();
    private final List<ReasoningItem> accumulatedReasoningItems = new ArrayList<>();

    /** Resets the inline-reasoning splitter for a fresh stream attempt (a retry is a new generation). */
    public void reset() {
        inlineSplitter = new ThinkTagSplitter().create();
        accumulatedThinking.setLength(0);
        accumulatedReasoningItems.clear();
    }

    /**
     * Feeds a wire {@code TextDelta} through the inline-reasoning splitter and returns the classified
     * segments. When the model streams reasoning through a dedicated channel the splitter sees a clean
     * {@code content} and emits a single visible {@code TextDelta} - a no-op aside from the one
     * extra parser pass. An empty return means the whole delta was absorbed into the parser's bounded
     * hold-tail (a candidate delimiter fragment): emit nothing now, the content surfaces when the
     * parser emits it as part of a later feed/flush.
     */
    public List<ThinkTagSplitter.Segment> feed(final String text) {
        final var parsed = inlineSplitter == null ? null : inlineSplitter.feed(text);
        return parsed == null ? List.of() : parsed;
    }

    /** Flushes the bounded hold-tail of the inline splitter at stream end so no answer tail is lost. */
    public List<ThinkTagSplitter.Segment> flush() {
        if (inlineSplitter == null) {
            return List.of();
        }
        final var flushed = inlineSplitter.flush();
        return flushed == null ? List.of() : flushed;
    }

    /** Appends thinking text accumulated from a classified THINKING segment. */
    public void appendThinking(final String text) {
        accumulatedThinking.append(text);
    }

    /** Records one opaque reasoning item for multi-turn replay. */
    public void addReasoningItem(final ReasoningItem item) {
        accumulatedReasoningItems.add(item);
    }

    /** Copy of the accumulated thinking text. */
    public String thinking() {
        return accumulatedThinking.toString();
    }

    /** Copy of the accumulated opaque reasoning items. */
    public List<ReasoningItem> reasoningItems() {
        return List.copyOf(accumulatedReasoningItems);
    }
}
