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
package io.yupiik.ia.agent.bot.feedback;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.question.Question;
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
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** A pinned, bounded per-attempt channel, not arbitrary filesystem or tool RPC. */
public final class FeedbackMailbox implements AutoCloseable {
    private static final Pattern NONCE =
            Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}");

    private static final int LIMIT = 65536;
    private static final int VERSION = 1;
    private final SecureDirectoryStream<Path> directory;
    private final UserPrincipal owner;
    private final JsonMapper json;

    public FeedbackMailbox(final Path root, final JsonMapper json) throws IOException {
        this.json = json;
        owner = root.getFileSystem()
                .getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        // Walk every component relative to an already pinned handle; never resolve a symlink ancestor.
        final var absolute = root.toAbsolutePath();
        if (!absolute.equals(absolute.normalize())) {
            throw new IOException("Invalid feedback directory path");
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
                    throw new IOException("Untrusted feedback directory owner");
                }
            }
            final var attributes =
                    pinned.getFileAttributeView(PosixFileAttributeView.class).readAttributes();
            if (!attributes.isDirectory()
                    || !attributes.owner().equals(owner)
                    || !attributes.permissions().equals(PosixFilePermissions.fromString("rwx------"))) {
                throw new IOException("Feedback mailbox must be an owned private directory");
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
        throw new IOException("Feedback mailbox requires secure directory handles");
    }

    @JsonModel
    public record Item(String id, String prompt, List<String> options, boolean multiple) {
        public Item {
            options = options == null ? List.of() : List.copyOf(options);
            if (id == null
                    || id.isBlank()
                    || id.length() > 128
                    || prompt == null
                    || prompt.isBlank()
                    || prompt.length() > 2000
                    || options.size() > 20
                    || options.stream().distinct().count() != options.size()
                    || options.stream().anyMatch(option -> option.isBlank() || option.length() > 75)
                    || (multiple && options.isEmpty())) {
                throw new IllegalArgumentException("Invalid feedback question");
            }
        }
    }

    @JsonModel
    public record Request(int version, String nonce, long expires, List<Item> questions) {
        public Request {
            if (version != VERSION
                    || !validNonce(nonce)
                    || expires <= 0
                    || questions == null
                    || questions.isEmpty()
                    || questions.size() > 8
                    || questions.stream().map(Item::id).distinct().count() != questions.size()) {
                throw new IllegalArgumentException("Invalid feedback request");
            }
            questions = List.copyOf(questions);
        }
    }

    @JsonModel
    public record Response(int version, String nonce, boolean cancelled, Map<String, Object> answers) {
        public Response {
            if (version != VERSION || !validNonce(nonce) || answers == null || (cancelled && !answers.isEmpty())) {
                throw new IllegalArgumentException("Invalid feedback response");
            }
            answers = Map.copyOf(answers);
        }
    }

    private static boolean validNonce(final String nonce) {
        return nonce != null && NONCE.matcher(nonce).matches();
    }

    private static void checkExpiry(final Request request) throws IOException {
        final var remaining = request.expires() - System.currentTimeMillis();
        if (remaining <= 0 || remaining > 3600000L) {
            throw new IOException("Expired or invalid feedback deadline");
        }
    }

    public Request request(final List<Question> questions, final long expires) throws IOException {
        final var request = new Request(
                VERSION,
                UUID.randomUUID().toString(),
                expires,
                questions.stream()
                        .map(q -> new Item(q.id(), q.prompt(), q.options(), q.multiple()))
                        .toList());
        checkExpiry(request);
        write("request.json", json.toString(request));
        return request;
    }

    public Request pending() throws IOException {
        final var content = read("request.json");
        if (content == null) {
            return null;
        }
        final var request = json.fromString(Request.class, content);
        checkExpiry(request);
        return request;
    }

    public Response response(final String nonce) throws IOException {
        final var content = read("response.json");
        if (content == null) {
            return null;
        }
        final var request = pending();
        final var response = json.fromString(Response.class, content);
        if (request == null
                || !validNonce(nonce)
                || !nonce.equals(response.nonce())
                || !nonce.equals(request.nonce())) {
            throw new IOException("Stale feedback response");
        }
        if (!response.cancelled()) {
            validateAnswers(request, response.answers());
        }
        return response;
    }

    public void answer(final Request request, final Map<String, Object> answers, final boolean cancelled)
            throws IOException {
        checkExpiry(request);
        if (!request.equals(pending())) {
            throw new IOException("Stale feedback request");
        }
        if (!cancelled) {
            validateAnswers(request, answers);
        } else if (answers == null || !answers.isEmpty()) {
            throw new IllegalArgumentException("Cancelled feedback must not contain answers");
        }
        write("response.json", json.toString(new Response(VERSION, request.nonce(), cancelled, answers)));
    }

    public static void validateAnswers(final Request request, final Map<String, Object> answers) {
        if (answers == null || answers.size() != request.questions().size()) {
            throw new IllegalArgumentException("Feedback answers must match all questions");
        }
        for (final var item : request.questions()) {
            final var answer = answers.get(item.id());
            if (item.options().isEmpty()) {
                if (!(answer instanceof String text) || text.isBlank() || text.length() > 3000) {
                    throw new IllegalArgumentException("Invalid free-text feedback");
                }
            } else if (item.multiple()) {
                if (!(answer instanceof List<?> choices)
                        || choices.isEmpty()
                        || choices.size() > item.options().size()
                        || choices.stream().distinct().count() != choices.size()
                        || !item.options().containsAll(choices)) {
                    throw new IllegalArgumentException("Invalid multiple-choice feedback");
                }
            } else if (!(answer instanceof String choice) || !item.options().contains(choice)) {
                throw new IllegalArgumentException("Invalid choice feedback");
            }
        }
    }

    public void clear() throws IOException {
        for (final var name : List.of("request.json", "response.json")) {
            try {
                directory.deleteFile(Path.of(name));
            } catch (final NoSuchFileException ignored) {
                // Already cleared or no answer.
            }
        }
    }

    private PosixFileAttributes attributes(final Path name) throws IOException {
        final var attributes = directory
                .getFileAttributeView(name, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                .readAttributes();
        if (!attributes.isRegularFile()
                || !attributes.owner().equals(owner)
                || !attributes.permissions().equals(PosixFilePermissions.fromString("rw-------"))
                || attributes.size() > LIMIT
                || attributes.size() == 0
                || attributes.fileKey() == null) {
            throw new IOException("Invalid feedback document file");
        }
        return attributes;
    }

    private String read(final String name) throws IOException {
        final var path = Path.of(name);
        try {
            final var before = attributes(path);
            // On Linux O_RDWR never waits for a FIFO peer, even if a hostile writer swaps the
            // checked regular file for a FIFO just before open. Reject zero-sized/nonseekable
            // handles before reading; a FIFO cannot pass this check. NOFOLLOW rejects links.
            try (final var input = directory.newByteChannel(
                    path, Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                final var size = input.size();
                if (size <= 0
                        || size > LIMIT
                        || size != before.size()
                        || !before.fileKey().equals(attributes(path).fileKey())) {
                    throw new IOException("Changed feedback document");
                }
                final var buffer = ByteBuffer.allocate((int) size);
                while (buffer.hasRemaining()) {
                    if (input.read(buffer) <= 0) {
                        throw new IOException("Truncated feedback document");
                    }
                }
                if (input.size() != size
                        || !before.fileKey().equals(attributes(path).fileKey())) {
                    throw new IOException("Changed feedback document");
                }
                buffer.flip();
                return StandardCharsets.UTF_8.newDecoder().decode(buffer).toString();
            }
        } catch (final NoSuchFileException absent) {
            return null;
        }
    }

    private void write(final String name, final String content) throws IOException {
        final var bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LIMIT) {
            throw new IOException("Oversized feedback document");
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
                // Promoted.
            }
        }
    }

    @Override
    public void close() throws IOException {
        directory.close();
    }
}
