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
package io.yupiik.fusion.a2a;

import static io.yupiik.fusion.a2a.test.Awaits.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.a2a.client.A2AClient;
import io.yupiik.fusion.a2a.model.AuthenticationInfo;
import io.yupiik.fusion.a2a.model.Message;
import io.yupiik.fusion.a2a.model.SendMessageConfiguration;
import io.yupiik.fusion.a2a.model.SendMessageRequest;
import io.yupiik.fusion.a2a.model.TaskPushNotificationConfig;
import io.yupiik.fusion.a2a.protocol.A2AProtocol;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Push notifications: the third way to get task updates, for clients which cannot hold a connection open.
 */
@FusionSupport
class PushNotificationTest {
    @Test
    void webhookReceivesTheEvents(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) throws Exception {
        final var received = new CopyOnWriteArrayList<String>();
        final var tokens = new CopyOnWriteArrayList<String>();
        final var completed = new CountDownLatch(1);

        final var webhook = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        webhook.createContext("/hook", exchange -> {
            try (exchange) {
                final var payload = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
                received.add(payload);
                tokens.add(
                        String.valueOf(exchange.getRequestHeaders().getFirst(A2AProtocol.NOTIFICATION_TOKEN_HEADER)));
                assertEquals(
                        A2AProtocol.MEDIA_TYPE, exchange.getRequestHeaders().getFirst("content-type"));
                assertEquals("Bearer s3cr3t", exchange.getRequestHeaders().getFirst("authorization"));
                if (payload.contains("TASK_STATE_COMPLETED")) {
                    completed.countDown();
                }
                exchange.sendResponseHeaders(204, -1);
            } catch (final IOException ioe) {
                throw ioe;
            }
        });
        webhook.start();
        try {
            final var url = "http://localhost:" + webhook.getAddress().getPort() + "/hook";
            final var client = new A2AClient(agent, http, jsons);

            // the webhook is registered with the message itself, so nothing is missed
            final var task = await(client.sendMessage(new SendMessageRequest(
                            null,
                            Message.user("chunks:2"),
                            new SendMessageConfiguration(
                                    null,
                                    new TaskPushNotificationConfig(
                                            null, null, null, url, "tok-1", new AuthenticationInfo("Bearer", "s3cr3t")),
                                    null,
                                    false),
                            null)))
                    .task();

            assertTrue(completed.await(30, TimeUnit.SECONDS), () -> "No terminal notification, got " + received);
            // the payload is the very same StreamResponse a stream carries
            assertTrue(received.stream().anyMatch(it -> it.contains("\"artifactUpdate\"")), received::toString);
            assertTrue(received.stream().anyMatch(it -> it.contains("\"statusUpdate\"")), received::toString);
            assertTrue(received.stream().allMatch(it -> it.contains(task.id())), received::toString);
            assertEquals(List.of("tok-1"), tokens.stream().distinct().toList());
        } finally {
            webhook.stop(0);
        }
    }
}
