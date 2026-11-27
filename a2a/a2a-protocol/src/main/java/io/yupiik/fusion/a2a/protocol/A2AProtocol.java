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

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * The A2A protocol constants: version, paths, service parameters and method names.
 */
public final class A2AProtocol {
    /**
     * The protocol version this implementation serves, {@code Major.Minor} as the specification requires - patch
     * numbers must never be used on the wire.
     */
    public static final String VERSION = "1.0";

    /**
     * The version an absent - or blank - {@code A2A-Version} means, as section 3.6.2 of the specification states:
     * "Agents MUST interpret empty value as 0.3 version".
     * <p>
     * This server does not serve {@code 0.3} - it is a different wire format, {@code message/send} instead of
     * {@code SendMessage} and a different agent card - so a request carrying no version is answered with
     * {@code VersionNotSupportedError} listing {@link #SUPPORTED_VERSIONS}. A {@code 0.3} client learns what is wrong
     * instead of getting a puzzling {@code MethodNotFound}, and section 3.6.1 makes sending the header a client
     * obligation anyway.
     */
    public static final String ASSUMED_VERSION = "0.3";

    /**
     * The versions this server serves, i.e. the ones {@code A2A-Version} may carry.
     */
    public static final List<String> SUPPORTED_VERSIONS = List.of(VERSION);

    /**
     * Where the JSON-RPC binding is served, it is also where the plain Fusion JSON-RPC endpoint is bound by default -
     * configured by the server hosting the protocol.
     */
    public static final String JSONRPC_PATH = "/a2a";

    /**
     * The prefix of the HTTP+JSON binding, the specification paths are relative to it: {@code /message:send},
     * {@code /tasks/{id}}, ...
     */
    public static final String REST_PATH = "/a2a/v1";

    /**
     * Where the agent card is published, as section 8.2 of the specification requires.
     */
    public static final String AGENT_CARD_PATH = "/.well-known/agent-card.json";

    /**
     * The media type the specification registers for the A2A payloads. {@code application/json} is accepted too.
     */
    public static final String MEDIA_TYPE = "application/a2a+json";

    /**
     * Service parameter carrying the protocol version the client speaks.
     */
    public static final String VERSION_HEADER = "a2a-version";

    /**
     * Service parameter carrying the comma separated extension URIs the client activates.
     */
    public static final String EXTENSIONS_HEADER = "a2a-extensions";

    /**
     * Header echoing {@link io.yupiik.fusion.a2a.model.TaskPushNotificationConfig#token()} on a webhook call so the
     * receiver can check the notification is the one it registered.
     */
    public static final String NOTIFICATION_TOKEN_HEADER = "X-A2A-Notification-Token";

    public static final String SEND_MESSAGE = "SendMessage";
    public static final String SEND_STREAMING_MESSAGE = "SendStreamingMessage";
    public static final String GET_TASK = "GetTask";
    public static final String LIST_TASKS = "ListTasks";
    public static final String CANCEL_TASK = "CancelTask";
    public static final String SUBSCRIBE_TO_TASK = "SubscribeToTask";
    public static final String CREATE_PUSH_CONFIG = "CreateTaskPushNotificationConfig";
    public static final String GET_PUSH_CONFIG = "GetTaskPushNotificationConfig";
    public static final String LIST_PUSH_CONFIGS = "ListTaskPushNotificationConfigs";
    public static final String DELETE_PUSH_CONFIG = "DeleteTaskPushNotificationConfig";
    public static final String GET_EXTENDED_AGENT_CARD = "GetExtendedAgentCard";

    /**
     * The methods answering a {@code text/event-stream} instead of a JSON body, they cannot be batched.
     */
    public static final Set<String> STREAMING_METHODS = Set.of(SEND_STREAMING_MESSAGE, SUBSCRIBE_TO_TASK);

    /**
     * Request attribute the transport fills in with the extension URIs the client declared in
     * {@link #EXTENSIONS_HEADER}, as a {@code List<String>}.
     */
    public static final String EXTENSIONS_ATTRIBUTE = "io.yupiik.fusion.a2a.extensions";

    /**
     * Request attribute the transport fills in with the identifier of the JSON-RPC request being processed, so a
     * stream created by a {@code @JsonRpc} method can frame its events with it.
     */
    public static final String REQUEST_ID_ATTRIBUTE = "io.yupiik.fusion.a2a.requestId";

    /**
     * Request attribute a {@code @JsonRpc} method sets with the stream it opened, which tells the transport
     * to answer a {@code text/event-stream} instead of a JSON body.
     */
    public static final String STREAM_ATTRIBUTE = "io.yupiik.fusion.a2a.stream";

    private A2AProtocol() {
        // no-op
    }

    /**
     * @param version a raw {@code A2A-Version} value, it can be {@code null}.
     * @return {@code true} when this server serves it. An absent or blank value resolves to {@link #ASSUMED_VERSION},
     * which is <b>not</b> supported, so a request carrying no version is rejected.
     */
    public static boolean isSupported(final String version) {
        return SUPPORTED_VERSIONS.contains(resolve(version));
    }

    /**
     * Unwraps a failure carried by a {@code CompletionStage}.
     * <p>
     * What a stage hands to a callback is the envelope, not the failure: reporting it as is would tell a caller a
     * {@code CompletionException} occurred and nothing about why.
     *
     * @param error the failure a stage reported.
     * @return what actually failed: the cause when {@code error} is only an envelope around it, else {@code error}
     * itself - a wrapper with no cause says nothing, and an exception which merely <em>has</em> a cause is the failure.
     */
    public static Throwable unwrap(final Throwable error) {
        return (error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null
                ? error.getCause()
                : error;
    }

    /**
     * @param method the {@code method} member of a JSON-RPC request, it can be {@code null} - a request is not
     *               guaranteed to carry one, and {@link #STREAMING_METHODS} is an immutable set which refuses a
     *               {@code null} lookup.
     * @return {@code true} when {@code method} answers a {@code text/event-stream} instead of a JSON body.
     */
    public static boolean isStreaming(final Object method) {
        return method != null && STREAMING_METHODS.contains(method);
    }

    /**
     * @param version a raw {@code A2A-Version} value, it can be {@code null}.
     * @return the version the request actually asks for: {@link #ASSUMED_VERSION} when it carries none, as section
     * 3.6.2 requires. This is what an error must report - saying {@code 0.3} is unsupported is the informative part for
     * a caller which sent nothing.
     */
    public static String resolve(final String version) {
        return version == null || version.isBlank() ? ASSUMED_VERSION : version.strip();
    }
}
