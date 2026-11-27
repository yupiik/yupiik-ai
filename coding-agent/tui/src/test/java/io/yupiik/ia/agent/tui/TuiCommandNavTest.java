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
package io.yupiik.ia.agent.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class TuiCommandNavTest {
    @Test
    void emptyAnchorsYieldNoIndex() {
        assertEquals(-1, TuiCommand.nextToolAnchorIndex(List.of(), 5, 0, 1));
    }

    @Test
    void firstStepStartsAtNearestEnd() {
        assertEquals(0, TuiCommand.nextToolAnchorIndex(List.of(1L, 2L, 3L), -1, -1, 1));
        assertEquals(2, TuiCommand.nextToolAnchorIndex(List.of(1L, 2L, 3L), -1, -1, -1));
    }

    @Test
    void focusedOrdinalWinsOverFallbackAndWraps() {
        assertEquals(2, TuiCommand.nextToolAnchorIndex(List.of(7L, 8L, 9L), 8L, 0, 1));
        assertEquals(2, TuiCommand.nextToolAnchorIndex(List.of(7L, 8L, 9L), 7L, 2, -1));
        assertEquals(0, TuiCommand.nextToolAnchorIndex(List.of(7L, 8L, 9L), 9L, 1, 1));
        assertEquals(2, TuiCommand.nextToolAnchorIndex(List.of(7L, 8L, 9L), 7L, 0, -1));
    }

    @Test
    void staleOrdinalFallsBackToLastIndexWhileValid() {
        assertEquals(2, TuiCommand.nextToolAnchorIndex(List.of(7L, 8L, 9L), 42, 1, 1));
        assertEquals(0, TuiCommand.nextToolAnchorIndex(List.of(7L, 8L, 9L), 42, 5, 1));
        assertEquals(2, TuiCommand.nextToolAnchorIndex(List.of(7L, 8L, 9L), 42, -1, -1));
    }
}
