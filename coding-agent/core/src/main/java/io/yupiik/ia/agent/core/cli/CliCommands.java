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
package io.yupiik.ia.agent.core.cli;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.order.Order;
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import io.yupiik.ia.agent.core.agent.SubagentRegistry.SubagentState;
import io.yupiik.ia.agent.core.cli.prompt.Prompt;
import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.slash.ArgCompletion;
import io.yupiik.ia.agent.core.shell.slash.SlashAction;
import io.yupiik.ia.agent.core.shell.slash.SlashCommand;
import io.yupiik.ia.agent.core.shell.slash.SlashCommandSet;
import java.util.List;

/**
 * CLI-specific commands, contributed on top of the shared domain sets.
 */
@ApplicationScoped
@Order(2000)
public class CliCommands implements SlashCommandSet {
    @Override
    public String title() {
        return "CLI commands";
    }

    private final Prompt prompt;
    private final SubagentRegistry subagents;

    public CliCommands(final Prompt prompt) {
        this(prompt, null);
    }

    public CliCommands(final Prompt prompt, final SubagentRegistry subagents) {
        this.prompt = prompt;
        this.subagents = subagents;
    }

    @Override
    public List<SlashCommand> commands() {
        return List.of(
                SlashCommand.of(
                        new SlashCommandSpec("/shortcuts", null, "show the keyboard shortcuts", SlashGroup.OTHER),
                        SlashAction.sync((arg, env) -> ShellOutcome.transcript(shortcuts()))),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/subagents", "[id|name]", "list subagents or show a conversation", SlashGroup.INSPECT),
                        ArgCompletion.wholeArgument("subagent", ArgCompletion.Env::subagents),
                        SlashAction.sync(this::subagents)));
    }

    private ShellOutcome subagents(final String reference, final ShellEnv env) {
        if (subagents == null) {
            return ShellOutcome.transcript("No subagents.");
        }
        final var sessionId =
                env == null || env.session() == null ? null : env.session().sessionId();
        if (reference == null || reference.isBlank()) {
            final var states = subagents.listAll(sessionId);
            final var out = new StringBuilder("## Subagents\n\n");
            if (states.isEmpty()) {
                out.append("No subagents.");
            }
            for (final var state : states) {
                out.append("- ")
                        .append(label(state))
                        .append(" - ")
                        .append(status(state))
                        .append('\n');
            }
            return ShellOutcome.transcript(out.toString());
        }
        final var matches = subagents.resolve(sessionId, reference);
        if (matches.size() != 1) {
            return ShellOutcome.error(subagents.selectionError(reference, matches));
        }
        final var state = matches.getFirst();
        final var out = new StringBuilder("## Subagent ")
                .append(label(state))
                .append("\n\nStatus: ")
                .append(status(state));
        final var model = subagents
                .identityOf(state.id())
                .map(SubagentRegistry.TranscriptIdentity::model)
                .orElse(state.model());
        out.append("\n\nModel: ").append(model == null || model.isBlank() ? "(inherited)" : model);
        final var result = state.result();
        if (result != null) {
            if (result.summary() != null && !result.summary().isBlank()) {
                out.append("\n\n### Summary\n\n").append(result.summary());
            }
            if (result.error() != null && !result.error().isBlank()) {
                out.append("\n\nError: ").append(result.error());
            }
            if (!result.artifacts().isEmpty()) {
                out.append("\n\n### Artifacts\n\n");
                for (final var artifact : result.artifacts()) {
                    out.append("- ").append(artifact).append('\n');
                }
            }
        }
        final var conversation = result != null
                        && result.fullConversation() != null
                        && !result.fullConversation().isBlank()
                ? result.fullConversation()
                : subagents.transcriptOf(state.id()).markdown("", "(no conversation yet)");
        return ShellOutcome.transcript(
                out.append("\n\n### Conversation\n\n").append(conversation).toString());
    }

    private String label(final SubagentState state) {
        return state.name() == null || state.name().isBlank()
                ? "`" + state.id() + "`"
                : state.name() + " (`" + state.id() + "`)";
    }

    private String status(final SubagentState state) {
        if (state.result() != null) {
            return state.result().status().name();
        }
        return state.isCancelled() ? "CANCELLED" : state.isDone() ? "DONE" : "RUNNING";
    }

    private String shortcuts() {
        final var sb = new StringBuilder("## keys\n\n");
        if (prompt.editing()) {
            sb.append("""
                    - `Tab` complete slash commands and their arguments
                    - `Up/Down` browse the in-memory history
                    - `Left/Right` move by one character; `Delete` removes the next character
                    - `Alt-b`/`Alt-f` move back/forward by one word
                    - `Alt-Backspace`, `Ctrl-W` delete the previous word
                    - `Ctrl-A`/`Ctrl-E` go to the beginning/end of the line
                    - `Ctrl-U`/`Ctrl-K` delete to the beginning/end of the line
                    - `Ctrl-L` clear the screen
                    """);
        }
        sb.append("""
                - `Ctrl-C` open the interrupt menu (cancel the current turn / exit); pressed twice when idle (within 2s) it quits
                - `Ctrl-D` exit
                """);
        return sb.toString();
    }
}
