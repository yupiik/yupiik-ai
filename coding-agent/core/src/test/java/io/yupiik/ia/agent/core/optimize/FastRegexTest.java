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
package io.yupiik.ia.agent.core.optimize;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Focused coverage of {@link FastRegex}: the keyword-alternation specialization, the anchored-prefix
 * specialization, and the guarded regex fallback. Also proves the matching results are equivalent to
 * what the plain {@link java.util.regex.Pattern} would produce for the same pattern.
 */
class FastRegexTest {

    @Test
    void keywordAlternationMatchesWholeWordsOnly() {
        final var regex = new FastRegex("\\b(WARNING|WARN|SEVERE|ERROR|FATAL)\\b");
        assertTrue(regex.find("you hit an ERROR in the log"));
        assertTrue(regex.find("WARNING: something"));
        assertTrue(regex.find("FATAL outcome here")); // case-sensitive keyword
        assertTrue(regex.find("SEVERE failure"));
        // sub-words must not match (word boundaries preserved)
        assertFalse(regex.find("this is warningly written"));
        assertFalse(regex.find("no errors at all")); // "errors", not "error"
        assertFalse(regex.find("nothing relevant"));
    }

    @Test
    void anchoredPrefixSpecialization() {
        final var regex = new FastRegex("^(Starting Servlet engine|Initializing Spring|Completed initialization) .*");
        assertTrue(regex.find("Starting Servlet engine: [Tomcat]"));
        assertTrue(regex.find("Initializing Spring ApplicationContext"));
        assertFalse(regex.find("x Starting Servlet engine"));
    }

    @Test
    void guardedFallbackStillMatches() {
        // general pattern that the specialized recognizers do not handle -> guarded regex path
        final var regex = new FastRegex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*(?:INFO|DEBUG)");
        assertTrue(regex.find("2026-10-09T12:00:00.000 INFO main started"));
        assertTrue(regex.find("2026-10-09T12:00:00.000 DEBUG main stuff"));
        assertFalse(regex.find("2026-10-09T12:00:00.000 ERROR main boom"));
        assertFalse(regex.find("plain line with no timestamp"));
    }

    @Test
    void invalidPatternNeverMatchesInsteadOfThrowing() {
        final var regex = new FastRegex("[");
        assertFalse(regex.find("anything")); // must not throw on a broken pattern
    }

    @Test
    void boundedFallbackShortCircuitsOnMissingRequiredLiteral() {
        // the `--- [` separator is a required literal; a line without it cannot match and must be
        // rejected by the pre-check, proving the guard kicks in
        final var regex = new FastRegex(".*--- \\[[^\\]]*\\].*INFO");
        assertTrue(regex.find("12:00:00.123 9 --- [main] c.Foo : INFO started"));
        assertFalse(regex.find("12:00:00.123  INFO 9 : no bracket pipeline"));
    }
}
