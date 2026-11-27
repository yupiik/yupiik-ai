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
package io.yupiik.ia.agent.core.todo;

import io.yupiik.fusion.framework.api.container.Types;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.session.SessionStorageLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class TodoStore {
    private static final Pattern UNSAFE_KEY_CHARACTER = Pattern.compile("[^A-Za-z0-9._-]");

    private final CodingAgentConfiguration configuration;
    private final JsonMapper mapper;
    private final String key;
    private volatile List<TodoItem> todos = List.of();

    public TodoStore(final CodingAgentConfiguration configuration, final JsonMapper mapper) {
        this(configuration, mapper, null);
    }

    /**
     * A store persisting under a session key: each tab owns its own file
     * ({@code todos-<key>.json}) so concurrent tabs do not share a list.
     *
     * @param key the session (tab) key or null for the legacy shared {@code todos.json}
     */
    public TodoStore(final CodingAgentConfiguration configuration, final JsonMapper mapper, final String key) {
        this.configuration = configuration;
        this.mapper = mapper;
        this.key = sanitize(key);
    }

    protected TodoStore() {
        this.configuration = null;
        this.mapper = null;
        this.key = null;
    }

    private static String sanitize(final String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return UNSAFE_KEY_CHARACTER.matcher(key).replaceAll("_");
    }

    /**
     * The persisted location: the shared root {@code todos.json} for the legacy
     * unkeyed store, the session folder ({@code sessions/<key>/todos.json}) for
     * generated session ids, and the legacy root {@code todos-<key>.json} for
     * any other key (which never becomes a session folder).
     */
    private Path path() {
        final var home = Path.of(configuration.resolveStorageHome());
        if (key == null) {
            return home.resolve("todos.json");
        }
        if (SessionStorageLayout.isGeneratedSessionId(key)) {
            return SessionStorageLayout.sessionDir(home, key).resolve("todos.json");
        }
        return home.resolve("todos-" + key + ".json");
    }

    /** The legacy root {@code todos-<key>.json} (read-only fallback; never migrated). */
    private Path legacyPath() {
        return key == null ? null : Path.of(configuration.resolveStorageHome()).resolve("todos-" + key + ".json");
    }

    public List<TodoItem> all() {
        return List.copyOf(todos);
    }

    public synchronized List<TodoItem> replace(final List<TodoItem> items) {
        todos = items == null ? List.of() : List.copyOf(items);
        persist();
        return all();
    }

    /**
     * Loads the persisted TodoItem array, including an empty array clearing the current list.
     * Missing, corrupt or incorrectly typed files leave the current list unchanged.
     */
    public synchronized void load() {
        var path = path();
        // backward compatibility: a session whose todos live in the legacy root file is still read
        if (!Files.isReadable(path) && legacyPath() != null && Files.isReadable(legacyPath())) {
            path = legacyPath();
        }
        if (!Files.isReadable(path)) {
            return;
        }
        try {
            final List<TodoItem> loaded = mapper.fromString(
                    new Types.ParameterizedTypeImpl(List.class, TodoItem.class), Files.readString(path));
            if (loaded != null) {
                todos = List.copyOf(loaded);
            }
        } catch (final IOException | RuntimeException e) {
            // corrupted/legacy file: ignore and keep the in-memory list
        }
    }

    private void persist() {
        try {
            final var path = path();
            Files.createDirectories(path.getParent());
            Files.writeString(path, mapper.toString(todos));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String render() {
        return render(Integer.MAX_VALUE);
    }

    /**
     * Renders the todo list for the wire (session-state tail): pending/in-progress items always,
     * completed/cancelled ones limited to the most recent {@code maxDone} so a long history does
     * not get re-sent in full on every round. The store keeps the full list; only the wire view
     * is capped.
     */
    public String render(final int maxDone) {
        if (todos.isEmpty()) {
            return "(empty)";
        }
        final var sb = new StringBuilder();
        var done = 0;
        for (final var t : todos) {
            final boolean isDone = "completed".equals(t.status()) || "cancelled".equals(t.status());
            if (isDone && done >= maxDone) {
                continue;
            }
            if (isDone) {
                done++;
            }
            sb.append(
                            switch (t.status() == null ? "" : t.status()) {
                                case "in_progress" -> "[> ] ";
                                case "completed" -> "[x] ";
                                case "cancelled" -> "[-] ";
                                default -> "[ ] ";
                            })
                    .append(t.content())
                    .append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** Parses raw todo entries ({@link TodoItem} or maps) into todo items. */
    public static List<TodoItem> from(final List<?> raw) {
        final var out = new ArrayList<TodoItem>();
        if (raw != null) {
            for (final var o : raw) {
                if (o instanceof TodoItem t) {
                    out.add(t);
                } else if (o instanceof Map<?, ?> m) {
                    out.add(new TodoItem(str(m.get("content")), str(m.get("status"))));
                }
            }
        }
        return out;
    }

    private static String str(final Object o) {
        return o == null ? null : o.toString();
    }
}
