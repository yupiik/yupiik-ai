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

import static io.yupiik.fusion.a2a.test.Awaits.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.a2a.model.AgentCapabilities;
import io.yupiik.fusion.a2a.model.AgentCard;
import io.yupiik.fusion.a2a.model.AgentInterface;
import io.yupiik.fusion.a2a.model.AuthenticationInfo;
import io.yupiik.fusion.a2a.model.Message;
import io.yupiik.fusion.a2a.model.SendMessageConfiguration;
import io.yupiik.fusion.a2a.model.SendMessageRequest;
import io.yupiik.fusion.a2a.model.StreamResponse;
import io.yupiik.fusion.a2a.model.TaskPushNotificationConfig;
import io.yupiik.fusion.a2a.model.TaskState;
import io.yupiik.fusion.a2a.protocol.A2AErrors;
import io.yupiik.fusion.a2a.signature.AgentCardPayload;
import io.yupiik.fusion.a2a.signature.CardSigner;
import io.yupiik.fusion.a2a.signature.CardVerifier;
import io.yupiik.fusion.a2a.signature.JsonCanonicalizer;
import io.yupiik.fusion.a2a.test.Configurations;
import io.yupiik.fusion.a2a.test.Keys;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jwt.Jws;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The client: discovery, the service parameters it sends and what it does with an answer which is not a result.
 * <p>
 * The protocol tests already drive it against the agent. What is asserted here is the parts an agreeing server never
 * exercises: a card which cannot be fetched, a stream which is refused, a signature which is not trusted.
 */
@FusionSupport
class A2AClientTest {
    // --- discovery ----------------------------------------------------------------------------------------------

    @Test
    void aClientIsBuiltFromTheFirstJsonRpcInterface(@Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = A2AClient.of(
                card(
                        AgentInterface.of("https://agent.example.com/a2a/v1", AgentInterface.HTTP_JSON, "1.0"),
                        new AgentInterface("https://agent.example.com/a2a", AgentInterface.JSONRPC, "blue", "1.0"),
                        new AgentInterface("https://other.example.com/a2a", AgentInterface.JSONRPC, "green", "1.0")),
                http,
                jsons);

        // the HTTP+JSON one is skipped and the first JSON-RPC one wins, tenant included - section 8.3.2
        assertEquals("blue", client.tenant());
    }

    @Test
    void aCardWithoutAJsonRpcInterfaceCannotBeUsed(@Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var httpJsonOnly =
                card(AgentInterface.of("https://agent.example.com/a2a/v1", AgentInterface.HTTP_JSON, "1.0"));
        final var error = assertThrows(IllegalArgumentException.class, () -> A2AClient.of(httpJsonOnly, http, jsons));
        assertTrue(error.getMessage().contains("does not expose a JSONRPC interface"), error::getMessage);

        assertThrows(IllegalArgumentException.class, () -> A2AClient.of(card(), http, jsons));
        final var none = new AgentCard(
                "agent",
                "An agent.",
                null,
                null,
                "1.0.0",
                null,
                new AgentCapabilities(true, true, null, false),
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null);
        assertThrows(IllegalArgumentException.class, () -> A2AClient.of(none, http, jsons));
    }

    @Test
    void theBindingPathIsStrippedFromTheDeclaredUrl(@Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        // the client appends the binding paths itself, so the root is what it has to keep
        assertNull(A2AClient.of(
                        card(AgentInterface.of("https://agent.example.com/a2a", AgentInterface.JSONRPC, "1.0")),
                        http,
                        jsons)
                .tenant());

        // an interface declaring something else than the standard path is used as the root as is
        assertNull(A2AClient.of(
                        card(AgentInterface.of("https://agent.example.com/rpc", AgentInterface.JSONRPC, "1.0")),
                        http,
                        jsons)
                .tenant());

        // and a relative one collapsing to nothing still has to be a usable base
        assertNotNull(A2AClient.of(card(AgentInterface.of("/a2a", AgentInterface.JSONRPC, null)), http, jsons));
    }

    @Test
    void aCardWhichCannotBeFetched(@Fusion final HttpClient http, @Fusion final JsonMapper jsons) throws Exception {
        try (final var server = stub(exchange -> respond(exchange, 503, "gone"))) {
            final var client = new A2AClient(server.base(), http, jsons);

            final var error = assertThrows(A2AClientException.class, () -> await(client.agentCard()));
            assertEquals(A2AErrors.INTERNAL_ERROR, error.code());
            assertTrue(error.getMessage().contains("HTTP 503"), error::getMessage);
            assertEquals(List.of(), error.details());
        }
    }

    @Test
    void anUnsignedCardIsRefusedWhenAVerifierIsConfigured(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        // section 8.4.3 asks a client to verify before trusting: this agent publishes no signature, so nothing is
        // trusted
        final var client = new A2AClient(agent, http, jsons).verifier(CardVerifier.of(jsons), kid -> null);

        final var error = assertThrows(A2AClientException.class, () -> await(client.agentCard()));
        assertTrue(error.getMessage().contains("no signature this client trusts"), error::getMessage);
    }

    @Test
    void withoutAVerifierACardIsUsedAsIs(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        assertEquals(
                "test-agent",
                await(new A2AClient(agent, http, jsons).agentCard()).name());
    }

    @Test
    void aSignedCardIsTrustedWhenTheKeyIsKnown(
            @Fusion final HttpClient http, @Fusion final JsonMapper jsons, @TempDir final Path work) throws Exception {
        final var keys = Keys.ec();
        final var signed = new CardSigner(
                        new AgentCardPayload(jsons, new JsonCanonicalizer()),
                        new Jws(),
                        Configurations.configuration()
                                .signature(Keys.writePem(work, keys), Jws.ES256)
                                .build())
                .sign(card(AgentInterface.of("https://agent.example.com/a2a", AgentInterface.JSONRPC, "1.0")));

        try (final var server = stub(exchange -> respond(exchange, 200, jsons.toString(signed)))) {
            final var client = new A2AClient(server.base(), http, jsons)
                    .verifier(CardVerifier.of(jsons), kid -> "1.0.0".equals(kid) ? keys.getPublic() : null);

            // section 8.4.3: the card is verified against the exact JSON the agent served before being used
            final var fetched = await(client.agentCard());
            assertEquals("agent", fetched.name());
            assertEquals(1, fetched.signatures().size());
        }
    }

    @Test
    void thereIsNothingToVerifyWhenTheAgentAnswersNoCard(@Fusion final HttpClient http, @Fusion final JsonMapper jsons)
            throws Exception {
        try (final var server = stub(exchange -> respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":1}"))) {
            final var client = new A2AClient(server.base(), http, jsons).verifier(CardVerifier.of(jsons), kid -> null);

            assertNull(await(client.extendedAgentCard()), "a verifier must not turn an absent card into a failure");
        }
    }

    // --- service parameters -------------------------------------------------------------------------------------

    @Test
    void theTenantIsEchoedInEveryRequest(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        // this agent declares no tenant, so it accepts any - what is asserted is that the client sends it
        final var client = new A2AClient(agent, http, jsons).tenant("blue");
        assertEquals("blue", client.tenant());
        assertEquals(
                TaskState.TASK_STATE_COMPLETED,
                await(client.sendMessage("hi")).task().status().state());

        // a tenant carried by the request itself wins over the one of the client
        assertNotNull(await(client.sendMessage(new SendMessageRequest("green", Message.user("hi"), null, null)))
                .task());
        // and a blank one is no tenant at all, so the client one is used
        assertNotNull(await(client.sendMessage(new SendMessageRequest("  ", Message.user("hi"), null, null)))
                .task());
        assertNotNull(await(new A2AClient(agent, http, jsons).tenant("  ").sendMessage("hi"))
                .task());
    }

    @Test
    void theExtensionsHeaderIsSent(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons).extensions("https://example.com/x");

        // the agent declares no required extension, so declaring one changes nothing but must not break either
        assertEquals(
                TaskState.TASK_STATE_COMPLETED,
                await(client.sendMessage("hi")).task().status().state());
    }

    @Test
    void aTrailingSlashInTheBaseIsNotPartOfThePath(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(URI.create(agent + "/"), http, jsons);

        assertNotNull(await(client.sendMessage("hi")).task());
    }

    @Test
    void theRequestMetadataTravels(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var response = await(new A2AClient(agent, http, jsons)
                .sendMessage(new SendMessageRequest(
                        null,
                        Message.user("hi"),
                        new SendMessageConfiguration(List.of("text/plain"), null, null, false),
                        Map.of("origin", "test"))));

        assertEquals(Map.of("origin", "test"), response.task().metadata());
    }

    @Test
    void aRawCallWithoutAnyParameter(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);

        // an absent - or blank - parameter payload has to become an empty object, else the envelope is not valid JSON
        for (final var params : List.of("", "   ")) {
            assertEquals(200, await(client.raw("ListTasks", params)).statusCode());
        }
        assertEquals(200, await(client.raw("ListTasks", null)).statusCode());
    }

    // --- push notification configurations -----------------------------------------------------------------------

    @Test
    void aWebhookIsRegisteredWithItsAuthentication(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);
        final var task = await(client.sendMessage("input:waiting")).task();

        final var created = await(client.createTaskPushNotificationConfig(new TaskPushNotificationConfig(
                null,
                "mine",
                task.id(),
                "https://client.example.com/hook",
                "tok-1",
                new AuthenticationInfo("Bearer", "s3cr3t"))));

        assertEquals("mine", created.id());
        assertEquals("Bearer", created.authentication().scheme());
        assertEquals("s3cr3t", created.authentication().credentials());
        assertEquals("tok-1", created.token());
    }

    // --- streams ------------------------------------------------------------------------------------------------

    @Test
    void theHttpJsonBindingCanBeSubscribedTo(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);
        final var task = await(client.sendMessage(new SendMessageRequest(
                        null, Message.user("slow"), new SendMessageConfiguration(null, null, null, true), null)))
                .task();

        final var events = new CopyOnWriteArrayList<StreamResponse>();
        final var subscribed = new CountDownLatch(1);
        // no body on this route, the task is in the path
        final var subscription = client.restStreaming("/tasks/" + task.id() + ":subscribe", null, event -> {
            events.add(event);
            subscribed.countDown();
        });
        // cancel only once the stream is live, else the task would be terminal before the subscription reaches the
        // agent
        assertTrue(awaitLatch(subscribed), "the subscription did not start");
        await(client.cancelTask(task.id()));
        await(subscription);

        assertNotNull(events.get(0).task());
        assertEquals(task.id(), events.get(0).task().id());
    }

    @Test
    void aStreamRefusedWithAnUnreadableBody(@Fusion final HttpClient http, @Fusion final JsonMapper jsons)
            throws Exception {
        try (final var server = stub(exchange -> respond(exchange, 502, "<html>Bad Gateway</html>"))) {
            final var client = new A2AClient(server.base(), http, jsons);

            // the body is not a payload the client can read, so it reports the status it got
            final var error = assertThrows(
                    A2AClientException.class,
                    () -> await(client.sendStreamingMessage(SendMessageRequest.of(Message.user("hi")), event -> {})));
            assertEquals(A2AErrors.INTERNAL_ERROR, error.code());
            assertTrue(error.getMessage().contains("HTTP 502"), error::getMessage);
        }
    }

    @Test
    void aStreamRefusedWithNoBodyAtAll(@Fusion final HttpClient http, @Fusion final JsonMapper jsons) throws Exception {
        try (final var server = stub(exchange -> respond(exchange, 401, ""))) {
            final var client = new A2AClient(server.base(), http, jsons);

            final var error =
                    assertThrows(A2AClientException.class, () -> await(client.subscribeToTask("t-1", event -> {})));
            assertTrue(error.getMessage().contains("HTTP 401"), error::getMessage);
        }
    }

    @Test
    void aStreamRefusedWithAReadableButHarmlessBody(@Fusion final HttpClient http, @Fusion final JsonMapper jsons)
            throws Exception {
        // a well formed payload which is not an error: there is nothing in it to report, so the status is
        final var payload = """
                {"jsonrpc":"2.0","id":1,"result":{}}""";
        try (final var server = stub(exchange -> respond(exchange, 418, payload))) {
            final var client = new A2AClient(server.base(), http, jsons);

            final var error =
                    assertThrows(A2AClientException.class, () -> await(client.subscribeToTask("t-1", event -> {})));
            assertEquals(A2AErrors.INTERNAL_ERROR, error.code());
            assertTrue(error.getMessage().contains("HTTP 418"), error::getMessage);
        }
    }

    @Test
    void aStreamRefusedWithAnA2AErrorKeepsTheCode(@Fusion final HttpClient http, @Fusion final JsonMapper jsons)
            throws Exception {
        final var payload = """
                {"jsonrpc":"2.0","id":1,"error":{"code":-32004,"message":"Task already ended","data":[]}}""";
        try (final var server = stub(exchange -> respond(exchange, 400, payload))) {
            final var client = new A2AClient(server.base(), http, jsons);

            final var error =
                    assertThrows(A2AClientException.class, () -> await(client.subscribeToTask("t-1", event -> {})));
            assertEquals(A2AErrors.UNSUPPORTED_OPERATION, error.code());
            assertEquals("Task already ended", error.getMessage());
        }
    }

    // --- reading an answer --------------------------------------------------------------------------------------

    @Test
    void anAnswerWhichIsNotAJsonRpcResponse(@Fusion final HttpClient http, @Fusion final JsonMapper jsons)
            throws Exception {
        try (final var server = stub(exchange -> respond(exchange, 200, "[1, 2, 3]"))) {
            final var client = new A2AClient(server.base(), http, jsons);

            final var error = assertThrows(A2AClientException.class, () -> await(client.getTask("t-1", null)));
            assertEquals(A2AErrors.INTERNAL_ERROR, error.code());
            assertTrue(error.getMessage().contains("Unexpected response"), error::getMessage);
        }
    }

    @Test
    void anErrorWithoutACodeOrAMessage(@Fusion final HttpClient http, @Fusion final JsonMapper jsons) throws Exception {
        try (final var server = stub(
                exchange -> respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":\"nope\"}}"))) {
            final var client = new A2AClient(server.base(), http, jsons);

            // a server which does not follow the envelope must still produce an exception a caller can catch
            final var error = assertThrows(A2AClientException.class, () -> await(client.getTask("t-1", null)));
            assertEquals(A2AErrors.INTERNAL_ERROR, error.code());
            assertEquals("Unknown error", error.getMessage());
            assertEquals(List.of(), error.details());
        }
    }

    @Test
    void anErrorWithoutAnyDetail() {
        // the details are the structured part of an A2A error, and a caller must be able to read them without a null
        // check
        assertEquals(List.of(), new A2AClientException(A2AErrors.TASK_NOT_FOUND, "Task not found", null).details());
        assertEquals(
                List.of(Map.of("@type", "x")),
                new A2AClientException(A2AErrors.TASK_NOT_FOUND, "Task not found", List.of(Map.of("@type", "x")))
                        .details());
    }

    @Test
    void anAnswerWithoutAnyResult(@Fusion final HttpClient http, @Fusion final JsonMapper jsons) throws Exception {
        try (final var server = stub(exchange -> respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":1}"))) {
            final var client = new A2AClient(server.base(), http, jsons);

            assertNull(await(client.getTask("t-1", null)), "there is nothing to bind, and that is not a failure");
        }
    }

    // --- fixtures -----------------------------------------------------------------------------------------------

    private static boolean awaitLatch(final CountDownLatch latch) {
        try {
            return latch.await(30, TimeUnit.SECONDS);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * @param handler what to answer, whatever the request.
     * @return a server answering it, to be closed by the caller.
     */
    private static Stub stub(final Handler handler) throws IOException {
        final var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                handler.handle(exchange);
            }
        });
        server.start();
        return new Stub(server);
    }

    private static void respond(final HttpExchange exchange, final int status, final String body) throws IOException {
        final var bytes = body.getBytes(UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
    }

    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record Stub(HttpServer server) implements AutoCloseable {
        private URI base() {
            return URI.create("http://localhost:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static AgentCard card(final AgentInterface... interfaces) {
        return new AgentCard(
                "agent",
                "An agent.",
                List.of(interfaces),
                null,
                "1.0.0",
                null,
                new AgentCapabilities(true, true, null, false),
                null,
                null,
                List.of("text/plain"),
                List.of("text/plain"),
                List.of(),
                null,
                null);
    }
}
