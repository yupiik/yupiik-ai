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

import io.yupiik.ia.agent.core.cli.ToolPresenter;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Parses a tool region of the transcript (the markdown emitted by
 * {@link TuiTranscriptModel} between its {@code TOOL_BEGIN}/{@code TOOL_END}
 * markers) into structured fields used to render a bordered "tool card":
 * the tool name, its command/arguments (or the error message when it failed),
 * the captured output, the wall-clock duration in milliseconds and the
 * {@code #N} ordinal embedded by {@code ConversationTranscript} (used to key
 * full outputs, {@code -1} when absent).
 */
public record ToolCard(
        String name,
        String args,
        String output,
        boolean failed,
        boolean pending,
        long elapsedMs,
        boolean running,
        long ordinal) {
    private static final Pattern TRAILING_ASTERISKS = Pattern.compile("\\*$");

    /** Uses the completed capture when the transcript contains only streamed/capped stdout. */
    public ToolCard withCapturedStatus(final ToolOutputStore.Entry entry) {
        if (entry == null || running || pending || entry.running() || entry.pending()) {
            return this;
        }
        // The final capture is authoritative: streamed stdout can itself end with a misleading footer.
        return new ToolCard(name, args, output, entry.failed(), pending, elapsedMs, running, ordinal);
    }

    private static final Pattern DURATION = Pattern.compile("\\(\\s*(?:#(\\d+)\\s*,\\s*)?(\\d+)\\s*ms\\s*\\)");
    /** Trailing {@code (#n)} ordinal suffix of a live running-tool header. */
    private static final Pattern RUNNING_ORDINAL = Pattern.compile("^(.*)\\(\\s*#(\\d+)\\s*\\)\\s*$");

    private static final int KEY_COMMAND_MAX = 48;

    public static ToolCard parse(final String regionText) {
        final var text = regionText.strip();
        final var newline = text.indexOf('\n');
        final var header = newline < 0 ? text : text.substring(0, newline);
        final var failed = header.contains(" failed: ");
        final var pending = !failed && header.contains(" still running: ");
        // live running block from ConversationTranscript.markdown(): "`<spinner> running <command>`",
        // optionally carrying a trailing "(#ordinal)" so the UI can anchor the running card
        if (!failed && header.startsWith("`")) {
            final var content = header.endsWith("`") ? header.substring(1, header.length() - 1) : header.substring(1);
            final var idx = content.indexOf(" running ");
            var command =
                    idx >= 0 ? content.substring(idx + " running ".length()).strip() : content.strip();
            long ordinal = -1;
            final var ordinalMatcher = RUNNING_ORDINAL.matcher(command);
            if (ordinalMatcher.matches()) {
                command = ordinalMatcher.group(1).stripTrailing();
                try {
                    ordinal = Long.parseLong(ordinalMatcher.group(2));
                } catch (final NumberFormatException e) {
                    ordinal = -1;
                }
            }
            return new ToolCard(command, "", extractCode(text), false, false, 0, true, ordinal);
        }
        final var name = nameOf(header);
        final var argsAndDuration = failed ? failedArgs(header) : pending ? pendingArgs(header) : successArgs(header);
        final var output = extractCode(text);
        return new ToolCard(
                name,
                argsAndDuration.args(),
                output,
                failed || (!pending && BashExitStatus.failed(name, output)),
                pending,
                argsAndDuration.elapsed(),
                false,
                argsAndDuration.ordinal());
    }

    private static String nameOf(final String header) {
        final int bt = header.indexOf('`');
        final int et = bt >= 0 ? header.indexOf('`', bt + 1) : -1;
        return et > bt ? header.substring(bt + 1, et) : "tool";
    }

    private static Args successArgs(final String header) {
        final int bt = header.indexOf('`');
        final int et = bt >= 0 ? header.indexOf('`', bt + 1) : -1;
        if (et <= bt) {
            return new Args("", 0, -1);
        }
        var rest = header.substring(et + 1).strip();
        if (rest.startsWith(":")) {
            rest = rest.substring(1).strip();
        }
        return stripDuration(rest);
    }

    private static Args failedArgs(final String header) {
        final var idx = header.indexOf(" failed: ");
        if (idx < 0) {
            return stripDuration(header);
        }
        return stripDuration(header.substring(idx + " failed: ".length()).strip());
    }

    /** Extracts the "still running" descriptor after the pending marker (blank when absent). */
    private static Args pendingArgs(final String header) {
        final var idx = header.indexOf(" still running: ");
        if (idx < 0) {
            return stripDuration(header);
        }
        return stripDuration(header.substring(idx + " still running: ".length()).strip());
    }

    /** Removes a trailing {@code (#NNN, NNN ms)} then any trailing asterisk from an args/error string. */
    private static Args stripDuration(final String raw) {
        final var initial = raw.strip();
        var rest = initial;
        long elapsed = 0;
        long ordinal = -1;
        final var open = rest.lastIndexOf('(');
        if (open >= 0 && rest.endsWith(")")) {
            final var matcher = DURATION.matcher(rest.substring(open));
            if (matcher.matches()) {
                if (matcher.group(1) != null) {
                    try {
                        ordinal = Long.parseLong(matcher.group(1));
                    } catch (final NumberFormatException e) {
                        ordinal = -1;
                    }
                }
                try {
                    elapsed = Long.parseLong(matcher.group(2));
                } catch (final NumberFormatException e) {
                    elapsed = 0;
                }
                rest = rest.substring(0, open).strip();
            }
        }
        rest = TRAILING_ASTERISKS.matcher(rest).replaceAll("").strip();
        if (rest.startsWith(":")) {
            rest = rest.substring(1).strip();
        }
        // ConversationTranscript escapes the metacharacters of every emitted header
        // (*streaming, failed, still running and tool replay) so a markdown render keeps bare
        // stars/underscores literal. The TUI renders the same header verbatim, so the escapes are
        // undone here - after the duration strip, so a trailing \* (before the (#..)) is
        // preserved then restored. A backslash before '*'/'_'/'\\' is always an escape by
        // construction; anything else is left untouched (a genuine backslash in the raw output).
        return new Args(unescape(rest), elapsed, ordinal);
    }

    /**
     * Undoes the escapes {@code ConversationTranscript.escapeMarkdown} applied when it built a
     * {@code *streaming ...*} header: {@code \\*} → {@code *}, {@code \\_} → {@code _} and
     * {@code \\\\} → {@code \\}. A backslash before any other character is left untouched (a
     * genuine backslash in the original command). Note this runs AFTER {@link #stripDuration}:
     * a trailing {@code \\*} is preserved so the duration regex keeps matching, then unescaped
     * here.
     */
    static String unescape(final String text) {
        if (text == null || text.indexOf('\\') < 0) {
            return text;
        }
        final var sb = new StringBuilder(text.length());
        for (var i = 0; i < text.length(); i++) {
            final var c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                final var next = text.charAt(i + 1);
                if (next == '*' || next == '_' || next == '\\') {
                    sb.append(next);
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String extractCode(final String text) {
        final var fence = "```text\n";
        final var start = text.indexOf(fence);
        if (start < 0) {
            return "";
        }
        final var from = start + fence.length();
        var end = text.indexOf("\n```", from);
        if (end < 0) {
            end = text.length();
        }
        return text.substring(from, end).stripTrailing();
    }

    /** True when the captured output looks like a git/udiff patch. */
    public boolean isDiff(final DiffRenderer diffRenderer) {
        return diffRenderer.looksLikeDiff(output);
    }

    /**
     * Short, significant command marker for the condensed rendering: the
     * meaningful tail of a compound command line (eg. {@code cd /foo && ls -la}
     * -> {@code ls -la}) instead of the full command. Running cards carry the
     * raw command as name (args are not parsed yet); failed cards carry the
     * error first line. Blank when there is nothing worth showing.
     */
    public String keyCommand() {
        return keyCommand(null);
    }

    /**
     * Like {@link #keyCommand()} but with a transform applied to the significant
     * command *before* the display-length clip, so shortening (e.g. relativizing
     * an absolute path) sees the untruncated value {@code beforeClip}.
     */
    public String keyCommand(final Function<String, String> beforeClip) {
        final var raw = args.isBlank() && running ? name : args;
        var significant = ToolPresenter.significantCommand(raw);
        if (beforeClip != null) {
            significant = beforeClip.apply(significant);
        }
        if (significant.length() <= KEY_COMMAND_MAX) {
            return significant;
        }
        return significant.substring(0, KEY_COMMAND_MAX) + "...";
    }

    private record Args(String args, long elapsed, long ordinal) {}
}
