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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bounded, persisted ring buffer of submitted prompts plus an in-memory
 * navigation cursor used by the input line to recall history with the
 * Up/Down arrows. Pure and terminal-free so it can be unit tested.
 */
public final class PromptHistory {
    private static final int DEFAULT_CAPACITY = 200;

    private final int capacity;
    private final List<String> entries = new ArrayList<>();
    private int index; // -1 when not navigating, else 0..size-1 (0 == most recent)

    public PromptHistory() {
        this(DEFAULT_CAPACITY);
    }

    public PromptHistory(final int capacity) {
        this.capacity = Math.max(1, capacity);
        this.index = -1;
    }

    public static PromptHistory load(final Path file, final int capacity) {
        final var history = new PromptHistory(capacity);
        if (file == null || !Files.isRegularFile(file)) {
            return history;
        }
        try {
            final var lines = Files.readAllLines(file);
            for (int i = lines.size() - 1; i >= 0; i--) {
                final var line = lines.get(i).strip();
                if (!line.isEmpty()) {
                    history.add(line);
                }
            }
        } catch (final IOException e) {
            // best-effort: start empty when the file cannot be read
        }
        return history;
    }

    public void save(final Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent() == null ? Path.of(".") : file.getParent());
            Files.writeString(file, String.join("\n", entries) + (entries.isEmpty() ? "" : "\n"));
        } catch (final IOException e) {
            // best-effort: persisting history must never break a turn
        }
    }

    /** Replaces the entries with those of {@code loaded} (seeds an in-memory history from disk). */
    public void replaceWith(final PromptHistory loaded) {
        if (loaded == null) {
            return;
        }
        entries.clear();
        entries.addAll(loaded.entries);
        index = -1;
    }

    /**
     * Records a submitted prompt, deduplicating consecutive identical entries
     * and Drops the oldest entry beyond capacity. Resets the navigation index.
     */
    public void add(final String prompt) {
        final var line = prompt == null ? "" : prompt.strip();
        if (line.isEmpty()) {
            return;
        }
        if (!entries.isEmpty() && entries.get(0).equals(line)) {
            index = -1;
            return;
        }
        entries.add(0, line);
        if (entries.size() > capacity) {
            entries.remove(entries.size() - 1);
        }
        index = -1;
    }

    /** @return the raw history, most recent first. */
    public List<String> entries() {
        return Collections.unmodifiableList(entries);
    }

    public int size() {
        return entries.size();
    }

    public int capacity() {
        return capacity;
    }

    public boolean navigating() {
        return index >= 0;
    }

    /**
     * Moves to the older (Up) entry. When not navigating, an explicit
     * {@code draft} is remembered so it can be restored when walking back
     * down past the oldest entry.
     *
     * @return the recalled text, or null when there is nothing older
     */
    public String olderPush(final String draft) {
        if (entries.isEmpty()) {
            return null;
        }
        if (index < 0) {
            index = 0;
        } else if (index < entries.size() - 1) {
            index++;
        } else {
            return null;
        }
        return entries.get(index);
    }

    /**
     * Moves toward the newer (Down) entry.
     *
     * @param draft the in-progress draft restored when walking past the newest entry
     * @return the recalled text, or null when back at the baseline
     */
    public String newerPull(final String draft) {
        if (!navigating()) {
            return null;
        }
        if (index == 0) {
            index = -1;
            return draft;
        }
        index--;
        return entries.get(index);
    }
}
