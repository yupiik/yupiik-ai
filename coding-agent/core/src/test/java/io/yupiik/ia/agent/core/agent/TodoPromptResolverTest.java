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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.configuration.AgentMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class TodoPromptResolverTest {

    private final TodoPromptResolver resolver = new TodoPromptResolver();

    @Test
    void autoDefaultsToOpenAiBlockWhenFamilyUnknown() {
        final var resolved = resolver.resolve("auto", "https://mystery.example/v1");
        assertTrue(resolved.contains("skip a new list for a straightforward single-step task"));
        assertTrue(resolved.contains("Combine todo updates with substantive tool calls"));
        assertFalse(resolved.contains("VERY frequently"));
    }

    @Test
    void autoThatIsAnthropicFamilyPicksAnthropicBlock() {
        final var resolved = resolver.resolve("auto", "https://api.anthropic.com/v1/messages");
        assertTrue(resolved.contains("VERY frequently"));
    }

    @Test
    void autoThatIsGeminiFamilyPicksGeminiBlock() {
        final var resolved = resolver.resolve("auto", "https://generativelanguage.googleapis.com/v1");
        assertTrue(resolved.contains("structured task list"));
    }

    @Test
    void everyFamilyBlockTellsToConsultTheListAtTurnStart() {
        assertTrue(
                resolver.resolve("auto", "https://mystery.example/v1")
                        .contains("Consult the todo list in the <session-state> block at the"),
                "the OpenAI block must order consulting the list at turn start");
        assertTrue(
                resolver.resolve("auto", "https://api.anthropic.com/v1/messages")
                        .contains("Consult the todo list in the <session-state> block at the"),
                "the Anthropic block must order consulting the list at turn start");
        assertTrue(
                resolver.resolve("auto", "https://generativelanguage.googleapis.com/v1")
                        .contains("Consult the todo list in the <session-state> block at the"),
                "the Gemini block must order consulting the list at turn start");
        for (final var url : List.of(
                "https://mystery.example/v1",
                "https://api.anthropic.com/v1/messages",
                "https://generativelanguage.googleapis.com/v1")) {
            assertTrue(
                    resolver.resolve("auto", url).contains("pending items are your queue."),
                    "every family block must close on the queue directive");
        }
    }

    @Test
    void nullModeDefaultsToAuto() {
        final var resolved = resolver.resolve(null, "https://mystery.example/v1");
        assertTrue(resolved.contains("skip a new list for a straightforward single-step task"));
    }

    @Test
    void autoWithNullBaseUrlFallsBackToOpenAiBlock() {
        final var resolved = resolver.resolve("auto", null);
        assertTrue(resolved.contains("skip a new list for a straightforward single-step task"));
    }

    @Test
    void verbatimValueIsReturnedAsIs() {
        assertEquals(
                "custom guidance", resolver.resolve("custom guidance", "https://api.openai.com/v1/chat/completions"));
    }

    @Test
    void literalFalseDisablesGuidance() {
        assertEquals("", resolver.resolve("false", "https://api.anthropic.com"));
    }

    @Test
    void blankValueDisablesGuidance() {
        assertEquals("", resolver.resolve("", "https://api.anthropic.com"));
        assertEquals("", resolver.resolve("   ", "https://api.anthropic.com"));
    }

    @Test
    void planModePicksThePlanVariantForEveryFamily() {
        assertTrue(
                resolver.resolve("auto", "https://mystery.example/v1", AgentMode.PLAN)
                        .contains("Task Management (plan mode)"),
                "the unknown family must fall back to the OpenAI plan variant");
        assertFalse(
                resolver.resolve("auto", "https://mystery.example/v1", AgentMode.PLAN)
                        .contains("pending items are your queue."),
                "the plan variant must not use the build-mode queue directive");
        assertTrue(
                resolver.resolve("auto", "https://api.anthropic.com/v1/messages", AgentMode.PLAN)
                        .contains("Task Management (plan mode)"),
                "the Anthropic family must pick its plan variant");
        assertTrue(
                resolver.resolve("auto", "https://generativelanguage.googleapis.com/v1", AgentMode.PLAN)
                        .contains("Task Management (plan mode)"),
                "the Gemini family must pick its plan variant");
    }

    @Test
    void planVariantOrdersSyncOnDiscussionChanges() {
        final var resolved = resolver.resolve("auto", "https://mystery.example/v1", AgentMode.PLAN);
        assertTrue(
                resolved.contains("update the list in the same"),
                "the plan variant must order re-syncing the list when the discussion changes the plan: " + resolved);
        assertTrue(
                resolved.contains("it must match the plan you just agreed on"),
                "the plan variant must close on the plan-sync directive: " + resolved);
        assertTrue(
                resolved.contains("Consult the todo list in the <session-state> block at the start of each turn"),
                "the plan variant must also order consulting the list at turn start: " + resolved);
        assertTrue(
                resolved.contains("'pending' - statuses only advance in build mode"),
                "the plan variant must keep the all-pending invariant: " + resolved);
    }

    @Test
    void planVariantKeepsCompletedAndCancelledHistory() {
        final var resolved = resolver.resolve("auto", "https://mystery.example/v1", AgentMode.PLAN);
        assertTrue(
                resolved.contains(
                        "Completed and cancelled items recorded during build sessions are the progress history: keep them, never drop them"),
                "the plan variant must order keeping the progress history: " + resolved);
    }

    @Test
    void buildBlocksAllowParallelInProgress() {
        for (final var url : List.of("https://mystery.example/v1", "https://generativelanguage.googleapis.com/v1")) {
            final var block = resolver.resolve("auto", url, AgentMode.BUILD);
            assertFalse(
                    block.contains("exactly one"),
                    "the build guidance must not carry the single-in_progress rule: " + block);
            assertFalse(
                    block.contains("single in_progress"),
                    "the build guidance must not carry the single-in_progress rule: " + block);
            assertTrue(
                    block.contains("in parallel"),
                    "the build guidance must allow working several todos in parallel: " + block);
        }
    }

    @Test
    void twoArgResolveDefaultsToBuildModeVariant() {
        final var openai = resolver.resolve("auto", "https://mystery.example/v1");
        assertTrue(openai.contains("Combine todo updates with substantive tool calls"));
        assertTrue(openai.contains("in parallel"), "openai build block must allow parallel in_progress: " + openai);
        assertEquals(
                resolver.resolve("auto", "https://mystery.example/v1"),
                resolver.resolve("auto", "https://mystery.example/v1", AgentMode.BUILD),
                "the two-arg overload must resolve the build variant");
        assertEquals(
                resolver.resolve("custom guidance", "https://api.openai.com/v1"),
                resolver.resolve("custom guidance", "https://api.openai.com/v1", AgentMode.PLAN),
                "verbatim guidance must stay mode-agnostic");
        assertEquals(
                resolver.resolve("false", "https://api.anthropic.com"),
                resolver.resolve("false", "https://api.anthropic.com", AgentMode.PLAN),
                "disabled guidance must stay mode-agnostic");
    }
}
