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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.cli.prompt.PromptOutput;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SpinnerTest {
    private static final Pattern ELAPSED = Pattern.compile("thinking\\.\\.\\. \\(\\d+\\.\\ds\\)");

    @Test
    void inertWithoutTty() {
        final var stream = new RecordingStream();
        final var spinner = new Spinner(new Symbols(false), stream.promptOutput(), false, new ReentrantLock());
        spinner.start("thinking");
        spinner.stop();
        assertEquals(0, stream.size());
    }

    @Test
    void doubleStartDoesNotOrphanTheFirstAnimationThread() throws InterruptedException {
        final var stream = new RecordingStream();
        final var spinner = new Spinner(
                new Symbols(true), stream.promptOutput(), true, new ReentrantLock(), TimeUnit.MINUTES.toMillis(1));
        spinner.start("thinking");
        spinner.start("thinking");
        assertTrue(stream.awaitFrame(), "spinner did not render a frame");
        spinner.stop();
        final var sizeAfterStop = stream.size();
        assertTrue(sizeAfterStop > 0);
        assertEquals(sizeAfterStop, stream.size(), "a leaked animation thread kept writing after stop()");
    }

    @Test
    void restartAfterStopAnimatesAgain() throws InterruptedException {
        final var stream = new RecordingStream();
        final var spinner = new Spinner(
                new Symbols(true), stream.promptOutput(), true, new ReentrantLock(), TimeUnit.MINUTES.toMillis(1));
        spinner.start("thinking");
        assertTrue(stream.awaitFrame(), "spinner did not render a frame");
        spinner.stop();
        final var sizeAfterFirstStop = stream.size();
        stream.nextFrame();
        spinner.start("thinking");
        assertTrue(stream.awaitFrame(), "no frames after restart");
        spinner.stop();
        assertTrue(stream.size() > sizeAfterFirstStop, "no frames after restart");
    }

    @Test
    void stopReturnsOnlyAfterWorkerExited() throws InterruptedException {
        final var stream = new RecordingStream();
        final var spinner = new Spinner(
                new Symbols(true), stream.promptOutput(), true, new ReentrantLock(), TimeUnit.MINUTES.toMillis(1));
        spinner.start("thinking");
        assertTrue(stream.awaitFrame(), "spinner did not render a frame");
        final var worker = spinner.worker();
        spinner.stop(); // must join the worker so the erase is the last write
        assertFalse(worker.isAlive(), "stop() returned before the worker exited");
        // The only frame writer is dead, so the final erase cannot be followed by a stale frame.
        final var text = stream.text();
        final var erase = "\r\u001b[2K\r\n";
        assertTrue(text.contains("thinking"), "the worker must render before the erase");
        assertTrue(text.endsWith(erase), "the erase must be the final output: " + text);
    }

    @Test
    void framesAreSkippedWhileTheRenderLockIsHeld() throws InterruptedException {
        final var stream = new RecordingStream();
        // tryLock is only ever invoked by the spinner worker, so each call proves a
        // frame attempt was made: the test waits for a real attempt instead of sleeping
        final var attempts = new CountDownLatch(1);
        final var lock = new ReentrantLock() {
            @Override
            public boolean tryLock() {
                final var acquired = super.tryLock();
                attempts.countDown();
                return acquired;
            }
        };
        final var spinner = new Spinner(new Symbols(true), stream.promptOutput(), true, lock, 5);
        lock.lock();
        try {
            spinner.start("thinking");
            // output owns the terminal: the worker's tryLock fails and the frame is
            // dropped instead of queueing behind the write and landing on a half-drawn line
            assertTrue(
                    attempts.await(5, TimeUnit.SECONDS),
                    "spinner worker never attempted a frame while the render lock was held");
            assertEquals(0, stream.size(), "a frame was written while the render lock was held");
        } finally {
            lock.unlock();
        }
        assertTrue(stream.awaitFrame(), "no frame after the render lock was released");
        spinner.stop();
    }

    @Test
    void noFrameAndNoEraseWhileThePromptOwnsTheRow() throws InterruptedException {
        // The spinner is the only cursor-mutating writer of the CLI: an erase landing
        // on the row jline just painted is invisible to its display bookkeeping, so
        // the prompt only came back once the user typed. Frames and the final erase are
        // therefore suppressed for as long as a prompt is live.
        final var stream = new RecordingStream();
        final var output = stream.promptOutput();
        final var spinner = new Spinner(new Symbols(true), output, true, new ReentrantLock(), 5);
        spinner.start("thinking");
        assertTrue(stream.awaitFrame(), "spinner did not render a frame before the prompt took the row");
        spinner.stop();
        final var sizeAfterStop = stream.size();
        assertTrue(sizeAfterStop > 0);

        final var attempts = new CountDownLatch(1);
        final var lock = new ReentrantLock() {
            @Override
            public boolean tryLock() {
                final var acquired = super.tryLock();
                attempts.countDown();
                return acquired;
            }
        };
        final var guarded = new Spinner(new Symbols(true), output, true, lock, 5);
        stream.promptLive(true);
        guarded.start("thinking");
        // the worker keeps trying, it just never writes while the prompt is live
        assertTrue(attempts.await(5, TimeUnit.SECONDS), "the spinner worker never attempted a frame");
        assertEquals(sizeAfterStop, stream.size(), "a frame was written while the prompt owned the row");
        guarded.stop();
        assertEquals(sizeAfterStop, stream.size(), "the row was erased even though a prompt owned it");
        assertFalse(
                stream.text().substring(sizeAfterStop).contains("\u001b[2K"), "the prompt row must never be erased");
    }

    @Test
    void droppingAFrameForALivePromptStillReleasesTheRenderLock() throws InterruptedException {
        // A skipped frame must not keep the render lock: stop() takes it from another
        // thread, so holding it would hang the CLI on the next turn end.
        final var stream = new RecordingStream();
        stream.promptLive(true);
        final var attempts = new CountDownLatch(1);
        final var lock = new ReentrantLock() {
            @Override
            public boolean tryLock() {
                final var acquired = super.tryLock();
                attempts.countDown();
                return acquired;
            }
        };
        final var spinner = new Spinner(new Symbols(true), stream.promptOutput(), true, lock, 5);
        spinner.start("thinking");
        assertTrue(attempts.await(5, TimeUnit.SECONDS), "the spinner worker never attempted a frame");
        assertEquals(0, stream.size(), "no frame may be written while the prompt owns the row");

        // would hang here if stop() could not take the render lock
        spinner.stop();
        assertFalse(lock.isLocked(), "a dropped frame must not keep the render lock");
    }

    @Test
    void stopErasesAndClosesTheRowSoTheNextPromptStartsFresh() throws InterruptedException {
        final var stream = new RecordingStream();
        final var spinner = new Spinner(new Symbols(true), stream.promptOutput(), true, new ReentrantLock(), 5);
        spinner.start("thinking");
        assertTrue(stream.awaitFrame(), "spinner did not render a frame");
        spinner.stop();
        final var text = stream.text();
        assertTrue(text.contains("\u001b[2K"), "the spinner row must be erased");
        assertTrue(text.endsWith("\n"), "the erase must close the row, or the next prompt lands over it");
    }

    @Test
    void elapsedIsRenderedOnceAndParenthesised() throws InterruptedException {
        // regression: the label used to bake a time in ("thinking... (0s)") while the
        // worker appended its own counter, rendering the elapsed time twice. The
        // suite's default locale is pinned JVM-wide in the root pom (argLine), so the
        // "10.4s" spelling here is stable whatever the host locale is - production
        // relies on the Spinner's Locale.ROOT formatting for the same reason.
        final var stream = new RecordingStream();
        final var spinner = new Spinner(new Symbols(true), stream.promptOutput(), true, new ReentrantLock(), 25);
        spinner.start("thinking...");
        // the counter only kicks in from 1s: poll real frames until it does
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        String text = "";
        while (!ELAPSED.matcher(text).find() && System.nanoTime() < deadline) {
            assertTrue(stream.awaitFrame(1, TimeUnit.SECONDS), "spinner stopped rendering frames");
            stream.nextFrame();
            text = stream.text();
        }
        assertTrue(ELAPSED.matcher(text).find(), "no single parenthesised elapsed time: " + text);
        assertFalse(text.contains("(0s)"), "the label baked a stale time in: " + text);
        spinner.stop();
    }

    /**
     * Records rendered bytes and signals each write through a latch so tests await real frames instead of sleeping.
     */
    private static final class RecordingStream extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final AtomicReference<CountDownLatch> frame = new AtomicReference<>(new CountDownLatch(1));
        private final AtomicBoolean live = new AtomicBoolean();

        PrintStream printStream() {
            return new PrintStream(this, true);
        }

        /** A {@link PromptOutput} writing to this stream, whose {@code active()} a test can flip. */
        PromptOutput promptOutput() {
            return new PromptOutput() {
                @Override
                public boolean active() {
                    return live.get();
                }

                @Override
                public void print(final String text) {
                    writeText(text);
                }

                @Override
                public void printErr(final String text) {
                    writeText(text);
                }
            };
        }

        /** Turns the "a prompt owns the row" flag the returned sink reports. */
        void promptLive(final boolean value) {
            live.set(value);
        }

        String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }

        void nextFrame() {
            frame.set(new CountDownLatch(1));
        }

        boolean awaitFrame() throws InterruptedException {
            return awaitFrame(5, TimeUnit.SECONDS);
        }

        boolean awaitFrame(final long timeout, final TimeUnit unit) throws InterruptedException {
            return frame.get().await(timeout, unit);
        }

        int size() {
            return bytes.size();
        }

        private void writeText(final String text) {
            final var bytes = text.getBytes(StandardCharsets.UTF_8);
            write(bytes, 0, bytes.length);
        }

        @Override
        public void write(final int b) {
            bytes.write(b);
            frame.get().countDown();
        }

        @Override
        public void write(final byte[] b, final int off, final int len) {
            bytes.write(b, off, len);
            frame.get().countDown();
        }
    }
}
