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

import java.util.List;
import org.junit.jupiter.api.Test;

class TuiCleanupLabelTest {
    @Test
    void mapsKindsToShortLabels() {
        assertEquals("summarized", TuiCommand.cleanupLabel(List.of("round-compact"), "SUMMARY"));
        assertEquals("summarized", TuiCommand.cleanupLabel(List.of("compact-retry"), "SUMMARY"));
        assertEquals("trimmed", TuiCommand.cleanupLabel(List.of("round-prune"), "SHRUNK"));
        assertEquals("deduplicated", TuiCommand.cleanupLabel(List.of("dedup"), "CLEARED"));
        assertEquals("superseded", TuiCommand.cleanupLabel(List.of("supersede"), "CLEARED"));
        assertEquals("errors purged", TuiCommand.cleanupLabel(List.of("purge-errors"), "CLEARED"));
    }

    @Test
    void choosesHighestPriorityAndHandlesFallbacks() {
        assertEquals("summarized", TuiCommand.cleanupLabel(List.of("dedup", "round-prune", "compact"), "SUMMARY"));
        assertEquals("truncated", TuiCommand.cleanupLabel(List.of("round-prune"), "TRUNCATE"));
        assertEquals("context updated", TuiCommand.cleanupLabel(List.of("new-kind"), "NONE"));
        assertEquals("context updated", TuiCommand.cleanupLabel(List.of(), "NONE"));
    }
}
