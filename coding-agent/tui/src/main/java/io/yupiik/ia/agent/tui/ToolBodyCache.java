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
import io.yupiik.ia.agent.core.shell.DiffViewMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Render-thread confined, source- and layout-weight-bounded tool body memo. */
final class ToolBodyCache {
    private static final int MAX_ENTRIES = 64;
    private static final long MAX_WEIGHT = 4L * 1024 * 1024;
    private static final int MAX_SOURCE_CHARS = 256 * 1024;
    private final Map<Key, Value> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long weight;

    record Key(
            String name,
            String command,
            String args,
            String output,
            int width,
            boolean diff,
            int limit,
            DiffViewMode mode) {}

    private record Value(List<Line> lines, long weight) {}

    List<Line> get(final Key key, final Supplier<List<Line>> renderer) {
        final var hit = entries.get(key);
        if (hit != null) {
            return hit.lines();
        }
        final var lines = List.copyOf(renderer.get());
        final var sourceChars =
                (long) length(key.name()) + length(key.command()) + length(key.args()) + length(key.output());
        if (sourceChars > MAX_SOURCE_CHARS) {
            return lines;
        }
        var retained = 128L + 2L * sourceChars;
        for (final var line : lines) {
            retained += 64;
            for (final var span : line.spans()) {
                retained += 96L + 2L * length(span.content());
            }
        }
        if (retained > MAX_WEIGHT) {
            return lines;
        }
        entries.put(key, new Value(lines, retained));
        weight += retained;
        while (entries.size() > MAX_ENTRIES || weight > MAX_WEIGHT) {
            final var first = entries.entrySet().iterator();
            weight -= first.next().getValue().weight();
            first.remove();
        }
        return lines;
    }

    void retainSources(final Set<String> sources) {
        final var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            final var entry = iterator.next();
            if (!sources.contains(entry.getKey().output())) {
                weight -= entry.getValue().weight();
                iterator.remove();
            }
        }
    }

    void clear() {
        entries.clear();
        weight = 0;
    }

    private static int length(final String value) {
        return value == null ? 0 : value.length();
    }
}
