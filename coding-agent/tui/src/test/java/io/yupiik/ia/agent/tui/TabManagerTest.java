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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.shell.ThinkTagSplitter;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class TabManagerTest {

    private final TabManager manager = new TabManager();
    private final UiState s1 = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
    private final UiState s2 = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
    private final UiState s3 = new UiState(new ThinkTagSplitter(), Clock.systemUTC());

    private void seed() {
        manager.add(s1);
        manager.add(s2);
        manager.add(s3);
    }

    @Test
    void addMakesTabActive() {
        manager.add(s1);
        assertSame(s1, manager.active());
        assertEquals(0, manager.activeTab());
        manager.add(s2);
        assertSame(s2, manager.active());
        assertEquals(1, manager.activeTab());
        assertEquals(2, manager.size());
    }

    @Test
    void activateClampsOutOfBounds() {
        seed();
        manager.activate(99);
        assertEquals(2, manager.activeTab());
        manager.activate(-5);
        assertEquals(0, manager.activeTab());
        manager.activate(1);
        assertEquals(1, manager.activeTab());
    }

    @Test
    void cycleWraps() {
        seed();
        manager.activate(0);
        manager.cycle(1);
        assertEquals(1, manager.activeTab());
        manager.cycle(1);
        assertEquals(2, manager.activeTab());
        manager.cycle(1);
        assertEquals(0, manager.activeTab());
        manager.cycle(-1);
        assertEquals(2, manager.activeTab());
    }

    @Test
    void singleTabCycleIsNoOp() {
        manager.add(s1);
        manager.cycle(1);
        assertEquals(0, manager.activeTab());
    }

    @Test
    void removeKeepsActiveIndexInBounds() {
        seed(); // activeTab == 2
        manager.remove(s1);
        assertEquals(2, manager.size());
        assertEquals(1, manager.activeTab()); // clamped from 2 to the new last index
        manager.remove(s2);
        assertEquals(1, manager.size());
        assertEquals(0, manager.activeTab());
        assertEquals(s3, manager.active());
        manager.remove(s3);
        assertEquals(-1, manager.activeTab());
        assertNull(manager.active());
    }

    @Test
    void emptyManagerHasNoActive() {
        assertEquals(-1, manager.activeTab());
        assertNull(manager.active());
        manager.activate(2);
        assertEquals(-1, manager.activeTab());
    }

    @Test
    void removeLastTabLeavesActiveMinusOne() {
        manager.add(s1);
        manager.remove(s1);
        assertEquals(-1, manager.activeTab());
        assertNull(manager.active());
        assertTrue(manager.isEmpty());
    }

    @Test
    void resetClearsEverything() {
        seed();
        manager.openList();
        manager.listScroll(4);
        manager.reset();
        assertTrue(manager.isEmpty());
        assertEquals(-1, manager.activeTab());
        assertFalse(manager.isListOpen());
        assertEquals(0, manager.listScroll());
    }

    @Test
    void resolveIndexHandlesForms() {
        seed();
        manager.activate(1);
        assertEquals(2, manager.resolveIndex("last"));
        assertEquals(0, manager.resolveIndex("0"));
        assertEquals(2, manager.resolveIndex("2"));
        assertEquals(2, manager.resolveIndex("+1"));
        assertEquals(0, manager.resolveIndex("-1"));
        assertEquals(2, manager.resolveIndex("+"));
        assertEquals(0, manager.resolveIndex("-"));
        assertEquals(2, manager.resolveIndex("+99")); // clamped
        assertEquals(-1, manager.resolveIndex("abc"));
        assertEquals(-1, manager.resolveIndex("+x"));
        assertEquals(-1, manager.resolveIndex(""));
        assertEquals(-1, manager.resolveIndex(null));
    }

    @Test
    void resolveIndexOnEmptyManagerReturnsMinusOne() {
        assertEquals(-1, manager.resolveIndex("0"));
        assertEquals(-1, manager.resolveIndex("last"));
    }

    @Test
    void listReturnsSnapshot() {
        seed();
        assertEquals(3, manager.list().size());
        manager.remove(s1);
        assertEquals(2, manager.list().size());
    }

    @Test
    void overlayStateToggles() {
        assertFalse(manager.isListOpen());
        manager.openList();
        assertTrue(manager.isListOpen());
        assertEquals(0, manager.listScroll());
        manager.listScroll(7);
        assertEquals(7, manager.listScroll());
        manager.closeList();
        assertFalse(manager.isListOpen());
    }
}
