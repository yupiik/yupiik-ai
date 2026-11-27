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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Entry;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Kind;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Limits;
import io.yupiik.ia.agent.github.support.TestJava;
import io.yupiik.ia.agent.testing.UnlinkingTempDirDeletionStrategy;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheSnapshotTest {
    private static final Entry GENERIC = new Entry("npm", Kind.GENERIC, "cache", List.of());
    private static final Limits LIMITS = new Limits(10_000, 128L * 1024 * 1024, 4 * 1024 * 1024, 60, 1);
    private static final int STREAMING_HEAP_MIB = 8;
    private static final int STREAMING_FILE_BYTES = 12 * 1024 * 1024;

    @TempDir(deletionStrategy = UnlinkingTempDirDeletionStrategy.class)
    Path temporary;

    @Test
    void roundTripIsDeterministicWithIndependentHardlinksAndDigestNamedBlobs() throws Exception {
        final var source = Files.createDirectory(temporary.resolve("source"));
        Files.createDirectory(source.resolve("z"));
        Files.writeString(source.resolve("z/b"), "payload");
        Files.createLink(source.resolve("a"), source.resolve("z/b"));
        final var objects = new Objects(temporary.resolve("objects"));
        final var snapshot = new CacheSnapshot(LIMITS, Long.MAX_VALUE);
        final var first = temporary.resolve("first");
        final var result = snapshot.capture(source, GENERIC, first, objects::install);
        assertEquals(2, result.files());
        assertEquals(14, result.bytes());
        assertEquals(1, objects.installed.get());
        assertEquals(2, objects.calls.get());
        assertEquals(sha(Files.readAllBytes(first)), result.digest());
        // Recreate one name, losing hardlink identity and changing creation order/timestamps.
        Files.delete(source.resolve("a"));
        Files.writeString(source.resolve("a"), "payload");
        final var second = temporary.resolve("second");
        assertEquals(result, snapshot.capture(source, GENERIC, second, objects::install));
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
        assertEquals(1, objects.installed.get());
        final var destination = temporary.resolve("restored");
        snapshot.restore(first, destination, GENERIC, objects::acquire);
        assertEquals("payload", Files.readString(destination.resolve("z/b")));
        Files.writeString(destination.resolve("a"), "changed");
        assertEquals("payload", Files.readString(destination.resolve("z/b")));
    }

    @Test
    void presentEmptyIsValidMissingAndSymlinkRootsAreNot() throws Exception {
        final var snapshot = new CacheSnapshot(LIMITS, Long.MAX_VALUE);
        final var source = Files.createDirectory(temporary.resolve("empty"));
        final var manifest = temporary.resolve("manifest");
        final var result = snapshot.capture(source, GENERIC, manifest, (digest, path, size) -> {
            throw new AssertionError("Empty snapshot has no blobs");
        });
        assertEquals(0, result.files());
        assertEquals(0, result.bytes());
        final var destination = temporary.resolve("restored");
        snapshot.restore(manifest, destination, GENERIC, (digest, size) -> {
            throw new AssertionError("Empty snapshot has no blobs");
        });
        try (final var children = Files.list(destination)) {
            assertEquals(0, children.count());
        }
        assertThrows(
                IOException.class,
                () -> snapshot.capture(
                        temporary.resolve("missing"), GENERIC, temporary.resolve("missing-manifest"), this::noInstall));
        final var link = Files.createSymbolicLink(temporary.resolve("linked"), source.getFileName());
        assertThrows(
                IOException.class,
                () -> snapshot.capture(link, GENERIC, temporary.resolve("linked-manifest"), this::noInstall));
        assertThrows(IOException.class, () -> snapshot.restore(manifest, destination, GENERIC, this::noRead));
    }

    @Test
    void restoresInternalBinAndDirectoryLinksLast() throws Exception {
        final var source = Files.createDirectory(temporary.resolve("source"));
        Files.createDirectories(source.resolve("packages/tool"));
        Files.createDirectory(source.resolve(".bin"));
        Files.writeString(source.resolve("packages/tool/run"), "#!/bin/sh\n");
        Files.createSymbolicLink(source.resolve("alias"), Path.of("packages"));
        Files.createSymbolicLink(source.resolve(".bin/tool"), Path.of("../alias/tool/run"));
        Files.createSymbolicLink(source.resolve(".bin/chain"), Path.of("tool"));
        Files.createSymbolicLink(source.resolve("root"), Path.of("."));
        Files.createSymbolicLink(source.resolve("repeated"), Path.of("alias/../alias/tool/run"));
        final var objects = new Objects(temporary.resolve("objects"));
        final var snapshot = new CacheSnapshot(LIMITS, Long.MAX_VALUE);
        final var manifest = temporary.resolve("manifest");
        snapshot.capture(source, GENERIC, manifest, objects::install);
        final var destination = temporary.resolve("destination");
        snapshot.restore(manifest, destination, GENERIC, objects::acquire);
        assertEquals(Path.of("../alias/tool/run"), Files.readSymbolicLink(destination.resolve(".bin/tool")));
        assertEquals("#!/bin/sh\n", Files.readString(destination.resolve(".bin/chain")));
        assertTrue(Files.isSymbolicLink(destination.resolve("alias")));
        assertEquals(Path.of("."), Files.readSymbolicLink(destination.resolve("root")));
        assertEquals("#!/bin/sh\n", Files.readString(destination.resolve("repeated")));
    }

    @Test
    void refusesSpecialFilesAndStripsPrivilegedModeBits() throws Exception {
        final var source = Files.createDirectory(temporary.resolve("source"));
        final var socket = source.resolve("socket");
        try (final var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            assertThrows(
                    IOException.class,
                    () -> new CacheSnapshot(LIMITS, Long.MAX_VALUE)
                            .capture(source, GENERIC, temporary.resolve("special-manifest"), this::noInstall));
        }
        Files.delete(socket);
        Files.writeString(source.resolve("data"), "payload");
        Files.setAttribute(source.resolve("data"), "unix:mode", 06755, LinkOption.NOFOLLOW_LINKS);
        final var objects = new Objects(temporary.resolve("objects"));
        final var snapshot = new CacheSnapshot(LIMITS, Long.MAX_VALUE);
        final var manifest = temporary.resolve("manifest");
        snapshot.capture(source, GENERIC, manifest, objects::install);
        final var destination = temporary.resolve("destination");
        snapshot.restore(manifest, destination, GENERIC, objects::acquire);
        assertEquals(0755, (int) Files.getAttribute(destination.resolve("data"), "unix:mode") & 07777);
    }

    @Test
    void refusesExternalAbsoluteDanglingCyclicAndOverdeepLinks() throws Exception {
        final var snapshot = new CacheSnapshot(LIMITS, Long.MAX_VALUE);
        for (final var target : List.of("../outside", "/etc/passwd", "missing", "self", "C:/outside")) {
            final var source = Files.createTempDirectory(temporary, "unsafe");
            Files.createSymbolicLink(source.resolve("self"), Path.of(target));
            final var manifest = Files.createTempFile(temporary, "manifest", "");
            Files.delete(manifest);
            assertThrows(IOException.class, () -> snapshot.capture(source, GENERIC, manifest, this::noInstall), target);
            assertFalse(Files.exists(manifest));
        }
        final var source = Files.createDirectory(temporary.resolve("cycle"));
        Files.createSymbolicLink(source.resolve("a"), Path.of("b"));
        Files.createSymbolicLink(source.resolve("b"), Path.of("a"));
        assertThrows(
                IOException.class,
                () -> snapshot.capture(source, GENERIC, temporary.resolve("cycle-manifest"), this::noInstall));
        Files.delete(source.resolve("a"));
        Files.delete(source.resolve("b"));
        Files.createDirectory(source.resolve("end"));
        for (int i = 0; i < 41; i++) {
            Files.createSymbolicLink(source.resolve("link" + i), Path.of(i == 40 ? "end" : "link" + (i + 1)));
        }
        assertThrows(
                IOException.class,
                () -> snapshot.capture(source, GENERIC, temporary.resolve("depth-manifest"), this::noInstall));
        Files.delete(source.resolve("link0"));
        snapshot.capture(source, GENERIC, temporary.resolve("forty-links"), this::noInstall);
    }

    @Test
    void preservesExecutableAndReadonlyModesAndFinalizesDirectoriesAfterChildren() throws Exception {
        final var source = Files.createDirectory(temporary.resolve("source"));
        final var directory = Files.createDirectory(source.resolve("readonly"));
        final var script = Files.writeString(directory.resolve("run"), "exit 0\n");
        final var data = Files.writeString(directory.resolve("data"), "immutable");
        permissions(script, "r-xr-x---");
        permissions(data, "r--r-----");
        permissions(directory, "r-xr-x---");
        permissions(source, "r-x------");
        final var destination = temporary.resolve("destination");
        try {
            final var objects = new Objects(temporary.resolve("objects"));
            final var snapshot = new CacheSnapshot(LIMITS, Long.MAX_VALUE);
            final var manifest = temporary.resolve("manifest");
            snapshot.capture(source, GENERIC, manifest, objects::install);
            snapshot.restore(manifest, destination, GENERIC, objects::acquire);
            for (final var relative : List.of("", "readonly", "readonly/run", "readonly/data")) {
                assertEquals(
                        Files.getPosixFilePermissions(source.resolve(relative)),
                        Files.getPosixFilePermissions(destination.resolve(relative)),
                        relative);
            }
            assertEquals("immutable", Files.readString(destination.resolve("readonly/data")));
        } finally {
            permissions(source, "rwx------");
            permissions(directory, "rwx------");
            if (Files.exists(destination)) {
                permissions(destination, "rwx------");
                if (Files.exists(destination.resolve("readonly"))) {
                    permissions(destination.resolve("readonly"), "rwx------");
                }
            }
        }
    }

    @Test
    void filtersMavenAtEveryDepthAndValidatesFiltersOnRestore() throws Exception {
        final var source = Files.createDirectory(temporary.resolve("source"));
        Files.createDirectories(source.resolve("group/1-SNAPSHOT/nested"));
        Files.writeString(source.resolve("group/1-SNAPSHOT/nested/ignored"), "snapshot");
        Files.createDirectories(source.resolve("group/1.0"));
        Files.writeString(source.resolve("group/1.0/artifact"), "release");
        Files.writeString(source.resolve("file-SNAPSHOT"), "not a directory");
        final var maven = new Entry("custom-maven", Kind.MAVEN, "cache", List.of(".temporary"));
        Files.createDirectory(source.resolve("extra.temporary"));
        final var objects = new Objects(temporary.resolve("objects"));
        final var snapshot = new CacheSnapshot(LIMITS, Long.MAX_VALUE);
        final var manifest = temporary.resolve("filtered");
        assertEquals(
                2, snapshot.capture(source, maven, manifest, objects::install).files());
        final var destination = temporary.resolve("destination");
        snapshot.restore(manifest, destination, maven, objects::acquire);
        assertFalse(Files.exists(destination.resolve("group/1-SNAPSHOT")));
        assertFalse(Files.exists(destination.resolve("extra.temporary")));
        assertEquals("release", Files.readString(destination.resolve("group/1.0/artifact")));
        final var unfiltered = temporary.resolve("unfiltered");
        snapshot.capture(source, GENERIC, unfiltered, objects::install);
        final var rejected = temporary.resolve("rejected");
        assertThrows(IOException.class, () -> snapshot.restore(unfiltered, rejected, maven, this::noRead));
        assertFalse(Files.exists(rejected));
    }

    @Test
    void filtersOnlyDocumentedGradleTransientNamesAndDoesNotFilterGoLocks() throws Exception {
        final var source = Files.createDirectory(temporary.resolve("source"));
        Files.createDirectory(source.resolve("nested"));
        for (final var file : List.of("modules-2.lock", "nested/gc.properties", "keep.lock", "keep.properties")) {
            Files.writeString(source.resolve(file), file);
        }
        final var configuration = new DependencyCacheConfiguration(
                DependencyCacheConfiguration.Mode.READ_WRITE,
                "/cache",
                DependencyCacheConfiguration.Scope.REPOSITORY,
                "default",
                List.of(DependencyCacheConfiguration.Preset.GRADLE),
                List.of(),
                Map.of(),
                List.of(),
                300,
                new DependencyCacheConfiguration.Maintenance(30, 1024 * 1024),
                LIMITS);
        final var gradle = configuration.resolvedEntries().getFirst();
        final var go = new Entry("go", Kind.GENERIC, "cache", List.of());
        final var objects = new Objects(temporary.resolve("objects"));
        final var snapshot = new CacheSnapshot(LIMITS, Long.MAX_VALUE);
        final var manifest = temporary.resolve("filtered");
        assertEquals(
                2, snapshot.capture(source, gradle, manifest, objects::install).files());
        final var destination = temporary.resolve("destination");
        snapshot.restore(manifest, destination, gradle, objects::acquire);
        assertTrue(Files.exists(destination.resolve("keep.lock")));
        assertFalse(Files.exists(destination.resolve("nested/gc.properties")));
        final var unfiltered = temporary.resolve("go");
        assertEquals(
                4, snapshot.capture(source, go, unfiltered, objects::install).files());
        final var rejected = temporary.resolve("rejected");
        assertThrows(IOException.class, () -> snapshot.restore(unfiltered, rejected, gradle, this::noRead));
        assertFalse(Files.exists(rejected));
    }

    @Test
    void rejectsMaliciousMetadataBeforeCreatingDestination() throws Exception {
        // Cases exercise the public binary wire format, not private implementation methods.
        for (final var path : List.of(
                "/absolute",
                "../escape",
                "a/../../escape",
                "a//b",
                "a/./b",
                "C:/escape",
                "a\\b",
                "a/".repeat(129) + "deep")) {
            final var manifest = raw(List.of(new Record("", 1, 0700, null), new Record(path, 1, 0700, null)));
            rejected(manifest);
        }
        for (final var records : List.of(
                List.of(new Record("", 1, 0700, null), new Record("dir/child", 1, 0700, null)),
                List.of(new Record("", 1, 0700, null), new Record("", 1, 0700, null)),
                List.of(new Record("", 1, 01000, null)),
                List.of(new Record("", 1, 0700, null), new Record("a", 3, 0, "../outside")),
                List.of(new Record("", 1, 0700, null), new Record("a", 3, 0, "/absolute")),
                List.of(new Record("", 1, 0700, null), new Record("a", 3, 0, "a")),
                List.of(
                        new Record("", 1, 0700, null),
                        new Record("a", 3, 0, "."),
                        new Record("a/child", 1, 0700, null)),
                List.of(new Record("", 1, 0700, null), new Record("a", 3, 0, "b"), new Record("b", 3, 0, "a")),
                List.of(new Record("", 1, 0700, null), new Record("a", 99, 0, null)))) {
            rejected(raw(records));
        }
    }

    @Test
    void rejectsAllTruncationsBadFooterTrailingBytesAndOversizedMetadata() throws Exception {
        final var manifest = raw(List.of(new Record("", 1, 0700, null), new Record("directory", 1, 0700, null)));
        final var complete = Files.readAllBytes(manifest);
        for (int length = 0; length < complete.length; length++) {
            Files.write(manifest, Arrays.copyOf(complete, length));
            rejected(manifest);
        }
        Files.write(manifest, complete);
        Files.write(manifest, new byte[] {1}, StandardOpenOption.APPEND);
        rejected(manifest);
        complete[complete.length - 1] = 1; // Footer byte count no longer agrees.
        Files.write(manifest, complete);
        rejected(manifest);
        for (final int length : new int[] {-1, Integer.MAX_VALUE, 4097}) {
            try (final var out = new DataOutputStream(Files.newOutputStream(manifest))) {
                out.writeInt(0x59434143);
                out.writeInt(1);
                out.writeByte(1);
                out.writeInt(length);
            }
            rejected(manifest);
        }
        try (final var out = new DataOutputStream(Files.newOutputStream(manifest))) {
            out.writeInt(0x59434143);
            out.writeInt(1);
            out.writeByte(1);
            out.writeInt(2);
            out.write(new byte[] {(byte) 0xc0, (byte) 0xaf});
        }
        rejected(manifest);
    }

    @Test
    void enforcesFileManifestAndGlobalRecordBoundsOnCaptureAndRestore() throws Exception {
        final var source = Files.createDirectory(temporary.resolve("source"));
        Files.createDirectories(source.resolve("a/b"));
        Files.writeString(source.resolve("a/b/data"), "12345678");
        final var objects = new Objects(temporary.resolve("objects"));
        final var manifest = temporary.resolve("manifest");
        new CacheSnapshot(LIMITS, Long.MAX_VALUE).capture(source, GENERIC, manifest, objects::install);
        for (final var limits : List.of(
                new Limits(3, 1024, 4096, 60, 1), new Limits(10, 7, 4096, 60, 1), new Limits(10, 1024, 64, 60, 1))) {
            final var snapshot = new CacheSnapshot(limits, Long.MAX_VALUE);
            final var output = temporary.resolve("failed-manifest");
            assertThrows(IOException.class, () -> snapshot.capture(source, GENERIC, output, objects::install));
            assertFalse(Files.exists(output));
            final var destination = temporary.resolve("failed-destination");
            assertThrows(IOException.class, () -> snapshot.restore(manifest, destination, GENERIC, this::noRead));
            assertFalse(Files.exists(destination));
        }
        final var wide = Files.createDirectory(temporary.resolve("wide"));
        for (int i = 0; i < 4; i++) {
            Files.createDirectory(wide.resolve("d" + i));
        }
        assertThrows(
                IOException.class,
                () -> new CacheSnapshot(new Limits(4, 1024, 4096, 60, 1), Long.MAX_VALUE)
                        .capture(wide, GENERIC, temporary.resolve("wide-manifest"), this::noInstall));
    }

    @Test
    void refusesSymlinkAncestorsDestinationLinksAndBlobLinksWithoutTouchingTargets() throws Exception {
        final var outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("sentinel"), "untouched");
        final var alias = Files.createSymbolicLink(temporary.resolve("alias"), outside.getFileName());
        final var snapshot = new CacheSnapshot(LIMITS, Long.MAX_VALUE);
        assertThrows(
                IOException.class,
                () -> snapshot.capture(
                        alias.resolve("sentinel"), GENERIC, temporary.resolve("manifest1"), this::noInstall));
        final var source = Files.createDirectory(temporary.resolve("source"));
        Files.writeString(source.resolve("data"), "payload");
        final var objects = new Objects(temporary.resolve("objects"));
        final var manifest = temporary.resolve("manifest");
        snapshot.capture(source, GENERIC, manifest, objects::install);
        assertThrows(
                IOException.class,
                () -> snapshot.restore(manifest, alias.resolve("destination"), GENERIC, this::noRead));
        assertThrows(IOException.class, () -> snapshot.restore(manifest, alias, GENERIC, this::noRead));
        final var blobLink = Files.createSymbolicLink(temporary.resolve("blob-link"), outside.resolve("sentinel"));
        assertThrows(
                IOException.class,
                () -> snapshot.restore(manifest, temporary.resolve("bad-blob"), GENERIC, (digest, size) -> blobLink));
        final var manifestLink = Files.createSymbolicLink(temporary.resolve("manifest-link"), manifest.getFileName());
        assertThrows(
                IOException.class,
                () -> snapshot.restore(manifestLink, temporary.resolve("bad-manifest"), GENERIC, this::noRead));
        assertThrows(
                IOException.class,
                () -> snapshot.capture(source, GENERIC, alias.resolve("manifest"), objects::install));
        assertEquals("untouched", Files.readString(outside.resolve("sentinel")));
        assertFalse(Files.exists(outside.resolve("destination")));
        assertFalse(Files.exists(outside.resolve("manifest")));
        assertThrows(
                IOException.class,
                () -> snapshot.capture(source, GENERIC, source.resolve("manifest"), objects::install));
    }

    @Test
    void detectsCorruptTruncatedAndOversizedBlobs() throws Exception {
        final var source = Files.createDirectory(temporary.resolve("source"));
        Files.writeString(source.resolve("data"), "payload");
        final var objects = new Objects(temporary.resolve("objects"));
        final var snapshot = new CacheSnapshot(LIMITS, Long.MAX_VALUE);
        final var manifest = temporary.resolve("manifest");
        snapshot.capture(source, GENERIC, manifest, objects::install);
        final var blob = temporary.resolve("bad");
        for (final var contents : List.of("payloaD", "short", "payload plus")) {
            Files.writeString(blob, contents);
            final var destination = temporary.resolve("bad-" + contents.length());
            assertThrows(
                    IOException.class, () -> snapshot.restore(manifest, destination, GENERIC, (digest, size) -> blob));
        }
    }

    @Test
    void explicitDeadlineIsSharedAcrossCallsAndDoesNotRenewTheSnapshotBudget() throws Exception {
        final var source = Files.createDirectory(temporary.resolve("shared-deadline-source"));
        Files.writeString(source.resolve("data"), "payload");
        final var objects = new Objects(temporary.resolve("shared-deadline-objects"));
        final var snapshot = new CacheSnapshot(LIMITS, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(150));
        final var manifest = temporary.resolve("shared-deadline-manifest");
        snapshot.capture(source, GENERIC, manifest, objects::install);
        Thread.sleep(200);
        assertThrows(
                CacheSnapshot.OperationTimeoutException.class,
                () -> snapshot.restore(manifest, temporary.resolve("expired-restore"), GENERIC, objects::acquire));
        assertThrows(
                CacheSnapshot.OperationTimeoutException.class,
                () -> snapshot.capture(source, GENERIC, temporary.resolve("expired-capture"), objects::install));
        assertFalse(Files.exists(temporary.resolve("expired-restore")));
        assertFalse(Files.exists(temporary.resolve("expired-capture")));
    }

    @Test
    void checksDeadlineAfterCoordinatorCallbacksAndHonorsInterruption() throws Exception {
        final var source = Files.createDirectory(temporary.resolve("source"));
        Files.writeString(source.resolve("data"), "payload");
        final var snapshot = new CacheSnapshot(new Limits(100, 1024, 4096, 1, 1), Long.MAX_VALUE);
        final var manifest = temporary.resolve("timeout");
        assertThrows(
                IOException.class,
                () -> snapshot.capture(source, GENERIC, manifest, (digest, path, size) -> {
                    try {
                        Thread.sleep(1100);
                    } catch (final InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException(interrupted);
                    }
                }));
        assertFalse(Files.exists(manifest));
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> snapshot.capture(source, GENERIC, manifest, this::noInstall));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void streamsLargeFilesInConstrainedHeap() throws Exception {
        final var classes = Path.of(CacheSnapshotTest.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        final var mainClasses = Path.of(CacheSnapshot.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        final var configuration = Path.of(
                Entry.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        final var junit = Path.of(
                Test.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        final var classpath = String.join(
                File.pathSeparator,
                System.getProperty("java.class.path"),
                classes.toString(),
                mainClasses.toString(),
                configuration.toString(),
                junit.toString());
        final var log = temporary.resolve("child.log");
        final var process = new ProcessBuilder(TestJava.javaCommand(
                        "-Xmx" + STREAMING_HEAP_MIB + "m",
                        "-cp",
                        classpath,
                        CacheSnapshotTest.class.getName(),
                        temporary.toString()))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Constrained-heap test timed out");
            assertEquals(0, process.exitValue(), () -> {
                try {
                    return Files.readString(log);
                } catch (final IOException error) {
                    return error.toString();
                }
            });
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor();
            }
        }
    }

    // Keep the file larger than the entire child heap without retaining three 40 MiB copies on disk.
    public static void main(final String[] args) throws Exception {
        if (STREAMING_FILE_BYTES <= Runtime.getRuntime().maxMemory()) {
            throw new IllegalStateException("Streaming fixture must exceed the child heap");
        }
        final var temporary = Path.of(args[0]);
        final var source = Files.createDirectory(temporary.resolve("large-source"));
        final var data = source.resolve("large");
        try (final var out = Files.newOutputStream(data)) {
            final var buffer = new byte[64 * 1024];
            for (int i = 0; i < STREAMING_FILE_BYTES / buffer.length; i++) {
                out.write(buffer);
            }
        }
        for (int i = 0; i < 1000; i++) {
            Files.writeString(source.resolve("small-" + i), "content");
        }
        final var objects = new Objects(temporary.resolve("objects"));
        final var snapshot = new CacheSnapshot(LIMITS, Long.MAX_VALUE);
        final var manifest = temporary.resolve("large-manifest");
        final var result = snapshot.capture(source, GENERIC, manifest, objects::install);
        final var destination = temporary.resolve("large-destination");
        snapshot.restore(manifest, destination, GENERIC, objects::acquire);
        if (result.files() != 1001
                || result.bytes() != STREAMING_FILE_BYTES + 7000L
                || Files.mismatch(data, destination.resolve("large")) != -1) {
            throw new IllegalStateException("Streaming roundtrip failed");
        }
    }

    private void rejected(final Path manifest) {
        final var destination = temporary.resolve("rejected");
        assertThrows(
                IOException.class,
                () -> new CacheSnapshot(LIMITS, Long.MAX_VALUE).restore(manifest, destination, GENERIC, this::noRead));
        assertFalse(Files.exists(destination, LinkOption.NOFOLLOW_LINKS));
    }

    private Path raw(final List<Record> records) throws IOException {
        final var manifest = Files.createTempFile(temporary, "raw", "");
        try (final var out = new DataOutputStream(Files.newOutputStream(manifest))) {
            out.writeInt(0x59434143);
            out.writeInt(1);
            for (final var record : records) {
                out.writeByte(record.kind);
                text(out, record.path);
                out.writeInt(record.mode);
                if (record.kind == 3) {
                    text(out, record.target);
                }
            }
            out.writeByte(0);
            out.writeLong(records.size());
            out.writeLong(0);
            out.writeLong(0);
        }
        return manifest;
    }

    private static void text(final DataOutputStream out, final String value) throws IOException {
        final var bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static void permissions(final Path path, final String permissions) throws IOException {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
    }

    private void noInstall(final String digest, final Path source, final long size) {
        throw new AssertionError("Unexpected installer call");
    }

    private Path noRead(final String digest, final long size) {
        throw new AssertionError("Unexpected blob read");
    }

    private static String sha(final byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private record Record(String path, int kind, int mode, String target) {}

    /** Test-only coordinator: absent-only copying with digest and size verification. */
    private static final class Objects {
        private final Path root;
        private final AtomicInteger installed = new AtomicInteger();
        private final AtomicInteger calls = new AtomicInteger();

        private Objects(final Path root) throws IOException {
            this.root = Files.createDirectory(root);
        }

        private void install(final String digest, final Path source, final long size) throws IOException {
            calls.incrementAndGet();
            final var target = root.resolve(digest);
            if (Files.exists(target)) {
                return;
            }
            final MessageDigest hash;
            try {
                hash = MessageDigest.getInstance("SHA-256");
            } catch (final Exception impossible) {
                throw new IllegalStateException(impossible);
            }
            long copied = 0;
            try (final var in = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
                    final var out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                final var buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    copied += read;
                    hash.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
            if (copied != size || !HexFormat.of().formatHex(hash.digest()).equals(digest)) {
                Files.delete(target);
                throw new IOException("Installer rejected changed source");
            }
            installed.incrementAndGet();
        }

        private Path acquire(final String digest, final long expectedSize) throws IOException {
            final var result = root.resolve(digest);
            if (Files.size(result) != expectedSize) {
                throw new IOException("Unexpected blob size");
            }
            return result;
        }
    }
}
