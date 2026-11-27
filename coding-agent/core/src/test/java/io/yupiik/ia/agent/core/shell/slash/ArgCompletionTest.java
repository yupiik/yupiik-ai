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
package io.yupiik.ia.agent.core.shell.slash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArgCompletionTest {

    private static final List<String> PERMISSIONS = List.of("ask", "auto_edit", "yolo", "full_yolo", "none", "show");

    @Test
    void previousTokenUsesAsciiSeparatorsButUnicodeAwareStripping() {
        assertEquals("", ArgCompletion.previousToken(null));
        assertEquals("", ArgCompletion.previousToken("\u2003\t\n"));
        assertEquals("last", ArgCompletion.previousToken("\u2003first\tsecond\nlast\u2003"));
        assertEquals("last", ArgCompletion.previousToken("first\u000b\flast"));
        assertEquals("first\u2003last", ArgCompletion.previousToken("first\u2003last"));
        assertEquals("first\u00a0last", ArgCompletion.previousToken("first\u00a0last"));
    }

    @Test
    void prefixTokenMatchesCandidate() {
        // "y" and "yo" are prefixes of "yolo"
        assertEquals(
                List.of("full_yolo", "yolo"),
                ArgCompletion.fuzzyResult("option", PERMISSIONS, "y").items());
        assertEquals(
                List.of("full_yolo", "yolo"),
                ArgCompletion.fuzzyResult("option", PERMISSIONS, "yo").items());
        // "auto" is a prefix of "auto_edit"
        assertEquals(
                List.of("auto_edit"),
                ArgCompletion.fuzzyResult("option", PERMISSIONS, "auto").items());
        // "a" prefixes both "ask" and "auto_edit"
        assertEquals(
                List.of("ask", "auto_edit"),
                ArgCompletion.fuzzyResult("option", PERMISSIONS, "a").items());
    }

    @Test
    void infixTokenMatchesCandidateByDesign() {
        // containment is deliberate: "olo" is inside "yolo", "ini" inside "gpt-5-mini"
        // (the /model completion is reachable by typing the model's suffix)
        assertEquals(
                List.of("full_yolo", "yolo"),
                ArgCompletion.fuzzyResult("option", PERMISSIONS, "olo").items());
        assertEquals(
                List.of("gpt-5-mini"),
                ArgCompletion.fuzzyResult("option", List.of("gpt-5-mini", "gpt-5"), "mini")
                        .items());
    }

    @Test
    void emptyTokenMatchesEveryCandidateSortedNaturally() {
        assertEquals(
                List.of("ask", "auto_edit", "full_yolo", "none", "show", "yolo"),
                ArgCompletion.fuzzyResult("option", PERMISSIONS, "").items());
        assertEquals(
                ArgCompletion.fuzzyResult("option", PERMISSIONS, "").items(),
                ArgCompletion.fuzzyResult("option", PERMISSIONS, null).items());
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertEquals(
                ArgCompletion.fuzzyResult("option", PERMISSIONS, "Y").items(),
                ArgCompletion.fuzzyResult("option", PERMISSIONS, "y").items());
        assertEquals(
                List.of("full_yolo", "yolo"),
                ArgCompletion.fuzzyResult("option", PERMISSIONS, "YOLO").items());
    }

    @Test
    void blankAndNullCandidatesAreSkipped() {
        final var result = ArgCompletion.fuzzyResult("option", Arrays.asList("build", " ", null, "plan"), "b");
        assertEquals(List.of("build"), result.items());
    }

    @Test
    void noCandidatesOrNoMatchIsEmpty() {
        assertTrue(ArgCompletion.fuzzyResult("option", List.of(), "y").empty());
        assertTrue(ArgCompletion.fuzzyResult("option", null, "y").empty());
        assertTrue(ArgCompletion.fuzzyResult("option", PERMISSIONS, "zz").empty());
    }
}
