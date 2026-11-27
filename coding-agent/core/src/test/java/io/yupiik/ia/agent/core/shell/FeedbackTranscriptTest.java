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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.FeedbackTiming;
import io.yupiik.ia.agent.core.llm.Message;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeedbackTranscriptTest {
    private static final Pattern FIRST_ASSISTANT_HEADER = Pattern.compile("\\Q**[1000] Assistant**\\E");
    private static final Pattern SECOND_ASSISTANT_HEADER = Pattern.compile("\\Q**[3000] Assistant**\\E");

    @TempDir
    private static Path eventWorkdir;

    @Test
    void pendingFlushAndFinalKeepTheSameHeadingButNextFeedbackGetsANewOne() {
        final var transcript = transcript();
        transcript.startUserTurn("hi");
        transcript.onEvent(new AgentEvent.FeedbackStarted("text", 1000, "s", null, null, eventWorkdir));
        transcript.onEvent(new AgentEvent.TextDelta("first", "s", eventWorkdir));
        final var pending = transcript.markdown("", "");
        assertTrue(pending.contains("**[1000] Assistant**\n\nfirst"), pending);
        assertSame(pending, transcript.markdown("", ""));
        transcript.onEvent(new AgentEvent.ToolStarted("read", "{}", "s", eventWorkdir));
        transcript.onEvent(new AgentEvent.FeedbackStarted("thinking", 2000, "s", null, null, eventWorkdir));
        transcript.onEvent(new AgentEvent.ThinkingDelta("why", "s", eventWorkdir));
        assertEquals("[2000] Assistant - Thinking", transcript.thinkingLabel());
        transcript.onEvent(new AgentEvent.FeedbackStarted("text", 3000, "s", null, null, eventWorkdir));
        transcript.onEvent(new AgentEvent.TextDelta("last", "s", eventWorkdir));
        transcript.onEvent(new AgentEvent.TurnDone("last", "s", eventWorkdir));
        final var result = transcript.markdown("", "");
        assertEquals(1, FIRST_ASSISTANT_HEADER.split(result, -1).length - 1, result);
        assertEquals(1, SECOND_ASSISTANT_HEADER.split(result, -1).length - 1, result);
        assertTrue(result.indexOf("first") < result.indexOf("why"), result);
        assertTrue(result.indexOf("why") < result.indexOf("last"), result);
    }

    @Test
    void replayUsesCapturedTimeAndLegacyNeverUsesNow() {
        final var transcript = transcript();
        final var timed =
                new Message("assistant", "answer", null, null, null, FeedbackTiming.stamp(null, "text", 1000));
        final var replay = transcript.replay(List.of(timed, Message.assistant("legacy", null)));
        assertTrue(replay.contains("**[1000] Assistant**\n\nanswer"), replay);
        assertTrue(replay.contains("**Assistant**\n\nlegacy"), replay);
    }

    private ConversationTranscript transcript() {
        final var transcript = new ConversationTranscript();
        transcript.setFeedbackLabelResolver(
                (identity, user, at) -> (at == null ? "" : "[" + at + "] ") + (user ? "You" : "Assistant"));
        return transcript;
    }
}
