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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolCallMarkupStripperTest {
    private final ToolCallMarkupStripper stripper = new ToolCallMarkupStripper();

    private static String dsmlBlock(final String inner) {
        return "<\uFF5CDSML\uFF5Ctool_calls>\n" + inner + "\n</\uFF5CDSML\uFF5Ctool_calls>";
    }

    private static String invoke(final String name, final String body) {
        return "<\uFF5CDSML\uFF5Cinvoke " + name + ">\n" + body + "\n</\uFF5CDSML\uFF5Cinvoke>";
    }

    @Test
    void containsMarkupDetectsOnlyRealMarkerFamilies() {
        assertFalse(stripper.containsMarkup(null));
        assertFalse(stripper.containsMarkup(""));
        assertFalse(stripper.containsMarkup("just prose about opencode's todo tool"));
        // prose that merely *discusses* tools must not be mistaken for markup
        assertFalse(stripper.containsMarkup("opencode's tool list has no write_todo"));
        assertTrue(stripper.containsMarkup("A\n\n<|DSML|tool_calls>\n..."));
        assertTrue(stripper.containsMarkup("<tool_call>{\"name\":\"read\"}</tool_call>"));
        assertTrue(stripper.containsMarkup("A\n\n<|tool_calls|>\n..."));
    }

    @Test
    void cleanTextIsPassedThroughUnchanged() {
        final var text = "just an answer with a `code` fence\n\n- item\n```\ncontent\n```";
        // the fast path returns the very same instance
        assertSame(text, stripper.strip(text));
    }

    @Test
    void nullAndEmptyInputs() {
        assertEquals("", stripper.strip(null));
        assertEquals("", stripper.strip(""));
        assertEquals("   ", stripper.strip("   "));
    }

    @Test
    void trailingDsmlBlockIsRemoved() {
        final var raw = "Use the exec method.\n\n" + dsmlBlock(invoke("name=run", "{\"cmd\":\"ls\"}"));
        assertEquals("Use the exec method.", stripper.strip(raw));
    }

    @Test
    void proseOnBothSidesOfTheBlockIsKept() {
        final var raw = "Before.\n\n" + dsmlBlock(invoke("name=run", "{}")) + "\n\nAfter.";
        assertEquals("Before.\n\nAfter.", stripper.strip(raw));
    }

    @Test
    void multipleInvokesInsideOneOuterBlockAreRemovedTogether() {
        final var raw = "A\n\n" + dsmlBlock(invoke("name=a", "{}") + "\n" + invoke("name=b", "{}")) + "\n\nB";
        assertEquals("A\n\nB", stripper.strip(raw));
    }

    @Test
    void invokeWithoutOuterBlockIsStillRemoved() {
        // DeepSeek V4 intermittently emits a complete invoke while the outer tool_calls
        // opener is missing or corrupted.
        final var raw = "A\n\n<\uFF5CDSML\uFF5Cinvoke name=read>\n{}\n<\uFF5CDSML\uFF5Cend_tools>\n\nB";
        assertEquals("A\n\nB", stripper.strip(raw));
    }

    @Test
    void legacyV31MarkersAreRemoved() {
        final var begin = "<\uFF5Ctool\u2581calls\u2581begin\uFF5C>";
        final var end = "<\uFF5Ctool\u2581calls\u2581end\uFF5C>";
        final var raw = "A\n\n" + begin + "read\n```json\n{\"path\":\"x\"}\n```" + end + "\n\nB";
        assertEquals("A\n\nB", stripper.strip(raw));
    }

    @Test
    void asciiMangledDsmlMarkersAreRemoved() {
        final var raw = "A\n\n<|DSML|tool_calls>\n<|DSML|invoke name=read>\n</|DSML|tool_calls>";
        assertEquals("A", stripper.strip(raw));
    }

    @Test
    void asciiGenericToolCallsMarkersAreRemoved() {
        final var raw = "A\n\n<|tool_calls|>\n{\"a\":1}\n</|tool_calls|>\n\nB";
        assertEquals("A\n\nB", stripper.strip(raw));
    }

    @Test
    void mixedPipeWidthsAreTolerated() {
        // opener in fullwidth, closer mangled to ASCII
        final var raw = "A\n\n<\uFF5CDSML\uFF5Ctool_calls>\n{}\n</|DSML|tool_calls>\n\nB";
        assertEquals("A\n\nB", stripper.strip(raw));
    }

    @Test
    void orphanClosersAreSwept() {
        assertEquals("Answer here", stripper.strip("Answer</\uFF5CDSML\uFF5Ctool_calls> here"));
    }

    @Test
    void unterminatedBlockIsCutToTheEnd() {
        final var raw = "Answer.\n\n<\uFF5CDSML\uFF5Ctool_calls>\n<\uFF5CDSML\uFF5Cinvoke name=read>\n{\"p\":1}";
        assertEquals("Answer.", stripper.strip(raw));
    }

    @Test
    void qwenJsonTagIsRemoved() {
        final var raw =
                "Let me check.\n\n<tool_call>\n{\"name\": \"read\", \"arguments\": {\"path\": \"a.txt\"}}\n</tool_call>";
        assertEquals("Let me check.", stripper.strip(raw));
    }

    @Test
    void glmKvTagIsRemoved() {
        final var raw = "Answering.\n\n<tool_call>search_web<arg_key>q</arg_key><arg_value>foo</arg_value></tool_call>";
        assertEquals("Answering.", stripper.strip(raw));
    }

    @Test
    void proseAroundXmlTagsIsPreserved() {
        final var raw = "Before\n\n<tool_call>{\"name\":\"a\"}</tool_call>\n\nAfter";
        assertEquals("Before\n\nAfter", stripper.strip(raw));
    }
}
