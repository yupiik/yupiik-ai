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
package io.yupiik.fusion.a2a.client;

import static java.net.http.HttpResponse.BodyHandlers.fromLineSubscriber;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.yupiik.fusion.a2a.model.AgentCard;
import io.yupiik.fusion.a2a.model.AgentInterface;
import io.yupiik.fusion.a2a.model.ListTaskPushNotificationConfigsResponse;
import io.yupiik.fusion.a2a.model.ListTasksResponse;
import io.yupiik.fusion.a2a.model.Message;
import io.yupiik.fusion.a2a.model.SendMessageRequest;
import io.yupiik.fusion.a2a.model.SendMessageResponse;
import io.yupiik.fusion.a2a.model.StreamResponse;
import io.yupiik.fusion.a2a.model.Task;
import io.yupiik.fusion.a2a.model.TaskPushNotificationConfig;
import io.yupiik.fusion.a2a.model.TaskState;
import io.yupiik.fusion.a2a.protocol.A2AErrors;
import io.yupiik.fusion.a2a.protocol.A2AProtocol;
import io.yupiik.fusion.a2a.signature.CardVerifier;
import io.yupiik.fusion.json.JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PublicKey;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * An A2A client: what an agent uses to delegate to another agent, and what a test uses to drive this server.
 * <p>
 * Every call is asynchronous - nothing blocks, the streams are consumed event by event as the bytes arrive - and typed
 * on the wire model:
 * <pre>{@code
 * final var client = new A2AClient(URI.create("https://agent.example.com"), http, jsons);
 *
 * client.sendMessage(SendMessageRequest.of(Message.user("hello")))
 *       .thenAccept(response -> System.out.println(response.task().status().state()));
 *
 * client.sendStreamingMessage(
 *         SendMessageRequest.of(Message.user("count down from 3")),
 *         event -> System.out.println(event));
 * }</pre>
 * An agent answering an error fails the returned stage with an {@link A2AClientException} carrying the A2A code, so
 * {@code TaskNotFoundError} and friends can be told apart.
 * <p>
 * It speaks the JSON-RPC binding, i.e. the interface an agent card lists first. Use {@link #rest} and
 * {@link #restStreaming} to reach the HTTP+JSON one.
 * <p>
 * It holds no resource of its own - the {@link HttpClient} is provided and stays owned by the caller - so there is
 * nothing to close.
 */
public class A2AClient {
    private Duration requestTimeout = Duration.ofMinutes(2);
    private Duration streamTimeout = Duration.ofMinutes(10);

    /** Sets the finite request budget. The caller-owned HttpClient configures its connect timeout separately. */
    public A2AClient requestTimeout(final Duration timeout) {
        this.requestTimeout = positive(timeout);
        return this;
    }

    /** Sets the maximum stream lifetime, including consumption after response headers. */
    public A2AClient streamTimeout(final Duration timeout) {
        this.streamTimeout = positive(timeout);
        return this;
    }

    private static Duration positive(final Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("A positive finite timeout is required");
        }
        timeout.toNanos(); // reject overflow before starting a request
        return timeout;
    }

    private final URI base;
    private final HttpClient http;
    private final JsonMapper jsons;
    private final AtomicLong ids = new AtomicLong();

    private String tenant;
    private String version = A2AProtocol.VERSION;
    private String extensions;
    private CardVerifier verifier;
    private Function<String, PublicKey> keys;

    /**
     * @param base the agent root, {@code https://agent.example.com} for example - the binding paths are appended to it.
     * @param http the client to use, it is <b>not</b> closed by this class.
     * @param jsons the mapper to read and write the A2A model with.
     */
    public A2AClient(final URI base, final HttpClient http, final JsonMapper jsons) {
        this.base = URI.create(trimTrailingSlash(base.toString()));
        this.http = http;
        this.jsons = jsons;
    }

    /**
     * Builds a client from a card, i.e. after discovery: the first {@code JSONRPC} interface is selected and its
     * {@code tenant} - when it declares one - is echoed in every request, as section 8.3.2 requires.
     *
     * @param card  the card of the agent to reach.
     * @param http  the client to use.
     * @param jsons the mapper to read and write the A2A model with.
     * @return the client.
     * @throws IllegalArgumentException when the card declares no JSON-RPC interface.
     */
    public static A2AClient of(final AgentCard card, final HttpClient http, final JsonMapper jsons) {
        final var selected = card.supportedInterfaces() == null
                ? null
                : card.supportedInterfaces().stream()
                        .filter(it -> AgentInterface.JSONRPC.equals(it.protocolBinding()))
                        .findFirst()
                        .orElse(null);
        if (selected == null) {
            throw new IllegalArgumentException("Agent '" + card.name() + "' does not expose a JSONRPC interface");
        }
        final var url = selected.url();
        final var root = url.endsWith(A2AProtocol.JSONRPC_PATH)
                ? url.substring(0, url.length() - A2AProtocol.JSONRPC_PATH.length())
                : url;
        final var client = new A2AClient(URI.create(root.isEmpty() ? "/" : root), http, jsons);
        client.tenant = selected.tenant();
        if (selected.protocolVersion() != null) {
            client.version = selected.protocolVersion();
        }
        return client;
    }

    /**
     * @param tenant the routing identifier to send, it must be the one of the selected agent card interface.
     * @return this.
     */
    public A2AClient tenant(final String tenant) {
        this.tenant = tenant;
        return this;
    }

    /**
     * @param version the value of the {@code A2A-Version} header, {@code null} to send none - which a conformant agent
     *                reads as {@code 0.3} and therefore rejects, see section 3.6.2. It is only useful to assert that
     *                behaviour.
     * @return this.
     */
    public A2AClient version(final String version) {
        this.version = version;
        return this;
    }

    /**
     * @param extensions the comma separated extension URIs to declare in the {@code A2A-Extensions} header.
     * @return this.
     */
    public A2AClient extensions(final String extensions) {
        this.extensions = extensions;
        return this;
    }

    /**
     * Verifies the signature of every card this client fetches, which section 8.4.3 asks a client to do before trusting
     * one. It is opt-in: without it a card is used as is.
     * <pre>{@code
     * client.verifier(CardVerifier.of(jsons), Map.of("key-1", publicKey)::get);
     * }</pre>
     *
     * @param verifier what verifies, see {@link CardVerifier#of(io.yupiik.fusion.json.JsonMapper)} for a client built by
     *                 hand.
     * @param keys     resolves the {@code kid} of a signature to the public key to trust, {@code null} for an unknown
     *                 one. Deciding which keys are trusted is the security decision, so it stays with the caller.
     * @return this.
     */
    public A2AClient verifier(final CardVerifier verifier, final Function<String, PublicKey> keys) {
        this.verifier = verifier;
        this.keys = keys;
        return this;
    }

    /**
     * @return the routing identifier this client echoes, {@code null} when it sends none.
     */
    public String tenant() {
        return tenant;
    }

    /**
     * Fetches the public agent card, i.e. {@code GET /.well-known/agent-card.json}. This is how discovery starts.
     */
    public CompletionStage<AgentCard> agentCard() {
        return http.sendAsync(
                        withServiceParameters(HttpRequest.newBuilder(base.resolve(A2AProtocol.AGENT_CARD_PATH))
                                        .GET())
                                .header("accept", "application/json")
                                .build(),
                        ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new A2AClientException(
                                A2AErrors.INTERNAL_ERROR,
                                "Can't fetch the agent card: HTTP " + response.statusCode(),
                                List.of());
                    }
                    return verified(jsons.fromString(AgentCard.class, response.body()));
                });
    }

    /**
     * {@code GetExtendedAgentCard}: the card an authenticated caller is entitled to.
     */
    public CompletionStage<AgentCard> extendedAgentCard() {
        return call(A2AProtocol.GET_EXTENDED_AGENT_CARD, params(), AgentCard.class)
                .thenApply(this::verified);
    }

    /**
     * @param card a fetched card.
     * @return {@code card} when it is trusted, i.e. when no verifier is configured or when every signature verifies.
     * @throws A2AClientException when a verifier is configured and the card does not satisfy it.
     */
    private AgentCard verified(final AgentCard card) {
        if (verifier == null || card == null) {
            return card;
        }
        if (!verifier.verify(card, keys)) {
            throw new A2AClientException(
                    A2AErrors.INTERNAL_ERROR,
                    "The agent card of '" + card.name() + "' carries no signature this client trusts",
                    List.of());
        }
        return card;
    }

    /**
     * {@code SendMessage}: sends a message and waits for the answer, which is either the task tracking the work or a
     * direct message.
     */
    public CompletionStage<SendMessageResponse> sendMessage(final SendMessageRequest request) {
        return call(A2AProtocol.SEND_MESSAGE, sendMessageParams(request), SendMessageResponse.class);
    }

    /**
     * Convenience for the most common call: a text message starting a new task.
     */
    public CompletionStage<SendMessageResponse> sendMessage(final String text) {
        return sendMessage(SendMessageRequest.of(Message.user(text)));
    }

    /**
     * {@code SendStreamingMessage}: sends a message and consumes the events as they are produced.
     *
     * @param request the message and how to answer it.
     * @param onEvent called for every event, on the HTTP client threads, in the order the agent produced them.
     * @return a stage completing when the agent closes the stream, i.e. when the task settled.
     */
    public CompletionStage<Void> sendStreamingMessage(
            final SendMessageRequest request, final Consumer<StreamResponse> onEvent) {
        return stream(jsonRpcRequest(A2AProtocol.SEND_STREAMING_MESSAGE, sendMessageParams(request)), onEvent);
    }

    /**
     * {@code SubscribeToTask}: consumes the events of a task which is already running.
     *
     * @param id      the task to watch.
     * @param onEvent called for every event, the first one being the current state of the task.
     * @return a stage completing when the agent closes the stream.
     */
    public CompletionStage<Void> subscribeToTask(final String id, final Consumer<StreamResponse> onEvent) {
        return stream(jsonRpcRequest(A2AProtocol.SUBSCRIBE_TO_TASK, params("id", id)), onEvent);
    }

    /**
     * {@code GetTask}: the current state of a task, i.e. how to poll.
     *
     * @param id            the task identifier.
     * @param historyLength how many of the most recent messages to get, {@code null} for all of them and {@code 0} for
     *                      none.
     */
    public CompletionStage<Task> getTask(final String id, final Integer historyLength) {
        return call(A2AProtocol.GET_TASK, params("id", id, "historyLength", historyLength), Task.class);
    }

    /**
     * {@code CancelTask}: asks the agent to stop working on a task.
     */
    public CompletionStage<Task> cancelTask(final String id) {
        return call(A2AProtocol.CANCEL_TASK, params("id", id), Task.class);
    }

    /**
     * {@code ListTasks}: the tasks matching a filter, most recently updated first.
     *
     * @param contextId only the tasks of that conversation, {@code null} for all of them.
     * @param status    only the tasks in that state, {@code null} for all of them.
     * @param pageSize  how many to get, {@code null} for the agent default.
     * @param pageToken the cursor of a previous call, {@code null} for the first page.
     */
    public CompletionStage<ListTasksResponse> listTasks(
            final String contextId, final TaskState status, final Integer pageSize, final String pageToken) {
        return call(
                A2AProtocol.LIST_TASKS,
                params(
                        "contextId",
                        contextId,
                        "status",
                        status == null ? null : status.name(),
                        "pageSize",
                        pageSize,
                        "pageToken",
                        pageToken),
                ListTasksResponse.class);
    }

    /**
     * {@code CreateTaskPushNotificationConfig}: registers a webhook on a task.
     */
    public CompletionStage<TaskPushNotificationConfig> createTaskPushNotificationConfig(
            final TaskPushNotificationConfig config) {
        final var params =
                params("id", config.id(), "taskId", config.taskId(), "url", config.url(), "token", config.token());
        if (config.authentication() != null) {
            params.put("authentication", config.authentication());
        }
        return call(A2AProtocol.CREATE_PUSH_CONFIG, params, TaskPushNotificationConfig.class);
    }

    /**
     * {@code GetTaskPushNotificationConfig}.
     */
    public CompletionStage<TaskPushNotificationConfig> getTaskPushNotificationConfig(
            final String taskId, final String id) {
        return call(A2AProtocol.GET_PUSH_CONFIG, params("taskId", taskId, "id", id), TaskPushNotificationConfig.class);
    }

    /**
     * {@code ListTaskPushNotificationConfigs}.
     */
    public CompletionStage<ListTaskPushNotificationConfigsResponse> listTaskPushNotificationConfigs(
            final String taskId) {
        return call(
                A2AProtocol.LIST_PUSH_CONFIGS, params("taskId", taskId), ListTaskPushNotificationConfigsResponse.class);
    }

    /**
     * {@code DeleteTaskPushNotificationConfig}, it is idempotent.
     */
    public CompletionStage<Void> deleteTaskPushNotificationConfig(final String taskId, final String id) {
        return call(A2AProtocol.DELETE_PUSH_CONFIG, params("taskId", taskId, "id", id), Object.class)
                .thenApply(ignored -> null);
    }

    /**
     * Calls the HTTP+JSON binding, for the cases the typed JSON-RPC methods above do not cover.
     *
     * @param method the HTTP method.
     * @param path   the path relative to {@code /a2a/v1}, {@code /tasks/xxx} for example.
     * @param body   the payload to serialize, {@code null} for the methods without one.
     * @return the raw response, its status and body are the ones the agent sent.
     */
    public CompletionStage<HttpResponse<String>> rest(final String method, final String path, final Object body) {
        final var builder = HttpRequest.newBuilder(base.resolve(A2AProtocol.REST_PATH + path))
                .method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(jsons.toString(body), UTF_8))
                .header("accept", A2AProtocol.MEDIA_TYPE + ", application/json");
        if (body != null) {
            builder.header("content-type", A2AProtocol.MEDIA_TYPE);
        }
        return http.sendAsync(withServiceParameters(builder).build(), ofString());
    }

    /**
     * Consumes a streaming route of the HTTP+JSON binding, i.e. {@code /message:stream} or
     * {@code /tasks/{id}:subscribe}.
     *
     * @param path    the path relative to {@code /a2a/v1}.
     * @param body    the payload to serialize, {@code null} for none.
     * @param onEvent called for every event.
     * @return a stage completing when the agent closes the stream.
     */
    public CompletionStage<Void> restStreaming(
            final String path, final Object body, final Consumer<StreamResponse> onEvent) {
        final var builder = HttpRequest.newBuilder(base.resolve(A2AProtocol.REST_PATH + path))
                .POST(
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(jsons.toString(body), UTF_8))
                .header("accept", "text/event-stream");
        if (body != null) {
            builder.header("content-type", A2AProtocol.MEDIA_TYPE);
        }
        return stream(withServiceParameters(builder).build(), onEvent);
    }

    /**
     * The raw JSON-RPC escape hatch: it sends {@code params} as is and answers the payload as is, which is what a test
     * asserting on an error envelope needs.
     *
     * @param method the A2A method.
     * @param params the JSON of the parameters.
     * @return the raw response.
     */
    public CompletionStage<HttpResponse<String>> raw(final String method, final String params) {
        return http.sendAsync(jsonRpcRequest(method, params), ofString());
    }

    /**
     * Sends a raw JSON-RPC payload, a batch for example.
     *
     * @param body the payload.
     * @return the raw response.
     */
    public CompletionStage<HttpResponse<String>> rawBody(final String body) {
        return http.sendAsync(
                withServiceParameters(HttpRequest.newBuilder(base.resolve(A2AProtocol.JSONRPC_PATH))
                                .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8))
                                .header("content-type", "application/json")
                                .header("accept", "application/json, text/event-stream"))
                        .build(),
                ofString());
    }

    private <T> CompletionStage<T> call(
            final String method, final Map<String, Object> params, final Class<T> resultType) {
        return http.sendAsync(jsonRpcRequest(method, jsons.toString(params)), ofString())
                .thenApply(response -> result(response.body(), resultType));
    }

    private CompletionStage<Void> stream(final HttpRequest request, final Consumer<StreamResponse> onEvent) {
        final var subscriber = new StreamSubscriber(jsons, onEvent);
        final var exchange = http.sendAsync(request, fromLineSubscriber(subscriber));
        final var bounded = exchange.copy().orTimeout(streamTimeout.toNanos(), TimeUnit.NANOSECONDS);
        bounded.whenComplete((response, failure) -> {
            if (failure != null) {
                exchange.cancel(true);
            }
        });
        final var result = bounded.thenAccept(response -> {
            if (response.statusCode() == 200) {
                return;
            }
            // the agent refused to open the stream and answered an error payload instead
            final var body = subscriber.prelude();
            if (!body.isBlank()) {
                try {
                    result(body, Object.class);
                } catch (final A2AClientException ace) {
                    throw ace;
                } catch (final RuntimeException re) {
                    // not a payload we can read, fall through to the generic message below
                }
            }
            throw new A2AClientException(
                    A2AErrors.INTERNAL_ERROR, "Can't open the stream: HTTP " + response.statusCode(), List.of());
        });
        result.whenComplete((response, failure) -> {
            if (failure != null) {
                // Cancelling a dependent CompletableFuture does not cancel its upstream exchange.
                bounded.cancel(true);
                exchange.cancel(true);
            }
        });
        return result;
    }

    private HttpRequest jsonRpcRequest(final String method, final Map<String, Object> params) {
        return jsonRpcRequest(method, jsons.toString(params));
    }

    private HttpRequest jsonRpcRequest(final String method, final String params) {
        return withServiceParameters(HttpRequest.newBuilder(base.resolve(A2AProtocol.JSONRPC_PATH))
                        .POST(HttpRequest.BodyPublishers.ofString(envelope(method, params), UTF_8))
                        .header("content-type", "application/json")
                        .header("accept", "application/json, text/event-stream"))
                .build();
    }

    private String envelope(final String method, final String params) {
        return """
                {"jsonrpc":"2.0","id":%d,"method":"%s","params":%s}""".formatted(ids.incrementAndGet(), method, params == null || params.isBlank() ? "{}" : params);
    }

    /**
     * Reads a JSON-RPC response: the payload is bound to a plain structure first so an error can be told from a result
     * without needing one response type per method.
     */
    private <T> T result(final String body, final Class<T> resultType) {
        final var payload = jsons.fromString(Object.class, body);
        if (!(payload instanceof Map<?, ?> map)) {
            throw new A2AClientException(A2AErrors.INTERNAL_ERROR, "Unexpected response: " + body, List.of());
        }
        if (map.get("error") instanceof Map<?, ?> error) {
            throw new A2AClientException(
                    error.get("code") instanceof Number code ? code.intValue() : A2AErrors.INTERNAL_ERROR,
                    error.get("message") == null ? "Unknown error" : String.valueOf(error.get("message")),
                    error.get("data") instanceof List<?> details ? List.<Object>copyOf(details) : List.of());
        }
        final var result = map.get("result");
        if (result == null) {
            return null;
        }
        if (resultType == Object.class) {
            return resultType.cast(result);
        }
        return jsons.fromString(resultType, jsons.toString(result));
    }

    private Map<String, Object> sendMessageParams(final SendMessageRequest request) {
        final var params = new LinkedHashMap<String, Object>();
        params.put("message", request.message());
        if (request.configuration() != null) {
            params.put("configuration", request.configuration());
        }
        if (request.metadata() != null) {
            params.put("metadata", request.metadata());
        }
        withTenant(params, request.tenant());
        return params;
    }

    /**
     * @param keyValues the parameter names and values, the {@code null} values are dropped so the agent sees an absent
     *                  attribute and not a null one.
     */
    private Map<String, Object> params(final Object... keyValues) {
        final var params = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                params.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        withTenant(params, null);
        return params;
    }

    private void withTenant(final Map<String, Object> params, final String explicit) {
        final var value = explicit != null && !explicit.isBlank() ? explicit : tenant;
        if (value != null && !value.isBlank()) {
            params.put("tenant", value);
        }
    }

    private HttpRequest.Builder withServiceParameters(final HttpRequest.Builder builder) {
        builder.timeout(requestTimeout);
        if (version != null) {
            builder.header(A2AProtocol.VERSION_HEADER, version);
        }
        if (extensions != null) {
            builder.header(A2AProtocol.EXTENSIONS_HEADER, extensions);
        }
        return builder;
    }

    private static String trimTrailingSlash(final String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
