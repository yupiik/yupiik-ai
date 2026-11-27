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

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Render-thread-confined layout over a fixed transcript of {@code units},
 * mapping a unit {@code index} to its start {@code row} (and back) through
 * exact precomputed prefix sums.
 *
 * <p>This is a shared internal UI helper and is intentionally not part of the
 * public API. The instance is immutable and safe to share only from a single
 * rendering thread; the built-in prefix sums make every lookup logarithmic
 * without invoking the {@code height} accessor on demand.</p>
 *
 * @param <T> the unit type rendered by the UI
 */
final class TranscriptLayout<T> {
    private final List<T> units;
    private final int[] starts;
    private final int height;

    /**
     * @param units  the ordered transcript units (defensively copied)
     * @param height per-unit rendered height; must not be negative
     */
    TranscriptLayout(final List<T> units, final ToIntFunction<T> height) {
        this.units = List.copyOf(units);
        final int size = this.units.size();
        this.starts = new int[size + 1];
        int acc = 0;
        for (int i = 0; i < size; i++) {
            starts[i] = acc;
            final int h = height.applyAsInt(this.units.get(i));
            if (h < 0) {
                throw new IllegalArgumentException("negative height for unit at " + i + ": " + h);
            }
            acc = Math.addExact(acc, h);
        }
        starts[size] = acc;
        this.height = acc;
    }

    /** Total rows occupied by the whole transcript. */
    int height() {
        return height;
    }

    /** Number of units in the transcript. */
    int size() {
        return units.size();
    }

    /** Returns the unit at {@code index}; out of range returns {@code null}. */
    T get(final int index) {
        return index >= 0 && index < units.size() ? units.get(index) : null;
    }

    /**
     * Start row of the unit {@code index}; {@code size()} returns the end (total height).
     * Out of range maps to {@link #size()}.
     */
    int row(final int index) {
        if (index == units.size()) {
            return height;
        }
        if (index < 0 || index > units.size()) {
            return units.size();
        }
        return starts[index];
    }

    /**
     * First unit whose span ends strictly after {@code row}, so that {@code row}
     * falls inside it. Units with zero height are skipped. Empty transcripts and
     * rows at or past {@link #height()} return {@link #size()}.
     */
    int firstVisible(final int row) {
        if (row < 0 || row >= height || starts.length <= 1) {
            return size();
        }
        // binary search for the first start > row (end of span crossing that row)
        // start[i] <= row < start[i+1] is the unit covering the row.
        int lo = 0;
        int hi = units.size();
        while (lo < hi) {
            final int mid = (lo + hi) >>> 1;
            if (starts[mid] > row) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        // lo = first index with start > row; the covering unit is lo - 1.
        return lo - 1;
    }
}
