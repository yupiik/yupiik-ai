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
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.scanning.Bean;
import io.yupiik.ia.agent.core.wiring.HackStdIo;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * Producer for the JLine terminal and its reader so they can be injected
 * (instead of being built lazily inside {@link JLinePromptImpl}).
 * <p>
 * The terminal is deliberately the jline <em>system</em> terminal, never one
 * built from custom streams. The system terminal reads and renders through the
 * tty file descriptor directly, so it is immune to the {@link HackStdIo}
 * takeover of {@code System.out}/{@code System.err} (its output can never
 * re-enter the prompt sink) and it is the only terminal which puts the real
 * tty in raw mode, disabling the tty echo jline would otherwise fight.
 * <p>
 * The {@code streams(...)} alternative (nested {@code PosixPtyTerminal}) was
 * tried when the prompt-sink work landed (the {@code aea42d9} mistake): it
 * made jline paint on a pty slave while a background pump copied the bytes to
 * the real terminal and the real tty stayed cooked - so every line jline
 * redrew (completion, backspace, cursor motion) raced with the tty echo and
 * with the prompt sink writes, and the completed text was never repainted on
 * the prompt row. The completion kept working in the buffer (a Tab then Enter
 * still dispatched the command) but the screen went stale - the "completion
 * is a rendering/flushing issue" bug.
 * <p>
 * The {@code HackStdIo} parameter is retained so the generated Fusion bean
 * (which invokes {@code terminal(HackStdIo)}) stays binary compatible; the
 * hijack does not influence the terminal selection anymore.
 */
@DefaultScoped
public class JLineProducer {
    @Bean
    @ApplicationScoped
    public Terminal terminal(final HackStdIo stdio) {
        try {
            return TerminalBuilder.builder().system(true).build();
        } catch (final IOException e) {
            throw new UncheckedIOException("Can't initialize the JLine terminal", e);
        }
    }

    @Bean
    @ApplicationScoped
    public LineReader lineReader(final Terminal terminal, final SlashCommandCompleter completer) {
        return LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(completer)
                .parser(completer.parser())
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                // AUTO_FRESH_LINE is deliberately NOT set: its freshLine() widget runs
                // `clr_eol` + `columns-2` spaces at every prompt, which erases whatever
                // the row already holds and scrolls the screen when the cursor was not
                // at column 0 - erasing the row is what made the prompt disappear. The
                // deterministic equivalent (one line break, and only when the previous
                // raw write was left unterminated) is done by JLinePromptImpl.
                .build();
    }
}
