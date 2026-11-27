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
package io.yupiik.ia.agent.github.store;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.model.TaskState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/** Short transactions on a stable sidecar inode; invocation receipts outlive tasks. */
@ApplicationScoped
public class TaskStore {
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private final GithubBotConfiguration configuration;
    private final JsonMapper json;

    public TaskStore(final GithubBotConfiguration configuration, final JsonMapper json) {
        this.configuration = configuration;
        this.json = json;
    }

    private Path home() {
        final var home = Path.of(configuration.home()).toAbsolutePath().normalize();
        try {
            Path current = home.getRoot();
            for (final var segment : home) {
                current = current.resolve(segment);
                if (Files.isSymbolicLink(current)) {
                    throw new IllegalArgumentException("State directory must not contain symlinks");
                }
            }
            Files.createDirectories(home);
            return home;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Creates and validates the private directory without initializing authentication. */
    public void initialize() {
        home();
    }

    public <T> T transaction(final Function<State, T> operation) {
        final var home = home();
        final var local = LOCKS.computeIfAbsent(home, ignored -> new ReentrantLock());
        local.lock();
        try {
            final var lockPath = home.resolve("state.lock");
            if (Files.isSymbolicLink(lockPath)) {
                throw new IllegalStateException("Symbolic state lock");
            }
            try (final var channel = FileChannel.open(
                            lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    final var lock = channel.lock()) {
                final var path = home.resolve("state.json");
                if (Files.isSymbolicLink(path)) {
                    throw new IllegalStateException("Symbolic state file");
                }
                final var content = Files.exists(path) ? Files.readString(path) : null;
                final var data = content == null ? new StateDocument(1, null, null, null) : read(content);
                final var before = new State(data).document();
                // Decode independently: MutableTask and its nested collections must not alias the snapshot.
                // Compare normalized documents so an absent store or unchanged legacy fields do not cause writes.
                final var state = new State(content == null ? data : json.fromString(StateDocument.class, content));
                final var result = operation.apply(state);
                final var document = state.document();
                if (before.equals(document)) {
                    return result;
                }
                final var temp = Files.createTempFile(home, ".state-", ".tmp");
                try {
                    Files.writeString(temp, json.toString(document));
                    try (final var output = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                        output.force(true);
                    }
                    Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    try (final var directory = FileChannel.open(home, StandardOpenOption.READ)) {
                        directory.force(true);
                    }
                } finally {
                    Files.deleteIfExists(temp);
                }
                return result;
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            local.unlock();
        }
    }

    private StateDocument read(final String content) {
        // Shape checks protect retained evidence from permissive collection codecs, not schema migrations.
        final var shape = map(json.fromString(Object.class, content));
        for (final var section : List.of("tasks", "cursors", "receipts")) {
            if (!(shape.get(section) instanceof Map<?, ?>)) {
                throw new IllegalStateException("Expected queue object section: " + section);
            }
        }
        final var document = json.fromString(StateDocument.class, content);
        if (document == null) {
            throw new IllegalStateException("Expected queue document");
        }
        return document;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(final Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    public static final class State {
        private final Map<String, MutableTask> tasks;
        private final Map<String, String> cursors;
        private final Map<String, String> receipts;

        private State(final StateDocument data) {
            tasks = data.tasks() == null
                    ? new LinkedHashMap<>()
                    : data.tasks().entrySet().stream()
                            .collect(
                                    LinkedHashMap<String, MutableTask>::new,
                                    (result, entry) -> result.put(entry.getKey(), new MutableTask(entry.getValue())),
                                    Map::putAll);
            cursors = data.cursors() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data.cursors());
            receipts = data.receipts() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data.receipts());
        }

        public Map<String, MutableTask> tasks() {
            return tasks;
        }

        public Map<String, String> cursors() {
            return cursors;
        }

        public Map<String, String> receipts() {
            return receipts;
        }

        private StateDocument document() {
            final var documents = tasks.entrySet().stream()
                    .collect(
                            LinkedHashMap<String, TaskDocument>::new,
                            (result, entry) ->
                                    result.put(entry.getKey(), entry.getValue().document()),
                            Map::putAll);
            return new StateDocument(1, documents, cursors, receipts);
        }

        public String enqueue(final String invocation, final TaskDocument task) {
            if (receipts().containsKey(invocation)) {
                return String.valueOf(receipts().get(invocation));
            }
            final var id = UUID.randomUUID().toString();
            final var copy = new MutableTask(task);
            copy.id(id);
            copy.state(TaskState.pending);
            copy.attempts(0);
            copy.runs(new ArrayList<>());
            tasks().put(id, copy);
            receipts().put(invocation, id);
            return id;
        }

        public MutableTask task(final String id) {
            final var value = tasks().get(id);
            if (value == null) {
                throw new IllegalArgumentException("Unknown task: " + id);
            }
            return value;
        }

        public List<TaskDocument> list() {
            return tasks().values().stream().map(MutableTask::document).toList();
        }
    }
}
