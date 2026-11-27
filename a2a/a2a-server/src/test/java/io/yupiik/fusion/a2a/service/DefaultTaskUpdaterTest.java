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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.a2a.model.Artifact;
import io.yupiik.fusion.a2a.model.Message;
import io.yupiik.fusion.a2a.model.Part;
import io.yupiik.fusion.a2a.model.Role;
import io.yupiik.fusion.a2a.model.StreamResponse;
import io.yupiik.fusion.a2a.model.Task;
import io.yupiik.fusion.a2a.model.TaskState;
import io.yupiik.fusion.a2a.model.TaskStatus;
import io.yupiik.fusion.a2a.test.Configurations;
import io.yupiik.fusion.a2a.test.RecordingPushNotifier;
import io.yupiik.fusion.jsonrpc.JsonRpcException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * The only way a task is ever mutated, so also the only place enforcing the lifecycle rules of section 3.4: a terminal
 * task never moves again, a direct message answer is only valid before anything else happened, and every change reaches
 * the subscribers and the webhooks.
 */
class DefaultTaskUpdaterTest {
    @Test
    void statusTransitionsAreReportedEverywhere() {
        final var agent = agent();

        agent.updater().working();

        assertEquals(TaskState.TASK_STATE_WORKING, agent.task().status().state());
        assertTrue(agent.updater().hasTransitioned());
        assertFalse(agent.updater().isDone());
        // the same event reaches the streams and the webhooks, so a client can consume either
        assertEquals(1, agent.push().events().size());
        assertEquals(TaskState.TASK_STATE_WORKING, states(agent.push().events()).get(0));
        // and a non blocking SendMessage can already answer
        assertTrue(agent.turn().firstAction().isDone());
        assertFalse(agent.turn().settled().isDone());
    }

    @Test
    void aStatusMessageJoinsTheHistory() {
        final var agent = agent();

        agent.updater().working(Message.agent("looking at it"));

        assertEquals("looking at it", agent.task().status().message().text());
        assertEquals(1, agent.task().snapshot(null, false).history().size());
        assertEquals(agent.task().id(), agent.task().status().message().taskId(), "it is bound to the task");
    }

    @Test
    void aUserMessageIsRepublishedAsAnAgentOne() {
        final var agent = agent();

        // an executor echoing what it received must not make the agent look like the user
        agent.updater()
                .inputRequired(new Message(
                        "m-1", null, null, Role.ROLE_USER, List.of(Part.text("which city?")), null, null, null));

        final var message = agent.task().status().message();
        assertEquals(Role.ROLE_AGENT, message.role());
        assertEquals("m-1", message.messageId(), "the identifier of the author is kept");
        assertEquals(TaskState.TASK_STATE_INPUT_REQUIRED, agent.task().status().state());
        // an interrupted task is settled: the caller has to answer for it to progress
        assertTrue(agent.turn().settled().isDone());
    }

    @Test
    void aMessageWithoutAnyPartIsStillUsable() {
        final var agent = agent();

        agent.updater().authRequired(new Message("m-1", null, null, Role.ROLE_USER, null, null, null, null));

        assertEquals(TaskState.TASK_STATE_AUTH_REQUIRED, agent.task().status().state());
        assertEquals(List.of(), agent.task().status().message().parts(), "null parts must not travel as null");
    }

    @Test
    void completingWithArtifacts() {
        final var agent = agent();

        agent.updater().complete(Artifact.text("a", "one"), Artifact.text("b", "two"));

        assertEquals(TaskState.TASK_STATE_COMPLETED, agent.task().status().state());
        assertEquals(
                List.of("a", "b"),
                agent.task().artifacts().stream().map(Artifact::name).toList());
        assertTrue(agent.updater().isDone());
        // two artifact events plus the status one
        assertEquals(3, agent.push().events().size());
        assertNotNull(agent.push().events().get(0).artifactUpdate());
    }

    @Test
    void anArtifactWithoutAnIdentifierGetsOne() {
        final var agent = agent();

        agent.updater().artifact(new Artifact(null, "a", null, List.of(Part.text("one")), null, null));

        // the identifier is what correlates the chunks of a stream, so it cannot be left unset
        assertNotNull(agent.task().artifacts().get(0).artifactId());
        assertNotNull(agent.push().events().get(0).artifactUpdate().artifact().artifactId());
        assertTrue(agent.updater().hasTransitioned());
    }

    @Test
    void thereIsNothingToReportAboutAnAbsentArtifact() {
        final var agent = agent();

        agent.updater().artifact(null);
        agent.updater().artifact(null, true, true);

        assertEquals(List.of(), agent.task().artifacts());
        assertEquals(List.of(), agent.push().events());
        assertFalse(agent.updater().hasTransitioned());
    }

    @Test
    void chunkFlagsAreCarriedThrough() {
        final var agent = agent();

        agent.updater().artifact(Artifact.text("a", "one").withId("chunked"), false, false);
        agent.updater().artifact(Artifact.text("a", "two").withId("chunked"), true, true);

        final var chunks = agent.push().events().stream()
                .map(StreamResponse::artifactUpdate)
                .toList();
        assertFalse(chunks.get(0).append());
        assertFalse(chunks.get(0).lastChunk());
        assertTrue(chunks.get(1).append());
        assertTrue(chunks.get(1).lastChunk());
        // the polling clients see the reassembled artifact, not the chunks
        assertEquals(1, agent.task().artifacts().size());
        assertEquals(2, agent.task().artifacts().get(0).parts().size());
    }

    @Test
    void failureAndRejectionExplainThemselves() {
        final var failed = agent();
        failed.updater().fail("the backend is down");
        assertEquals(TaskState.TASK_STATE_FAILED, failed.task().status().state());
        assertEquals("the backend is down", failed.task().status().message().text());

        final var rejected = agent();
        rejected.updater().reject("out of scope");
        assertEquals(TaskState.TASK_STATE_REJECTED, rejected.task().status().state());
        assertEquals("out of scope", rejected.task().status().message().text());
    }

    @Test
    void anUnexplainedOutcomeStillCarriesAMessage() {
        // a client must always be able to read why, so a null reason is replaced rather than published as such
        final var failed = agent();
        failed.updater().fail(null);
        assertEquals("The task failed", failed.task().status().message().text());

        final var rejected = agent();
        rejected.updater().reject(null);
        assertEquals("The task was rejected", rejected.task().status().message().text());
    }

    @Test
    void cancelling() {
        final var agent = agent();

        agent.updater().cancel();

        assertEquals(TaskState.TASK_STATE_CANCELED, agent.task().status().state());
        assertNull(agent.task().status().message(), "a cancel needs no explanation, the client asked for it");
    }

    @Test
    void aTerminalTaskNeverMovesAgain() {
        final var agent = agent();
        agent.updater().complete();
        final var timestamp = agent.task().status().timestamp();

        agent.updater().working();
        agent.updater().fail("too late");
        agent.updater().status(TaskState.TASK_STATE_CANCELED, Message.agent("nope"));

        // else a client would see events after the stream closed
        assertEquals(TaskState.TASK_STATE_COMPLETED, agent.task().status().state());
        assertEquals(timestamp, agent.task().status().timestamp());
        assertEquals(1, agent.push().events().size(), "and nothing more is published");
    }

    @Test
    void anIgnoredTransitionIsTraced() {
        final var logger = Logger.getLogger(DefaultTaskUpdater.class.getName());
        final var level = logger.getLevel();
        // the refusal is logged at FINE, so the message is only built when somebody asked for it
        logger.setLevel(Level.FINEST);
        try {
            final var agent = agent();
            agent.updater().complete();

            agent.updater().working();

            assertEquals(TaskState.TASK_STATE_COMPLETED, agent.task().status().state());
        } finally {
            logger.setLevel(level);
        }
    }

    @Test
    void terminalTasksIgnoreLateArtifactsFromAnyUpdater() {
        final var agent = agent();
        agent.updater().artifact(Artifact.text("answer", "original").withId("a-1"));
        agent.updater().cancel();
        final var snapshot = agent.updater().task();
        final var events = List.copyOf(agent.push().events());
        final var late = agent.tasks().updaterFor(agent.task());

        agent.updater().artifact(Artifact.text("answer", "late").withId("a-1"), true, true);
        late.artifact(Artifact.text("extra", "late"));
        late.complete(Artifact.text("extra", "late"));
        late.adopt(new Task("t", "c", null, List.of(Artifact.text("extra", "late")), null, null));
        assertThrows(IllegalStateException.class, () -> late.respond(Message.agent("too late")));

        assertEquals(snapshot, agent.updater().task());
        assertEquals(events, agent.push().events());
    }

    @Test
    void respondingLeavesNoTaskBehind() {
        final var agent = agent(false);

        agent.updater().respond(Message.agent("no task needed"));

        // the task was created for this turn only and never surfaced: the client gets a plain message
        assertTrue(agent.tasks().find(agent.task().id()).isEmpty());
        final var answer = agent.turn().settled().join().message();
        assertEquals("no task needed", answer.text());
        assertEquals(agent.task().contextId(), answer.contextId());
        assertNull(answer.taskId(), "there is no task to point at anymore");
        assertEquals(TaskState.TASK_STATE_SUBMITTED, agent.task().status().state(), "no transition was recorded");
    }

    @Test
    void respondingOnAContinuationKeepsTheTask() {
        final var agent = agent(true);

        agent.updater().respond(Message.agent("here you go"));

        // the task already surfaced, so it stays - and the answer is part of its history
        assertNotNull(agent.tasks().find(agent.task().id()).orElse(null));
        final var answer = agent.turn().settled().join().message();
        assertEquals(agent.task().id(), answer.taskId());
        assertEquals(
                List.of("here you go"),
                agent.task().snapshot(null, false).history().stream()
                        .map(Message::text)
                        .toList());
    }

    @Test
    void respondingIsOnlyValidBeforeAnythingElse() {
        final var agent = agent();
        agent.updater().working();

        final var error =
                assertThrows(IllegalStateException.class, () -> agent.updater().respond(Message.agent("hi")));
        assertTrue(error.getMessage().contains("TASK_STATE_WORKING"), error::getMessage);
        assertTrue(error.getMessage().contains(agent.task().id()), error::getMessage);
    }

    @Test
    void respondingTwiceIsRefused() {
        final var agent = agent();
        agent.updater().respond(Message.agent("hi"));

        assertThrows(IllegalStateException.class, () -> agent.updater().respond(Message.agent("again")));
    }

    @Test
    void adoptingAWholeTask() {
        final var agent = agent();

        agent.updater()
                .adopt(new Task(
                        "ignored",
                        "ignored",
                        new TaskStatus(TaskState.TASK_STATE_COMPLETED, Message.agent("done"), null),
                        List.of(Artifact.text("a", "one")),
                        null,
                        null));

        // this is how a skill returning a Task takes over the whole state
        assertEquals(TaskState.TASK_STATE_COMPLETED, agent.task().status().state());
        assertEquals("done", agent.task().status().message().text());
        assertEquals(1, agent.task().artifacts().size());
    }

    @Test
    void adoptingOnlyWhatIsThere() {
        final var artifactsOnly = agent();
        artifactsOnly.updater().adopt(new Task("t", "c", null, List.of(Artifact.text("a", "one")), null, null));
        assertEquals(1, artifactsOnly.task().artifacts().size());
        assertEquals(
                TaskState.TASK_STATE_SUBMITTED, artifactsOnly.task().status().state(), "no status, no transition");

        final var statusOnly = agent();
        statusOnly
                .updater()
                .adopt(new Task(
                        "t",
                        "c",
                        TaskStatus.of(TaskState.TASK_STATE_WORKING, Instant.parse("2026-08-01T09:32:46Z")),
                        null,
                        null,
                        null));
        assertEquals(TaskState.TASK_STATE_WORKING, statusOnly.task().status().state());
        assertEquals(List.of(), statusOnly.task().artifacts());

        // a status carrying no state is not a transition either
        final var stateless = agent();
        stateless.updater().adopt(new Task("t", "c", new TaskStatus(null, null, null), null, null, null));
        assertEquals(TaskState.TASK_STATE_SUBMITTED, stateless.task().status().state());

        final var nothing = agent();
        nothing.updater().adopt(null);
        assertFalse(nothing.updater().hasTransitioned());
    }

    @Test
    void aProtocolErrorIsNotATaskFailure() {
        final var agent = agent(false);

        agent.updater().failWithProtocolError(new JsonRpcException(-32602, "Invalid parameters", null, null));

        // the call itself was invalid: the client gets a JSON-RPC error and no task is left behind
        assertTrue(agent.tasks().find(agent.task().id()).isEmpty());
        final var error = assertThrows(
                ExecutionException.class, () -> agent.turn().settled().get());
        assertEquals(-32602, ((JsonRpcException) error.getCause()).code());
        assertEquals(TaskState.TASK_STATE_SUBMITTED, agent.task().status().state());
    }

    @Test
    void aProtocolErrorOnAContinuationKeepsTheTask() {
        final var agent = agent(true);

        agent.updater().failWithProtocolError(new JsonRpcException(-32602, "Invalid parameters", null, null));

        // the task existed before this message, rejecting the message must not destroy it
        assertNotNull(agent.tasks().find(agent.task().id()).orElse(null));
        assertTrue(agent.turn().settled().isCompletedExceptionally());
    }

    @Test
    void theSnapshotAnExecutorSeesCarriesTheOutputs() {
        final var agent = agent();
        agent.updater().artifact(Artifact.text("a", "one"));

        final var snapshot = agent.updater().task();

        assertEquals(agent.task().id(), snapshot.id());
        assertEquals(1, snapshot.artifacts().size());
    }

    private static List<TaskState> states(final List<StreamResponse> events) {
        return events.stream()
                .map(StreamResponse::statusUpdate)
                .filter(Objects::nonNull)
                .map(it -> it.status().state())
                .toList();
    }

    private static Driven agent() {
        return agent(true);
    }

    /**
     * @param continuation {@code false} when the task was created for this very turn, i.e. when it can still be
     *                     discarded in favor of a direct message answer.
     */
    private static Driven agent(final boolean continuation) {
        final var push = new RecordingPushNotifier();
        final var tasks = new A2ATasks(push, Configurations.defaults(), Optional.empty());
        final var task = tasks.create("c-1", null);
        return new Driven(
                tasks, task, push, task.turn(), new DefaultTaskUpdater(task, tasks, push, task.turn(), continuation));
    }

    private record Driven(
            A2ATasks tasks, A2ATask task, RecordingPushNotifier push, A2ATask.Turn turn, DefaultTaskUpdater updater) {}
}
