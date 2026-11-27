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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.ia.agent.core.events.TodoUpdatedEvent;

/**
 * Bridges the {@code write_todo} {@link TodoUpdatedEvent} (a core/YcaEvent) to the SSE stream of the
 * session that owns the update, as a lightweight {@code todo-updated} agent frame. The frontend treats
 * it as a "reload the sidebar todos" signal and re-fetches {@code session.todos}; the frame carries the
 * fresh list so the widget stays live <em>during</em> a running turn instead of only at {@code turn-done}.
 *
 * <p>Observe-only and never blocks the turn. When the session has no live SSE subscriber (a CLI/TUI run,
 * or no tab open) the {@link SseBroker} broadcast is a no-op, so this adds no cross-session noise.</p>
 */
@ApplicationScoped
public class TodoUpdatedSseObserver {
    private final SseBroker broker;

    public TodoUpdatedSseObserver(final SseBroker broker) {
        this.broker = broker;
    }

    public void onTodoUpdated(@OnEvent final TodoUpdatedEvent event) {
        final var eventContext = event.eventContext();
        if (eventContext == null) {
            return;
        }
        final var sessionId = eventContext.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        broker.broadcastTodoUpdated(sessionId, event.to());
    }
}
