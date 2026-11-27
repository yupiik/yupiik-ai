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
import io.yupiik.ia.agent.core.llm.ChatRoutes;
import java.util.List;
import java.util.Map;

@JsonModel
public record ProviderConfiguration(
        @Property(
                defaultValue = "\"default\"",
                documentation = "Unique identifier of this provider, referenced in model properties as $<id>/<model>.")
        String id,

        @Property(
                defaultValue = "\"https://api.openai.com/v1\"",
                documentation =
                        "Base URL of the LLM endpoint. This is the full endpoint including the API route: it may end with /chat/completions (OpenAI chat), /responses (OpenAI responses) or /messages (Anthropic messages API). The models endpoint is derived by dropping that route suffix and appending /models. When the base URL ends in /v1 with no route, the models endpoint is a best effort {baseUrl}/models and a warning is logged because the flavor cannot be guessed reliably.")
        String baseUrl,

        @Property(
                defaultValue = "configuration.get(\"OPENAI_API_KEY\").orElse(\"\")",
                documentation =
                        "API key. When unset, defaults to OPENAI_API_KEY through configuration. An explicit empty value disables this default.")
        String apiKey,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Default chat model (eg. gpt-5-mini). Validated at runtime, not at startup, so `help` works without configuration.")
        String model,

        @Property(
                defaultValue = "io.yupiik.ia.agent.core.llm.ApiFlavor.AUTO",
                documentation =
                        "API flavor: auto (detect from the base URL route suffix), completions, responses, anthropic, codex or experimental opencode-free.")
        ApiFlavor api,

        @Property(
                defaultValue = "-1",
                documentation =
                        "Context window size in tokens, used by the usage gauges and thresholds. Zero or negative (the default) auto-detects it from the provider /v1/models vendor extensions (context_window, model_context_window, context_length, top_provider.context_length, max_model_len, max_input_tokens, llama.cpp meta.n_ctx, or meta.n_ctx_train when smaller than the default), caches it one day per provider and model under the storage home and falls back to 32000. Detected values are totals (prompt + completion combined): the usable input budget subtracts the declared output cap (per-model behavior.outputTokens, outputTokens or the detected top_provider.max_completion_tokens/max_tokens) and falls back to a min(20000, window/4) reserve when no cap is declared.")
        int contextWindow,

        @Property(
                defaultValue = "-1",
                documentation =
                        "Default output token budget (the max_tokens parameter for providers that require it, such as Anthropic). Zero or negative (the default) auto-detects it from the provider /v1/models max_tokens, cached one day per provider and model under the storage home, and falls back to 32000. This is the per-provider default; a per-model value configured in the models behavior field wins over it. A declared value also sizes the completion headroom reserved from the context window (a value greater than the window cannot be honoured and falls back to the min(20000, window/4) heuristic).")
        int outputTokens,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Optional ISO-8601 release date (yyyy-MM-dd) of the latest model of this provider. Mirrors opencode's model release date and unlocks the newer reasoning effort tiers (OpenAI 'none' since 2025-11-13 and 'xhigh' since 2025-12-04) for the implicit per-family variant scheme.")
        String releaseDate,

        @Property(
                documentation =
                        "Provider-level tuning defaults (reasoning effort, variants and provider specific options) applied to every model of this provider below the per-model entries. Unset facets fall back to the implicit per-family scheme.")
        ModelTuningConfiguration tuning,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Per-model entries for this provider (display style and behavior). Each entry applies to a model id matched exactly or, when the configured id is a substring of the actual model name, by containment. The strongest override is an exact id match, then the first substring match, then the provider defaults (tuning, color, outputTokens and the implicit value-based scheme). The list order defines the implicit bold rule within this provider: the first two listed models are bold.")
        List<ProviderModelConfiguration> models,

        @Property(
                defaultValue = "-1",
                documentation =
                        "Maximum number of concurrent in-flight LLM requests for this provider. 0 or negative (the default) falls back to the global yca.llm.maxConcurrent; when that is also unset the concurrency is unlimited and adapted from the provider 429 concurrency_limit_exceeded responses.")
        int maxConcurrent,

        @Property(defaultValue = "300", documentation = "HTTP request timeout in seconds (5 minutes).")
        int timeoutSeconds,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Optional JSON object merged into the payload of every API call for this provider (eg. {\"reasoning_effort\":\"high\"} or a variant). Empty is ignored.")
        String providerSpecificOptions,

        @Property(
                defaultValue = "java.util.Map.of()",
                documentation =
                        "Extra HTTP headers added to every request to this provider (eg. beta headers, org identifiers or service tiers). Values are empty-string ignored.")
        Map<String, String> headers,

        @Property(
                defaultValue = "java.util.Map.of()",
                documentation =
                        "Dynamic HTTP headers added to every request to this provider. Each value is a handlebars template compiled once per provider and rendered per request; blank rendered values are skipped. A dynamic header wins over the static `headers` map and over the default API-key authorization when it targets the same name (eg. \"authorization\": \"Bearer {{exec \\\"exec_options=ttl=3600,strip=true\\\" \\\"MY_ENV=foo\\\" \\\"somebin\\\" \\\"somearg\\\"}}\" renders the command stdout, cached for 3600 seconds). The exec helper runs the given command with the leading KEY=VALUE arguments as environment entries and returns its stripped stdout; exec_options is a comma-separated key=value list (ttl=N caches the output N seconds, strip=true|false trims it, strip defaults to true).")
        Map<String, String> dynamicHeaders,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Base display tone (hex, eg. #e0a458) for every model of this provider. When blank the provider keeps a tone derived from its id. Per-model styles in the models field may override this tone for individual models.")
        String color,

        @Property(documentation = "Provider-tunable system prompt sections (todo/plan guidance).")
        PromptConfiguration prompt,

        @Property(
                documentation =
                        "Sub-agent routing policy for sessions on this provider. Unset fields inherit yca.subagents.")
        ProviderSubagentConfiguration subagents,

        @Property(
                documentation =
                        "Per-provider overrides of the global yca.model.* background models (same compact/prune/memory shape). "
                                + "Resolution per background call (compaction summarizer, memory writes): a non-blank, non-\"inherit\" "
                                + "value is that model - a bare name is this provider's own model (implicitly routed to this provider), "
                                + "a $provider/model reference keeps its explicit routing; the \"inherit\" keyword resolves the global "
                                + "yca.model.* chain (including its compact fallbacks); a blank value (the default) means this provider's "
                                + "own default model, ie. no dedicated background model for this provider.")
        ModelConfiguration backgroundModelOverrides,

        @Property(
                documentation =
                        "Per-provider compaction resilience overrides (summarizer retries, backoff, rate limit and "
                                + "main-model fallback). Unset fields inherit the global yca.llm / yca.compaction values.")
        ProviderCompactionConfiguration compaction,

        @Property(
                documentation =
                        "Declarative capability knobs of this provider (codex client version, encrypted-reasoning include, usage-quota enablement). "
                                + "These are typed internal options that drive yca behaviour and, unlike providerSpecificOptions, are never merged "
                                + "into the wire request payload.")
        ProviderCapabilities capabilities) {

    /** The effective per-provider compaction overrides: the configured record, or null when absent. */
    public ProviderCompactionConfiguration resolvedCompaction() {
        return compaction;
    }

    /** The effective codex capabilities: the configured record, or empty defaults when absent. */
    public CodexCapabilities codexCapabilities() {
        final var caps = capabilities;
        if (caps == null || caps.codex() == null) {
            return new CodexCapabilities(160, false, false);
        }
        return caps.codex();
    }

    /** The effective OpenAI capabilities: the configured record, or empty defaults when absent. */
    public OpenAICapabilities openaiCapabilities() {
        final var caps = capabilities;
        if (caps == null || caps.openai() == null) {
            return new OpenAICapabilities(null, false);
        }
        return caps.openai();
    }

    /** Whether this provider enables the Codex account usage quota (the /usage command and sidebar gauge). */
    public boolean usageEnabled() {
        return codexCapabilities().enableUsage();
    }

    /** True when the value is the reserved "inherit" keyword (resolves the global yca.model.* chain). */
    public static boolean isInheritKeyword(final String value) {
        return value != null && "inherit".equalsIgnoreCase(value.strip());
    }

    public String resolveApiKey() {
        if (api == ApiFlavor.OPENCODE_FREE
                || (api == null || api == ApiFlavor.AUTO)
                        && ChatRoutes.detectFlavor(baseUrl) == ApiFlavor.OPENCODE_FREE) {
            return "public";
        }
        return apiKey == null ? "" : apiKey;
    }

    /** The todo-guidance mode for this provider: 'auto' when the prompt section is absent or unset. */
    public String todoPrompt() {
        final var p = prompt;
        return p == null || p.todo() == null || p.todo().isBlank() ? "auto" : p.todo();
    }

    /**
     * Whether this provider configures the todo guidance itself, as opposed to inheriting the global
     * {@code yca.todos.guidance}. Blank and {@code auto} both mean unset: {@code auto} is the
     * documented default of {@link PromptConfiguration#todo()} and only asks for the family block.
     * Any other value (verbatim guidance, or {@code false} to opt out) is an explicit choice.
     */
    public boolean todoPromptConfigured() {
        final var p = prompt;
        if (p == null || p.todo() == null || p.todo().isBlank()) {
            return false;
        }
        return !p.todo().trim().equalsIgnoreCase("auto");
    }
}
