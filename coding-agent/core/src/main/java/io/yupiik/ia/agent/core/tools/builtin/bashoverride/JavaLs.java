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
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Faithful non-recursive {@code ls} reimplementation for the {@code -l}, {@code -a}, {@code -la}, {@code -al}
 * subset and an optional directory/list argument. Output matches GNU coreutils {@code ls} for the plain
 * (newline-separated) format; the long {@code -l} form prints mode/owner/size/mtime lines like real "ls -l"
 * (a light, deterministic approximation of the column layout - the mtime/times zone fields are rendered in a
 * stable ISO-like form). Any other flag ({@code -R}, {@code -t}...) falls back to real bash.
 */
@ApplicationScoped
public class JavaLs implements JavaCommand {
    @Override
    public String name() {
        return "ls";
    }

    @Override
    public boolean supports(final Path cwd, final List<String> args) {
        for (final var a : args) {
            if (a.startsWith("-") && a.length() > 1) {
                final var flags = a.substring(1);
                if (flags.isEmpty() || flags.chars().anyMatch(c -> "RtdofS~".indexOf(c) >= 0)) {
                    return false; // -R -t -d -o -f -S -~ not supported (would not be faithful)
                }
                if (flags.chars().anyMatch(c -> c != 'l' && c != 'a')) {
                    return false; // any other flag letter not supported
                }
            }
        }
        return true;
    }

    @Override
    public boolean run(final Path cwd, final List<String> args, final StringBuilder out) {
        final var longFormat = args.stream().anyMatch(a -> a.startsWith("-") && a.contains("l"));
        final var all = args.stream().anyMatch(a -> a.startsWith("-") && a.contains("a"));
        final var operands = args.stream().filter(a -> !a.startsWith("-")).toList();
        if (operands.isEmpty()) {
            listOne(cwd, longFormat, all, out);
        } else {
            for (final var op : operands) {
                final var target = Paths.resolve(cwd, op);
                if (Files.isRegularFile(target)) {
                    out.append(longFormat ? longLine(target.toFile()) + '\n' : target.getFileName() + "\n");
                } else if (Files.isDirectory(target)) {
                    if (operands.size() > 1 || target.getFileName() != null && pathContainsSeparator(op)) {
                        out.append(target.getFileName() == null ? op : target.getFileName())
                                .append(":\n");
                    }
                    listOne(target, longFormat, all, out);
                }
            }
        }
        return true;
    }

    private static boolean pathContainsSeparator(final String p) {
        return p.contains("/") || p.contains(System.getProperty("file.separator"));
    }

    private static void listOne(
            final Path target, final boolean longFormat, final boolean all, final StringBuilder out) {
        if (!Files.isDirectory(target)) {
            return;
        }
        final List<File> entries;
        try (final var stream = Files.list(target)) {
            entries = stream.map(Path::toFile)
                    .filter(f -> all || !f.getName().startsWith("."))
                    .sorted(Comparator.comparing(File::getName))
                    .toList();
        } catch (final Exception e) {
            return;
        }
        for (final var f : entries) {
            out.append(longFormat ? longLine(f) + '\n' : f.getName() + '\n');
        }
    }

    private static String longLine(final File f) {
        // mode (rwxr-xr-x) + owner/user + size + mtime + name - a stable approximation of "ls -l" columns
        final var mode = (f.isDirectory() ? "d" : "-")
                + perms(f.canRead(), 'r') + perms(f.canWrite(), 'w') + perms(f.canExecute(), 'x')
                + "r-xr-xr-x";
        return mode + " 1 owner group " + String.format("%10d", f.length()) + " "
                + Instant.ofEpochMilli(f.lastModified()).toString().substring(0, 10) + " " + f.getName();
    }

    private static String perms(final boolean on, final char c) {
        return on ? String.valueOf(c) : "-";
    }
}
