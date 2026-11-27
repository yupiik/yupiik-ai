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

import io.yupiik.ia.agent.core.tools.FileMutation;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Captures bounded output of running and finished tool calls, keyed by the
 * {@code #N} ordinal embedded in the transcript tool headers, so the UI can
 * expand a truncated tool card into a full-screen overlay. Entries live in a
 * bounded FIFO ring (default 128 calls, 256 KiB of output each), optionally
 * bounded by a total character budget. Re-recording an existing ordinal
 * (stream chunks, the finish record) updates it in place without rotating the
 * eviction order, so a long-running tool cannot evict the rest of the ring
 * around itself. Pure and terminal-free so it is unit testable.
 */
public final class ToolOutputStore {
    /** Immutable tool output snapshot; {@code elapsedMs < 0} marks a still-running call. */
    public record Entry(String name, String args, String output, boolean failed, boolean pending, long elapsedMs) {
        public Entry {
            failed = failed || (elapsedMs >= 0 && !pending && BashExitStatus.failed(name, output));
        }

        /** Whether the call is still running (no final output/elapsed yet). */
        public boolean running() {
            return elapsedMs < 0;
        }

        /** Backward-compatible 5-arg form: a non-pending (terminal) tool result. */
        public Entry(
                final String name, final String args, final String output, final boolean failed, final long elapsedMs) {
            this(name, args, output, failed, false, elapsedMs);
        }
    }

    public static final int DEFAULT_MAX_ENTRIES = 128;
    public static final int DEFAULT_MAX_OUTPUT_CHARS = 256 * 1024;

    private static final String TRUNCATED = "\n... older output truncated";
    private static final int CHUNK_SIZE = 1024;

    private final int maxEntries;
    private final int maxOutputChars;
    private final long maxTotalChars;
    private final Map<Long, StoredEntry> entries = new HashMap<>();
    private final Deque<Long> order = new ArrayDeque<>();
    private long totalChars;
    private long latest = -1;
    private long maxOrdinalSeen = -1;
    private long revision;
    private final Map<Long, List<FileMutation>> captures = new HashMap<>();
    private long captureChars;

    /** Retains only exact display-safe captures; oversized/redacted content stays unavailable. */
    public void recordFiles(
            final long ordinal, final List<FileMutation> files, final UnaryOperator<String> displayPolicy) {
        if (!entries.containsKey(ordinal)) {
            return;
        }
        final var previous = captures.remove(ordinal);
        captureChars -= captureWeight(previous);
        final var retained = new ArrayList<FileMutation>();
        long chars = 0;
        for (final var file : files) {
            if (retained.size() >= maxEntries) {
                break;
            }
            final var path = displayPath(file.path(), displayPolicy);
            if (chars + path.toString().length() > maxOutputChars) {
                break;
            }
            var content = file.content();
            final var weight = content == null
                    ? 0L
                    : (long) (content.oldText() == null ? 0 : content.oldText().length())
                            + content.newText().length();
            if (!displaySafe(content, weight + chars + path.toString().length(), displayPolicy)) {
                content = null;
            }
            retained.add(new FileMutation(path, file.kind(), content));
            chars += path.toString().length() + (content == null ? 0 : weight);
        }
        captures.put(ordinal, List.copyOf(retained));
        captureChars += chars;
        revision++;
        evict();
    }

    private static Path displayPath(final Path path, final UnaryOperator<String> displayPolicy) {
        try {
            return Path.of(displayPolicy.apply(path.toString())).toAbsolutePath();
        } catch (final RuntimeException unavailable) {
            return path.getRoot().resolve("redacted-path");
        }
    }

    private boolean displaySafe(
            final FileMutation.Content content, final long weight, final UnaryOperator<String> displayPolicy) {
        if (content == null || weight > maxOutputChars) {
            return false;
        }
        try {
            return (content.oldText() == null || content.oldText().equals(displayPolicy.apply(content.oldText())))
                    && content.newText().equals(displayPolicy.apply(content.newText()));
        } catch (final RuntimeException unavailable) {
            return false;
        }
    }

    public List<FileMutation> files(final long ordinal) {
        return captures.getOrDefault(ordinal, List.of());
    }

    private static long captureWeight(final List<FileMutation> files) {
        if (files == null) {
            return 0;
        }
        long chars = 0;
        for (final var file : files) {
            chars += file.path().toString().length();
            if (file.content() != null) {
                chars += (file.content().oldText() == null
                                ? 0L
                                : file.content().oldText().length())
                        + file.content().newText().length();
            }
        }
        return chars;
    }

    /** Includes updates of an existing ordinal, not just insertions. */
    long revision() {
        return revision;
    }

    public ToolOutputStore() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_OUTPUT_CHARS, 0);
    }

    public ToolOutputStore(final int maxEntries, final int maxOutputChars) {
        this(maxEntries, maxOutputChars, 0);
    }

    public ToolOutputStore(final int maxEntries, final int maxOutputChars, final long maxTotalChars) {
        this.maxEntries = Math.max(1, maxEntries);
        this.maxOutputChars = Math.max(1, maxOutputChars);
        this.maxTotalChars = Math.max(0, maxTotalChars);
    }

    public void record(final long ordinal, final Entry entry) {
        if (ordinal < 0 || entry == null) {
            return;
        }
        revision++;
        var output = entry.output() == null ? "" : entry.output();
        if (output.length() > maxOutputChars) {
            output = output.substring(output.length() - maxOutputChars) + TRUNCATED;
        }
        final var stored =
                new Entry(entry.name(), entry.args(), output, entry.failed(), entry.pending(), entry.elapsedMs());
        final var replaced = entries.put(
                ordinal,
                new StoredEntry(stored, entry.output() != null && entry.output().length() > maxOutputChars));
        // Not a LinkedHashMap on purpose: the separate insertion deque lets us plug more
        // complex eviction policies later (pinning running ordinals, two-tier aging, LRU...),
        // not just the FIFO one. The guard below is the invariant an insertion-order
        // LinkedHashMap would give for free: re-recording an existing ordinal (stream
        // chunks, the finish record) updates it in place and must never rotate the
        // eviction order, otherwise a long-running tool evicts the whole ring around it.
        if (replaced == null) {
            order.addLast(ordinal);
            totalChars += output.length();
        } else {
            totalChars += output.length() - replaced.length();
        }
        if (ordinal > latest) {
            latest = ordinal;
        }
        if (ordinal > maxOrdinalSeen) {
            maxOrdinalSeen = ordinal;
        }
        evict();
    }

    /**
     * Appends a live fragment to an existing running call. Missing calls, finished
     * calls and null fragments are ignored. A missing LF/CR is restored, including
     * for an empty fragment. The newest {@link #maxOutputChars()} characters are
     * retained, plus the truncation marker when older output was discarded.
     * No immutable snapshot is built until {@link #get(long)} is called.
     */
    public void append(final long ordinal, final String fragment) {
        final var stored = entries.get(ordinal);
        if (fragment == null || stored == null || !stored.metadata.running()) {
            return;
        }
        revision++;
        final var previousLength = stored.length();
        stored.append(fragment, maxOutputChars);
        if (!fragment.endsWith("\n") && !fragment.endsWith("\r")) {
            stored.append("\n", maxOutputChars);
        }
        totalChars += stored.length() - previousLength;
        evict();
    }

    private void evict() {
        // FIFO eviction bound by the entry count and, when configured, by the total
        // retained characters: the oldest entries drop first until the budget fits.
        while (order.size() > maxEntries || (maxTotalChars > 0 && totalChars + captureChars > maxTotalChars)) {
            final var evicted = order.removeFirst();
            final var entryEvicted = entries.remove(evicted);
            captureChars -= captureWeight(captures.remove(evicted));
            if (entryEvicted != null) {
                totalChars -= entryEvicted.length();
            }
        }
    }

    /** @return a cached immutable snapshot for the given ordinal, or null. */
    public Entry get(final long ordinal) {
        final var stored = entries.get(ordinal);
        return stored == null ? null : stored.snapshot();
    }

    /** Releases all outputs and resets ordinal tracking. */
    public void clear() {
        revision++;
        entries.clear();
        captures.clear();
        captureChars = 0;
        order.clear();
        totalChars = 0;
        latest = -1;
        maxOrdinalSeen = -1;
    }

    private static final class StoredEntry {
        private final Entry metadata;
        private Entry cached;
        private Deque<StringBuilder> chunks;
        private int tailLength;
        private boolean truncated;

        private StoredEntry(final Entry entry, final boolean truncated) {
            metadata = new Entry(entry.name(), entry.args(), "", entry.failed(), entry.pending(), entry.elapsedMs());
            cached = entry;
            this.truncated = truncated;
            tailLength = entry.output().length() - (truncated ? TRUNCATED.length() : 0);
        }

        private int length() {
            return tailLength + (truncated ? TRUNCATED.length() : 0);
        }

        private void append(final String fragment, final int cap) {
            if (chunks == null) {
                chunks = new ArrayDeque<>();
                copy(cached.output(), 0, tailLength);
            }
            cached = null;
            // Trim before copying so even a huge incoming fragment cannot grow the
            // retained buffer beyond the cap. Never retain the producer's String.
            final int start = Math.max(0, fragment.length() - cap);
            final int added = fragment.length() - start;
            final long overflow = Math.max(0L, (long) tailLength + fragment.length() - cap);
            if (overflow > 0) {
                truncated = true;
                int discard = (int) Math.min(overflow, tailLength);
                while (discard > 0) {
                    final var first = chunks.getFirst();
                    final int removed = Math.min(discard, first.length());
                    if (removed == first.length()) {
                        chunks.removeFirst();
                    } else {
                        first.delete(0, removed);
                    }
                    discard -= removed;
                    tailLength -= removed;
                }
            }
            copy(fragment, start, fragment.length());
            tailLength += added;
        }

        private void copy(final String source, final int start, final int end) {
            int offset = start;
            while (offset < end) {
                if (chunks.isEmpty() || chunks.getLast().length() == CHUNK_SIZE) {
                    chunks.addLast(new StringBuilder(CHUNK_SIZE));
                }
                final var last = chunks.getLast();
                final int count = Math.min(end - offset, CHUNK_SIZE - last.length());
                last.append(source, offset, offset + count);
                offset += count;
            }
        }

        private Entry snapshot() {
            if (cached == null) {
                final var output = new StringBuilder(length());
                for (final var chunk : chunks) {
                    output.append(chunk);
                }
                if (truncated) {
                    output.append(TRUNCATED);
                }
                cached = new Entry(
                        metadata.name(),
                        metadata.args(),
                        output.toString(),
                        metadata.failed(),
                        metadata.pending(),
                        metadata.elapsedMs());
            }
            return cached;
        }
    }

    /** @return the highest recorded ordinal, or -1 when empty. */
    public long latestOrdinal() {
        return latest;
    }

    /**
     * @return the highest ordinal ever recorded, even if it was evicted since;
     *     lets the UI tell "captured then evicted" apart from "never captured".
     */
    public long maxOrdinalSeen() {
        return maxOrdinalSeen;
    }

    /** @return the configured per-entry output cap (characters). */
    public int maxOutputChars() {
        return maxOutputChars;
    }

    /** @return how many distinct tool calls are currently retained. */
    public int size() {
        return entries.size();
    }

    /** @return total characters currently retained across all entries (truncation markers included). */
    public long totalChars() {
        return totalChars + captureChars;
    }
}
