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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KillRingTest {
    @Test
    void yankReturnsMostRecentKill() {
        final var ring = new KillRing();
        ring.kill("first");
        ring.kill("second");
        assertEquals("second", ring.yank());
    }

    @Test
    void yankPopCycles() {
        final var ring = new KillRing();
        ring.kill("a");
        ring.kill("b");
        assertEquals("b", ring.yank());
        assertEquals("a", ring.yankPop());
        assertEquals("b", ring.yankPop());
    }

    @Test
    void emptyRingYanksNull() {
        assertNull(new KillRing().yank());
        assertNull(new KillRing().yankPop());
    }

    @Test
    void killResetsCycle() {
        final var ring = new KillRing();
        ring.kill("a");
        ring.kill("b");
        ring.yank();
        ring.yankPop();
        ring.kill("c");
        assertEquals("c", ring.yank());
        assertEquals("b", ring.yankPop());
    }

    @Test
    void capacityBounded() {
        final var ring = new KillRing(2);
        ring.kill("1");
        ring.kill("2");
        ring.kill("3");
        assertEquals(2, ring.size());
        assertEquals("3", ring.yank());
        assertEquals("2", ring.yankPop());
    }

    @Test
    void emptyKillIgnored() {
        final var ring = new KillRing();
        ring.kill("");
        ring.kill(null);
        assertTrue(ring.isEmpty());
    }
}
