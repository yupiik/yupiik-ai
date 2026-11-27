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
package io.yupiik.ia.agent.github.support;

import io.yupiik.ia.agent.github.model.AgentResult;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Legacy wire fixtures are deliberately separate from the production typed API. */
public final class TaskFixtures {
    private TaskFixtures() {}

    public static TaskDocument task(final Map<String, Object> source) {
        final var copy = new LinkedHashMap<>(source);
        if ("review".equals(String.valueOf(copy.get("kind")))) {
            copy.putIfAbsent(
                    "reviewTarget", "discussion".equals(copy.get("sourceType")) ? "discussion" : "pull_request");
        }
        if (copy.get("actorId") != null) {
            copy.put("actorId", String.valueOf(copy.get("actorId")));
        }
        for (final var key : List.of("number", "attempts")) {
            if (copy.get(key) != null) {
                new BigDecimal(copy.get(key).toString()).longValueExact();
            }
        }
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            return json.fromString(TaskDocument.class, json.toString(copy));
        }
    }

    public static AgentResult result(final Map<String, Object> source) {
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            return json.fromString(AgentResult.class, json.toString(source));
        }
    }

    public static Map<String, Object> map(final Object value) {
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            return TaskStore.map(json.fromString(Object.class, json.toString(value)));
        }
    }
}
