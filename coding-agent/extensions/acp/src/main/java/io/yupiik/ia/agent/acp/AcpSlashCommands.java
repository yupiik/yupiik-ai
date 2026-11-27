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

import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.slash.SlashAction;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Session-local ACP v1 slash-command projection, not a prompt/turn runner. Wrap {@link #update()} in
 * {@code session/update}'s {@code params.update} after session creation/loading. No registry is
 * mutated, no work is scheduled, and no catalog or conversation is cached here.
 *
 * <p>The server owns admission, serialization against config changes and turns, active prompt scope,
 * cancellation, permission/feedback gates, output and exactly one prompt response. Call
 * {@link #dispatch(String, ShellEnv)} BEFORE submitting a model turn: only an empty optional permits
 * ordinary model submission. Every leading slash, including unknown/unsupported commands, is consumed.
 * Assemble textual blocks without converting attachments/resource descriptions into command arguments;
 * the server must reject unsupported mixed-content command prompts rather than fall through to the LLM.
 *
 * <p>Await the returned stage and render its ShellOutcome while that scope is still active, including
 * frontend output produced during the action. Quit means close THIS session, never exit the process.
 * Stages are returned verbatim, without an extra dependent wrapper; a returned CompletableFuture is
 * the very future the caller can cancel. This does NOT make arbitrary CompletionStage views, core's
 * chained IO futures or detached work cancellable: the server must also
 * cancel owned IO requests/work, drain them before disposing the runtime, and suppress stale output by
 * scope/epoch. The supplied ShellEnv and its current runtime workdir are borrowed without replacement;
 * there is no stdin, automatic approval, or fallback project root here.
 */
public final class AcpSlashCommands {
    // Reviewed headless actions only. A future/extension command needs a lifecycle audit before exposure.
    private static final Set<String> SHARED = Set.of(
            "mode",
            "model",
            "variant",
            "agent",
            "permissions",
            "secrets",
            "permissions-show",
            "permissions-add",
            "permission-remove",
            "context",
            "session-id",
            "session-flush",
            "memory",
            "tools",
            "executing-tools",
            "todos",
            "compact",
            "snapshot",
            "editplan",
            "structural-code-graph",
            "maven-repository-knowledge",
            "mcp",
            "session-housekeeping",
            "subagents",
            "usage");

    private final SlashRegistry registry;
    private final Routes routes;

    /**
     * Effective server integrations, not client permissions. Set display flags ONLY when the frontend
     * actually implements them. Clear is visible-transcript clearing, never deletion of model context.
     * Metrics reporting remains usable without metrics display controls.
     *
     * <p>Nullable actions replace ONLY the unsafe shared entry points, using the same argument/ShellEnv
     * contract. They must reuse core services, retain ownership through completion, and connect server
     * cancellation to the actual work (not merely cancel a wrapper future):
     * <ul>
     *   <li>btw: BtwAnswerer.ask with a real cancellation probe, session gates/feedback/settings/usage,
     *       system prompt, runtime workdir, effective registry and explicit tool-name ceiling.</li>
     *   <li>provider: selection plus any required login, including the no-argument picker. Preserve
     *       core hooks/readback and only authenticate the effective selected Codex provider.</li>
     *   <li>codex: retain CodexLoginService.newAttempt before start on an owned executor; cancellation
     *       reaches the attempt and prompt disposal waits for drained, not merely completion.
     *       Deliver structured browser/device instructions privately, never credential forms.</li>
     *   <li>mavenRefresh: receives the full "refresh [gav]" argument. Discover on an owned worker,
     *       retain the exact refreshJob handle, cancel it, and await its actual completion/drain.</li>
     * </ul>
     * Missing actions are explicit unavailable routes, never silently dispatched to core or the LLM.
     */
    public record Routes(
            boolean clear,
            boolean thinking,
            boolean toolOutput,
            boolean metrics,
            SlashAction btw,
            SlashAction provider,
            SlashAction codex,
            SlashAction mavenRefresh,
            SlashAction tools,
            SlashAction mcp,
            SlashAction editPlan) {}

    /**
     * Supply a headless registry of core command sets, including CliCommands' transcript subagents
     * action, NOT TUI/Web overrides with the same names. A name/spec alone cannot establish that an
     * arbitrary override's implementation is headless. The server owns this registry composition.
     */
    public AcpSlashCommands(final SlashRegistry registry, final Routes routes) {
        this.registry = Objects.requireNonNull(registry);
        this.routes = Objects.requireNonNull(routes);
    }

    /** Stable-v1 AvailableCommand objects, registration order, names WITHOUT a slash. */
    public List<Map<String, Object>> availableCommands() {
        return registry.specs().stream()
                .filter(spec -> supported(spec.command()))
                .map(this::availableCommand)
                .toList();
    }

    /** The update payload only; session identity and transport remain server-owned. */
    public Map<String, Object> update() {
        return Map.of("sessionUpdate", "available_commands_update", "availableCommands", availableCommands());
    }

    /**
     * Empty ONLY for ordinary text. Bare slash shows filtered help. Leading/trailing whitespace is
     * ignored; argument contents/case are otherwise preserved. No outcome is rendered by this helper.
     */
    public Optional<CompletionStage<ShellOutcome>> dispatch(final String text, final ShellEnv env) {
        final var value = text == null ? "" : text.strip();
        if (!value.startsWith("/")) {
            return Optional.empty();
        }
        Objects.requireNonNull(env);
        if ("/".equals(value)) {
            return completed(ShellOutcome.transcript(help()));
        }
        final var end = tokenEnd(value, 1);
        final var name = value.substring(1, end).toLowerCase(Locale.ROOT);
        final var arg = value.substring(end).strip();
        if ("new".equals(name)) {
            return completed(ShellOutcome.status(
                    "Use ACP session/new to create another session. /new cannot change this ACP session's identity."));
        }
        if ("resume".equals(name)) {
            return completed(ShellOutcome.status(
                    "Use ACP session/load, or session/resume when advertised by the server, to open an existing session. "
                            + "/resume cannot change this ACP session's identity."));
        }
        if (name.isEmpty() || registry.find(name).isEmpty()) {
            return completed(ShellOutcome.error("Unknown command /" + name + " - use /help for ACP commands."));
        }
        if (!supported(name)) {
            return completed(ShellOutcome.error(unavailable(name)));
        }
        if ("help".equals(name)) {
            return completed(ShellOutcome.transcript(help()));
        }
        if ("quit".equals(name) || "exit".equals(name)) {
            return completed(ShellOutcome.quit());
        }
        if ("metrics".equals(name) && !routes.metrics() && !arg.isEmpty()) {
            return completed(ShellOutcome.error(
                    "/metrics reports metrics without arguments; display controls need server visibility support."));
        }
        SlashAction owned =
                switch (name) {
                    case "btw" -> routes.btw();
                    case "provider" -> routes.provider();
                    case "codex" -> routes.codex();
                    case "tools" -> routes.tools();
                    case "mcp" -> routes.mcp();
                    case "editplan" -> routes.editPlan();
                    default -> null;
                };
        if ("maven-repository-knowledge".equals(name)) {
            final var operation = arg.substring(0, tokenEnd(arg, 0));
            if ("refresh".equalsIgnoreCase(operation)) {
                owned = routes.mavenRefresh();
                if (owned == null) {
                    return completed(ShellOutcome.error(mavenUnavailable()));
                }
            } else if (routes.mavenRefresh() == null && !"status".equalsIgnoreCase(operation)) {
                return completed(
                        ShellOutcome.status("usage: /maven-repository-knowledge status. " + mavenUnavailable()));
            }
        }
        try {
            return Optional.of(
                    owned == null
                            ? registry.dispatch("/" + name + (arg.isEmpty() ? "" : " " + arg), env)
                            : Objects.requireNonNull(
                                    owned.apply(arg, env), "owned command returned no completion stage"));
        } catch (final RuntimeException e) {
            return completed(
                    ShellOutcome.error("/" + name + " failed; check the command arguments and configuration."));
        }
    }

    private boolean supported(final String name) {
        return switch (name) {
            case "help", "quit", "exit", "metrics" -> true;
            case "clear" -> routes.clear();
            case "thinking" -> routes.thinking();
            case "tool-output" -> routes.toolOutput();
            case "btw" -> routes.btw() != null;
            case "provider" -> routes.provider() != null;
            case "codex" -> routes.codex() != null;
            default -> SHARED.contains(name);
        };
    }

    private Map<String, Object> availableCommand(final SlashCommandSpec spec) {
        final var name = spec.command();
        final var description =
                switch (name) {
                    case "help" -> "list commands supported by this ACP session and native lifecycle equivalents";
                    case "quit", "exit" -> "close this ACP session, not the agent process";
                    case "clear" ->
                        "clear the visible transcript only; persisted conversation and model context are unchanged";
                    case "context" -> "show/export transcript or LLM payload, or compact/prune context";
                    case "metrics" -> routes.metrics() ? spec.description() : "report current turn/session metrics";
                    case "maven-repository-knowledge" ->
                        routes.mavenRefresh() != null
                                ? spec.description()
                                : "show Maven knowledge index status (refresh requires server task ownership)";
                    case "mcp" -> "show this session's effective MCP tools; reset affects configured core servers only";
                    case "tools" -> "show this session's effective tools without connection secrets";
                    case "editplan" -> "export and reload the actual plan file with explicit nonsecret confirmation";
                    case "codex" ->
                        "owned Codex browser/device login, enterprise enrollment and saved-login status; core logout unavailable";
                    default -> spec.description();
                };
        final var hint =
                switch (name) {
                    case "metrics" -> routes.metrics() ? spec.usage() : null;
                    case "codex" ->
                        "login [provider] [--device] [--enterprise] [--workspace <org-id>] | enroll [provider] [--device] [--workspace <org-id>] | status [provider]";
                    case "maven-repository-knowledge" -> routes.mavenRefresh() != null ? spec.usage() : "status";
                    default -> spec.usage();
                };
        return hint == null || hint.isBlank()
                ? Map.of("name", name, "description", description)
                : Map.of("name", name, "description", description, "input", Map.of("hint", hint));
    }

    private String help() {
        final var out = new StringBuilder("## ACP commands\n\n");
        for (final var command : availableCommands()) {
            out.append("- **/").append(command.get("name"));
            if (command.get("input") instanceof Map<?, ?> input) {
                out.append(' ').append(input.get("hint"));
            }
            out.append("** - ").append(command.get("description")).append('\n');
        }
        return out.append("\n### Native lifecycle equivalents\n\n")
                .append("Create a separate session with `session/new`, not a slash command.\n")
                .append("Open saved history with `session/load` when supported, or `session/resume` when advertised.\n")
                .append("The identity and working directory of this ACP session remain unchanged.\n")
                .append(
                        "The client owns the visible transcript; use its clear-view action without deleting session history.\n")
                .toString();
    }

    private String unavailable(final String name) {
        return switch (name) {
            case "btw" -> "/btw needs a server-owned side-answer scope with cancellation, gates and awaited output.";
            case "provider" ->
                "/provider needs server-owned provider selection/authentication with cancellable login; "
                        + "the shared provider action disables login cancellation.";
            case "codex" ->
                "/codex needs server-owned cancellable URL/device authentication; "
                        + "the shared login is detached and uncancellable. Never enter credentials in a generic form.";
            case "clear" ->
                "/clear is client-owned in ACP. Use your editor's clear view; session history and model context are unchanged.";
            case "thinking", "tool-output" -> "/" + name + " needs effective server/client display support.";
            default -> "/" + name + " is not supported by this ACP frontend - use /help for ACP commands.";
        };
    }

    private static String mavenUnavailable() {
        return "Maven refresh needs a server-owned terminal completion/error/cancellation lifecycle; "
                + "the shared action only returns an acknowledgement.";
    }

    private static int tokenEnd(final String value, final int start) {
        var end = start;
        while (end < value.length() && !Character.isWhitespace(value.charAt(end))) {
            end++;
        }
        return end;
    }

    private static Optional<CompletionStage<ShellOutcome>> completed(final ShellOutcome outcome) {
        return Optional.of(CompletableFuture.completedFuture(outcome));
    }
}
