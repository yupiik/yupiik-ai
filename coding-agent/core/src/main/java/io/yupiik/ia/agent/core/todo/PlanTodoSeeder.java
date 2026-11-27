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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.session.SessionStore;

/** Retrieves the last assistant plan locally for export and editing. */
@ApplicationScoped
public class PlanTodoSeeder {
    private final SessionStore sessionStore;

    public PlanTodoSeeder(final SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    protected PlanTodoSeeder() {
        this.sessionStore = null;
    }

    /**
     * Returns the full (uncapped) content of the last assistant message of the
     * session, or {@code null} when there is none.
     *
     * @param sessionId the session to inspect.
     * @return the last assistant message content or {@code null}.
     */
    public String plan(final String sessionId) {
        String last = null;
        for (final var m : sessionStore.load(sessionId)) {
            if ("assistant".equals(m.role()) && m.content() instanceof String s && !s.isBlank()) {
                last = s;
            }
        }
        return last;
    }
}
