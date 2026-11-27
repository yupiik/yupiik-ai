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
package io.yupiik.ia.agent.github.execution.cache;

import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Repository-isolated, whole-directory optimistic cache.
 *
 * <p>The small atomic state file is the only root of truth. Allocation inventory records are
 * fixed 64-byte records, publication FIFO records are fixed 80-byte records. Their combined
 * length is capped by maxManifestBytes. Sweeps rebuild a reusable free-slot inventory and
 * publications use a bounded circular FIFO. No foreground operation enumerates a live cache or sizes its contents.
 * First initialization only verifies an empty, known layout, stopping at the first unknown entry.
 * Charged bytes include object contents, allocation pointers, both inventories, private staging,
 * and a conservative allowance for two bounded state files. Directory/inode filesystem overhead
 * is not portable and is not a byte quota on filesystem block allocation.
 *
 * <p>Reservations precede data writes. A crash can overcharge, never undercharge. Slot-owned
 * staging reservations are recovered only while holding that slot's kernel lock. An interrupted
 * index mutation retains its bounded reservation because the index installer has no transactional
 * allocation callback; ambiguous index allocations are deliberately not reclaimed by age.
 * Successful mutations inventory and fsync their new search path before exposing the new root.
 * Strict crash-byte accounting additionally requires the index installer to durably remove its
 * private temporary files, including when reusing an existing node; that is an index-owner contract.
 *
 * <p>Maintenance owns all operation slots for at most one bounded slice, never while waiting
 * for a foreground user. Its persisted depth-first grey stack has at most 1024 items. Each
 * manifest record and each allocation record consumes one budget unit. Every root commit
 * invalidates marking; sweeping cannot resume until the new current graph has been marked.
 *
 * <p>State format version 2 persists both metadata capacities, absolute FIFO byte cursors,
 * and a free-list head/count. A freed allocation's mark word is its next-free offset (-1 ends
 * the list); live allocations still use that word for their mark generation. FIFO records end
 * with their absolute publication sequence. The ring reserves one quarter of maxManifestBytes,
 * rounded down to whole records; the remaining whole records bound the allocation inventory.
 * The last allocation word is a durable known-corrupt flag; it is cleared only when that
 * allocation is freed by all-slot maintenance. Valid deduplication checks it in O(1), without
 * hashing existing blobs again.
 * Version 1 stores require replacement, not an unbounded startup migration.
 */
public final class DependencyCache {
    private static final Pattern CACHE_DIGEST = Pattern.compile("[0-9a-f]{64}");

    private static final int MAGIC = 0x59434431;
    private static final int RECORD = 64;
    private static final int FIFO_RECORD = 80;
    private static final int BLOB = 1;
    private static final int MANIFEST = 2;
    private static final int INDEX = 3;
    private static final int RESERVATION = 4;
    private static final int MAX_STACK = 1024;
    private static final int MAX_NEW_NODES = 66;
    private static final long INDEX_RESERVATION = MAX_NEW_NODES * (8192L + RECORD + Long.BYTES);
    private static final long SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final HexFormat HEX = HexFormat.of();

    private final DependencyCacheConfiguration configuration;
    private final Clock clock;
    private final Path root;
    private final CacheLocks locks;
    private final CacheIndex index;
    private final long stateAllowance;

    public DependencyCache(final DependencyCacheConfiguration configuration, final Clock clock) throws IOException {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (!configuration.enabled()) {
            throw new IllegalArgumentException("Disabled dependency cache must not be constructed");
        }
        if (configuration.limits().maxConcurrentOperations() > 4096) {
            throw new IllegalArgumentException("Too many dependency cache operation slots");
        }
        root = Path.of(configuration.root()).toAbsolutePath().normalize();
        directory(root);
        for (final var permission : Files.getPosixFilePermissions(root, LinkOption.NOFOLLOW_LINKS)) {
            if (permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_")) {
                throw new IOException("Dependency cache root must be private (0700)");
            }
        }
        for (final var name : List.of("blobs", "manifests", "index", "work")) {
            directory(root.resolve(name));
        }
        locks = new CacheLocks(root.resolve("locks"), configuration.limits().maxConcurrentOperations());
        index = new CacheIndex(root.resolve("index"));
        stateAllowance = 196608L + 32L * configuration.limits().maxConcurrentOperations();
        final var operationDeadline = deadline(configuration.limits().operationTimeoutSeconds());
        try (final var metadata = metadata(operationDeadline)) {
            if (!Files.exists(root.resolve("state"), LinkOption.NOFOLLOW_LINKS)) {
                checkEmptyLayout();
                if (stateAllowance > configuration.maintenance().maxSizeBytes()) {
                    throw new IOException("Cache quota cannot hold bounded store metadata");
                }
                final var state = new State();
                state.epoch = 1;
                state.generation = 1;
                state.resetAt = clock.millis();
                state.charged = stateAllowance;
                state.fifoCapacity = configuration.limits().maxManifestBytes() / 4 / FIFO_RECORD * FIFO_RECORD;
                state.inventoryCapacity =
                        (configuration.limits().maxManifestBytes() - state.fifoCapacity) / RECORD * RECORD;
                if (state.fifoCapacity < FIFO_RECORD || state.inventoryCapacity < (MAX_NEW_NODES + 3L) * RECORD) {
                    throw new IOException("Cache metadata budget cannot hold a bounded mutation and publication ring");
                }
                charge(state, state.fifoCapacity); // Reserve the entire ring once, including unwritten slots.
                state.staging = new long[configuration.limits().maxConcurrentOperations()];
                for (final var name : List.of("inventory", "publications")) {
                    try (final var channel = FileChannel.open(
                            root.resolve(name),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS)) {
                        channel.force(true);
                    }
                }
                forceDirectory(root);
                save(state);
            } else {
                load(); // Validates the persisted slot count; never scans the store at startup.
            }
        }
    }

    public record Baseline(long epoch, Map<String, String> revisions) {
        public Baseline {
            revisions = Map.copyOf(revisions);
        }
    }

    public record Publication(int published, int unchanged, int conflicted, int timedOut) {}

    public record MaintenanceResult(long epoch, long chargedBytes, int processed, boolean busy) {}

    public Baseline restore(final String repositoryIdentity, final Path storage) throws IOException {
        final var keys = keys(repositoryIdentity);
        checkedStorage(storage);
        // Restore uses one operation deadline across all entries, including admission and recovery.
        final var operationDeadline = deadline(configuration.limits().operationTimeoutSeconds());
        try (final var slot = locks.foreground(operationDeadline)) {
            if (slot == null) {
                throw new CacheSnapshot.OperationTimeoutException("Cache restore admission timed out");
            }
            recoverStaging(slot.slot(), operationDeadline);
            final State selected;
            final var revisions = new LinkedHashMap<String, String>();
            try (final var metadata = metadata(operationDeadline)) {
                selected = load();
                try {
                    for (final var entry : configuration.resolvedEntries()) {
                        checkDeadline(operationDeadline);
                        revisions.put(entry.id(), revision(index.get(selected.root, keys.get(entry.id()))));
                    }
                } catch (final CacheSnapshot.OperationTimeoutException timeout) {
                    throw timeout;
                } catch (final IOException corruptIndex) {
                    reset(selected);
                    save(selected);
                    return coldBaseline(selected);
                }
            }
            Baseline repaired = null;
            for (final var entry : configuration.resolvedEntries()) {
                final var revision = revisions.get(entry.id());
                if (revision.isEmpty()) {
                    continue;
                }
                checkDeadline(operationDeadline);
                final var destination = storage.toAbsolutePath().normalize().resolve(entry.directory());
                checkedPath(destination);
                directory(destination.getParent());
                final var staging = Files.createTempDirectory(
                        destination.getParent(),
                        ".dependency-restore-",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                try {
                    final var manifest = object(MANIFEST, revision);
                    final var manifestSize = Files.size(manifest);
                    if (manifestSize > configuration.limits().maxManifestBytes()) {
                        throw new CorruptObjectException(MANIFEST, revision);
                    }
                    rejectCorrupt(MANIFEST, revision);
                    verify(manifest, revision, manifestSize, operationDeadline);
                    final var restored = staging.resolve("tree");
                    new CacheSnapshot(configuration.limits(), operationDeadline)
                            .restore(manifest, restored, entry, (digest, size) -> {
                                rejectCorrupt(BLOB, digest);
                                final var blob = object(BLOB, digest);
                                try {
                                    regular(blob, size);
                                } catch (final IOException corrupt) {
                                    throw new CorruptObjectException(BLOB, digest);
                                }
                                return blob;
                            });
                    checkDeadline(operationDeadline);
                    Files.move(
                            restored, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (final CacheSnapshot.CorruptBlobException corrupt) {
                    // Integrity settlement has its own bounded cleanup allowance: a hash
                    // mismatch discovered at the deadline must still fence future reuse.
                    repaired = quarantineAndReset(BLOB, corrupt.digest(), deadline(1));
                } catch (final CorruptObjectException corrupt) {
                    repaired = quarantineAndReset(corrupt.type, corrupt.digest, deadline(1));
                } catch (final CacheSnapshot.OperationTimeoutException timeout) {
                    throw timeout;
                } catch (final StoreException integrity) {
                    throw integrity;
                } catch (final IOException coldFallback) {
                    // Missing objects and destination conflicts are cold misses. No shared object is removed here.
                } finally {
                    removeTree(staging, deadline(1));
                }
            }
            return repaired == null ? new Baseline(selected.epoch, revisions) : repaired;
        }
    }

    private Baseline coldBaseline(final State state) {
        final var revisions = configuration.resolvedEntries().stream()
                .collect(
                        LinkedHashMap<String, String>::new, (result, entry) -> result.put(entry.id(), ""), Map::putAll);
        return new Baseline(state.epoch, revisions);
    }

    private Baseline quarantineAndReset(final int type, final String digest, final long operationDeadline)
            throws IOException {
        try (final var metadata = metadata(operationDeadline)) {
            final var state = load();
            final var offset = readPointer(pointer(type, digest));
            final var allocation = allocation(offset);
            if (allocation.freed || allocation.type != type || !allocation.digest.equals(digest)) {
                throw new IOException("Invalid quarantine allocation");
            }
            // Inventory force precedes epoch/root replacement. No writer may reuse this target,
            // even with a baseline obtained after the reset. Only all-slot maintenance removes it.
            writeAllocation(offset, new Allocation(type, false, digest, allocation.size, allocation.mark, true));
            reset(state);
            save(state);
            return coldBaseline(state);
        }
    }

    public Publication publish(final String repositoryIdentity, final Path storage, final Baseline baseline)
            throws IOException {
        Objects.requireNonNull(baseline, "baseline");
        if (configuration.mode() != DependencyCacheConfiguration.Mode.READ_WRITE) {
            return new Publication(0, configuration.resolvedEntries().size(), 0, 0);
        }
        final var keys = keys(repositoryIdentity);
        checkedStorage(storage);
        final var admission = deadline(configuration.gracefulTimeoutSeconds());
        var published = 0;
        var unchanged = 0;
        var conflicted = 0;
        var timedOut = 0;
        try (final var slot = locks.foreground(admission)) {
            if (slot == null) {
                return new Publication(0, 0, 0, keys.size());
            }
            for (final var entry : configuration.resolvedEntries()) {
                final var key = keys.get(entry.id());
                if (System.nanoTime() >= admission) {
                    timedOut++;
                    continue;
                }
                final var source = storage.toAbsolutePath().normalize().resolve(entry.directory());
                checkedPath(source);
                // Missing folders have no candidate to publish. Do not wait for another
                // writer or consume admission time for an entry this attempt did not use.
                if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                    unchanged++;
                    continue;
                }
                try (final var unit = locks.unit(key, admission)) {
                    if (unit == null || System.nanoTime() >= admission) {
                        timedOut++;
                        continue;
                    }
                    // Each admitted folder has one deadline. No metadata wait or snapshot renews it.
                    final var operationDeadline =
                            deadline(configuration.limits().operationTimeoutSeconds());
                    try {
                        recoverStaging(slot.slot(), operationDeadline);
                        final var expected = baseline.revisions().getOrDefault(entry.id(), "");
                        try (final var metadata = metadata(operationDeadline)) {
                            final var state = load();
                            if (state.epoch != baseline.epoch()
                                    || !revision(index.get(state.root, key)).equals(expected)) {
                                conflicted++;
                                continue;
                            }
                            if (!expected.isEmpty()) {
                                rejectCorrupt(MANIFEST, expected);
                            }
                        }
                        final var manifest = work(slot.slot(), "manifest");
                        stage(slot.slot(), configuration.limits().maxManifestBytes(), operationDeadline);
                        final CacheSnapshot.SnapshotResult candidate;
                        try {
                            candidate = new CacheSnapshot(configuration.limits(), operationDeadline)
                                    .capture(source, entry, manifest, (digest, file, size) -> {
                                        try {
                                            installBlob(slot.slot(), digest, file, size, operationDeadline);
                                        } catch (final CacheSnapshot.OperationTimeoutException timeout) {
                                            throw timeout;
                                        } catch (final SourceException failedSource) {
                                            throw failedSource;
                                        } catch (final IOException integrity) {
                                            throw new StoreException(integrity);
                                        }
                                    });
                        } catch (final StoreException integrity) {
                            throw integrity;
                        } catch (final IOException failedSource) {
                            if (failedSource.getSuppressed().length != 0) {
                                throw failedSource;
                            }
                            // A failed or over-limit source entry must not starve independent entries.
                            timedOut++;
                            continue;
                        }
                        checkDeadline(operationDeadline);
                        rejectCorrupt(MANIFEST, candidate.digest());
                        if (candidate.digest().equals(expected)) {
                            unchanged++;
                            continue;
                        }
                        installManifest(candidate.digest(), manifest, operationDeadline);
                        try (final var metadata = metadata(operationDeadline)) {
                            final var state = load();
                            if (state.epoch != baseline.epoch()
                                    || !revision(index.get(state.root, key)).equals(expected)) {
                                conflicted++;
                                continue;
                            }
                            checkPublicationSpace(state);
                            final var replacement = updateIndex(state, key, candidate.digest(), operationDeadline);
                            checkDeadline(operationDeadline);
                            appendPublication(state, key, candidate.digest(), operationDeadline);
                            checkDeadline(operationDeadline);
                            state.root = replacement;
                            state.generation = increment(state.generation);
                            save(state);
                            published++;
                        }
                    } catch (final CacheSnapshot.OperationTimeoutException timeout) {
                        timedOut++;
                    } finally {
                        // Separate fixed cleanup allowance; corruption here propagates, never becomes a miss.
                        recoverStaging(slot.slot(), deadline(1));
                    }
                }
            }
        }
        return new Publication(published, unchanged, conflicted, timedOut);
    }

    public MaintenanceResult maintain(final int budget) throws IOException {
        if (budget < 1) {
            throw new IllegalArgumentException("Maintenance budget must be positive");
        }
        try (final var slots = locks.maintenance()) {
            if (slots == null) {
                // Atomic state replacement permits a consistent, read-only busy report.
                final var state = load();
                return new MaintenanceResult(state.epoch, state.charged, 0, true);
            }
            try (final var metadata = locks.metadata(System.nanoTime())) {
                if (metadata == null) {
                    final var state = load();
                    return new MaintenanceResult(state.epoch, state.charged, 0, true);
                }
                final var state = load();
                final var until = System.nanoTime() + SLICE_NANOS;
                final var limit = Math.min(budget, 4096);
                var processed = 0;
                if (clock.millis() - state.resetAt
                        >= TimeUnit.DAYS.toMillis(configuration.maintenance().resetAfterDays())) {
                    reset(state);
                    save(state);
                    processed++;
                }
                while (processed < limit && System.nanoTime() < until) {
                    // Recover at most one dead slot per budget unit. Owning all slots proves death,
                    // rather than guessing from an age or PID. There are only two fixed staging files.
                    if (state.recoverySlot < state.staging.length) {
                        final var slot = state.recoverySlot++;
                        clearStaging(state, slot);
                        save(state);
                        processed++;
                        continue;
                    }
                    final var maxBytes = configuration.maintenance().maxSizeBytes();
                    if ((state.charged > maxBytes - maxBytes / 4
                                    || state.fifoLength - state.fifoCursor
                                            >= state.fifoCapacity - state.fifoCapacity / 4)
                            && state.fifoCursor < state.fifoLength) {
                        try {
                            evict(state, until);
                        } catch (final CacheSnapshot.OperationTimeoutException timeout) {
                            break;
                        }

                        save(state);
                        processed++;
                        continue;
                    }
                    try {
                        if (!collect(state)) {
                            state.recoverySlot = 0;
                            save(state);
                            break;
                        }
                    } catch (final IOException invalidGraph) {
                        if (state.phase != 1) {
                            throw invalidGraph;
                        }
                        // No sweep may use this partial mark. A fresh generation starts empty.
                        reset(state);
                    }

                    save(state);
                    processed++;
                }
                return new MaintenanceResult(state.epoch, state.charged, processed, false);
            }
        }
    }

    private void checkEmptyLayout() throws IOException {
        for (final var name : List.of("blobs", "manifests", "index", "work")) {
            try (final var entries = Files.newDirectoryStream(root.resolve(name))) {
                if (entries.iterator().hasNext()) {
                    throw new IOException("Cannot initialize an unaccounted cache layout");
                }
            }
        }
        try (final var entries = Files.newDirectoryStream(root)) {
            for (final var path : entries) {
                switch (path.getFileName().toString()) {
                    case "blobs", "manifests", "index", "work" -> {
                        // Checked empty above; no recursive startup traversal.
                    }
                    case "locks", "inventory", "publications" -> regular(path, 0);
                    case "state.next" -> {
                        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                                || Files.size(path) > stateAllowance / 2) {
                            throw new IOException("Invalid interrupted cache initialization");
                        }
                    }
                    default -> throw new IOException("Unknown file in uninitialized cache root: " + path);
                }
            }
        }
    }

    private Map<String, String> keys(final String repositoryIdentity) throws IOException {
        if (repositoryIdentity == null || repositoryIdentity.isBlank()) {
            throw new IllegalArgumentException("Immutable hosting API and repository identity are required");
        }
        final var keys = new LinkedHashMap<String, String>();
        for (final var entry : configuration.resolvedEntries()) {
            final var bytes = new ByteArrayOutputStream();
            try (final var out = new DataOutputStream(bytes)) {
                text(out, "dependency-cache-key-v1");
                text(out, repositoryIdentity);
                text(out, configuration.scope().name());
                text(out, configuration.compatibility());
                text(out, entry.id());
                text(out, entry.kind().name());
                text(out, entry.directory());
                out.writeInt(entry.excludeDirectoryNameSuffixes().size());
                for (final var suffix : entry.excludeDirectoryNameSuffixes()) {
                    text(out, suffix);
                }
                final var environment = new TreeMap<>(configuration.resolvedEnvironment());
                out.writeInt(environment.size());
                for (final var item : environment.entrySet()) {
                    text(out, item.getKey());
                    text(out, item.getValue());
                }
                final var files = configuration.resolvedFiles().stream()
                        .collect(
                                TreeMap<String, String>::new,
                                (result, file) -> result.put(file.path(), file.content()),
                                Map::putAll);
                out.writeInt(files.size());
                for (final var file : files.entrySet()) {
                    text(out, file.getKey());
                    text(out, file.getValue());
                }
            }
            keys.put(entry.id(), HEX.formatHex(sha256().digest(bytes.toByteArray())));
        }
        return keys;
    }

    private void checkedStorage(final Path storage) throws IOException {
        final var normalized = storage.toAbsolutePath().normalize();
        if (normalized.startsWith(root) || root.startsWith(normalized)) {
            throw new IOException("Attempt storage and cache root must be disjoint");
        }
        checkedPath(normalized);
    }

    private void installBlob(
            final int slot, final String digest, final Path source, final long size, final long operationDeadline)
            throws IOException {
        checkDeadline(operationDeadline);
        rejectCorrupt(BLOB, digest);
        final var target = object(BLOB, digest);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            regular(target, size);
            return;
        }
        // Exact reservation before the first temporary blob byte is written.
        stage(slot, size, operationDeadline);
        final var temporary = work(slot, "blob");
        try {
            reserveObject(BLOB, digest, size, operationDeadline);
            final var hash = sha256();
            long copied = 0;
            try (final var in = sourceInput(source);
                    final var out = FileChannel.open(
                            temporary,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS)) {
                final var buffer = new byte[65536];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (System.nanoTime() >= operationDeadline) {
                        throw new CacheSnapshot.OperationTimeoutException("Cache blob installation timed out");
                    }
                    if (count > size - copied) {
                        throw new SourceException("Blob grew during snapshot capture", null);
                    }
                    copied += count;
                    hash.update(buffer, 0, count);
                    write(out, ByteBuffer.wrap(buffer, 0, count));
                }
                if (copied != size || !HEX.formatHex(hash.digest()).equals(digest)) {
                    throw new SourceException("Blob changed during snapshot capture", null);
                }
                out.force(true);
            }
            link(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
            forceDirectory(root.resolve("work"));
            if (System.nanoTime() < operationDeadline) {
                unstage(slot, size, operationDeadline);
            }
        }
    }

    private void installManifest(final String digest, final Path manifest, final long operationDeadline)
            throws IOException {
        checkDeadline(operationDeadline);
        rejectCorrupt(MANIFEST, digest);
        final var size = Files.size(manifest);
        reserveObject(MANIFEST, digest, size, operationDeadline);
        force(manifest);
        link(manifest, object(MANIFEST, digest));
    }

    private void reserveObject(final int type, final String digest, final long size, final long operationDeadline)
            throws IOException {
        try (final var metadata = metadata(operationDeadline)) {
            final var state = load();
            final var pointer = pointer(type, digest);
            if (Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)) {
                final var allocation = allocation(readPointer(pointer));
                if (allocation.type != type
                        || !allocation.digest.equals(digest)
                        || allocation.size != size
                        || allocation.freed
                        || allocation.corrupt) {
                    throw new IOException("Invalid cache allocation pointer");
                }
                return;
            }
            allocate(state, type, digest, size, false);
        }
    }

    /** Caller owns metadata lock; charges are persisted before inventory/data bytes. */
    private long allocate(
            final State state, final int type, final String digest, final long size, final boolean prepaid)
            throws IOException {
        checkMetadata(state, RECORD);
        final var reused = state.freeHead != -1;
        final var offset = reused ? state.freeHead : state.inventoryLength;
        final var next = reused ? freeSuccessor(state, offset) : -1;
        final var overhead = (reused ? 0 : RECORD) + (type == RESERVATION ? 0 : Long.BYTES);
        if (size < 0 || size > Long.MAX_VALUE - overhead) {
            throw new CapacityException("Dependency cache allocation size overflow");
        }
        final var charge = size + overhead;
        if (!prepaid) {
            charge(state, charge);
        }
        if (reused) {
            state.freeHead = next;
            state.freeCount--;
        } else {
            state.inventoryLength += RECORD;
        }
        // Remove a reused slot from the durable list before overwriting it or installing its pointer.
        // A crash here loses a free slot until the next sweep, but cannot allocate it twice.
        save(state);
        writeAllocation(offset, new Allocation(type, false, digest, size, 0, false));
        if (type != RESERVATION) {
            final var pointer = pointer(type, digest);
            directory(pointer.getParent());
            try (final var channel = FileChannel.open(
                    pointer, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                write(channel, ByteBuffer.allocate(Long.BYTES).putLong(offset).flip());
                channel.force(true);
            }
            forceDirectory(pointer.getParent());
        }
        return offset;
    }

    private long freeSuccessor(final State state, final long offset) throws IOException {
        if (offset < 0 || offset >= state.inventoryLength || offset % RECORD != 0 || state.freeCount < 1) {
            throw new IOException("Invalid cache free-list head");
        }
        final var record = allocation(offset);
        final var next = record.mark;
        if (!record.freed
                || record.type < BLOB
                || record.type > RESERVATION
                || record.size < 0
                || next < -1
                || next >= offset
                || (next != -1 && next % RECORD != 0)
                || (next == -1) != (state.freeCount == 1)) {
            throw new IOException("Invalid cache free-list link");
        }
        if (next != -1 && !allocation(next).freed) {
            throw new IOException("Cache free-list references a live allocation");
        }
        if (record.type != RESERVATION) {
            final var oldPointer = pointer(record.type, record.digest);
            if (Files.exists(oldPointer, LinkOption.NOFOLLOW_LINKS)) {
                final var owner = readPointer(oldPointer);
                if (owner == offset || owner >= state.inventoryLength) {
                    throw new IOException("Freed cache allocation still owns an object pointer");
                }
                // The same digest can have been installed again in another slot while this
                // freed record waited in the inventory. Never delete that object's new pointer.
                final var current = allocation(owner);
                if (current.freed || current.type != record.type || !current.digest.equals(record.digest)) {
                    throw new IOException("Invalid replacement cache allocation pointer");
                }
            }
        }
        return next;
    }

    private String updateIndex(final State state, final String key, final String value, final long operationDeadline)
            throws IOException {
        // CacheIndex has a bounded search path but no installation observer. Reserve that
        // entire path before invoking it, then inventory only new nodes by following new edges.
        checkMetadata(state, (MAX_NEW_NODES + 1L) * RECORD);
        final var reservation = allocate(
                state,
                RESERVATION,
                HEX.formatHex(sha256().digest(UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII))),
                INDEX_RESERVATION,
                false);
        final var replacement = index.put(state.root, key, value);
        // A durable pointer certifies the complete immutable subgraph. Register new nodes
        // postorder, so a crash can never expose a parent pointer before its children.
        final var pending = new ArrayList<String>();
        final var postorder = new ArrayList<String>();
        final var seen = new HashSet<String>();
        if (!replacement.isEmpty()) {
            pending.add(replacement);
        }
        while (!pending.isEmpty()) {
            checkDeadline(operationDeadline);
            final var digest = pending.removeLast();
            if (!seen.add(digest)) {
                continue;
            }
            if (Files.exists(pointer(INDEX, digest), LinkOption.NOFOLLOW_LINKS)) {
                rejectCorrupt(INDEX, digest);
                continue;
            }
            if (postorder.size() >= MAX_NEW_NODES) {
                throw new IOException("Index exceeded its bounded mutation allocation");
            }
            final var node = index.inspect(digest);
            postorder.add(digest);
            pending.addAll(node.children());
        }
        long actual = 0;
        for (final var digest : postorder.reversed()) {
            checkDeadline(operationDeadline);
            final var file = object(INDEX, digest);
            final var size = Files.size(file);
            // File and shard durability must precede the certifying allocation pointer.
            force(file);
            forceDirectory(file.getParent());
            forceDirectory(root.resolve("index"));
            actual += size + (state.freeHead == -1 ? RECORD : 0) + Long.BYTES;
            allocate(state, INDEX, digest, size, true);
        }
        // CacheIndex creates its own digest shards. Persist those directory entries too.
        forceDirectory(root.resolve("index"));
        final var held = allocation(reservation);
        // Do not insert here: a sweep may not have reached this offset yet. Only sweep inserts,
        // in ascending offset order, so every free-list link is strictly decreasing.
        writeAllocation(reservation, new Allocation(RESERVATION, true, held.digest, 0, -1, false));
        state.charged -= held.size - actual;
        save(state);
        return replacement;
    }

    private void appendPublication(
            final State state, final String key, final String revision, final long operationDeadline)
            throws IOException {
        checkDeadline(operationDeadline);
        checkPublicationSpace(state);
        final var offset = state.fifoLength % state.fifoCapacity;
        final var sequence = state.fifoLength / FIFO_RECORD + 1;
        state.fifoLength += FIFO_RECORD;
        // Reserve the absolute sequence before touching a consumed slot. Interrupted appends
        // leave a hole, never an unaccounted publication or an overwritten unconsumed record.
        save(state);
        final var bytes = ByteBuffer.allocate(FIFO_RECORD)
                .putLong(state.epoch)
                .put(HEX.parseHex(key))
                .put(HEX.parseHex(revision))
                .putLong(sequence)
                .flip();
        try (final var channel = FileChannel.open(
                root.resolve("publications"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            channel.position(offset);
            write(channel, bytes);
            channel.force(true);
        }
        forceDirectory(root);
    }

    private void checkPublicationSpace(final State state) throws IOException {
        if (state.fifoLength > Long.MAX_VALUE - FIFO_RECORD
                || state.fifoLength - state.fifoCursor >= state.fifoCapacity) {
            throw new CapacityException("Dependency cache publication ring is full; maintenance required");
        }
    }

    private void evict(final State state, final long operationDeadline) throws IOException {
        final var bytes = readRecord(root.resolve("publications"), state.fifoCursor % state.fifoCapacity, FIFO_RECORD);
        final var nextCursor = state.fifoCursor + FIFO_RECORD;
        final var epoch = bytes.getLong();
        final var key = new byte[32];
        final var revision = new byte[32];
        bytes.get(key).get(revision);
        final var sequence = bytes.getLong();
        final var expectedSequence = state.fifoCursor / FIFO_RECORD + 1;
        if (sequence < 0 || sequence > expectedSequence) {
            throw new IOException("Invalid cache publication sequence");
        }
        String current = null;
        if (sequence == expectedSequence && epoch == state.epoch) {
            try {
                current = index.get(state.root, HEX.formatHex(key));
            } catch (final IOException corruptIndex) {
                reset(state);
                state.fifoCursor = nextCursor;
                return;
            }
        }
        if (HEX.formatHex(revision).equals(current)) {
            try {
                state.root = updateIndex(state, HEX.formatHex(key), null, operationDeadline);
                state.generation = increment(state.generation);
            } catch (final CapacityException noHeadroom) {
                // At hard quota no index path can be installed. An O(1) epoch reset is the
                // conservative pressure escape hatch; never write outside the quota to evict.
                reset(state);
            }
        }
        // Index accounting persists intermediate states. Do not advance this cursor until the
        // replacement root is ready to commit, or a crash could permanently skip a live entry.
        state.fifoCursor = nextCursor;
    }

    private void reset(final State state) throws IOException {
        state.epoch = increment(state.epoch);
        state.generation = increment(state.generation);
        state.root = CacheIndex.EMPTY;
        state.resetAt = clock.millis();
        state.stack.clear();
        state.phase = 0;
        state.sweep = 0;
    }

    /** One bounded piece of mark or sweep. Returns false after the completed inventory pass. */
    private boolean collect(final State state) throws IOException {
        if (state.gcGeneration != state.generation || state.phase == 0) {
            state.stack.clear();
            if (!state.root.isEmpty()) {
                state.stack.add(new Grey(INDEX, state.root, 0));
            }
            state.gcGeneration = state.generation;
            state.phase = 1;
            state.sweep = 0;
        }
        if (state.phase == 1) {
            if (state.stack.isEmpty()) {
                state.phase = 2;
                // Rebuild incrementally, including freed slots lost by interrupted allocations.
                // Old links must not remain reachable when this pass starts linking slots again.
                state.freeHead = -1;
                state.freeCount = 0;
                return true;
            }
            final var grey = state.stack.removeLast();
            if (grey.type == INDEX) {
                mark(INDEX, grey.digest, state.gcGeneration);
                // Traverse even an already-marked node: a crash can persist its mark before
                // persisting the pushed children. The immutable index is a bounded-depth tree.
                final var node = index.inspect(grey.digest);
                for (final var child : node.children()) {
                    push(state, new Grey(INDEX, child, 0));
                }
                if (node.value() != null) {
                    push(state, new Grey(MANIFEST, node.value(), 0));
                }
            } else {
                if (grey.offset == 0) {
                    mark(MANIFEST, grey.digest, state.gcGeneration);
                }
                final CacheManifestReferences.Reference reference;
                try {
                    reference = CacheManifestReferences.next(object(MANIFEST, grey.digest), grey.offset);
                } catch (final IOException invalidManifest) {
                    final var offset = readPointer(pointer(MANIFEST, grey.digest));
                    final var allocation = allocation(offset);
                    writeAllocation(
                            offset,
                            new Allocation(MANIFEST, false, grey.digest, allocation.size, allocation.mark, true));
                    throw invalidManifest;
                }

                if (reference.digest() != null) {
                    mark(BLOB, reference.digest(), state.gcGeneration);
                }
                if (!reference.done()) {
                    push(state, new Grey(MANIFEST, grey.digest, reference.offset()));
                }
            }
            return true;
        }
        if (state.sweep >= state.inventoryLength) {
            state.phase = 0;
            return false;
        }
        final var offset = state.sweep;
        var allocation = allocation(offset);
        state.sweep += RECORD;
        if (allocation.type >= BLOB && allocation.type <= INDEX && !allocation.freed) {
            final var pointer = pointer(allocation.type, allocation.digest);
            final var ownsTarget = Files.exists(pointer, LinkOption.NOFOLLOW_LINKS) && readPointer(pointer) == offset;
            if (!ownsTarget || allocation.mark != state.gcGeneration) {
                if (ownsTarget) {
                    final var file = object(allocation.type, allocation.digest);
                    Files.deleteIfExists(file);
                    Files.deleteIfExists(pointer);
                    forceDirectory(file.getParent());
                }
                // A crash before pointer installation can leave duplicate records on retry.
                // Release only this orphan's conservative charge, never another owner's target.
                allocation = new Allocation(allocation.type, true, allocation.digest, allocation.size, -1, false);
                writeAllocation(offset, allocation);
                state.charged -= allocation.size + Long.BYTES;
            }
        }
        if (allocation.freed) {
            writeAllocation(
                    offset,
                    new Allocation(allocation.type, true, allocation.digest, allocation.size, state.freeHead, false));
            state.freeHead = offset;
            state.freeCount++;
        }
        return true;
    }

    private void mark(final int type, final String digest, final long generation) throws IOException {
        final var offset = readPointer(pointer(type, digest));
        final var allocation = allocation(offset);
        if (allocation.type != type || !allocation.digest.equals(digest) || allocation.freed || allocation.corrupt) {
            throw new IOException("Live graph references an invalid allocation");
        }
        regular(object(type, digest), allocation.size);
        if (allocation.mark == generation) {
            return;
        }
        writeAllocation(offset, new Allocation(type, false, digest, allocation.size, generation, allocation.corrupt));
    }

    private void push(final State state, final Grey grey) throws IOException {
        if (state.stack.size() >= MAX_STACK) {
            throw new IOException("Cache mark stack bound exceeded");
        }
        state.stack.add(grey);
    }

    private void recoverStaging(final int slot, final long operationDeadline) throws IOException {
        try (final var metadata = metadata(operationDeadline)) {
            final var state = load();
            clearStaging(state, slot);
            save(state);
        }
    }

    private void clearStaging(final State state, final int slot) throws IOException {
        Files.deleteIfExists(work(slot, "blob"));
        Files.deleteIfExists(work(slot, "manifest"));
        forceDirectory(root.resolve("work"));
        state.charged -= state.staging[slot];
        state.staging[slot] = 0;
    }

    private void stage(final int slot, final long bytes, final long operationDeadline) throws IOException {
        try (final var metadata = metadata(operationDeadline)) {
            final var state = load();
            charge(state, bytes);
            state.staging[slot] = Math.addExact(state.staging[slot], bytes);
            save(state);
        }
    }

    private void unstage(final int slot, final long bytes, final long operationDeadline) throws IOException {
        try (final var metadata = metadata(operationDeadline)) {
            final var state = load();
            if (bytes > state.staging[slot]) {
                throw new IOException("Invalid staging reservation");
            }
            state.staging[slot] -= bytes;
            state.charged -= bytes;
            save(state);
        }
    }

    private void charge(final State state, final long bytes) throws IOException {
        if (bytes < 0 || bytes > configuration.maintenance().maxSizeBytes() - state.charged) {
            throw new CapacityException("Dependency cache byte quota exhausted");
        }
        state.charged += bytes;
    }

    private void checkMetadata(final State state, final long bytes) throws IOException {
        final var newRecords = Math.max(0, bytes / RECORD - state.freeCount);
        if (newRecords > (state.inventoryCapacity - state.inventoryLength) / RECORD) {
            throw new CapacityException("Dependency cache inventory is full; maintenance required");
        }
    }

    private Path work(final int slot, final String name) {
        return root.resolve("work").resolve(slot + "-" + name);
    }

    private Path object(final int type, final String digest) throws IOException {
        if (digest == null || !CACHE_DIGEST.matcher(digest).matches()) {
            throw new IOException("Invalid cache object digest");
        }
        final var directory =
                switch (type) {
                    case BLOB -> "blobs";
                    case MANIFEST -> "manifests";
                    case INDEX -> "index";
                    default -> throw new IOException("Invalid cache object type");
                };
        final var path = root.resolve(directory).resolve(digest.substring(0, 2)).resolve(digest.substring(2));
        checkedPath(path);
        return path;
    }

    private void rejectCorrupt(final int type, final String digest) throws IOException {
        final var pointer = pointer(type, digest);
        if (!Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        final var allocation = allocation(readPointer(pointer));
        if (allocation.freed || allocation.type != type || !allocation.digest.equals(digest)) {
            throw new StoreException(new IOException("Invalid cache allocation pointer"));
        }
        if (allocation.corrupt) {
            throw new CorruptObjectException(type, digest);
        }
    }

    private Path pointer(final int type, final String digest) throws IOException {
        final var object = object(type, digest);
        return object.resolveSibling(object.getFileName() + ".allocation");
    }

    private long readPointer(final Path path) throws IOException {
        regular(path, Long.BYTES);
        return readRecord(path, 0, Long.BYTES).getLong();
    }

    private Allocation allocation(final long offset) throws IOException {
        if (offset < 0 || offset % RECORD != 0) {
            throw new IOException("Invalid allocation offset");
        }
        final var bytes = readRecord(root.resolve("inventory"), offset, RECORD);
        final var type = bytes.getInt();
        final var freed = bytes.getInt() != 0;
        final var digest = new byte[32];
        bytes.get(digest);
        return new Allocation(
                type, freed, HEX.formatHex(digest), bytes.getLong(), bytes.getLong(), bytes.getLong() != 0);
    }

    private void writeAllocation(final long offset, final Allocation allocation) throws IOException {
        final var bytes = ByteBuffer.allocate(RECORD)
                .putInt(allocation.type)
                .putInt(allocation.freed ? 1 : 0)
                .put(HEX.parseHex(allocation.digest))
                .putLong(allocation.size)
                .putLong(allocation.mark)
                .putLong(allocation.corrupt ? 1 : 0)
                .flip();
        try (final var channel = FileChannel.open(
                root.resolve("inventory"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            channel.position(offset);
            write(channel, bytes);
            channel.force(true);
        }
    }

    private ByteBuffer readRecord(final Path file, final long offset, final int length) throws IOException {
        final var bytes = ByteBuffer.allocate(length);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            try (final var channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                channel.position(offset);
                while (bytes.hasRemaining() && channel.read(bytes) != -1) {
                    // A reserved record can be absent after a crash. Zero-filled holes remain charged.
                }
            }
        }
        bytes.position(0);
        return bytes;
    }

    private CacheLocks.Lease metadata(final long operationDeadline) throws IOException {
        checkDeadline(operationDeadline);
        final var lock = locks.metadata(operationDeadline);
        if (lock == null) {
            throw new CacheSnapshot.OperationTimeoutException("Cache metadata lock timed out");
        }
        try {
            checkDeadline(operationDeadline);
            return lock;
        } catch (final IOException timeout) {
            lock.close();
            throw timeout;
        }
    }

    private State load() throws IOException {
        final var file = root.resolve("state");
        checkedPath(file);
        final var length = Files.size(file);
        if (length > stateAllowance / 2 || length < 64) {
            throw new IOException("Invalid cache state size");
        }
        final var state = new State();
        try (final var in = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(file)))) {
            if (in.readInt() != MAGIC || in.readInt() != 2) {
                throw new IOException("Unsupported dependency cache store");
            }
            state.epoch = in.readLong();
            state.generation = in.readLong();
            state.resetAt = in.readLong();
            state.charged = in.readLong();
            state.root = in.readUTF();
            state.inventoryLength = in.readLong();
            state.fifoLength = in.readLong();
            state.fifoCursor = in.readLong();
            state.inventoryCapacity = in.readLong();
            state.fifoCapacity = in.readLong();
            state.freeHead = in.readLong();
            state.freeCount = in.readLong();
            state.gcGeneration = in.readLong();
            state.phase = in.readInt();
            state.sweep = in.readLong();
            state.recoverySlot = in.readInt();
            final var slots = in.readInt();
            if (slots != configuration.limits().maxConcurrentOperations()) {
                throw new IOException("All stores sharing a root must use the same maxConcurrentOperations");
            }
            state.staging = new long[slots];
            for (var i = 0; i < slots; i++) {
                state.staging[i] = in.readLong();
            }
            final var count = in.readInt();
            if (count < 0 || count > MAX_STACK) {
                throw new IOException("Invalid cache mark stack");
            }
            for (var i = 0; i < count; i++) {
                state.stack.add(new Grey(in.readInt(), in.readUTF(), in.readLong()));
            }
            if (in.read() != -1
                    || state.epoch < 1
                    || state.generation < 1
                    || state.charged < stateAllowance
                    || state.inventoryLength < 0
                    || state.inventoryLength % RECORD != 0
                    || state.inventoryCapacity < (MAX_NEW_NODES + 3L) * RECORD
                    || state.inventoryCapacity % RECORD != 0
                    || state.inventoryLength > state.inventoryCapacity
                    || state.fifoCapacity < FIFO_RECORD
                    || state.fifoCapacity % FIFO_RECORD != 0
                    || state.fifoCapacity > configuration.limits().maxManifestBytes()
                    || state.inventoryCapacity > configuration.limits().maxManifestBytes() - state.fifoCapacity
                    || state.charged - stateAllowance < state.fifoCapacity
                    || state.charged - stateAllowance - state.fifoCapacity < state.inventoryLength
                    || state.fifoLength < 0
                    || state.fifoLength % FIFO_RECORD != 0
                    || state.fifoCursor < 0
                    || state.fifoCursor > state.fifoLength
                    || state.fifoCursor % FIFO_RECORD != 0
                    || state.fifoLength - state.fifoCursor > state.fifoCapacity
                    || state.freeHead < -1
                    || state.freeHead >= state.inventoryLength
                    || (state.freeHead != -1 && state.freeHead % RECORD != 0)
                    || state.freeCount < 0
                    || state.freeCount > state.inventoryLength / RECORD
                    || (state.freeHead == -1) != (state.freeCount == 0)
                    || (state.freeHead != -1 && state.freeCount > state.freeHead / RECORD + 1)
                    || state.sweep < 0
                    || state.sweep > state.inventoryLength
                    || state.sweep % RECORD != 0
                    || state.gcGeneration < 0
                    || state.gcGeneration > state.generation
                    || state.phase < 0
                    || state.phase > 2
                    || state.recoverySlot < 0
                    || state.recoverySlot > slots) {
                throw new IOException("Invalid cache state");
            }
        }
        return state;
    }

    private void save(final State state) throws IOException {
        final var buffer = new ByteArrayOutputStream();
        try (final var out = new DataOutputStream(buffer)) {
            out.writeInt(MAGIC);
            out.writeInt(2);
            out.writeLong(state.epoch);
            out.writeLong(state.generation);
            out.writeLong(state.resetAt);
            out.writeLong(state.charged);
            out.writeUTF(state.root);
            out.writeLong(state.inventoryLength);
            out.writeLong(state.fifoLength);
            out.writeLong(state.fifoCursor);
            out.writeLong(state.inventoryCapacity);
            out.writeLong(state.fifoCapacity);
            out.writeLong(state.freeHead);
            out.writeLong(state.freeCount);
            out.writeLong(state.gcGeneration);
            out.writeInt(state.phase);
            out.writeLong(state.sweep);
            out.writeInt(state.recoverySlot);
            out.writeInt(state.staging.length);
            for (final var reservation : state.staging) {
                out.writeLong(reservation);
            }
            out.writeInt(state.stack.size());
            for (final var grey : state.stack) {
                out.writeInt(grey.type);
                out.writeUTF(grey.digest);
                out.writeLong(grey.offset);
            }
        }
        if (buffer.size() > stateAllowance / 2) {
            throw new IOException("Bounded cache state overflow");
        }
        final var temporary = root.resolve("state.next");
        checkedPath(temporary);
        try (final var channel = FileChannel.open(
                temporary,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            write(channel, ByteBuffer.wrap(buffer.toByteArray()));
            channel.force(true);
        }
        Files.move(
                temporary, root.resolve("state"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        forceDirectory(root);
    }

    private void link(final Path source, final Path target) throws IOException {
        directory(target.getParent());
        try {
            Files.createLink(target, source); // Only private store staging is ever hardlinked.
        } catch (final FileAlreadyExistsException reused) {
            regular(target, Files.size(source));
        }
        forceDirectory(target.getParent());
    }

    private static void verify(final Path file, final String digest, final long size, final long operationDeadline)
            throws IOException {
        try {
            regular(file, size);
        } catch (final IOException invalidManifest) {
            throw new CorruptObjectException(MANIFEST, digest);
        }
        final var hash = sha256();
        try (final var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            final var buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) {
                checkDeadline(operationDeadline);
                hash.update(buffer, 0, count);
            }
        }
        if (!HEX.formatHex(hash.digest()).equals(digest)) {
            throw new CorruptObjectException(MANIFEST, digest);
        }
    }

    private static void regular(final Path file, final long size) throws IOException {
        final var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() != size) {
            throw new IOException("Invalid cache object size or type");
        }
    }

    private static void checkedPath(final Path path) throws IOException {
        var current = path.toAbsolutePath().getRoot();
        for (final var part : path.toAbsolutePath()) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Cache paths cannot contain symbolic links: " + current);
            }
        }
    }

    private static void directory(final Path directory) throws IOException {
        var current = directory.toAbsolutePath().getRoot();
        for (final var component : directory.toAbsolutePath()) {
            current = current.resolve(component);
            try {
                Files.createDirectory(
                        current, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                forceDirectory(current.getParent());
            } catch (final FileAlreadyExistsException existing) {
                // Never follow a symlink supplied in a cache or destination ancestor.
            }
            if (!Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .isDirectory()) {
                throw new IOException("Expected a real directory: " + current);
            }
        }
    }

    private static void removeTree(final Path tree, final long operationDeadline) throws IOException {
        checkDeadline(operationDeadline);
        if (Files.readAttributes(tree, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                .isDirectory()) {
            // Snapshot rwx modes can make a completely restored directory unreadable. This is
            // exclusively our private staging tree, so restore owner access before cleanup.
            Files.setPosixFilePermissions(tree, PosixFilePermissions.fromString("rwx------"));
            try (final var children = Files.newDirectoryStream(tree)) {
                for (final var child : children) {
                    removeTree(child, operationDeadline);
                }
            }
        }
        Files.delete(tree);
    }

    private static void force(final Path file) throws IOException {
        try (final var channel = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
    }

    private static void forceDirectory(final Path directory) throws IOException {
        try (final var channel = FileChannel.open(directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
    }

    private static void write(final FileChannel channel, final ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) {
            channel.write(bytes);
        }
    }

    private static void text(final DataOutputStream out, final String value) throws IOException {
        final var bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static void checkDeadline(final long operationDeadline) throws CacheSnapshot.OperationTimeoutException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= operationDeadline) {
            throw new CacheSnapshot.OperationTimeoutException("Cache operation deadline exceeded");
        }
    }

    private static long deadline(final int seconds) {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    }

    private static long increment(final long value) throws IOException {
        if (value == Long.MAX_VALUE) {
            throw new IOException("Cache generation exhausted");
        }
        return value + 1;
    }

    private static String revision(final String value) {
        return value == null ? "" : value;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Allocation(int type, boolean freed, String digest, long size, long mark, boolean corrupt) {}

    private record Grey(int type, String digest, long offset) {}

    private static InputStream sourceInput(final Path source) throws IOException {
        final InputStream input;
        try {
            input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
        } catch (final IOException failedSource) {
            throw new SourceException("Cannot open snapshot source", failedSource);
        }
        return new FilterInputStream(input) {
            @Override
            public int read(final byte[] buffer, final int offset, final int length) throws IOException {
                try {
                    return in.read(buffer, offset, length);
                } catch (final IOException failedSource) {
                    throw new SourceException("Cannot read snapshot source", failedSource);
                }
            }
        };
    }

    private static final class SourceException extends IOException {
        private SourceException(final String message, final IOException cause) {
            super(message, cause);
        }
    }

    private static final class StoreException extends IOException {
        private StoreException(final IOException cause) {
            super(cause);
        }
    }

    private static final class CorruptObjectException extends IOException {
        private final int type;
        private final String digest;

        private CorruptObjectException(final int type, final String digest) {
            super("Known corrupt cache object: " + digest);
            this.type = type;
            this.digest = digest;
        }
    }

    private static final class CapacityException extends IOException {
        private CapacityException(final String message) {
            super(message);
        }
    }

    private static final class State {
        private long epoch;
        private long generation;
        private long resetAt;
        private long charged;
        private String root = CacheIndex.EMPTY;
        private long inventoryLength;
        private long fifoLength;
        private long fifoCursor;
        private long inventoryCapacity;
        private long fifoCapacity;
        private long freeHead = -1;
        private long freeCount;
        private long gcGeneration;
        private int phase;
        private long sweep;
        private int recoverySlot;
        private long[] staging;
        private final List<Grey> stack = new ArrayList<>();
    }
}
