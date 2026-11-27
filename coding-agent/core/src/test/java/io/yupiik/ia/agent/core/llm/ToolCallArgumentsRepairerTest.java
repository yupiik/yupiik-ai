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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.io.StringReader;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(TestJsonMapper.class)
class ToolCallArgumentsRepairerTest {

    private JsonMapper mapper;
    private ToolCallArgumentsRepairer repairer;

    @BeforeAll
    void init(final JsonMapper mapper) {
        this.mapper = mapper;
        repairer = new ToolCallArgumentsRepairer(mapper);
    }

    @Test
    void validArgumentsAreUntouched() {
        final var args = "{\"path\":\"src/main/java\",\"line\":12}";
        assertEquals(args, repairer.repair(args, true, "qwen3.8-27b"));
        // pretty printed (whitespace between tokens) stays valid too
        final var pretty = "{\n  \"path\": \"a b\",\n  \"line\": 1\n}";
        assertEquals(pretty, repairer.repair(pretty, true, "qwen3.8-27b"));
    }

    @Test
    void escapesBareControlCharsInsideStrings() {
        final var raw = "{\"command\":\"echo a\nb\",\"cwd\":\"/tmp\"}";
        final var repaired = repairer.repair(raw, true, "qwen3.8-27b");
        assertEquals("{\"command\":\"echo a\\nb\",\"cwd\":\"/tmp\"}", repaired);
        // tab inside a string value
        final var rawTab = "{\"pattern\":\"a\tb\"}";
        assertEquals("{\"pattern\":\"a\\tb\"}", repairer.repair(rawTab, true, "qwen3.8-27b"));
        // the repaired payload must parse
        assertTrue(isParseable(repaired));
    }

    @Test
    void preservesStructuralWhitespaceBetweenTokens() {
        // newlines/tabs as JSON whitespace (not in strings) must not be escaped
        final var pretty = "{\n\t\"path\": \"x\"\n}";
        assertEquals(pretty, repairer.repair(pretty, true, "qwen3.8-27b"));
    }

    @Test
    void alreadyEscapedSequencesAreNotDoubleEscaped() {
        final var args = "{\"x\":\"a\\nb\"}"; // already escaped \n
        assertEquals(args, repairer.repair(args, true, "qwen3.8-27b"));
    }

    @Test
    void unparseableOrUnfixableIsLeftUntouched() {
        final var garbage = "{\"command\": \"uv run python -c \"\nimport jax\n";
        assertEquals(garbage, repairer.repair(garbage, true, "qwen3.8-27b"));
        // disabled mode never touches anything
        final var raw = "{\"command\":\"echo a\nb\"}";
        assertEquals(raw, repairer.repair(raw, false, "qwen3.8-27b"));
    }

    @Test
    void validJsonWithTrailingObjectIsTrimmed() {
        // the parse_error.101 signature: a complete tool-call object followed by a second object
        final var args = "{\"path\":\"src/main/java\",\"line\":12}{\"extra\":true}";
        final var repaired = repairer.repair(args, true, "qwen3.8-27b");
        assertEquals("{\"path\":\"src/main/java\",\"line\":12}", repaired);
        assertTrue(isParseable(repaired));
    }

    @Test
    void trailingArrayOrIncompleteSecondValueIsTrimmed() {
        assertEquals("{\"a\":1}", repairer.repair("{\"a\":1}[1,2,3]", true, "qwen3.8-27b"));
        // whitespace between the completed value and the trailing junk is fine
        assertEquals("{\"a\":1}", repairer.repair("{\"a\":1}  {\"b\":2}", true, "qwen3.8-27b"));
        // an incomplete re-emitted object (the model was cut mid-brace) is trimmed too
        assertEquals("{\"a\":1}", repairer.repair("{\"a\":1}{\"b\":2,", true, "qwen3.8-27b"));
    }

    @Test
    void unterminatedObjectIsLeftUntouched() {
        // no complete top-level value to cut at: nothing is invented or dropped
        final var unterminated = "{\"path\":\"src/main/java\",\"line\":12";
        assertEquals(unterminated, repairer.repair(unterminated, true, "qwen3.8-27b"));
    }

    @Test
    void trimTailGarbageCutsAnyTrailingGarbage() {
        // a second JSON value, an incomplete re-emitted object and free text are all trimmed
        assertEquals("{\"a\":1}", ToolCallArgumentsRepairer.trimTailGarbage("{\"a\":1}{\"b\":2}"));
        assertEquals("{\"a\":1}", ToolCallArgumentsRepairer.trimTailGarbage("{\"a\":1}  {\"b\":2}"));
        assertEquals("{\"a\":1}", ToolCallArgumentsRepairer.trimTailGarbage("{\"a\":1}{"));
        // only whitespace follows the closed value: nothing to cut
        assertEquals("{\"a\":1}   ", ToolCallArgumentsRepairer.trimTailGarbage("{\"a\":1}   "));
        // free text after the closed object is trailing garbage (the NVIDIA "trailing characters" 400)
        assertEquals("{\"a\":1}", ToolCallArgumentsRepairer.trimTailGarbage("{\"a\":1} trailing"));
        assertEquals("{\"a\":1}", ToolCallArgumentsRepairer.trimTailGarbage("{\"a\":1}\nimport jax\n"));
        assertEquals("{\"a\":1}", ToolCallArgumentsRepairer.trimTailGarbage("{\"a\":1}, and more"));
        assertEquals("{\"a\":1}", ToolCallArgumentsRepairer.trimTailGarbage("{\"a\":1}"));
        assertEquals("{\"a\":1", ToolCallArgumentsRepairer.trimTailGarbage("{\"a\":1"));
        assertEquals("", ToolCallArgumentsRepairer.trimTailGarbage(""));
        assertEquals("x", ToolCallArgumentsRepairer.trimTailGarbage("x"));
        // a brace inside a string value is not a closing brace
        assertEquals("{\"a\":\"}\"}", ToolCallArgumentsRepairer.trimTailGarbage("{\"a\":\"}\"}{\"b\":1}"));
    }

    @Test
    void freeTextTailIsRepairedEndToEnd() {
        // a valid object followed by free text is repaired to the valid object (the NVIDIA 400 case)
        final var args = "{\"path\":\"src/main/java\",\"line\":12} trailing text";
        assertEquals("{\"path\":\"src/main/java\",\"line\":12}", repairer.repair(args, true, "qwen3.8-27b"));
        // a trimmed result must always parse
        assertTrue(isParseable(repairer.repair(args, true, "qwen3.8-27b")));
    }

    @Test
    void nullAndBlankAreUntouched() {
        assertNull(repairer.repair(null, true, "qwen3.8-27b"));
        assertEquals("", repairer.repair("", true, "qwen3.8-27b"));
    }

    @Test
    void scanDetectsBareControlChars() {
        assertTrue(ToolCallArgumentsRepairer.hasBareControlChar("{\"a\":\"b\nc\"}"));
        assertFalse(ToolCallArgumentsRepairer.hasBareControlChar("{\"a\":\"b\\nc\"}")); // escaped
        assertFalse(ToolCallArgumentsRepairer.hasBareControlChar("{\n\"a\": \"b\"\n}")); // whitespace
        assertFalse(ToolCallArgumentsRepairer.hasBareControlChar("{\"a\":\"b\"}"));
    }

    private boolean isParseable(final String json) {
        try (final var reader = new StringReader(json)) {
            mapper.read(Map.class, reader);
            return true;
        } catch (final RuntimeException e) {
            return false;
        }
    }
}
