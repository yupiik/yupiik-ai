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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.shell.ConversationTranscript;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BashExitStatusTest {
    @TempDir
    private static Path eventWorkdir;

    @Test
    void classifiesOnlyTheFinalBashFooter() {
        for (final var output : List.of("Exit code: 1", "failed\nExit code: 42", "failed\nExit code: -1\n")) {
            assertTrue(BashExitStatus.failed("bash", output), output);
            assertFalse(BashExitStatus.failed("read", output), output);
        }
        for (final var output : List.of(
                "",
                "Exit code: 0",
                "Exit code: 1\nExit code: 0",
                "Exit code: 1\nmore stdout",
                "prefix Exit code: 1",
                "Exit code: nope",
                "Exit code: 1 extra",
                "Exit code: 999999999999",
                "Exit code: 1 ",
                "Exit code: 1\n\n")) {
            assertFalse(BashExitStatus.failed("bash", output), output);
        }
        assertFalse(BashExitStatus.failed("bash", null));
    }

    @Test
    void cardsAndCollapsedGroupsUseVisualFailure() {
        final var card = ToolCard.parse("*streaming `bash`: exit 42* (#1, 12 ms)\n\n```text\nExit code: 42\n```");
        assertTrue(card.failed());
        assertFalse(card.pending());
        assertEquals("exit 42", card.args());
        assertEquals("Exit code: 42", card.output());
        assertEquals(1, ToolRun.of(List.of(new ToolRun.Member(card, ""))).failures());
    }

    @Test
    void capturedCompletionClassifiesCardsWithoutFooterInStream() {
        final var transcript = new ConversationTranscript();
        transcript.startUserTurn("run command");
        transcript.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"exit 1\"}", "s1", eventWorkdir));
        transcript.onEvent(new AgentEvent.ToolStream("bash", "stdout", "s1", eventWorkdir));
        final var finished = new AgentEvent.ToolFinished("bash", "stdout\nExit code: 1", true, "s1", eventWorkdir);
        transcript.onEvent(finished);
        final var capture = new ToolOutputStore.Entry("bash", "exit 1", finished.output(), !finished.success(), 0);
        final var card = ConversationTranscript.regions(transcript.markdown("|", "busy")).stream()
                .filter(ConversationTranscript.Region::tool)
                .map(region -> ToolCard.parse(region.text()))
                .filter(parsed -> !parsed.running())
                .findFirst()
                .orElseThrow();
        assertFalse(card.failed(), "the transcript stream does not include the exit footer");
        assertTrue(card.withCapturedStatus(capture).failed());
        assertTrue(finished.success(), "presentation must not change event success");
        assertEquals("stdout\nExit code: 1", finished.output());
    }

    @Test
    void pendingAndRunningOutputsAreNotClassifiedAsFailures() {
        final var output = "Exit code: 1";
        assertFalse(new ToolCard("bash", "", output, false, true, 0, false, 1).failed());
        assertFalse(new ToolCard("bash", "", output, false, false, 0, true, 1).failed());
        assertFalse(new ToolOutputStore.Entry("bash", "", output, false, true, 0).failed());
        assertFalse(new ToolOutputStore.Entry("bash", "", output, false, -1).failed());
        assertTrue(new ToolOutputStore.Entry("bash", "", "Exit code: 0", true, 0).failed());
    }

    @Test
    void finalCaptureOverridesMisleadingStreamFooter() {
        final var card = ToolCard.parse("*streaming `bash`: command* (#1, 12 ms)\n\n```text\nExit code: 1\n```");
        final var capture = new ToolOutputStore.Entry("bash", "command", "Exit code: 1\nExit code: 0", false, 0);
        assertFalse(card.withCapturedStatus(capture).failed());
    }

    @Test
    void overlayStorePreservesClassificationAcrossTruncationAndPendingState() {
        final var store = new ToolOutputStore(4, 10);
        store.record(1, new ToolOutputStore.Entry("bash", "exit 42", "output\nExit code: 42", false, 0));
        assertTrue(store.get(1).failed());
        assertFalse(store.get(1).running());
        store.record(2, new ToolOutputStore.Entry("bash", "", "Exit code: 1", false, true, 0));
        assertTrue(store.get(2).pending());
        assertFalse(store.get(2).failed());
        store.record(3, new ToolOutputStore.Entry("bash", "", "Exit code: 1", false, -1));
        assertTrue(store.get(3).running());
        assertFalse(store.get(3).failed());
    }
}
