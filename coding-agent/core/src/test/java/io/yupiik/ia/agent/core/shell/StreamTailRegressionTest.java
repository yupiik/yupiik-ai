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
package io.yupiik.ia.agent.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Live-stream tail tests: inline-code tails must not lag or be lost (regression for char loss in the TUI). */
class StreamTailRegressionTest {
    @TempDir
    private static Path eventWorkdir;

    private final ThinkTagSplitter splitter = new ThinkTagSplitter();

    @Test
    void inlineCodeTailStreamsImmediatelyWhenMoreTextFollowsTheClosingBacktick() {
        final var parser = splitter.create();
        final var visible = new StringBuilder();
        // the closing backtick is followed by more text, so it cannot open a fence:
        // the whole inline-code span must reach the caller now, not sit in the carry
        // window until a flush (regression: probe showed "...`TuiCommand.java" with
        // "` specifically" held back, truncating the live stream tail mid-line)
        for (final var segment : parser.feed("let me look at the git diff of `TuiCommand.java` specifically")) {
            visible.append(segment.text());
        }
        assertEquals(
                "let me look at the git diff of `TuiCommand.java` specifically",
                visible.toString(),
                "inline code followed by text must stream immediately, complete: " + visible);
    }

    @Test
    void textResetAfterInlineCodeTailKeepsTheStreamedText() {
        // ConversationTranscript-level regression: the splitter holds the inline-code tail
        // ("` :" in this example); a TextReset (mid-stream retry) must not drop that carry
        // - it has to drain into pending first so the streamed text survives.
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.TextDelta("je commence par le code de `AgentLoop.java` :", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.TextReset("s1", eventWorkdir));
        // the retry re-sends the whole text (same bytes in this repro)
        t.onEvent(new AgentEvent.TextDelta("je commence par le code de `AgentLoop.java` :", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.ToolStarted("edit_file", "{}", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.ToolFinished("edit_file", "ok", true, "s1", eventWorkdir));

        final var md = t.transcript().toString();
        assertEquals(
                1,
                countOccurrences(md, "`AgentLoop.java` :"),
                "the inline-code tail must appear exactly once, complete, even across a reset: " + md);
    }

    private static int countOccurrences(final String text, final String needle) {
        var count = 0;
        for (var index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }
}
