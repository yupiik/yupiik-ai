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
package io.yupiik.ia.agent.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.llm.Usage;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SubagentUsageTrackerTest {
    @Test
    void fullInputFromNormalAndAuxiliaryCallsSurvivesMergeAndReset() {
        final var parent = new UsageTracker(null, null, null, Clock.systemUTC());
        final var subagent = new SubagentUsageTracker(parent, "sub-input");
        subagent.record(new Usage(10, 5, 15, 60, 30, 2, 0, 100));
        subagent.recordAuxiliary(new Usage(100, 8, 108, 60, 0, 3, 0, 100));
        subagent.record(null);
        subagent.recordAuxiliary(null);
        assertEquals(200, subagent.contextInputTokens());
        assertEquals(110, subagent.promptTokens());
        assertEquals(2, subagent.requests());
        assertEquals(0, subagent.auxiliaryContextInputTokens());
        assertEquals(0, subagent.auxiliaryFreshTokens());
        assertEquals(0, parent.contextInputTokens());

        subagent.mergeToParent();
        assertEquals(200, parent.contextInputTokens());
        assertEquals(200, parent.auxiliaryContextInputTokens());
        assertEquals(110, parent.promptTokens());
        assertEquals(110, parent.auxiliaryPromptTokens());
        assertEquals(120, parent.auxiliaryCacheReadTokens());
        assertEquals(80, parent.auxiliaryFreshTokens());
        assertEquals(13, parent.completionTokens());
        assertEquals(5, parent.reasoningTokens());

        subagent.reset();
        assertEquals(0, subagent.contextInputTokens());
        assertEquals(0, subagent.promptTokens());
        assertEquals(0, subagent.requests());
        assertEquals(0, subagent.auxiliaryContextInputTokens());
        subagent.record(new Usage(0, 0, 0, 50, 0, 0, 0, 50));
        subagent.mergeToParent();
        assertEquals(50, subagent.contextInputTokens());
        assertEquals(250, parent.contextInputTokens());
        assertEquals(250, parent.auxiliaryContextInputTokens());
        assertEquals(110, parent.promptTokens());
        assertEquals(80, parent.auxiliaryFreshTokens());
    }

    @Test
    void toksAreKeptOffTheParentUntilMerged() {
        final var parent = new UsageTracker(null, null, null, Clock.systemUTC());
        final var subagent = new SubagentUsageTracker(parent, "sub-1");

        assertEquals("sub-1", subagent.subagentId());
        subagent.record(new Usage(50, 30, 80, 10, 0, 12, 0));
        subagent.record(new Usage(20, 20, 40, 5, 0, 3, 0));

        assertEquals(70, subagent.promptTokens());
        assertEquals(50, subagent.completionTokens());
        assertEquals(15, subagent.reasoningTokens());
        // before merge nothing reaches the parent
        assertEquals(0, parent.promptTokens());
        assertEquals(0, parent.auxiliaryPromptTokens());
        assertEquals(0, parent.reasoningTokens());
    }

    @Test
    void mergeRollsSubagentUsageIntoParentAuxiliary() {
        final var parent = new UsageTracker(null, null, null, Clock.systemUTC());
        final var subagent = new SubagentUsageTracker(parent, "sub-1");
        subagent.record(new Usage(50, 30, 80, 10, 0, 12, 0));

        subagent.mergeToParent();

        assertEquals(50, parent.auxiliaryPromptTokens());
        assertEquals(30, parent.auxiliaryCompletionTokens());
        assertEquals(10, parent.cacheReadTokens());
        assertEquals(1, parent.auxiliaryRequests());
        assertEquals(1, parent.requests());
        assertEquals(50, parent.promptTokens());
        assertEquals(12, parent.reasoningTokens(), "the merged usage must carry the reasoning tokens");
    }

    @Test
    void mergeIsCumulativeAcrossSubagents() {
        final var parent = new UsageTracker(null, null, null, Clock.systemUTC());
        final var a = new SubagentUsageTracker(parent, "a");
        final var b = new SubagentUsageTracker(parent, "b");
        a.record(new Usage(10, 10, 20, 0, 0));
        b.record(new Usage(20, 20, 40, 0, 0));
        a.mergeToParent();
        b.mergeToParent();
        assertEquals(30, parent.auxiliaryPromptTokens());
        assertEquals(30, parent.auxiliaryCompletionTokens());
    }

    @Test
    void concurrentMergesAreSafeAndComplete() throws Exception {
        final var parent = new UsageTracker(null, null, null, Clock.systemUTC());
        final var tracker = new SubagentUsageTracker(parent, "sub-concurrent");
        final var threads = 8;
        final var executor = Executors.newFixedThreadPool(threads);
        final var done = new CountDownLatch(threads);
        try {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        tracker.record(new Usage(5, 5, 10, 0, 0));
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(threads * 5L, tracker.promptTokens());
            assertEquals(threads * 5L, tracker.completionTokens());
            tracker.mergeToParent();
            assertEquals(threads * 5L, parent.auxiliaryPromptTokens());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void optimizationCountersAccumulateOnTheSubagentAndMergeIntoTheParent() {
        final var parent = new UsageTracker(null, null, null, Clock.systemUTC());
        final var subagent = new SubagentUsageTracker(parent, "sub-opt");

        // records land on the subagent, not the parent, before the merge
        subagent.recordOptimization(10_000, 1_000, 3_000);
        subagent.recordOptimization(20_000, 2_000, -200);
        assertEquals(2, subagent.optimizedOutputs());
        assertEquals(30_000, subagent.optimizedCharsBefore());
        assertEquals(3_000, subagent.optimizedCharsAfter());
        assertEquals(27_000, subagent.optimizedCharsSaved());
        assertEquals(2_800, subagent.optimizedTokensSaved());
        assertEquals(0, parent.optimizedOutputs(), "before the merge the parent must not be touched");

        subagent.mergeToParent();

        assertEquals(2, parent.optimizedOutputs());
        assertEquals(30_000, parent.optimizedCharsBefore());
        assertEquals(3_000, parent.optimizedCharsAfter());
        assertEquals(27_000, parent.optimizedCharsSaved());
        assertEquals(2_800, parent.optimizedTokensSaved());

        subagent.reset();
        assertEquals(0, subagent.optimizedOutputs());
        assertEquals(0, subagent.optimizedCharsSaved());
        assertEquals(0, subagent.optimizedTokensSaved());
        assertEquals(2_800, parent.optimizedTokensSaved());
    }

    @Test
    void nestedMergePreservesNegativeSavingsUntilDisplay() {
        final var parent = new UsageTracker(null, null, null, Clock.systemUTC());
        final var child = new SubagentUsageTracker(parent, "child");
        final var grandchild = new SubagentUsageTracker(child, "grandchild");
        parent.recordOptimization(100, 10, 20);
        grandchild.recordOptimization(100, 90, -30);
        assertEquals(0, grandchild.optimizedTokensSaved());
        grandchild.mergeToParent();
        assertEquals(1, child.optimizedOutputs());
        assertEquals(0, child.optimizedTokensSaved());
        child.mergeToParent();
        assertEquals(0, parent.optimizedTokensSaved());
        parent.recordOptimization(100, 10, 15);
        assertEquals(5, parent.optimizedTokensSaved());
    }
}
