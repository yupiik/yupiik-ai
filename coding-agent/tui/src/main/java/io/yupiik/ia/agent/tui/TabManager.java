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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns the open session tabs of the TUI and the "tabs list" overlay state.
 * <p>
 * Extracted from {@link TuiCommand} so the tab registry (and the {@code /tabs}
 * slash command) is a plain injectable bean instead of late-bound callbacks:
 * both the TUI command and {@link TuiSlashCommands} constructor-inject it. The
 * manager stores {@code UiState} entries and the active index but never renders
 * or touches the runner - rendering stays in {@link TuiCommand}.
 *
 * <p>Thread-safety: the tab list is a {@link CopyOnWriteArrayList} and the
 * active index is volatile, so background callbacks (for example
 * {@code /tabs} dispatched from the executor thread) can mutate safely.</p>
 */
@ApplicationScoped
public class TabManager {
    private final List<UiState> tabs = new CopyOnWriteArrayList<>();
    private volatile int activeTab;

    /** Whether the "/tabs" list overlay is open. */
    private volatile boolean tabsListOpen;

    /** Scroll offset of the "/tabs" list overlay. */
    private volatile int tabsListScroll;

    public List<UiState> list() {
        return List.copyOf(tabs);
    }

    public int size() {
        return tabs.size();
    }

    public boolean isEmpty() {
        return tabs.isEmpty();
    }

    public UiState get(final int index) {
        return tabs.get(index);
    }

    public int indexOf(final UiState state) {
        return tabs.indexOf(state);
    }

    /** Adds a tab and makes it the active one. */
    public void add(final UiState state) {
        tabs.add(state);
        activeTab = tabs.size() - 1;
    }

    /** Removes a tab, keeping the active index valid (clamped to the new bounds). */
    public void remove(final UiState state) {
        tabs.remove(state);
        if (tabs.isEmpty()) {
            activeTab = -1;
        } else {
            activeTab = Math.clamp(activeTab, 0, tabs.size() - 1);
        }
    }

    /**
     * Activates the tab at {@code index}, clamped to the current bounds. The caller
     * (for example {@code /tabs}) resolves user-facing forms ({@code last}, relative)
     * into a concrete index first through {@link #resolveIndex}.
     */
    public void activate(final int index) {
        if (tabs.isEmpty()) {
            activeTab = -1;
            return;
        }
        activeTab = Math.clamp(index, 0, tabs.size() - 1);
    }

    /** The active tab index, or {@code -1} when there is no tab. */
    public int activeTab() {
        return tabs.isEmpty() ? -1 : activeTab;
    }

    /** The active tab, or {@code null} when there is no tab. */
    public UiState active() {
        final var index = activeTab();
        return index < 0 ? null : tabs.get(index);
    }

    /** Cycles the active tab by {@code delta}; no-op when fewer than two tabs exist. */
    public void cycle(final int delta) {
        if (tabs.size() < 2) {
            return;
        }
        activate(Math.floorMod(activeTab + delta, tabs.size()));
    }

    /**
     * Resolves the argument of {@code /tabs} into a tab index, or returns a negative
     * value when it cannot be resolved. Supported forms: {@code N} (0-based), {@code last},
     * {@code +N}/{@code -N} relative to the active tab, and bare {@code +}/{@code -}
     * (cycle by one).
     */
    public int resolveIndex(final String arg) {
        final var value = arg == null ? "" : arg.strip();
        if (value.isEmpty() || tabs.isEmpty()) {
            return -1;
        }
        if ("last".equalsIgnoreCase(value)) {
            return tabs.size() - 1;
        }
        if ("+".equals(value) || "-".equals(value)) {
            return Math.floorMod(activeTab + ("+".equals(value) ? 1 : -1), tabs.size());
        }
        final var relative = value.startsWith("+") || value.startsWith("-");
        final var number = value.substring(relative ? 1 : 0);
        if (number.isEmpty() || number.chars().anyMatch(c -> !Character.isDigit(c))) {
            return -1;
        }
        final var n = Integer.parseInt(number);
        final var index = relative ? activeTab + (value.startsWith("+") ? n : -n) : n;
        return Math.clamp(index, 0, tabs.size() - 1);
    }

    /** Opens the "/tabs" list overlay (resetting its scroll to the top). */
    public void openList() {
        this.tabsListScroll = 0;
        this.tabsListOpen = true;
    }

    /** Closes the "/tabs" list overlay. */
    public void closeList() {
        this.tabsListOpen = false;
    }

    /** Whether the "/tabs" list overlay is open. */
    public boolean isListOpen() {
        return tabsListOpen;
    }

    /** Scroll offset of the "/tabs" list overlay. */
    public int listScroll() {
        return tabsListScroll;
    }

    /** Sets the scroll offset of the "/tabs" list overlay. */
    public void listScroll(final int scroll) {
        this.tabsListScroll = scroll;
    }

    /** Clears every tab and the overlay state; called when a TUI session starts. */
    public void reset() {
        tabs.clear();
        activeTab = -1;
        tabsListOpen = false;
        tabsListScroll = 0;
    }
}
