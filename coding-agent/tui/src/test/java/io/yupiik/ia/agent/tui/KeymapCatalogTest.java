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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

class KeymapCatalogTest {
    @Test
    void keysAreUnique() {
        final var seen = new HashSet<String>();
        for (final var binding : KeymapCatalog.all()) {
            assertTrue(seen.add(binding.key()), "duplicate key: " + binding.key());
        }
    }

    @Test
    void allBindingsHaveLabelAndGroup() {
        for (final var binding : KeymapCatalog.all()) {
            assertFalse(binding.key().isBlank());
            assertFalse(binding.label().isBlank());
            assertFalse(binding.group().isBlank());
        }
    }

    @Test
    void idleHintsAreCoreOnly() {
        for (final var binding : KeymapCatalog.idleHints()) {
            assertTrue(binding.core(), "idle hints must only use core bindings");
        }
        assertFalse(KeymapCatalog.busyHints().isEmpty());
        assertFalse(KeymapCatalog.overlayHints().isEmpty());
    }

    @Test
    void markdownTableRendersAll() {
        final var md = KeymapCatalog.markdown();
        assertTrue(md.contains("| Key |"));
        for (final var binding : KeymapCatalog.all()) {
            assertTrue(md.contains("| `" + binding.key() + "` |"), "missing in markdown: " + binding.key());
        }
    }

    @Test
    void newFeaturesDocumented() {
        final var md = KeymapCatalog.markdown();
        assertTrue(md.contains("Ctrl+F"));
        assertTrue(md.contains("Ctrl+O"));
        assertTrue(md.contains("Ctrl+R"));
        assertTrue(md.contains("Ctrl+Z"));
        assertTrue(md.contains("Alt+K/J"));
        assertTrue(md.contains("Alt+Backspace"));
        assertTrue(md.contains("Alt+?"));
    }
}
