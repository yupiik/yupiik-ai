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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.llm.client.model.LlmStreamEvent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Hardening coverage for {@link Streams}: the EOF flush of a final unterminated SSE event. */
class StreamsHardeningTest {

    @Test
    void finalEventWithoutTrailingBlankLineIsDispatchedOnEof() throws Exception {
        // some backends close the stream right after the last 'data:' line, without the
        // spec's terminating blank line: the buffered terminal event must not be lost
        final var chunks =
                List.of(List.of(ByteBuffer.wrap(("event: response.completed\ndata: {\"usage\":{\"total_tokens\":12}}")
                        .getBytes(StandardCharsets.UTF_8))));
        final var events = collect(chunks);

        assertEquals(
                List.of(new LlmStreamEvent.TextDelta("{\"usage\":{\"total_tokens\":12}}")),
                events.stream()
                        .filter(e -> e instanceof LlmStreamEvent.TextDelta)
                        .toList(),
                "the final event payload must be dispatched on EOF, got: " + events);
        assertTrue(
                events.stream().anyMatch(e -> e instanceof LlmStreamEvent.End),
                "the terminal End event must still be emitted, got: " + events);
    }

    private static List<LlmStreamEvent> collect(final List<List<ByteBuffer>> chunks) throws InterruptedException {
        final var events = new CopyOnWriteArrayList<LlmStreamEvent>();
        final var done = new CountDownLatch(1);
        final SubmissionPublisher<List<ByteBuffer>> source = new SubmissionPublisher<>();
        Streams.toEvents(
                        source,
                        () -> false,
                        event -> event.data() == null ? List.of() : List.of(new LlmStreamEvent.TextDelta(event.data())),
                        () -> new LlmStreamEvent.End("stop", null))
                .subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(final Flow.Subscription subscription) {
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(final LlmStreamEvent item) {
                        events.add(item);
                    }

                    @Override
                    public void onError(final Throwable throwable) {
                        done.countDown();
                    }

                    @Override
                    public void onComplete() {
                        done.countDown();
                    }
                });
        chunks.forEach(source::submit);
        source.close();
        if (!done.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("stream did not complete in time, got: " + events);
        }
        return events;
    }
}
