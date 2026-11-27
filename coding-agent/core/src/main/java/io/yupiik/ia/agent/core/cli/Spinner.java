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

import io.yupiik.ia.agent.core.cli.prompt.PromptOutput;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;

/**
 * A lightweight terminal activity indicator for waiting periods (LLM first
 * token, long tool execution). Writes a single spinner line using carriage
 * returns and erases itself on {@link #stop()}. Inert when the output is not an
 * interactive terminal or no frames are configured, so it never corrupts
 * piped/scripted output.
 * <p>
 * The spinner is the only cursor-mutating writer of the CLI, so it is also the
 * only one able to erase the row a rich prompt backend has painted. Every frame
 * and the final erase therefore go through the {@link PromptOutput} write path
 * (which owns the transition with the read lifecycle) and are <b>skipped
 * entirely while a prompt is live</b>: rewriting a row from column 0 behind a
 * line editor's back desynchronizes its display bookkeeping, and the prompt
 * would only reappear once the user typed something.
 */
public final class Spinner {
    private final Symbols symbols;
    private final PromptOutput output;
    private final Lock lock;
    private final boolean active;
    private final long frameIntervalMillis;
    private final long startNanos = System.nanoTime();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread thread;
    private volatile String label;
    /** Whether a frame is currently drawn and not yet erased: only then may {@link #stop()} clear the row. */
    private volatile boolean rowOwned;

    public Spinner(final Symbols symbols, final PromptOutput output, final boolean tty, final Lock lock) {
        this(symbols, output, tty, lock, 80L);
    }

    Spinner(
            final Symbols symbols,
            final PromptOutput output,
            final boolean tty,
            final Lock lock,
            final long frameIntervalMillis) {
        this.symbols = symbols;
        this.output = output;
        this.lock = lock;
        this.active = tty && symbols.spinner().length > 0;
        this.frameIntervalMillis = frameIntervalMillis;
    }

    public void start(final String label) {
        if (!active || !running.compareAndSet(false, true)) {
            return;
        }
        this.label = label;
        thread = Thread.ofVirtual().name("cli-spinner").start(() -> {
            final var frames = symbols.spinner();
            int i = 0;
            while (running.get()) {
                final var seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                // tryLock: while a real output write holds the render lock the frame is
                // dropped instead of queueing behind the write and landing on a
                // half-drawn line - the spinner is simply ignored while output flows.
                // A live prompt owns the row the same way: the frame is dropped rather
                // than overwriting the prompt from column 0. The unlock lives in the
                // finally of the acquired branch: skipping the frame must never keep
                // the render lock, or stop() would block forever.
                if (lock.tryLock()) {
                    try {
                        if (!output.active()) {
                            output.printErr('\r'
                                    + String.valueOf(frames[i++ % frames.length])
                                    + ' '
                                    + label
                                    + (seconds >= 1 ? String.format(Locale.ROOT, " (%.1fs)", seconds) : ""));
                            rowOwned = true;
                        }
                    } finally {
                        lock.unlock();
                    }
                }
                try {
                    Thread.sleep(frameIntervalMillis);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    Thread worker() {
        return thread;
    }

    /**
     * Erases the spinner line (keeps the current status text as a static line
     * on stderr when {@code keepLabel} is true).
     */
    public void stop() {
        stop(false);
    }

    public void stop(final boolean keepLabel) {
        if (!active) {
            return;
        }
        running.set(false);
        final var worker = thread;
        thread = null;
        if (worker != null) {
            // Guarantee the worker fully exited before we erase the line: a
            // short join could return while a stale frame is still being
            // written, letting it land after the erase and interleave with the
            // interaction prompt that draws right after stop().
            worker.interrupt();
            try {
                worker.join();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // no frame was ever drawn (the prompt owned the row all along) or the
        // prompt took the row back since: erasing now would wipe the prompt the
        // user is typing into, and the line editor would not repaint it.
        final var owned = rowOwned;
        rowOwned = false;
        if (!owned || output.active()) {
            return;
        }
        lock.lock();
        try {
            if (keepLabel && label != null) {
                output.printErr("\r" + symbols.infoMarker() + label + "\n");
            } else {
                // the trailing newline closes the row so the next prompt is drawn on a
                // fresh one instead of over the cells the erase just cleared
                output.printErr("\r\u001b[2K\r\n");
            }
        } finally {
            lock.unlock();
        }
    }
}
