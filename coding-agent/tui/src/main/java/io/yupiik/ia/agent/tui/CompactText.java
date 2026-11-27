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

import dev.tamboui.layout.Alignment;
import dev.tamboui.style.Color;
import dev.tamboui.style.Overflow;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.widget.Widget;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.paragraph.Paragraph;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

/**
 * Pre-wrapped layout, not a retained widget tree. Offscreen rows retain only text and
 * style runs; Line/Span/Text/Paragraph objects are materialized for the viewport only.
 * In particular Paragraph's CLIP implementation must never visit a whole long turn
 * on a spinner repaint. This also keeps search text available without parsing markdown.
 */
final class CompactText {
    private record Row(String text, int[] ends, Style[] styles, Alignment alignment) {
        private Line line() {
            final var spans = new ArrayList<Span>(ends.length);
            var from = 0;
            for (var i = 0; i < ends.length; i++) {
                spans.add(Span.styled(text.substring(from, ends[i]), styles[i]));
                from = ends[i];
            }
            return new Line(spans, alignment);
        }
    }

    private final Row[] rows;
    private final List<String> plain;

    CompactText(final List<Line> lines) {
        rows = new Row[lines.size()];
        for (var i = 0; i < rows.length; i++) {
            final var line = lines.get(i);
            final var spans = line.spans();
            final var text = new StringBuilder();
            final var ends = new int[spans.size()];
            final var styles = new Style[spans.size()];
            for (var j = 0; j < spans.size(); j++) {
                final var span = spans.get(j);
                text.append(span.content());
                ends[j] = text.length();
                styles[j] = span.style();
            }
            rows[i] = new Row(text.toString(), ends, styles, line.alignment().orElse(null));
        }
        plain = new AbstractList<>() {
            @Override
            public String get(final int index) {
                return rows[index].text();
            }

            @Override
            public int size() {
                return rows.length;
            }
        };
    }

    List<String> rows() {
        return plain;
    }

    Widget widget(final int skip, final Block block, final Color background) {
        return (area, buffer) -> {
            final var inner = block == null ? area : block.inner(area);
            // Match Paragraph's scroll clamp, including a partially visible boxed chunk.
            final var start = Math.min(Math.max(0, skip), Math.max(0, rows.length - inner.height()));
            final var end = Math.min(rows.length, start + Math.max(0, inner.height()));
            final var visible = new ArrayList<Line>(end - start);
            for (var i = start; i < end; i++) {
                visible.add(rows[i].line());
            }
            final var paragraph = Paragraph.builder().text(Text.from(visible)).overflow(Overflow.CLIP);
            if (block != null) {
                paragraph.block(block);
            }
            if (background != null) {
                paragraph.background(background);
            }
            paragraph.build().render(area, buffer);
        };
    }
}
