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
package io.yupiik.ia.agent.acp;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ai.acp.protocol.*;
import io.yupiik.ai.acp.protocol.AcpProtocolModule;
import io.yupiik.ai.mcp.protocol.McpProtocolModule;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.fusion.json.serialization.JsonCodec;
import io.yupiik.fusion.mcp.model.Content;
import io.yupiik.fusion.mcp.model.ToolResponse;
import io.yupiik.ia.agent.core.YcaCoreModule;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class CodecsTest {
    private static List<JsonCodec<?>> acpCodecs() {
        return Stream.concat(
                        new McpProtocolModule().beans(),
                        Stream.concat(new AcpProtocolModule().beans(), new AcpModule().beans()))
                .filter(b -> (b.type() instanceof Class<?> c && JsonCodec.class.isAssignableFrom(c))
                        || (b.type() instanceof ParameterizedType type
                                && type.getRawType() instanceof Class<?> raw
                                && JsonCodec.class.isAssignableFrom(raw)))
                .<JsonCodec<?>>map(b -> (JsonCodec<?>) b.create(null, new ArrayList<>()))
                .toList();
    }

    static JsonMapperImpl acpMapper() {
        return new JsonMapperImpl(acpCodecs(), key -> Optional.empty());
    }

    static JsonMapperImpl mapper() {
        // Production generated module beans, without starting application services/listeners.
        final var all = new ArrayList<JsonCodec<?>>(acpCodecs());
        new YcaCoreModule()
                .beans()
                .filter(b -> (b.type() instanceof Class<?> c && JsonCodec.class.isAssignableFrom(c))
                        || (b.type() instanceof ParameterizedType type
                                && type.getRawType() instanceof Class<?> raw
                                && JsonCodec.class.isAssignableFrom(raw)))
                .forEach(b -> all.add((JsonCodec<?>) b.create(null, new ArrayList<>())));
        return new JsonMapperImpl(all, key -> Optional.empty());
    }

    @Test
    void sharedMcpToolContentUsesGeneratedEnumCodecs() {
        try (final var mapper = mapper()) {
            final var result = mapper.fromString(
                    ToolResponse.class, "{\"content\":[{\"type\":\"text\",\"text\":\"shared\"}],\"isError\":false}");
            assertEquals(Content.Type.text, result.content().getFirst().type());
            assertEquals("shared", result.content().getFirst().text());
        }
    }

    @Test
    void initializeRoundTripsThroughEnvelopeParameters() {
        try (final var mapper = mapper()) {
            final var init = new Initialize(
                    1,
                    Map.of(
                            "fs",
                            Map.of("readTextFile", true),
                            "elicitation",
                            Map.of("form", Map.of()),
                            "session",
                            Map.of("notices", Map.of()),
                            "terminal",
                            false),
                    null);
            final var json = mapper.toString(Envelope.of("2.0", 0, "initialize", init, null, null));
            final var envelope = mapper.fromString(Envelope.class, json);
            final var params = mapper.fromString(Initialize.class, mapper.toString(envelope.params()));
            assertEquals(init, params, json);
        }
    }

    @Test
    void absentOptionalCapabilitiesAndLifecycleParametersDecodeWithoutFabricatingValues() {
        try (final var mapper = mapper()) {
            assertEquals(new Initialize(1, null, null), mapper.fromString(Initialize.class, "{\"protocolVersion\":1}"));
            assertEquals(new ListSessions("/a", null), mapper.fromString(ListSessions.class, "{\"cwd\":\"/a\"}"));
            assertEquals(new ListSessions(null, null), mapper.fromString(ListSessions.class, "{}"));
        }
    }

    @Test
    void typedEnvelopesUnionsAndExactIds() {
        try (final var mapper = mapper()) {
            final var value = mapper.fromString(
                    Envelope.class,
                    "{\"jsonrpc\":\"2.0\",\"id\":9007199254740993,\"method\":\"session/prompt\",\"params\":{\"sessionId\":\"s\",\"prompt\":[{\"type\":\"text\",\"text\":\"hi\"},{\"type\":\"resource_link\",\"uri\":\"file:///tmp/a\",\"name\":\"a\"}]}}");
            assertEquals("9007199254740993", value.id().toString());
            final var prompt = mapper.fromString(Prompt.class, mapper.toString(value.params()));
            assertEquals("resource_link", prompt.prompt().get(1).type());
            assertEquals("file:///tmp/a", prompt.prompt().get(1).uri());
            final var update = Envelope.of(
                    "2.0",
                    null,
                    "session/update",
                    new UpdateParams(
                            "s",
                            new ToolUpdate(
                                    "tool_call_update",
                                    "t",
                                    null,
                                    null,
                                    ToolStatus.completed,
                                    List.of(new ToolContent("content", io.yupiik.ai.acp.protocol.Content.text("ok"))))),
                    null,
                    null);
            final var json = mapper.toString(update);
            assertTrue(json.contains("\"content\":["), json);
            assertFalse(json.contains("\"id\""), json);
            assertFalse(json.contains("\"result\""), json);
            assertEquals(
                    "selected",
                    mapper.fromString(
                                    PermissionResult.class,
                                    "{\"outcome\":{\"outcome\":\"selected\",\"optionId\":\"allow_once\"}}")
                            .outcome()
                            .outcome());
        }
    }
}
