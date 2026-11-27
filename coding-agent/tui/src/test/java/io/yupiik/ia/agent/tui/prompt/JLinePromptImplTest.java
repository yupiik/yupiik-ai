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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.cli.prompt.PromptInterruptException;
import io.yupiik.ia.agent.core.cli.prompt.PromptOutput;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
class JLinePromptImplTest {
    private PrintStream originalOut;
    private PrintStream originalErr;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void capture() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void externalOutputUsesCurrentTerminalWidthAndFallsBackAfterTerminalFailure() {
        final var width = new AtomicInteger(160);
        final var terminal = (Terminal) Proxy.newProxyInstance(
                Terminal.class.getClassLoader(), new Class<?>[] {Terminal.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getWidth")) {
                        if (width.get() < 0) {
                            throw new IllegalStateException("closed terminal");
                        }
                        return width.get();
                    }
                    return null;
                });
        final var reader = (LineReader) Proxy.newProxyInstance(
                LineReader.class.getClassLoader(),
                new Class<?>[] {LineReader.class},
                (proxy, method, args) -> method.getName().equals("getTerminal") ? terminal : null);
        final var output = new JLinePromptImpl(reader, null).externalOutput();
        assertEquals(160, output.columns());
        width.set(72);
        assertEquals(72, output.columns());
        width.set(0);
        assertEquals(PromptOutput.raw().columns(), output.columns());
        width.set(-1);
        assertEquals(PromptOutput.raw().columns(), output.columns());
    }

    @Test
    void mapsJLineExceptionsToThePromptContract() throws Exception {
        final var calls = new AtomicInteger();
        final var prompts = new AtomicReference<String>();
        final var reader = (LineReader) Proxy.newProxyInstance(
                LineReader.class.getClassLoader(), new Class<?>[] {LineReader.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("readLine")) {
                        return null;
                    }
                    prompts.set((String) args[0]);
                    return switch (calls.getAndIncrement()) {
                        case 0 -> "hello";
                        case 1 -> throw new UserInterruptException("ctrl-c");
                        default -> throw new EndOfFileException();
                    };
                });
        final var prompt = new JLinePromptImpl(reader, null);

        assertTrue(prompt.editing());
        assertEquals(Optional.of("hello"), prompt.readLine("> "));
        assertThrows(PromptInterruptException.class, () -> prompt.readLine("> "));
        assertEquals(Optional.empty(), prompt.readLine("> "));
    }

    @Test
    void escapesPerCentForTheJLinePromptPattern() throws Exception {
        final var seen = new AtomicReference<String>();
        final var prompt = new JLinePromptImpl(
                reader(arg -> {
                    seen.set(arg);
                    return null;
                }),
                null);
        assertEquals(Optional.empty(), prompt.readLine(">>>%>MID< <<"));
        assertEquals(">>>%%>MID< <<", seen.get(), "the reader must receive escaped percents");
    }

    @Test
    void escapesLiteralPerCentsOnly() {
        assertEquals("a%%>b", JLinePromptImpl.escapePromptForJLine("a%>b"));
        assertEquals(">>>%%>MID< <<", JLinePromptImpl.escapePromptForJLine(">>>%>MID< <<"));
        assertEquals("0%%> ", JLinePromptImpl.escapePromptForJLine("0%> "));
        assertEquals("x%%y", JLinePromptImpl.escapePromptForJLine("x%%y"));
        assertEquals("prompt", JLinePromptImpl.escapePromptForJLine("prompt"));
        assertEquals("%{}", JLinePromptImpl.escapePromptForJLine("%{}"));
        assertEquals("%N %M %{hidden%}", JLinePromptImpl.escapePromptForJLine("%N %M %{hidden%}"));
        assertEquals("%P10.", JLinePromptImpl.escapePromptForJLine("%P10."));
        assertEquals("%-5P", JLinePromptImpl.escapePromptForJLine("%-5P"));
        assertEquals("abc%%", JLinePromptImpl.escapePromptForJLine("abc%"));
    }

    @Test
    void standaloneEscIsAnInterruptAndEscapeSequencesAreConsumed() {
        // a real-tty jline reader cannot be peeked without blocking, so the
        // prompt must not advertise the interrupt poll even with a terminal
        assertFalse(
                new JLinePromptImpl(readerWithTerminal(new ScriptedReader()), null).isInterruptAvailable(),
                "a jline reader is not pollable without blocking: advertising it parks the REPL");

        // ESC alone at the end of the stream: a standalone Esc press -> interrupt
        final var promptEsc = new JLinePromptImpl(readerWithTerminal(new ScriptedReader('\033')), null);
        assertTrue(promptEsc.pollInterrupt(), "a standalone Esc must be reported as an interrupt");
        assertFalse(promptEsc.pollInterrupt(), "an empty stream must not interrupt");

        // ESC [ A (arrow key): the whole sequence is consumed without interrupting
        final var promptArrow = new JLinePromptImpl(readerWithTerminal(new ScriptedReader('\033', '[', 'A')), null);
        assertFalse(promptArrow.pollInterrupt(), "an escape sequence must not interrupt");
        assertFalse(promptArrow.pollInterrupt(), "the sequence must be fully consumed");
    }

    @Test
    void externalOutputRoutesThroughPrintAboveWhileThePromptIsLive() throws Exception {
        // The regression this guards: a turn-cleanup / late sub-agent chunk landing on the
        // row jline just painted. jline only *diffs* its display, so a raw write there
        // leaves the prompt invisible until the user types - the reported bug.
        final var painted = new CountDownLatch(1); // reached once the row is claimed
        final var release = new CountDownLatch(1); // lets the read complete
        final var above = new ArrayList<String>();
        final var terminalWriter = new StringWriter();
        final var reader = blockingReader(painted, release, above, terminalWriter);
        final var prompt = new JLinePromptImpl(reader, null);
        final var output = prompt.externalOutput();
        assertNotNull(output, "jline backend must expose a prompt output sink");
        assertFalse(output.active(), "not active while no read is in progress");

        final var read = new Thread(() -> readQuietly(prompt, "> "), "readLine");
        read.start();
        assertTrue(painted.await(5, TimeUnit.SECONDS), "the read never reached the point where it paints");

        assertTrue(output.active(), "active while a read is in progress");
        output.print("late turn chunk\n");
        output.printErr("late turn chunk\n");
        assertEquals(List.of("late turn chunk\n", "late turn chunk\n"), above, "chunks must reach printAbove");
        assertEquals("", stdout(), "nothing may reach stdout while the prompt owns the row");
        assertEquals("", stderr(), "nothing may reach stderr while the prompt owns the row");

        release.countDown();
        read.join(5000);
        assertFalse(output.active(), "the sink is no longer active once the read completed");
    }

    @Test
    void unterminatedRawWriteMakesTheNextPromptStartOnAFreshRow() throws Exception {
        // No clr_eol / columns-2 spaces: the old AUTO_FRESH_LINE widget destroyed the
        // whole row, which is precisely how the prompt got erased.
        final var terminalWriter = new StringWriter();
        final var prompt = new JLinePromptImpl(passthroughReader(new ArrayList<>(), terminalWriter), null);

        prompt.externalOutput().printErr("streamed without a newline"); // e.g. a thinking delta tail
        assertTrue(stderr().contains("streamed without a newline"), "an idle read must write raw");

        prompt.readLine("> "); // consumes the dirty flag with a single line break
        assertEquals("\n", terminalWriter.toString(), "the fresh row must be a single line break");
        assertFalse(terminalWriter.toString().contains("\u001b[2K"), "the row must never be erased with clr_eol");
        assertFalse(terminalWriter.toString().contains("  "), "no padding may be painted to force a wrap");

        terminalWriter.getBuffer().setLength(0);
        prompt.readLine("> "); // the row is clean now: nothing more to close
        assertEquals("", terminalWriter.toString(), "no extra line break once the row is already closed");
    }

    @Test
    void noRawWriteEverLandsWhileThePromptIsLive() throws Exception {
        // Hammer the transition: every write issued after the row was claimed must be
        // routed through printAbove, so a chunk can never straddle the claim.
        for (int round = 0; round < 50; round++) {
            final var painted = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            final var above = new ArrayList<String>();
            final var prompt = new JLinePromptImpl(blockingReader(painted, release, above, new StringWriter()), null);
            final var output = prompt.externalOutput();

            final var read = new Thread(() -> readQuietly(prompt, "> "), "read-" + round);
            read.start();
            assertTrue(painted.await(5, TimeUnit.SECONDS), "round " + round + ": the read never claimed the row");

            final var writer = new Thread(
                    () -> {
                        for (int i = 0; i < 16; i++) {
                            output.print("chunk" + i + "\n");
                            output.printErr("chunk" + i + "\n");
                        }
                    },
                    "writer-" + round);
            writer.start();
            writer.join(5000);
            assertEquals("", stdout(), "round " + round + ": a raw write reached stdout while the prompt was live");
            assertEquals("", stderr(), "round " + round + ": a raw write reached stderr while the prompt was live");
            assertEquals(32, above.size(), "round " + round + ": every chunk must have gone through printAbove");

            release.countDown();
            read.join(5000);
        }
    }

    @Test
    void traceRecordsTheWriteDecisionWithoutTouchingTheTerminal(final @TempDir Path temp) throws Exception {
        final var trace = temp.resolve("prompt-trace.log");
        final var painted = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var above = new ArrayList<String>();
        final var prompt =
                new JLinePromptImpl(blockingReader(painted, release, above, new StringWriter()), null, true, trace);
        final var output = prompt.externalOutput();

        output.print("raw chunk\n"); // idle read -> raw
        final var read = new Thread(() -> readQuietly(prompt, "> "), "readLine");
        read.start();
        assertTrue(painted.await(5, TimeUnit.SECONDS));
        output.print("live chunk\n"); // live read -> printAbove
        release.countDown();
        read.join(5000);

        assertTrue(Files.exists(trace), "the trace file must be created");
        final var content = Files.readString(trace, StandardCharsets.UTF_8);
        assertTrue(content.contains("branch=raw"), "the raw write must be recorded: " + content);
        assertTrue(content.contains("branch=printAbove"), "the printAbove write must be recorded: " + content);
        assertTrue(content.contains("readerLive=false"), "the raw record must show an idle read: " + content);
        assertTrue(content.contains("readerLive=true"), "the printAbove record must show a live read: " + content);
        assertFalse(content.contains("\u001b"), "control characters must be escaped: " + content);
        assertEquals(1, count(stdout() + stderr(), '\n'), "tracing must never write to the terminal");
    }

    private static int count(final String haystack, final char needle) {
        var n = 0;
        for (int i = 0; i < haystack.length(); i++) {
            if (haystack.charAt(i) == needle) {
                n++;
            }
        }
        return n;
    }

    /** Swallows the checked {@link java.io.IOException} of a background read. */
    private void readQuietly(final JLinePromptImpl prompt, final String promptText) {
        try {
            prompt.readLine(promptText);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * {@link LineReader} proxy whose {@code readLine} returns straight away, so a
     * read never stays live across the assertions.
     */
    private LineReader passthroughReader(final List<String> above, final StringWriter terminalWriter) {
        return readerProxy(above, terminalWriter, null, null);
    }

    /**
     * {@link LineReader} proxy whose {@code readLine} signals {@code painted} (the row
     * is claimed) and then parks on {@code release}, so a test can write while the
     * prompt is verifiably live.
     */
    private LineReader blockingReader(
            final CountDownLatch painted,
            final CountDownLatch release,
            final List<String> above,
            final StringWriter terminalWriter) {
        return readerProxy(above, terminalWriter, painted, release);
    }

    private LineReader readerProxy(
            final List<String> above,
            final StringWriter terminalWriter,
            final CountDownLatch painted,
            final CountDownLatch release) {
        final var terminal = (Terminal) Proxy.newProxyInstance(
                Terminal.class.getClassLoader(),
                new Class<?>[] {Terminal.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "writer" -> new PrintWriter(terminalWriter, true);
                    case "flush" -> {
                        terminalWriter.flush();
                        yield null;
                    }
                    case "reader" -> new ScriptedReader();
                    default -> {
                        yield null;
                    }
                });
        return (LineReader) Proxy.newProxyInstance(
                LineReader.class.getClassLoader(), new Class<?>[] {LineReader.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getTerminal")) {
                        return terminal;
                    }
                    if (method.getName().equals("readLine")) {
                        if (painted != null) {
                            painted.countDown();
                            try {
                                release.await(5, TimeUnit.SECONDS);
                            } catch (final InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        return "answer";
                    }
                    if (method.getName().equals("printAbove")) {
                        above.add((String) args[0]);
                        return null;
                    }
                    return null;
                });
    }

    @Test
    void readLineFlushesTheTerminalWriterBeforeRendering() throws Exception {
        final var flushed = new AtomicInteger();
        final var terminal = (Terminal) Proxy.newProxyInstance(
                Terminal.class.getClassLoader(),
                new Class<?>[] {Terminal.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "flush" -> {
                        flushed.incrementAndGet();
                        yield null;
                    }
                    case "reader" -> {
                        yield new ScriptedReader('x', '\r');
                    }
                    default -> {
                        yield null;
                    }
                });
        final var reader = (LineReader) Proxy.newProxyInstance(
                LineReader.class.getClassLoader(), new Class<?>[] {LineReader.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getTerminal")) {
                        return terminal;
                    }
                    if (method.getName().equals("readLine")) {
                        return "line";
                    }
                    return null;
                });
        final var prompt = new JLinePromptImpl(reader, null);
        assertEquals(Optional.of("line"), prompt.readLine("> "));
        assertTrue(flushed.get() > 0, "the terminal writer must be flushed before each readLine");
    }

    /**
     * Deterministic in-memory {@link org.jline.utils.NonBlockingReader}: characters
     * are served synchronously (no feeder thread), exhausted reads return
     * {@code READ_EXPIRED} and a peek never consumes.
     */
    private static final class ScriptedReader extends NonBlockingReader {
        private final int[] chars;
        private int index;

        private ScriptedReader(final int... chars) {
            this.chars = chars;
        }

        @Override
        protected int read(final long timeout, final boolean isPeek) {
            if (index >= chars.length) {
                return READ_EXPIRED;
            }
            final var value = chars[index];
            if (!isPeek) {
                index++;
            }
            return value;
        }

        @Override
        public int readBuffered(final char[] buffer, final int offset, final int length, final long timeout) {
            return -1;
        }

        @Override
        public void close() {
            // in-memory synchronous reader: nothing to release
        }
    }

    /** LineReader proxy whose Terminal only serves {@code reader()}; nothing else is touched by the prompt. */
    private LineReader readerWithTerminal(final NonBlockingReader nonBlocking) {
        final var terminal = (Terminal) Proxy.newProxyInstance(
                Terminal.class.getClassLoader(),
                new Class<?>[] {Terminal.class},
                (proxy, method, args) -> method.getName().equals("reader") ? nonBlocking : null);
        return (LineReader) Proxy.newProxyInstance(
                LineReader.class.getClassLoader(),
                new Class<?>[] {LineReader.class},
                (proxy, method, args) -> method.getName().equals("getTerminal") ? terminal : null);
    }

    private LineReader reader(final Function<String, Object> onReadLine) {
        return (LineReader) Proxy.newProxyInstance(
                LineReader.class.getClassLoader(), new Class<?>[] {LineReader.class}, (proxy, method, args) -> {
                    if (method.getName().equals("readLine")) {
                        return onReadLine.apply((String) args[0]);
                    }
                    return null;
                });
    }
}
