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

import io.yupiik.ia.agent.core.wiring.HackStdIo;
import java.io.PrintStream;

/**
 * {@link PromptOutput#raw()} implementation: an unconditional pass-through to
 * the current {@code System.out}/{@code System.err}.
 * <p>
 * The streams are resolved on every call (not captured) so a test redirecting
 * them - or a container swapping them - keeps working. When the runtime
 * hijacked the streams, writing through the wrapper would feed it back into
 * itself, so the pristine stream the wrapper replaced is used instead; without
 * a hijacked stream the live {@code System.out} is the target. Both cases keep
 * the write on the real terminal and out of the routing loop.
 */
final class RawPromptOutput implements PromptOutput {
    static final RawPromptOutput INSTANCE = new RawPromptOutput();

    private RawPromptOutput() {
        // singleton
    }

    @Override
    public boolean active() {
        return false;
    }

    @Override
    public void print(final String text) {
        final var out = resolve(System.out);
        out.print(text);
        out.flush();
    }

    @Override
    public void printErr(final String text) {
        final var err = resolve(System.err);
        err.print(text);
        err.flush();
    }

    private static PrintStream resolve(final PrintStream current) {
        return current instanceof HackStdIo.HijackedPrintStream hijacked ? hijacked.pristine() : current;
    }
}
