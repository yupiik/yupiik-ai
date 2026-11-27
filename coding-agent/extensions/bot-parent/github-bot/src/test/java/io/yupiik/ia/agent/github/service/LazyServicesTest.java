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

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.impl.MapConfigSource;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.container.configuration.ConfigurationRegistration;
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.github.client.DiscussionClient;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.command.PollCommand;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.context.GithubContextSnapshot;
import io.yupiik.ia.agent.github.context.GithubPrivateContext;
import io.yupiik.ia.agent.github.execution.AttemptPreparation;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.model.TaskKind;
import io.yupiik.ia.agent.github.model.TaskState;
import io.yupiik.ia.agent.github.store.TaskStore;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LazyServicesTest {
    @TempDir
    Path home;

    @Test
    void resolvingServicesDoesNotInitializeAuthenticationOrCreateState() {
        final var state = home.resolve("uncreated");
        try (final var container =
                configure(Map.of("github.home", state.toString())).start()) {
            for (final var type : new Class<?>[] {
                TaskStore.class,
                BotAudit.class,
                BotMaintenance.class,
                GithubRuntime.class,
                Poller.class,
                ReviewSummaryPoller.class,
                DiscussionPoller.class,
                RepositoryDiscovery.class,
                Worker.class,
                TeamAuthorization.class,
                DiscussionClient.class,
                ForkProvisioner.class,
                AttemptPreparation.class,
                DiscussionPublisher.class,
                ReviewPublisher.class,
                StrictSolvePublisher.class,
                GithubContextSnapshot.class,
                GithubPrivateContext.class
            }) {
                try (final var instance = container.lookup(type)) {
                    assertNotEquals(type, instance.instance().getClass(), type.getName());
                }
            }
            assertFalse(Files.exists(state));
        }
        assertFalse(Files.exists(state));
    }

    @Test
    void configurationSourcesRemainLocalToEachContainer() {
        final var firstHome = home.resolve("first").toString();
        final var secondHome = home.resolve("second").toString();
        try (final var first = configure(Map.of("github.home", firstHome)).start();
                final var second = configure(Map.of("github.home", secondHome)).start();
                final var firstConfiguration = first.lookup(GithubBotConfiguration.class);
                final var secondConfiguration = second.lookup(GithubBotConfiguration.class)) {
            assertEquals(firstHome, firstConfiguration.instance().home());
            assertEquals(secondHome, secondConfiguration.instance().home());
            assertFalse(Files.exists(home.resolve("first")));
            assertFalse(Files.exists(home.resolve("second")));
        }
    }

    @Test
    void pollCommandRunsReviewBaselineAndMentionSearchThroughTheContainer() throws Exception {
        final var requests = new CopyOnWriteArrayList<String>();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                final var path = exchange.getRequestURI().getPath();
                final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                final String response;
                if ("POST".equals(exchange.getRequestMethod()) && "/graphql".equals(path)) {
                    if (body.contains("query ReviewDiscovery")) {
                        requests.add("reviews");
                        response = """
                                {"data":{"r0":{"nameWithOwner":"owner/repo","pullRequests":{
                                  "nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":null}
                                }}}}
                                """;
                    } else if (body.contains("query MentionCandidates")) {
                        requests.add("search");
                        response = """
                                {"data":{"search":{"issueCount":0,"nodes":[],
                                  "pageInfo":{"hasNextPage":false,"endCursor":null}
                                }}}
                                """;
                    } else {
                        throw new IllegalStateException("Unexpected GraphQL operation");
                    }
                } else if ("GET".equals(exchange.getRequestMethod())
                        && List.of(
                                        "/repos/owner/repo/issues",
                                        "/repos/owner/repo/issues/comments",
                                        "/repos/owner/repo/pulls/comments")
                                .contains(path)) {
                    requests.add(path);
                    response = "[]";
                } else {
                    throw new IllegalStateException("Unexpected request: " + exchange.getRequestMethod() + " " + path);
                }
                final var bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        final var values = Map.ofEntries(
                Map.entry("github.home", home.toString()),
                Map.entry("github.login", "bot"),
                Map.entry("github.authorization.userIds", "42"),
                Map.entry("github.authorization.teams", ""),
                Map.entry("github.repositories.explicit", "owner/repo"),
                Map.entry("github.repositories.discovery", ""),
                Map.entry("github.repositories.includes", ""),
                Map.entry("github.repositories.excludes", ""),
                Map.entry("poll.reviews.enabled", "true"),
                Map.entry("poll.discussions.enabled", "false"),
                Map.entry("poll.search.enabled", "true"));
        try (final var json = BotJsonMapper.create(key -> Optional.empty());
                final var github = new GitHubClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        json,
                        () -> "test-token",
                        BotTestConfiguration.CLOCK);
                final var container = configure(values)
                        .register(new ProvidedInstanceBean<>(DefaultScoped.class, GitHubClient.class, () -> github) {
                            @Override
                            public int priority() {
                                return 2000;
                            }
                        })
                        .start();
                final var command = container.lookup(PollCommand.class);
                final var store = container.lookup(TaskStore.class)) {
            assertFalse(Files.exists(home.resolve("state.json")));
            command.instance().run();
            assertEquals(
                    List.of(
                            "/repos/owner/repo/issues",
                            "/repos/owner/repo/issues/comments",
                            "/repos/owner/repo/pulls/comments",
                            "reviews"),
                    requests);
            assertTrue(Files.isRegularFile(home.resolve("state.json")));
            requests.clear();
            command.instance().run();
            assertEquals(List.of("search"), requests);
            assertTrue(
                    store.instance().<Boolean>transaction(state -> state.list().isEmpty()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void injectedMapperDiscoversModelsAndLocalStoreWorksWithoutAuthentication() {
        try (final var container =
                        configure(Map.of("github.home", home.toString())).start();
                final var mapper = container.lookup(JsonMapper.class);
                final var store = container.lookup(TaskStore.class)) {
            final var json = mapper.instance();
            assertEquals("\"app\"", json.toString(GithubBotConfiguration.AuthType.app));
            assertEquals(
                    GithubBotConfiguration.AuthType.pat,
                    json.fromString(GithubBotConfiguration.AuthType.class, "\"pat\""));
            final var id = store.instance()
                    .transaction(state -> state.enqueue(
                            "container-test", TaskFixtures.task(Map.of("kind", "help", "repository", "acme/repo"))));
            final var task =
                    store.instance().transaction(state -> state.task(id).document());
            assertEquals(TaskKind.help, task.kind());
            assertEquals(TaskState.pending, task.state());
            assertEquals(task, json.fromString(TaskDocument.class, json.toString(task)));
        }
    }

    private static ConfiguringContainer configure(final Map<String, String> values) {
        return ConfiguringContainer.of()
                .register(new ProvidedInstanceBean<>(
                        DefaultScoped.class, ArgsConfigSource.class, () -> new ArgsConfigSource(List.of())))
                .register(new FusionListener<ConfigurationRegistration>() {
                    @Override
                    public Type eventType() {
                        return ConfigurationRegistration.class;
                    }

                    @Override
                    public int priority() {
                        return Integer.MIN_VALUE;
                    }

                    @Override
                    public void onEvent(
                            final RuntimeContainer container, final ConfigurationRegistration registration) {
                        registration.addSource().accept(new MapConfigSource(values));
                    }
                });
    }
}
