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
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class GlobTool implements EmbeddedTool {

    private static final List<String> SKIP_PARTS = List.of("/target/", "/.git/", "/node_modules/", "/build/", "/.yca/");

    private final CodingAgentConfiguration configuration;

    public GlobTool(final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
    }

    protected GlobTool() {
        this.configuration = null;
    }

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description(final Path workdir) {
        return "Search file paths using a glob pattern (examples: `**/*.java`, `src/**/*.xml`), skipping build output "
                + "and ignored directories. Set hidden=true to include hidden files/directories.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(
                Map.of(
                        "pattern", Schemas.str("Glob pattern, eg. `**/*.java`."),
                        "path", Schemas.str("Base directory, default `.`."),
                        "hidden", Schemas.bool("Include hidden files and directories, default false."),
                        "limit", Schemas.intProp("Maximum number of matching files to return, default 100.")),
                "pattern");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        final var pattern = arg(args, "pattern");
        final var base = context.resolvePath(arg(args, "path") == null ? "." : arg(args, "path"));
        if (pattern == null || pattern.isBlank()) {
            return ToolResult.error("pattern is required");
        }
        final var matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        // glob semantics: '**/' requires a directory component, but agents expect `**/*.txt` to also
        // match files directly under the base - tolerate the zero-directory case
        final var flatMatcher = pattern.startsWith("**/")
                ? FileSystems.getDefault().getPathMatcher("glob:" + pattern.substring(3))
                : null;
        SandboxFiles.requireRead(context, base);
        // Ancestor discovery and .gitignore reads are not sandbox-aware.
        final var ignore = context.sandbox().enabled() ? null : FileIgnore.of(FileIgnore.gitRoot(base), null);
        final var hidden = optBool(args, "hidden", false);
        final var limit = (int) Math.min(1000, Math.max(1, optInt(args, "limit", 100)));
        final var matches = new ArrayList<String>();
        final var truncated = new boolean[] {false};
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                    if (!SandboxFiles.readable(context, dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!dir.equals(base) && skip(dir, ignore, hidden)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                    if (!SandboxFiles.readable(context, file) || skip(file, ignore, hidden)) {
                        return FileVisitResult.CONTINUE;
                    }
                    final var rel = base.relativize(file);
                    final var relPath = rel.toString().isEmpty() ? file.getFileName() : rel;
                    if (matcher.matches(relPath) || (flatMatcher != null && flatMatcher.matches(relPath))) {
                        if (matches.size() >= limit) {
                            truncated[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        matches.add(file.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(final Path file, final IOException exc) {
                    // ignore not allowed folders/files so the tool doesn't fail
                    return FileVisitResult.SKIP_SUBTREE;
                }
            });
        } catch (final IOException e) {
            return ToolResult.error("Cannot walk " + base + ": " + e.getMessage());
        }
        final var sb = new StringBuilder(matches.size() + " match(es)\n");
        for (final var m : matches) {
            sb.append(m).append('\n');
        }
        if (truncated[0]) {
            sb.append("... results capped at ").append(limit).append('\n');
        }
        return ToolResult.ok(sb.toString());
    }

    private static boolean skip(final Path p, final FileIgnore ignore, final boolean hidden) {
        final var n = p.toString();
        final var fileName = p.getFileName() == null ? "" : p.getFileName().toString();
        if (!hidden && fileName.startsWith(".")) {
            return true;
        }
        for (final var part : SKIP_PARTS) {
            if (n.contains(part)) {
                return true;
            }
        }
        return ignore != null && ignore.isIgnored(p.toAbsolutePath().normalize());
    }
}
