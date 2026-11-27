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

/** Hardening coverage for {@link SseParser}: bare {@code data} lines and EOF flushing. */
class SseParserHardeningTest {

    @Test
    void bareDataLineIsAnEmptyPayload() {
        // per the SSE spec a bare field name without a colon carries an empty payload: "data"
        // must behave exactly like "data:" (append a separator, no value)
        final var parser = new SseParser();
        parser.feed("event: message");
        parser.feed("data: first");
        parser.feed("data");
        final var events = parser.feed("");
        assertEquals(1, events.size());
        assertEquals("message", events.get(0).event());
        assertEquals("first\n", events.get(0).data());
    }

    @Test
    void eofFlushesThePendingEvent() {
        // a stream that ends right after the last data line (no trailing blank line) must still
        // dispatch the buffered event on EOF instead of silently dropping a terminal event
        final var parser = new SseParser();
        parser.feed("event: response.completed");
        parser.feed("data: {\"usage\":{\"total_tokens\":12}}");
        final var events = parser.feed(null);
        assertEquals(1, events.size());
        assertEquals("response.completed", events.get(0).event());
        assertEquals("{\"usage\":{\"total_tokens\":12}}", events.get(0).data());
    }

    @Test
    void eofWithoutBufferedDataIsANoOp() {
        final var parser = new SseParser();
        assertEquals(List.of(), parser.feed(null));
        parser.feed("event: noop");
        assertEquals(List.of(), parser.feed(null));
    }
}
