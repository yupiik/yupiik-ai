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
package io.yupiik.fusion.a2a.service;

import static io.yupiik.fusion.a2a.test.Awaits.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.a2a.model.AuthenticationInfo;
import io.yupiik.fusion.a2a.model.Message;
import io.yupiik.fusion.a2a.model.StreamResponse;
import io.yupiik.fusion.a2a.model.TaskPushNotificationConfig;
import io.yupiik.fusion.a2a.protocol.A2AProtocol;
import io.yupiik.fusion.a2a.test.Configurations;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Webhook delivery: the headers a notification carries, and the fact that a failing one is logged and forgotten.
 * <p>
 * {@code PushNotificationTest} asserts the events an agent really delivers. What is asserted here is everything which
 * must <b>not</b> reach the task: a webhook answering 500, an unreachable host, a URL which is not even a URL.
 */
@FusionSupport
class PushNotifierTest {
    @Test
    void aNotificationCarriesTheAuthorizationAndTheToken(@Fusion final JsonMapper jsons) throws Exception {
        final var received = new CopyOnWriteArrayList<Map<String, String>>();
        try (final var webhook = webhook(exchange -> {
            received.add(Map.of(
                    "body", new String(exchange.getRequestBody().readAllBytes(), UTF_8),
                    "content-type", String.valueOf(exchange.getRequestHeaders().getFirst("content-type")),
                    "authorization", String.valueOf(exchange.getRequestHeaders().getFirst("authorization")),
                    "token",
                            String.valueOf(
                                    exchange.getRequestHeaders().getFirst(A2AProtocol.NOTIFICATION_TOKEN_HEADER))));
            exchange.sendResponseHeaders(204, -1);
        })) {
            deliver(
                    jsons,
                    new TaskPushNotificationConfig(
                            null, "c-1", "t-1", webhook.url(), "tok-1", new AuthenticationInfo("Bearer", "s3cr3t")));

            assertEquals(1, received.size());
            final var call = received.get(0);
            assertEquals(A2AProtocol.MEDIA_TYPE, call.get("content-type"));
            assertEquals("Bearer s3cr3t", call.get("authorization"));
            assertEquals("tok-1", call.get("token"));
            assertTrue(call.get("body").contains("\"message\""), call::toString);
        }
    }

    @Test
    void aNotificationWithoutAuthenticationNorToken(@Fusion final JsonMapper jsons) throws Exception {
        final var headers = new CopyOnWriteArrayList<Map<String, String>>();
        try (final var webhook = webhook(exchange -> {
            exchange.getRequestBody().readAllBytes();
            headers.add(Map.of(
                    "authorization", String.valueOf(exchange.getRequestHeaders().getFirst("authorization")),
                    "token",
                            String.valueOf(
                                    exchange.getRequestHeaders().getFirst(A2AProtocol.NOTIFICATION_TOKEN_HEADER))));
            exchange.sendResponseHeaders(204, -1);
        })) {
            deliver(jsons, TaskPushNotificationConfig.of(webhook.url()).boundTo("c-1", "t-1"));
            // an authentication carrying no scheme has nothing to send either, and neither has a blank token
            deliver(
                    jsons,
                    new TaskPushNotificationConfig(
                            null, "c-2", "t-1", webhook.url(), "  ", new AuthenticationInfo(null, "s3cr3t")));

            assertEquals(2, headers.size());
            assertEquals(
                    List.of("null"),
                    headers.stream()
                            .map(it -> it.get("authorization"))
                            .distinct()
                            .toList());
            assertEquals(
                    List.of("null"),
                    headers.stream().map(it -> it.get("token")).distinct().toList());
        }
    }

    @Test
    void aWebhookAnsweringAnErrorDoesNotFailTheTask(@Fusion final JsonMapper jsons) throws Exception {
        try (final var webhook = webhook(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(500, -1);
        })) {
            // delivery is best effort: the task must not learn about it
            assertNull(
                    deliver(jsons, TaskPushNotificationConfig.of(webhook.url()).boundTo("c-1", "t-1")));
        }
    }

    @Test
    void aDeliveredNotificationIsTraced(@Fusion final JsonMapper jsons) throws Exception {
        final var logger = Logger.getLogger(PushNotifier.class.getName());
        final var level = logger.getLevel();
        // a success is logged at FINE, so its message is only built when somebody asked for it
        logger.setLevel(Level.FINEST);
        try (final var webhook = webhook(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(204, -1);
        })) {
            assertNull(
                    deliver(jsons, TaskPushNotificationConfig.of(webhook.url()).boundTo("c-1", "t-1")));
        } finally {
            logger.setLevel(level);
        }
    }

    @Test
    void aWebhookAnsweringARedirectIsNotASuccessEither(@Fusion final JsonMapper jsons) throws Exception {
        try (final var webhook = webhook(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("location", "https://elsewhere.example.com/hook");
            exchange.sendResponseHeaders(302, -1);
        })) {
            assertNull(
                    deliver(jsons, TaskPushNotificationConfig.of(webhook.url()).boundTo("c-1", "t-1")));
        }
    }

    @Test
    void anUnreachableWebhookDoesNotFailTheTask(@Fusion final JsonMapper jsons) {
        // a port nothing listens on: the delivery fails, the task does not
        assertNull(deliver(
                jsons, TaskPushNotificationConfig.of("http://localhost:1/hook").boundTo("c-1", "t-1")));
    }

    @Test
    void aUrlWhichIsNotAUrlDoesNotFailTheTask(@Fusion final JsonMapper jsons) {
        // the request cannot even be built, and that must not break the task the event belongs to
        assertNull(
                deliver(jsons, TaskPushNotificationConfig.of("not a url at all").boundTo("c-1", "t-1")));
        assertNull(deliver(
                jsons, TaskPushNotificationConfig.of("ftp://example.com/hook").boundTo("c-1", "t-1")));
    }

    @Test
    void aWebhookWithoutAUrlIsSkipped(@Fusion final JsonMapper jsons) {
        assertNull(deliver(jsons, new TaskPushNotificationConfig(null, "c-1", "t-1", null, null, null)));
        assertNull(deliver(jsons, new TaskPushNotificationConfig(null, "c-2", "t-1", "  ", null, null)));
    }

    @Test
    void aTaskWithoutAnyWebhookCostsNothing(@Fusion final JsonMapper jsons) {
        final var threads = new A2AThreads();
        final var notifier = new PushNotifier(jsons, Configurations.defaults(), threads);
        try {
            final var task = new A2ATask("t-1", "c-1", null, null, Clock.systemUTC());

            notifier.notify(task, StreamResponse.of(Message.agent("hi")));

            assertNull(await(task.serializePush(() -> CompletableFuture.completedFuture((Void) null))));
        } finally {
            notifier.close();
            threads.close();
        }
    }

    @Test
    void closingWithoutAnyClientIsFine() {
        // the protected constructor builds no client, which is what the container proxies
        new PushNotifier(null, null, null) {}.close();
    }

    /**
     * Delivers one event to {@code config} and waits for the chain to drain, whatever the outcome.
     *
     * @return {@code null}, the chain never carries a value - and it must never carry a failure either.
     */
    private Void deliver(final JsonMapper jsons, final TaskPushNotificationConfig config) {
        final var threads = new A2AThreads();
        final var notifier = new PushNotifier(
                jsons, Configurations.configuration().pushTimeout("PT1S").build(), threads);
        try {
            final var task = new A2ATask("t-1", "c-1", null, null, Clock.systemUTC());
            task.pushConfigsById().put(config.id(), config);

            notifier.notify(task, StreamResponse.of(Message.agent("hi")));

            // the deliveries are chained on the task, so queuing behind them is how to wait for them - bounded,
            // because an unbounded join here would turn a stuck delivery into a stuck build
            return await(task.serializePush(() -> CompletableFuture.completedFuture((Void) null)));
        } finally {
            notifier.close();
            threads.close();
        }
    }

    private static Webhook webhook(final Handler handler) throws IOException {
        final var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/hook", exchange -> {
            try (exchange) {
                handler.handle(exchange);
            }
        });
        server.start();
        return new Webhook(server);
    }

    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record Webhook(HttpServer server) implements AutoCloseable {
        private String url() {
            return "http://localhost:" + server.getAddress().getPort() + "/hook";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
