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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Faithful {@code sed -n 'a,bp' <file>} reimplementation for the dominant LLM usage: print the inclusive line
 * range a..b of a single file. Only that form is supported; any other {@code sed} expression falls back to the
 * real shell (supports returns false).
 */
@ApplicationScoped
public class JavaSed implements JavaCommand {
    @Override
    public String name() {
        return "sed";
    }

    @Override
    public boolean supports(final Path cwd, final List<String> args) {
        if (args == null || args.size() < 3 || !"-n".equals(args.get(0))) {
            return false;
        }
        final var range = args.get(1);
        if (!BashRegex.SED_RANGE.matcher(range).matches()) {
            return false;
        }
        return !args.get(2).startsWith("-");
    }

    @Override
    public boolean run(final Path cwd, final List<String> args, final StringBuilder out) {
        final var range = args.get(1);
        final var comma = range.indexOf(',');
        final var a = Long.parseLong(range.substring(0, comma));
        final var b = Long.parseLong(range.substring(comma + 1, range.length() - 1)); // drop trailing 'p'
        final var file = Paths.resolve(cwd, args.get(2));
        if (!Files.isRegularFile(file)) {
            return true;
        }
        long idx = 1;
        try (final var reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (idx > b) {
                    break;
                }
                if (idx >= a) {
                    out.append(line).append('\n');
                }
                idx++;
            }
        } catch (final Exception e) {
            // skip unreadable file
        }
        return true;
    }
}
