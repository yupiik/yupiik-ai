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
package io.yupiik.ia.agent.documentation;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.Instance;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.FusionModule;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.lifecycle.Start;
import io.yupiik.fusion.framework.api.lifecycle.Stop;
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.ia.agent.core.cli.PromptHandlebarsHelper;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.shell.slash.SlashCommandSet;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

public class CodingAgentDocumentationGenerator implements Runnable {
    private final Path sourceBase;
    private final Map<String, String> configuration;

    public CodingAgentDocumentationGenerator(final Path sourceBase, final Map<String, String> configuration) {
        this.sourceBase = sourceBase;
        this.configuration = configuration == null ? Map.of() : configuration;
    }

    @Override
    public void run() {
        try (final var container = ConfiguringContainer.of()
                .register(new ProvidedInstanceBean<>(
                        DefaultScoped.class, ArgsConfigSource.class, () -> new ArgsConfigSource(List.of())))
                .register(new ProvidedInstanceBean<>(
                        ApplicationScoped.class, ConfigurationSource.class, () -> new ConfigurationSource() {
                            @Override
                            public String get(final String key) {
                                return null;
                            }
                        }))
                .register(
                        new FusionModule() { // disable start/stop events to not launch side effects (McpManager, ...)
                            @Override
                            public BiPredicate<RuntimeContainer, FusionListener<?>> listenerFilter() {
                                return (r, l) -> l.eventType() != Start.class && l.eventType() != Stop.class;
                            }
                        })
                .start()) {
            writeTools(container);
            writeSlashCommands(container);
            writePromptHelpers(container);
        }
    }

    private void writePromptHelpers(final RuntimeContainer container) {
        final var helpers = container
                .lookups(
                        PromptHandlebarsHelper.class,
                        it -> it.stream()
                                .map(Instance::instance)
                                .sorted(Comparator.comparing(PromptHandlebarsHelper::name))
                                .toList())
                .instance();
        if (helpers.isEmpty()) {
            return;
        }
        final var sb = new StringBuilder();
        for (final var helper : helpers) {
            sb.append("`{{").append(helper.name()).append("}}`::\n");
            sb.append("  ")
                    .append(
                            helper.description() == null || helper.description().isBlank()
                                    ? "No description."
                                    : helper.description())
                    .append("\n\n");
        }
        writePartial("prompt-helpers.adoc", sb.toString());
    }

    private void writeTools(final RuntimeContainer container) {
        final var workdir = sourceBase.toAbsolutePath().normalize();
        final var tools = container
                .lookups(
                        EmbeddedTool.class,
                        it -> it.stream()
                                .map(Instance::instance)
                                .sorted(Comparator.comparing(EmbeddedTool::name))
                                .toList())
                .instance();
        final var sb = new StringBuilder();
        for (final var category : Stream.of(ToolCategory.values())
                .sorted(Comparator.comparing(Enum::name))
                .toList()) {
            final var inCategory =
                    tools.stream().filter(t -> t.category() == category).toList();
            if (inCategory.isEmpty()) {
                continue;
            }
            sb.append("== ")
                    .append(capitalize(category.name().toLowerCase(Locale.ROOT)))
                    .append(" tools\n\n");
            for (final var tool : inCategory) {
                sb.append("=== `").append(tool.name()).append("`\n\n");
                final var description = tool.description(workdir);
                sb.append(description == null || description.isBlank() ? "No description." : description)
                        .append("\n\n");
                if (tool.readonly()) {
                    sb.append("NOTE: Read-only tool: it never modifies the working copy.\n\n");
                }
                sb.append("[%collapsible]\n");
                sb.append(".JSON schema\n====\n[source,json]\n----\n")
                        .append(prettyJson(tool.schema()))
                        .append("\n----\n====\n\n");
            }
        }
        writePartial("embedded-tools.adoc", sb.toString());
    }

    private void writeSlashCommands(final RuntimeContainer container) {
        try (final var providersInstance = container.lookups(
                SlashCommandSet.class, it -> it.stream().map(Instance::instance).toList())) {
            final var providers = providersInstance.instance();
            if (providers.isEmpty()) {
                return;
            }
            final var sb = new StringBuilder();
            for (final var set : providers) {
                final var commands = set.commands();
                if (commands.isEmpty()) {
                    continue;
                }
                sb.append("== ").append(set.title()).append("\n\n");
                for (final var group : SlashGroup.values()) {
                    final var inGroup = commands.stream()
                            .filter(c -> c.spec().group() == group)
                            .sorted(Comparator.comparing(c -> c.spec().name()))
                            .toList();
                    if (inGroup.isEmpty()) {
                        continue;
                    }
                    sb.append("=== ")
                            .append(capitalize(group.name().toLowerCase(Locale.ROOT)))
                            .append("\n\n");
                    for (final var command : inGroup) {
                        sb.append("`").append(command.spec().display()).append("`::\n");
                        sb.append("  ").append(command.spec().description()).append("\n\n");
                    }
                }
            }
            writePartial("slash-commands.adoc", sb.toString());
        }
    }

    private String capitalize(final String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private void writePartial(final String fileName, final String content) {
        try {
            final var base = sourceBase.resolve(
                    configuration.getOrDefault("output", "content/coding-agent/_partials/generated"));
            final var target = base.resolve(fileName).normalize();
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, content.endsWith("\n") ? content : content + "\n");
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String prettyJson(final Map<String, Object> value) {
        final var sb = new StringBuilder();
        append(sb, value, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void append(final StringBuilder sb, final Object value, final int indent) {
        if (value instanceof Map<?, ?> map) {
            sb.append("{\n");
            final var entries = new LinkedHashMap<String, Object>((Map<String, Object>) map);
            final var flat =
                    entries.entrySet().stream().map(entry -> entry.getKey()).toList();
            for (final var key : flat) {
                pad(sb, indent + 1).append('"').append(key).append("\": ");
                append(sb, entries.get(key), indent + 1);
                sb.append(",\n");
            }
            if (!entries.isEmpty()) {
                sb.setLength(sb.length() - 2); // drop trailing ",\n"
            }
            sb.append('\n');
            pad(sb, indent);
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append("[\n");
            for (final var item : list) {
                pad(sb, indent + 1);
                append(sb, item, indent + 1);
                sb.append(",\n");
            }
            if (!list.isEmpty()) {
                sb.setLength(sb.length() - 2);
            }
            sb.append('\n');
            pad(sb, indent);
            sb.append(']');
        } else if (value == null) {
            sb.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            sb.append('"')
                    .append(String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
    }

    private static StringBuilder pad(final StringBuilder sb, final int indent) {
        for (var i = 0; i < indent; i++) {
            sb.append("  ");
        }
        return sb;
    }
}
