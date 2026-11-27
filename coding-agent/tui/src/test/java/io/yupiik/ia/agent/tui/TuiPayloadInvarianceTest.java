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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tamboui.markdown.MarkdownStyles;
import dev.tamboui.style.Style;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.LlmWire;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.Tool;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.shell.ConversationTranscript;
import io.yupiik.ia.agent.core.shell.ThinkTagSplitter;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.openai.OpenAIResponsesDialect;
import io.yupiik.llm.client.spi.ProviderSpec;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Component-level display isolation guard, not a TurnRunner/AgentLoop integration test.
 * The actual shared Message objects are replayed by the production models; the actual wire
 * translator and Responses serializer project them again after each display-only operation.
 */
@ExtendWith(TestJsonMapper.class)
class TuiPayloadInvarianceTest {
    @TempDir
    private static Path eventWorkdir;

    private static final String SESSION = "payload-invariance-session";
    private static final Method TO_REQUEST = requestTranslator();
    private static JsonMapper mapper;

    @BeforeAll
    static void bindMapper(final JsonMapper jsonMapper) {
        // The existing extension owns the mapper; isolate its one-time container bootstrap
        // from the test body without creating another mapper or lifecycle.
        mapper = jsonMapper;
    }

    @Test
    void displayUpdatesCannotChangeMultiRoundPayloadOrCacheAffinity() throws Exception {
        // A null transport makes accidental network use fail immediately. requestPayload is local.
        final var dialect = new OpenAIResponsesDialect(
                mapper, null, ProviderSpec.of("https://unused.invalid/v1/responses", "unused"));
        final var history = new ArrayList<Message>();
        final var control = new ArrayList<Message>();
        final var transcript = new ConversationTranscript();
        final var model = new TuiTranscriptModel(new ThinkTagSplitter());
        final var renderer = new TranscriptRenderer();
        final var store = new ToolOutputStore(2, 64, 180);
        final var affinity =
                UUID.nameUUIDFromBytes(SESSION.getBytes(StandardCharsets.UTF_8)).toString();
        byte[] previousPayload = null;

        for (var round = 0; round < 3; round++) {
            // Independent fixtures: expected records/maps/lists never alias the display inputs.
            appendRound(history, round);
            appendRound(control, round);
            final var request = request(history, SESSION);
            assertSame(history, request.messages());
            final var expected = wire(request(control, SESSION));
            final var payload = dialect.requestPayload(expected);
            final var historyJson = mapper.toString(history);
            if (previousPayload != null) {
                assertFalse(Arrays.equals(previousPayload, payload), "new LLM rounds must change payload");
            }
            previousPayload = payload;
            assertEquals(affinity, expected.extensions().get("prompt_cache_key"));
            assertEquals(history.size(), expected.messages().size());
            assertFalse(expected.messages().get(1).extensions().containsKey(Message.IDENTITY_KEY));
            assertFalse(expected.messages().get(2).extensions().containsKey("thinking"));
            final var encoded = new String(payload, StandardCharsets.UTF_8);
            assertTrue(encoded.contains("function_call_output"));
            assertTrue(encoded.contains("call-" + round));
            assertTrue(encoded.contains(affinity));

            for (final var display : List.of(transcript, model)) {
                display.clearConversation();
                display.setLabels("Reader", "Display bot");
                display.setLabelResolver((identity, user) -> user ? "Reader" : identity.model());
                assertTrue(display.replay(history).contains("Inspect round " + round));
                unchanged(mapper, historyJson, request, expected, payload, dialect, "replay");

                display.startUserTurn("display-only prompt");
                display.onEvent(new AgentEvent.ThinkingDelta("private display thought", SESSION, eventWorkdir));
                display.onEvent(new AgentEvent.TextDelta("display answer\n", SESSION, eventWorkdir));
                display.onEvent(
                        new AgentEvent.ToolStarted("read_file", "{\"path\":\"README.md\"}", SESSION, eventWorkdir));
                display.onEvent(
                        new AgentEvent.ToolStream("read_file", "display tool fragment\n", SESSION, eventWorkdir));
                display.onEvent(
                        new AgentEvent.ToolFinished("read_file", "display result", true, SESSION, eventWorkdir));
                display.onEvent(new AgentEvent.TurnDone("display final", SESSION, eventWorkdir));
                unchanged(mapper, historyJson, request, expected, payload, dialect, "stream events");

                final var markdown = display.markdown("|", "welcome");
                assertTrue(markdown.contains("display final"));
                assertTrue(ConversationTranscript.regions(markdown).stream()
                        .anyMatch(ConversationTranscript.Region::tool));
                assertTrue(ConversationTranscript.regions(markdown).stream()
                        .anyMatch(ConversationTranscript.Region::thought));
                // Visibility and viewport are deliberately a proxy, not TuiCommand's render pipeline.
                final var expanded = render(renderer, markdown, 72, 20, true, Style.EMPTY);
                final var collapsed = render(renderer, markdown, 24, 4, false, Style.EMPTY.bold());
                assertFalse(expanded.isEmpty());
                assertFalse(collapsed.isEmpty());
                assertNotEquals(expanded, collapsed);
                renderer.theme(null, null, true);
                assertFalse(render(renderer, markdown, 48, 8, true, Style.EMPTY.dim())
                        .isEmpty());
                unchanged(mapper, historyJson, request, expected, payload, dialect, "render/resize/style/visibility");

                final var fullLength = display.transcript().length();
                display.maxHistoryCharacters(80);
                display.trim();
                assertTrue(display.transcript().length() < fullLength);
                unchanged(mapper, historyJson, request, expected, payload, dialect, "display trim");
                display.reset();
                assertEquals("", display.transcript().toString());
                display.maxHistoryCharacters(0);
                display.replay(history);
                unchanged(mapper, historyJson, request, expected, payload, dialect, "reset/replay");
            }

            model.maxViewOffset(100);
            model.anchorRow(7);
            model.userScrolled(true);
            model.onEvent(new AgentEvent.TextDelta("offscreen display tail", SESSION, eventWorkdir));
            assertEquals(7, model.anchorRow());
            unchanged(mapper, historyJson, request, expected, payload, dialect, "scroll");

            final var result = history.stream()
                    .filter(m -> "tool".equals(m.role()))
                    .findFirst()
                    .orElseThrow();
            store.record(1, new ToolOutputStore.Entry("read_file", "{}", (String) result.content(), false, -1));
            store.append(1, (String) result.content());
            assertTrue(store.get(1).output().contains("older output truncated"));
            assertNotEquals(result.content(), store.get(1).output());
            unchanged(mapper, historyJson, request, expected, payload, dialect, "tool append/cap");
            store.record(2, new ToolOutputStore.Entry("read_file", "{}", "second", false, -1));
            store.record(3, new ToolOutputStore.Entry("read_file", "{}", "third", false, -1));
            assertNull(store.get(1));
            assertEquals(2, store.size());
            store.append(2, "x".repeat(128));
            store.append(3, "y".repeat(128));
            assertNull(store.get(2), "total character budget must evict oldest output");
            unchanged(mapper, historyJson, request, expected, payload, dialect, "tool eviction");
            store.clear();
            assertEquals(0, store.size());
            unchanged(mapper, historyJson, request, expected, payload, dialect, "tool clear");

            // Sensitivity controls: neither payload nor affinity comparisons may be constant projections.
            final var otherSession = wire(request(history, SESSION + "-other"));
            assertNotEquals(affinity, otherSession.extensions().get("prompt_cache_key"));
            assertFalse(Arrays.equals(payload, dialect.requestPayload(otherSession)));
        }
    }

    private static List<String> render(
            final TranscriptRenderer renderer,
            final String markdown,
            final int width,
            final int height,
            final boolean details,
            final Style style) {
        final var rows = new ArrayList<String>();
        for (final var region : ConversationTranscript.regions(markdown)) {
            if (!details && (region.thought() || region.tool() || region.log())) {
                continue;
            }
            for (final var line : renderer.renderRegion(region.text(), width, style, MarkdownStyles.DEFAULTS)) {
                rows.add(line.spans().stream().map(span -> span.content()).collect(Collectors.joining()));
            }
        }
        return List.copyOf(rows.subList(Math.max(0, rows.size() - height), rows.size()));
    }

    private static void unchanged(
            final JsonMapper mapper,
            final String historyJson,
            final ChatCompletionRequest request,
            final LlmRequest expected,
            final byte[] payload,
            final OpenAIResponsesDialect dialect,
            final String phase)
            throws Exception {
        assertEquals(historyJson, mapper.toString(request.messages()), phase + ": complete persisted history");
        final var actual = wire(request);
        assertEquals(expected, actual, phase + ": complete neutral request");
        assertEquals(
                expected.extensions().get("prompt_cache_key"),
                actual.extensions().get("prompt_cache_key"),
                phase);
        assertArrayEquals(payload, dialect.requestPayload(actual), phase + ": serialized provider payload");
    }

    private static void appendRound(final List<Message> history, final int round) {
        if (round == 0) {
            history.add(Message.system("Keep the full authoritative history."));
        }
        history.add(Message.withIdentity(Message.user("Inspect round " + round), "provider", "model", "agent"));
        final var metadata = new LinkedHashMap<String, Object>();
        metadata.put("thinking", "persisted private reasoning " + round);
        metadata.put(
                "reasoningItems",
                new ArrayList<>(List.of(new LinkedHashMap<>(Map.of(
                        "type", "reasoning", "id", "reasoning-" + round, "encrypted_content", "opaque-" + round)))));
        final var calls = new ArrayList<>(List.of(new ToolCall(
                "call-" + round, "function", new ToolCall.FunctionCall("read_file", "{\"path\":\"README.md\"}"), 0)));
        history.add(Message.withIdentity(
                new Message("assistant", "Inspecting.\n", null, calls, null, metadata), "provider", "model", "agent"));
        history.add(new Message(
                "tool",
                "authoritative round " + round + "\n" + "full result line\n".repeat(12),
                "read_file",
                null,
                "call-" + round,
                new LinkedHashMap<>(Map.of("is_error", false))));
        history.add(Message.assistant("## Result " + round + "\n\n```text\ncomplete\n```\n", null));
    }

    private static ChatCompletionRequest request(final List<Message> history, final String session) {
        return new ChatCompletionRequest(
                        "model",
                        history,
                        0.2,
                        0.9,
                        true,
                        List.of(Tool.function(
                                "read_file",
                                "Read a file",
                                new LinkedHashMap<>(Map.of(
                                        "type", "object", "properties", Map.of("path", Map.of("type", "string")))))),
                        "auto",
                        512,
                        "low",
                        new LinkedHashMap<>(Map.of("parallel_tool_calls", true)))
                .withSessionId(session);
    }

    private static LlmRequest wire(final ChatCompletionRequest request) throws Exception {
        return (LlmRequest) TO_REQUEST.invoke(null, request, List.of("prompt_cache_key", "x-session-id"));
    }

    private static Method requestTranslator() {
        // Core intentionally keeps this boundary package-private; do not expand production API for this test.
        try {
            final var method = LlmWire.class.getDeclaredMethod("toLlmRequest", ChatCompletionRequest.class, List.class);
            method.setAccessible(true);
            return method;
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
