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
import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

/**
 * The single slash command registry: collects every {@link SlashCommandSet}
 * bean (ordered by {@code @Order}) and exposes dispatch, the catalog and the
 * argument-completion kinds. Shells only parse input and render outcomes -
 * there is no per-shell dispatch table anymore.
 */
@ApplicationScoped
public class SlashRegistry {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private final Map<String, SlashCommand> commands;

    public SlashRegistry(final List<SlashCommandSet> sets) {
        this.commands = new LinkedHashMap<>();
        for (final var set : sets) {
            for (final var command : set.commands()) {
                commands.putIfAbsent(command.spec().command(), command);
            }
        }
        commands.putIfAbsent(
                "help",
                SlashCommand.of(
                        new SlashCommandSpec("/help", null, "show this help", SlashGroup.OTHER),
                        (arg, env) -> CompletableFuture.completedStage(ShellOutcome.transcript(helpMarkdown()))));
    }

    /** Degenerate registry without any contributed command, for subclasses/tests. */
    protected SlashRegistry() {
        this.commands = new LinkedHashMap<>();
    }

    /** All catalog entries, in registration order. */
    public List<SlashCommandSpec> specs() {
        return commands.values().stream().map(SlashCommand::spec).toList();
    }

    /** Command names with their leading slash, in registration order. */
    public List<String> names() {
        return commands.values().stream().map(command -> command.spec().name()).toList();
    }

    public Optional<SlashCommand> find(final String name) {
        return Optional.ofNullable(commands.get(name.toLowerCase(Locale.ROOT)));
    }

    /** The argument completion kind of a command (bare name, no slash). */
    public ArgCompletion completionFor(final String name) {
        return find(name).map(SlashCommand::completion).orElse(ArgCompletion.NONE);
    }

    /**
     * Parses {@code /name arg} and runs the matching action. Unknown commands
     * and synchronous failures are reported as outcomes, never thrown.
     */
    public CompletionStage<ShellOutcome> dispatch(final String text, final ShellEnv env) {
        final var value = text.substring(1);
        final var parts = WHITESPACE.split(value, 2);
        final var name = parts[0].toLowerCase(Locale.ROOT);
        final var arg = parts.length > 1 ? parts[1].strip() : "";
        final var command = commands.get(name);
        if (command == null) {
            return CompletableFuture.completedStage(ShellOutcome.status("unknown command /" + name + " - type /help"));
        }
        try {
            return command.action().apply(arg, env);
        } catch (final RuntimeException e) {
            return CompletableFuture.completedStage(
                    ShellOutcome.error(command.spec().name() + " failed: " + e.getMessage()));
        }
    }

    /**
     * The whole catalog as markdown, grouped like the catalog groups, rendered
     * by every shell through its transcript channel.
     */
    public String helpMarkdown() {
        final var sb = new StringBuilder("## commands\n");
        for (final var group : SlashGroup.values()) {
            final var rows =
                    specs().stream().filter(spec -> spec.group() == group).toList();
            if (rows.isEmpty()) {
                continue;
            }
            sb.append("\n### ").append(group.name().toLowerCase(Locale.ROOT)).append("\n\n");
            for (final var spec : rows) {
                sb.append("- **")
                        .append(spec.display())
                        .append("** - ")
                        .append(spec.description())
                        .append('\n');
            }
        }
        return sb.toString();
    }

    /** Markdown table escaping for pipe-containing cells. */
    public static String cell(final String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    /** Builds a markdown table from header + rows. */
    public static String table(final List<String> header, final Collection<List<String>> rows) {
        final var sb =
                new StringBuilder("| ").append(String.join(" | ", header)).append(" |\n");
        sb.append("|");
        for (int i = 0; i < header.size(); i++) {
            sb.append("---|");
        }
        sb.append('\n');
        for (final var row : rows) {
            sb.append("| ").append(String.join(" | ", row)).append(" |\n");
        }
        return sb.toString();
    }

    /** First line of a text, capped, used by pickers and summaries. */
    public static String firstLine(final String text, final int max) {
        final var line = text == null ? "" : text.lines().findFirst().orElse("");
        return line.length() <= max ? line : line.substring(0, max) + "...";
    }

    /** Flattens a listing of option labels for pickers. */
    public static List<String> limit(final List<String> items, final int max) {
        final var out = new ArrayList<String>();
        for (final var item : items) {
            if (out.size() >= max) {
                break;
            }
            out.add(item);
        }
        return out;
    }
}
