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

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Bounded undo/redo history of input-line snapshots: callers push the state
 * before each mutating operation, undo/redo exchange states with the redo
 * stack. Pure and terminal-free so it is unit testable.
 */
public final class UndoStack {
    private final Deque<String> undo = new ArrayDeque<>();
    private final Deque<String> redo = new ArrayDeque<>();
    private final int capacity;

    public UndoStack() {
        this(100);
    }

    public UndoStack(final int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /**
     * Records a snapshot of the state before an edit. Consecutive identical
     * snapshots are coalesced, and any new edit drops the redo history.
     */
    public void push(final String state) {
        if (state == null) {
            return;
        }
        if (!undo.isEmpty() && undo.peek().equals(state)) {
            return;
        }
        undo.push(state);
        while (undo.size() > capacity) {
            undo.removeLast();
        }
        redo.clear();
    }

    /**
     * @param current the state being undone (becomes redoable)
     * @return the previous state, or null when there is nothing to undo
     */
    public String undo(final String current) {
        if (undo.isEmpty()) {
            return null;
        }
        final var previous = undo.pop();
        if (current != null) {
            redo.push(current);
        }
        return previous;
    }

    /**
     * @param current the state being redone (becomes undoable again)
     * @return the state undone most recently, or null
     */
    public String redo(final String current) {
        if (redo.isEmpty()) {
            return null;
        }
        final var next = redo.pop();
        if (current != null) {
            undo.push(current);
        }
        return next;
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public void clear() {
        undo.clear();
        redo.clear();
    }
}
