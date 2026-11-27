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

import static java.util.stream.Collectors.toList;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Periodic hygiene pass over the project memory file: when it grows past a size
 * threshold, an LLM call (on the compact model when configured) rewrites the
 * durable facts, merging near-duplicates, dropping superseded one-off
 * observations and keeping one short bullet per fact. A {@code .bak} backup is
 * kept and rewrites that do not shrink the file by at least {@code minReduction}
 * are discarded, so a poor model output can never destroy memory.
 *
 * <p>Runs at most {@code minIntervalMs} apart (debounced), never more than once
 * per session (a {@code compaction.txt} state file records the last run), and
 * only when memory is enabled.</p>
 */
@ApplicationScoped
public class MemoryCompactor {
    private static final Logger LOGGER = Logger.getLogger(MemoryCompactor.class.getName());

    /** Project memory files below this size are never worth rewriting. */
    private static final long THRESHOLD_BYTES = 64 * 1024;

    /** A rewrite must shrink the file by at least this fraction, or it is discarded. */
    private static final double MIN_REDUCTION = 0.20;

    /** Debounce between automatic runs (manual {@link #compactNow()} bypasses it). */
    private static final long MIN_INTERVAL_MS = 60_000;

    private final ChatClient chatClient;
    private final CodingAgentConfiguration configuration;
    private final MemoryStore memoryStore;
    private final ProviderManager providerManager;
    private final UsageTracker usageTracker;
    private final Emitter emitter;
    private final String sessionId;
    private final AtomicLong lastRun = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean();

    /** Kept for callers (tests) that do not wire an emitter nor a session. */
    public MemoryCompactor(
            final ChatClient chatClient,
            final CodingAgentConfiguration configuration,
            final MemoryStore memoryStore,
            final ProviderManager providerManager,
            final UsageTracker usageTracker) {
        this(chatClient, configuration, memoryStore, providerManager, usageTracker, null, null);
    }

    public MemoryCompactor(
            final ChatClient chatClient,
            final CodingAgentConfiguration configuration,
            final MemoryStore memoryStore,
            final ProviderManager providerManager,
            final UsageTracker usageTracker,
            final Emitter emitter,
            final String sessionId) {
        this.chatClient = chatClient;
        this.configuration = configuration;
        this.memoryStore = memoryStore;
        this.providerManager = providerManager;
        this.usageTracker = usageTracker;
        this.emitter = emitter;
        this.sessionId = sessionId;
    }

    /**
     * Called at the end of a turn: debounced, threshold-gated, single-flight.
     *
     * @return a short status message, or {@code null} when nothing ran
     */
    /** Checks and compacts only the selected session project; null preserves the configured default. */
    public String maybeCompact(final ChatSettings settings, final Path root) {
        Objects.requireNonNull(root, "workdir");
        if (configuration == null
                || !configuration.memory().compactEnabled()
                || !configuration.memory().enabled()) {
            return null;
        }
        final var now = System.currentTimeMillis();
        final var last = lastRun.get();
        if (now - last < MIN_INTERVAL_MS || !lastRun.compareAndSet(last, now)) {
            return null;
        }
        final var file = MemoryStore.projectFile(root);
        try {
            if (!Files.isRegularFile(file) || Files.size(file) < THRESHOLD_BYTES) {
                return null; // below the threshold: nothing to gain
            }
            return compactNow(false, ChatSettings.EMPTY, root);
        } catch (final IOException e) {
            LOGGER.log(Level.FINE, "memory compaction threshold check failed", e);
            return null;
        }
    }

    /**
     * Runs the rewrite right now (manual {@code /memory compact}). Bypasses the
     * debounce and threshold, honoring only the enabled flag and the single-flight
     * guard (protected constructor use is only for the manual path).
     */
    /** Manual compaction of the selected project, including its backup and state file. */
    public String compactNow(final Path root) {
        return compactNow(true, ChatSettings.EMPTY, root);
    }

    private String compactNow(final boolean manual, final ChatSettings settings, final Path root) {
        Objects.requireNonNull(root, "workdir");
        if (!running.compareAndSet(false, true)) {
            return "memory compaction already running";
        }
        try {
            final var content = memoryStore.readProject(root);
            if (content == null || content.isBlank()) {
                return manual ? "no project memory to compact" : null;
            }
            final var rewritten = rewrite(content, settings, root);
            if (rewritten == null || rewritten.isBlank()) {
                return manual ? "memory compaction produced no output; left untouched" : null;
            }
            // normalize and re-parse the model output into plain bullets; keep the ones that are not blank
            final var rawBullets = rewritten
                    .lines()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .map(l -> l.startsWith("-") ? l : "- " + l)
                    .collect(toList());
            // re-attach metadata: each returned bullet inherits the higher priority / path / topic of the
            // pre-existing bullet it most closely matches, so a p5 directive that survives the rewrite
            // keeps its protection; genuinely new bullets get the plain default. A compat file with no
            // envelope anywhere stays plain, so compaction never balloons a legacy file with metadata.
            final var existing = content.lines().filter(l -> !l.isBlank()).toList();
            final var useEnvelope = existing.stream().anyMatch(MemoryStore::isEnvelopedLine);
            final var bullets = new ArrayList<String>(rawBullets.size());
            for (final var raw : rawBullets) {
                final var text = MemoryStore.textOf(raw);
                if (text.isBlank() || MemoryStore.HEADER_LINE.equals(text)) {
                    continue;
                }
                final var match = MemoryStore.bestMatch(text, existing);
                bullets.add(
                        useEnvelope
                                ? (match == null
                                        ? MemoryStore.withMeta(2, null, null, text)
                                        : MemoryStore.keepMeta(match, text))
                                : "- " + text);
            }
            // p5 guard: never accept a rewrite that dropped an explicit keep directive
            final var keepLines = content.lines()
                    .map(String::strip)
                    .filter(l -> MemoryStore.metaOf(l).p() == MemoryStore.Meta.KEEP_P)
                    .toList();
            final var droppedKeep = !keepLines.isEmpty() && keepLines.stream().anyMatch(l -> !bestExists(l, bullets));
            if (droppedKeep) {
                return manual ? "memory compaction kept the file (an explicit keep directive was dropped)" : null;
            }
            final var beforeBytes = content.length();
            final var afterBytes =
                    bullets.stream().mapToInt(l -> l.length() + 1).sum() + MemoryStore.HEADER.length();
            if (beforeBytes > 0 && afterBytes >= beforeBytes * (1 - MIN_REDUCTION)) {
                // not enough reduction: a poor rewrite would only churn the file
                return manual ? "memory compaction kept the file (reduction below 20%)" : null;
            }
            memoryStore.replace(false, bullets, root);
            writeState(beforeBytes, afterBytes, root);
            return "memory compacted: "
                    + beforeBytes
                    + " -> "
                    + afterBytes
                    + " chars ("
                    + Math.round((1 - (double) afterBytes / beforeBytes) * 100)
                    + "% smaller)";
        } finally {
            running.set(false);
        }
    }

    private String rewrite(final String content, final ChatSettings settings, final Path root) {
        // provider-level override (blank = this provider's own model, "inherit" = the global
        // chain) then the global resolvedMemory chain, kept routable so the rewrite is not
        // routed to a session's provider that does not host the model
        final var model = providerManager == null ? null : providerManager.resolvedMemory(settings);
        // strip the metadata envelope before the model sees the content: p|date|@path|topic| is
        // file-internal; the model compacts plain bullets and we re-attach metadata by text match
        final var stripped = content.lines()
                .map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .map(MemoryStore::textOf)
                .filter(t -> !t.isBlank())
                .map(l -> l.startsWith("-") || MemoryStore.HEADER_LINE.equals(l) ? l : "- " + l)
                .collect(Collectors.joining("\n"));
        try {
            final var res = chatClient.chat(new ChatCompletionRequest(
                            model,
                            List.of(
                                    Message.system(
                                            "You maintain a project memory file: a list of durable facts, decisions and "
                                                    + "preferences, one bullet per line, all about this repository. "
                                                    + "Rewrite the provided memory: merge near-duplicate bullets, drop facts that "
                                                    + "supersede or contradict older ones, drop one-off session observations "
                                                    + "(benchmarks, sandbox paths, single-run findings) and drop non-facts (plans, "
                                                    + "\"must be fixed\", implementation details). Keep one short bullet per fact, "
                                                    + "each at most 200 characters. Always KEEP verbatim any bullet that reads as an "
                                                    + "explicit user directive (eg. starts with \"never\", \"always\", \"don't forget\", "
                                                    + "\"remember\", \"must\"): such directives must never be merged, reworded or dropped. "
                                                    + "Keep the same information density; only remove the noise. Reply with only the "
                                                    + "new bullet list, one per line, '-' prefixed, no preamble, no markdown fences."),
                                    Message.user(stripped)),
                            null,
                            null,
                            false,
                            null,
                            null,
                            null,
                            null,
                            null)
                    .withSessionId(sessionId)
                    .withSettings(settings)
                    .withTuning(
                            providerManager == null
                                    ? null
                                    : providerManager.resolvedBackgroundTuning(settings, "memory")));
            if (usageTracker != null) {
                usageTracker.recordAuxiliary(res.usage());
            }
            return res.message() == null || res.message().content() == null
                    ? ""
                    : String.valueOf(res.message().content());
        } catch (final IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.log(Level.FINE, "memory compaction LLM call failed", e);
            LOGGER.info("memory compaction failed; run /memory compact to retry");
            emitNotice("memory compaction failed: " + e.getMessage() + " - run /memory compact to retry", e, root);
            return null;
        }
    }

    private void emitNotice(final String message, final Throwable cause, final Path root) {
        if (emitter != null) {
            emitter.emit(new AgentEvent.Notice(
                    AgentEvent.Notice.Level.WARN, message, null, sessionId, null, null, cause, root));
        }
    }

    /** Whether any {@code bullets} still closely carries a keep line whose text matches {@code keepLine}. */
    private static boolean bestExists(final String keepLine, final List<String> bullets) {
        final var text = MemoryStore.textOf(keepLine);
        for (final var b : bullets) {
            if (MemoryStore.textOf(b).equals(text) || (MemoryStore.bestMatch(text, List.of(b)) != null)) {
                return true;
            }
        }
        return false;
    }

    private void writeState(final long beforeBytes, final long afterBytes, final Path root) {
        try {
            final var file = (MemoryStore.projectFile(root)).resolveSibling("compaction.txt");
            Files.writeString(
                    file,
                    "timestamp=" + System.currentTimeMillis()
                            + "\nbefore=" + beforeBytes
                            + "\nafter=" + afterBytes
                            + "\n");
        } catch (final IOException e) {
            LOGGER.log(Level.FINE, "could not persist memory compaction state", e);
        }
    }

    /** Reads the last compaction state, or {@code null} when none is recorded. */
    /** Reads compaction state beside the selected project's memory file. */
    public CompactionState state(final Path root) {
        Objects.requireNonNull(root, "workdir");
        final var file = (MemoryStore.projectFile(root)).resolveSibling("compaction.txt");
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            long before = -1;
            long after = -1;
            long timestamp = -1;
            for (final var line : Files.readAllLines(file)) {
                final var i = line.indexOf('=');
                if (i < 0) {
                    continue;
                }
                final var value = line.substring(i + 1);
                switch (line.substring(0, i)) {
                    case "timestamp" -> timestamp = Long.parseLong(value);
                    case "before" -> before = Long.parseLong(value);
                    case "after" -> after = Long.parseLong(value);
                    default -> {
                        // ignore unknown keys
                    }
                }
            }
            return new CompactionState(timestamp, before, after);
        } catch (final IOException e) {
            LOGGER.log(Level.FINE, "could not read memory compaction state", e);
            return null;
        }
    }

    public record CompactionState(long timestamp, long before, long after) {}
}
