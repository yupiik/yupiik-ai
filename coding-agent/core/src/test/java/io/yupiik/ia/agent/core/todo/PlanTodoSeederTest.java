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
import static org.junit.jupiter.api.Assertions.assertNull;

import io.yupiik.ia.agent.core.session.InMemorySessionStore;
import io.yupiik.ia.agent.core.session.SessionEntry;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanTodoSeederTest {
    @Test
    void retrievesLastNonblankAssistantPlanWithoutTruncation() {
        final var sessions = new InMemorySessionStore(Clock.systemUTC());
        final var id = sessions.create();
        final var plan = "Plan:\n" + "a concrete implementation step\n".repeat(500);
        sessions.append(id, SessionEntry.of(1, "assistant", "earlier plan", null, null));
        sessions.append(id, SessionEntry.of(2, "assistant", plan, null, null));
        sessions.append(id, SessionEntry.of(3, "user", "implement it", null, null));
        sessions.append(id, SessionEntry.of(4, "assistant", " \n", null, null));
        sessions.append(id, SessionEntry.of(5, "tool", "tool result", null, null));
        sessions.append(id, SessionEntry.of(6, "assistant", List.of("not a text plan"), null, null));
        final var otherId = sessions.create();
        sessions.append(otherId, SessionEntry.of(1, "assistant", "another session's plan", null, null));

        final var plans = new PlanTodoSeeder(sessions);

        assertEquals(plan, plans.plan(id));
        assertEquals("another session's plan", plans.plan(otherId));
    }

    @Test
    void returnsNullWithoutAssistantText() {
        final var sessions = new InMemorySessionStore(Clock.systemUTC());
        final var id = sessions.create();
        final var plans = new PlanTodoSeeder(sessions);
        assertNull(plans.plan(id));
        sessions.append(id, SessionEntry.of(1, "user", "plan a refactor", null, null));
        sessions.append(id, SessionEntry.of(2, "assistant", " ", null, null));
        sessions.append(id, SessionEntry.of(3, "assistant", null, null, null));
        assertNull(plans.plan(id));
    }
}
