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
package io.yupiik.ia.agent.acp;

import io.yupiik.ai.acp.protocol.*;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.ModelsResponse;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.session.SessionMetadata;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Session-local ACP selectors. Descriptors never perform network I/O or mutate the process defaults. */
@DefaultScoped
public class AcpSessionOptions {
    public static final class InvalidStoredSelectionException extends IllegalArgumentException {
        public InvalidStoredSelectionException() {
            super("Stored session preset is invalid or its configured provider, variant or agent is unavailable");
        }
    }

    private final ChatClient chat;
    private final ProviderManager providers;
    private final AgentSessionService sessions;

    public AcpSessionOptions(
            final ChatClient chat, final ProviderManager providers, final AgentSessionService sessions) {
        this.chat = chat;
        this.providers = providers;
        this.sessions = sessions;
    }

    public String providerId(final ChatSettings settings) {
        final var provider = chat.provider(settings);
        return provider.id() == null || provider.id().isBlank() ? "default" : provider.id();
    }

    public CompletableFuture<ModelsResponse> discover(final ChatSettings settings) {
        return chat.modelsAsync(settings);
    }

    public ChatSettings pin(final ChatSettings settings) {
        return new ChatSettings(providerId(settings), settings.model(), settings.variant(), settings.sampling());
    }

    public record Setup(Path cwd, List<Path> additionalDirectories, SessionMetadata.Instructions systemPrompt) {}

    /** Strict raw shapes are checked before decoding can coerce values or discard unsupported content. */
    public Setup setup(final Object params, final SessionMetadata retained) {
        if (!(params instanceof Map<?, ?> fields) || !(fields.get("cwd") instanceof String cwd)) {
            throw new IllegalArgumentException("Invalid session setup");
        }
        final var root = directory(cwd);
        final var roots = new LinkedHashSet<Path>();
        if (fields.get("additionalDirectories") != null) {
            if (!(fields.get("additionalDirectories") instanceof List<?> directories) || directories.size() > 32) {
                throw new IllegalArgumentException("Invalid additional directories");
            }
            for (final var value : directories) {
                if (!(value instanceof String path)) {
                    throw new IllegalArgumentException("Invalid additional directory");
                }
                final var normalized = directory(path);
                if (!normalized.equals(root)) {
                    roots.add(normalized);
                }
            }
        }
        // Stable v1 never implicitly restores roots. Only instructions may be retained when omitted/null.
        var prompt = retained == null ? null : retained.systemPrompt();
        if (fields.get("systemPrompt") != null) {
            if (!(fields.get("systemPrompt") instanceof Map<?, ?> supplied)
                    || !(supplied.get("mode") instanceof String mode)
                    || !(supplied.get("content") instanceof List<?> blocks)
                    || blocks.isEmpty()
                    || blocks.size() > 64
                    || (supplied.get("_meta") != null && !(supplied.get("_meta") instanceof Map<?, ?>))) {
                throw new IllegalArgumentException("Invalid system prompt");
            }
            final var text = new ArrayList<String>();
            for (final var block : blocks) {
                if (!(block instanceof Map<?, ?> content)
                        || !"text".equals(content.get("type"))
                        || !(content.get("text") instanceof String value)
                        || (content.get("_meta") != null && !(content.get("_meta") instanceof Map<?, ?>))
                        || (content.get("annotations") != null && !(content.get("annotations") instanceof Map<?, ?>))
                        || content.keySet().stream()
                                .anyMatch(key -> !List.of("type", "text", "annotations", "_meta")
                                        .contains(key))) {
                    throw new IllegalArgumentException("Expected flat system prompt text blocks");
                }
                text.add(value);
            }
            prompt = new SessionMetadata.Instructions(mode, text);
        }
        return new Setup(root, List.copyOf(roots), prompt);
    }

    private Path directory(final String value) {
        if (value.length() > 4096) {
            throw new IllegalArgumentException("Invalid directory");
        }
        final var path = Path.of(value).normalize();
        if (!path.isAbsolute() || !Files.isDirectory(path)) {
            throw new IllegalArgumentException("Expected an existing absolute directory");
        }
        return path;
    }

    /** Snapshot actual settled selections, never the last history identity or requested hook input. */
    public SessionMetadata snapshot(
            final SessionRuntime runtime, final String agent, final SessionMetadata.Instructions prompt) {
        final var settings = runtime.chatSettings();
        final var sampling = settings.sampling() == null
                ? List.<SessionMetadata.Sampling>of()
                : settings.sampling().values().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> new SessionMetadata.Sampling(
                                entry.getKey().name(), entry.getValue().orElse(null)))
                        .toList();
        return new SessionMetadata(
                1,
                providerId(settings),
                chat.model(settings),
                chat.resolvedVariant(settings),
                sampling,
                agent,
                mode(runtime),
                prompt);
    }

    /** Offline validation: provider/agent/variant references must still exist. Concrete model IDs need not be public. */
    public ChatSettings restore(final SessionMetadata preset) {
        if (preset.version() != 1
                || preset.provider() == null
                || providers.providers().stream()
                        .noneMatch(provider -> Objects.equals(
                                provider.id() == null || provider.id().isBlank() ? "default" : provider.id(),
                                preset.provider()))
                || (!"plan".equals(preset.mode()) && !"build".equals(preset.mode()))
                || (preset.model() != null
                        && (preset.model().length() > 1024 || preset.model().indexOf('\0') >= 0))
                || (preset.agent() != null && sessions.setAgent(preset.agent()) == null)) {
            throw new IllegalArgumentException("Stored session selection is unavailable");
        }
        final var sampling = new EnumMap<ChatClient.SamplingField, Optional<Double>>(ChatClient.SamplingField.class);
        for (final var field : preset.sampling()) {
            final ChatClient.SamplingField key;
            try {
                key = ChatClient.SamplingField.valueOf(field.field());
            } catch (final RuntimeException invalid) {
                throw new IllegalArgumentException("Invalid stored sampling selection");
            }
            final var value = field.value();
            if (sampling.containsKey(key)
                    || (value != null
                            && (!Double.isFinite(value)
                                    || value < 0
                                    || (key == ChatClient.SamplingField.TOP_P && value > 1)
                                    || (key == ChatClient.SamplingField.TOP_K && value != Math.rint(value))))) {
                throw new IllegalArgumentException("Invalid stored sampling selection");
            }
            sampling.put(key, Optional.ofNullable(value));
        }
        final var settings = new ChatSettings(
                preset.provider(),
                preset.model(),
                preset.variant(),
                sampling.isEmpty() ? null : new ChatClient.SamplingOverride(Map.copyOf(sampling)));
        if (settings.variant() != null
                && chat.variants(settings).stream()
                        .noneMatch(v -> settings.variant().equals(v.name()))) {
            throw new IllegalArgumentException("Stored session variant is unavailable");
        }
        return settings;
    }

    /** Only configured full windows; never performs model discovery on the event/protocol path. */
    public Long contextWindow(final ChatSettings settings) {
        final var provider = chat.provider(settings);
        final var override = providers.contextWindowOverride(provider, chat.model(settings));
        final var size = override > 0 ? override : provider.contextWindow();
        return size > 0 ? (long) size : null;
    }

    public List<ConfigOption> describe(final SessionRuntime runtime, final List<String> discovered) {
        return describe(runtime.chatSettings(), mode(runtime), discovered);
    }

    public List<ConfigOption> describe(final ChatSettings settings, final String mode, final List<String> discovered) {
        final var providerValues = providers.providers().stream()
                .map(provider -> value(provider.id() == null || provider.id().isBlank() ? "default" : provider.id()))
                .distinct()
                .toList();
        final var models = new LinkedHashMap<String, ConfigValue>();
        add(models, chat.provider(settings).model());
        add(models, chat.model(settings));
        if (chat.model(settings).isBlank()) {
            models.put("", new ConfigValue("", "Default (not configured)", "Configure a chat model to send prompts"));
        }
        discovered.forEach(id -> add(models, id));
        // ProviderModelConfiguration.model is also a substring matcher, not a list of concrete IDs.
        // Only the actual defaults/current selections and provider-confirmed IDs are safe to advertise.
        final var variants = new LinkedHashMap<String, ConfigValue>();
        variants.put("default", new ConfigValue("default", "Default", "Use the configured model/provider defaults"));
        chat.variants(settings).forEach(variant -> {
            if (variant.name() != null && !variant.name().isBlank() && !"default".equals(variant.name())) {
                variants.putIfAbsent(variant.name(), value(variant.name()));
            }
        });
        if (settings.variant() != null) {
            add(variants, settings.variant());
        }
        return List.of(
                new ConfigOption("provider", "Provider", "_provider", "select", providerId(settings), providerValues),
                new ConfigOption(
                        "model", "Model", "model", "select", chat.model(settings), List.copyOf(models.values())),
                new ConfigOption(
                        "variant",
                        "Variant",
                        "model_config",
                        "select",
                        settings.variant() == null ? "default" : settings.variant(),
                        List.copyOf(variants.values())),
                new ConfigOption(
                        "mode",
                        "Mode",
                        "mode",
                        "select",
                        mode,
                        List.of(
                                new ConfigValue("plan", "Plan", "Read-only planning"),
                                new ConfigValue("build", "Build", "Implement with tool approvals"))));
    }

    /** Validate against precisely the last advertised list, then use normal core hooks and read back actual state. */
    public void apply(
            final String sessionId,
            final SessionRuntime runtime,
            final List<ConfigOption> advertised,
            final String id,
            final Object rawValue) {
        apply(sessionId, runtime, advertised, id, rawValue, null);
    }

    public void apply(
            final String sessionId,
            final SessionRuntime runtime,
            final List<ConfigOption> advertised,
            final String id,
            final Object rawValue,
            final BooleanSupplier cancelled) {
        if (!(rawValue instanceof String selected)) {
            throw new IllegalArgumentException("Expected a select value");
        }
        final var option = advertised.stream()
                .filter(it -> it.id().equals(id))
                .findFirst()
                .orElseThrow(IllegalArgumentException::new);
        if (option.options().stream().noneMatch(it -> it.value().equals(selected))) {
            throw new IllegalArgumentException("Value was not advertised");
        }
        final var current = runtime.chatSettings();
        final var actual =
                switch (id) {
                    case "provider" -> providerId(current);
                    case "model" -> chat.model(current);
                    case "variant" -> current.variant() == null ? "default" : current.variant();
                    case "mode" -> mode(runtime);
                    default -> throw new IllegalArgumentException();
                };
        if (Objects.equals(actual, selected)) {
            if ("mode".equals(id)) {
                runtime.permissions().setPermissionMode(PermissionMode.ASK);
            }
            return; // Do not erase model/variant/sampling overrides on a no-op.
        }
        if ("mode".equals(id)) {
            sessions.setMode(selected, sessionId, runtime);
            runtime.permissions().setPermissionMode(PermissionMode.ASK);
            return;
        }
        final var next =
                switch (id) {
                    // Construct directly: a provider literally called "default" is NOT a slash-command reset.
                    case "provider" -> new ChatSettings(selected, null, null, null);
                    case "model" -> new ChatSettings(current.providerId(), selected, null, null);
                    case "variant" ->
                        new ChatSettings(
                                current.providerId(),
                                current.model(),
                                "default".equals(selected) ? null : selected,
                                null);
                    default -> throw new IllegalArgumentException();
                };
        if (cancelled == null) {
            runtime.updateChatSettings(next);
        } else {
            runtime.updateChatSettings(next, cancelled);
        }
    }

    public String mode(final SessionRuntime runtime) {
        return runtime.permissions().mode() == AgentMode.PLAN ? "plan" : "build";
    }

    private void add(final LinkedHashMap<String, ConfigValue> values, final String id) {
        if (id != null && !id.isBlank()) {
            values.putIfAbsent(id, value(id));
        }
    }

    private ConfigValue value(final String id) {
        return new ConfigValue(id, id, null);
    }
}
