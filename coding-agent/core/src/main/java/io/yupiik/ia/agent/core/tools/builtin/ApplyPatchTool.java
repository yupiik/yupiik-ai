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
import static java.nio.charset.StandardCharsets.UTF_8;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.FileMutation;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContainment;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Applies patches expressed in one of two dialects.
 *
 * <p><b>Unified diff</b>: the classic {@code --- a/<file>}/ {@code +++ b/<file>}/
 * {@code @@ -l,c +l,c @@} form that the description advertises. Anchored hunks bind at
 * their declared position (with a small fuzz window) and reject ambiguous matches, so a
 * drifted or repeated block can never be spliced at the wrong occurrence.</p>
 *
 * <p><b>OpenCode dialect</b>: the {@code *** Begin Patch}/{@code *** End Patch},
 * {@code *** Add/Delete/Update File:}, {@code @@ change-context}, {@code *** Move to:},
 * {@code *** End of File} form. Matching follows the OpenCode reference implementation:
 * each chunk is sought forward from the running cursor (first match wins), the whole
 * {@code @@} line is an opaque change-context hint (never parsed as numbers, so a
 * {@code (+N -N)} stat suffix is harmless), and a {@code *** End of File} chunk anchors to
 * the end of the file.</p>
 *
 * <p>Both dialects share a lenient line matcher (exact {@literal ->} trimEnd
 * {@literal ->} trim {@literal ->} Unicode-normalized) and reconcile hunk counts from the
 * hunk body rather than trusting the header, mirroring OpenCode's {@code seekSequence}.
 * All changes across the whole patch are validated before any file is written (atomic
 * whole-patch apply), a UTF-8 BOM is preserved on rewrites, and added/updated files keep a
 * trailing newline.</p>
 */
@ApplicationScoped
public class ApplyPatchTool implements EmbeddedTool {
    private static final Pattern HEREDOC =
            Pattern.compile("^(?:cat\\s+)?<<['\"]?(\\w+)['\"]?\\s*\\n([\\s\\S]*?)\\n\\1\\s*$");
    private static final Pattern HUNK_COORDINATES = Pattern.compile("^[0-9\\s,+\\-]+$");

    private static final String DEV_NULL = "/dev/null";
    /** How far an anchored hunk may drift from its declared position before being rejected. */
    private static final int ANCHOR_FUZZ = 5;

    private static final String BEGIN_PATCH = "*** Begin Patch";
    private static final String END_PATCH = "*** End Patch";
    private static final String ADD_FILE = "*** Add File:";
    private static final String DELETE_FILE = "*** Delete File:";
    private static final String UPDATE_FILE = "*** Update File:";
    private static final String MOVE_TO = "*** Move to:";
    private static final String END_OF_FILE = "*** End of File";

    /** Extracts {@code -l[,c] +l[,c]} from a unified-diff {@code @@} header, ignoring any trailing stat suffix. */
    private static final Pattern POSITIONS = Pattern.compile("^@@\\s+-(\\d+)(?:,(\\d+))?\\s*\\+(\\d+)(?:,(\\d+))?");

    private final CodingAgentConfiguration configuration;

    public ApplyPatchTool(final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
    }

    protected ApplyPatchTool() {
        this.configuration = null;
    }

    @Override
    public String name() {
        return "apply_patch";
    }

    @Override
    public String description(final Path workdir) {
        return "Apply a patch to the project files. Two dialects are accepted. "
                + "(1) Unified diff: every patch opens with a `--- a/<file>` line, then a `+++ b/<file>` line, "
                + "then one or more `@@ -old,count +new,count @@` hunk blocks "
                + "(example: `--- a/Main.java` then `+++ b/Main.java` then `@@ -1,3 +1,4 @@`). "
                + "A `/dev/null` path creates (from) or deletes (to) the file. "
                + "(2) OpenCode dialect: wrap in `*** Begin Patch` / `*** End Patch` and use "
                + "`*** Add File:<path>`, `*** Delete File:<path>`, or `*** Update File:<path>` headers, "
                + "with `@@ change-context` hunk sections where ` ` keeps a line, `-` removes it and `+` adds it, "
                + "optionally `*** Move to:<path>` after an Update header and `*** End of File` to anchor to the end. "
                + "Both dialects tolerate a trailing `(+N -N)` stat suffix on the `@@` line. "
                + "Lines are matched leniently (exact, then trailing-whitespace-insensitive, then trimmed, "
                + "then Unicode-normalized) and all changes are validated before anything is written.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.EDIT;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(Map.of("patch", Schemas.str("The patch text to apply.")), "patch");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        final var patch = arg(args, "patch");
        if (patch == null || patch.isBlank()) {
            return ToolResult.error("patch is required");
        }
        // honour a per-session working directory when the running session set one; the process
        // default applies otherwise. This is how a session on project B applies patches to B, not
        // to the directory the web/TUI process was launched from.
        final var base = context.projectDir();
        final var capture = new FileMutationCapture();
        try {
            final List<Change> changes = parse(patch);
            // Authorize the entire patch before reading sources or changing any target.
            for (final var change : changes) {
                authorize(context, base, change);
            }
            // Phase 1: validate and compute every change without touching the disk, so a failure
            // anywhere (context mismatch, missing file, escaping path) aborts the whole patch.
            final var ops = new ArrayList<PendingOp>();
            for (final var change : changes) {
                ops.add(compute(base, change, capture));
            }
            final var applied = new ArrayList<String>();
            final var created = new ArrayList<String>();
            final var deleted = new ArrayList<String>();
            // Phase 2: apply every validated change.
            for (final var op : ops) {
                op.authorize(context);
                op.apply(applied, created, deleted, capture, context);
            }
            final var sb = new StringBuilder();
            appendSummary(sb, "Applied", applied);
            appendSummary(sb, "Created", created);
            appendSummary(sb, "Deleted", deleted);
            return new ToolResult(
                    ToolResult.Status.OK,
                    sb.isEmpty() ? "Patch applied successfully." : sb.toString().strip(),
                    capture.mutations());
        } catch (final PatchException e) {
            return new ToolResult(ToolResult.Status.ERROR, e.getMessage(), capture.mutations());
        } catch (final Exception e) {
            return new ToolResult(
                    ToolResult.Status.ERROR, "Cannot apply patch: " + e.getMessage(), capture.mutations());
        }
    }

    private static void authorize(final ToolContext context, final Path base, final Change change) {
        final var path = resolve(base, change.path);
        context.sandbox().requireWrite(path);
        if (!change.create && !change.delete) {
            context.sandbox().requireRead(path);
        }
        if (change.movePath != null) {
            context.sandbox().requireWrite(resolve(base, change.movePath));
        }
    }

    private static void appendSummary(final StringBuilder sb, final String label, final List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        sb.append(label).append(' ').append(items.size()).append(" file(s):\n");
        items.forEach(p -> sb.append("  ").append(p).append('\n'));
    }

    // ---------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------

    private static List<Change> parse(final String rawPatch) throws PatchException {
        final var cleaned = stripHeredoc(rawPatch.replace("\r\n", "\n"));
        final var lines = cleaned.lines().toList();
        if (lines.stream()
                .anyMatch(l -> l.trim().equals(BEGIN_PATCH) || l.trim().equals(END_PATCH))) {
            return parseOpenCode(lines);
        }
        return parseUnified(lines);
    }

    /** Strips a leading {@code cat <<'EOF' ... EOF} (or bare {@code <<EOF ... EOF}) heredoc wrapper. */
    private static String stripHeredoc(final String input) {
        final var m = HEREDOC.matcher(input);
        return m.matches() ? m.group(2) : input;
    }

    // --- unified diff dialect -------------------------------------------------

    private static List<Change> parseUnified(final List<String> lines) throws PatchException {
        final var changes = new ArrayList<Change>();
        String oldPath = null;
        String newPath = null;
        List<Hunk> hunks = null;
        String currentHeader = null;
        List<String> current = null;
        for (final var line : lines) {
            if (line.startsWith("--- ")) {
                if (oldPath != null) {
                    if (current != null && !current.isEmpty()) {
                        hunks.add(Hunk.ofUnified(currentHeader, current));
                    }
                    changes.add(Change.unified(oldPath, newPath, hunks));
                }
                oldPath = line.substring(4).strip();
                newPath = null;
                hunks = new ArrayList<>();
                currentHeader = null;
                current = null;
            } else if (line.startsWith("+++ ")) {
                newPath = line.substring(4).strip();
            } else if (line.startsWith("@@")) {
                if (current != null && !current.isEmpty()) {
                    if (hunks == null) {
                        throw new PatchException("Hunk found before any ---/+++ file header");
                    }
                    hunks.add(Hunk.ofUnified(currentHeader, current));
                }
                currentHeader = line;
                current = new ArrayList<>();
            } else if (current != null) {
                current.add(line);
            }
            // lines before any hunk (file headers) are ignored
        }
        if (oldPath != null) {
            if (current != null && !current.isEmpty()) {
                hunks.add(Hunk.ofUnified(currentHeader, current));
            }
            changes.add(Change.unified(oldPath, newPath, hunks));
        } else if (current != null) {
            throw new PatchException("Hunk present without a ---/+++ file header");
        }
        return changes;
    }

    // --- OpenCode dialect ----------------------------------------------------

    private static List<Change> parseOpenCode(final List<String> lines) throws PatchException {
        int beginIdx = -1;
        int endIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            final var t = lines.get(i).trim();
            if (t.equals(BEGIN_PATCH)) {
                beginIdx = i;
            } else if (t.equals(END_PATCH)) {
                endIdx = i;
                break;
            }
        }
        if (beginIdx == -1 || endIdx == -1 || beginIdx >= endIdx) {
            throw new PatchException("Invalid patch format: missing Begin/End markers");
        }

        final var changes = new ArrayList<Change>();
        var i = beginIdx + 1;
        while (i < endIdx) {
            final var line = lines.get(i);
            if (line.startsWith(ADD_FILE)) {
                final var path = line.substring(ADD_FILE.length()).strip();
                if (path.isEmpty()) {
                    i++;
                    continue;
                }
                final var body = new ArrayList<String>();
                i++;
                while (i < lines.size()
                        && !lines.get(i).trim().equals(END_PATCH)
                        && !lines.get(i).startsWith("***")) {
                    if (lines.get(i).startsWith("+") && !lines.get(i).equals("+++")) {
                        body.add(lines.get(i));
                    }
                    i++;
                }
                changes.add(Change.create(path, List.of(Hunk.ofOpenCode(null, body, false))));
            } else if (line.startsWith(DELETE_FILE)) {
                final var path = line.substring(DELETE_FILE.length()).strip();
                if (!path.isEmpty()) {
                    changes.add(Change.delete(path, List.of()));
                }
                i++;
            } else if (line.startsWith(UPDATE_FILE)) {
                final var path = line.substring(UPDATE_FILE.length()).strip();
                if (path.isEmpty()) {
                    i++;
                    continue;
                }
                var j = i + 1;
                String movePath = null;
                if (j < endIdx && lines.get(j).startsWith(MOVE_TO)) {
                    movePath = lines.get(j).substring(MOVE_TO.length()).strip();
                    j++;
                }
                final var hunks = new ArrayList<Hunk>();
                String currentContext = null;
                boolean currentEof = false;
                final var body = new ArrayList<String>();
                while (j < endIdx) {
                    final var l = lines.get(j);
                    if (l.startsWith("@@")) {
                        if (!body.isEmpty()) {
                            hunks.add(Hunk.ofOpenCode(currentContext, body, currentEof));
                        }
                        currentContext = l.substring(2).trim();
                        currentEof = false;
                        body.clear();
                    } else if (l.equals(END_OF_FILE)) {
                        currentEof = true;
                    } else if (l.startsWith("***")) {
                        break;
                    } else if (!l.trim().isEmpty() && (l.startsWith(" ") || l.startsWith("-") || l.startsWith("+"))) {
                        body.add(l);
                    }
                    j++;
                }
                if (!body.isEmpty()) {
                    hunks.add(Hunk.ofOpenCode(currentContext, body, currentEof));
                }
                changes.add(Change.update(path, movePath, hunks));
                i = j;
            } else {
                i++;
            }
        }
        return changes;
    }

    // ---------------------------------------------------------------------
    // Validation / application model
    // ---------------------------------------------------------------------

    private static Path resolve(final Path base, final String raw) throws PatchException {
        var clean = raw;
        if (clean.startsWith("a/") || clean.startsWith("b/")) {
            clean = clean.substring(2);
        }
        if (DEV_NULL.equals(clean)) {
            throw new PatchException("Unexpected /dev/null path header");
        }
        final var candidate = base.resolve(clean).normalize();
        if (!candidate.startsWith(base)) {
            throw new PatchException("Patch path escapes the project directory: '" + raw + "'");
        }
        final var symlinkEscape = ToolContainment.symlinkEscapeError(base, candidate);
        if (symlinkEscape != null) {
            throw new PatchException(symlinkEscape);
        }
        return candidate;
    }

    private static PendingOp compute(final Path base, final Change change, final FileMutationCapture capture)
            throws PatchException {
        if (change.delete) {
            return new DeleteOp(resolve(base, change.path));
        }
        final var path = resolve(base, change.path);
        if (change.create) {
            final var out = applyHunks(new ArrayList<>(), change.hunks);
            return new WriteOp(path, new PendingContent(out, false), true, null);
        }
        final TextLines current = read(path, capture);
        final var out = applyHunks(new ArrayList<>(current.lines), change.hunks);
        if (change.movePath != null) {
            return new MoveOp(
                    path, resolve(base, change.movePath), new PendingContent(out, current.bom), current.before);
        }
        return new WriteOp(path, new PendingContent(out, current.bom), false, current.before);
    }

    private static List<String> applyHunks(final List<String> lines, final List<Hunk> hunks) throws PatchException {
        final var out = new ArrayList<>(lines);
        if (!hunks.isEmpty() && hunks.get(0).seek) {
            return applyHunksSeek(out, hunks);
        }
        return applyHunksAnchored(out, hunks);
    }

    /** Anchored (unified-diff) semantics: bind at declared position with fuzz, reject ambiguity. */
    private static List<String> applyHunksAnchored(final List<String> lines, final List<Hunk> hunks) {
        var drift = 0;
        for (final var hunk : hunks) {
            final var start = resolveStartAnchored(lines, hunk, drift);
            applyAt(lines, start, hunk);
            drift += hunk.newCount - hunk.oldCount;
        }
        return lines;
    }

    /** OpenCode semantics: forward first-match-wins seek per chunk, honoring change-context and EOF anchors. */
    private static List<String> applyHunksSeek(final List<String> lines, final List<Hunk> hunks) {
        var lineIndex = 0;
        for (final var hunk : hunks) {
            if (hunk.changeContext != null && !hunk.changeContext.isBlank()) {
                final var base = List.of(hunk.changeContext);
                final var ctx = hunk.eof ? seek(lines, base, lineIndex, true) : seek(lines, base, lineIndex, false);
                if (ctx < 0) {
                    throw new PatchException("Failed to find change context '" + hunk.changeContext + "'");
                }
                lineIndex = ctx + base.size();
            }
            final var old = oldLines(hunk);
            if (old.isEmpty()) {
                applyAt(lines, Math.min(lineIndex, lines.size()), hunk);
                continue;
            }
            final var found = seek(lines, old, lineIndex, hunk.eof);
            if (found < 0) {
                throw new PatchException("Failed to find expected lines");
            }
            applyAt(lines, found, hunk);
            lineIndex = Math.min(found + old.size(), lines.size());
        }
        return lines;
    }

    /** How far a hunk may drift from its declared position before being rejected. */
    private static int resolveStartAnchored(final List<String> lines, final Hunk hunk, final int drift) {
        final var old = oldLines(hunk);
        if (old.isEmpty()) { // pure addition: bind at the declared position (or end)
            return hunk.oldStart == null ? Math.min(lines.size(), drift) : Math.max(0, hunk.oldStart - 1 + drift);
        }
        if (hunk.oldStart == null) {
            var found = -1;
            for (var offset = 0; offset <= lines.size(); offset++) {
                if (matchesAt(lines, offset, old)) {
                    if (found >= 0) {
                        throw new PatchException("Hunk context is ambiguous: it matches at lines " + (found + 1)
                                + " and " + (offset + 1) + "; add explicit positions to the @@ header");
                    }
                    found = offset;
                }
            }
            if (found < 0) {
                throw new PatchException("Hunk context did not match the file contents");
            }
            return found;
        }
        final var declared = Math.max(0, hunk.oldStart - 1 + drift);
        if (matchesAt(lines, declared, old)) {
            return declared;
        }
        for (var offset = Math.max(0, declared - ANCHOR_FUZZ);
                offset <= Math.min(lines.size(), declared + ANCHOR_FUZZ);
                offset++) {
            if (matchesAt(lines, offset, old)) {
                return offset;
            }
        }
        throw new PatchException("Hunk context did not match the file contents around line " + (declared + 1));
    }

    /** The sequence of file lines a hunk must see before applying (context and removed lines). */
    private static List<String> oldLines(final Hunk hunk) {
        final var out = new ArrayList<String>(hunk.oldCount);
        for (final var body : hunk.lines) {
            final var c = body.charAt(0);
            if (c == ' ' || c == '-') {
                out.add(body.substring(1));
            }
        }
        return out;
    }

    private static boolean matchesAt(final List<String> lines, final int start, final List<String> old) {
        var idx = start;
        for (final var line : old) {
            if (idx >= lines.size() || !matches(lines.get(idx), line)) {
                return false;
            }
            idx++;
        }
        return true;
    }

    /** First-match-wins forward search (OpenCode {@code seekSequence}); on eof tries from the end first. */
    private static int seek(
            final List<String> lines, final List<String> pattern, final int startIndex, final boolean eof) {
        if (pattern.isEmpty()) {
            return -1;
        }
        if (eof) {
            final var fromEnd = lines.size() - pattern.size();
            if (fromEnd >= startIndex && matchesAt(lines, fromEnd, pattern)) {
                return fromEnd;
            }
        }
        for (var i = Math.max(0, startIndex); i <= lines.size() - pattern.size(); i++) {
            if (matchesAt(lines, i, pattern)) {
                return i;
            }
        }
        return -1;
    }

    private static void applyAt(final List<String> lines, final int start, final Hunk hunk) {
        final var out = new ArrayList<String>(lines.size() + hunk.newCount);
        out.addAll(lines.subList(0, Math.min(Math.max(start, 0), lines.size())));
        var src = Math.min(Math.max(start, 0), lines.size());
        for (final var body : hunk.lines) {
            final var c = body.charAt(0);
            if (c == ' ') {
                out.add(src < lines.size() ? lines.get(src) : body.substring(1));
                src++;
            } else if (c == '-') {
                src++;
            } else {
                out.add(body.substring(1));
            }
        }
        out.addAll(lines.subList(Math.min(src, lines.size()), lines.size()));
        lines.clear();
        lines.addAll(out);
    }

    /**
     * Lenient line comparison mirroring OpenCode's {@code seekSequence} matcher: exact,
     * then trailing-whitespace-insensitive, then fully trimmed, then Unicode-normalized.
     */
    private static boolean matches(final String fileLine, final String patchLine) {
        if (fileLine.equals(patchLine)) {
            return true;
        }
        if (fileLine.stripTrailing().equals(patchLine.stripTrailing())) {
            return true;
        }
        if (fileLine.trim().equals(patchLine.trim())) {
            return true;
        }
        return normalizeUnicode(fileLine.trim()).equals(normalizeUnicode(patchLine.trim()));
    }

    private static String normalizeUnicode(final String s) {
        return s.replace('\u2018', '\'') // left single quotation mark
                .replace('\u2019', '\'') // right single quotation mark
                .replace('\u201A', '\'') // single low-9 quotation mark
                .replace('\u201B', '\'') // single high-reversed quotation mark
                .replace('\u201C', '"') // left double quotation mark
                .replace('\u201D', '"') // right double quotation mark
                .replace('\u201E', '"') // double low-9 quotation mark
                .replace('\u201F', '"') // double high-reversed quotation mark
                .replace('\u2010', '-') // hyphen
                .replace('\u2011', '-') // non-breaking hyphen
                .replace('\u2012', '-') // figure dash
                .replace('\u2013', '-') // en dash
                .replace('\u2014', '-') // em dash
                .replace('\u2015', '-') // horizontal bar
                .replace('\u2026', '.') // ellipsis
                .replace('\u00A0', ' '); // non-breaking space
    }

    // ---------------------------------------------------------------------
    // IO: BOM-preserving read/write and trailing-newline policy
    // ---------------------------------------------------------------------

    private static TextLines read(final Path path, final FileMutationCapture capture) throws PatchException {
        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (final IOException e) {
            throw new PatchException("Failed to read file: '" + path + "'");
        }
        if (!Files.isRegularFile(path)) {
            throw new PatchException("Failed to read file to update: '" + path + "'");
        }
        final boolean bom = bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF;
        final var text =
                new String(bom ? Arrays.copyOfRange(bytes, 3, bytes.length) : bytes, UTF_8).replace("\r\n", "\n");
        final var parts = text.split("\n", -1);
        var end = parts.length;
        if (end > 0 && parts[end - 1].isEmpty()) { // one trailing empty element marks the trailing newline
            end--;
        }
        final var lines = new ArrayList<String>(end);
        lines.addAll(Arrays.asList(parts).subList(0, end));
        return new TextLines(lines, bom, capture.before(path, bytes));
    }

    private interface PendingOp {
        void authorize(ToolContext context);

        void apply(
                final List<String> applied,
                final List<String> created,
                final List<String> deleted,
                final FileMutationCapture capture,
                final ToolContext context);
    }

    private record WriteOp(Path path, PendingContent content, boolean create, FileMutationCapture.Before before)
            implements PendingOp {
        @Override
        public void authorize(final ToolContext context) {
            context.sandbox().requireWrite(path);
        }

        @Override
        public void apply(
                final List<String> applied,
                final List<String> created,
                final List<String> deleted,
                final FileMutationCapture capture,
                final ToolContext context) {
            try {
                SandboxFiles.createParents(context, path);
                final var bytes = toBytes(content);
                var newFile = false;
                if (create) {
                    try {
                        Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                        newFile = true;
                    } catch (final FileAlreadyExistsException existing) {
                        Files.write(path, bytes);
                    }
                } else {
                    Files.write(path, bytes);
                }
                final var after = capture.text(bytes);
                if (newFile) {
                    capture.completed(path, FileMutation.Kind.CREATE, null, after);
                } else if (!capture.unchanged(path, before, after)) {
                    capture.changed(path, FileMutation.Kind.UPDATE, before, after);
                }
            } catch (final IOException e) {
                throw new PatchException("Cannot write file '" + path + "': " + e.getMessage());
            }
            if (create) {
                created.add(path.toString());
            } else {
                applied.add(path.toString());
            }
        }
    }

    private record DeleteOp(Path path) implements PendingOp {
        @Override
        public void authorize(final ToolContext context) {
            context.sandbox().requireWrite(path);
        }

        @Override
        public void apply(
                final List<String> applied,
                final List<String> created,
                final List<String> deleted,
                final FileMutationCapture capture,
                final ToolContext context) {
            try {
                if (Files.deleteIfExists(path)) {
                    // This operation never read the file: deletion permission is not an extra read grant.
                    capture.completed(path, FileMutation.Kind.DELETE, null, null);
                    deleted.add(path.toString());
                }
            } catch (final IOException e) {
                throw new PatchException("Cannot delete file '" + path + "': " + e.getMessage());
            }
        }
    }

    private record MoveOp(Path source, Path target, PendingContent content, FileMutationCapture.Before before)
            implements PendingOp {
        @Override
        public void authorize(final ToolContext context) {
            context.sandbox().requireWrite(source);
            context.sandbox().requireWrite(target);
        }

        @Override
        public void apply(
                final List<String> applied,
                final List<String> created,
                final List<String> deleted,
                final FileMutationCapture capture,
                final ToolContext context) {
            try {
                SandboxFiles.createParents(context, target);
                final var bytes = toBytes(content);
                var newFile = false;
                try {
                    Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    newFile = true;
                } catch (final FileAlreadyExistsException existing) {
                    Files.write(target, bytes);
                }
                // Record the target before attempting deletion: a failed move can still write its target.
                capture.completed(
                        target,
                        newFile ? FileMutation.Kind.CREATE : FileMutation.Kind.UPDATE,
                        null,
                        newFile ? capture.text(bytes) : null);
                if (Files.deleteIfExists(source)) {
                    capture.changed(source, FileMutation.Kind.DELETE, before, "");
                }
            } catch (final IOException e) {
                throw new PatchException("Cannot move file '" + source + "' to '" + target + "': " + e.getMessage());
            }
            applied.add(target.toString());
            deleted.add(source.toString());
        }
    }

    private static byte[] toBytes(final PendingContent content) {
        final var sb = new StringBuilder();
        for (var i = 0; i < content.lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(content.lines.get(i));
        }
        var body = sb.toString();
        // trailing-newline policy: added/updated files keep a trailing newline when non-empty
        if (!body.isEmpty() && !body.endsWith("\n")) {
            body += "\n";
        }
        final var bytes = body.getBytes(UTF_8);
        if (!content.bom) {
            return bytes;
        }
        final var out = new byte[bytes.length + 3];
        out[0] = (byte) 0xEF;
        out[1] = (byte) 0xBB;
        out[2] = (byte) 0xBF;
        System.arraycopy(bytes, 0, out, 3, bytes.length);
        return out;
    }

    private record TextLines(List<String> lines, boolean bom, FileMutationCapture.Before before) {}

    private record PendingContent(List<String> lines, boolean bom) {}

    private record Change(String path, String movePath, boolean create, boolean delete, List<Hunk> hunks) {
        static Change create(final String path, final List<Hunk> hunks) {
            return new Change(path, null, true, false, hunks == null ? List.of() : hunks);
        }

        static Change delete(final String path, final List<Hunk> hunks) {
            return new Change(path, null, false, true, hunks == null ? List.of() : hunks);
        }

        /** OpenCode {@code *** Update File:} with an optional {@code *** Move to:} target. */
        static Change update(final String path, final String movePath, final List<Hunk> hunks) {
            return new Change(path, movePath, false, false, hunks == null ? List.of() : hunks);
        }

        /** Unified-diff change: {@code /dev/null} as old/new path signals create/delete; no move concept. */
        static Change unified(final String oldPath, final String newPath, final List<Hunk> hunks) {
            final boolean delete = DEV_NULL.equals(newPath);
            final boolean create = DEV_NULL.equals(oldPath);
            final String path = delete ? oldPath : newPath;
            return new Change(path, null, create, delete, hunks == null ? List.of() : hunks);
        }
    }

    /**
     * A parsed hunk, normalized into prefix-bearing body lines ({@code ' '} context,
     * {@code '-'} removed, {@code '+'} added). Counts are always derived from the body so a
     * wrong header count can never reject an unambiguous edit.
     */
    private record Hunk(
            Integer oldStart,
            boolean seek,
            String changeContext,
            boolean eof,
            List<String> lines,
            int oldCount,
            int newCount) {
        static Hunk ofUnified(final String header, final List<String> body) {
            final var pos = header == null ? null : positions(header);
            final var counts = counts(body);
            return new Hunk(pos == null ? null : pos[0], false, null, false, List.copyOf(body), counts[0], counts[1]);
        }

        static Hunk ofOpenCode(final String changeContext, final List<String> body, final boolean eof) {
            final var counts = counts(body);
            final String ctx = changeContext == null ? null : changeContext.strip();
            // A change-context that is pure line-range noise (unified `-l,c +l,c @@ (+0 -0)`,
            // which models append even in the OpenCode dialect) is not a real seek hint: ignore it
            // so it can never fail a forward-seek on text that looks nothing like it.
            final String effective = ctx == null || numericNoise(ctx) ? null : ctx;
            return new Hunk(null, true, effective, eof, List.copyOf(body), counts[0], counts[1]);
        }

        /** True when the OpenCode change-context is purely digits/commas/plus-minus (line-range noise). */
        private static boolean numericNoise(final String s) {
            final var stripped =
                    s.replace("@", "").replace("(", "").replace(")", "").trim();
            return stripped.isEmpty() || HUNK_COORDINATES.matcher(stripped).matches();
        }

        /** Returns {1-based oldStart, oldCount, newCount} or null when the header carries no position. */
        private static int[] positions(final String header) {
            final var m = POSITIONS.matcher(header);
            if (!m.find()) {
                return null;
            }
            final var oldStart = Integer.parseInt(m.group(1));
            final var oldCount = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            final var newCount = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
            return new int[] {oldStart, oldCount, newCount};
        }

        private static int[] counts(final List<String> body) {
            var oldCount = 0;
            var newCount = 0;
            for (final var line : body) {
                final var c = line.charAt(0);
                if (c == ' ') { // context lines exist on both sides of the diff
                    oldCount++;
                    newCount++;
                } else if (c == '+') {
                    newCount++;
                } else if (c == '-') {
                    oldCount++;
                }
            }
            return new int[] {oldCount, newCount};
        }
    }

    private static final class PatchException extends RuntimeException {
        private PatchException(final String message) {
            super(message);
        }
    }
}
