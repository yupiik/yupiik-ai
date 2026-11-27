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

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.notifications.NotificationService;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.configuration.FixConfiguration;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.configuration.GithubExecutorConfiguration;
import io.yupiik.ia.agent.github.configuration.PromptConfiguration;
import io.yupiik.ia.agent.github.configuration.ReviewPresentationConfiguration;
import io.yupiik.ia.agent.github.model.TaskKind;
import io.yupiik.ia.agent.github.model.TaskState;
import io.yupiik.ia.agent.github.service.BotAudit;
import io.yupiik.ia.agent.github.service.BotMaintenance;
import io.yupiik.ia.agent.github.service.GithubServices;
import io.yupiik.ia.agent.github.service.TaskDiagnostics;
import io.yupiik.ia.agent.github.store.TaskStore;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BotCommandTest {
    @TempDir
    Path home;

    @TempDir
    static Path appHome;

    private static HttpServer appServer;

    @Test
    void manualReviewResolvesParentMetadataAndFailsWithoutEnqueueOnLookupError() throws Exception {
        final var conf = BotTestConfiguration.home(home);
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var requests = new ArrayList<String>();
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            server.createContext("/", exchange -> {
                final var path = exchange.getRequestURI().getPath();
                requests.add(path);
                final var body =
                        switch (path) {
                            case "/repos/acme/repo/issues/1" ->
                                Map.of("number", 1, "html_url", "https://github.com/acme/repo/pull/1");
                            case "/repos/acme/repo/issues/2" ->
                                Map.of(
                                        "number",
                                        2,
                                        "pull_request",
                                        Map.of(),
                                        "html_url",
                                        "https://github.com/acme/repo/issues/2");
                            default -> Map.of("message", "Not Found");
                        };
                final var bytes = json.toString(body).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(body.containsKey("message") ? 404 : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            final var store = new TaskStore(conf, json);
            final var clock = BotTestConfiguration.CLOCK;
            final var audit = new BotAudit(conf, json, clock);
            final var fix = new FixConfiguration(false, "");
            try (final var github = new GitHubClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), json, () -> "test", clock)) {
                new CreateCommand(
                                new CreateCommand.Options("acme/repo", 2, TaskKind.review, "preview", true),
                                json,
                                store,
                                audit,
                                clock,
                                github,
                                fix)
                        .run();
                assertTrue(store.transaction(state -> state.list()).isEmpty());
                assertFalse(Files.exists(home.resolve("audit.jsonl")));
                for (final var number : List.of(1, 2)) {
                    new CreateCommand(
                                    new CreateCommand.Options("acme/repo", number, TaskKind.review, "review", false),
                                    json,
                                    store,
                                    audit,
                                    clock,
                                    github,
                                    fix)
                            .run();
                }
                final var tasks = store.transaction(state -> state.list());
                assertEquals(2, tasks.size());
                for (final var task : tasks) {
                    assertEquals(task.number() == 1 ? "issue" : "pull_request", task.reviewTarget());
                    assertEquals(TaskKind.review, task.kind());
                }
                assertThrows(
                        GitHubClient.HttpException.class,
                        () -> new CreateCommand(
                                        new CreateCommand.Options("acme/repo", 3, TaskKind.review, "failed", false),
                                        json,
                                        store,
                                        audit,
                                        clock,
                                        github,
                                        fix)
                                .run());
                assertEquals(2, store.transaction(state -> state.list()).size());
                assertEquals(
                        2, store.<Integer>transaction(state -> state.receipts().size()));
                final var before = requests.size();
                for (final var kind : List.of(TaskKind.solve, TaskKind.help)) {
                    new CreateCommand(
                                    new CreateCommand.Options("acme/repo", 4, kind, "unchanged", false),
                                    json,
                                    store,
                                    audit,
                                    clock,
                                    github,
                                    fix)
                            .run();
                }
                assertEquals(before, requests.size());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void manualFixValidatesPolicyAndCanonicalOpenPullBeforeEnqueue() throws Exception {
        final var conf = BotTestConfiguration.home(home);
        final var requests = new AtomicInteger();
        final var sha = "a".repeat(40);
        final var base = BotTestConfiguration.fixRepository("acme/repo", "BASE");
        final var head = BotTestConfiguration.fixRepository("human/fork", "HEAD");
        final var valid = BotTestConfiguration.fixPull("acme/repo", sha);
        final var response = new AtomicReference<Map<String, Object>>(valid);
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                final var path = exchange.getRequestURI().getPath();
                final var body =
                        switch (path) {
                            case "/repos/acme/repo/pulls/12" -> response.get();
                            case "/repos/acme/repo" -> base;
                            case "/repos/human/fork" -> head;
                            case "/repos/human/fork/git/ref/heads/feature" -> BotTestConfiguration.fixRef(sha);
                            default -> Map.of("message", "Not Found");
                        };
                final var bytes = json.toString(body).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(body.containsKey("message") ? 404 : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            final var store = new TaskStore(conf, json);
            final var clock = BotTestConfiguration.CLOCK;
            final var audit = new BotAudit(conf, json, clock);
            final var options = new CreateCommand.Options("acme/repo", 12, TaskKind.fix, "fix findings", false);
            try (final var github = new GitHubClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), json, () -> "test", clock)) {
                assertThrows(
                        IllegalStateException.class,
                        () -> new CreateCommand(
                                        options,
                                        json,
                                        store,
                                        audit,
                                        clock,
                                        github,
                                        new FixConfiguration(false, "human/fork"))
                                .run());
                assertEquals(0, requests.get());
                for (final var policy : List.of("", "acme/repo", "human/*", "Human/fork")) {
                    assertThrows(
                            IllegalStateException.class,
                            () -> new CreateCommand(
                                            options,
                                            json,
                                            store,
                                            audit,
                                            clock,
                                            github,
                                            new FixConfiguration(true, policy))
                                    .run());
                }
                final var enabled = new FixConfiguration(true, "human/fork");
                for (final var invalid : List.of(
                        Map.entry("state", (Object) "closed"),
                        Map.entry("number", (Object) 13),
                        Map.entry("html_url", (Object) "https://evil.example/acme/repo/pull/12"),
                        Map.entry(
                                "base", (Object) Map.of("repo", Map.of("full_name", "acme/repo", "node_id", "WRONG"))),
                        Map.entry(
                                "base", (Object) Map.of("repo", Map.of("full_name", "other/repo", "node_id", "BASE"))),
                        Map.entry("head", (Object) Map.of(
                                "repo",
                                Map.of("full_name", "human/fork", "node_id", "WRONG"),
                                "ref",
                                "feature",
                                "sha",
                                sha)),
                        Map.entry("head", (Object) Map.of(
                                "repo",
                                Map.of("full_name", "unknown/repo", "node_id", "UNKNOWN"),
                                "ref",
                                "feature",
                                "sha",
                                sha)))) {
                    final var changed = new LinkedHashMap<>(valid);
                    changed.put(invalid.getKey(), invalid.getValue());
                    response.set(changed);
                    assertThrows(
                            IllegalStateException.class,
                            () -> new CreateCommand(options, json, store, audit, clock, github, enabled).run());
                }
                final var unknown = new LinkedHashMap<>(valid);
                unknown.put(
                        "head",
                        Map.of(
                                "repo",
                                Map.of("full_name", "unknown/repo", "node_id", "UNKNOWN"),
                                "ref",
                                "feature",
                                "sha",
                                sha));
                response.set(unknown);
                assertThrows(
                        GitHubClient.HttpException.class,
                        () -> new CreateCommand(
                                        options,
                                        json,
                                        store,
                                        audit,
                                        clock,
                                        github,
                                        new FixConfiguration(true, "unknown/repo"))
                                .run());
                assertTrue(store.transaction(state -> state.list()).isEmpty());
                assertTrue(store.transaction(state -> state.receipts()).isEmpty());
                assertFalse(Files.exists(home.resolve("audit.jsonl")));
                response.set(valid);
                new CreateCommand(
                                new CreateCommand.Options("acme/repo", 12, TaskKind.fix, "preview", true),
                                json,
                                store,
                                audit,
                                clock,
                                github,
                                enabled)
                        .run();
                assertTrue(store.transaction(state -> state.list()).isEmpty());
                new CreateCommand(options, json, store, audit, clock, github, enabled).run();
                final var task = store.transaction(state -> state.list().getFirst());
                assertEquals(TaskKind.fix, task.kind());
                assertEquals("https://github.com/acme/repo/pull/12", task.reference());
                assertEquals(clock.instant().toString(), task.createdAt());
                assertEquals(
                        sha, TaskStore.map(task.recovery().get("fixTarget")).get("headSha"));
                assertEquals(
                        "HEAD", TaskStore.map(task.recovery().get("fixTarget")).get("repositoryId"));
                assertEquals(
                        "1", TaskStore.map(task.recovery().get("fixTarget")).get("version"));
                assertEquals(null, task.actorId(), "Manual creation retains trusted operator semantics");
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void manualCreationUsesInjectedClockForTaskAndAudit() throws Exception {
        final var conf = BotTestConfiguration.home(home);
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            final var store = new TaskStore(conf, json);
            final var clock = Clock.fixed(Instant.parse("2035-02-03T04:05:06Z"), ZoneOffset.UTC);
            new CreateCommand(
                            new CreateCommand.Options("acme/repo", 1, TaskKind.help, "request", false),
                            json,
                            store,
                            new BotAudit(conf, json, clock),
                            clock,
                            null,
                            new FixConfiguration(false, ""))
                    .run();
            final var tasks = store.transaction(state -> state.list());
            assertEquals(1, tasks.size());
            assertEquals(clock.instant().toString(), tasks.getFirst().createdAt());
            final var audit = (Map<?, ?>) json.fromString(Object.class, Files.readString(home.resolve("audit.jsonl")));
            assertEquals(clock.instant().toString(), audit.get("at"));
            assertEquals("manual-created", audit.get("event"));
        }
    }

    @Test
    void deleteCleansOnlyItsDiagnosticsAndCanBeRetriedAfterPrivateDeletionFailure() throws Exception {
        final var conf = BotTestConfiguration.home(home);
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            final var store = new TaskStore(conf, json);
            final var id = store.transaction(state -> {
                final var created = state.enqueue("delete-private", TaskFixtures.task(Map.of()));
                state.task(created).state(TaskState.failed);
                return created;
            });
            final var other = store.transaction(state -> state.enqueue("other-private", TaskFixtures.task(Map.of())));
            final var diagnostics = new TaskDiagnostics(home, json, BotTestConfiguration.CLOCK);
            final var record = home.resolve(diagnostics.capture(
                    store.transaction(state -> state.task(id).document()), "execution", new IOException("private"), 1));
            diagnostics.capture(store.transaction(state -> state.task(other).document()), "execution", null, 2);
            final var otherDiagnostic = diagnostics.read(other);
            new RetryCommand(new RetryCommand.Options(id, false), json, store, BotTestConfiguration.audit(conf, json))
                    .run();
            assertTrue(Files.exists(record), "Retry must retain private diagnostics");
            final var originalState = Files.readString(home.resolve("state.json"));
            final var originalAudit = Files.readString(home.resolve("audit.jsonl"));
            final var saved = home.resolve("saved-diagnostic.json");
            Files.move(record, saved);
            Files.createDirectory(record);
            final var command = new DeleteCommand(
                    new DeleteCommand.Options(id, false),
                    json,
                    store,
                    BotTestConfiguration.audit(conf, json),
                    diagnostics);
            assertThrows(UncheckedIOException.class, command::run);
            assertEquals(originalState, Files.readString(home.resolve("state.json")));
            assertEquals(originalAudit, Files.readString(home.resolve("audit.jsonl")));
            assertEquals(
                    TaskState.pending, store.transaction(state -> state.task(id).state()));
            assertEquals(otherDiagnostic, diagnostics.read(other));

            Files.delete(record);
            Files.move(saved, record);
            command.run();
            assertFalse(Files.exists(record.getParent()));
            assertEquals("[]", diagnostics.read(id));
            assertEquals(otherDiagnostic, diagnostics.read(other));
            assertEquals(
                    other, store.transaction(state -> state.list().getFirst().id()));
            assertEquals(id, store.transaction(state -> state.receipts().get("delete-private")));
            assertEquals(2, store.transaction(state -> state.receipts().size()).intValue());
        }
    }

    @Nested
    @FusionSupport(modules = AppSource.class)
    class PollUsesAppPemAuthenticationAndTypedRepositoryDiscovery {
        @Fusion
        private GithubBotConfiguration injectedConfiguration;

        @Fusion
        private PollCommand.Options defaultOptions;

        @Fusion
        private WorkerCommand.Options injectedWorkerOptions;

        @Fusion
        private GithubExecutorConfiguration injectedExecutor;

        @Fusion
        private PromptConfiguration injectedPrompts;

        @Fusion
        private ReviewPresentationConfiguration injectedPresentation;

        @Test
        void pollUsesAppPemAuthenticationAndTypedRepositoryDiscovery() throws Exception {
            final var home = appHome;
            final var keys = KeyPairGenerator.getInstance("RSA");
            keys.initialize(2048);
            final var key = keys.generateKeyPair().getPrivate();
            final var keyFile = home.resolve("key.pem");
            Files.writeString(
                    keyFile,
                    "-----BEGIN " + "PRIVATE KEY-----\n"
                            + Base64.getMimeEncoder().encodeToString(key.getEncoded()) + "\n-----END "
                            + "PRIVATE KEY-----\n");
            final var installations = new AtomicInteger();
            final var polls = new AtomicInteger();
            final var server = appServer;
            try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
                server.createContext("/", exchange -> {
                    final var path = exchange.getRequestURI().getPath();
                    final String body;
                    if (path.equals("/app/installations/456/access_tokens")) {
                        installations.incrementAndGet();
                        body = json.toString(Map.of(
                                "token",
                                "installation-token",
                                "expires_at",
                                BotTestConfiguration.CLOCK
                                        .instant()
                                        .plusSeconds(3600)
                                        .toString()));
                    } else if (!"Bearer installation-token"
                            .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                        exchange.sendResponseHeaders(401, -1);
                        exchange.close();
                        return;
                    } else if (path.equals("/orgs/acme/repos") || path.equals("/users/alice/repos")) {
                        body = json.toString(List.of(
                                Map.of("full_name", "acme/repo", "visibility", "internal"),
                                Map.of("full_name", "acme/excluded", "visibility", "internal"),
                                Map.of("full_name", "acme/public", "visibility", "public")));
                    } else {
                        if (!path.startsWith("/repos/acme/repo/")) {
                            exchange.sendResponseHeaders(404, -1);
                            exchange.close();
                            return;
                        }
                        polls.incrementAndGet();
                        body = "[]";
                    }
                    final var bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
                server.start();
                final var conf = injectedConfiguration;
                BotTestConfiguration.pollCommand(
                                new PollCommand.Options(defaultOptions.poll()),
                                conf,
                                json,
                                BotTestConfiguration.store(conf, json),
                                BotTestConfiguration.audit(conf, json))
                        .run();
                assertEquals(1, installations.get());
                assertEquals(3, polls.get());
                final var store = new TaskStore(BotTestConfiguration.home(home), json);
                final String failed = store.transaction(state -> {
                    final var id = state.enqueue(
                            "notification-test", TaskFixtures.task(Map.of("repository", "acme/repo", "kind", "help")));
                    state.task(id).state(TaskState.failed);
                    return id;
                });
                final var sent = new AtomicInteger();
                final var notifications = new NotificationService(null, null) {
                    @Override
                    public void onTurnEnd(
                            final String session,
                            final String title,
                            final String model,
                            final String provider,
                            final boolean success,
                            final String body) {
                        assertEquals(failed, session);
                        assertFalse(success);
                        assertFalse(body.contains("token"));
                        sent.incrementAndGet();
                    }
                };
                final var executor = injectedExecutor.shared();
                final var worker = BotTestConfiguration.workerCommand(
                        injectedPrompts,
                        injectedPresentation,
                        new WorkerCommand.Options("", injectedWorkerOptions.worker()),
                        conf,
                        json,
                        BotTestConfiguration.store(conf, json),
                        BotTestConfiguration.audit(conf, json),
                        executor,
                        notifications);
                worker.run();
                worker.run();
                assertEquals(1, sent.get());
                assertEquals(true, store.transaction(state -> state.task(failed).failureNotified()));
            } finally {
                server.stop(0);
            }
        }
    }

    @Nested
    @FusionSupport(modules = MissingTokenSource.class)
    class PatFailsClosedWhenEnvironmentCredentialIsMissing {
        @Fusion
        private GithubBotConfiguration injectedConfiguration;

        @Fusion
        private PollCommand.Options defaultOptions;

        @Test
        void patFailsClosedWhenEnvironmentCredentialIsMissing() {
            final var conf = atHome(injectedConfiguration, home);
            try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
                assertThrows(
                        IllegalStateException.class,
                        () -> BotTestConfiguration.pollCommand(
                                        new PollCommand.Options(defaultOptions.poll()),
                                        conf,
                                        json,
                                        BotTestConfiguration.store(conf, json),
                                        BotTestConfiguration.audit(conf, json))
                                .run());
            }
        }
    }

    @Nested
    @FusionSupport(modules = IncompleteAppSource.class)
    class RejectsIncompleteAppConfigurationAndNonPkcs8Pem {
        @Fusion
        private GithubBotConfiguration injectedConfiguration;

        @Test
        void rejectsIncompleteAppConfigurationAndNonPkcs8Pem() throws Exception {
            final var key = home.resolve("invalid.pem");
            Files.writeString(key, "not a PKCS#8 key");
            try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
                final var missing = Map.of("github.auth.type", "app");
                final var incomplete = injectedConfiguration;
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new GithubServices()
                                .client(
                                        incomplete,
                                        json,
                                        BotTestConfiguration.CLOCK,
                                        name -> Optional.ofNullable(missing.get(name))));
                final var values = Map.of(
                        "github.auth.type",
                        "app",
                        "github.auth.appId",
                        "123",
                        "github.auth.installationId",
                        "456",
                        "github.auth.privateKeyFile",
                        key.toString());
                final var invalid = withAppKey(injectedConfiguration, key);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new GithubServices()
                                .client(
                                        invalid,
                                        json,
                                        BotTestConfiguration.CLOCK,
                                        name -> Optional.ofNullable(values.get(name))));
            }
        }
    }

    @Nested
    @FusionSupport(modules = DefaultsSource.class)
    class Defaults {
        @Fusion
        private GithubBotConfiguration injectedConfiguration;

        @Test
        void previewsMutationsWithoutChangingTasksReceiptsOrAudit() throws Exception {
            final var conf = atHome(injectedConfiguration, home);
            try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
                final var store = new TaskStore(BotTestConfiguration.home(home), json);
                final String id = store.transaction(state -> {
                    final var created = state.enqueue(
                            "preview-test", TaskFixtures.task(Map.of("kind", "help", "repository", "acme/repo")));
                    state.task(created).state(TaskState.failed);
                    state.task(created).completedAt("2000-01-01T00:00:00Z");
                    return created;
                });
                final var original = Files.readString(home.resolve("state.json"));
                final var diagnostics = new TaskDiagnostics(home, json, BotTestConfiguration.CLOCK);
                final var record = home.resolve(diagnostics.capture(
                        store.transaction(state -> state.task(id).document()),
                        "execution",
                        new IOException("private"),
                        1));
                final var originalDiagnostic = Files.readString(record);
                for (final var command : List.of(
                        new RetryCommand(
                                new RetryCommand.Options(id, true),
                                json,
                                BotTestConfiguration.store(conf, json),
                                BotTestConfiguration.audit(conf, json)),
                        new DeleteCommand(
                                new DeleteCommand.Options(id, true),
                                json,
                                BotTestConfiguration.store(conf, json),
                                BotTestConfiguration.audit(conf, json),
                                diagnostics),
                        new PurgeCommand(
                                new PurgeCommand.Options(7, true),
                                new BotMaintenance(
                                        BotTestConfiguration.store(conf, json),
                                        BotTestConfiguration.CLOCK,
                                        diagnostics)),
                        new CreateCommand(
                                new CreateCommand.Options("acme/repo", 1, TaskKind.help, "preview", true),
                                json,
                                BotTestConfiguration.store(conf, json),
                                BotTestConfiguration.audit(conf, json),
                                BotTestConfiguration.CLOCK,
                                null,
                                new FixConfiguration(false, "")))) {
                    command.run();
                    assertEquals(original, Files.readString(home.resolve("state.json")));
                    assertEquals(originalDiagnostic, Files.readString(record));
                }
                assertFalse(Files.exists(home.resolve("audit.jsonl")));
            }
        }

        @Test
        void listsOnlyTasksMatchingAllExactFilters() throws Exception {
            final var conf = atHome(injectedConfiguration, home);
            try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
                final var store = new TaskStore(BotTestConfiguration.home(home), json);
                final String wanted = store.transaction(state -> {
                    final var id = state.enqueue(
                            "wanted", TaskFixtures.task(Map.of("kind", "review", "repository", "acme/repo")));
                    state.enqueue(
                            "different-kind", TaskFixtures.task(Map.of("kind", "help", "repository", "acme/repo")));
                    state.enqueue(
                            "different-repo", TaskFixtures.task(Map.of("kind", "review", "repository", "other/repo")));
                    final var failed = state.enqueue(
                            "different-state", TaskFixtures.task(Map.of("kind", "review", "repository", "acme/repo")));
                    state.task(failed).state(TaskState.failed);
                    return id;
                });
                final var output = new ArrayList<String>();
                final var logger = Logger.getLogger(ListCommand.class.getName());
                final var handler = new Handler() {
                    @Override
                    public void publish(final LogRecord record) {
                        output.add(record.getMessage());
                    }

                    @Override
                    public void flush() {}

                    @Override
                    public void close() {}
                };
                logger.addHandler(handler);
                try {
                    new ListCommand(
                                    new ListCommand.Options(TaskState.pending, "acme/repo", TaskKind.review),
                                    json,
                                    BotTestConfiguration.store(conf, json))
                            .run();
                } finally {
                    logger.removeHandler(handler);
                }
                final var selected =
                        (List<?>) ((Map<?, ?>) json.fromString(Object.class, output.getFirst())).get("tasks");
                assertEquals(1, selected.size());
                assertEquals(wanted, ((Map<?, ?>) selected.getFirst()).get("id"));
            }
        }

        @Test
        void auditsOperatorRetryAndDeleteWithoutRequestText() throws Exception {
            final var conf = atHome(injectedConfiguration, home);
            try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
                final var store = new TaskStore(BotTestConfiguration.home(home), json);
                final String id = store.transaction(state -> {
                    final var created = state.enqueue(
                            "audit-test", TaskFixtures.task(Map.of("kind", "help", "request", "sensitive-request")));
                    state.task(created).state(TaskState.failed);
                    return created;
                });
                new RetryCommand(
                                new RetryCommand.Options(id, false),
                                json,
                                BotTestConfiguration.store(conf, json),
                                BotTestConfiguration.audit(conf, json))
                        .run();
                assertEquals(
                        TaskState.pending,
                        store.transaction(state -> state.task(id).state()));
                new DeleteCommand(
                                new DeleteCommand.Options(id, false),
                                json,
                                BotTestConfiguration.store(conf, json),
                                BotTestConfiguration.audit(conf, json),
                                new TaskDiagnostics(home, json, BotTestConfiguration.CLOCK))
                        .run();
                final var audit = Files.readString(home.resolve("audit.jsonl"));
                assertTrue(audit.contains("manual-retried"));
                assertTrue(audit.contains("manual-deleted"));
                assertFalse(audit.contains("sensitive-request"));
                assertTrue(store.transaction(state ->
                                state.list().stream().map(TaskFixtures::map).toList())
                        .isEmpty());
            }
        }

        @Test
        void preventsRetryOfPublishedFailureAndDeletionOfRetainedAttempt() throws Exception {
            final var conf = atHome(injectedConfiguration, home);
            try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
                final var store = new TaskStore(BotTestConfiguration.home(home), json);
                final String id = store.transaction(
                        state -> state.enqueue("test:retry", TaskFixtures.task(Map.of("kind", "help"))));
                final var diagnostics = new TaskDiagnostics(home, json, BotTestConfiguration.CLOCK);
                diagnostics.capture(store.transaction(state -> state.task(id).document()), "execution", null, 1);
                final var originalDiagnostics = diagnostics.read(id);
                store.transaction(state -> {
                    final var task = state.task(id);
                    task.state(TaskState.failed);
                    task.publicationIntent("published");
                    return null;
                });
                assertThrows(
                        IllegalStateException.class,
                        () -> new RetryCommand(
                                        new RetryCommand.Options(id, false),
                                        json,
                                        BotTestConfiguration.store(conf, json),
                                        BotTestConfiguration.audit(conf, json))
                                .run());
                store.transaction(state -> {
                    final var task = state.task(id);
                    task.publicationIntent(null);
                    task.reviewIntent("ambiguous-review");
                    return null;
                });
                assertThrows(
                        IllegalStateException.class,
                        () -> new RetryCommand(
                                        new RetryCommand.Options(id, false),
                                        json,
                                        BotTestConfiguration.store(conf, json),
                                        BotTestConfiguration.audit(conf, json))
                                .run());
                store.transaction(state -> {
                    final var task = state.task(id);
                    task.reviewIntent(null);
                    task.attemptDirectory(home.resolve("attempt").toString());
                    return null;
                });
                assertThrows(
                        IllegalStateException.class,
                        () -> new DeleteCommand(
                                        new DeleteCommand.Options(id, false),
                                        json,
                                        BotTestConfiguration.store(conf, json),
                                        BotTestConfiguration.audit(conf, json),
                                        diagnostics)
                                .run());
                assertEquals(
                        TaskState.failed,
                        store.transaction(state -> state.task(id).state()));
                assertEquals(originalDiagnostics, diagnostics.read(id));
            }
        }
    }

    private static GithubBotConfiguration atHome(final GithubBotConfiguration baseline, final Path home) {
        return new GithubBotConfiguration(
                home.toString(),
                baseline.api(),
                baseline.login(),
                baseline.requestIntervalMillis(),
                baseline.auth(),
                baseline.authorization(),
                baseline.repositories());
    }

    private static GithubBotConfiguration withAppKey(final GithubBotConfiguration baseline, final Path key) {
        final var auth = baseline.auth();
        return new GithubBotConfiguration(
                baseline.home(),
                baseline.api(),
                baseline.login(),
                baseline.requestIntervalMillis(),
                new GithubBotConfiguration.Auth(
                        auth.type(), auth.tokenEnv(), "123", 456, key.toString(), auth.agentTokenEnv()),
                baseline.authorization(),
                baseline.repositories());
    }

    public static class DefaultsSource extends GithubConfigurationTestModule {}

    public static class AppSource extends GithubConfigurationTestModule {
        public AppSource() {
            super(settings());
        }

        private static Map<String, String> settings() {
            try {
                appServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
            return Map.ofEntries(
                    Map.entry(
                            "github.api",
                            "http://127.0.0.1:" + appServer.getAddress().getPort()),
                    Map.entry("github.home", appHome.toString()),
                    Map.entry(
                            "github.auth.privateKeyFile",
                            appHome.resolve("key.pem").toString()),
                    Map.entry("github.auth.type", "app"),
                    Map.entry("github.auth.appId", "123"),
                    Map.entry("github.auth.installationId", "456"),
                    Map.entry("github.authorization.userIds", "42"),
                    Map.entry("github.repositories.discovery", "org:acme,user:alice"),
                    Map.entry("github.repositories.includes", "acme/*"),
                    Map.entry("github.repositories.excludes", "*/excluded"),
                    Map.entry("github.repositories.visibility", "INTERNAL"));
        }
    }

    public static class MissingTokenSource extends GithubConfigurationTestModule {
        public MissingTokenSource() {
            super(Map.of(
                    "github.auth.tokenEnv",
                    "YCA_TEST_MISSING_HOST_TOKEN_1972",
                    "github.authorization.userIds",
                    "42",
                    "github.repositories.explicit",
                    "acme/repo"));
        }
    }

    public static class IncompleteAppSource extends GithubConfigurationTestModule {
        public IncompleteAppSource() {
            super(Map.of("github.auth.type", "app"));
        }
    }
}
