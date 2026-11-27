/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
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
package io.yupiik.ia.agent.bot.runtime.execution;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.bot.runtime.JsonObjects;
import io.yupiik.ia.agent.bot.runtime.model.AgentConfigurationDocument;
import io.yupiik.ia.agent.core.configuration.ProviderBudgetConfiguration;
import io.yupiik.ia.agent.core.llm.ApiFlavor;
import io.yupiik.ia.agent.core.llm.ChatRoutes;
import io.yupiik.ia.agent.core.llm.LlmRelay;
import io.yupiik.ia.agent.core.llm.ProviderRequestBudget;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Owns a per-attempt relay; only the ephemeral access token is written to agent configuration. */
public final class AttemptRelay implements AutoCloseable {
    private static final Pattern BASE_PATH = Pattern.compile("(/[A-Za-z0-9_-]+)*");
    private static final Pattern TRAILING_SLASHES = Pattern.compile("/+$");
    private static final Pattern NUMERIC_ADDRESS = Pattern.compile("[0-9.]+|[0-9a-fA-F:]+");

    private final LlmRelay relay;

    public AttemptRelay(
            final Path configuration,
            final JsonMapper json,
            final String bind,
            final String advertised,
            final ProviderBudgetConfiguration budgetConfiguration)
            throws IOException {
        final var address = URI.create(advertised);
        if (!"http".equals(address.getScheme())
                || address.getHost() == null
                || address.getPort() != -1
                || address.getRawUserInfo() != null
                || address.getRawQuery() != null
                || address.getRawFragment() != null
                || !(address.getRawPath().isEmpty() || "/".equals(address.getRawPath()))) {
            throw new IllegalArgumentException("Relay advertised address must be an HTTP origin without port");
        }
        if (!NUMERIC_ADDRESS.matcher(bind).matches()) {
            throw new IllegalArgumentException("Relay bind must be a numeric private address");
        }
        final var document = json.fromString(AgentConfigurationDocument.class, Files.readString(configuration));
        final var yca = document.configuration();
        yca.remove("run");
        final var provider = new LinkedHashMap<>(JsonObjects.map(yca.get("provider")));
        if (!(provider.get("apiKey") instanceof String secret)
                || secret.isBlank()
                || !(provider.get("baseUrl") instanceof String base)) {
            throw new IllegalArgumentException("Relay requires explicit OpenAI-compatible provider baseUrl and apiKey");
        }
        if (provider.containsKey("type") && !"openai".equalsIgnoreCase(String.valueOf(provider.get("type")))) {
            throw new IllegalArgumentException("Relay supports OpenAI-compatible providers only");
        }
        final ApiFlavor configured;
        try {
            configured = ApiFlavor.valueOf(
                    String.valueOf(provider.getOrDefault("api", "AUTO")).toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unsupported pinned provider API");
        }
        final URI baseUri;
        try {
            baseUri = URI.create(base);
        } catch (final IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid fixed provider endpoint");
        }
        if (baseUri.getHost() == null
                || baseUri.getRawUserInfo() != null
                || baseUri.getRawQuery() != null
                || baseUri.getRawFragment() != null
                || !BASE_PATH.matcher(baseUri.getRawPath()).matches()) {
            throw new IllegalArgumentException("Invalid fixed provider endpoint");
        }
        final var detected = ChatRoutes.detectFlavor(base);
        final var api =
                configured == ApiFlavor.AUTO ? (detected == null ? ApiFlavor.COMPLETIONS : detected) : configured;
        if ((api != ApiFlavor.COMPLETIONS && api != ApiFlavor.RESPONSES) || (detected != null && detected != api)) {
            throw new IllegalArgumentException("Relay requires a pinned completions or responses provider");
        }
        final var endpoint = ChatRoutes.chatEndpoint(base, api);
        final var started = new LlmRelay(
                new InetSocketAddress(bind, 0),
                URI.create(endpoint),
                secret,
                4,
                ProviderRequestBudget.fromConfiguration(budgetConfiguration));
        try {
            // Rebuild rather than copying headers/auth fields which might contain another credential.
            final var safe = new LinkedHashMap<String, Object>();
            for (final var key : List.of("model", "contextWindow", "outputTokens", "timeoutSeconds")) {
                if (provider.containsKey(key)) {
                    safe.put(key, provider.get(key));
                }
            }
            safe.put(
                    "baseUrl",
                    TRAILING_SLASHES.matcher(advertised).replaceAll("") + ":"
                            + started.address().getPort()
                            + (api == ApiFlavor.RESPONSES ? "/responses" : "/chat/completions"));
            safe.put("api", api.name());
            safe.put("apiKey", started.accessToken());
            yca.put("provider", safe);
            // Alternate model definitions can contain direct credentials. Relay mode permits one provider only.
            yca.remove("model");
            Files.writeString(
                    configuration, json.toString(new AgentConfigurationDocument(yca, document.completionSettings())));
            this.relay = started;
        } catch (final IOException | RuntimeException e) {
            started.close();
            throw e;
        }
    }

    @Override
    public void close() {
        relay.close();
    }
}
