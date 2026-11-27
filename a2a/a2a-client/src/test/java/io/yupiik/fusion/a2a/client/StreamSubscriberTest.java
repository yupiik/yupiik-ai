/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.fusion.a2a.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.a2a.model.StreamResponse;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

/**
 * How the {@code text/event-stream} lines of an A2A stream become events.
 * <p>
 * Both bindings are consumed by the same code, so the JSON-RPC envelope is unwrapped when there is one - and the lines
 * which are not an event, keep-alive comments included, must not produce one.
 */
@FusionSupport
class StreamSubscriberTest {
    @Test
    void bothFramingsProduceTheSameEvents(@Fusion final JsonMapper jsons) {
        final var events = feed(
                jsons,
                "id: 1",
                "event: message",
                "data: {\"message\":{\"role\":\"ROLE_AGENT\",\"parts\":[{\"text\":\"bare\"}]}}",
                "",
                "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"message\":{\"role\":\"ROLE_AGENT\","
                        + "\"parts\":[{\"text\":\"wrapped\"}]}}}",
                "");

        assertEquals(
                List.of("bare", "wrapped"),
                events.stream().map(it -> it.message().text()).toList());
    }

    @Test
    void keepAliveCommentsAreNotEvents(@Fusion final JsonMapper jsons) {
        final var events = feed(
                jsons,
                ": ping",
                "",
                "data: {\"message\":{\"role\":\"ROLE_AGENT\",\"parts\":[{\"text\":\"hi\"}]}}",
                "",
                ": ping",
                "");

        assertEquals(1, events.size());
        assertEquals("hi", events.get(0).message().text());
    }

    @Test
    void aDataLineWhichIsNotAnObjectIsIgnored(@Fusion final JsonMapper jsons) {
        assertEquals(List.of(), feed(jsons, "data: 42", "", "data: \"a string\"", "", "data: [1, 2]", ""));
    }

    @Test
    void anEnvelopeWithoutAResultIsIgnored(@Fusion final JsonMapper jsons) {
        // a JSON-RPC error travels with a status which is not 200, so the stream never sees one - but an envelope with
        // nothing in it must not produce an empty event either
        assertEquals(List.of(), feed(jsons, "data: {\"jsonrpc\":\"2.0\",\"id\":1}", ""));
    }

    @Test
    void whatArrivesBeforeTheFirstEventIsThePrelude(@Fusion final JsonMapper jsons) {
        final var subscriber = new StreamSubscriber(jsons, event -> {});
        subscriber.onSubscribe(new NoOpSubscription());

        subscriber.onNext("{\"jsonrpc\":\"2.0\",\"id\":1,");
        subscriber.onNext("\"error\":{\"code\":-32004}}");

        // this is the whole body when the agent answered an error instead of a stream, which is what the client reports
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32004}}", subscriber.prelude());
    }

    @Test
    void whatArrivesAfterTheFirstEventIsNotThePrelude(@Fusion final JsonMapper jsons) {
        final var subscriber = new StreamSubscriber(jsons, event -> {});
        subscriber.onSubscribe(new NoOpSubscription());

        subscriber.onNext("data: {\"message\":{\"role\":\"ROLE_AGENT\",\"parts\":[{\"text\":\"hi\"}]}}");
        subscriber.onNext("id: 2");

        assertTrue(subscriber.prelude().isEmpty(), subscriber::prelude);
    }

    @Test
    void theTerminalCallbacksCarryNothing(@Fusion final JsonMapper jsons) {
        final var subscriber = new StreamSubscriber(jsons, event -> {
            throw new IllegalStateException("no event was published");
        });
        subscriber.onSubscribe(new NoOpSubscription());

        // the promise of the request already carries the outcome, so there is nothing to add here
        subscriber.onError(new IllegalStateException("the connection dropped"));
        subscriber.onComplete();

        assertNotNull(subscriber.prelude());
    }

    @Test
    void multilineDataWithOptionalSpacesIsDispatchedAtTheBlankLine(@Fusion final JsonMapper jsons) {
        final var events = new ArrayList<StreamResponse>();
        final var subscriber = new StreamSubscriber(jsons, events::add);
        subscriber.onSubscribe(new NoOpSubscription());
        subscriber.onNext("data:{\"message\":{");
        subscriber.onNext(": keepalive");
        subscriber.onNext("data: \"role\":\"ROLE_AGENT\",");
        subscriber.onNext("data:\"parts\":[{\"text\":\"multiline\"}]}}");
        assertTrue(events.isEmpty());
        subscriber.onNext("");
        subscriber.onNext("");
        assertEquals(
                List.of("multiline"),
                events.stream().map(it -> it.message().text()).toList());
    }

    @Test
    void emptyDataAndUnterminatedEventsAreNotDispatched(@Fusion final JsonMapper jsons) {
        final var events = new ArrayList<StreamResponse>();
        final var subscriber = new StreamSubscriber(jsons, events::add);
        subscriber.onSubscribe(new NoOpSubscription());
        subscriber.onNext("data");
        subscriber.onNext("");
        subscriber.onNext("data:");
        subscriber.onNext("");
        subscriber.onNext("data:{\"message\":{\"role\":\"ROLE_AGENT\",\"parts\":[{\"text\":\"tail\"}]}}");
        subscriber.onComplete();
        assertTrue(events.isEmpty());
    }

    private static List<StreamResponse> feed(final JsonMapper jsons, final String... lines) {
        final var events = new ArrayList<StreamResponse>();
        final var subscriber = new StreamSubscriber(jsons, events::add);
        subscriber.onSubscribe(new NoOpSubscription());
        for (final var line : lines) {
            subscriber.onNext(line);
        }
        return events;
    }

    /**
     * The HTTP client asks for the next line itself, so a test only has to accept the demand.
     */
    private static final class NoOpSubscription implements Flow.Subscription {
        @Override
        public void request(final long n) {
            // no-op, the lines are fed by hand
        }

        @Override
        public void cancel() {
            // no-op
        }
    }
}
