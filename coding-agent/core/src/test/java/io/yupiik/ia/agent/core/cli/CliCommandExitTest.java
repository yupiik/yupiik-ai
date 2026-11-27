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
package io.yupiik.ia.agent.core.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.yupiik.ia.agent.core.cli.CliCommand.MenuChoice;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Quit semantics of the interrupt menu: picking {@code 2} must terminate the CLI even
 * when the turn hangs, and a Ctrl-C landing after the quit must never re-open the menu
 * (the reported bug: "2" printed bye, then the menu came back and printed
 * "no change - turn keeps running" forever).
 */
class CliCommandExitTest {

    @Test
    void menuAnswersAreParsed() {
        assertEquals(MenuChoice.QUIT, CliCommand.menuChoice("2"));
        assertEquals(MenuChoice.QUIT, CliCommand.menuChoice(" E "));
        assertEquals(MenuChoice.QUIT, CliCommand.menuChoice("exit"));
        assertEquals(MenuChoice.QUIT, CliCommand.menuChoice("q"));
        assertEquals(MenuChoice.CANCEL, CliCommand.menuChoice("1"));
        assertEquals(MenuChoice.CANCEL, CliCommand.menuChoice("c"));
        assertEquals(MenuChoice.CANCEL, CliCommand.menuChoice("Cancel"));
        assertEquals(MenuChoice.NONE, CliCommand.menuChoice(""));
        assertEquals(MenuChoice.NONE, CliCommand.menuChoice("   "));
        assertEquals(MenuChoice.NONE, CliCommand.menuChoice("3"));
        assertEquals(MenuChoice.NONE, CliCommand.menuChoice("yes"));
        assertEquals(MenuChoice.NONE, CliCommand.menuChoice(null));
    }

    @Test
    void menuRendersWhileTheTurnIsNotQuitting() {
        final var future = new CompletableFuture<Boolean>();
        final var menuPending = new AtomicBoolean(true);
        final var menus = new AtomicInteger();
        final var forced = new AtomicInteger();

        CliCommand.awaitTurnLoop(
                future,
                menuPending,
                () -> false,
                () -> {
                    menus.incrementAndGet();
                    future.complete(Boolean.TRUE);
                },
                forced::incrementAndGet,
                60_000_000_000L);

        assertEquals(1, menus.get(), "the armed menu must be rendered once");
        assertEquals(0, forced.get(), "a non-quit answer must never force an exit");
    }

    @Test
    void answeringQuitStopsTheMenuAndForcesExitWhenTheTurnNeverEnds() {
        final var future = new CompletableFuture<Boolean>();
        final var menuPending = new AtomicBoolean(true);
        final var quit = new AtomicBoolean();
        final var menus = new AtomicInteger();
        final var forced = new AtomicInteger();

        CliCommand.awaitTurnLoop(
                future,
                menuPending,
                quit::get,
                () -> {
                    menus.incrementAndGet();
                    // answering "2": quit requested and a stray Ctrl-C re-arms the menu
                    quit.set(true);
                    menuPending.set(true);
                },
                forced::incrementAndGet,
                50_000_000L);

        assertEquals(1, menus.get(), "the menu must not be rendered again after the quit answer");
        assertEquals(1, forced.get(), "a turn outliving the grace period must be force-exited");
    }

    @Test
    void quitFromTheStartSkipsTheMenuEntirely() {
        final var future = new CompletableFuture<Boolean>();
        final var menuPending = new AtomicBoolean(true);
        final var menus = new AtomicInteger();
        final var forced = new AtomicInteger();

        CliCommand.awaitTurnLoop(
                future, menuPending, () -> true, menus::incrementAndGet, forced::incrementAndGet, 50_000_000L);

        assertEquals(0, menus.get(), "no menu may be rendered once quit is requested");
        assertEquals(1, forced.get(), "the stuck turn must be force-exited after the grace period");
    }

    @Test
    void turnFinishingWithinTheGraceNeverForcesAnExit() {
        final var future = new CompletableFuture<Boolean>();
        final var forced = new AtomicInteger();
        final var completer = new Thread(
                () -> {
                    try {
                        Thread.sleep(50);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    future.complete(Boolean.TRUE);
                },
                "test-turn-completer");
        completer.setDaemon(true);
        completer.start();

        CliCommand.awaitTurnLoop(
                future,
                new AtomicBoolean(),
                () -> true,
                () -> {
                    throw new AssertionError("no menu after quit");
                },
                forced::incrementAndGet,
                60_000_000_000L);

        assertEquals(0, forced.get(), "a turn unwinding in time exits gracefully without force");
    }

    @Test
    void turnFailuresStillPropagate() {
        final var future = new CompletableFuture<Boolean>();
        final var failer = new Thread(
                () -> {
                    try {
                        Thread.sleep(50);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    future.completeExceptionally(new IllegalStateException("boom"));
                },
                "test-turn-failer");
        failer.setDaemon(true);
        failer.start();

        final var error = assertThrows(
                CompletionException.class,
                () -> CliCommand.awaitTurnLoop(
                        future, new AtomicBoolean(), () -> false, () -> {}, () -> {}, 60_000_000_000L));
        assertInstanceOf(IllegalStateException.class, error.getCause());
    }
}
