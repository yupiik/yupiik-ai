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
package io.yupiik.ia.agent.core.shell.slash;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.MetricsConfiguration;
import io.yupiik.ia.agent.core.configuration.MetricsDisplayMode;
import io.yupiik.ia.agent.core.configuration.ModelVariantConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.configuration.SecretRedactionMode;
import io.yupiik.ia.agent.core.llm.ApiFlavor;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatRoutes;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.CodexLoginService;
import io.yupiik.ia.agent.core.security.SecretRedactor;
import io.yupiik.ia.agent.core.security.SecretSessionModes;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.shell.DiffViewMode;
import io.yupiik.ia.agent.core.shell.MetricsFormat;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ToolOutputVisibility;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Configuration commands shared by every shell: mode, model, provider, agent,
 * permissions, rendering toggles and metrics.
 */
@ApplicationScoped
public class ConfigCommands implements SlashCommandSet {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    @Override
    public String title() {
        return "Configuration commands";
    }

    private final AgentSessionService agentSessionService;
    private final ChatClient chatClient;
    private final ProviderManager providerManager;
    private final AgentProvider agentProvider;

    /** /permissions accepted values: the permission-mode enum plus the toggle extras. */
    private static final List<String> PERMISSION_OPTIONS = permissionOptions();

    /** /secrets accepted values. */
    private static final List<String> SECRET_OPTIONS = List.of("redact", "passthrough", "fail", "show");

    private static List<String> permissionOptions() {
        final var out = new ArrayList<String>(PermissionMode.VALUES);
        out.add("none");
        out.add("show");
        return List.copyOf(out);
    }

    public ConfigCommands(
            final AgentSessionService agentSessionService,
            final ChatClient chatClient,
            final ProviderManager providerManager,
            final AgentProvider agentProvider,
            final SecretSessionModes secretModes,
            final SecretRedactor secretRedactor,
            final CodexLoginService codexLoginService) {
        this.agentSessionService = agentSessionService;
        this.chatClient = chatClient;
        this.providerManager = providerManager;
        this.agentProvider = agentProvider;
        this.secretModes = secretModes;
        this.secretRedactor = secretRedactor;
        this.codexLoginService = codexLoginService;
    }

    private final SecretSessionModes secretModes;
    private final SecretRedactor secretRedactor;
    private final CodexLoginService codexLoginService;

    @Override
    public List<SlashCommand> commands() {
        return List.of(
                SlashCommand.of(
                        new SlashCommandSpec("/mode", "plan|build", "switch the agent working mode", SlashGroup.CONFIG),
                        List.of("build", "plan"),
                        SlashAction.sync(this::mode)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/model",
                                "[name] [variant] [temperature] [topK] [topP] | [temp=..] [top_k=..] [top_p=..]",
                                "show or switch the model, optionally a named variant and in-memory sampling overrides",
                                SlashGroup.CONFIG),
                        (arg, token, env) -> {
                            if (arg == null || arg.isBlank()) {
                                return ArgCompletion.fuzzyResult("model", env.models(), token);
                            }
                            return ArgCompletion.fuzzyResult(
                                    "variant", env.variants(WHITESPACE.split(arg.strip())[0]), token);
                        },
                        this::model),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/variant",
                                "[name]",
                                "show the current model's variants or switch the variant",
                                SlashGroup.CONFIG),
                        (arg, token, env) -> ArgCompletion.fuzzyResult("variant", env.variants(), token),
                        this::variant),
                SlashCommand.of(
                        new SlashCommandSpec("/provider", "[id]", "show or switch the provider", SlashGroup.CONFIG),
                        ArgCompletion.from("provider", ArgCompletion.Env::providers),
                        this::provider),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/agent", "[name]", "show or set the active custom agent", SlashGroup.CONFIG),
                        ArgCompletion.from("agent", ArgCompletion.Env::agents),
                        this::agent),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/permissions",
                                String.join("|", PermissionMode.VALUES) + "|all|none|show",
                                "allow all tools, ask for approval, or show current (session-scoped) settings",
                                SlashGroup.CONFIG),
                        PERMISSION_OPTIONS,
                        SlashAction.sync(this::permissions)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/secrets",
                                String.join("|", SECRET_OPTIONS),
                                "redact secrets outbound / restore inbound, passthrough (send raw, per-session), "
                                        + "fail (block if a secret would ship), or show the current state",
                                SlashGroup.CONFIG),
                        SECRET_OPTIONS,
                        SlashAction.sync(this::secrets)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/thinking",
                                "show|hide",
                                "show or hide the reasoning/thinking stream",
                                SlashGroup.CONFIG),
                        List.of("show", "hide"),
                        SlashAction.sync(ConfigCommands::thinking)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/tool-output",
                                "show|collapse|hide",
                                "show, collapse to a summary line, or hide tool invocations and output",
                                SlashGroup.CONFIG),
                        List.of("show", "collapse", "hide"),
                        SlashAction.sync(ConfigCommands::toolOutput)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/diff-view",
                                "[auto|side-by-side|simple]",
                                "show or select the diff layout",
                                SlashGroup.CONFIG),
                        List.of("auto", "side-by-side", "simple"),
                        SlashAction.sync(ConfigCommands::diffView)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/metrics",
                                "[show|hide|compact|full|session on|off]",
                                "show or hide the metrics display, switch its density, or report the current values",
                                SlashGroup.CONFIG),
                        (arg, token, env) -> {
                            if ("session".equals(ArgCompletion.previousToken(arg))) {
                                return ArgCompletion.fuzzyResult("option", List.of("on", "off"), token);
                            }
                            return ArgCompletion.fuzzyResult(
                                    "option", List.of("show", "hide", "compact", "full", "session"), token);
                        },
                        SlashAction.sync(this::metrics)));
    }

    private ShellOutcome mode(final String arg, final ShellEnv env) {
        final var mode = AgentMode.fromConfigurationString(arg);
        if (env.session().busy()) {
            // deferred: applied by TurnRunner when the current turn ends, so the running turn keeps
            // its mode and permissions intact
            env.session().pendingMode(mode.name().toLowerCase(Locale.ROOT));
            return ShellOutcome.status("mode switch to " + mode.name().toLowerCase(Locale.ROOT)
                    + " queued - applied when the current turn ends");
        }
        env.session().pendingMode(null); // an explicit idle switch clears any queued one
        final var result = agentSessionService.setMode(
                arg, env.session().sessionId(), env.session().runtime());
        return ShellOutcome.status(result.message());
    }

    private CompletionStage<ShellOutcome> model(final String arg, final ShellEnv env) {
        if (arg.isBlank()) {
            return modelWizard(env);
        }
        return CompletableFuture.completedStage(applyModelArg(arg, env));
    }

    private CompletionStage<ShellOutcome> modelWizard(final ShellEnv env) {
        final List<String> ids;
        try {
            ids = chatClient.models(env.session().chatSettings()).ids();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.completedStage(ShellOutcome.error("cannot list models: " + e.getMessage()));
        } catch (final IOException | RuntimeException e) {
            return CompletableFuture.completedStage(ShellOutcome.error("cannot list models: " + e.getMessage()));
        }
        return env.io().pick("model", ids).thenCompose(selected -> {
            if (selected.isEmpty()) {
                return CompletableFuture.completedStage(ShellOutcome.status(
                        modelStatus(chatClient.model(env.session().chatSettings()), env)));
            }
            return pickVariant(env, selected.get());
        });
    }

    /**
     * Ask for the variant (or the implicit {@code default}) once a model has been chosen. The
     * "default" entry clears any forced variant and resets the in-memory sampling overrides so the
     * implicit tuner defaults are proposed again. The variants are resolved for the chosen model so
     * the list reflects it rather than the still-active model; when the model exposes no variant
     * beyond the implicit default (one option or fewer) the step is skipped entirely.
     */
    private CompletionStage<ShellOutcome> pickVariant(final ShellEnv env, final String model) {
        final var settings = env.session().chatSettings();
        final var options = new ArrayList<String>();
        options.add("default");
        options.addAll(
                chatClient.variants(settings, model).stream().map(v -> v.name()).toList());
        var updated = agentSessionService.setModel(model, settings);
        env.session().runtime().updateChatSettings(updated, env.io().settingsUpdateCancellation());
        if (options.size() <= 1) {
            return CompletableFuture.completedStage(ShellOutcome.status(modelStatus(model, env)));
        }
        return env.io().pick("variant", options).thenApply(selected -> {
            if (selected.isEmpty()) {
                return ShellOutcome.status("model wizard cancelled");
            }
            final var finalUpdated = "default".equals(selected.get())
                    ? updated.withVariant(null).withSampling(null)
                    : updated.withVariant(selected.get()).withSampling(null);
            env.session().runtime().updateChatSettings(finalUpdated, env.io().settingsUpdateCancellation());
            return ShellOutcome.status(modelStatus(model, env));
        });
    }

    /**
     * Apply the non-interactive form: {@code /model [model] [variant] [temperature] [topK] [topP]} or a
     * mix with {@code key=value} overrides ({@code temp} / {@code top_k} / {@code top_p}). Positional
     * arguments follow a fixed order once the first sampling value is given; a token containing {@code =}
     * is always treated as a key/value pair. The model and variant are optional (falling back to the
     * current ones); a sampling value may be a decimal or {@code none}/{@code -}/{@code null} to force-omit
     * the field from the payload and bypass the variant and implicit tuner defaults.
     */
    private ShellOutcome applyModelArg(final String arg, final ShellEnv env) {
        final var settings = env.session().chatSettings();
        final var upserts = new LinkedHashMap<String, String>();
        final var positional = new ArrayList<String>();
        for (final var token : WHITESPACE.split(arg.strip())) {
            final var eq = token.indexOf('=');
            if (eq > 0) {
                upserts.put(token.substring(0, eq).toLowerCase(Locale.ROOT), token.substring(eq + 1));
            } else {
                positional.add(token);
            }
        }
        final var model = positional.isEmpty() ? chatClient.model(settings) : positional.get(0);
        final String variant;
        if (positional.size() > 1) {
            variant = positional.get(1);
        } else if (upserts.isEmpty()) {
            variant = null;
        } else {
            variant = chatClient.variant(settings);
        }
        // positional order is fixed: model, variant, temperature, topK, topP
        if (positional.size() >= 3) {
            upserts.putIfAbsent("temp", positional.get(2));
        }
        if (positional.size() >= 4) {
            upserts.putIfAbsent("top_k", positional.get(3));
        }
        if (positional.size() >= 5) {
            upserts.putIfAbsent("top_p", positional.get(4));
        }
        env.session()
                .runtime()
                .updateChatSettings(
                        agentSessionService
                                .setModel(model, settings)
                                .withVariant(variant)
                                .withSampling(upserts.isEmpty() ? null : parseSampling(upserts)),
                        env.io().settingsUpdateCancellation());
        return ShellOutcome.status(modelStatus(model, env));
    }

    /**
     * Switch the variant of the current model only - the model keeps its active
     * selection, so unlike {@code /model} the picker (or the non-interactive
     * argument) never asks for a model. Contextual to the session's current model:
     * the available options are its variants plus the implicit {@code default}.
     */
    private CompletionStage<ShellOutcome> variant(final String arg, final ShellEnv env) {
        if (arg.isBlank()) {
            return variantPicker(env);
        }
        return CompletableFuture.completedStage(applyVariantArg(arg, env));
    }

    /**
     * Ask for the variant (or the implicit {@code default}) of the current model. The
     * "default" entry clears any forced variant and resets the in-memory sampling
     * overrides so the implicit tuner defaults are proposed again; a named variant
     * clears the sampling overrides so the variant's own tuning governs. When the
     * model exposes no variant beyond the implicit default (one option or fewer) the
     * step is skipped and just the current status is reported.
     */
    private CompletionStage<ShellOutcome> variantPicker(final ShellEnv env) {
        final var settings = env.session().chatSettings();
        final var model = chatClient.model(settings);
        final var options = new ArrayList<String>();
        options.add("default");
        options.addAll(
                chatClient.variants(settings, model).stream().map(v -> v.name()).toList());
        if (options.size() <= 1) {
            return CompletableFuture.completedStage(ShellOutcome.status(modelStatus(model, env)));
        }
        return env.io().pick("variant", options).thenApply(selected -> {
            if (selected.isEmpty()) {
                return ShellOutcome.status("variant picker cancelled");
            }
            final var updated = "default".equals(selected.get())
                    ? settings.withVariant(null).withSampling(null)
                    : settings.withVariant(selected.get()).withSampling(null);
            env.session().runtime().updateChatSettings(updated, env.io().settingsUpdateCancellation());
            return ShellOutcome.status(modelStatus(model, env));
        });
    }

    /**
     * Apply the non-interactive form: {@code /variant [name]} or {@code /variant default}.
     * {@code default} (or a blank/absent name) clears any forced variant and the
     * in-memory sampling overrides so the implicit tuner defaults apply; any other
     * name selects that variant of the current model and clears the sampling
     * overrides so the variant's own tuning governs.
     */
    private ShellOutcome applyVariantArg(final String arg, final ShellEnv env) {
        final var settings = env.session().chatSettings();
        final var model = chatClient.model(settings);
        final var name = arg.strip();
        final var updated = "default".equalsIgnoreCase(name)
                ? settings.withVariant(null).withSampling(null)
                : settings.withVariant(name).withSampling(null);
        env.session().runtime().updateChatSettings(updated, env.io().settingsUpdateCancellation());
        return ShellOutcome.status(modelStatus(model, env));
    }

    private ChatClient.SamplingOverride parseSampling(final Map<String, String> upserts) {
        if (upserts.isEmpty()) {
            return null;
        }
        final var values = new HashMap<ChatClient.SamplingField, Optional<Double>>();
        putSampling(values, ChatClient.SamplingField.TEMPERATURE, upserts.get("temp"), upserts.get("temperature"));
        putSampling(values, ChatClient.SamplingField.TOP_K, upserts.get("top_k"));
        putSampling(values, ChatClient.SamplingField.TOP_P, upserts.get("top_p"));
        return ChatClient.SamplingOverride.of(values);
    }

    private void putSampling(
            final Map<ChatClient.SamplingField, Optional<Double>> into,
            final ChatClient.SamplingField field,
            final String... raw) {
        final var value = firstNonBlank(raw);
        if (value == null) {
            return;
        }
        if (isNone(value)) {
            into.put(field, Optional.empty());
            return;
        }
        try {
            into.put(field, Optional.of(Double.valueOf(value)));
        } catch (final NumberFormatException e) {
            into.put(field, Optional.empty());
        }
    }

    private String firstNonBlank(final String... values) {
        for (final var value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private boolean isNone(final String value) {
        return "none".equalsIgnoreCase(value) || "null".equalsIgnoreCase(value) || "-".equals(value);
    }

    /** Render the model status line: active model, sampling overrides, implicit default variant and explicit variants. */
    private String modelStatus(final String model, final ShellEnv env) {
        final var settings = env.session().chatSettings();
        final var sb = new StringBuilder("model: ").append(model);
        final var sampling = chatClient.sampling(settings);
        if (sampling != null && !sampling.isEmpty()) {
            sb.append(" [sampling ");
            final var fields = new ArrayList<String>();
            appendOverride(sampling, fields, ChatClient.SamplingField.TEMPERATURE, "temp");
            appendOverride(sampling, fields, ChatClient.SamplingField.TOP_K, "top_k");
            appendOverride(sampling, fields, ChatClient.SamplingField.TOP_P, "top_p");
            sb.append(String.join(", ", fields));
            sb.append(']');
        }
        final var resolved = chatClient.resolvedVariant(settings);
        sb.append(" [variant ").append(resolved == null ? "default" : resolved).append(']');
        final var implicit = chatClient.implicitVariant(settings);
        if (implicit != null) {
            sb.append(" - default");
            final var fields = new ArrayList<String>();
            if (implicit.temperature() != null) {
                fields.add("temp=" + implicit.temperature());
            }
            if (implicit.topK() != null) {
                fields.add("top_k=" + implicit.topK());
            }
            if (implicit.topP() != null) {
                fields.add("top_p=" + implicit.topP());
            }
            if (!fields.isEmpty()) {
                sb.append("(").append(String.join(", ", fields)).append(')');
            }
        }
        final var variants = chatClient.variants(settings);
        final var activeVariant = resolved == null ? "default" : resolved;
        if (!variants.isEmpty()) {
            sb.append(" - variants: ");
            final var names = new ArrayList<String>();
            names.add("default");
            for (final var v : variants) {
                names.add(appendVariantFields(v));
            }
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                final var name = names.get(i);
                if (activeVariant.equals(name)) {
                    sb.append("**").append(name).append("**");
                } else {
                    sb.append(name);
                }
            }
        }
        return sb.toString();
    }

    private String appendVariantFields(final ModelVariantConfiguration v) {
        if (v.temperature() == null && v.topP() == null && v.topK() == null) {
            return v.name();
        }
        final var fields = new ArrayList<String>();
        if (v.temperature() != null) {
            fields.add("temp=" + v.temperature());
        }
        if (v.topP() != null) {
            fields.add("top_p=" + v.topP());
        }
        if (v.topK() != null) {
            fields.add("top_k=" + v.topK());
        }
        return v.name() + "(" + String.join(", ", fields) + ")";
    }

    private void appendOverride(
            final ChatClient.SamplingOverride sampling,
            final List<String> fields,
            final ChatClient.SamplingField field,
            final String label) {
        final var value = sampling.get(field);
        if (value == null) {
            return;
        }
        fields.add(value.isPresent() ? label + "=" + value.get() : label + "=none");
    }

    private CompletionStage<ShellOutcome> provider(final String arg, final ShellEnv env) {
        final var settings = env.session().chatSettings();
        if (!arg.isBlank()) {
            final var updated = agentSessionService.setProvider(arg, settings);
            env.session().runtime().updateChatSettings(updated, env.io().settingsUpdateCancellation());
            // an explicit /provider on an already-active provider is a no-op for updateChatSettings
            // (no provider change, hence no quota push): force the live attach + push so the TUI/web
            // gauge still shows usage immediately, also before the first turn
            if (sameProvider(settings, updated)) {
                env.session().runtime().refreshUsage();
            }
            final var effective = updated.providerId() == null
                    ? (agentSessionService.lastProviderId() == null ? arg : agentSessionService.lastProviderId())
                    : updated.providerId();
            return CompletableFuture.completedStage(autoLoginCodex(effective, env));
        }
        final var ids = providerManager.providers().stream().map(p -> p.id()).toList();
        return env.io().pick("provider", ids).thenApply(selected -> {
            if (selected.isEmpty()) {
                return ShellOutcome.status("provider picker cancelled");
            }
            final var updated = agentSessionService.setProvider(selected.get(), settings);
            env.session().runtime().updateChatSettings(updated, env.io().settingsUpdateCancellation());
            if (sameProvider(settings, updated)) {
                env.session().runtime().refreshUsage();
            }
            final var effective = updated.providerId() == null ? selected.get() : updated.providerId();
            return autoLoginCodex(effective, env);
        });
    }

    /** True when the effective provider id is identical between two selections (blank = process default). */
    private static boolean sameProvider(final ChatSettings before, final ChatSettings after) {
        return normalizeProvider(before == null ? null : before.providerId())
                .equals(normalizeProvider(after == null ? null : after.providerId()));
    }

    private static String normalizeProvider(final String id) {
        return id == null || id.isBlank() ? "" : id.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * A codex-flavored provider needs an OAuth session before its models can be listed or used. When
     * switching to one that has no cached session, start the interactive browser login (surfacing the
     * authorize link through the frontend) then retry the model list, so the switch to a codex
     * provider auto-authenticates instead of failing the model fetch.
     */
    private ShellOutcome autoLoginCodex(final String effectiveProvider, final ShellEnv env) {
        final var provider = providerManager.provider(effectiveProvider);
        final boolean codex = provider != null
                && (provider.api() == ApiFlavor.CODEX
                        || ChatRoutes.detectFlavor(provider.baseUrl()) == ApiFlavor.CODEX);
        if (!codex || codexLoginService == null || codexLoginService.load(effectiveProvider) != null) {
            return ShellOutcome.status("provider: " + effectiveProvider);
        }
        try {
            codexLoginService.signIn(
                    effectiveProvider,
                    false,
                    url -> {
                        env.frontend().openExternalUrl(url);
                        env.frontend()
                                .transcript("Sign in to use the Codex provider '" + effectiveProvider
                                        + "': authorize at\n\n<" + url + ">\n\nWaiting for authorization...");
                    },
                    _ -> false);
            return ShellOutcome.status("provider: " + effectiveProvider + " (Codex signed in)");
        } catch (final RuntimeException e) {
            return ShellOutcome.error("provider: " + effectiveProvider + " - Codex login failed: " + e.getMessage());
        }
    }

    private CompletionStage<ShellOutcome> agent(final String arg, final ShellEnv env) {
        if (!arg.isBlank()) {
            return CompletableFuture.completedStage(setAgent(arg, env));
        }
        final var names = agentProvider.agents().stream()
                .map(AgentConfiguration::name)
                .sorted()
                .toList();
        return env.io()
                .pick("agent", names)
                .thenApply(selected -> selected.isEmpty()
                        ? ShellOutcome.status("agent picker cancelled")
                        : setAgent(selected.get(), env));
    }

    private ShellOutcome setAgent(final String arg, final ShellEnv env) {
        final var name = agentSessionService.setAgent(arg);
        if (name == null) {
            return ShellOutcome.error("unknown agent: " + arg);
        }
        env.session().activeAgent(name);
        return ShellOutcome.status("agent: " + name);
    }

    private ShellOutcome permissions(final String arg, final ShellEnv env) {
        final var value = arg == null ? "" : arg.strip();
        if (value.equals("show") || value.equals("list")) {
            final var info = agentSessionService.permissionsInfo(env.session().runtime());
            final var sb = new StringBuilder("**permissions status (session-scoped)**\n\n");
            sb.append("- agent mode: ")
                    .append(info.agentMode().name().toLowerCase(Locale.ROOT))
                    .append(", permission mode: ")
                    .append(
                            info.permissionMode() == null
                                    ? "?"
                                    : info.permissionMode().toString().toLowerCase(Locale.ROOT))
                    .append('\n');
            if (info.sessionGrants().isEmpty()) {
                sb.append("- session grants: none (use 'a' in the approval prompt to add one; cleared on mode->plan)");
            } else {
                sb.append("- session grants (in-memory, cleared on mode->plan):");
                for (final var grant : info.sessionGrants()) {
                    sb.append("\n  - ").append(grant);
                }
            }
            return ShellOutcome.transcript(sb.toString());
        }
        final var mode = agentSessionService.setPermissions(value, env.session().runtime());
        if (mode == null) {
            return ShellOutcome.status("invalid /permissions value '" + value + "'; expected one of: "
                    + String.join("|", PermissionMode.VALUES) + "|none|show");
        }
        return ShellOutcome.status(
                mode == PermissionMode.ASK ? "permissions: asking for approval" : "permissions: " + mode.name());
    }

    private ShellOutcome secrets(final String arg, final ShellEnv env) {
        final var value = arg == null ? "" : arg.strip().toLowerCase(Locale.ROOT);
        final var sessionId = env.session().sessionId();
        if (value.equals("show")) {
            final var mode = secretRedactor.modeFor(sessionId);
            final var svc = new StringBuilder("**secrets status**\n\n");
            svc.append("- effective mode: ")
                    .append(mode == null ? "disabled" : mode.name().toLowerCase(Locale.ROOT));
            final var cfg = secretRedactor.registry() == null
                    ? null
                    : secretRedactor.registry().config();
            if (cfg != null) {
                svc.append(" (global default: ")
                        .append(cfg.mode().name().toLowerCase(Locale.ROOT))
                        .append(')');
            }
            svc.append('\n');
            final var harvested = secretRedactor.registry() == null
                    ? 0
                    : secretRedactor.registry().harvestedValues().size();
            final var matchers = secretRedactor.registry() == null
                    ? 0
                    : secretRedactor.registry().matchers().size();
            svc.append("- harvested local secret values: ").append(harvested).append('\n');
            svc.append("- secret matchers (built-in + configured + extensions): ")
                    .append(matchers)
                    .append('\n');
            final var vault = secretRedactor.registry() == null
                    ? null
                    : secretRedactor.registry().vault();
            if (vault != null) {
                svc.append("- provenance entries (emitted+restorable this process): ")
                        .append(vault.count(sessionId))
                        .append('\n');
            }
            return ShellOutcome.transcript(svc.toString());
        }
        if (value.isEmpty()) {
            return ShellOutcome.status("usage: /secrets " + String.join("|", SECRET_OPTIONS)
                    + " ; current effective mode: "
                    + (secretRedactor.modeFor(sessionId) == null
                            ? "disabled"
                            : secretRedactor.modeFor(sessionId).name().toLowerCase(Locale.ROOT)));
        }
        final var mode = SecretRedactionMode.fromConfigurationString(value);
        final var prev = secretModes.set(sessionId, mode);
        return ShellOutcome.status("secrets: " + mode.name().toLowerCase(Locale.ROOT)
                + (prev == null ? " (session override)" : " (was " + prev.name().toLowerCase(Locale.ROOT) + ")"));
    }

    private static ShellOutcome thinking(final String arg, final ShellEnv env) {
        return toggle(
                env,
                arg,
                "thinking",
                value -> {
                    env.session().showThinking(value);
                    env.frontend().showThinking(value);
                },
                env.session().showThinking());
    }

    private static ShellOutcome toolOutput(final String arg, final ShellEnv env) {
        final var current = env.session().showToolOutput();
        if (arg == null || arg.isBlank()) {
            return ShellOutcome.status("tool output is " + current.name().toLowerCase(Locale.ROOT)
                    + " - usage: /tool-output show|collapse|hide");
        }
        final var value = ToolOutputVisibility.parse(arg.strip());
        if (value == null) {
            return ShellOutcome.status("tool output is " + current.name().toLowerCase(Locale.ROOT)
                    + " - usage: /tool-output show|collapse|hide");
        }
        env.session().showToolOutput(value);
        env.frontend().showToolOutput(value);
        return ShellOutcome.status("tool output " + value.name().toLowerCase(Locale.ROOT));
    }

    private static ShellOutcome diffView(final String arg, final ShellEnv env) {
        final var mode = DiffViewMode.parse(arg);
        if (mode == null) {
            return ShellOutcome.status("diff view: "
                    + env.session().diffViewMode().value() + " - usage: /diff-view auto|side-by-side|simple");
        }
        env.session().diffViewMode(mode);
        env.frontend().diffViewMode(mode);
        return ShellOutcome.status("diff view: " + mode.value());
    }

    private static ShellOutcome toggle(
            final ShellEnv env,
            final String arg,
            final String label,
            final Consumer<Boolean> setter,
            final boolean current) {
        final var parsed = parseShowHide(arg);
        if (parsed == null) {
            return ShellOutcome.status(label + " " + (current ? "shown" : "hidden") + " - usage: /"
                    + label.replace(" ", "-") + " show|hide");
        }
        setter.accept(parsed);
        return ShellOutcome.status(label + " " + (parsed ? "shown" : "hidden"));
    }

    private static Boolean parseShowHide(final String arg) {
        final var value = arg.strip().toLowerCase(Locale.ROOT);
        if (value.equals("show")) {
            return true;
        }
        if (value.equals("hide")) {
            return false;
        }
        return null;
    }

    private ShellOutcome metrics(final String arg, final ShellEnv env) {
        final var value = arg == null ? "" : arg.strip().toLowerCase(Locale.ROOT);
        final var metrics = env.session().metrics();
        if (value.isBlank()) {
            final var runtime = env.session().runtime();
            return ShellOutcome.status(
                    MetricsFormat.report(metrics, env.transcript(), runtime == null ? null : runtime.usage()));
        }
        final var parts = WHITESPACE.split(value, 2);
        var updated = metrics;
        switch (parts[0]) {
            case "show" -> updated = new MetricsConfiguration(true, true, metrics.displayMode());
            case "hide" -> updated = new MetricsConfiguration(false, false, metrics.displayMode());
            case "compact" ->
                updated = new MetricsConfiguration(
                        metrics.showPerTurn(), metrics.showSessionTotals(), MetricsDisplayMode.COMPACT);
            case "full" ->
                updated = new MetricsConfiguration(
                        metrics.showPerTurn(), metrics.showSessionTotals(), MetricsDisplayMode.FULL);
            case "session" -> {
                if (parts.length > 1) {
                    final var sessionArg = parts[1].strip().toLowerCase(Locale.ROOT);
                    if (!"on".equals(sessionArg) && !"off".equals(sessionArg)) {
                        return ShellOutcome.status("usage: /metrics session on|off");
                    }
                    updated = new MetricsConfiguration(
                            metrics.showPerTurn(), "on".equals(sessionArg), metrics.displayMode());
                } else {
                    return ShellOutcome.status("usage: /metrics session on|off");
                }
            }
            default -> {
                return ShellOutcome.status("usage: /metrics [show|hide|compact|full|session on|off]");
            }
        }
        env.session().metrics(updated);
        return ShellOutcome.status("metrics updated");
    }
}
