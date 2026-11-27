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
package io.yupiik.llm.client.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.llm.client.model.LlmMessage;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmToolCall;
import io.yupiik.llm.client.spi.ProviderSpec;
import io.yupiik.llm.client.test.TestJsonMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
class AnthropicCachingTest {
    private static int cacheControls(final String body) {
        return (body.length() - body.replace("cache_control", "").length()) / "cache_control".length();
    }

    private static final Map<String, Object> EPHEMERAL = Map.of("type", "ephemeral");

    @Test
    void defaultsToSystemAndAutomaticConversationCaching(final JsonMapper mapper) {
        final var messages = List.of(LlmMessage.system("stable rules"), LlmMessage.user("hello"));
        final var wire = wire(mapper, Map.of(), request(messages, Map.of()));
        assertEquals(EPHEMERAL, wire.get("cache_control"));
        assertEquals(EPHEMERAL, systemBlock(wire).get("cache_control"));
        assertEquals(List.of(Map.of("role", "user", "content", "hello")), wire.get("messages"));
        assertFalse(wire.containsKey("promptCache"));
    }

    @Test
    void automaticCachingDoesNotRequireASystemMessage(final JsonMapper mapper) {
        final var wire = wire(mapper, Map.of(), request(List.of(LlmMessage.user("hello")), Map.of()));
        assertEquals(EPHEMERAL, wire.get("cache_control"));
        assertFalse(wire.containsKey("system"));
    }

    @Test
    void providerDefaultsAndRequestTtlOverridesAreRespectedWithoutMutation(final JsonMapper mapper) {
        final var providerControl = Map.<String, Object>of("type", "ephemeral", "ttl", "1h");
        final var requestControl = Map.<String, Object>of("type", "ephemeral", "ttl", "5m");
        final var options = Map.<String, Object>of("cache_control", providerControl, "anthropicSplitBlocks", false);
        final var history = List.of(LlmMessage.system("stable"), LlmMessage.user("hello"));
        final var providerWire = wire(mapper, options, request(history, Map.of()));
        assertEquals(providerControl, providerWire.get("cache_control"));
        assertEquals(providerControl, systemBlock(providerWire).get("cache_control"));
        final var extensions = Map.<String, Object>of("cache_control", requestControl);
        final var request = request(history, extensions);
        final var overridden = wire(mapper, options, request);
        assertEquals(requestControl, overridden.get("cache_control"));
        assertEquals(requestControl, systemBlock(overridden).get("cache_control"));
        assertEquals(providerControl, options.get("cache_control"));
        assertEquals(extensions, request.extensions());
        assertFalse(overridden.containsKey("anthropicSplitBlocks"));
    }

    @Test
    void disableWorksForBooleanAndStringAndStripsExplicitControls(final JsonMapper mapper) {
        final var content = List.of(Map.of("type", "text", "text", "hello", "cache_control", EPHEMERAL));
        final var history =
                List.of(LlmMessage.system("stable"), new LlmMessage("user", content, null, null, null, Map.of()));
        for (final var disabled : List.of(false, "false")) {
            final var extensions = Map.of("promptCache", disabled, "cache_control", EPHEMERAL);
            final var wire = wire(mapper, Map.of("promptCache", true), request(history, extensions));
            assertEquals("stable", wire.get("system"));
            assertFalse(mapper.toString(wire).contains("cache_control"));
            assertFalse(wire.containsKey("promptCache"));
        }
        final var disabledByProvider = wire(mapper, Map.of("promptCache", "false"), request(history, Map.of()));
        assertFalse(mapper.toString(disabledByProvider).contains("cache_control"));
        assertEquals(EPHEMERAL, content.getFirst().get("cache_control"));
        final var enabledByRequest =
                wire(mapper, Map.of("promptCache", false), request(history, Map.of("promptCache", true)));
        assertEquals(EPHEMERAL, systemBlock(enabledByRequest).get("cache_control"));
    }

    @Test
    void systemOnlyAndNullControlSuppressAutomaticCaching(final JsonMapper mapper) {
        final var history = List.of(LlmMessage.system("stable"), LlmMessage.user("hello"));
        final var systemOnly = wire(mapper, Map.of("promptCache", "system"), request(history, Map.of()));
        assertFalse(systemOnly.containsKey("cache_control"));
        assertEquals(EPHEMERAL, systemBlock(systemOnly).get("cache_control"));
        final var extensions = new HashMap<String, Object>();
        extensions.put("cache_control", null);
        final var explicitNull = wire(mapper, Map.of("cache_control", EPHEMERAL), request(history, extensions));
        assertFalse(explicitNull.containsKey("cache_control"));
        assertEquals(EPHEMERAL, systemBlock(explicitNull).get("cache_control"));
    }

    @Test
    void generatedBreakpointsStayWithinFourIncludingLegacySplits(final JsonMapper mapper) {
        final var history = new ArrayList<LlmMessage>();
        history.add(LlmMessage.system("stable"));
        for (var i = 0; i < 10; i++) {
            history.add(new LlmMessage(
                    "user",
                    "step " + i + "\n\n<session-state>changing</session-state>",
                    null,
                    null,
                    null,
                    Map.of("volatileTail", true)));
        }
        final var wire = wire(mapper, Map.of(), request(history, Map.of("anthropicSplitBlocks", true)));
        assertEquals(4, cacheControls(mapper.toString(wire)));
        assertEquals(EPHEMERAL, wire.get("cache_control"));
    }

    @Test
    void callerBreakpointsTakePriorityOverGeneratedOnes(final JsonMapper mapper) {
        final var blocks = new ArrayList<Map<String, Object>>();
        for (var i = 0; i < 4; i++) {
            blocks.add(Map.of("type", "text", "text", "block " + i, "cache_control", EPHEMERAL));
        }
        final var history =
                List.of(LlmMessage.system("stable"), new LlmMessage("user", blocks, null, null, null, null));
        final var wire = wire(mapper, Map.of(), request(history, Map.of()));
        assertFalse(wire.containsKey("cache_control"));
        assertEquals("stable", wire.get("system"));
        assertEquals(4, cacheControls(mapper.toString(wire)));
        assertEquals(blocks, ((Map<?, ?>) ((List<?>) wire.get("messages")).getFirst()).get("content"));
    }

    @Test
    void explicitLongLivedBlockDoesNotAcquireAShortLivedSystemPrefix(final JsonMapper mapper) {
        final var longControl = Map.of("type", "ephemeral", "ttl", "1h");
        final var history = List.of(
                LlmMessage.system("stable"),
                new LlmMessage(
                        "user",
                        "prefix\n\n<session-state>state</session-state>",
                        null,
                        null,
                        null,
                        Map.of("volatileTail", true)),
                new LlmMessage(
                        "user",
                        List.of(Map.of("type", "text", "text", "hello", "cache_control", longControl)),
                        null,
                        null,
                        null,
                        null));
        final var wire = wire(mapper, Map.of(), request(history, Map.of("anthropicSplitBlocks", true)));
        assertEquals(longControl, systemBlock(wire).get("cache_control"));
        assertFalse(wire.containsKey("cache_control"));
        assertEquals(
                2,
                cacheControls(mapper.toString(wire)),
                "legacy splitting must not insert short-lived controls before the explicit 1h block");
    }

    @Test
    void explicitAutomaticControlTakesPriorityOverGeneratedSystemBreakpoint(final JsonMapper mapper) {
        final var blocks = new ArrayList<Map<String, Object>>();
        for (var i = 0; i < 3; i++) {
            blocks.add(Map.of("type", "text", "text", "block " + i, "cache_control", EPHEMERAL));
        }
        blocks.add(Map.of("type", "text", "text", "unmarked endpoint"));
        final var history =
                List.of(LlmMessage.system("stable"), new LlmMessage("user", blocks, null, null, null, null));
        final var wire = wire(mapper, Map.of(), request(history, Map.of("cache_control", EPHEMERAL)));
        assertEquals(EPHEMERAL, wire.get("cache_control"));
        assertEquals("stable", wire.get("system"));
        assertEquals(4, cacheControls(mapper.toString(wire)));
    }

    @Test
    void toolHistoryAndStablePrefixAreUnchangedAcrossConsecutiveRequests(final JsonMapper mapper) {
        final var call = new LlmToolCall("call-1", "function", new LlmToolCall.FunctionCall("read", "{}"), 0);
        final var history = new ArrayList<>(List.of(
                LlmMessage.system("stable"),
                LlmMessage.user("inspect"),
                new LlmMessage("assistant", "reading", null, List.of(call), null, null),
                new LlmMessage("tool", "file data", null, null, "call-1", Map.of("is_error", true))));
        final var first = wire(mapper, Map.of(), request(history, Map.of()));
        history.add(new LlmMessage(
                "user", "<session-state>updated</session-state>", null, null, null, Map.of("volatileTail", true)));
        final var second = wire(mapper, Map.of(), request(history, Map.of()));
        final var firstMessages = (List<?>) first.get("messages");
        final var secondMessages = (List<?>) second.get("messages");
        assertEquals(first.get("system"), second.get("system"));
        assertEquals(firstMessages, secondMessages.subList(0, firstMessages.size()));
        assertEquals(EPHEMERAL, first.get("cache_control"));
        assertEquals(EPHEMERAL, second.get("cache_control"));
        final var assistantBlocks = (List<?>) ((Map<?, ?>) firstMessages.get(1)).get("content");
        assertEquals("tool_use", ((Map<?, ?>) assistantBlocks.get(1)).get("type"));
        final var resultBlocks = (List<?>) ((Map<?, ?>) firstMessages.get(2)).get("content");
        final var result = (Map<?, ?>) resultBlocks.getFirst();
        assertEquals("tool_result", result.get("type"));
        assertEquals("call-1", result.get("tool_use_id"));
        assertEquals(true, result.get("is_error"));
        assertTrue(mapper.toString(second).contains("<session-state>updated</session-state>"));
    }

    private static LlmRequest request(final List<LlmMessage> messages, final Map<String, Object> extensions) {
        return new LlmRequest("m", messages, null, null, 64, null, null, null, null, null, false, extensions);
    }

    private static Map<?, ?> wire(
            final JsonMapper mapper, final Map<String, Object> options, final LlmRequest request) {
        final var dialect = new AnthropicDialect(
                mapper, null, new ProviderSpec("https://example.invalid/v1/messages", null, null, options, null));
        return mapper.fromBytes(Map.class, dialect.requestPayload(request));
    }

    private static Map<?, ?> systemBlock(final Map<?, ?> wire) {
        return (Map<?, ?>) ((List<?>) wire.get("system")).getFirst();
    }
}
