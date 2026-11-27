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
import io.yupiik.ia.agent.core.usage.CodexUsageSnapshot;
import io.yupiik.ia.agent.core.usage.QuotaSnapshotStore;
import java.time.Instant;
import java.util.List;

/**
 * {@code /usage} slash command: shows the current provider's account usage/rate-limit quota for
 * this session and forces a refresh of the cached snapshot. Available only when the current
 * provider enables the Codex usage quota ({@code capabilities.codex.enableUsage}); otherwise it
 * reports that the provider does not expose usage (read-only, no reset action).
 */
@ApplicationScoped
public class UsageCommands implements SlashCommandSet {

    private final QuotaSnapshotStore quotaStore;

    public UsageCommands(final QuotaSnapshotStore quotaStore) {
        this.quotaStore = quotaStore;
    }

    @Override
    public String title() {
        return "Usage inspection";
    }

    @Override
    public List<SlashCommand> commands() {
        return List.of(SlashCommand.of(
                new SlashCommandSpec(
                        "/usage",
                        null,
                        "show the current provider's account usage/rate-limit quota (forces a refresh; only for providers that enable Codex usage)",
                        SlashGroup.INSPECT),
                SlashAction.sync(this::usage)));
    }

    private ShellOutcome usage(final String arg, final ShellEnv env) {
        final var sessionId = env.session() == null ? null : env.session().sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return ShellOutcome.status("no active session to show usage for");
        }
        // ensure this session's provider is attached (first run) and force a fresh fetch
        if (env.session().chatSettings() != null) {
            quotaStore.attach(sessionId, env.session().chatSettings());
        }
        final var view = quotaStore.refresh(sessionId);
        // push the freshly-refreshed snapshot to the TUI/web sidebar gauge (a /usage always
        // reflects live usage, including a fully-reached quota), so it is not hidden behind the
        // command's own table: re-emit a UsageUpdated exactly like a provider switch does.
        final var loop =
                env.session().runtime() == null ? null : env.session().runtime().loop();
        if (loop != null) {
            loop.pushUsageQuota();
        }
        if (!view.supported()) {
            return ShellOutcome.status(
                    "usage not supported by the current provider (set capabilities.codex.enableUsage to turn it on)");
        }
        if (view.snapshot() == null
                || view.snapshot().planType() == null
                || view.snapshot().rateLimits() == null
                || view.snapshot().rateLimits().isEmpty()) {
            return ShellOutcome.status(
                    "no usage data available for this account (are you logged in? run /codex login)");
        }
        return ShellOutcome.transcript(render(view.snapshot()));
    }

    /** Renders the usage snapshot as a markdown table (shared shape with the other inspection commands). */
    private static String render(final CodexUsageSnapshot snapshot) {
        final var sb = new StringBuilder("# Usage\n\n");
        sb.append("plan: ")
                .append(snapshot.planType() == null ? "(unknown)" : quoted(snapshot.planType()))
                .append('\n');
        final Object credits = snapshot.credits() == null
                ? null
                : snapshot.credits().unlimited() != null && snapshot.credits().unlimited()
                        ? "unlimited"
                        : snapshot.credits().balance();
        if (credits != null) {
            sb.append("credits: ").append(credits).append('\n');
        }
        sb.append('\n');
        final var header = List.of("window", "used", "reset");
        final var rows = snapshot.rateLimits().stream()
                .map(l -> List.of(
                        SlashRegistry.cell(l.label() == null ? l.kind() : l.label()),
                        l.usedPercent() == null ? "-" : Math.round(l.usedPercent()) + "%",
                        l.resetAt() == null
                                ? "-"
                                : Instant.ofEpochSecond(l.resetAt()).toString()))
                .toList();
        if (!rows.isEmpty()) {
            sb.append(SlashRegistry.table(header, rows));
        } else {
            sb.append("(no rate-limit window reported)\n");
        }
        return sb.toString();
    }

    private static String quoted(final String value) {
        return "`" + value + "`";
    }
}
