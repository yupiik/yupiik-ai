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
package io.yupiik.ia.agent.core.context;

import io.yupiik.ia.agent.core.configuration.CompressConfiguration;
import io.yupiik.ia.agent.core.configuration.ContextLimit;
import io.yupiik.ia.agent.core.llm.Message;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wire-level, model-authored range compression, following opencode-dcp semantics:
 * the model references stable message ids, writes the replacement summary itself, and the
 * covered span is replaced with a {@code [Compressed conversation section @bN@]} block only in
 * outgoing requests. The session transcript itself stays append-only.
 *
 * <p>Compression is deliberately separate from automatic compaction: it is a view over the
 * conversation that keeps the prompt small in steady state, while compaction still guards the
 * hard model window (see {@link ContextManager}).
 *
 * <p>Concurrency: stateful operations are guarded by a {@link Lock}. The render-path scrubbing
 * ({@link #stripEchoedRefs}, {@link #withholdDanglingPrefix}) is lock-free so a UI thread never
 * blocks on an agent turn; it only reads the immutable values published through volatile fields,
 * never the working list the agent thread keeps mutating in place.
 */
public final class ContextCompression {
    public static final String BLOCK_MARKER = "compressedBlock";
    private static final Pattern BLOCK_REF = Pattern.compile("@b([1-9]\\d*)@");
    private static final Pattern MESSAGE_REF = Pattern.compile("@?(?:m)?(\\d+)@?");
    private static final Pattern PROTECT_TAG = Pattern.compile("<protect>(.*?)</protect>", Pattern.DOTALL);
    /**
     * Minimum digit-run length for the evidence-gated glued-seam strip: short runs ("step 12",
     * "issue #42") are far too common in real prose to gate on, while the observed leaks embed a
     * 3-5 digit ref id (125, 10632, 12504) and compaction refs are monotonically large in practice.
     */
    static final int MIN_GLUED_SEAM_DIGITS = 3;

    /** The longest supported numeric ref token (Integer.MAX_VALUE has 10 digits). */
    private static final int MAX_REF_DIGITS = 10;

    /**
     * Upper bound on the closed-turn pairs surfaced by the {@code compress} unknown-ref observer and the
     * compression nudge guidance, keeping the bounded helper line (and the persisted tool fallback)
     * cheap. Oldest pairs are listed first, so a large session never dumps its whole history to the model.
     */
    public static final int MAX_GUIDANCE_REFS = 16;

    public enum Status {
        ACTIVE,
        SUPERSEDED,
        DECOMPRESSED,
        ABSORBED
    }

    /**
     * One compressed span. {@code startRef}/{@code endRef} are stable message references, not list
     * indexes, so appending messages never invalidates a block.
     */
    public record Block(
            String id,
            String topic,
            int startRef,
            int endRef,
            String summary,
            int summaryTokens,
            List<String> consumedBlockIds,
            Status status,
            String supersededBy,
            long createdAt) {}

    /** One model-requested range: inclusive stable message references plus the model-written summary. */
    public record Range(String startId, String endId, String summary) {}

    /** Result of a compress/decompress request, suitable to return to the model verbatim. */
    public record Result(boolean success, String message, String blockId) {}

    private final CompressConfiguration configuration;
    private final AtomicInteger nextRef = new AtomicInteger(1);
    private final AtomicInteger nextBlock = new AtomicInteger(1);
    private final Map<Message, Integer> refsByMessage = new IdentityHashMap<>();
    private final Map<Integer, Message> messagesByRef = new LinkedHashMap<>();
    private final List<Block> blocks = new ArrayList<>();
    /** Guards all mutable state below; reentrant because locked entry points call each other. */
    private final Lock lock = new ReentrantLock();
    /**
     * Live working list, owned by the agent thread. Kept by reference on purpose: the loop appends
     * to it in place between publishes, so it must never be iterated from another thread.
     */
    private volatile List<Message> published = List.of();
    /**
     * Complete seam tokens ({@code @N@}, {@code @bN@}, {@code @blocked@}) carried by the wire built
     * by the last {@link #renderRefs} call. Immutable and replaced wholesale, so render surfaces can
     * read it from any thread without touching {@link #published}.
     */
    private volatile Set<String> renderedRefs = Set.of("");
    /**
     * Optional subscriber notified when a {@code compress} call skipped at least one range because its
     * refs were not in the live conversation (the ref-lookup skip). Receives the bounded, guaranteed-
     * valid closed-turn pairs so a model that guessed wrong refs can be shown the real ones without
     * bloating the persisted transcript. Render-surface agnostic: {@link ContextCompression}
     * never mixes with a UI; the TUI/web subscribe through it.
     */
    private volatile Consumer<List<Range>> onUnknownRefs;

    public ContextCompression(final CompressConfiguration configuration) {
        this.configuration = configuration;
    }

    public boolean enabled() {
        return configuration != null && configuration.enabled();
    }

    /**
     * Highest reference that was ever assigned to a message (monotonic: refs are assigned once and
     * never reused, see {@link #refOf}), plus one. Every id in {@code [1, maxAssignedRef())} has
     * been assigned at some point; {@link #publish} pruning shrinks the live mapping but never this
     * high-water mark, so it is the complete, bounded evidence set for the glued-seam strip.
     * Lock-free on purpose: render surfaces may call it from any thread.
     */
    public int maxAssignedRef() {
        return nextRef.get();
    }

    public CompressConfiguration configuration() {
        return configuration;
    }

    /**
     * Publishes the live working list so the tool can resolve the references it rendered. The list
     * is kept by reference: the agent thread owns it and keeps appending in place, so cross-thread
     * readers must use {@link #renderedRefs} (refreshed by the next {@link #renderRefs}) instead.
     */
    public void publish(final List<Message> messages) {
        lock.lock();
        try {
            this.published = messages == null ? List.of() : messages;
            // the wire - hence the live seam set - is rebuilt by the next renderRefs; drop the
            // previous tags now so a ref that left the conversation can never be withheld
            this.renderedRefs = Set.of("");
            // memory bound: refs are assigned once and never reused, so pruning a map entry whose
            // Message left the wire only frees the retained object - it never renumbers anything
            // (see refOf). This mirrors ConversationTranscript.trim()'s history head-truncation.
            final var keep = Collections.newSetFromMap(new IdentityHashMap<Message, Boolean>());
            for (final var message : published) {
                if (message != null) {
                    keep.add(message);
                }
            }
            refsByMessage.keySet().retainAll(keep);
            messagesByRef.clear();
            for (final var entry : refsByMessage.entrySet()) {
                messagesByRef.put(entry.getValue(), entry.getKey());
            }
            // drop definitively-absorbed blocks so the blocks list stays bounded; ACTIVE/SUPERSEDED/
            // DECOMPRESSED blocks are still referenced by tools and stay.
            blocks.removeIf(b -> b.status() == Status.ABSORBED);
        } finally {
            lock.unlock();
        }
    }

    public List<Message> published() {
        lock.lock();
        try {
            return published;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Registers a listener invoked when a {@code compress} call skipped at least one range because its
     * refs were not in the live conversation. The listener receives the bounded, guaranteed-valid
     * closed-turn pair list (see {@link #compressibleClosedTurns()}). A null value clears the listener.
     */
    public void onUnknownRefs(final Consumer<List<Range>> listener) {
        this.onUnknownRefs = listener;
    }

    /** Returns whether this user message is a synthetic compaction/pruning digest. */
    private static boolean isDigestOnlyUserMessage(final Message message) {
        if (message == null || !"user".equals(message.role())) {
            return false;
        }
        final var content = message.content() instanceof String s ? s : String.valueOf(message.content());
        return content.startsWith("<compacted-context>") || content.startsWith("<pruned-context>");
    }

    /**
     * Stable reference of a message ({@code @1@}, {@code @2@}, ...), assigned once per message
     * identity and never reused. References start at 1; the leading system prompt is never tagged.
     */
    public int refOf(final Message message) {
        lock.lock();
        try {
            final var existing = refsByMessage.get(message);
            if (existing != null) {
                return existing;
            }
            final var ref = nextRef.getAndIncrement();
            refsByMessage.put(message, ref);
            messagesByRef.put(ref, message);
            return ref;
        } finally {
            lock.unlock();
        }
    }

    /** The message a reference points to, or null when unknown. */
    public Message messageOf(final int ref) {
        lock.lock();
        try {
            return messagesByRef.get(ref);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a copy of the wire messages with a small trailing {@code @N@} tag so the model
     * can name exact boundaries. System messages, synthetic compression blocks and markers are not
     * tagged; the tags are deterministic for a given message identity, so prompt prefixes stay stable.
     *
     * <p>The tag deliberately uses the compact {@code @N@} seam instead of a bracket-like
     * {@code [ref @N@]} line: bracket-shaped markers read as conversational text that flash-class
     * models tend to mirror into their own output (echoing them back into the transcript), while a
     * bare {@code @N@} marker has no natural-language shape to imitate, which strongly reduces the
     * echoed-refs failure mode (see {@link #stripEchoedRefs}).
     *
     * <p>Also publishes the exact seam tokens carried by the returned wire into
     * {@link #renderedRefs}, so render-time scrubbing keys off real refs without walking the live
     * working list.
     */
    public List<Message> renderRefs(final List<Message> messages) {
        return renderRefs(messages, false);
    }

    /**
     * Renders either the legacy per-message refs or only compressible turn boundaries.
     * Boundary refs retain the identities of the published messages, even after rewriting blocks.
     */
    public List<Message> renderRefs(final List<Message> messages, final boolean boundaryOnly) {
        return renderRefs(messages, boundaryOnly, true);
    }

    /** Preview the model-visible context without publishing seams or absorbing blocks. */
    public List<Message> project(final List<Message> messages, final boolean boundaryOnly) {
        lock.lock();
        final var originalRefs = new IdentityHashMap<>(refsByMessage);
        final var originalMessages = new HashMap<>(messagesByRef);
        final var originalNext = nextRef.get();
        try {
            return renderRefs(rewrite(messages, false), boundaryOnly, false);
        } finally {
            refsByMessage.clear();
            refsByMessage.putAll(originalRefs);
            messagesByRef.clear();
            messagesByRef.putAll(originalMessages);
            nextRef.set(originalNext);
            lock.unlock();
        }
    }

    private List<Message> renderRefs(final List<Message> messages, final boolean boundaryOnly, final boolean publish) {
        lock.lock();
        try {
            if (!enabled() || messages == null) {
                return messages;
            }
            final var out = new ArrayList<Message>(messages.size());
            final var refs = new HashSet<String>();
            refs.add("");
            // Boundary-only mode advertises each eligible fully-closed historical turn as one
            // explicit inclusive pair ("compactIds=start-end") on the user message that opens it,
            // so the model copies real boundaries instead of reconstructing them from scattered refs.
            // Legacy mode keeps the per-message "@N@" seams for strong/other models that opt out.
            final var closedTurns =
                    boundaryOnly ? collectCompressibleClosedTurns(List.of(), Integer.MAX_VALUE) : List.<ClosedTurn>of();
            final var compactIdsByMessage = new IdentityHashMap<Message, String>();
            for (final var turn : closedTurns) {
                if (messages != null && !messageVisible(messages, turn.startMessage())) {
                    continue;
                }
                compactIdsByMessage.put(turn.startMessage(), turn.startRef() + "-" + turn.endRef());
            }
            for (var i = 0; i < messages.size(); i++) {
                final var message = messages.get(i);
                if (i == 0
                        || "system".equals(message.role())
                        || isCompressedBlock(message)
                        || isVolatileTail(message)
                        || message.content() == null
                        || !("user".equals(message.role())
                                || "assistant".equals(message.role())
                                || "tool".equals(message.role()))) {
                    out.add(message);
                    continue;
                }
                final var content = message.content() instanceof String s ? s : String.valueOf(message.content());
                if (boundaryOnly) {
                    final var compactIds = compactIdsByMessage.get(message);
                    if (compactIds == null) {
                        out.add(message);
                        continue;
                    }
                    final var tag = "compactIds=" + compactIds;
                    refs.add(tag);
                    out.add(new Message(
                            message.role(),
                            content + "\n" + tag,
                            message.name(),
                            message.toolCalls(),
                            message.toolCallId(),
                            message.others()));
                    continue;
                }
                // compact seam: "@N@" rather than "[ref @N@]" - no bracket shape for the model to copy
                final var ref = refOf(message);
                refs.add("@" + ref + "@");
                final var tagged = content + "\n@" + ref + "@";
                out.add(new Message(
                        message.role(),
                        tagged,
                        message.name(),
                        message.toolCalls(),
                        message.toolCallId(),
                        message.others()));
            }
            if (publish) {
                renderedRefs = Set.copyOf(refs);
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Applies every active block to {@code messages}: each covered span becomes one synthetic user
     * message carrying the model's summary. The original list is never mutated.
     */
    public List<Message> rewrite(final List<Message> messages) {
        return rewrite(messages, true);
    }

    private List<Message> rewrite(final List<Message> messages, final boolean absorb) {
        lock.lock();
        try {
            if (!enabled() || blocks.isEmpty()) {
                return messages;
            }
            final var active = new ArrayList<Block>();
            for (final var block : blocks) {
                if (block.status() == Status.ACTIVE) {
                    active.add(block);
                }
            }
            if (active.isEmpty()) {
                return messages;
            }
            active.sort(Comparator.comparingInt(Block::startRef));
            final var out = new ArrayList<Message>(messages.size());
            var index = 0;
            for (final var block : active) {
                final var start = indexOf(messages, block.startRef(), index);
                final var end = indexOf(messages, block.endRef(), start);
                if (start < 0 || end < 0 || end < start) {
                    // the block boundaries are no longer in this list (e.g. a core compaction rewrote it)
                    if (absorb) {
                        if (absorb) {
                            markAbsorbed(block);
                        }
                    }
                    continue;
                }
                if (start < index) {
                    // overlapping active block: keep the earliest one, mark this one absorbed
                    markAbsorbed(block);
                    continue;
                }
                out.addAll(messages.subList(index, start));
                out.add(new Message(
                        "user",
                        "[Compressed conversation section @" + block.id() + "@]\n" + block.summary(),
                        null,
                        null,
                        null,
                        Map.of(BLOCK_MARKER, block.id())));
                index = end + 1;
            }
            if (index < messages.size()) {
                out.addAll(messages.subList(index, messages.size()));
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Compresses one or more non-overlapping closed ranges. A range that does not already start on a
     * user message is snapped forward to the nearest following user ref, and an end that is not at a
     * turn boundary is snapped backward to the nearest preceding boundary; a range the snaps cannot
     * rescue (start/end collide) is dropped, always returning a success with a corrective nudge to
     * the model rather than a hard rejection.
     */
    public Result compress(final String topic, final List<Range> ranges) {
        lock.lock();
        try {
            return compressLocked(topic, ranges);
        } finally {
            lock.unlock();
        }
    }

    /** Runs one compression request while {@link #lock} is held. */
    private Result compressLocked(final String topic, final List<Range> ranges) {
        if (!enabled()) {
            return new Result(false, "Compression is disabled (yca.compression.enabled=false).", null);
        }
        if (ranges == null || ranges.isEmpty()) {
            return new Result(false, "content must contain at least one {startId, endId, summary} range.", null);
        }
        final var resolved = new ArrayList<ResolvedRange>(ranges.size());
        final var nudges = new ArrayList<String>();
        final var skipped = new ArrayList<String>();
        // how many ranges failed purely because their refs were not in the live conversation; when any
        // range fails this way the observer (if any) is handed the valid closed-turn pairs so a
        // model that guessed wrong refs can see the real ones without bloating the transcript
        var refLookupSkips = 0;
        // how many ranges were dropped or absorbed because they overlapped an already-resolved range
        // from the same request (effective bounds); used to append the grounded closed-turn pairs to
        // the result message so a model that writes overlapping batches can re-anchor in one
        // round instead of being told the whole request failed
        var overlapSkips = 0;
        for (final var range : ranges) {
            if (range == null || range.startId() == null || range.endId() == null) {
                skipped.add("range without both startId and endId (message refs like @12@)");
                continue;
            }
            final var startRef = parseRef(range.startId());
            final var endRef = parseRef(range.endId());
            if (startRef <= 0 || endRef <= 0) {
                skipped.add("range " + range.startId() + ".." + range.endId() + ": invalid message ref");
                continue;
            }
            final var start = indexOf(published, startRef, 0);
            final var end = indexOf(published, endRef, 0);
            if (start < 0 || end < 0) {
                refLookupSkips++;
                final var missing = start < 0 && end < 0
                        ? "startId " + range.startId() + " and endId " + range.endId() + " are"
                        : (start < 0 ? "startId " + range.startId() : "endId " + range.endId()) + " is";
                skipped.add("range " + range.startId() + ".." + range.endId() + ": " + missing
                        + " not in the live conversation");
                continue;
            }
            if (end < start) {
                skipped.add("range " + range.startId() + ".." + range.endId()
                        + ": both endpoints exist, but endId precedes startId in conversation order");
                continue;
            }
            if (start == 0
                    || (published.get(start) != null
                            && "system".equals(published.get(start).role()))) {
                skipped.add(
                        "range " + range.startId() + ".." + range.endId() + ": the system prompt cannot be compressed");
                continue;
            }
            if (isVolatileTail(published.get(start)) || isVolatileTail(published.get(end))) {
                skipped.add(
                        "range " + range.startId() + ".." + range.endId() + ": touches the live session-state tail");
                continue;
            }
            // Snap the requested boundaries onto valid, safe ones instead of hard-rejecting a
            // sloppy flash-class boundary choice. A non-user START moves forward to the nearest
            // following user ref (the leading assistant/tool span is left verbatim - never user
            // content, and never detaching a tool result from its assistant caller); an END that is
            // not a turn boundary moves backward to the nearest preceding one. If the two snaps
            // collide (start >= end) or leave no valid range, the entry is dropped and the model is
            // nudged - never a hard failure.
            int effectiveStart = start;
            int effectiveEnd = end;
            final var startRole = published.get(start).role();
            if (!"user".equals(startRole)) {
                for (var i = start + 1; i <= end; i++) {
                    if ("user".equals(published.get(i).role())) {
                        effectiveStart = i;
                        break;
                    }
                }
            }
            if (!turnBoundary(end)) {
                for (var i = end - 1; i >= effectiveStart; i--) {
                    if (turnBoundary(i)) {
                        effectiveEnd = i;
                        break;
                    }
                }
            }
            final var drop = !"user".equals(published.get(effectiveStart).role())
                    || !turnBoundary(effectiveEnd)
                    || effectiveStart >= effectiveEnd;
            if (drop) {
                skipped.add("range " + range.startId() + ".." + range.endId()
                        + " cannot be bounded to a user-message start and a turn-boundary end"
                        + " (the requested span collapses)");
                continue;
            }
            final var effectiveStartRef = refOf(published.get(effectiveStart));
            final var effectiveEndRef = refOf(published.get(effectiveEnd));
            if (effectiveStartRef != startRef) {
                nudges.add("range " + range.startId() + ".." + range.endId() + " adjusted: start ref "
                        + range.startId() + " is " + startRole + "; compressed from @" + effectiveStartRef
                        + "@ instead - always start ranges on a user-message ref; if unsure, split"
                        + " into small disjoint ranges.");
            }
            if (effectiveEndRef != endRef) {
                nudges.add("range " + range.startId() + ".." + range.endId() + " adjusted: end ref "
                        + range.endId() + " was not at a turn boundary; compressed up to @" + effectiveEndRef
                        + "@ instead. End ranges just before the next user message (a turn boundary).");
            }
            if (configuration.turnProtection() && configuration.turnProtectionTurns() > 0) {
                final var protectedFrom = protectedTurnStart(configuration.turnProtectionTurns());
                if (protectedFrom >= 0 && effectiveEnd >= protectedFrom) {
                    skipped.add("range " + range.startId() + ".." + range.endId()
                            + " touches the last " + configuration.turnProtectionTurns()
                            + " protected user turn(s) (turnProtection)");
                    continue;
                }
            }
            if (overlapsActive(effectiveStartRef, effectiveEndRef)) {
                // a new range may cover an existing ACTIVE block only when it fully contains it AND
                // the summary summons it through its @bN@ placeholder - the block is then consumed
                // (superseded) instead of being applied twice by rewrite()
                final var overlapped = activeOverlappingBlocks(effectiveStartRef, effectiveEndRef);
                final var referenced = blockRefsIn(range.summary());
                final var rejected = overlapped.stream()
                        .filter(block -> block.startRef() < effectiveStartRef || block.endRef() > effectiveEndRef)
                        .findFirst()
                        .orElse(overlapped.stream()
                                .filter(block -> !referenced.contains(block.id()))
                                .findFirst()
                                .orElse(null));
                if (rejected != null) {
                    skipped.add("range " + range.startId() + ".." + range.endId()
                            + " overlaps active block @b" + rejected.id()
                            + "@ which must be fully contained by the new range and referenced"
                            + " through its @bN@ placeholder in the summary");
                    continue;
                }
            }
            // Resolve a same-request overlap deterministically instead of hard-aborting the batch,
            // keeping the "snap/skip with a corrective nudge, never fail" convention used for every
            // other bound problem in this loop. Array order is the only tie-break, matching the
            // ACTIVE-block precedence rewrite() applies to overlapping blocks. The resolution loops so
            // a broad covering range can absorb every narrower previously-resolved span it covers
            // (e.g. [1..6] sent after [1..2] and [5..6] absorbs both), guaranteeing that `resolved`
            // stays pairwise disjoint; if it still straddles a remaining range with no containment it
            // cannot be inserted disjointly and is dropped instead.
            var resolvable = true;
            var sawOverlap = false;
            while (resolvable) {
                final var overlapping = resolved.stream()
                        .filter(previous ->
                                effectiveStartRef <= previous.endRef && effectiveEndRef >= previous.startRef)
                        .findFirst();
                if (overlapping.isEmpty()) {
                    break; // disjoint from all already-resolved ranges -> add it below
                }
                if (!sawOverlap) {
                    sawOverlap = true;
                    overlapSkips++;
                }
                final var previous = overlapping.get();
                final var currentCovered =
                        previous.startRef() <= effectiveStartRef && previous.endRef() >= effectiveEndRef;
                final var currentCovers =
                        effectiveStartRef <= previous.startRef() && effectiveEndRef >= previous.endRef();
                if (currentCovered) {
                    // the current range adds nothing beyond the already-applied covering one
                    skipped.add("range " + range.startId() + ".." + range.endId()
                            + " is fully covered by @" + previous.startRef() + "@..@" + previous.endRef()
                            + "@ which was already applied; dropped the redundant covered range");
                    resolvable = false;
                    break;
                }
                if (currentCovers) {
                    // the current range is the broader one: absorb the narrower previous span - it is
                    // rewritten once by the covering summary instead of twice - then re-check the rest
                    resolved.remove(previous);
                    nudges.add("range " + range.startId() + ".." + range.endId()
                            + " absorbs the previously applied @" + previous.startRef() + "@..@"
                            + previous.endRef() + "@ into its covering span and rewrites it once, not twice");
                    continue;
                }
                // straddling overlap with no containment: the current range cannot be inserted
                // disjointly, so keep the earlier range (first-writer-wins, mirroring ACTIVE-block
                // precedence) and drop the later one so the batch still applies
                skipped.add("range " + range.startId() + ".." + range.endId()
                        + " straddles @" + previous.startRef() + "@..@" + previous.endRef()
                        + "@ with no containment; kept the earlier range and dropped this later"
                        + " overlapping one. Send disjoint ranges (each ending just before the next"
                        + " user message) instead.");
                resolvable = false;
                break;
            }
            if (resolvable) {
                resolved.add(new ResolvedRange(
                        effectiveStartRef, effectiveEndRef, range.summary() == null ? "" : range.summary()));
            }
        }

        final var created = new ArrayList<String>();
        for (final var range : resolved) {
            final var blockId = "b" + nextBlock.getAndIncrement();
            final var consumed = new ArrayList<String>();
            final var expanded = expandPlaceholders(range.summary(), range.startRef, range.endRef, blockId, consumed);
            final var summary = appendProtected(expanded, range.startRef, range.endRef);
            final var block = new Block(
                    blockId,
                    topic == null || topic.isBlank() ? "compressed range" : topic.strip(),
                    range.startRef,
                    range.endRef,
                    summary,
                    TokenEstimator.estimate(summary),
                    List.copyOf(consumed),
                    Status.ACTIVE,
                    null,
                    System.currentTimeMillis());
            blocks.add(block);
            created.add("@" + blockId + "@");
        }
        // Report candidates after this batch's successful ranges have become active blocks.
        final var refListener = onUnknownRefs;
        if (refLookupSkips > 0 && refListener != null) {
            refListener.accept(compressibleClosedTurns());
        }
        final var base = created.isEmpty()
                ? "No ranges were compressed."
                : "Compressed " + created.size() + " range(s): " + String.join(", ", created)
                        + ". The transcript is unchanged; these blocks are now applied to outgoing requests.";
        final var parts = new ArrayList<String>(2);
        final var outcome = created.isEmpty() ? "no_change" : skipped.isEmpty() ? "applied" : "partial_success";
        parts.add("outcome: " + outcome + "\ncreated: " + created.size() + "\nskipped: " + skipped.size());
        parts.add(base);
        if (!skipped.isEmpty()) {
            parts.add("skipped: " + String.join("; ", skipped) + ".");
            parts.add("Skipped ranges were not applied. Do not retry rejected ranges unchanged.");
        }
        if (!nudges.isEmpty()) {
            parts.add(String.join("\n", nudges));
        }
        if (!skipped.isEmpty() || overlapSkips > 0) {
            final var candidates = compressibleClosedTurns();
            if (candidates.isEmpty()) {
                parts.add(
                        "No structurally eligible historical ranges are available. Continue the current task;"
                                + " do not guess references or run a whole-session compact just to meet the working-context target.");
            } else {
                final var refsText = new StringBuilder("compressible inclusive ranges: ");
                for (var i = 0; i < candidates.size(); i++) {
                    if (i > 0) {
                        refsText.append(", ");
                    }
                    refsText.append(candidates.get(i).startId())
                            .append('-')
                            .append(candidates.get(i).endId());
                }
                final var omitted = compressibleClosedTurnCount() - candidates.size();
                if (omitted > 0) {
                    refsText.append(" (+").append(omitted).append(" omitted)");
                }
                refsText.append(" - use exact startId/endId only for genuinely finished work."
                        + " Do not substitute these IDs into old summaries. Verify the content of a current pair"
                        + " and write a summary for that span; if its content is unavailable or the work is unfinished,"
                        + " continue the current task.");
                parts.add(refsText.toString());
            }
        }
        final var message = parts.size() == 1 ? parts.get(0) : String.join("\n", parts);
        return new Result(
                true, message, created.isEmpty() ? null : created.get(0).replace("@", ""));
    }

    /** Re-enables a block (only while it is still active; superseded/absorbed blocks are gone). */
    public Result decompress(final String blockId) {
        lock.lock();
        try {
            if (!enabled()) {
                return new Result(false, "Compression is disabled (yca.compression.enabled=false).", null);
            }
            final var id = normalizeBlockId(blockId);
            final var block =
                    blocks.stream().filter(b -> b.id().equals(id)).findFirst().orElse(null);
            if (block == null) {
                return new Result(false, "Unknown compressed block @" + id + "@.", null);
            }
            if (block.status() != Status.ACTIVE) {
                return new Result(
                        false,
                        "Block @" + id + "@ is " + block.status().name().toLowerCase() + " and cannot be decompressed.",
                        null);
            }
            replaceBlock(
                    block,
                    new Block(
                            block.id(),
                            block.topic(),
                            block.startRef(),
                            block.endRef(),
                            block.summary(),
                            block.summaryTokens(),
                            block.consumedBlockIds(),
                            Status.DECOMPRESSED,
                            null,
                            block.createdAt()));
            return new Result(true, "Decompressed @" + id + "@: its original messages are sent again.", id);
        } finally {
            lock.unlock();
        }
    }

    /** Marks every active block as absorbed, e.g. after a core compaction replaced the history. */
    public void absorbAll() {
        lock.lock();
        try {
            if (blocks.isEmpty()) {
                return;
            }
            for (var i = 0; i < blocks.size(); i++) {
                final var block = blocks.get(i);
                if (block.status() == Status.ACTIVE) {
                    blocks.set(
                            i,
                            new Block(
                                    block.id(),
                                    block.topic(),
                                    block.startRef(),
                                    block.endRef(),
                                    block.summary(),
                                    block.summaryTokens(),
                                    block.consumedBlockIds(),
                                    Status.ABSORBED,
                                    null,
                                    block.createdAt()));
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** Estimated tokens the active blocks remove from the live conversation. */
    public int savedTokens(final List<Message> messages) {
        lock.lock();
        try {
            if (!enabled() || blocks.isEmpty() || messages == null) {
                return 0;
            }
            var saved = 0;
            for (final var block : blocks) {
                if (block.status() != Status.ACTIVE) {
                    continue;
                }
                final var start = indexOf(messages, block.startRef(), 0);
                final var end = indexOf(messages, block.endRef(), start);
                if (start < 0 || end < start) {
                    continue;
                }
                var covered = 0;
                for (var i = start; i <= end; i++) {
                    covered += estimate(messages.get(i));
                }
                saved += Math.max(0, covered - block.summaryTokens());
            }
            return saved;
        } finally {
            lock.unlock();
        }
    }

    /** Effective prompt size after active compression: the raw estimate minus {@link #savedTokens}. */
    public int effectiveTokens(final List<Message> messages, final int rawTokens) {
        lock.lock();
        try {
            return Math.max(0, rawTokens - savedTokens(messages));
        } finally {
            lock.unlock();
        }
    }

    public List<Block> blocks() {
        lock.lock();
        try {
            return List.copyOf(blocks);
        } finally {
            lock.unlock();
        }
    }

    public int summaryTokens() {
        lock.lock();
        try {
            return blocks.stream()
                    .filter(b -> b.status() == Status.ACTIVE)
                    .mapToInt(Block::summaryTokens)
                    .sum();
        } finally {
            lock.unlock();
        }
    }

    /**
     * The resolved compression guidance targets for a given provider window, shared by the loop
     * (context-usage line, nudges) and the {@code compress} tool (status). The base {@code min} /
     * {@code max} are the configured absolute or percentage limits; when {@code summaryBuffer} is
     * enabled the hard target is extended by the active summary tokens, so an already-condensed
     * working set is not immediately hammered by the strong nudge. The extension is purely a
     * guidance target: {@link #effectiveTokens}/{@link #savedTokens} still count the summaries
     * toward real context usage and the compaction guard is untouched, so capacity accounting
     * never weakens.
     */
    /**
     * The resolved compression guidance targets for a given provider window, shared by the loop
     * (context-usage line, nudges) and the {@code compress} tool (status). The base {@code min} /
     * {@code max} come from the caller-provided {@code limits} (already merged per provider/session:
     * provider override over global over default); when {@code summaryBuffer} is enabled the hard
     * target is extended by the active summary tokens, so an already-condensed working set is not
     * immediately hammered by the strong nudge. The extension is purely a guidance target:
     * {@link #effectiveTokens}/{@link #savedTokens} still count the summaries toward real context
     * usage and the compaction guard is untouched, so capacity accounting never weakens.
     *
     * @param window the provider context window in tokens.
     * @param limits the resolved working-context limits (min/max) for the current session/provider.
     */
    public Targets targets(final int window, final ContextLimit limits) {
        lock.lock();
        try {
            final var summary = summaryTokens();
            final var baseMin = configuration == null ? 0 : limits.resolveMin(window);
            final var baseMax = configuration == null ? 0 : limits.resolveMax(window);
            final var buffered = configuration != null && configuration.summaryBuffer() && summary > 0;
            return new Targets(window, baseMin, baseMax, buffered ? baseMax + summary : baseMax, summary, buffered);
        } finally {
            lock.unlock();
        }
    }

    /** Resolved compression guidance targets (see {@link #targets(int, ContextLimit)}). */
    public record Targets(
            int window, int baseMin, int baseMax, int effectiveMax, int summaryTokens, boolean summaryBuffer) {}

    // --- internals ---------------------------------------------------------------------------

    private record ResolvedRange(int startRef, int endRef, String summary) {}

    /**
     * One structurally eligible, fully closed historical user turn: {@code startRef} is the user
     * message that opens the turn, {@code endRef} is the last assistant/tool message before the next
     * user message (both inclusive). Advertised only when passing the whole {@link #compress}
     * validation without snapping.
     */
    private record ClosedTurn(int startRef, int endRef, Message startMessage) {}

    private String expandPlaceholders(
            final String summary,
            final int startRef,
            final int endRef,
            final String newBlockId,
            final List<String> consumed) {
        final var matcher = BLOCK_REF.matcher(summary == null ? "" : summary);
        final var out = new StringBuilder();
        while (matcher.find()) {
            final var id = "b" + matcher.group(1);
            final var block =
                    blocks.stream().filter(b -> b.id().equals(id)).findFirst().orElse(null);
            if (block == null) {
                matcher.appendReplacement(out, "");
                // keep the literal when unknown: the caller validates refs, so this is only defensive
                out.append(matcher.group());
                continue;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(stripHeader(block.summary())));
            if (block.status() == Status.ACTIVE && block.startRef() >= startRef && block.endRef() <= endRef) {
                consumed.add(block.id());
                replaceBlock(
                        block,
                        new Block(
                                block.id(),
                                block.topic(),
                                block.startRef(),
                                block.endRef(),
                                block.summary(),
                                block.summaryTokens(),
                                block.consumedBlockIds(),
                                Status.SUPERSEDED,
                                newBlockId,
                                block.createdAt()));
            }
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String appendProtected(final String summary, final int startRef, final int endRef) {
        final var start = indexOf(published, startRef, 0);
        final var end = indexOf(published, endRef, start);
        if (start < 0 || end < start) {
            return summary;
        }
        final var out = new StringBuilder(summary == null ? "" : summary);
        final var toolNames = Message.toolNames(published);
        for (var i = start; i <= end; i++) {
            final var message = published.get(i);
            final var content = message.content() instanceof String s ? s : String.valueOf(message.content());
            if (configuration.protectUserMessages() && "user".equals(message.role()) && !isVolatileTail(message)) {
                out.append("\n\n[protected user message]\n").append(content);
            }
            if (configuration.protectTags()) {
                final var matcher = PROTECT_TAG.matcher(content);
                while (matcher.find()) {
                    out.append("\n\n[protected tag]\n").append(matcher.group(1));
                }
            }
            final var toolName = message.name() != null ? message.name() : toolNames.get(message.toolCallId());
            if ("tool".equals(message.role())
                    && toolName != null
                    && configuration.protectedTools() != null
                    && configuration.protectedTools().contains(toolName)) {
                out.append("\n\n[protected tool output: ")
                        .append(toolName)
                        .append("]\n")
                        .append(content);
            }
        }
        return out.toString();
    }

    private boolean overlapsActive(final int startRef, final int endRef) {
        return blocks.stream()
                .filter(b -> b.status() == Status.ACTIVE)
                .anyMatch(b -> startRef <= b.endRef() && endRef >= b.startRef());
    }

    private List<Block> activeOverlappingBlocks(final int startRef, final int endRef) {
        return blocks.stream()
                .filter(b -> b.status() == Status.ACTIVE && startRef <= b.endRef() && endRef >= b.startRef())
                .toList();
    }

    private static List<String> blockRefsIn(final String summary) {
        final var matcher = BLOCK_REF.matcher(summary == null ? "" : summary);
        final var out = new ArrayList<String>();
        while (matcher.find()) {
            out.add("b" + matcher.group(1));
        }
        return out;
    }

    private int protectedTurnStart(final int turns) {
        var remaining = turns;
        for (var i = published.size() - 1; i >= 0; i--) {
            final var message = published.get(i);
            if ("user".equals(message.role()) && !isVolatileTail(message)) {
                remaining--;
                if (remaining <= 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * The bounded, guaranteed-valid set of fully-closed historical turns a {@code compress} range may
     * currently anchor on, oldest-first and capped at {@link #MAX_GUIDANCE_REFS}. Each entry is an
     * inclusive {@code (startId, endId)} pair that passes the {@link #compress} validation without
     * any snapping: the start is a real user message and the end is that same turn's last
     * assistant/tool message just before the next user message. The current open turn, protected
     * recent turns, volatile tails, {@code <compacted-context>} digests and turns overlapped by an
     * ACTIVE compression block are all excluded. Empty when nothing is safe to compress.
     */
    public List<Range> compressibleClosedTurns() {
        lock.lock();
        try {
            final var turns = collectCompressibleClosedTurns(List.of(), MAX_GUIDANCE_REFS);
            final var out = new ArrayList<Range>(turns.size());
            for (final var turn : turns) {
                out.add(new Range(Integer.toString(turn.startRef()), Integer.toString(turn.endRef()), null));
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Total number of currently compressible fully-closed turns (unbounded), so a caller that
     * truncates the bounded guidance can report exactly how many were omitted rather than guessing.
     */
    public int compressibleClosedTurnCount() {
        lock.lock();
        try {
            return collectCompressibleClosedTurns(List.of(), Integer.MAX_VALUE).size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Discovers the currently eligible fully-closed historical turns, oldest-first, up to
     * {@code max}. {@code visible} optionally constrains the start message to a specific
     * (post-rewrite) wire list; an empty list means no visibility filter (status/guidance operate on
     * the live published list). Every returned turn passes the {@link #compress} structural
     * validation without snapping. Caller holds the lock.
     */
    private List<ClosedTurn> collectCompressibleClosedTurns(final List<Message> visible, final int max) {
        if (!enabled() || published == null || max <= 0) {
            return List.of();
        }
        // Assign refs in transcript order (including assistant/tool messages with no textual body),
        // so every discovered pair uses transcript-ordered ids that match the wire's stable refs.
        for (var i = 1; i < published.size(); i++) {
            final var message = published.get(i);
            if (message != null && !"system".equals(message.role()) && !isVolatileTail(message)) {
                refOf(message);
            }
        }
        final var protectedFrom = configuration.turnProtection() && configuration.turnProtectionTurns() > 0
                ? protectedTurnStart(configuration.turnProtectionTurns())
                : -1;
        final var out = new ArrayList<ClosedTurn>();
        // The current open turn is the one opened by the last real user message in the published
        // list: when a compression nudge fires we are mid-turn on it, so it is never advertised.
        var lastEligibleUserStart = -1;
        for (var i = 1; i < published.size(); i++) {
            final var candidate = published.get(i);
            if (isEligibleTurnStart(candidate, visible)) {
                lastEligibleUserStart = i;
            }
        }
        for (var i = 1; i < published.size() && out.size() < max; i++) {
            final var start = published.get(i);
            if (!isEligibleTurnStart(start, visible)) {
                continue;
            }
            // exclude the current open turn (the very last real user message)
            if (i == lastEligibleUserStart) {
                continue;
            }
            var end = i;
            while (end + 1 < published.size()
                    && (isVolatileTail(published.get(end + 1))
                            || !"user".equals(published.get(end + 1).role()))) {
                end++;
            }
            // Internal state is not a logical turn boundary, nor a valid range endpoint.
            while (end > i && isVolatileTail(published.get(end))) {
                end--;
            }
            // a closed turn must have a non-empty response span and end on a real turn boundary
            if (end <= i || !turnBoundary(end) || end >= lastEligibleUserStart) {
                continue;
            }
            if (protectedFrom >= 0 && end >= protectedFrom) {
                continue;
            }
            final var endMessage = published.get(end);
            if (overlapsActive(refOf(start), refOf(endMessage))) {
                continue;
            }
            out.add(new ClosedTurn(refOf(start), refOf(endMessage), start));
        }
        return out;
    }

    /** True when {@code message} is a real, non-protected user message that can open a compress turn. */
    private static boolean isEligibleTurnStart(final Message message, final List<Message> visible) {
        if (message == null
                || !"user".equals(message.role())
                || isVolatileTail(message)
                || isDigestOnlyUserMessage(message)
                || message.content() == null) {
            return false;
        }
        return visible.isEmpty() || messageVisible(visible, message);
    }

    /** True when {@code message} (by identity) is present in {@code list}. */
    private static boolean messageVisible(final List<Message> list, final Message message) {
        if (list == null || list.isEmpty()) {
            return true;
        }
        for (final var m : list) {
            if (m == message) {
                return true;
            }
        }
        return false;
    }

    private void markAbsorbed(final Block block) {
        replaceBlock(
                block,
                new Block(
                        block.id(),
                        block.topic(),
                        block.startRef(),
                        block.endRef(),
                        block.summary(),
                        block.summaryTokens(),
                        block.consumedBlockIds(),
                        Status.ABSORBED,
                        null,
                        block.createdAt()));
    }

    private void replaceBlock(final Block old, final Block replacement) {
        for (var i = 0; i < blocks.size(); i++) {
            if (blocks.get(i) == old || blocks.get(i).id().equals(old.id())) {
                blocks.set(i, replacement);
                return;
            }
        }
    }

    private int indexOf(final List<Message> messages, final int ref, final int from) {
        // refs are resolved through the published identity map, so a block always points at the
        // exact message it was created from, never at a same-looking later message
        if (messages == null) {
            return -1;
        }
        final var target = messagesByRef.get(ref);
        if (target == null) {
            return -1;
        }
        for (var i = Math.max(0, from); i < messages.size(); i++) {
            if (messages.get(i) == target) {
                return i;
            }
        }
        return -1;
    }

    private static int parseRef(final String value) {
        if (value == null) {
            return -1;
        }
        final var matcher = MESSAGE_REF.matcher(value.strip());
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static String normalizeBlockId(final String value) {
        if (value == null) {
            return "";
        }
        var id = value.strip();
        if (id.startsWith("@")) {
            id = id.substring(1);
        }
        if (id.endsWith("@")) {
            id = id.substring(0, id.length() - 1);
        }
        return id.startsWith("b") ? id : "b" + id;
    }

    private static String stripHeader(final String summary) {
        if (summary == null) {
            return "";
        }
        final var marker = "[Compressed conversation section";
        if (summary.startsWith(marker)) {
            final var newline = summary.indexOf('\n');
            return newline < 0 ? "" : summary.substring(newline + 1);
        }
        return summary;
    }

    private static boolean isVolatileTail(final Message message) {
        return message != null
                && message.others() != null
                && Boolean.TRUE.equals(message.others().get("volatileTail"));
    }

    /**
     * True when the message at {@code index} is a clean end-of-turn boundary: there is nothing after
     * it, the follower is the start of the next user turn, or the follower is a synthetic tail. A
     * volatile-tail message itself is never a boundary (it cannot be compressed).
     */
    private boolean turnBoundary(final int index) {
        if (index < 0 || index >= published.size()) {
            return false;
        }
        if (isVolatileTail(published.get(index))) {
            return false;
        }
        return index + 1 >= published.size()
                || isVolatileTail(published.get(index + 1))
                || "user".equals(published.get(index + 1).role());
    }

    private static boolean isCompressedBlock(final Message message) {
        return message != null && message.others() != null && message.others().get(BLOCK_MARKER) != null;
    }

    private static int estimate(final Message message) {
        var total = 8 + TokenEstimator.estimate(message.role());
        total += TokenEstimator.estimate(message.content() instanceof String s ? s : String.valueOf(message.content()));
        total += TokenEstimator.estimate(message.name());
        total += TokenEstimator.estimate(message.toolCallId());
        if (message.toolCalls() != null) {
            for (final var call : message.toolCalls()) {
                total += TokenEstimator.estimate(
                        call.function() == null ? null : call.function().name());
                total += TokenEstimator.estimate(
                        call.function() == null ? null : call.function().arguments());
            }
        }
        return total;
    }

    /** @return true when {@code message} is one of the synthetic compressed blocks. */
    public static boolean isCompressedBlockMessage(final Message message) {
        return isCompressedBlock(message);
    }

    /**
     * Removes echoed compact {@code @N@} ref tags from model-generated text (assistant replies or
     * tool output). This is the strip-on-ingest defense: even a model that violates the "do not
     * output the injected markers" instruction cannot leak them into the persisted transcript.
     * Mirrors the compact-format scrub of the reference DCP implementation.
     *
     * <p>Content preservation rule (shared with {@link #withholdDanglingPrefix}): a seam-shaped
     * token is removed only when it stands ALONE on its line OUTSIDE a fenced code block (the whole
     * line, apart from horizontal padding and an optional supported priority suffix, is the token).
     * A token inside prose, inside inline code, or anywhere inside a fenced block - including alone
     * on its own content line - is intended content and survives, so a model that writes the
     * injected marker literally, or wraps it in backticks, never has its delimiters orphaned.
     * Unknown/malformed shapes (emails, {@code @user}, over-long or non-numeric tokens) survive too.
     *
     * @param text text to clean, may be null.
     * @return the cleaned text, or null when the input was null.
     */
    public String stripEchoedRefs(final String text) {
        if (text == null) {
            return null;
        }
        if (text.indexOf('@') < 0) {
            return text;
        }
        // One linear, fence-aware pass: each line is classified by MarkdownFence (so code blocks
        // are never touched) and, outside a fence, a standalone complete seam line is dropped whole
        // and the evidence-gated glued-marker strip runs per line. The 'inline' tag form is
        // intentionally NOT removed - see the content preservation rule. Line terminator style is
        // preserved and the input instance is returned when nothing changed.
        final var out = new StringBuilder(text.length());
        var changed = false;
        final var maxRef = maxAssignedRef();
        final var fence = new MarkdownFence.Classifier();
        var lineStart = 0;
        while (lineStart <= text.length()) {
            final var newline = text.indexOf('\n', lineStart);
            final var lineEnd = newline < 0 ? text.length() : newline;
            final var line = text.substring(lineStart, lineEnd);
            final var inFence = fence.classify(text, lineStart, lineEnd);
            if (!inFence && isStandaloneSeamLine(line)) {
                changed = true; // the whole line (and its terminator) is dropped
            } else {
                final var scrubbed = inFence ? line : stripGluedSeamWords(line, maxRef);
                if (scrubbed != line) {
                    changed = true;
                }
                out.append(scrubbed);
                if (newline >= 0) {
                    out.append('\n');
                }
            }
            if (newline < 0) {
                break;
            }
            lineStart = newline + 1;
        }
        return changed ? out.toString() : text;
    }

    /**
     * True when {@code line} is exactly a seam token on its own: optional horizontal padding, then
     * {@code @} + a bounded token ({@code N}, {@code bN}, {@code blocked}) + optional supported
     * priority suffix ({@code [low|medium|high]}), then trailing horizontal padding - nothing else.
     * A trailing {@code \r} is treated as padding so a CRLF line ending is matched; the {@code \r}
     * itself is never emitted because the line is dropped whole. Bounded validation avoids any
     * regex; only recognized shapes are removed, so emails, {@code @user}, {@code answer@1},
     * over-long digit runs and the lone {@code @} always survive.
     */
    private static boolean isStandaloneSeamLine(final String line) {
        var i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        if (i >= line.length() || line.charAt(i) != '@') {
            return false;
        }
        i++;
        final var tokenStart = i;
        while (i < line.length() && (isAsciiLetter(line.charAt(i)) || isAsciiDigit(line.charAt(i)))) {
            i++;
        }
        final var tokenEnd = i;
        if (i >= line.length() || line.charAt(i) != '@') {
            return false;
        }
        if (!isSeamToken(line, tokenStart, tokenEnd)) {
            return false;
        }
        // complete seams require the [1-9]\d* ref shape (the historic ECHOED_REF_LINE): reject
        // leading-zero numeric tokens (@01@, @b01@) here, while the bare-anchor shape keeps its
        // looser all-digit acceptance
        final var first = line.charAt(tokenStart);
        if (first != 'b' && first == '0') {
            return false;
        }
        if (first == 'b' && tokenEnd - tokenStart > 1 && line.charAt(tokenStart + 1) == '0') {
            return false;
        }
        i++;
        var afterClosingAt = false;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            afterClosingAt = true;
            i++;
        }
        if (i < line.length() && line.charAt(i) == '[') {
            if (!afterClosingAt) {
                return false; // the historical shape requires whitespace before the priority suffix
            }
            final var start = i;
            while (i < line.length() && line.charAt(i) != ']') {
                i++;
            }
            if (i >= line.length()) {
                return false;
            }
            final var prio = line.substring(start + 1, i);
            if (!("low".equals(prio) || "medium".equals(prio) || "high".equals(prio))) {
                return false;
            }
            i++;
            while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
                i++;
            }
        }
        // trailing CR (from a CRLF line ending) is display padding, not content
        if (i < line.length() && line.charAt(i) == '\r') {
            i++;
        }
        return i == line.length();
    }

    private static boolean isAsciiDigit(final char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * Char-level replacement for the former {@code GLUED_SEAM_WORD} regex pass: removes the leaked
     * {@code @} of a glued seam marker ("@EntCompactionTruncates10632" -> "EntCompactionTruncates10632")
     * while keeping the word (real content). The {@code @} must sit at a word boundary - not preceded
     * by an ASCII letter/digit, so emails and identifiers like {@code a@b} are exempt - may be followed
     * by blanks, then a letter-initial ASCII word whose digit-run embeds an assigned ref id. Only the
     * single {@code @} is dropped; blanks and the word survive ("@ man12504que" -> " man12504que").
     * <p>Deliberately a plain linear scan (one pass, no regex state machine) because this is the
     * exact frame the jstack showed hot (Matcher.find() over a multi-megabyte transcript during a
     * streaming turn); the scan costs O(n) worst-case with a tiny constant and zero allocations
     * when nothing matches.
     */
    private static String stripGluedSeamWords(final String text, final int maxRef) {
        final var firstAt = text.indexOf('@');
        if (firstAt < 0) {
            return text;
        }
        final var sb = new StringBuilder(text.length());
        var last = 0;
        var stripped = false;
        var i = 0;
        var inlineRun = 0;
        while (i < text.length()) {
            final var c = text.charAt(i);
            if (c == '\\' && inlineRun == 0 && i + 1 < text.length()) {
                i += 2;
                continue;
            }
            if (c == '`') {
                final var start = i;
                while (i < text.length() && text.charAt(i) == '`') {
                    i++;
                }
                final var run = i - start;
                if (inlineRun == 0) {
                    inlineRun = run;
                } else if (inlineRun == run) {
                    inlineRun = 0;
                }
                continue;
            }
            if (c != '@' || (i > 0 && isAsciiAlphanumeric(text.charAt(i - 1)))) {
                i++;
                continue;
            }
            if (inlineRun > 0) {
                i++;
                continue;
            }
            // '@' at a word boundary (regex lookbehind (?<![A-Za-z0-9]) is ASCII-only): skip
            // optional blanks, then require a letter-initial word
            var j = i + 1;
            while (j < text.length() && (text.charAt(j) == ' ' || text.charAt(j) == '\t')) {
                j++;
            }
            final var wordStart = j;
            if (j >= text.length() || !isAsciiLetter(text.charAt(j))) {
                i++;
                continue;
            }
            while (j < text.length() && isAsciiAlphanumeric(text.charAt(j))) {
                j++;
            }
            if (gluedSeamWordMatchesAssignedRef(text.substring(wordStart, j), maxRef)) {
                // keep the word (it is real content), drop only the leaked '@' but preserve the
                // separating blanks ("@ man12504que" -> " man12504que")
                sb.append(text, last, i).append(text, i + 1, j);
                last = j;
                stripped = true;
            }
            // the regex find() resumes at the end of the full match ('@' + blanks + word); the
            // word is purely alphanumeric so no further '@' can hide inside the span - skipping
            // to j in both outcomes is exactly equivalent and cheaper than re-scanning it
            i = j;
        }
        if (!stripped) {
            return text;
        }
        return sb.append(text, last, text.length()).toString();
    }

    private static boolean isAsciiLetter(final char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isAsciiAlphanumeric(final char c) {
        return isAsciiLetter(c) || (c >= '0' && c <= '9');
    }

    /**
     * True when the word right after a leaked {@code @} embeds a digit-run of at least
     * {@link #MIN_GLUED_SEAM_DIGITS} digits that equals an ever-assigned ref id. Any digit-run of
     * the word is considered, so both the suffix form ("@EntCompactionTruncates10632") and the
     * mid-word form ("@ man12504que") are caught; shorter runs ("@step12", "@m125") are far too
     * common in real prose to gate on.
     */
    private static boolean gluedSeamWordMatchesAssignedRef(final String word, final int maxRef) {
        var i = 0;
        while (i < word.length()) {
            if (Character.isDigit(word.charAt(i))) {
                final var start = i;
                while (i < word.length() && Character.isDigit(word.charAt(i))) {
                    i++;
                }
                final var digits = word.substring(start, i);
                if (digits.length() >= MIN_GLUED_SEAM_DIGITS) {
                    try {
                        final var ref = Integer.parseInt(digits);
                        if (ref >= 1 && ref < maxRef) {
                            return true;
                        }
                    } catch (final NumberFormatException nfe) {
                        // over-long digit-run (e.g. a 28-digit pinned token) can never be a ref
                    }
                }
            } else {
                i++;
            }
        }
        return false;
    }

    /**
     * Render-time view of {@code text} that, in addition to stripping complete echoed seams, also
     * hides the bare-anchor lines a flash-class model emits as partial echoes (e.g. {@code @1290} or
     * {@code @b7} without their closing {@code @}) - whether trailing or frozen mid-block once a
     * tool block follows. Runs on a copy only - the underlying buffer stays raw, so
     * {@code flushThinking()}/{@code flushPending()} still assemble the full text and a completed
     * tag is stripped normally.
     *
     * <p>This is deliberately <em>shape</em>-driven, not evidence-gated against {@link #renderedRefs}:
     * a leaked anchor always refers to a ref the current wire no longer renders (the wire moves on as
     * compaction happens, or the message left it), so a live-ref whitelist would leave exactly the
     * anchors this surface exists to hide. The anchor shape is narrow enough to be safe: a line whose
     * trimmed content starts with {@code @} followed by a short token (1..24 chars) that is all
     * digits, {@code b} + digits, or the literal {@code blocked}. Real markdown lines (@user,
     * @RequestMapping), lone {@code @}, over-long tokens, inline/glued fragments ("answer@1") and
     * fenced prose are untouched.
     *
     * <p>Lock-free on purpose: it only reads the immutable {@link #renderedRefs} snapshot (or
     * nothing), never the agent-owned {@link #published} list.
     */
    public String withholdDanglingPrefix(final String text) {
        if (text == null) {
            return null;
        }
        return stripBareAnchorLines(stripEchoedRefs(text));
    }

    /**
     * Drops every line that looks like a leaked seam-anchor fragment anywhere in the text - not just
     * at the tail, so a fragment frozen mid-block (followed by a tool card) is still hidden.
     * Fence-aware: a bare-anchor-shaped line inside a fenced code block is literal content and is
     * preserved, matching the {@link #stripEchoedRefs} content-preservation rule. Extremely cheap on
     * purpose: one manual pass over the existing {@code \n} spans, no auxiliary matcher objects, no
     * per-line regex runs. A single trailing {@code \n} is preserved (the historical
     * stripBareAnchorLines behavior, pinned by {@code withholdDanglingPrefixFastPathWithoutAtSign}).
     */
    private static String stripBareAnchorLines(final String text) {
        final var fence = new MarkdownFence.Classifier();
        final var sb = new StringBuilder(text.length());
        var removed = false;
        var lineStart = 0;
        while (lineStart <= text.length()) {
            final var newline = text.indexOf('\n', lineStart);
            final var lineEnd = newline < 0 ? text.length() : newline;
            final var line = text.substring(lineStart, lineEnd);
            final var inFence = fence.classify(text, lineStart, lineEnd);
            if (!inFence && isBareSeamAnchorLine(line)) {
                removed = true;
            } else {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
            if (newline < 0) {
                break;
            }
            lineStart = newline + 1;
        }
        // preserve the input instance when nothing was removed (matches the identity fast path of
        // the composed stripEchoedRefs and the historical behavior for '@'-free text)
        return removed ? sb.toString() : text;
    }

    /** Cheap shape test for a leaked seam-anchor line: see {@link #withholdDanglingPrefix}. */
    private static boolean isBareSeamAnchorLine(final String line) {
        var i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t' || line.charAt(i) == '\r')) {
            i++; // leading blanks (and CR before LF) are display padding, never part of the token
        }
        if (i >= line.length() || line.charAt(i) != '@') {
            return false;
        }
        i++;
        final var tokenStart = i;
        while (i < line.length() && Character.isLetterOrDigit(line.charAt(i))) {
            i++;
        }
        final var tokenEnd = i;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t' || line.charAt(i) == '\r')) {
            i++; // trailing blanks are display padding too
        }
        if (i != line.length()) {
            return false; // anything else on the line (prose, punctuation) means real content
        }
        return isSeamToken(line, tokenStart, tokenEnd);
    }

    /**
     * Validates that the token {@code text[start, end)} is one of the supported seam shapes
     * ({@code N}, {@code bN}, {@code blocked}) with a bounded numeric ref. Value-based bound: refs
     * are ints, so a digit run longer than {@value #MAX_REF_DIGITS} (Integer.MAX_VALUE has 10
     * digits) can never be a real ref - the pinned 28-digit shape survives *because it is not a
     * ref*. {@code blocked} (7) and {@code b}+10 digits (11) still fit.
     */
    private static boolean isSeamToken(final String text, final int start, final int end) {
        if (start >= end) {
            return false;
        }
        if (end - start == "blocked".length() && text.regionMatches(start, "blocked", 0, "blocked".length())) {
            return true;
        }
        final var b = text.charAt(start) == 'b';
        final var digitsStart = b ? start + 1 : start;
        final var digitsLen = end - digitsStart;
        if (b && digitsLen < 1) {
            return false;
        }
        if (digitsLen < 1 || digitsLen > MAX_REF_DIGITS) {
            return false;
        }
        for (var i = digitsStart; i < end; i++) {
            if (!isAsciiDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
