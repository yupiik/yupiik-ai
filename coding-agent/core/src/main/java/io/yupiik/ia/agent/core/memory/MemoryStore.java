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
package io.yupiik.ia.agent.core.memory;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Pattern;

@ApplicationScoped
public class MemoryStore {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** The header line of {@link #HEADER}: filtered out of the prompt sections (it is not a fact). */
    public static final String HEADER_LINE = "Durable facts, decisions and preferences. One bullet per line.";

    public static final String HEADER = "# Memory\n\n" + HEADER_LINE + "\n\n";

    private final CodingAgentConfiguration configuration;

    /**
     * Cached content fingerprint of the memory files, protected by a last-modified/size guard so an
     * external edit (which bypasses {@link #append}) also invalidates it. Lets the agent loop detect
     * a mid-turn {@code memory_write} without re-hashing the files at every LLM round.
     */

    /**
     * Tokenized memory corpus cache (access-order LRU, so unchanged content costs one {@code get}
     * per turn instead of a full re-tokenization). Keyed by the actual scoped content read from
     * disk on every {@link #promptSection(String)} call: any change to the memory files - including
     * external edits outside {@link #append} - produces a key mismatch and a safe recompute, so the
     * cache can never return a stale corpus.
     */
    private final Map<String, Corpus> corpusCache = new LinkedHashMap<>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, Corpus> eldest) {
            return size() > 4;
        }
    };

    public MemoryStore(final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
    }

    protected MemoryStore() {
        this.configuration = null;
    }

    /**
     * Cached fingerprint of the current memory content (global + project), recomputed only when
     * the files change (detected through last-modified/size, so in-store writes and external edits
     * both invalidate it). A never-written store is stable across calls.
     */
    public int version(final Path root) {
        return Objects.hash(readGlobal(), readProject(root));
    }

    private static long mtime(final Path file) {
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : -1;
        } catch (final IOException e) {
            return Long.MIN_VALUE; // unreadable: always report a change, recompute is the safe side
        }
    }

    private static long size(final Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) : -1;
        } catch (final IOException e) {
            return Long.MIN_VALUE;
        }
    }

    public Path globalFile() {
        return Path.of(configuration.resolveStorageHome(), "memory", "MEMORY.md");
    }

    /** Project memory file for a specific session working directory (per-session root). */
    public static Path projectFile(final Path root) {
        return Objects.requireNonNull(root, "workdir")
                .resolve(".yca")
                .resolve("memory")
                .resolve("MEMORY.md");
    }

    public String readGlobal() {
        return read(globalFile());
    }

    /** Reads the project memory under the given session root, or an empty string. */
    public String readProject(final Path root) {
        return read(projectFile(root));
    }

    /**
     * Builds the {@code <memory>} section for the system prompt.
     * <p>
     * When a {@code prompt} is provided AND the combined memory exceeds the configured budget, the least
     * relevant project entries (ranked with a lightweight BM25-style overlap against the prompt) are dropped
     * first, while global facts are always kept. This bounds in-context memory cost without ever silently
     * truncating high-value durable directives. When no prompt is available (eg. a context dump or resume),
     * the full memory is returned capped at the configured budget, keeping the tail (most recent) lines.
     *
     * @param prompt the user prompt used to rank relevance; may be null
     * @return the memory section, or empty when no memory is stored
     */
    /**
     * Builds the {@code <memory>} section with an explicit character budget.
     * {@code budget <= 0} (memory auto-disabled, eg. when the resolved ratio budget
     * is too small) returns an empty section without reading the files.
     */
    /**
     * Root-aware variant for a session working on a specific project directory: the project memory
     * is read from {@code projectRoot/.yca/memory/MEMORY.md} instead of the process-wide working dir,
     * so each session gets its own project memory. {@code projectRoot == null} falls back to the
     * process-wide project file ({@link #promptSection(String, int)}).
     */
    public String promptSection(final String prompt, final int budget, final Path projectRoot) {
        return promptSection(prompt, budget, projectRoot, null);
    }

    /**
     * Root-aware variant that also receives the set of file paths the current turn is touching
     * ({@code currentPaths}, may be null/empty). Bullets whose {@code @path} anchor matches/prefixes
     * a current path get a relevance boost, surfacing the facts about the code being worked on.
     */
    public String promptSection(
            final String prompt, final int budget, final Path projectRoot, final List<String> currentPaths) {
        Objects.requireNonNull(projectRoot, "workdir");
        if (configuration == null || !configuration.memory().enabled() || budget < 500) {
            return "";
        }
        final var global = readGlobal();
        final var project = readProject(projectRoot);
        if (global.isBlank() && project.isBlank()) {
            return "";
        }
        return buildMemorySection(global, project, prompt, budget, currentPaths);
    }

    private String buildMemorySection(
            final String global, final String project, final String prompt, final int budget) {
        return buildMemorySection(global, project, prompt, budget, null);
    }

    private String buildMemorySection(
            final String global,
            final String project,
            final String prompt,
            final int budget,
            final List<String> currentPaths) {
        final var max = budget;
        final var globalText = withScope("global", global);
        final var projectText = withScope("project", project);
        final var selected = select(globalText, projectText, prompt, max, currentPaths);
        final var sb = new StringBuilder("<memory>\n");
        if (!selected.global().isBlank()) {
            sb.append("<global>\n").append(selected.global()).append("\n</global>\n");
        }
        if (!selected.project().isBlank()) {
            sb.append("<project>\n").append(selected.project()).append("\n</project>\n");
        }
        sb.append("</memory>");
        return sb.toString();
    }

    private static String withScope(final String scope, final String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        // strip the markdown header/blank lines and the file header line (not a fact),
        // keep only the durable bullet lines, deduplicated on their bare text (the metadata
        // envelope is stripped here and never shown to the model)
        final var seen = new LinkedHashSet<String>();
        for (final var line : content.lines().toList()) {
            final var s = line.strip();
            if (s.isEmpty()
                    || s.startsWith("#")
                    || HEADER_LINE.equals(s)
                    || "Memory".equals(s)
                    || ("-" + HEADER_LINE).equals(s)) {
                continue;
            }
            final var rendered = "- " + textOf(s);
            seen.add(rendered);
        }
        return String.join("\n", seen) + (seen.isEmpty() ? "" : "\n");
    }

    private static final int MARKUP_OVERHEAD = 57;

    private ScopeSelection select(
            final String globalText,
            final String projectText,
            final String prompt,
            final int max,
            final List<String> currentPaths) {
        if (max <= 0) {
            return new ScopeSelection("", "");
        }
        // Both scopes get the same treatment: rank every bullet against the query (the latest
        // user turn + todo items), keep the top scorers per scope, then spill the remaining
        // budget to whichever scope has the most relevant leftovers. Global keeps a small
        // recency tie-break (its file is the flat append-only store where the newest facts
        // sit at the tail and would otherwise be starved by the older head).
        final var query = prompt == null || prompt.isBlank() ? List.<String>of() : tokens(prompt);
        if (query.isEmpty()) {
            // no ranking signal (context dump, resume, bare prompt): keep the head of each scope,
            // capped at half the budget - deterministic and still bounded.
            return new ScopeSelection(
                    bounded(globalText, max / 2, false),
                    bounded(projectText, max - (max / 2) - MARKUP_OVERHEAD, false));
        }
        final var globalEntries = ranked(globalText, query, true, currentPaths);
        final var projectEntries = ranked(projectText, query, false, currentPaths);
        final var gSb = new StringBuilder();
        final var pSb = new StringBuilder();
        final var half = max / 2;
        final var usedAfterFirst = fill(globalEntries, gSb, half) + fill(projectEntries, pSb, half);
        final var remaining = max - usedAfterFirst - MARKUP_OVERHEAD - (gSb.length() > 0 && pSb.length() > 0 ? 1 : 0);
        if (remaining > 0) {
            // spillover: global first (directives are usually the higher-value), then project
            final var afterGlobal = fill(globalEntries, gSb, half + remaining) + pSb.length();
            fill(projectEntries, pSb, half + remaining + (afterGlobal - usedAfterFirst));
        }
        // durable-directive floor: even when no global bullet matches the query, keep the head of
        // the global scope (the most established directives) so global memory is never fully
        // dropped - only its stale tail is. This preserves the pre-ranking behavior when there is
        // no query signal while still favoring relevance when there is one.
        if (gSb.isEmpty() && !globalText.isBlank()) {
            gSb.append(bounded(globalText, Math.min(globalText.length(), half * 2 / 3), false));
        }
        return new ScopeSelection(gSb.toString(), pSb.toString());
    }

    /** Deterministic ranked entries: score desc, then file line index asc (stable). */
    private List<Entry> ranked(
            final String text, final List<String> query, final boolean recencyBoost, final List<String> currentPaths) {
        if (text == null || text.isBlank() || query.isEmpty()) {
            return List.of();
        }
        final var ranked = rank(text, query, currentPaths);
        if (!recencyBoost && currentPaths == null) {
            return ranked;
        }
        // The @path anchor boost: a bullet whose @path matches/prefixes a current file or package
        // (the code being worked on) is surfaced regardless of sentence similarity - that is the
        // "memory relevant to the code under the cursor" win. Score multiplier when anchored.
        final var anchored = currentPaths == null || currentPaths.isEmpty() ? null : currentPaths;
        final var lineDates =
                corpus(text).lines().stream().map(l -> metaOf(l).date()).toList();
        final var today = LocalDate.now();
        final var boosted = new ArrayList<Entry>(ranked.size());
        for (final var entry : ranked) {
            double score = entry.score();
            if (anchored != null) {
                final var path = metaOf(entry.line()).path();
                if (path != null) {
                    for (final var current : anchored) {
                        if (path.equals(current) || current.startsWith(path) || path.startsWith(current)) {
                            score *= 1.6;
                            break;
                        }
                    }
                }
            }
            if (recencyBoost) {
                // real freshness via the envelope date, not file position: a recently stamped
                // durable fact wins over an equally-relevant older one, without overriding relevance
                final var date = entry.index() < lineDates.size() ? lineDates.get(entry.index()) : today;
                final var ageDays = Math.min(30, ChronoUnit.DAYS.between(date, today));
                score *= 1 + 0.02 * (30 - ageDays);
            }
            boosted.add(new Entry(entry.line(), score, entry.index()));
        }
        boosted.sort(Comparator.comparingDouble(Entry::score).reversed().thenComparingInt(Entry::index));
        return boosted;
    }

    /** Adds entries until the budget is exhausted; returns the chars consumed. */
    private int fill(final List<Entry> entries, final StringBuilder sb, final int budget) {
        if (budget <= 0) {
            return sb.length();
        }
        var remaining = budget;
        for (final var entry : entries) {
            final var line = entry.line();
            final var add = line.length() + (sb.length() == 0 ? 0 : 1);
            if (add > remaining) {
                break;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
            remaining -= add;
        }
        return sb.length();
    }

    private static String bounded(final String content, final int limit, final boolean tail) {
        if (content == null || content.isBlank() || limit <= 0) {
            return "";
        }
        if (content.length() <= limit) {
            return content;
        }
        return tail ? content.substring(content.length() - limit) : content.substring(0, limit);
    }

    /**
     * Splits a durable fact longer than {@code maxLen} into a list of chunks, each at most
     * {@code maxLen} characters, breaking at word boundaries where possible. A single short fact is
     * returned as-is. Used so long extracted knowledge is never silently discarded.
     */
    private static List<String> wrapLine(final String line, final int maxLen) {
        if (line.length() <= maxLen) {
            return List.of(line);
        }
        final var out = new ArrayList<String>();
        final var words = line.split(" ");
        final var current = new StringBuilder();
        for (final var word : words) {
            if (!current.isEmpty() && current.length() + 1 + word.length() > maxLen) {
                out.add(current.toString());
                current.setLength(0);
            }
            if (current.isEmpty()) {
                current.append(word);
            } else {
                current.append(' ').append(word);
            }
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_]+");

    private static List<String> tokens(final String text) {
        final var m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        final var list = new ArrayList<String>();
        while (m.find()) {
            list.add(m.group());
        }
        return list;
    }

    /**
     * In-band metadata attached to a memory bullet, kept file-internal and stripped before the
     * model ever sees it. Backward-compat is first-class: a pre-existing {@code - text} line is
     * valid, hand-written content - it is parsed leniently as {@code p=2 / today / no path / no
     * topic} and is NEVER rewritten just because it was read (only a real append/evict/rewrite
     * changes a file). A line is only treated as carrying an envelope when it unambiguously
     * matches the full {@code - <p>|<ISO-date>|<@path|blank>|<topic|blank>|<text>} shape.
     */
    record Meta(int p, LocalDate date, String path, List<String> topic) {
        static final int DEFAULT_P = 2;
        static final int KEEP_P = 5;

        static Meta plain() {
            return new Meta(DEFAULT_P, LocalDate.now(), null, List.of());
        }
    }

    /** A parsed bullet: the metadata envelope (possibly the plain default) plus the bare text. */
    private record MetaLine(Meta meta, String text) {}

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Parses a stored bullet into {@code (meta, text)}. A line that does not unambiguously start
     * with the envelope prefix {@code <1-5>|<ISO-date>|@?<path-or-blank>|<topic-or-blank>|}
     * (eg. a hand-written {@code - user prefers tabs}) is treated as plain content: {@code p=2},
     * today's date, no path, no topic, text = the whole line. The strict prefix check is what
     * keeps arbitrary {@code |}-containing prose from being mistaken for metadata.
     */
    static MetaLine parse(final String bullet) {
        final var s = bullet == null ? "" : bullet.strip();
        // strip the leading "- "
        final var text = s.startsWith("-") ? s.substring(1).strip() : s;
        final var meta = tryEnvelope(text);
        if (meta == null) {
            return new MetaLine(Meta.plain(), text);
        }
        return meta;
    }

    /**
     * Attempts to read an enveloped line (text WITHOUT the leading "- "). Returns null when the
     * line does not match the strict envelope shape (then it is plain content). The shape is
     * {@code <p>|<date>|@?<path>|<topic>|<text>} where {@code p} is 1..5, {@code date} ISO, the
     * path field may be blank or start with {@code @}, and the topic field is 0..n space tokens.
     */
    private static MetaLine tryEnvelope(final String text) {
        // find the 4th '|' (after p, date, path, topic) that delimits the text
        final var first = text.indexOf('|');
        if (first <= 0) {
            return null;
        }
        final var pToken = text.substring(0, first);
        final int p;
        if (pToken.length() == 1 && pToken.charAt(0) >= '1' && pToken.charAt(0) <= '5') {
            p = pToken.charAt(0) - '0';
        } else {
            return null;
        }
        final var second = text.indexOf('|', first + 1);
        if (second < 0) {
            return null;
        }
        final LocalDate date;
        try {
            date = LocalDate.parse(text.substring(first + 1, second), DATE_FMT);
        } catch (final RuntimeException e) {
            return null; // not an ISO date: plain content
        }
        final var third = text.indexOf('|', second + 1);
        if (third < 0) {
            return null;
        }
        final var rawPath = text.substring(second + 1, third);
        final String path;
        if (rawPath.isBlank()) {
            path = null;
        } else if (rawPath.startsWith("@")) {
            path = rawPath.substring(1).strip();
        } else {
            return null; // path field must be blank or @-prefixed
        }
        final var fourth = text.indexOf('|', third + 1);
        if (fourth < 0) {
            return null; // must have at least an empty topic then the text
        }
        final var rawTopic = text.substring(third + 1, fourth).strip();
        final var topic = rawTopic.isBlank() ? List.<String>of() : List.of(WHITESPACE.split(rawTopic));
        final var factText = text.substring(fourth + 1).strip();
        if (factText.isEmpty()) {
            return null; // an envelope needs real text
        }
        return new MetaLine(new Meta(p, date, path, topic), factText);
    }

    /** Serializes metadata + text into the stored bullet shape (including the leading "- "). */
    static String format(final Meta meta, final String text) {
        final var path = meta.path() == null || meta.path().isBlank()
                ? ""
                : "@" + meta.path().strip();
        final var topic = meta.topic() == null ? "" : String.join(" ", meta.topic());
        return "- "
                + meta.p()
                + '|'
                + meta.date().format(DATE_FMT)
                + '|'
                + path
                + '|'
                + topic
                + '|'
                + (text == null ? "" : text.strip());
    }

    /** The bare fact text of a stored bullet (envelope stripped); used for dedup and the model. */
    static String textOf(final String line) {
        return parse(line).text();
    }

    /** The metadata of a stored bullet, defaulting to the plain {@code p=2/today} on compat lines. */
    static Meta metaOf(final String line) {
        return parse(line).meta();
    }

    /** Whether the line unambiguously carries the metadata envelope (vs a plain hand-written line). */
    public static boolean isEnvelopedLine(final String line) {
        return line != null && isEnveloped(line.strip());
    }

    /**
     * Builds a stored-format bullet carrying explicit metadata (importance weight, optional
     * {@code @path} anchor and topic tokens), stamping today's date. Produced by the memory_write
     * tool and the extractor so their facts are weight/recall-aware instead of flat p2 bullets.
     *
     * @param p      importance weight 1..5 (5 = explicit keep directive)
     * @param topic  topic keywords (may be null/empty)
     * @param path   relative code path/package the fact is anchored to (may be null)
     * @param text   the fact
     * @return the stored line, or {@code null} when text is blank
     */
    public static String withMeta(final int p, final List<String> topic, final String path, final String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        final var clamped = p >= 1 && p <= 5 ? p : DEFAULT_PRIORITY_FALLBACK;
        final var normTopic = topic == null || topic.isEmpty()
                ? List.<String>of()
                : topic.stream().filter(t -> !t.isBlank()).toList();
        return format(new Meta(clamped, LocalDate.now(), path, normTopic), text);
    }

    private static final int DEFAULT_PRIORITY_FALLBACK = 2;

    /**
     * Returns the existing bullet line whose {@code textOf} most closely matches {@code newText}
     * (token-set Dice similarity {@code >= 0.6}), or {@code null}. Used by the compactor to
     * re-attach the metadata of the original bullet to a model-reworded one.
     */
    public static String bestMatch(final String newText, final List<String> existingLines) {
        final var newTokens = new HashSet<>(tokens(textOf(newText)));
        if (newTokens.size() < 2) {
            return null;
        }
        String best = null;
        double bestScore = 0;
        for (final var line : existingLines) {
            final var oldTokens = new HashSet<>(tokens(textOf(line)));
            if (oldTokens.size() < 2) {
                continue;
            }
            int common = 0;
            for (final var t : newTokens) {
                if (oldTokens.contains(t)) {
                    common++;
                }
            }
            final var dice = 2.0 * common / (oldTokens.size() + newTokens.size());
            if (dice >= 0.6 && dice > bestScore) {
                best = line;
                bestScore = dice;
            }
        }
        return best;
    }

    /**
     * Returns a stored bullet carrying the metadata of {@code originalLine} but the (reworded)
     * {@code newText}, keeping the original's priority/date/path/topic and the higher of the
     * original vs. default priority. Used by the compactor to re-attach metadata to a
     * model-reworded bullet. A compat {@code originalLine} (no envelope) keeps the plain default.
     */
    public static String keepMeta(final String originalLine, final String newText) {
        if (newText == null || newText.isBlank()) {
            return null;
        }
        final var old = parse(originalLine == null ? "" : originalLine).meta();
        final var p = Math.max(clampPriority(old.p(), Meta.DEFAULT_P), Meta.DEFAULT_P);
        final var date = old.date() == null ? LocalDate.now() : old.date();
        return format(new Meta(p, date, old.path(), old.topic()), newText.strip());
    }

    /**
     * Tokenized shape of a memory corpus, computed once per distinct content and cached (LRU).
     * {@code lineTokens} holds the tokens of each non-blank line (same order), {@code documentFrequency}
     * counts how many lines contain each token (presence, not frequency); both are used to score lines
     * in {@link #rank(String, List)} without any re-tokenization. Metadata is folded in: the {@code p}
     * weight and {@code @path} anchor per line are carried so ranking can weigh importance and code
     * anchoring, and topic tokens are included alongside the sentence tokens.
     */
    private record Corpus(
            List<String> lines,
            List<List<String>> lineTokens,
            List<Integer> lineP,
            List<String> linePath,
            Map<String, Integer> documentFrequency) {

        private static Corpus of(final String text) {
            final var lines = text.lines().filter(l -> !l.isBlank()).toList();
            final var lineTokens = new ArrayList<List<String>>(lines.size());
            final var lineP = new ArrayList<Integer>(lines.size());
            final var linePath = new ArrayList<String>(lines.size());
            final var df = new HashMap<String, Integer>();
            for (final var line : lines) {
                final var metaLine = parse(line);
                final var toks = tokens(metaLine.text());
                // topic tokens are indexed too, so a directive found by its topic (eg. "rounding")
                // surfaces even when the sentence wording differs from the query
                toks.addAll(metaLine.meta().topic());
                lineTokens.add(toks);
                lineP.add(metaLine.meta().p());
                linePath.add(metaLine.meta().path());
                final var seen = new HashSet<String>(toks.size());
                for (final var token : toks) {
                    if (seen.add(token)) {
                        df.merge(token, 1, Integer::sum);
                    }
                }
            }
            return new Corpus(lines, lineTokens, lineP, linePath, df);
        }
    }

    private synchronized Corpus corpus(final String content) {
        final var cached = corpusCache.get(content);
        if (cached != null) {
            return cached;
        }
        final var built = Corpus.of(content);
        corpusCache.put(content, built);
        return built;
    }

    private List<Entry> rank(
            final String projectText, final List<String> promptTokens, final List<String> currentPaths) {
        final var corpus = corpus(projectText);
        final var lines = corpus.lines();
        final var lineTokens = corpus.lineTokens();
        final var lineP = corpus.lineP();
        final var linePath = corpus.linePath();
        final var df = corpus.documentFrequency();
        final var pq = new PriorityQueue<Entry>(Comparator.comparingDouble(Entry::score));
        for (var i = 0; i < lines.size(); i++) {
            final var currentTokens = lineTokens.get(i);
            if (currentTokens.isEmpty()) {
                continue;
            }
            // BM25-lite: tf of shared tokens weighted by inverse rarity across the corpus (idf approx = log(1+N/nT))
            final var counts = new LinkedHashMap<String, Integer>();
            for (final var t : currentTokens) {
                counts.merge(t, 1, Integer::sum);
            }
            double score = 0;
            for (final var t : promptTokens) {
                final var tf = counts.getOrDefault(t, 0);
                if (tf > 0) {
                    final var idf = Math.log1p((double) lines.size() / Math.max(1, df.getOrDefault(t, 0)));
                    score += tf / (tf + 1.3) * idf;
                }
            }
            if (score > 0) {
                // importance weighting: a high-p directive scores above an equally-relevant fact
                final var p = lineP == null || i >= lineP.size() ? Meta.DEFAULT_P : lineP.get(i);
                score *= 0.5 + p / 5.0;
                pq.offer(new Entry(lines.get(i), score, i));
                if (pq.size() > 64) {
                    pq.poll();
                }
            }
        }
        final var all = new ArrayList<>(pq);
        all.sort(Comparator.comparingDouble(Entry::score).reversed().thenComparingInt(Entry::index));
        return all;
    }

    private record Entry(String line, double score, int index) {}

    private record ScopeSelection(String global, String project) {}

    /**
     * Direct, crash-safe on-disk write of facts into the given scope's file. Used by both the
     * {@code memory_write} TOOL (an explicit user "remember" directive must persist immediately)
     * and the automatic extractor. The session's prompt snapshot is frozen once, so writing the
     * file mid-session never rewrites the memory shown in the current prompt - it is durable for
     * the <em>next</em> session (or a resume) which rebuilds the snapshot.
     */
    /** Writes project facts under the session root; null retains the configured default. Global storage is unchanged. */
    public synchronized List<String> append(final boolean global, final List<String> facts, final Path root) {
        Objects.requireNonNull(root, "workdir");
        if (facts == null || facts.isEmpty()) {
            return List.of();
        }
        final var file = global ? globalFile() : projectFile(root);
        try {
            Files.createDirectories(file.getParent());
            final var existing = new LinkedHashSet<String>(existingLines(file));
            final var added = addFacts(global, existing, facts);
            if (!added.isEmpty()) {
                write(file, existing);
            }
            // deterministic floor: cap the store size per scope so memory cannot grow unbounded
            // even when the LLM-based compaction is disabled or below its size threshold.
            applyMaxLines(file, new LinkedHashSet<>(existing));
            return added;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Shared inner loop behind {@link #append}: normalizes, wraps and
     * fuzzy-dedups each fact against {@code existingBase} (in place) and returns the lines that
     * were genuinely added. Never touches the filesystem.
     */
    private List<String> addFacts(
            final boolean global, final LinkedHashSet<String> existingBase, final List<String> facts) {
        final var added = new ArrayList<String>();
        for (final var fact : facts) {
            final var line = fact.strip();
            if (line.isEmpty()) {
                continue;
            }
            // A fact may arrive either as plain text (-> default priority/today envelope) or already
            // carrying an envelope (from memory_write / the extractor). Existing plain lines in
            // the file are never treated as legacy: they round-trip untouched until a real write.
            final var meta = envelopeFrom(line);
            final var bareText = textOf(line);
            // Long durable facts are wrapped across bullets rather than silently dropped, so no
            // extracted knowledge is lost to the 500-char line cap. The envelope rides on the
            // first wrapped piece only.
            final var wrapped = wrapLine(bareText, 480);
            for (var i = 0; i < wrapped.size(); i++) {
                final var piece = wrapped.get(i);
                final var metaForPiece = i == 0 ? meta : Meta.plain();
                final var stored = format(metaForPiece, piece);
                final var replaced = fuzzyReplace(existingBase, stored);
                if (replaced != null) {
                    // a near-duplicate bullet was superseded by the new wording: keep the higher
                    // priority and any existing anchor/topic so a p5 directive is not demoted
                    existingBase.remove(replaced);
                    existingBase.add(stored);
                    added.add(stored);
                } else if (existingBase.add(stored)) {
                    added.add(stored);
                }
                // exact duplicate: already present, nothing to do
            }
        }
        return added;
    }

    /** The metadata to attach to a fact being appended: preserved when it already carries an envelope. */
    private Meta envelopeFrom(final String line) {
        final var parsed = parse(line);
        // the envelope is present (p in 1..5, ISO date, path field, topic, text): preserve it
        if (isEnveloped(line)) {
            return parsed.meta();
        }
        // a plain line: stamp it with the configured default priority and today on write
        final var p = clampPriority(configuration.memory().defaultPriority(), Meta.DEFAULT_P);
        return new Meta(p, LocalDate.now(), null, List.of());
    }

    /** Whether a line unambiguously carries the metadata envelope (used to avoid re-stamping compat lines). */
    private static boolean isEnveloped(final String line) {
        return tryEnvelope(line.startsWith("-") ? line.substring(1).strip() : line.strip()) != null;
    }

    private static int clampPriority(final int p, final int fallback) {
        if (p >= 1 && p <= 5) {
            return p;
        }
        return fallback >= 1 && fallback <= 5 ? fallback : 2;
    }

    /**
     * Caps the store at {@code memory.maxLines} per scope, evicting lowest priority first then
     * oldest date (never a hand-written plain line's text - only the order in which an over-cap
     * store is trimmed). When the count of {@code p==5} "keep" bullets exceeds
     * {@code memory.maxKeepLines}, the oldest keep bullet is demoted to p4 (not dropped) before the
     * normal eviction runs, so explicit directives stay protected yet bounded.
     */
    private void applyMaxLines(final Path file, final LinkedHashSet<String> lines) throws IOException {
        final var maxLines = configuration.memory().maxLines();
        final var maxKeep = configuration.memory().maxKeepLines();
        var current = lines;
        // keep-tier bound acts independently of maxLines: whenever more p5 "keep" bullets exist than
        // maxKeepLines, the oldest keep bullets are demoted to p4 (never dropped) so the protected
        // explicit-directive tier stays bounded even while the file is under the bullet cap.
        if (maxKeep > 0) {
            final var keepCount =
                    current.stream().filter(l -> metaOf(l).p() == Meta.KEEP_P).count();
            if (keepCount > maxKeep) {
                final var keepLines = current.stream()
                        .filter(l -> metaOf(l).p() == Meta.KEEP_P)
                        .sorted(Comparator.comparing((String l) -> metaOf(l).date())
                                .thenComparing(l -> l))
                        .toList();
                final var demote = keepLines.size() - maxKeep;
                final var demoted = new LinkedHashSet<String>(current);
                for (var i = 0; i < Math.min(demote, keepLines.size()); i++) {
                    final var old = keepLines.get(i);
                    demoted.remove(old);
                    final var m = metaOf(old);
                    demoted.add(format(new Meta(m.p() - 1, m.date(), m.path(), m.topic()), textOf(old)));
                }
                current = demoted;
            }
        }
        if (maxLines > 0 && current.size() > maxLines) {
            // evict lowest p first, then oldest: keep a window of the highest-signal facts rather
            // than the simple tail-cut, so importance outranks mere append order.
            final var evictOrder = current.stream()
                    .sorted(Comparator.comparingInt((String l) -> metaOf(l).p())
                            .thenComparing(l -> metaOf(l).date()))
                    .toList();
            final var toDrop = current.size() - maxLines;
            final var drop = new HashSet<>(evictOrder.subList(0, Math.min(toDrop, evictOrder.size())));
            final var capped = new LinkedHashSet<String>();
            for (final var line : current) {
                if (!drop.contains(line)) {
                    capped.add(line);
                }
            }
            current = capped;
        }
        if (!current.equals(lines)) {
            write(file, current);
        }
    }

    /** Stores a free-text memory block (eg. a compressed conversation snippet), split into bullet lines. */
    /** Root-aware free-text write, with the same normalization and bounds as {@link #appendText(boolean, String)}. */
    public synchronized List<String> appendText(final boolean global, final String text, final Path root) {
        Objects.requireNonNull(root, "workdir");
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return append(
                global,
                text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList(),
                root);
    }

    /**
     * Near-duplicate detection: when a bullet already covers the new fact (normalized token
     * Dice similarity {@code >= 0.8} with at least 4 shared tokens), the new wording supersedes
     * the old one and the existing bullet is returned for replacement. This keeps the store from
     * growing with reformulations ("supersedes" observations, reworded decisions) while never
     * merging distinct facts, thanks to the conservative token-overlap floor.
     */
    private static String fuzzyReplace(final LinkedHashSet<String> existing, final String normalized) {
        final var newTokens = set(tokens(textOf(normalized)));
        if (newTokens.size() < 4) {
            return null;
        }
        String best = null;
        double bestScore = 0;
        for (final var bullet : existing) {
            final var oldTokens = set(tokens(textOf(bullet)));
            if (oldTokens.size() < 4 || Math.min(oldTokens.size(), newTokens.size()) < 4) {
                continue;
            }
            int common = 0;
            for (final var t : newTokens) {
                if (oldTokens.contains(t)) {
                    common++;
                }
            }
            if (common < 4) {
                continue;
            }
            // Dice coefficient on token sets
            final var dice = 2.0 * common / (oldTokens.size() + newTokens.size());
            if (dice >= 0.8 && dice > bestScore) {
                best = bullet;
                bestScore = dice;
            }
        }
        return best;
    }

    private static Set<String> set(final List<String> tokens) {
        return new HashSet<>(tokens);
    }

    /**
     * Replaces the whole memory content (used by the compactor): writes the given
     * bullet lines atomically (keeping the file header). The previous file is kept
     * as a {@code .bak} sibling so a bad rewrite can be restored.
     *
     * @return the number of bytes written, or -1 when the rewrite was rejected
     */
    /** Root-aware atomic replacement, including a backup beside the selected memory file. */
    public synchronized long replace(final boolean global, final List<String> bullets, final Path root) {
        Objects.requireNonNull(root, "workdir");
        try {
            final var file = global ? globalFile() : projectFile(root);
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            final var content = new StringBuilder(HEADER);
            bullets.stream()
                    .map(b -> b.startsWith("-") ? b : "- " + b)
                    .forEach(b -> content.append(b).append('\n'));
            final var tmp = file.resolveSibling(file.getFileName() + ".tmp-"
                    + Thread.currentThread().getName().hashCode());
            Files.writeString(tmp, content.toString());
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (final IOException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
            return content.length();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(final Path file, final LinkedHashSet<String> lines) throws IOException {
        final var content = new StringBuilder(HEADER);
        lines.forEach(l -> content.append(l).append('\n'));
        final var tmp = file.resolveSibling(
                file.getFileName() + ".tmp-" + Thread.currentThread().getName().hashCode());
        Files.createDirectories(file.getParent());
        Files.writeString(tmp, content.toString());
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    private List<String> existingLines(final Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return List.of();
            }
            return Files.readAllLines(file).stream()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#") && !HEADER_LINE.equals(l))
                    .toList();
        } catch (final IOException e) {
            return List.of();
        }
    }

    private String read(final Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return "";
            }
            return Files.readString(file);
        } catch (final IOException e) {
            return "";
        }
    }
}
