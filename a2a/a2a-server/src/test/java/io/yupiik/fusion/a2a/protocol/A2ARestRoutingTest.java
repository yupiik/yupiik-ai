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
package io.yupiik.fusion.a2a.protocol;

import static io.yupiik.fusion.a2a.test.Awaits.await;
import static io.yupiik.fusion.a2a.test.Bodies.contains;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.a2a.api.A2AExecutor;
import io.yupiik.fusion.a2a.configuration.A2AConfiguration;
import io.yupiik.fusion.a2a.model.Artifact;
import io.yupiik.fusion.a2a.model.ListTasksResponse;
import io.yupiik.fusion.a2a.model.Message;
import io.yupiik.fusion.a2a.model.SendMessageRequest;
import io.yupiik.fusion.a2a.model.SendMessageResponse;
import io.yupiik.fusion.a2a.model.Task;
import io.yupiik.fusion.a2a.model.TaskState;
import io.yupiik.fusion.a2a.service.A2AService;
import io.yupiik.fusion.a2a.test.Agents;
import io.yupiik.fusion.a2a.test.Configurations;
import io.yupiik.fusion.a2a.test.FakeRequest;
import io.yupiik.fusion.a2a.test.Responses;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.FusionModule;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.http.server.api.Response;
import io.yupiik.fusion.http.server.spi.Endpoint;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jsonrpc.JsonRpcException;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;

/**
 * The routing and the validation of the HTTP+JSON binding, driven without a server.
 * <p>
 * {@code A2ARestProtocolTest} covers the operations over real HTTP. What only this level reaches is the requests a
 * client library will not send - a {@code Content-Length} lying about the body, a method on the wrong route - and the
 * {@code fusion.a2a.restBinding=false} configuration, which the module cannot have a second container for.
 */
@FusionSupport
class A2ARestRoutingTest {
    // --- the binding itself -------------------------------------------------------------------------------------

    @Test
    void theBindingCanBeTurnedOff(@Fusion final JsonMapper jsons) {
        final var binding =
                binding(jsons, Configurations.configuration().restBinding(false).build());

        final var response = await(handle(binding, jsons, get("/tasks")));

        assertEquals(404, response.status());
        contains(Responses.body(response), "\"status\":\"NOT_FOUND\"", "Unknown A2A route");
    }

    @Test
    void withoutAnyConfigurationTheBindingIsServed(@Fusion final JsonMapper jsons) {
        // the container can build the bean before the configuration is resolved, so this must not disable the binding
        final var binding = new A2ARestProtocol(null, jsons, null);

        // there is no service behind it, so reaching a failure - rather than a 404 - proves the binding was served
        final var response = await(handle(binding, jsons, get("/tasks")));
        assertEquals(500, response.status(), () -> Responses.body(response));
    }

    @Test
    void theVersionIsEnforcedBeforeAnythingElse(@Fusion final JsonMapper jsons) {
        final var binding = binding(jsons, Configurations.defaults());

        final var response = await(handle(binding, jsons, FakeRequest.of("GET", "/a2a/v1/tasks")));

        assertEquals(400, response.status());
        contains(Responses.body(response), "\"reason\":\"VERSION_NOT_SUPPORTED\"", "0.3");
    }

    @Test
    void theVersionCanComeFromTheQueryString(@Fusion final JsonMapper jsons) {
        final var binding = binding(jsons, Configurations.defaults());

        final var response = await(
                handle(binding, jsons, FakeRequest.of("GET", "/a2a/v1/tasks").query("A2A-Version=1.0")));

        assertEquals(200, response.status());
    }

    @Test
    void theActivatedExtensionsAreExposedToTheExecutors(@Fusion final JsonMapper jsons) {
        final var seen = new CopyOnWriteArrayList<String>();
        final var binding = binding(jsons, Configurations.defaults(), (request, updater) -> {
            seen.addAll(request.activatedExtensions());
            updater.complete(Artifact.text("echo", request.text()));
            return completedFuture(null);
        });

        await(handle(
                binding,
                jsons,
                post("/message:send", jsons.toString(SendMessageRequest.of(Message.user("hi"))))
                        .header("A2A-Extensions", " https://example.com/a , , https://example.com/b ")));

        assertEquals(List.of("https://example.com/a", "https://example.com/b"), seen);
    }

    // --- the routes ---------------------------------------------------------------------------------------------

    @Test
    void unknownRoutes(@Fusion final JsonMapper jsons) {
        final var binding = binding(jsons, Configurations.defaults());

        for (final var path : List.of(
                "/",
                "/nope",
                "/message",
                "/message:unknown",
                "/tasks/",
                "/task/t-1",
                "/tasks/t-1/unknown",
                "/tasks/t-1/pushNotificationConfigs/",
                "/tasks/t-1/pushNotificationConfigs/c-1/more",
                "/tasks/t-1:cancel/more",
                // a colon in first position is not an action, so this is a task whose identifier starts with one
                "/tasks/:cancel")) {
            assertEquals(
                    404,
                    await(handle(binding, jsons, get(path))).status(),
                    () -> "GET " + path + " should not be a route");
        }
    }

    @Test
    void theRootOfTheBindingIsNotARoute(@Fusion final JsonMapper jsons) {
        final var binding = binding(jsons, Configurations.defaults());

        // the prefix itself, with nothing after it, reads as "/"
        assertEquals(
                404,
                await(handle(binding, jsons, FakeRequest.of("GET", "/a2a/v1").header("a2a-version", "1.0")))
                        .status());
    }

    @Test
    void methodsWhichDoNotMatchTheirRoute(@Fusion final JsonMapper jsons) {
        final var binding = binding(jsons, Configurations.defaults(), echo());
        final var task = task(binding, jsons, "ask");
        final var configs = "/tasks/" + task.id() + "/pushNotificationConfigs";

        for (final var call : List.of(
                List.of("GET", "/message:send"),
                List.of("GET", "/message:stream"),
                List.of("POST", "/tasks"),
                List.of("POST", "/tasks/" + task.id()),
                List.of("GET", "/tasks/" + task.id() + ":cancel"),
                List.of("GET", "/tasks/" + task.id() + ":subscribe"),
                List.of("PUT", configs),
                List.of("PUT", configs + "/c-1"),
                List.of("POST", "/extendedAgentCard"))) {
            final var response = await(handle(binding, jsons, request(call.get(0), call.get(1))));
            assertEquals(404, response.status(), () -> call + " should not be a route");
        }
    }

    @Test
    void anActionWhichIsNotOneOfTheTwo(@Fusion final JsonMapper jsons) {
        final var binding = binding(jsons, Configurations.defaults(), echo());
        final var task = task(binding, jsons, "ask");

        // cancel and subscribe are the only ones, anything else is not a route whatever the method
        assertEquals(
                404,
                await(handle(binding, jsons, request("POST", "/tasks/" + task.id() + ":restart")))
                        .status());
        assertEquals(
                404,
                await(handle(binding, jsons, request("POST", "/tasks/" + task.id() + ":")))
                        .status());
    }

    @Test
    void aSubResourceWhichIsNotTheWebhooks(@Fusion final JsonMapper jsons) {
        final var binding = binding(jsons, Configurations.defaults());

        // two and three segment forms both have to name the only sub resource there is
        assertEquals(404, await(handle(binding, jsons, get("/tasks/t-1/other"))).status());
        assertEquals(
                404, await(handle(binding, jsons, get("/tasks/t-1/other/c-1"))).status());
    }

    @Test
    void aBodyWhichReadsAsNothing(@Fusion final JsonMapper jsons) {
        final var binding = binding(jsons, Configurations.defaults(), echo());

        // the length announced a payload and the container handed over nothing: where the body carries the parameters
        // that is an invalid call
        final var required =
                await(handle(binding, jsons, request("POST", "/message:send").bodyReadingAsNull()));
        assertEquals(400, required.status());
        contains(Responses.body(required), "\"field\":\"body\"");

        // and where it is optional the defaults apply, exactly as for an absent one
        final var task = task(binding, jsons, "ask");
        final var optional = await(handle(
                binding,
                jsons,
                request("POST", "/tasks/" + task.id() + ":cancel").bodyReadingAsNull()));
        assertEquals(200, optional.status());
        contains(Responses.body(optional), "TASK_STATE_CANCELED");
    }

    @Test
    void theRouteSegmentsAreUrlDecoded(@Fusion final JsonMapper jsons) {
        final var binding = binding(jsons, Configurations.defaults());

        // a task identifier is opaque, so a client is free to use one needing encoding
        final var response = await(handle(binding, jsons, get("/tasks/a%2Fb")));
        assertEquals(404, response.status());
        contains(Responses.body(response), "\"taskId\":\"a/b\"");
    }

    @Test
    void theExtendedAgentCardRoute(@Fusion final JsonMapper jsons) {
        final var binding = binding(jsons, Configurations.defaults());

        final var response = await(handle(binding, jsons, get("/extendedAgentCard")));

        // the capability is off by default, and the route has to say so rather than answer a 404
        assertEquals(400, response.status());
        contains(Responses.body(response), "\"reason\":\"UNSUPPORTED_OPERATION\"");
    }

    // --- bodies -------------------------------------------------------------------------------------------------

    @Test
    void aBodyIsRequiredWhereItCarriesTheParameters(@Fusion final JsonMapper jsons) {
        final var binding = binding(jsons, Configurations.defaults());

        for (final var request : List.of(
                request("POST", "/message:send"), // no Content-Length at all
                post("/message:send", ""),
                post("/message:send", "   "))) {
            final var response = await(handle(binding, jsons, request));
            assertEquals(400, response.status());
            contains(Responses.body(response), "\"field\":\"body\"", "A JSON body is required");
        }
    }

    @Test
    void anInvalidBodyIsAParseError(@Fusion final JsonMapper jsons) {
        final var binding = binding(jsons, Configurations.defaults());

        final var response = await(handle(binding, jsons, post("/message:send", "{ not json")));

        assertEquals(400, response.status());
        contains(Responses.body(response), "Invalid JSON payload");
    }

    @Test
    void aChunkedBodyIsRead(@Fusion final JsonMapper jsons) {
        final var binding = binding(jsons, Configurations.defaults(), echo());
        // a chunked request carries no Content-Length, the transfer encoding is what says there is a body
        final var request = post("/message:send", jsons.toString(SendMessageRequest.of(Message.user("hi"))))
                .header("transfer-encoding", "chunked");

        assertEquals(200, await(handle(binding, jsons, request)).status());

        // and a blank one announces nothing, so the length is what decides
        final var blank = post("/message:send", jsons.toString(SendMessageRequest.of(Message.user("hi"))))
                .header("transfer-encoding", "  ");
        assertEquals(200, await(handle(binding, jsons, blank)).status());
    }

    @Test
    void aCancelBodyIsOptional(@Fusion final JsonMapper jsons) {
        final var binding = binding(jsons, Configurations.defaults(), echo());

        for (final var body : List.of("", "   ")) {
            final var task = task(binding, jsons, "ask");
            final var response = await(handle(binding, jsons, post("/tasks/" + task.id() + ":cancel", body)));
            assertEquals(200, response.status(), () -> "A body of '" + body + "' should be tolerated");
            contains(Responses.body(response), "TASK_STATE_CANCELED");
        }

        // and so is having no Content-Length at all
        final var task = task(binding, jsons, "ask");
        assertEquals(
                200,
                await(handle(binding, jsons, request("POST", "/tasks/" + task.id() + ":cancel")))
                        .status());
    }

    @Test
    void aContentLengthWhichIsNotANumberMeansNoBody(@Fusion final JsonMapper jsons) {
        final var binding = binding(jsons, Configurations.defaults(), echo());

        // reading the body of a request which has none never completes, so a header this broken has to read as "none"
        for (final var length : List.of("not-a-number", "0", "  ")) {
            final var task = task(binding, jsons, "ask");
            final var response = await(handle(
                    binding,
                    jsons,
                    request("POST", "/tasks/" + task.id() + ":cancel").header("content-length", length)));
            assertEquals(200, response.status(), () -> "Content-Length: " + length);
        }
    }

    @Test
    void theTenantOfACancelBodyIsHonored(@Fusion final JsonMapper jsons) {
        final var binding = binding(jsons, Configurations.defaults(), echo());

        // this agent declares no tenant so any value goes through, what is asserted is that the body is read at all
        for (final var tenant : List.of("\"blue\"", "\"  \"", "null")) {
            final var task = task(binding, jsons, "ask");
            final var response = await(
                    handle(binding, jsons, post("/tasks/" + task.id() + ":cancel", "{\"tenant\": " + tenant + "}")));
            assertEquals(200, response.status(), () -> "tenant " + tenant);
        }
    }

    // --- query parameters ---------------------------------------------------------------------------------------

    @Test
    void numericParametersAreValidated(@Fusion final JsonMapper jsons) {
        final var binding = binding(jsons, Configurations.defaults());

        for (final var name : List.of("pageSize", "historyLength")) {
            final var response = await(handle(binding, jsons, get("/tasks").parameter(name, "many")));
            assertEquals(400, response.status());
            contains(Responses.body(response), "\"field\":\"" + name + '"', "It must be a number");
        }

        // an absent - or blank - value is simply not a filter
        assertEquals(
                200,
                await(handle(binding, jsons, get("/tasks").parameter("pageSize", "  ")))
                        .status());
        assertEquals(
                200,
                await(handle(binding, jsons, get("/tasks").parameter("pageSize", " 2 ")))
                        .status());
    }

    @Test
    void theStatusFilterMustBeATaskStateName(@Fusion final JsonMapper jsons) {
        final var binding = binding(jsons, Configurations.defaults());

        final var response = await(handle(binding, jsons, get("/tasks").parameter("status", "RUNNING")));
        assertEquals(400, response.status());
        contains(Responses.body(response), "\"field\":\"status\"", "TASK_STATE_*");

        assertEquals(
                200,
                await(handle(binding, jsons, get("/tasks").parameter("status", " TASK_STATE_WORKING ")))
                        .status());
        assertEquals(
                200,
                await(handle(binding, jsons, get("/tasks").parameter("status", "  ")))
                        .status());
    }

    @Test
    void booleanParameters(@Fusion final JsonMapper jsons) {
        final var binding = binding(jsons, Configurations.defaults(), echo());
        task(binding, jsons, "hi");

        contains(
                Responses.body(await(handle(binding, jsons, get("/tasks").parameter("includeArtifacts", " true ")))),
                "\"artifacts\"");
        assertTrue(!Responses.body(await(handle(binding, jsons, get("/tasks").parameter("includeArtifacts", "false"))))
                .contains("\"artifacts\""));
        // anything which is not "true" is false, and a blank value is not a preference at all
        assertTrue(!Responses.body(await(handle(binding, jsons, get("/tasks").parameter("includeArtifacts", "  "))))
                .contains("\"artifacts\""));
    }

    // --- failures -----------------------------------------------------------------------------------------------

    @Test
    void aSynchronousFailureIsAnInternalError(@Fusion final JsonMapper jsons) {
        final var binding = new A2ARestProtocol(
                new A2AService(null, null, null, null) {
                    @Override
                    public ListTasksResponse listTasks(
                            final String tenant,
                            final String contextId,
                            final TaskState status,
                            final Integer pageSize,
                            final String pageToken,
                            final Integer historyLength,
                            final String statusTimestampAfter,
                            final Boolean includeArtifacts) {
                        throw new IllegalStateException("the store is gone");
                    }
                },
                jsons,
                Configurations.defaults());

        final var response = await(handle(binding, jsons, get("/tasks")));

        assertEquals(500, response.status());
        contains(Responses.body(response), "\"status\":\"INTERNAL\"", "the store is gone");
    }

    @Test
    void anAsynchronousFailureIsAnInternalErrorToo(@Fusion final JsonMapper jsons) {
        final var binding = new A2ARestProtocol(
                new A2AService(null, null, null, null) {
                    @Override
                    public CompletionStage<SendMessageResponse> sendMessage(
                            final SendMessageRequest request, final Request http) {
                        return CompletableFuture.failedFuture(new IllegalStateException("the model is unreachable"));
                    }
                },
                jsons,
                Configurations.defaults());

        final var response = await(handle(
                binding, jsons, post("/message:send", jsons.toString(SendMessageRequest.of(Message.user("hi"))))));

        assertEquals(500, response.status());
        contains(Responses.body(response), "\"status\":\"INTERNAL\"", "the model is unreachable");
    }

    @Test
    void anAsynchronousA2AErrorKeepsItsStatus(@Fusion final JsonMapper jsons) {
        final var binding = binding(jsons, Configurations.defaults(), (request, updater) -> {
            throw A2AErrors.invalidParams("metadata.skillId", "Unknown skill");
        });

        final var response = await(handle(
                binding, jsons, post("/message:send", jsons.toString(SendMessageRequest.of(Message.user("hi"))))));

        assertEquals(400, response.status());
        contains(Responses.body(response), "\"status\":\"INVALID_ARGUMENT\"", "\"field\":\"metadata.skillId\"");
    }

    @Test
    void anErrorWithoutStructuredDetailsIsStillAGrpcStatus(@Fusion final JsonMapper jsons) {
        final var binding = new A2ARestProtocol(
                new A2AService(null, null, null, null) {
                    @Override
                    public ListTasksResponse listTasks(
                            final String tenant,
                            final String contextId,
                            final TaskState status,
                            final Integer pageSize,
                            final String pageToken,
                            final Integer historyLength,
                            final String statusTimestampAfter,
                            final Boolean includeArtifacts) {
                        // data which is not the array of details the specification describes must not end up in the
                        // payload
                        throw new JsonRpcException(A2AErrors.TASK_NOT_FOUND, "Task not found", "not a list", null);
                    }
                },
                jsons,
                Configurations.defaults());

        final var response = await(handle(binding, jsons, get("/tasks")));

        assertEquals(404, response.status());
        final var body = Responses.body(response);
        contains(body, "\"status\":\"NOT_FOUND\"", "\"message\":\"Task not found\"");
        assertTrue(!body.contains("not a list"), body);
    }

    // --- streaming ----------------------------------------------------------------------------------------------

    @Test
    void streamingRoutesAnswerAnEventStream(@Fusion final JsonMapper jsons) {
        final var binding = binding(jsons, Configurations.defaults(), echo());

        final var streamed = await(handle(
                binding, jsons, post("/message:stream", jsons.toString(SendMessageRequest.of(Message.user("hi"))))));
        assertEquals(200, streamed.status());
        assertEquals("text/event-stream;charset=utf-8", Responses.header(streamed, "content-type"));
        assertEquals("no-cache", Responses.header(streamed, "cache-control"));
        assertEquals("keep-alive", Responses.header(streamed, "connection"));

        final var task = task(binding, jsons, "ask");
        final var subscribed = await(handle(binding, jsons, post("/tasks/" + task.id() + ":subscribe", "")));
        assertEquals(200, subscribed.status());
        assertEquals("text/event-stream;charset=utf-8", Responses.header(subscribed, "content-type"));
    }

    @Test
    void aStreamWhichCannotBeOpenedAnswersTheError(@Fusion final JsonMapper jsons) {
        final var binding = binding(jsons, Configurations.defaults(), echo());

        final var response = await(handle(binding, jsons, post("/message:stream", "{}")));

        assertEquals(400, response.status());
        contains(Responses.body(response), "\"field\":\"message\"");
    }

    private static CompletionStage<Response> handle(
            final A2ARestProtocol binding, final JsonMapper jsons, final Request request) {
        try (final var container = ConfiguringContainer.of()
                        .register(
                                new ProvidedInstanceBean<>(DefaultScoped.class, A2ARestProtocol.class, () -> binding),
                                new ProvidedInstanceBean<>(
                                        DefaultScoped.class, A2AEndpoint.class, () -> new A2AEndpoint(null, jsons)),
                                new ProvidedInstanceBean<>(
                                        DefaultScoped.class,
                                        AgentCardEndpoint.class,
                                        () -> new AgentCardEndpoint(null, jsons, null)))
                        .register(new FusionModule() {
                            @Override
                            public BiPredicate<RuntimeContainer, FusionBean<?>> beanFilter() {
                                return (container, bean) -> bean.type() instanceof Class<?> type
                                        && Endpoint.class.isAssignableFrom(type)
                                        && bean.scope() == DefaultScoped.class;
                            }

                            @Override
                            public BiPredicate<RuntimeContainer, FusionListener<?>> listenerFilter() {
                                return (container, listener) -> false;
                            }
                        })
                        .start();
                final var endpoints = container.lookups(Endpoint.class)) {
            // Finish handling before releasing the endpoint instances and their container.
            return completedFuture(await(endpoints.instance().stream()
                    .filter(endpoint -> endpoint.matches(request))
                    .findFirst()
                    .map(endpoint -> endpoint.handle(request))
                    .orElseGet(() -> completedFuture(Response.of().status(404).build()))));
        }
    }

    // --- fixtures -----------------------------------------------------------------------------------------------

    private static A2ARestProtocol binding(
            final JsonMapper jsons, final A2AConfiguration configuration, final A2AExecutor... executors) {
        return new A2ARestProtocol(Agents.agent(configuration, executors).service(), jsons, configuration);
    }

    private static A2AExecutor echo() {
        return (request, updater) -> {
            if (request.text().startsWith("ask")) {
                updater.inputRequired(Message.agent("which city?"));
            } else {
                updater.complete(Artifact.text("echo", request.text()));
            }
            return completedFuture(null);
        };
    }

    /**
     * @return a task which is still running - so it can be canceled, subscribed to or given a webhook.
     */
    private static Task task(final A2ARestProtocol binding, final JsonMapper jsons, final String text) {
        final var response = await(handle(
                binding, jsons, post("/message:send", jsons.toString(SendMessageRequest.of(Message.user(text))))));
        assertEquals(200, response.status(), () -> Responses.body(response));
        final var task = jsons.fromString(SendMessageResponse.class, Responses.body(response))
                .task();
        assertNotNull(task);
        return task;
    }

    private static FakeRequest request(final String method, final String path) {
        return FakeRequest.of(method, "/a2a/v1" + path).header("a2a-version", "1.0");
    }

    private static FakeRequest get(final String path) {
        return request("GET", path);
    }

    private static FakeRequest post(final String path, final String body) {
        return request("POST", path).body(body);
    }
}
