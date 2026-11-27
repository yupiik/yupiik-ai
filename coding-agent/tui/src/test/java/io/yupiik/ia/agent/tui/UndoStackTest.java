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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UndoStackTest {
    @Test
    void undoReturnsPreviousState() {
        final var stack = new UndoStack();
        stack.push(""); // before the first edit
        stack.push("a"); // input "a", before the next edit
        assertEquals("a", stack.undo("ab")); // undo the edit that produced "ab"
        assertEquals("", stack.undo("a"));
        assertFalse(stack.canUndo());
        assertNull(stack.undo("a"));
    }

    @Test
    void redoRestoresUndoneState() {
        final var stack = new UndoStack();
        stack.push("");
        stack.push("a");
        stack.undo("ab");
        assertEquals("ab", stack.redo("a"));
        assertFalse(stack.canRedo());
    }

    @Test
    void newEditDropsRedo() {
        final var stack = new UndoStack();
        stack.push("");
        stack.push("a");
        stack.undo("ab");
        assertTrue(stack.canRedo());
        stack.push("abc"); // fresh edit drops the redo history
        assertFalse(stack.canRedo());
    }

    @Test
    void identicalSnapshotsCoalesced() {
        final var stack = new UndoStack();
        stack.push("same");
        stack.push("same");
        stack.push("same");
        assertTrue(stack.canUndo());
        assertEquals("same", stack.undo("other"));
        assertFalse(stack.canUndo());
    }

    @Test
    void capacityBounded() {
        final var stack = new UndoStack(2);
        stack.push("1");
        stack.push("2");
        stack.push("3"); // evicts "1"
        assertEquals("3", stack.undo("x"));
        assertEquals("2", stack.undo("3"));
        assertFalse(stack.canUndo());
    }

    @Test
    void clearEmptiesEverything() {
        final var stack = new UndoStack();
        stack.push("a");
        stack.undo("a");
        stack.clear();
        assertFalse(stack.canUndo());
        assertFalse(stack.canRedo());
        assertNull(stack.undo("x"));
        assertNull(stack.redo("x"));
    }
}
