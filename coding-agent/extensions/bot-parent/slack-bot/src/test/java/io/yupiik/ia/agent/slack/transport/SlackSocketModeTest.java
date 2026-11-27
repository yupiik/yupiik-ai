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

import io.yupiik.ia.agent.slack.model.SlackJson;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SlackSocketModeTest {
    @Test
    void lifecycleDiagnosticsContainOnlyBoundedSafeStateText() throws Exception {
        final var logger = Logger.getLogger(SlackSocketMode.class.getName());
        final var previous = logger.getLevel();
        final var records = new LinkedBlockingQueue<LogRecord>();
        final var handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        logger.setLevel(Level.INFO);
        try (final var fixture = new SlackTransportFixture()) {
            fixture.socketDiscovery(); // Discovery URL contains a secret socket ticket.
            try (final var transport = fixture.socket(4096, ignored -> {
                throw new IllegalStateException("xapp-secret xoxb-secret private-body");
            })) {
                var peer = start(fixture, transport);
                assertDiagnostic(fixture.take(records), Level.INFO, "Slack Socket Mode connection open");
                peer.text("{\"type\":\"hello\",\"reason\":\"private-body\"}", true, false);
                assertDiagnostic(fixture.take(records), Level.INFO, "Slack Socket Mode hello received");
                peer.text("{\"type\":\"hello\"}", true, false);
                peer.text("{\"type\":\"events_api\",\"envelope_id\":\"private-body\"}", true, false);
                fixture.take(fixture.scheduler.tasks).run();
                assertDiagnostic(fixture.take(records), Level.INFO, "Slack Socket Mode reconnecting");
                peer = fixture.take(fixture.sessions);
                assertDiagnostic(fixture.take(records), Level.INFO, "Slack Socket Mode connection open");
                peer.text(
                        "{\"type\":\"disconnect\",\"reason\":\"disabled\",\"debug_info\":\"private-body\"}",
                        true,
                        false);
                assertDiagnostic(
                        fixture.take(records),
                        Level.WARNING,
                        "Slack Socket Mode disabled: remote_policy_or_credentials");
                Assertions.assertDoesNotThrow(transport::close);
                Assertions.assertDoesNotThrow(transport::close);
                assertDiagnostic(fixture.take(records), Level.INFO, "Slack Socket Mode closed");
                assertTrue(records.isEmpty(), "Duplicate hello/close must not produce repeated diagnostics");
            }
            fixture.scheduler.shutdownNow();
            try (final var transport = fixture.socket(4096, ignored -> Optional.empty())) {
                transport.start();
                assertDiagnostic(
                        fixture.take(records), Level.WARNING, "Slack Socket Mode disabled: scheduling_unavailable");
                assertEquals(SlackSocketMode.State.DISABLED, transport.state());
            }
            assertDiagnostic(fixture.take(records), Level.INFO, "Slack Socket Mode closed");
            assertTrue(records.isEmpty());
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
    }

    private void assertDiagnostic(final LogRecord record, final Level level, final String message) {
        assertEquals(level, record.getLevel());
        assertEquals(message, record.getMessage());
        assertNull(record.getThrown());
        assertNull(record.getParameters());
    }

    @Test
    void realJdkWebSocketReassemblesFragmentsAndAcksOnlyAfterDurableAdmission() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            fixture.socketDiscovery();
            final var admitted = new CompletableFuture<SlackJson.Envelope>();
            final var release = new CountDownLatch(1);
            try (final var transport = fixture.socket(4096, envelope -> {
                admitted.complete(envelope);
                if (!release.await(200, TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("Admission was not released");
                }
                return Optional.of(new SlackJson.Reply("accepted", null, null));
            })) {
                final var peer = start(fixture, transport);
                peer.text("{\"type\":\"events_api\",\"envelope_id\":\"E1\",", false, false);
                peer.text("\"accepts_response_payload\":true,\"payload\":{\"event\":{\"type\":\"test\"}}}", true, true);
                assertEquals("E1", admitted.get(200, TimeUnit.MILLISECONDS).envelope_id());
                assertTrue(peer.messages.isEmpty(), "ACK preceded durable admission");
                release.countDown();
                assertEquals(
                        Map.of("envelope_id", "E1", "payload", Map.of("text", "accepted")),
                        fixture.json.fromString(Object.class, fixture.take(peer.messages)));
                assertEquals(SlackSocketMode.State.CONNECTED, transport.state());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void missingEnvelopeIdentityWithholdsAckBeforeCallingAdmission() throws Exception {
        for (final var body : List.of("{}", "{\"envelope_id\":null}", "{\"envelope_id\":\"\"}")) {
            try (final var fixture = new SlackTransportFixture()) {
                fixture.socketDiscovery();
                final var calls = new AtomicInteger();
                try (final var transport = fixture.socket(4096, ignored -> {
                    calls.incrementAndGet();
                    return Optional.empty();
                })) {
                    final var peer = start(fixture, transport);
                    peer.text(body, true, false);
                    fixture.take(fixture.scheduler.tasks);
                    assertEquals(0, calls.get(), body);
                    assertTrue(peer.messages.isEmpty(), body);
                    assertEquals(SlackSocketMode.State.RECONNECTING, transport.state());
                }
            }
        }
    }

    @Test
    void bareAckAndInteractionPayloadCapabilityAreRespected() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            fixture.socketDiscovery();
            final var calls = new AtomicInteger();
            try (final var transport = fixture.socket(
                    1024,
                    ignored -> calls.incrementAndGet() == 1
                            ? Optional.empty()
                            : Optional.of(new SlackJson.Reply("not-permitted", null, null)))) {
                final var peer = start(fixture, transport);
                for (final var id : new String[] {"E1", "E2"}) {
                    peer.text(
                            "{\"type\":\"interactive\",\"envelope_id\":\"" + id
                                    + "\",\"accepts_response_payload\":false}",
                            true,
                            false);
                    assertEquals(
                            Map.of("envelope_id", id),
                            fixture.json.fromString(Object.class, fixture.take(peer.messages)));
                }
                assertEquals(2, calls.get());
            }
        }
    }

    @Test
    void admissionFailureWithholdsAckAndSchedulesRatherThanSleeping() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            fixture.socketDiscovery();
            try (final var transport = fixture.socket(1024, ignored -> {
                throw new IllegalStateException("store unavailable");
            })) {
                final var peer = start(fixture, transport);
                peer.text("{\"type\":\"events_api\",\"envelope_id\":\"retry-me\"}", true, false);
                final var reconnect = fixture.take(fixture.scheduler.tasks);
                assertEquals(Duration.ofMillis(10).toNanos(), reconnect.delay);
                assertEquals(SlackSocketMode.State.RECONNECTING, transport.state());
                assertTrue(peer.messages.isEmpty());
                assertTrue(fixture.requests.isEmpty(), "Reconnect ran without scheduler release");
            }
        }
    }

    @Test
    void refreshGetsNewUrlAndCloseCancelsScheduledReconnect() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            fixture.socketDiscovery();
            try (final var transport = fixture.socket(1024, ignored -> Optional.empty())) {
                final var first = start(fixture, transport);
                first.text("{\"type\":\"warning\",\"reason\":\"refresh_requested\"}", true, false);
                first.text("{\"type\":\"disconnect\",\"reason\":\"refresh_requested\"}", true, false);
                fixture.take(fixture.scheduler.tasks).run();
                final var second = fixture.take(fixture.sessions);
                assertNotSame(first, second);
                assertEquals(
                        "/api/apps.connections.open",
                        fixture.take(fixture.requests).path());
                assertNull(
                        fixture.take(fixture.requests).headers().get("authorization"), "Token leaked to WS handshake");
                second.text("{\"type\":\"disconnect\",\"reason\":\"refresh_requested\"}", true, false);
                final var pending = fixture.take(fixture.scheduler.tasks);
                Assertions.assertDoesNotThrow(transport::close);
                Assertions.assertDoesNotThrow(transport::close);
                transport.start();
                assertTrue(pending.isCancelled());
                pending.run();
                assertTrue(fixture.requests.isEmpty());
                assertEquals(SlackSocketMode.State.CLOSED, transport.state());
                assertFalse(fixture.scheduler.isShutdown(), "Borrowed scheduler was closed");
                assertFalse(fixture.http.isTerminated(), "Borrowed client was closed");
            }
        }
    }

    @Test
    void linkDisabledStopsWithoutReconnect() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            fixture.socketDiscovery();
            try (final var transport = fixture.socket(1024, ignored -> Optional.empty())) {
                final var peer = start(fixture, transport);
                peer.text("{\"type\":\"disconnect\",\"reason\":\"link_disabled\"}", true, false);
                peer.closed.get(200, TimeUnit.MILLISECONDS);
                assertEquals(SlackSocketMode.State.DISABLED, transport.state());
                assertTrue(fixture.scheduler.tasks.isEmpty());
                transport.start();
                assertTrue(fixture.scheduler.tasks.isEmpty());
            }
        }
    }

    @Test
    void backoffGrowsWithinBoundsAndHelloResetsIt() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            fixture.socketDiscovery();
            try (final var transport = fixture.socket(1024, ignored -> Optional.empty())) {
                var peer = start(fixture, transport);
                final var expected = new long[] {10, 15, 30, 60, 60};
                for (final var millis : expected) {
                    peer.disconnect();
                    final var task = fixture.take(fixture.scheduler.tasks);
                    assertEquals(Duration.ofMillis(millis).toNanos(), task.delay);
                    task.run();
                    peer = fixture.take(fixture.sessions);
                }
                peer.text("{\"type\":\"hello\"}", true, false);
                peer.text("{\"type\":\"disconnect\",\"reason\":\"refresh_requested\"}", true, false);
                assertEquals(Duration.ofMillis(10).toNanos(), fixture.take(fixture.scheduler.tasks).delay);
            }
        }
    }

    @Test
    void boundsFragmentedUtf8BytesNotJustCharactersAndRejectsMalformedMessages() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            fixture.socketDiscovery();
            final var calls = new AtomicInteger();
            try (final var transport = fixture.socket(128, ignored -> {
                calls.incrementAndGet();
                return Optional.empty();
            })) {
                var peer = start(fixture, transport);
                // 66 characters, 132 bytes; first frame alone is within the 128-byte limit.
                peer.text("\u00e9".repeat(33), false, false);
                peer.text("\u00e9".repeat(33), true, true);
                var task = fixture.take(fixture.scheduler.tasks);
                assertTrue(peer.messages.isEmpty());
                assertEquals(0, calls.get());
                task.run();
                peer = fixture.take(fixture.sessions);
                peer.text("{bad json secret-ticket", true, false);
                task = fixture.take(fixture.scheduler.tasks);
                assertTrue(peer.messages.isEmpty());
                assertEquals(0, calls.get());
                task.run();
                peer = fixture.take(fixture.sessions);
                peer.frame(0x82, "binary".getBytes(StandardCharsets.UTF_8));
                fixture.take(fixture.scheduler.tasks);
                assertEquals(0, calls.get());
            }
        }
    }

    @Test
    void oversizedAckIsNotSentAndSocketCloseReleasesLiveConnection() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            fixture.socketDiscovery();
            try (final var transport =
                    fixture.socket(128, ignored -> Optional.of(new SlackJson.Reply("x".repeat(200), null, null)))) {
                final var peer = start(fixture, transport);
                peer.text("{\"envelope_id\":\"E1\",\"accepts_response_payload\":true}", true, false);
                fixture.take(fixture.scheduler.tasks);
                assertTrue(peer.messages.isEmpty());
            }
            try (final var transport = fixture.socket(1024, ignored -> Optional.empty())) {
                final var peer = start(fixture, transport);
                Assertions.assertDoesNotThrow(transport::close);
                peer.closed.get(200, TimeUnit.MILLISECONDS);
                assertEquals(SlackSocketMode.State.CLOSED, transport.state());
                assertTrue(fixture.scheduler.tasks.isEmpty());
            }
        }
    }

    @Test
    void discovery429UsesRetryAfterAsMinimumWithoutImmediateRetry() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            fixture.respond(ignored -> new SlackTransportFixture.Response(429, Map.of("Retry-After", "37"), "{}"));
            try (final var transport = fixture.socket(1024, ignored -> Optional.empty())) {
                transport.start();
                fixture.take(fixture.scheduler.tasks).run();
                final var retry = fixture.take(fixture.scheduler.tasks);
                assertEquals(Duration.ofSeconds(37).toNanos(), retry.delay);
                assertEquals(SlackSocketMode.State.RECONNECTING, transport.state());
                assertEquals(1, fixture.extended.getRequestCount());
                assertTrue(fixture.sessions.isEmpty());
            }
        }
    }

    @Test
    void wrongAppCredentialDisablesWithoutNetworkOrReconnect() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            final var api = new SlackWebApi(
                    fixture.json,
                    fixture.extended,
                    new SlackWebApi.Settings(fixture.base(), Duration.ofMillis(200), 1024, 1024),
                    () -> "xoxb-wrong-token-type",
                    () -> "xoxb-secret");
            try (final var transport = new SlackSocketMode(
                    fixture.json,
                    api,
                    fixture.http,
                    fixture.scheduler,
                    new SlackSocketMode.Settings(
                            Duration.ofMillis(200), Duration.ofMillis(10), Duration.ofMillis(80), 1024),
                    ignored -> Optional.empty(),
                    () -> 0.5)) {
                transport.start();
                fixture.take(fixture.scheduler.tasks).run();
                assertEquals(SlackSocketMode.State.DISABLED, transport.state());
                assertTrue(fixture.requests.isEmpty());
                assertTrue(fixture.scheduler.tasks.isEmpty());
            }
        }
    }

    @Test
    void closeBeforeScheduledStartIsTerminalAndCancelsWork() throws Exception {
        try (final var fixture = new SlackTransportFixture();
                final var transport = fixture.socket(1024, ignored -> Optional.empty())) {
            assertEquals(SlackSocketMode.State.NEW, transport.state());
            transport.start();
            transport.start();
            assertEquals(1, fixture.scheduler.tasks.size());
            final var task = fixture.take(fixture.scheduler.tasks);
            Assertions.assertDoesNotThrow(transport::close);
            assertTrue(task.isCancelled());
            task.run();
            assertTrue(fixture.requests.isEmpty());
            assertEquals(SlackSocketMode.State.CLOSED, transport.state());
        }
    }

    @Test
    void closeDuringDiscoveryPreventsLateWebSocketConnection() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            final var entered = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            fixture.respond(request -> {
                fixture.expectedDisconnectedResponses.add(request);
                entered.countDown();
                try {
                    if (!release.await(200, TimeUnit.MILLISECONDS)) {
                        throw new IllegalStateException("Discovery was not released");
                    }
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return new SlackTransportFixture.Response(
                        200, Map.of(), "{\"ok\":true,\"url\":\"" + fixture.socketUrl() + "\"}");
            });
            try (final var transport = fixture.socket(1024, ignored -> Optional.empty())) {
                transport.start();
                fixture.take(fixture.scheduler.tasks).run();
                assertTrue(entered.await(200, TimeUnit.MILLISECONDS));
                Assertions.assertDoesNotThrow(transport::close);
                release.countDown();
                fixture.take(fixture.responded);
                assertEquals(SlackSocketMode.State.CLOSED, transport.state());
                assertTrue(fixture.sessions.isEmpty());
                assertTrue(fixture.scheduler.tasks.isEmpty());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void pingPongMaintainsDemandForSubsequentEnvelopes() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            fixture.socketDiscovery();
            try (final var transport = fixture.socket(1024, ignored -> Optional.empty())) {
                final var peer = start(fixture, transport);
                peer.frame(0x89, "ping".getBytes(StandardCharsets.UTF_8));
                assertEquals("ping", fixture.take(peer.pongs));
                peer.text("{\"envelope_id\":\"after-ping\"}", true, false);
                assertEquals(
                        Map.of("envelope_id", "after-ping"),
                        fixture.json.fromString(Object.class, fixture.take(peer.messages)));
            }
        }
    }

    private SlackTransportFixture.Session start(final SlackTransportFixture fixture, final SlackSocketMode transport)
            throws Exception {
        transport.start();
        fixture.take(fixture.scheduler.tasks).run();
        final var session = fixture.take(fixture.sessions);
        assertEquals(
                "Bearer xapp-secret", fixture.take(fixture.requests).headers().get("authorization"));
        assertNull(fixture.take(fixture.requests).headers().get("authorization"));
        return session;
    }
}
