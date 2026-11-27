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
package io.yupiik.fusion.a2a.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

/**
 * The A2A server configuration. All the keys are prefixed with {@code fusion.a2a.} and can be set with a system
 * property, an environment variable ({@code FUSION_A2A_...}) or any Fusion {@code ConfigurationSource}.
 * <p>
 * Everything which is structured - security schemes, extensions, signatures - is contributed with
 * {@link io.yupiik.fusion.a2a.api.A2AAgentCard} instead.
 *
 * @param name                 the agent name published in its card.
 * @param description          what the agent does, this is what another agent reads to decide to delegate to it.
 * @param version              the agent version, it is also the {@code ETag} of the card.
 * @param documentationUrl     where to read more about the agent.
 * @param iconUrl              an icon for the agent.
 * @param providerOrganization who runs the agent, no {@code provider} is published when it is not set.
 * @param providerUrl          the provider website.
 * @param defaultInputModes    the media types the agent accepts, comma separated.
 * @param defaultOutputModes   the media types the agent produces, comma separated.
 * @param publicUrl            the base URL the agent is reachable at, {@code https://agent.example.com} for example.
 *                             When it is not set the card interface URLs are derived from the request which fetches
 *                             it, which is convenient in development but wrong behind a proxy.
 * @param tenant               the opaque routing identifier to advertise in the card interfaces, clients then echo it
 *                             in every request. Leave it empty when the server hosts a single agent.
 * @param streaming            whether {@code SendStreamingMessage} and {@code SubscribeToTask} are supported.
 * @param pushNotifications    whether push notification webhooks can be registered on a task.
 * @param extendedAgentCard    whether {@code GetExtendedAgentCard} is supported, it needs at least one
 *                             {@link io.yupiik.fusion.a2a.api.A2AAgentCard} with {@code extended()} returning
 *                             {@code true} to answer something.
 * @param restBinding          whether the HTTP+JSON binding is exposed next to the JSON-RPC one.
 * @param taskTimeout          how long a blocking {@code SendMessage} waits for the task to be settled, see
 *                             {@link A2ADurations} for the accepted spellings.
 * @param taskRetention        how long a settled task is kept so it can still be fetched with {@code GetTask} -
 *                             {@code 0} keeps them forever.
 * @param pushTimeout          the timeout of a push notification webhook call.
 * @param agentCardMaxAge      the {@code Cache-Control: max-age} of the agent card, in seconds.
 * @param signatureKey         the PKCS#8 PEM private key to sign the agent card with. Signing is optional in the
 *                             specification, so leaving it unset publishes an unsigned card. Setting it requires
 *                             {@code publicUrl} to be set too.
 * @param signatureAlgorithm   {@code ES256} or {@code RS256}, it must match {@code signatureKey}.
 * @param signatureKid         the {@code kid} of the JWS protected header, so a caller knows which key to use. It
 *                             defaults to the agent version.
 * @param signatureJku         the JWKS URL to advertise in the JWS protected header, where a caller can fetch the
 *                             public key.
 */
@RootConfiguration("fusion.a2a")
public record A2AConfiguration(
        @Property(defaultValue = "\"fusion-a2a-server\"", documentation = "Agent name.")
        String name,

        @Property(
                defaultValue = "\"An agent built with Yupiik Fusion.\"",
                documentation = "What the agent does, this is what other agents read.")
        String description,

        @Property(defaultValue = "\"1.0.0\"", documentation = "Agent version, also used as the agent card ETag.")
        String version,

        @Property(documentation = "URL of the agent documentation.")
        String documentationUrl,

        @Property(documentation = "URL of the agent icon.") String iconUrl,

        @Property(documentation = "Organization running the agent, no provider is published when unset.")
        String providerOrganization,

        @Property(documentation = "Website of the organization running the agent.")
        String providerUrl,

        @Property(
                defaultValue = "\"text/plain,application/json\"",
                documentation = "Media types the agent accepts, comma separated.")
        String defaultInputModes,

        @Property(
                defaultValue = "\"text/plain,application/json\"",
                documentation = "Media types the agent produces, comma separated.")
        String defaultOutputModes,

        @Property(documentation = "Base URL of the agent, derived from the request when unset.")
        String publicUrl,

        @Property(documentation = "Opaque routing identifier clients must echo, for a server hosting several agents.")
        String tenant,

        @Property(
                defaultValue = "true",
                documentation = "Is streaming - SendStreamingMessage and SubscribeToTask - supported.")
        boolean streaming,

        @Property(defaultValue = "true", documentation = "Are push notification webhooks supported.")
        boolean pushNotifications,

        @Property(defaultValue = "false", documentation = "Is GetExtendedAgentCard supported.")
        boolean extendedAgentCard,

        @Property(defaultValue = "true", documentation = "Is the HTTP+JSON binding exposed next to the JSON-RPC one.")
        boolean restBinding,

        @Property(
                defaultValue = "\"PT5M\"",
                documentation =
                        "How long a blocking SendMessage waits for the task to settle, an ISO-8601 duration such as PT0.5S, PT30S or PT5M - a bare number is seconds.")
        String taskTimeout,

        @Property(
                defaultValue = "\"PT30M\"",
                documentation =
                        "How long a settled task is kept, 0 to keep them forever, an ISO-8601 duration such as PT30S or PT30M - a bare number is seconds.")
        String taskRetention,

        @Property(
                defaultValue = "\"PT30S\"",
                documentation =
                        "Timeout of a push notification webhook call, an ISO-8601 duration such as PT0.5S or PT30S - a bare number is seconds.")
        String pushTimeout,

        @Property(defaultValue = "300", documentation = "Cache-Control max-age of the agent card, in seconds.")
        int agentCardMaxAge,

        @Property(
                documentation =
                        "PKCS#8 PEM private key to sign the agent card with, unset publishes an unsigned card. It requires publicUrl to be set.")
        String signatureKey,

        @Property(defaultValue = "\"ES256\"", documentation = "Algorithm of signatureKey, ES256 or RS256.")
        String signatureAlgorithm,

        @Property(documentation = "kid of the JWS protected header, it defaults to the agent version.")
        String signatureKid,

        @Property(
                documentation = "JWKS url advertised in the JWS protected header so a caller can fetch the public key.")
        String signatureJku) {}
