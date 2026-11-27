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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.a2a.configuration.A2AConfiguration;
import io.yupiik.fusion.a2a.model.Artifact;
import io.yupiik.fusion.a2a.model.Message;
import io.yupiik.fusion.a2a.model.Part;
import io.yupiik.fusion.a2a.model.TaskState;
import io.yupiik.fusion.a2a.model.TaskStatus;
import io.yupiik.fusion.a2a.protocol.A2AErrors;
import io.yupiik.fusion.a2a.protocol.A2AStream;
import io.yupiik.fusion.a2a.test.Configurations;
import io.yupiik.fusion.a2a.test.RecordingPushNotifier;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jsonrpc.JsonRpcException;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * The live task store and the mutable task behind the immutable snapshots clients get.
 * <p>
 * Tasks are held in memory, so eviction is not housekeeping but the only thing keeping a long running agent from growing
 * forever - and it is triggered by a task being created rather than by a background thread, which keeps a native image
 * simple.
 */
@FusionSupport
class A2ATasksTest {
    @Test
    void createAndLookUp() {
        final var tasks = tasks(Configurations.defaults());

        final var task = tasks.create("c-1", Map.of("origin", "test"));
        assertNotNull(task.id(), "the identifier is server generated, as the specification requires");
        assertEquals("c-1", task.contextId());
        assertEquals(TaskState.TASK_STATE_SUBMITTED, task.status().state());

        assertSame(task, tasks.require(task.id()));
        assertSame(task, tasks.find(task.id()).orElseThrow());
        assertEquals(List.of(task), List.copyOf(tasks.tasks()));
        assertEquals(Map.of("origin", "test"), task.snapshot(null, true).metadata());
    }

    @Test
    void anUnknownTaskIsNotFound() {
        final var tasks = tasks(Configurations.defaults());

        assertTrue(tasks.find("nope").isEmpty());
        assertTrue(tasks.find(null).isEmpty(), "a null identifier is nothing to look up, not a different failure");

        final var error = assertThrows(JsonRpcException.class, () -> tasks.require("nope"));
        assertEquals(A2AErrors.TASK_NOT_FOUND, error.code());
        assertThrows(JsonRpcException.class, () -> tasks.require(null));
    }

    @Test
    void updaterForATask() {
        final var tasks = tasks(Configurations.defaults());
        final var task = tasks.create("c-1", null);

        final var updater = tasks.updater(task.id()).orElseThrow();
        assertEquals(task.id(), updater.taskId());
        assertEquals("c-1", updater.contextId());

        // this is how a scheduler - or an incoming webhook - concludes a task from outside any executor
        updater.complete(Artifact.text("answer", "42"));
        assertEquals(TaskState.TASK_STATE_COMPLETED, task.status().state());

        assertTrue(tasks.updater("nope").isEmpty());
    }

    @Test
    void droppingATaskFailsWhoeverWaitsOnIt() {
        final var tasks = tasks(Configurations.defaults());
        final var task = tasks.create("c-1", null);
        final var settled = task.turn().settled();

        assertTrue(tasks.drop(task.id()));

        assertTrue(tasks.find(task.id()).isEmpty());
        final var error = assertThrows(ExecutionException.class, settled::get);
        assertTrue(error.getCause() instanceof JsonRpcException, () -> String.valueOf(error.getCause()));
        assertTrue(error.getCause().getMessage().contains(task.id()), error.getCause()::getMessage);

        // dropping is idempotent, and a null identifier is simply nothing to drop
        assertFalse(tasks.drop(task.id()));
        assertFalse(tasks.drop("nope"));
        assertFalse(tasks.drop(null));
    }

    @Test
    void aStreamIsRegisteredOnTheTask(@Fusion final JsonMapper jsons) {
        final var tasks = tasks(Configurations.defaults());
        final var task = tasks.create("c-1", null);

        final var stream = tasks.newStream(task, sink -> A2AStream.plain(sink, jsons));

        assertEquals(List.of(stream), task.streams());
        assertFalse(stream.isClosed());
    }

    @Test
    void aClosedStreamIsDeregisteredOnTheNextEvent(@Fusion final JsonMapper jsons) {
        final var tasks = tasks(Configurations.defaults());
        final var task = tasks.create("c-1", null);
        final var stream = tasks.newStream(task, sink -> A2AStream.plain(sink, jsons));

        stream.close();
        // publishing is what makes the task notice, so a non terminal event is enough - no need to end the task
        tasks.updater(task.id()).orElseThrow().artifact(Artifact.text("a", "b"));

        assertTrue(stream.isClosed());
        assertEquals(List.of(), task.streams());
    }

    @Test
    void forgettingATaskLeavesNothingBehind() {
        final var tasks = tasks(Configurations.defaults());
        final var task = tasks.create("c-1", null);

        // this is what an executor answering a plain message triggers: the task must not be observable at all
        tasks.forget(task.id());
        assertTrue(tasks.find(task.id()).isEmpty());

        tasks.forget(null); // and it tolerates having nothing to forget
    }

    @Test
    void expiredTasksAreEvictedWhenANewOneIsCreated() throws Exception {
        // a hundred milliseconds of retention: what is asserted is the eviction, not how long a real deployment keeps a
        // task. Dropping and expiring are also logged at FINE, so the level is raised to build those messages here
        // rather than in a second test which would have to wait all over again.
        final var logger = Logger.getLogger(A2ATasks.class.getName());
        final var level = logger.getLevel();
        logger.setLevel(Level.FINEST);
        try {
            final var tasks =
                    tasks(Configurations.configuration().taskRetention("PT0.1S").build());
            final var dropped = tasks.create("c-1", null);
            assertTrue(tasks.drop(dropped.id()));

            final var stale = tasks.create("c-2", null);
            final var settled = stale.turn().settled();

            Thread.sleep(300); // twice the retention over, so a loaded machine cannot make this flake
            final var fresh = tasks.create("c-3", null);

            assertTrue(tasks.find(stale.id()).isEmpty(), "the stale task should have been evicted");
            assertNotNull(tasks.find(fresh.id()).orElseThrow());
            // and whoever was still waiting on it is released rather than left hanging
            assertTrue(settled.isCompletedExceptionally());
        } finally {
            logger.setLevel(level);
        }
    }

    @Test
    void retentionCanBeDisabled() {
        // no sleep: eviction returns before looking at any age, so waiting would prove nothing
        for (final var retention : List.of("0", "PT0S", "-1", "PT-1S")) {
            final var tasks = tasks(
                    Configurations.configuration().taskRetention(retention).build());
            final var task = tasks.create("c-1", null);

            tasks.evictExpired();

            assertNotNull(
                    tasks.find(task.id()).orElseThrow(),
                    () -> "a retention of " + retention + " keeps the tasks forever");
        }
    }

    @Test
    void expiration() {
        final var task = new A2ATask("t-1", "c-1", null, null, Clock.systemUTC());

        assertFalse(task.isExpired(Duration.ofMinutes(30)));
        // zero - and a negative value - means "keep them forever", so nothing is ever expired
        assertFalse(task.isExpired(Duration.ZERO));
        assertFalse(task.isExpired(Duration.ofSeconds(-1)));
        assertTrue(task.isExpired(Duration.ofNanos(1)), "anything is older than a nanosecond by the time it is asked");
    }

    @Test
    void closingTheStoreReleasesEverything() {
        final var tasks = tasks(Configurations.defaults());
        final var task = tasks.create("c-1", null);
        final var settled = task.turn().settled();

        tasks.close();

        assertEquals(List.of(), List.copyOf(tasks.tasks()));
        assertTrue(settled.isCompletedExceptionally());
    }

    @Test
    void streamedArtifactChunksAreMerged() {
        final var task = new A2ATask("t-1", "c-1", null, null, Clock.systemUTC());
        final var first =
                new Artifact("a", "chunked", "first", List.of(Part.text("one")), Map.of("k", "v"), List.of("x"));

        task.putArtifact(first, false);
        // nothing to append to yet is the same as not appending, else the first chunk of a stream would be lost
        task.putArtifact(new Artifact("b", null, null, List.of(Part.text("only")), null, null), true);
        task.putArtifact(new Artifact("a", null, null, List.of(Part.text("two")), null, null), true);

        final var merged = task.artifacts().stream()
                .filter(it -> "a".equals(it.artifactId()))
                .findFirst()
                .orElseThrow();
        assertEquals(
                List.of("one", "two"), merged.parts().stream().map(Part::text).toList());
        // the members the chunk did not carry are the ones of the artifact being extended
        assertEquals("chunked", merged.name());
        assertEquals("first", merged.description());
        assertEquals(Map.of("k", "v"), merged.metadata());
        assertEquals(List.of("x"), merged.extensions());
        assertEquals(2, task.artifacts().size());
    }

    @Test
    void anAppendedChunkCanOverrideTheDescriptiveMembers() {
        final var task = new A2ATask("t-1", "c-1", null, null, Clock.systemUTC());
        task.putArtifact(
                new Artifact("a", "old", "old", List.of(Part.text("one")), Map.of("k", "1"), List.of("x")), false);

        task.putArtifact(new Artifact("a", "new", "new", null, Map.of("k", "2"), List.of("y")), true);

        final var merged = task.artifacts().get(0);
        assertEquals("new", merged.name());
        assertEquals("new", merged.description());
        assertEquals(Map.of("k", "2"), merged.metadata());
        assertEquals(List.of("y"), merged.extensions());
        assertEquals(
                List.of("one"), merged.parts().stream().map(Part::text).toList(), "a chunk with no part adds none");
    }

    @Test
    void appendingToAnArtifactWhichCarriedNoPart() {
        final var task = new A2ATask("t-1", "c-1", null, null, Clock.systemUTC());
        task.putArtifact(new Artifact("a", "chunked", null, null, null, null), false);

        task.putArtifact(new Artifact("a", null, null, List.of(Part.text("one")), null, null), true);

        assertEquals(
                List.of("one"),
                task.artifacts().get(0).parts().stream().map(Part::text).toList());
    }

    @Test
    void replacingAnArtifactDropsWhatItHeld() {
        final var task = new A2ATask("t-1", "c-1", null, null, Clock.systemUTC());
        task.putArtifact(Artifact.text("a", "one").withId("a"), false);

        task.putArtifact(Artifact.text("a", "two").withId("a"), false);

        assertEquals(1, task.artifacts().size());
        assertEquals(
                List.of("two"),
                task.artifacts().get(0).parts().stream().map(Part::text).toList());
    }

    @Test
    void historyIsTruncatedFromTheEnd() {
        final var task = new A2ATask("t-1", "c-1", null, null, Clock.systemUTC());
        for (int i = 0; i < 3; i++) {
            task.addMessage(Message.user("m-" + i));
        }

        assertEquals(3, task.snapshot(null, false).history().size(), "no limit means everything");
        assertNull(task.snapshot(0, false).history(), "the attribute must be omitted, not sent empty");
        assertNull(task.snapshot(-1, false).history());
        assertEquals(
                List.of("m-1", "m-2"),
                task.snapshot(2, false).history().stream().map(Message::text).toList(),
                "the most recent messages are the ones which matter");
        assertEquals(3, task.snapshot(10, false).history().size(), "asking for more than there is answers everything");
    }

    @Test
    void snapshotsOmitTheArtifactsOnRequest() {
        final var task = new A2ATask("t-1", "c-1", null, null, Clock.systemUTC());
        task.putArtifact(Artifact.text("a", "one"), false);

        assertEquals(1, task.snapshot(null, true).artifacts().size());
        // ListTasks defaults to false to keep the payload small
        assertNull(task.snapshot(null, false).artifacts());
    }

    @Test
    void aStatusWithoutATimestampIsStillOrdered() {
        final var task = new A2ATask("t-1", "c-1", null, null, Clock.systemUTC());

        task.setStatus(new TaskStatus(TaskState.TASK_STATE_WORKING, null, null));

        // ListTasks sorts and filters on it, so it can never be null - now is the only sensible fallback
        assertNotNull(task.statusInstant());
    }

    private A2ATasks tasks(final A2AConfiguration configuration) {
        return new A2ATasks(new RecordingPushNotifier(), configuration, Optional.empty());
    }

    @Test
    void suppliedClockTimestampsCreationAndTransitions() {
        final var instant = Instant.parse("2026-08-01T09:32:46Z");
        final var clock = Clock.fixed(instant, ZoneOffset.UTC);
        final var tasks = new A2ATasks(new RecordingPushNotifier(), Configurations.defaults(), Optional.of(clock));
        try {
            final var task = tasks.create("clock", null);
            assertEquals(instant, task.statusInstant());
            assertEquals("2026-08-01T09:32:46.000Z", task.status().timestamp());
            tasks.updaterFor(task).working();
            assertEquals(instant, task.statusInstant());
            assertEquals("2026-08-01T09:32:46.000Z", task.status().timestamp());
        } finally {
            tasks.close();
        }
    }
}
