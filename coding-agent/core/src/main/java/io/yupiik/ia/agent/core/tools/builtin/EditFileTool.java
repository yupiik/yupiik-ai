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

import static io.yupiik.ia.agent.core.tools.Schemas.bool;
import static io.yupiik.ia.agent.core.tools.Schemas.str;
import static io.yupiik.ia.agent.core.tools.ToolArgs.arg;
import static io.yupiik.ia.agent.core.tools.ToolArgs.optBool;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.FileMutation;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContainment;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@ApplicationScoped
public class EditFileTool implements EmbeddedTool {

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String description(final Path workdir) {
        return "Replace an exact string in a file by another. oldString must match exactly once unless replaceAll is true. "
                + "Requires a non-empty \"path\" argument.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.EDIT;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(
                Map.of(
                        "path", str("File path, absolute or relative to the working directory."),
                        "oldString", str("Exact text to replace."),
                        "newString", str("Replacement text."),
                        "replaceAll", bool("Replace every occurrence instead of requiring an unique match.")),
                "path",
                "oldString",
                "newString");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        final var raw = arg(args, "path");
        if (raw == null || raw.isEmpty()) {
            return ToolResult.error("path is required");
        }
        final var path = context.resolvePath(raw);
        SandboxFiles.requireRead(context, path);
        SandboxFiles.requireWrite(context, path);
        final var oldString = arg(args, "oldString");
        final var newString = arg(args, "newString");
        if (oldString == null || oldString.isEmpty()) {
            return ToolResult.error("oldString is required");
        }
        if (newString == null) {
            return ToolResult.error("newString is required");
        }
        final var escape = escapeGuard(context, raw, path);
        if (escape != null) {
            return ToolResult.error(escape);
        }
        if (!Files.isRegularFile(path)) {
            return ToolResult.error("File not found: " + path);
        }
        try {
            final var content = Files.readString(path);
            var count = 0;
            var idx = 0;
            while ((idx = content.indexOf(oldString, idx)) >= 0) {
                count++;
                idx += oldString.length();
                if (count > 1 && !optBool(args, "replaceAll", false)) {
                    return ToolResult.error("oldString matches " + count + "+ times in " + path
                            + ", provide more context or use replaceAll");
                }
            }
            if (count == 0) {
                return ToolResult.error("oldString not found in " + path
                        + ". It must match the current file content exactly (whitespace sensitive). "
                        + "Re-read the file for current content. File starts with: "
                        + snippet(content) + ".");
            }
            final var updated = content.replace(oldString, newString);
            SandboxFiles.requireWrite(context, path);
            Files.writeString(path, updated);
            final var capture = new FileMutationCapture();
            if (!content.equals(updated)) {
                capture.completed(path, FileMutation.Kind.UPDATE, capture.text(content), capture.text(updated));
            }
            final var notice = reRoutedNotice(context, raw, path);
            return new ToolResult(
                    ToolResult.Status.OK,
                    notice + "Replaced " + (optBool(args, "replaceAll", false) ? count : 1) + " occurrence(s) in "
                            + path,
                    capture.mutations());
        } catch (final IOException | UncheckedIOException e) {
            return ToolResult.error("Cannot edit " + path + ": " + e.getMessage());
        }
    }

    private static String snippet(final String content) {
        final var s = content.stripLeading();
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    /** A relative path escaping the session root is refused; absolute paths are explicit caller choices. */
    private static String escapeGuard(final ToolContext context, final String raw, final Path path) {
        if (Path.of(raw).isAbsolute()) {
            return null;
        }
        final var base = context.projectDir().toAbsolutePath().normalize();
        if (!path.toAbsolutePath().normalize().startsWith(base)) {
            return "path '" + raw + "' escapes the session project directory";
        }
        return ToolContainment.symlinkEscapeError(context.projectDir(), path);
    }

    /**
     * Surfaces that a relative edit was resolved against a session project root that differs from the
     * configured default, so a cross-root operation is visible instead of silent. Default roots: no notice.
     */
    private String reRoutedNotice(final ToolContext context, final String raw, final Path path) {
        return context.reRoutedToSessionRoot(raw)
                ? "target (session root " + context.projectDir() + "): " + path + "\n"
                : "";
    }
}
