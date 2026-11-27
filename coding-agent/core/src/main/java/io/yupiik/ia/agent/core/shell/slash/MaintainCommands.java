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
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Maintenance commands shared by every shell: compaction, snapshots, plan
 * edition, code graph and Maven knowledge index, MCP servers, housekeeping.
 */
@ApplicationScoped
public class MaintainCommands implements SlashCommandSet {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    @Override
    public String title() {
        return "Maintenance commands";
    }

    private final AgentSessionService agentSessionService;
    private final McpManager mcpManager;
    private final MavenRepositoryKnowledgeService mavenKnowledgeService;

    /** /mcp completion: `reset` then completes the server names, the other forms are a closed set. */
    private static final ArgCompletion MCP_COMPLETION = (arg, token, env) -> {
        final var previous = ArgCompletion.previousToken(arg);
        if ("reset".equals(previous)) {
            return ArgCompletion.fuzzyResult("server", env.servers(), token);
        }
        return ArgCompletion.fuzzyResult("option", List.of("status", "reset"), token);
    };

    public MaintainCommands(
            final AgentSessionService agentSessionService,
            final McpManager mcpManager,
            final MavenRepositoryKnowledgeService mavenKnowledgeService) {
        this.agentSessionService = agentSessionService;
        this.mcpManager = mcpManager;
        this.mavenKnowledgeService = mavenKnowledgeService;
    }

    @Override
    public List<SlashCommand> commands() {
        return List.of(
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/compact",
                                "[force] [urgent] [keep=<n>]",
                                "compact the context window (/compact [force] [urgent] [keep=<n>])",
                                SlashGroup.MAINTAIN),
                        ArgCompletion.options("force", "urgent", "keep="),
                        SlashAction.sync(this::compact)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/snapshot",
                                "save|cancel|restore|status",
                                "manage context checkpoints that survive compaction",
                                SlashGroup.MAINTAIN),
                        List.of("save", "cancel", "restore", "status"),
                        SlashAction.sync(this::snapshot)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/editplan", "[path]", "edit the current plan (in plan mode)", SlashGroup.MAINTAIN),
                        ArgCompletion.PATH,
                        this::editPlan),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/structural-code-graph", null, "resync the code structure index", SlashGroup.MAINTAIN),
                        SlashAction.sync(this::resyncGraph)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/maven-repository-knowledge",
                                "<refresh [gav]|status>",
                                "index local Maven artifacts so agents do not rediscover library usage",
                                SlashGroup.MAINTAIN),
                        List.of("refresh", "status"),
                        SlashAction.sync(this::mavenRepositoryKnowledge)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/mcp",
                                "status|reset [server]",
                                "show MCP server status, or reset (reconnect) a single server or all of them",
                                SlashGroup.MAINTAIN),
                        MCP_COMPLETION,
                        SlashAction.sync(this::mcp)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/session-housekeeping",
                                null,
                                "clean up sessions (compress, delete empty/expired) and report",
                                SlashGroup.MAINTAIN),
                        SlashAction.sync(this::sessionHousekeeping)));
    }

    private ShellOutcome compact(final String arg, final ShellEnv env) {
        final var parsed = parseCompact(arg);
        if (parsed == null) {
            return ShellOutcome.status("usage: /compact [force] [urgent] [keep=<n>]");
        }
        if (env.session().busy()) {
            // deferred: the loop applies the operation at the next safe checkpoint, at most one per turn
            final var queued = env.session()
                    .runtime()
                    .loop()
                    .requestUserContextOperation("compact", null, parsed.keepLast(), parsed.force(), parsed.urgent());
            return ShellOutcome.status(
                    queued
                            ? "compaction scheduled - applied at the next agent checkpoint"
                            : "a context operation is already pending this turn");
        }
        try {
            final var result = agentSessionService.compact(
                    env.session().sessionId(),
                    parsed.force(),
                    parsed.urgent(),
                    parsed.keepLast(),
                    env.session().runtime());
            if (result.applied() && result.error() != null) {
                return ShellOutcome.status(result.message() + "\n" + result.error());
            }
            return ShellOutcome.status(result.message());
        } catch (final RuntimeException e) {
            return ShellOutcome.error("compact failed: " + e.getMessage());
        }
    }

    /**
     * Parses the {@code /compact} arguments ({@code force}, {@code urgent}, {@code keep=<n>}),
     * space-separated in any order. Returns {@code null} on an unknown/ill-formed token so the
     * caller can surface the usage line.
     */
    private static CompactOptions parseCompact(final String arg) {
        var force = false;
        var urgent = false;
        var keepLast = 0;
        if (arg == null || arg.isBlank()) {
            return new CompactOptions(force, urgent, keepLast);
        }
        for (final var token : WHITESPACE.split(arg.strip())) {
            final var t = token.toLowerCase(Locale.ROOT);
            if ("force".equals(t)) {
                force = true;
            } else if ("urgent".equals(t)) {
                urgent = true;
            } else if (t.startsWith("keep=")) {
                try {
                    keepLast =
                            Integer.parseInt(token.substring("keep=".length()).strip());
                    if (keepLast < 0) {
                        return null;
                    }
                } catch (final NumberFormatException e) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return new CompactOptions(force, urgent, keepLast);
    }

    private record CompactOptions(boolean force, boolean urgent, int keepLast) {}

    private ShellOutcome snapshot(final String arg, final ShellEnv env) {
        try {
            return ShellOutcome.status(
                    agentSessionService.snapshot(arg, env.session().sessionId()));
        } catch (final RuntimeException e) {
            return ShellOutcome.error("snapshot failed: " + e.getMessage());
        }
    }

    private CompletionStage<ShellOutcome> editPlan(final String arg, final ShellEnv env) {
        if (env.session().busy()) {
            return CompletableFuture.completedStage(ShellOutcome.status("editplan is not available while busy"));
        }
        final var export = agentSessionService.exportPlan(
                env.session().sessionId(), arg, "plan", env.session().runtime());
        if (export.error() != null) {
            return CompletableFuture.completedStage(ShellOutcome.error(export.error()));
        }
        final var exported = ShellOutcome.status("plan exported to " + export.path()
                + " - edit it, save, then press Enter to reload (type 'c' to cancel)");
        exported.applyTo(env.frontend());
        return env.io()
                .readLine("Edit " + export.path() + ", save, then submit to reload (type 'c' to cancel)")
                .thenApply(input -> {
                    if (input.isEmpty()) {
                        return ShellOutcome.status("plan edit cancelled");
                    }
                    final var decision = input.get().strip();
                    if (decision.equalsIgnoreCase("c") || decision.equalsIgnoreCase("cancel")) {
                        return ShellOutcome.status("plan edit cancelled");
                    }
                    final var reload = agentSessionService.reloadPlan(
                            env.session().sessionId(),
                            export.path(),
                            export.original(),
                            env.session().runtime());
                    if (reload.error() != null) {
                        return ShellOutcome.error(reload.error());
                    }
                    return ShellOutcome.status(reload.message());
                });
    }

    private ShellOutcome resyncGraph(final String arg, final ShellEnv env) {
        if (env.session().busy()) {
            return ShellOutcome.status("cannot resync the graph while busy");
        }
        try {
            return ShellOutcome.status(
                    agentSessionService.resyncGraph(env.session().runtime().workdir()));
        } catch (final RuntimeException e) {
            return ShellOutcome.error("resync failed: " + e.getMessage());
        }
    }

    private ShellOutcome mavenRepositoryKnowledge(final String arg, final ShellEnv env) {
        if (env.session().busy()) {
            return ShellOutcome.status("cannot run maven knowledge while busy");
        }
        try {
            if (arg == null || arg.isBlank()) {
                return ShellOutcome.status("usage: /maven-repository-knowledge <refresh [gav]|status>");
            }
            final var parts = WHITESPACE.split(arg.strip(), 2);
            final var sub = parts[0].toLowerCase(Locale.ROOT);
            final var runtime = env.session().runtime();
            final var root = runtime.workdir();
            if ("status".equals(sub)) {
                return ShellOutcome.transcript(
                        "> maven-repository-knowledge:\n" + String.join("\n", mavenKnowledgeService.status(root)));
            }
            if (!"refresh".equalsIgnoreCase(sub)) {
                return ShellOutcome.status("usage: /maven-repository-knowledge <refresh [gav]|status>");
            }
            final var gav = parts.length > 1 ? parts[1].strip() : "";
            final Consumer<String> progress = text -> env.frontend().status(text);
            final Consumer<String> completed =
                    summary -> env.frontend().transcript("\n\n> maven-repository-knowledge:\n" + summary);
            final var ack = mavenKnowledgeService.refresh(
                    root, gav, progress, completed, env.session().chatSettings());
            return ShellOutcome.transcript("> maven-repository-knowledge:\n" + String.join("\n", ack));
        } catch (final RuntimeException e) {
            return ShellOutcome.error("maven-repository-knowledge failed: " + e.getMessage());
        }
    }

    private ShellOutcome mcp(final String arg, final ShellEnv env) {
        try {
            final var value = arg == null ? "" : arg.strip();
            if (value.isBlank() || value.equalsIgnoreCase("status")) {
                final var rows = mcpManager.serverStatuses().stream()
                        .map(status -> List.of(
                                SlashRegistry.cell(status.name()),
                                status.state().name().toLowerCase(Locale.ROOT)))
                        .toList();
                return ShellOutcome.transcript(
                        "**MCP servers**\n\n" + SlashRegistry.table(List.of("server", "state"), rows));
            }
            final var parts = WHITESPACE.split(value, 2);
            if (!parts[0].equalsIgnoreCase("reset")) {
                return ShellOutcome.status("usage: /mcp status|reset [server]");
            }
            if (parts.length > 1 && !parts[1].isBlank()) {
                final var server = parts[1].strip();
                mcpManager.reset(server);
                return ShellOutcome.status("MCP server " + server + " reset");
            }
            mcpManager.reset();
            return ShellOutcome.status("MCP servers reset");
        } catch (final RuntimeException e) {
            return ShellOutcome.error("mcp failed: " + e.getMessage());
        }
    }

    private ShellOutcome sessionHousekeeping(final String arg, final ShellEnv env) {
        final var report = agentSessionService.housekeeping();
        return ShellOutcome.status("housekeeping done - " + report.compressed() + " compressed, " + report.deleted()
                + " deleted, " + report.kept() + " kept");
    }
}
