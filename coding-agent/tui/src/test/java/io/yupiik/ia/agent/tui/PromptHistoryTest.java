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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PromptHistoryTest {
    @Test
    void replaceWithSeedsFromDiskAndResetsCursor() {
        final var h = new PromptHistory(10);
        h.add("draft-in-progress");
        final var loaded = new PromptHistory(10);
        loaded.add("newest");
        loaded.add("older");
        h.replaceWith(loaded);
        // entries are most-recent-first ("older" was added last)
        assertEquals(List.of("older", "newest"), h.entries());
        // cursor was reset: the first Older recall returns the most recent entry
        assertEquals("older", h.olderPush("draft"));
        // walking back down to the baseline restores the draft
        assertEquals("draft-restored", h.newerPull("draft-restored"));
    }

    @Test
    void capacityAccessorMatchesBound() {
        assertEquals(7, new PromptHistory(7).capacity());
        assertTrue(new PromptHistory().capacity() >= 1);
    }

    @Test
    void storesMostRecentFirstDeduplicated() {
        final var h = new PromptHistory(10);
        h.add("first");
        h.add("second");
        h.add("first");
        assertEquals(3, h.size());
        assertEquals("first", h.entries().get(0));
        h.add("first");
        assertEquals(3, h.size(), "consecutive duplicates are dropped");
    }

    @Test
    void slashCommandsKeepArgumentsAndOnlyConsecutiveExactDuplicatesAreSuppressed() {
        final var history = new PromptHistory(10);
        history.add("/btw explain this\nwith another line");
        history.add("/btw explain this\nwith another line");
        history.add("/mode build");
        history.add("/mode plan");
        history.add("/mode build");
        assertEquals(
                List.of("/mode build", "/mode plan", "/mode build", "/btw explain this\nwith another line"),
                history.entries());
        assertEquals("/mode build", history.olderPush("draft"));
        assertEquals("/mode plan", history.olderPush("draft"));
    }

    @Test
    void ignoresBlank() {
        final var h = new PromptHistory(10);
        h.add("   ");
        h.add("");
        assertEquals(0, h.size());
    }

    @Test
    void olderPullWalksHistoryAndRestoresDraft() {
        final var h = new PromptHistory(10);
        h.add("a");
        h.add("b");
        h.add("c");
        assertEquals("c", h.olderPush("draft"));
        assertEquals("b", h.olderPush("draft"));
        assertEquals("a", h.olderPush("draft"));
        assertNull(h.olderPush("draft"), "no more older entries");
    }

    @Test
    void newerPullWalksBackDownToDraft() {
        final var h = new PromptHistory(10);
        h.add("a");
        h.add("b");
        assertEquals("b", h.olderPush("draft"));
        assertEquals("a", h.olderPush("draft"));
        assertEquals("b", h.newerPull("draft"));
        assertEquals("draft", h.newerPull("draft"), "restores the in-progress draft at baseline");
    }

    @Test
    void addWhileNavigatingResets() {
        final var h = new PromptHistory(10);
        h.add("a");
        h.add("b");
        h.olderPush("draft");
        assertTrue(h.navigating());
        h.add("c");
        assertFalse(h.navigating(), "add resets navigation");
    }

    @Test
    void capsSize() {
        final var h = new PromptHistory(3);
        h.add("1");
        h.add("2");
        h.add("3");
        h.add("4");
        assertEquals(3, h.size());
        assertEquals("4", h.entries().get(0));
    }

    @Test
    void persistsRoundTrip(@TempDir final Path dir) {
        final var file = dir.resolve("history.txt");
        final var h = new PromptHistory(10);
        h.add("one");
        h.add("two");
        h.save(file);
        final var loaded = PromptHistory.load(file, 10);
        assertEquals(2, loaded.size());
        assertEquals("two", loaded.entries().get(0));
        assertEquals("one", loaded.entries().get(1));
    }

    @Test
    void loadMissingFileIsEmpty(@TempDir final Path dir) {
        final var loaded = PromptHistory.load(dir.resolve("nope.txt"), 10);
        assertEquals(0, loaded.size());
    }
}
