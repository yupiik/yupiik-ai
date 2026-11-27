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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TranscriptLayoutTest {

    @Test
    void mapsUnitsToStartRowsThroughPrefixSums() {
        final var layout = new TranscriptLayout<>(List.of("a", "bb", "ccc"), String::length);
        assertEquals(6, layout.height());
        assertEquals(3, layout.size());
        // starts
        assertEquals(0, layout.row(0));
        assertEquals(1, layout.row(1));
        assertEquals(3, layout.row(2));
        assertEquals(6, layout.row(3)); // size() -> end
        // get
        assertEquals("a", layout.get(0));
        assertEquals("ccc", layout.get(2));
    }

    @Test
    void skipsZeroHeightUnitsButStillCountsThemInIndices() {
        final var layout = new TranscriptLayout<>(List.of("a", "", "b", "", "cc"), s -> s.length());
        assertEquals(4, layout.height()); // 1 + 0 + 1 + 0 + 2
        assertEquals(5, layout.size());
        assertEquals(2, layout.row(3)); // zero-height unit shares its start
        assertEquals(2, layout.row(4));
        // firstVisible must cross the zero-height spans
        assertEquals(0, layout.firstVisible(0));
        assertEquals(2, layout.firstVisible(1));
        assertEquals(4, layout.firstVisible(2));
        assertEquals(4, layout.firstVisible(3));
    }

    @Test
    void firstVisibleIsTheUnitCrossingTheRow() {
        final var layout = new TranscriptLayout<>(List.of("a", "bbb", "cc"), String::length);
        // heights: a=1, bbb=3, cc=2 -> starts [0,1,4,6]
        assertEquals(0, layout.firstVisible(0));
        assertEquals(1, layout.firstVisible(1));
        assertEquals(1, layout.firstVisible(2));
        assertEquals(1, layout.firstVisible(3));
        assertEquals(2, layout.firstVisible(4));
        assertEquals(2, layout.firstVisible(5));
    }

    @Test
    void outOfRangeAndEmptyReturnSize() {
        final var empty = new TranscriptLayout<>(List.of(), String::length);
        assertEquals(0, empty.size());
        assertEquals(0, empty.height());
        assertEquals(0, empty.row(-1));
        assertEquals(0, empty.row(0));
        assertEquals(0, empty.firstVisible(0));
        assertEquals(0, empty.firstVisible(-5));
        assertEquals(0, empty.firstVisible(100));

        final var layout = new TranscriptLayout<>(List.of("a", "bb"), String::length);
        assertEquals(2, layout.row(-1)); // out of range -> size
        assertEquals(2, layout.row(99));
        assertEquals(3, layout.row(2)); // valid end sentinel -> height
        assertEquals(2, layout.firstVisible(-1));
        assertEquals(2, layout.firstVisible(3)); // row at height -> size
        assertEquals(2, layout.firstVisible(1000));
        assertNull(layout.get(-1));
        assertNull(layout.get(2));
        assertNull(layout.get(999));
    }

    @Test
    void defensivelyCopiesTheInputList() {
        final var mutable = new ArrayList<>(List.of("a", "bb", "ccc"));
        final var layout = new TranscriptLayout<>(mutable, String::length);
        mutable.clear();
        assertEquals(3, layout.size());
        assertEquals(6, layout.height());
        assertEquals("a", layout.get(0));
        assertEquals("ccc", layout.get(2));
        // the unit type itself is not copied but this list is structural-only
    }

    @Test
    void negativeHeightIsRejectedAtConstruction() {
        final var layout = assertThrows(
                IllegalArgumentException.class,
                () -> new TranscriptLayout<>(List.of("a", "bb"), s -> s.equals("bb") ? -1 : 1));
        assertTrue(layout.getMessage().contains("negative"));
    }

    @Test
    void overflowIsExactAndThrows() {
        assertThrows(
                ArithmeticException.class, () -> new TranscriptLayout<>(List.of("a", "b"), s -> Integer.MAX_VALUE));
    }

    @Test
    void lookupsDoNotInvokeTheHeightAccessor() {
        // large transcript: every lookup stays logarithmic and never calls height again
        final var calls = new AtomicInteger();
        final var units = new ArrayList<String>();
        for (int i = 0; i < 100_000; i++) {
            units.add("unit-" + i);
        }
        final var layout = new TranscriptLayout<>(units, s -> {
            calls.incrementAndGet();
            return 1;
        });
        final int constructionCalls = 100_000;
        assertEquals(constructionCalls, calls.get());

        // exercising get/row/firstVisible must not touch the accessor
        for (int i = 0; i < 10_000; i++) {
            layout.get(i);
            layout.row(i);
            layout.firstVisible(i);
        }
        assertEquals(constructionCalls, calls.get());
        assertEquals(100_000, layout.height());
        assertEquals(99_999, layout.firstVisible(99_999));
    }
}
