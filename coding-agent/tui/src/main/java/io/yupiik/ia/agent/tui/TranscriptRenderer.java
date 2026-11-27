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

import dev.tamboui.markdown.MarkdownStyles;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders transcript regions to {@link Line} lists with an eye on streaming:
 * the transcript is append-only while a turn streams, so rendering the whole
 * thing again on every frame would be wasteful. This renderer holds back the
 * trailing (still growing) source at a safe boundary - the last line end that is
 * not inside a fenced code block - and reuses the rendered lines of the stable
 * prefix while only re-rendering the short suffix each frame.
 *
 * <p>On top of that suffix trick, the stable prefix itself is not re-parsed while
 * it grows: the previous frame's prefix is remembered, and when the new prefix is
 * just that prefix plus appended lines (streaming is append-only), only the delta
 * between the two is sent through the markdown engine. The rendered lines of the
 * already-stable part are kept as-is, so a long streaming turn costs O(delta) per
 * frame instead of re-parsing the whole accumulated text every frame (which
 * previously put the main render thread at ~40% CPU inside commonmark).
 *
 * <p>This matches the streaming constraint: we never wait for the stream to end
 * and never buffer more than one logical region; buffering happens at line and
 * code-block granularity only. The {@link MarkdownLines} renderer produces plain,
 * pre-wrapped lines so the cached prefix stays valid while new text is appended.
 */
final class TranscriptRenderer {
    private static final int MAX_CACHED_PREFIXES = 64;
    private static final int MAX_CACHED_SOURCE_LENGTH = 64 * 1024;
    private static final int MAX_CACHE_WEIGHT = 256 * 1024;

    private final MarkdownLines markdownLines = new MarkdownLines();
    // key -> rendered lines of a stable (completed) source prefix; thread-safe and
    // bounded so a long session (a new key per region switch) never grows the
    // footprint. The append-only delta path deliberately skips this cache: growing
    // prefixes are moving targets that would churn (and blow up) the entries.
    private final Map<Key, CachedPrefix> prefixCache = new LinkedHashMap<>(16, 0.75f, true);
    private int prefixCacheWeight;

    // Render-thread-only, exact-input memo. No append-state assumptions: a newly
    // arriving pipe can retroactively turn the preceding line into a table row.
    private String lastSafeSource;
    private int lastSafeOffset;

    // single slot remembering the last stable prefix rendered: an exact hit makes a
    // busy frame with unchanged content O(1), and the append-only path re-parses only
    // the delta added since the previous frame instead of the whole accumulated turn.
    // Only ever touched by the render thread, but kept volatile so concurrent readers
    // never observe a torn half-updated state.
    private volatile String lastPrefix;
    private volatile Rendered lastPrefixLines;
    private volatile int lastWidth = -1;
    private volatile Style lastBase;
    private volatile MarkdownStyles lastStyles;

    void theme(final Palette palette, final TuiConfiguration.MarkdownColors colors, final boolean ascii) {
        markdownLines.theme(palette, colors, ascii);
        clear();
    }

    void clear() {
        synchronized (prefixCache) {
            prefixCache.clear();
            prefixCacheWeight = 0;
        }
        lastPrefix = null;
        lastPrefixLines = null;
        lastSafeSource = null;
    }

    /** A rendered line list plus the per-row copy kind of each line (same size). */
    record Rendered(List<Line> lines, List<SelectionState.RowKind> kinds) {
        private static final Rendered EMPTY = new Rendered(List.of(), List.of());

        private static List<SelectionState.RowKind> kindsOf(final List<Line> lines) {
            return Collections.nCopies(lines.size(), SelectionState.RowKind.HARD);
        }
    }

    List<Line> renderRegion(final String source, final int width, final Style base, final MarkdownStyles styles) {
        return renderRegionWithKinds(source, width, base, styles).lines();
    }

    /**
     * Same as {@link #renderRegion(String, int, Style, MarkdownStyles)} but also returns the
     * per-row copy kinds propagated from {@link MarkdownLines#renderWithKinds}, kept aligned
     * with the lines through the same prefix caching, streaming-delta and blank-row logic.
     */
    Rendered renderRegionWithKinds(
            final String source, final int width, final Style base, final MarkdownStyles styles) {
        if (source == null || source.isEmpty()) {
            return Rendered.EMPTY;
        }
        final var safe = lastSafeNewline(source);
        final var prefix = source.substring(0, safe);
        final var suffix = source.substring(safe);
        final var prefixLines = prefixLines(prefix, width, base, styles);
        if (suffix.isEmpty()) {
            // defensive copy: cached lines must never be mutated by the caller
            return new Rendered(new ArrayList<>(prefixLines.lines()), new ArrayList<>(prefixLines.kinds()));
        }
        final var combined = new ArrayList<Line>(prefixLines.lines().size() + 16);
        combined.addAll(prefixLines.lines());
        final var combinedKinds =
                new ArrayList<SelectionState.RowKind>(prefixLines.kinds().size() + 16);
        combinedKinds.addAll(prefixLines.kinds());
        final var suffixRendered = renderIndependent(suffix, width, base, styles);
        appendBlocks(combined, combinedKinds, suffixRendered, boundaryBlanks(prefix, suffix) > 0, !suffix.isBlank());
        return new Rendered(combined, combinedKinds);
    }

    /**
     * Appends {@code rendered} to {@code out}, optionally inserting the single blank row that
     * separates two block groups.
     * <p>
     * A blank source line only separates blocks <em>inside</em> one markdown document: both
     * chunks here are parsed independently, so commonmark drops the trailing blanks of the first
     * and the leading blanks of the second and the separator row would be lost - the paragraph
     * above glues to the paragraph below, and a stream that already rendered such a boundary
     * keeps it glued for the rest of the session (the cached prefix lines are reused verbatim).
     * The row is inserted only once the new chunk actually produced lines, so a stream paused on
     * a paragraph-ending blank line still renders exactly like a cold render of the same text.
     *
     * @param blankRowBetween true to keep one blank row between the two groups: either a blank
     *                        source line at the boundary, or a new block group (a fenced block)
     */
    static void appendBlocks(final List<Line> out, final List<Line> rendered, final boolean blankRowBetween) {
        appendBlocks(out, rendered, blankRowBetween, false);
    }

    /**
     * Same as {@link #appendBlocks(List, List, boolean)} but aware that the second document may
     * have already started a block that did not emit a row yet (an open fenced block): the cold
     * renderer still counts it as a sibling block and keeps the separator row, so the live stream
     * must not wait for the first content row in that case or the frame right after the opening
     * fence line would read denser than a full render of the same text.
     */
    static void appendBlocks(
            final List<Line> out, final List<Line> rendered, final boolean blankRowBetween, final boolean openBlock) {
        appendBlocks(
                out,
                null,
                new TranscriptRenderer.Rendered(rendered, Rendered.kindsOf(rendered)),
                blankRowBetween,
                openBlock);
    }

    /** Kinds-aware core of {@link #appendBlocks(List, List, boolean, boolean)}. */
    static void appendBlocks(
            final List<Line> out,
            final List<SelectionState.RowKind> outKinds,
            final Rendered rendered,
            final boolean blankRowBetween,
            final boolean openBlock) {
        final var blank = (blankRowBetween && !rendered.lines().isEmpty())
                || (openBlock && rendered.lines().isEmpty());
        if (blank && !out.isEmpty() && !isBlankRow(out.get(out.size() - 1))) {
            out.add(Line.empty());
            if (outKinds != null) {
                outKinds.add(SelectionState.RowKind.HARD);
            }
        }
        if (!rendered.lines().isEmpty()) {
            out.addAll(rendered.lines());
            if (outKinds != null) {
                outKinds.addAll(rendered.kinds());
            }
        }
    }

    /** True when {@code line} carries no visible text (a spacer row). */
    static boolean isBlankRow(final Line line) {
        for (final var span : line.spans()) {
            if (span.content() != null && !span.content().isBlank()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Number of blank source lines at the boundary between two chunks of the same region, i.e.
     * the blank lines {@code left} ends with plus the ones {@code right} starts with (the split
     * point is a line boundary, so the two sets never overlap). Positive when the two chunks must
     * be kept apart by a blank row.
     */
    static int boundaryBlanks(final String left, final String right) {
        return trailingBlankLines(left) + leadingBlankLines(right);
    }

    /**
     * Blank lines at the end of {@code text}: a trailing line terminator only terminates the last
     * content line, so {@code "a\n"} has none while {@code "a\n\n"} has one.
     */
    private static int trailingBlankLines(final String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // Ignore only the artifact after the last terminator, not the blank line
        // before it. Scan from the edge and stop at the first content character.
        var end = text.endsWith("\n") ? text.length() - 1 : text.length();
        var count = 0;
        while (true) {
            if (end == 0) {
                return count + 1;
            }
            final var cp = text.codePointBefore(end);
            if (cp == '\n') {
                count++;
            } else if (!Character.isWhitespace(cp)) {
                return count;
            }
            end -= Character.charCount(cp);
        }
    }

    /** Blank lines at the start of {@code text} ({@code "\n\na"} has two). */
    private static int leadingBlankLines(final String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        var count = 0;
        for (var i = 0; i < text.length(); ) {
            final var cp = text.codePointAt(i);
            if (cp == '\n') {
                count++;
            } else if (!Character.isWhitespace(cp)) {
                return count;
            }
            i += Character.charCount(cp);
        }
        // Like split("\n", -1), count the unterminated (possibly empty) final line.
        return count + 1;
    }

    private Rendered prefixLines(final String prefix, final int width, final Style base, final MarkdownStyles styles) {
        if (prefix.isEmpty()) {
            return Rendered.EMPTY;
        }
        final var sameParams = width == lastWidth && lastBase != null && lastBase.equals(base) && styles == lastStyles;
        if (lastPrefixLines != null && sameParams) {
            if (prefix.equals(lastPrefix)) {
                // identical stable prefix as the previous frame: reuse verbatim
                return lastPrefixLines;
            }
            if (lastPrefix != null
                    && prefix.startsWith(lastPrefix)
                    && !prefix.substring(lastPrefix.length()).stripLeading().startsWith(">")) {
                // append-only streaming: only the delta (the lines added since the
                // previous frame) is markdown-parsed; the rest was already rendered.
                final var delta = prefix.substring(lastPrefix.length());
                final var result = new ArrayList<Line>(lastPrefixLines.lines().size() + 8);
                result.addAll(lastPrefixLines.lines());
                final var resultKinds = new ArrayList<SelectionState.RowKind>(
                        lastPrefixLines.kinds().size() + 8);
                resultKinds.addAll(lastPrefixLines.kinds());
                final var deltaRendered = renderIndependent(delta, width, base, styles);
                appendBlocks(result, resultKinds, deltaRendered, boundaryBlanks(lastPrefix, delta) > 0, false);
                final var rendered = new Rendered(result, resultKinds);
                remember(prefix, rendered, width, base, styles);
                return rendered;
            }
        }
        // region switch or width/base/styles change: full render through the bounded cache
        final var key = new Key(prefix, width, base, styles);
        Rendered rendered;
        synchronized (prefixCache) {
            final var cached = prefixCache.get(key);
            rendered = cached == null ? null : cached.rendered();
        }
        if (rendered == null) {
            rendered = renderIndependent(prefix, width, base, styles);
            cachePrefix(key, rendered);
        }
        remember(prefix, rendered, width, base, styles);
        return rendered;
    }

    private void cachePrefix(final Key key, final Rendered rendered) {
        if (key.source().length() > MAX_CACHED_SOURCE_LENGTH) {
            return;
        }
        // Approximate retained weight in character/row units, including rendered
        // output (a narrow width can produce many rows from a small source).
        long weight = key.source().length() + 32L * rendered.lines().size();
        for (final var line : rendered.lines()) {
            for (final var span : line.spans()) {
                weight += 16L + (span.content() == null ? 0 : span.content().length());
            }
            if (weight > MAX_CACHE_WEIGHT) {
                return;
            }
        }
        synchronized (prefixCache) {
            final var previous = prefixCache.put(key, new CachedPrefix(rendered, (int) weight));
            prefixCacheWeight += (int) weight - (previous == null ? 0 : previous.weight());
            final var entries = prefixCache.entrySet().iterator();
            while (prefixCache.size() > MAX_CACHED_PREFIXES || prefixCacheWeight > MAX_CACHE_WEIGHT) {
                prefixCacheWeight -= entries.next().getValue().weight();
                entries.remove();
            }
        }
    }

    private void remember(
            final String prefix,
            final Rendered rendered,
            final int width,
            final Style base,
            final MarkdownStyles styles) {
        final var retain =
                prefix.length() <= MAX_CACHED_SOURCE_LENGTH && rendered.lines().size() <= MAX_CACHE_WEIGHT / 32;
        lastPrefix = retain ? prefix : null;
        lastPrefixLines = retain ? rendered : null;
        lastWidth = width;
        lastBase = base;
        lastStyles = styles;
    }

    /** Parses an independently delimited block group, shared with the compact streaming layout. */
    Rendered renderIndependent(final String source, final int width, final Style base, final MarkdownStyles styles) {
        if (source == null || source.isEmpty()) {
            return Rendered.EMPTY;
        }
        final var result = markdownLines.renderWithKinds(source, width, base, styles);
        return new Rendered(result.lines(), result.kinds());
    }

    /**
     * Index just past the last {@code '\n'} that is a safe place to cut: not inside a
     * fenced code block and not inside a GFM table. Cutting between fence-aware or
     * table-aware lines keeps each suffix independently re-parseable, so streaming
     * appends only re-render the tail.
     *
     * <p>As a conservative heuristic, a table candidate is a contiguous run of two or
     * more lines containing a pipe. CommonMark only recognizes a GFM table once the
     * separator row follows the header. Splitting in the middle of such a run leaves the
     * table's header/separator in the committed prefix and the remaining {@code |} rows
     * in the suffix, which then parse as a literal-pipe paragraph (the reported
     * {@code /usage} last-row symptom, made possible by the transcript's {@code strip()}
     * removing the final row's newline). Keeping the whole run in the re-parsed suffix
     * renders it as a single table. A lone pipe-containing line is an ordinary paragraph
     * and stays a valid split point.
     *
     * @return if {@code source} contains any safe break the index right after its
     *         newline, otherwise {@code 0}
     */
    int lastSafeNewline(final String source) {
        if (source == null || source.isEmpty()) {
            return 0;
        }
        if (source.equals(lastSafeSource)) {
            return lastSafeOffset;
        }
        var inFence = false;
        var inQuote = false;
        var safe = 0;
        var previousPipe = false;
        var safeBeforePipeRun = 0;
        for (var start = 0; start < source.length(); ) {
            var end = start;
            var first = -1;
            var pipe = false;
            while (end < source.length() && source.charAt(end) != '\n') {
                final var cp = source.codePointAt(end);
                pipe |= cp == '|';
                if (first < 0 && !Character.isWhitespace(cp)) {
                    first = end;
                }
                end += Character.charCount(cp);
            }
            // A second pipe line, even an unterminated one, invalidates the safe
            // cut after the first. Fence/quote state still advances on every row.
            if (pipe && previousPipe) {
                safe = safeBeforePipeRun;
            } else if (pipe) {
                safeBeforePipeRun = safe;
            }
            if (first >= 0 && source.charAt(first) == '>') {
                inQuote = true;
            } else if (first < 0) {
                inQuote = false;
            }
            if (first >= 0 && (source.startsWith("```", first) || source.startsWith("~~~", first))) {
                inFence = !inFence;
            }
            if (end == source.length()) {
                break;
            }
            if (!inFence && !inQuote && !(pipe && previousPipe)) {
                safe = end + 1;
            }
            previousPipe = pipe;
            start = end + 1;
        }
        // Oversized inputs are scanned but never retained, and release the old slot.
        lastSafeSource = source.length() <= MAX_CACHED_SOURCE_LENGTH ? source : null;
        lastSafeOffset = safe;
        return safe;
    }

    private record CachedPrefix(Rendered rendered, int weight) {}

    private record Key(String source, int width, Style base, MarkdownStyles styles) {}
}
