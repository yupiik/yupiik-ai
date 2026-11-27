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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.yupiik.ia.agent.core.shell.ThinkTagSplitter;
import io.yupiik.llm.client.model.LlmStreamEvent.ReasoningItem;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StreamReasoningStateTest {
    @Test
    void feedClassifiesThinkingAndVisible() {
        final var state = new StreamReasoningState();
        state.reset();
        // the <thinking opener is consumed as a boundary: what follows is THINKING
        assertEquals(
                List.of(new ThinkTagSplitter.Segment(ThinkTagSplitter.Channel.THINKING, "hidden")),
                state.feed("<think>hidden"));
        // the </think> closer switches back to VISIBLE
        assertEquals(
                List.of(new ThinkTagSplitter.Segment(ThinkTagSplitter.Channel.VISIBLE, " world")),
                state.feed("</think> world"));
    }

    @Test
    void ambiguousDelimiterTailIsHeldUntilFlush() {
        final var state = new StreamReasoningState();
        state.reset();
        // a trailing "<thi" could grow into the <thinking opener: unambiguous text streams
        // immediately, the ambiguous tail is withheld
        assertEquals(
                List.of(new ThinkTagSplitter.Segment(ThinkTagSplitter.Channel.VISIBLE, "please ")),
                state.feed("please <thi"));
        // the held tail is only classified at stream end
        assertEquals(List.of(new ThinkTagSplitter.Segment(ThinkTagSplitter.Channel.VISIBLE, "<thi")), state.flush());
    }

    @Test
    void openTagSplitAcrossFeedsIsRecognized() {
        final var state = new StreamReasoningState();
        state.reset();
        // "<think" alone is not a complete opener (the closing > is missing): held
        assertEquals(List.of(), state.feed("<think"));
        // the completing ">" opens the THINKING channel: what follows streams as THINKING
        assertEquals(
                List.of(new ThinkTagSplitter.Segment(ThinkTagSplitter.Channel.THINKING, "hidden")),
                state.feed(">hidden"));
    }

    @Test
    void closeTagSplitAcrossFeedsIsRecognized() {
        final var state = new StreamReasoningState();
        state.reset();
        assertEquals(
                List.of(new ThinkTagSplitter.Segment(ThinkTagSplitter.Channel.THINKING, "reasoning")),
                state.feed("<think>reasoning"));
        // "</thi" (missing the final >) may still grow into the closer: held
        assertEquals(List.of(), state.feed("</thi"));
        // the completing ">" closes the reasoning channel: the tail streams as VISIBLE
        assertEquals(
                List.of(new ThinkTagSplitter.Segment(ThinkTagSplitter.Channel.VISIBLE, " done")),
                state.feed("nk> done"));
    }

    @Test
    void feedBeforeResetIsSafelyIgnored() {
        final var state = new StreamReasoningState();
        assertEquals(List.of(), state.feed("ignored before reset"));
        assertEquals(List.of(), state.flush());
    }

    @Test
    void thinkingAndReasoningItemsAreCopies() {
        final var state = new StreamReasoningState();
        state.reset();
        state.appendThinking("thought");
        final var item = new ReasoningItem("r1", List.of(Map.of("k", "v")), "enc");
        state.addReasoningItem(item);

        assertEquals("thought", state.thinking());
        final var items = state.reasoningItems();
        assertEquals(1, items.size());
        assertSame(item, items.get(0));

        // both accessors must return defensive copies, not the mutable internals
        assertNotSame(state.reasoningItems(), state.reasoningItems());
    }

    @Test
    void resetClearsEverything() {
        final var state = new StreamReasoningState();
        state.reset();
        state.appendThinking("old");
        state.addReasoningItem(new ReasoningItem("r1", List.of(), null));
        state.feed("a");
        state.reset();
        assertEquals("", state.thinking());
        assertEquals(List.of(), state.reasoningItems());
        // a fresh parser: the old held content is gone
        assertEquals(List.of(new ThinkTagSplitter.Segment(ThinkTagSplitter.Channel.VISIBLE, "b")), state.feed("b"));
    }
}
