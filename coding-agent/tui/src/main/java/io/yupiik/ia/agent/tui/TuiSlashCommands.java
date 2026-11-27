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
package io.yupiik.ia.agent.tui;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.order.Order;
import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.slash.ArgCompletion;
import io.yupiik.ia.agent.core.shell.slash.SlashAction;
import io.yupiik.ia.agent.core.shell.slash.SlashCommand;
import io.yupiik.ia.agent.core.shell.slash.SlashCommandSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * TUI-contributed slash commands: the TUI-only theme switch and the TUI key
 * cheatsheet. Registered into the shared {@link SlashRegistry} like any other
 * {@link SlashCommandSet} bean.
 */
@ApplicationScoped
@Order(1900)
public class TuiSlashCommands implements SlashCommandSet {
    private static final String SHORTCUTS = KeymapCatalog.markdown();

    private final TabManager tabManager;

    /** Implemented by the TUI command once the widget stack is up. */
    public interface ThemeSwitcher {
        ShellOutcome switchTheme(String arg);
    }

    /** Implemented by the TUI command: opens the subagents overlay (list or one subagent detail). */
    public interface SubagentViewer {
        ShellOutcome open(String arg);
    }

    private volatile ThemeSwitcher themeSwitcher;
    private volatile SubagentViewer subagentViewer;

    public TuiSlashCommands(final TabManager tabManager) {
        this.tabManager = tabManager;
    }

    public void bind(final ThemeSwitcher switcher) {
        this.themeSwitcher = switcher;
    }

    public void bindSubagents(final SubagentViewer viewer) {
        this.subagentViewer = viewer;
    }

    @Override
    public String title() {
        return "TUI commands";
    }

    @Override
    public List<SlashCommand> commands() {
        return List.of(
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/theme",
                                "(light|dark)",
                                "switch between light and dark themes (persisted)",
                                SlashGroup.CONFIG),
                        List.of("light", "dark"),
                        themeAction()),
                SlashCommand.of(
                        new SlashCommandSpec("/shortcuts", null, "show the keyboard shortcuts", SlashGroup.OTHER),
                        SlashAction.sync((arg, env) -> ShellOutcome.transcript("\n\n" + SHORTCUTS))),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/tabs",
                                "[index|last|+N|-N|+|-]",
                                "list open tabs, or switch to the tab at the given index",
                                SlashGroup.SESSION),
                        List.of("last", "+", "-"),
                        SlashAction.sync(this::tabsOutcome)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/subagents",
                                "[id|name]",
                                "list the session subagents, or open the transcript of the given subagent",
                                SlashGroup.SESSION),
                        ArgCompletion.wholeArgument("subagent", ArgCompletion.Env::subagents),
                        SlashAction.sync(this::subagentsOutcome)));
    }

    private ShellOutcome subagentsOutcome(final String arg, final ShellEnv env) {
        final var viewer = subagentViewer;
        return viewer == null ? ShellOutcome.status("subagents overlay is not available yet") : viewer.open(arg);
    }

    private ShellOutcome tabsOutcome(final String arg, final ShellEnv env) {
        if (arg == null || arg.isBlank()) {
            tabManager.openList();
            return ShellOutcome.none();
        }
        final var index = tabManager.resolveIndex(arg);
        if (index < 0) {
            return ShellOutcome.status("usage: /tabs [index|last|+N|-N|+|-]");
        }
        tabManager.activate(index);
        return ShellOutcome.none();
    }

    private SlashAction themeAction() {
        return (arg, env) -> {
            final var switcher = themeSwitcher;
            return switcher == null
                    ? CompletableFuture.completedStage(ShellOutcome.status("theme is not available yet"))
                    : CompletableFuture.completedStage(switcher.switchTheme(arg));
        };
    }
}
