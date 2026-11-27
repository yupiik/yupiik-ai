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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Find-in-transcript state: matches are case-insensitive indexes into the
 * flat list of rendered transcript rows. Pure and terminal-free so it is
 * unit testable.
 */
public final class TranscriptSearch {
    private String query = "";
    private final List<Integer> matchRows = new ArrayList<>();
    private int current = -1;

    /** Sets the query without rescanning. */
    public void query(final String query) {
        this.query = query == null ? "" : query;
    }

    public String query() {
        return query;
    }

    /** Re-scans the given rows (plain text per row) for the current query. */
    public void recompute(final List<String> rows) {
        matchRows.clear();
        if (!query.isBlank()) {
            final var needle = query.toLowerCase(Locale.ROOT);
            for (var i = 0; i < rows.size(); i++) {
                final var row = rows.get(i);
                if (row != null && row.toLowerCase(Locale.ROOT).contains(needle)) {
                    matchRows.add(i);
                }
            }
        }
        current = matchRows.isEmpty() ? -1 : 0;
    }

    public boolean active() {
        return !query.isBlank();
    }

    public int count() {
        return matchRows.size();
    }

    /** @return 1-based current match index (0 when there is no match). */
    public int currentMatch() {
        return current < 0 ? 0 : current + 1;
    }

    /** @return the row of the current match, or -1. */
    public int currentRow() {
        return current < 0 || current >= matchRows.size() ? -1 : matchRows.get(current);
    }

    public void next() {
        if (!matchRows.isEmpty()) {
            current = (current + 1) % matchRows.size();
        }
    }

    public void previous() {
        if (!matchRows.isEmpty()) {
            current = (current - 1 + matchRows.size()) % matchRows.size();
        }
    }

    public void clear() {
        query = "";
        matchRows.clear();
        current = -1;
    }

    /** @return all matching rows, ascending. */
    public List<Integer> matches() {
        return List.copyOf(matchRows);
    }
}
