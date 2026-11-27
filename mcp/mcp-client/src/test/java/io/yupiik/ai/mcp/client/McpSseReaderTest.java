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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpSseReaderTest {
    @ParameterizedTest
    @ValueSource(strings = {"data: 123456789012345", "data: 12345678\ndata: 12345678\n"})
    void oversizedFramesAreRejectedWhileReading(final String wire) throws Exception {
        try (final var input = new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8))) {
            final var error = Assertions.assertThrows(IOException.class, () -> new McpSseReader(input, 16).next());
            Assertions.assertTrue(error.getMessage().contains("exceeds maximum size"));
        }
    }

    @Test
    void eventLimitResetsForEachFrame() throws Exception {
        try (final var input =
                new ByteArrayInputStream("data: 12345678\n\ndata: 12345678\n\n".getBytes(StandardCharsets.UTF_8))) {
            final var reader = new McpSseReader(input, 16);
            assertEquals("12345678", reader.next().data());
            assertEquals("12345678", reader.next().data());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r", "\r\n"})
    void bomCommentsMultilineAndEmptyEventName(final String newline) throws Exception {
        final var wire = String.join(
                newline,
                "\uFEFF: comment",
                "event: ignored-without-data",
                "",
                "id: opaque",
                "retry: 100",
                "event: custom",
                "event:",
                "data: first",
                ": another comment",
                "data:  second",
                "",
                "data",
                "",
                "");
        try (final var input = new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8))) {
            final var reader = new McpSseReader(input, 1024);
            assertEquals(new McpSseReader.Event("message", "first\n second"), reader.next());
            assertEquals(new McpSseReader.Event("message", ""), reader.next());
            assertNull(reader.next());
        }
    }

    @Test
    void eventNameResetsAndOnlyInitialBomIsRemoved() throws Exception {
        final var wire = "\uFEFFevent: custom\ndata: one\n\n\uFEFFdata: ignored\ndata: two\n\n";
        try (final var input = new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8))) {
            final var reader = new McpSseReader(input, 1024);
            assertEquals(new McpSseReader.Event("custom", "one"), reader.next());
            assertEquals(new McpSseReader.Event("message", "two"), reader.next());
            assertNull(reader.next());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"data: incomplete", "data: incomplete\n", "event: message\r\ndata: incomplete\r\n"})
    void incompleteEofIsNotDispatched(final String wire) throws Exception {
        try (final var input = new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8))) {
            assertNull(new McpSseReader(input, 1024).next());
        }
    }
}
