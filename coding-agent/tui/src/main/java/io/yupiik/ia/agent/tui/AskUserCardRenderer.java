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
package io.yupiik.ia.agent.tui;

import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.cli.AskUserAnswersFormatter;
import java.util.List;

/**
 * Renders {@code ask_user} tool card output as human-friendly {@code key: value}
 * lines instead of raw JSON. Falls back to {@code null} (caller keeps its default
 * rendering) when the output is not the expected flat answers object.
 */
@ApplicationScoped
public class AskUserCardRenderer {
    private final AskUserAnswersFormatter formatter;

    public AskUserCardRenderer(final AskUserAnswersFormatter formatter) {
        this.formatter = formatter;
    }

    /**
     * @return one styled line per answer, or {@code null} to fall back to the
     *         default tool output rendering
     */
    public List<Line> render(final String output, final Palette palette) {
        final var answers = formatter.answers(output);
        if (answers == null) {
            return null;
        }
        return answers.stream()
                .map(answer -> Line.from(
                        Span.styled(answer.key(), palette.bold(palette.accent())),
                        Span.styled(": ", palette.muted()),
                        Span.styled(answer.value(), palette.style(palette.text()))))
                .toList();
    }
}
