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
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Overflow;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompactTextTest {
    @Test
    void viewportMatchesFullParagraphIncludingClampingBoxesAndStyles() {
        final var lines = List.of(
                Line.from(Span.styled("one", Style.EMPTY.bold()), Span.styled(" two", Style.EMPTY.italic())),
                Line.empty(),
                Line.from("wide \u754c and \ud83d\ude00 text"),
                Line.styled("third row", Style.EMPTY.fg(Color.hex("#aaff00"))),
                Line.from("last row"));
        final var compact = new CompactText(lines);
        assertEquals(
                List.of("one two", "", "wide \u754c and \ud83d\ude00 text", "third row", "last row"), compact.rows());
        assertThrows(UnsupportedOperationException.class, () -> compact.rows().set(0, "changed"));
        for (final var boxed : List.of(false, true)) {
            final var block =
                    boxed ? Block.builder().borders(Borders.ALL).title("code").build() : null;
            for (final var width : List.of(1, 6, 25)) {
                for (final var height : List.of(1, 3, 7)) {
                    for (final var skip : List.of(0, 1, 3, 99)) {
                        final var area = new Rect(0, 0, width, height);
                        final var expected = Buffer.empty(area);
                        final var actual = Buffer.empty(area);
                        final var background = Color.hex("#123456");
                        final var original = Paragraph.builder()
                                .text(Text.from(lines))
                                .overflow(Overflow.CLIP)
                                .scroll(Math.min(lines.size() - 1, skip))
                                .background(background);
                        if (block != null) {
                            original.block(block);
                        }
                        original.build().render(area, expected);
                        compact.widget(skip, block, background).render(area, actual);
                        for (var y = 0; y < height; y++) {
                            for (var x = 0; x < width; x++) {
                                assertEquals(
                                        expected.get(x, y).symbol(),
                                        actual.get(x, y).symbol());
                                assertEquals(
                                        expected.get(x, y).style(),
                                        actual.get(x, y).style());
                                assertEquals(
                                        expected.get(x, y).isContinuation(),
                                        actual.get(x, y).isContinuation());
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void emptyTextStillPaintsItsBackground() {
        final var area = new Rect(0, 0, 3, 2);
        final var expected = Buffer.empty(area);
        final var actual = Buffer.empty(area);
        Paragraph.builder().text(Text.empty()).build().render(area, expected);
        final var compact = new CompactText(List.of());
        compact.widget(0, null, null).render(area, actual);
        assertEquals(List.of(), compact.rows());
        for (var y = 0; y < area.height(); y++) {
            for (var x = 0; x < area.width(); x++) {
                assertEquals(expected.get(x, y).symbol(), actual.get(x, y).symbol());
                assertEquals(expected.get(x, y).style(), actual.get(x, y).style());
            }
        }
    }
}
