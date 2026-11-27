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

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TranscriptSearchTest {
    private TranscriptSearch search(final String query, final String... rows) {
        final var search = new TranscriptSearch();
        search.query(query);
        search.recompute(asList(rows));
        return search;
    }

    @Test
    void findsCaseInsensitiveMatches() {
        final var search = search("error", "nope", "Error found", "another ERROR here");
        assertEquals(2, search.count());
        assertEquals(1, search.currentRow());
    }

    @Test
    void nextAndPreviousWrap() {
        final var search = search("x", "x1", "x2", "x3");
        assertEquals(0, search.currentRow());
        search.next();
        assertEquals(1, search.currentRow());
        search.next();
        search.next();
        assertEquals(0, search.currentRow());
        search.previous();
        assertEquals(2, search.currentRow());
    }

    @Test
    void noMatchMeansZero() {
        final var search = search("zzz", "a", "b");
        assertEquals(0, search.count());
        assertEquals(0, search.currentMatch());
        assertEquals(-1, search.currentRow());
    }

    @Test
    void blankQueryIsInactive() {
        final var search = search("  ", "match me");
        assertFalse(search.active());
        assertEquals(0, search.count());
    }

    @Test
    void clearResets() {
        final var search = search("a", "abc");
        search.clear();
        assertFalse(search.active());
        assertEquals(0, search.count());
        assertEquals("", search.query());
    }

    @Test
    void recomputeWithNewRows() {
        final var search = search("needle", "no needle yet");
        assertEquals(1, search.count());
        search.recompute(List.of("nothing"));
        assertEquals(0, search.count());
    }

    @Test
    void matchesAscending() {
        final var search = search("b", "ab", "bb", "cb");
        assertEquals(List.of(0, 1, 2), search.matches());
    }

    @Test
    void oneBasedCurrentMatch() {
        final var search = search("b", "ab", "bb");
        assertEquals(1, search.currentMatch());
        search.next();
        assertEquals(2, search.currentMatch());
    }

    @Test
    void nullAndEmptyRowsIgnored() {
        final var search = new TranscriptSearch();
        search.query("a");
        search.recompute(asList(null, "a", ""));
        assertTrue(search.active());
        assertEquals(1, search.count());
    }
}
