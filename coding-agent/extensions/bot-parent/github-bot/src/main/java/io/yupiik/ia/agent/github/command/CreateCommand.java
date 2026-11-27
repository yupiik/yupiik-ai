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
package io.yupiik.ia.agent.github.command;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.client.RestResponses;
import io.yupiik.ia.agent.github.configuration.FixConfiguration;
import io.yupiik.ia.agent.github.model.TaskKind;
import io.yupiik.ia.agent.github.service.BotAudit;
import io.yupiik.ia.agent.github.service.FixRequestAuthority;
import io.yupiik.ia.agent.github.store.MutableTask;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;

@DefaultScoped
@Command(
        name = {"github", "bot", "create"},
        description = "Create a trusted operator task.")
public class CreateCommand implements Runnable {
    private static final Pattern REPOSITORY_NAME = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final Logger LOGGER = Logger.getLogger(CreateCommand.class.getName());
    private final Options options;
    private final JsonMapper json;
    private final TaskStore store;
    private final BotAudit audit;
    private final Clock clock;
    private final FixRequestAuthority fixRequests;
    private final GitHubClient github;

    public CreateCommand(
            final Options options,
            final JsonMapper json,
            final TaskStore store,
            final BotAudit audit,
            final Clock clock,
            final GitHubClient github,
            final FixConfiguration fix) {
        this.options = options;
        this.json = json;
        this.clock = clock;
        this.store = store;
        this.audit = audit;
        this.github = github;
        this.fixRequests = new FixRequestAuthority(github, fix);
    }

    @RootConfiguration("-")
    public record Options(
            @Property(defaultValue = "\"\"", documentation = "Exact owner/repository for manual creation.")
            String repository,

            @Property(defaultValue = "0", documentation = "Issue or pull request number for creation.")
            int number,

            @Property(
                    defaultValue = "io.yupiik.ia.agent.github.model.TaskKind.solve",
                    documentation = "Task kind: solve, review, help or fix (explicit opt-in, open PR only).")
            TaskKind kind,

            @Property(defaultValue = "\"\"", documentation = "Manual request text (trusted operator input).")
            String request,

            @Property(
                    defaultValue = "false",
                    documentation = "Validate and preview without changing tasks or audit events.")
            boolean dryRun) {}

    @Override
    public void run() {
        if (!REPOSITORY_NAME.matcher(options.repository()).matches()
                || options.number() <= 0
                || options.kind() == null) {
            throw new IllegalArgumentException("Valid repository, positive number and task kind required");
        }
        final var task = MutableTask.create();
        task.repository(options.repository());
        task.number((long) options.number());
        task.kind(options.kind());
        task.request(options.request());
        task.createdAt(clock.instant().toString());
        task.reference("https://github.com/" + options.repository() + "/issues/" + options.number());
        if (options.kind() == TaskKind.review) {
            final var parent = github.get(
                    "/repos/" + options.repository() + "/issues/" + options.number(), RestResponses.Issue.class);
            task.reviewTarget(parent.pull_request() != null ? "pull_request" : "issue");
        }
        if (options.kind() == TaskKind.fix) {
            task.reference("https://github.com/" + options.repository() + "/pull/" + options.number());
            task.recovery(Map.of("fixTarget", fixRequests.pinRequest(task.document())));
        }
        if (options.dryRun()) {
            LOGGER.info(json.toString(task.document()));
            return;
        }
        final String id = store.transaction(state -> state.enqueue("manual:" + UUID.randomUUID(), task.document()));
        task.id(id);
        audit.record("manual-created", task.document());
        LOGGER.info(id);
    }
}
