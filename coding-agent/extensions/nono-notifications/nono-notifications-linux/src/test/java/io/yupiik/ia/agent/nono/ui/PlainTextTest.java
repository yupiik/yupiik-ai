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
package io.yupiik.ia.agent.nono.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlainTextTest {
    @Test
    void removesTerminalEscapesAndUnicodeDirectionControls() {
        assertEquals(
                "red link safe  text\nnext line ",
                PlainText.sanitize("\u001b[31mred\u001b[0m \u001b]8;;https://untrusted\u0007link\u001b]8;;\u001b\\ "
                        + "safe\u202e\u2066text\nnext\tline\u0000"));
        assertEquals("hello", PlainText.sanitize("\u009b31mhello\u009b0m"));
        assertEquals("safe", PlainText.sanitize("safe\u001b]unterminated"));
    }

    @Test
    void boundsTextWithVisibleTruncationWithoutSplittingSurrogatePairs() {
        final var result = PlainText.sanitize("\ud83d\ude00".repeat(5000));
        assertTrue(result.length() <= 4096);
        assertTrue(result.endsWith("... [truncated]"));
        assertFalse(result.contains("\ufffd"));
        assertEquals(
                0,
                result.codePoints()
                        .filter(cp -> Character.getType(cp) == Character.SURROGATE)
                        .count());
    }

    @Test
    void preservesPlainTextAndDoesNotInterpretMarkupOrShellSyntax() {
        final var text = "<b>Allow?</b> $(touch /tmp/no) `id` --timeout=0\nCaf\u00e9";
        assertEquals(text, PlainText.sanitize(text));
        assertEquals("", PlainText.sanitize(""));
    }
}
