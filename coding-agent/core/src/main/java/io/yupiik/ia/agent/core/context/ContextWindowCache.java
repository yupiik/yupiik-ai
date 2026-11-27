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

import static java.lang.System.currentTimeMillis;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ModelsResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Optional;

/**
 * Daily cache of the context windows detected from the providers /v1/models endpoint.
 * Entries are keyed by provider id and model name, persisted as JSONL under the
 * {@code context-window} directory of the storage home and stay valid for a day.
 *
 * <p>The file holds at most one entry per (provider id, model) pair: {@link #put} is an
 * upsert that replaces any prior entry for the same key, so the file self-heals from
 * duplicate lines (legacy appends or a crash between the read and the rewrite) on the
 * next write to a given key rather than growing one line per fetch.</p>
 */
@ApplicationScoped
public class ContextWindowCache {
    private static final long TTL_MILLIS = 24L * 60L * 60L * 1000L;
    /** Bounded so a crashed writer holding the lock cannot stall readers or later writers forever. */
    private static final long LOCK_TIMEOUT_MILLIS = 5_000L;

    private final CodingAgentConfiguration configuration;
    private final JsonMapper mapper;
    private final Clock clock;

    public ContextWindowCache(
            final CodingAgentConfiguration configuration, final JsonMapper mapper, final Clock clock) {
        this.configuration = configuration;
        this.mapper = mapper;
        this.clock = clock;
    }

    @JsonModel
    public record Entry(
            String providerId,
            String model,
            ModelsResponse.TokenCount window,
            ModelsResponse.TokenCount maxTokens,
            long fetchedAt) {}

    /** The cached window for the provider+model pair when fresher than a day; the last entry wins. */
    public Optional<ModelsResponse.TokenCount> get(final String providerId, final String model) {
        return getEntry(providerId, model).map(Entry::window);
    }

    /** @return the window value as int, or 0 if not cached/unknown */
    public int getInt(final String providerId, final String model) {
        return get(providerId, model).map(ModelsResponse.TokenCount::value).orElse(0);
    }

    /** The cached max output tokens for the provider+model pair when fresher than a day; nullable when absent or unknown. */
    public Optional<ModelsResponse.TokenCount> getMaxTokens(final String providerId, final String model) {
        return getEntry(providerId, model).map(Entry::maxTokens);
    }

    /**
     * The cached window even when older than the TTL: a day-stale figure is a far better fallback
     * than the conservative auto baseline when a fresh detection fails (provider unreachable, auth
     * pending). Overflow-adjusted entries are excluded: they are provisional guesses derived from a
     * fallback window, not a detected value, so they must never become an authoritative stale fallback.
     */
    public Optional<ModelsResponse.TokenCount> getStale(final String providerId, final String model) {
        return getEntry(providerId, model, true)
                .filter(entry -> entry.window() != null
                        && entry.window().sourceType() != ModelsResponse.SourceType.OVERFLOW_ADJUSTED)
                .map(Entry::window);
    }

    private Optional<Entry> getEntry(final String providerId, final String model) {
        return getEntry(providerId, model, false);
    }

    private Optional<Entry> getEntry(final String providerId, final String model, final boolean ignoreTtl) {
        final var file = file();
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        final var now = clock.millis();
        try {
            Optional<Entry> found = Optional.empty();
            for (final var line : Files.readAllLines(file)) {
                if (line.isBlank()) {
                    continue;
                }
                final Entry entry;
                try {
                    entry = mapper.fromString(Entry.class, line);
                } catch (final RuntimeException e) {
                    continue;
                }
                if (matches(entry, providerId, model)
                        && (ignoreTtl || now - entry.fetchedAt() <= TTL_MILLIS)
                        && (found.isEmpty() || entry.fetchedAt() > found.get().fetchedAt())) {
                    // keep the freshest (highest fetchedAt) matching entry, not merely the last line,
                    // so ordering accidents cannot let a stale entry shadow a fresh one
                    found = Optional.of(entry);
                }
            }
            return found;
        } catch (final IOException e) {
            return Optional.empty();
        }
    }

    public void put(
            final String providerId, final String model, final ModelsResponse.TokenCount window, final long fetchedAt) {
        put(providerId, model, window, null, fetchedAt);
    }

    public void put(
            final String providerId,
            final String model,
            final ModelsResponse.TokenCount window,
            final ModelsResponse.TokenCount maxTokens,
            final long fetchedAt) {
        final var entry = new Entry(providerId, model, window, maxTokens, fetchedAt);
        try {
            Files.createDirectories(dir());
            try (final var channel = FileChannel.open(lockFile(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    final FileLock ignored = lock(channel)) {
                writeUpserted(entry);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(
                    "Could not persist context window for '" + providerId + "/" + model + "'", e);
        }
    }

    /**
     * Rewrites {@code windows.jsonl} keeping at most one entry per (provider id, model): the existing
     * lines are loaded, the prior entry for the same key (when present) is dropped, the new entry is
     * appended, and the whole file is replaced atomically so a crash never leaves it truncated. Entries
     * that fail to parse are discarded, which also lets the file self-heal from legacy duplicate/partial
     * lines. Runs under the {@link #lockFile()} exclusion so concurrent writers do not interleave.
     */
    private void writeUpserted(final Entry entry) throws IOException {
        final var file = file();
        final var byKey = new LinkedHashMap<Key, Entry>();
        if (Files.isRegularFile(file)) {
            for (final var line : Files.readAllLines(file)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    final var parsed = mapper.fromString(Entry.class, line);
                    if (parsed.providerId() == null || parsed.model() == null) {
                        continue;
                    }
                    byKey.put(new Key(parsed.providerId(), parsed.model()), parsed);
                } catch (final RuntimeException e) {
                    // ignore malformed/skippable legacy lines; they are dropped on the next write
                }
            }
        }
        byKey.put(new Key(entry.providerId(), entry.model()), entry);

        final var tmp = file.resolveSibling(file.getFileName() + ".tmp");
        final var sb = new StringBuilder(byKey.size() * 96);
        for (final var kept : byKey.values()) {
            sb.append(mapper.toString(kept)).append('\n');
        }
        Files.writeString(tmp, sb, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException atomicMoveUnsupported) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Bounded acquire of the exclusive file lock, so a transient contention just waits a little. */
    private FileLock lock(final FileChannel channel) throws IOException {
        final var deadline = currentTimeMillis() + LOCK_TIMEOUT_MILLIS;
        FileLock lock = null;
        while (lock == null) {
            lock = channel.tryLock();
            if (lock == null) {
                if (currentTimeMillis() > deadline) {
                    throw new IOException("Timed out waiting for the context-window cache lock");
                }
                try {
                    Thread.sleep(50);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for the context-window cache lock", e);
                }
            }
        }
        return lock;
    }

    private boolean matches(final Entry entry, final String providerId, final String model) {
        return entry.providerId() != null
                && entry.model() != null
                && entry.providerId().equals(providerId)
                && entry.model().equals(model);
    }

    private Path dir() {
        return Path.of(configuration.resolveStorageHome(), "context-window");
    }

    private Path file() {
        return dir().resolve("windows.jsonl");
    }

    private Path lockFile() {
        return dir().resolve("windows.jsonl.lock");
    }

    /** Stable identity key for the (provider id, model) dedup dimension. */
    private record Key(String providerId, String model) {}
}
