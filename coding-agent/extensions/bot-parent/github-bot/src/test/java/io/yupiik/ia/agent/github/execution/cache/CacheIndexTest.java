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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class CacheIndexTest {
    private static final String HEX = "0123456789abcdef";
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);

    @TempDir
    public Path temporary;

    @Test
    public void emptySingleLeafAndReopening() throws Exception {
        final var directory = temporary.resolve("index");
        final var index = new CacheIndex(directory);
        assertEquals("", CacheIndex.EMPTY);
        assertNull(index.get(CacheIndex.EMPTY, A));
        assertEquals(new CacheIndex.NodeView(List.of(), null), index.inspect(CacheIndex.EMPTY));
        assertEquals(CacheIndex.EMPTY, index.put(CacheIndex.EMPTY, A, null));
        assertEquals(0, files(directory).size());

        final var root = index.put(CacheIndex.EMPTY, A, B);
        assertEquals(64, root.length());
        assertEquals(B, index.get(root, A));
        assertNull(index.get(root, B));
        assertEquals(new CacheIndex.NodeView(List.of(), B), index.inspect(root));
        assertEquals(B, new CacheIndex(directory).get(root, A));
        assertEquals(root, index.put(root, A, B));
        assertEquals(root, index.put(root, B, null));
        assertEquals(CacheIndex.EMPTY, index.put(root, A, null));
        assertEquals(B, index.get(root, A));
        assertEquals(Set.of(nodePath(directory, root)), files(directory));
    }

    @Test
    public void forksAreIndependentAndPublishedNodesAreNeverRewritten() throws Exception {
        final var index = new CacheIndex(temporary);
        final var root = index.put(CacheIndex.EMPTY, A, B);
        final var path = nodePath(temporary, root);
        final var bytes = Files.readAllBytes(path);
        final var attributes = Files.readAttributes(path, BasicFileAttributes.class);
        final var left = index.put(root, B, C);
        final var right = index.put(root, A, C);
        assertEquals(B, index.get(root, A));
        assertNull(index.get(root, B));
        assertEquals(B, index.get(left, A));
        assertEquals(C, index.get(left, B));
        assertEquals(C, index.get(right, A));
        assertNull(index.get(right, B));
        assertEquals(root, index.put(right, A, B));
        assertArrayEquals(bytes, Files.readAllBytes(path));
        final var after = Files.readAttributes(path, BasicFileAttributes.class);
        assertEquals(attributes.fileKey(), after.fileKey());
        assertEquals(attributes.lastModifiedTime(), after.lastModifiedTime());
        assertEquals(attributes.creationTime(), after.creationTime());
    }

    @Test
    public void sharesUntouchedSubtreesAndOnlyReadsTheSelectedPath() throws Exception {
        final var index = new CacheIndex(temporary);
        final var a0 = "a0" + "0".repeat(62);
        final var a1 = "a1" + "0".repeat(62);
        final var b0 = "b0" + "0".repeat(62);
        final var b1 = "b1" + "0".repeat(62);
        var root = CacheIndex.EMPTY;
        for (final var key : List.of(a0, a1, b0, b1)) {
            root = index.put(root, key, A);
        }
        final var children = index.inspect(root).children();
        assertEquals(2, children.size());
        final var unchanged = reachable(index, children.get(1));
        final var before = files(temporary).size();
        final var updated = index.put(root, a0, B);
        assertEquals(children.get(1), index.inspect(updated).children().get(1));
        assertTrue(reachable(index, updated).containsAll(unchanged));
        assertEquals(3, files(temporary).size() - before); // leaf, a-branch, root
        assertEquals(A, index.get(root, a0));
        assertEquals(B, index.get(updated, a0));

        // Removing an unrelated subtree's files makes any accidental read fail.
        for (final var digest : unchanged) {
            Files.delete(nodePath(temporary, digest));
        }
        final var changedAgain = index.put(updated, a1, C);
        assertEquals(B, index.get(changedAgain, a0));
        assertEquals(C, index.get(changedAgain, a1));
        assertEquals(changedAgain, index.put(changedAgain, a1, C));
        assertEquals(2, index.inspect(changedAgain).children().size());
        final var collapsed = index.put(index.put(changedAgain, a0, null), a1, null);
        assertEquals(children.get(1), collapsed); // collapse needs no surviving-node read
    }

    @Test
    public void compressesLongPrefixesAndCollapsesRemovalsBackToOriginalRoots() throws Exception {
        final var index = new CacheIndex(temporary);
        final var first = "0".repeat(64);
        final var second = "0".repeat(63) + "1";
        final var third = "0".repeat(31) + "1" + "0".repeat(32);
        final var leaf = index.put(CacheIndex.EMPTY, first, A);
        final var pair = index.put(leaf, second, B);
        assertEquals(3, reachable(index, pair).size());
        assertEquals(2, index.inspect(pair).children().size());
        final var triple = index.put(pair, third, C);
        assertEquals(5, reachable(index, triple).size());
        assertEquals(A, index.get(triple, first));
        assertEquals(B, index.get(triple, second));
        assertEquals(C, index.get(triple, third));
        assertEquals(pair, index.put(triple, third, null));
        assertEquals(leaf, index.put(pair, second, null));
        assertEquals(leaf, index.put(index.put(triple, second, null), third, null));
        assertEquals(CacheIndex.EMPTY, index.put(leaf, first, null));
    }

    @Test
    public void canonicalRootsDoNotDependOnInsertionOrderAndFanoutIsBounded() throws Exception {
        final var index = new CacheIndex(temporary);
        var forward = CacheIndex.EMPTY;
        var backward = CacheIndex.EMPTY;
        for (var i = 0; i < 16; i++) {
            forward = index.put(forward, HEX.charAt(i) + "0".repeat(63), A);
            backward = index.put(backward, HEX.charAt(15 - i) + "0".repeat(63), A);
        }
        assertEquals(forward, backward);
        assertEquals(16, index.inspect(forward).children().size());
        assertEquals(17, reachable(index, forward).size());
        final var immutableChildren = index.inspect(forward).children();
        assertThrows(UnsupportedOperationException.class, immutableChildren::clear);
        for (final var path : files(temporary)) {
            assertTrue(Files.size(path) <= 8192);
            assertEquals(2, path.getParent().getFileName().toString().length());
            assertEquals(62, path.getFileName().toString().length());
        }
    }

    @Test
    public void terminalBranchesSupportAllNibblesAndPreserveCanonicalRoots() throws Exception {
        final var index = new CacheIndex(temporary);
        final var prefix = "a".repeat(63);
        final var first = index.put(CacheIndex.EMPTY, prefix + "0", A);
        var root = first;
        var reverse = CacheIndex.EMPTY;
        for (var i = 1; i < 16; i++) {
            root = index.put(root, prefix + HEX.charAt(i), A);
        }
        for (var i = 15; i >= 0; i--) {
            reverse = index.put(reverse, prefix + HEX.charAt(i), A);
        }
        assertEquals(root, reverse);
        assertEquals(16, index.inspect(root).children().size());
        assertNull(index.inspect(root).value());
        assertEquals(17, reachable(index, root).size());
        for (var i = 0; i < 16; i++) {
            final var key = prefix + HEX.charAt(i);
            assertEquals(A, index.get(root, key));
            final var changed = index.put(root, key, B);
            assertEquals(B, index.get(changed, key));
            assertEquals(root, index.put(changed, key, A));
        }
        for (var i = 15; i > 0; i--) {
            root = index.put(root, prefix + HEX.charAt(i), null);
        }
        assertEquals(first, root);
    }

    @Test
    public void boundsWorstCaseLookupTo64NodesAndValidatesTerminalLeafSummaries() throws Exception {
        final var index = new CacheIndex(temporary);
        final var key = "0".repeat(64);
        var root = index.put(CacheIndex.EMPTY, key, A);
        for (var nibble = 0; nibble < 64; nibble++) {
            root = index.put(root, "0".repeat(nibble) + "1" + "0".repeat(63 - nibble), B);
        }
        var terminal = root;
        for (var depth = 0; depth < 63; depth++) {
            final var children = index.inspect(terminal).children();
            assertEquals(2, children.size());
            terminal = children.getFirst();
        }
        final var terminalChildren = index.inspect(terminal).children();
        assertEquals(2, terminalChildren.size());
        final var terminalBytes = Files.readAllBytes(nodePath(temporary, terminal));
        terminalBytes[terminalBytes.length - 1] = 'c';
        final var corrupt = install(terminalBytes); // digest-correct node, inconsistent embedded value
        assertThrows(IOException.class, () -> index.inspect(corrupt));
        Files.delete(nodePath(temporary, terminalChildren.getFirst()));
        // The terminal branch authenticates these summaries without a 65th file read.
        assertEquals(A, index.get(root, key));
        assertEquals(B, index.get(root, "0".repeat(63) + "1"));
        assertNull(index.get(root, "0".repeat(63) + "2"));
        assertEquals(root, index.put(root, key, A));
        final var updated = index.put(root, key, C);
        assertEquals(C, index.get(updated, key));
        assertNull(index.get(index.put(updated, key, null), key));
    }

    @Test
    public void deterministicMixedUpdatesMatchAMapAndPreserveSnapshots() throws Exception {
        final var index = new CacheIndex(temporary);
        final var random = new Random(100);
        final var expected = new HashMap<String, String>();
        final var snapshots = new HashMap<String, HashMap<String, String>>();
        final var keys = new ArrayList<String>();
        for (var i = 0; i < 80; i++) {
            keys.add(hash(("key-" + i).getBytes(StandardCharsets.US_ASCII)));
        }
        var root = CacheIndex.EMPTY;
        for (var i = 0; i < 400; i++) {
            final var key = keys.get(random.nextInt(keys.size()));
            final var value = random.nextInt(3) == 0 ? null : (random.nextBoolean() ? A : B);
            root = index.put(root, key, value);
            if (value == null) {
                expected.remove(key);
            } else {
                expected.put(key, value);
            }
            for (final var candidate : keys) {
                assertEquals(expected.get(candidate), index.get(root, candidate));
            }
            if (i % 50 == 0) {
                snapshots.put(root, new HashMap<>(expected));
            }
        }
        for (final var snapshot : snapshots.entrySet()) {
            for (final var key : keys) {
                assertEquals(snapshot.getValue().get(key), index.get(snapshot.getKey(), key));
            }
        }
        for (final var node : reachable(index, root)) {
            assertTrue(index.inspect(node).children().size() <= 16);
        }
    }

    @Test
    public void concurrentWritersInstallIdenticalNodesWithoutReplacingThem() throws Exception {
        final var index = new CacheIndex(temporary);
        final var tasks = new ArrayList<Callable<String>>();
        for (var i = 0; i < 16; i++) {
            tasks.add(() -> index.put(index.put(CacheIndex.EMPTY, A, B), C, A));
        }
        try (final var executor = Executors.newFixedThreadPool(8)) {
            String expected = null;
            for (final var result : executor.invokeAll(tasks)) {
                final var root = result.get();
                if (expected == null) {
                    expected = root;
                }
                assertEquals(expected, root);
                assertEquals(B, index.get(root, A));
                assertEquals(A, index.get(root, C));
            }
        }
        assertEquals(3, files(temporary).size());
    }

    @Test
    public void rejectsInvalidArgumentsBeforeResolvingPaths() throws Exception {
        final var index = new CacheIndex(temporary);
        final var invalid = new ArrayList<>(List.of(
                "",
                "a",
                "a".repeat(63),
                "a".repeat(65),
                "A".repeat(64),
                "g".repeat(64),
                "../" + "a".repeat(61),
                "a".repeat(63) + "\n"));
        invalid.add(null);
        for (final var digest : invalid) {
            assertThrows(IOException.class, () -> index.get(CacheIndex.EMPTY, digest));
            assertThrows(IOException.class, () -> index.put(CacheIndex.EMPTY, digest, A));
            if (digest != null) {
                assertThrows(IOException.class, () -> index.put(CacheIndex.EMPTY, A, digest));
            }
            if (!CacheIndex.EMPTY.equals(digest)) {
                assertThrows(IOException.class, () -> index.get(digest, A));
                assertThrows(IOException.class, () -> index.put(digest, A, B));
                assertThrows(IOException.class, () -> index.inspect(digest));
            }
        }
        assertTrue(files(temporary).isEmpty());
    }

    @Test
    public void rejectsMissingTamperedOversizedAndNonRegularNodes() throws Exception {
        final var index = new CacheIndex(temporary);
        assertThrows(IOException.class, () -> index.inspect(A));
        final var root = index.put(CacheIndex.EMPTY, A, B);
        final var path = nodePath(temporary, root);
        final var bytes = Files.readAllBytes(path);
        final var altered = bytes.clone();
        altered[altered.length - 1] = 'c';
        Files.write(path, altered);
        assertThrows(IOException.class, () -> index.inspect(root));
        assertThrows(IOException.class, () -> index.get(root, A));
        assertThrows(IOException.class, () -> index.put(root, A, B));
        // A deterministic reinstall must reject, not overwrite, a corrupt existing file.
        assertThrows(IOException.class, () -> index.put(CacheIndex.EMPTY, A, B));
        assertArrayEquals(altered, Files.readAllBytes(path));
        Files.write(path, Arrays.copyOf(bytes, bytes.length + 1));
        assertThrows(IOException.class, () -> index.inspect(root));
        Files.write(path, Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(IOException.class, () -> index.inspect(root));
        Files.write(path, bytes);
        assertEquals(B, index.get(root, A));
        Files.write(path, new byte[8193]);
        assertThrows(IOException.class, () -> index.inspect(root));
        Files.delete(path);
        Files.createDirectory(path);
        assertThrows(IOException.class, () -> index.inspect(root));
        assertThrows(IOException.class, () -> index.put(CacheIndex.EMPTY, A, B));
    }

    @Test
    public void rejectsDigestCorrectButMalformedEncodings() throws Exception {
        final var index = new CacheIndex(temporary);
        final var leaf = leafBytes(A, B);
        final var malformed = new ArrayList<byte[]>();
        malformed.add(new byte[0]);
        malformed.add(new byte[] {0, 0, 0, 0});
        malformed.add(Arrays.copyOf(leaf, leaf.length - 1));
        malformed.add(Arrays.copyOf(leaf, leaf.length + 1));
        final var unknown = leaf.clone();
        unknown[4] = 3;
        malformed.add(unknown);
        final var badLength = leaf.clone();
        badLength[5] = 63;
        malformed.add(badLength);
        final var badHex = leaf.clone();
        badHex[6] = 'G';
        malformed.add(badHex);
        final var nonAscii = leaf.clone();
        nonAscii[70] = (byte) 0xff;
        malformed.add(nonAscii);
        malformed.add(branchBytes("", new int[] {}, new String[] {}));
        malformed.add(branchBytes("", new int[] {0}, new String[] {A}));
        final var tooManyChildren = new String[17];
        Arrays.fill(tooManyChildren, A);
        malformed.add(branchBytes("", new int[17], tooManyChildren));
        malformed.add(branchBytes("", new int[] {0, 0}, new String[] {A, B}));
        malformed.add(branchBytes("", new int[] {0, 1}, new String[] {A, A}));
        malformed.add(branchBytes("", new int[] {1, 0}, new String[] {A, B}));
        malformed.add(branchBytes("", new int[] {0, 16}, new String[] {A, B}));
        malformed.add(branchBytes("0".repeat(64), new int[] {0, 1}, new String[] {A, B}));
        malformed.add(branchBytes("", new int[] {0, 1}, new String[] {"A".repeat(64), B}));
        for (final var bytes : malformed) {
            final var root = install(bytes);
            assertThrows(IOException.class, () -> index.inspect(root));
            assertThrows(IOException.class, () -> index.get(root, A));
            assertThrows(IOException.class, () -> index.put(root, A, B));
        }
    }

    @Test
    public void rejectsChildrenInTheWrongPositionAndReadsGcNodesIndependently() throws Exception {
        final var index = new CacheIndex(temporary);
        final var a = index.put(CacheIndex.EMPTY, A, B);
        final var b = index.put(CacheIndex.EMPTY, B, C);
        final var misplaced = install(branchBytes("", new int[] {0, 11}, new String[] {a, b}));
        assertEquals(List.of(a, b), index.inspect(misplaced).children());
        assertThrows(IOException.class, () -> index.get(misplaced, "0".repeat(64)));
        assertThrows(IOException.class, () -> index.put(misplaced, "0".repeat(64), C));
        final var sameDepth = install(branchBytes("", new int[] {0, 1}, new String[] {a, b}));
        final var parent = install(branchBytes("", new int[] {0, 1}, new String[] {sameDepth, b}));
        assertThrows(IOException.class, () -> index.get(parent, "0".repeat(64)));
        Files.delete(nodePath(temporary, a));
        assertEquals(List.of(a, b), index.inspect(misplaced).children());
        assertEquals(C, index.inspect(b).value());
        assertTrue(index.inspect(b).children().isEmpty());
    }

    @Test
    public void rejectsSymlinkedDirectoriesAncestorsShardsAndNodeFiles() throws Exception {
        final var outside = Files.createDirectory(temporary.resolve("outside"));
        final var alias = Files.createSymbolicLink(temporary.resolve("alias"), outside);
        assertThrows(IOException.class, () -> new CacheIndex(alias));
        assertThrows(IOException.class, () -> new CacheIndex(alias.resolve("nested")));
        final var directory = temporary.resolve("index");
        final var index = new CacheIndex(directory);
        final var root = index.put(CacheIndex.EMPTY, A, B);
        final var path = nodePath(directory, root);
        final var saved = Files.move(path, outside.resolve("saved"));
        Files.createSymbolicLink(path, saved);
        assertThrows(IOException.class, () -> index.inspect(root));
        assertThrows(IOException.class, () -> index.put(CacheIndex.EMPTY, A, B));
        Files.delete(path);
        final var shard = path.getParent();
        Files.delete(shard);
        Files.createSymbolicLink(shard, outside);
        assertThrows(IOException.class, () -> index.inspect(root));
        assertThrows(IOException.class, () -> index.put(CacheIndex.EMPTY, A, B));
        Files.delete(shard);
        Files.delete(directory);
        Files.createSymbolicLink(directory, outside);
        assertThrows(IOException.class, () -> index.inspect(root));
        assertThrows(IOException.class, () -> index.put(CacheIndex.EMPTY, A, B));
        assertArrayEquals(leafBytes(A, B), Files.readAllBytes(saved));
        assertFalse(Files.exists(outside.resolve("nested")));
        assertFalse(Files.exists(outside.resolve(root.substring(2))));
    }

    @Test
    public void revalidatesAncestorsAfterConstructionWithoutCachingTheirState() throws Exception {
        final var ancestor = Files.createDirectory(temporary.resolve("ancestor"));
        final var directory = ancestor.resolve("index");
        final var index = new CacheIndex(directory);
        final var root = index.put(CacheIndex.EMPTY, A, B);
        final var moved = Files.move(ancestor, temporary.resolve("moved"));
        Files.createSymbolicLink(ancestor, moved);
        assertThrows(IOException.class, () -> index.get(root, A));
        assertThrows(IOException.class, () -> index.inspect(root));
        assertThrows(IOException.class, () -> index.put(CacheIndex.EMPTY, C, B));
        Files.delete(ancestor);
        Files.move(moved, ancestor);
        assertEquals(B, index.get(root, A));
        Files.delete(nodePath(directory, root));
        Files.delete(nodePath(directory, root).getParent());
        assertThrows(IOException.class, () -> index.inspect(root));
        assertEquals(root, index.put(CacheIndex.EMPTY, A, B));
        assertEquals(B, index.get(root, A));
    }

    private Set<String> reachable(final CacheIndex index, final String root) throws IOException {
        final var result = new HashSet<String>();
        final var pending = new ArrayList<String>();
        pending.add(root);
        while (!pending.isEmpty()) {
            final var digest = pending.removeLast();
            if (!digest.isEmpty() && result.add(digest)) {
                pending.addAll(index.inspect(digest).children());
            }
        }
        return result;
    }

    private Set<Path> files(final Path directory) throws IOException {
        try (final var paths = Files.walk(directory)) {
            return new HashSet<>(paths.filter(Files::isRegularFile).toList());
        }
    }

    private Path nodePath(final Path directory, final String digest) {
        return directory.resolve(digest.substring(0, 2)).resolve(digest.substring(2));
    }

    private String install(final byte[] bytes) throws Exception {
        final var root = hash(bytes);
        final var path = nodePath(temporary, root);
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
        return root;
    }

    private String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private byte[] leafBytes(final String key, final String value) throws IOException {
        final var buffer = new ByteArrayOutputStream();
        try (final var output = new DataOutputStream(buffer)) {
            output.writeInt(0x43495831);
            output.writeByte(0);
            output.writeByte(key.length());
            output.writeBytes(key);
            output.writeBytes(value);
        }
        return buffer.toByteArray();
    }

    private byte[] branchBytes(final String prefix, final int[] slots, final String[] children) throws IOException {
        final var buffer = new ByteArrayOutputStream();
        try (final var output = new DataOutputStream(buffer)) {
            output.writeInt(0x43495831);
            output.writeByte(1);
            output.writeByte(prefix.length());
            output.writeBytes(prefix);
            output.writeByte(slots.length);
            for (var i = 0; i < slots.length; i++) {
                output.writeByte(slots[i]);
                output.writeBytes(children[i]);
            }
        }
        return buffer.toByteArray();
    }
}
