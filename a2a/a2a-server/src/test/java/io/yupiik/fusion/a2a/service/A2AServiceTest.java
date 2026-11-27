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

import static io.yupiik.fusion.a2a.model.A2ATimestamps.format;
import static io.yupiik.fusion.a2a.test.Awaits.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.a2a.api.A2AAgentCard;
import io.yupiik.fusion.a2a.api.A2AExecutor;
import io.yupiik.fusion.a2a.api.AgentRequest;
import io.yupiik.fusion.a2a.api.TaskUpdater;
import io.yupiik.fusion.a2a.model.AgentCapabilities;
import io.yupiik.fusion.a2a.model.AgentCard;
import io.yupiik.fusion.a2a.model.AgentExtension;
import io.yupiik.fusion.a2a.model.AgentSkill;
import io.yupiik.fusion.a2a.model.Artifact;
import io.yupiik.fusion.a2a.model.Message;
import io.yupiik.fusion.a2a.model.Part;
import io.yupiik.fusion.a2a.model.Role;
import io.yupiik.fusion.a2a.model.SendMessageConfiguration;
import io.yupiik.fusion.a2a.model.SendMessageRequest;
import io.yupiik.fusion.a2a.model.Task;
import io.yupiik.fusion.a2a.model.TaskPushNotificationConfig;
import io.yupiik.fusion.a2a.model.TaskState;
import io.yupiik.fusion.a2a.protocol.A2AErrors;
import io.yupiik.fusion.a2a.protocol.A2AProtocol;
import io.yupiik.fusion.a2a.protocol.A2AStream;
import io.yupiik.fusion.a2a.test.Agents;
import io.yupiik.fusion.a2a.test.Configurations;
import io.yupiik.fusion.a2a.test.FakeRequest;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jsonrpc.JsonRpcException;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The binding independent operations, driven without a transport.
 * <p>
 * {@code A2AJSONRPCProtocolTest} and {@code A2ARestProtocolTest} already cover the happy paths over HTTP. What only this
 * level reaches is everything depending on the <em>configuration</em> - a disabled capability, a tenant, a short task
 * timeout - since a module has one container, and therefore one configuration.
 */
@FusionSupport
class A2AServiceTest {
    // --- capabilities -------------------------------------------------------------------------------------------

    @Test
    void immediateResponseDoesNotWaitForTheFirstUpdate() {
        final var release = new CompletableFuture<Void>();
        final var agent = Agents.agent(Configurations.defaults(), (request, updater) -> release);
        final var response = agent.service()
                .sendMessage(
                        new SendMessageRequest(
                                null, Message.user("hi"), new SendMessageConfiguration(null, null, null, true), null),
                        null)
                .toCompletableFuture();
        assertTrue(response.isDone());
        assertEquals(
                TaskState.TASK_STATE_SUBMITTED, response.join().task().status().state());
        agent.tasks().updater(response.join().task().id()).orElseThrow().complete();
        release.complete(null);
    }

    @Test
    void newTasksBetweenPagesDoNotRepeatThePreviousPage() {
        final var agent = Agents.agent(Configurations.defaults(), executor());
        for (var i = 0; i < 3; i++) {
            await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null));
        }
        final var first = agent.service().listTasks(null, null, null, 1, null, null, null, null);
        await(agent.service().sendMessage(SendMessageRequest.of(Message.user("new")), null));
        final var second = agent.service().listTasks(null, null, null, 2, first.nextPageToken(), null, null, null);
        assertEquals(2, second.tasks().size());
        assertTrue(second.tasks().stream()
                .noneMatch(it -> it.id().equals(first.tasks().getFirst().id())));
    }

    @Test
    void callersCannotReadMutateContinueOrSubscribeToOtherTasks(@Fusion final JsonMapper jsons) {
        final var agent = Agents.agent(Configurations.defaults(), (request, updater) -> {
            updater.working();
            return completedFuture(null);
        });
        final var aliceHttp = new FakeRequest();
        aliceHttp.setAttribute(A2AService.CALLER_ATTRIBUTE, "alice");
        final var bobHttp = new FakeRequest();
        bobHttp.setAttribute(A2AService.CALLER_ATTRIBUTE, "bob");
        final var alice = agent.service().forCaller(aliceHttp);
        final var bob = agent.service().forCaller(bobHttp);
        final var task = await(alice.sendMessage(
                        new SendMessageRequest(
                                null, Message.user("hi"), new SendMessageConfiguration(null, null, null, true), null),
                        aliceHttp))
                .task();
        assertEquals(
                1,
                alice.listTasks(null, null, null, null, null, null, null, null)
                        .tasks()
                        .size());
        assertEquals(
                0,
                bob.listTasks(null, null, null, null, null, null, null, null)
                        .tasks()
                        .size());
        for (final var operation : List.<Executable>of(
                () -> bob.getTask(null, task.id(), null),
                () -> bob.cancelTask(null, task.id(), null),
                () -> bob.subscribeToTask(null, task.id(), sink -> A2AStream.plain(sink, jsons)),
                () -> bob.createPushConfig(TaskPushNotificationConfig.of("https://client.example.com"), task.id()),
                () -> bob.getPushConfig(null, task.id(), "config"),
                () -> bob.listPushConfigs(null, task.id(), null, null),
                () -> bob.deletePushConfig(null, task.id(), "config"),
                () -> bob.sendMessage(
                        SendMessageRequest.of(Message.user("continue").boundTo(task.contextId(), task.id())),
                        bobHttp))) {
            assertEquals(
                    A2AErrors.TASK_NOT_FOUND,
                    assertThrows(JsonRpcException.class, operation).code());
        }
        assertThrows(
                JsonRpcException.class,
                () -> agent.service().forCaller(new FakeRequest().header("Authorization", "Bearer unverified")));
        assertEquals(task.id(), alice.getTask(null, task.id(), null).id());
        assertThrows(JsonRpcException.class, () -> alice.getTask(null, task.id(), -1));
    }

    @Test
    void partNullPresenceAndOneofValidation(@Fusion final JsonMapper jsons) {
        final var part = jsons.fromString(Part.class, "{\"data\":null}");
        assertTrue(part.hasData());
        assertNull(part.data());
        assertTrue(jsons.toString(part).contains("\"data\":null"));
        assertTrue(jsons.toString(Part.data(null)).contains("\"data\":null"));
        assertThrows(IllegalArgumentException.class, () -> Part.of("text", "raw", null, null, null, null, null));
        assertThrows(RuntimeException.class, () -> jsons.fromString(Part.class, "{}"));
        assertThrows(RuntimeException.class, () -> jsons.fromString(Part.class, "{\"text\":\"x\",\"data\":null}"));
    }

    @Test
    void streamingCanBeDisabled(@Fusion final JsonMapper jsons) {
        final var agent =
                Agents.agent(Configurations.configuration().streaming(false).build(), executor());
        final var task = await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null))
                .task();

        final var streamed = assertThrows(
                JsonRpcException.class,
                () -> agent.service()
                        .sendStreamingMessage(
                                SendMessageRequest.of(Message.user("hi")), null, sink -> A2AStream.plain(sink, jsons)));
        assertEquals(A2AErrors.UNSUPPORTED_OPERATION, streamed.code());
        assertTrue(streamed.getMessage().contains("does not support streaming"), streamed::getMessage);

        final var subscribed = assertThrows(
                JsonRpcException.class,
                () -> agent.service().subscribeToTask(null, task.id(), sink -> A2AStream.plain(sink, jsons)));
        assertEquals(A2AErrors.UNSUPPORTED_OPERATION, subscribed.code());
    }

    @Test
    void pushNotificationsCanBeDisabled() {
        final var agent = Agents.agent(
                Configurations.configuration().pushNotifications(false).build(), executor());
        final var task = await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null))
                .task();

        for (final var call : List.<Executable>of(
                () -> agent.service().createPushConfig(TaskPushNotificationConfig.of("https://x"), task.id()),
                () -> agent.service().getPushConfig(null, task.id(), "c-1"),
                () -> agent.service().listPushConfigs(null, task.id(), null, null),
                () -> agent.service().deletePushConfig(null, task.id(), "c-1"))) {
            final var error = assertThrows(JsonRpcException.class, call);
            assertEquals(A2AErrors.PUSH_NOTIFICATION_NOT_SUPPORTED, error.code());
        }
    }

    @Test
    void everyOperationValidatesTheTenant(@Fusion final JsonMapper jsons) {
        final var agent =
                Agents.agent(Configurations.configuration().tenant("blue").build(), executor());
        final var task = await(agent.service()
                        .sendMessage(new SendMessageRequest("blue", Message.user("hi"), null, null), null))
                .task();

        for (final var call : List.<Executable>of(
                () -> agent.service().getTask("green", task.id(), null),
                () -> agent.service().cancelTask("green", task.id(), null),
                () -> agent.service().listTasks("green", null, null, null, null, null, null, null),
                () -> agent.service().listPushConfigs("green", task.id(), null, null),
                () -> agent.service().getPushConfig("green", task.id(), "c-1"),
                () -> agent.service().deletePushConfig("green", task.id(), "c-1"),
                () -> agent.service().extendedAgentCard("green", null),
                () -> agent.service().subscribeToTask("green", task.id(), sink -> A2AStream.plain(sink, jsons)),
                () -> await(agent.service()
                        .sendMessage(new SendMessageRequest("green", Message.user("hi"), null, null), null)),
                () -> agent.service()
                        .createPushConfig(
                                new TaskPushNotificationConfig("green", null, task.id(), "https://x", null, null),
                                task.id()))) {
            final var error = assertThrows(JsonRpcException.class, call);
            assertEquals(A2AErrors.INVALID_PARAMS, error.code(), "A tenant mismatch must be rejected");
        }

        // and the declared one is of course accepted
        assertNotNull(agent.service().getTask("blue", task.id(), null));
    }

    @Test
    void aMissingTenantIsAMismatchToo() {
        final var agent =
                Agents.agent(Configurations.configuration().tenant("blue").build(), executor());

        assertThrows(
                JsonRpcException.class,
                () -> agent.service().listTasks(null, null, null, null, null, null, null, null));
    }

    @Test
    void withoutATenantAnythingIsAccepted() {
        // nothing to route: a client echoing a tenant it made up is not the server's problem
        final var agent =
                Agents.agent(Configurations.configuration().tenant("  ").build(), executor());

        assertEquals(
                0,
                agent.service()
                        .listTasks("whatever", null, null, null, null, null, null, null)
                        .totalSize());
    }

    // --- accepted output modes ----------------------------------------------------------------------------------

    @Test
    void acceptedOutputModesAreMatchedAgainstTheCard() {
        final var agent = Agents.agent(
                Configurations.configuration()
                        .outputModes("text/plain,application/json")
                        .build(),
                executor());

        for (final var accepted :
                List.of("*", "*/*", "text/plain", "TEXT/PLAIN", "text/*", " text/plain ", "application/*")) {
            assertEquals(
                    TaskState.TASK_STATE_COMPLETED,
                    await(agent.service().sendMessage(accepting(accepted), null))
                            .task()
                            .status()
                            .state(),
                    () -> "'" + accepted + "' should have been accepted");
        }

        for (final var accepted : List.of("image/png", "text", "text/", "texts/*", "*/plain", "/*", "text/*x")) {
            final var error = assertThrows(
                    JsonRpcException.class,
                    () -> await(agent.service().sendMessage(accepting(accepted), null)),
                    () -> "'" + accepted + "' should have been refused");
            assertEquals(A2AErrors.CONTENT_TYPE_NOT_SUPPORTED, error.code());
        }
    }

    @Test
    void anAgentDeclaringNoOutputModeRestrictsNothing() {
        final var agent =
                Agents.agent(Configurations.configuration().outputModes("  ").build(), executor());

        // it never claimed to restrict its outputs, so it cannot refuse one
        assertEquals(
                TaskState.TASK_STATE_COMPLETED,
                await(agent.service().sendMessage(accepting("image/png"), null))
                        .task()
                        .status()
                        .state());
    }

    @Test
    void anAcceptedOutputModeWhichIsNotAMediaType() {
        final var agent = Agents.agent(Configurations.defaults(), executor());

        // acceptedOutputModes is free form JSON on the wire, so an entry can be null
        final var error = assertThrows(
                JsonRpcException.class,
                () -> await(agent.service()
                        .sendMessage(
                                new SendMessageRequest(
                                        null,
                                        Message.user("hi"),
                                        new SendMessageConfiguration(Arrays.asList((String) null), null, null, false),
                                        null),
                                null)));
        assertEquals(A2AErrors.CONTENT_TYPE_NOT_SUPPORTED, error.code());
    }

    @Test
    void anOutputModeTheCardDeclaresAsNothing() {
        // a customizer is free to hand the card a mode which is not a media type, and comparing against it must not
        // blow up in the middle of validating a message
        final var agent = Agents.agent(
                Configurations.defaults(),
                List.of(card -> new AgentCard(
                        card.name(),
                        card.description(),
                        card.supportedInterfaces(),
                        card.provider(),
                        card.version(),
                        card.documentationUrl(),
                        card.capabilities(),
                        card.securitySchemes(),
                        card.securityRequirements(),
                        card.defaultInputModes(),
                        Arrays.asList((String) null),
                        card.skills(),
                        card.signatures(),
                        card.iconUrl())),
                executor());

        final var error = assertThrows(
                JsonRpcException.class, () -> await(agent.service().sendMessage(accepting("text/plain"), null)));
        assertEquals(A2AErrors.CONTENT_TYPE_NOT_SUPPORTED, error.code());
    }

    @Test
    void anEmptyAcceptedOutputModesIsNotARestriction() {
        final var agent = Agents.agent(Configurations.defaults(), executor());

        assertNotNull(await(agent.service()
                .sendMessage(
                        new SendMessageRequest(
                                null,
                                Message.user("hi"),
                                new SendMessageConfiguration(List.of(), null, null, false),
                                null),
                        null)));
        assertNotNull(await(agent.service()
                .sendMessage(
                        new SendMessageRequest(
                                null, Message.user("hi"), new SendMessageConfiguration(null, null, null, false), null),
                        null)));
    }

    // --- extensions ---------------------------------------------------------------------------------------------

    @Test
    void aRequiredExtensionMustBeDeclaredByTheClient() {
        final var uri = "https://example.com/required";
        final var agent = Agents.agent(
                Configurations.defaults(), List.of(withExtension(AgentExtension.of(uri, "Needed.", true))), executor());

        final var error = assertThrows(
                JsonRpcException.class,
                () -> await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null)));
        assertEquals(A2AErrors.EXTENSION_SUPPORT_REQUIRED, error.code());

        // the transport puts what the client declared in a request attribute, so a declaring client goes through
        final var declared = FakeRequest.of("POST", "/a2a");
        declared.setAttribute(A2AProtocol.EXTENSIONS_ATTRIBUTE, List.of(uri));
        assertEquals(
                TaskState.TASK_STATE_COMPLETED,
                await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), declared))
                        .task()
                        .status()
                        .state());
    }

    @Test
    void aRequestCarryingNoExtensionAttribute() {
        final var agent = Agents.agent(Configurations.defaults(), executor());

        // the transport always fills the attribute in, but a call reaching the service another way carries none
        assertEquals(
                TaskState.TASK_STATE_COMPLETED,
                await(agent.service()
                                .sendMessage(SendMessageRequest.of(Message.user("hi")), FakeRequest.of("POST", "/a2a")))
                        .task()
                        .status()
                        .state());
    }

    @Test
    void anOptionalExtensionNeedsNothing() {
        final var agent = Agents.agent(
                Configurations.defaults(),
                List.of(withExtension(AgentExtension.of("https://example.com/optional", "Nice to have.", false))),
                executor());

        assertNotNull(await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null))
                .task());
    }

    // --- the message itself -------------------------------------------------------------------------------------

    @Test
    void aMessageIsRequired() {
        final var agent = Agents.agent(Configurations.defaults(), executor());

        assertEquals(
                A2AErrors.INVALID_PARAMS,
                assertThrows(JsonRpcException.class, () -> await(agent.service().sendMessage(null, null)))
                        .code());
        assertEquals(
                A2AErrors.INVALID_PARAMS,
                assertThrows(
                                JsonRpcException.class,
                                () -> await(agent.service()
                                        .sendMessage(new SendMessageRequest(null, null, null, null), null)))
                        .code());
    }

    @Test
    void atLeastOnePartIsRequired() {
        final var agent = Agents.agent(Configurations.defaults(), executor());

        final var empty = assertThrows(
                JsonRpcException.class,
                () -> await(agent.service()
                        .sendMessage(SendMessageRequest.of(Message.of(Role.ROLE_USER, List.<Part>of())), null)));
        assertEquals(A2AErrors.INVALID_PARAMS, empty.code());

        final var absent = assertThrows(
                JsonRpcException.class,
                () -> await(agent.service()
                        .sendMessage(
                                SendMessageRequest.of(
                                        new Message("m-1", null, null, Role.ROLE_USER, null, null, null, null)),
                                null)));
        assertEquals(A2AErrors.INVALID_PARAMS, absent.code());
    }

    @Test
    void aNewConversationGetsAGeneratedContext() {
        final var agent = Agents.agent(Configurations.defaults(), executor());

        final var generated = await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null))
                .task();
        assertNotNull(generated.contextId());
        assertNotNull(UUID.fromString(generated.contextId()));

        // a blank one is no context either
        final var blank = await(agent.service()
                        .sendMessage(
                                SendMessageRequest.of(new Message(
                                        "m-1", "  ", null, Role.ROLE_USER, List.of(Part.text("hi")), null, null, null)),
                                null))
                .task();
        assertFalse(blank.contextId().isBlank());

        final var explicit = await(agent.service()
                        .sendMessage(
                                SendMessageRequest.of(new Message(
                                        "m-2",
                                        "c-mine",
                                        null,
                                        Role.ROLE_USER,
                                        List.of(Part.text("hi")),
                                        null,
                                        null,
                                        null)),
                                null))
                .task();
        assertEquals("c-mine", explicit.contextId());
    }

    @Test
    void continuingATaskWithoutRepeatingTheContext() {
        final var agent = Agents.agent(Configurations.defaults(), executor());
        final var first = await(agent.service().sendMessage(SendMessageRequest.of(Message.user("ask")), null))
                .task();

        // the context is optional on a continuation, the task identifier already designates the conversation
        final var second = await(agent.service()
                        .sendMessage(
                                SendMessageRequest.of(new Message(
                                        "m-2",
                                        "  ",
                                        first.id(),
                                        Role.ROLE_USER,
                                        List.of(Part.text("Paris")),
                                        null,
                                        null,
                                        null)),
                                null))
                .task();

        assertEquals(first.id(), second.id());
        assertEquals(first.contextId(), second.contextId());
        assertEquals(TaskState.TASK_STATE_COMPLETED, second.status().state());
    }

    @Test
    void aBlankTaskIdentifierStartsANewTask() {
        final var agent = Agents.agent(Configurations.defaults(), executor());

        // an identifier which is only whitespace designates nothing, so it must not be looked up
        final var task = await(agent.service()
                        .sendMessage(
                                SendMessageRequest.of(new Message(
                                        "m-1", null, "  ", Role.ROLE_USER, List.of(Part.text("hi")), null, null, null)),
                                null))
                .task();

        assertNotNull(task.id());
        assertFalse(task.id().isBlank());
        assertEquals(TaskState.TASK_STATE_COMPLETED, task.status().state());
    }

    @Test
    void aTaskWhichIsNotThereCannotBeContinued() {
        final var agent = Agents.agent(Configurations.defaults(), executor());

        final var error = assertThrows(
                JsonRpcException.class,
                () -> await(agent.service()
                        .sendMessage(
                                SendMessageRequest.of(new Message(
                                        "m-1",
                                        null,
                                        "made-up",
                                        Role.ROLE_USER,
                                        List.of(Part.text("hi")),
                                        null,
                                        null,
                                        null)),
                                null)));
        assertEquals(A2AErrors.TASK_NOT_FOUND, error.code());
    }

    // --- executor selection -------------------------------------------------------------------------------------

    @Test
    void aRefusingExecutorIsSkipped() {
        final var called = new ArrayList<String>();
        final var agent = Agents.agent(
                Configurations.defaults(),
                new A2AExecutor() {
                    @Override
                    public boolean accept(final AgentRequest request) {
                        return false;
                    }

                    @Override
                    public CompletionStage<Void> execute(final AgentRequest request, final TaskUpdater updater) {
                        called.add("refusing");
                        return completedFuture(null);
                    }
                },
                executor());

        assertEquals(
                TaskState.TASK_STATE_COMPLETED,
                await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null))
                        .task()
                        .status()
                        .state());
        assertEquals(List.of(), called, "an executor which refused the message must not be called");
    }

    @Test
    void anExecutorWhichCannotEvenDecideIsSkipped() {
        final var agent = Agents.agent(
                Configurations.defaults(),
                new A2AExecutor() {
                    @Override
                    public boolean accept(final AgentRequest request) {
                        throw new IllegalStateException("my routing table is broken");
                    }

                    @Override
                    public CompletionStage<Void> execute(final AgentRequest request, final TaskUpdater updater) {
                        return completedFuture(null);
                    }
                },
                executor());

        // a broken executor must not take the whole agent down with it
        assertEquals(
                TaskState.TASK_STATE_COMPLETED,
                await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null))
                        .task()
                        .status()
                        .state());
    }

    @Test
    void withoutAnyExecutorThereIsNothingToAnswerWith() {
        final var agent = Agents.agent(Configurations.defaults());

        final var error = assertThrows(
                JsonRpcException.class,
                () -> await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null)));
        assertEquals(A2AErrors.UNSUPPORTED_OPERATION, error.code());
        assertEquals("No executor accepted the message", error.getMessage());
        // and the task which was created for that message is not left behind
        assertEquals(0, agent.tasks().tasks().size());
    }

    @Test
    void aRefusedContinuationKeepsTheTask() {
        final var accepted = new boolean[] {true};
        final var agent = Agents.agent(Configurations.defaults(), new A2AExecutor() {
            @Override
            public boolean accept(final AgentRequest request) {
                return accepted[0];
            }

            @Override
            public CompletionStage<Void> execute(final AgentRequest request, final TaskUpdater updater) {
                updater.inputRequired(Message.agent("more?"));
                return completedFuture(null);
            }
        });
        final var task = await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null))
                .task();

        accepted[0] = false;
        assertThrows(
                JsonRpcException.class,
                () -> await(agent.service()
                        .sendMessage(
                                SendMessageRequest.of(new Message(
                                        "m-2",
                                        null,
                                        task.id(),
                                        Role.ROLE_USER,
                                        List.of(Part.text("more")),
                                        null,
                                        null,
                                        null)),
                                null)));

        // the task existed before this message, refusing the message must not destroy it
        assertNotNull(agent.tasks().find(task.id()).orElse(null));
        assertEquals(task, agent.service().getTask(null, task.id(), null), "a refused message is not recorded");
    }

    @Test
    void invalidInlinePushConfigurationsLeaveNoNewTaskBehind() {
        for (final var configuration : List.of(
                Configurations.defaults(),
                Configurations.configuration().pushNotifications(false).build())) {
            final var agent = Agents.agent(configuration, executor());
            final var request = new SendMessageRequest(
                    null,
                    Message.user("hi"),
                    new SendMessageConfiguration(null, TaskPushNotificationConfig.of(" "), null, false),
                    null);

            assertThrows(JsonRpcException.class, () -> agent.service().sendMessage(request, null));

            assertTrue(agent.tasks().tasks().isEmpty(), "a rejected request must not leave a submitted task");
        }
    }

    @Test
    void invalidInlinePushConfigurationDoesNotChangeAContinuation() {
        final var agent = Agents.agent(Configurations.defaults(), (request, updater) -> {
            updater.inputRequired(Message.agent("more?"));
            return completedFuture(null);
        });
        final var task = await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null))
                .task();
        final var request = new SendMessageRequest(
                null,
                Message.user("more").boundTo(task.contextId(), task.id()),
                new SendMessageConfiguration(null, TaskPushNotificationConfig.of(" "), null, false),
                null);

        assertThrows(JsonRpcException.class, () -> agent.service().sendMessage(request, null));

        assertEquals(task, agent.service().getTask(null, task.id(), null));
        assertTrue(agent.service()
                .listPushConfigs(null, task.id(), null, null)
                .configs()
                .isEmpty());
    }

    @Test
    void rejectedExecutionRestoresContinuationHistoryAndWebhooks() {
        for (final var asynchronous : List.of(false, true)) {
            for (final var replacement : List.of(false, true)) {
                final var rejection = new CompletableFuture<Void>();
                final var agent = Agents.agent(Configurations.defaults(), (request, updater) -> {
                    if (request.continuation()) {
                        assertEquals(request.message(), updater.task().history().getLast());
                        if (asynchronous) {
                            return rejection;
                        }
                        throw A2AErrors.invalidParams("message", "Rejected continuation");
                    }
                    updater.inputRequired(Message.agent("more?"));
                    return completedFuture(null);
                });
                final var task = await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null))
                        .task();
                final var existing = agent.service()
                        .createPushConfig(TaskPushNotificationConfig.of("https://original.example/hook"), task.id());
                final var registered = TaskPushNotificationConfig.of("https://rejected.example/hook")
                        .boundTo(replacement ? existing.id() : "new-hook", task.id());
                final var request = new SendMessageRequest(
                        null,
                        Message.user("rejected").boundTo(task.contextId(), task.id()),
                        new SendMessageConfiguration(null, registered, null, false),
                        null);
                final var previousRequest =
                        agent.tasks().find(task.id()).orElseThrow().request();
                final var response = agent.service().sendMessage(request, null);
                if (asynchronous) {
                    rejection.completeExceptionally(A2AErrors.invalidParams("message", "Rejected continuation"));
                }
                assertThrows(JsonRpcException.class, () -> await(response));
                assertEquals(task, agent.service().getTask(null, task.id(), null));
                assertEquals(
                        List.of(existing),
                        agent.service()
                                .listPushConfigs(null, task.id(), null, null)
                                .configs());
                assertEquals(
                        previousRequest,
                        agent.tasks().find(task.id()).orElseThrow().request());
            }
        }
    }

    // --- executor failures --------------------------------------------------------------------------------------

    @Test
    void anExecutorThrowingBeforeStartingFailsTheTask() {
        final var agent = Agents.agent(Configurations.defaults(), (request, updater) -> {
            throw new IllegalStateException("the backend is down");
        });

        final var task = await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null))
                .task();
        assertEquals(TaskState.TASK_STATE_FAILED, task.status().state());
        assertEquals("the backend is down", task.status().message().text());
    }

    @Test
    void aFailureWithoutAMessageStillSaysSomething() {
        final var agent = Agents.agent(Configurations.defaults(), (request, updater) -> {
            throw new IllegalStateException();
        });

        // an empty status message would tell the caller nothing at all, so the type is used instead
        assertEquals(
                "IllegalStateException",
                await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null))
                        .task()
                        .status()
                        .message()
                        .text());
    }

    @Test
    void aFailedStageFailsTheTask() {
        final var agent = Agents.agent(
                Configurations.defaults(),
                (request, updater) -> CompletableFuture.failedFuture(new IllegalStateException("later")));

        assertEquals(
                "later",
                await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null))
                        .task()
                        .status()
                        .message()
                        .text());
    }

    @Test
    void anExecutorAnsweringNullIsAFailure() {
        final var agent = Agents.agent(Configurations.defaults(), (request, updater) -> null);

        final var task = await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null))
                .task();
        assertEquals(TaskState.TASK_STATE_FAILED, task.status().state());
        assertTrue(
                task.status().message().text().contains("answered null"),
                () -> task.status().message().text());
    }

    @Test
    void anExecutorReportingNothingIsAFailure() {
        final var agent = Agents.agent(Configurations.defaults(), (request, updater) -> completedFuture(null));

        final var task = await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null))
                .task();
        assertEquals(TaskState.TASK_STATE_FAILED, task.status().state());
        assertTrue(
                task.status().message().text().contains("did not report anything"),
                () -> task.status().message().text());
    }

    @Test
    void anInvalidCallIsAProtocolErrorAndNotAFailedTask() {
        final var agent = Agents.agent(Configurations.defaults(), (request, updater) -> {
            throw A2AErrors.invalidParams("metadata.skillId", "Unknown skill");
        });

        final var error = assertThrows(
                JsonRpcException.class,
                () -> await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null)));
        assertEquals(A2AErrors.INVALID_PARAMS, error.code());
        assertEquals(0, agent.tasks().tasks().size(), "no task is left behind for a call which was never valid");
    }

    @Test
    void anInvalidCallOnAStreamIsAFailedTaskInstead(@Fusion final JsonMapper jsons) {
        final var agent = Agents.agent(Configurations.defaults(), (request, updater) -> {
            throw A2AErrors.invalidParams("metadata.skillId", "Unknown skill");
        });

        // the response is already committed as an event stream, so the only way left to report is the task itself
        final var stream = agent.service()
                .sendStreamingMessage(
                        SendMessageRequest.of(Message.user("hi")), null, sink -> A2AStream.plain(sink, jsons));

        assertTrue(stream.isClosed());
        assertEquals(
                TaskState.TASK_STATE_FAILED,
                agent.tasks().tasks().iterator().next().status().state());
    }

    @Test
    void aRejectedMessageIsTraced() {
        final var logger = Logger.getLogger(A2AService.class.getName());
        final var level = logger.getLevel();
        // the rejection is logged at FINE, so the message is only built when somebody asked for it
        logger.setLevel(Level.FINEST);
        try {
            final var agent = Agents.agent(Configurations.defaults(), (request, updater) -> {
                throw A2AErrors.invalidParams("metadata.skillId", "Unknown skill");
            });

            assertEquals(
                    A2AErrors.INVALID_PARAMS,
                    assertThrows(
                                    JsonRpcException.class,
                                    () -> await(agent.service()
                                            .sendMessage(SendMessageRequest.of(Message.user("hi")), null)))
                            .code());
        } finally {
            logger.setLevel(level);
        }
    }

    @Test
    void anExecutorFailingAfterATransitionFailsTheTask() {
        final var agent = Agents.agent(Configurations.defaults(), (request, updater) -> {
            updater.working();
            throw A2AErrors.invalidParams("late", "too late to be a protocol error");
        });

        // the client already saw the task, so it has to be told through the task
        assertEquals(
                TaskState.TASK_STATE_FAILED,
                await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null))
                        .task()
                        .status()
                        .state());
    }

    @Test
    void anEnvelopedFailureIsReportedByWhatItWraps() {
        // an executor is free to answer a stage carrying an ExecutionException, which is an envelope like any other
        final var agent = Agents.agent(
                Configurations.defaults(),
                (request, updater) -> CompletableFuture.failedFuture(
                        new ExecutionException("wrapped", new IllegalStateException("the real one"))));

        assertEquals(
                "the real one",
                await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null))
                        .task()
                        .status()
                        .message()
                        .text());
    }

    @Test
    void aNotifierWhichBreaksWhileFailingATask(@Fusion final JsonMapper jsons) {
        final var agent = Agents.agent(Configurations.defaults(), (request, updater) -> {
            throw new IllegalStateException("the backend is down");
        });
        agent.push().failWith(new IllegalStateException("the notifier is broken too"));

        // reporting the failure is itself going to break, so the turn never settles and a blocking SendMessage could
        // only answer on its timeout. Streaming hands the work over synchronously and awaits nothing, which is what
        // makes the swallowed notifier observable without waiting for anything at all.
        agent.service()
                .sendStreamingMessage(
                        SendMessageRequest.of(Message.user("hi")), null, sink -> A2AStream.plain(sink, jsons));

        // the failure handler swallowed the broken notifier, so the task still carries the outcome
        final var task = agent.tasks().tasks().iterator().next();
        assertEquals(TaskState.TASK_STATE_FAILED, task.status().state());
        assertEquals("the backend is down", task.status().message().text());
    }

    @Test
    void aFailureAfterTheTaskEndedIsSwallowed() {
        final var agent = Agents.agent(Configurations.defaults(), (request, updater) -> {
            updater.complete(Artifact.text("a", "done"));
            return CompletableFuture.failedFuture(new IllegalStateException("and then it broke"));
        });

        // the task is terminal: it must keep the outcome the client already got
        final var task = await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null))
                .task();
        assertEquals(TaskState.TASK_STATE_COMPLETED, task.status().state());
    }

    // --- blocking and non blocking ------------------------------------------------------------------------------

    @Test
    void aBlockingTaskWhichDoesNotSettleInTimeFailsWithoutReturningAnUnfinishedTask() {
        // a tenth of a second: the point is the timeout firing, not how long a real deployment waits
        final var agent = Agents.agent(
                Configurations.configuration().taskTimeout("PT0.1S").build(), (request, updater) -> {
                    updater.working(Message.agent("still at it"));
                    return completedFuture(null);
                });

        final var failure = assertThrows(
                JsonRpcException.class,
                () -> await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null)));
        assertEquals(A2AErrors.INTERNAL_ERROR, failure.code());
        final var taskId = (String) ((Map<?, ?>) failure.data()).get("taskId");
        assertEquals(
                TaskState.TASK_STATE_WORKING,
                agent.service().getTask(null, taskId, null).status().state());

        // The timeout applies to a copy: execution remains observable and can still settle afterwards.
        agent.tasks().updater(taskId).orElseThrow().complete(Artifact.text("a", "late but done"));
        assertEquals(
                TaskState.TASK_STATE_COMPLETED,
                agent.service().getTask(null, taskId, null).status().state());
    }

    @Test
    void returnImmediatelyAnswersTheFirstAction() {
        final var release = new CompletableFuture<Void>();
        final var agent = Agents.agent(Configurations.defaults(), (request, updater) -> {
            updater.working();
            return release.thenRun(() -> updater.complete(Artifact.text("a", "done")));
        });

        final var task = await(agent.service()
                        .sendMessage(
                                new SendMessageRequest(
                                        null,
                                        Message.user("hi"),
                                        new SendMessageConfiguration(null, null, null, true),
                                        null),
                                null))
                .task();

        assertEquals(TaskState.TASK_STATE_WORKING, task.status().state());
        release.complete(null);
        assertEquals(
                TaskState.TASK_STATE_COMPLETED,
                agent.service().getTask(null, task.id(), null).status().state());
    }

    // --- history truncation -------------------------------------------------------------------------------------

    @Test
    void theAnsweredHistoryIsTruncated() {
        final var agent = Agents.agent(Configurations.defaults(), (request, updater) -> {
            updater.working(Message.agent("one"));
            updater.complete(Artifact.text("a", "two"));
            return completedFuture(null);
        });

        // the incoming message plus the working one
        assertEquals(
                2,
                await(agent.service().sendMessage(withHistoryLength(null), null))
                        .task()
                        .history()
                        .size());
        assertEquals(
                1,
                await(agent.service().sendMessage(withHistoryLength(1), null))
                        .task()
                        .history()
                        .size());
        assertNull(
                await(agent.service().sendMessage(withHistoryLength(0), null))
                        .task()
                        .history(),
                "0 asks for no history at all, and the attribute must then be omitted");
        assertEquals(
                2,
                await(agent.service().sendMessage(withHistoryLength(10), null))
                        .task()
                        .history()
                        .size(),
                "asking for more than there is answers everything");
    }

    @Test
    void thereIsNoHistoryToTruncateOnAMessageAnswer() {
        final var agent = Agents.agent(Configurations.defaults(), (request, updater) -> {
            updater.respond(Message.agent("no task needed"));
            return completedFuture(null);
        });

        final var response = await(agent.service().sendMessage(withHistoryLength(1), null));
        assertNull(response.task());
        assertEquals("no task needed", response.message().text());
    }

    // --- ListTasks ----------------------------------------------------------------------------------------------

    @Test
    void listTasksFiltersAndSorts() throws Exception {
        final var agent = Agents.agent(Configurations.defaults(), executor());
        final var completed = await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null))
                .task();
        // the status timestamp has a millisecond precision, so two tasks created within one would tie and the order
        // would then depend on their generated identifiers
        Thread.sleep(5);
        final var waiting = await(agent.service().sendMessage(SendMessageRequest.of(Message.user("ask")), null))
                .task();

        final var all = agent.service().listTasks(null, null, null, null, null, null, null, null);
        assertEquals(2, all.totalSize());
        assertEquals(A2AService.DEFAULT_PAGE_SIZE, all.pageSize());
        assertEquals("", all.nextPageToken(), "the last page carries an empty token, not null");
        // most recently updated first
        assertEquals(waiting.id(), all.tasks().get(0).id());
        assertNull(all.tasks().get(0).artifacts(), "the outputs are omitted by default");

        assertEquals(
                List.of(completed.id()),
                agent
                        .service()
                        .listTasks(null, completed.contextId(), null, null, null, null, null, null)
                        .tasks()
                        .stream()
                        .map(Task::id)
                        .toList());
        assertEquals(
                List.of(waiting.id()),
                agent
                        .service()
                        .listTasks(null, null, TaskState.TASK_STATE_INPUT_REQUIRED, null, null, null, null, null)
                        .tasks()
                        .stream()
                        .map(Task::id)
                        .toList());
        assertEquals(
                1,
                agent.service().listTasks(null, null, null, null, null, null, null, true).tasks().stream()
                        .filter(it -> it.artifacts() != null && !it.artifacts().isEmpty())
                        .count());
    }

    @Test
    void listTasksIgnoresTheNeutralFilters() {
        final var agent = Agents.agent(Configurations.defaults(), executor());
        await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null));

        assertEquals(
                1,
                agent.service()
                        .listTasks(null, "  ", null, null, null, null, null, null)
                        .totalSize());
        assertEquals(
                1,
                agent.service()
                        .listTasks(null, null, TaskState.TASK_STATE_UNSPECIFIED, null, null, null, null, null)
                        .totalSize(),
                "an unspecified state is not a filter");
    }

    @Test
    void listTasksPaginates() {
        final var agent = Agents.agent(Configurations.defaults(), executor());
        for (int i = 0; i < 3; i++) {
            await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi-" + i)), null));
        }

        final var first = agent.service().listTasks(null, null, null, 2, null, null, null, null);
        assertEquals(2, first.tasks().size());
        assertEquals(3, first.totalSize());
        assertFalse(first.nextPageToken().isEmpty());

        final var second = agent.service().listTasks(null, null, null, 2, first.nextPageToken(), null, null, null);
        assertEquals(1, second.tasks().size());
        assertEquals("", second.nextPageToken());

        assertThrows(
                JsonRpcException.class,
                () -> agent.service().listTasks(null, null, null, 2, cursor(99), null, null, null));
    }

    @Test
    void listTasksRejectsInvalidPageSizes() {
        final var agent = Agents.agent(Configurations.defaults(), executor());
        for (final var size : List.of(-1, 0, 101, 1000)) {
            final var error = assertThrows(
                    JsonRpcException.class,
                    () -> agent.service().listTasks(null, null, null, size, null, null, null, null));
            assertEquals(A2AErrors.INVALID_PARAMS, error.code());
        }
        assertEquals(
                A2AService.DEFAULT_PAGE_SIZE,
                agent.service()
                        .listTasks(null, null, null, null, null, null, null, null)
                        .pageSize());
    }

    @Test
    void listTasksRefusesACursorItDidNotIssue() {
        final var agent = Agents.agent(Configurations.defaults(), executor());

        for (final var token : List.of("not-a-cursor", "###")) {
            final var error = assertThrows(
                    JsonRpcException.class,
                    () -> agent.service().listTasks(null, null, null, null, token, null, null, null));
            assertEquals(A2AErrors.INVALID_PARAMS, error.code());
        }
        assertThrows(
                JsonRpcException.class,
                () -> agent.service().listTasks(null, null, null, null, "  ", null, null, null));
    }

    @Test
    void listTasksFiltersOnTheStatusTimestamp() {
        final var agent = Agents.agent(Configurations.defaults(), executor());
        await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null));

        assertEquals(
                1,
                agent.service()
                        .listTasks(
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                format(Instant.now().minusSeconds(60)),
                                null)
                        .totalSize());
        assertEquals(
                0,
                agent.service()
                        .listTasks(
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                format(Instant.now().plusSeconds(60)),
                                null)
                        .totalSize());
    }

    @Test
    void listTasksRejectsAnInvalidTimestamp() {
        final var agent = Agents.agent(Configurations.defaults(), executor());

        final var error = assertThrows(
                JsonRpcException.class,
                () -> agent.service().listTasks(null, null, null, null, null, null, "yesterday", null));
        assertEquals(A2AErrors.INVALID_PARAMS, error.code());
    }

    // --- push notification configurations -----------------------------------------------------------------------

    @Test
    void aWebhookNeedsAUrl() {
        final var agent = Agents.agent(Configurations.defaults(), executor());
        final var task = await(agent.service().sendMessage(SendMessageRequest.of(Message.user("ask")), null))
                .task();

        assertEquals(
                A2AErrors.INVALID_PARAMS,
                assertThrows(JsonRpcException.class, () -> agent.service().createPushConfig(null, task.id()))
                        .code());
        assertEquals(
                A2AErrors.INVALID_PARAMS,
                assertThrows(
                                JsonRpcException.class,
                                () -> agent.service().createPushConfig(TaskPushNotificationConfig.of(null), task.id()))
                        .code());
        assertEquals(
                A2AErrors.INVALID_PARAMS,
                assertThrows(
                                JsonRpcException.class,
                                () -> agent.service().createPushConfig(TaskPushNotificationConfig.of("  "), task.id()))
                        .code());
    }

    @Test
    void aWebhookIdentifierCanBeChosenByTheClient() {
        final var agent = Agents.agent(Configurations.defaults(), executor());
        final var task = await(agent.service().sendMessage(SendMessageRequest.of(Message.user("ask")), null))
                .task();

        final var chosen = agent.service()
                .createPushConfig(
                        new TaskPushNotificationConfig(
                                null, "mine", null, "https://client.example.com/hook", null, null),
                        task.id());
        assertEquals("mine", chosen.id());

        final var generated = agent.service()
                .createPushConfig(
                        new TaskPushNotificationConfig(null, "  ", null, "https://client.example.com/hook", null, null),
                        task.id());
        assertNotNull(UUID.fromString(generated.id()), "a blank identifier is no identifier");
    }

    @Test
    void theTaskOfTheBodyIsUsedWhenThePathCarriesNone() {
        final var agent = Agents.agent(Configurations.defaults(), executor());
        final var task = await(agent.service().sendMessage(SendMessageRequest.of(Message.user("ask")), null))
                .task();

        // the JSON-RPC binding carries the task in the payload, the HTTP+JSON one in the path
        assertEquals(
                task.id(),
                agent.service()
                        .createPushConfig(
                                new TaskPushNotificationConfig(null, null, task.id(), "https://x", null, null), null)
                        .taskId());
        assertEquals(
                task.id(),
                agent.service()
                        .createPushConfig(
                                new TaskPushNotificationConfig(null, null, task.id(), "https://x", null, null), "  ")
                        .taskId());
    }

    @Test
    void aWebhookCanBeRegisteredWithTheMessageItself() {
        final var agent = Agents.agent(Configurations.defaults(), executor());

        final var task = await(agent.service()
                        .sendMessage(
                                new SendMessageRequest(
                                        null,
                                        Message.user("ask"),
                                        new SendMessageConfiguration(
                                                null,
                                                new TaskPushNotificationConfig(
                                                        null,
                                                        null,
                                                        null,
                                                        "https://client.example.com/hook",
                                                        null,
                                                        null),
                                                null,
                                                false),
                                        null),
                                null))
                .task();

        // nothing is missed: the webhook exists before the executor is even called
        assertEquals(
                1,
                agent.service()
                        .listPushConfigs(null, task.id(), null, null)
                        .configs()
                        .size());
        assertFalse(agent.push().events().isEmpty());
    }

    @Test
    void aMissingWebhookIsATaskNotFound() {
        final var agent = Agents.agent(Configurations.defaults(), executor());
        final var task = await(agent.service().sendMessage(SendMessageRequest.of(Message.user("ask")), null))
                .task();

        // the specification asks not to distinguish a missing task from a missing configuration
        final var error =
                assertThrows(JsonRpcException.class, () -> agent.service().getPushConfig(null, task.id(), "nope"));
        assertEquals(A2AErrors.TASK_NOT_FOUND, error.code());
    }

    @Test
    void deletingAWebhookIsIdempotent() {
        final var agent = Agents.agent(Configurations.defaults(), executor());
        final var task = await(agent.service().sendMessage(SendMessageRequest.of(Message.user("ask")), null))
                .task();
        final var config = agent.service()
                .createPushConfig(TaskPushNotificationConfig.of("https://client.example.com/hook"), task.id());

        agent.service().deletePushConfig(null, task.id(), config.id());
        agent.service().deletePushConfig(null, task.id(), config.id());
        agent.service().deletePushConfig(null, task.id(), "never-existed");

        assertEquals(
                List.of(),
                agent.service().listPushConfigs(null, task.id(), null, null).configs());
    }

    @Test
    void listingWebhooksPaginates() {
        final var agent = Agents.agent(Configurations.defaults(), executor());
        final var task = await(agent.service().sendMessage(SendMessageRequest.of(Message.user("ask")), null))
                .task();
        for (int i = 0; i < 3; i++) {
            agent.service()
                    .createPushConfig(TaskPushNotificationConfig.of("https://client.example.com/" + i), task.id());
        }

        final var first = agent.service().listPushConfigs(null, task.id(), 2, null);
        assertEquals(2, first.configs().size());
        assertFalse(first.nextPageToken().isEmpty());

        final var second = agent.service().listPushConfigs(null, task.id(), 2, first.nextPageToken());
        assertEquals(1, second.configs().size());
        assertEquals("", second.nextPageToken());

        assertThrows(JsonRpcException.class, () -> agent.service().listPushConfigs(null, task.id(), 1000, null));
        assertThrows(JsonRpcException.class, () -> agent.service().listPushConfigs(null, task.id(), 0, null));
    }

    // --- CancelTask ---------------------------------------------------------------------------------------------

    @Test
    void cancellingConcludesTheTaskWhenTheExecutorDoesNot() {
        final var agent = Agents.agent(Configurations.defaults(), new A2AExecutor() {
            @Override
            public CompletionStage<Void> execute(final AgentRequest request, final TaskUpdater updater) {
                updater.working();
                return completedFuture(null);
            }

            @Override
            public void cancel(final AgentRequest request, final TaskUpdater updater) {
                // signals its work and returns without concluding, which the server has to finish for it
            }
        });
        final var task = await(agent.service()
                        .sendMessage(
                                new SendMessageRequest(
                                        null,
                                        Message.user("hi"),
                                        new SendMessageConfiguration(null, null, null, true),
                                        null),
                                null))
                .task();

        assertEquals(
                TaskState.TASK_STATE_CANCELED,
                agent.service().cancelTask(null, task.id(), null).status().state());
    }

    @Test
    void anExecutorFailingToCancelDoesNotBlockTheCancellation() {
        final var agent = Agents.agent(Configurations.defaults(), new A2AExecutor() {
            @Override
            public CompletionStage<Void> execute(final AgentRequest request, final TaskUpdater updater) {
                updater.working();
                return completedFuture(null);
            }

            @Override
            public void cancel(final AgentRequest request, final TaskUpdater updater) {
                throw new IllegalStateException("I cannot stop");
            }
        });
        final var task = await(agent.service()
                        .sendMessage(
                                new SendMessageRequest(
                                        null,
                                        Message.user("hi"),
                                        new SendMessageConfiguration(null, null, null, true),
                                        null),
                                null))
                .task();

        // the client asked to stop: it gets a canceled task whatever the executor thinks
        assertEquals(
                TaskState.TASK_STATE_CANCELED,
                agent.service().cancelTask(null, task.id(), null).status().state());
    }

    @Test
    void aTaskNobodyStartedIsStillCancelable() {
        final var agent = Agents.agent(Configurations.defaults(), executor());
        // created out of band, so it carries neither request nor executor
        final var task = agent.tasks().create("c-1", null);

        assertNull(task.request());
        assertNull(task.executor());
        assertEquals(
                TaskState.TASK_STATE_CANCELED,
                agent.service().cancelTask(null, task.id(), null).status().state());
    }

    @Test
    void aTurnOpenedWithoutARequestIsCancelableToo() {
        final var asked = new ArrayList<String>();
        final var executor = new A2AExecutor() {
            @Override
            public CompletionStage<Void> execute(final AgentRequest request, final TaskUpdater updater) {
                return completedFuture(null);
            }

            @Override
            public void cancel(final AgentRequest request, final TaskUpdater updater) {
                asked.add("cancel");
            }
        };
        final var agent = Agents.agent(Configurations.defaults(), executor);
        final var task = agent.tasks().create("c-1", null);
        // an out of band turn: there is an executor to stop but no request to hand it
        task.startTurn(null, executor);

        assertEquals(
                TaskState.TASK_STATE_CANCELED,
                agent.service().cancelTask(null, task.id(), null).status().state());
        assertEquals(List.of(), asked, "there is nothing to describe the work, so the executor is not asked");
    }

    @Test
    void aTerminalTaskIsNotCancelable() {
        final var agent = Agents.agent(Configurations.defaults(), executor());
        final var task = await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null))
                .task();

        final var error =
                assertThrows(JsonRpcException.class, () -> agent.service().cancelTask(null, task.id(), null));
        assertEquals(A2AErrors.TASK_NOT_CANCELABLE, error.code());
    }

    // --- SubscribeToTask ----------------------------------------------------------------------------------------

    @Test
    void subscribingStartsWithTheCurrentState(@Fusion final JsonMapper jsons) {
        final var agent = Agents.agent(Configurations.defaults(), executor());
        final var task = await(agent.service()
                        .sendMessage(
                                new SendMessageRequest(
                                        null,
                                        Message.user("ask"),
                                        new SendMessageConfiguration(null, null, null, true),
                                        null),
                                null))
                .task();

        // input required is not terminal, so the task can still be watched
        final var stream = agent.service().subscribeToTask(null, task.id(), sink -> A2AStream.plain(sink, jsons));

        assertNotNull(stream);
        assertEquals(1, agent.tasks().require(task.id()).streams().size());
    }

    @Test
    void subscribingToATerminalTaskIsRefused(@Fusion final JsonMapper jsons) {
        final var agent = Agents.agent(Configurations.defaults(), executor());
        final var task = await(agent.service().sendMessage(SendMessageRequest.of(Message.user("hi")), null))
                .task();

        final var error = assertThrows(
                JsonRpcException.class,
                () -> agent.service().subscribeToTask(null, task.id(), sink -> A2AStream.plain(sink, jsons)));
        assertEquals(A2AErrors.UNSUPPORTED_OPERATION, error.code());
        assertTrue(error.getMessage().contains("terminal state"), error::getMessage);
    }

    // --- the extended card --------------------------------------------------------------------------------------

    @Test
    void theExtendedCardIsServedWhenConfigured() {
        final var agent = Agents.agent(
                Configurations.configuration().extendedAgentCard(true).build(),
                List.of(new A2AAgentCard() {
                    @Override
                    public AgentCard customize(final AgentCard card) {
                        return card.withSkills(
                                List.of(AgentSkill.of("secret", "Secret", "For members.", List.of("x"))));
                    }

                    @Override
                    public boolean extended() {
                        return true;
                    }
                }),
                executor());

        assertEquals(1, agent.service().extendedAgentCard(null, null).skills().size());
    }

    // --- the defaults -------------------------------------------------------------------------------------------

    @Test
    void withoutAnyConfigurationTheCapabilitiesAreOn() {
        // the container can build the bean before the configuration is resolved, so this must not disable anything
        final var service = new A2AService(null, null, null, null);

        // both capability guards run first, so reaching the state - there is none - proves neither refused the call
        assertThrows(
                NullPointerException.class,
                () -> service.sendStreamingMessage(SendMessageRequest.of(Message.user("hi")), null, sink -> null),
                "streaming must not be refused");
        assertEquals(
                A2AErrors.INVALID_PARAMS,
                assertThrows(JsonRpcException.class, () -> service.createPushConfig(null, "t-1"))
                        .code(),
                "push notifications must not be refused");
    }

    // --- fixtures -----------------------------------------------------------------------------------------------

    /**
     * The agent these tests drive: the text picks the behaviour, so a case needing a task which is still running - to
     * cancel it, to subscribe to it, to hang a webhook on it - only has to say {@code ask}.
     */
    private static A2AExecutor executor() {
        return (request, updater) -> {
            if (request.continuation()) {
                updater.complete(Artifact.text("answer", request.text()));
            } else if (request.text().startsWith("ask")) {
                updater.inputRequired(Message.agent("which city?"));
            } else {
                updater.complete(Artifact.text("echo", request.text()));
            }
            return completedFuture(null);
        };
    }

    private static A2AAgentCard withExtension(final AgentExtension extension) {
        return card -> {
            final var capabilities = card.capabilities();
            return new AgentCard(
                    card.name(),
                    card.description(),
                    card.supportedInterfaces(),
                    card.provider(),
                    card.version(),
                    card.documentationUrl(),
                    new AgentCapabilities(
                            capabilities.streaming(), capabilities.pushNotifications(),
                            List.of(extension), capabilities.extendedAgentCard()),
                    card.securitySchemes(),
                    card.securityRequirements(),
                    card.defaultInputModes(),
                    card.defaultOutputModes(),
                    card.skills(),
                    card.signatures(),
                    card.iconUrl());
        };
    }

    private static SendMessageRequest accepting(final String outputMode) {
        return new SendMessageRequest(
                null, Message.user("hi"), new SendMessageConfiguration(List.of(outputMode), null, null, false), null);
    }

    private static SendMessageRequest withHistoryLength(final Integer historyLength) {
        return new SendMessageRequest(
                null, Message.user("hi"), new SendMessageConfiguration(null, null, historyLength, false), null);
    }

    private static String cursor(final int offset) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(String.valueOf(offset).getBytes(UTF_8));
    }
}
