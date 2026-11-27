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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.shell.ConversationTranscript;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellSession;
import io.yupiik.ia.agent.core.shell.ThinkTagSplitter;
import io.yupiik.ia.agent.core.shell.slash.ArgCompletion;
import io.yupiik.ia.agent.core.shell.slash.SlashCommand;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class TuiSlashCommandsTest {

    private final TabManager tabManager = new TabManager();
    private final TuiSlashCommands commands = new TuiSlashCommands(tabManager);
    private final ShellEnv env = new ShellEnv(null, null, new ShellSession(), new ConversationTranscript());

    private void seed(final int count) {
        for (int i = 0; i < count; i++) {
            tabManager.add(new UiState(new ThinkTagSplitter(), Clock.systemUTC()));
        }
        tabManager.activate(0);
    }

    private ShellOutcome run(final String arg) {
        final var cmd = commands.commands().stream()
                .filter(c -> c.spec().name().equals("/tabs"))
                .findFirst()
                .orElseThrow();
        return cmd.action().apply(arg, env).toCompletableFuture().join();
    }

    @Test
    void noArgOpensListOverlayWithoutTranscript() {
        seed(2);
        final var outcome = run(null);
        assertInstanceOf(ShellOutcome.None.class, outcome, "the list is UI-only, not a transcript message");
        assertTrue(tabManager.isListOpen());
        assertEquals(0, tabManager.listScroll());
    }

    @Test
    void blankArgBehavesLikeNoArg() {
        seed(1);
        final var outcome = run("   ");
        assertInstanceOf(ShellOutcome.None.class, outcome);
        assertTrue(tabManager.isListOpen());
    }

    @Test
    void indexSwitchesAndKeepsOverlayClosed() {
        seed(3);
        final var outcome = run("2");
        assertInstanceOf(ShellOutcome.None.class, outcome);
        assertFalse(tabManager.isListOpen());
        assertEquals(2, tabManager.activeTab());
    }

    @Test
    void lastRelativeAndBareSignsResolve() {
        seed(3);
        tabManager.activate(1);
        run("last");
        assertEquals(2, tabManager.activeTab());
        tabManager.activate(1);
        run("+1");
        assertEquals(2, tabManager.activeTab());
        tabManager.activate(1);
        run("-1");
        assertEquals(0, tabManager.activeTab());
        tabManager.activate(1);
        run("+");
        assertEquals(2, tabManager.activeTab());
        tabManager.activate(1);
        run("-");
        assertEquals(0, tabManager.activeTab());
    }

    @Test
    void invalidArgumentReturnsStatusWithoutTouchingTabs() {
        seed(2);
        tabManager.activate(1);
        final var outcome = run("abc");
        assertEquals(ShellOutcome.Status.class, outcome.getClass());
        assertEquals(1, tabManager.activeTab());
        assertFalse(tabManager.isListOpen());
    }

    @Test
    void noTabsReturnsStatus() {
        final var outcome = run("0");
        assertEquals(ShellOutcome.Status.class, outcome.getClass());
        assertFalse(tabManager.isListOpen());
    }

    @Test
    void commandIsRegisteredInTheList() {
        final var names = commands.commands().stream()
                .map(SlashCommand::spec)
                .map(s -> s.name())
                .toList();
        assertTrue(names.contains("/tabs"), "expected /tabs in " + names);
    }

    @Test
    void tuiCommandsDocumentTheirCompletions() {
        for (final var command : commands.commands()) {
            if (command.spec().usage() == null || command.spec().usage().isBlank()) {
                continue;
            }
            assertNotSame(
                    command.completion(), ArgCompletion.NONE, command.spec().name() + " must have a completion");
        }
    }
}
