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
package io.yupiik.ia.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.context.CompactionResult.Mode;
import io.yupiik.ia.agent.core.context.ContextMeasurement.Snapshot;
import io.yupiik.ia.agent.core.llm.Usage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Mapping of the tool lifecycle events to the flat SSE DTO the browser consumes. */
class WebEventDtoTest {
    @Test
    void diagnosticUsesDedicatedDisplayPayload() {
        final var dto = WebEventDto.of(new AgentEvent.ToolDiagnostic(
                "mcp_example", "private failure", 42, "s", "child", "Worker", eventWorkdir));
        assertEquals("tool-diagnostic", dto.type());
        assertEquals("mcp_example", dto.message());
        assertEquals("s", dto.sessionId());
        assertEquals("child", dto.subagentId());
        assertEquals("Worker", dto.subagentName());
        assertEquals("private failure", dto.data().get("detail"));
        assertEquals(42L, dto.data().get("ordinal"));
        assertFalse(dto.data().containsKey("output"));
    }

    @TempDir
    private static Path eventWorkdir;

    @Test
    void turnMetricsExposeRequestAndCoveredServerThroughput() {
        final var throughput = new AgentEvent.Throughput(300, 40, 2000, 500, 100, 3, 1, true);
        final var dto =
                WebEventDto.of(new AgentEvent.TurnMetrics(3, 10, 20, 15, 2, 3000, throughput, "s1", eventWorkdir));
        assertEquals("turn-metrics", dto.type());
        assertEquals(3, dto.data().get("llmCalls"));
        assertEquals(10L, dto.data().get("thinkingDurationMs"));
        assertEquals(20L, dto.data().get("toolDurationSumMs"));
        assertEquals(15L, dto.data().get("toolDurationWallMs"));
        assertEquals(2, dto.data().get("toolsRun"));
        assertEquals(3000L, dto.data().get("turnDurationMs"));
        assertEquals(300, dto.data().get("outputTokens"));
        assertEquals(40, dto.data().get("reasoningTokens"));
        assertEquals(150.0, dto.data().get("tokensPerSecond"));
        assertEquals(150.0, dto.data().get("requestTokensPerSecond"));
        assertEquals(200.0, dto.data().get("serverTokensPerSecond"));
        assertEquals(2000L, dto.data().get("activeMs"));
        assertEquals(500L, dto.data().get("reportedMs"));
        assertEquals(100, dto.data().get("reportedOutputTokens"));
        assertEquals(3, dto.data().get("completedRequests"));
        assertEquals(1, dto.data().get("reportedRequests"));
        assertEquals(true, dto.data().get("outputEstimated"));
    }

    @Test
    void missingThroughputExposesZeroMetrics() {
        final var data = WebEventDto.of(new AgentEvent.TurnMetrics(0, 0, 0, 0, 0, 0, null, "s1", eventWorkdir))
                .data();
        assertEquals(0.0, data.get("tokensPerSecond"));
        assertEquals(0.0, data.get("requestTokensPerSecond"));
        assertEquals(0.0, data.get("serverTokensPerSecond"));
        assertEquals(0L, data.get("activeMs"));
        assertEquals(0L, data.get("reportedMs"));
        assertEquals(0, data.get("reportedOutputTokens"));
        assertEquals(0, data.get("completedRequests"));
        assertEquals(0, data.get("reportedRequests"));
        assertEquals(false, data.get("outputEstimated"));
    }

    @Test
    void toolStreamCarriesNameAndFragment() {
        final var dto = WebEventDto.of(new AgentEvent.ToolStream("bash", "line 1", "s1", null, null, eventWorkdir));
        assertEquals("tool-stream", dto.type());
        // message = tool name (the frontend binds stream fragments to the running card by name)
        assertEquals("bash", dto.message());
        // the streamed fragment travels in data.fragment
        assertEquals("line 1", dto.data().get("fragment"));
        assertEquals(true, dto.data().get("stream"));
    }

    @Test
    void toolStartedAndFinishedCarryTheSameName() {
        final var started =
                WebEventDto.of(new AgentEvent.ToolStarted("write_file", "{}", 1, "s1", null, null, eventWorkdir));
        final var finished =
                WebEventDto.of(new AgentEvent.ToolFinished("write_file", "ok", true, "s1", null, null, eventWorkdir));
        assertEquals("tool-started", started.type());
        assertEquals("write_file", started.message());
        assertEquals(1L, started.data().get("ordinal"));
        assertEquals("tool-finished", finished.type());
        assertEquals("write_file", finished.message());
        assertEquals("ok", finished.data().get("output"));
        assertEquals(true, finished.data().get("success"));
        assertEquals(false, finished.data().get("pending"));
    }

    @Test
    void pendingToolFinishedCarriesThePendingFlag() {
        // a pending tool (e.g. a collect wait that timed out) must surface as pending so the web chip
        // renders it neutral rather than a hard failure
        final var finished = WebEventDto.of(new AgentEvent.ToolFinished(
                "check_subagents", "pending", false, true, 3, "s1", null, null, eventWorkdir));
        assertEquals("tool-finished", finished.type());
        assertEquals(false, finished.data().get("success"));
        assertEquals(true, finished.data().get("pending"));
    }

    @Test
    void subagentEventsCarryIdentityAndModel() {
        final var started =
                WebEventDto.of(new AgentEvent.SubagentStarted("do the thing", "s1", "sub-1", "child", eventWorkdir));
        assertEquals("subagent-started", started.type());
        assertEquals("do the thing", started.message());
        assertEquals("sub-1", started.data().get("subagentId"));
        assertEquals("child", started.data().get("subagentName"));
        assertNull(started.data().get("model"), "the model is unresolved at spawn");

        final var completed = WebEventDto.of(
                new AgentEvent.SubagentCompleted("done", true, false, "s1", "sub-1", "child", "gpt-5.5", eventWorkdir));
        assertEquals("subagent-completed", completed.type());
        assertEquals("done", completed.message());
        assertEquals(true, completed.data().get("success"));
        assertEquals(false, completed.data().get("cancelled"));
        assertEquals("sub-1", completed.data().get("subagentId"));
        assertEquals("child", completed.data().get("subagentName"));
        assertEquals("gpt-5.5", completed.data().get("model"), "the effective model must ride the payload");
    }

    @Test
    void usageUpdatedExposesLiveCompletionAndReasoningTokens() {
        final var dto = WebEventDto.of(new AgentEvent.UsageUpdated(
                new Usage(10, 120, 130, 0, 0, 40, 0),
                50,
                2.0,
                0,
                -1,
                "model-x",
                "s1",
                null,
                null,
                AgentEvent.UsageUpdated.Quota.NONE,
                new Snapshot(50, 52, "provider-anchored", 40, 10, 1.0),
                eventWorkdir));
        assertEquals("usage-updated", dto.type());
        assertEquals("model-x", dto.message());
        assertEquals(120, dto.data().get("completionTokens"));
        // reasoning is carried for the frontend's live per-turn "out + reasoning" display
        assertEquals(40, dto.data().get("reasoningTokens"));
        assertEquals(50, dto.data().get("estimatedContextTokens"));
        assertEquals("provider-anchored", dto.data().get("contextSource"));
        assertEquals(10, dto.data().get("pendingContextTokens"));
        assertEquals(52, dto.data().get("contextBudgetTokens"));
        assertEquals(2.0, dto.data().get("contextPercent"));
    }

    @Test
    void compactionPassAddsNoOperationPayloadToTheLegacyWebTransport() {
        for (final var status : AgentEvent.CompactionPass.Status.values()) {
            final var dto = WebEventDto.of(new AgentEvent.CompactionPass(
                    "pass", status, Mode.SUMMARY, "operation detail", "s1", "child", "Worker", eventWorkdir));
            assertEquals("compaction-pass", dto.type());
            assertEquals("s1", dto.sessionId());
            assertEquals("child", dto.subagentId());
            assertEquals("Worker", dto.subagentName());
            assertNull(dto.message());
            assertEquals(Map.of(), dto.data(), "no detail, root, digest or invented summary/counts are exposed");
        }
    }

    @Test
    void compactedCarriesTheMergedKindsAndMode() {
        // several reductions of the same checkpoint are merged into one event: the message keeps
        // the flat "kinds|mode" shape with the comma-joined kinds, so the web frontend can split
        // on the pipe without re-parsing per-kind payloads
        final var dto =
                WebEventDto.of(new AgentEvent.Compacted(List.of("dedup", "prune"), "CLEARED", "s1", eventWorkdir));
        assertEquals("compacted", dto.type());
        assertEquals("dedup,prune|CLEARED", dto.message());
        assertNull(dto.data().get("tokensBefore"), "no tokens carried -> no before key");
        assertNull(dto.data().get("tokensAfter"), "no tokens carried -> no after key");
    }

    @Test
    void usageFramesPreserveRawPromptAndExplicitFullInput() {
        for (final var usage : List.of(
                new Usage(100, 25, 125, 400, 0, 0, 0, 500),
                new Usage(0, 0, 0, 500, 0, 0, 0, 500),
                new Usage(0, 25, 25, 0, 0, 0, 0, 0),
                new Usage(100, 25, 125))) {
            final var updated = WebEventDto.of(new AgentEvent.UsageUpdated(usage, 0, 0, "s1", eventWorkdir));
            final var completed =
                    WebEventDto.of(new AgentEvent.LlmRequestCompleted(usage, null, 1, "s1", eventWorkdir));
            for (final var dto : List.of(updated, completed)) {
                assertEquals(usage.promptTokens(), dto.data().get("promptTokens"));
                assertEquals(usage.contextInputTokens(), dto.data().get("contextInputTokens"));
                assertEquals(usage.cacheReadTokens(), dto.data().get("cacheReadTokens"));
            }
        }
    }

    @Test
    void absentUsageHasZeroFullInput() {
        assertEquals(
                0,
                WebEventDto.of(new AgentEvent.UsageUpdated(null, 0, 0, "s1", eventWorkdir))
                        .data()
                        .get("contextInputTokens"));
        assertEquals(
                0,
                WebEventDto.of(new AgentEvent.LlmRequestCompleted(null, null, 1, "s1", eventWorkdir))
                        .data()
                        .get("contextInputTokens"));
    }

    @Test
    void compactedCarriesTheTokenMetricsWhenPresent() {
        // the raw before/after estimates are plumbed for the web frontend to render its own
        // "513k -> 87k (83%)" style feedback
        final var dto = WebEventDto.of(new AgentEvent.Compacted(
                List.of("dedup", "compact-spill"),
                "SHRUNK",
                "s1",
                null,
                null,
                new AgentEvent.Compacted.CompactionTokens(513_000, 87_000),
                eventWorkdir));
        assertEquals(513_000, dto.data().get("tokensBefore"));
        assertEquals(87_000, dto.data().get("tokensAfter"));
    }

    @Test
    void compactionNoticesCarryTheLifecycleMarker() {
        // the compaction lifecycle (core A) surfaces to the browser as a compacting flag on the
        // notice frames: the start notice arms it, the result notices (compacted/gate) clear it.
        final var start = WebEventDto.of(AgentEvent.Notice.compactionStart(
                "Compaction in progress... (\u2248 51234 estimated tokens)", "s1", eventWorkdir));
        assertEquals("notice", start.type());
        assertEquals(true, start.data().get("compacting"));

        final var done =
                WebEventDto.of(AgentEvent.Notice.compacted("Compacted: context 68% -> 41%", "s1", eventWorkdir));
        assertEquals(false, done.data().get("compacting"));

        final var gate = WebEventDto.of(AgentEvent.Notice.compactionGateEscalated(
                "Compaction gate: the context cannot be compacted below the trigger.", "s1", eventWorkdir));
        assertEquals(false, gate.data().get("compacting"), "an escalation must clear the flag");

        // a non-compaction notice never flips the flag (absent, not false)
        final var unrelated = WebEventDto.of(
                new AgentEvent.Notice(AgentEvent.Notice.Level.WARN, "retrying (1/3)", "s1", eventWorkdir));
        assertNull(unrelated.data().get("compacting"));
    }
}
