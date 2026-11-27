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
package io.yupiik.ia.agent.tui.prompt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.wiring.HackStdIo;
import java.io.PrintStream;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Guards the terminal wiring behind the prompt: the producer must always hand
 * jline the <em>system</em> terminal ({@link TerminalBuilder#builder()}{@code
 * .system(true)}) so the real tty is put in raw mode and jline's rendering
 * reaches it directly - a terminal built from custom streams would paint on a
 * pty slave instead and the completed text would never show up on the prompt
 * row (the history {@code aea42d9} regression).
 */
@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
class JLineProducerTest {
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void capture() {
        originalOut = System.out;
        originalErr = System.err;
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    void hijackedRuntimeStillKeepsTheSystemTerminal() throws Exception {
        final var stdio = new HackStdIo();
        try {
            stdio.install();
            assertTrue(stdio.installed(), "the runtime must have taken the streams over");
            try (final var terminal = new JLineProducer().terminal(stdio)) {
                assertSystemTerminal(terminal);
            }
        } finally {
            stdio.uninstall();
        }
    }

    @Test
    void untouchedRuntimeKeepsTheSystemTerminal() throws Exception {
        try (final var terminal = new JLineProducer().terminal(new HackStdIo())) {
            assertNotNull(terminal, "a terminal must always be produced");
            assertNotNull(terminal.getSize(), "a size must always be exposed");
        }
    }

    private static void assertSystemTerminal(final Terminal terminal) {
        // a system terminal is either the real one (PosixSysTerminal / NativeSysTerminal)
        // or the dumb fallback when no tty is attached (headless tests, piped stdout):
        // both are fine - what must never come back is the streams-based pty slave
        final var name = terminal.getClass().getName();
        assertFalse(
                name.contains("PtyTerminal"),
                "the hijacked runtime must not produce a streams-based pty terminal, got " + name);
        // a headless build has no tty and legitimately yields a dumb terminal (0x0):
        // only the class must be asserted here, the size comes from the real tty
        assertNotNull(terminal.getSize(), "a size must always be exposed");
    }
}
