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

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.slack.configuration.SlackConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackContextConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackPrompts;
import io.yupiik.ia.agent.slack.configuration.SlackThreadConfiguration;
import io.yupiik.ia.agent.slack.context.SlackActionRegistry;
import io.yupiik.ia.agent.slack.model.SlackTask;
import io.yupiik.ia.agent.slack.model.TaskStatus;
import io.yupiik.ia.agent.slack.store.SlackStore;
import io.yupiik.ia.agent.slack.support.SlackConfigurationTestModule;
import io.yupiik.ia.agent.slack.support.SlackDomainFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

@FusionSupport(modules = SlackConfigurationTestModule.class)
class SlackAdmissionAuthorityRegressionTest {
    @Fusion
    private SlackPrompts baselinePrompts;

    private static final String TOKEN = "private-admission-action-token";
    private static final String DUPLICATE_TOKEN = "private-duplicate-action-token";

    @TempDir
    Path root;

    @Test
    void triggeringEventTimestampIsSeparateFromRootAndRemainsEphemeral() throws Exception {
        final var now = 1_800_000_000_000L;
        final var clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC);
        final var cutoff = "9.10000000000000000001";
        try (final var fixture = new SlackDomainFixture(root, 4, 4);
                final var actions = new SlackActionRegistry(contextConfiguration(), clock)) {
            final var admission =
                    new SlackAdmission(fixture.configuration, new SlackMentionParser(), fixture.store, actions, clock);
            final var payload = new HashMap<>(event("EvThreadCutoff", TOKEN, false, false));
            final var message = new HashMap<String, Object>();
            ((Map<?, ?>) payload.get("event")).forEach((key, value) -> message.put(key.toString(), value));
            message.put("thread_ts", "9.1");
            message.put("ts", cutoff);
            payload.put("event", message);
            final var id = admission.admit(fixture.payload(payload)).orElseThrow();
            final var task = fixture.store.list().getFirst();
            assertEquals("9.1", task.thread());
            assertFalse(fixture.json.toString(task).contains(cutoff));
            message.put("ts", "10.0");
            assertEquals(id, admission.admit(fixture.payload(payload)).orElseThrow());
            try (final var grant = actions.claim(task, 0, now).orElseThrow()) {
                assertEquals("9.1", grant.threadRoot());
                assertEquals(cutoff, grant.triggerTs(), "Duplicate events cannot move the cutoff");
                assertEquals(now + 600_000, grant.deadline());
            }
            try (final var restarted = new SlackActionRegistry(contextConfiguration(), clock)) {
                assertEquals(
                        id,
                        new SlackAdmission(
                                        fixture.configuration,
                                        new SlackMentionParser(),
                                        fixture.store,
                                        restarted,
                                        clock)
                                .admit(fixture.payload(payload))
                                .orElseThrow());
                assertTrue(restarted.claim(task, 0, now).isEmpty());
            }
        }
    }

    @Test
    void malformedOrPreciselyEarlierEventTimestampRejectsAdmissionEvenWithValidRoot() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 4);
                final var actions = new SlackActionRegistry(contextConfiguration(), Clock.systemUTC())) {
            final var admission = new SlackAdmission(
                    fixture.configuration, new SlackMentionParser(), fixture.store, actions, Clock.systemUTC());
            for (final var timestamp : List.of("bad", "9", "9e1", "9.09999999999999999999")) {
                final var payload = new HashMap<>(event("EvInvalidCutoff", TOKEN, false, false));
                final var message = new HashMap<String, Object>();
                ((Map<?, ?>) payload.get("event")).forEach((key, value) -> message.put(key.toString(), value));
                message.put("thread_ts", "9.1");
                message.put("ts", timestamp);
                payload.put("event", message);
                assertTrue(admission.admit(fixture.payload(payload)).isEmpty(), timestamp);
                assertTrue(fixture.store.list().isEmpty());
            }
        }
    }

    @Test
    @ResourceLock(value = "slack-admission-logger", mode = ResourceAccessMode.READ_WRITE)
    void durableAdmissionRegistersOnlyOnceAndNeverPersistsOrLogsAuthority() throws Exception {
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
        final var context = contextConfiguration();
        try (final var fixture = new SlackDomainFixture(root, 4, 4);
                final var actions = new SlackActionRegistry(context, Clock.systemUTC())) {
            final var admission = new SlackAdmission(
                    fixture.configuration, new SlackMentionParser(), fixture.store, actions, Clock.systemUTC());
            final var payload = fixture.payload(event("EvAuthority", TOKEN, false, false));
            final var duplicate = fixture.payload(event("EvAuthority", DUPLICATE_TOKEN, false, false));
            final var id = admission.admit(payload).orElseThrow();
            final var reopened = new SlackStore(fixture.configuration, fixture.json);
            assertEquals(id, reopened.list().getFirst().id(), "Authority belongs to a durably accepted task");
            assertEquals(id, admission.admit(duplicate).orElseThrow());
            try (final var grant = actions.claim(reopened.list().getFirst(), 0, System.currentTimeMillis())
                    .orElseThrow()) {
                assertEquals("ok", grant.status());
                assertEquals(TOKEN, grant.get(), "A duplicate must not replace the first accepted token");
            }
            assertEquals(id, admission.admit(duplicate).orElseThrow());
            assertTrue(
                    actions.claim(reopened.list().getFirst(), 0, System.currentTimeMillis())
                            .isEmpty(),
                    "Duplicate delivery must not replenish consumed authority");

            final var expiring = admission
                    .admit(fixture.payload(event("EvNoRenewal", TOKEN, false, false)))
                    .orElseThrow();
            actions.expire(
                    System.currentTimeMillis() + (context.actionRetentionSeconds() + 1L) * 1000,
                    () -> Set.of(expiring));
            assertEquals(
                    expiring,
                    admission
                            .admit(fixture.payload(event("EvNoRenewal", DUPLICATE_TOKEN, false, false)))
                            .orElseThrow());
            assertTrue(
                    actions.claim(
                                    fixture.store.list().stream()
                                            .filter(task -> task.id().equals(expiring))
                                            .findFirst()
                                            .orElseThrow(),
                                    0,
                                    System.currentTimeMillis())
                            .isEmpty(),
                    "Duplicate delivery must not renew expired pending authority");

            final var failingStore = new SlackStore(fixture.configuration, fixture.json) {
                @Override
                public <T> T transaction(final Function<List<SlackTask>, T> operation) {
                    operation.apply(new ArrayList<>());
                    throw new IllegalStateException("durable-commit-failed");
                }
            };
            assertThrows(
                    IllegalStateException.class,
                    () -> new SlackAdmission(
                                    fixture.configuration,
                                    new SlackMentionParser(),
                                    failingStore,
                                    actions,
                                    Clock.systemUTC())
                            .admit(fixture.payload(event("EvFailedCommit", TOKEN, false, false))));
            assertTrue(
                    actions.claim(
                                    SlackDomainFixture.task(
                                            SlackStore.identity("T1:EvFailedCommit"),
                                            "U1",
                                            TaskStatus.queued,
                                            System.currentTimeMillis()),
                                    0,
                                    System.currentTimeMillis())
                            .isEmpty(),
                    "Failed durable admission must not register a grant");

            final var renderer = new SlackPromptRenderer(SlackDomainFixture.prompts(baselinePrompts), fixture.json);
            for (final var task : reopened.list()) {
                assertRedacted(fixture.json.toString(task));
                assertRedacted(renderer.render(task, reopened.list(), true, Map.of()));
            }
            try (final var files = Files.walk(fixture.configuration.stateRoot())) {
                for (final var file : files.filter(Files::isRegularFile).toList()) {
                    assertRedacted(Files.readString(file));
                }
            }
            assertFalse(records.isEmpty());
            for (final var record : records) {
                assertRedacted(record.getMessage());
                assertNull(record.getParameters());
                assertNull(record.getThrown());
            }
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
    }

    @Test
    void admissionCreatesMemberAuthorityWithoutSearchOrTokenWithTheOriginalDeadline() throws Exception {
        final var now = 1_800_000_000_000L;
        final var clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC);
        try (final var fixture = new SlackDomainFixture(root, 4, 4)) {
            for (final var search : List.of(false, true)) {
                final var context = new SlackContextConfiguration(
                        true,
                        true,
                        search,
                        List.of("C1"),
                        30,
                        32,
                        12,
                        20,
                        131072,
                        20,
                        2,
                        600,
                        false,
                        new SlackThreadConfiguration(false, List.of(), 20, 100, 65536, 3, 24),
                        false);
                try (final var actions = new SlackActionRegistry(context, clock)) {
                    final var admission = new SlackAdmission(
                            fixture.configuration, new SlackMentionParser(), fixture.store, actions, clock);
                    final var payload = fixture.payload(event("EvMember" + search, "", false, false));
                    final var id = admission.admit(payload).orElseThrow();
                    final var queued = fixture.store.list().stream()
                            .filter(task -> task.id().equals(id))
                            .findFirst()
                            .orElseThrow();
                    assertEquals(now, queued.created());
                    final var later = new SlackAdmission(
                            fixture.configuration,
                            new SlackMentionParser(),
                            fixture.store,
                            actions,
                            Clock.offset(clock, Duration.ofSeconds(300)));
                    assertEquals(id, later.admit(payload).orElseThrow());
                    try (final var grant =
                            actions.claim(queued, 0, now + 300_000).orElseThrow()) {
                        assertEquals("U1", grant.requester());
                        assertEquals(now, grant.admittedAt());
                        assertEquals(now + 600_000, grant.deadline());
                        assertTrue(grant.active(now + 599_999));
                        assertFalse(grant.active(now + 600_000));
                        assertNull(grant.get());
                    }
                    try (final var restarted = new SlackActionRegistry(context, clock)) {
                        assertEquals(
                                id,
                                new SlackAdmission(
                                                fixture.configuration,
                                                new SlackMentionParser(),
                                                fixture.store,
                                                restarted,
                                                clock)
                                        .admit(payload)
                                        .orElseThrow());
                        assertTrue(
                                restarted.claim(queued, 0, now).isEmpty(),
                                "Durable duplicate after restart must not recreate even member authority");
                    }
                }
            }
        }
    }

    @Test
    void admittedSlackConnectEventsNeverRegisterActionAuthority() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 4);
                final var actions = new SlackActionRegistry(contextConfiguration(), Clock.systemUTC())) {
            final var base = fixture.configuration;
            final var sharedAllowed = new SlackConfiguration(
                    base.workspace(),
                    base.botUser(),
                    base.channels(),
                    base.users(),
                    base.allowChannelMembers(),
                    true,
                    base.appTokenEnv(),
                    base.botTokenEnv(),
                    base.home(),
                    base.agentConfiguration(),
                    base.maxPending(),
                    base.maxPendingPerUser(),
                    base.maxInputChars(),
                    base.maxAnswerChars(),
                    base.questionTimeoutSeconds(),
                    base.retentionSeconds());
            final var admission = new SlackAdmission(
                    sharedAllowed, new SlackMentionParser(), fixture.store, actions, Clock.systemUTC());
            for (final var markers : List.of(List.of(true, false), List.of(false, true), List.of(true, true))) {
                final var id = admission
                        .admit(fixture.payload(
                                event("EvShared" + fixture.store.list().size(), TOKEN, markers.get(0), markers.get(1))))
                        .orElseThrow();
                assertTrue(fixture.store.list().stream().anyMatch(task -> id.equals(task.id())));
                assertTrue(
                        actions.claim(
                                        fixture.store.list().stream()
                                                .filter(task -> task.id().equals(id))
                                                .findFirst()
                                                .orElseThrow(),
                                        0,
                                        System.currentTimeMillis())
                                .isEmpty(),
                        "Allowing shared-channel admission does not grant context authority");
            }
        }
    }

    private static SlackContextConfiguration contextConfiguration() {
        return new SlackContextConfiguration(
                true,
                true,
                true,
                List.of("C1"),
                30,
                32,
                12,
                20,
                131072,
                20,
                2,
                600,
                false,
                new SlackThreadConfiguration(false, List.of(), 20, 100, 65536, 3, 24),
                false);
    }

    private static Map<String, Object> event(
            final String id, final String token, final boolean payloadShared, final boolean eventShared) {
        return Map.of(
                "type",
                "event_callback",
                "team_id",
                "T1",
                "event_id",
                id,
                "is_ext_shared_channel",
                payloadShared,
                "event",
                Map.of(
                        "type", "app_mention",
                        "user", "U1",
                        "channel", "C1",
                        "ts", "9.1",
                        "text", "<@UBOT> Please explain this error.",
                        "is_ext_shared_channel", eventShared,
                        "action_token", token));
    }

    private static void assertRedacted(final String text) {
        assertFalse(text.contains(TOKEN));
        assertFalse(text.contains(DUPLICATE_TOKEN));
    }
}
