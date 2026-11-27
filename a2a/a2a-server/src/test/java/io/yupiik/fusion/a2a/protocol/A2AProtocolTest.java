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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.a2a.test.FakeRequest;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

/**
 * Version negotiation, i.e. section 3.6 of the specification.
 * <p>
 * The query string is parsed by hand rather than through {@code Request#parameter}, because asking a servlet container
 * for a parameter can make it read the request body - and this runs on {@code POST /a2a} before the JSON-RPC payload.
 * That hand written parser is what these tests pin.
 */
class A2AProtocolTest {
    @Test
    void supportedVersions() {
        assertEquals(List.of("1.0"), A2AProtocol.SUPPORTED_VERSIONS);
        assertTrue(A2AProtocol.isSupported("1.0"));
        assertTrue(A2AProtocol.isSupported(" 1.0 "), "the value is stripped first");

        assertFalse(A2AProtocol.isSupported("0.9"));
        assertFalse(A2AProtocol.isSupported("1.1"));
        assertFalse(A2AProtocol.isSupported("1.0.0"), "patch numbers must never be used on the wire");
        // section 3.6.2: an absent value means 0.3, which this server does not serve
        assertFalse(A2AProtocol.isSupported(null));
        assertFalse(A2AProtocol.isSupported(""));
        assertFalse(A2AProtocol.isSupported("  "));
    }

    @Test
    void anAbsentVersionResolvesToZeroThree() {
        assertEquals(A2AProtocol.ASSUMED_VERSION, A2AProtocol.resolve(null));
        assertEquals(A2AProtocol.ASSUMED_VERSION, A2AProtocol.resolve(""));
        assertEquals(A2AProtocol.ASSUMED_VERSION, A2AProtocol.resolve("   "));
        assertEquals("0.3", A2AProtocol.ASSUMED_VERSION);

        assertEquals("1.0", A2AProtocol.resolve("1.0"));
        assertEquals("1.0", A2AProtocol.resolve(" 1.0\n"));
    }

    @Test
    void theHeaderIsReadFirst() {
        assertEquals(
                "1.0",
                A2AHttp.version(FakeRequest.of("POST", "/a2a").header("A2A-Version", "1.0")),
                "the lookup must ignore the case of the header name");
        // a proxy adding a parameter cannot downgrade a caller which sent the header
        assertEquals(
                "1.0",
                A2AHttp.version(FakeRequest.of("POST", "/a2a")
                        .header("a2a-version", "1.0")
                        .query("A2A-Version=0.9")));
    }

    @Test
    void theQueryParameterIsTheFallback() {
        // section 3.6.1: "Clients MAY provide the A2A-Version as a request parameter instead of a header"
        assertEquals("1.0", A2AHttp.version(request("A2A-Version=1.0")));
        assertEquals("1.0", A2AHttp.version(request("a2a-version=1.0")), "parameter keys are case insensitive");
        assertEquals("1.0", A2AHttp.version(request("other=x&A2A-Version=1.0&more=y")));
        assertEquals("1.0", A2AHttp.version(request("A2A-Version=1.0&A2A-Version=0.9")), "the first one wins");
    }

    @Test
    void aBlankHeaderFallsBackToTheQueryParameter() {
        assertEquals(
                "1.0",
                A2AHttp.version(FakeRequest.of("POST", "/a2a")
                        .header("a2a-version", " ")
                        .query("A2A-Version=1.0")));
    }

    @Test
    void theValueIsUrlDecoded() {
        assertEquals("1.0 ", A2AHttp.version(request("A2A-Version=1.0+")));
        assertEquals("1.0", A2AHttp.version(request("A2A-Version=%31%2E%30")));
    }

    @Test
    void requestsCarryingNoVersion() {
        assertNull(A2AHttp.version(FakeRequest.of("POST", "/a2a")));
        assertNull(A2AHttp.version(request("")));
        assertNull(A2AHttp.version(request("other=x")));
        assertNull(A2AHttp.version(request("A2A-Version=")), "an empty value is no value");
        assertNull(A2AHttp.version(request("A2A-Version=%20")));
        assertNull(A2AHttp.version(request("A2A-Version")), "a parameter with no '=' is not a value");
        assertNull(A2AHttp.version(request("=1.0")), "and neither is a value with no name");
        // a longer name which merely starts with the one we look for must not match
        assertNull(A2AHttp.version(request("A2A-VersionExtra=1.0")));
        assertNull(A2AHttp.version(request("XA2A-Version=1.0")));
        // nor a shorter one which is a prefix of it
        assertNull(A2AHttp.version(request("a2a-versio=1.0")));
        assertNull(A2AHttp.version(request("a=1.0")));
    }

    @Test
    void unwrappingAStageFailure() {
        final var real = new IllegalStateException("the backend is down");

        // what a stage hands to a callback is the envelope, and reporting that would say nothing about the failure
        assertSame(real, A2AProtocol.unwrap(new CompletionException(real)));
        assertSame(real, A2AProtocol.unwrap(new ExecutionException(real)));

        // an envelope with nothing in it is all there is to report
        final var emptyEnvelope = new CompletionException(null);
        assertSame(emptyEnvelope, A2AProtocol.unwrap(emptyEnvelope));

        // and an exception which merely has a cause is the failure, not a wrapper around one
        assertSame(real, A2AProtocol.unwrap(real));
        final var chained = new IllegalStateException("could not connect", real);
        assertSame(chained, A2AProtocol.unwrap(chained));
    }

    @Test
    void streamingMethods() {
        assertEquals(
                Set.of(A2AProtocol.SEND_STREAMING_MESSAGE, A2AProtocol.SUBSCRIBE_TO_TASK),
                A2AProtocol.STREAMING_METHODS);

        assertTrue(A2AProtocol.isStreaming(A2AProtocol.SEND_STREAMING_MESSAGE));
        assertTrue(A2AProtocol.isStreaming(A2AProtocol.SUBSCRIBE_TO_TASK));
        assertFalse(A2AProtocol.isStreaming(A2AProtocol.SEND_MESSAGE));
        assertFalse(A2AProtocol.isStreaming(""));
        // a request is not guaranteed to carry a method, and an immutable set refuses a null lookup
        assertFalse(A2AProtocol.isStreaming(null));
        assertFalse(A2AProtocol.isStreaming(42), "and the member is not guaranteed to be a string either");
    }

    @Test
    void whetherARequestCarriesABody() {
        // reading a body which is not there never completes, so this is what the bindings decide on
        assertFalse(A2AHttp.hasBody(FakeRequest.of("POST", "/a2a")), "no header at all means no body");
        assertFalse(A2AHttp.hasBody(FakeRequest.of("POST", "/a2a").header("content-length", "0")));
        assertFalse(A2AHttp.hasBody(FakeRequest.of("POST", "/a2a").header("content-length", "  ")));
        assertFalse(
                A2AHttp.hasBody(FakeRequest.of("POST", "/a2a").header("content-length", "many")),
                "a length which is not a number cannot be trusted to announce a body");
        assertFalse(A2AHttp.hasBody(FakeRequest.of("POST", "/a2a").header("transfer-encoding", "  ")));

        assertTrue(A2AHttp.hasBody(FakeRequest.of("POST", "/a2a").header("content-length", " 12 ")));
        // a chunked request announces no length, the transfer encoding is what says there is a body
        assertTrue(A2AHttp.hasBody(FakeRequest.of("POST", "/a2a").header("transfer-encoding", "chunked")));
    }

    @Test
    void paths() {
        // the HTTP+JSON prefix is under the JSON-RPC path, so the routing order matters - pinned here
        assertTrue(A2AProtocol.REST_PATH.startsWith(A2AProtocol.JSONRPC_PATH));
        assertEquals("/a2a", A2AProtocol.JSONRPC_PATH);
        assertEquals("/a2a/v1", A2AProtocol.REST_PATH);
        assertEquals("/.well-known/agent-card.json", A2AProtocol.AGENT_CARD_PATH);
        assertEquals("application/a2a+json", A2AProtocol.MEDIA_TYPE);
    }

    private static FakeRequest request(final String query) {
        return FakeRequest.of("POST", "/a2a").query(query);
    }
}
