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
package io.yupiik.ia.agent.slack.service;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.json.JsonException;
import io.yupiik.ia.agent.slack.model.SlackTask;
import io.yupiik.ia.agent.slack.store.SlackStore;
import io.yupiik.ia.agent.slack.support.SlackDomainFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

class SlackAdmissionTest {
    @TempDir
    Path root;

    @Test
    @ResourceLock(value = "slack-admission-logger", mode = ResourceAccessMode.READ_WRITE)
    void diagnosticsExplainDeniedChannelAndKeepAdmissionContentPrivate() throws Exception {
        final var logger = Logger.getLogger(SlackAdmission.class.getName());
        final var previous = logger.getLevel();
        final var threadId = Thread.currentThread().threadId();
        final var records = new CopyOnWriteArrayList<LogRecord>();
        final var handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                if (record.getLongThreadID() == threadId) {
                    records.add(record);
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        logger.setLevel(Level.FINE);
        try (final var fixture = new SlackDomainFixture(root, 1, 1)) {
            Thread.ofVirtual()
                    .start(() -> logger.fine("Unrelated concurrent admission diagnostic"))
                    .join();
            assertTrue(records.isEmpty(), "Diagnostics from concurrent tests must not enter this capture");
            final var admission = new SlackAdmission(
                    fixture.configuration, new SlackMentionParser(), fixture.store, fixture.actions, Clock.systemUTC());
            assertTrue(admission
                    .admit(fixture.payload(event("Ev1", "U1", "CSECRET", "1.1")))
                    .isEmpty());
            assertFalse(Files.exists(fixture.configuration.stateRoot()));
            assertEquals(
                    "Slack admission denied: channel_not_approved",
                    records.getLast().getMessage());
            assertEquals(Level.FINE, records.getLast().getLevel());
            final var payload = fixture.payload(event("Ev1", "U1", "C1", "1.1"));
            final var id = admission.admit(payload).orElseThrow();
            assertEquals(id, fixture.store.list().getFirst().id());
            assertEquals(id, admission.admit(payload).orElseThrow());
            assertTrue(admission
                    .admit(fixture.payload(event("Ev2", "U2", "C1", "1.1")))
                    .isEmpty());
            assertEquals(
                    List.of(
                            "Slack admission denied: channel_not_approved",
                            "Slack admission accepted task",
                            "Slack admission: duplicate",
                            "Slack admission: queue_full"),
                    records.stream().map(LogRecord::getMessage).toList());
            assertEquals(
                    List.of(Level.FINE, Level.INFO, Level.FINE, Level.FINE),
                    records.stream().map(LogRecord::getLevel).toList());
            for (final var record : records) {
                assertNull(record.getThrown());
                assertNull(record.getParameters());
            }
            records.clear();
            final var failingStore = new SlackStore(fixture.configuration, fixture.json) {
                @Override
                public <T> T transaction(final Function<List<SlackTask>, T> operation) {
                    operation.apply(new ArrayList<>());
                    throw new IllegalStateException("private-store-failure");
                }
            };
            assertThrows(
                    IllegalStateException.class,
                    () -> new SlackAdmission(
                                    fixture.configuration,
                                    new SlackMentionParser(),
                                    failingStore,
                                    fixture.actions,
                                    Clock.systemUTC())
                            .admit(payload));
            assertTrue(records.isEmpty(), "No acceptance diagnostic before durable transaction success");
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
    }

    @Test
    void denyUnauthorizedAndNonUserEventsBeforeStateCreation() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            final var admission = new SlackAdmission(
                    fixture.configuration, new SlackMentionParser(), fixture.store, fixture.actions, Clock.systemUTC());
            for (final var field : new String[] {"team_id", "type", "event_id"}) {
                final var invalid = new HashMap<>(event("Ev1", "U1", "C1", "1.1"));
                invalid.put(field, "wrong");
                assertTrue(admission.admit(fixture.payload(invalid)).isEmpty(), field);
            }
            for (final var override : List.of(
                    Map.of("user", "U3"),
                    Map.of("user", "UBOT"),
                    Map.of("channel", "C3"),
                    Map.of("bot_id", "B1"),
                    Map.of("subtype", "message_changed"),
                    Map.of("type", "message"),
                    Map.of("thread_ts", "not-a-timestamp"),
                    Map.of("text", "no mention"),
                    Map.of("is_ext_shared_channel", true))) {
                final var invalid = new HashMap<>(event("Ev1", "U1", "C1", "1.1"));
                final Map<Object, Object> inner = new HashMap<>((Map<?, ?>) invalid.get("event"));
                inner.putAll(override);
                invalid.put("event", inner);
                assertTrue(admission.admit(fixture.payload(invalid)).isEmpty(), override.toString());
            }
            final var shared = new HashMap<>(event("Ev1", "U1", "C1", "1.1"));
            shared.put("is_ext_shared_channel", true);
            assertTrue(admission.admit(fixture.payload(shared)).isEmpty());
            shared.remove("is_ext_shared_channel");
            assertTrue(admission.admit(fixture.payload(shared)).isEmpty());
            shared.put("is_ext_shared_channel", "false");
            assertThrows(JsonException.class, () -> fixture.payload(shared));
            assertFalse(Files.exists(fixture.configuration.stateRoot()));
        }
    }

    @Test
    void channelMembersStillRequireTrustedWorkspaceChannelAndNonSharedUserEvents() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            final var configuration = fixture.channelMemberConfiguration();
            final var admission = new SlackAdmission(
                    configuration, new SlackMentionParser(), fixture.store, fixture.actions, Clock.systemUTC());
            for (final var override : List.of(
                    Map.of("user", "UBOT"),
                    Map.of("user", "B1"),
                    Map.of("user", "U.*"),
                    Map.of("user", ""),
                    Map.of("user", "U" + "A".repeat(64)),
                    Map.of("channel", "C3"),
                    Map.of("bot_id", "B1"),
                    Map.of("subtype", "message_changed"),
                    Map.of("is_ext_shared_channel", true))) {
                final var invalid = new HashMap<>(event("Ev1", "U3", "C1", "1.1"));
                final Map<Object, Object> inner = new HashMap<>((Map<?, ?>) invalid.get("event"));
                inner.putAll(override);
                invalid.put("event", inner);
                assertTrue(admission.admit(fixture.payload(invalid)).isEmpty(), override.toString());
            }
            for (final var override : List.of(Map.of("team_id", "T2"), Map.of("is_ext_shared_channel", true))) {
                final var invalid = new HashMap<>(event("Ev1", "U3", "C1", "1.1"));
                invalid.putAll(override);
                assertTrue(admission.admit(fixture.payload(invalid)).isEmpty());
            }
            final var missingSharedMarker = new HashMap<>(event("Ev1", "U3", "C1", "1.1"));
            missingSharedMarker.remove("is_ext_shared_channel");
            assertTrue(admission.admit(fixture.payload(missingSharedMarker)).isEmpty());
            assertFalse(Files.exists(configuration.stateRoot()));
            assertTrue(admission
                    .admit(fixture.payload(event("Ev1", "U3", "C1", "1.1")))
                    .isPresent());
            assertTrue(admission
                    .admit(fixture.payload(event("Ev2", "W3", "C1", "1.1")))
                    .isPresent());
            assertEquals(
                    List.of("U3", "W3"),
                    fixture.store.list().stream().map(task -> task.requester()).toList());
        }
    }

    @Test
    void preservesEventAndTimestampBoundsWithTypedPayloads() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            final var admission = new SlackAdmission(
                    fixture.configuration, new SlackMentionParser(), fixture.store, fixture.actions, Clock.systemUTC());
            assertTrue(admission
                    .admit(fixture.payload(event("Ev" + "1".repeat(127), "U1", "C1", "1.1")))
                    .isEmpty());
            for (final var thread : List.of("1".repeat(21) + ".1", "1." + "1".repeat(21), "1", "-1.1")) {
                assertTrue(admission
                        .admit(fixture.payload(event("Ev1", "U1", "C1", thread)))
                        .isEmpty());
            }
            assertFalse(Files.exists(fixture.configuration.stateRoot()));
            assertTrue(admission
                    .admit(fixture.payload(
                            event("Ev" + "1".repeat(126), "U1", "C1", "1".repeat(20) + "." + "1".repeat(20))))
                    .isPresent());
        }
    }

    @Test
    void dedupePrecedesQuotaAndSurvivesNewStoreInstance() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 1, 1)) {
            final var admission = new SlackAdmission(
                    fixture.configuration, new SlackMentionParser(), fixture.store, fixture.actions, Clock.systemUTC());
            final var payload = event("Ev1", "U1", "C1", "1.1");
            final var id = admission.admit(fixture.payload(payload)).orElseThrow();
            assertEquals(id, admission.admit(fixture.payload(payload)).orElseThrow());
            assertTrue(admission
                    .admit(fixture.payload(event("Ev2", "U2", "C1", "1.1")))
                    .isEmpty());
            final var reopened = new SlackStore(fixture.configuration, fixture.json);
            assertEquals(1, reopened.list().size());
            assertEquals(
                    id,
                    new SlackAdmission(
                                    fixture.configuration,
                                    new SlackMentionParser(),
                                    reopened,
                                    fixture.actions,
                                    Clock.systemUTC())
                            .admit(fixture.payload(payload))
                            .orElseThrow());
        }
    }

    @Test
    void conversationIsOwnedByRequesterAndScopedToChannelAndThread() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 6, 4)) {
            final var admission = new SlackAdmission(
                    fixture.configuration, new SlackMentionParser(), fixture.store, fixture.actions, Clock.systemUTC());
            admission.admit(fixture.payload(event("Ev1", "U1", "C1", "1.1"))).orElseThrow();
            admission.admit(fixture.payload(event("Ev2", "U1", "C1", "1.1"))).orElseThrow();
            admission.admit(fixture.payload(event("Ev3", "U2", "C1", "1.1"))).orElseThrow();
            admission.admit(fixture.payload(event("Ev4", "U1", "C2", "1.1"))).orElseThrow();
            admission.admit(fixture.payload(event("Ev5", "U1", "C1", "2.1"))).orElseThrow();
            final var tasks = fixture.store.list();
            assertEquals(tasks.get(0).conversation(), tasks.get(1).conversation());
            assertEquals(
                    4,
                    tasks.stream().map(task -> task.conversation()).distinct().count());
            assertEquals(SlackStore.identity("T1:C1:1.1:U1"), tasks.getFirst().conversation());
        }
    }

    @Test
    void requesterQuotaDoesNotConsumeOtherRequestersCapacity() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 3, 1)) {
            final var admission = new SlackAdmission(
                    fixture.configuration, new SlackMentionParser(), fixture.store, fixture.actions, Clock.systemUTC());
            admission.admit(fixture.payload(event("Ev1", "U1", "C1", "1.1"))).orElseThrow();
            assertTrue(admission
                    .admit(fixture.payload(event("Ev2", "U1", "C1", "2.1")))
                    .isEmpty());
            assertTrue(admission
                    .admit(fixture.payload(event("Ev3", "U2", "C1", "1.1")))
                    .isPresent());
        }
    }

    private Map<String, Object> event(final String id, final String user, final String channel, final String thread) {
        return Map.of(
                "type",
                "event_callback",
                "team_id",
                "T1",
                "event_id",
                id,
                "is_ext_shared_channel",
                false,
                "event",
                Map.of(
                        "type",
                        "app_mention",
                        "user",
                        user,
                        "channel",
                        channel,
                        "ts",
                        thread,
                        "thread_ts",
                        thread,
                        "text",
                        "<@UBOT> Please explain this error."));
    }
}
