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

import dev.tamboui.text.Line;
import io.yupiik.ia.agent.core.shell.DiffViewMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ToolBodyCacheTest {
    @Test
    void diffModeIsPartOfBodyIdentity() {
        final var cache = new ToolBodyCache();
        final var simple =
                new ToolBodyCache.Key("apply_patch", null, null, "patch", 120, true, 20, DiffViewMode.SIMPLE);
        final var split =
                new ToolBodyCache.Key("apply_patch", null, null, "patch", 120, true, 20, DiffViewMode.SIDE_BY_SIDE);
        assertEquals(List.of(Line.from("simple")), cache.get(simple, () -> List.of(Line.from("simple"))));
        assertEquals(List.of(Line.from("split")), cache.get(split, () -> List.of(Line.from("split"))));
        assertEquals(List.of(Line.from("simple")), cache.get(simple, () -> {
            throw new AssertionError("mode-specific body was not reused");
        }));
    }

    @Test
    void reusesImmutableLayoutAndInvalidatesWidthAndSource() {
        final var cache = new ToolBodyCache();
        final var calls = new AtomicInteger();
        final var original = new ArrayList<>(List.of(Line.from("output")));
        final var key = key("command", "output", 80);
        final var lines = cache.get(key, () -> {
            calls.incrementAndGet();
            return original;
        });
        original.clear();
        assertEquals(1, lines.size());
        assertThrows(UnsupportedOperationException.class, () -> lines.clear());
        cache.get(key, () -> {
            throw new AssertionError("unchanged body was rebuilt");
        });
        cache.get(key("command", "output", 40), () -> {
            calls.incrementAndGet();
            return lines;
        });
        cache.get(key("command", "changed", 80), () -> {
            calls.incrementAndGet();
            return lines;
        });
        assertEquals(3, calls.get());
        cache.clear();
        cache.get(key, () -> {
            calls.incrementAndGet();
            return lines;
        });
        assertEquals(4, calls.get());
    }

    @Test
    void evictsOldestAndDropsObsoleteLiveRevisions() {
        final var cache = new ToolBodyCache();
        final var lines = List.of(Line.from("line"));
        for (var i = 0; i < 65; i++) {
            cache.get(key("command " + i, "output " + i, 80), () -> lines);
        }
        final var calls = new AtomicInteger();
        cache.get(key("command 0", "output 0", 80), () -> {
            calls.incrementAndGet();
            return lines;
        });
        cache.get(key("command 64", "new revision", 80), () -> lines);
        cache.retainSources(Set.of("new revision", "output 0"));
        cache.get(key("command 64", "output 64", 80), () -> {
            calls.incrementAndGet();
            return lines;
        });
        cache.retainSources(Set.of("other"));
        cache.get(key("command 0", "output 0", 80), () -> {
            calls.incrementAndGet();
            return lines;
        });
        assertEquals(3, calls.get());
    }

    @Test
    void oversizedSourceAndLayoutsBypassRetention() {
        final var cache = new ToolBodyCache();
        final var key = key("command", "x".repeat(256 * 1024 + 1), 80);
        final var calls = new AtomicInteger();
        for (var i = 0; i < 2; i++) {
            cache.get(key, () -> {
                calls.incrementAndGet();
                return List.of(Line.from("small"));
            });
        }
        final var oversizedLayout = List.of(Line.from("x".repeat(2 * 1024 * 1024)));
        for (var i = 0; i < 2; i++) {
            cache.get(key("command", "small", 80), () -> {
                calls.incrementAndGet();
                return oversizedLayout;
            });
        }
        assertEquals(4, calls.get());
    }

    private ToolBodyCache.Key key(final String command, final String output, final int width) {
        return new ToolBodyCache.Key("bash", command, null, output, width, false, Integer.MAX_VALUE, DiffViewMode.AUTO);
    }
}
