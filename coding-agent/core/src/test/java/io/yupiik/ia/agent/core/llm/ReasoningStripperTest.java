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
package io.yupiik.ia.agent.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.yupiik.ia.agent.core.configuration.ReasoningFormat;
import org.junit.jupiter.api.Test;

class ReasoningStripperTest {

    @Test
    void thinkTagsDropsWholeLineMarkers() {
        final var raw = "Let me answer.\n think Let me think\n response The answer is 42\nAnd here.\n";
        assertEquals(
                "Let me answer.\n response The answer is 42\nAnd here.\n\n",
                ReasoningStripper.strip(raw, ReasoningFormat.thinkTags()));
    }

    @Test
    void cleanProseIsUntouched() {
        final var prose = "This is a plain answer with no reasoning at all.";
        assertEquals(prose, ReasoningStripper.strip(prose, ReasoningFormat.thinkTags()));
    }

    @Test
    void deepSeekR1StripsFromStartUntilClose() {
        final var raw = "I need to reason about this carefully.\n responseThe answer is 42.";
        assertEquals("The answer is 42.", ReasoningStripper.strip(raw, ReasoningFormat.deepSeekR1()));
    }

    @Test
    void deepSeekR1UnclosedReturnsEmpty() {
        // reasoning never closed (truncated): nothing visible survives
        assertEquals("", ReasoningStripper.strip("all honest thinking, cut short", ReasoningFormat.deepSeekR1()));
    }

    @Test
    void qwenStripsBlockAndClosesAtResponseLine() {
        final var raw = "head\n think reasoning here\n responseanswer middle\n<tool_call>keep the tool and tail";
        assertEquals("head\n<tool_call>keep the tool and tail", ReasoningStripper.strip(raw, ReasoningFormat.qwen()));
    }

    @Test
    void qwenUnclosedBlockEndsAtToolStart() {
        final var raw = "head\n think reasoning never closed\n<tool_call>keep the tool";
        assertEquals("head\n<tool_call>keep the tool", ReasoningStripper.strip(raw, ReasoningFormat.qwen()));
    }

    @Test
    void nullAndEmptyAreHandled() {
        assertEquals("", ReasoningStripper.strip(null, ReasoningFormat.thinkTags()));
        assertEquals("", ReasoningStripper.strip("", ReasoningFormat.thinkTags()));
    }
}
