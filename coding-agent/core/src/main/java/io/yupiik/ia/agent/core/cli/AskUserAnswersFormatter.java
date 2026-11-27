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
package io.yupiik.ia.agent.core.cli;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns the compact JSON answers map returned by the {@code ask_user} tool
 * ({@code {questionId: answer}}) into human-friendly {@code key: value} pairs.
 * Returns {@code null} when the output is not a flat JSON object so callers can
 * fall back to their raw rendering.
 */
@ApplicationScoped
public class AskUserAnswersFormatter {
    private final JsonMapper mapper;

    public AskUserAnswersFormatter(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** One rendered answer: question id + flattened value (lists joined with ", "). */
    public record Answer(String key, String value) {}

    /**
     * @param output the raw tool output
     * @return ordered answers, an empty list for an empty object, or {@code null}
     *         when the output is not a parseable flat JSON object
     */
    public List<Answer> answers(final String output) {
        if (output == null || !output.strip().startsWith("{")) {
            return null;
        }
        final Map<?, ?> map;
        try {
            map = mapper.fromString(Map.class, output);
        } catch (final RuntimeException e) {
            return null;
        }
        if (map == null) {
            return null;
        }
        final var answers = new ArrayList<Answer>(map.size());
        for (final var entry : map.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?>) {
                return null; // nested structure: not the ask_user answers shape
            }
            final var value = entry.getValue() instanceof List<?> list
                    ? String.join(", ", list.stream().map(String::valueOf).toList())
                    : String.valueOf(entry.getValue());
            answers.add(new Answer(String.valueOf(entry.getKey()), value));
        }
        return answers;
    }
}
