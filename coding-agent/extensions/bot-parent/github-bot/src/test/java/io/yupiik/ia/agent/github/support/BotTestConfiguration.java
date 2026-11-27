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
package io.yupiik.ia.agent.github.support;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.configuration.ConfigurationImpl;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.bot.runtime.DockerControlConfiguration;
import io.yupiik.ia.agent.bot.runtime.admission.HostAdmissionConfiguration;
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderBudgetConfiguration;
import io.yupiik.ia.agent.core.notifications.NotificationService;
import io.yupiik.ia.agent.github.client.DiscussionClient;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.command.PollCommand;
import io.yupiik.ia.agent.github.command.WorkerCommand;
import io.yupiik.ia.agent.github.configuration.ContextConfiguration;
import io.yupiik.ia.agent.github.configuration.FixConfiguration;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.configuration.PromptConfiguration;
import io.yupiik.ia.agent.github.configuration.ReviewPresentationConfiguration;
import io.yupiik.ia.agent.github.context.GithubContextSnapshot;
import io.yupiik.ia.agent.github.context.GithubPrivateContext;
import io.yupiik.ia.agent.github.execution.AttemptPreparation;
import io.yupiik.ia.agent.github.service.BotAudit;
import io.yupiik.ia.agent.github.service.DiscussionPoller;
import io.yupiik.ia.agent.github.service.DiscussionPublisher;
import io.yupiik.ia.agent.github.service.FixPublisher;
import io.yupiik.ia.agent.github.service.ForkProvisioner;
import io.yupiik.ia.agent.github.service.GithubServices;
import io.yupiik.ia.agent.github.service.MentionParser;
import io.yupiik.ia.agent.github.service.Poller;
import io.yupiik.ia.agent.github.service.RepositoryDiscovery;
import io.yupiik.ia.agent.github.service.ReviewPublisher;
import io.yupiik.ia.agent.github.service.ReviewSummaryPoller;
import io.yupiik.ia.agent.github.service.StrictSolvePublisher;
import io.yupiik.ia.agent.github.service.TeamAuthorization;
import io.yupiik.ia.agent.github.service.Worker;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class BotTestConfiguration {
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC);

    private BotTestConfiguration() {}

    public static Map<String, Object> fixRepository(final String repository, final String nodeId) {
        return Map.of(
                "full_name",
                repository,
                "node_id",
                nodeId,
                "archived",
                false,
                "disabled",
                false,
                "permissions",
                Map.of("push", true));
    }

    public static Map<String, Object> fixPull(final String upstream, final String headSha) {
        return Map.of(
                "number",
                12,
                "node_id",
                "PR",
                "state",
                "open",
                "html_url",
                "https://github.com/" + upstream + "/pull/12",
                "base",
                Map.of("repo", fixRepository(upstream, "BASE")),
                "head",
                Map.of("repo", fixRepository("human/fork", "HEAD"), "ref", "feature", "sha", headSha));
    }

    public static Map<String, Object> fixRef(final String headSha) {
        return Map.of("ref", "refs/heads/feature", "object", Map.of("sha", headSha));
    }

    public static GithubBotConfiguration home(final Path home) {
        return configuration(home.toString(), "bot", "");
    }

    public static GithubBotConfiguration configuration(final String home, final String login, final String teams) {
        return new GithubBotConfiguration(
                home,
                null,
                login,
                0,
                null,
                new GithubBotConfiguration.Authorization("42", teams, false, 7200, 4096),
                null);
    }

    public static TaskStore store(final GithubBotConfiguration conf, final JsonMapper json) {
        return new TaskStore(conf, json);
    }

    public static BotAudit audit(final GithubBotConfiguration conf, final JsonMapper json) {
        return new BotAudit(conf, json, CLOCK);
    }

    public static TeamAuthorization authorization(final GitHubClient github, final String teams) {
        return new TeamAuthorization(github, configuration("", "bot", teams), CLOCK);
    }

    public static ReviewPublisher reviewPublisher(
            final ReviewPresentationConfiguration presentation,
            final GitHubClient github,
            final TaskStore store,
            final String login) {
        return new ReviewPublisher(github, store, configuration("", login, ""), presentation);
    }

    public static DiscussionPublisher discussionPublisher(
            final GitHubClient github, final TaskStore store, final String login) {
        return new DiscussionPublisher(new DiscussionClient(github), store, configuration("", login, ""));
    }

    public static Worker worker(
            final PromptConfiguration prompts,
            final ReviewPresentationConfiguration presentation,
            final GithubBotConfiguration conf,
            final TaskStore store,
            final GitHubClient github,
            final JsonMapper json,
            final ExecutorConfiguration execution) {
        return worker(
                prompts,
                presentation,
                conf,
                store,
                github,
                json,
                execution,
                key -> Optional.empty(),
                new ProviderBudgetConfiguration(null, null, null),
                new DockerControlConfiguration("", ""));
    }

    public static Worker worker(
            final PromptConfiguration prompts,
            final ReviewPresentationConfiguration presentation,
            final GithubBotConfiguration conf,
            final TaskStore store,
            final GitHubClient github,
            final JsonMapper json,
            final ExecutorConfiguration execution,
            final Configuration sources,
            final ProviderBudgetConfiguration budgetConfiguration,
            final DockerControlConfiguration dockerControl) {
        return new Worker(
                conf,
                store,
                github,
                json,
                execution,
                audit(conf, json),
                new TeamAuthorization(github, conf, CLOCK),
                new AttemptPreparation(json, prompts, execution),
                new GithubPrivateContext(github, json),
                new GithubContextSnapshot(github, json),
                new ForkProvisioner(github, store),
                new ReviewPublisher(github, store, conf, presentation),
                new StrictSolvePublisher(github, store, json),
                new DiscussionPublisher(new DiscussionClient(github), store, conf),
                new ContextConfiguration("127.0.0.1", 0, "", "", "git", "", 256, 1073741824L, 67108864L),
                new FixPublisher(github, store, json, new FixConfiguration(false, "")),
                new HostAdmissionConfiguration("", 1, ""),
                CLOCK,
                sources,
                budgetConfiguration,
                dockerControl);
    }

    public static ReviewSummaryPoller reviewSummaryPoller(
            final GithubBotConfiguration conf,
            final TaskStore store,
            final GitHubClient github,
            final Clock clock,
            final BotAudit audit) {
        return new ReviewSummaryPoller(
                conf,
                store,
                github,
                clock,
                audit,
                new TeamAuthorization(github, conf, clock),
                new FixConfiguration(false, ""),
                new MentionParser());
    }

    public static DiscussionPoller discussionPoller(
            final GithubBotConfiguration conf,
            final TaskStore store,
            final GitHubClient github,
            final Clock clock,
            final BotAudit audit) {
        return new DiscussionPoller(
                conf,
                store,
                github,
                clock,
                audit,
                new TeamAuthorization(github, conf, clock),
                new DiscussionClient(github),
                new MentionParser());
    }

    public static Poller poller(
            final GithubBotConfiguration conf,
            final TaskStore store,
            final GitHubClient github,
            final Clock clock,
            final BotAudit audit) {
        return new Poller(
                conf,
                store,
                github,
                clock,
                audit,
                new TeamAuthorization(github, conf, clock),
                reviewSummaryPoller(conf, store, github, clock, audit),
                discussionPoller(conf, store, github, clock, audit),
                new RepositoryDiscovery(conf, github, clock),
                new FixConfiguration(false, ""),
                new MentionParser());
    }

    public static PollCommand pollCommand(
            final PollCommand.Options options,
            final GithubBotConfiguration conf,
            final JsonMapper json,
            final TaskStore store,
            final BotAudit audit) {
        return new PollCommand(options, null) {
            @Override
            public void run() {
                try (final var runtime =
                        new GithubServices().client(conf, json, CLOCK, new ConfigurationImpl(List.of()))) {
                    new PollCommand(options, poller(conf, store, runtime, CLOCK, audit)).run();
                }
            }
        };
    }

    public static WorkerCommand workerCommand(
            final PromptConfiguration prompts,
            final ReviewPresentationConfiguration presentation,
            final WorkerCommand.Options options,
            final GithubBotConfiguration conf,
            final JsonMapper json,
            final TaskStore store,
            final BotAudit audit,
            final ExecutorConfiguration execution,
            final NotificationService notifications) {
        return new WorkerCommand(options, store, null, notifications) {
            @Override
            public void run() {
                try (final var runtime =
                        new GithubServices().client(conf, json, CLOCK, new ConfigurationImpl(List.of()))) {
                    new WorkerCommand(
                                    options,
                                    store,
                                    worker(prompts, presentation, conf, store, runtime, json, execution),
                                    notifications)
                            .run();
                }
            }
        };
    }
}
