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
package io.yupiik.ai.mcp.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.yupiik.ai.mcp.test.TestJsonMapper;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.mcp.model.Content;
import io.yupiik.fusion.mcp.model.ToolResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ExtendWith(TestJsonMapper.class)
class McpWireTest {
    @Test
    void failedResultsJoinTextAndUseStructuredFallback(final JsonMapper mapper) throws Exception {
        assertFailureDetail(
                mapper,
                "{\"content\":[{\"type\":\"text\",\"text\":\"Rate limited\"},{\"type\":\"text\",\"text\":\"Retry later\"}],\"isError\":true}",
                "Rate limited\nRetry later");
        assertFailureDetail(
                mapper,
                "{\"content\":[],\"structuredContent\":{\"reason\":\"Quota exceeded\"},\"isError\":true}",
                "{\"reason\":\"Quota exceeded\"}");
        assertFailureDetail(mapper, "{\"content\":[],\"isError\":true}", "");
    }

    private void assertFailureDetail(final JsonMapper mapper, final String json, final String expected)
            throws Exception {
        final var decoded = McpWire.decode(mapper, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + json + "}");
        final var result = McpWire.payload(mapper, decoded.get("result"), ToolResponse.class);
        assertEquals(
                expected,
                assertThrows(McpFailure.class, () -> McpWire.text(mapper, result))
                        .detail());
    }

    @Test
    void errorDetailUsesTextThenStructuredContentThenGenericFallback(final JsonMapper mapper) {
        final var text = new ToolResponse(
                null,
                true,
                List.of(Content.text("one"), Content.image("image/png", new byte[0]), Content.text("two")),
                Map.of("reason", "ignored"),
                null);
        assertEquals(
                "one\ntwo",
                assertThrows(McpFailure.class, () -> McpWire.text(mapper, text)).detail());
        final var structured = new ToolResponse(null, true, List.of(), Map.of("reason", "Unavailable"), null);
        assertEquals(
                "{\"reason\":\"Unavailable\"}",
                assertThrows(McpFailure.class, () -> McpWire.text(mapper, structured))
                        .detail());
        final var empty = new ToolResponse(null, true, List.of(), null, null);
        final var failure = assertThrows(McpFailure.class, () -> McpWire.text(mapper, empty));
        assertEquals("MCP tool returned an error", failure.getMessage());
    }

    @Test
    void failedResultRetainsDetailOutsideExceptionMessage(final JsonMapper mapper) throws Exception {
        final var decoded = McpWire.decode(
                mapper,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"Library unavailable\"}]}}");
        final var result = McpWire.payload(mapper, decoded.get("result"), ToolResponse.class);
        final var failure = assertThrows(McpFailure.class, () -> McpWire.text(mapper, result));
        assertEquals("MCP tool returned an error", failure.getMessage());
        assertEquals("Library unavailable", failure.detail());
        assertFalse(failure.toString().contains("Library unavailable"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"answer\":42}",
                "{\"decimal\":0.12345678901234567890123456789}",
                "{\"large\":123456789012345678901234567890}",
                "{\"values\":[42,0.1234567890123456789,\"42\",true,null,{\"nested\":17}]}",
                "{}"
            })
    void structuredResultsPreserveJsonTypesAndNumericPrecision(final String structured, final JsonMapper mapper)
            throws Exception {
        final var decoded = McpWire.decode(
                mapper,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[],\"structuredContent\":" + structured + "}}");
        final var result = McpWire.payload(mapper, decoded.get("result"), ToolResponse.class);
        assertEquals(mapper.fromString(Object.class, structured), result.structuredContent());
        assertEquals(
                mapper.fromString(Object.class, structured),
                mapper.fromString(Object.class, McpWire.text(mapper, result)));
    }
}
