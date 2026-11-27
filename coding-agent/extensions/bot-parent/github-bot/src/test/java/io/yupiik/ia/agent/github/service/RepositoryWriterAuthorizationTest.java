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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.command.WorkerCommand.Options;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.configuration.GithubExecutorConfiguration;
import io.yupiik.ia.agent.github.configuration.PollOptions;
import io.yupiik.ia.agent.github.configuration.PromptConfiguration;
import io.yupiik.ia.agent.github.configuration.ReviewPresentationConfiguration;
import io.yupiik.ia.agent.github.configuration.WorkerOptions;
import io.yupiik.ia.agent.github.model.TaskState;
import io.yupiik.ia.agent.github.store.TaskStore;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Real HTTP and durable queue regression coverage; no live GitHub calls or executor processes. */
@FusionSupport(modules = RepositoryWriterAuthorizationTest.Source.class)
class RepositoryWriterAuthorizationTest {
    @Fusion
    private GithubExecutorConfiguration injectedExecutor;

    @Fusion
    private Options injectedOptions;

    @Fusion
    private PromptConfiguration injectedPrompts;

    @Fusion
    private ReviewPresentationConfiguration injectedPresentation;

    private static final Pattern ISSUE_COMMENTS = Pattern.compile("/repos/owner/(repo|other)/issues/12/comments");
    private static final Pattern FEED =
            Pattern.compile("/repos/owner/(repo|other)/(issues|issues/comments|pulls/comments)");
    private static final List<String> REPOSITORIES = List.of("owner/repo", "owner/other");
    private static final List<Integer> ACTORS = List.of(42, 43, 44, 45);

    @TempDir
    Path home;

    @TempDir
    static Path executorHome;

    @Test
    void issueAndCommentFeedsAcceptWritersWithEmptyWhitelist() throws Exception {
        verifyWriterPolling(Surface.FEEDS, "write", 3);
    }

    @Test
    void reviewSummariesAcceptAdminsWithEmptyWhitelist() throws Exception {
        verifyWriterPolling(Surface.REVIEWS, "admin", 1);
    }

    @Test
    void discussionRootsCommentsAndRepliesAcceptWritersWithEmptyWhitelist() throws Exception {
        verifyWriterPolling(Surface.DISCUSSIONS, "write", 3);
    }

    private void verifyWriterPolling(final Surface surface, final String permission, final int expected)
            throws Exception {
        try (final var fixture = new Fixture(surface)) {
            fixture.writerPermission = permission;
            final var conf = fixture.configuration("", "", true);
            assertEquals(expected, fixture.poll(conf));
            final var tasks = fixture.tasks();
            assertEquals(expected, tasks.size());
            assertTrue(
                    tasks.stream()
                            .allMatch(task ->
                                    "42".equals(task.get("actorId")) && "owner/repo".equals(task.get("repository"))),
                    tasks.toString());
            // The same actor is read-only in owner/other; a grant in owner/repo must not leak there.
            for (final var repository : REPOSITORIES) {
                assertEquals(1, fixture.count("/repos/" + repository + "/collaborators/writer/permission"));
                assertEquals(1, fixture.count("/repos/" + repository + "/collaborators/reader/permission"));
                assertTrue(fixture.requests.indexOf("/user/42")
                        < fixture.requests.indexOf("/repos/" + repository + "/collaborators/writer/permission"));
            }
            assertEquals(2, fixture.count("/user/42"));
            assertEquals(2, fixture.count("/user/43"));
            assertEquals(0, fixture.count("/user/44"), "Bot must be filtered before authorization");
            assertEquals(0, fixture.count("/user/45"), "Self must be filtered before authorization");
            assertTrue(fixture.requests.stream().noneMatch(path -> path.startsWith("/orgs/")));
            assertEquals(0, fixture.poll(conf), "Repeated polling must not duplicate accepted invocations");
            assertEquals(expected, fixture.tasks().size());
            assertTrue(fixture.unexpected.isEmpty(), fixture.unexpected.toString());
        }
    }

    @ParameterizedTest
    @EnumSource(Surface.class)
    void explicitIdsBypassIdentityPermissionAndTeamApisButNotBotSelfFilters(final Surface surface) throws Exception {
        try (final var fixture = new Fixture(surface)) {
            // Include bot and self IDs to prove explicit grants do not bypass ingestion filters.
            final var conf = fixture.configuration("42,44,45", "org/restricted", false);
            final int perRepository = surface == Surface.REVIEWS ? 1 : 3;
            assertEquals(2 * perRepository, fixture.poll(conf));
            assertEquals(2 * perRepository, fixture.tasks().size());
            assertTrue(fixture.tasks().stream().allMatch(task -> "42".equals(task.get("actorId"))));
            assertTrue(
                    fixture.requests.stream()
                            .noneMatch(path -> path.startsWith("/user/")
                                    || path.contains("/collaborators/")
                                    || path.startsWith("/orgs/")),
                    fixture.requests.toString());
            final var authorization = new TeamAuthorization(
                    fixture.github, fixture.configuration("42", "org/restricted", true), BotTestConfiguration.CLOCK);
            final int before = fixture.requests.size();
            assertTrue(authorization.allows(null, "42", "not/a/login"));
            assertEquals(before, fixture.requests.size(), "Explicit ID overrides require no API lookup");
            assertTrue(fixture.unexpected.isEmpty(), fixture.unexpected.toString());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void workerAcceptsWriterForPendingHelpAndStagedPublication(final boolean staged) throws Exception {
        try (final var fixture = new Fixture(Surface.FEEDS)) {
            final var conf = fixture.configuration("", "", true);
            final var id = fixture.enqueue("owner/repo", 42, "help", staged);
            final var worker = fixture.worker(conf);
            worker.run(id, fixture.workerOptions());
            assertEquals(
                    TaskState.done,
                    fixture.store.transaction(state -> state.task(id).state()));
            assertEquals(1, fixture.posts.size());
            assertTrue(String.valueOf(fixture.posts.getFirst().get("body")).contains("<!-- yca-task:" + id + " -->"));
            assertTrue(fixture.intentAtPost, "Publication intent must be durable before HTTP mutation");
            assertEquals(1, fixture.count("/user/42"));
            assertEquals(1, fixture.count("/repos/owner/repo/collaborators/writer/permission"));
            assertNull(fixture.store.transaction(state -> state.task(id).attemptDirectory()));
            assertTrue(fixture.unexpected.isEmpty(), fixture.unexpected.toString());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void workerRejectsReadOnlyAndOtherRepositoryActorsBeforeExecutionOrPublication(final boolean staged)
            throws Exception {
        try (final var fixture = new Fixture(Surface.FEEDS)) {
            final var conf = fixture.configuration("", "", true);
            final var worker = fixture.worker(conf);
            for (final var repository : REPOSITORIES) {
                final int actor = "owner/repo".equals(repository) ? 43 : 42;
                final var id = fixture.enqueue(repository, actor, staged ? "help" : "solve", staged);
                final var error =
                        assertThrows(IllegalStateException.class, () -> worker.run(id, fixture.workerOptions()));
                assertTrue(
                        error.getMessage().contains(staged ? "Publication blocked" : "Execution blocked"),
                        error.getMessage());
                assertEquals(
                        staged ? TaskState.publishing : TaskState.pending,
                        fixture.store.transaction(state -> state.task(id).state()));
                assertNull(fixture.store.transaction(state -> state.task(id).attemptDirectory()));
                assertNull(fixture.store.transaction(state -> state.task(id).publicationIntent()));
                if (staged) {
                    assertNotNull(
                            fixture.store.transaction(state -> state.task(id).result()));
                }
            }
            assertTrue(fixture.posts.isEmpty());
            assertEquals(1, fixture.count("/repos/owner/repo/collaborators/reader/permission"));
            assertEquals(1, fixture.count("/repos/owner/other/collaborators/writer/permission"));
            assertTrue(fixture.unexpected.isEmpty(), fixture.unexpected.toString());
        }
    }

    private enum Surface {
        FEEDS,
        REVIEWS,
        DISCUSSIONS
    }

    private static String login(final int id) {
        return switch (id) {
            case 42 -> "writer";
            case 43 -> "reader";
            case 44 -> "robot";
            case 45 -> "BoT";
            default -> throw new IllegalArgumentException("Unexpected actor: " + id);
        };
    }

    private static Map<String, Object> actor(final int id, final boolean graphql) {
        return Map.of(
                graphql ? "databaseId" : "id",
                id,
                "login",
                login(id),
                graphql ? "__typename" : "type",
                id == 44 ? "Bot" : "User");
    }

    private static Map<String, Object> connection(final List<?> nodes) {
        final var pageInfo = new LinkedHashMap<String, Object>();
        pageInfo.put("hasNextPage", false);
        pageInfo.put("endCursor", null);
        return Map.of("nodes", nodes, "pageInfo", pageInfo);
    }

    private final class Fixture implements AutoCloseable {
        private final JsonMapper json = BotJsonMapper.create(ignored -> Optional.empty());
        private final TaskStore store = BotTestConfiguration.store(BotTestConfiguration.home(home), json);
        private final List<String> requests = new CopyOnWriteArrayList<>();
        private final List<String> unexpected = new CopyOnWriteArrayList<>();
        private final List<Map<String, Object>> posts = new CopyOnWriteArrayList<>();
        private final Map<String, Map<String, Object>> discussions = new ConcurrentHashMap<>();
        private final HttpServer server;
        private final GitHubClient github;
        private final Surface surface;
        private volatile String writerPermission = "write";
        private volatile boolean intentAtPost;

        private Fixture(final Surface surface) throws IOException {
            this.surface = surface;
            for (final var repository : REPOSITORIES) {
                for (final int id : ACTORS) {
                    final var node = Map.<String, Object>of(
                            "id",
                            "D" + repository.substring(6) + id,
                            "number",
                            id,
                            "title",
                            "Writer authorization",
                            "body",
                            "@bot help root",
                            "updatedAt",
                            BotTestConfiguration.CLOCK.instant().toString(),
                            "url",
                            "https://github.com/" + repository + "/discussions/" + id,
                            "repository",
                            Map.of("nameWithOwner", repository),
                            "author",
                            actor(id, true));
                    discussions.put(String.valueOf(node.get("id")), node);
                }
            }
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
            github = new GitHubClient(URI.create(api()), json, () -> "test", BotTestConfiguration.CLOCK);
        }

        private String api() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private GithubBotConfiguration configuration(final String ids, final String teams, final boolean writers) {
            return new GithubBotConfiguration(
                    home.toString(),
                    api(),
                    "bot",
                    0,
                    null,
                    new GithubBotConfiguration.Authorization(ids, teams, writers, 7200, 4096),
                    new GithubBotConfiguration.Repositories(
                            String.join(",", REPOSITORIES),
                            "",
                            "",
                            "",
                            List.of(RepositorySelector.Visibility.values()),
                            false,
                            false));
        }

        private int poll(final GithubBotConfiguration conf) {
            final var options = new PollOptions(
                    "now",
                    20,
                    new PollOptions.Reviews(surface == Surface.REVIEWS),
                    new PollOptions.Discussions(surface == Surface.DISCUSSIONS),
                    1,
                    new PollOptions.Search(false, 3600));
            return switch (surface) {
                case FEEDS ->
                    BotTestConfiguration.poller(conf, store, github, BotTestConfiguration.CLOCK, null)
                            .poll(options);
                case REVIEWS ->
                    BotTestConfiguration.reviewSummaryPoller(conf, store, github, BotTestConfiguration.CLOCK, null)
                            .poll(REPOSITORIES, options);
                case DISCUSSIONS ->
                    BotTestConfiguration.discussionPoller(conf, store, github, BotTestConfiguration.CLOCK, null)
                            .poll(REPOSITORIES, options);
            };
        }

        private List<Map<String, Object>> tasks() {
            return store.transaction(
                    state -> state.list().stream().map(TaskFixtures::map).toList());
        }

        private long count(final String path) {
            return requests.stream().filter(path::equals).count();
        }

        private String enqueue(final String repository, final int actorId, final String kind, final boolean staged) {
            final var id = store.transaction(state -> state.enqueue(
                    "worker:" + repository + ":" + actorId,
                    TaskFixtures.task(
                            Map.of("repository", repository, "number", 12, "actorId", actorId, "kind", kind))));
            if (staged) {
                store.transaction(state -> {
                    state.task(id).state(TaskState.publishing);
                    state.task(id).result(TaskFixtures.result(Map.of("summary", "Staged answer")));
                    return null;
                });
            }
            return id;
        }

        private Worker worker(final GithubBotConfiguration conf) {
            // Any accidental execution fails rather than invoking a real container runtime.
            final var execution = injectedExecutor.shared();
            return BotTestConfiguration.worker(
                    injectedPrompts, injectedPresentation, conf, store, github, json, execution);
        }

        private WorkerOptions workerOptions() {
            return injectedOptions.worker();
        }

        private void handle(final HttpExchange exchange) throws IOException {
            try (exchange) {
                final var path = exchange.getRequestURI().getPath();
                requests.add(path);
                Object response;
                int status = 200;
                if (path.startsWith("/user/")) {
                    final int id = Integer.parseInt(path.substring("/user/".length()));
                    response = Map.of("id", id, "login", login(id));
                } else if (path.contains("/collaborators/")) {
                    final var parts = path.split("/");
                    final var repository = parts[2] + "/" + parts[3];
                    final var resolvedLogin = parts[5];
                    final int id = ACTORS.stream()
                            .filter(actorId -> login(actorId).equals(resolvedLogin))
                            .findFirst()
                            .orElseThrow();
                    response = Map.of(
                            "permission",
                            id == 42 && repository.equals("owner/repo") ? writerPermission : "read",
                            "user",
                            Map.of("id", id, "login", resolvedLogin));
                } else if (path.equals("/graphql")) {
                    final var request = TaskStore.map(json.fromString(
                            Object.class,
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                    response = Map.of("data", graphql(request));
                } else if (ISSUE_COMMENTS.matcher(path).matches()) {
                    if ("POST".equals(exchange.getRequestMethod())) {
                        final var post = TaskStore.map(json.fromString(
                                Object.class,
                                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                        posts.add(post);
                        intentAtPost = store.transaction(state -> state.list().stream()
                                .anyMatch(task ->
                                        task.state() == TaskState.publishing && task.publicationIntent() != null));
                        response = Map.of("html_url", "https://github.com/owner/repo/issues/12#issuecomment-99");
                    } else {
                        response = List.of();
                    }
                } else if (FEED.matcher(path).matches()) {
                    response = feed(path);
                } else {
                    unexpected.add(exchange.getRequestMethod() + " " + path);
                    status = 404;
                    response = Map.of();
                }
                final var bytes = json.toString(response).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        }

        private List<Map<String, Object>> feed(final String path) {
            final var repository = path.split("/")[2] + "/" + path.split("/")[3];
            return ACTORS.stream()
                    .map(id -> Map.<String, Object>of(
                            "id",
                            id,
                            "user",
                            actor(id, false),
                            "body",
                            "@bot help feed",
                            "updated_at",
                            BotTestConfiguration.CLOCK.instant().toString(),
                            "html_url",
                            "https://github.com/" + repository
                                    + (path.endsWith("/pulls/comments") ? "/pull/" : "/issues/") + id
                                    + (path.endsWith("/comments") ? "#issuecomment-" + id : "")))
                    .toList();
        }

        private Map<String, Object> graphql(final Map<String, Object> request) {
            final var query = String.valueOf(request.get("query"));
            final var variables = TaskStore.map(request.get("variables"));
            if (surface == Surface.REVIEWS && query.contains("pullRequests(")) {
                final var data = new LinkedHashMap<String, Object>();
                for (int i = 0; variables.containsKey("owner" + i); i++) {
                    final var repository = variables.get("owner" + i) + "/" + variables.get("name" + i);
                    final var reviews = ACTORS.stream()
                            .map(id -> Map.<String, Object>of(
                                    "databaseId",
                                    id,
                                    "body",
                                    "@bot help review",
                                    "state",
                                    "COMMENTED",
                                    "submittedAt",
                                    BotTestConfiguration.CLOCK.instant().toString(),
                                    "url",
                                    "https://github.com/" + repository + "/pull/12#pullrequestreview-" + id,
                                    "author",
                                    actor(id, true)))
                            .toList();
                    data.put(
                            "r" + i,
                            Map.of(
                                    "nameWithOwner",
                                    repository,
                                    "pullRequests",
                                    connection(List.of(
                                            Map.of("number", 12, "state", "OPEN", "reviews", connection(reviews))))));
                }
                return data;
            }
            if (query.startsWith("query DiscussionCapability")) {
                return Map.of("repository", Map.of("hasDiscussionsEnabled", true));
            }
            if (query.startsWith("query DiscussionDiscovery")) {
                final var repository = variables.get("owner") + "/" + variables.get("name");
                final var nodes = ACTORS.stream()
                        .map(id -> discussions.get("D" + repository.substring(6) + id))
                        .toList();
                return Map.of("repository", Map.of("discussions", connection(nodes)));
            }
            final var id = String.valueOf(variables.get("id"));
            if (query.startsWith("query DiscussionRead")) {
                return Map.of("node", discussions.get(id));
            }
            if (query.startsWith("query DiscussionComments")) {
                final var node = discussions.get(id);
                return Map.of(
                        "node",
                        Map.of(
                                "id",
                                id,
                                "comments",
                                connection(id.endsWith("42") ? List.of(comment(node, "C" + id, false)) : List.of())));
            }
            if (query.startsWith("query DiscussionReplies")) {
                final var node = discussions.get(id.substring(1));
                return Map.of("node", Map.of("id", id, "replies", connection(List.of(comment(node, "R" + id, true)))));
            }
            unexpected.add(query);
            return Map.of();
        }

        private Map<String, Object> comment(
                final Map<String, Object> discussion, final String id, final boolean reply) {
            final var node = new LinkedHashMap<String, Object>();
            node.put("id", id);
            node.put("databaseId", reply ? 102 : 101);
            node.put("body", "@bot help thread");
            node.put("updatedAt", BotTestConfiguration.CLOCK.instant().toString());
            node.put("url", discussion.get("url") + "#discussioncomment-" + (reply ? 102 : 101));
            node.put("author", actor(42, true));
            node.put("discussion", Map.of("id", discussion.get("id")));
            node.put("replyTo", reply ? Map.of("id", "C" + discussion.get("id")) : null);
            return node;
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
                    "github.executor.docker.executable",
                    executorHome.resolve("must-not-run").toString()));
        }
    }
}
