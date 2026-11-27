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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Persistent radix-16 Patricia index. Roots and child references are SHA-256 node digests;
 * values are opaque object digests, never references to index nodes.
 *
 * <p>Each node is stored at {@code directory/first-two-digest-characters/remaining-characters}.
 * The versioned binary encoding is canonical: CIX1, a type byte (0 leaf, 1 branch, 2 terminal branch),
 * a one-byte absolute prefix length, the ASCII prefix, then either a 64-byte ASCII value
 * or a child count and sorted (one-byte nibble, 64-byte ASCII digest) pairs.
 * Branches have 2..16 children and prefixes of length 0..63; leaves have a full key.
 * At prefix length 63, each child reference also carries its leaf value, verified against
 * the referenced leaf digest. This avoids a 65th node read on a maximally deep path.
 * Absolute prefixes permit removal to collapse a branch without loading its surviving child.
 * Only the search path is read or rewritten, with at most 64 branching decisions.
 *
 * <p>Installation uses an atomic, create-if-absent hard link from a completed temporary file.
 * The filesystem must support hard links. Published files are never overwritten.
 * The caller owns root publication and garbage collection, and must not collect nodes
 * while they are in use. As with other filesystem stores, the directory must not be
 * concurrently renamed or modified by an untrusted process.
 */
public final class CacheIndex {
    public static final String EMPTY = "";

    private static final int MAGIC = 0x43495831;
    private static final int MAX_BYTES = 8192;
    private static final String HEX = "0123456789abcdef";

    private final Path directory;

    public CacheIndex(final Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        ensureDirectory(this.directory, true);
    }

    public String get(final String root, final String key) throws IOException {
        validateRoot(root);
        validateDigest(key);
        var current = root;
        var expectedPrefix = EMPTY;
        while (!current.isEmpty()) {
            final var node = load(current);
            validatePosition(node, expectedPrefix);
            if (!key.startsWith(node.prefix())) {
                return null;
            }
            if (node.value() != null) {
                return node.value();
            }
            final var slot = HEX.indexOf(key.charAt(node.prefix().length()));
            if (node.prefix().length() == 63) {
                final var value = node.terminalValues().get(slot);
                return value.isEmpty() ? null : value;
            }
            current = node.children().get(slot);
            expectedPrefix = node.prefix() + HEX.charAt(slot);
        }
        return null;
    }

    /** Returns a new root, or the original root for an unchanged mapping. Null removes a key. */
    public String put(final String root, final String key, final String value) throws IOException {
        validateRoot(root);
        validateDigest(key);
        if (value != null) {
            validateDigest(value);
        }
        return update(root, key, value, EMPTY);
    }

    /** Loads exactly one node; EMPTY has neither children nor a value. */
    public NodeView inspect(final String root) throws IOException {
        validateRoot(root);
        if (root.isEmpty()) {
            return new NodeView(List.of(), null);
        }
        final var node = load(root);
        return new NodeView(
                node.children().stream().filter(child -> !child.isEmpty()).toList(), node.value());
    }

    public record NodeView(List<String> children, String value) {
        public NodeView {
            children = List.copyOf(children);
        }
    }

    private String update(final String root, final String key, final String value, final String expectedPrefix)
            throws IOException {
        if (root.isEmpty()) {
            return value == null ? EMPTY : store(new Node(key, value, List.of()));
        }
        final var node = load(root);
        validatePosition(node, expectedPrefix);
        final var common = commonPrefix(key, node.prefix());
        if (common < node.prefix().length()) {
            if (value == null) {
                return root;
            }
            final var children = emptyChildren();
            children.set(HEX.indexOf(node.prefix().charAt(common)), root);
            children.set(HEX.indexOf(key.charAt(common)), store(new Node(key, value, List.of())));
            final var terminalValues = common == 63 ? emptyChildren() : List.<String>of();
            if (common == 63) {
                terminalValues.set(HEX.indexOf(node.prefix().charAt(common)), node.value());
                terminalValues.set(HEX.indexOf(key.charAt(common)), value);
            }
            return store(new Node(key.substring(0, common), null, children, terminalValues));
        }
        if (node.value() != null) {
            if (value == null) {
                return EMPTY;
            }
            return value.equals(node.value()) ? root : store(new Node(key, value, List.of()));
        }
        final var slot = HEX.indexOf(key.charAt(node.prefix().length()));
        final var previous = node.children().get(slot);
        final String replacement;
        final var terminalValues = new ArrayList<>(node.terminalValues());
        if (node.prefix().length() == 63) {
            final var previousValue = terminalValues.get(slot);
            if ((value == null && previousValue.isEmpty()) || previousValue.equals(value)) {
                return root;
            }
            replacement = value == null ? EMPTY : store(new Node(key, value, List.of()));
            terminalValues.set(slot, value == null ? EMPTY : value);
        } else {
            replacement = update(previous, key, value, node.prefix() + HEX.charAt(slot));
        }
        if (previous.equals(replacement)) {
            return root;
        }
        final var children = new ArrayList<>(node.children());
        children.set(slot, replacement);
        var count = 0;
        var survivor = EMPTY;
        for (final var child : children) {
            if (!child.isEmpty()) {
                count++;
                survivor = child;
            }
        }
        return count == 1 ? survivor : store(new Node(node.prefix(), null, children, terminalValues));
    }

    private int commonPrefix(final String key, final String prefix) {
        var length = 0;
        while (length < prefix.length() && key.charAt(length) == prefix.charAt(length)) {
            length++;
        }
        return length;
    }

    private void validatePosition(final Node node, final String expectedPrefix) throws IOException {
        if (!node.prefix().startsWith(expectedPrefix)) {
            throw new IOException("Index node does not match its parent edge");
        }
    }

    private Node load(final String digest) throws IOException {
        final var bytes = read(digest);
        try (final var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Unknown index node format");
            }
            final var type = input.readUnsignedByte();
            final var length = input.readUnsignedByte();
            if (type > 2 || (type == 0 ? length != 64 : type == 1 ? length > 62 : length != 63)) {
                throw new IOException("Invalid index node type or prefix length");
            }
            final var prefix = readHex(input, length);
            final Node node;
            if (type == 0) {
                node = new Node(prefix, readHex(input, 64), List.of());
            } else {
                final var count = input.readUnsignedByte();
                if (count < 2 || count > 16) {
                    throw new IOException("Invalid index branch fanout");
                }
                final var children = emptyChildren();
                final var terminalValues = type == 2 ? emptyChildren() : List.<String>of();
                var previous = -1;
                for (var i = 0; i < count; i++) {
                    final var slot = input.readUnsignedByte();
                    if (slot <= previous || slot > 15) {
                        throw new IOException("Invalid or unordered index child slot");
                    }
                    final var child = readHex(input, 64);
                    if (children.contains(child)) {
                        throw new IOException("Duplicate index child digest");
                    }
                    children.set(slot, child);
                    if (type == 2) {
                        final var value = readHex(input, 64);
                        if (!hash(encode(new Node(prefix + HEX.charAt(slot), value, List.of())))
                                .equals(child)) {
                            throw new IOException("Invalid terminal leaf digest");
                        }
                        terminalValues.set(slot, value);
                    }
                    previous = slot;
                }
                node = new Node(prefix, null, children, terminalValues);
            }
            if (input.read() != -1) {
                throw new IOException("Trailing index node bytes");
            }
            return node;
        }
    }

    private String readHex(final DataInputStream input, final int length) throws IOException {
        final var bytes = new byte[length];
        input.readFully(bytes);
        for (final var value : bytes) {
            if ((value < '0' || value > '9') && (value < 'a' || value > 'f')) {
                throw new IOException("Invalid hexadecimal index field");
            }
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private byte[] encode(final Node node) throws IOException {
        final var buffer = new ByteArrayOutputStream();
        try (final var output = new DataOutputStream(buffer)) {
            output.writeInt(MAGIC);
            output.writeByte(node.value() != null ? 0 : node.prefix().length() == 63 ? 2 : 1);
            output.writeByte(node.prefix().length());
            output.writeBytes(node.prefix());
            if (node.value() != null) {
                output.writeBytes(node.value());
            } else {
                output.writeByte((int) node.children().stream()
                        .filter(child -> !child.isEmpty())
                        .count());
                for (var slot = 0; slot < 16; slot++) {
                    if (!node.children().get(slot).isEmpty()) {
                        output.writeByte(slot);
                        output.writeBytes(node.children().get(slot));
                        if (node.prefix().length() == 63) {
                            output.writeBytes(node.terminalValues().get(slot));
                        }
                    }
                }
            }
        }
        return buffer.toByteArray();
    }

    private String store(final Node node) throws IOException {
        final var bytes = encode(node);
        final var digest = hash(bytes);
        final var target = path(digest);
        ensureDirectory(target.getParent(), true);
        final var temporary = Files.createTempFile(target.getParent(), ".index-", ".tmp");
        try {
            try (final var output = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                final var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                output.force(true);
            }
            try {
                Files.createLink(target, temporary);
            } catch (final FileAlreadyExistsException exists) {
                if (!Arrays.equals(bytes, read(digest))) {
                    throw new IOException("Conflicting index node: " + digest, exists);
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
            forceDirectory(target.getParent());
        }
        return digest;
    }

    private byte[] read(final String digest) throws IOException {
        final var file = path(digest);
        ensureDirectory(file.getParent(), false);
        final var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() > MAX_BYTES) {
            throw new IOException("Invalid index node file: " + digest);
        }
        final byte[] bytes;
        try (final var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            // Nodes are immutable: reject size changes as well as digest corruption.
            // Size the allocation to the checked node, not MAX_BYTES for every small leaf.
            bytes = input.readNBytes((int) attributes.size());
            if (bytes.length != attributes.size() || input.read() != -1) {
                throw new IOException("Index node size changed while reading: " + digest);
            }
        }
        if (bytes.length > MAX_BYTES || !hash(bytes).equals(digest)) {
            throw new IOException("Invalid index node size or digest: " + digest);
        }
        return bytes;
    }

    private Path path(final String digest) {
        return directory.resolve(digest.substring(0, 2)).resolve(digest.substring(2));
    }

    private void ensureDirectory(final Path path, final boolean create) throws IOException {
        var current = path.getRoot();
        for (final var component : path) {
            current = current.resolve(component);
            try {
                if (!Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                        .isDirectory()) {
                    throw new IOException("Index path is not a real directory: " + current);
                }
                continue;
            } catch (final NoSuchFileException missing) {
                if (!create) {
                    throw missing;
                }
            }
            // Only attempt creation for a missing entry, not every existing ancestor.
            // Concurrent creators are accepted only after the same NOFOLLOW validation.
            try {
                Files.createDirectory(current);
                forceDirectory(current.getParent());
            } catch (final FileAlreadyExistsException exists) {
                // Validate the existing entry without following links below.
            }
            if (!Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .isDirectory()) {
                throw new IOException("Index path is not a real directory: " + current);
            }
        }
    }

    private static void forceDirectory(final Path path) throws IOException {
        try (final var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
    }

    private String hash(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void validateRoot(final String root) throws IOException {
        if (root == null || !root.isEmpty()) {
            validateDigest(root);
        }
    }

    private void validateDigest(final String digest) throws IOException {
        if (digest == null || digest.length() != 64) {
            throw new IOException("Expected a lowercase SHA-256 digest");
        }
        for (var i = 0; i < digest.length(); i++) {
            if (HEX.indexOf(digest.charAt(i)) < 0) {
                throw new IOException("Expected a lowercase SHA-256 digest");
            }
        }
    }

    private List<String> emptyChildren() {
        final var children = new ArrayList<String>(16);
        for (var i = 0; i < 16; i++) {
            children.add(EMPTY);
        }
        return children;
    }

    private record Node(String prefix, String value, List<String> children, List<String> terminalValues) {
        private Node(final String prefix, final String value, final List<String> children) {
            this(prefix, value, children, List.of());
        }
    }
}
