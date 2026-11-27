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

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.DiffResult;
import dev.tamboui.layout.Position;
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.style.Color;
import dev.tamboui.style.Modifier;
import dev.tamboui.terminal.Backend;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory terminal backend capturing drawn buffers so tests can assert on the
 * rendered screen. Read/peek answer -2 (no input data available), mirroring the
 * sentinel used by the JLine backend.
 */
final class HeadlessBackend implements Backend {
    static final int WIDTH = 120;
    static final int HEIGHT = 40;

    private final ReentrantLock lock = new ReentrantLock();
    private final Size size;
    private final Buffer buffer;
    private final Semaphore drawn = new Semaphore(0);
    private final StringBuilder raw = new StringBuilder();
    private final AtomicLong frames = new AtomicLong();

    HeadlessBackend() {
        this(WIDTH, HEIGHT);
    }

    HeadlessBackend(final int width, final int height) {
        size = new Size(width, height);
        buffer = Buffer.empty(new Rect(0, 0, width, height));
    }

    @Override
    public Size size() {
        return size;
    }

    @Override
    public void draw(final DiffResult diff) {
        lock.lock();
        try {
            for (int i = 0; i < diff.size(); i++) {
                buffer.set(diff.getX(i), diff.getY(i), diff.getCell(i));
            }
            frames.incrementAndGet();
        } finally {
            lock.unlock();
        }
        drawn.release();
    }

    /** Number of backend draws, including frames whose diff is empty. */
    long frames() {
        return frames.get();
    }

    /** Records what the app writes outside of the cell grid: clipboard OSC 52 payloads. */
    @Override
    public void writeRaw(final String text) {
        lock.lock();
        try {
            raw.append(text);
        } finally {
            lock.unlock();
        }
        drawn.release();
    }

    /** Everything written through {@link #writeRaw(String)} so far, ie. clipboard payloads. */
    String raw() {
        lock.lock();
        try {
            return raw.toString();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Awaits a rendered frame so tests synchronize on real UI updates instead of sleeping.
     * <p>
     * The wait polls the permit finely instead of blocking for the whole timeout: a paint is
     * already scheduled (the render thread repaints on the next tick), so a coarse wait would
     * add up to {@code timeoutMillis} of pure latency to every assertion of every test. A real
     * terminal blocks in {@code read()}, but here there is nothing to block on.
     *
     * @return {@code true} when a frame was rendered before the timeout.
     */
    boolean awaitFrame(final long timeoutMillis) throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            if (drawn.tryAcquire()) {
                return true;
            }
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            LockSupport.parkNanos(200_000L);
        }
    }

    @Override
    public void flush() {}

    @Override
    public void clear() {}

    @Override
    public void showCursor() {}

    @Override
    public void hideCursor() {}

    @Override
    public Position getCursorPosition() {
        return Position.ORIGIN;
    }

    @Override
    public void setCursorPosition(final Position position) {}

    @Override
    public void enterAlternateScreen() {}

    @Override
    public void leaveAlternateScreen() {}

    @Override
    public void enableRawMode() {}

    @Override
    public void disableRawMode() {}

    @Override
    public void onResize(final Runnable callback) {}

    @Override
    public int read(final int timeoutMillis) {
        // a real backend blocks here for up to timeoutMillis; returning -2 straight away would
        // turn the runner's input reader into a 100% cpu spin for the whole test (one core per
        // running test, which then slows the agent loop down when tests run in parallel)
        LockSupport.parkNanos(200_000L);
        return -2;
    }

    @Override
    public int peek(final int timeoutMillis) {
        return -2;
    }

    @Override
    public void close() {
        final var notify = onClose;
        if (notify != null) {
            notify.run();
        }
    }

    private Runnable onClose;

    /** Registers a callback invoked once the TUI released the terminal (runner closed). */
    void onClose(final Runnable callback) {
        this.onClose = callback;
    }

    String text() {
        lock.lock();
        try {
            final var sb = new StringBuilder();
            for (int y = 0; y < buffer.height(); y++) {
                final var line = new StringBuilder();
                for (int x = 0; x < buffer.width(); x++) {
                    final var cell = buffer.get(x, y);
                    if (cell.isContinuation()) {
                        continue;
                    }
                    line.append(cell.isEmpty() ? ' ' : cell.symbol());
                }
                sb.append(line.toString().stripTrailing()).append('\n');
            }
            return sb.toString();
        } finally {
            lock.unlock();
        }
    }

    /** Symbol at a screen coordinate, or a space when the cell is empty. */
    String symbol(final int x, final int y) {
        lock.lock();
        try {
            return y < 0
                            || y >= buffer.height()
                            || x < 0
                            || x >= buffer.width()
                            || buffer.get(x, y).isEmpty()
                    ? " "
                    : buffer.get(x, y).symbol();
        } finally {
            lock.unlock();
        }
    }

    /** Foreground color at a screen coordinate. */
    Optional<Color> fg(final int x, final int y) {
        lock.lock();
        try {
            return buffer.get(x, y).style().fg();
        } finally {
            lock.unlock();
        }
    }

    /** Background color at a screen coordinate. */
    Optional<Color> bg(final int x, final int y) {
        lock.lock();
        try {
            return buffer.get(x, y).style().bg();
        } finally {
            lock.unlock();
        }
    }

    /** True when the cell at the coordinate carries the reversed-video modifier. */
    boolean reversed(final int x, final int y) {
        lock.lock();
        try {
            if (y < 0 || y >= buffer.height() || x < 0 || x >= buffer.width()) {
                return false;
            }
            return buffer.get(x, y).style().effectiveModifiers().contains(Modifier.REVERSED);
        } finally {
            lock.unlock();
        }
    }

    /** Last reversed-video cell in the buffer (bottommost, rightmost), or {@code null} when none. */
    int[] lastReversed() {
        final var cursor = cursor();
        return cursor == Cursor.NONE ? null : new int[] {cursor.col, cursor.row};
    }

    /** Bottommost reversed-video cell (the prompt input cursor) as an atomic (col,row) snapshot, or {@link #NONE}. */
    Cursor cursor() {
        lock.lock();
        try {
            for (var y = buffer.height() - 1; y >= 0; y--) {
                for (var x = buffer.width() - 1; x >= 0; x--) {
                    if (buffer.get(x, y).style().effectiveModifiers().contains(Modifier.REVERSED)) {
                        return new Cursor(x, y);
                    }
                }
            }
            return Cursor.NONE;
        } finally {
            lock.unlock();
        }
    }

    /** A terminal-cell position: both coordinates always come from the same rendered frame. */
    record Cursor(int col, int row) {
        static final Cursor NONE = new Cursor(-1, -1);
    }
}
