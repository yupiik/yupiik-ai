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
package io.yupiik.ia.agent.core.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LenientPatternTest {

    private static boolean find(final String regex, final String input) {
        return LenientPattern.compile(regex, 0).pattern().matcher(input).find();
    }

    @Test
    void validPatternIsNeverTouched() {
        final var result = LenientPattern.compile(".*(foo|bar)\\d+", 0);
        assertNotNull(result.pattern());
        assertFalse(result.adjusted());
        assertNull(result.originalError());
    }

    @Test
    void unbalancedParenInTopLevelAlternationIsRepaired() {
        // the observed model failure: one branch escapes its paren, another leaves a bare '(' open
        final var pattern = "private boolean handleKey|private boolean onKey|boolean handle\\(|private void dispatch"
                + "|boolean dispatch\\(|private boolean key(";
        final var result = LenientPattern.compile(pattern, 0);
        assertNotNull(result.pattern(), "pattern must compile after repair, got: " + result.originalError());
        assertTrue(result.adjusted(), "the unbalanced-paren pattern must be flagged as adjusted");
        // the repaired pattern still matches every intended branch
        assertTrue(find(pattern, "private boolean onKey"));
        assertTrue(find(pattern, "boolean handle( arg"));
        assertTrue(find(pattern, "private boolean key( arg"));
        assertTrue(find(pattern, "private void dispatch"));
    }

    @Test
    void singleStrayOpenParenIsEscaped() {
        final var result = LenientPattern.compile("foo(bar", 0);
        assertNotNull(result.pattern());
        assertTrue(result.adjusted());
        assertTrue(result.pattern().matcher("x foo(bar y").find());
    }

    @Test
    void singleStrayCloseParenIsEscaped() {
        final var result = LenientPattern.compile("foo)bar", 0);
        assertNotNull(result.pattern());
        assertTrue(result.adjusted());
        assertTrue(result.pattern().matcher("x foo)bar y").find());
    }

    @Test
    void balancedParensAreLeftAsGroups() {
        // `foo(bar)` compiles fine on its own -> never touched; the group stays a capturing group
        final var result = LenientPattern.compile("foo(bar)", 0);
        assertNotNull(result.pattern());
        assertFalse(result.adjusted());
        assertTrue(result.pattern().matcher("prefix foobar suffix").find(), "group must still capture");
    }

    @Test
    void parensInsideCharacterClassAreNotTouched() {
        // the class `[()]` holds literal parens and must be left alone; the branch-final `(x` is the stray
        final var result = LenientPattern.compile("[()]foo(x", 0);
        assertNotNull(result.pattern());
        assertTrue(result.adjusted());
        assertTrue(result.pattern().matcher(")foo(x").find());
    }

    @Test
    void unrepairablePatternReportsOriginalError() {
        final var result = LenientPattern.compile("(unclosed\\p{q}", 0);
        assertNull(result.pattern(), "an unrepairable pattern must not fabricate a result");
        assertFalse(result.adjusted());
        assertNotNull(result.originalError());
    }

    @Test
    void repairNeverChangesBranchesWithoutStrayParens() {
        final var result = LenientPattern.compile("onKey|key( arg", 0);
        assertNotNull(result.pattern());
        assertTrue(result.adjusted());
        assertTrue(result.pattern().matcher("onKey").find());
        assertTrue(result.pattern().matcher("key( arg").find());
    }
}
