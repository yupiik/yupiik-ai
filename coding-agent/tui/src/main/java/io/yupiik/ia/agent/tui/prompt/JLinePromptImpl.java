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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.order.Order;
import io.yupiik.ia.agent.core.cli.prompt.Prompt;
import io.yupiik.ia.agent.core.cli.prompt.PromptInterruptException;
import io.yupiik.ia.agent.core.cli.prompt.PromptOutput;
import io.yupiik.ia.agent.core.wiring.HackStdIo;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.utils.NonBlockingReader;

/**
 * JLine backed {@link Prompt} giving the plain {@code cli} REPL full line
 * editing: in-memory history (Up/Down), word motion (Alt-b/Alt-f),
 * word deletion (Alt-Backspace, Ctrl-W), line motion/deletion
 * (Ctrl-A/E/U/K), Left/Right/Delete and Ctrl-L. It is only available when the
 * terminal widget stack (tui module) is present; the higher
 * {@link Order} makes it win over {@code DefaultPromptImpl}.
 * <p>
 * The terminal and reader are produced by {@link JLineProducer}; the terminal
 * is an application-scoped bean whose lifecycle (including the close) is owned
 * by the producer, so this class only consumes the {@link LineReader}.
 * <p>
 * <b>Terminal ownership.</b> JLine paints the prompt once when {@code readLine}
 * starts and then only <em>diffs</em> against what it believes is on screen: any
 * byte written outside its writer that overwrites the prompt row leaves
 * {@code Display.oldLines} stale, so nothing repaints and the prompt stays
 * invisible until the next keystroke. This class is therefore the single owner
 * of the read/terminal transition: {@link #externalOutput()} decides and writes
 * under the same lock that {@code readLine} uses to claim and release the prompt
 * row, so a writer can never observe "no read in progress", let one start, and
 * then land on the freshly painted row.
 */
@ApplicationScoped
@Order(2000)
public class JLinePromptImpl implements Prompt {
    /**
     * Enables the {@code prompt-trace.log} diagnostic: one line per write
     * decision, so a raw write landing while the prompt is live - the exact
     * shape of the "the prompt only shows up once I type" bug - is
     * attributable instead of anecdotal.
     */
    static final String TRACE_ENV = "YCA_PROMPT_TRACE";

    private static final int TRACE_EXCERPT = 60;

    private final LineReader reader;

    /**
     * The terminal stream owner. {@code null} only when constructed outside the
     * runtime (tests): {@code raw()} then writes to the live {@code System.out},
     * which is the terminal in that context. In the app the runtime holds the
     * streams and {@link HackStdIo#out()}/{@link HackStdIo#err()} return the
     * pristine ones so a raw write cannot feed the wrapper back into the sink.
     */
    private final HackStdIo stdio;

    /**
     * Guards the {@code readerLive} / {@code lineDirty} pair below. Taken for
     * the whole read *transition* (claim and release) and for the whole of every
     * {@link PromptOutput} write, which makes "is a read live?" and "where does
     * this chunk go?" a single atomic decision. Never held across
     * {@code reader.readLine} itself, so the user is never blocked by a writer.
     */
    private final ReentrantLock terminalLock = new ReentrantLock();

    /** Whether {@code readLine} currently owns the prompt row. Guarded by {@link #terminalLock}. */
    private boolean readerLive;

    /**
     * Whether the last raw write left the cursor at an unknown column, so the
     * next prompt must move to a fresh row instead of drawing over it. This
     * replaces the reader's {@code AUTO_FRESH_LINE} option, whose
     * {@code freshLine()} widget unconditionally runs {@code clr_eol} plus
     * {@code columns-2} spaces at every prompt - it erases whatever the row
     * already holds and scrolls the screen when the cursor was not at column 0.
     * Guarded by {@link #terminalLock}.
     */
    private boolean lineDirty;

    private final boolean trace;
    private final Path tracePath;

    public JLinePromptImpl(final LineReader reader, final HackStdIo stdio) {
        this(reader, stdio, tracing());
    }

    JLinePromptImpl(final LineReader reader, final HackStdIo stdio, final boolean trace) {
        this(reader, stdio, trace, trace ? traceFile() : null);
    }

    /** Explicit trace file seam, so a test never has to touch {@code user.home}. */
    JLinePromptImpl(final LineReader reader, final HackStdIo stdio, final boolean trace, final Path tracePath) {
        this.reader = reader;
        this.stdio = stdio;
        this.trace = trace;
        this.tracePath = tracePath;
    }

    private static boolean tracing() {
        final var value = System.getenv(TRACE_ENV);
        return value != null && !value.isBlank() && !"false".equalsIgnoreCase(value.strip());
    }

    private static Path traceFile() {
        final var home = System.getProperty("user.home", ".");
        return Path.of(home, ".yupiik", "coding-agent", "prompt-trace.log");
    }

    @Override
    public Optional<String> readLine(final String prompt) throws IOException {
        beginRead();
        try {
            return Optional.ofNullable(reader.readLine(escapePromptForJLine(prompt)));
        } catch (final UserInterruptException e) {
            throw new PromptInterruptException();
        } catch (final EndOfFileException e) {
            return Optional.empty();
        } finally {
            endRead();
        }
    }

    /**
     * Claims the prompt row: flushes the terminal, moves to a fresh row when the
     * previous raw write left the cursor mid-line, then marks the reader live
     * <em>before</em> jline paints - so any concurrent write is forced through
     * {@code printAbove} instead of landing on the prompt.
     */
    private void beginRead() {
        resyncTerminal();
        terminalLock.lock();
        try {
            if (lineDirty) {
                newline();
                lineDirty = false;
                trace("fresh-line", "");
            }
            readerLive = true;
        } finally {
            terminalLock.unlock();
        }
    }

    /** Releases the prompt row once jline is done with it. */
    private void endRead() {
        terminalLock.lock();
        try {
            readerLive = false;
        } finally {
            terminalLock.unlock();
        }
    }

    /**
     * A single line break on jline's own writer, so it is ordered with the
     * prompt jline is about to paint (the raw {@code System.out} path would go
     * through a different buffer).
     */
    private void newline() {
        try {
            final var terminal = reader.getTerminal();
            if (terminal != null) {
                terminal.writer().print('\n');
                terminal.flush();
                return;
            }
        } catch (final RuntimeException e) {
            // fall through to the raw stream
        }
        final var out = stdio == null ? System.out : stdio.out();
        out.print('\n');
        out.flush();
    }
    /**
     * Flushes any byte left in the terminal writer before the next prompt
     * render: the previous turn's plain {@code stdout}/{@code stderr} writes
     * bypass the jline writer, so without this a stale partial line could be
     * flushed mid-render and shift the prompt.
     */
    private void resyncTerminal() {
        try {
            final var terminal = reader.getTerminal();
            if (terminal != null) {
                terminal.flush();
            }
        } catch (final RuntimeException e) {
            // best-effort: readLine still works; only the display resync is lost
        }
    }

    /**
     * JLine interprets the prompt as a prompt-pattern (bash/zsh like escapes):
     * {@code %{...%}} hidden region, {@code %N} line offset, {@code %M} message,
     * {@code %[count]P[char]} padding and {@code %%} for a literal percent.
     * Any other {@code %} is consumed together with its following character and
     * never displayed - this is how the default prompt template suffix
     * {@code %>} would silently vanish. This method doubles every {@code %}
     * that is not part of a recognised pattern so literal percents survive
     * while intentional patterns are left untouched.
     */
    static String escapePromptForJLine(final String prompt) {
        final int length = prompt.length();
        final StringBuilder builder = new StringBuilder(length + 8);
        int i = 0;
        while (i < length) {
            final char c = prompt.charAt(i);
            if (c != '%') {
                builder.append(c);
                i++;
                continue;
            }
            if (i + 1 < length) {
                final char next = prompt.charAt(i + 1);
                if (next == '%') {
                    builder.append("%%"); // already an escaped literal percent
                    i += 2;
                    continue;
                }
                if (isPatternStart(next)) {
                    builder.append(c); // intentional JLine pattern, keep as-is
                    i++;
                    continue;
                }
            }
            builder.append("%%"); // literal percent for the user
            i++;
        }
        return builder.toString();
    }

    private static boolean isPatternStart(final char c) {
        return c == '{' || c == '}' || c == 'N' || c == 'M' || c == 'P' || c == '-' || (c >= '0' && c <= '9');
    }

    @Override
    public boolean editing() {
        return true;
    }

    /**
     * The one write path for the whole REPL: a chunk landing while a prompt is
     * live goes through jline's {@link LineReader#printAbove(String)} so it is
     * printed above the prompt and the prompt is redrawn underneath; otherwise
     * it goes straight to {@code stdout}/{@code stderr} and the next prompt is
     * moved to a fresh row.
     * <p>
     * Both branches run under {@link #terminalLock} together with the decision,
     * which is what the previous {@code if (active()) printAbove else raw} split
     * in the callers could not guarantee: a late sub-agent, the async memory
     * extraction or the turn cleanup then desynchronized jline's display
     * bookkeeping and the next prompt (and the typed input) stayed invisible
     * until the following {@code readLine}.
     */
    @Override
    public PromptOutput externalOutput() {
        return new PromptOutput() {
            @Override
            public int columns() {
                try {
                    final var terminal = reader.getTerminal();
                    final var width = terminal == null ? 0 : terminal.getWidth();
                    if (width > 0) {
                        return width;
                    }
                } catch (final RuntimeException ignored) {
                    // A closed terminal must not prevent output; use the raw backend fallback.
                }
                return PromptOutput.super.columns();
            }

            @Override
            public boolean active() {
                return isReaderLive();
            }

            @Override
            public void print(final String text) {
                write(text, false);
            }

            @Override
            public void printErr(final String text) {
                write(text, true);
            }
        };
    }

    private boolean isReaderLive() {
        terminalLock.lock();
        try {
            return readerLive;
        } finally {
            terminalLock.unlock();
        }
    }

    private void write(final String text, final boolean err) {
        if (text == null || text.isEmpty()) {
            return;
        }
        terminalLock.lock();
        try {
            if (readerLive) {
                // printAbove always terminates with a newline and repaints the
                // prompt, so the row is clean afterwards
                printAbove(text);
                lineDirty = false;
            } else {
                // the read has not started yet: the chunk lands above the prompt
                // that is about to be painted. A chunk without a trailing
                // newline would leave the cursor mid-row, hence lineDirty.
                raw(text, err);
                lineDirty = !text.endsWith("\n");
            }
        } finally {
            terminalLock.unlock();
        }
    }

    private void printAbove(final String text) {
        try {
            reader.printAbove(text);
            trace("printAbove", text);
        } catch (final RuntimeException e) {
            // last-resort fallback: a closed/overridden reader still lets the
            // text reach the terminal, only the prompt redraw is lost
            raw(text, false);
        }
    }

    private void raw(final String text, final boolean err) {
        // never System.out/System.err directly: the runtime owns those streams now, so
        // writing to them would feed the wrapper back into the prompt sink
        final PrintStream stream =
                err ? (stdio == null ? System.err : stdio.err()) : (stdio == null ? System.out : stdio.out());
        stream.print(text);
        stream.flush();
        trace("raw", text);
    }

    /**
     * Appends one diagnostic line per write decision. Never writes to the
     * terminal itself (that would be the very race being diagnosed) and is a
     * no-op unless {@link #TRACE_ENV} is set.
     */
    private void trace(final String branch, final String text) {
        if (!trace || tracePath == null) {
            return;
        }
        try {
            final var excerpt = text.length() > TRACE_EXCERPT ? text.substring(0, TRACE_EXCERPT) + "..." : text;
            Files.writeString(
                    tracePath,
                    Instant.now() + " [" + Thread.currentThread().getName() + "] branch=" + branch
                            + " readerLive=" + readerLive
                            + " lineDirty=" + lineDirty
                            + " text=" + escape(excerpt) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (final IOException | RuntimeException e) {
            // diagnostics must never break the CLI
        }
    }

    private static String escape(final String text) {
        final var sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            final var c = text.charAt(i);
            if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\u001b') {
                sb.append("\\e");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * {@code false}: the jline reader of a real terminal cannot be peeked
     * without blocking in the target runtime (JDK 26 restricted natives: no
     * poll function, so {@code peek(0)} and even {@code peek(30)} park in a
     * native {@code read} until physical input arrives). Advertising an
     * interrupt poll here parked the whole REPL in the turn wait: the turn
     * completed, yet the divider and the next prompt were only painted when
     * the user typed - the exact "prompt invisible until you type" bug.
     * Returning {@code false} keeps the REPL on the plain blocking wait, which
     * repaints the prompt the moment the turn future completes.
     * {@link #pollInterrupt()} stays for readers which really are pollable
     * (unit tests).
     */
    @Override
    public boolean isInterruptAvailable() {
        return false;
    }

    /**
     * Non-blocking standalone {@code Esc} probe for the turn-wait loop: a lone
     * escape is an interrupt request, an escape sequence (arrow key, Alt-key)
     * is consumed whole and ignored so it never leaks into the next line edit
     * nor interrupts the turn.
     */
    @Override
    public boolean pollInterrupt() {
        try {
            final var terminal = reader.getTerminal();
            if (terminal == null) {
                return false;
            }
            final var input = terminal.reader();
            if (input.peek(0) != '\033') {
                return false;
            }
            input.read(); // consume the escape; peek guaranteed it is available
            // a continuation byte within 30ms means an escape sequence
            final var next = input.peek(30);
            if (next != NonBlockingReader.READ_EXPIRED && next != NonBlockingReader.EOF) {
                int tail;
                do {
                    input.read();
                    tail = input.peek(10);
                } while (tail != NonBlockingReader.READ_EXPIRED && tail != NonBlockingReader.EOF);
                return false;
            }
            return true;
        } catch (final Exception e) {
            return false;
        }
    }
}
