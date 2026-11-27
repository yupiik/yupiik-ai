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

import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SlackTransportFixtureTest {
    @Test
    void acceptsResetAsEstablishedWebSocketDisconnect() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            final SlackTransportFixture.Session session;
            try (final var peer = new Socket("127.0.0.1", fixture.base().getPort())) {
                peer.setSoLinger(true, 0);
                peer.getOutputStream()
                        .write(("GET /socket HTTP/1.1\r\nHost: localhost\r\n"
                                        + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                                        + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                peer.getOutputStream().flush();
                session = fixture.take(fixture.sessions);
            }
            session.closed.get(5, TimeUnit.SECONDS);
            // close() waits for serve() to exit and asserts that no fixture failure was recorded.
        }
    }

    @Test
    void closesIdleConnectionAndClient() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            final var peer = fixture.http
                    .newWebSocketBuilder()
                    .buildAsync(URI.create(fixture.socketUrl()), new WebSocket.Listener() {})
                    .get(5, TimeUnit.SECONDS);
            try {
                final var session = fixture.take(fixture.sessions);
                Assertions.assertDoesNotThrow(fixture::close);
                assertTrue(session.closed.isDone(), "Accepted idle session must exit during teardown");
                assertTrue(fixture.http.isTerminated());
                assertTrue(fixture.scheduler.isShutdown());
            } finally {
                peer.abort();
            }
        }
    }

    @Test
    void interruptsInflightResponseAndTerminatesClient() throws Exception {
        final var entered = new CountDownLatch(1);
        final var exited = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        try (final var fixture = new SlackTransportFixture()) {
            fixture.respond(request -> {
                entered.countDown();
                try {
                    release.await();
                } catch (final InterruptedException expected) {
                    Thread.currentThread().interrupt();
                } finally {
                    exited.countDown();
                }
                return new SlackTransportFixture.Response(200, Map.of(), "{}");
            });
            final var response = fixture.http.sendAsync(
                    HttpRequest.newBuilder(fixture.base()).build(), HttpResponse.BodyHandlers.discarding());
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                Assertions.assertDoesNotThrow(fixture::close);
                assertEquals(0, exited.getCount(), "Teardown must wait for responder exit");
                // Client termination does not imply its dependent future has been completed yet.
                assertThrows(ExecutionException.class, () -> response.get(5, TimeUnit.SECONDS));
                assertTrue(fixture.http.isTerminated());
                assertTrue(fixture.scheduler.isShutdown());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void reportsFixtureFailureAfterReleasingResources() throws Exception {
        final var fixture = new SlackTransportFixture();
        try {
            fixture.failures.add(new IllegalStateException("intentional fixture failure"));
            assertThrows(AssertionError.class, fixture::close);
            assertTrue(fixture.http.isTerminated());
            assertTrue(fixture.scheduler.isShutdown());
        } finally {
            fixture.failures.clear();
            fixture.close();
        }
    }
}
