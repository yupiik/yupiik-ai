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
package io.yupiik.ia.agent.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.session.InMemorySessionStore;
import io.yupiik.ia.agent.core.session.TestSessionStores;
import io.yupiik.llm.client.LlmHttpFailureException;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolCallPairingRepairTest {
    private final InMemorySessionStore store = TestSessionStores.ephemeral();
    private final ToolCallPairingRepair repair = new ToolCallPairingRepair(store, Clock.systemUTC());

    @Test
    void repairsDanglingAssistantToolCallWithMarker() {
        final var messages =
                new ArrayList<>(List.of(Message.user("hi"), Message.assistant("reading", List.of(call("c1", "read")))));
        final var result = repair.repair(messages);
        assertTrue(result.changed());
        assertEquals(1, result.healed());
        assertEquals(0, result.droppedOrphans());
        assertEquals(3, result.messages().size());
        assertEquals("assistant", result.messages().get(1).role());
        assertEquals("tool", result.messages().get(2).role());
        assertEquals("c1", result.messages().get(2).toolCallId());
        assertTrue(String.valueOf(result.messages().get(2).content())
                .startsWith(ToolCallPairingRepair.TOOL_RESULT_LOST_MARKER));
        assertEquals(Boolean.TRUE, result.messages().get(2).others().get("is_error"));
    }

    @Test
    void dropsOrphanToolResult() {
        final var messages = new ArrayList<>(List.of(Message.tool("c1", "orphan result")));
        final var result = repair.repair(messages);
        assertTrue(result.changed());
        assertEquals(0, result.healed());
        assertEquals(1, result.droppedOrphans());
        assertTrue(result.messages().isEmpty());
    }

    @Test
    void leavesAlreadyPairedHistoryUntouched() {
        final var messages = new ArrayList<>(List.of(
                Message.user("hi"),
                Message.assistant("reading", List.of(call("c1", "read"))),
                Message.tool("c1", "ok")));
        final var result = repair.repair(messages);
        assertFalse(result.changed());
        assertSame(messages, result.messages());
    }

    @Test
    void repairIsIdempotent() {
        final var messages =
                new ArrayList<>(List.of(Message.user("hi"), Message.assistant("reading", List.of(call("c1", "read")))));
        final var first = repair.repair(messages);
        assertTrue(first.changed());
        final var second = repair.repair(first.messages());
        assertFalse(second.changed());
        assertSame(first.messages(), second.messages());
    }

    @Test
    void repairAndPersistAppendsOnlyNewMarkersOnce() {
        final var messages =
                new ArrayList<>(List.of(Message.user("hi"), Message.assistant("reading", List.of(call("c1", "read")))));
        final var first = repair.repairAndPersist("s1", messages);
        assertTrue(first.changed());
        assertEquals(1, first.healed());
        assertEquals(3, messages.size());
        assertEquals(1, store.size("s1"));
        // the marker is now contiguous: a second pass heals nothing and appends nothing
        final var second = repair.repairAndPersist("s1", messages);
        assertFalse(second.changed());
        assertEquals(0, second.healed());
        assertEquals(1, store.size("s1"));
    }

    @Test
    void repairAndPersistNeverReappendsAnIdThatAlreadyHadAResponse() {
        // stray marker for c2 exists in history; the /btw-style slice heals the block but the marker
        // for c2 must not be persisted again (its session entry already exists -> would re-append)
        final var messages = new ArrayList<>(List.of(
                Message.tool("c2", ToolCallPairingRepair.LOST_RESULT_TEXT, true),
                Message.user("hi"),
                Message.assistant("reading", List.of(call("c1", "read"), call("c2", "read")))));
        assertEquals(0, store.size("s1"));
        repair.repairAndPersist("s1", messages);
        // c1 has no response anywhere -> persisted once; c2 already had a (stray) marker -> not persisted
        assertEquals(1, store.size("s1"));
        // and the healed in-memory list still carries both markers (contiguity for the wire)
        assertEquals(2, messages.stream().filter(m -> "tool".equals(m.role())).count());
    }

    @Test
    void isPairingRejectionRecognizesResponsesMissingToolOutput() {
        // the exact OpenAI Responses signature ("No tool output found for function call ...") must be
        // classified as a pairing rejection so the agent loop and /btw recover it like the Azure wording.
        final var typed = new LlmHttpFailureException(
                400, "{\"error\":{\"message\":\"No tool output found for function call call_1.\"}}", null);
        assertTrue(
                ToolCallPairingRepair.isPairingRejection(typed),
                "a typed 400 carrying the Responses error must be a pairing rejection");
        // message-only fallback (no body): only the "LLM request failed (400)" prefix variant matches
        final var messageOnly =
                new IOException("LLM request failed (400): No tool output found for function call call_1.");
        assertTrue(
                ToolCallPairingRepair.isPairingRejection(messageOnly),
                "the message-only fallback must also recognize the Responses error");
        // a non-400 typed failure with the same wording is NOT a pairing rejection (status is authoritative)
        assertFalse(
                ToolCallPairingRepair.isPairingRejection(
                        new LlmHttpFailureException(429, "No tool output found for function call call_1.", null)),
                "a 429 with the Responses wording is not a pairing rejection");
        // unrelated 400s stay untouched
        assertFalse(
                ToolCallPairingRepair.isPairingRejection(
                        new LlmHttpFailureException(400, "{\"error\":{\"message\":\"invalid api key\"}}", null)),
                "an unrelated 400 is not a pairing rejection");
        assertFalse(ToolCallPairingRepair.isPairingRejection(null), "null is never a pairing rejection");
    }

    private static ToolCall call(final String id, final String name) {
        return new ToolCall(id, "function", new ToolCall.FunctionCall(name, "{}"), 0);
    }
}
