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

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory todo store for subagents. No persistence, purely ephemeral.
 * Thread-safe for concurrent access.
 */
public class InMemoryTodoStore extends TodoStore {
    private final CopyOnWriteArrayList<TodoItem> todos = new CopyOnWriteArrayList<>();

    public InMemoryTodoStore() {
        super(null, null);
    }

    public InMemoryTodoStore(final CodingAgentConfiguration configuration, final JsonMapper mapper) {
        super(configuration, mapper);
    }

    @Override
    public List<TodoItem> all() {
        return List.copyOf(todos);
    }

    @Override
    public synchronized List<TodoItem> replace(final List<TodoItem> items) {
        todos.clear();
        if (items != null) {
            todos.addAll(items);
        }
        return all();
    }

    @Override
    public void load() {
        // No-op for in-memory store
    }

    /**
     * Adds a todo item.
     */
    public void add(final TodoItem item) {
        if (item != null) {
            todos.add(item);
        }
    }

    /**
     * Removes a todo item by content.
     */
    public boolean remove(final String content) {
        return todos.removeIf(t -> content.equals(t.content()));
    }

    /**
     * Clears all todos.
     */
    public void clear() {
        todos.clear();
    }
}
