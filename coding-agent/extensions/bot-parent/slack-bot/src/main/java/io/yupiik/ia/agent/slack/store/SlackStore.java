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
package io.yupiik.ia.agent.slack.store;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.slack.configuration.SlackConfiguration;
import io.yupiik.ia.agent.slack.model.SlackTask;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Short private durable transactions. All state access is relative to a pinned directory handle. */
@ApplicationScoped
public class SlackStore {
    private static final Pattern TASK_ID = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern WORKSPACE_ID = Pattern.compile("T[A-Z0-9]{1,63}");
    private static final Pattern CHANNEL_ID = Pattern.compile("[CG][A-Z0-9]{1,63}");
    private static final Pattern USER_ID = Pattern.compile("[UW][A-Z0-9]{1,63}");
    private static final Pattern THREAD_TIMESTAMP = Pattern.compile("[0-9]{1,20}\\.[0-9]{1,20}");

    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private final SlackConfiguration configuration;
    private final JsonMapper json;
    private final ReentrantLock lock = new ReentrantLock();

    public SlackStore(final SlackConfiguration configuration, final JsonMapper json) {
        this.configuration = configuration;
        this.json = json;
    }

    public <T> T transaction(final Function<List<SlackTask>, T> operation) {
        return transact(operation, true);
    }

    private <T> T transact(final Function<List<SlackTask>, T> operation, final boolean write) {
        lock.lock();
        try {
            final var root = configuration.stateRoot();
            requirePrivate(root);
            try (final var stream = Files.newDirectoryStream(root)) {
                if (!(stream instanceof SecureDirectoryStream<Path> directory)) {
                    throw new IllegalStateException("Slack state requires SecureDirectoryStream support");
                }
                checkPrivateFile(directory, "state.lock");
                try (final var channel = directory.newByteChannel(
                        Path.of("state.lock"),
                        Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                    if (!(channel instanceof FileChannel file)) {
                        throw new IllegalStateException("State lock does not support file locks");
                    }
                    try (final var lease = file.lock()) {
                        final var tasks = load(directory);
                        final var result = operation.apply(tasks);
                        validate(tasks);
                        if (!write) {
                            return result;
                        }
                        final var bytes =
                                json.toString(new SlackState(1, tasks)).getBytes(StandardCharsets.UTF_8);
                        if (bytes.length > MAX_BYTES) {
                            throw new IllegalStateException("Slack state capacity exceeded");
                        }
                        final var temporary = Path.of("state-" + UUID.randomUUID() + ".tmp");
                        try {
                            try (final var output = directory.newByteChannel(
                                    temporary,
                                    Set.of(
                                            StandardOpenOption.CREATE_NEW,
                                            StandardOpenOption.WRITE,
                                            LinkOption.NOFOLLOW_LINKS),
                                    PosixFilePermissions.asFileAttribute(
                                            PosixFilePermissions.fromString("rw-------")))) {
                                final var buffer = ByteBuffer.wrap(bytes);
                                while (buffer.hasRemaining()) {
                                    output.write(buffer);
                                }
                                if (output instanceof FileChannel durable) {
                                    durable.force(true);
                                }
                            }
                            directory.move(temporary, directory, Path.of("state.json"));
                            try (final var parent = FileChannel.open(root, StandardOpenOption.READ)) {
                                parent.force(true);
                            }
                        } finally {
                            try {
                                directory.deleteFile(temporary);
                            } catch (final NoSuchFileException ignored) {
                                // Already promoted.
                            }
                        }
                        return result;
                    }
                }
            }
        } catch (final IOException e) {
            throw new IllegalStateException("Private Slack state transaction failed", e);
        } finally {
            lock.unlock();
        }
    }

    public List<SlackTask> list() {
        return transact(List::copyOf, false);
    }

    private List<SlackTask> load(final SecureDirectoryStream<Path> directory) throws IOException {
        checkPrivateFile(directory, "state.json");
        try (final var input = directory.newByteChannel(
                Path.of("state.json"), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            if (input.size() > MAX_BYTES) {
                throw new IllegalStateException("Oversized Slack state");
            }
            final var buffer = ByteBuffer.allocate((int) input.size() + 1);
            while (input.read(buffer) > 0) {
                if (!buffer.hasRemaining()) {
                    throw new IllegalStateException("Oversized Slack state");
                }
            }
            buffer.flip();
            final var tasks = new ArrayList<>(json.fromString(
                            SlackState.class,
                            StandardCharsets.UTF_8.newDecoder().decode(buffer).toString())
                    .tasks());
            validate(tasks);
            return tasks;
        } catch (final NoSuchFileException absent) {
            return new ArrayList<>();
        }
    }

    private void checkPrivateFile(final SecureDirectoryStream<Path> directory, final String name) throws IOException {
        try {
            final var attributes = directory
                    .getFileAttributeView(Path.of(name), PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes();
            final var owner = directory
                    .getFileAttributeView(PosixFileAttributeView.class)
                    .readAttributes()
                    .owner();
            if (!attributes.isRegularFile()
                    || !attributes.owner().equals(owner)
                    || !attributes.permissions().equals(PosixFilePermissions.fromString("rw-------"))) {
                throw new IOException("Slack state files must be private owned regular files");
            }
        } catch (final NoSuchFileException absent) {
            // A newly created state/lock file receives explicit owner-only permissions.
        }
    }

    private void validate(final List<SlackTask> tasks) {
        final var ids = new HashSet<String>();
        for (final var task : tasks) {
            if (task == null
                    || task.id() == null
                    || !TASK_ID.matcher(task.id()).matches()
                    || !ids.add(task.id())
                    || task.request() == null
                    || task.request().length() > configuration.maxInputChars()
                    || task.workspace() == null
                    || !WORKSPACE_ID.matcher(task.workspace()).matches()
                    || task.channel() == null
                    || !CHANNEL_ID.matcher(task.channel()).matches()
                    || task.requester() == null
                    || !USER_ID.matcher(task.requester()).matches()
                    || task.thread() == null
                    || !THREAD_TIMESTAMP.matcher(task.thread()).matches()
                    || task.status() == null
                    || task.conversation() == null
                    || !task.conversation()
                            .equals(identity(task.workspace() + ":" + task.channel() + ":" + task.thread() + ":"
                                    + task.requester()))
                    || task.question() != null
                            && (task.question().request() == null
                                    || !Set.of("publishing", "waiting", "opening", "open", "answered", "cancelled")
                                            .contains(task.question().state()))
                    || task.attempt() < 0
                    || task.answer() != null && task.answer().length() > configuration.maxAnswerChars()) {
                throw new IllegalStateException("Invalid Slack task document");
            }
        }
    }

    private void requirePrivate(final Path root) throws IOException {
        Path current = root.getRoot();
        for (final var part : root) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalStateException("State root must not contain symbolic links");
            }
        }
        Files.createDirectories(
                root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        final var attributes = Files.readAttributes(root, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()
                || !attributes.permissions().equals(PosixFilePermissions.fromString("rwx------"))
                || !attributes
                        .owner()
                        .equals(root.getFileSystem()
                                .getUserPrincipalLookupService()
                                .lookupPrincipalByName(System.getProperty("user.name")))) {
            throw new IllegalStateException("Slack state root must be private and owned by the controller user");
        }
    }

    public static String identity(final String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
