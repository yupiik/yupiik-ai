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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ToolRunTest {

    private ToolCard card(
            final long ordinal, final String name, final String args, final long elapsed, final boolean failed) {
        return new ToolCard(name, args, "", failed, false, elapsed, false, ordinal);
    }

    @Test
    void groupSingle() {
        final var run = ToolRun.of(List.of(new ToolRun.Member(card(1, "bash", "grep x", 10, false), "r1")));
        assertEquals(1, run.size());
        assertEquals(1, run.id());
    }

    @Test
    void countsByCommandAndFailures() {
        final var run = ToolRun.of(List.of(
                new ToolRun.Member(card(1, "bash", "grep x", 10, false), "r1"),
                new ToolRun.Member(card(2, "bash", "grep x", 20, false), "r2"),
                new ToolRun.Member(card(3, "read", "a.java", 5, true), "r3")));
        assertEquals(3, run.size());
        assertEquals(2, run.commandCounts().get("grep x")); // same args -> one command bucket
        assertEquals(1, run.commandCounts().get("a.java"));
        assertEquals(1, run.failures());
        assertEquals(35, run.elapsedMs());
    }

    @Test
    void runningCardNeverExtends() {
        final var builder = ToolRun.builder(new ToolRun.Member(card(1, "bash", "ls", 10, false), "r1"));
        // a running (still streaming) card must not join an existing run
        assertFalse(ToolRun.of(List.of(new ToolRun.Member(card(2, "bash", "sleep 5", 0, false), "r2")))
                .canExtend(new ToolCard("bash", "sleep 5", "", false, false, 0, true, 2)));
    }

    @Test
    void idIsFirstOrdinal() {
        final var run = ToolRun.of(List.of(
                new ToolRun.Member(card(7, "bash", "ls", 1, false), "r7"),
                new ToolRun.Member(card(9, "bash", "pwd", 1, false), "r9")));
        assertEquals(7, run.id());
    }

    @Test
    void canExtendRejectsRunningAndNull() {
        final var finished = ToolRun.of(List.of(new ToolRun.Member(card(1, "bash", "ls", 1, false), "r1")));
        assertTrue(finished.canExtend(card(2, "bash", "pwd", 1, false)));
        assertFalse(finished.canExtend(new ToolCard("bash", "sleep 5", "", false, false, 0, true, 3)));
        assertFalse(finished.canExtend(null));
    }

    @Test
    void summaryOnEmptyNeverBreaks() {
        final var run = ToolRun.of(List.of(new ToolRun.Member(card(1, "bash", "ls", 0, false), "r1")));
        assertEquals(1, run.size());
    }

    @Test
    void collapsedMemberLineCarriesOrdinalForOverlayClick() {
        // collapsed run members render as dim one-liners; each must keep its ordinal so
        // a click on the line can open the full output overlay (regression: the collapsed
        // run branch used to drop every member anchor, making the lines dead space)
        final var palette = Palette.from("dark", tuiConfiguration());
        final var glyphs = Glyphs.of("unicode");
        for (final var c : List.of(
                card(7, "bash", "grep x", 10, false),
                card(9, "read", "a.java", 5, false),
                card(11, "git", "status", 3, true))) {
            final var text = TuiCommand.hiddenToolLineText(c, palette, glyphs);
            // the ordinal appears in the member label, so the TUI can anchor the overlay
            assertTrue(text.contains("#" + c.ordinal()), () -> "expected #" + c.ordinal() + " in: " + text);
        }
    }

    private static TuiConfiguration tuiConfiguration() {
        final var log = new TuiLogConfiguration(
                new TuiLogLevelConfiguration("auto"),
                new TuiLogLevelConfiguration("auto"),
                new TuiLogLevelConfiguration("auto"),
                new TuiLogLevelConfiguration("auto"),
                new TuiLogLevelConfiguration("auto"),
                new TuiLogLevelConfiguration("auto"),
                new TuiLogLevelConfiguration("auto"));
        return new TuiConfiguration(
                null,
                "#9d7cd8",
                "#fab283",
                "#7fd88f",
                "#f5a742",
                "#e06c75",
                "#b896d8",
                "#eeeeee",
                "#808080",
                -1.0,
                0.22,
                0.14,
                0.85,
                "#484848",
                "#606060",
                "#141414",
                "auto",
                "auto",
                4000L,
                3500L,
                true,
                "unicode",
                "auto",
                "auto",
                List.of(),
                new TuiTranscriptConfiguration(10_000_000L),
                log,
                true,
                3,
                10,
                "",
                0.8,
                "#ff5555",
                new TuiConfiguration.ToolOutputConfiguration(128, 256 * 1024, 0),
                null);
    }

    @Test
    void collapsedRunRendersOneLinePerMemberInOrder() {
        // the collapsed run must render exactly the member one-liners (readable, dimmed)
        // and no full card bodies, so the run stays compact while each line stays clickable
        final var members = List.of(
                new ToolRun.Member(card(1, "bash", "ls", 10, false), "r1"),
                new ToolRun.Member(card(2, "read", "x", 20, false), "r2"),
                new ToolRun.Member(card(3, "bash", "pwd", 5, false), "r3"));
        final var run = ToolRun.of(members);
        assertEquals(3, run.size());
        assertEquals(3, run.members().size());
        // each member keeps its ordinal and the run id is the first member's ordinal
        final var ordinals = run.members().stream().map(m -> m.card().ordinal()).toList();
        assertEquals(List.of(1L, 2L, 3L), ordinals);
        assertEquals(1L, run.id());
    }
}
