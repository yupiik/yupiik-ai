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

import io.yupiik.fusion.a2a.api.A2AExecutor;
import io.yupiik.fusion.a2a.api.A2ASkill;
import io.yupiik.fusion.a2a.api.AgentRequest;
import io.yupiik.fusion.a2a.api.TaskUpdater;
import io.yupiik.fusion.a2a.model.Artifact;
import io.yupiik.fusion.a2a.model.Message;
import io.yupiik.fusion.a2a.model.Task;
import io.yupiik.fusion.a2a.protocol.A2AErrors;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.order.Order;
import io.yupiik.fusion.jsonrpc.JsonRpcException;
import io.yupiik.fusion.jsonrpc.JsonRpcHandler;
import io.yupiik.fusion.jsonrpc.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * The built-in {@link A2AExecutor}: it routes a message to a {@link A2ASkill} method and turns what the method returns
 * into a task outcome.
 * <p>
 * It is registered last ({@code @Order(10_000)}) so any application executor takes precedence, and it only accepts a
 * message it can route: one naming a skill in its {@code skillId} metadata, or any message when the agent declares
 * exactly one skill method.
 * <p>
 * The skill arguments are assembled from the message, most specific last, so a skill can be written against whichever
 * shape its callers use:
 * <ol>
 *     <li>{@code message} - the incoming {@link Message} itself - and {@code text} - its text parts joined,</li>
 *     <li>the entries of the first {@code data} part when it is an object,</li>
 *     <li>the entries of the {@code arguments} metadata when it is an object.</li>
 * </ol>
 * What the method returns is mapped as follows: a {@link Message} answers directly without producing any output, a
 * {@link Task} replaces the whole state, an {@link Artifact} - or a list of them - completes the task with those
 * outputs, {@code void} completes it with none and anything else is wrapped in a structured artifact.
 * <p>
 * NOTE: a skill reports one transition, at the end. Implement {@link A2AExecutor} to report progress while working.
 */
@Order(10_000)
@ApplicationScoped
public class SkillExecutor implements A2AExecutor {
    private final SkillService skills;
    private final JsonRpcHandler handler;

    public SkillExecutor(final SkillService skills, final JsonRpcHandler handler) {
        this.skills = skills;
        this.handler = handler;
    }

    @Override
    public boolean accept(final AgentRequest request) {
        final var requested = request.skillId();
        if (requested != null && !requested.isBlank()) {
            return true; // it explicitly asked for a skill, tell it when there is no such thing
        }
        // accept as soon as the agent has skills so the routing failure is reported precisely - which skill to name -
        // instead of the generic "no executor accepted the message"
        return !skills.methodBackedIds().isEmpty();
    }

    @Override
    public CompletionStage<Void> execute(final AgentRequest request, final TaskUpdater updater) {
        final var skill = resolve(request);
        // the JSON-RPC stack is asynchronous, so a skill returning a CompletionStage is awaited without holding a
        // thread - the outcome is mapped when it completes
        return handler.execute(jsonRpc(skill, arguments(request)), request.httpRequest())
                .thenAccept(result -> onResult(skill, result, updater));
    }

    private String resolve(final AgentRequest request) {
        final var requested = request.skillId();
        if (requested == null || requested.isBlank()) {
            final var single = skills.singleMethodBackedId();
            if (single == null) {
                throw A2AErrors.invalidParams(
                        "metadata.skillId",
                        "This agent exposes " + skills.methodBackedIds().size() + " skills, name the one to use");
            }
            return single;
        }
        if (!skills.isMethodBacked(requested)) {
            throw A2AErrors.invalidParams(
                    "metadata.skillId",
                    "Unknown skill '" + requested + "', known ones are " + skills.methodBackedIds());
        }
        return requested;
    }

    private Map<String, Object> arguments(final AgentRequest request) {
        final var arguments = new LinkedHashMap<String, Object>();
        arguments.put("message", request.message());
        arguments.put("text", request.text());
        putAll(arguments, request.data());
        if (request.message() != null && request.message().metadata() != null) {
            putAll(arguments, request.message().metadata().get(AgentRequest.ARGUMENTS));
        }
        if (request.metadata() != null) {
            putAll(arguments, request.metadata().get(AgentRequest.ARGUMENTS));
        }
        return arguments;
    }

    private void onResult(final String skill, final Object result, final TaskUpdater updater) {
        if (result == null) { // a void skill: it did its job and has nothing to say
            updater.complete();
            return;
        }
        if (!(result instanceof Response response)) { // unlikely, a bulk response for a single request
            throw A2AErrors.invalidAgentResponse("Unexpected result calling skill '" + skill + "'");
        }
        if (response.error() != null) {
            final var error = response.error();
            if (isProtocolError(error.code())) { // the call was wrong, this is not a skill failure
                throw new JsonRpcException(error.code(), error.message(), error.data(), null);
            }
            // the skill threw: the specification wants the caller to read why in the task status, not to get a
            // protocol error it cannot act on
            updater.fail(error.message());
            return;
        }
        switch (response.result()) {
            case null -> updater.complete();
            case Message message -> updater.respond(message);
            case Task task -> updater.adopt(task);
            case Artifact artifact -> updater.complete(artifact);
            case List<?> list
            when !list.isEmpty() && list.stream().allMatch(it -> it instanceof Artifact) ->
                updater.complete(list.stream().map(Artifact.class::cast).toArray(Artifact[]::new));
            case String text -> updater.complete(Artifact.text(skill, text));
            case Object value -> updater.complete(Artifact.data(skill, value));
        }
    }

    private static void putAll(final Map<String, Object> target, final Object candidate) {
        if (candidate instanceof Map<?, ?> map) {
            target.putAll(map.entrySet().stream()
                    .collect(
                            java.util.LinkedHashMap::new,
                            (result, entry) -> result.put(String.valueOf(entry.getKey()), entry.getValue()),
                            Map::putAll));
        }
    }

    /**
     * @param code a JSON-RPC error code.
     * @return {@code true} for the codes JSON-RPC reserves, i.e. the ones meaning the call itself was invalid - a
     * missing argument for example - and not that the skill failed.
     */
    private static boolean isProtocolError(final int code) {
        return code == -32700 || (code <= -32600 && code >= -32603);
    }

    private static Map<String, Object> jsonRpc(final String method, final Map<String, Object> params) {
        // no id: this is a nested invocation, the enclosing A2A request carries the client one
        return Map.of(
                "jsonrpc", "2.0",
                "method", method,
                "params", params);
    }
}
