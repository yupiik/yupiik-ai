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
package io.yupiik.ia.agent.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolOutputVisibilityTest {
    @Test
    void parseAcceptsKeywordsAndLegacyBooleans() {
        assertEquals(ToolOutputVisibility.SHOWN, ToolOutputVisibility.parse("show"));
        assertEquals(ToolOutputVisibility.SHOWN, ToolOutputVisibility.parse("shown"));
        assertEquals(ToolOutputVisibility.SHOWN, ToolOutputVisibility.parse("true"));
        assertEquals(ToolOutputVisibility.SHOWN, ToolOutputVisibility.parse(" on "));
        assertEquals(ToolOutputVisibility.COLLAPSED, ToolOutputVisibility.parse("collapse"));
        assertEquals(ToolOutputVisibility.COLLAPSED, ToolOutputVisibility.parse("collapsed"));
        assertEquals(ToolOutputVisibility.HIDDEN, ToolOutputVisibility.parse("hide"));
        assertEquals(ToolOutputVisibility.HIDDEN, ToolOutputVisibility.parse("hidden"));
        assertEquals(ToolOutputVisibility.HIDDEN, ToolOutputVisibility.parse("false"));
        assertEquals(ToolOutputVisibility.HIDDEN, ToolOutputVisibility.parse("off"));
        assertNull(ToolOutputVisibility.parse("bogus"));
        assertNull(ToolOutputVisibility.parse(null));
    }

    @Test
    void fromBooleanMapsLegacyFlag() {
        assertEquals(ToolOutputVisibility.SHOWN, ToolOutputVisibility.fromBoolean(true));
        assertEquals(ToolOutputVisibility.COLLAPSED, ToolOutputVisibility.fromBoolean(false));
    }

    @Test
    void nextRotatesThroughAllThreeStates() {
        assertEquals(ToolOutputVisibility.HIDDEN, ToolOutputVisibility.SHOWN.next());
        assertEquals(ToolOutputVisibility.SHOWN, ToolOutputVisibility.COLLAPSED.next());
        assertEquals(ToolOutputVisibility.COLLAPSED, ToolOutputVisibility.HIDDEN.next());
    }

    @Test
    void predicates() {
        assertTrue(ToolOutputVisibility.SHOWN.shown());
        assertFalse(ToolOutputVisibility.COLLAPSED.shown());
        assertTrue(ToolOutputVisibility.COLLAPSED.collapsed());
        assertFalse(ToolOutputVisibility.HIDDEN.collapsed());
        assertFalse(ToolOutputVisibility.SHOWN.collapsed());
    }
}
