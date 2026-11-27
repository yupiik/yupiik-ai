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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryTodoStoreTest {
    @Test
    void subagentStartsWithEmptyTodoList() {
        final var store = new InMemoryTodoStore();
        assertTrue(store.all().isEmpty(), "a subagent must start with an empty todo list");
    }

    @Test
    void addReplaceAllAndClear() {
        final var store = new InMemoryTodoStore();
        store.add(new TodoItem("first", "pending"));
        store.add(new TodoItem("second", "done"));
        assertEquals(2, store.all().size());

        final var replaced = store.replace(List.of(new TodoItem("only", "pending")));
        assertEquals(1, replaced.size());
        assertEquals("only", replaced.get(0).content());

        store.clear();
        assertTrue(store.all().isEmpty());
    }

    @Test
    void removeByContent() {
        final var store = new InMemoryTodoStore();
        store.add(new TodoItem("keep", "pending"));
        store.add(new TodoItem("drop", "pending"));
        assertTrue(store.remove("drop"));
        assertEquals(
                List.of("keep"), store.all().stream().map(TodoItem::content).toList());
    }
}
