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

import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Session-scoped permission-anchor management: inspect what the session has
 * approved but the global config does not already cover, add anchors before
 * the model ever asks, and remove them. Complements the global
 * {@code yca.permissions.bash.rules} allowlist and the per-approval flow.
 */
public class PermissionCommands implements SlashCommandSet {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    @Override
    public String title() {
        return "Permission commands";
    }

    @Override
    public List<SlashCommand> commands() {
        return List.of(
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/permissions-show",
                                "",
                                "list the bash anchors this session approved that the global rules do not already cover",
                                SlashGroup.CONFIG),
                        SlashAction.sync(this::show)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/permissions-add",
                                "<command-or-*>",
                                "approve a bash anchor (or '*' for every bash command) for this session before the model asks",
                                SlashGroup.CONFIG),
                        SlashAction.sync(this::add)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/permission-remove",
                                "<anchor...>",
                                "forget one or more session-approved bash anchors",
                                SlashGroup.CONFIG),
                        SlashAction.sync(this::remove)));
    }

    private ShellOutcome show(final String arg, final ShellEnv env) {
        final var permissions = env.session().runtime().permissions();
        final var anchors = permissions.sessionGrantAnchors();
        if (anchors.isEmpty()) {
            return ShellOutcome.status("no session bash grants are recorded");
        }
        final var rows = new ArrayList<List<String>>(anchors.size());
        for (final var anchor : anchors) {
            final var covered = permissions.coveredByGlobalRules(anchor);
            if (covered) {
                continue; // /permissions-show lists only what the global config does not already cover
            }
            rows.add(List.of(anchor));
        }
        if (rows.isEmpty()) {
            return ShellOutcome.status("every session grant is already covered by the global bash rules");
        }
        return ShellOutcome.transcript("**Session-approved bash anchors** (not in the global config):\n\n"
                + SlashRegistry.table(List.of("anchor"), rows));
    }

    private ShellOutcome add(final String arg, final ShellEnv env) {
        final var permissions = env.session().runtime().permissions();
        if (arg == null || arg.isBlank()) {
            return ShellOutcome.error("usage: /permissions-add <command-or-*>");
        }
        final var trimmed = arg.strip();
        if (trimmed.equals("*")) {
            permissions.addSessionGrantAnchor("*");
            return ShellOutcome.status("every bash command is now approved for this session (wildcard)");
        }
        // segment the value through the same anchor resolution the grant flow uses,
        // so /permissions-add "docker run nginx" records the same anchors as approving it
        final var anchors = permissions.anchorsOf(trimmed);
        if (anchors.isEmpty()) {
            return ShellOutcome.error("cannot derive a permission anchor from '" + trimmed + "'");
        }
        anchors.forEach(permissions::addSessionGrantAnchor);
        return ShellOutcome.status("session-approved: " + String.join(", ", anchors));
    }

    private ShellOutcome remove(final String arg, final ShellEnv env) {
        final var permissions = env.session().runtime().permissions();
        if (arg == null || arg.isBlank()) {
            return ShellOutcome.error("usage: /permission-remove <anchor...>");
        }
        final var removed = new ArrayList<String>();
        for (final var token : WHITESPACE.split(arg.strip())) {
            if (token.isBlank()) {
                continue;
            }
            permissions.removeSessionGrant(token);
            removed.add(token);
        }
        return ShellOutcome.status("removed: " + String.join(", ", removed));
    }

    /** Used by {@link #show} to decide coverage; delegates to the permission service. */
    static boolean coveredByGlobalRules(final PermissionService permissions, final String anchor) {
        return permissions.coveredByGlobalRules(anchor);
    }
}
