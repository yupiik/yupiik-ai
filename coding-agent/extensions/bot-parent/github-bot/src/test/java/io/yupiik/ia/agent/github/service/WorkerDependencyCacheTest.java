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
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.command.WorkerCommand.Options;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.configuration.GithubExecutorConfiguration;
import io.yupiik.ia.agent.github.configuration.PromptConfiguration;
import io.yupiik.ia.agent.github.configuration.ReviewPresentationConfiguration;
import io.yupiik.ia.agent.github.configuration.WorkerOptions;
import io.yupiik.ia.agent.github.execution.cache.DependencyCache;
import io.yupiik.ia.agent.github.model.TaskState;
import io.yupiik.ia.agent.github.store.TaskStore;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import io.yupiik.ia.agent.github.support.TestScript;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Worker integration only: the fake CLI observes the same mounts and environment as the real agent. */
@Execution(ExecutionMode.SAME_THREAD) // The class owns one stable configuration and its fixture paths/transport.
@FusionSupport(modules = WorkerDependencyCacheTest.Source.class)
class WorkerDependencyCacheTest {
    @Fusion
    private GithubBotConfiguration injectedConfiguration;

    @Fusion
    private GithubExecutorConfiguration injectedExecutor;

    @Fusion
    private Options injectedOptions;

    @Fusion
    private PromptConfiguration injectedPrompts;

    @Fusion
    private ReviewPresentationConfiguration injectedPresentation;

    private static final String SHA = "a".repeat(40);
    private static final String ARTIFACT = "caches/maven/repository/org/example/library/1.0/library-1.0.jar";
    private static final String SETTINGS =
            "<settings><localRepository>/attempt/storage/caches/maven/repository</localRepository></settings>\n";

    @TempDir
    static Path root;

    private Path home;
    private Path attempts;
    private Path cacheRoot;
    private Path executable;
    private JsonMapper json;
    private TaskStore store;
    private GithubBotConfiguration conf;
    private ExecutorConfiguration execution;
    private GitHubClient github;
    private static HttpServer apiServer;
    private HttpServer server;
    private String api;
    private volatile Map<String, Object> repository = Map.of("id", 12345);
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> repositoryAuthorizations = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));
        assumeTrue(
                ((Number) Files.getAttribute(root, "unix:uid")).intValue() != 0,
                "Docker backend requires a non-root dispatcher");
        try (final var paths = Files.walk(root)) {
            for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(root)) {
                    Files.delete(path);
                }
            }
        }
        home = Files.createDirectory(root.resolve("state"));
        attempts = Files.createDirectory(root.resolve("attempts"));
        cacheRoot = root.resolve("dependencies");
        executable = root.resolve("fake-docker");
        Files.writeString(home.resolve("agent-configuration.json"), "{}");
        json = BotJsonMapper.create(key -> Optional.empty());
        store = new TaskStore(BotTestConfiguration.home(home), json);
        server = apiServer;
        server.createContext("/", exchange -> {
            final var path = exchange.getRequestURI().getPath();
            final var method = exchange.getRequestMethod();
            requests.add(method + " " + path);
            final Object response;
            int status = 200;
            if ("GET".equals(method) && path.equals("/repos/owner/repo")) {
                repositoryAuthorizations.add(
                        String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                response = repository;
            } else if ("GET".equals(method) && path.equals("/repos/owner/repo/pulls/12")) {
                response = Map.of("head", Map.of("sha", SHA));
            } else if ("POST".equals(method) && path.equals("/repos/owner/repo/pulls/12/reviews")) {
                response = Map.of("html_url", "https://github.com/owner/repo/pull/12#pullrequestreview-99");
            } else if ("POST".equals(method) && path.equals("/repos/owner/repo/issues/12/comments")) {
                response = Map.of("html_url", "https://github.com/owner/repo/issues/12#issuecomment-99");
            } else if ("GET".equals(method)
                    && (path.endsWith("/reviews") || path.endsWith("/files") || path.endsWith("/comments"))) {
                response = List.of();
            } else {
                status = 404;
                response = Map.of();
            }
            final var bytes = json.toString(response).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        api = "http://127.0.0.1:" + server.getAddress().getPort();
        conf = injectedConfiguration;
        github = new GitHubClient(URI.create(api), json, () -> "dispatcher-token", BotTestConfiguration.CLOCK);
        execution = injectedExecutor.shared();
    }

    @AfterEach
    void tearDown() {
        if (github != null) {
            github.close();
        }
        if (server != null) {
            server.removeContext("/");
        }
        if (json != null) {
            json.close();
        }
    }

    @Test
    void offDoesNotResolveIdentityCreateRootOrProvisionTools() throws Exception {
        final var id = enqueue("off");
        agent(id, "success", "not-cached");
        run(id, options("OFF", cacheRoot));
        assertEquals(TaskState.done, store.transaction(state -> state.task(id).state()));
        assertFalse(requests.contains("GET /repos/owner/repo"));
        assertTrue(repositoryAuthorizations.isEmpty());
        assertFalse(Files.exists(cacheRoot));
        final var arguments = Files.readAllLines(observation(id, "arguments"));
        assertFalse(arguments.stream().anyMatch(line -> line.startsWith("MAVEN_OPTS=")));
        assertEquals("absent", Files.readString(observation(id, "settings")));
        assertEquals("cold", Files.readString(observation(id, "restored")));
    }

    @Test
    void successfulAttemptsRestoreAndPublishUpdatesButRegenerateConfiguration() throws Exception {
        final var options = options("READ_WRITE", cacheRoot);
        final var first = enqueue("first");
        agent(first, "success", "first-version");
        run(first, options);
        assertEquals(
                TaskState.done, store.transaction(state -> state.task(first).state()));
        assertEquals("cold", Files.readString(observation(first, "restored")));
        assertProvisioned(first);
        final var cache = new DependencyCache(options.dependencyCache(), BotTestConfiguration.CLOCK);
        final var firstStorage = root.resolve("inspect-first");
        final var firstBaseline = cache.restore(identity(), firstStorage);
        assertEquals("first-version", Files.readString(firstStorage.resolve(ARTIFACT)));
        assertFalse(firstBaseline.revisions().get("maven").isEmpty());
        assertFalse(Files.exists(firstStorage.resolve(".m2/settings.xml")), "Generated files are not cached");

        final var second = enqueue("second");
        agent(second, "success", "second-version");
        run(second, options);
        assertEquals(
                TaskState.done, store.transaction(state -> state.task(second).state()));
        assertEquals("first-version", Files.readString(observation(second, "restored")));
        assertProvisioned(second);
        final var secondStorage = root.resolve("inspect-second");
        final var secondBaseline = cache.restore(identity(), secondStorage);
        assertEquals("second-version", Files.readString(secondStorage.resolve(ARTIFACT)));
        assertNotEquals(
                firstBaseline.revisions().get("maven"),
                secondBaseline.revisions().get("maven"));
        assertEquals(List.of("Bearer dispatcher-token", "Bearer dispatcher-token"), repositoryAuthorizations);
        assertEquals(
                1,
                store.<Integer>transaction(state -> state.task(first).attempts())
                        .intValue());
        assertEquals(
                1,
                store.<Integer>transaction(state -> state.task(second).attempts())
                        .intValue());
        try (final var children = Files.list(attempts)) {
            assertEquals(0, children.count(), "Successful attempts are cleaned after cache publication");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"exit-failure", "agent-failure", "missing", "invalid", "wrong-task", "uncertain-stop"})
    void unsuccessfulOrUnconfirmedAttemptsNeverPublish(final String outcome) throws Exception {
        final var options = options("READ_WRITE", cacheRoot);
        final var id = enqueue(outcome);
        agent(id, outcome, "must-not-publish");
        if ("uncertain-stop".equals(outcome)) {
            assertThrows(IllegalStateException.class, () -> run(id, options));
            assertEquals(
                    TaskState.running, store.transaction(state -> state.task(id).state()));
            assertTrue(Files.isDirectory(
                    Path.of(store.<String>transaction(state -> state.task(id).attemptDirectory()))));
        } else {
            run(id, options);
            assertEquals(
                    TaskState.failed, store.transaction(state -> state.task(id).state()));
            final var expectedStage =
                    switch (outcome) {
                        case "exit-failure" -> "execution";
                        case "agent-failure" -> "agent-result";
                        default -> "result-validation";
                    };
            assertEquals(
                    expectedStage,
                    store.transaction(state -> state.task(id).lastFailure().stage()));
        }
        assertTrue(Files.exists(observation(id, "arguments")), "The agent reached launch and wrote cache data");
        assertEquals(
                1,
                store.<Integer>transaction(state -> state.task(id).attempts()).intValue());
        final var restored = root.resolve("inspect-failed");
        final var baseline = new DependencyCache(options.dependencyCache(), BotTestConfiguration.CLOCK)
                .restore(identity(), restored);
        assertEquals("", baseline.revisions().get("maven"));
        assertFalse(Files.exists(restored.resolve(ARTIFACT)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"state", "state-child", "attempts", "attempt-child", "ancestor"})
    void overlappingRootsAreRejectedBeforeLaunch(final String overlap) throws Exception {
        final var target =
                switch (overlap) {
                    case "state" -> home;
                    case "state-child" -> home.resolve("dependencies");
                    case "attempts" -> attempts;
                    case "attempt-child" -> attempts.resolve("dependencies");
                    default -> root;
                };
        final var id = enqueue(overlap);
        agent(id, "success", "must-not-publish");
        run(id, options("READ_WRITE", target));
        assertEquals(TaskState.failed, store.transaction(state -> state.task(id).state()));
        assertEquals(
                "dependency-cache",
                store.transaction(state -> state.task(id).lastFailure().stage()));
        assertFalse(Files.exists(observation(id, "arguments")), "Unsafe cache root must fail before Docker launch");
        assertFalse(requests.contains("GET /repos/owner/repo"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "zero", "negative", "name"})
    void invalidRepositoryIdentityRunsColdWithoutPublication(final String invalid) throws Exception {
        repository = switch (invalid) {
            case "missing" -> Map.of();
            case "zero" -> Map.of("id", 0);
            case "negative" -> Map.of("id", -1);
            default -> Map.of("id", "owner/repo");
        };
        final var id = enqueue(invalid);
        agent(id, "success", "must-not-publish");
        run(id, options("READ_WRITE", cacheRoot));
        assertEquals(TaskState.done, store.transaction(state -> state.task(id).state()));
        assertEquals(
                1,
                store.<Integer>transaction(state -> state.task(id).attempts()).intValue());
        assertEquals("cold", Files.readString(observation(id, "restored")));
        assertProvisioned(id);
        assertEquals(List.of("Bearer dispatcher-token"), repositoryAuthorizations);
        assertFalse(Files.exists(cacheRoot), "No mutable-name cache fallback or publication is allowed");
    }

    @Test
    void publicationQuotaFailureDoesNotRetryOrFailSuccessfulAgent() throws Exception {
        // Enough room for the empty store, but not for publication staging/index reservations.
        final var configuredOptions = options("READ_WRITE", cacheRoot);
        final var cache = configuredOptions.dependencyCache();
        final var limits = cache.limits();
        final var quota = new DependencyCacheConfiguration(
                cache.mode(),
                cache.root(),
                cache.scope(),
                cache.compatibility(),
                cache.presets(),
                cache.entries(),
                cache.environment(),
                cache.files(),
                cache.gracefulTimeoutSeconds(),
                new DependencyCacheConfiguration.Maintenance(cache.maintenance().resetAfterDays(), 200000),
                new DependencyCacheConfiguration.Limits(
                        limits.maxFiles(),
                        limits.maxFileBytes(),
                        12288,
                        limits.operationTimeoutSeconds(),
                        limits.maxConcurrentOperations()));
        final var options = cacheOptions(configuredOptions, 2, quota);
        final var id = enqueue("quota-failure");
        agent(id, "success", "not-admitted");
        run(id, options);
        assertEquals(TaskState.done, store.transaction(state -> state.task(id).state()));
        assertEquals(
                1,
                store.<Integer>transaction(state -> state.task(id).attempts()).intValue());
        assertProvisioned(id);
        assertTrue(Files.isRegularFile(cacheRoot.resolve("state")), "The store was initialized before publication");
        final var restored = root.resolve("inspect-quota-failure");
        final var baseline = new DependencyCache(options.dependencyCache(), BotTestConfiguration.CLOCK)
                .restore(identity(), restored);
        assertEquals("", baseline.revisions().get("maven"));
        assertFalse(Files.exists(restored.resolve(ARTIFACT)));
    }

    private void assertProvisioned(final String id) throws Exception {
        final var arguments = Files.readAllLines(observation(id, "arguments"));
        final var environment = "MAVEN_OPTS=-Dmaven.repo.local=/attempt/storage/caches/maven/repository";
        final var index = arguments.indexOf(environment);
        assertTrue(index > 0);
        assertEquals("--env", arguments.get(index - 1));
        assertEquals(SETTINGS, Files.readString(observation(id, "settings")));
        assertFalse(
                arguments.stream().anyMatch(line -> line.contains(cacheRoot.toString())),
                "The shared host cache is never mounted into the agent");
    }

    private String enqueue(final String invocation) {
        return store.transaction(state -> state.enqueue(
                invocation,
                TaskFixtures.task(Map.of("repository", "owner/repo", "number", 12, "actorId", 42, "kind", "review"))));
    }

    private WorkerOptions options(final String mode, final Path cache) {
        final var baseline = injectedOptions.worker();
        final var policy = baseline.dependencyCache();
        return cacheOptions(
                baseline,
                baseline.maxRetries(),
                new DependencyCacheConfiguration(
                        DependencyCacheConfiguration.Mode.valueOf(mode),
                        cache.toString(),
                        policy.scope(),
                        policy.compatibility(),
                        policy.presets(),
                        policy.entries(),
                        policy.environment(),
                        policy.files(),
                        policy.gracefulTimeoutSeconds(),
                        policy.maintenance(),
                        policy.limits()));
    }

    private void run(final String id, final WorkerOptions options) {
        BotTestConfiguration.worker(injectedPrompts, injectedPresentation, conf, store, github, json, execution)
                .run(id, options);
    }

    private String identity() {
        return api + "#12345";
    }

    private Path observation(final String id, final String suffix) {
        return root.resolve(id + "-" + suffix);
    }

    private void agent(final String id, final String outcome, final String artifact) throws Exception {
        final var result =
                switch (outcome) {
                    case "missing" -> "";
                    case "invalid" -> "printf '[]' > \"$output/result.json\"\n";
                    default ->
                        "cat > \"$output/result.json\" <<'RESULT'\n"
                                + json.toString(
                                        "agent-failure".equals(outcome)
                                                ? Map.of(
                                                        "schemaVersion",
                                                        1,
                                                        "taskId",
                                                        id,
                                                        "failure",
                                                        "Agent could not complete")
                                                : Map.of(
                                                        "schemaVersion",
                                                        1,
                                                        "taskId",
                                                        "wrong-task".equals(outcome) ? "other" : id,
                                                        "summary",
                                                        "Reviewed",
                                                        "review",
                                                        Map.of("headSha", SHA, "body", "Reviewed")))
                                + "\nRESULT\n";
                };
        // Only exact ownership probes report absent services resources; runtime probes remain independent.
        final var script = """
                #!/bin/sh
                if [ "$#" = 5 ] && [ "$2 $3" = 'inspect --format' ]; then
                  case "$4" in
                    *io.yupiik.yca.bot.runtime*io.yupiik.yca.bot.component*)
                      case "$1:$5" in
                        container:yca-*-services|container:yca-*-egress) printf 'Error: No such container: %%s\\n' "$5"; exit 1 ;;
                        network:yca-*-services-net|network:yca-*-external) printf 'Error: No such network: %%s\\n' "$5"; exit 1 ;;
                      esac ;;
                  esac
                fi
                if [ "$1" != run ]; then
                  %s
                  agent_state "$@"
                  exit 0
                fi
                set -eu
                printf '%%s\\n' "$@" > '%s'
                storage=''
                output=''
                for argument in "$@"; do
                  case "$argument" in
                    type=bind,src=*,dst=/attempt/storage) storage=${argument#type=bind,src=}; storage=${storage%%,dst=*} ;;
                    type=bind,src=*,dst=/attempt/output) output=${argument#type=bind,src=}; output=${output%%,dst=*} ;;
                  esac
                done
                test -n "$storage"
                test -n "$output"
                if [ -f "$storage/.m2/settings.xml" ]; then
                  cp "$storage/.m2/settings.xml" '%s'
                else
                  printf absent > '%s'
                fi
                if [ -f "$storage/%s" ]; then
                  cat "$storage/%s" > '%s'
                else
                  printf cold > '%s'
                fi
                mkdir -p "$storage/caches/maven/repository/org/example/library/1.0" "$storage/.m2"
                printf '%%s' '%s' > "$storage/%s"
                printf 'agent-mutated-settings' > "$storage/.m2/settings.xml"
                %s
                exit %d
                """.formatted(
                        "uncertain-stop".equals(outcome) ? "exit 97" : ":",
                        observation(id, "arguments"),
                        observation(id, "settings"),
                        observation(id, "settings"),
                        ARTIFACT,
                        ARTIFACT,
                        observation(id, "restored"),
                        observation(id, "restored"),
                        artifact,
                        ARTIFACT,
                        result,
                        "exit-failure".equals(outcome) ? 9 : 0);
        Files.writeString(executable, script.replace("#!/bin/sh\n", "#!/bin/sh\n" + TestScript.githubAgentState()));
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
    }

    private static WorkerOptions cacheOptions(
            final WorkerOptions baseline, final int retries, final DependencyCacheConfiguration policy) {
        return new WorkerOptions(
                retries,
                baseline.parallelism(),
                baseline.agentConfiguration(),
                baseline.forkOwner(),
                baseline.agentCommenting(),
                baseline.provisionFork(),
                baseline.cacheRoot(),
                baseline.cacheGit(),
                baseline.privateSource(),
                baseline.source(),
                policy);
    }

    @AfterAll
    static void stopApiServer() {
        if (apiServer != null) {
            apiServer.stop(0);
        }
    }

    public static class Source extends GithubConfigurationTestModule {
        public Source() {
            super(settings());
        }

        private static Map<String, String> settings() {
            try {
                apiServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                apiServer.start();
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
            return Map.ofEntries(
                    Map.entry(
                            "github.api",
                            "http://127.0.0.1:" + apiServer.getAddress().getPort()),
                    Map.entry("github.home", root.resolve("state").toString()),
                    Map.entry("github.login", "bot"),
                    Map.entry("github.authorization.userIds", "42"),
                    Map.entry(
                            "github.executor.docker.executable",
                            root.resolve("fake-docker").toString()),
                    Map.entry("github.executor.docker.image", "trusted-agent"),
                    Map.entry(
                            "github.executor.attemptRoot",
                            root.resolve("attempts").toString()),
                    Map.entry("github.executor.timeoutSeconds", "5"),
                    Map.entry("worker.maxRetries", "0"),
                    Map.entry("worker.agentCommenting", "true"),
                    Map.entry("worker.dependencyCache.presets", "MAVEN"));
        }
    }
}
