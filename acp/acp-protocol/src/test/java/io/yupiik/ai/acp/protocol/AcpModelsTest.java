/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package io.yupiik.ai.acp.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;

@FusionSupport
class AcpModelsTest {
    @Test
    void closedSetsKeepTheirWireNames(@Fusion final JsonMapper mapper) {
        for (final var value : StopReason.values()) {
            assertEquals('"' + value.name() + '"', mapper.toString(value));
            assertEquals(value, mapper.fromString(StopReason.class, mapper.toString(value)));
        }
        for (final var value : PermissionOptionKind.values()) {
            assertEquals(value, mapper.fromString(PermissionOptionKind.class, mapper.toString(value)));
        }
        for (final var value : ToolKind.values()) {
            assertEquals(value, mapper.fromString(ToolKind.class, mapper.toString(value)));
        }
        for (final var value : ToolStatus.values()) {
            assertEquals(value, mapper.fromString(ToolStatus.class, mapper.toString(value)));
        }
    }

    @Test
    void convenienceFactoriesPopulateTheWireFields() {
        assertEquals(new RpcError(-32600, "Invalid request", null), RpcError.of(-32600, "Invalid request"));
        final var fs = new Fs(true, false);
        assertEquals(new ClientCapabilities(fs, true, null), ClientCapabilities.of(fs, true));
        assertEquals(
                new McpServer(null, "tools", "server", null, null, null, null),
                McpServer.of(null, "tools", "server", null, null));
        assertEquals(
                new Content("resource_link", null, "file:///reference", "reference", null, null, "text/plain", null),
                Content.of("resource_link", null, "file:///reference", "reference", null, null, "text/plain"));
        assertEquals(new Content("text", "hello", null, null, null, null, null, null), Content.text("hello"));
    }

    @Test
    void generatedEnvelopeCodecPreservesNullPresence(@Fusion final JsonMapper mapper) {
        final var request = mapper.fromString(
                Envelope.class, "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":null,\"vendor/optional\":null}");
        assertTrue(request.hasId());
        assertNull(request.id());
        final var decodedRequest = mapper.fromString(Envelope.class, mapper.toString(request));
        assertEquals(request, decodedRequest);
        assertTrue(decodedRequest.fields().containsKey("vendor/optional"));
        final var notification =
                mapper.fromString(Envelope.class, "{\"jsonrpc\":\"2.0\",\"method\":\"session/cancel\"}");
        assertFalse(
                mapper.fromString(Envelope.class, mapper.toString(notification)).hasId());
        final var response = Envelope.of("2.0", null, null, null, null, null);
        final var json = mapper.toString(response);
        assertTrue(json.contains("\"id\":null"), json);
        assertTrue(json.contains("\"result\":null"), json);
        assertFalse(json.contains("\"error\""), json);
        assertEquals(response, mapper.fromString(Envelope.class, json));
    }

    @Test
    void generatedCodecsWorkWithoutAgentClasses(@Fusion final JsonMapper mapper) {
        final var initialize = new Initialize(1, Map.of("terminal", true), null);
        final var envelope = Envelope.of("2.0", 9007199254740993L, "initialize", initialize, null, null);
        final var json = mapper.toString(envelope);
        final var decoded = mapper.fromString(Envelope.class, json);
        assertEquals("9007199254740993", decoded.id().toString());
        assertEquals(initialize, mapper.fromString(Initialize.class, mapper.toString(decoded.params())));
        assertFalse(json.contains("\"result\""));
        assertFalse(json.contains("\"error\""));
    }
}
