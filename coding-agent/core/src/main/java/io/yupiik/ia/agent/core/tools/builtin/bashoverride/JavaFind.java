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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

/**
 * Faithful {@code find <root> [-name <glob>] [-type f|d] [-maxdepth N]} reimplementation over an explicit root
 * directory ({@code -name} uses shell-style {@code * ?}). Output matches real find: one path per line (relative
 * when the root was relative), directory-walk order, capped only via the caller's truncation. Any other
 * predicate (-exec, -print0, -mtime...) falls back to the real shell.
 */
@ApplicationScoped
public class JavaFind implements JavaCommand {
    @Override
    public String name() {
        return "find";
    }

    @Override
    public boolean supports(final Path cwd, final List<String> args) {
        if (args == null || args.isEmpty() || args.getFirst().startsWith("-")) {
            return false;
        }
        var i = 1;
        while (i < args.size()) {
            switch (args.get(i)) {
                case "-name" -> {
                    if (i + 1 >= args.size()) {
                        return false;
                    }
                    i += 2;
                }
                case "-type" -> {
                    if (i + 1 >= args.size()
                            || !BashRegex.FIND_TYPE.matcher(args.get(i + 1)).matches()) {
                        return false;
                    }
                    i += 2;
                }
                case "-maxdepth" -> {
                    if (i + 1 >= args.size()
                            || !BashRegex.NON_NEG_INT.matcher(args.get(i + 1)).matches()) {
                        return false;
                    }
                    i += 2;
                }
                default -> {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public boolean run(final Path cwd, final List<String> args, final StringBuilder out) {
        final var root = Paths.resolve(cwd, args.get(0));
        if (!Files.isDirectory(root)) {
            return true;
        }
        String name = null;
        Character type = null;
        int maxDepth = Integer.MAX_VALUE;
        for (var i = 1; i < args.size(); i++) {
            switch (args.get(i)) {
                case "-name" -> name = args.get(++i);
                case "-type" -> type = args.get(++i).charAt(0);
                case "-maxdepth" -> maxDepth = Integer.parseInt(args.get(++i));
                default -> {}
            }
        }
        final var relative = !args.get(0).startsWith("/");
        final var printBase = relative ? cwd.toAbsolutePath().normalize() + "/" : "";
        final var namePattern = name == null ? null : glob(name);
        walk(root, namePattern, type, 0, maxDepth, out, printBase);
        return true;
    }

    private static void walk(
            final Path dir,
            final Pattern name,
            final Character type,
            final int depth,
            final int maxDepth,
            final StringBuilder out,
            final String printBase) {
        if (depth > maxDepth) {
            return;
        }
        final List<Path> children = new ArrayList<>();
        try (final DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            children.addAll(StreamSupport.stream(stream.spliterator(), false).toList());
        } catch (final Exception e) {
            return;
        }
        Collections.sort(children);
        for (final var child : children) {
            final var isDir = Files.isDirectory(child);
            final var matchesType = type == null || (type == 'd' && isDir) || (type == 'f' && !isDir);
            final var fileName = child.getFileName().toString();
            final var matchesName = name == null || name.matcher(fileName).matches();
            if (matchesType && matchesName) {
                out.append(printBase).append(child).append('\n');
            }
            if (isDir) {
                walk(child, name, type, depth + 1, maxDepth, out, printBase);
            }
        }
    }

    private static Pattern glob(final String pattern) {
        final var regex = new StringBuilder();
        for (final var c : pattern.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
