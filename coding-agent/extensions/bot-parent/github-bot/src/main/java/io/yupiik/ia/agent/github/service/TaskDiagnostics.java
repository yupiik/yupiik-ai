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
package io.yupiik.ia.agent.github.service;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.github.model.TaskDocument;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Private, bounded failure snapshots, independent of attempt cleanup.
 * Returned JSON is operator-only: original messages and log output can contain secrets.
 * This service deliberately never logs or journals their contents.
 * Requires POSIX permissions and atomic moves. Operations are serialized within this JVM;
 * the owning OS user must not concurrently replace checked filesystem ancestors.
 */
public final class TaskDiagnostics {
    private static final Pattern TASK_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");
    private static final Pattern RECORD =
            Pattern.compile("diagnostic-[0-9]{19}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json");
    private static final Pattern TEMPORARY = Pattern.compile("\\.diagnostic-[A-Za-z0-9_-]+\\.tmp");
    private static final int RETAINED = 16;
    private static final int LOG_BYTES = 64 * 1024;
    private static final int MESSAGE_CHARS = 4096;
    private static final int RECORD_BYTES = 1024 * 1024;
    private static final ReentrantLock LOCK = new ReentrantLock();

    private final Path home;
    private final JsonMapper json;
    private final Clock clock;

    public TaskDiagnostics(final Path home, final JsonMapper json, final Clock clock) {
        this.home = Objects.requireNonNull(home).toAbsolutePath().normalize();
        this.json = Objects.requireNonNull(json);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Returns a home-relative filename, never exception text or execution output. */
    public String capture(final TaskDocument task, final String stage, final Throwable failure, final Integer exitCode)
            throws IOException {
        final var directory = taskDirectory(task.id());
        LOCK.lock();
        try {
            privateDirectory(home);
            privateDirectory(home.resolve("diagnostics"));
            privateDirectory(directory);
            removeTemporaryRecords(directory);
            final var records = records(directory);
            final var details = new LinkedHashMap<String, Object>();
            details.put("timestamp", clock.instant().toString());
            details.put("taskId", task.id());
            details.put("attempt", task.attempts());
            details.put("runtimeId", bounded(task.runtimeId(), MESSAGE_CHARS));
            details.put("stage", bounded(stage, MESSAGE_CHARS));
            details.put("exitCode", exitCode);
            if (failure != null) {
                details.put("exceptionClass", bounded(failure.getClass().getName(), MESSAGE_CHARS));
                details.put("message", bounded(failure.getMessage(), MESSAGE_CHARS));
                final var stack = new ArrayList<String>();
                for (final var frame : failure.getStackTrace()) {
                    if (stack.size() == 32) {
                        break;
                    }
                    stack.add(bounded(frame.toString(), MESSAGE_CHARS));
                }
                details.put("stack", stack);
                final var causes = new ArrayList<Object>();
                final var seen = new IdentityHashMap<Throwable, Boolean>();
                seen.put(failure, Boolean.TRUE);
                var cause = failure.getCause();
                while (cause != null && causes.size() < 16 && seen.put(cause, Boolean.TRUE) == null) {
                    final var entry = new LinkedHashMap<String, Object>();
                    entry.put("exceptionClass", bounded(cause.getClass().getName(), MESSAGE_CHARS));
                    entry.put("message", bounded(cause.getMessage(), MESSAGE_CHARS));
                    causes.add(entry);
                    cause = cause.getCause();
                }
                details.put("causes", causes);
            }
            captureLog(task.attemptDirectory(), details);
            final long sequence;
            try {
                sequence = records.isEmpty()
                        ? 1
                        : Math.addExact(
                                Long.parseLong(records.getLast()
                                        .getFileName()
                                        .toString()
                                        .substring(11, 30)),
                                1);
            } catch (final ArithmeticException | NumberFormatException e) {
                throw new IOException("Diagnostic sequence exhausted");
            }
            final var filename = String.format(Locale.ROOT, "diagnostic-%019d-%s.json", sequence, UUID.randomUUID());
            final var destination = directory.resolve(filename);
            final var bytes = json.toString(details).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > RECORD_BYTES) {
                throw new IOException("Diagnostic record exceeds size limit");
            }
            final var temporary = Files.createTempFile(
                    directory,
                    ".diagnostic-",
                    ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                checkAncestors(directory);
                try (final var output =
                        FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    final var buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) {
                        output.write(buffer);
                    }
                    output.force(true);
                }
                checkAncestors(directory);
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
            records.add(destination);
            while (records.size() > RETAINED) {
                checkAncestors(directory);
                Files.delete(records.removeFirst());
            }
            return home.relativize(destination).toString();
        } finally {
            LOCK.unlock();
        }
    }

    /** Returns at most 16 private records, oldest first, or an empty JSON array. */
    public String read(final String taskId) throws IOException {
        final var directory = taskDirectory(taskId);
        LOCK.lock();
        try {
            if (!existingDirectory(directory)) {
                return "[]";
            }
            final var records = records(directory);
            final var retained = new ArrayList<Object>();
            for (final var record : records.subList(Math.max(0, records.size() - RETAINED), records.size())) {
                checkAncestors(directory);
                try {
                    retained.add(json.fromString(
                            Object.class, new String(readBytes(record, RECORD_BYTES, false), StandardCharsets.UTF_8)));
                } catch (final RuntimeException e) {
                    throw new IOException("Invalid private diagnostic record");
                }
            }
            return json.toString(retained);
        } finally {
            LOCK.unlock();
        }
    }

    /** Deletes only this task's generated records; never recurses into unexpected entries. */
    public void delete(final String taskId) throws IOException {
        final var directory = taskDirectory(taskId);
        LOCK.lock();
        try {
            if (!existingDirectory(directory)) {
                return;
            }
            final var records = records(directory);
            removeTemporaryRecords(directory);
            for (final var record : records) {
                checkAncestors(directory);
                Files.deleteIfExists(record);
            }
            checkAncestors(directory);
            try (final var entries = Files.newDirectoryStream(directory)) {
                if (entries.iterator().hasNext()) {
                    throw new IOException("Unexpected private diagnostic entries require operator inspection");
                }
            }
            Files.deleteIfExists(directory);
        } finally {
            LOCK.unlock();
        }
    }

    private Path taskDirectory(final String taskId) {
        if (taskId == null || !TASK_ID.matcher(taskId).matches()) {
            throw new IllegalArgumentException("Invalid task identifier");
        }
        return home.resolve("diagnostics").resolve(taskId);
    }

    private void removeTemporaryRecords(final Path directory) throws IOException {
        checkAncestors(directory);
        try (final var entries = Files.newDirectoryStream(directory)) {
            int count = 0;
            for (final var entry : entries) {
                if (++count > 1024) {
                    throw new IOException("Too many diagnostic directory entries");
                }
                if (TEMPORARY.matcher(entry.getFileName().toString()).matches()) {
                    final var attributes =
                            Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                        throw new IOException("Unsafe temporary diagnostic record");
                    }
                    Files.delete(entry);
                }
            }
        }
    }

    private void captureLog(final String attemptDirectory, final LinkedHashMap<String, Object> details) {
        if (attemptDirectory == null || attemptDirectory.isBlank()) {
            details.put("logUnavailable", "missing");
            return;
        }
        try {
            final var directory = Path.of(attemptDirectory).toAbsolutePath().normalize();
            checkAncestors(directory);
            final var bytes = readBytes(directory.resolve("execution.log"), LOG_BYTES, true);
            // Ignore incomplete UTF-8 at the tail boundary instead of expanding replacement characters.
            final var text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.IGNORE)
                    .onUnmappableCharacter(CodingErrorAction.IGNORE)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            details.put("logTail", text);
        } catch (final NoSuchFileException e) {
            details.put("logUnavailable", "missing");
        } catch (final IOException | InvalidPathException | SecurityException e) {
            details.put("logUnavailable", "unavailable");
        }
    }

    private byte[] readBytes(final Path path, final int limit, final boolean tail) throws IOException {
        checkAncestors(path.getParent());
        final var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("Unsafe diagnostic source");
        }
        try (final var input = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            final var size = input.size();
            if (!tail && size > limit) {
                throw new IOException("Diagnostic record exceeds size limit");
            }
            final var buffer = ByteBuffer.allocate((int) Math.min(size, limit));
            input.position(tail ? Math.max(0, size - limit) : 0);
            while (buffer.hasRemaining() && input.read(buffer) != -1) {
                // Never read beyond the bounded snapshot, even if the file grows.
            }
            return Arrays.copyOf(buffer.array(), buffer.position());
        }
    }

    private List<Path> records(final Path directory) throws IOException {
        checkAncestors(directory);
        final var records = new ArrayList<Path>();
        try (final var entries = Files.newDirectoryStream(directory)) {
            var count = 0;
            for (final var entry : entries) {
                if (++count > 1024) {
                    throw new IOException("Too many diagnostic directory entries");
                }
                final var attributes =
                        Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink()) {
                    throw new IOException("Unsafe diagnostic entry");
                }
                if (RECORD.matcher(entry.getFileName().toString()).matches()) {
                    if (!attributes.isRegularFile()) {
                        throw new IOException("Unsafe diagnostic record");
                    }
                    records.add(entry);
                }
            }
        }
        records.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return records;
    }

    private boolean existingDirectory(final Path directory) throws IOException {
        try {
            checkAncestors(directory);
            return true;
        } catch (final NoSuchFileException e) {
            return false;
        }
    }

    private void privateDirectory(final Path directory) throws IOException {
        ensureDirectory(directory);
        checkAncestors(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }

    private void ensureDirectory(final Path directory) throws IOException {
        try {
            checkAncestors(directory);
        } catch (final NoSuchFileException e) {
            final var parent = directory.getParent();
            if (parent != null) {
                ensureDirectory(parent);
            }
            try {
                Files.createDirectory(
                        directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } catch (final FileAlreadyExistsException alreadyExists) {
                checkAncestors(directory);
            }
        }
    }

    private void checkAncestors(final Path directory) throws IOException {
        var current = directory.getRoot();
        for (final var segment : directory) {
            current = current.resolve(segment);
            final var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                throw new IOException("Unsafe diagnostic directory");
            }
        }
    }

    private String bounded(final String value, final int limit) {
        return value == null || value.length() <= limit ? value : value.substring(0, limit);
    }
}
