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
package io.yupiik.ia.agent.core.tools.builtin.bashoverride;

import java.nio.file.Path;
import java.util.List;

/**
 * A faithful, option-subset in-process reimplementation of a read-only bash command. Output must be
 * byte-identical to what the real command would print for the supported option/argument subset; the only
 * permitted deviation is cap/truncation (handled by the caller). Query methods ({@link #supports}) decide
 * whether this implementation can take a given invoked command head; when it cannot (unknown flag, unsafe
 * argument...), the caller must fall back to the real shell rather than approximate.
 */
public interface JavaCommand {

    /**
     * @return the shell command name this implementation reimplements (eg. {@code cat}, {@code ls}).
     */
    String name();

    /**
     * Whether this implementation can faithfully run the given invocation (the raw argument tokens after the
     * command head, already unquoted). Returns false for unsupported options or unsafe/unsure arguments so the
     * caller triggers the real-bash fallback.
     */
    boolean supports(Path cwd, List<String> args);

    /**
     * Runs the command and appends its byte-faithful output (stdout) to {@code out}. Must not throw on
     * ordinary "file does not exist" conditions - it writes nothing (matching the usual {@code 2>/dev/null}
     * survey semantics) and returns without error. Returns true when the invocation should count as
     * executed; false is not expected here but implemented defensively.
     */
    boolean run(Path cwd, List<String> args, StringBuilder out);
}
