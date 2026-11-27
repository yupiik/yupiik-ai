/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.yupiik.ia.agent.core.cli;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.session.FileSessionStore;
import io.yupiik.ia.agent.core.session.SessionCleanPolicy;

@DefaultScoped
@Command(
        name = {"maintenance", "sessions", "clean"},
        description =
                "Delete stored sessions matching all supplied age/size filters, or sessions.clean defaults when neither is supplied. Normal startup housekeeping still runs, including with --dry-run true.")
public class MaintenanceSessionsCleanCommand implements Runnable {
    private final CleanConfiguration options;
    private final CodingAgentConfiguration configuration;
    private final FileSessionStore sessions;

    public MaintenanceSessionsCleanCommand(
            final CleanConfiguration options,
            final CodingAgentConfiguration configuration,
            final FileSessionStore sessions) {
        this.options = options;
        this.configuration = configuration;
        this.sessions = sessions;
    }

    @RootConfiguration("-")
    public record CleanConfiguration(
            @Property(
                    value = "older-than",
                    documentation =
                            "Delete sessions inactive longer than this positive duration (s, m, h, d, w). CLI criteria replace configured criteria as a set.")
            String olderThan,

            @Property(
                    value = "bigger-than",
                    documentation =
                            "Delete sessions larger than these owned on-disk bytes (e.g. 1M or 1MiB). Combined with older-than using AND.")
            String biggerThan,

            @Property(
                    value = "dry-run",
                    defaultValue = "false",
                    documentation =
                            "Preview this clean operation only; normal startup housekeeping can still change storage.")
            boolean dryRun) {}

    @Override
    public void run() {
        final var policy = SessionCleanPolicy.resolve(
                configuration.sessions() == null
                        ? null
                        : configuration.sessions().clean(),
                options.olderThan(),
                options.biggerThan());
        final var source =
                options.olderThan() != null || options.biggerThan() != null ? "CLI" : "sessions.clean configuration";
        System.out.println("Session cleanup criteria (" + source + "): older-than="
                + (policy.olderThan() == null ? "disabled" : policy.olderThan()) + ", bigger-than="
                + (policy.biggerThanBytes() == null ? "disabled" : policy.biggerThanBytes() + " bytes"));
        if (!policy.enabled()) {
            System.out.println("Session cleanup disabled: no effective criteria.");
            return;
        }
        final var result = sessions.clean(policy, options.dryRun());
        for (final var session : result.sessions()) {
            System.out.println((options.dryRun() ? "Would delete " : "Deleted ") + session.id() + " ("
                    + session.sizeBytes() + " bytes)");
        }
        System.out.println((options.dryRun() ? "Dry-run: would delete " : "Deleted ")
                + result.sessions().size() + " sessions, " + result.bytes() + " estimated bytes; kept=" + result.kept()
                + ", protected=" + result.protectedSessions());
    }
}
