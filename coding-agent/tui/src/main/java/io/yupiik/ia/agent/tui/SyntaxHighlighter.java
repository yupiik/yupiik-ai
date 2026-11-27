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

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import io.yupiik.language.tokenizer.Token;
import io.yupiik.language.tokenizer.Tokenizer;
import io.yupiik.language.tokenizer.languages.GenericTokenizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongBiFunction;

/**
 * TamboUI adapter for the shared semantic syntax highlighter. Tokenization is deliberately
 * line-by-line to retain line-cache reuse and the existing lack of cross-line lexical context.
 * Unknown languages use generic highlighting; explicit plain-text aliases stay unstyled.
 */
public class SyntaxHighlighter {

    // Fenced code blocks are located by a linear line scanner ({@link #scanFences})
    // instead of a DOTALL-lazy backreference regex. The old `(?<body>.*?)` pattern
    // matched the whole region between the opening and closing fence by expanding
    // one character at a time and re-testing the anchored close fence over the whole
    // remaining tail on every step - quadratic on large fenced bodies (the busy path
    // is exactly a long `---text``` tool output block). The scanner walks the region
    // once line by line, so every character is examined a bounded number of times.
    // Semantics are preserved: a fence closes only on its own delimiter, an info
    // string may follow the language tag, and an opener with no close stays plain text.

    private static final int DEFAULT_MAX_LINE_LENGTH = 10_000;
    private static final int MAX_SPLIT_CACHE = 32;
    private static final int MAX_BLOCK_CACHE = 64;
    private static final int MAX_LINE_CACHE = 4_096;
    private static final long MAX_SPLIT_WEIGHT = 1L << 20;
    private static final long MAX_BLOCK_WEIGHT = 4L << 20;
    private static final long MAX_LINE_WEIGHT = 2L << 20;

    private static final io.yupiik.language.tokenizer.SyntaxHighlighter TOKENIZER =
            new io.yupiik.language.tokenizer.SyntaxHighlighter();
    private final Tokenizer fallback = new GenericTokenizer();

    private volatile int maxLineLength = DEFAULT_MAX_LINE_LENGTH;
    private volatile CodeTheme theme = CodeTheme.ONE_DARK;

    // memoized tokenization results: rendering reruns the same stable transcript
    // blocks every frame (live thinking grows append-only), so caching makes a
    // long session render in O(tail) instead of O(transcript). Keys are plain
    // strings/records, values are immutable lines/spans safe to share between
    // frames and threads. Both entry counts and estimated retained bytes are bounded.
    // We conservatively charge UTF-16 source/output characters even when strings are shared,
    // plus layout overhead (segments, lines, spans and their styles). This is not a heap census.

    private record LineKey(long generation, String language, String line) {}

    private record BlockKey(long generation, String language, String code) {}

    // bumped on every theme/maxLineLength change so an in-flight tokenization
    // computed with the previous settings can never be stored as "fresh" after
    // the cache was cleared (a clear-then-put race would otherwise cache stale
    // colors forever)
    private volatile long generation;

    private final Map<String, List<Segment>> splitCache = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<BlockKey, List<Line>> blockCache = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<LineKey, List<Span>> lineCache = new LinkedHashMap<>(16, 0.75f, true);
    // Guarded by the corresponding cache monitor, including resets and replacement/eviction.
    private long splitWeight;
    private long blockWeight;
    private long lineWeight;

    public record Segment(Type type, String language, String text) {
        public enum Type {
            TEXT,
            CODE
        }
    }

    public SyntaxHighlighter() {}

    /** Sets the token color theme (see {@link CodeTheme}). */
    public SyntaxHighlighter theme(final CodeTheme theme) {
        this.theme = theme == null ? CodeTheme.ONE_DARK : theme;
        clearCaches();
        return this;
    }

    public SyntaxHighlighter maxLineLength(final int maxLineLength) {
        this.maxLineLength = Math.max(1, maxLineLength);
        clearCaches();
        return this;
    }

    /** Releases retained source and layout results; in-flight old generations are not cached. */
    public synchronized void clearCaches() {
        this.generation++;
        synchronized (splitCache) {
            splitCache.clear();
            splitWeight = 0;
        }
        synchronized (blockCache) {
            blockCache.clear();
            blockWeight = 0;
        }
        synchronized (lineCache) {
            lineCache.clear();
            lineWeight = 0;
        }
    }

    // Caller holds the cache monitor. Oversized results must not evict useful small entries.
    private static <K, V> long weightedPut(
            final Map<K, V> cache,
            final K key,
            final V value,
            final long retained,
            final int maxEntries,
            final long maxWeight,
            final ToLongBiFunction<K, V> weigh) {
        final var weight = weigh.applyAsLong(key, value);
        if (weight > maxWeight) {
            return retained;
        }
        var total = retained + weight;
        final var previous = cache.put(key, value);
        if (previous != null) {
            total -= weigh.applyAsLong(key, previous);
        }
        final var entries = cache.entrySet().iterator();
        while (cache.size() > maxEntries || total > maxWeight) {
            final var eldest = entries.next();
            total -= weigh.applyAsLong(eldest.getKey(), eldest.getValue());
            entries.remove();
        }
        return total;
    }

    private static long splitWeight(final String source, final List<Segment> segments) {
        long weight = 64L + 2L * source.length();
        for (final var segment : segments) {
            weight += 64L + 2L * segment.text().length();
            if (segment.language() != null) {
                weight += 2L * segment.language().length();
            }
        }
        return weight;
    }

    private static long spansWeight(final List<Span> spans) {
        long weight = 32;
        for (final var span : spans) {
            weight += 128L + 2L * span.content().length();
        }
        return weight;
    }

    private static long blockWeight(final BlockKey key, final List<Line> lines) {
        long weight = 64L + 2L * key.code().length() + 2L * key.language().length();
        for (final var line : lines) {
            weight += 64L + spansWeight(line.spans());
        }
        return weight;
    }

    private static long lineWeight(final LineKey key, final List<Span> spans) {
        return 64L + 2L * key.language().length() + 2L * key.line().length() + spansWeight(spans);
    }

    public List<Segment> split(final String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        synchronized (splitCache) {
            return splitLocked(markdown);
        }
    }

    private List<Segment> splitLocked(final String markdown) {
        final var cached = splitCache.get(markdown);
        if (cached != null) {
            return cached;
        }
        // Full linear fence scan (no backtracking). The streaming frame cost is O(n) for the
        // changed region; stable regions hit splitCache, and unchanged frames are short-circuited
        // by ContentKey before split is even called, so the transcript never re-parses wholesale.
        final var scanned = scanFences(markdown);
        if (!scanned.pendingTail.isBlank()) {
            scanned.committed.add(new Segment(Segment.Type.TEXT, null, scanned.pendingTail));
        }
        final var immutable = List.copyOf(scanned.committed);
        splitWeight = weightedPut(
                splitCache,
                markdown,
                immutable,
                splitWeight,
                MAX_SPLIT_CACHE,
                MAX_SPLIT_WEIGHT,
                SyntaxHighlighter::splitWeight);
        return immutable;
    }

    /**
     * @param committed   segments up to the last closed fence
     * @param pendingTail text after the last closed fence (may turn into CODE later)
     */
    private record ScanResult(List<Segment> committed, String pendingTail) {}

    /**
     * Linear, backtracking-free fence scan: walks the region line by line once, committing
     * a {@code TEXT} segment for the text before a closing fence and a {@code CODE} segment for
     * each fenced block. Every character is examined a bounded number of times, so the cost is
     * O(n) for the whole region regardless of fence count or body size.
     */
    private static ScanResult scanFences(final String markdown) {
        if (markdown.indexOf('`') < 0 && markdown.indexOf('~') < 0) {
            // no fence marker anywhere: a single pending TEXT region, no committed segment
            return new ScanResult(new ArrayList<>(), markdown);
        }
        final var committed = new ArrayList<Segment>();
        final var n = markdown.length();
        var textStart = 0;
        // monotonic cursors: the first unscanned candidate line start for each delimiter's
        // closing fence. close searches only move forward, so the whole scan is O(n).
        int btCursor = 0;
        int ttCursor = 0;
        var lineStart = 0;
        while (lineStart < n) {
            final var nl = markdown.indexOf('\n', lineStart);
            final var lineEnd = nl < 0 ? n : nl;
            // an opener needs a newline-terminated line (a trailing "```" without "\n" never opens)
            if (nl >= 0) {
                final var delim = fenceDelim(markdown, lineStart, lineEnd);
                if (delim != 0) {
                    final var lang = fenceLang(markdown, lineStart, lineEnd);
                    final var bodyStart = nl + 1;
                    final var cursor = delim == '`' ? btCursor : ttCursor;
                    final var closeStart = Math.max(cursor, bodyStart) >= n
                            ? -1
                            : nextClose(markdown, delim, Math.max(cursor, bodyStart), n);
                    if (delim == '`') {
                        btCursor = closeStart < 0 ? n : closeLineEnd(markdown, closeStart);
                    } else {
                        ttCursor = closeStart < 0 ? n : closeLineEnd(markdown, closeStart);
                    }
                    if (closeStart >= 0) {
                        if (textStart < lineStart
                                && !markdown.substring(textStart, lineStart).isBlank()) {
                            committed.add(
                                    new Segment(Segment.Type.TEXT, null, markdown.substring(textStart, lineStart)));
                        }
                        committed.add(new Segment(
                                Segment.Type.CODE, lang, codeBody(markdown.substring(bodyStart, closeStart))));
                        final var closeEnd = closeLineEnd(markdown, closeStart);
                        textStart = closeEnd;
                        lineStart = closeEnd;
                        continue;
                    }
                    // opener never closes: it is plain text; keep scanning for a later fence
                    lineStart = lineEnd + 1;
                    continue;
                }
            }
            lineStart = lineEnd + 1;
        }
        return new ScanResult(committed, markdown.substring(textStart));
    }

    /** Returns the opening-fence delimiter (`` ` `` or `~`) if the line opens a fence, else 0. */
    private static char fenceDelim(final String md, final int start, final int end) {
        var k = start;
        while (k < end && (md.charAt(k) == ' ' || md.charAt(k) == '\t')) {
            k++;
        }
        if (k + 3 <= end) {
            final var c = md.charAt(k);
            if ((c == '`' || c == '~') && md.charAt(k + 1) == c && md.charAt(k + 2) == c) {
                return c;
            }
        }
        return 0;
    }

    /** Language tag of a fence-opening line (the run of {@code [A-Za-z0-9_+.-]} right after the delimiter). */
    private static String fenceLang(final String md, final int start, final int end) {
        var k = start;
        while (k < end && (md.charAt(k) == ' ' || md.charAt(k) == '\t')) {
            k++;
        }
        k += 3; // skip the delimiter
        final var langStart = k;
        while (k < end && isLangChar(md.charAt(k))) {
            k++;
        }
        return md.substring(langStart, k);
    }

    private static boolean isLangChar(final char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_'
                || c == '+'
                || c == '.'
                || c == '-';
    }

    /**
     * Char offset right after the closing-fence line (or the region end when it is the last line).
     * The closing fence is {@code [ \t]*<delim><delim><delim>[ \t]*} alone on its line.
     */
    private static int closeLineEnd(final String md, final int closeLineStart) {
        final var nl = md.indexOf('\n', closeLineStart);
        return nl < 0 ? md.length() : nl + 1;
    }

    /** Whether the line starting at {@code lineStart} is a closing fence for {@code delim}. */
    private static boolean isCloseLine(final String md, final int lineStart, final int lineEnd, final char delim) {
        int a = lineStart;
        while (a < lineEnd && (md.charAt(a) == ' ' || md.charAt(a) == '\t')) {
            a++;
        }
        int b = lineEnd;
        while (b > a && (md.charAt(b - 1) == ' ' || md.charAt(b - 1) == '\t')) {
            b--;
        }
        return b - a == 3 && md.charAt(a) == delim && md.charAt(a + 1) == delim && md.charAt(a + 2) == delim;
    }

    /** Start of the next closing-fence line of {@code delim} at-or-after {@code from}, or -1 if none. */
    private static int nextClose(final String md, final char delim, final int from, final int n) {
        var line = from;
        while (line < n) {
            final var nl = md.indexOf('\n', line);
            final var le = nl < 0 ? n : nl;
            if (isCloseLine(md, line, le, delim)) {
                return line;
            }
            line = le + 1;
        }
        return -1;
    }

    /**
     * The captured fence body ends with the newline that terminates its last line; highlighting it
     * verbatim renders a phantom blank row under the block, so that single terminator is dropped.
     * An intentional empty last code line ({@code "code\n\n"}) keeps its own row, and the blank row
     * that really separates the block from its neighbours is the one the segment walk inserts.
     */
    private static String codeBody(final String body) {
        return body != null && body.endsWith("\n") ? body.substring(0, body.length() - 1) : body;
    }

    public Text highlight(final String lang, final String code) {
        return Text.from(highlightLines(lang, code));
    }

    /** Same tokenization as {@link #highlight(String, String)} but as a line list (cards/overlay friendly). */
    public List<Line> highlightLines(final String lang, final String code) {
        if (code == null) {
            return List.of();
        }
        final var generation = this.generation;
        final var theme = this.theme;
        final var maxLineLength = this.maxLineLength;
        final var key = new BlockKey(generation, lang == null ? "" : lang, code);
        List<Line> cached;
        synchronized (blockCache) {
            cached = blockCache.get(key);
        }
        if (cached != null) {
            return cached;
        }
        final var resolved = TOKENIZER.resolveLanguage(lang);
        final var language = resolved == null ? "generic" : resolved;
        final var lines = code.replace("\r\n", "\n").split("\n", -1);
        final var result = new ArrayList<Line>(lines.length);
        for (final var line : lines) {
            result.add(Line.from(
                    colored(language, line, generation, theme, maxLineLength).toArray(Span[]::new)));
        }
        final var immutable = List.copyOf(result);
        synchronized (blockCache) {
            if (generation == this.generation) {
                blockWeight = weightedPut(
                        blockCache,
                        key,
                        immutable,
                        blockWeight,
                        MAX_BLOCK_CACHE,
                        MAX_BLOCK_WEIGHT,
                        SyntaxHighlighter::blockWeight);
            }
        }
        return immutable;
    }

    private List<Span> colored(
            final String language,
            final String line,
            final long generation,
            final CodeTheme theme,
            final int maxLineLength) {
        if (line.length() > maxLineLength || line.isBlank()) {
            return List.of(Span.raw(line));
        }
        final var cacheKey = new LineKey(generation, language, line);
        List<Span> cached;
        synchronized (lineCache) {
            cached = lineCache.get(cacheKey);
        }
        if (cached != null) {
            return cached;
        }
        final var tokens = "generic".equals(language) ? fallback.tokenize(line) : TOKENIZER.highlight(language, line);
        final var out = new ArrayList<Span>(tokens.size());
        for (final var token : tokens) {
            out.add(span(token, theme));
        }
        final var immutable = List.copyOf(out);
        synchronized (lineCache) {
            if (generation == this.generation) {
                lineWeight = weightedPut(
                        lineCache,
                        cacheKey,
                        immutable,
                        lineWeight,
                        MAX_LINE_CACHE,
                        MAX_LINE_WEIGHT,
                        SyntaxHighlighter::lineWeight);
            }
        }
        return immutable;
    }

    private static Span span(final Token token, final CodeTheme theme) {
        final var color =
                switch (token.kind()) {
                    case STRING -> theme.string();
                    case COMMENT -> theme.comment();
                    case NUMBER -> theme.number();
                    case KEYWORD -> theme.keyword();
                    case TYPE -> theme.type();
                    case TAG -> theme.tag();
                    case ATTRIBUTE -> theme.attr();
                    case ANNOTATION -> theme.annotation();
                    case OPERATOR, PLAIN -> null;
                };
        return color == null ? Span.raw(token.text()) : Span.styled(token.text(), Style.EMPTY.fg(Color.hex(color)));
    }

    /** Maps a file path to a canonical language id, or {@code null} when unknown. */
    public static String guessLanguage(final String path) {
        return TOKENIZER.guessLanguage(path);
    }
}
