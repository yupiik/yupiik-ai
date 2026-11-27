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

import static io.yupiik.fusion.a2a.test.Awaits.await;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.a2a.api.AgentRequest;
import io.yupiik.fusion.a2a.model.Artifact;
import io.yupiik.fusion.a2a.model.Message;
import io.yupiik.fusion.a2a.model.Part;
import io.yupiik.fusion.a2a.model.Role;
import io.yupiik.fusion.a2a.model.Task;
import io.yupiik.fusion.a2a.model.TaskState;
import io.yupiik.fusion.a2a.model.TaskStatus;
import io.yupiik.fusion.a2a.protocol.A2AErrors;
import io.yupiik.fusion.a2a.test.Configurations;
import io.yupiik.fusion.a2a.test.RecordingPushNotifier;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jsonrpc.JsonRpcException;
import io.yupiik.fusion.jsonrpc.JsonRpcHandler;
import io.yupiik.fusion.jsonrpc.JsonRpcRegistry;
import io.yupiik.fusion.jsonrpc.impl.JsonRpcMethod;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The built-in executor: how a message becomes a skill call, and how what the skill returns becomes a task outcome.
 * <p>
 * The JSON-RPC stack is the real one - only the methods are fixtures - so the argument binding, the error codes and the
 * asynchronous result are the ones an application really gets. {@code DemoTest} covers the same paths with annotated
 * methods; what is asserted here is every return shape and every routing failure, which one agent cannot exhibit at once.
 */
@FusionSupport
class SkillExecutorTest {
    // --- accepting a message ------------------------------------------------------------------------------------

    @Test
    void aNamedSkillIsAlwaysAccepted(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        // even an unknown one: telling the client which skills exist is more useful than "no executor accepted"
        final var executor = executor(container, jsons, skills());

        assertTrue(executor.accept(request("demo/greet", Map.of())));
        assertFalse(executor.accept(request("  ", Map.of())), "a blank name is no name");
    }

    @Test
    void withoutASkillNameItDependsOnWhatTheAgentExposes(
            final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        assertTrue(executor(container, jsons, skills("demo/greet")).accept(request(null, Map.of())));
        // no skill at all: some other executor has to answer, and the generic error is then the right one
        assertFalse(executor(container, jsons, skills()).accept(request(null, Map.of())));
    }

    // --- routing ------------------------------------------------------------------------------------------------

    @Test
    void aSingleSkillAgentNeedsNoRouting(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        final var driven = drive(
                container,
                jsons,
                skills("demo/greet"),
                request(null, Map.of("name", "fusion")),
                method("demo/greet", params -> Artifact.text("greeting", "hello " + argument(params, "name") + "!")));

        assertEquals(TaskState.TASK_STATE_COMPLETED, driven.status());
        assertEquals("hello fusion!", driven.text());
    }

    @Test
    void severalSkillsNeedTheClientToPickOne(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        final var error = assertThrows(
                JsonRpcException.class,
                () -> drive(
                        container,
                        jsons,
                        skills("demo/greet", "demo/report"),
                        request(null, Map.of()),
                        method("demo/greet", params -> "hi")));

        assertEquals(A2AErrors.INVALID_PARAMS, error.code());
        assertTrue(String.valueOf(error.data()).contains("skillId"), () -> String.valueOf(error.data()));
        assertTrue(String.valueOf(error.data()).contains("2 skills"), () -> String.valueOf(error.data()));
    }

    @Test
    void aBlankSkillNameIsNoName(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        // there is one skill, so the router does not need a name - and whitespace is not one
        final var driven = drive(
                container,
                jsons,
                skills("demo/greet"),
                request("  ", Map.of()),
                method("demo/greet", params -> "hello"));

        assertEquals(TaskState.TASK_STATE_COMPLETED, driven.status());
        assertEquals("hello", driven.text());
    }

    @Test
    void aRequestWithoutAnyMessage(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        // an out of band request: the skill is named by the request metadata and there is no message to read
        final var request = new AgentRequest(
                null, null, Map.of(AgentRequest.SKILL_ID, "demo/greet"), null, "c-1", "t-1", false, List.of(), null);

        final var driven = drive(
                container,
                jsons,
                skills("demo/greet"),
                request,
                method("demo/greet", params -> String.valueOf(argument(params, "text"))));

        assertEquals("", driven.text(), "there is no text to pass on, and that is not a failure");
    }

    @Test
    void aMessageWithoutAnyMetadata(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        final var request = new AgentRequest(
                new Message("m-1", "c-1", "t-1", Role.ROLE_USER, List.of(Part.text("hello")), null, null, null),
                null,
                Map.of(AgentRequest.SKILL_ID, "demo/greet"),
                null,
                "c-1",
                "t-1",
                false,
                List.of(),
                null);

        final var driven = drive(
                container,
                jsons,
                skills("demo/greet"),
                request,
                method("demo/greet", params -> String.valueOf(argument(params, "text"))));

        assertEquals("hello", driven.text());
    }

    @Test
    void aHandlerAnsweringSomethingElseThanAResponse(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        final var driven = driven();
        final var executor = new SkillExecutor(
                skills("demo/greet"), new JsonRpcHandler(container, jsons, new JsonRpcRegistry(List.of())) {
                    @Override
                    public CompletionStage<?> execute(final Object request, final Request httpRequest) {
                        return completedFuture("not a JSON-RPC response");
                    }
                });

        final var error = assertThrows(
                JsonRpcException.class,
                () -> await(executor.execute(request("demo/greet", Map.of()), driven.updater())));

        assertEquals(A2AErrors.INVALID_AGENT_RESPONSE, error.code());
        assertTrue(error.getMessage().contains("demo/greet"), error::getMessage);
    }

    @Test
    void anUnknownSkillIsAnInvalidCall(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        final var error = assertThrows(
                JsonRpcException.class,
                () -> drive(
                        container,
                        jsons,
                        skills("demo/greet"),
                        request("demo/nope", Map.of()),
                        method("demo/greet", params -> "hi")));

        assertEquals(A2AErrors.INVALID_PARAMS, error.code());
        // the known ones are listed, so the caller can fix its request
        assertTrue(String.valueOf(error.data()).contains("demo/greet"), () -> String.valueOf(error.data()));
    }

    // --- arguments ----------------------------------------------------------------------------------------------

    @Test
    void theArgumentsAreAssembledMostSpecificLast(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        final var request = new AgentRequest(
                new Message(
                        "m-1",
                        "c-1",
                        "t-1",
                        Role.ROLE_USER,
                        List.of(Part.text("hello"), Part.data(Map.of("name", "from-data", "city", "Paris"))),
                        Map.of(AgentRequest.ARGUMENTS, Map.of("name", "from-message-metadata")),
                        null,
                        null),
                null,
                Map.of(AgentRequest.ARGUMENTS, Map.of("name", "from-request-metadata")),
                null,
                "c-1",
                "t-1",
                false,
                List.of(),
                null);

        final var driven = drive(
                container,
                jsons,
                skills("demo/echo"),
                request,
                method(
                        "demo/echo",
                        params -> Map.of(
                                "name", String.valueOf(argument(params, "name")),
                                "city", String.valueOf(argument(params, "city")),
                                "text", String.valueOf(argument(params, "text")),
                                "message", argument(params, "message") != null)));

        @SuppressWarnings("unchecked")
        final var answer = (Map<String, Object>) driven.data();
        // the request metadata is the most specific, so it wins over the message metadata and over the data part
        assertEquals("from-request-metadata", answer.get("name"));
        assertEquals("Paris", answer.get("city"));
        assertEquals("hello", answer.get("text"));
        assertEquals(true, answer.get("message"), "the incoming message is always available");
    }

    @Test
    void aMessageWithoutAnyArgument(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        final var driven = drive(
                container,
                jsons,
                skills("demo/echo"),
                request(null, null),
                method("demo/echo", params -> String.valueOf(argument(params, "text"))));

        assertEquals("hello", driven.text());
    }

    @Test
    void argumentsWhichAreNotAnObjectAreIgnored(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        // metadata is free form JSON, so nothing guarantees 'arguments' is an object
        final var request = new AgentRequest(
                new Message(
                        "m-1",
                        "c-1",
                        "t-1",
                        Role.ROLE_USER,
                        List.of(Part.text("hello"), Part.data("not an object")),
                        Map.of(AgentRequest.ARGUMENTS, "not an object either"),
                        null,
                        null),
                null,
                Map.of(AgentRequest.ARGUMENTS, List.of("nor this")),
                null,
                "c-1",
                "t-1",
                false,
                List.of(),
                null);

        final var driven = drive(
                container,
                jsons,
                skills("demo/echo"),
                request,
                method("demo/echo", params -> String.valueOf(argument(params, "text"))));

        assertEquals("hello", driven.text());
    }

    // --- what a skill returns ------------------------------------------------------------------------------------

    @Test
    void anArtifactCompletesTheTask(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        final var driven = single(container, jsons, params -> Artifact.text("greeting", "hello"));

        assertEquals(TaskState.TASK_STATE_COMPLETED, driven.status());
        assertEquals("hello", driven.text());
        assertEquals("greeting", driven.task().artifacts().get(0).name());
    }

    @Test
    void aListOfArtifactsCompletesTheTaskWithAllOfThem(
            final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        final var driven =
                single(container, jsons, params -> List.of(Artifact.text("a", "one"), Artifact.text("b", "two")));

        assertEquals(TaskState.TASK_STATE_COMPLETED, driven.status());
        assertEquals(
                List.of("a", "b"),
                driven.task().artifacts().stream().map(Artifact::name).toList());
    }

    @Test
    void aStringIsWrappedInATextArtifact(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        final var driven = single(container, jsons, params -> "just some text");

        assertEquals("just some text", driven.text());
        assertEquals("demo/skill", driven.task().artifacts().get(0).name(), "the skill names the artifact");
        assertEquals(
                "text/plain", driven.task().artifacts().get(0).parts().get(0).mediaType());
    }

    @Test
    void anythingElseIsWrappedInAStructuredArtifact(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        final var driven = single(container, jsons, params -> Map.of("city", "Paris"));

        assertEquals(Map.of("city", "Paris"), driven.data());
        assertEquals(
                "application/json",
                driven.task().artifacts().get(0).parts().get(0).mediaType());
    }

    @Test
    void aListWhichIsNotOnlyArtifactsIsStructuredContent(
            final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        final var mixed = single(container, jsons, params -> List.of("one", 2));
        assertEquals(List.of("one", 2), mixed.data());

        // an empty list is not "no artifact", it is an empty answer
        final var empty = single(container, jsons, params -> List.of());
        assertEquals(List.of(), empty.data());
        assertEquals(TaskState.TASK_STATE_COMPLETED, empty.status());
    }

    @Test
    void aMessageAnswersWithoutLeavingATaskBehind(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        final var driven = single(container, jsons, params -> Message.agent("you said hello"));

        // the task never surfaced, so the client gets a plain message
        assertTrue(driven.tasks().find(driven.live().id()).isEmpty());
        assertEquals("you said hello", driven.turn().settled().join().message().text());
    }

    @Test
    void aTaskTakesOverTheWholeState(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        final var driven = single(
                container,
                jsons,
                params -> new Task(
                        "ignored",
                        "ignored",
                        new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, Message.agent("which city?"), null),
                        List.of(Artifact.text("partial", "so far")),
                        null,
                        null));

        assertEquals(TaskState.TASK_STATE_INPUT_REQUIRED, driven.status());
        assertEquals("which city?", driven.task().status().message().text());
        assertEquals(1, driven.task().artifacts().size());
    }

    @Test
    void aVoidSkillJustCompletesTheTask(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        // a method returning nothing still answers a result, it is simply null
        final var returningNull = single(container, jsons, params -> null);
        assertEquals(TaskState.TASK_STATE_COMPLETED, returningNull.status());
        assertEquals(List.of(), returningNull.task().artifacts());

        // and a notification answers no result at all
        final var driven = driven();
        await(executor(container, jsons, skills("demo/skill"), notification("demo/skill"))
                .execute(request("demo/skill", Map.of()), driven.updater()));
        assertEquals(TaskState.TASK_STATE_COMPLETED, driven.live().status().state());
    }

    @Test
    void anAsynchronousSkillIsAwaitedWithoutHoldingAThread(
            final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        final var driven = driven();
        final var release = new CompletableFuture<Object>();
        final var execution = executor(container, jsons, skills("demo/skill"), new JsonRpcMethod() {
                    @Override
                    public String name() {
                        return "demo/skill";
                    }

                    @Override
                    public CompletionStage<?> invoke(final Context context) {
                        return release;
                    }
                })
                .execute(request("demo/skill", Map.of()), driven.updater());

        assertFalse(execution.toCompletableFuture().isDone());
        assertEquals(TaskState.TASK_STATE_SUBMITTED, driven.live().status().state());

        release.complete("late but done");
        await(execution);
        assertEquals(TaskState.TASK_STATE_COMPLETED, driven.live().status().state());
        assertEquals("late but done", driven.text());
    }

    // --- when a skill fails --------------------------------------------------------------------------------------

    @Test
    void aSkillThrowingFailsTheTask(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        final var driven = single(container, jsons, params -> {
            throw new IllegalArgumentException("name should not be blank");
        });

        // the specification wants the caller to read why in the task status, not to get a protocol error
        assertEquals(TaskState.TASK_STATE_FAILED, driven.status());
        assertEquals(
                "name should not be blank", driven.task().status().message().text());
    }

    @Test
    void anApplicationErrorCodeIsStillATaskFailure(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        final var driven = single(container, jsons, params -> {
            throw A2AErrors.taskNotFound("t-other"); // -32001, an A2A code and not a JSON-RPC one
        });

        assertEquals(TaskState.TASK_STATE_FAILED, driven.status());
        assertEquals("Task not found", driven.task().status().message().text());
    }

    @Test
    void anInvalidCallIsAProtocolError(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        // the reserved JSON-RPC codes mean the call itself was wrong - a missing argument for example
        for (final var code : List.of(-32700, -32600, -32601, -32602, -32603)) {
            final var driven = driven();
            final var error = assertThrows(
                    JsonRpcException.class,
                    () -> await(executor(container, jsons, skills("demo/skill"), method("demo/skill", params -> {
                                throw new JsonRpcException(code, "wrong call", null, null);
                            }))
                            .execute(request("demo/skill", Map.of()), driven.updater())),
                    () -> code + " should be a protocol error");
            assertEquals(code, error.code());
            assertEquals(
                    TaskState.TASK_STATE_SUBMITTED, driven.live().status().state(), "the task must not be touched");
        }
    }

    @Test
    void aCodeOutsideTheReservedRangeIsATaskFailure(final RuntimeContainer container, @Fusion final JsonMapper jsons) {
        // -32604 is below the reserved block, so it is an application error like any other
        final var driven = single(container, jsons, params -> {
            throw new JsonRpcException(-32604, "not reserved", null, null);
        });

        assertEquals(TaskState.TASK_STATE_FAILED, driven.status());
        assertEquals("not reserved", driven.task().status().message().text());
    }

    // --- fixtures -----------------------------------------------------------------------------------------------

    private static SkillExecutor executor(
            final RuntimeContainer container,
            final JsonMapper jsons,
            final SkillService skills,
            final JsonRpcMethod... methods) {
        return new SkillExecutor(skills, new JsonRpcHandler(container, jsons, new JsonRpcRegistry(List.of(methods))));
    }

    /**
     * Runs {@code demo/skill}, the single skill of the agent, and answers what the task became.
     */
    private static Driven single(
            final RuntimeContainer container, final JsonMapper jsons, final Function<Object, Object> skill) {
        return drive(
                container, jsons, skills("demo/skill"), request("demo/skill", Map.of()), method("demo/skill", skill));
    }

    private static Driven drive(
            final RuntimeContainer container,
            final JsonMapper jsons,
            final SkillService skills,
            final AgentRequest request,
            final JsonRpcMethod... methods) {
        final var driven = driven();
        await(executor(container, jsons, skills, methods).execute(request, driven.updater()));
        return driven;
    }

    private static Driven driven() {
        final var tasks = new A2ATasks(new RecordingPushNotifier(), Configurations.defaults(), Optional.empty());
        final var task = tasks.create("c-1", null);
        // continuation: the task already surfaced, unless a skill answers a plain message and discards it
        return new Driven(tasks, task, new DefaultTaskUpdater(task, tasks, tasks.pushNotifier(), task.turn(), false));
    }

    /**
     * @param ids the identifiers the built-in router may invoke.
     * @return a skill service answering {@code ids}, which the annotation processor would otherwise derive from the
     * OpenRPC document - and there is no annotated method here.
     */
    private static SkillService skills(final String... ids) {
        final var known = new LinkedHashSet<>(List.of(ids));
        return new SkillService(null, null, null) {
            @Override
            public boolean isMethodBacked(final String id) {
                return known.contains(id);
            }

            @Override
            public Set<String> methodBackedIds() {
                return known;
            }

            @Override
            public String singleMethodBackedId() {
                return known.size() == 1 ? known.iterator().next() : null;
            }
        };
    }

    private static JsonRpcMethod method(final String name, final Function<Object, Object> implementation) {
        return new JsonRpcMethod() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public CompletionStage<?> invoke(final Context context) {
                return completedFuture(implementation.apply(context.params()));
            }
        };
    }

    private static JsonRpcMethod notification(final String name) {
        return new JsonRpcMethod() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean isNotification() {
                return true;
            }

            @Override
            public CompletionStage<?> invoke(final Context context) {
                return completedFuture(null);
            }
        };
    }

    private static AgentRequest request(final String skillId, final Map<String, Object> arguments) {
        final var metadata = new LinkedHashMap<String, Object>();
        if (skillId != null) {
            metadata.put(AgentRequest.SKILL_ID, skillId);
        }
        if (arguments != null) {
            metadata.put(AgentRequest.ARGUMENTS, arguments);
        }
        return new AgentRequest(
                new Message("m-1", "c-1", "t-1", Role.ROLE_USER, List.of(Part.text("hello")), metadata, null, null),
                null,
                null,
                null,
                "c-1",
                "t-1",
                false,
                List.of(),
                null);
    }

    private static Object argument(final Object params, final String name) {
        return params instanceof Map<?, ?> map ? map.get(name) : null;
    }

    /**
     * The task a skill was run against, and the shortcuts to read what it became.
     */
    private static final class Driven {
        private final A2ATasks tasks;
        private final A2ATask live;
        private final DefaultTaskUpdater updater;

        private Driven(final A2ATasks tasks, final A2ATask live, final DefaultTaskUpdater updater) {
            this.tasks = tasks;
            this.live = live;
            this.updater = updater;
        }

        private DefaultTaskUpdater updater() {
            return updater;
        }

        private A2ATasks tasks() {
            return tasks;
        }

        private A2ATask live() {
            return live;
        }

        private A2ATask.Turn turn() {
            return live.turn();
        }

        private TaskState status() {
            return live.status().state();
        }

        private Task task() {
            return live.snapshot(null, true);
        }

        private String text() {
            return task().artifacts().get(0).parts().get(0).text();
        }

        private Object data() {
            return task().artifacts().get(0).parts().get(0).data();
        }
    }
}
