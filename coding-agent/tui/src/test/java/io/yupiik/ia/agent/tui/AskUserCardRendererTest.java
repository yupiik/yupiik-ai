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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.tamboui.text.Line;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.cli.AskUserAnswersFormatter;
import io.yupiik.ia.agent.test.TestJsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
class AskUserCardRendererTest {
    private JsonMapper mapper;
    private AskUserCardRenderer renderer;

    @BeforeEach
    void setUp(final JsonMapper mapper) {
        this.mapper = mapper;
        renderer = new AskUserCardRenderer(new AskUserAnswersFormatter(mapper));
    }

    @Test
    void answersRenderAsKeyValueLines() {
        final var lines = renderer.render(
                "{\"integration\":\"fusion\",\"frontend\":[\"react\",\"vite\"]}", PaletteTest.palette());
        assertNotNull(lines);
        assertEquals(2, lines.size());
        assertEquals("integration: fusion", plain(lines.get(0)));
        assertEquals("frontend: react, vite", plain(lines.get(1)));
    }

    @Test
    void nonAskUserPayloadFallsBack() {
        assertNull(renderer.render("{oops", PaletteTest.palette()));
        assertNull(renderer.render(null, PaletteTest.palette()));
    }

    private static String plain(final Line line) {
        final var sb = new StringBuilder();
        for (final var span : line.spans()) {
            sb.append(span.content());
        }
        return sb.toString();
    }
}
