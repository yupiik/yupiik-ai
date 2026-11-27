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
package io.yupiik.maven.knowledge.index;

import io.yupiik.maven.knowledge.Gav;
import java.util.List;

/**
 * Result of indexing a single GAV.
 *
 * @param gav        the artifact.
 * @param indexed    true when a full knowledge file was (re)written for it.
 * @param skipped    true when the artifact was present but skipped (immutable-skip or no usable content).
 * @param reason     human readable outcome for logging / feedback.
 */
public record IndexResult(Gav gav, boolean indexed, boolean skipped, String reason) {

    public static IndexResult indexed(final Gav gav, final String reason) {
        return new IndexResult(gav, true, false, reason);
    }

    public static IndexResult skipped(final Gav gav, final String reason) {
        return new IndexResult(gav, false, true, reason);
    }

    public static IndexResult missing(final Gav gav) {
        return new IndexResult(gav, false, true, "not present in local repository");
    }

    /**
     * Collects results in a stable container fit for Reactor-style reporting.
     */
    public record Results(List<IndexResult> items, int indexedCount, int skippedCount) {

        public static Results summarize(final List<IndexResult> items) {
            var indexed = 0;
            var skipped = 0;
            for (final var item : items) {
                if (item.indexed()) {
                    indexed++;
                } else if (item.skipped()) {
                    skipped++;
                }
            }
            return new Results(items, indexed, skipped);
        }
    }
}
