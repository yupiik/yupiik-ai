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
package io.yupiik.ia.agent.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.llm.ApiFlavor;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.notifications.NotificationService;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@FusionSupport(modules = ConfigurationTestModule.class)
class TurnNotificationReporterTest {
    @TempDir
    private static Path eventWorkdir;

    @Test
    void emitTurnMetricsEmitsBothEventsAndSetsLastContent() {
        final var state = new TurnState();
        state.llmCalls = 3;
        state.thinkingMs = 40;
        state.toolSumMs = 500;
        state.toolWallMs = 450;
        state.toolsRun = 2;
        final var events = new ArrayList<AgentEvent>();
        final var lastContent = new AtomicBoolean(false);
        final var reporter = new TurnNotificationReporter(
                state,
                new PrecisionDurationMarkerProvider(),
                null,
                null,
                () -> new ProviderManager.ResolvedModel(null, "gpt-9"),
                () -> "s1",
                () -> "sa",
                () -> "name",
                lastContent::get,
                lastContent::set,
                events::add,
                eventWorkdir);

        reporter.emitTurnMetrics("final answer", System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(1234));

        assertTrue(lastContent.get(), "a non-blank content must record the turn ended with content");
        assertEquals(2, events.size());
        final var metrics = events.get(0);
        assertInstanceOf(AgentEvent.TurnMetrics.class, metrics);
        final var turnMetrics = (AgentEvent.TurnMetrics) metrics;
        assertEquals(3, turnMetrics.llmCalls());
        assertEquals(40, turnMetrics.thinkingDurationMs());
        assertEquals(500, turnMetrics.toolDurationSumMs());
        assertEquals(450, turnMetrics.toolDurationWallMs());
        assertEquals(2, turnMetrics.toolsRun());
        assertTrue(
                turnMetrics.turnDurationMs() >= 1234 && turnMetrics.turnDurationMs() <= 2500,
                "the elapsed turn duration must be measured from the turn start");
        assertEquals("s1", turnMetrics.sessionId());
        assertEquals("sa", turnMetrics.subagentId());
        assertEquals("name", turnMetrics.subagentName());
        final var done = (AgentEvent.TurnDone) events.get(1);
        assertEquals("final answer", done.content());
    }

    @Test
    void emitTurnMetricsWithBlankContentRecordsNoContent() {
        final var state = new TurnState();
        final var events = new ArrayList<AgentEvent>();
        final var lastContent = new AtomicBoolean();
        final var reporter = new TurnNotificationReporter(
                state,
                new PrecisionDurationMarkerProvider(),
                null,
                null,
                () -> null,
                () -> "s1",
                () -> null,
                () -> null,
                lastContent::get,
                lastContent::set,
                events::add,
                eventWorkdir);
        reporter.emitTurnMetrics("  ", 0L);
        assertEquals(2, events.size(), "the metrics and the turn-done event are both emitted");
        assertFalse(lastContent.get(), "a blank content must record the turn ended without content");
    }

    @Test
    void recordThroughputAccumulatesIntoTheTurnState() {
        final var state = new TurnState();
        // The caller has already removed the 150ms pause from the captured request duration.
        state.llmPausedMs = 300;
        final var reporter = new TurnNotificationReporter(
                state,
                new PrecisionDurationMarkerProvider(),
                null,
                null,
                () -> null,
                () -> "s1",
                () -> null,
                () -> null,
                () -> false,
                b -> {},
                e -> {},
                eventWorkdir);
        reporter.recordThroughput(Message.assistant("hello", List.of()), new Usage(10, 120, 130, 0, 0, 30, 250), 850);
        assertEquals(120, state.outputTokens);
        assertEquals(30, state.reasoningTokens);
        assertEquals(250, state.llmReportedMs);
        assertEquals(120, state.reportedOutputTokens);
        assertEquals(1, state.reportedRequests);
        assertFalse(state.outputEstimated);
        assertEquals(850, state.llmActiveMs); // wall 1000 minus the 150 pause
    }

    @Test
    void recordThroughputEstimatesWhenNoUsageIsReported() {
        final var state = new TurnState();
        final var reporter = new TurnNotificationReporter(
                state,
                new PrecisionDurationMarkerProvider(),
                null,
                null,
                () -> null,
                () -> "s1",
                () -> null,
                () -> null,
                () -> false,
                b -> {},
                e -> {},
                eventWorkdir);
        reporter.recordThroughput(Message.assistant("hello world", List.of()), Usage.EMPTY, 100);
        assertTrue(state.outputEstimated);
        assertTrue(state.outputTokens > 0, "the produced tokens must be estimated from the content");
    }

    @Test
    void recordThroughputWithToolCallsEstimatesArguments() {
        final var state = new TurnState();
        final var reporter = new TurnNotificationReporter(
                state,
                new PrecisionDurationMarkerProvider(),
                null,
                null,
                () -> null,
                () -> "s1",
                () -> null,
                () -> null,
                () -> false,
                b -> {},
                e -> {},
                eventWorkdir);
        final var call = new ToolCall(
                "id1", "function", new ToolCall.FunctionCall("bash", "{\"command\":\"echo hello\"}"), null);
        reporter.recordThroughput(Message.assistant("", List.of(call)), Usage.EMPTY, 100);
        assertTrue(state.outputEstimated);
        assertTrue(state.outputTokens > 0, "tool arguments must contribute to the estimate");
    }

    @Test
    void lastAssistantTextCollapsesAndTruncates() {
        final var messages = List.of(
                Message.user("what now?"),
                Message.assistant("first\nline", List.of()),
                Message.assistant("x".repeat(500), List.of()));
        final var text = TurnNotificationReporter.lastAssistantText(messages);
        assertEquals("x".repeat(397) + "...", text);
        assertFalse(text.contains("\n"), "the body must be a single line");
    }

    @Test
    void lastAssistantTextIgnoresToolOnlyMessagesAndNull() {
        assertEquals("", TurnNotificationReporter.lastAssistantText(null));
        assertEquals("", TurnNotificationReporter.lastAssistantText(List.of()));
        // a tool-call-only assistant reply is not a real text answer
        final var call =
                new ToolCall("id1", "function", new ToolCall.FunctionCall("bash", "{\"command\":\"env\"}"), null);
        assertEquals("", TurnNotificationReporter.lastAssistantText(List.of(Message.assistant("", List.of(call)))));
    }

    @Test
    void isTruncationNormalizesReasons() {
        assertTrue(TurnNotificationReporter.isTruncation("length"));
        assertTrue(TurnNotificationReporter.isTruncation("max_tokens"));
        assertFalse(TurnNotificationReporter.isTruncation("stop"));
        assertFalse(TurnNotificationReporter.isTruncation(null));
    }

    @Test
    void emitTurnEndNotificationComposesTitleFromSessionSummary(@Fusion final CodingAgentConfiguration configuration) {
        final var state = new TurnState();
        final var sent = new ArrayList<String>();
        final var notification = new NotificationService(configuration, null) {
            @Override
            public void onTurnEnd(
                    final String sessionId,
                    final String title,
                    final String model,
                    final String provider,
                    final boolean success,
                    final String body) {
                sent.add(title + "|" + model + "|" + provider + "|" + success + "|" + body);
            }
        };
        final var reporter = new TurnNotificationReporter(
                state,
                new PrecisionDurationMarkerProvider(),
                notification,
                new SessionStore() {
                    @Override
                    public String summary(final String id) {
                        return "my summary";
                    }
                },
                () -> new ProviderManager.ResolvedModel(provider(), "gpt-9"),
                () -> "s1",
                () -> null,
                () -> null,
                () -> true,
                b -> {},
                e -> {},
                eventWorkdir);
        reporter.emitTurnEndNotification("s1", List.of(Message.assistant("done", List.of())));
        assertEquals(1, sent.size());
        final var payload = sent.get(0);
        assertTrue(payload.startsWith("s1 - my summary|gpt-9|p1|true|"), payload);
        assertTrue(payload.contains("done"), payload);
    }

    @Test
    void emitTurnEndNotificationSkipsWithoutService() {
        final var state = new TurnState();
        final var reporter = new TurnNotificationReporter(
                state,
                new PrecisionDurationMarkerProvider(),
                null,
                null,
                () -> null,
                () -> "s1",
                () -> null,
                () -> null,
                () -> false,
                b -> {},
                e -> {},
                eventWorkdir);
        reporter.emitTurnEndNotification("s1", List.of());
        // nothing to assert beyond no NPE
    }

    private static ProviderConfiguration provider() {
        return new ProviderConfiguration(
                "p1",
                "https://example.test/v1",
                "",
                "gpt-9",
                ApiFlavor.AUTO,
                -1,
                -1,
                "",
                null,
                List.of(),
                -1,
                300,
                "",
                Map.of(),
                Map.of(),
                "",
                null,
                null,
                null,
                null,
                null);
    }
}
