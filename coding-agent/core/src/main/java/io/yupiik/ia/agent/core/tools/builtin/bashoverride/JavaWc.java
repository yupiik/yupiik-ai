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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Faithful {@code wc -l | -c | -w <file>...} reimplementation. */
@ApplicationScoped
public class JavaWc implements JavaCommand {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    @Override
    public String name() {
        return "wc";
    }

    @Override
    public boolean supports(final Path cwd, final List<String> args) {
        if (args == null || args.isEmpty()) {
            return false;
        }
        var sawFlag = false;
        for (final var a : args) {
            if (a.startsWith("-")) {
                if (!BashRegex.WC_FLAGS.matcher(a).matches()) {
                    return false;
                }
                sawFlag = true;
            }
        }
        return sawFlag; // require at least one of -l -c -w
    }

    @Override
    public boolean run(final Path cwd, final List<String> args, final StringBuilder out) {
        final var countLines = args.stream()
                .anyMatch(a -> BashRegex.WC_FLAGS_CONTAIN_L.matcher(a).matches());
        final var countBytes = args.stream()
                .anyMatch(a -> BashRegex.WC_FLAGS_CONTAIN_C.matcher(a).matches());
        final var countWords = args.stream()
                .anyMatch(a -> BashRegex.WC_FLAGS_CONTAIN_W.matcher(a).matches());
        final var files = args.stream().filter(a -> !a.startsWith("-")).toList();
        var tl = 0L;
        var tb = 0L;
        var tw = 0L;
        for (final var f : files) {
            final var p = Paths.resolve(cwd, f);
            if (!Files.isRegularFile(p)) {
                continue;
            }
            final List<String> lines;
            try {
                lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            } catch (final Exception e) {
                continue;
            }
            final long bytes;
            try {
                bytes = Files.size(p);
            } catch (final Exception e) {
                continue;
            }
            final long words = lines.stream()
                    .mapToLong(l -> l.trim().isEmpty() ? 0 : WHITESPACE.split(l.trim()).length)
                    .sum();
            tl += lines.size();
            tb += bytes;
            tw += words;
            final var row = new StringBuilder();
            if (countLines) {
                row.append(String.format("%8d ", lines.size()));
            }
            if (countWords) {
                row.append(String.format("%8d ", words));
            }
            if (countBytes) {
                row.append(String.format("%8d ", bytes));
            }
            row.append(f).append('\n');
            out.append(row);
        }
        if (files.size() > 1) {
            final var row = new StringBuilder();
            if (countLines) {
                row.append(String.format("%8d ", tl));
            }
            if (countWords) {
                row.append(String.format("%8d ", tw));
            }
            if (countBytes) {
                row.append(String.format("%8d ", tb));
            }
            out.append(row).append("total\n");
        }
        return true;
    }
}
