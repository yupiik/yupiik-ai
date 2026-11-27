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
package io.yupiik.ia.agent.slack.context;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Pinned per-attempt mailbox. The host creates the owned 0700 directory before launching the agent.
 * Only the two fixed document names are used; no upstream credentials or caller-selected paths.
 * Deadlines are epoch milliseconds. Controller replay/admission limits belong to the attempt owner.
 */
public final class SlackContextMailbox implements AutoCloseable {
    private static final int REQUEST_LIMIT = 16 * 1024;
    private static final int RESPONSE_LIMIT = 128 * 1024;
    private static final long MAX_WAIT_MILLIS = 300_000;
    private static final Pattern NONCE =
            Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}");
    private static final Pattern CHANNEL = Pattern.compile("[CG][A-Z0-9]{1,31}");
    // Shared across instances: tools may open a new pinned handle for each call. No unbounded lock registry.
    private static final List<ReentrantLock> CLIENT_CALLS =
            IntStream.range(0, 64).mapToObj(i -> new ReentrantLock(true)).toList();

    private final SecureDirectoryStream<Path> directory;
    private final UserPrincipal owner;
    private final JsonMapper json;
    private final String channel;
    private final ReentrantLock clientCall;

    public SlackContextMailbox(final Path root, final JsonMapper json, final String channel) throws IOException {
        if (!validChannel(channel)) {
            throw new IllegalArgumentException("Invalid Slack context channel");
        }
        this.channel = channel;
        this.json = Objects.requireNonNull(json);
        owner = root.getFileSystem()
                .getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        final var absolute = root.toAbsolutePath();
        clientCall = CLIENT_CALLS.get(Math.floorMod(absolute.hashCode(), CLIENT_CALLS.size()));
        if (!absolute.equals(absolute.normalize())) {
            throw new IOException("Invalid Slack context directory path");
        }
        var pinned = secure(Files.newDirectoryStream(absolute.getRoot()));
        try {
            for (final var component : absolute) {
                final var previous = pinned;
                pinned = previous.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS);
                previous.close();
                final var attributes = pinned.getFileAttributeView(PosixFileAttributeView.class)
                        .readAttributes();
                if (!attributes.owner().equals(owner)
                        && !attributes.owner().getName().equals("root")) {
                    throw new IOException("Untrusted Slack context directory owner");
                }
            }
            final var attributes =
                    pinned.getFileAttributeView(PosixFileAttributeView.class).readAttributes();
            if (!attributes.isDirectory()
                    || !attributes.owner().equals(owner)
                    || !attributes.permissions().equals(PosixFilePermissions.fromString("rwx------"))) {
                throw new IOException("Slack context mailbox must be an owned private directory");
            }
            directory = pinned;
        } catch (final IOException | RuntimeException failure) {
            try {
                pinned.close();
            } catch (final IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private static SecureDirectoryStream<Path> secure(final DirectoryStream<Path> stream) throws IOException {
        if (stream instanceof SecureDirectoryStream<Path> secure) {
            return secure;
        }
        stream.close();
        throw new IOException("Slack context requires secure directory handles");
    }

    public static boolean validChannel(final String channel) {
        return channel != null
                && channel.length() >= 2
                && channel.length() <= 32
                && CHANNEL.matcher(channel).matches();
    }

    private static boolean validId(final String id) {
        return id != null && id.length() == 36 && NONCE.matcher(id).matches();
    }

    @JsonModel
    public record Request(int version, String channel, String id, long expires, Map<String, Object> arguments) {
        public Request {
            if (version != 1 || !validChannel(channel) || !validId(id) || expires <= 0 || arguments == null) {
                throw new IllegalArgumentException("Invalid Slack context request");
            }
            arguments = Map.copyOf(arguments);
        }
    }

    @JsonModel
    public record Response(int version, String channel, String id, Map<String, Object> body) {
        public Response {
            if (version != 1 || !validChannel(channel) || !validId(id) || body == null) {
                throw new IllegalArgumentException("Invalid Slack context response");
            }
            body = Map.copyOf(body);
        }
    }

    private void validateRequest(final Request request) throws IOException {
        final var now = System.currentTimeMillis();
        if (request == null
                || !channel.equals(request.channel())
                || request.expires() <= now
                || request.expires() > now + MAX_WAIT_MILLIS) {
            throw new IOException("Expired or unbound Slack context request");
        }
    }

    /** Returns null if absent; malformed, expired, oversized or cross-channel documents fail closed. */
    public Request pending() throws IOException {
        final var content = read("request.json", REQUEST_LIMIT);
        if (content == null) {
            return null;
        }
        try {
            final var request = json.fromString(Request.class, content);
            validateRequest(request);
            return request;
        } catch (final RuntimeException invalid) {
            throw new IOException("Invalid Slack context request");
        }
    }

    /** Publishes a response only while this exact request is still current and live. */
    public void respond(final Request request, final Map<String, Object> body) throws IOException {
        validateRequest(request);
        // Read the response before checking the request: client cleanup removes the request
        // first. Never replace a published response while the client may be consuming it.
        final var published = read("response.json", RESPONSE_LIMIT);
        if (!request.equals(pending())) {
            throw new IOException("Stale Slack context request");
        }
        if (published != null) {
            final var response = json.fromString(Response.class, published);
            if (response != null
                    && channel.equals(response.channel())
                    && request.id().equals(response.id())) {
                return;
            }
        }
        write("response.json", json.toString(new Response(1, channel, request.id(), body)), RESPONSE_LIMIT);
    }

    /**
     * Serializes all in-process calls (including lock wait) within the supplied deadline.
     * A mismatched response is rejected, never returned as the result of a later request.
     */
    public Map<String, Object> request(
            final Map<String, Object> arguments, final long deadlineEpochMillis, final BooleanSupplier cancelled)
            throws IOException {
        Objects.requireNonNull(cancelled);
        final var request = new Request(1, channel, UUID.randomUUID().toString(), deadlineEpochMillis, arguments);
        validateRequest(request);
        final long deadline =
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineEpochMillis - System.currentTimeMillis());
        var locked = false;
        var published = false;
        try {
            while (!locked) {
                checkWaiting(deadline, cancelled);
                locked = clientCall.tryLock(25, TimeUnit.MILLISECONDS);
            }
            checkWaiting(deadline, cancelled);
            clear();
            write("request.json", json.toString(request), REQUEST_LIMIT);
            published = true;
            while (true) {
                checkWaiting(deadline, cancelled);
                final var content = read("response.json", RESPONSE_LIMIT);
                if (content != null) {
                    final var response = json.fromString(Response.class, content);
                    if (response == null
                            || !channel.equals(response.channel())
                            || !request.id().equals(response.id())) {
                        throw new IOException("Stale Slack context response");
                    }
                    validateRequest(request);
                    return response.body();
                }
                Thread.sleep(25);
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Slack context request cancelled");
        } catch (final RuntimeException invalid) {
            throw new IOException("Invalid Slack context response");
        } finally {
            if (locked) {
                try {
                    if (published) {
                        clear();
                    }
                } finally {
                    clientCall.unlock();
                }
            }
        }
    }

    private static void checkWaiting(final long deadline, final BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new IOException("Slack context request cancelled");
        }
        if (System.nanoTime() - deadline >= 0) {
            throw new IOException("Slack context request timed out");
        }
    }

    /** Attempt owner only: do not clear concurrently with an active client call. Symlinks are unlinked, not followed. */
    public void clear() throws IOException {
        for (final var name : List.of("request.json", "response.json")) {
            try {
                directory.deleteFile(Path.of(name));
            } catch (final NoSuchFileException ignored) {
                // Absent.
            }
        }
    }

    private PosixFileAttributes attributes(final Path name, final int limit) throws IOException {
        final var attributes = directory
                .getFileAttributeView(name, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                .readAttributes();
        if (!attributes.isRegularFile()
                || !attributes.owner().equals(owner)
                || !attributes.permissions().equals(PosixFilePermissions.fromString("rw-------"))
                || attributes.size() > limit
                || attributes.size() == 0
                || attributes.fileKey() == null) {
            throw new IOException("Invalid Slack context document file");
        }
        return attributes;
    }

    private String read(final String name, final int limit) throws IOException {
        final var path = Path.of(name);
        try {
            final var before = attributes(path, limit);
            // O_RDWR avoids waiting for a FIFO peer after a hostile replacement. NOFOLLOW rejects links.
            try (final var input = directory.newByteChannel(
                    path, Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                final var size = input.size();
                if (size <= 0
                        || size > limit
                        || size != before.size()
                        || !before.fileKey().equals(attributes(path, limit).fileKey())) {
                    throw new IOException("Changed Slack context document");
                }
                final var buffer = ByteBuffer.allocate((int) size);
                while (buffer.hasRemaining()) {
                    if (input.read(buffer) <= 0) {
                        throw new IOException("Truncated Slack context document");
                    }
                }
                if (input.size() != size
                        || !before.fileKey().equals(attributes(path, limit).fileKey())) {
                    throw new IOException("Changed Slack context document");
                }
                buffer.flip();
                return StandardCharsets.UTF_8.newDecoder().decode(buffer).toString();
            }
        } catch (final NoSuchFileException absent) {
            return null;
        }
    }

    private void write(final String name, final String content, final int limit) throws IOException {
        final var bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limit) {
            throw new IOException("Oversized Slack context document");
        }
        final var temporary = Path.of("message-" + UUID.randomUUID() + ".tmp");
        try {
            try (final var output = directory.newByteChannel(
                    temporary,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                final var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                if (output instanceof FileChannel file) {
                    file.force(true);
                }
            }
            directory.move(temporary, directory, Path.of(name));
        } finally {
            try {
                directory.deleteFile(temporary);
            } catch (final NoSuchFileException ignored) {
                // Atomically promoted.
            }
        }
    }

    @Override
    public void close() throws IOException {
        directory.close();
    }
}
