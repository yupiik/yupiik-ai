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
package io.yupiik.ia.agent.core.llm;

import io.yupiik.ia.agent.core.configuration.ModelVariantConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Derives provider-agnostic sampling defaults (temperature, topP, topK) and the implicit reasoning
 * variant scheme from a model family, so a request that does not set them explicitly still gets
 * sensible values instead of the provider random default. Highly inspired from the {code temperature},
 * {code topP}, {code topK} and {code variants} model default helpers of opencode.
 *
 * <p>Every method returns {@code null} when no default applies: the parameter is then simply not
 * sent and the provider applies its own default. Comparisons are case-insensitive on the model
 * name so model identifiers from different vendors (gpt, claude, gemini, deepseek, minimax, kimi,
 * glm) are detected consistently.</p>
 */
public final class DefaultModelTuner {
    private static final Pattern KIMI_K2_DOT_MODEL = Pattern.compile(".*k2\\..*");
    private static final Pattern KIMI_K2P_MODEL = Pattern.compile(".*k2p.*");
    private static final Pattern KIMI_K2_5_MODEL = Pattern.compile(".*k2-5.*");

    private DefaultModelTuner() {
        // utility
    }

    /** Gemini models that carry explicit sampling defaults (see opencode). */
    private static final List<Pattern> GEMINI_SAMPLING_DEFAULTS = List.of(
            Pattern.compile("gemini-2[.-]5(?:[.-]|$)"),
            Pattern.compile("gemini-3-(?:flash|pro)(?:[.-]|$)"),
            Pattern.compile("gemini-3[.-]1(?:[.-]|$)"),
            Pattern.compile("gemini-3[.-]5-flash(?!-lite)(?:[.-]|$)"));

    // Reasoning effort tiers and gpt family helpers, mirroring opencode transform.ts.

    private static final List<String> WIDELY_SUPPORTED_EFFORTS = List.of("low", "medium", "high");
    private static final List<String> OPENAI_EFFORTS = List.of("none", "minimal", "low", "medium", "high", "xhigh");
    private static final List<String> OPENAI_GPT5_1_EFFORTS = List.of("none", "low", "medium", "high");
    private static final List<String> OPENAI_GPT5_2_PLUS_EFFORTS = List.of("none", "low", "medium", "high", "xhigh");
    private static final List<String> OPENAI_GPT5_PRO_EFFORTS = List.of("high");
    private static final List<String> OPENAI_GPT5_PRO_2_PLUS_EFFORTS = List.of("medium", "high", "xhigh");
    private static final List<String> OPENAI_GPT5_CHAT_EFFORTS = List.of("medium");
    private static final List<String> OPENAI_GPT5_CODEX_XHIGH_EFFORTS = List.of("low", "medium", "high", "xhigh");
    private static final List<String> OPENAI_GPT5_CODEX_3_PLUS_EFFORTS =
            List.of("none", "low", "medium", "high", "xhigh");

    private static final String OPENAI_NONE_EFFORT_RELEASE_DATE = "2025-11-13";
    private static final String OPENAI_XHIGH_EFFORT_RELEASE_DATE = "2025-12-04";

    // Matches members of the gpt reasoning family across the id formats we encounter: "gpt-5",
    // "gpt-5-nano", "gpt-5.4", "gpt-6", "gpt-6.1-terra", "openai/gpt-5.4-codex", "azure_gpt-5.5".
    // Anchored to start-of-string or a "/" or "_" separator so it does not false-match "gpt-50",
    // "gpt-5o" or "mygpt-5". The gpt-5.1/5.2+ tier sets are reused by later majors (gpt-6.x
    // resolves to the newest sets); only the gpt-5 launch quirk (the "minimal" effort) is
    // major-specific.
    private static final Pattern GPT_FAMILY_RE = Pattern.compile("(?:^|[/_])gpt-(\\d+)(?:[.-](\\d+))?(?:[.-]|$)");
    // unversioned pro only ("gpt-5-pro"): versioned pro ids ("gpt-5.1-pro") fall through to
    // GPT_VERSIONED_PRO_RE and its dedicated PRO_2_PLUS tiers, mirroring opencode.
    private static final Pattern GPT_PRO_RE = Pattern.compile("(?:^|[/_])gpt-\\d+[.-]?pro(?:[.-]|$)");
    private static final Pattern GPT_VERSIONED_PRO_RE = Pattern.compile("(?:^|[/_])gpt-\\d+[.-]\\d+[.-]pro(?:[.-]|$)");

    private static final Pattern CLAUDE_VERSION_RE =
            Pattern.compile("claude-(?:[a-z]+-)?(\\d+)(?:[.-](\\d{1,2}))?(?:[.@-]|$)");

    /** Default temperature from a model name and provider id; null defers to the provider. */
    public static Double temperature(final String model, final String providerId) {
        final var m = norm(model);
        if (m == null) {
            return null;
        }
        if (m.startsWith("north-mini-code")) {
            return 1.0;
        }
        if (m.startsWith("claude")) {
            return null;
        }
        if (isGemini(m)) {
            return 1.0;
        }
        if (m.startsWith("glm-4.6") || m.startsWith("glm-4.7")) {
            return 1.0;
        }
        if (m.startsWith("minimax-m2")) {
            return 1.0;
        }
        if (m.startsWith("kimi-k2")) {
            if (isKimiK2(m)) {
                return 1.0;
            }
            return 0.6;
        }
        return null;
    }

    /** Default topP from a model name and provider id; null defers to the provider. */
    public static Double topP(final String model, final String providerId) {
        final var m = norm(model);
        if (m == null) {
            return null;
        }
        if (isGemini(m)) {
            return 0.95;
        }
        if (m.startsWith("minimax-m2")
                || m.startsWith("kimi-k2.5")
                || m.startsWith("kimi-k2p5")
                || m.startsWith("kimi-k2-5")) {
            return 0.95;
        }
        if (m.contains("deepseek-v4-flash-0731") || m.contains("deepseek-v4-flash:0731")) {
            return 0.95;
        }
        final var pid = providerId == null ? null : providerId.toLowerCase(Locale.ROOT);
        if (m.startsWith("deepseek-v4-flash")
                && (pid == null || pid.equals("deepseek") || pid.startsWith("opencode"))) {
            return 0.95;
        }
        return null;
    }

    /** Default topK from a model name and provider id; null defers to the provider. */
    public static Double topK(final String model, final String providerId) {
        final var m = norm(model);
        if (m == null) {
            return null;
        }
        if (m.startsWith("minimax-m2")) {
            return m.contains("m2.") || m.contains("m25") || m.contains("m21") ? 40.0 : 20.0;
        }
        if (isGemini(m)) {
            return 64.0;
        }
        return null;
    }

    /** A synthetic default variant name usable by the {@code /model} command when no variants are configured. */
    public static String implicitVariantName(final String model, final String providerId) {
        if (temperature(model, providerId) != null
                || topP(model, providerId) != null
                || topK(model, providerId) != null) {
            return "default";
        }
        return null;
    }

    /**
     * The context in which the implicit reasoning variant scheme is derived, mirroring the model facets
     * opencode reads (model id, provider id, npm package as our {@link ApiFlavor}, release date and
     * output budget).
     *
     * @param model model id (normalized during matching)
     * @param providerId provider id, used for a best-effort match against opencode catalog provider slugs
     * @param dialect the API wire flavor (our stand-in for opencode's npm package)
     * @param maxOutputTokens the resolved output budget, fine for the anthropic budget math (nullable)
     * @param releaseDate optional ISO-8601 release date (yyyy-MM-dd) unlocking OpenAI none/xhigh tiers
     * @param supportedReasoningEfforts reasoning efforts advertised by the model catalog, used as-is when non-empty
     */
    public record ModelContext(
            String model,
            String providerId,
            ApiFlavor dialect,
            Integer maxOutputTokens,
            String releaseDate,
            List<String> supportedReasoningEfforts) {

        public ModelContext(
                final String model,
                final String providerId,
                final ApiFlavor dialect,
                final Integer maxOutputTokens,
                final String releaseDate) {
            this(model, providerId, dialect, maxOutputTokens, releaseDate, null);
        }
    }

    /**
     * Reasoning-effort variants exposed for reasoning-capable families that have no configured variants.
     * A faithful port of opencode's {@code variants()} resolver, keyed on the wire flavor (COMPLETIONS /
     * RESPONSES as OpenAI-compatible, ANTHROPIC as Anthropic-compatible) and discriminated further by the
     * model id and, as a best-effort, the provider id. The flavor-less form assumes the plain
     * OpenAI-compatible chat wire (chat completions), the common layout opencode uses.
     */
    public static List<ModelVariantConfiguration> variants(final String model, final String providerId) {
        return variants(new ModelContext(model, providerId, ApiFlavor.COMPLETIONS, null, null));
    }

    /** Rich variant resolution from a {@link ModelContext}; see {@link #variants(String, String)}. */
    public static List<ModelVariantConfiguration> variants(final ModelContext ctx) {
        final var m = norm(ctx.model());
        if (m == null) {
            return List.of();
        }
        final var dialect = ctx.dialect() == null ? ApiFlavor.COMPLETIONS : ctx.dialect();
        final boolean anthropic = dialect == ApiFlavor.ANTHROPIC;
        // CODEX speaks the OpenAI Responses wire (reasoning effort / summaries), so it shares the
        // openai-compatible variant scheme (minus the provider-slug branches that never apply).
        final boolean openaiWire =
                dialect == ApiFlavor.COMPLETIONS || dialect == ApiFlavor.RESPONSES || dialect == ApiFlavor.CODEX;

        // The model catalog wins over every heuristic when it advertises reasoning efforts: the
        // provider is the source of truth for what the model actually accepts. Absent/empty keeps
        // the historical family/provider based resolution below unchanged.
        if (openaiWire
                && ctx.supportedReasoningEfforts() != null
                && !ctx.supportedReasoningEfforts().isEmpty()) {
            return efforts(
                    ctx.supportedReasoningEfforts(),
                    dialect == ApiFlavor.CODEX
                            ? DefaultModelTuner::openAiReasoningSuffix
                            : DefaultModelTuner::reasoningEffortOnly);
        }

        // deepseek names excluded by opencode before any other resolution.
        if (m.contains("deepseek-chat")
                || m.contains("deepseek-reasoner")
                || m.contains("deepseek-r1")
                || m.contains("deepseek-v3")) {
            return List.of();
        }

        final boolean glm52 = m.contains("glm-5.2") || m.contains("glm-5-2") || m.contains("glm-5p2");
        final boolean isKimi = isKimiFamily(m, ctx.providerId());
        final String adaptiveEfforts = anthropicAdaptiveEfforts(m);
        final String id = m;

        // minimax-m3 on the anthropic or OpenAI-compatible bounds uses a thinking toggle.
        if (id.contains("minimax-m3") && (anthropic || openaiWire)) {
            if (providerMatches(ctx.providerId(), "nvidia", "lilac")) {
                return List.of(
                        variant("none", null, "{\"chat_template_kwargs\":{\"thinking_mode\":\"disabled\"}}"),
                        variant("thinking", null, "{\"chat_template_kwargs\":{\"thinking_mode\":\"enabled\"}}"));
            }
            return List.of(
                    variant("none", null, "{\"thinking\":{\"type\":\"disabled\"}}"),
                    variant("thinking", null, "{\"thinking\":{\"type\":\"adaptive\"}}"));
        }

        // GLM-5.2 high/max on each wire.
        if (glm52 && openaiWire) {
            return List.of(variant("high", "high", null), variant("max", "max", null));
        }
        if (glm52 && anthropic) {
            return List.of(
                    variant("high", null, "{\"output_config\":{\"effort\":\"high\"}}"),
                    variant("max", null, "{\"output_config\":{\"effort\":\"max\"}}"));
        }

        // Kimi on the Anthropic-compatible wire uses adaptive efforts low..max.
        if (isKimi && anthropic) {
            final var vars = new ArrayList<ModelVariantConfiguration>();
            for (final var effort : List.of("low", "medium", "high", "xhigh", "max")) {
                vars.add(variant(
                        effort,
                        null,
                        "{\"thinking\":{\"type\":\"adaptive\",\"display\":\"summarized\"},\"output_config\":{\"effort\":\""
                                + effort + "\"}}"));
            }
            return vars;
        }

        // Early-exclude families opencode does not expose variants for.
        if (id.contains("minimax")
                || (m.contains("glm") && !glm52)
                || id.contains("kimi")
                || id.contains("k2p")
                || id.contains("qwen")
                || id.contains("big-pickle")) {
            return List.of();
        }

        // grok-3-mini exposes low/high.
        if (id.contains("grok") && id.contains("grok-3-mini")) {
            return List.of(variant("low", "low", null), variant("high", "high", null));
        }

        if (anthropic) {
            return anthropicVariants(ctx, m);
        }
        if (openaiWire) {
            return openAiCompatibleVariants(ctx, m);
        }
        return List.of();
    }

    /**
     * OpenAI-compatible wire: model-id expert efforts, else widely-supported tiers with a deepseek-v4 max.
     * The provider id gives a best-effort match against opencode's OpenAI/azure/meta/copilot catalog slugs.
     */
    private static List<ModelVariantConfiguration> openAiCompatibleVariants(final ModelContext ctx, final String m) {
        if (m.contains("north-mini-code")) {
            return List.of(variant("none", "none", null), variant("high", "high", null));
        }

        // The Codex backend (chatgpt.com/backend-api/codex) speaks the OpenAI Responses wire with the
        // full OpenAI reasoning-effort set (none/none gated by the model/release date). The provider id
        // for a codex-flavored provider is user-chosen (eg. "default"), never a catalog slug, so route
        // on the dialect instead: the openai-tier resolution already carries gptCodexReasoningEfforts.
        if (ctx.dialect() == ApiFlavor.CODEX) {
            return efforts(openAiReasoningEfforts(m, ctx.releaseDate()), DefaultModelTuner::openAiReasoningSuffix);
        }

        final var providerId = ctx.providerId();
        // meta exposes the full OpenAI set on the openai package.
        if (providerMatches(providerId, "meta")) {
            return efforts(OPENAI_EFFORTS, DefaultModelTuner::openAiReasoningSuffix);
        }
        // groq exposes none + widely supported.
        if (providerMatches(providerId, "groq")) {
            return efforts(List.of("none", "low", "medium", "high"), DefaultModelTuner::reasoningEffortOnly);
        }
        // mistral exposes only high on the few reasoning models.
        if (providerMatches(providerId, "mistral")) {
            if (List.of("mistral-small-2603", "mistral-small-latest", "mistral-medium-3.5", "mistral-medium-2604")
                    .stream()
                    .noneMatch(m::contains)) {
                return List.of();
            }
            return List.of(variant("high", "high", null));
        }
        // azure: o1-mini has no variants, everything else the openai tiers.
        if (providerMatches(providerId, "azure")) {
            if (m.equals("o1-mini")) {
                return List.of();
            }
            return efforts(openAiReasoningEfforts(m, ctx.releaseDate()), DefaultModelTuner::openAiReasoningSuffix);
        }
        // openai package / mantle: meta handled above, else openai tiers.
        if (providerMatches(providerId, "openai") || providerMatches(providerId, "mantle")) {
            return efforts(openAiReasoningEfforts(m, ctx.releaseDate()), DefaultModelTuner::openAiReasoningSuffix);
        }
        // copilot: no variants for gemini; claude -> widely supported; else gpt tiers (+xhigh late 2025).
        if (providerMatches(providerId, "github-copilot", "copilot")) {
            if (m.contains("gemini")) {
                return List.of();
            }
            if (m.contains("claude")) {
                return efforts(WIDELY_SUPPORTED_EFFORTS, DefaultModelTuner::reasoningEffortOnly);
            }
            var efforts = new ArrayList<String>(WIDELY_SUPPORTED_EFFORTS);
            if (m.contains("5.1-codex-max") || m.contains("5.2") || m.contains("5.3")) {
                efforts.add("xhigh");
            } else if (gptReasoningFamily(m)
                    && ctx.releaseDate() != null
                    && ctx.releaseDate().compareTo(OPENAI_XHIGH_EFFORT_RELEASE_DATE) >= 0) {
                efforts.add("xhigh");
            }
            return List.of(
                    variant("low", "low", reasoningExtras()),
                    variant("medium", "medium", reasoningExtras()),
                    variant("high", "high", reasoningExtras()),
                    efforts.contains("xhigh") ? variant("xhigh", "xhigh", reasoningExtras()) : null);
        }
        // Generic OpenAI-compatible / cerebras / togetherai / xai / deepinfra / venice.
        var efforts = new ArrayList<String>(WIDELY_SUPPORTED_EFFORTS);
        if (m.contains("deepseek-v4")) {
            efforts.add("max");
        }
        if (gptReasoningFamily(m)) {
            // The real gpt-5/gpt-6 ids on a provider without an openai/meta/azure/copilot catalog
            // slug (eg. freeinference, openrouter, chatgpt-procapital) still accept the OpenAI
            // reasoning tiers: resolve them through the same major+minor tiering as the openai
            // branches instead of the widely-supported-only default. The catalog
            // (supported_reasoning_levels) still overrides when the provider advertises one.
            return efforts(openAiReasoningEfforts(m, ctx.releaseDate()), DefaultModelTuner::reasoningEffortOnly);
        }
        return efforts(efforts, DefaultModelTuner::reasoningEffortOnly);
    }

    /** Anthropic-compatible wire: adaptive efforts when modern, opus-4.5 widely-supported, else token budgets. */
    private static List<ModelVariantConfiguration> anthropicVariants(final ModelContext ctx, final String m) {
        final String adaptiveEfforts = anthropicAdaptiveEfforts(m);
        if (adaptiveEfforts != null) {
            final boolean omitted = anthropicOmitsThinking(m);
            final var vars = new ArrayList<ModelVariantConfiguration>();
            for (final var effort : adaptiveEfforts.split(",")) {
                final var display = omitted ? ",\"display\":\"summarized\"" : "";
                // effort lives in output_config per the Messages API (a top-level 'effort' is not a
                // documented parameter and strict endpoints reject or ignore it)
                vars.add(variant(
                        effort,
                        null,
                        "{\"thinking\":{\"type\":\"adaptive\"" + display + "},\"output_config\":{\"effort\":\"" + effort
                                + "\"}}"));
            }
            return vars;
        }
        if (anthropicOpus45(m)) {
            final var vars = new ArrayList<ModelVariantConfiguration>();
            for (final var effort : WIDELY_SUPPORTED_EFFORTS) {
                vars.add(variant(
                        effort,
                        null,
                        "{\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":" + opus45Budget(ctx, effort) + "}}"));
            }
            return vars;
        }
        final var out = ctx.maxOutputTokens() == null || ctx.maxOutputTokens() <= 0
                ? OutputBudgetResolver.DEFAULT_MAX_TOKEN
                : ctx.maxOutputTokens();
        final int highBudget = Math.min(16_000, Math.max(1, out / 2 - 1));
        final int maxBudget = Math.min(31_999, Math.max(1, out - 1));
        return List.of(
                variant("high", null, "{\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":" + highBudget + "}}"),
                variant("max", null, "{\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":" + maxBudget + "}}"));
    }

    private static long opus45Budget(final ModelContext ctx, final String effort) {
        // opencode keeps the widely-supported default for opus-4.5; we reuse the generic budget maths.
        final var out = ctx.maxOutputTokens() == null || ctx.maxOutputTokens() <= 0
                ? OutputBudgetResolver.DEFAULT_MAX_TOKEN
                : ctx.maxOutputTokens();
        return "high".equals(effort)
                ? Math.min(16_000, Math.max(1, out / 2 - 1))
                : Math.min(31_999, Math.max(1, out - 1));
    }

    /** OpenAI reasoning effort tiers for a model id + release date, mirroring opencode's openaiReasoningEfforts. */
    private static List<String> openAiReasoningEfforts(final String apiId, final String releaseDate) {
        final var id = apiId.toLowerCase(Locale.ROOT);
        if (id.contains("deep-research")) {
            return List.of("medium");
        }
        final var chat = gptChatReasoningEfforts(id);
        if (chat != null) {
            return chat;
        }
        if (GPT_PRO_RE.matcher(id).find()) {
            return OPENAI_GPT5_PRO_EFFORTS;
        }
        final var codex = gptCodexReasoningEfforts(id);
        if (codex != null) {
            return codex;
        }
        final var versioned = versionedGptReasoningEfforts(id);
        if (versioned != null) {
            return versioned;
        }
        final var efforts = new ArrayList<String>(WIDELY_SUPPORTED_EFFORTS);
        if (gptFamily(id)) {
            final var major = gptMajor(id);
            if (major == 5) {
                efforts.add(0, "minimal");
                if (releaseDate != null && releaseDate.compareTo(OPENAI_NONE_EFFORT_RELEASE_DATE) >= 0) {
                    efforts.add(0, "none");
                }
                if (releaseDate != null && releaseDate.compareTo(OPENAI_XHIGH_EFFORT_RELEASE_DATE) >= 0) {
                    efforts.add("xhigh");
                }
            } else if (major >= 6) {
                // gpt-6 launch-era models dropped the gpt-5-only "minimal" effort and accept the
                // full none..xhigh set out of the box (both rollout tiers predate the family).
                efforts.add(0, "none");
                efforts.add("xhigh");
            }
        }
        return efforts;
    }

    private static List<String> versionedGptReasoningEfforts(final String apiId) {
        if (GPT_VERSIONED_PRO_RE.matcher(apiId).find()) {
            return OPENAI_GPT5_PRO_2_PLUS_EFFORTS;
        }
        final var major = gptMajor(apiId);
        final var minor = gptMinor(apiId);
        if (major < 5 || minor == null) {
            return null;
        }
        // gpt-5.1 replaced gpt-5's "minimal" effort with "none"; gpt-5.2+ and every gpt-6.x
        // accept the full set. Unversioned ids fall through to the release-date gated logic.
        return major == 5 && minor == 1 ? OPENAI_GPT5_1_EFFORTS : OPENAI_GPT5_2_PLUS_EFFORTS;
    }

    private static List<String> gptCodexReasoningEfforts(final String apiId) {
        if (!gptReasoningFamily(apiId) || !apiId.contains("codex")) {
            return null;
        }
        final var major = gptMajor(apiId);
        final var minor = gptMinor(apiId);
        // gpt-6 (and gpt-5.3+) carry the newest codex effort set; gpt-5.2+ and the codex-max
        // ids expose xhigh; the remaining gpt-5 codex ids keep the widely-supported set.
        if (major >= 6 || (minor != null && minor >= 3)) {
            return OPENAI_GPT5_CODEX_3_PLUS_EFFORTS;
        }
        if (apiId.contains("codex-max") || (minor != null && minor >= 2)) {
            return OPENAI_GPT5_CODEX_XHIGH_EFFORTS;
        }
        return WIDELY_SUPPORTED_EFFORTS;
    }

    private static List<String> gptChatReasoningEfforts(final String apiId) {
        if (!gptReasoningFamily(apiId) || !apiId.contains("-chat")) {
            return null;
        }
        return gptMinor(apiId) == null ? List.of() : OPENAI_GPT5_CHAT_EFFORTS;
    }

    private static boolean gptFamily(final String apiId) {
        return GPT_FAMILY_RE.matcher(apiId).find();
    }

    /** The gpt reasoning family: gpt-5 and later majors (gpt-4x predates reasoning efforts). */
    private static boolean gptReasoningFamily(final String apiId) {
        return gptMajor(apiId) >= 5;
    }

    private static int gptMajor(final String apiId) {
        final var matcher = GPT_FAMILY_RE.matcher(apiId);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static Integer gptMinor(final String apiId) {
        final var matcher = GPT_FAMILY_RE.matcher(apiId);
        return matcher.find() && matcher.group(2) != null ? Integer.valueOf(matcher.group(2)) : null;
    }

    /** Anthropic adaptive effort tiers for a model id, or null when the model predates adaptive thinking. */
    private static String anthropicAdaptiveEfforts(final String apiId) {
        if (anthropicUsesModernAdaptiveThinking(apiId)) {
            return "low,medium,high,xhigh,max";
        }
        if (List.of(
                        "opus-4-6",
                        "opus-4.6",
                        "4-6-opus",
                        "4.6-opus",
                        "sonnet-4-6",
                        "sonnet-4.6",
                        "4-6-sonnet",
                        "4.6-sonnet")
                .stream()
                .anyMatch(apiId::contains)) {
            return "low,medium,high,max";
        }
        return null;
    }

    private static boolean anthropicUsesModernAdaptiveThinking(final String apiId) {
        if (!apiId.toLowerCase(Locale.ROOT).contains("claude-")) {
            return false;
        }
        final var matcher = CLAUDE_VERSION_RE.matcher(apiId);
        if (!matcher.find()) {
            return true;
        }
        final var major = Integer.parseInt(matcher.group(1));
        final var minor = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        return major > 4 || (major == 4 && minor >= 7);
    }

    private static boolean anthropicOmitsThinking(final String apiId) {
        return anthropicUsesModernAdaptiveThinking(apiId);
    }

    private static boolean anthropicOpus45(final String apiId) {
        return apiId.contains("opus-4-5") || apiId.contains("opus-4.5");
    }

    /** Kimi/Moonshot family detection from the model id (and provider id as a fallback). */
    private static boolean isKimiFamily(final String model, final String providerId) {
        if (model.contains("kimi") || model.contains("moonshot")) {
            return true;
        }
        if (providerId == null) {
            return false;
        }
        final var pid = providerId.toLowerCase(Locale.ROOT);
        return pid.contains("kimi") || pid.contains("moonshot");
    }

    private static boolean providerMatches(final String providerId, final String... slugs) {
        if (providerId == null) {
            return false;
        }
        final var pid = providerId.toLowerCase(Locale.ROOT);
        for (final var slug : slugs) {
            if (pid.equals(slug) || pid.startsWith(slug)) {
                return true;
            }
        }
        return false;
    }

    private static List<ModelVariantConfiguration> efforts(
            final List<String> efforts, final Function<String, String> payload) {
        final var vars = new ArrayList<ModelVariantConfiguration>(efforts.size());
        for (final var effort : efforts) {
            vars.add(variant(effort, effort, payload.apply(effort)));
        }
        return vars;
    }

    private static String reasoningEffortOnly(final String effort) {
        return null;
    }

    private static String openAiReasoningSuffix(final String effort) {
        return "{\"reasoning_effort\":\"" + effort
                + "\",\"reasoning_summary\":\"auto\",\"include\":[\"reasoning.encrypted_content\"]}";
    }

    private static String reasoningExtras() {
        return "{\"reasoning_summary\":\"auto\",\"include\":[\"reasoning.encrypted_content\"]}";
    }

    private static ModelVariantConfiguration variant(final String name, final String effort, final String extra) {
        return new ModelVariantConfiguration(name, null, null, null, null, effort, extra);
    }

    /**
     * The implicit reasoning-effort default for a model family, mirroring opencode. The deepseek-v4
     * family defaults to {@code max} (opencode exposes {@code max} as the strongest available effort for
     * deepseek-v4); the gpt-5/gpt-6 family (excluding the -chat and -pro sub-models) defaults to
     * {@code medium}; every other family returns {@code null} so nothing is forced and the provider
     * default applies.
     *
     * <p>Display/selection hint only: the request path does not apply this value on the wire.
     * ChatClient.resolveReasoningEffort deliberately skips the implicit per-family default (the
     * deepseek-v4 {@code max} fallback made freeinference emit tens of thousands of reasoning
     * characters per call); the same tiering is exposed through the {@code /model} variants.</p>
     */
    public static String reasoningEffort(final String model, final String providerId) {
        final var m = norm(model);
        if (m == null) {
            return null;
        }
        if (m.startsWith("deepseek-v4")) {
            return "max";
        }
        if (gptReasoningFamily(m) && !m.contains("-chat") && !isGptPro(m)) {
            return "medium";
        }
        return null;
    }

    private static boolean isGptPro(final String apiId) {
        return GPT_PRO_RE.matcher(apiId).find()
                || GPT_VERSIONED_PRO_RE.matcher(apiId).find();
    }

    /** True when the model belongs to a Gemini family carrying explicit sampling defaults. */
    private static boolean isGemini(final String model) {
        for (final var pattern : GEMINI_SAMPLING_DEFAULTS) {
            if (pattern.matcher(model).find()) {
                return true;
            }
        }
        return false;
    }

    /** True for kimi k2 reasoning families (thinking or k2. sub-versions). */
    private static boolean isKimiK2(final String model) {
        return model.contains("thinking")
                || KIMI_K2_DOT_MODEL.matcher(model).matches()
                || KIMI_K2P_MODEL.matcher(model).matches()
                || KIMI_K2_5_MODEL.matcher(model).matches();
    }

    /** Normalize a model name for matching: null/blank to null, else lower-case with trimmed. */
    private static String norm(final String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        return model.trim().toLowerCase(Locale.ROOT);
    }
}
