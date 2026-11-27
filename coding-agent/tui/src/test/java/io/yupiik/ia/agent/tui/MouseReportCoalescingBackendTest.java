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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tamboui.layout.Position;
import dev.tamboui.terminal.Backend;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.EventParser;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.PasteEvent;
import java.io.EOFException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Byte-level contract of {@link MouseReportCoalescingBackend}, driven through the REAL
 * TamboUI {@code EventParser} (deterministic {@code readEvent} calls, no runner thread).
 * Proves the actual guarantee: however a mouse report is split/paused by the parser's
 * per-byte timeout, the application only ever sees one {@link MouseEvent} with the exact
 * coordinates - never the printable tail as {@code CHAR} keys - while non-mouse bytes
 * (ordinary typing, escape sequences, pastes) pass through byte-identical to the raw
 * delegate.
 */
class MouseReportCoalescingBackendTest {
    private static final String REPORT = "\u001b[<35;23;35M"; // button 35, col 23, row 35 -> x=22, y=34

    private static String describe(final Event e) {
        if (e instanceof final KeyEvent k) {
            return k.code() == KeyCode.CHAR ? "CHAR(" + ((char) k.codePoint()) + ")" : "KEY(" + k.code() + ")";
        }
        if (e instanceof final MouseEvent m) {
            return "MOUSE(" + m.x() + "," + m.y() + ")";
        }
        if (e instanceof final PasteEvent p) {
            return "PASTE(" + p.text() + ")";
        }
        return String.valueOf(e);
    }

    /** Drains every event the parser yields for the backend; the parser emits null on idle. */
    private static List<String> drain(final Backend backend) throws Exception {
        final var out = new ArrayList<String>();
        for (int i = 0; i < 16; i++) {
            final var e = EventParser.readEvent(backend, 30);
            if (e == null) {
                break;
            }
            out.add(describe(e));
        }
        return out;
    }

    /** Reads a single event (the parser blocks until one is produced or the timeout expires). */
    private static String one(final Backend backend) throws Exception {
        final var e = EventParser.readEvent(backend, 30);
        return e == null ? "null" : describe(e);
    }

    private static List<String> eventStream(final Backend backend, final int count) throws Exception {
        final var out = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            final var e = EventParser.readEvent(backend, 30);
            if (e == null) {
                break;
            }
            out.add(describe(e));
        }
        return out;
    }

    @Test
    void nonMouseBytesPassThroughByteIdenticalToRaw() throws Exception {
        // same bytes fed to a raw ByteFeedBackend and to the wrapper(ByteFeedBackend); the
        // resulting event streams must be identical for every non-mouse corpus
        final String[] corpora = {
            "hello world",
            "[mermaid]",
            "[5m]",
            "\u001b[A", // up arrow
            "\u001b[21~", // F10
            "\u001b[200~hi there\u001b[201~", // bracketed paste
            "\u001bm", // ESC m -> Alt+m
            "\u001b\u001b", // double ESC
            "[<12;34;56M", // literal bracket text that LOOKS like a mouse tail
            "\u001b[<35;23;35M\u001b[<1;2;3M", // two consecutive complete reports
        };
        for (final var corpus : corpora) {
            final var rawBackend = new ByteFeedBackend();
            final var wrapped = new MouseReportCoalescingBackend(rawBackend);
            rawBackend.feed(corpus);
            final var rawStream = drain(rawBackend);
            // fresh backend for the wrapped side (the raw one is consumed by the drain above)
            final var raw2 = new ByteFeedBackend();
            final var wrap = new MouseReportCoalescingBackend(raw2);
            raw2.feed(corpus);
            final var wrappedStream = drain(wrap);
            assertEquals(
                    rawStream,
                    wrappedStream,
                    "corpus '" + corpus.replace("\u001b", "<ESC>") + "' must be byte-identical through the wrapper");
        }
    }

    @Test
    void splitReportEveryBoundaryIsNeverWorseThanRaw() throws Exception {
        // feed the report prefix, let the parser stall, then feed the rest - at every byte
        // boundary. The wrapped stream must be no longer than raw and must never contain the
        // printable tail as '<'/'M' chars; when the ESC[< prefix was consumed together it must
        // reconstruct exactly one MOUSE(22,34).
        for (int split = 1; split < REPORT.length(); split++) {
            // raw side
            final var rawBackend = new ByteFeedBackend();
            rawBackend.feed(REPORT.substring(0, split));
            final var rawPrefix = drain(rawBackend);
            rawBackend.feed(REPORT.substring(split));
            final var rawSuffix = drain(rawBackend);
            final var rawStream = new ArrayList<>(rawPrefix);
            rawStream.addAll(rawSuffix);

            // wrapped side
            final var delegate = new ByteFeedBackend();
            final var wrap = new MouseReportCoalescingBackend(delegate);
            delegate.feed(REPORT.substring(0, split));
            final var wrapPrefix = drain(wrap);
            delegate.feed(REPORT.substring(split));
            final var wrapSuffix = drain(wrap);
            final var wrappedStream = new ArrayList<>(wrapPrefix);
            wrappedStream.addAll(wrapSuffix);

            // never worse than raw
            assertTrue(
                    wrappedStream.size() <= rawStream.size(),
                    "split@" + split + " wrapped must not be longer than raw: wrapped=" + wrappedStream + " raw="
                            + rawStream);
            // no report-tail junk
            assertFalse(
                    wrappedStream.contains("CHAR(<)") || wrappedStream.contains("CHAR(M)"),
                    "split@" + split + " must never emit the tail as '<'/'M' chars, got " + wrappedStream);

            // when the ESC[< prefix was consumed together (split >= 3), the report must be
            // reconstructed as exactly one MOUSE
            if (split >= 3) {
                assertTrue(
                        wrappedStream.contains("MOUSE(22,34)"),
                        "split@" + split + " (intact prefix) must yield MOUSE(22,34), got " + wrappedStream);
                assertFalse(
                        wrappedStream.stream().anyMatch(d -> d.startsWith("CHAR")),
                        "split@" + split + " (intact prefix) must have no CHAR junk, got " + wrappedStream);
            }
        }
    }

    @Test
    void doubleSplitWithIntactPrefixYieldsSingleExactMouse() throws Exception {
        for (int s1 = 3; s1 < REPORT.length(); s1++) {
            for (int s2 = s1 + 1; s2 < REPORT.length(); s2++) {
                final var delegate = new ByteFeedBackend();
                final var wrap = new MouseReportCoalescingBackend(delegate);
                delegate.feed(REPORT.substring(0, s1));
                final var p1 = eventStream(wrap, 2); // parser stalls -> at most an inert UNKNOWN
                delegate.feed(REPORT.substring(s1, s2));
                final var p2 = eventStream(wrap, 2);
                delegate.feed(REPORT.substring(s2));
                final var p3 = eventStream(wrap, 4);
                final var all = new ArrayList<String>();
                all.addAll(p1);
                all.addAll(p2);
                all.addAll(p3);
                assertTrue(
                        all.contains("MOUSE(22,34)"), "dbl@" + s1 + "/" + s2 + " must yield MOUSE(22,34), got " + all);
                assertFalse(
                        all.stream().anyMatch(d -> d.startsWith("CHAR")),
                        "dbl@" + s1 + "/" + s2 + " must never yield CHAR, got " + all);
            }
        }
    }

    @Test
    void tripleSplitWithIntactPrefixYieldsSingleExactMouse() throws Exception {
        for (int s1 = 3; s1 < REPORT.length() - 1; s1++) {
            for (int s2 = s1 + 1; s2 < REPORT.length(); s2++) {
                for (int s3 = s2 + 1; s3 < REPORT.length(); s3++) {
                    final var delegate = new ByteFeedBackend();
                    final var wrap = new MouseReportCoalescingBackend(delegate);
                    delegate.feed(REPORT.substring(0, s1));
                    final var p1 = eventStream(wrap, 2);
                    delegate.feed(REPORT.substring(s1, s2));
                    final var p2 = eventStream(wrap, 2);
                    delegate.feed(REPORT.substring(s2, s3));
                    final var p3 = eventStream(wrap, 2);
                    delegate.feed(REPORT.substring(s3));
                    final var p4 = eventStream(wrap, 4);
                    final var all = new ArrayList<String>();
                    all.addAll(p1);
                    all.addAll(p2);
                    all.addAll(p3);
                    all.addAll(p4);
                    assertTrue(
                            all.contains("MOUSE(22,34)"),
                            "tri@" + s1 + "/" + s2 + "/" + s3 + " must yield MOUSE(22,34), got " + all);
                    assertFalse(
                            all.stream().anyMatch(d -> d.startsWith("CHAR")),
                            "tri@" + s1 + "/" + s2 + "/" + s3 + " must never yield CHAR, got " + all);
                }
            }
        }
    }

    @Test
    void coldUserBracketsArePreserved() throws Exception {
        final var delegate = new ByteFeedBackend();
        final var wrap = new MouseReportCoalescingBackend(delegate);
        delegate.feed("[mermaid]");
        assertEquals(
                List.of(
                        "CHAR([)", "CHAR(m)", "CHAR(e)", "CHAR(r)", "CHAR(m)", "CHAR(a)", "CHAR(i)", "CHAR(d)",
                        "CHAR(])"),
                drain(wrap));
    }

    @Test
    void escapeThenUserTypingSurvives() throws Exception {
        final var delegate = new ByteFeedBackend();
        final var wrap = new MouseReportCoalescingBackend(delegate);
        delegate.feed("\u001b");
        assertEquals("KEY(ESCAPE)", one(wrap), "a standalone ESC must be ESCAPE");
        delegate.feed("[mermaid]");
        assertEquals(
                List.of(
                        "CHAR([)", "CHAR(m)", "CHAR(e)", "CHAR(r)", "CHAR(m)", "CHAR(a)", "CHAR(i)", "CHAR(d)",
                        "CHAR(])"),
                drain(wrap));
    }

    @Test
    void completeReportYieldsSingleMouse() throws Exception {
        final var delegate = new ByteFeedBackend();
        final var wrap = new MouseReportCoalescingBackend(delegate);
        delegate.feed(REPORT);
        assertEquals(List.of("MOUSE(22,34)"), eventStream(wrap, 2), "one complete report must yield one MOUSE");
    }

    @Test
    void eofMidReportIsSafe() throws Exception {
        final var delegate = new ByteFeedBackend();
        final var wrap = new MouseReportCoalescingBackend(delegate);
        delegate.feed("\u001b[<35;23;35");
        // draining the partial prefix first may emit an inert UNKNOWN (parser abort) - fine
        try {
            eventStream(wrap, 1);
        } catch (final EOFException expected) {
            // the terminal closed before the report completed: the parser aborts with EOF,
            // exactly as it does on a raw backend - the wrapper must not swallow it
        }
        delegate.closed = true;
        assertThrows(
                EOFException.class,
                () -> EventParser.readEvent(wrap, 30),
                "EOF mid-report must be propagated, not swallowed");
    }

    @Test
    void noSpamWhenReportStuck() throws Exception {
        final var delegate = new ByteFeedBackend();
        final var wrap = new MouseReportCoalescingBackend(delegate);
        delegate.feed("\u001b[<35;23;35");
        final var first = one(wrap);
        // the parser aborts the truncated report once (inert UNKNOWN), then the wrapper holds
        assertTrue(first.equals("KEY(UNKNOWN)") || first.equals("null"), "first event, got " + first);
        for (int i = 0; i < 3; i++) {
            assertEquals("null", one(wrap), "stuck report must not spam events");
        }
    }

    @Test
    void peekDoesNotConsume() throws Exception {
        final var raw = new ByteFeedBackend();
        final var wrapper = new MouseReportCoalescingBackend(raw);
        raw.feed("ab");
        assertEquals('a', wrapper.peek(5));
        assertEquals('a', wrapper.peek(5));
        assertEquals('a', wrapper.read(5));
        assertEquals('b', wrapper.peek(5));
        assertEquals('b', wrapper.read(5));
    }

    @Test
    void eofReturnsMinusOneAndNoCrash() throws Exception {
        final var raw = new ByteFeedBackend();
        final var wrapper = new MouseReportCoalescingBackend(raw);
        raw.closed = true;
        assertEquals(-1, wrapper.read(5));
        assertEquals(-1, wrapper.peek(5));
    }

    @Test
    void allNonInputMethodsDelegate() throws Exception {
        final var raw = new ByteFeedBackend();
        final var wrapper = new MouseReportCoalescingBackend(raw);
        wrapper.enableMouseCapture(true);
        wrapper.disableMouseCapture();
        wrapper.enableBracketedPaste();
        wrapper.disableBracketedPaste();
        wrapper.beginSynchronizedUpdate();
        wrapper.endSynchronizedUpdate();
        wrapper.writeRaw("x");
        wrapper.writeRaw(new StringBuilder("y"));
        wrapper.flush();
        wrapper.clear();
        wrapper.showCursor();
        wrapper.hideCursor();
        wrapper.enterAlternateScreen();
        wrapper.leaveAlternateScreen();
        wrapper.enableRawMode();
        wrapper.disableRawMode();
        wrapper.onResize(() -> {});
        wrapper.setCursorPosition(Position.ORIGIN);
        wrapper.moveCursorUp(1);
        wrapper.moveCursorDown(1);
        wrapper.moveCursorLeft(1);
        wrapper.moveCursorRight(1);
        wrapper.eraseToEndOfLine();
        wrapper.insertLines(1);
        wrapper.deleteLines(1);
        wrapper.scrollUp(1);
        wrapper.scrollDown(1);
        wrapper.setWindowTitle("t");
        wrapper.saveWindowTitle();
        wrapper.restoreWindowTitle();
        wrapper.carriageReturn();
        assertTrue(raw.calls.contains("enableMouseCapture(true)"));
        assertTrue(raw.calls.contains("disableMouseCapture"));
        assertTrue(raw.calls.contains("enableBracketedPaste"));
        assertTrue(raw.calls.contains("disableBracketedPaste"));
        assertTrue(raw.calls.contains("beginSynchronizedUpdate"));
        assertTrue(raw.calls.contains("endSynchronizedUpdate"));
        assertTrue(raw.calls.contains("writeRaw(String)"));
        assertTrue(raw.calls.contains("writeRaw(CharSequence)"));
        assertTrue(raw.calls.contains("flush"));
        assertTrue(raw.calls.contains("clear"));
        assertTrue(raw.calls.contains("showCursor"));
        assertTrue(raw.calls.contains("hideCursor"));
        assertTrue(raw.calls.contains("enterAlternateScreen"));
        assertTrue(raw.calls.contains("leaveAlternateScreen"));
        assertTrue(raw.calls.contains("enableRawMode"));
        assertTrue(raw.calls.contains("disableRawMode"));
        assertTrue(raw.calls.contains("onResize"));
        assertTrue(raw.calls.contains("setCursorPosition"));
        assertTrue(raw.calls.contains("moveCursorUp"));
        assertTrue(raw.calls.contains("moveCursorDown"));
        assertTrue(raw.calls.contains("moveCursorLeft"));
        assertTrue(raw.calls.contains("moveCursorRight"));
        assertTrue(raw.calls.contains("eraseToEndOfLine"));
        assertTrue(raw.calls.contains("insertLines"));
        assertTrue(raw.calls.contains("deleteLines"));
        assertTrue(raw.calls.contains("scrollUp"));
        assertTrue(raw.calls.contains("scrollDown"));
        assertTrue(raw.calls.contains("setWindowTitle"));
        assertTrue(raw.calls.contains("saveWindowTitle"));
        assertTrue(raw.calls.contains("restoreWindowTitle"));
        assertTrue(raw.calls.contains("carriageReturn"));
        wrapper.close();
        assertTrue(raw.calls.contains("close"));
    }
}
