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
package io.yupiik.ia.agent.core.cli.prompt;

/**
 * Single write path for everything the REPL prints while a prompt backend may
 * own the terminal: the streamed answer, the turn cleanup (todos, usage,
 * dividers), late async events (sub-agents, memory extraction) and the
 * interaction cards.
 * <p>
 * The contract is deliberately narrow: <b>callers always call {@link #print(String)}
 * or {@link #printErr(String)} and never inspect {@link #active()} to pick a
 * different path</b>. A rich line-editing backend (jline) owns the prompt row
 * between the moment it paints it and the moment it releases it, and only it
 * can decide - atomically - whether a chunk can go to the raw terminal or must
 * be printed above the prompt and the prompt repainted underneath. A caller
 * checking {@link #active()} and then writing elsewhere reintroduces a
 * check-then-act race: the read can start in between, and the out-of-band write
 * lands on the freshly painted prompt row. JLine only repaints on a buffer
 * change, so the prompt (and everything typed so far) then stays invisible until
 * the next keystroke.
 */
public interface PromptOutput {
    /**
     * Whether the backend currently displays a prompt (a read is in progress).
     * Informational only - for deciding <em>policy</em> (e.g. whether a spinner
     * may animate), never for choosing where to write.
     */
    boolean active();

    /** Current terminal width; rich backends can override to reflect live resize events. */
    default int columns() {
        try {
            return Math.max(1, Integer.parseInt(System.getenv("COLUMNS")));
        } catch (final NumberFormatException ignored) {
            return 80;
        }
    }

    /**
     * Prints text on {@code stdout}: above the live prompt and with the prompt
     * redrawn underneath when a read is in progress, raw otherwise.
     */
    void print(String text);

    /**
     * Same as {@link #print(String)} for {@code stderr}. The default routes to
     * {@link #print(String)} for backends with a single stream; a backend able
     * to tell the two apart overrides it so the diagnostic stream stays on its
     * own file descriptor even when a prompt is live.
     */
    default void printErr(final String text) {
        print(text);
    }

    /**
     * Sink for backends without any in-place display (plain {@code stdin},
     * piped or scripted output): everything goes to the raw streams, exactly
     * like the pre-sink behavior.
     */
    static PromptOutput raw() {
        return RawPromptOutput.INSTANCE;
    }
}
