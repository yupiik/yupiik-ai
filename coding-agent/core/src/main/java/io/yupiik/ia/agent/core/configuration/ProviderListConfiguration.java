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
package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.ia.agent.core.llm.ApiFlavor;
import java.util.List;
import java.util.Map;

@JsonModel
public record ProviderListConfiguration(
        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "List of OpenAI compatible providers. Each provider has an id used to reference it as $<id>/<model> in model properties.")
        List<ProviderConfiguration> providers,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Id of the default provider. When blank or unknown, the first provider in the list wins.")
        String defaultProvider,

        // for convenience: when 'providers' is empty these legacy flat scalars (previously at yca.provider.*)
        // are synthesized into a single implicit provider with id "default". They let existing
        // --yca-provider-model / --yca-provider-baseUrl / YCA_PROVIDER_MODEL ... keep working unchanged.
        @Property(
                defaultValue = "\"https://api.openai.com/v1\"",
                documentation =
                        "Convenience shorthand for the default provider base URL (used only when no providers are listed).")
        String baseUrl,

        @Property(
                defaultValue = "configuration.get(\"OPENAI_API_KEY\").orElse(\"\")",
                documentation =
                        "Convenience shorthand for the default provider API key (used only when no providers are listed). When unset, defaults to OPENAI_API_KEY through configuration; an explicit empty value disables this default.")
        String apiKey,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Convenience shorthand for the default provider model (used only when no providers are listed).")
        String model,

        @Property(
                defaultValue = "io.yupiik.ia.agent.core.llm.ApiFlavor.AUTO",
                documentation =
                        "Convenience shorthand for the default provider API flavor (used only when no providers are listed).")
        ApiFlavor api,

        @Property(
                defaultValue = "-1",
                documentation =
                        "Convenience shorthand for the default provider context window (used only when no providers are listed). Zero or negative auto-detects it from the provider /v1/models vendor extensions, caches it one day per provider and model and falls back to 32000.")
        int contextWindow,

        @Property(
                defaultValue = "-1",
                documentation =
                        "Convenience shorthand for the default provider output token budget (used only when no providers are listed). Zero or negative auto-detects it from the provider /v1/models max_tokens and falls back to 32000.")
        int outputTokens,

        @Property(
                defaultValue = "-1",
                documentation =
                        "Convenience shorthand for the default provider maximum concurrent in-flight LLM requests (used only when no providers are listed). Zero or negative falls back to the global yca.llm.maxConcurrent; when that is also unset the concurrency is unlimited and adapted from the provider 429 concurrency_limit_exceeded responses.")
        int maxConcurrent,

        @Property(
                defaultValue = "300",
                documentation =
                        "Convenience shorthand for the default provider timeout (used only when no providers are listed).")
        int timeoutSeconds,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Convenience shorthand for the default provider base display tone (hex, used only when no providers are listed).")
        String color) {

    public String resolvedDefaultProvider() {
        final var explicit = defaultProvider;
        if (explicit != null
                && !explicit.isBlank()
                && providers != null
                && providers.stream().anyMatch(p -> explicit.equals(p.id()))) {
            return explicit;
        }
        if (providers != null && !providers.isEmpty()) {
            final var first = providers.get(0).id();
            return first == null || first.isBlank() ? "default" : first;
        }
        return "default";
    }

    public ProviderConfiguration asSingleProvider() {
        // for convenience, present the legacy flat scalars as an implicit provider named "default"
        return new ProviderConfiguration(
                "default",
                baseUrl == null ? "https://api.openai.com/v1" : baseUrl,
                apiKey == null ? "" : apiKey,
                model == null ? "" : model,
                api,
                contextWindow,
                outputTokens,
                "",
                null,
                List.of(),
                maxConcurrent,
                timeoutSeconds,
                "",
                Map.of(),
                Map.of(),
                color == null ? "" : color,
                new PromptConfiguration("auto"),
                null,
                null,
                null,
                null);
    }
}
