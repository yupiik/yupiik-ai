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
package io.yupiik.ia.agent.slack.context;

import static io.yupiik.ia.agent.slack.support.SlackDomainFixture.task;
import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.slack.configuration.SlackContextConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackThreadConfiguration;
import io.yupiik.ia.agent.slack.model.SlackTask;
import io.yupiik.ia.agent.slack.model.TaskStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SlackActionRegistryTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);

    @Test
    void exactThreadCutoffIsAnImmutableAdmissionSnapshot() {
        final var queued = task("thread", "U1", TaskStatus.queued, NOW);
        final var cutoff = "1.10000000000000000001";
        try (final var actions = new SlackActionRegistry(configuration(true, true), CLOCK)) {
            actions.admitted(queued, "first", cutoff, NOW);
            actions.admitted(queued, "replacement", "2.0", NOW + 300_000);
            try (final var grant = actions.claim(queued, 0, NOW + 599_999).orElseThrow()) {
                assertEquals(queued.thread(), grant.threadRoot());
                assertEquals(cutoff, grant.triggerTs());
                assertEquals("first", grant.get());
                assertEquals(NOW + 600_000, grant.deadline());
                assertFalse(grant.active(NOW + 600_000));
            }
        }
    }

    @Test
    void malformedOrEarlierCutoffsCannotCreateAuthority() {
        final var queued = task("invalid-cutoff", "U1", TaskStatus.queued, NOW);
        for (final var cutoff : new String[] {null, "", "1", "1e1", "NaN", " 2.0", "1.09999999999999999999"}) {
            try (final var actions = new SlackActionRegistry(configuration(true, true), CLOCK)) {
                actions.admitted(queued, "secret", cutoff, NOW);
                assertTrue(actions.claim(queued, 0, NOW).isEmpty(), cutoff);
            }
        }
    }

    @Test
    void changedThreadOrRequestConsumesButCannotClaimAuthority() {
        final var queued = task("changed-identity", "U1", TaskStatus.queued, NOW);
        for (final var changeThread : List.of(false, true)) {
            final var changed = new SlackTask(
                    queued.id(),
                    queued.workspace(),
                    queued.channel(),
                    changeThread ? "2.0" : queued.thread(),
                    queued.requester(),
                    changeThread ? queued.request() : "Changed request",
                    queued.conversation(),
                    queued.status(),
                    queued.created(),
                    queued.updated(),
                    queued.attempt(),
                    queued.attemptPath(),
                    queued.runtimeId(),
                    queued.fingerprint(),
                    queued.answer(),
                    queued.messageTs(),
                    queued.question(),
                    queued.contextBearing(),
                    queued.publicationId());
            try (final var actions = new SlackActionRegistry(configuration(true, true), CLOCK)) {
                actions.admitted(queued, "secret", "3.0", NOW);
                assertTrue(actions.claim(changed, 0, NOW).isEmpty());
                assertTrue(actions.claim(queued, 0, NOW).isEmpty());
            }
        }
    }

    @Test
    void maintenanceSnapshotsAdmissionStateAndInvokesCancellationOutsideItsLock() throws Exception {
        final var queued = task("snapshot", "U1", TaskStatus.queued, NOW);
        try (final var actions = new SlackActionRegistry(configuration(true, true), CLOCK);
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var current = new AtomicReference<Set<String>>(Set.of());
            actions.admission(() -> {
                current.set(Set.of(queued.id())); // Represents the durable commit before registration.
                actions.admitted(queued, "secret", queued.thread(), NOW);
                return null;
            });
            actions.expire(NOW, current::get);
            final var grant = actions.claim(queued, 0, NOW).orElseThrow();
            final var cancellations = new AtomicInteger();
            grant.onRevoke(() -> {
                try {
                    // This other thread must be able to enter admission while a callback is running.
                    executor.submit(() -> actions.admission(cancellations::incrementAndGet))
                            .get(5, TimeUnit.SECONDS);
                } catch (final Exception failure) {
                    throw new IllegalStateException("Cancellation ran under the registry lock", failure);
                }
            });
            current.set(Set.of());
            actions.expire(NOW, current::get);
            assertEquals(1, cancellations.get());
            assertNull(grant.get());
        }
    }

    @Test
    void memberOnlyAuthorityExistsWithoutSearchOrValidTokenButDisabledContextHasNone() {
        final var queued = task("member", "U1", TaskStatus.queued, NOW);
        for (final var search : List.of(false, true)) {
            for (final var token : new String[] {null, "", "invalid token", "valid-secret"}) {
                try (final var actions = new SlackActionRegistry(configuration(true, search), CLOCK)) {
                    actions.admitted(queued, token, queued.thread(), NOW);
                    try (final var grant = actions.claim(queued, 0, NOW).orElseThrow()) {
                        assertTrue(grant.active(NOW));
                        assertEquals("T1", grant.workspace());
                        assertEquals("C1", grant.channel());
                        assertEquals("U1", grant.requester());
                        assertEquals(queued.request(), grant.request());
                        assertEquals(NOW, grant.admittedAt());
                        assertEquals(NOW + 600_000, grant.deadline());
                        assertEquals(search && "valid-secret".equals(token) ? token : null, grant.get());
                        assertFalse(grant.toString().contains("valid-secret"));
                    }
                }
            }
        }
        try (final var actions = new SlackActionRegistry(configuration(false, true), CLOCK)) {
            actions.admitted(queued, "secret", queued.thread(), NOW);
            assertTrue(actions.claim(queued, 0, NOW).isEmpty());
        }
    }

    @Test
    void duplicateCannotReplaceRenewOrReplenishAClaimedGrant() {
        final var queued = task("first", "U1", TaskStatus.queued, NOW);
        try (final var actions = new SlackActionRegistry(configuration(true, true), CLOCK)) {
            actions.admitted(queued, "first-secret", queued.thread(), NOW);
            actions.admitted(queued, "replacement-secret", queued.thread(), NOW + 300_000);
            try (final var grant = actions.claim(queued, 0, NOW + 300_000).orElseThrow()) {
                assertEquals("first-secret", grant.get());
                assertEquals(NOW + 600_000, grant.deadline());
                actions.admitted(queued, "replacement-secret", queued.thread(), NOW + 400_000);
                assertTrue(actions.claim(queued, 0, NOW + 400_000).isEmpty());
                Assertions.assertDoesNotThrow(grant::close);
                actions.admitted(queued, "replacement-secret", queued.thread(), NOW + 500_000);
                assertTrue(actions.claim(queued, 0, NOW + 500_000).isEmpty());
            }
        }
    }

    @Test
    void durableIdentityRetriesRestartAndDelayedQueueCannotCreateAuthority() {
        final var queued = task("first", "U1", TaskStatus.queued, NOW);
        try (final var actions = new SlackActionRegistry(configuration(true, true), CLOCK)) {
            assertTrue(actions.claim(queued, 0, NOW).isEmpty(), "Recovered work has no in-memory authority");
            actions.admitted(queued, "secret", queued.thread(), NOW);
            assertTrue(actions.claim(queued, 1, NOW).isEmpty());
            assertTrue(actions.claim(queued, 0, NOW).isEmpty(), "Rejected retries consume the original grant");
            final var delayed = task("delayed", "U1", TaskStatus.queued, NOW);
            actions.admitted(delayed, "secret", delayed.thread(), NOW);
            assertTrue(actions.claim(delayed, 0, NOW + 600_000).isEmpty());
            final var mismatched = task("mismatch", "U1", TaskStatus.queued, NOW);
            actions.admitted(mismatched, "secret", mismatched.thread(), NOW);
            assertTrue(actions.claim(task("mismatch", "U2", TaskStatus.queued, NOW), 0, NOW)
                    .isEmpty());
            assertTrue(actions.claim(mismatched, 0, NOW).isEmpty());
        }
        try (final var restarted = new SlackActionRegistry(configuration(true, true), CLOCK)) {
            assertTrue(restarted.claim(queued, 0, NOW).isEmpty());
        }
    }

    @Test
    void maintenanceRevokesConsumedGrantsWithoutPollingAndCallbacksAreIdempotent() {
        final var queued = task("active", "U1", TaskStatus.queued, NOW);
        final var callbacks = new AtomicInteger();
        try (final var actions = new SlackActionRegistry(configuration(true, true), CLOCK)) {
            actions.admitted(queued, "secret", queued.thread(), NOW);
            final var grant = actions.claim(queued, 0, NOW).orElseThrow();
            grant.onRevoke(callbacks::incrementAndGet);
            actions.expire(NOW + 599_999, () -> Set.of(queued.id()));
            assertEquals(0, callbacks.get());
            assertTrue(grant.active(NOW + 599_999));
            assertFalse(grant.active(NOW + 600_000), "Validity does not wait for a sweep");
            actions.expire(NOW + 600_000, () -> Set.of(queued.id()));
            assertNull(grant.get());
            assertEquals("authorization_expired", grant.status());
            assertEquals(1, callbacks.get());
            actions.expire(NOW + 600_001, () -> Set.of(queued.id()));
            grant.close();
            Assertions.assertDoesNotThrow(actions::close);
            assertEquals(1, callbacks.get());
            grant.onRevoke(callbacks::incrementAndGet);
            assertEquals(2, callbacks.get(), "A late listener must observe terminal revocation");
        }
    }

    @Test
    void terminalTasksAndControllerShutdownRevokeActiveAndPendingGrants() {
        final var running = task("running", "U1", TaskStatus.running, NOW);
        final var queued = task("queued", "U1", TaskStatus.queued, NOW);
        final var callbacks = new AtomicInteger();
        try (final var actions = new SlackActionRegistry(configuration(true, true), CLOCK)) {
            actions.admitted(running, "secret", running.thread(), NOW);
            actions.admitted(queued, "secret", queued.thread(), NOW);
            final var grant = actions.claim(running, 0, NOW).orElseThrow();
            grant.onRevoke(callbacks::incrementAndGet);
            actions.expire(NOW, () -> Set.of(queued.id()));
            assertEquals(1, callbacks.get());
            assertNull(grant.get());
            final var pending = actions.claim(queued, 0, NOW).orElseThrow();
            pending.onRevoke(callbacks::incrementAndGet);
            Assertions.assertDoesNotThrow(actions::close);
            assertEquals(2, callbacks.get());
            assertNull(pending.get());
            assertFalse(pending.active(NOW));
            assertTrue(actions.claim(queued, 0, NOW).isEmpty());
        }
    }

    private static SlackContextConfiguration configuration(final boolean enabled, final boolean search) {
        return new SlackContextConfiguration(
                enabled,
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
    }
}
