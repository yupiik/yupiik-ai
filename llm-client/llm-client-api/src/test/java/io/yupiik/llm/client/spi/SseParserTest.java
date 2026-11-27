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
package io.yupiik.llm.client.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class SseParserTest {

    @Test
    void dispatchSingleEventOnBlankLine() {
        final var parser = new SseParser();
        assertEquals(List.of(), parser.feed("event: response.output_text.delta"));
        assertEquals(List.of(), parser.feed("data: {\"delta\":\"hi\"}"));
        final var events = parser.feed("");
        assertEquals(1, events.size());
        final var event = events.get(0);
        assertEquals("response.output_text.delta", event.event());
        assertEquals("{\"delta\":\"hi\"}", event.data());
    }

    @Test
    void ignoresCommentsAndAccumulatesMultiLineData() {
        final var parser = new SseParser();
        assertEquals(List.of(), parser.feed(": keep-alive"));
        assertEquals(List.of(), parser.feed("event: message"));
        assertEquals(List.of(), parser.feed("data: first"));
        assertEquals(List.of(), parser.feed("data: second"));
        final var events = parser.feed("");
        assertEquals(1, events.size());
        assertEquals("first\nsecond", events.get(0).data());
        assertEquals("message", events.get(0).event());
    }

    @Test
    void stripsLeadingSpaceAfterColon() {
        final var parser = new SseParser();
        parser.feed("data:  value");
        final var events = parser.feed("");
        assertEquals(" value", events.get(0).data());
    }

    @Test
    void missingDataLineIsDropped() {
        final var parser = new SseParser();
        parser.feed("event: noop");
        final var events = parser.feed("");
        assertEquals(List.of(), events);
    }
}
