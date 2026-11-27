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
package io.yupiik.fusion.a2a.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.yupiik.fusion.a2a.client.A2AClient;
import io.yupiik.fusion.a2a.client.A2AClientException;
import io.yupiik.fusion.a2a.model.AgentSkill;
import io.yupiik.fusion.a2a.model.Message;
import io.yupiik.fusion.a2a.model.Part;
import io.yupiik.fusion.a2a.model.Role;
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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * End to end tests of a real agent: the card the demo publishes and the four skills it exposes, both declarative and
 * imperative.
 */
@FusionSupport
class DemoTest {
    @Test
    void card(@Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var card = await(new A2AClient(agent, http, jsons).agentCard());

        assertEquals(
                List.of("demo/chat", "demo/greet", "demo/report", "demo/countdown"),
                card.skills().stream().map(AgentSkill::id).toList());
        // the description comes from the @JsonRpc documentation, the tags from A2ASkills
        final var greet = card.skills().stream()
                .filter(it -> "demo/greet".equals(it.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("Greets someone by name.", greet.description());
        assertEquals(List.of("demo", "greeting"), greet.tags());
        assertEquals(List.of("Greet Fusion."), greet.examples());
        assertEquals(List.of("text/plain"), greet.outputModes());
        // the extension is declared by A2AAgentCard and is optional, so a client can ignore it
        assertEquals(
                List.of(DemoAgentCard.TRACE_EXTENSION),
                card.capabilities().extensions().stream().map(it -> it.uri()).toList());
        assertEquals(false, card.capabilities().extensions().get(0).required());
    }

    @Test
    void skillReturningAnArtifact(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var task = await(
                        new A2AClient(agent, http, jsons).sendMessage(skill("demo/greet", Map.of("name", "fusion"))))
                .task();

        assertEquals(TaskState.TASK_STATE_COMPLETED, task.status().state());
        assertEquals("greeting", task.artifacts().get(0).name());
        assertEquals("hello fusion!", task.artifacts().get(0).parts().get(0).text());
    }

    @Test
    void skillReturningAModel(@Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var task = await(
                        new A2AClient(agent, http, jsons).sendMessage(skill("demo/report", Map.of("city", "Paris"))))
                .task();

        assertEquals(TaskState.TASK_STATE_COMPLETED, task.status().state());
        // a model is wrapped in a structured artifact, so the caller gets typed JSON and not a string
        final var part = task.artifacts().get(0).parts().get(0);
        assertEquals("application/json", part.mediaType());
        final var data = (Map<?, ?>) part.data();
        assertEquals("Paris", data.get("city"));
        assertEquals("sunny", data.get("weather"));
        assertEquals(24, ((Number) data.get("temperature")).intValue());
    }

    @Test
    void skillReturningAMessage(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var response = await(new A2AClient(agent, http, jsons).sendMessage(skill("demo/chat", Map.of())));

        // no task is created at all, which is what a plain conversation should cost
        assertNull(response.task());
        assertEquals(Role.ROLE_AGENT, response.message().role());
        assertEquals("you said: hello", response.message().text());
    }

    @Test
    void skillFailureFailsTheTask(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var task = await(new A2AClient(agent, http, jsons).sendMessage(skill("demo/greet", Map.of("name", " "))))
                .task();

        assertEquals(TaskState.TASK_STATE_FAILED, task.status().state());
        assertEquals("name should not be blank", task.status().message().text());
    }

    @Test
    void unknownSkill(@Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);

        final var error =
                assertThrows(A2AClientException.class, () -> await(client.sendMessage(skill("demo/nope", Map.of()))));
        // naming a skill which does not exist is an invalid call, not a task failure
        assertEquals(A2AErrors.INVALID_PARAMS, error.code());
    }

    @Test
    void severalSkillsNeedTheClientToPickOne(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);

        final var error = assertThrows(A2AClientException.class, () -> await(client.sendMessage("hello")));
        assertEquals(A2AErrors.INVALID_PARAMS, error.code());
        assertTrue(String.valueOf(error.details()).contains("skillId"), () -> String.valueOf(error.details()));
    }

    @Test
    void streamingExecutor(@Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var events = new CopyOnWriteArrayList<StreamResponse>();
        await(new A2AClient(agent, http, jsons)
                .sendStreamingMessage(skill(DemoCountdownExecutor.SKILL_ID, Map.of()), events::add));

        assertNotNull(events.get(0).task());
        final var chunks = events.stream()
                .map(StreamResponse::artifactUpdate)
                .filter(Objects::nonNull)
                .map(it -> it.artifact().parts().get(0).text())
                .toList();
        assertEquals(List.of("3...", "2...", "1...", "go!"), chunks);
        assertEquals(
                List.of(TaskState.TASK_STATE_WORKING, TaskState.TASK_STATE_COMPLETED),
                events.stream()
                        .map(StreamResponse::statusUpdate)
                        .filter(Objects::nonNull)
                        .map(it -> it.status().state())
                        .toList());
    }

    /**
     * @param skillId   the skill to invoke, the built-in router reads it from the message metadata.
     * @param arguments the structured arguments of the skill.
     */
    private static SendMessageRequest skill(final String skillId, final Map<String, Object> arguments) {
        return SendMessageRequest.of(new Message(
                "m-" + skillId,
                null,
                null,
                Role.ROLE_USER,
                List.of(Part.text("hello")),
                Map.of("skillId", skillId, "arguments", arguments),
                null,
                null));
    }

    private static <T> T await(final CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            return fail(ie);
        } catch (final TimeoutException te) {
            return fail("The call did not complete within 30s", te);
        } catch (final ExecutionException ee) {
            final var cause = ee.getCause() == null ? ee : ee.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            return fail(cause);
        }
    }
}
