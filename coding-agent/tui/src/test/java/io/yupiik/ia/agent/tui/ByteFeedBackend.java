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

import dev.tamboui.buffer.DiffResult;
import dev.tamboui.layout.Position;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Backend;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Byte-queue {@link Backend} for driving the real TamboUI {@code EventParser} at the byte
 * level in tests. {@link #read}/{@link #peek} consume/peek from an in-memory queue and return
 * {@code -2} when empty (the same "would block" sentinel a real terminal returns), optionally
 * parking on a latch so a test can pause mid-report to reproduce the parser timeout.
 * Every call is recorded so tests can assert delegation and call counts.
 */
final class ByteFeedBackend implements Backend {
    final ArrayDeque<Integer> queue = new ArrayDeque<>();
    final List<String> calls = new ArrayList<>();
    boolean closed;
    int emptyReturn = -2;

    /** Feeds raw bytes; each char becomes one code point. */
    void feed(final String bytes) {
        for (final var c : bytes.toCharArray()) {
            queue.addLast((int) c);
        }
    }

    /** Whether the byte queue currently has no data. */
    boolean isEmpty() {
        return queue.isEmpty();
    }

    @Override
    public int read(final int timeoutMillis) {
        calls.add("read(" + timeoutMillis + ")");
        if (closed) {
            return -1;
        }
        return queue.isEmpty() ? emptyReturn : queue.removeFirst();
    }

    @Override
    public int peek(final int timeoutMillis) {
        calls.add("peek(" + timeoutMillis + ")");
        if (closed) {
            return -1;
        }
        return queue.isEmpty() ? emptyReturn : queue.peekFirst();
    }

    @Override
    public void draw(final DiffResult diff) {
        calls.add("draw");
    }

    @Override
    public void flush() {
        calls.add("flush");
    }

    @Override
    public void clear() {
        calls.add("clear");
    }

    @Override
    public Size size() {
        return new Size(120, 40);
    }

    @Override
    public void showCursor() {
        calls.add("showCursor");
    }

    @Override
    public void hideCursor() {
        calls.add("hideCursor");
    }

    @Override
    public Position getCursorPosition() {
        return Position.ORIGIN;
    }

    @Override
    public void setCursorPosition(final Position position) {
        calls.add("setCursorPosition");
    }

    @Override
    public void enterAlternateScreen() {
        calls.add("enterAlternateScreen");
    }

    @Override
    public void leaveAlternateScreen() {
        calls.add("leaveAlternateScreen");
    }

    @Override
    public void enableRawMode() {
        calls.add("enableRawMode");
    }

    @Override
    public void disableRawMode() {
        calls.add("disableRawMode");
    }

    @Override
    public void enableMouseCapture() {
        calls.add("enableMouseCapture()");
    }

    @Override
    public void enableMouseCapture(final boolean motion) {
        calls.add("enableMouseCapture(" + motion + ")");
    }

    @Override
    public void disableMouseCapture() {
        calls.add("disableMouseCapture");
    }

    @Override
    public void enableBracketedPaste() {
        calls.add("enableBracketedPaste");
    }

    @Override
    public void disableBracketedPaste() {
        calls.add("disableBracketedPaste");
    }

    @Override
    public void scrollUp(final int lines) {
        calls.add("scrollUp");
    }

    @Override
    public void scrollDown(final int lines) {
        calls.add("scrollDown");
    }

    @Override
    public void insertLines(final int lines) {
        calls.add("insertLines");
    }

    @Override
    public void deleteLines(final int lines) {
        calls.add("deleteLines");
    }

    @Override
    public void moveCursorUp(final int lines) {
        calls.add("moveCursorUp");
    }

    @Override
    public void moveCursorDown(final int lines) {
        calls.add("moveCursorDown");
    }

    @Override
    public void moveCursorLeft(final int columns) {
        calls.add("moveCursorLeft");
    }

    @Override
    public void moveCursorRight(final int columns) {
        calls.add("moveCursorRight");
    }

    @Override
    public void eraseToEndOfLine() {
        calls.add("eraseToEndOfLine");
    }

    @Override
    public void setWindowTitle(final String title) {
        calls.add("setWindowTitle");
    }

    @Override
    public void saveWindowTitle() {
        calls.add("saveWindowTitle");
    }

    @Override
    public void restoreWindowTitle() {
        calls.add("restoreWindowTitle");
    }

    @Override
    public void carriageReturn() {
        calls.add("carriageReturn");
    }

    @Override
    public void beginSynchronizedUpdate() {
        calls.add("beginSynchronizedUpdate");
    }

    @Override
    public void endSynchronizedUpdate() {
        calls.add("endSynchronizedUpdate");
    }

    @Override
    public void writeRaw(final byte[] bytes) {
        calls.add("writeRaw(byte[])");
    }

    @Override
    public void writeRaw(final String text) {
        calls.add("writeRaw(String)");
    }

    @Override
    public void writeRaw(final CharSequence text) {
        calls.add("writeRaw(CharSequence)");
    }

    @Override
    public void onResize(final Runnable callback) {
        calls.add("onResize");
    }

    @Override
    public void close() {
        closed = true;
        calls.add("close");
    }
}
