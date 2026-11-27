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
 * Emacs-style kill ring for the input line: killed text (Ctrl+K, Ctrl+U,
 * Ctrl+W, Alt+Backspace) is pushed here and can be yanked back (Alt+Y),
 * cycling through previous kills with consecutive yanks. Pure and
 * terminal-free so it is unit testable.
 */
public final class KillRing {
    private final Deque<String> ring = new ArrayDeque<>();
    private final int capacity;
    private int yankCursor = -1;

    public KillRing() {
        this(10);
    }

    public KillRing(final int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** Records a killed chunk of text (most recent first). */
    public void kill(final String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        ring.addFirst(text);
        while (ring.size() > capacity) {
            ring.removeLast();
        }
        yankCursor = -1;
    }

    /** @return the most recent kill and arms cycling, or null when empty. */
    public String yank() {
        if (ring.isEmpty()) {
            return null;
        }
        yankCursor = 0;
        return ring.peekFirst();
    }

    /**
     * Advances to the previous kill; only meaningful right after a
     * {@link #yank()}.
     *
     * @return the previous kill, or null when the ring is empty
     */
    public String yankPop() {
        if (ring.isEmpty()) {
            return null;
        }
        yankCursor = yankCursor < 0 ? 0 : (yankCursor + 1) % ring.size();
        return elementAt(yankCursor);
    }

    /** Ends a yank sequence so the next yank starts from the most recent kill. */
    public void resetCursor() {
        yankCursor = -1;
    }

    public int size() {
        return ring.size();
    }

    public boolean isEmpty() {
        return ring.isEmpty();
    }

    private String elementAt(final int index) {
        var i = 0;
        for (final var entry : ring) {
            if (i++ == index) {
                return entry;
            }
        }
        return null;
    }
}
