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
import io.yupiik.ia.agent.core.tools.LenientPattern;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Faithful {@code grep} reimplementation over explicit file/directory operands (globs are expanded by the
 * orchestrator before invocation). Supports {@code -h -n -i -l -c} and {@code -r} (recursive walk of a directory
 * operand). Output matches real grep: {@code line} (single file or {@code -h}), {@code path:line},
 * {@code path:num:line} with {@code -n}, filenames only with {@code -l}, per-file counts with {@code -c}.
 * Any other flag falls back to the real shell.
 */
@ApplicationScoped
public class JavaGrep implements JavaCommand {
    private static final String SUPPORTED_FLAGS = "hnilcr";

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public boolean supports(final Path cwd, final List<String> args) {
        if (args == null || args.size() < 2) {
            return false;
        }
        for (final var a : args) {
            if (a.startsWith("-") && a.length() > 1) {
                if (a.equals("--")) {
                    continue;
                }
                final var flags = a.substring(1);
                if (flags.chars().anyMatch(c -> SUPPORTED_FLAGS.indexOf(c) < 0)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public boolean run(final Path cwd, final List<String> args, final StringBuilder out) {
        final var flags = new StringBuilder();
        var pattern = (String) null;
        final List<String> operands = new ArrayList<>();
        var patternExpected = true;
        for (final var a : args) {
            if (a.equals("--") || patternExpected && a.startsWith("-") && a.length() > 1) {
                if (patternExpected && a.startsWith("-") && !a.equals("--")) {
                    flags.append(a.substring(1));
                    continue;
                }
                if (a.equals("--")) {
                    patternExpected = false;
                }
                continue;
            }
            if (patternExpected) {
                pattern = a;
                patternExpected = false;
            } else {
                operands.add(a);
            }
        }
        if (pattern == null) {
            return true;
        }
        final boolean caseInsensitive = flags.chars().anyMatch(c -> c == 'i');
        final boolean lineNumber = flags.chars().anyMatch(c -> c == 'n');
        final boolean noFilename = flags.chars().anyMatch(c -> c == 'h');
        final boolean onlyFilename = flags.chars().anyMatch(c -> c == 'l');
        final boolean count = flags.chars().anyMatch(c -> c == 'c');
        final boolean recursive = flags.chars().anyMatch(c -> c == 'r');
        final var literalRepair = LenientPattern.compile(pattern, caseInsensitive ? Pattern.CASE_INSENSITIVE : 0);
        final var regex = literalRepair.pattern();
        if (regex == null) {
            return false; // invalid/broken regex: not something we can faithfully honor -> let the real shell try
        }
        final List<Path> files = new ArrayList<>();
        for (final var op : operands) {
            final var p = Paths.resolve(cwd, op);
            if (Files.isRegularFile(p)) {
                files.add(p);
            } else if (recursive && Files.isDirectory(p)) {
                try (final var paths = Files.walk(p)) {
                    files.addAll(paths.filter(Files::isRegularFile).toList());
                } catch (final Exception e) {
                    // ignore unreadable subtree
                }
            }
        }
        final var showName = !noFilename && (files.size() > 1 || recursive);
        long totalMatches = 0;
        for (final var file : files) {
            final var lineMatches = new ArrayList<Object[]>();
            try (final var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                int n = 0;
                while ((line = reader.readLine()) != null) {
                    n++;
                    if (regex.matcher(line).find()) {
                        lineMatches.add(new Object[] {n, line});
                    }
                }
            } catch (final Exception e) {
                continue;
            }
            if (onlyFilename) {
                if (!lineMatches.isEmpty()) {
                    out.append(file).append('\n');
                }
            } else if (count) {
                out.append(showName ? file + ":" + lineMatches.size() : "" + lineMatches.size())
                        .append('\n');
            } else {
                for (final var m : lineMatches) {
                    final var num = (Integer) m[0];
                    final var text = (String) m[1];
                    final var prefix = new StringBuilder();
                    if (showName) {
                        prefix.append(file).append(':');
                    }
                    if (lineNumber) {
                        prefix.append(num).append(':');
                    }
                    out.append(prefix).append(text).append('\n');
                }
            }
            totalMatches += lineMatches.size();
            if (totalMatches > 4000) {
                break; // hard safety bound (cap/truncation is the only allowed deviation)
            }
        }
        return true;
    }
}
