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
package io.yupiik.ia.agent.core.wiring;

import io.yupiik.ia.agent.core.cli.prompt.PromptOutput;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Makes <em>every</em> terminal writer prompt aware, including {@code java.util.logging}.
 * <p>
 * The "prompt invisible after a turn" bug was originally fixed by enumerating every raw writer and pushing it
 * through {@link PromptOutput}. That was not enough: the project ships no logging framework (no slf4j/logback/
 * log4j on the classpath, no {@code logging.properties}) so JUL's default {@code ConsoleHandler} wrote straight
 * to {@code System.err}. On a turn that rebuilt the code graph, {@code StructuralCodeGraph} logged
 * {@code "CodeGraph ensureReady slow-path(rebuild) took Nms"} from a background {@code yca-scheduler} thread,
 * seconds after the turn had ended and after the next prompt was painted. JLine only <em>diffs</em> its display,
 * so the prompt row was desynced and stayed invisible until the next keystroke.
 * <p>
 * Enumerating writers does not scale - the next one would be a library or a new call site. This bean instead
 * takes over {@code System.out}/{@code System.err} themselves, so any writer is prompt aware by construction.
 *
 * <h2>Lifecycle</h2>
 * Everything here is instance state; there is no static field. {@link StdIoControl} owns the lifecycle: it
 * installs this control when the {@code Configuration} is created (the {@code ConfigurationRegistration} event,
 * ordered after the configuration source registrations so {@code yca.logging.auto} is readable), i.e. before the
 * first {@code Logger.getLogger(...)} initialised JUL. The default {@code ConsoleHandler} is therefore created with
 * the <em>hijacked</em> {@code System.err} and keeps writing through it: the logging manager, the root handlers,
 * their levels, their formatters and {@code useParentHandlers} are exactly what the user configured. Nothing is
 * removed, added, re-parented or re-leveled. The {@code Stop} listener of the module restores the pristine
 * streams so a second run in the same JVM starts clean.
 *
 * <h2>Routing</h2>
 * One target, defaulting to passthrough (raw writes to the pristine streams) so a command that does not own the
 * terminal is unaffected. The {@code cli} REPL registers its prompt aware sink, which routes a record landing
 * mid-prompt through {@code printAbove} so the prompt is repainted; the {@code tui} and the headless {@code run}
 * command leave it in passthrough.
 *
 * <h2>Recursion</h2>
 * A delegating {@code System.out} must never write back through itself, so {@link #out()} / {@link #err()} return
 * the <em>pristine</em> stream captured at installation when {@code System.out} is still our wrapper, and the
 * <em>live</em> {@code System.out} otherwise. That second case is what keeps a test (or any embedder) that swaps
 * {@code System.out} in control: the wrapper is bypassed, not a closed loop. Injectable consumers use
 * {@link #out()} / {@link #err()}; the non-injectable static factory {@code PromptOutput.raw()} resolves the same
 * way through {@link HijackedPrintStream#pristine()}.
 */
public class HackStdIo {
    private final Lock lock = new ReentrantLock();
    private boolean installed;
    /** The streams captured at {@link #install()} time; {@code null} until then. */
    private PrintStream pristineOut;

    private PrintStream pristineErr;
    /** Our wrappers, only set while installed; used to detect "nobody replaced me". */
    private HijackedPrintStream wrapperOut;

    private HijackedPrintStream wrapperErr;
    private volatile PromptOutput streamTarget;

    /** Installs the wrapper streams; idempotent and a no-op if another runtime already owns them. */
    public void install() {
        lock.lock();
        try {
            if (installed || System.out instanceof HijackedPrintStream) {
                return;
            }
            final var out = System.out;
            final var err = System.err;
            pristineOut = out;
            pristineErr = err;
            wrapperOut = new HijackedPrintStream(out, new StreamPump(out, out.charset(), false), true, out.charset());
            wrapperErr = new HijackedPrintStream(err, new StreamPump(err, err.charset(), true), true, err.charset());
            System.setOut(wrapperOut);
            System.setErr(wrapperErr);
            installed = true;
        } finally {
            lock.unlock();
        }
    }

    /** Restores the original streams so a second run in the same JVM starts clean. */
    public void uninstall() {
        lock.lock();
        try {
            if (!installed) {
                return;
            }
            if (System.out == wrapperOut) {
                System.setOut(pristineOut);
            }
            if (System.err == wrapperErr) {
                System.setErr(pristineErr);
            }
            wrapperOut = null;
            wrapperErr = null;
            pristineOut = null;
            pristineErr = null;
            streamTarget = null;
            installed = false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * The stream to write "stdout-ish" bytes to. The pristine stream when {@code System.out} is still our wrapper
     * (so the wrapper cannot feed itself), the live {@code System.out} otherwise (so a test that swapped it, or an
     * embedder that installed its own, keeps full control of what it captures).
     */
    public PrintStream out() {
        final var pristine = pristineOut;
        if (pristine != null && System.out == wrapperOut) {
            return pristine;
        }
        return System.out;
    }

    /** Same as {@link #out()} for {@code System.err}. */
    public PrintStream err() {
        final var pristine = pristineErr;
        if (pristine != null && System.err == wrapperErr) {
            return pristine;
        }
        return System.err;
    }

    /** Where the hijacked streams are forwarded; {@code null} (the default) means passthrough. */
    public PromptOutput streamTarget() {
        return streamTarget;
    }

    public void streamTarget(final PromptOutput target) {
        this.streamTarget = target;
    }

    /** {@code true} while the wrapper streams are installed. */
    public boolean installed() {
        lock.lock();
        try {
            return installed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * The wrapper installed as {@code System.out}/{@code System.err}: routes every byte to the
     * {@link PromptOutput} target while the command owns the terminal, and exposes the pristine stream it
     * replaced so a non-injectable writer (e.g. {@code PromptOutput.raw()}) can reach the physical terminal
     * without feeding the wrapper back into itself.
     */
    public static final class HijackedPrintStream extends PrintStream {
        private final PrintStream pristine;

        HijackedPrintStream(
                final PrintStream pristine, final OutputStream pump, final boolean autoFlush, final Charset charset) {
            super(pump, autoFlush, charset);
            this.pristine = pristine;
        }

        /** The stream that {@code System.out} was when this wrapper was installed. */
        public PrintStream pristine() {
            return pristine;
        }
    }

    /**
     * Forwards decoded text to a {@link PromptOutput}, one complete line per call so a live prompt is repainted
     * with {@code printAbove} per line instead of per partial chunk. Bytes are decoded with the charset of the
     * stream being replaced so nothing is transcoded twice.
     */
    private final class StreamPump extends OutputStream {
        private final PrintStream delegate;
        private final Charset charset;
        private final boolean stderr;

        StreamPump(final PrintStream delegate, final Charset charset, final boolean stderr) {
            this.delegate = delegate;
            this.charset = charset == null ? StandardCharsets.UTF_8 : charset;
            this.stderr = stderr;
        }

        @Override
        public void write(final int b) {
            final var target = streamTarget;
            if (target == null) {
                delegate.write(b);
                return;
            }
            final var text = new String(new byte[] {(byte) b}, charset);
            if (stderr) {
                target.printErr(text);
            } else {
                target.print(text);
            }
        }

        @Override
        public void write(final byte[] bytes, final int off, final int len) {
            if (len <= 0) {
                return;
            }
            final var target = streamTarget;
            if (target == null) {
                delegate.write(bytes, off, len);
                return;
            }
            final var text = new String(bytes, off, len, charset);
            var from = 0;
            while (true) {
                final var nl = text.indexOf('\n', from);
                if (nl < 0) {
                    break;
                }
                emit(target, text.substring(from, nl + 1));
                from = nl + 1;
            }
            if (from < text.length()) {
                emit(target, text.substring(from));
            }
        }

        private void emit(final PromptOutput target, final String line) {
            if (stderr) {
                target.printErr(line);
            } else {
                target.print(line);
            }
        }

        @Override
        public void flush() {
            delegate.flush();
        }
    }
}
