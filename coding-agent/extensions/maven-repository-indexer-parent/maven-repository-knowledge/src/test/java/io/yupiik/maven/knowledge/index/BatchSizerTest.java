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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.maven.knowledge.Gav;
import io.yupiik.maven.knowledge.config.KnowledgeConfig;
import io.yupiik.maven.knowledge.config.TokenBudget;
import io.yupiik.maven.knowledge.content.ContentAcquired;
import io.yupiik.maven.knowledge.content.ContentKind;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BatchSizerTest {

    private static String contentOf(final int charCount) {
        return "a".repeat(charCount);
    }

    private static Map<Gav, ContentAcquired> contentByGav(final List<Gav> gavs, final int perArtifactChars) {
        final var out = new LinkedHashMap<Gav, ContentAcquired>();
        for (final var gav : gavs) {
            out.put(gav, ContentAcquired.of(gav, ContentKind.DOC, contentOf(perArtifactChars)));
        }
        return out;
    }

    private static List<Gav> gavs(final int n) {
        final var out = new ArrayList<Gav>();
        for (int i = 0; i < n; i++) {
            out.add(Gav.of("g", "a" + i, "1"));
        }
        return out;
    }

    private static KnowledgeConfig config(final int maxBatchChars, final int batchSize, final TokenBudget budget) {
        return new KnowledgeConfig(
                Path.of("."),
                4,
                batchSize,
                true,
                false,
                KnowledgeConfig.defaults(Path.of(".")).boundedFetch(),
                maxBatchChars,
                budget);
    }

    @Test
    void countModeIgnoresBudgetAndSplitsByBatchSize() {
        final var gavs = gavs(5);
        final var batches =
                BatchSizer.batches(gavs, contentByGav(gavs, 10_000), config(0, 2, new TokenBudget(100_000, 32_000)));
        assertEquals(3, batches.size());
        assertEquals(2, batches.get(0).size());
        assertEquals(2, batches.get(1).size());
        assertEquals(1, batches.get(2).size());
    }

    @Test
    void autoModeWithUnknownBudgetFallsBackTo128000Chars() {
        // unknown budget must NOT fall back to GAV-count: it uses a conservative 128000-char cap instead.
        // 5 x 10k = 50k chars < 128k -> a single batch even though a much larger batchSize=64 would fit it all.
        final var gavs = gavs(5);
        final var batches = BatchSizer.batches(gavs, contentByGav(gavs, 10_000), config(-1, 64, new TokenBudget(0, 0)));
        assertEquals(1, batches.size(), "small content must stay in one batch, got " + batches.size());
        assertEquals(5, batches.get(0).size());
    }

    @Test
    void autoModeUnknownBudgetSplitsOver128000Chars() {
        // 15 x 20k = 300k chars > 128k -> split by the 128k fallback. charCapped closes a batch once the running
        // char count passes the cap, so a batch may overshoot by up to one artifact (20k) -> ~140k worst case.
        final var gavs = gavs(15);
        final var batches = BatchSizer.batches(gavs, contentByGav(gavs, 20_000), config(-1, 64, new TokenBudget(0, 0)));
        assertTrue(batches.size() >= 2, "large content must be split by the 128k fallback, got " + batches.size());
        long maxChars = 0;
        for (final var b : batches) {
            maxChars = Math.max(maxChars, (long) b.size() * 20_000);
        }
        assertTrue(maxChars <= 148_000, "no batch may exceed the 128k cap by more than one artifact, got " + maxChars);
        final var seen = new ArrayList<Gav>();
        for (final var b : batches) {
            seen.addAll(b);
        }
        assertEquals(seen.size(), gavs.size());
    }

    @Test
    void autoModeGreedyFillsUpToTokenBudget() {
        // 100k prose chars -> ~36k input tokens each; a 200k window fits ~4 per batch (see inputAllocation math).
        final var gavs = gavs(6);
        final var batches =
                BatchSizer.batches(gavs, contentByGav(gavs, 100_000), config(-1, 64, new TokenBudget(200_000, 1_000)));
        assertTrue(batches.size() >= 2, "large artifacts must be split across batches, got " + batches.size());
        assertTrue(batches.get(0).size() <= 4, "greedy must not overfill the input budget");
        // every GAV present exactly once
        final var seen = new ArrayList<Gav>();
        for (final var b : batches) {
            seen.addAll(b);
        }
        assertEquals(seen.size(), gavs.size());
    }

    @Test
    void autoModeRespectsBatchSizeHardFence() {
        final var gavs = gavs(10);
        // huge budget so input never binds; batchSize=3 must still cap each batch
        final var batches =
                BatchSizer.batches(gavs, contentByGav(gavs, 100), config(-1, 3, new TokenBudget(1_000_000, 1_000_000)));
        assertEquals(4, batches.size(), "batchSize must remain the hard fence");
        for (final var b : batches) {
            assertTrue(b.size() <= 3);
        }
    }

    @Test
    void charCapModeSplitsByCharacters() {
        final var gavs = gavs(4);
        // 10k chars each, cap 15k -> 2 per batch
        final var batches = BatchSizer.batches(gavs, contentByGav(gavs, 10_000), config(15_000, 64, null));
        assertEquals(2, batches.size());
        assertEquals(2, batches.get(0).size());
        assertEquals(2, batches.get(1).size());
    }

    @Test
    void windowsSplitByCountWithoutNeedingContent() {
        // 128 gavs, batchSize=2 -> window = 64*2 = 128, so a single window
        final var small = BatchSizer.windows(gavs(128), config(0, 2, null));
        assertEquals(1, small.size());
        assertEquals(128, small.get(0).size());
        // 129 gavs -> two windows (128 + 1)
        final var large = BatchSizer.windows(gavs(129), config(0, 2, null));
        assertEquals(2, large.size());
        assertEquals(128, large.get(0).size());
        assertEquals(128, large.get(0).size(), "windows must be bounded to WINDOW_MULTIPLIER * batchSize");
        // every GAV present exactly once across windows (no dupes, no loss)
        final var seen = new ArrayList<Gav>();
        for (final var w : large) {
            seen.addAll(w);
        }
        assertEquals(129, seen.size());
        assertEquals(129, new HashSet<>(seen).size(), "windows must not duplicate or drop GAVs");
    }
}
