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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.store.TaskStore;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForkProvisionerTest {
    private static final String VALID = "{\"fork\":true,\"parent\":{\"full_name\":\"SOURCE/Repo\"}}";
    private static final Map<String, String> INTENT = Map.of("upstream", "source/repo", "fork", "destination/repo");

    @TempDir
    Path home;

    @Test
    void existingForkNeedsNoIntentOrPost() throws Exception {
        try (final var fixture = new Fixture(home)) {
            fixture.reply(200, VALID);
            assertEquals("destination/repo", fixture.ensure());
            assertNull(fixture.intent());
            assertEquals(List.of("GET /repos/destination/repo"), fixture.calls);
        }
    }

    @Test
    void commitsIntentBeforePostAndVerifiesWithGet() throws Exception {
        try (final var fixture = new Fixture(home)) {
            fixture.reply(404, "{}");
            fixture.reply(202, "{}");
            fixture.reply(200, VALID);
            assertEquals("destination/repo", fixture.ensure());
            assertEquals(INTENT, fixture.intent());
            assertEquals(List.of(INTENT), fixture.intentsAtPost);
            assertEquals(List.of(Map.of("organization", "destination")), fixture.postBodies);
            assertEquals(
                    List.of(
                            "GET /repos/destination/repo",
                            "POST /repos/source/repo/forks",
                            "GET /repos/destination/repo"),
                    fixture.calls);
        }
    }

    @Test
    void asynchronousNotFoundSurvivesRestartAndNeverRepeatsPost() throws Exception {
        try (final var fixture = new Fixture(home)) {
            fixture.reply(404, "{}");
            fixture.reply(202, VALID); // Even a plausible POST response must be verified with GET.
            fixture.reply(404, "{}");
            assertEquals(
                    404,
                    assertThrows(GitHubClient.HttpException.class, fixture::ensure)
                            .status());
            assertEquals(INTENT, fixture.intent());

            try (final var restarted = fixture.newClient()) {
                final var provisioner =
                        new ForkProvisioner(restarted, new TaskStore(BotTestConfiguration.home(home), fixture.json));
                fixture.reply(404, "{}");
                assertEquals(
                        404,
                        assertThrows(
                                        GitHubClient.HttpException.class,
                                        () -> provisioner.ensure(fixture.id, "source/repo", "destination"))
                                .status());
                fixture.reply(200, VALID);
                assertEquals("destination/repo", provisioner.ensure(fixture.id, "source/repo", "destination"));
            }
            assertEquals(1, fixture.postBodies.size());
            assertEquals(
                    List.of(
                            "GET /repos/destination/repo",
                            "POST /repos/source/repo/forks",
                            "GET /repos/destination/repo",
                            "GET /repos/destination/repo",
                            "GET /repos/destination/repo"),
                    fixture.calls);
        }
    }

    @Test
    void rejectedPostStillLeavesDurableIntent() throws Exception {
        try (final var fixture = new Fixture(home)) {
            fixture.reply(404, "{}");
            fixture.reply(422, "{}");
            assertEquals(
                    422,
                    assertThrows(GitHubClient.HttpException.class, fixture::ensure)
                            .status());
            fixture.reply(404, "{}");
            assertEquals(
                    404,
                    assertThrows(GitHubClient.HttpException.class, fixture::ensure)
                            .status());
            assertEquals(INTENT, fixture.intent());
            assertEquals(1, fixture.postBodies.size());
        }
    }

    @Test
    void preexistingIntentNeverPostsEvenIfCreationNeverStarted() throws Exception {
        try (final var fixture = new Fixture(home)) {
            fixture.store.transaction(state -> {
                state.task(fixture.id)
                        .forkProvisionIntent(new TaskDocument.ForkIntent("source/repo", "destination/repo", Map.of()));
                return null;
            });
            fixture.reply(404, "{}");
            assertEquals(
                    404,
                    assertThrows(GitHubClient.HttpException.class, fixture::ensure)
                            .status());
            assertTrue(fixture.postBodies.isEmpty());
        }
    }

    @Test
    void onlyActual404AllowsCreation() throws Exception {
        for (final int status : List.of(401, 403, 422, 302, 429, 503)) {
            try (final var fixture = new Fixture(home.resolve(Integer.toString(status)))) {
                fixture.reply(status, "{\"message\":\"GitHub GET returned 404\"}");
                final var error = assertThrows(GitHubClient.HttpException.class, fixture::ensure);
                assertEquals(status, error.status());
                assertNull(fixture.intent());
                assertTrue(fixture.postBodies.isEmpty());
            }
        }
    }

    @Test
    void malformedSuccessfulResponseDoesNotAuthorizeCreation() throws Exception {
        try (final var fixture = new Fixture(home)) {
            fixture.reply(200, "not JSON: GitHub GET returned 404");
            assertThrows(RuntimeException.class, fixture::ensure);
            assertNull(fixture.intent());
            assertTrue(fixture.postBodies.isEmpty());
        }
    }

    @Test
    void non404ReconciliationFailureRetainsIntent() throws Exception {
        try (final var fixture = new Fixture(home)) {
            fixture.reply(404, "{}");
            fixture.reply(202, "{}");
            fixture.reply(403, "{}");
            assertEquals(
                    403,
                    assertThrows(GitHubClient.HttpException.class, fixture::ensure)
                            .status());
            assertEquals(INTENT, fixture.intent());
            fixture.reply(200, VALID);
            assertEquals("destination/repo", fixture.ensure());
            assertEquals(1, fixture.postBodies.size());
        }
    }

    @Test
    void unexpectedOrMalformedRepositoryFailsWithoutCreation() throws Exception {
        final var bodies = List.of(
                "{\"fork\":false,\"parent\":{\"full_name\":\"source/repo\"}}",
                "{\"fork\":true,\"parent\":{\"full_name\":\"other/repo\"}}",
                "{\"fork\":true}",
                "{\"fork\":\"true\",\"parent\":{\"full_name\":\"source/repo\"}}",
                "[]");
        for (int index = 0; index < bodies.size(); index++) {
            try (final var fixture = new Fixture(home.resolve(Integer.toString(index)))) {
                fixture.reply(200, bodies.get(index));
                assertThrows(IllegalStateException.class, fixture::ensure);
                assertNull(fixture.intent());
                assertTrue(fixture.postBodies.isEmpty());
            }
        }
    }

    @Test
    void invalidRepositoryAfterPostRetainsIntent() throws Exception {
        try (final var fixture = new Fixture(home)) {
            fixture.reply(404, "{}");
            fixture.reply(202, VALID);
            fixture.reply(200, "{\"fork\":false}");
            assertThrows(IllegalStateException.class, fixture::ensure);
            assertEquals(INTENT, fixture.intent());
            fixture.reply(200, VALID);
            assertEquals("destination/repo", fixture.ensure());
            assertEquals(1, fixture.postBodies.size());
        }
    }

    @Test
    void differentIntentUnknownTaskAndInvalidPathsFailBeforeHttp() throws Exception {
        try (final var fixture = new Fixture(home)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.provisioner.ensure("missing", "source/repo", "destination"));
            for (final var upstream : List.of("source/repo/extra", "source/..", "source/repo?query", "source/%2f")) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> fixture.provisioner.ensure(fixture.id, upstream, "destination"));
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.provisioner.ensure(fixture.id, "source/repo", "destination/other"));
            fixture.store.transaction(state -> {
                state.task(fixture.id)
                        .forkProvisionIntent(new TaskDocument.ForkIntent("other/repo", "destination/repo", Map.of()));
                return null;
            });
            assertThrows(IllegalStateException.class, fixture::ensure);
            assertTrue(fixture.calls.isEmpty());
        }
    }

    private record Reply(int status, String body) {}

    private static final class Fixture implements AutoCloseable {
        private final JsonMapper json = BotJsonMapper.create(key -> Optional.empty());
        private final HttpServer server;
        private final TaskStore store;
        private final String id;
        private final GitHubClient client;
        private final ForkProvisioner provisioner;
        private final LinkedBlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private final List<Object> intentsAtPost = new CopyOnWriteArrayList<>();
        private final List<Object> postBodies = new CopyOnWriteArrayList<>();

        private Fixture(final Path home) throws Exception {
            store = new TaskStore(BotTestConfiguration.home(home), json);
            id = store.transaction(state -> state.enqueue("invocation", TaskFixtures.task(Map.of("kind", "solve"))));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                    if ("POST".equals(exchange.getRequestMethod())) {
                        intentsAtPost.add(new TaskStore(BotTestConfiguration.home(home), json)
                                .transaction(
                                        state -> TaskFixtures.map(state.task(id).forkProvisionIntent())));
                        postBodies.add(json.fromString(
                                Object.class,
                                new String(exchange.getRequestBody().readAllBytes(), UTF_8)));
                    }
                    final var reply = replies.poll();
                    final var bytes = (reply == null ? "{}" : reply.body()).getBytes(UTF_8);
                    // Avoid retry delays while still testing typed deferred HTTP errors.
                    if (reply != null && (reply.status() == 429 || reply.status() == 503)) {
                        exchange.getResponseHeaders().add("Retry-After", "3600");
                    }
                    exchange.sendResponseHeaders(reply == null ? 418 : reply.status(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
            client = newClient();
            provisioner = new ForkProvisioner(client, store);
        }

        private GitHubClient newClient() {
            return new GitHubClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    json,
                    () -> "secret",
                    BotTestConfiguration.CLOCK);
        }

        private void reply(final int status, final String body) {
            replies.add(new Reply(status, body));
        }

        private String ensure() {
            return provisioner.ensure(id, "source/repo", "destination");
        }

        private Object intent() {
            return store.transaction(state -> state.task(id).forkProvisionIntent() == null
                    ? null
                    : TaskFixtures.map(state.task(id).forkProvisionIntent()));
        }

        @Override
        public void close() {
            try {
                client.close();
            } finally {
                server.stop(0);
                json.close();
            }
        }
    }
}
