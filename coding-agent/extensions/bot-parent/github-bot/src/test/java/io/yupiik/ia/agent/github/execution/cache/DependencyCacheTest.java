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

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencyCacheTest {
    private static final String REPOSITORY = "https://api.github.example/repositories/42";
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporary;

    @Test
    void branchAttemptsShareOnlyExplicitImmutableRepositoryIdentity() throws Exception {
        final var policy = policy(16_000_000, List.of(entry("tool")), 2);
        final var store = new DependencyCache(policy, NOW);
        final var branchOne = temporary.resolve("attempt-branch-one/storage");
        final var baseline = store.restore(REPOSITORY, branchOne);
        assertEquals(Map.of("tool", ""), baseline.revisions());
        file(branchOne, "tool/artifact", "release");
        assertEquals(new DependencyCache.Publication(1, 0, 0, 0), store.publish(REPOSITORY, branchOne, baseline));

        // The branch and transient attempt paths deliberately never enter the key.
        final var branchTwo = temporary.resolve("attempt-branch-two/storage");
        final var observed = new DependencyCache(policy, NOW).restore(REPOSITORY, branchTwo);
        assertFalse(observed.revisions().get("tool").isEmpty());
        assertEquals("release", Files.readString(branchTwo.resolve("tool/artifact")));

        final var other = temporary.resolve("other-repository/storage");
        assertEquals(
                "",
                store.restore("https://api.github.example/repositories/43", other)
                        .revisions()
                        .get("tool"));
        assertFalse(Files.exists(other.resolve("tool/artifact")));
        final var otherHost = temporary.resolve("other-host/storage");
        assertEquals(
                "",
                store.restore("https://api.other.example/repositories/42", otherHost)
                        .revisions()
                        .get("tool"));
    }

    @Test
    void staleWholeEntryCannotOverwriteWinnerAndUnrelatedRepositoryIsPreserved() throws Exception {
        final var store = new DependencyCache(policy(16_000_000, List.of(entry("tool")), 2), NOW);
        final var first = temporary.resolve("first");
        final var second = temporary.resolve("second");
        final var a = store.restore(REPOSITORY, first);
        final var b = store.restore(REPOSITORY, second);
        file(first, "tool/a", "winner");
        file(second, "tool/b", "loser");
        assertEquals(1, store.publish(REPOSITORY, first, a).published());
        assertEquals(new DependencyCache.Publication(0, 0, 1, 0), store.publish(REPOSITORY, second, b));

        final var unrelated = temporary.resolve("unrelated");
        final var otherBaseline = store.restore("other-host/repository/7", unrelated);
        file(unrelated, "tool/c", "independent");
        assertEquals(
                1,
                store.publish("other-host/repository/7", unrelated, otherBaseline)
                        .published());
        final var restored = temporary.resolve("restored");
        store.restore(REPOSITORY, restored);
        assertEquals("winner", Files.readString(restored.resolve("tool/a")));
        assertFalse(Files.exists(restored.resolve("tool/b")));
    }

    @Test
    void missingIsNotPublishedButAnEmptyDirectoryIsARealRevision() throws Exception {
        final var store = new DependencyCache(policy(16_000_000, List.of(entry("tool")), 2), NOW);
        final var attempt = temporary.resolve("attempt");
        final var baseline = store.restore(REPOSITORY, attempt);
        assertEquals(new DependencyCache.Publication(0, 1, 0, 0), store.publish(REPOSITORY, attempt, baseline));
        Files.createDirectories(attempt.resolve("tool"));
        assertEquals(1, store.publish(REPOSITORY, attempt, baseline).published());
        final var restored = temporary.resolve("restored");
        final var empty = store.restore(REPOSITORY, restored);
        assertFalse(empty.revisions().get("tool").isEmpty());
        assertTrue(Files.isDirectory(restored.resolve("tool")));
        try (final var children = Files.list(restored.resolve("tool"))) {
            assertEquals(0, children.count());
        }
        assertEquals(new DependencyCache.Publication(0, 1, 0, 0), store.publish(REPOSITORY, restored, empty));
    }

    @Test
    void unchangedPublicationDoesNotRewriteObjectsOrAppendInventory() throws Exception {
        final var policy = policy(16_000_000, List.of(entry("tool")), 2);
        final var store = new DependencyCache(policy, NOW);
        final var source = temporary.resolve("source");
        final var before = store.restore(REPOSITORY, source);
        file(source, "tool/artifact", "same");
        store.publish(REPOSITORY, source, before);
        final var restored = temporary.resolve("restored");
        final var baseline = store.restore(REPOSITORY, restored);
        final var objects = new ArrayList<Path>();
        for (final var name : List.of("blobs", "manifests", "index")) {
            try (final var files = Files.walk(Path.of(policy.root()).resolve(name))) {
                objects.addAll(files.filter(Files::isRegularFile).toList());
            }
        }
        final var attributes = objects.stream()
                .map(path -> {
                    try {
                        return Files.readAttributes(path, BasicFileAttributes.class);
                    } catch (final IOException error) {
                        throw new IllegalStateException(error);
                    }
                })
                .toList();
        final var inventoryLength = Files.size(Path.of(policy.root()).resolve("inventory"));
        final var fifoLength = Files.size(Path.of(policy.root()).resolve("publications"));
        assertEquals(new DependencyCache.Publication(0, 1, 0, 0), store.publish(REPOSITORY, restored, baseline));
        assertEquals(inventoryLength, Files.size(Path.of(policy.root()).resolve("inventory")));
        assertEquals(fifoLength, Files.size(Path.of(policy.root()).resolve("publications")));
        for (var i = 0; i < objects.size(); i++) {
            final var after = Files.readAttributes(objects.get(i), BasicFileAttributes.class);
            assertEquals(attributes.get(i).fileKey(), after.fileKey());
            assertEquals(attributes.get(i).lastModifiedTime(), after.lastModifiedTime());
        }
    }

    @Test
    void resetFencesAWriterThatRestoredAnEarlierEpoch() throws Exception {
        final var policy = policy(16_000_000, List.of(entry("tool")), 2);
        final var store = new DependencyCache(policy, NOW);
        final var source = temporary.resolve("source");
        final var baseline = store.restore(REPOSITORY, source);
        file(source, "tool/value", "old epoch");
        final var later = new DependencyCache(policy, Clock.offset(NOW, Duration.ofDays(31)));
        final var maintenance = later.maintain(1);
        assertTrue(maintenance.epoch() > baseline.epoch());
        assertEquals(new DependencyCache.Publication(0, 0, 1, 0), store.publish(REPOSITORY, source, baseline));
        assertEquals(
                "",
                later.restore(REPOSITORY, temporary.resolve("after-reset"))
                        .revisions()
                        .get("tool"));
    }

    @Test
    void independentlyChangedEntriesMergeAgainstTheLatestRoot() throws Exception {
        final var policy = policy(16_000_000, List.of(entry("a"), entry("b")), 2);
        final var one = new DependencyCache(policy, NOW);
        final var two = new DependencyCache(policy, NOW);
        final var a = temporary.resolve("attempt-a");
        final var b = temporary.resolve("attempt-b");
        final var baselineA = one.restore(REPOSITORY, a);
        final var baselineB = two.restore(REPOSITORY, b);
        file(a, "a/one", "A");
        file(b, "b/two", "B");
        final var start = new CountDownLatch(1);
        try (final var executor = Executors.newFixedThreadPool(2)) {
            final var first = executor.submit(() -> {
                start.await();
                return one.publish(REPOSITORY, a, baselineA);
            });
            final var second = executor.submit(() -> {
                start.await();
                return two.publish(REPOSITORY, b, baselineB);
            });
            start.countDown();
            assertEquals(new DependencyCache.Publication(1, 1, 0, 0), first.get());
            assertEquals(new DependencyCache.Publication(1, 1, 0, 0), second.get());
        }
        final var restored = temporary.resolve("merged");
        one.restore(REPOSITORY, restored);
        assertEquals("A", Files.readString(restored.resolve("a/one")));
        assertEquals("B", Files.readString(restored.resolve("b/two")));
    }

    @Test
    void missingEntryDoesNotConflictWithANewerRevisionWhileAnotherEntryPublishes() throws Exception {
        final var policy = policy(16_000_000, List.of(entry("a"), entry("b")), 2);
        final var one = new DependencyCache(policy, NOW);
        final var two = new DependencyCache(policy, NOW);
        final var a = temporary.resolve("attempt-a");
        final var b = temporary.resolve("attempt-b");
        final var baselineA = one.restore(REPOSITORY, a);
        final var baselineB = two.restore(REPOSITORY, b);
        file(a, "a/one", "A");
        file(b, "b/two", "B");
        assertEquals(new DependencyCache.Publication(1, 1, 0, 0), one.publish(REPOSITORY, a, baselineA));
        // a has changed since baselineB, but this attempt has no a candidate at all.
        assertEquals(new DependencyCache.Publication(1, 1, 0, 0), two.publish(REPOSITORY, b, baselineB));
        final var restored = temporary.resolve("merged-after-stale-missing-entry");
        final var merged = new DependencyCache(policy, NOW).restore(REPOSITORY, restored);
        assertFalse(merged.revisions().get("a").isEmpty());
        assertFalse(merged.revisions().get("b").isEmpty());
        assertEquals("A", Files.readString(restored.resolve("a/one")));
        assertEquals("B", Files.readString(restored.resolve("b/two")));
        assertEquals(new DependencyCache.Publication(0, 2, 0, 0), two.publish(REPOSITORY, restored, merged));
    }

    @Test
    void maintenanceNeverEvictsWhileAnyOperationSlotIsOwned() throws Exception {
        final var policy = policy(16_000_000, List.of(entry("tool")), 2);
        final var store = new DependencyCache(policy, NOW);
        final var later = new DependencyCache(policy, Clock.offset(NOW, Duration.ofDays(31)));
        try (final var channel = FileChannel.open(Path.of(policy.root()).resolve("locks"), StandardOpenOption.WRITE);
                final var _ = channel.lock(1, 1, false)) {
            final var maintenance = later.maintain(1000);
            assertTrue(maintenance.busy());
            assertEquals(0, maintenance.processed());
            assertEquals(1, maintenance.epoch());
        }
        assertEquals(2, later.maintain(1).epoch());
        assertFalse(store.maintain(1).busy());
    }

    @Test
    void fifoEvictionChecksTheCurrentRevisionAndWaitsForAllSlots() throws Exception {
        final var policy = policy(4_000_000, List.of(entry("tool")), 2);
        final var store = new DependencyCache(policy, NOW);
        final var source = temporary.resolve("source");
        final var baseline = store.restore(REPOSITORY, source);
        Files.createDirectories(source.resolve("tool"));
        for (var i = 0; i < 12; i++) {
            final var bytes = new byte[250_000];
            bytes[0] = (byte) i; // Distinct objects, not twelve references to one deduplicated blob.
            Files.write(source.resolve("tool/blob-" + i), bytes);
        }
        assertEquals(1, store.publish(REPOSITORY, source, baseline).published());
        final var fresh = temporary.resolve("fresh");
        final var observed = store.restore(REPOSITORY, fresh);
        file(fresh, "tool/new-marker", "new revision");
        assertEquals(1, store.publish(REPOSITORY, fresh, observed).published());
        try (final var channel = FileChannel.open(Path.of(policy.root()).resolve("locks"), StandardOpenOption.WRITE);
                final var _ = channel.lock(1, 1, false)) {
            assertTrue(store.maintain(1000).busy());
        }
        // Two dead-slot checks, then the old FIFO candidate. Its obsolete revision must not
        // evict the newer mapping for the same entry.
        for (var i = 0; i < 3; i++) {
            assertEquals(1, store.maintain(1).processed());
        }
        final var retained = temporary.resolve("retained");
        assertFalse(store.restore(REPOSITORY, retained).revisions().get("tool").isEmpty());
        assertEquals("new revision", Files.readString(retained.resolve("tool/new-marker")));
        assertEquals(1, store.maintain(1).processed());
        assertEquals(
                "",
                store.restore(REPOSITORY, temporary.resolve("evicted"))
                        .revisions()
                        .get("tool"));
        long charge = Long.MAX_VALUE;
        for (var i = 0; i < 150; i++) {
            charge = store.maintain(1).chargedBytes();
        }
        assertTrue(charge < 1_000_000, "Sweeping should reclaim the evicted entry's physical objects");
    }

    @Test
    void foregroundAdmissionTimesOutInsteadOfIgnoringOccupiedSlots() throws Exception {
        final var policy = policy(16_000_000, List.of(entry("tool")), 1);
        final var store = new DependencyCache(policy, NOW);
        final var attempt = temporary.resolve("attempt");
        final var baseline = store.restore(REPOSITORY, attempt);
        file(attempt, "tool/value", "value");
        try (final var channel = FileChannel.open(Path.of(policy.root()).resolve("locks"), StandardOpenOption.WRITE);
                final var _ = channel.lock(1, 1, false)) {
            assertEquals(new DependencyCache.Publication(0, 0, 0, 1), store.publish(REPOSITORY, attempt, baseline));
            assertTrue(store.maintain(10).busy());
        }
    }

    @Test
    void quotaFailsBeforeOversizedBytesAreStoredAndOrphansAreCollectable() throws Exception {
        final var policy = policy(1_000_000, List.of(entry("tool")), 1);
        final var store = new DependencyCache(policy, NOW);
        final var attempt = temporary.resolve("attempt");
        final var baseline = store.restore(REPOSITORY, attempt);
        Files.createDirectories(attempt.resolve("tool"));
        Files.write(attempt.resolve("tool/large"), new byte[900_000]);
        assertThrows(IOException.class, () -> store.publish(REPOSITORY, attempt, baseline));
        final var result = store.maintain(100);
        assertTrue(result.chargedBytes() <= policy.maintenance().maxSizeBytes());
        assertEquals(
                "",
                store.restore(REPOSITORY, temporary.resolve("cold")).revisions().get("tool"));
        try (final var work = Files.list(Path.of(policy.root()).resolve("work"))) {
            assertEquals(0, work.count());
        }
    }

    @Test
    void maintenanceResumesWithSingleRecordBudgetsAndRestartsAfterANewPublication() throws Exception {
        final var policy = policy(16_000_000, List.of(entry("tool")), 2);
        final var store = new DependencyCache(policy, NOW);
        final var first = temporary.resolve("first");
        final var baseline = store.restore(REPOSITORY, first);
        for (var i = 0; i < 20; i++) {
            file(first, "tool/file-" + i, "old-" + i);
        }
        store.publish(REPOSITORY, first, baseline);
        for (var i = 0; i < 8; i++) {
            assertTrue(store.maintain(1).processed() <= 1);
        }
        final var next = temporary.resolve("next");
        final var observed = store.restore(REPOSITORY, next);
        for (var i = 0; i < 20; i++) {
            Files.writeString(next.resolve("tool/file-" + i), "new-" + i);
        }
        assertEquals(1, store.publish(REPOSITORY, next, observed).published());
        final var peak = store.maintain(1).chargedBytes();
        long lowest = peak;
        for (var i = 0; i < 500; i++) {
            // Reopening exercises the persisted mark stack, manifest offset and sweep cursor.
            final var result = new DependencyCache(policy, NOW).maintain(1);
            assertTrue(result.processed() <= 1);
            assertFalse(result.busy());
            lowest = Math.min(lowest, result.chargedBytes());
        }
        assertTrue(lowest < peak, "Old blobs and manifest/index nodes should be collected");
        final var restored = temporary.resolve("after-gc");
        store.restore(REPOSITORY, restored);
        for (var i = 0; i < 20; i++) {
            assertEquals("new-" + i, Files.readString(restored.resolve("tool/file-" + i)));
        }
    }

    @Test
    void failedRestoreNeverDeletesAttemptOwnedContents() throws Exception {
        final var store = new DependencyCache(policy(16_000_000, List.of(entry("tool")), 2), NOW);
        final var source = temporary.resolve("source");
        final var baseline = store.restore(REPOSITORY, source);
        file(source, "tool/cached", "cached");
        store.publish(REPOSITORY, source, baseline);
        final var destination = temporary.resolve("destination");
        file(destination, "tool/owned", "must survive");
        store.restore(REPOSITORY, destination);
        assertEquals("must survive", Files.readString(destination.resolve("tool/owned")));
        try (final var children = Files.list(destination)) {
            assertFalse(children.anyMatch(path -> path.getFileName().toString().startsWith(".dependency-restore-")));
        }
    }

    @Test
    void restoreReplacesProvisionedEmptyDirectoriesWithoutHardlinkingAttemptFiles() throws Exception {
        final var policy = policy(16_000_000, List.of(entry("tool")), 2);
        final var store = new DependencyCache(policy, NOW);
        final var source = temporary.resolve("source");
        final var baseline = store.restore(REPOSITORY, source);
        file(source, "tool/value", "immutable");
        store.publish(REPOSITORY, source, baseline);
        final var restored = temporary.resolve("restored");
        Files.createDirectories(restored.resolve("tool"));
        store.restore(REPOSITORY, restored);
        Files.writeString(restored.resolve("tool/value"), "attempt mutation");
        final var another = temporary.resolve("another");
        store.restore(REPOSITORY, another);
        assertEquals("immutable", Files.readString(another.resolve("tool/value")));
    }

    @Test
    void corruptBlobFallsBackOnlyThatEntryAndRemovesItsPrivatePartialRestore() throws Exception {
        final var policy = policy(16_000_000, List.of(entry("a"), entry("b")), 2);
        final var store = new DependencyCache(policy, NOW);
        final var source = temporary.resolve("source");
        final var baseline = store.restore(REPOSITORY, source);
        file(source, "a/value", "corrupt me");
        file(source, "b/value", "healthy");
        assertEquals(2, store.publish(REPOSITORY, source, baseline).published());
        try (final var files = Files.walk(Path.of(policy.root()).resolve("blobs"))) {
            final var corrupt = files.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().endsWith(".allocation"))
                    .filter(path -> {
                        try {
                            return Files.readString(path).equals("corrupt me");
                        } catch (final IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    })
                    .findFirst()
                    .orElseThrow();
            // Preserve the length so that hash validation, not just the reader's size check, fails.
            Files.writeString(corrupt, "not right!");
        }
        final var destination = temporary.resolve("destination");
        final var observed = store.restore(REPOSITORY, destination);
        assertTrue(observed.epoch() > baseline.epoch());
        assertEquals(Map.of("a", "", "b", ""), observed.revisions());
        assertFalse(Files.exists(destination.resolve("a")));
        assertEquals("healthy", Files.readString(destination.resolve("b/value")));
        try (final var children = Files.list(destination)) {
            assertFalse(children.anyMatch(path -> path.getFileName().toString().startsWith(".dependency-restore-")));
        }
    }

    @Test
    void restoreOnlyDoesNotPublishAndUnknownUnaccountedRootsAreRejected() throws Exception {
        final var policy = policy(16_000_000, List.of(entry("tool")), 2);
        final var writer = new DependencyCache(policy, NOW);
        final var source = temporary.resolve("source");
        final var baseline = writer.restore(REPOSITORY, source);
        file(source, "tool/value", "original");
        writer.publish(REPOSITORY, source, baseline);
        final var readOnlyPolicy = new DependencyCacheConfiguration(
                DependencyCacheConfiguration.Mode.RESTORE_ONLY,
                policy.root(),
                policy.scope(),
                policy.compatibility(),
                policy.presets(),
                policy.entries(),
                policy.environment(),
                policy.files(),
                policy.gracefulTimeoutSeconds(),
                policy.maintenance(),
                policy.limits());
        final var reader = new DependencyCache(readOnlyPolicy, NOW);
        final var restored = temporary.resolve("read-only");
        final var observed = reader.restore(REPOSITORY, restored);
        Files.writeString(restored.resolve("tool/value"), "must not publish");
        assertEquals(new DependencyCache.Publication(0, 1, 0, 0), reader.publish(REPOSITORY, restored, observed));
        final var check = temporary.resolve("check-original");
        writer.restore(REPOSITORY, check);
        assertEquals("original", Files.readString(check.resolve("tool/value")));

        final var foreign = temporary.resolve("foreign");
        Files.createDirectory(
                foreign, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.writeString(foreign.resolve("unknown"), "not an accounted allocation");
        final var foreignPolicy = new DependencyCacheConfiguration(
                policy.mode(),
                foreign.toString(),
                policy.scope(),
                policy.compatibility(),
                policy.presets(),
                policy.entries(),
                policy.environment(),
                policy.files(),
                policy.gracefulTimeoutSeconds(),
                policy.maintenance(),
                policy.limits());
        assertThrows(IOException.class, () -> new DependencyCache(foreignPolicy, NOW));
        assertEquals("not an accounted allocation", Files.readString(foreign.resolve("unknown")));
    }

    @Test
    void policyFingerprintsUseLogicalCompatibilityNotCacheRootOrAttemptPaths() throws Exception {
        final var policy = policy(16_000_000, List.of(entry("tool")), 2);
        final var store = new DependencyCache(policy, NOW);
        final var source = temporary.resolve("source");
        final var baseline = store.restore(REPOSITORY, source);
        file(source, "tool/value", "original");
        store.publish(REPOSITORY, source, baseline);
        final var incompatible = new DependencyCacheConfiguration(
                policy.mode(),
                policy.root(),
                policy.scope(),
                "other",
                policy.presets(),
                policy.entries(),
                policy.environment(),
                policy.files(),
                policy.gracefulTimeoutSeconds(),
                policy.maintenance(),
                policy.limits());
        assertEquals(
                "",
                new DependencyCache(incompatible, NOW)
                        .restore(REPOSITORY, temporary.resolve("incompatible"))
                        .revisions()
                        .get("tool"));
    }

    @Test
    void rootsCannotChangeSlotCountOrContainSymlinkAncestors() throws Exception {
        final var policy = policy(16_000_000, List.of(entry("tool")), 2);
        new DependencyCache(policy, NOW);
        assertThrows(IOException.class, () -> new DependencyCache(policy(16_000_000, List.of(entry("tool")), 1), NOW));
        final var link = temporary.resolve("link");
        Files.createSymbolicLink(link, Path.of(policy.root()));
        final var linked = new DependencyCacheConfiguration(
                policy.mode(),
                link.toString(),
                policy.scope(),
                policy.compatibility(),
                policy.presets(),
                policy.entries(),
                policy.environment(),
                policy.files(),
                policy.gracefulTimeoutSeconds(),
                policy.maintenance(),
                policy.limits());
        assertThrows(IOException.class, () -> new DependencyCache(linked, NOW));
        final var store = new DependencyCache(policy, NOW);
        assertThrows(
                IOException.class,
                () -> store.restore(REPOSITORY, Path.of(policy.root()).resolve("attempt")));
        assertThrows(IllegalArgumentException.class, () -> store.restore("", temporary.resolve("blank")));
        assertThrows(IllegalArgumentException.class, () -> store.maintain(0));
    }

    @Test
    void monthlyResetReusesBoundedMetadataAndChargesOnlyRetainedRecordsAcrossReopens() throws Exception {
        final var policy = metadataPolicy(8192);
        final var source = temporary.resolve("source");
        final var root = Path.of(policy.root());
        // The old append-only layout exhausted this budget after about 24 publications.
        for (var cycle = 0; cycle < 64; cycle++) {
            final var time = Clock.offset(NOW, Duration.ofDays(31L * cycle));
            final var store = new DependencyCache(policy, time);
            final var baseline = store.restore(REPOSITORY, temporary.resolve("baseline-" + cycle));
            file(source, "tool/value", "revision-" + cycle);
            assertEquals(1, store.publish(REPOSITORY, source, baseline).published());
            final var restored = temporary.resolve("restored-" + cycle);
            new DependencyCache(policy, time).restore(REPOSITORY, restored);
            assertEquals("revision-" + cycle, Files.readString(restored.resolve("tool/value")));

            final var later = new DependencyCache(policy, Clock.offset(time, Duration.ofDays(31)));
            assertEquals(1, later.maintain(1).processed()); // Reset first; sweep in bounded slices below.
            finishCollection(policy, Clock.offset(time, Duration.ofDays(31)));
            final var state = cacheState(root);
            assertEquals(state.inventoryLength() / 64, state.freeCount());
            assertEquals(196608L + 32 + state.fifoCapacity() + state.inventoryLength(), state.charge());
            assertTrue(Files.size(root.resolve("inventory")) + Files.size(root.resolve("publications")) <= 8192);
            assertEquals(4 * 64, state.inventoryLength(), "All four allocation slots should be reused");
        }
        assertTrue(
                cacheState(root).producer() > 2 * cacheState(root).fifoCapacity(),
                "The ring must have wrapped repeatedly");
    }

    @Test
    void publicationRingPressureConsumesStaleCandidatesBelowTheByteWatermarkAndNeverOverwritesTheFullRing()
            throws Exception {
        final var policy = metadataPolicy(16384);
        final var store = new DependencyCache(policy, NOW);
        final var root = Path.of(policy.root());
        final var source = temporary.resolve("source");
        var baseline = store.restore(REPOSITORY, temporary.resolve("initial"));
        final var capacity = cacheState(root).fifoCapacity() / 80;
        final var pressure = cacheState(root).fifoCapacity() - cacheState(root).fifoCapacity() / 4;
        for (var publication = 0; publication < capacity; publication++) {
            file(source, "tool/value", "revision-" + publication);
            assertEquals(1, store.publish(REPOSITORY, source, baseline).published());
            baseline = store.restore(REPOSITORY, temporary.resolve("baseline-" + publication));
            // Collect obsolete objects while the ring is below its independent pressure threshold.
            if (cacheState(root).producer() + 80 < pressure) {
                finishCollection(policy, NOW);
            }
        }
        final var full = Files.readAllBytes(root.resolve("publications"));
        assertTrue(cacheState(root).charge() < policy.maintenance().maxSizeBytes() * 3 / 4);
        file(source, "tool/value", "after-full");
        final var expected = baseline;
        assertThrows(IOException.class, () -> store.publish(REPOSITORY, source, expected));
        assertArrayEquals(full, Files.readAllBytes(root.resolve("publications")));
        assertEquals(capacity * 80, Files.size(root.resolve("publications")));

        // Maintenance first skips the obsolete expected revision, not the current root mapping.
        for (var step = 0; step < 4 && cacheState(root).consumer() == 0; step++) {
            assertEquals(1, store.maintain(1).processed());
        }
        assertEquals(80, cacheState(root).consumer());
        final var retained = temporary.resolve("retained-after-pressure");
        assertEquals(expected.revisions(), store.restore(REPOSITORY, retained).revisions());
        assertEquals("revision-" + (capacity - 1), Files.readString(retained.resolve("tool/value")));
        assertEquals(
                1,
                new DependencyCache(policy, NOW)
                        .publish(REPOSITORY, source, expected)
                        .published());
        assertEquals(
                full.length, Files.size(root.resolve("publications")), "Consumed slots must be overwritten in place");
        assertTrue(cacheState(root).producer() > cacheState(root).fifoCapacity());
        finishCollection(policy, NOW);
        final var restored = temporary.resolve("after-wrap");
        new DependencyCache(policy, NOW).restore(REPOSITORY, restored);
        assertEquals("after-full", Files.readString(restored.resolve("tool/value")));
    }

    @Test
    void corruptFreeSlotLinkFailsClosedBeforeOverwritingAnAllocation() throws Exception {
        final var policy = metadataPolicy(8192);
        final var store = new DependencyCache(policy, NOW);
        final var source = temporary.resolve("source");
        final var baseline = store.restore(REPOSITORY, source);
        file(source, "tool/value", "old");
        assertEquals(1, store.publish(REPOSITORY, source, baseline).published());
        final var laterClock = Clock.offset(NOW, Duration.ofDays(31));
        final var later = new DependencyCache(policy, laterClock);
        later.maintain(1);
        finishCollection(policy, laterClock);
        final var root = Path.of(policy.root());
        final var state = cacheState(root);
        assertTrue(state.freeCount() > 0);
        final var inventory = root.resolve("inventory");
        final var bytes = Files.readAllBytes(inventory);
        final var link = Math.toIntExact(state.freeHead() + 48);
        ByteBuffer.wrap(bytes).putLong(link, state.freeHead()); // A self-cycle must never be popped.
        Files.write(inventory, bytes);
        final var empty = later.restore(REPOSITORY, temporary.resolve("empty"));
        file(source, "tool/value", "new");
        assertThrows(IOException.class, () -> later.publish(REPOSITORY, source, empty));
        assertArrayEquals(bytes, Files.readAllBytes(inventory));
        assertEquals(state.charge(), cacheState(root).charge());
        assertEquals(state.freeHead(), cacheState(root).freeHead());
        assertEquals(
                "",
                later.restore(REPOSITORY, temporary.resolve("still-empty"))
                        .revisions()
                        .get("tool"));
    }

    @Test
    void aNewRootUsingSweptSlotsRestartsMarkingAndBusySlicesDoNotTouchTheFreeList() throws Exception {
        final var policy = metadataPolicy(16384);
        final var store = new DependencyCache(policy, NOW);
        final var source = temporary.resolve("source");
        final var baseline = store.restore(REPOSITORY, source);
        for (var i = 0; i < 20; i++) {
            file(source, "tool/file-" + i, "old-" + i);
        }
        assertEquals(1, store.publish(REPOSITORY, source, baseline).published());
        final var nextBaseline = store.restore(REPOSITORY, temporary.resolve("next-baseline"));
        for (var i = 0; i < 20; i++) {
            file(source, "tool/file-" + i, "middle-" + i);
        }
        assertEquals(1, store.publish(REPOSITORY, source, nextBaseline).published());
        final var root = Path.of(policy.root());
        var ready = false;
        for (var step = 0; step < 1000; step++) {
            store.maintain(1);
            final var state = cacheState(root);
            if (state.phase() == 2 && state.freeCount() >= 5 && state.sweep() < state.inventoryLength()) {
                ready = true;
                break;
            }
        }
        assertTrue(ready, "Pause during sweep with a partially rebuilt free list");
        final var before = Files.readAllBytes(root.resolve("state"));
        final var inventory = Files.readAllBytes(root.resolve("inventory"));
        try (final var channel = FileChannel.open(root.resolve("locks"), StandardOpenOption.WRITE);
                final var _ = channel.lock(1, 1, false)) {
            assertTrue(store.maintain(100).busy());
        }
        assertArrayEquals(before, Files.readAllBytes(root.resolve("state")));
        assertArrayEquals(inventory, Files.readAllBytes(root.resolve("inventory")));

        final var observed = store.restore(REPOSITORY, temporary.resolve("middle"));
        file(source, "tool/file-0", "newest-reused-slot");
        final var priorFree = cacheState(root).freeCount();
        assertEquals(1, store.publish(REPOSITORY, source, observed).published());
        assertTrue(cacheState(root).freeCount() < priorFree);
        finishCollection(policy, NOW); // Reopens on every single-record slice.
        final var restored = temporary.resolve("after-partial-sweep");
        new DependencyCache(policy, NOW).restore(REPOSITORY, restored);
        assertEquals("newest-reused-slot", Files.readString(restored.resolve("tool/file-0")));
        for (var i = 1; i < 20; i++) {
            assertEquals("middle-" + i, Files.readString(restored.resolve("tool/file-" + i)));
        }
    }

    @Test
    void reusingAnOldDigestSlotDoesNotRemoveTheSameObjectsNewAllocationPointer() throws Exception {
        final var policy = metadataPolicy(8192);
        final var store = new DependencyCache(policy, NOW);
        final var source = temporary.resolve("source");
        final var baseline = store.restore(REPOSITORY, source);
        file(source, "tool/value", "identical-content");
        assertEquals(1, store.publish(REPOSITORY, source, baseline).published());
        final var laterClock = Clock.offset(NOW, Duration.ofDays(31));
        final var later = new DependencyCache(policy, laterClock);
        later.maintain(1);
        finishCollection(policy, laterClock);
        final var inventoryLength = cacheState(Path.of(policy.root())).inventoryLength();
        final var empty = later.restore(REPOSITORY, temporary.resolve("empty"));
        // The rebuilt free list is in reverse allocation order. Blob/manifest digests move to
        // other slots before their own old inventory records are reused for index allocations.
        assertEquals(1, later.publish(REPOSITORY, source, empty).published());
        assertEquals(inventoryLength, cacheState(Path.of(policy.root())).inventoryLength());
        finishCollection(policy, laterClock);
        final var restored = temporary.resolve("same-digest-restored");
        new DependencyCache(policy, laterClock).restore(REPOSITORY, restored);
        assertEquals("identical-content", Files.readString(restored.resolve("tool/value")));
    }

    @Test
    void sweepRecoversAFreeSlotLostAfterTheDurablePopWithoutReleasingItsAmbiguousCharge() throws Exception {
        final var policy = metadataPolicy(8192);
        final var store = new DependencyCache(policy, NOW);
        final var source = temporary.resolve("source");
        final var baseline = store.restore(REPOSITORY, source);
        file(source, "tool/value", "old");
        store.publish(REPOSITORY, source, baseline);
        final var laterClock = Clock.offset(NOW, Duration.ofDays(31));
        final var later = new DependencyCache(policy, laterClock);
        later.maintain(1);
        finishCollection(policy, laterClock);
        final var root = Path.of(policy.root());
        final var state = cacheState(root);
        final var inventory = ByteBuffer.wrap(Files.readAllBytes(root.resolve("inventory")));
        final var successor = inventory.getLong(Math.toIntExact(state.freeHead() + 48));
        final var image = Files.readAllBytes(root.resolve("state"));
        final var bytes = ByteBuffer.wrap(image);
        // Simulate the durable state image after reserving an empty blob and popping a slot,
        // but before overwriting that freed allocation or creating its eight-byte pointer.
        bytes.putLong(32, state.charge() + Long.BYTES);
        bytes.position(40);
        final var rootLength = Short.toUnsignedInt(bytes.getShort());
        bytes.position(bytes.position() + rootLength + 5 * Long.BYTES);
        bytes.putLong(successor).putLong(state.freeCount() - 1);
        Files.write(root.resolve("state"), image);
        assertEquals(state.freeCount() - 1, cacheState(root).freeCount());
        finishCollection(policy, laterClock);
        assertEquals(state.freeCount(), cacheState(root).freeCount());
        assertEquals(state.charge() + Long.BYTES, cacheState(root).charge());
        final var empty = later.restore(REPOSITORY, temporary.resolve("after-interrupted-pop"));
        file(source, "tool/value", "new");
        assertEquals(1, later.publish(REPOSITORY, source, empty).published());
        assertEquals(state.inventoryLength(), cacheState(root).inventoryLength());
        final var restored = temporary.resolve("after-recovered-pop");
        new DependencyCache(policy, laterClock).restore(REPOSITORY, restored);
        assertEquals("new", Files.readString(restored.resolve("tool/value")));
    }

    @Test
    void interruptedPostorderRegistrationCanRetryAndOrphanParentCannotDeleteCurrentGraph() throws Exception {
        final var policy = policy(16_000_000, List.of(entry("a"), entry("b")), 1);
        final var store = new DependencyCache(policy, NOW);
        final var source = temporary.resolve("source");
        final var empty = store.restore(REPOSITORY, source);
        file(source, "a/value", "first");
        assertEquals(1, store.publish(REPOSITORY, source, empty).published());
        final var before = store.restore(REPOSITORY, temporary.resolve("before"));
        final var root = Path.of(policy.root());
        final var oldRoot = indexRoot(root);
        file(source, "b/value", "second");
        assertEquals(1, store.publish(REPOSITORY, source, before).published());
        final var newRoot = indexRoot(root);
        final var newPointer = objectPath(root, "index", newRoot).resolveSibling(newRoot.substring(2) + ".allocation");
        final var parentOffset = ByteBuffer.wrap(Files.readAllBytes(newPointer)).getLong();
        final var children =
                new CacheIndex(root.resolve("index")).inspect(newRoot).children();
        assertFalse(children.isEmpty());
        for (final var child : children) {
            final var childPointer =
                    objectPath(root, "index", child).resolveSibling(child.substring(2) + ".allocation");
            assertTrue(
                    ByteBuffer.wrap(Files.readAllBytes(childPointer)).getLong() < parentOffset,
                    "A durable parent pointer must certify all its child pointers");
        }
        // Durable allocation, interrupted before its pointer and the root commit. Keep its
        // conservative charge and all registered child certificates, exactly as recovery must.
        final var state = Files.readAllBytes(root.resolve("state"));
        assertEquals(64, ByteBuffer.wrap(state).getShort(40));
        System.arraycopy(oldRoot.getBytes(StandardCharsets.US_ASCII), 0, state, 42, 64);
        Files.write(root.resolve("state"), state);
        Files.delete(newPointer);
        assertEquals(
                1,
                new DependencyCache(policy, NOW)
                        .publish(REPOSITORY, source, before)
                        .published());
        assertNotEquals(
                parentOffset, ByteBuffer.wrap(Files.readAllBytes(newPointer)).getLong());
        finishCollection(policy, NOW);
        final var restored = temporary.resolve("after-retry");
        new DependencyCache(policy, NOW).restore(REPOSITORY, restored);
        assertEquals("first", Files.readString(restored.resolve("a/value")));
        assertEquals("second", Files.readString(restored.resolve("b/value")));
    }

    @Test
    void duplicateBlobInventoryRecordNeverUnlinksItsMarkedCurrentOwner() throws Exception {
        final var policy = metadataPolicy(8192);
        final var store = new DependencyCache(policy, NOW);
        final var source = temporary.resolve("source");
        final var baseline = store.restore(REPOSITORY, source);
        file(source, "tool/value", "retained");
        store.publish(REPOSITORY, source, baseline);
        final var root = Path.of(policy.root());
        final var digest = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest("retained".getBytes(StandardCharsets.UTF_8)));
        final var blob = objectPath(root, "blobs", digest);
        final var pointer = blob.resolveSibling(blob.getFileName() + ".allocation");
        final var oldOffset = ByteBuffer.wrap(Files.readAllBytes(pointer)).getLong();
        final var inventory = Files.readAllBytes(root.resolve("inventory"));
        final var appended = Arrays.copyOf(inventory, inventory.length + 64);
        System.arraycopy(inventory, Math.toIntExact(oldOffset), appended, inventory.length, 64);
        Files.write(root.resolve("inventory"), appended);
        Files.write(pointer, ByteBuffer.allocate(8).putLong(inventory.length).array());
        final var state = Files.readAllBytes(root.resolve("state"));
        final var bytes = ByteBuffer.wrap(state);
        bytes.putLong(32, bytes.getLong(32) + 64 + 8 + Files.size(blob));
        final var lengthOffset = 42 + Short.toUnsignedInt(bytes.getShort(40));
        bytes.putLong(lengthOffset, appended.length);
        Files.write(root.resolve("state"), state);
        finishCollection(policy, NOW);
        assertTrue(Files.exists(blob));
        assertEquals(
                inventory.length, ByteBuffer.wrap(Files.readAllBytes(pointer)).getLong());
        final var restored = temporary.resolve("retained");
        store.restore(REPOSITORY, restored);
        assertEquals("retained", Files.readString(restored.resolve("tool/value")));
    }

    @Test
    void knownSameSizeCorruptionCannotBeReusedOrReportedUnchangedUntilAllSlotCollection() throws Exception {
        for (final var kind : List.of("blobs", "manifests")) {
            final var base = policy(16_000_000, List.of(entry("tool")), 1);
            final var policy = new DependencyCacheConfiguration(
                    base.mode(),
                    temporary.resolve(kind).toString(),
                    base.scope(),
                    base.compatibility(),
                    base.presets(),
                    base.entries(),
                    base.environment(),
                    base.files(),
                    base.gracefulTimeoutSeconds(),
                    base.maintenance(),
                    base.limits());
            final var store = new DependencyCache(policy, NOW);
            final var source = temporary.resolve("source-" + kind);
            final var baseline = store.restore(REPOSITORY, source);
            file(source, "tool/value", "original");
            store.publish(REPOSITORY, source, baseline);
            final var root = Path.of(policy.root());
            final Path corrupt;
            try (final var files = Files.walk(root.resolve(kind))) {
                corrupt = files.filter(Files::isRegularFile)
                        .filter(path -> !path.getFileName().toString().endsWith(".allocation"))
                        .findFirst()
                        .orElseThrow();
            }
            final var contents = Files.readAllBytes(corrupt);
            contents[0] ^= 1;
            Files.write(corrupt, contents);
            final var repaired = store.restore(REPOSITORY, temporary.resolve("cold-" + kind));
            assertTrue(repaired.epoch() > baseline.epoch());
            assertEquals("", repaired.revisions().get("tool"));
            assertEquals(1, store.publish(REPOSITORY, source, baseline).conflicted());
            assertThrows(IOException.class, () -> store.publish(REPOSITORY, source, repaired));
            assertArrayEquals(contents, Files.readAllBytes(corrupt), "Never overwrite a known-bad immutable target");
            try (final var channel = FileChannel.open(root.resolve("locks"), StandardOpenOption.WRITE);
                    final var _ = channel.lock(1, 1, false)) {
                assertTrue(store.maintain(100).busy());
                assertTrue(Files.exists(corrupt));
            }
            finishCollection(policy, NOW);
            assertFalse(Files.exists(corrupt));
            assertEquals(1, store.publish(REPOSITORY, source, repaired).published());
            final var healthy = temporary.resolve("healthy-" + kind);
            store.restore(REPOSITORY, healthy);
            assertEquals("original", Files.readString(healthy.resolve("tool/value")));
        }
    }

    @Test
    void corruptIndexLookupReturnsColdCoherentEpochAndFencesOldWriters() throws Exception {
        final var policy = metadataPolicy(8192);
        final var store = new DependencyCache(policy, NOW);
        final var source = temporary.resolve("source");
        final var baseline = store.restore(REPOSITORY, source);
        file(source, "tool/value", "original");
        store.publish(REPOSITORY, source, baseline);
        final var root = Path.of(policy.root());
        Files.write(objectPath(root, "index", indexRoot(root)), new byte[] {0});
        final var cold = store.restore(REPOSITORY, temporary.resolve("cold"));
        assertTrue(cold.epoch() > baseline.epoch());
        assertEquals("", cold.revisions().get("tool"));
        assertEquals(1, store.publish(REPOSITORY, source, baseline).conflicted());
        finishCollection(policy, NOW);
        assertEquals(1, store.publish(REPOSITORY, source, cold).published());
    }

    @Test
    void malformedLiveManifestAndMissingLivePointerCannotPoisonMarkingForever() throws Exception {
        for (final var missingPointer : List.of(false, true)) {
            final var base = metadataPolicy(8192);
            final var policy = new DependencyCacheConfiguration(
                    base.mode(),
                    temporary.resolve("poison-" + missingPointer).toString(),
                    base.scope(),
                    base.compatibility(),
                    base.presets(),
                    base.entries(),
                    base.environment(),
                    base.files(),
                    base.gracefulTimeoutSeconds(),
                    base.maintenance(),
                    base.limits());
            final var store = new DependencyCache(policy, NOW);
            final var source = temporary.resolve("source-" + missingPointer);
            final var baseline = store.restore(REPOSITORY, source);
            file(source, "tool/value", "original");
            store.publish(REPOSITORY, source, baseline);
            final var root = Path.of(policy.root());
            final var digest = store.restore(REPOSITORY, temporary.resolve("before-" + missingPointer))
                    .revisions()
                    .get("tool");
            final var manifest = objectPath(root, "manifests", digest);
            if (missingPointer) {
                Files.delete(manifest.resolveSibling(manifest.getFileName() + ".allocation"));
            } else {
                final var bytes = Files.readAllBytes(manifest);
                bytes[8] = 99; // Invalid root record kind, not a full-manifest hash in the GC tick.
                Files.write(manifest, bytes);
            }
            // First pass must reset instead of retrying the same grey item indefinitely.
            for (var step = 0; step < 100 && indexRoot(root).length() != 0; step++) {
                assertTrue(store.maintain(1).processed() <= 1);
            }
            assertEquals("", indexRoot(root));
            finishCollection(policy, NOW);
            final var cold = store.restore(REPOSITORY, temporary.resolve("after-" + missingPointer));
            assertTrue(cold.epoch() > baseline.epoch());
            assertEquals("", cold.revisions().get("tool"));
            assertEquals(1, store.publish(REPOSITORY, source, cold).published());
        }
    }

    @Test
    void restoreAdmissionAndRecoveryMetadataWaitShareOneAbsoluteDeadline() throws Exception {
        final var base = policy(16_000_000, List.of(entry("tool")), 1);
        final var policy = new DependencyCacheConfiguration(
                base.mode(),
                base.root(),
                base.scope(),
                base.compatibility(),
                base.presets(),
                base.entries(),
                base.environment(),
                base.files(),
                base.gracefulTimeoutSeconds(),
                base.maintenance(),
                new DependencyCacheConfiguration.Limits(1000, 2_000_000, 64_000, 1, 1));
        final var store = new DependencyCache(policy, NOW);
        final var root = Path.of(policy.root());
        assertEquals(0, Files.size(root.resolve("inventory")));
        assertEquals(0, Files.size(root.resolve("publications")));
        final var locks = new CacheLocks(root.resolve("locks"), 1);
        try (final var executor = Executors.newSingleThreadExecutor();
                final var metadata = locks.metadata(System.nanoTime() + TimeUnit.SECONDS.toNanos(5))) {
            assertNotNull(metadata);
            final Future<DependencyCache.Baseline> restore;
            final var started = new CountDownLatch(1);
            try (final var slot = locks.foreground(System.nanoTime() + TimeUnit.SECONDS.toNanos(5))) {
                assertNotNull(slot);
                restore = executor.submit(() -> {
                    started.countDown();
                    return store.restore(REPOSITORY, temporary.resolve("restore-with-held-metadata"));
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                Thread.sleep(650);
            }
            // Only the remaining ~350ms is available for recovery's metadata lock, not a
            // newly-started one-second budget. Keep metadata owned until the future settles.
            final var failure = assertThrows(ExecutionException.class, () -> restore.get(650, TimeUnit.MILLISECONDS));
            assertInstanceOf(CacheSnapshot.OperationTimeoutException.class, failure.getCause());
        }
        assertFalse(store.maintain(1).busy(), "A timed-out restore must release its operation slot");
    }

    @Test
    void invalidSourceEntryDoesNotPreventPublishingIndependentEntries() throws Exception {
        final var policy = policy(16_000_000, List.of(entry("a"), entry("b")), 1);
        final var store = new DependencyCache(policy, NOW);
        final var source = temporary.resolve("source");
        final var baseline = store.restore(REPOSITORY, source);
        Files.createDirectories(source);
        Files.writeString(source.resolve("a"), "not a directory");
        file(source, "b/value", "independent");
        assertEquals(new DependencyCache.Publication(1, 0, 0, 1), store.publish(REPOSITORY, source, baseline));
        final var restored = temporary.resolve("restored");
        store.restore(REPOSITORY, restored);
        assertFalse(Files.exists(restored.resolve("a")));
        assertEquals("independent", Files.readString(restored.resolve("b/value")));
    }

    private static Path objectPath(final Path root, final String kind, final String digest) {
        return root.resolve(kind).resolve(digest.substring(0, 2)).resolve(digest.substring(2));
    }

    private static String indexRoot(final Path root) throws IOException {
        try (final var in = new DataInputStream(Files.newInputStream(root.resolve("state")))) {
            in.skipNBytes(40);
            return in.readUTF();
        }
    }

    private void finishCollection(final DependencyCacheConfiguration policy, final Clock time) throws IOException {
        var started = false;
        for (var step = 0; step < 2000; step++) {
            final var result = new DependencyCache(policy, time).maintain(1);
            assertFalse(result.busy());
            assertTrue(result.processed() <= 1);
            final var phase = cacheState(Path.of(policy.root())).phase();
            started |= phase != 0;
            if (started && phase == 0) {
                return;
            }
        }
        fail("Bounded collection failed to finish");
    }

    private DependencyCacheConfiguration metadataPolicy(final long bytes) {
        final var base = policy(16_000_000, List.of(entry("tool")), 1);
        return new DependencyCacheConfiguration(
                base.mode(),
                base.root(),
                base.scope(),
                base.compatibility(),
                base.presets(),
                base.entries(),
                base.environment(),
                base.files(),
                base.gracefulTimeoutSeconds(),
                base.maintenance(),
                new DependencyCacheConfiguration.Limits(1000, 2_000_000, bytes, 5, 1));
    }

    private CacheState cacheState(final Path root) throws IOException {
        try (final var in = new DataInputStream(Files.newInputStream(root.resolve("state")))) {
            assertEquals(0x59434431, in.readInt());
            assertEquals(2, in.readInt());
            in.readLong(); // epoch
            in.readLong(); // generation
            in.readLong(); // reset time
            final var charge = in.readLong();
            in.readUTF(); // current root
            final var inventoryLength = in.readLong();
            final var producer = in.readLong();
            final var consumer = in.readLong();
            in.readLong(); // inventory capacity
            final var fifoCapacity = in.readLong();
            final var freeHead = in.readLong();
            final var freeCount = in.readLong();
            in.readLong(); // GC generation
            return new CacheState(
                    charge,
                    inventoryLength,
                    producer,
                    consumer,
                    fifoCapacity,
                    freeHead,
                    freeCount,
                    in.readInt(),
                    in.readLong());
        }
    }

    private record CacheState(
            long charge,
            long inventoryLength,
            long producer,
            long consumer,
            long fifoCapacity,
            long freeHead,
            long freeCount,
            int phase,
            long sweep) {}

    private DependencyCacheConfiguration policy(
            final long quota, final List<DependencyCacheConfiguration.Entry> entries, final int slots) {
        return new DependencyCacheConfiguration(
                DependencyCacheConfiguration.Mode.READ_WRITE,
                temporary.resolve("store").toString(),
                DependencyCacheConfiguration.Scope.REPOSITORY,
                "default",
                List.of(),
                entries,
                Map.of(),
                List.of(),
                1,
                new DependencyCacheConfiguration.Maintenance(30, quota),
                new DependencyCacheConfiguration.Limits(1000, 2_000_000, 64_000, 5, slots));
    }

    private static DependencyCacheConfiguration.Entry entry(final String id) {
        return new DependencyCacheConfiguration.Entry(id, DependencyCacheConfiguration.Kind.GENERIC, id, List.of());
    }

    private static void file(final Path storage, final String relative, final String content) throws IOException {
        final var target = storage.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
