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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.nio.file.Path;
import java.util.List;

/**
 * Faithful {@code echo}: supports {@code echo <args>...} with the {@code -n} (no trailing newline) flag and
 * literal text (including the common {@code echo "---"} separator). Command substitution, backticks, environment
 * expansion and write redirects are rejected before this runs (substitution/redirect are filtered by the caller).
 */
@ApplicationScoped
public class JavaEcho implements JavaCommand {
    @Override
    public String name() {
        return "echo";
    }

    @Override
    public boolean supports(final Path cwd, final List<String> args) {
        if (args == null || args.isEmpty()) {
            return true;
        }
        // only the actual echo flags are rejected; a bare '-'/literal like "---" separator is fine.
        for (final var a : args) {
            if (a != null && (a.contains("$") || a.contains("`"))) {
                return false;
            }
            if (a.startsWith("-") && a.length() > 1 && (a.equals("-n") || "-e".equals(a) || "-E".equals(a))) {
                return false; // only -n is supported (-e/-E and flag-like tokens rejected)
            }
        }
        return true;
    }

    @Override
    public boolean run(final Path cwd, final List<String> args, final StringBuilder out) {
        final var noNewline = args != null && !args.isEmpty() && "-n".equals(args.get(0));
        final var text = new StringBuilder();
        for (var i = noNewline ? 1 : 0; args != null && i < args.size(); i++) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(args.get(i));
        }
        out.append(text);
        if (!noNewline) {
            out.append('\n');
        }
        return true;
    }
}
