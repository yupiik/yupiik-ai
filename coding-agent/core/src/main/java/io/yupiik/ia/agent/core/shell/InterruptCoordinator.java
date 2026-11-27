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

import java.time.Clock;

/**
 * Shared interrupt state machine: while a turn is running an interrupt
 * request cancels it, once idle it quits - either on the first press
 * (TUI) or through a double-press window (CLI). Shells only map the
 * returned {@link Decision} to their own rendering and teardown.
 */
public class InterruptCoordinator {
    /** What the shell should do in reaction to an interrupt request. */
    public enum Decision {
        /** Cancel the running turn, stay alive. */
        INTERRUPT,
        /** First idle press: hint the quit shortcut, stay alive. */
        ARM_QUIT,
        /** Quit the shell. */
        QUIT
    }

    private static final long DEFAULT_QUIT_WINDOW_MILLIS = 2000;

    private final Clock clock;
    private final long quitWindowMillis;
    private final boolean quitOnFirstPress;
    private volatile long lastInterruptAt;
    private volatile boolean pressedOnce;

    /** CLI-style coordinator: quit on the second press inside the window. */
    public InterruptCoordinator(final Clock clock) {
        this(clock, DEFAULT_QUIT_WINDOW_MILLIS, false);
    }

    /** Coordinator with an explicit quit-on-first-press policy. */
    public InterruptCoordinator(final Clock clock, final boolean quitOnFirstPress) {
        this(clock, DEFAULT_QUIT_WINDOW_MILLIS, quitOnFirstPress);
    }

    public InterruptCoordinator(final Clock clock, final long quitWindowMillis, final boolean quitOnFirstPress) {
        this.clock = clock;
        this.quitWindowMillis = quitWindowMillis;
        this.quitOnFirstPress = quitOnFirstPress;
    }

    /**
     * Records an interrupt request and classifies it.
     *
     * @param busy {@code true} while a turn is running
     */
    public Decision interrupt(final boolean busy) {
        if (busy) {
            return Decision.INTERRUPT;
        }
        final var now = clock.millis();
        final var previous = lastInterruptAt;
        final var wasPressedOnce = pressedOnce;
        lastInterruptAt = now;
        pressedOnce = true;
        if (quitOnFirstPress) {
            return Decision.QUIT;
        }
        return wasPressedOnce && now - previous <= quitWindowMillis ? Decision.QUIT : Decision.ARM_QUIT;
    }
}
