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
package io.yupiik.ia.agent.slack.transport;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.ia.agent.slack.model.SlackJson;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class SlackWebApiTest {
    @Test
    void threadRepliesUsesFixedFormPostAndDecodesOnePage() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            fixture.respond(ignored -> new SlackTransportFixture.Response(200, Map.of(), """
                    {"ok":true,"messages":[{"type":"message","ts":"120.000001","text":"root"},
                     {"ts":"121.000002","thread_ts":"120.000001","user":"U1","text":"reply"}],
                     "has_more":true,"response_metadata":{"next_cursor":"next"}}
                    """));
            final var api = fixture.api(4096, 4096);
            final var response = api.threadReplies(new SlackJson.ThreadRequest(
                            "C1&channel=other", "120.000001", "119.000000", "122.000000", true, 15, "a+/= &\u00e9"))
                    .toCompletableFuture()
                    .join();
            final var request = fixture.take(fixture.requests);
            assertEquals("POST", request.method());
            assertEquals("/api/conversations.replies", request.path());
            assertEquals("Bearer xoxb-secret", request.headers().get("authorization"));
            assertEquals(
                    "application/x-www-form-urlencoded; charset=utf-8",
                    request.headers().get("content-type"));
            assertEquals("application/json", request.headers().get("accept"));
            assertEquals(
                    "channel=C1%26channel%3Dother&ts=120.000001&oldest=119.000000&latest=122.000000"
                            + "&inclusive=true&limit=15&cursor=a%2B%2F%3D+%26%C3%A9",
                    request.body());
            assertEquals("120.000001", response.messages().getFirst().ts());
            assertEquals("120.000001", response.messages().get(1).thread_ts());
            assertEquals("reply", response.messages().get(1).text());
            assertEquals(Boolean.TRUE, response.has_more());
            assertEquals("next", response.response_metadata().next_cursor());
            assertNull(response.channel());
            assertNull(response.team());
            assertEquals(1, fixture.extended.getRequestCount(), "Transport must not paginate automatically");

            api.threadReplies(new SlackJson.ThreadRequest("C1", "120.000001", null, null, null, null, null))
                    .toCompletableFuture()
                    .join();
            assertEquals(
                    "channel=C1&ts=120.000001", fixture.take(fixture.requests).body());
            api.threadReplies(new SlackJson.ThreadRequest("C1", "120.000001", null, null, false, 1, ""))
                    .toCompletableFuture()
                    .join();
            assertEquals(
                    "channel=C1&ts=120.000001&inclusive=false&limit=1&cursor=",
                    fixture.take(fixture.requests).body());
        }
    }

    @Test
    void threadRepliesBoundsEncodedRequestsAndResponsesAndPreservesRateMetadata() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            final var request = new SlackJson.ThreadRequest("C1", "1.2", null, null, null, null, null);
            final var api = fixture.api(19, 16);
            assertEquals(
                    "request_too_large",
                    failure(api.threadReplies(
                                    new SlackJson.ThreadRequest("C1", "1.2", null, null, null, null, "&".repeat(10))))
                            .error());
            assertTrue(fixture.requests.isEmpty());
            assertEquals(0, fixture.extended.getRequestCount());
            fixture.respond(ignored -> new SlackTransportFixture.Response(200, Map.of(), "x".repeat(1024)));
            assertEquals(
                    "response_too_large", failure(api.threadReplies(request)).error());
            fixture.take(fixture.requests);
            for (final var body : List.of("secret", "secret".repeat(1024))) {
                fixture.respond(ignored -> new SlackTransportFixture.Response(429, Map.of("Retry-After", "37"), body));
                final var error = failure(api.threadReplies(request));
                assertEquals(429, error.status());
                assertEquals("ratelimited", error.error());
                assertEquals(Duration.ofSeconds(37), error.retryAfter().orElseThrow());
                assertFalse(error.toString().contains("secret"));
                assertNull(error.getCause());
                fixture.take(fixture.requests);
            }
            assertEquals(3, fixture.extended.getRequestCount());
            assertTrue(fixture.requests.isEmpty());
        }
    }

    @Test
    void threadRepliesSanitizesErrorsAndDoesNotFollowRedirects() throws Exception {
        try (final var fixture = new SlackTransportFixture();
                final var target = new SlackTransportFixture()) {
            final var api = fixture.api(4096, 4096);
            final var request = new SlackJson.ThreadRequest("C1", "1.2", null, null, null, null, null);
            for (final var body : List.of("{\"ok\":false,\"error\":\"xoxb-secret\"}", "secret", "{}", "null")) {
                fixture.respond(ignored -> new SlackTransportFixture.Response(200, Map.of(), body));
                final var error = failure(api.threadReplies(request));
                assertEquals(body.startsWith("{\"ok\":false") ? "api_error" : "invalid_response", error.error());
                assertFalse(error.toString().contains("secret"));
                assertNull(error.getCause());
                assertEquals(0, error.getSuppressed().length);
                fixture.take(fixture.requests);
            }
            fixture.respond(ignored ->
                    new SlackTransportFixture.Response(200, Map.of(), "{\"ok\":false,\"error\":\"missing_scope\"}"));
            assertEquals("missing_scope", failure(api.threadReplies(request)).error());
            fixture.take(fixture.requests);
            for (final var location : List.of(fixture.base().resolve("other"), target.base())) {
                fixture.respond(ignored ->
                        new SlackTransportFixture.Response(302, Map.of("Location", location.toString()), "secret"));
                final var error = failure(api.threadReplies(request));
                assertEquals(302, error.status());
                assertEquals("http_error", error.error());
                fixture.take(fixture.requests);
                assertTrue(target.requests.isEmpty());
            }
            assertEquals(7, fixture.extended.getRequestCount());
            assertTrue(fixture.requests.isEmpty());
            assertEquals("request_error", failure(api.threadReplies(null)).error());
            assertEquals(
                    "request_error",
                    failure(api.threadReplies(new SlackJson.ThreadRequest(null, "1.2", null, null, null, null, null)))
                            .error());
            assertEquals(
                    "request_error",
                    failure(api.threadReplies(new SlackJson.ThreadRequest("C1", null, null, null, null, null, null)))
                            .error());
            assertEquals(7, fixture.extended.getRequestCount());
        }
    }

    @Test
    void threadRepliesCancellationDoesNotReplayOrCompleteWithLateResponse() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            final var release = new CompletableFuture<Void>();
            fixture.respond(request -> {
                release.join();
                return new SlackTransportFixture.Response(200, Map.of(), "{\"ok\":true,\"messages\":[]}");
            });
            try {
                final var result = fixture.api(4096, 4096)
                        .threadReplies(new SlackJson.ThreadRequest("C1", "1.2", null, null, null, null, null))
                        .toCompletableFuture();
                final var request = fixture.take(fixture.requests);
                fixture.expectedDisconnectedResponses.add(request);
                assertTrue(result.cancel(true));
                assertTrue(result.isCancelled());
                release.complete(null);
                fixture.take(fixture.responded);
                assertTrue(result.isCancelled());
                assertEquals(1, fixture.extended.getRequestCount());
                assertTrue(fixture.requests.isEmpty());
            } finally {
                release.complete(null);
            }
        }
    }

    @Test
    void memberLookupUsesFormEncodingWithoutChangingJsonPublication() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            fixture.respond(ignored ->
                    new SlackTransportFixture.Response(200, Map.of(), "{\"ok\":true,\"user\":{\"id\":\"U1\"}}"));
            final var api = fixture.api(4096, 4096);
            assertEquals(
                    "U1",
                    api.memberInfo("U1").toCompletableFuture().join().user().id());
            var request = fixture.take(fixture.requests);
            assertEquals("POST", request.method());
            assertEquals("/api/users.info", request.path());
            assertEquals("Bearer xoxb-secret", request.headers().get("authorization"));
            assertEquals(
                    "application/x-www-form-urlencoded; charset=utf-8",
                    request.headers().get("content-type"));
            assertEquals("user=U1", request.body());
            api.memberInfo("U1&other=private").toCompletableFuture().join();
            request = fixture.take(fixture.requests);
            assertEquals("user=U1%26other%3Dprivate", request.body());
            api.postMessage(new SlackJson.PostMessage("C1", null, "hello", null, null, null))
                    .toCompletableFuture()
                    .join();
            request = fixture.take(fixture.requests);
            assertEquals("application/json; charset=utf-8", request.headers().get("content-type"));
            assertEquals(
                    Map.of("channel", "C1", "text", "hello"), fixture.json.fromString(Object.class, request.body()));
        }
    }

    @Test
    void routesEachMethodToTheRightCredentialAndNeverUsesGet() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            fixture.socketDiscovery();
            final var api = fixture.api(4096, 4096);
            assertEquals(
                    URI.create(fixture.socketUrl()),
                    api.openConnection().toCompletableFuture().join());
            var request = fixture.take(fixture.requests);
            assertEquals("/api/apps.connections.open", request.path());
            assertEquals("Bearer xapp-secret", request.headers().get("authorization"));
            assertEquals("POST", request.method());
            assertEquals("{}", request.body());

            fixture.respond(
                    ignored -> new SlackTransportFixture.Response(200, Map.of(), "{\"ok\":true,\"ts\":\"1.2\"}"));
            assertEquals(
                    "1.2",
                    api.postMessage(new SlackJson.PostMessage("C1", null, "hello", null, null, null))
                            .toCompletableFuture()
                            .join()
                            .ts());
            request = fixture.take(fixture.requests);
            assertEquals("/api/chat.postMessage", request.path());
            assertEquals("Bearer xoxb-secret", request.headers().get("authorization"));
            assertEquals("POST", request.method());
            assertEquals(
                    Map.of("channel", "C1", "text", "hello"), fixture.json.fromString(Object.class, request.body()));
            api.openView(new SlackJson.OpenView(
                            "trigger", new SlackJson.Modal("modal", null, null, null, null, null, null, null)))
                    .toCompletableFuture()
                    .join();
            request = fixture.take(fixture.requests);
            assertEquals("/api/views.open", request.path());
            assertEquals("Bearer xoxb-secret", request.headers().get("authorization"));
            assertEquals("POST", request.method());
        }
    }

    @Test
    void missingApiSuccessAndDiscoveryUrlAreSanitizedAndNeverReplayed() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            final var api = fixture.api(4096, 4096);
            for (final var body : List.of("{}", "{\"ok\":null}", "null")) {
                fixture.respond(ignored -> new SlackTransportFixture.Response(200, Map.of(), body));
                assertEquals(
                        "invalid_response",
                        failure(api.postMessage(new SlackJson.PostMessage(null, null, null, null, null, null)))
                                .error(),
                        body);
                fixture.take(fixture.requests);
                assertTrue(fixture.requests.isEmpty());
            }
            fixture.respond(ignored -> new SlackTransportFixture.Response(200, Map.of(), "{\"ok\":true}"));
            assertEquals("invalid_response", failure(api.openConnection()).error());
            assertEquals(4, fixture.extended.getRequestCount());
        }
    }

    @Test
    void returnsRetryAfterWithoutReplayingPublication() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            fixture.respond(
                    ignored -> new SlackTransportFixture.Response(429, Map.of("Retry-After", "37"), "xoxb-secret"));
            final var error = failure(
                    fixture.api(100, 100).postMessage(new SlackJson.PostMessage(null, null, null, null, null, null)));
            assertEquals(429, error.status());
            assertEquals("ratelimited", error.error());
            assertEquals(Duration.ofSeconds(37), error.retryAfter().orElseThrow());
            fixture.take(fixture.requests);
            assertTrue(fixture.requests.isEmpty());
            assertEquals(1, fixture.extended.getRequestCount());
        }
    }

    @Test
    void doesNotFollowSameOriginOrCrossOriginRedirects() throws Exception {
        try (final var fixture = new SlackTransportFixture();
                final var target = new SlackTransportFixture()) {
            final var api = fixture.api(100, 100);
            for (final var location : List.of(fixture.base().resolve("other"), target.base())) {
                fixture.respond(ignored ->
                        new SlackTransportFixture.Response(302, Map.of("Location", location.toString()), ""));
                assertEquals(
                        302,
                        failure(api.postMessage(new SlackJson.PostMessage(null, null, null, null, null, null)))
                                .status());
                fixture.take(fixture.requests);
                assertTrue(fixture.requests.isEmpty());
                assertTrue(target.requests.isEmpty(), "Credentials were forwarded to redirect target");
            }
            assertEquals(2, fixture.extended.getRequestCount());
        }
    }

    @Test
    void boundsRequestsBeforeSendingAndBoundsResponseBytes() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            final var api = fixture.api(16, 16);
            assertEquals(
                    "request_too_large",
                    failure(api.postMessage(new SlackJson.PostMessage(null, null, "x".repeat(32), null, null, null)))
                            .error());
            assertTrue(fixture.requests.isEmpty());
            fixture.respond(ignored -> new SlackTransportFixture.Response(200, Map.of(), "x".repeat(1024)));
            assertEquals(
                    "response_too_large",
                    failure(api.postMessage(new SlackJson.PostMessage(null, null, null, null, null, null)))
                            .error());
            assertEquals(1, fixture.extended.getRequestCount());
        }
    }

    @Test
    void oversized429StillProvidesRetryMetadata() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            fixture.respond(
                    ignored -> new SlackTransportFixture.Response(429, Map.of("Retry-After", "37"), "x".repeat(1024)));
            final var error = failure(
                    fixture.api(16, 16).postMessage(new SlackJson.PostMessage(null, null, null, null, null, null)));
            assertEquals(429, error.status());
            assertEquals(Duration.ofSeconds(37), error.retryAfter().orElseThrow());
            assertEquals(1, fixture.extended.getRequestCount());
        }
    }

    @Test
    void sanitizesRemoteErrorsMalformedJsonAndThrownCredentialSupplier() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            final var api = fixture.api(4096, 4096);
            for (final var body : List.of("{\"ok\":false,\"error\":\"xoxb-secret\"}", "xapp-secret", "[]", "{}")) {
                fixture.respond(ignored -> new SlackTransportFixture.Response(200, Map.of(), body));
                final var error =
                        failure(api.postMessage(new SlackJson.PostMessage(null, null, null, null, null, null)));
                assertFalse(error.toString().contains("secret"));
                assertNull(error.getCause());
                assertEquals(0, error.getSuppressed().length);
            }
            final var bad = new SlackWebApi(
                    fixture.json,
                    fixture.extended,
                    new SlackWebApi.Settings(fixture.base(), Duration.ofMillis(100), 100, 100),
                    () -> {
                        throw new IllegalStateException("xapp-secret");
                    },
                    () -> "xapp-wrong-kind");
            assertEquals("request_error", failure(bad.openConnection()).error());
            assertEquals(
                    "invalid_token",
                    failure(bad.postMessage(new SlackJson.PostMessage(null, null, null, null, null, null)))
                            .error());
        }
    }

    @Test
    void returnsSafeApiFailureAndRejectsUnsafeSocketUrls() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            final var api = fixture.api(1024, 1024);
            fixture.respond(ignored ->
                    new SlackTransportFixture.Response(200, Map.of(), "{\"ok\":false,\"error\":\"invalid_auth\"}"));
            assertEquals("invalid_auth", failure(api.openConnection()).error());
            for (final var url : List.of(
                    "https://slack.com/secret",
                    "ws://example.com/secret",
                    "wss://user:secret@slack.com/",
                    "%%%secret")) {
                fixture.respond(ignored ->
                        new SlackTransportFixture.Response(200, Map.of(), "{\"ok\":true,\"url\":\"" + url + "\"}"));
                final var error = failure(api.openConnection());
                assertEquals("invalid_response", error.error());
                assertFalse(error.toString().contains("secret"));
            }
        }
    }

    @Test
    void rejectsUnsafeBaseAndRedirectingClient() throws Exception {
        try (final var fixture = new SlackTransportFixture();
                final var delegate = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.ALWAYS)
                        .build();
                final var redirecting =
                        new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate))) {
            for (final var base : List.of(
                    "http://example.com/api/",
                    "https://secret@slack.com/api/",
                    "https://slack.com/api/?token=secret",
                    "https://slack.com/api")) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new SlackWebApi.Settings(URI.create(base), Duration.ofSeconds(1), 10, 10));
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new SlackWebApi(
                            fixture.json,
                            redirecting,
                            new SlackWebApi.Settings(fixture.base(), Duration.ofSeconds(1), 10, 10),
                            () -> "xapp-secret",
                            () -> "xoxb-secret"));
        }
    }

    private SlackWebApi.SlackApiException failure(final CompletionStage<?> stage) {
        final var error = assertThrows(
                CompletionException.class, () -> stage.toCompletableFuture().join());
        return assertInstanceOf(SlackWebApi.SlackApiException.class, error.getCause());
    }
}
