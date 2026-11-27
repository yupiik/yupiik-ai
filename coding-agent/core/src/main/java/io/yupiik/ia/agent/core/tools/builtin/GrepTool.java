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
package io.yupiik.ia.agent.core.tools.builtin;

import static io.yupiik.ia.agent.core.tools.ToolArgs.arg;
import static io.yupiik.ia.agent.core.tools.ToolArgs.optBool;
import static io.yupiik.ia.agent.core.tools.ToolArgs.optInt;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.FileIgnore;
import io.yupiik.ia.agent.core.tools.LenientPattern;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@ApplicationScoped
public class GrepTool implements EmbeddedTool {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final List<String> SKIP_PARTS = List.of("/target/", "/.git/", "/node_modules/", "/build/", "/.yca/");

    private final CodingAgentConfiguration configuration;

    public GrepTool(final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
    }

    protected GrepTool() {
        this.configuration = null;
    }

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description(final Path workdir) {
        return "Search file contents using a regular expression or exact text matching; locate specific code, "
                + "symbols, or text patterns, and narrow searches with `include`. Returns matching file paths, line "
                + "numbers, and line previews, skipping build output, VCS directories and ignored paths.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(
                Map.of(
                        "pattern",
                                Schemas.str(
                                        "Java regular expression to search; escape literal parentheses as `\\(` and `\\)`. "
                                                + "Use literal=true for a plain phrase, especially one containing parentheses."),
                        "path",
                                Schemas.str("Base directory, file, or several space-separated locations, default `.`. "
                                        + "When the whole string exists on disk (even a path containing spaces) "
                                        + "it is searched verbatim; otherwise it is split on spaces and each "
                                        + "token is searched. A JSON array of paths is also accepted, each "
                                        + "element searched verbatim."),
                        "include", Schemas.str("Glob filter on file names, eg. `*.java`."),
                        "literal",
                                Schemas.bool(
                                        "Treat pattern as exact text instead of a regular expression, default false."),
                        "ignoreCase", Schemas.bool("Case insensitive search."),
                        "limit", Schemas.intProp("Maximum number of matching lines to return, default 100."),
                        "maxResults", Schemas.intProp("Maximum number of matching lines to return, default 100.")),
                "pattern");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        final var pattern = arg(args, "pattern");
        if (pattern == null || pattern.isBlank()) {
            return ToolResult.error("pattern is required");
        }
        final var literal = optBool(args, "literal", false);
        final var flags = optBool(args, "ignoreCase", false) ? Pattern.CASE_INSENSITIVE : 0;
        final var lenient = LenientPattern.compile(literal ? Pattern.quote(pattern) : pattern, flags);
        final var regex = lenient.pattern();
        if (regex == null) {
            // even the tolerant repair could not salvage it -> surface a helpful message instead of the
            // raw engine exception, and steer the model toward literal matching for a plain phrase
            return ToolResult.error("Invalid regular expression: " + lenient.originalError()
                    + ". Pattern is a Java regex; escape literal parentheses as `\\(`/`\\)`. "
                    + "For a plain phrase (e.g. one containing parentheses) set `literal: true`.");
        }
        final var adjustedNote = lenient.adjusted()
                ? "NOTE: pattern was adjusted (escaped unbalanced parentheses) to be a valid Java regex; "
                        + "prefer writing balanced escapes yourself, or set `literal: true` for a plain phrase.\n"
                : "";
        final var roots = resolveRoots(args.get("path"), context);
        final var include = arg(args, "include");
        final var includeMatcher =
                include == null ? null : FileSystems.getDefault().getPathMatcher("glob:" + include);
        final var limitArg = optInt(args, "limit", -1);
        final var max = Math.clamp(limitArg > 0 ? limitArg : optInt(args, "maxResults", 100), 1, 1000);
        final var matches = new ArrayList<String>();
        final var filesScanned = new int[] {0};
        final var capped = new boolean[] {false};
        final var errors = new ArrayList<String>();
        for (final var base : roots) {
            SandboxFiles.requireRead(context, base);
            // Ancestor discovery and .gitignore reads are not sandbox-aware.
            final var ignore = context.sandbox().enabled() ? null : FileIgnore.of(FileIgnore.gitRoot(base), null);
            try {
                if (Files.isRegularFile(base)) {
                    searchFile(base, regex, includeMatcher, matches, max, filesScanned);
                } else if (!Files.exists(base)) {
                    errors.add("Cannot search " + base + ": no such file or directory");
                } else {
                    Files.walkFileTree(base, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                            if (!SandboxFiles.readable(context, dir)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            if (!dir.equals(base) && skip(dir, ignore)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                            if (matches.size() >= max) {
                                capped[0] = true;
                                return FileVisitResult.TERMINATE;
                            }
                            if (SandboxFiles.readable(context, file)
                                    && !skip(file, ignore)
                                    && (includeMatcher == null || includeMatcher.matches(file.getFileName()))) {
                                searchFile(file, regex, includeMatcher, matches, max, filesScanned);
                            }
                            return matches.size() >= max ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(final Path file, final IOException exc) {
                            // ignore not allowed folders/files so the tool doesn't fail
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    });
                }
            } catch (final IOException e) {
                errors.add("Cannot search " + base + ": " + e.getMessage());
            }
            if (matches.size() >= max) {
                capped[0] = true;
                break;
            }
        }
        if (matches.isEmpty()) {
            if (!errors.isEmpty()) {
                return ToolResult.error(adjustedNote + String.join("\n", errors));
            }
            return ToolResult.ok(adjustedNote + "No match (" + filesScanned[0] + " files scanned)");
        }
        final var sb = new StringBuilder(adjustedNote).append(matches.size()).append(" match(es)\n");
        for (final var m : matches) {
            sb.append(m).append('\n');
        }
        for (final var e : errors) {
            sb.append("-- ").append(e).append('\n');
        }
        if (capped[0] || matches.size() >= max) {
            sb.append("... results capped at ").append(max).append('\n');
        }
        return ToolResult.ok(sb.toString());
    }

    /**
     * Resolves the {@code path} argument into one or more search roots. A whole string that exists
     * on disk (a directory or a file, possibly containing spaces) is kept verbatim; any other string
     * is split on whitespace and each token searched, so several space-separated locations work while
     * an existing path containing a space is never mangled. A JSON array is accepted defensively and
     * each of its elements is one explicit path (never split). Blank/absent falls back to {@code "."}.
     */
    private List<Path> resolveRoots(final Object raw, final ToolContext context) {
        final var fallback = context.projectDir();
        final var tokens = new ArrayList<String>();
        if (raw instanceof List<?> list) {
            // explicit boundaries: each element is a single path, spaces preserved
            for (final var item : list) {
                final var p = item == null ? "." : item.toString().strip();
                if (!p.isBlank() && !p.equals(".")) {
                    tokens.add(p);
                }
            }
        } else {
            final var rawPath = raw == null ? "." : raw.toString().strip();
            if (!rawPath.isEmpty() && !rawPath.equals(".")) {
                final var whole = context.resolvePath(rawPath);
                if (SandboxFiles.readable(context, whole) && Files.exists(whole)) {
                    // an existing path (file or directory) may legitimately contain spaces: verbatim
                    tokens.add(rawPath);
                } else {
                    tokens.addAll(List.of(WHITESPACE.split(rawPath)));
                }
            }
        }
        if (tokens.isEmpty()) {
            return List.of(context.resolvePath("."));
        }
        final var roots = new ArrayList<Path>();
        for (final var token : tokens) {
            final var root = context.resolvePath(token);
            if (!roots.contains(root)) {
                roots.add(root);
            }
        }
        return roots;
    }

    private static boolean skip(final Path p, final FileIgnore ignore) {
        final var n = p.toString();
        for (final var part : SKIP_PARTS) {
            if (n.contains(part)) {
                return true;
            }
        }
        return ignore != null && ignore.isIgnored(p.toAbsolutePath().normalize());
    }

    private static void searchFile(
            final Path file,
            final Pattern regex,
            final PathMatcher includeMatcher,
            final List<String> matches,
            final int max,
            final int[] filesScanned) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 2_000_000) {
                return;
            }
            filesScanned[0]++;
            final var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (var i = 0; i < lines.size() && matches.size() < max; i++) {
                if (regex.matcher(lines.get(i)).find()) {
                    final var text = lines.get(i).strip();
                    matches.add(file + ":" + (i + 1) + ": "
                            + (text.length() > 300 ? text.substring(0, 300) + "..." : text));
                }
            }
        } catch (final IOException | RuntimeException e) {
            // skip unreadable/binary
        }
    }
}
