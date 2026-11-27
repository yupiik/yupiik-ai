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

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.mcp.model.Content;
import io.yupiik.fusion.mcp.model.InitializeResponse;
import io.yupiik.fusion.mcp.model.JsonRpcMessage;
import io.yupiik.fusion.mcp.model.ToolResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP wire models and validation before binding the method-dependent payload union. */
public final class McpWire {
    private McpWire() {}

    // Binding directly to Envelope loses field presence and can coerce invalid scalar types.
    // Never retain a parser exception: its message can contain server data or credentials.
    static Map<?, ?> decode(final JsonMapper mapper, final String json) throws IOException {
        final Object value;
        try {
            value = mapper.fromString(Object.class, json);
        } catch (final RuntimeException e) {
            throw new IOException("Invalid MCP JSON");
        }
        final var message = object(value);
        if (!"2.0".equals(message.get("jsonrpc"))) {
            throw new IOException("Invalid MCP JSON-RPC version");
        }
        if (message.containsKey("id")) {
            final var id = message.get("id");
            if (!(id instanceof String)) {
                integer(id); // MCP IDs must not be null, fractional, boolean, or composite.
            }
        }
        if (message.containsKey("method")) {
            if (!(message.get("method") instanceof String)
                    || message.containsKey("result")
                    || message.containsKey("error")
                    || (message.containsKey("params") && !(message.get("params") instanceof Map<?, ?>))) {
                throw new IOException("Invalid MCP request or notification");
            }
        } else {
            if (!message.containsKey("id")
                    || message.containsKey("params")
                    || message.containsKey("result") == message.containsKey("error")) {
                throw new IOException("Invalid MCP response");
            }
            if (message.containsKey("error")) {
                final var error = object(message.get("error"));
                try {
                    integer(error.get("code")).intValueExact();
                } catch (final ArithmeticException e) {
                    throw new IOException("Invalid MCP error code");
                }
                if (!(error.get("message") instanceof String)) {
                    throw new IOException("Invalid MCP error message");
                }
            } else {
                object(message.get("result")); // MCP results are objects, including empty results.
            }
        }
        return message;
    }

    static Long numericId(final Object value) {
        if (!(value instanceof Number number)) {
            return null; // In particular, a string ID never matches one of our numeric IDs.
        }
        try {
            return new BigDecimal(number.toString()).longValueExact();
        } catch (final NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    static JsonRpcMessage envelope(final JsonMapper mapper, final String json) throws IOException {
        decode(mapper, json);
        try {
            return mapper.fromString(JsonRpcMessage.class, json);
        } catch (final RuntimeException e) {
            throw new IOException("Invalid MCP envelope");
        }
    }

    static <T> T payload(final JsonMapper mapper, final Object value, final Class<T> type) throws IOException {
        final var map = object(value);
        if (type == ToolResponse.class) {
            validateResult(map);
        } else if (type == InitializeResponse.class && !(map.get("protocolVersion") instanceof String)) {
            throw new IOException("Invalid MCP initialization result");
        }
        try {
            return mapper.fromString(type, json(mapper, map));
        } catch (final RuntimeException e) {
            throw new IOException("Invalid MCP result payload");
        }
    }

    static String text(final JsonMapper mapper, final ToolResponse result) throws IOException {
        final var text = new StringBuilder();
        boolean hasText = false;
        for (final var part : result.content()) {
            if (Content.Type.text == part.type()) {
                if (hasText) {
                    text.append('\n');
                }
                hasText = true;
                text.append(part.text());
            }
        }
        if (text.isEmpty() && result.structuredContent() != null) {
            text.append(json(mapper, object(result.structuredContent())));
        }
        if (Boolean.TRUE.equals(result.isError())) {
            throw McpFailure.toolError(text.toString());
        }
        return text.toString();
    }

    private static String json(final JsonMapper mapper, final Map<?, ?> value) {
        // A root homogeneous map selects Fusion's scalar codec (BigDecimal is encoded as a string).
        // A map in a collection uses its JSON object codec, preserving arbitrary nested JSON numbers.
        final var array = mapper.toString(List.of(value));
        return array.substring(1, array.length() - 1);
    }

    static List<McpServerTool> tools(final Object value) throws IOException {
        final var map = object(value);
        validateTools(map);
        final var result = new ArrayList<McpServerTool>();
        for (final var item : (List<?>) map.get("tools")) {
            final var tool = object(item);
            final var schema = object(tool.get("inputSchema")).entrySet().stream()
                    .collect(
                            LinkedHashMap<String, Object>::new,
                            (values, entry) -> values.put((String) entry.getKey(), entry.getValue()),
                            Map::putAll);
            result.add(new McpServerTool((String) tool.get("name"), (String) tool.get("description"), schema));
        }
        return List.copyOf(result);
    }

    static String nextCursor(final Object value) throws IOException {
        final var map = object(value);
        validateTools(map);
        return (String) map.get("nextCursor");
    }

    private static void validateTools(final Map<?, ?> map) throws IOException {
        if (!(map.get("tools") instanceof List<?> tools)
                || (map.containsKey("nextCursor") && !(map.get("nextCursor") instanceof String))) {
            throw new IOException("Invalid MCP tool list");
        }
        for (final var value : tools) {
            final var tool = object(value);
            if (!(tool.get("name") instanceof String name)
                    || name.isBlank()
                    || (tool.containsKey("description") && !(tool.get("description") instanceof String))) {
                throw new IOException("Invalid MCP tool descriptor");
            }
            validateSchema(object(tool.get("inputSchema")));
            if (tool.containsKey("outputSchema")) {
                validateSchema(object(tool.get("outputSchema")));
            }
        }
    }

    private static void validateSchema(final Map<?, ?> schema) throws IOException {
        if (!"object".equals(schema.get("type"))) {
            throw new IOException("Invalid MCP tool schema");
        }
        if (schema.containsKey("properties")) {
            for (final var property : object(schema.get("properties")).values()) {
                if (!(property instanceof Map<?, ?>) && !(property instanceof Boolean)) {
                    throw new IOException("Invalid MCP tool property schema");
                }
            }
        }
        if (schema.containsKey("required")) {
            if (!(schema.get("required") instanceof List<?> required)
                    || required.stream().anyMatch(it -> !(it instanceof String))
                    || new HashSet<>(required).size() != required.size()) {
                throw new IOException("Invalid MCP required tool properties");
            }
        }
        if (schema.containsKey("additionalProperties")
                && !(schema.get("additionalProperties") instanceof Map<?, ?>)
                && !(schema.get("additionalProperties") instanceof Boolean)) {
            throw new IOException("Invalid MCP additional properties schema");
        }
    }

    private static void validateResult(final Map<?, ?> map) throws IOException {
        if (!(map.get("content") instanceof List<?> content)
                || (map.containsKey("isError") && !(map.get("isError") instanceof Boolean))
                || (map.containsKey("structuredContent") && !(map.get("structuredContent") instanceof Map<?, ?>))) {
            throw new IOException("Invalid MCP tool result");
        }
        for (final var value : content) {
            final var part = object(value);
            if (!(part.get("type") instanceof String type)) {
                throw new IOException("Invalid MCP tool content");
            }
            switch (type) {
                case "text" -> requireStrings(part, "text");
                case "image", "audio" -> requireStrings(part, "data", "mimeType");
                case "resource_link" -> requireStrings(part, "uri", "name");
                case "resource" -> {
                    final var resource = object(part.get("resource"));
                    requireStrings(resource, "uri");
                    if (!(resource.get("text") instanceof String) && !(resource.get("blob") instanceof String)) {
                        throw new IOException("Invalid MCP resource content");
                    }
                }
                default -> throw new IOException("Invalid MCP content type");
            }
        }
    }

    private static void requireStrings(final Map<?, ?> map, final String... keys) throws IOException {
        for (final var key : keys) {
            if (!(map.get(key) instanceof String)) {
                throw new IOException("Invalid MCP content field");
            }
        }
    }

    private static BigDecimal integer(final Object value) throws IOException {
        if (value instanceof Number number) {
            try {
                final var decimal = new BigDecimal(number.toString());
                // Check integrality without expanding a potentially enormous exponent to a BigInteger.
                if (decimal.stripTrailingZeros().scale() <= 0) {
                    return decimal;
                }
            } catch (final NumberFormatException | ArithmeticException e) {
                // Report only the field class, never its value.
            }
        }
        throw new IOException("Invalid MCP integer");
    }

    private static Map<?, ?> object(final Object value) throws IOException {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new IOException("Invalid MCP object");
    }
}
