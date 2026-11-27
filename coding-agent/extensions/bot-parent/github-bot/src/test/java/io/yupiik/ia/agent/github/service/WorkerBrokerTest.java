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
package io.yupiik.ia.agent.github.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.bot.runtime.DockerControlConfiguration;
import io.yupiik.ia.agent.bot.runtime.admission.HostAdmissionConfiguration;
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.bot.runtime.execution.DockerExecutionBackend;
import io.yupiik.ia.agent.core.configuration.ProviderBudgetConfiguration;
import io.yupiik.ia.agent.github.client.DiscussionClient;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.command.WorkerCommand.Options;
import io.yupiik.ia.agent.github.configuration.ContextConfiguration;
import io.yupiik.ia.agent.github.configuration.FixConfiguration;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.configuration.GithubExecutorConfiguration;
import io.yupiik.ia.agent.github.configuration.PromptConfiguration;
import io.yupiik.ia.agent.github.configuration.ReviewPresentationConfiguration;
import io.yupiik.ia.agent.github.configuration.WorkerOptions;
import io.yupiik.ia.agent.github.context.GithubContextSnapshot;
import io.yupiik.ia.agent.github.context.GithubPrivateContext;
import io.yupiik.ia.agent.github.execution.AttemptPreparation;
import io.yupiik.ia.agent.github.execution.GithubExecutionPolicy;
import io.yupiik.ia.agent.github.model.TaskState;
import io.yupiik.ia.agent.github.store.TaskStore;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import io.yupiik.ia.agent.github.support.TestScript;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD) // The class owns one stable configuration and its fixture paths/transport.
@FusionSupport(modules = WorkerBrokerTest.Source.class)
class WorkerBrokerTest {
    @Fusion
    private Options injectedOptions;

    @Fusion
    private GithubExecutorConfiguration injectedExecutor;

    @Fusion
    private PromptConfiguration injectedPrompts;

    @Fusion
    private ReviewPresentationConfiguration injectedPresentation;

    private static final String HEAD = "a".repeat(40);

    @TempDir
    static Path home;

    @TempDir
    static Path attempts;

    @BeforeEach
    void resetState() throws Exception {
        try (final var paths = Files.walk(home)) {
            for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(home)) {
                    Files.delete(path);
                }
            }
        }
    }

    @Test
    void brokerRejectsAgentCommentingAndMixedStagingBeforeExecution() throws Exception {
        try (final var fixture = new Fixture()) {
            for (final var settings : List.of(
                    Map.of("worker.agentCommenting", "true"),
                    Map.of("worker.privateSource", "true"),
                    Map.of("worker.cacheRoot", home.resolve("cache").toString()))) {
                final var id = fixture.enqueue("review");
                final var failure = assertThrows(
                        IllegalArgumentException.class, () -> fixture.worker().run(id, options(settings)));
                assertTrue(failure.getMessage().contains("Broker mode"));
                assertEquals(
                        TaskState.pending,
                        fixture.store.transaction(state -> state.task(id).state()));
            }
            assertTrue(fixture.calls.isEmpty());
            assertFalse(Files.exists(fixture.invocations));
        }
    }

    @Test
    void nonCanonicalApiFailsClosedWithoutLaunchingBackendOrEagerReviewContext() throws Exception {
        assumeTrue(
                ((Number) Files.getAttribute(home, "unix:uid")).intValue() != 0,
                "The production Docker dispatcher intentionally refuses root");
        try (final var fixture = new Fixture()) {
            final var id = fixture.enqueue("review");
            fixture.worker().run(id, options(Map.of()));
            assertFalse(Files.exists(fixture.invocations));
            assertTrue(
                    fixture.calls.stream()
                            .allMatch(it -> it.endsWith("/repos/owner/repo/issues/7/comments?per_page=100")
                                    || it.endsWith("/repos/owner/repo/issues/7/comments")),
                    "Only host failure-comment reconciliation/publication is permitted, not eager review context: "
                            + fixture.calls);
            assertEquals(
                    0,
                    fixture.store
                            .<Integer>transaction(state -> state.task(id).attempts())
                            .intValue());
            assertEquals(
                    TaskState.failed,
                    fixture.store.transaction(state -> state.task(id).state()));
            assertNull(fixture.store.transaction(state -> state.task(id).attemptDirectory()));
            assertFalse(Files.exists(home.resolve("private-context")));
        }
    }

    @Test
    void brokerSolveAndFixStageMetadataWithoutTreeOrBlobRequests() throws Exception {
        try (final var fixture = new Fixture()) {
            final var preparation = new AttemptPreparation(fixture.json, injectedPrompts, fixture.backend);
            final var source = new GithubPrivateContext(fixture.github, fixture.json);
            final var solve = preparation.prepare(home.resolve("agent-configuration.json"), home.resolve("attempts"));
            final var fix = preparation.prepare(home.resolve("agent-configuration.json"), home.resolve("attempts"));
            try {
                final var pinned = source.stageSolve(
                        TaskFixtures.task(
                                Map.of("id", "solve", "repository", "owner/repo", "kind", "solve", "number", 7)),
                        solve,
                        WorkerOptions.SourceMode.broker);
                assertEquals(HEAD, pinned.baseSha());
                assertEquals(List.of("GET /repos/owner/repo", "GET /repos/owner/repo/commits/main"), fixture.calls);
                fixture.calls.clear();
                source.stageFix(
                        TaskFixtures.task(Map.of("id", "fix", "repository", "owner/repo", "kind", "fix", "number", 7)),
                        fix,
                        "owner/repo",
                        HEAD,
                        "feature",
                        WorkerOptions.SourceMode.broker);
                assertTrue(fixture.calls.isEmpty());
                for (final var attempt : List.of(solve, fix)) {
                    assertTrue(Files.isRegularFile(attempt.resolve("workspace/github-solve.json")));
                    assertFalse(Files.exists(attempt.resolve("workspace/repository")));
                    assertFalse(Files.exists(attempt.resolve("workspace/github-source.json")));
                    assertFalse(Files.readString(attempt.resolve("workspace/github-solve.json"))
                            .contains("host-secret"));
                }
            } finally {
                AttemptPreparation.delete(solve);
                AttemptPreparation.delete(fix);
            }
        }
    }

    @Test
    void brokerCredentialsReachFakeDockerOnlyThroughAllowlistedEnvironmentNotArgumentsOrFiles() throws Exception {
        assumeTrue(
                ((Number) Files.getAttribute(home, "unix:uid")).intValue() != 0,
                "The production Docker dispatcher intentionally refuses root");
        try (final var fixture = new Fixture()) {
            final var attempt = new AttemptPreparation(fixture.json, injectedPrompts, fixture.backend)
                    .prepare(home.resolve("agent-configuration.json"), home.resolve("attempts"));
            try {
                final var executable = home.resolve("environment-docker");
                Files.writeString(
                        executable,
                        "#!/bin/sh\nset -eu\n" + TestScript.githubAgentState()
                                + "if [ \"$1 $2 $3\" = 'container inspect --format' ]; then agent_state \"$@\"; exit 0; fi\n"
                                + "printf '%s\\n' \"$@\" > '" + fixture.invocations + "'\n"
                                + "test \"${YCA_CONTEXT_TOKEN-}\" = 'control-secret'\n"
                                + "test \"${YCA_CONTEXT_GIT_TOKEN-}\" = 'git-secret'\n"
                                + "test -z \"${GH_TOKEN-}\"\ntest -z \"${GITHUB_BOT_FORK_TOKEN-}\"\n"
                                + "test -z \"${GITHUB_SOURCE_TOKEN-}\"\nexit 0\n");
                Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
                final var backend = new DockerExecutionBackend(
                        GithubExecutionPolicy.POLICY,
                        executable.toString(),
                        "trusted-agent",
                        Duration.ofSeconds(5),
                        "",
                        null,
                        null,
                        "shown",
                        false);
                assertEquals(
                        0,
                        backend.execute(
                                attempt,
                                "yca-task-1",
                                "credential-free prompt",
                                Map.of("YCA_CONTEXT_TOKEN", "control-secret", "YCA_CONTEXT_GIT_TOKEN", "git-secret"),
                                Map.of()));
                final var arguments = Files.readString(fixture.invocations);
                assertTrue(arguments.contains("YCA_CONTEXT_TOKEN\n"));
                assertTrue(arguments.contains("YCA_CONTEXT_GIT_TOKEN\n"));
                for (final var forbidden :
                        List.of("control-secret", "git-secret", "host-secret", "GH_TOKEN", "GITHUB_SOURCE_TOKEN")) {
                    assertFalse(arguments.contains(forbidden));
                }
                try (final var files = Files.walk(attempt)) {
                    for (final var file : files.filter(Files::isRegularFile).toList()) {
                        final var content = Files.readString(file);
                        assertFalse(content.contains("control-secret"), file.toString());
                        assertFalse(content.contains("git-secret"), file.toString());
                    }
                }
                assertThrows(
                        IllegalArgumentException.class,
                        () -> backend.execute(
                                attempt, "yca-task-2", "prompt", Map.of("UNTRUSTED_CREDENTIAL", "secret"), Map.of()));
            } finally {
                AttemptPreparation.delete(attempt);
            }
        }
    }

    private WorkerOptions options(final Map<String, String> overrides) {
        final var baseline = injectedOptions.worker();
        return new WorkerOptions(
                baseline.maxRetries(),
                baseline.parallelism(),
                baseline.agentConfiguration(),
                baseline.forkOwner(),
                Boolean.parseBoolean(overrides.getOrDefault("worker.agentCommenting", "false")),
                baseline.provisionFork(),
                overrides.getOrDefault("worker.cacheRoot", baseline.cacheRoot()),
                baseline.cacheGit(),
                Boolean.parseBoolean(overrides.getOrDefault("worker.privateSource", "false")),
                baseline.source(),
                baseline.dependencyCache());
    }

    private final class Fixture implements AutoCloseable {
        private final JsonMapper json = BotJsonMapper.create(key -> Optional.empty());
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private final HttpServer server;
        private final GitHubClient github;
        private final GithubBotConfiguration conf;
        private final TaskStore store;
        private final ExecutorConfiguration backend;
        private final Path invocations = home.resolve("backend-invocations");
        private int sequence;

        private Fixture() throws Exception {
            Files.writeString(home.resolve("agent-configuration.json"), "{}");
            final var executable = Files.writeString(
                    home.resolve("fake-docker"),
                    "#!/bin/sh\nprintf '%s\\n' \"$@\" >> '" + invocations + "'\nexit 97\n");
            Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
            backend = injectedExecutor.shared();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                final Object response =
                        switch (exchange.getRequestURI().getPath()) {
                            case "/repos/owner/repo" ->
                                Map.of("id", 12, "full_name", "owner/repo", "default_branch", "main");
                            case "/repos/owner/repo/commits/main" -> Map.of("sha", HEAD);
                            case "/repos/owner/repo/issues/7/comments" ->
                                "GET".equals(exchange.getRequestMethod())
                                        ? List.of()
                                        : Map.of("html_url", "https://github.com/owner/repo/issues/7#issuecomment-99");
                            default ->
                                Map.of("unexpected", exchange.getRequestURI().toString());
                        };
                final var bytes = json.toString(response).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            final var api = "http://127.0.0.1:" + server.getAddress().getPort();
            conf = new GithubBotConfiguration(
                    home.toString(),
                    api,
                    "bot",
                    0,
                    new GithubBotConfiguration.Auth(
                            GithubBotConfiguration.AuthType.pat, "GH_TOKEN", "", 0, "", "GH_AGENT_TOKEN"),
                    new GithubBotConfiguration.Authorization("42", "", false, 7200, 4096),
                    new GithubBotConfiguration.Repositories(
                            "owner/repo", "", "", "", List.of(RepositorySelector.Visibility.values()), false, false));
            github = new GitHubClient(URI.create(api), json, () -> "host-secret", BotTestConfiguration.CLOCK);
            store = new TaskStore(conf, json);
        }

        private String enqueue(final String kind) {
            return store.transaction(state -> state.enqueue(
                    "comment:" + ++sequence,
                    TaskFixtures.task(Map.of(
                            "repository",
                            "owner/repo",
                            "number",
                            7,
                            "actorId",
                            42,
                            "kind",
                            kind,
                            "request",
                            "Review pinned source"))));
        }

        private Worker worker() {
            return new Worker(
                    conf,
                    store,
                    github,
                    json,
                    backend,
                    BotTestConfiguration.audit(conf, json),
                    new TeamAuthorization(github, conf, BotTestConfiguration.CLOCK),
                    new AttemptPreparation(json, injectedPrompts, backend),
                    new GithubPrivateContext(github, json),
                    new GithubContextSnapshot(github, json),
                    new ForkProvisioner(github, store),
                    new ReviewPublisher(github, store, conf, injectedPresentation),
                    new StrictSolvePublisher(github, store, json),
                    new DiscussionPublisher(new DiscussionClient(github), store, conf),
                    new ContextConfiguration(
                            "127.0.0.1",
                            0,
                            "https://worker.example",
                            home.resolve("private-context").toString(),
                            "git",
                            "",
                            256,
                            1073741824L,
                            67108864L),
                    new FixPublisher(github, store, json, new FixConfiguration(false, "")),
                    new HostAdmissionConfiguration("", 1, ""),
                    BotTestConfiguration.CLOCK,
                    key -> Optional.empty(),
                    new ProviderBudgetConfiguration(null, null, null),
                    new DockerControlConfiguration("", ""));
        }

        @Override
        public void close() {
            github.close();
            server.stop(0);
            json.close();
        }
    }

    public static class Source extends GithubConfigurationTestModule {
        public Source() {
            super(Map.of(
                    "worker.source.mode",
                    "broker",
                    "worker.agentCommenting",
                    "false",
                    "github.executor.docker.executable",
                    home.resolve("fake-docker").toString(),
                    "github.executor.docker.image",
                    "trusted-agent",
                    "github.executor.timeoutSeconds",
                    "5",
                    "github.executor.attemptRoot",
                    attempts.toString()));
        }
    }
}
