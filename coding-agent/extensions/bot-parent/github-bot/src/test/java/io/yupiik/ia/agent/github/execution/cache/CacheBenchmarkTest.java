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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Entry;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Kind;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Limits;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Maintenance;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Mode;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Scope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opt-in end-to-end measurements, not latency gates. Cold means an empty application cache;
 * no attempt is made to flush OS page caches. Setup, verification and offline node walks are
 * excluded from timings. Publication and restoration use the real durable store API.
 */
@EnabledIfSystemProperty(named = "yca.cache.benchmark", matches = "true")
@Timeout(600)
class CacheBenchmarkTest {
    private static final long MIB = 1024L * 1024;
    private static final String REPOSITORY = "https://api.github.example/repositories/benchmark";

    @TempDir
    Path temporary;

    @Test
    void largeBlobColdPublishWarmPrivateRestoreAndUnchangedPublish() throws Exception {
        final long bytes = Long.getLong("yca.cache.benchmark.bytes", 1073741824L);
        assertTrue(bytes > 0, "yca.cache.benchmark.bytes must be positive");
        measureStore("large-blob", bytes, 1);
    }

    @Test
    void smallFileTreeColdPublishWarmPrivateRestoreAndExactMetadata() throws Exception {
        final int files = Integer.getInteger("yca.cache.benchmark.files", 1024);
        final long fileBytes = Long.getLong("yca.cache.benchmark.smallFileBytes", 4096L);
        assertTrue(files > 0 && files <= 100000, "files must be in [1,100000]");
        assertTrue(fileBytes >= 0, "smallFileBytes must be nonnegative");
        measureStore("small-file-tree", fileBytes, files);
    }

    @Test
    void selectedKeyIndexScalingHasBoundedCopyOnWritePages() throws Exception {
        requireSpace(128 * MIB);
        final var directory = privateDirectory(temporary.resolve("index"));
        final var index = new CacheIndex(directory);
        final var selected = hash("selected");
        final var originalValue = hash("original");
        var root = index.put(CacheIndex.EMPTY, selected, originalValue);
        final var retainedRoots = new ArrayList<String>();
        final var retainedValues = new ArrayList<String>();
        var value = originalValue;
        var inserted = 0;
        environment("index");
        System.out.println(
                "cache_benchmark_index,unrelated_keys,lookups,lookup_total_ns,ns_per_lookup,put_ns,new_nodes");
        for (final var size : new int[] {16, 256, 4096}) {
            while (inserted < size) {
                final var key = hash("unrelated-" + inserted);
                assertFalse(selected.equals(key));
                root = index.put(root, key, hash("value-" + inserted));
                inserted++;
            }
            for (var i = 0; i < 100; i++) {
                assertEquals(value, index.get(root, selected));
            }
            final var lookupStart = System.nanoTime();
            String found = null;
            for (var i = 0; i < 100; i++) {
                found = index.get(root, selected);
            }
            final var lookupNanos = System.nanoTime() - lookupStart;
            assertEquals(value, found);
            final var oldRoot = root;
            retainedRoots.add(oldRoot);
            retainedValues.add(value);
            final var replacement = hash("selected-value-at-" + size);
            // These complete offline walks are deliberately outside the measured operation.
            final var before = countIndexPages(directory);
            final var putStart = System.nanoTime();
            root = index.put(oldRoot, selected, replacement);
            final var putNanos = System.nanoTime() - putStart;
            final var newNodes = countIndexPages(directory) - before;
            assertTrue(newNodes > 0 && newNodes <= 66, "Copy-on-write pages: " + newNodes);
            assertEquals(value, index.get(oldRoot, selected));
            assertEquals(replacement, index.get(root, selected));
            assertEquals(root, index.put(root, selected, replacement));
            assertEquals(hash("value-0"), index.get(root, hash("unrelated-0")));
            value = replacement;
            System.out.println("cache_benchmark_index," + size + ",100," + lookupNanos + ',' + lookupNanos / 100.0 + ','
                    + putNanos + ',' + newNodes);
        }
        for (var i = 0; i < retainedRoots.size(); i++) {
            assertEquals(retainedValues.get(i), index.get(retainedRoots.get(i), selected));
        }
    }

    private void measureStore(final String scenario, final long fileBytes, final int files) throws Exception {
        final var logicalBytes = Math.multiplyExact(fileBytes, files);
        requireSpace(Math.addExact(Math.multiplyExact(4, logicalBytes), 128 * MIB));
        final var shared = privateDirectory(temporary.resolve("shared"));
        final var source = privateDirectory(temporary.resolve("source"));
        final var warm = privateDirectory(temporary.resolve("warm"));
        assertFalse(source.startsWith(shared) || shared.startsWith(source));
        assertFalse(warm.startsWith(shared) || shared.startsWith(warm));
        assertFalse(warm.startsWith(source) || source.startsWith(warm));
        final var configuration = new DependencyCacheConfiguration(
                Mode.READ_WRITE,
                shared.toString(),
                Scope.REPOSITORY,
                "benchmark",
                List.of(),
                List.of(new Entry("payload", Kind.GENERIC, "payload", List.of())),
                Map.of(),
                List.of(),
                600,
                new Maintenance(30, Math.addExact(Math.multiplyExact(5, logicalBytes), 64 * MIB)),
                new Limits(Math.max(100000, files + 17), Math.max(1, logicalBytes), 16 * MIB, 600, 2));
        final var store = new DependencyCache(configuration, Clock.systemUTC());
        final var baseline = store.restore(REPOSITORY, source);
        assertEquals(Map.of("payload", ""), baseline.revisions());
        final var payload = privateDirectory(source.resolve("payload"));
        final var expected = new ArrayList<String>(files);
        for (var i = 0; i < files; i++) {
            final var path = payload.resolve(relativeFile(i));
            if (!Files.exists(path.getParent())) {
                privateDirectory(path.getParent());
            }
            expected.add(fill(path, fileBytes, i));
            Files.setPosixFilePermissions(
                    path, PosixFilePermissions.fromString(i % 2 == 0 ? "rwx------" : "rw-r-----"));
        }
        environment(scenario);
        System.out.println("cache_benchmark_store,scenario,operation,logical_bytes,files,elapsed_ns");
        final var coldStart = System.nanoTime();
        final var cold = store.publish(REPOSITORY, source, baseline);
        final var coldNanos = System.nanoTime() - coldStart;
        assertEquals(new DependencyCache.Publication(1, 0, 0, 0), cold);
        record(scenario, "cold_publish", logicalBytes, files, coldNanos);

        final var restoreStart = System.nanoTime();
        final var restoredBaseline = store.restore(REPOSITORY, warm);
        final var restoreNanos = System.nanoTime() - restoreStart;
        assertFalse(restoredBaseline.revisions().get("payload").isEmpty());
        record(scenario, "warm_private_restore", logicalBytes, files, restoreNanos);

        final var unchangedStart = System.nanoTime();
        final var unchanged = store.publish(REPOSITORY, warm, restoredBaseline);
        final var unchangedNanos = System.nanoTime() - unchangedStart;
        assertEquals(new DependencyCache.Publication(0, 1, 0, 0), unchanged);
        record(scenario, "unchanged_publish", logicalBytes, files, unchangedNanos);

        final var restoredPayload = warm.resolve("payload");
        try (final var paths = Files.walk(restoredPayload)) {
            assertEquals(
                    files,
                    paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                            .count());
        }
        try (final var paths = Files.walk(restoredPayload)) {
            // No extra directories, links or special entries may appear in the restored tree.
            assertEquals(1L + Math.min(files, 16) + files, paths.count());
        }
        assertEquals(Files.getPosixFilePermissions(payload), Files.getPosixFilePermissions(restoredPayload));
        for (var i = 0; i < files; i++) {
            final var original = payload.resolve(relativeFile(i));
            final var restored = restoredPayload.resolve(relativeFile(i));
            final var digest = expected.get(i);
            // Actual DependencyCache object layout, not a scan of the shared store.
            final var blob =
                    shared.resolve("blobs").resolve(digest.substring(0, 2)).resolve(digest.substring(2));
            assertEquals(fileBytes, Files.size(restored));
            assertEquals(fileBytes, Files.size(blob));
            assertEquals(digest, digest(restored));
            assertEquals(Files.getPosixFilePermissions(original), Files.getPosixFilePermissions(restored));
            assertEquals(
                    Files.getPosixFilePermissions(original.getParent()),
                    Files.getPosixFilePermissions(restored.getParent()));
            assertFalse(Files.isSameFile(original, blob));
            assertFalse(Files.isSameFile(original, restored));
            assertFalse(Files.isSameFile(blob, restored));
        }
    }

    private Path relativeFile(final int index) {
        return Path.of("group-" + index % 16, "file-" + index);
    }

    private Path privateDirectory(final Path path) throws IOException {
        return Files.createDirectory(
                path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    private String fill(final Path path, final long bytes, final int seed) throws Exception {
        final var digest = MessageDigest.getInstance("SHA-256");
        final var random = new Random(seed);
        final var buffer = new byte[64 * 1024];
        try (final var output = Files.newOutputStream(path)) {
            var remaining = bytes;
            while (remaining > 0) {
                random.nextBytes(buffer);
                final var length = (int) Math.min(buffer.length, remaining);
                output.write(buffer, 0, length);
                digest.update(buffer, 0, length);
                remaining -= length;
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String digest(final Path path) throws Exception {
        final var digest = MessageDigest.getInstance("SHA-256");
        final var buffer = new byte[64 * 1024];
        try (final var input = Files.newInputStream(path)) {
            int length;
            while ((length = input.read(buffer)) != -1) {
                digest.update(buffer, 0, length);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String hash(final String text) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private long countIndexPages(final Path directory) throws IOException {
        long count = 0;
        try (final var paths = Files.walk(directory)) {
            final var iterator = paths.iterator();
            while (iterator.hasNext()) {
                final var path = iterator.next();
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    assertTrue(Files.size(path) <= 8192, "Index page exceeds bounded encoding: " + path);
                    count++;
                }
            }
        }
        return count;
    }

    private void requireSpace(final long required) throws IOException {
        final var available = Files.getFileStore(temporary).getUsableSpace();
        assumeTrue(
                available >= required,
                () -> "Cache benchmark skipped: usable bytes=" + available + ", required=" + required);
    }

    private void environment(final String scenario) throws IOException {
        final var store = Files.getFileStore(temporary);
        System.out.println("cache_benchmark_environment," + csv(scenario) + ','
                + csv(temporary.getFileSystem().provider().getClass().getName()) + ',' + csv(store.type()) + ','
                + csv(System.getProperty("java.version")) + ',' + csv(System.getProperty("os.name")) + ','
                + csv(System.getProperty("os.version")) + ',' + csv(System.getProperty("os.arch")) + ','
                + Runtime.getRuntime().availableProcessors());
    }

    private String csv(final String value) {
        return '"' + value.replace("\"", "\"\"").replace('\n', ' ').replace('\r', ' ') + '"';
    }

    private void record(
            final String scenario, final String operation, final long bytes, final int files, final long nanos) {
        System.out.println(
                "cache_benchmark_store," + scenario + ',' + operation + ',' + bytes + ',' + files + ',' + nanos);
    }
}
