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
package io.yupiik.ia.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.events.EventContext;
import io.yupiik.ia.agent.core.events.TodoUpdatedEvent;
import io.yupiik.ia.agent.core.todo.TodoItem;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The {@code write_todo} event must reach the session's SSE stream as a {@code todo-updated} frame. */
@ExtendWith(TestWebJsonMapper.class)
class TodoUpdatedSseObserverTest {

    @Test
    void todoUpdateBroadcastsToTheOwningSession(final JsonMapper mapper) {
        final var broker = new SseBroker(mapper);
        final var captured = new ArrayList<byte[]>();
        broker.subscribe("s1", captured::add);

        final var observer = new TodoUpdatedSseObserver(broker);
        observer.onTodoUpdated(new TodoUpdatedEvent(
                new EventContext("s1", null, null, null, null, null, null, null),
                List.of(new TodoItem("task", "pending")),
                List.of(new TodoItem("task", "completed"), new TodoItem("step", "pending"))));

        assertEquals(1, captured.size(), "one todo-updated frame expected");
        final var frame = new String(captured.get(0), StandardCharsets.UTF_8);
        assertTrue(frame.startsWith("event: agent\n"), "frame must be an agent event: " + frame);
        final var payload = frame.substring(frame.indexOf("data: ") + "data: ".length());
        final var dto = mapper.fromString(WebEventDto.class, payload);
        assertEquals("todo-updated", dto.type());
        assertEquals("s1", dto.sessionId());
        assertNotNull(dto.data().get("todos"), "the fresh todo list travels in data.todos");
    }

    @Test
    void todoUpdateWithoutSessionIsIgnored(final JsonMapper mapper) {
        final var broker = new SseBroker(mapper);
        final var captured = new ArrayList<byte[]>();
        broker.subscribe("s1", captured::add);

        final var observer = new TodoUpdatedSseObserver(broker);
        observer.onTodoUpdated(new TodoUpdatedEvent(
                new EventContext(null, null, null, null, null, null, null, null),
                List.of(),
                List.of(new TodoItem("task", "pending"))));

        assertTrue(captured.isEmpty(), "no session -> nothing broadcast");
    }
}
