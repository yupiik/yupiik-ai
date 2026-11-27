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
package io.yupiik.fusion.a2a.protocol;

import static io.yupiik.fusion.a2a.test.Awaits.await;
import static io.yupiik.fusion.a2a.test.Bodies.contains;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.yupiik.fusion.a2a.test.FakeRequest;
import io.yupiik.fusion.a2a.test.Responses;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jsonrpc.JsonRpcHandler;
import io.yupiik.fusion.jsonrpc.JsonRpcRegistry;
import io.yupiik.fusion.jsonrpc.api.PartialResponse;
import io.yupiik.fusion.jsonrpc.impl.JsonRpcMethod;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The {@code POST /a2a} transport driven without a server, so the JSON-RPC methods behind it can be anything the Fusion
 * stack is able to produce.
 * <p>
 * {@code A2AEndpointTest} covers the malformed payloads over real HTTP. What only this level reaches is what the eleven
 * A2A operations happen not to be: a notification, a method setting response headers, a result which cannot be
 * serialized - and a request whose body cannot be read at all.
 */
@FusionSupport
class A2AEndpointTransportTest {
    @Test
    void aBodyWhichCannotEvenBeReachedIsAParseError(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        final var endpoint = endpoint(container, jsons);

        final var response = await(endpoint.handle(FakeRequest.of("POST", "/a2a")
                .header("a2a-version", "1.0")
                .failingBody(new IllegalStateException("the connection dropped"))));

        assertEquals(200, response.status());
        contains(Responses.body(response), "\"code\":" + A2AErrors.PARSE_ERROR, "the connection dropped");
    }

    @Test
    void aNotificationIsAccepted(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        final var endpoint = endpoint(container, jsons, new JsonRpcMethod() {
            @Override
            public String name() {
                return "Notify";
            }

            @Override
            public boolean isNotification() {
                return true;
            }

            @Override
            public CompletionStage<?> invoke(final Context context) {
                return completedFuture(null);
            }
        });

        final var response = await(endpoint.handle(post("Notify")));

        // there is nothing to answer, and an empty 200 body would not be a JSON-RPC response
        assertEquals(202, response.status());
        assertEquals("", Responses.body(response));
    }

    @Test
    void theResponseHeadersAMethodSetAreCopied(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        final var endpoint = endpoint(
                container,
                jsons,
                method(
                        "WithHeaders",
                        params -> new PartialResponse<>(Map.of("answer", 42))
                                .setHttpResponseHeaders(Map.of(
                                        "x-trace-id", "abcd",
                                        "x-answer", "42"))));

        final var response = await(endpoint.handle(post("WithHeaders")));

        assertEquals(200, response.status());
        assertEquals("abcd", Responses.header(response, "x-trace-id"));
        assertEquals("42", Responses.header(response, "x-answer"));
        contains(Responses.body(response), "\"answer\":42");
    }

    @Test
    void aResultWhichCannotBeSerializedIsAnInternalError(
            final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        // the mapper only knows the @JsonModel types, so an arbitrary object fails on the way out - after the method
        // ran
        final var endpoint = endpoint(container, jsons, method("Unserializable", params -> new Object()));

        final var response = await(endpoint.handle(post("Unserializable")));

        assertEquals(200, response.status());
        contains(Responses.body(response), "\"code\":" + A2AErrors.INTERNAL_ERROR);
    }

    @Test
    void aStreamingMethodWhichOpenedNoStreamAnswersItsResult(
            final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        // a streaming method is expected to set the stream attribute; one which answers a plain result instead must not
        // be reported as a failed subscription, there is nothing wrong with the payload
        final var endpoint =
                endpoint(container, jsons, method(A2AProtocol.SEND_STREAMING_MESSAGE, params -> Map.of("a", 1)));

        final var response = await(endpoint.handle(post(A2AProtocol.SEND_STREAMING_MESSAGE)));

        assertEquals(200, response.status());
        assertEquals("application/json;charset=utf-8", Responses.header(response, "content-type"));
        contains(Responses.body(response), "\"result\"");
    }

    @Test
    void aStreamIsAnsweredWithoutTouchingASynchronousRequest(
            final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        final var stream = A2AStream.plain(new SseBus(() -> {}), jsons);
        final var endpoint = endpoint(container, jsons, new JsonRpcMethod() {
            @Override
            public String name() {
                return A2AProtocol.SUBSCRIBE_TO_TASK;
            }

            @Override
            public CompletionStage<?> invoke(final Context context) {
                context.request().setAttribute(A2AProtocol.STREAM_ATTRIBUTE, stream);
                return completedFuture(null);
            }
        });
        // a servlet request which is not in asynchronous mode: there is no async context to disable the timeout on
        final var request = post(A2AProtocol.SUBSCRIBE_TO_TASK);

        final var response = await(endpoint.handle(request));

        assertEquals(200, response.status());
        assertEquals("text/event-stream;charset=utf-8", Responses.header(response, "content-type"));
    }

    @Test
    void aStreamingMethodWhichAnswersNothingAtAll(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        // no stream and no result either: there is nothing to frame as a failed subscription, and nothing to send
        final var endpoint = endpoint(container, jsons, new JsonRpcMethod() {
            @Override
            public String name() {
                return A2AProtocol.SUBSCRIBE_TO_TASK;
            }

            @Override
            public boolean isNotification() {
                return true;
            }

            @Override
            public CompletionStage<?> invoke(final Context context) {
                return completedFuture(null);
            }
        });

        final var response = await(endpoint.handle(post(A2AProtocol.SUBSCRIBE_TO_TASK)));

        assertEquals(202, response.status());
    }

    @Test
    void aRequestWithNothingBehindItStillGetsItsStream(
            final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        final var stream = A2AStream.plain(new SseBus(() -> {}), jsons);

        final var response = A2AEndpoint.sse(FakeRequest.of("POST", "/a2a"), stream);

        assertEquals(200, response.status());
        assertEquals("no-cache", Responses.header(response, "cache-control"));
        assertEquals("keep-alive", Responses.header(response, "connection"));
    }

    @Test
    void theRequestIdentifierIsExposedToTheStreamFraming(
            final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        final var seen = new Object[1];
        final var endpoint = endpoint(container, jsons, new JsonRpcMethod() {
            @Override
            public String name() {
                return "ReadId";
            }

            @Override
            public CompletionStage<?> invoke(final Context context) {
                seen[0] = context.request().attribute(A2AProtocol.REQUEST_ID_ATTRIBUTE, Object.class);
                return completedFuture(Map.of("ok", true));
            }
        });

        await(endpoint.handle(post("ReadId")));

        // this is what lets a streaming method repeat the envelope of the originating request on every event - it is
        // the
        // identifier as the mapper read it, so a number arrives as one and is echoed unchanged
        assertEquals(new BigDecimal("1"), seen[0]);
    }

    @Test
    void aBatchWithoutAnyStreamingMethodIsExecuted(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        final var endpoint = endpoint(container, jsons, method("First", params -> Map.of("a", 1)));
        final var request =
                FakeRequest.of("POST", "/a2a").header("a2a-version", "1.0").body("""
                        [{"jsonrpc":"2.0","id":1,"method":"First","params":{}}]""");

        final var response = await(endpoint.handle(request));

        assertEquals(200, response.status());
        contains(Responses.body(response), "\"id\":1", "\"result\"");
        assertNull(
                request.attribute(A2AProtocol.REQUEST_ID_ATTRIBUTE, Object.class),
                "a batch carries no single identifier to frame a stream with");
    }

    // --- fixtures -----------------------------------------------------------------------------------------------

    private static A2AEndpoint endpoint(
            final RuntimeContainer container, final JsonMapper jsons, final JsonRpcMethod... methods) {
        return new A2AEndpoint(new JsonRpcHandler(container, jsons, new JsonRpcRegistry(List.of(methods))), jsons);
    }

    private static JsonRpcMethod method(final String name, final Function<Object, Object> implementation) {
        return new JsonRpcMethod() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public CompletionStage<?> invoke(final Context context) {
                return completedFuture(implementation.apply(context.params()));
            }
        };
    }

    private static FakeRequest post(final String method) {
        return FakeRequest.of("POST", "/a2a")
                .header("a2a-version", "1.0")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":{}}");
    }
}
