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
package io.yupiik.ia.agent.web;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import io.yupiik.ia.agent.core.cli.CliCommands;
import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellSession;
import io.yupiik.ia.agent.core.shell.slash.ArgCompletion;
import io.yupiik.ia.agent.core.shell.slash.SlashAction;
import io.yupiik.ia.agent.core.shell.slash.SlashCommand;
import io.yupiik.ia.agent.core.shell.slash.SlashCommandSet;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/** Web overrides precede shared handlers; the resulting registry also drives help and completion. */
@DefaultScoped
public class WebSlashCommands {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private final SlashRegistry registry;

    public WebSlashCommands(final SlashRegistry shared, final SubagentRegistry subagents, final SessionStore store) {
        final var commands = new ArrayList<SlashCommand>();
        commands.add(SlashCommand.of(
                new SlashCommandSpec("/tabs", null, "select or close browser session tabs", SlashGroup.SESSION),
                SlashAction.sync((arg, env) -> {
                    frontend(env).command("tabs", Map.of());
                    return ShellOutcome.none();
                })));
        for (final var name : List.of("quit", "exit")) {
            commands.add(SlashCommand.of(
                    new SlashCommandSpec("/" + name, null, "close the current browser session tab", SlashGroup.SESSION),
                    SlashAction.sync((arg, env) -> {
                        frontend(env).command(name, Map.of("arg", arg));
                        return ShellOutcome.none();
                    })));
        }
        commands.add(SlashCommand.of(
                new SlashCommandSpec("/theme", "light|dark", "change the browser theme", SlashGroup.CONFIG),
                List.of("light", "dark"),
                (arg, env) -> {
                    if (arg.isBlank()) {
                        return env.io()
                                .pick("Browser theme", List.of("light", "dark"))
                                .thenApply(value -> value.map(theme -> theme(env, theme))
                                        .orElseGet(() -> ShellOutcome.status("theme selection cancelled")));
                    }
                    return CompletableFuture.completedStage(theme(env, arg));
                }));
        commands.add(command(shared, "new", (arg, env) -> open(env, store.create())));
        commands.add(SlashCommand.of(
                spec(shared, "resume"), ArgCompletion.from("session", ArgCompletion.Env::sessions), (arg, env) -> {
                    if (!arg.isBlank()) {
                        return CompletableFuture.completedStage(resume(store, env, arg));
                    }
                    final var ids = store.list().stream()
                            .sorted(Comparator.comparingLong(SessionStore.SessionInfo::modifiedAt)
                                    .reversed())
                            .map(info -> info.id() + "  " + store.summary(info.id()))
                            .toList();
                    if (ids.isEmpty()) {
                        return CompletableFuture.completedStage(ShellOutcome.status("no session to resume"));
                    }
                    return env.io()
                            .pick("resume session", ids)
                            .thenApply(selected -> selected.map(value ->
                                            resume(store, env, WHITESPACE.split(value, 2)[0]))
                                    .orElseGet(() -> ShellOutcome.status("resume cancelled")));
                }));
        commands.add(command(shared, "shortcuts", (arg, env) -> ShellOutcome.transcript("""
                ## Web keyboard shortcuts

                - `Enter` sends the prompt; `Shift+Enter` inserts a newline.
                - `Tab` accepts a completion; arrow keys select a suggestion.
                - `Escape` dismisses completion or closes an open information modal.
                - Use the session tab bar to switch or close sessions.
                """)));
        // The CLI subagent action is presentation-independent: it returns a session-scoped markdown snapshot.
        final var subagentCommand = new CliCommands(null, subagents)
                .commands().stream()
                        .filter(command -> "subagents".equals(command.spec().command()))
                        .findFirst()
                        .orElseThrow();
        commands.add(SlashCommand.of(subagentCommand.spec(), subagentCommand.completion(), (arg, env) -> {
            String reference = arg;
            if (subagents != null && !arg.isBlank()) {
                final var scoped = subagents.listAll(env.session().sessionId());
                final var byId =
                        scoped.stream().filter(state -> arg.equals(state.id())).toList();
                final var matches = byId.isEmpty()
                        ? scoped.stream()
                                .filter(state -> arg.equals(state.name()))
                                .toList()
                        : byId;
                if (matches.size() != 1) {
                    frontend(env).command("subagents", Map.of("markdown", subagents.selectionError(arg, matches)));
                    return CompletableFuture.completedStage(ShellOutcome.none());
                }
                reference = matches.getFirst().id();
            }
            return subagentCommand.action().apply(reference, env).thenApply(outcome -> {
                if (outcome instanceof ShellOutcome.Transcript(var markdown)) {
                    frontend(env).command("subagents", Map.of("markdown", markdown));
                    return ShellOutcome.none();
                }
                if (outcome instanceof ShellOutcome.Error(var message)) {
                    frontend(env).command("subagents", Map.of("markdown", message));
                    return ShellOutcome.none();
                }
                return outcome;
            });
        }));
        this.registry = new SlashRegistry(List.of(
                set(commands),
                set(shared.specs().stream()
                        .filter(spec -> !"help".equals(spec.command()))
                        .map(spec -> shared.find(spec.command()).orElseThrow())
                        .toList())));
    }

    public SlashRegistry registry() {
        return registry;
    }

    public CompletionStage<ShellOutcome> dispatch(final String text, final ShellEnv env) {
        return registry.dispatch(text, env).thenApply(outcome -> {
            frontend(env).settings(displaySettings(env.session()));
            return outcome;
        });
    }

    private static ShellOutcome resume(final SessionStore store, final ShellEnv env, final String id) {
        if (store.list().stream().noneMatch(info -> info.id().equals(id))) {
            return ShellOutcome.error("unknown session " + id);
        }
        return open(env, id);
    }

    static Map<String, Object> displaySettings(final ShellSession session) {
        final var metrics = session.metrics();
        return Map.of(
                "showThinking", session.showThinking(),
                "showToolOutput", session.showToolOutput().name().toLowerCase(Locale.ROOT),
                "diffViewMode", session.diffViewMode().value(),
                "metricsPerTurn", metrics.showPerTurn(),
                "metricsSession", metrics.showSessionTotals(),
                "metricsMode", metrics.displayMode().name().toLowerCase(Locale.ROOT));
    }

    private static ShellOutcome theme(final ShellEnv env, final String theme) {
        if (!List.of("light", "dark").contains(theme)) {
            return ShellOutcome.status("usage: /theme light|dark");
        }
        frontend(env).command("theme", Map.of("arg", theme));
        return ShellOutcome.none();
    }

    private static ShellOutcome open(final ShellEnv env, final String id) {
        frontend(env).command("open-session", Map.of("id", id));
        return ShellOutcome.none();
    }

    private static WebShellFrontend frontend(final ShellEnv env) {
        return (WebShellFrontend) env.frontend();
    }

    private static SlashCommand command(
            final SlashRegistry shared, final String name, final BiFunction<String, ShellEnv, ShellOutcome> action) {
        return SlashCommand.of(spec(shared, name), SlashAction.sync(action::apply));
    }

    private static SlashCommandSpec spec(final SlashRegistry shared, final String name) {
        return shared.find(name)
                .map(SlashCommand::spec)
                .orElseGet(() -> new SlashCommandSpec("/" + name, null, "Web " + name, SlashGroup.SESSION));
    }

    private static SlashCommandSet set(final List<SlashCommand> commands) {
        return new SlashCommandSet() {
            @Override
            public String title() {
                return "Web commands";
            }

            @Override
            public List<SlashCommand> commands() {
                return commands;
            }
        };
    }
}
