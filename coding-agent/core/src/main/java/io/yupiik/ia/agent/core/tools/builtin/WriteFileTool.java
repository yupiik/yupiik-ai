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

import static io.yupiik.ia.agent.core.tools.Schemas.str;
import static io.yupiik.ia.agent.core.tools.ToolArgs.arg;

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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

@ApplicationScoped
public class WriteFileTool implements EmbeddedTool {
    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description(final Path workdir) {
        return "Write content to a file, creating it (and parent directories) if needed. Overwrites the whole file. "
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
                        "content", str("Full file content to write.")),
                "path",
                "content");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        final var raw = arg(args, "path");
        if (raw == null || raw.isEmpty()) {
            return ToolResult.error("path is required");
        }
        final var path = context.resolvePath(raw);
        SandboxFiles.requireWrite(context, path);
        final var content = arg(args, "content");
        if (content == null) {
            return ToolResult.error("content is required");
        }
        final var guard = escapeGuard(context, raw, path);
        if (guard != null) {
            return ToolResult.error(guard);
        }
        try {
            SandboxFiles.createParents(context, path);
            var created = false;
            try {
                Files.writeString(path, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                created = true;
            } catch (final FileAlreadyExistsException existing) {
                // Overwrite permission is not permission for an additional old-content read.
                Files.writeString(path, content);
            }
            final var capture = new FileMutationCapture();
            capture.completed(
                    path,
                    created ? FileMutation.Kind.CREATE : FileMutation.Kind.UPDATE,
                    null,
                    created ? capture.text(content) : null);
            final var lines = content.isEmpty() ? 0 : content.split("\n", -1).length;
            final var notice = reRoutedNotice(context, raw, path);
            return new ToolResult(
                    ToolResult.Status.OK, notice + "Wrote " + lines + " lines to " + path, capture.mutations());
        } catch (final IOException | UncheckedIOException e) {
            return ToolResult.error("Cannot write " + path + ": " + e.getMessage());
        }
    }

    /**
     * A relative path that normalizes outside the session root is refused instead of silently writing
     * outside it: an absolute path survives any containment check because it is an explicit caller choice.
     */
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
     * Surfaces that a relative write was resolved against a session project root that differs from the
     * configured default, so a cross-root operation (the write landed in the session tree, not the
     * process launch directory) is visible instead of silent. Unset or default roots produce no notice.
     */
    private String reRoutedNotice(final ToolContext context, final String raw, final Path path) {
        return context.reRoutedToSessionRoot(raw)
                ? "target (session root " + context.projectDir() + "): " + path + "\n"
                : "";
    }
}
