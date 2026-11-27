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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.a2a.client.A2AClient;
import io.yupiik.fusion.a2a.client.A2AClientException;
import io.yupiik.fusion.a2a.model.Message;
import io.yupiik.fusion.a2a.model.SendMessageConfiguration;
import io.yupiik.fusion.a2a.model.SendMessageRequest;
import io.yupiik.fusion.a2a.model.StreamResponse;
import io.yupiik.fusion.a2a.model.TaskState;
import io.yupiik.fusion.a2a.protocol.A2AErrors;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The streaming operations: the event sequence, the artifact chunks, the resubscription and the terminal task refusal.
 */
@FusionSupport
class A2AStreamingTest {
    @Test
    void sendStreamingMessage(@Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var events = new CopyOnWriteArrayList<StreamResponse>();
        await(new A2AClient(agent, http, jsons)
                .sendStreamingMessage(SendMessageRequest.of(Message.user("chunks:3")), events::add));

        // a task stream begins with the task itself, then the deltas, and closes on the terminal status
        assertNotNull(events.get(0).task(), () -> "First event should be the task: " + events);
        assertEquals(TaskState.TASK_STATE_WORKING, states(events).get(0));
        assertEquals(
                TaskState.TASK_STATE_COMPLETED,
                states(events).get(states(events).size() - 1));

        final var chunks = events.stream()
                .map(StreamResponse::artifactUpdate)
                .filter(Objects::nonNull)
                .toList();
        assertEquals(3, chunks.size());
        assertEquals("chunk-1", chunks.get(0).artifact().parts().get(0).text());
        assertEquals(false, chunks.get(0).append(), "the first chunk creates the artifact");
        assertEquals(true, chunks.get(1).append());
        assertEquals(true, chunks.get(2).lastChunk(), "the last chunk must be flagged");
        // every chunk shares the artifact identifier so the client can reassemble them
        assertEquals(
                1,
                chunks.stream().map(it -> it.artifact().artifactId()).distinct().count());
    }

    @Test
    void streamedChunksAreMergedForPollingClients(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);
        final var events = new CopyOnWriteArrayList<StreamResponse>();
        await(client.sendStreamingMessage(SendMessageRequest.of(Message.user("chunks:3")), events::add));

        final var taskId = events.get(0).task().id();
        final var task = await(client.getTask(taskId, null));

        // a client which polls instead of streaming gets the whole artifact, not the chunks
        assertEquals(1, task.artifacts().size());
        assertEquals(
                List.of("chunk-1", "chunk-2", "chunk-3"),
                task.artifacts().get(0).parts().stream().map(it -> it.text()).toList());
    }

    @Test
    void streamingAMessageOnlyAnswer(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var events = new CopyOnWriteArrayList<StreamResponse>();
        await(new A2AClient(agent, http, jsons)
                .sendStreamingMessage(SendMessageRequest.of(Message.user("message:hi there")), events::add));

        // a message only stream carries exactly one message and closes
        assertEquals(1, events.size(), () -> String.valueOf(events));
        assertEquals("hi there", events.get(0).message().text());
    }

    @Test
    void streamClosesOnAnInterruptedTask(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var events = new CopyOnWriteArrayList<StreamResponse>();
        await(new A2AClient(agent, http, jsons)
                .sendStreamingMessage(SendMessageRequest.of(Message.user("input:which city?")), events::add));

        // an interrupted task cannot progress without a new request, so the stream must not stay open
        assertEquals(
                TaskState.TASK_STATE_INPUT_REQUIRED,
                states(events).get(states(events).size() - 1));
    }

    @Test
    void subscribeToTask(@Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);
        final var task = await(client.sendMessage(new SendMessageRequest(
                        null, Message.user("slow"), new SendMessageConfiguration(null, null, null, true), null)))
                .task();

        final var events = new CopyOnWriteArrayList<StreamResponse>();
        final var subscribed = new CountDownLatch(1);
        final var subscription = client.subscribeToTask(task.id(), event -> {
            events.add(event);
            subscribed.countDown();
        });
        // cancel only once the stream is live, else the task would be terminal before the subscription reaches the
        // agent
        assertTrue(awaitLatch(subscribed), "the subscription did not start");
        await(client.cancelTask(task.id()));
        await(subscription);

        // the first event is the current state, so nothing is lost between GetTask and SubscribeToTask
        assertNotNull(events.get(0).task());
        assertEquals(task.id(), events.get(0).task().id());
        assertEquals(
                TaskState.TASK_STATE_CANCELED, states(events).get(states(events).size() - 1));
    }

    @Test
    void subscribeToATerminalTask(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);
        final var task = await(client.sendMessage("hello")).task();

        final var error =
                assertThrows(A2AClientException.class, () -> await(client.subscribeToTask(task.id(), event -> {})));
        // the agent answers the error payload with the mapped status, so the client reports the A2A error itself
        assertEquals(A2AErrors.UNSUPPORTED_OPERATION, error.code());
        assertTrue(error.getMessage().contains("terminal state"), error::getMessage);
    }

    private static boolean awaitLatch(final CountDownLatch latch) {
        try {
            return latch.await(30, TimeUnit.SECONDS);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static List<TaskState> states(final List<StreamResponse> events) {
        return events.stream()
                .map(StreamResponse::statusUpdate)
                .filter(Objects::nonNull)
                .map(it -> it.status().state())
                .toList();
    }
}
