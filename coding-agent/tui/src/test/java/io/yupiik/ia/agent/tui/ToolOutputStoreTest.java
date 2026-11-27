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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.tools.FileMutation;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ToolOutputStoreTest {
    @Test
    void captureDisplayPolicyFailuresNeverExposeContentOrInterruptToolCompletion() {
        final var store = new ToolOutputStore(2, 256, 0);
        store.record(1, new ToolOutputStore.Entry("edit_file", "{}", "", false, -1));
        final var file = new FileMutation(
                Path.of("/private/file.txt"),
                FileMutation.Kind.UPDATE,
                new FileMutation.Content("private old", "private new"));
        store.recordFiles(1, List.of(file), text -> {
            throw new IllegalStateException("private error");
        });
        final var captured = store.files(1).getFirst();
        assertNull(captured.content());
        assertFalse(captured.path().toString().contains("private"));
        store.record(1, new ToolOutputStore.Entry("edit_file", "{}", "done", false, 1));
        assertEquals("done", store.get(1).output());
        assertEquals(captured.path().toString().length() + 4, store.totalChars());
    }

    @Test
    void capturesAreExactDisplaySafeBoundedAndShareFifoLifetime() {
        final var store = new ToolOutputStore(2, 256, 0);
        final var path = Path.of("/tmp/captured.txt");
        final var file = new FileMutation(path, FileMutation.Kind.UPDATE, new FileMutation.Content("before", "after"));
        store.record(1, new ToolOutputStore.Entry("edit_file", "{}", "", false, -1));
        final var revision = store.revision();
        store.recordFiles(1, List.of(file), UnaryOperator.identity());
        assertEquals(List.of(file), store.files(1));
        store.recordFiles(1, List.of(file), text -> text.equals(path.toString()) ? "masked-path" : text);
        assertEquals(store.files(1).getFirst().path().toString().length() + "beforeafter".length(), store.totalChars());
        store.recordFiles(1, List.of(file), UnaryOperator.identity());
        Assertions.assertTrue(store.revision() > revision);
        store.record(1, new ToolOutputStore.Entry("edit_file", "{}", "done", false, 1));
        assertEquals(List.of(file), store.files(1));
        store.recordFiles(1, List.of(file), text -> text.equals("before") ? "masked" : text);
        Assertions.assertNull(store.files(1).getFirst().content());
        store.recordFiles(
                1,
                List.of(new FileMutation(
                        path, FileMutation.Kind.CREATE, new FileMutation.Content(null, "x".repeat(300)))),
                UnaryOperator.identity());
        Assertions.assertNull(store.files(1).getFirst().content());
        store.record(2, new ToolOutputStore.Entry("read", "{}", "", false, 1));
        store.record(3, new ToolOutputStore.Entry("read", "{}", "", false, 1));
        Assertions.assertTrue(store.files(1).isEmpty());
        store.clear();
        assertEquals(0, store.totalChars());
    }

    @Test
    void captureCharactersParticipateInTotalBudget() {
        final var store = new ToolOutputStore(4, 256, 80);
        store.record(1, new ToolOutputStore.Entry("edit_file", "{}", "", false, 1));
        store.recordFiles(
                1,
                List.of(new FileMutation(
                        Path.of("/tmp/a"),
                        FileMutation.Kind.UPDATE,
                        new FileMutation.Content("x".repeat(40), "y".repeat(40)))),
                UnaryOperator.identity());
        Assertions.assertNull(store.get(1));
        Assertions.assertTrue(store.files(1).isEmpty());
        assertEquals(0, store.totalChars());
    }

    private static final String TRUNCATED = "\n... older output truncated";

    @Test
    void mixedFragmentsMatchBoundedTailAcrossChunkBoundaries() {
        final int cap = 4097;
        final var store = new ToolOutputStore(1, cap);
        store.record(1, new ToolOutputStore.Entry("bash", "", "seed", false, -1));
        var expected = "seed";
        boolean truncated = false;
        for (int i = 0; i < 300; i++) {
            final var fragment = ("part-" + i + ":").repeat((i * 31) % 900) + (i % 2 == 0 ? "\r" : "");
            expected += fragment.endsWith("\r") ? fragment : fragment + "\n";
            if (expected.length() > cap) {
                expected = expected.substring(expected.length() - cap);
                truncated = true;
            }
            store.append(1, fragment);
            final var output = expected + (truncated ? TRUNCATED : "");
            assertEquals(output, store.get(1).output(), "fragment " + i);
            assertEquals(output.length(), store.totalChars());
        }
    }

    @Test
    void exactCapOnlyTruncatesOnNextCharacter() {
        final var store = new ToolOutputStore(1, 4);
        store.record(1, new ToolOutputStore.Entry("bash", "", "", false, -1));
        store.append(1, "abc");
        assertEquals("abc\n", store.get(1).output());
        assertEquals(4, store.totalChars());
        store.append(1, "\r");
        assertEquals("bc\n\r" + TRUNCATED, store.get(1).output());
    }

    @Test
    void appendNormalizesLinesAndSnapshotsAreImmutableAndCached() {
        final var store = new ToolOutputStore();
        store.record(1, new ToolOutputStore.Entry("bash", "args", "", false, true, -1));
        final var empty = store.get(1);
        store.append(1, "first");
        final var first = store.get(1);
        assertSame(first, store.get(1));
        store.append(1, "second\n");
        store.append(1, "third\r");
        store.append(1, "fourth\r\n");
        store.append(1, "");
        final var snapshot = store.get(1);
        assertEquals("", empty.output());
        assertEquals("first\n", first.output());
        assertEquals("first\nsecond\nthird\rfourth\r\n\n", snapshot.output());
        assertEquals(snapshot.output().length(), store.totalChars());
        assertEquals("bash", snapshot.name());
        assertEquals("args", snapshot.args());
        assertTrue(snapshot.pending());
        assertTrue(snapshot.running());
        assertFalse(snapshot.failed());
        assertSame(snapshot, store.get(1));
    }

    @Test
    void appendIgnoresMissingFinishedAndNullFragments() {
        final var store = new ToolOutputStore();
        store.append(-1, "ignored");
        store.append(1, "ignored");
        assertEquals(0, store.size());
        assertEquals(-1, store.maxOrdinalSeen());
        store.record(1, new ToolOutputStore.Entry("bash", "", "exit code: 1", true, 42));
        final var finished = store.get(1);
        store.append(1, "ignored");
        assertSame(finished, store.get(1));
        assertTrue(finished.failed());
        assertEquals(42, finished.elapsedMs());
        store.record(2, new ToolOutputStore.Entry("bash", "", "", false, -1));
        final var running = store.get(2);
        store.append(2, null);
        assertSame(running, store.get(2));
    }

    @Test
    void manySmallAppendsKeepBoundedTailWithoutReadingSnapshots() {
        final var store = new ToolOutputStore(2, 2050);
        store.record(1, new ToolOutputStore.Entry("bash", "", "", false, -1));
        for (int i = 0; i < 20_000; i++) {
            store.append(1, "x");
            assertTrue(store.totalChars() <= 2050 + TRUNCATED.length());
        }
        assertEquals("x\n".repeat(1025) + TRUNCATED, store.get(1).output());
        store.append(1, "last");
        assertEquals(
                ("x\n".repeat(1025) + "last\n").substring(5) + TRUNCATED,
                store.get(1).output());
        assertEquals(2050 + TRUNCATED.length(), store.totalChars());
    }

    @Test
    void oversizedFragmentsAndTinyCapsKeepNewestNormalizedTail() {
        final var store = new ToolOutputStore(2, 7);
        store.record(1, new ToolOutputStore.Entry("bash", "", "old", false, -1));
        store.append(1, "x".repeat(100_000) + "abcdefg");
        assertEquals("bcdefg\n" + TRUNCATED, store.get(1).output());
        store.append(1, "123456\r");
        assertEquals("123456\r" + TRUNCATED, store.get(1).output());
        assertEquals(7 + TRUNCATED.length(), store.totalChars());

        final var tiny = new ToolOutputStore(1, 1);
        tiny.record(1, new ToolOutputStore.Entry("bash", "", "", false, -1));
        tiny.append(1, "abc");
        assertEquals("\n" + TRUNCATED, tiny.get(1).output());
        tiny.append(1, "z\r");
        assertEquals("\r" + TRUNCATED, tiny.get(1).output());
    }

    @Test
    void appendPreservesFifoSlotAndEvictedOrdinalsDoNotReappear() {
        final var store = new ToolOutputStore(2, 32);
        store.record(4, new ToolOutputStore.Entry("bash", "", "", false, -1));
        store.record(2, new ToolOutputStore.Entry("bash", "", "", false, -1));
        for (int i = 0; i < 100; i++) {
            store.append(4, "a");
        }
        assertEquals(2, store.size());
        assertNotNull(store.get(2));
        store.record(3, new ToolOutputStore.Entry("bash", "", "", false, -1));
        assertNull(store.get(4));
        store.append(4, "late");
        assertNull(store.get(4));
        assertNotNull(store.get(2));
        assertEquals(4, store.maxOrdinalSeen());
        assertEquals(0, store.totalChars());
    }

    @Test
    void appendTotalBudgetEvictsOldestIncludingGrowingSelfAndMarker() {
        final var store = new ToolOutputStore(8, 8, 12);
        store.record(1, new ToolOutputStore.Entry("bash", "", "1234", false, -1));
        store.record(2, new ToolOutputStore.Entry("bash", "", "5678", false, -1));
        store.append(2, "abc");
        assertEquals(12, store.totalChars());
        store.append(1, "z");
        assertNull(store.get(1));
        assertEquals("5678abc\n", store.get(2).output());
        assertEquals(8, store.totalChars());
        // The marker participates in the total budget, even when it alone exceeds it.
        store.append(2, "overflow");
        assertNull(store.get(2));
        assertEquals(0, store.totalChars());
        assertEquals(0, store.size());
    }

    @Test
    void recordReleasesMutableTailAndResetsTruncation() {
        final var store = new ToolOutputStore(2, 8);
        store.record(1, new ToolOutputStore.Entry("bash", "", "", false, -1));
        store.append(1, "old output overflowing");
        final var old = store.get(1);
        store.record(1, new ToolOutputStore.Entry("bash", "new", "new", false, -1));
        store.append(1, "line");
        assertEquals("newline\n", store.get(1).output());
        assertEquals(8, store.totalChars());
        store.record(1, new ToolOutputStore.Entry("bash", "done", "result", true, true, 19));
        store.append(1, "ignored");
        final var done = store.get(1);
        assertEquals("result", done.output());
        assertTrue(done.failed());
        assertTrue(done.pending());
        assertEquals(19, done.elapsedMs());
        assertEquals(6, store.totalChars());
        assertEquals("flowing\n" + TRUNCATED, old.output());
    }

    @Test
    void appendAfterOversizedRecordDoesNotAccumulateMarkers() {
        final var store = new ToolOutputStore(1, 4);
        store.record(1, new ToolOutputStore.Entry("bash", "", "0123456789", false, -1));
        store.append(1, "a");
        assertEquals("89a\n" + TRUNCATED, store.get(1).output());
        assertEquals(4 + TRUNCATED.length(), store.totalChars());
    }

    @Test
    void clearReleasesOutputsAndResetsFifoBudgetsAndOrdinals() {
        final var store = new ToolOutputStore(1, 8, 40);
        store.record(9, new ToolOutputStore.Entry("bash", "", "", false, -1));
        store.append(9, "old output");
        final var old = store.get(9);
        store.clear();
        store.clear();
        assertNull(store.get(9));
        assertEquals(0, store.size());
        assertEquals(0, store.totalChars());
        assertEquals(-1, store.latestOrdinal());
        assertEquals(-1, store.maxOrdinalSeen());
        store.append(9, "ignored");
        store.record(1, new ToolOutputStore.Entry("bash", "", "", false, -1));
        store.append(1, "fresh");
        assertEquals("fresh\n", store.get(1).output());
        assertEquals(6, store.totalChars());
        assertEquals(1, store.latestOrdinal());
        assertEquals(1, store.maxOrdinalSeen());
        assertEquals(" output\n" + TRUNCATED, old.output());
    }

    @Test
    void recordsAndRetrievesByOrdinal() {
        final var store = new ToolOutputStore();
        store.record(3, new ToolOutputStore.Entry("mvn", "test", "output-3", false, 12));
        assertEquals("output-3", store.get(3).output());
        assertEquals("mvn", store.get(3).name());
        assertEquals(12, store.get(3).elapsedMs());
        assertEquals(3, store.latestOrdinal());
    }

    @Test
    void missingOrdinalReturnsNull() {
        assertNull(new ToolOutputStore().get(42));
    }

    @Test
    void negativeOrdinalIgnored() {
        final var store = new ToolOutputStore();
        store.record(-1, new ToolOutputStore.Entry("x", "", "y", false, 0));
        assertEquals(0, store.size());
        assertEquals(-1, store.latestOrdinal());
    }

    @Test
    void ringEvictsOldest() {
        final var store = new ToolOutputStore(2, 1024);
        store.record(1, new ToolOutputStore.Entry("a", "", "1", false, 0));
        store.record(2, new ToolOutputStore.Entry("b", "", "2", false, 0));
        store.record(3, new ToolOutputStore.Entry("c", "", "3", false, 0));
        assertNull(store.get(1));
        assertEquals("2", store.get(2).output());
        assertEquals("3", store.get(3).output());
        assertEquals(2, store.size());
    }

    @Test
    void reRecordingExistingOrdinalNeverRotatesEviction() {
        // the reported bug: each stream chunk re-records the same running ordinal, and the
        // unconditional ring push rotated the eviction order, evicting older tools around it
        final var store = new ToolOutputStore(2, 1024);
        store.record(1, new ToolOutputStore.Entry("bash", "{}", "0", false, -1L));
        store.record(2, new ToolOutputStore.Entry("ls", "", "2", false, 0));
        // stream 200 chunks on ordinal 1: must update in place, never push the ring
        for (int i = 1; i <= 200; i++) {
            store.record(1, new ToolOutputStore.Entry("bash", "{}", "chunk-" + i, false, -1L));
        }
        assertNotNull(store.get(1), "ordinal 1 must still be retained after 200 stream chunks");
        assertNotNull(store.get(2), "ordinal 2 must not be evicted by stream chunks of ordinal 1");
        assertEquals(2, store.size(), "re-records must not stack entries");
        assertEquals("chunk-200", store.get(1).output());
    }

    @Test
    void reRecordKeepsFirstInsertionSlotInFifoOrder() {
        final var store = new ToolOutputStore(2, 1024);
        store.record(1, new ToolOutputStore.Entry("a", "", "1", false, 0));
        store.record(2, new ToolOutputStore.Entry("b", "", "2", false, 0));
        // re-record 1 (like a finish): must not move it past 2 in the eviction order
        store.record(1, new ToolOutputStore.Entry("a", "", "1-final", false, 5));
        assertEquals("1-final", store.get(1).output());
        // 3 evicts the oldest = 1, not 2, because 1 kept its original slot
        store.record(3, new ToolOutputStore.Entry("c", "", "3", false, 0));
        assertNull(store.get(1), "the updated ordinal 1 must still be the FIFO head and evict first");
        assertEquals("2", store.get(2).output());
        assertEquals("3", store.get(3).output());
    }

    @Test
    void maxTotalCharsEvictsOldestUntilBudgetFits() {
        final var store = new ToolOutputStore(128, 1024, 20);
        store.record(1, new ToolOutputStore.Entry("a", "", "1111111111", false, 0)); // 10
        store.record(2, new ToolOutputStore.Entry("b", "", "2222222222", false, 0)); // 10
        store.record(3, new ToolOutputStore.Entry("c", "", "3333333333", false, 0)); // 10
        // total is 30 > 20: evict oldest until <= 20 -> drops 1, keeps 2 and 3 (20 chars)
        assertNull(store.get(1));
        assertNotNull(store.get(2));
        assertNotNull(store.get(3));
        assertEquals(2, store.size());
        assertEquals(20, store.totalChars());
        // re-recording an existing ordinal that grows past the budget: 2 goes from 10 to 12 chars,
        // total would be 22 > 20 -> evict the oldest (2) until the budget fits -> only 3 remains
        store.record(2, new ToolOutputStore.Entry("b", "", "222222222222", false, 0)); // 12
        assertNull(store.get(2), "growing an existing entry past the total budget evicts the oldest first");
        assertEquals("3333333333", store.get(3).output());
        assertEquals(10, store.totalChars());
    }

    @Test
    void maxTotalCharsZeroMeansUnboundedByTotal() {
        final var store = new ToolOutputStore(128, 1024, 0);
        for (int i = 1; i <= 5; i++) {
            store.record(i, new ToolOutputStore.Entry("a", "", "1234567890", false, 0));
        }
        assertEquals(5, store.size());
        assertEquals(50, store.totalChars());
    }

    @Test
    void oversizedOutputKeepsTail() {
        final var store = new ToolOutputStore(8, 10);
        store.record(1, new ToolOutputStore.Entry("a", "", "0123456789ABCDEF", false, 0));
        final var output = store.get(1).output();
        assertTrue(output.startsWith("6789ABCDEF"));
        assertTrue(output.contains("truncated"));
        assertTrue(output.length() < 40);
    }

    @Test
    void failedFlagPreserved() {
        final var store = new ToolOutputStore();
        store.record(1, new ToolOutputStore.Entry("a", "", "boom", true, 0));
        assertTrue(store.get(1).failed());
    }

    @Test
    void reRecordSameOrdinalReplaces() {
        final var store = new ToolOutputStore();
        store.record(1, new ToolOutputStore.Entry("a", "", "first", false, 0));
        store.record(1, new ToolOutputStore.Entry("a", "", "second", false, 0));
        assertEquals("second", store.get(1).output());
        assertEquals(1, store.size());
    }

    @Test
    void runningEntryFlaggedAndReplacedOnFinish() {
        final var store = new ToolOutputStore();
        // the TUI records a placeholder at start (elapsedMs < 0 => running)
        store.record(1, new ToolOutputStore.Entry("bash", "{\"command\":\"mvn test\"}", "", false, -1L));
        final var running = store.get(1);
        assertTrue(running.running(), "elapsedMs < 0 must mark a still-running call");
        assertEquals("bash", running.name());
        assertFalse(running.failed());

        // the ToolFinished event overwrites the same ordinal with the real output
        store.record(
                1, new ToolOutputStore.Entry("bash", "{\"command\":\"mvn test\"}", "BUILD SUCCESS", false, 1_234L));
        final var done = store.get(1);
        assertFalse(done.running(), "the finished record must not be running");
        assertEquals("BUILD SUCCESS", done.output());
        assertEquals(1_234L, done.elapsedMs());
        assertEquals(1, store.size(), "the placeholder must be replaced, not stacked");
        assertEquals(1, store.latestOrdinal());
    }

    @Test
    void pendingOutputRendersWithoutCrashing() {
        // the overlay builds a body from the running entry's empty output
        final var store = new ToolOutputStore();
        store.record(2, new ToolOutputStore.Entry("bash", "{}", "", false, -1L));
        assertNotNull(store.get(2));
        assertTrue(store.get(2).output().isEmpty());
    }

    @Test
    void streamAppendsToRunningEntryUntilItReplacesIt() {
        final var store = new ToolOutputStore();
        store.record(1, new ToolOutputStore.Entry("bash", "{\"command\":\"mvn test\"}", "", false, -1L));
        // each ToolStream chunk is recorded under the same ordinal; the running entry
        // accumulates the live tail without stacking new ring entries
        store.record(1, new ToolOutputStore.Entry("bash", "{\"command\":\"mvn test\"}", "compiling", false, -1L));
        store.record(
                1, new ToolOutputStore.Entry("bash", "{\"command\":\"mvn test\"}", "compiling\nBUILD", false, -1L));
        final var running = store.get(1);
        assertTrue(running.running());
        assertEquals("compiling\nBUILD", running.output());
        assertEquals(1, store.size(), "stream chunks must not stack entries");
    }

    @Test
    void streamAppendIsCappedToMaxOutputChars() {
        final var store = new ToolOutputStore(4, 16);
        store.record(1, new ToolOutputStore.Entry("bash", "{}", "", false, -1L));
        store.record(1, new ToolOutputStore.Entry("bash", "{}", "0123456789abcdef", false, -1L));
        store.record(1, new ToolOutputStore.Entry("bash", "{}", "0123456789abcdefghijklmnopqrstuv", false, -1L));
        final var output = store.get(1).output();
        assertTrue(output.length() <= 16 + "\n... older output truncated".length(), "output=" + output);
        assertTrue(output.endsWith("\n... older output truncated"), output);
    }
}
