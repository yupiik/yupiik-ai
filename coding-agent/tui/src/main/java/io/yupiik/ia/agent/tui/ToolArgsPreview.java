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
package io.yupiik.ia.agent.tui;

import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import io.yupiik.ia.agent.core.cli.ToolPresenter;
import io.yupiik.ia.agent.core.shell.DiffViewMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Best-effort request details and explicitly labeled replacement previews. */
public class ToolArgsPreview {
    private final ToolOutputFormat toolOutputFormat;
    private final SyntaxHighlighter syntaxHighlighter;
    private final DiffRenderer diffRenderer;
    private final ToolPresenter presenter;

    public ToolArgsPreview(
            final ToolOutputFormat toolOutputFormat,
            final SyntaxHighlighter syntaxHighlighter,
            final DiffRenderer diffRenderer,
            final ToolPresenter presenter) {
        this.toolOutputFormat = toolOutputFormat;
        this.syntaxHighlighter = syntaxHighlighter;
        this.diffRenderer = diffRenderer;
        this.presenter = presenter;
    }

    /** Returns null only when there are no arguments to display. Rendering failures retain the raw request. */
    public List<Line> preview(
            final String name, final String argsJson, final int width, final Palette palette, final DiffViewMode mode) {
        if (argsJson == null || argsJson.isBlank()) {
            return null;
        }
        try {
            final var args = toolOutputFormat.parseArguments(argsJson);
            final var filePreview = args == null
                    ? null
                    : switch (name == null ? "" : name) {
                        case "write_file" -> writeFile(args, width, palette);
                        case "edit_file" -> editFile(args, width, palette, mode);
                        case "apply_patch" -> applyPatch(args, width, palette, mode);
                        default -> null;
                    };
            if (filePreview != null) {
                return filePreview;
            }
            final var details = presenter.request(name, argsJson, Integer.MAX_VALUE);
            final var text = details.isEmpty() ? argsJson : String.join("\n", details);
            final var stripped = text.stripLeading();
            if (stripped.startsWith("{") || stripped.startsWith("[")) {
                return MarkdownLines.wrapCode(
                        syntaxHighlighter.highlightLines("json", toolOutputFormat.prettyJson(text)),
                        Math.max(1, width));
            }
            return plain(text, width, palette);
        } catch (final RuntimeException e) {
            return plain(argsJson, width, palette);
        }
    }

    private List<Line> writeFile(final Map<String, Object> args, final int width, final Palette palette) {
        if (!(args.get("content") instanceof final String content)
                || !(args.get("path") instanceof final String path)) {
            return null;
        }
        final var lines = heading(path, palette);
        lines.add(Line.from(Span.styled("content: " + description(content), palette.muted())));
        lines.add(Line.empty());
        final var lang = SyntaxHighlighter.guessLanguage(path);
        lines.addAll(MarkdownLines.wrapCode(
                syntaxHighlighter.highlightLines(lang == null ? "" : lang, content), Math.max(1, width)));
        return lines;
    }

    /** Only genuine patch/replacement previews, never ordinary arguments or write-file content. */
    public List<Line> diffPreview(
            final String name, final String argsJson, final int width, final Palette palette, final DiffViewMode mode) {
        if (argsJson == null || argsJson.isBlank()) {
            return null;
        }
        try {
            final var args = toolOutputFormat.parseArguments(argsJson);
            if (args == null) {
                return null;
            }
            return switch (name == null ? "" : name) {
                case "edit_file" -> editFile(args, width, palette, mode);
                case "apply_patch" -> applyPatch(args, width, palette, mode);
                default -> null;
            };
        } catch (final RuntimeException invalid) {
            return null;
        }
    }

    private List<Line> editFile(
            final Map<String, Object> args, final int width, final Palette palette, final DiffViewMode mode) {
        if (!(args.get("path") instanceof final String path)
                || !(args.get("oldString") instanceof final String oldString)
                || oldString.isEmpty()
                || !(args.get("newString") instanceof final String newString)
                || (args.containsKey("replaceAll") && !(args.get("replaceAll") instanceof Boolean))) {
            return null;
        }
        final var lines = heading(path, palette);
        lines.add(Line.from(Span.styled(
                "scope: " + (Boolean.TRUE.equals(args.get("replaceAll")) ? "all occurrences" : "single occurrence"),
                palette.muted())));
        lines.add(Line.from(Span.styled("old: " + description(oldString), palette.muted())));
        lines.add(Line.from(Span.styled("new: " + description(newString), palette.muted())));
        final var patch = new StringBuilder("--- a/")
                .append(path)
                .append('\n')
                .append("+++ b/")
                .append(path)
                .append('\n');
        appendSide(patch, '-', oldString);
        appendSide(patch, '+', newString);
        lines.addAll(fullDiff(patch.toString(), width, palette, mode, SyntaxHighlighter.guessLanguage(path)));
        return lines;
    }

    private List<Line> applyPatch(
            final Map<String, Object> args, final int width, final Palette palette, final DiffViewMode mode) {
        if (!(args.get("patch") instanceof final String patch) || patch.isBlank()) {
            return null;
        }
        final var lines = new ArrayList<Line>();
        lines.add(Line.from(Span.styled("Requested changes", palette.bold(palette.primary()))));
        lines.addAll(fullDiff(patch, width, palette, mode, null));
        return lines;
    }

    private List<Line> fullDiff(
            final String patch,
            final int width,
            final Palette palette,
            final DiffViewMode mode,
            final String language) {
        return diffRenderer.render(patch, Math.max(1, width), palette, mode, syntaxHighlighter, language);
    }

    private ArrayList<Line> heading(final String path, final Palette palette) {
        final var lines = new ArrayList<Line>();
        lines.add(Line.from(Span.styled("Requested changes", palette.bold(palette.primary()))));
        lines.add(
                Line.from(Span.styled("path: ", palette.muted()), Span.styled(path, palette.bold(palette.primary()))));
        return lines;
    }

    private static void appendSide(final StringBuilder patch, final char tag, final String text) {
        if (text.isEmpty()) {
            return;
        }
        final var parts = text.split("\n", -1);
        final var count = parts.length - (text.endsWith("\n") ? 1 : 0);
        for (var i = 0; i < count; i++) {
            patch.append(tag).append(parts[i]).append('\n');
        }
        if (!text.endsWith("\n")) {
            patch.append("\\ No newline at end of string\n");
        }
    }

    private static String description(final String text) {
        if (text.isEmpty()) {
            return "empty";
        }
        return text.length() + " characters; "
                + (text.contains("\r\n") ? "CRLF; " : "")
                + (text.endsWith("\n") ? "ends with newline" : "no final newline");
    }

    private List<Line> plain(final String text, final int width, final Palette palette) {
        final var lines = new ArrayList<Line>();
        for (final var line : text.split("\n", -1)) {
            lines.add(Line.from(Span.styled(line, palette.style(palette.text()))));
        }
        return MarkdownLines.wrapCode(lines, Math.max(1, width));
    }
}
