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
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Bounded, versioned filesystem manifests; no object ownership or publication policy.
 * Sources must be quiescent and destination parents private for the entire operation.
 * POSIX permissions are required; deadlines are cooperative, not cancellation of blocking filesystem calls.
 * The coordinator owns staging cleanup on failure and atomic publication on success.
 *
 * <p>Limits uses maxFiles(), maxFileBytes(), maxManifestBytes(), operationTimeoutSeconds().
 * maxFiles counts all records (including the root directory), not just regular files.
 * SnapshotResult.files counts regular files; bytes counts their logical sizes, including duplicates.
 * Only rwx bits are transported; symlink permissions are deliberately ignored.
 * Metadata memory is bounded by the manifest and record limits, never by blob sizes.
 */
public final class CacheSnapshot {
    // Format: magic, version, records, END, record count, regular file count, logical bytes, EOF.
    // Record: kind byte, UTF-8 path (int length + bytes), rwx int, then
    // FILE: size long + 32 raw digest bytes; LINK: UTF-8 target; DIR: no payload.
    private static final int MAGIC = 0x59434143;
    private static final int VERSION = 1;
    private static final int END = 0;
    private static final int DIRECTORY = 1;
    private static final int FILE = 2;
    private static final int LINK = 3;
    private static final int MAX_PATH_BYTES = 4096;
    private static final int MAX_DEPTH = 128;
    private static final int MAX_LINK_DEPTH = 40;
    private static final Set<OpenOption> READ = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    private static final PosixFilePermission[] PERMISSIONS = {
        PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_READ,
        PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_READ,
        PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ
    };

    private final DependencyCacheConfiguration.Limits limits;
    private final long deadline;

    public CacheSnapshot(final DependencyCacheConfiguration.Limits limits, final long deadline) {
        this.deadline = deadline;
        this.limits = Objects.requireNonNull(limits, "limits");
        if (limits.maxFiles() < 1
                || limits.maxFileBytes() < 1
                || limits.maxManifestBytes() < 1
                || limits.operationTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException("Invalid snapshot limits");
        }
    }

    /** Installer must deduplicate and verify the size and SHA-256 while copying an absent blob. */
    @FunctionalInterface
    public interface BlobInstaller {
        void install(String digest, Path source, long size) throws IOException;
    }

    /** Reader returns a pinned, immutable regular blob until restore finishes. */
    @FunctionalInterface
    public interface BlobReader {
        Path acquire(String digest, long expectedSize) throws IOException;
    }

    public static final class CorruptBlobException extends IOException {
        private final String digest;

        public CorruptBlobException(final String digest) {
            super("Corrupt cache blob: " + digest);
            this.digest = digest;
        }

        public String digest() {
            return digest;
        }
    }

    public static final class OperationTimeoutException extends IOException {
        public OperationTimeoutException(final String message) {
            super(message);
        }
    }

    public record SnapshotResult(String digest, long files, long bytes) {}

    public SnapshotResult capture(
            final Path source,
            final DependencyCacheConfiguration.Entry entry,
            final Path manifest,
            final BlobInstaller installer)
            throws IOException {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(installer, "installer");
        final var state = new State();
        final var root = checked(source);
        if (!attributes(root).isDirectory()) {
            throw new IOException("Snapshot source must be a real directory");
        }
        final var output = checked(manifest);
        if (output.startsWith(root)) {
            throw new IOException("Manifest must be outside source");
        }
        final var digest = sha256();
        boolean created = false;
        try {
            final var stream = Files.newOutputStream(
                    output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            created = true;
            try (final var out = new DataOutputStream(
                    new BufferedOutputStream(new DigestOutputStream(new BoundedOutput(stream, state), digest)))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                captureNode(root, "", entry, out, installer, state);
                validateLinks(state.nodes, state);
                out.writeByte(END);
                out.writeLong(state.nodes.size());
                out.writeLong(state.files);
                out.writeLong(state.bytes);
            }
            state.check();
            return new SnapshotResult(HexFormat.of().formatHex(digest.digest()), state.files, state.bytes);
        } catch (final IOException | RuntimeException failure) {
            if (created) {
                try {
                    Files.deleteIfExists(output);
                } catch (final IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    /** Validates the entire manifest before creating a fresh, private destination. */
    public void restore(
            final Path manifest,
            final Path destination,
            final DependencyCacheConfiguration.Entry entry,
            final BlobReader reader)
            throws IOException {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(reader, "reader");
        final var state = new State();
        final var input = checked(manifest);
        if (!attributes(input).isRegularFile() || Files.size(input) > limits.maxManifestBytes()) {
            throw new IOException("Invalid manifest file");
        }
        try (final var in = new DataInputStream(new BufferedInputStream(new BoundedInput(openRead(input), state)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                throw new IOException("Unsupported snapshot manifest");
            }
            while (true) {
                state.check();
                final int kind = in.readUnsignedByte();
                if (kind == END) {
                    break;
                }
                if (kind != DIRECTORY && kind != FILE && kind != LINK) {
                    throw new IOException("Unknown snapshot record");
                }
                final var path = text(in);
                validatePath(path, state.nodes.isEmpty());
                final int mode = in.readInt();
                if (mode < 0 || mode > 0777) {
                    throw new IOException("Invalid rwx mode");
                }
                long size = 0;
                String hash = null;
                String target = null;
                if (kind == FILE) {
                    size = in.readLong();
                    checkSize(size);
                    final var raw = new byte[32];
                    in.readFully(raw);
                    hash = HexFormat.of().formatHex(raw);
                } else if (kind == LINK) {
                    target = text(in);
                    validateTarget(target);
                    if (mode != 0) {
                        throw new IOException("Symlink mode must be zero");
                    }
                }
                final var node = new Node(path, kind, mode, size, hash, target);
                if (excluded(entry, path, kind)) {
                    throw new IOException("Excluded cache entry in manifest: " + path);
                }
                add(node, state);
            }
            if (in.readLong() != state.nodes.size()
                    || in.readLong() != state.files
                    || in.readLong() != state.bytes
                    || in.read() != -1) {
                throw new IOException("Invalid snapshot footer");
            }
        }
        if (state.nodes.isEmpty()) {
            throw new IOException("Missing root record");
        }
        validateLinks(state.nodes, state);
        final var root = checked(destination);
        // CREATE_DIRECTORY is exclusive: never merge with an existing tree, even an empty one.
        createDirectory(root);
        for (final var node : state.nodes.values()) {
            state.check();
            if (node.path.isEmpty()) {
                continue;
            }
            final var path = checked(root.resolve(node.path));
            if (node.kind == DIRECTORY) {
                createDirectory(path);
            } else if (node.kind == FILE) {
                final var blob = checked(reader.acquire(node.digest, node.size));
                if (!attributes(blob).isRegularFile()) {
                    throw new IOException("Blob must be regular");
                }
                final var hash = sha256();
                try (final var in = openRead(blob);
                        final var out = Files.newOutputStream(
                                path,
                                StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS)) {
                    transfer(in, out, node.size, hash, state);
                }
                if (!HexFormat.of().formatHex(hash.digest()).equals(node.digest)) {
                    throw new CorruptBlobException(node.digest);
                }
                mode(path, node.mode);
            }
        }
        // No traversal through restored links is possible during file/directory creation.
        for (final var node : state.nodes.values()) {
            state.check();
            if (node.kind == LINK) {
                Files.createSymbolicLink(checked(root.resolve(node.path)), Path.of(node.target));
            }
        }
        // Children were required to follow parents in the manifest; reverse order is deepest first.
        final var nodes = new ArrayList<>(state.nodes.values());
        for (int i = nodes.size() - 1; i >= 0; i--) {
            state.check();
            final var node = nodes.get(i);
            if (node.kind == DIRECTORY) {
                mode(checked(root.resolve(node.path)), node.mode);
            }
        }
    }

    private void captureNode(
            final Path source,
            final String relative,
            final DependencyCacheConfiguration.Entry entry,
            final DataOutputStream out,
            final BlobInstaller installer,
            final State state)
            throws IOException {
        state.check();
        validatePath(relative, state.nodes.isEmpty());
        checked(source);
        final var attrs = attributes(source);
        final int kind;
        if (attrs.isSymbolicLink()) {
            kind = LINK;
        } else if (attrs.isDirectory()) {
            kind = DIRECTORY;
        } else if (attrs.isRegularFile()) {
            kind = FILE;
        } else {
            throw new IOException("Unsupported filesystem object: " + relative);
        }
        if (excluded(entry, relative, kind)) {
            return;
        }
        if (state.nodes.size() >= limits.maxFiles()) {
            throw new IOException("Too many manifest records");
        }
        final int rwx = kind == LINK ? 0 : mode(source);
        long size = 0;
        String hash = null;
        String target = null;
        if (kind == FILE) {
            size = attrs.size();
            checkSize(size);
            final var digest = sha256();
            try (final var in = openRead(source)) {
                transfer(in, OutputStream.nullOutputStream(), size, digest, state);
            }
            hash = HexFormat.of().formatHex(digest.digest());
        } else if (kind == LINK) {
            final var link = Files.readSymbolicLink(source);
            target = link.toString();
            if (!Path.of(target).equals(link)) {
                throw new IOException("Symlink target is not losslessly representable as UTF-8");
            }
            validateTarget(target);
        }
        final var node = new Node(relative, kind, rwx, size, hash, target);
        add(node, state);
        out.writeByte(kind);
        text(out, relative);
        out.writeInt(rwx);
        if (kind == FILE) {
            out.writeLong(size);
            out.write(HexFormat.of().parseHex(hash));
            out.flush(); // Enforce manifest limit before asking the coordinator to install a blob.
            installer.install(hash, source, size);
            state.check();
        } else if (kind == LINK) {
            text(out, target);
        } else {
            final var children = new ArrayList<String>();
            try (final var directory = Files.newDirectoryStream(source)) {
                for (final var child : directory) {
                    state.check();
                    // Bound sorting memory even for excluded entries or a single huge directory.
                    final var name = child.getFileName().toString();
                    if (!Path.of(name).equals(child.getFileName())) {
                        throw new IOException("Filename is not losslessly representable as UTF-8");
                    }
                    validatePath(name, false);
                    final long cost = name.getBytes(StandardCharsets.UTF_8).length + 16L;
                    if (state.pendingEntries >= limits.maxFiles() - state.nodes.size()
                            || cost > limits.maxManifestBytes() - state.metadata - state.pendingMetadata) {
                        throw new IOException("Too many directory entries");
                    }
                    state.pendingEntries++;
                    state.pendingMetadata += cost;
                    children.add(name);
                }
            }
            children.sort(Comparator.naturalOrder());
            for (int i = 0; i < children.size(); i++) {
                final var name = children.set(i, null);
                state.pendingEntries--;
                state.pendingMetadata -= name.getBytes(StandardCharsets.UTF_8).length + 16L;
                captureNode(
                        source.resolve(name),
                        relative.isEmpty() ? name : relative + '/' + name,
                        entry,
                        out,
                        installer,
                        state);
            }
        }
    }

    private void add(final Node node, final State state) throws IOException {
        state.check();
        if (state.nodes.size() >= limits.maxFiles() || state.nodes.containsKey(node.path)) {
            throw new IOException("Too many or duplicate manifest records");
        }
        if (state.nodes.isEmpty()) {
            if (!node.path.isEmpty() || node.kind != DIRECTORY) {
                throw new IOException("First record must be the root directory");
            }
        } else {
            final var parent = state.nodes.get(parent(node.path));
            if (node.path.isEmpty() || parent == null || parent.kind != DIRECTORY) {
                throw new IOException("Missing directory parent or symlink ancestor");
            }
        }
        // Also bounds unflushed capture metadata (and all restore metadata) independent of buffering.
        final long cost = 16L
                + node.path.getBytes(StandardCharsets.UTF_8).length
                + (node.target == null ? 0 : node.target.getBytes(StandardCharsets.UTF_8).length);
        if (cost > limits.maxManifestBytes() - state.metadata - state.pendingMetadata) {
            throw new IOException("Manifest metadata limit exceeded");
        }
        state.metadata += cost;
        state.nodes.put(node.path, node);
        if (node.kind == FILE) {
            if (node.size > Long.MAX_VALUE - state.bytes) {
                throw new IOException("Snapshot size overflow");
            }
            state.files++;
            state.bytes += node.size;
        }
    }

    private void validateLinks(final Map<String, Node> nodes, final State state) throws IOException {
        for (final var node : nodes.values()) {
            state.check();
            if (node.kind == LINK) {
                resolveLink(node, nodes, state);
            }
        }
    }

    private void resolveLink(final Node link, final Map<String, Node> nodes, final State state) throws IOException {
        var pending = components(parent(link.path));
        pending.addAll(List.of(link.target.split("/", -1)));
        final var resolved = new ArrayList<String>();
        int expansions = 1;
        for (int index = 0; index < pending.size(); index++) {
            state.check();
            final var part = pending.get(index);
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (resolved.isEmpty()) {
                    throw new IOException("Symlink escapes snapshot");
                }
                resolved.removeLast();
                continue;
            }
            resolved.add(part);
            final var path = String.join("/", resolved);
            final var node = nodes.get(path);
            if (node == null) {
                throw new IOException("Symlink target missing from snapshot: " + path);
            }
            if (node.kind == LINK) {
                // Bound expansions rather than banning repeated nodes: a/../a can legitimately
                // traverse the same symlink twice without creating a cycle.
                if (++expansions > MAX_LINK_DEPTH) {
                    throw new IOException("Symlink cycle or excessive chain depth");
                }
                resolved.removeLast();
                final var rest = new ArrayList<>(pending.subList(index + 1, pending.size()));
                pending = new ArrayList<>(List.of(node.target.split("/", -1)));
                pending.addAll(rest);
                index = -1;
            } else if (index + 1 < pending.size() && node.kind != DIRECTORY) {
                throw new IOException("Symlink traverses non-directory");
            }
        }
    }

    private static ArrayList<String> components(final String path) {
        return path.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(path.split("/")));
    }

    private static String parent(final String path) {
        final int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static boolean excluded(final DependencyCacheConfiguration.Entry entry, final String path, final int kind) {
        final var name = path.substring(path.lastIndexOf('/') + 1);
        return (!path.isEmpty()
                        && kind == DIRECTORY
                        && entry.excludeDirectoryNameSuffixes().stream().anyMatch(name::endsWith))
                || ("gradle".equals(entry.id())
                        && kind != DIRECTORY
                        && (name.equals("modules-2.lock") || name.equals("gc.properties")));
    }

    private static void validatePath(final String path, final boolean root) throws IOException {
        if (path.isEmpty() && root) {
            return;
        }
        validateText(path);
        final var parts = path.split("/", -1);
        if (parts.length > MAX_DEPTH) {
            throw new IOException("Snapshot path too deep");
        }
        for (final var part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.indexOf(':') >= 0) {
                throw new IOException("Unsafe relative snapshot path");
            }
        }
    }

    private static void validateTarget(final String target) throws IOException {
        validateText(target);
        if (target.startsWith("/") || target.indexOf(':') >= 0 || target.split("/", -1).length > MAX_DEPTH) {
            throw new IOException("Unsafe symlink target");
        }
    }

    private static void validateText(final String text) throws IOException {
        if (text.isEmpty()
                || text.indexOf('\0') >= 0
                || text.indexOf('\\') >= 0
                || text.getBytes(StandardCharsets.UTF_8).length > MAX_PATH_BYTES) {
            throw new IOException("Invalid snapshot path or target");
        }
    }

    /** Reject all existing symlink ancestors, but allow a symlink leaf for lstat/readlink. */
    private static Path checked(final Path path) throws IOException {
        final var absolute = path.toAbsolutePath();
        if (!absolute.equals(absolute.normalize())) {
            throw new IOException("Snapshot filesystem path must be normalized");
        }
        var parent = absolute.getParent();
        while (parent != null) {
            if (!attributes(parent).isDirectory()) {
                throw new IOException("Non-directory or symlink ancestor: " + parent);
            }
            parent = parent.getParent();
        }
        return absolute;
    }

    private static BasicFileAttributes attributes(final Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static InputStream openRead(final Path path) throws IOException {
        checked(path);
        // Pin the parent and use a relative NOFOLLOW open on providers supporting secure streams.
        try (final var directory = Files.newDirectoryStream(path.getParent())) {
            if (directory instanceof SecureDirectoryStream<Path> secure) {
                return Channels.newInputStream(secure.newByteChannel(path.getFileName(), READ));
            }
            return Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        }
    }

    private static void createDirectory(final Path path) throws IOException {
        Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    private static int mode(final Path path) throws IOException {
        final var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        int result = 0;
        for (int i = 0; i < PERMISSIONS.length; i++) {
            if (permissions.contains(PERMISSIONS[i])) {
                result |= 1 << i;
            }
        }
        return result;
    }

    private static void mode(final Path path, final int mode) throws IOException {
        final var permissions = EnumSet.noneOf(PosixFilePermission.class);
        for (int i = 0; i < PERMISSIONS.length; i++) {
            if ((mode & (1 << i)) != 0) {
                permissions.add(PERMISSIONS[i]);
            }
        }
        Files.setPosixFilePermissions(path, permissions);
    }

    private void checkSize(final long size) throws IOException {
        if (size < 0 || size > limits.maxFileBytes()) {
            throw new IOException("Blob size limit exceeded");
        }
    }

    private static void transfer(
            final InputStream in,
            final OutputStream out,
            final long size,
            final MessageDigest digest,
            final State state)
            throws IOException {
        final var buffer = new byte[64 * 1024];
        long remaining = size;
        while (remaining > 0) {
            state.check();
            final int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("Truncated blob or changed source");
            }
            if (read == 0) {
                continue;
            }
            remaining -= read;
            digest.update(buffer, 0, read);
            out.write(buffer, 0, read);
        }
        state.check();
        if (in.read() != -1) {
            throw new IOException("Oversized blob or changed source");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void text(final DataOutputStream out, final String value) throws IOException {
        final var bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_PATH_BYTES) {
            throw new IOException("Snapshot text too large");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String text(final DataInputStream in) throws IOException {
        final int length = in.readInt();
        if (length < 0 || length > MAX_PATH_BYTES) {
            throw new IOException("Snapshot text too large");
        }
        final var bytes = new byte[length];
        in.readFully(bytes);
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (final CharacterCodingException invalid) {
            throw new IOException("Invalid UTF-8 in manifest", invalid);
        }
    }

    private record Node(String path, int kind, int mode, long size, String digest, String target) {}

    private final class State {
        private final long started = System.nanoTime();
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private long files;
        private long bytes;
        private long metadata;
        private long pendingMetadata;
        private long pendingEntries;

        private void check() throws IOException {
            if (Thread.currentThread().isInterrupted()
                    || System.nanoTime() >= deadline
                    || System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(limits.operationTimeoutSeconds())) {
                throw new OperationTimeoutException("Snapshot operation interrupted or deadline exceeded");
            }
        }
    }

    private final class BoundedOutput extends FilterOutputStream {
        private final State state;
        private long written;

        private BoundedOutput(final OutputStream out, final State state) {
            super(out);
            this.state = state;
        }

        @Override
        public void write(final int value) throws IOException {
            reserve(1);
            out.write(value);
        }

        @Override
        public void write(final byte[] bytes, final int offset, final int length) throws IOException {
            reserve(length);
            out.write(bytes, offset, length);
        }

        private void reserve(final int length) throws IOException {
            state.check();
            if (length > limits.maxManifestBytes() - written) {
                throw new IOException("Manifest byte limit exceeded");
            }
            written += length;
        }
    }

    private final class BoundedInput extends FilterInputStream {
        private final State state;
        private long read;

        private BoundedInput(final InputStream in, final State state) {
            super(in);
            this.state = state;
        }

        @Override
        public int read() throws IOException {
            state.check();
            final int value = in.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(final byte[] bytes, final int offset, final int length) throws IOException {
            state.check();
            final int count =
                    in.read(bytes, offset, (int) Math.min(length, Math.max(1, limits.maxManifestBytes() - read)));
            if (count > 0) {
                count(count);
            }
            return count;
        }

        private void count(final int count) throws IOException {
            if (count > limits.maxManifestBytes() - read) {
                throw new IOException("Manifest byte limit exceeded");
            }
            read += count;
        }
    }
}
