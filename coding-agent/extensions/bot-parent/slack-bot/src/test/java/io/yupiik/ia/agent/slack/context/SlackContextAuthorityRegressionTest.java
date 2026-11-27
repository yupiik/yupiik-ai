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
package io.yupiik.ia.agent.slack.context;

import static io.yupiik.ia.agent.slack.support.SlackDomainFixture.task;
import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.bot.runtime.model.AgentConfigurationDocument;
import io.yupiik.ia.agent.slack.configuration.SlackContextConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackThreadConfiguration;
import io.yupiik.ia.agent.slack.model.TaskStatus;
import io.yupiik.ia.agent.slack.support.ControlledHttpClient;
import io.yupiik.ia.agent.slack.support.SlackDomainFixture;
import io.yupiik.ia.agent.slack.transport.SlackWebApi;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlackContextAuthorityRegressionTest {
    private static final String TOKEN = "private-context-regression-action-token";
    private static final Map<String, Object> SEARCH = Map.of("operation", "search", "terms", List.of("release"));

    @TempDir
    Path root;

    @Test
    void maintenanceCancelsAnActiveAttemptAndPreventsLatePublicationWithoutAnotherPoll() throws Exception {
        final var config = configuration(1);
        final var clock = new MutableClock();
        final var upstream = new ControlledHttpClient(true);
        try (final var fixture = new SlackDomainFixture(root, 4, 2);
                final var http = upstream;
                final var actions = new SlackActionRegistry(config, clock);
                final var dispatcher = new SlackContextAttempt.Dispatcher(config)) {
            final var queued = task("active-expiry", "U1", TaskStatus.queued, clock.millis());
            actions.admitted(queued, TOKEN, queued.thread(), clock.millis());
            final var grant = actions.claim(queued, 0, clock.millis()).orElseThrow();
            final var path = prepare(fixture, "active-expiry");
            final var api = new SlackWebApi(
                    fixture.json,
                    http,
                    new SlackWebApi.Settings(
                            URI.create("https://slack.invalid/api/"), Duration.ofSeconds(10), 131072, 131072),
                    () -> "xapp-test",
                    () -> "xoxb-test");
            try (final var attempt =
                    new SlackContextAttempt(path, fixture.json, config, api, grant, clock, dispatcher)) {
                publish(path, fixture, request(path, fixture, Map.of("operation", "resolve_user", "userId", "U1")));
                attempt.poll();
                assertTrue(upstream.invoked.await(5, TimeUnit.SECONDS));
                clock.advance(600);
                actions.expire(clock.millis(), () -> Set.of(queued.id()));
                assertTrue(upstream.cancelled.await(5, TimeUnit.SECONDS));
                assertTrue(upstream.exchange().isCancelled());
                upstream.completeBody("{\"ok\":true,\"user\":{\"id\":\"U1\",\"team_id\":\"T1\"}}");
                assertTrue(upstream.exchange().isCancelled(), "A late body must not replace cancellation");
                assertNull(grant.get());
                assertFalse(Files.exists(path.resolve("storage/slack-context/response.json")));
                Assertions.assertDoesNotThrow(attempt::close);
                Assertions.assertDoesNotThrow(attempt::close);
                assertDoesNotThrow(attempt::poll);
                assertFalse(Files.exists(path.resolve("storage/slack-context/response.json")));
            }
        }
    }

    @Test
    void upstreamSafeErrorsMapToAuthorizationStatusesAndRateLimitCoolsAllAttempts() throws Exception {
        final var config = configuration(2);
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            for (final var scenario : List.of(
                    List.of("ratelimited", "rate_limited"), List.of("missing_scope", "authorization_unavailable"))) {
                final var error = scenario.get(0);
                fixture.response = ignored -> "{\"ok\":false,\"error\":\"" + error + "\"}";
                final var firstPath = prepare(fixture, error + "-first");
                final var secondPath = prepare(fixture, error + "-second");
                final var firstTask = task(error + "-first", "U1", TaskStatus.queued, System.currentTimeMillis());
                final var secondTask = task(error + "-second", "U1", TaskStatus.queued, System.currentTimeMillis());
                try (final var actions = new SlackActionRegistry(config, Clock.systemUTC());
                        final var dispatcher = new SlackContextAttempt.Dispatcher(config)) {
                    actions.admitted(firstTask, TOKEN, firstTask.thread(), System.currentTimeMillis());
                    actions.admitted(secondTask, TOKEN, secondTask.thread(), System.currentTimeMillis());
                    try (final var first = new SlackContextAttempt(
                                    firstPath,
                                    fixture.json,
                                    config,
                                    fixture.api,
                                    actions.claim(firstTask, 0, System.currentTimeMillis())
                                            .orElseThrow(),
                                    Clock.systemUTC(),
                                    dispatcher);
                            final var second = new SlackContextAttempt(
                                    secondPath,
                                    fixture.json,
                                    config,
                                    fixture.api,
                                    actions.claim(secondTask, 0, System.currentTimeMillis())
                                            .orElseThrow(),
                                    Clock.systemUTC(),
                                    dispatcher)) {
                        final var before = fixture.exchanges.size();
                        assertEquals(
                                Map.of("status", scenario.get(1)),
                                exchange(first, firstPath, fixture, request(firstPath, fixture, SEARCH)));
                        assertEquals(before + 1, fixture.exchanges.size());
                        assertEquals(
                                "/api/assistant.search.context",
                                fixture.exchanges.getLast().request().uri().getPath());
                        if ("ratelimited".equals(error)) {
                            // HTTP 200 with Slack's ratelimited error uses the shared default 30-second cooldown.
                            assertEquals(
                                    Map.of("status", "rate_limited"),
                                    exchange(second, secondPath, fixture, request(secondPath, fixture, SEARCH)));
                            assertEquals(
                                    before + 1,
                                    fixture.exchanges.size(),
                                    "No second upstream exchange during cooldown");
                        }
                    }
                }
            }
        }
    }

    @Test
    void dispatcherConcurrencyIsSharedAndRequestExpiryInterruptsInFlightWork() throws Exception {
        final var config = configuration(1);
        final var clock = new MutableClock();
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var interrupted = new CountDownLatch(1);
        try (final var fixture = new SlackDomainFixture(root, 4, 2);
                final var actions = new SlackActionRegistry(config, clock);
                final var dispatcher = new SlackContextAttempt.Dispatcher(config)) {
            fixture.response = ignored -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("bounded-fake-timeout");
                    }
                } catch (final InterruptedException cancelled) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return "{\"ok\":false,\"error\":\"missing_scope\"}";
            };
            final var firstPath = prepare(fixture, "blocked-first");
            final var secondPath = prepare(fixture, "blocked-second");
            final var firstTask = task("blocked-first", "U1", TaskStatus.queued, System.currentTimeMillis());
            final var secondTask = task("blocked-second", "U1", TaskStatus.queued, System.currentTimeMillis());
            actions.admitted(firstTask, TOKEN, firstTask.thread(), System.currentTimeMillis());
            actions.admitted(secondTask, TOKEN, secondTask.thread(), System.currentTimeMillis());
            try (final var first = new SlackContextAttempt(
                            firstPath,
                            fixture.json,
                            config,
                            fixture.api,
                            actions.claim(firstTask, 0, System.currentTimeMillis())
                                    .orElseThrow(),
                            clock,
                            dispatcher);
                    final var second = new SlackContextAttempt(
                            secondPath,
                            fixture.json,
                            config,
                            fixture.api,
                            actions.claim(secondTask, 0, System.currentTimeMillis())
                                    .orElseThrow(),
                            clock,
                            dispatcher)) {
                try {
                    final var normal = request(firstPath, fixture, SEARCH);
                    final var expiring = new SlackContextMailbox.Request(
                            1, normal.channel(), normal.id(), System.currentTimeMillis() + 1500, SEARCH);
                    publish(firstPath, fixture, expiring);
                    first.poll();
                    assertTrue(entered.await(1, TimeUnit.SECONDS), "First attempt must reach the bounded fake");
                    assertEquals(
                            Map.of("status", "busy"),
                            exchange(second, secondPath, fixture, request(secondPath, fixture, SEARCH)));
                    assertEquals(
                            1, fixture.exchanges.size(), "The second attempt must share the global concurrency cap");
                    clock.advance(2);
                    first.poll();
                    assertTrue(interrupted.await(1, TimeUnit.SECONDS));
                    assertEquals(0L, interrupted.getCount(), "Request expiry must cancel the in-flight worker");
                    first.poll();
                    assertFalse(
                            Files.exists(firstPath.resolve("storage/slack-context/response.json")),
                            "Expired requests must not receive a late response");
                    assertEquals(1, fixture.exchanges.size());
                } finally {
                    release.countDown();
                }
            }
        }
    }

    @Test
    void nonconsecutiveReplayIsDeniedWithoutRedispatchingUpstream() throws Exception {
        final var config = configuration(2);
        try (final var fixture = new SlackDomainFixture(root, 4, 2);
                final var actions = new SlackActionRegistry(config, Clock.systemUTC());
                final var dispatcher = new SlackContextAttempt.Dispatcher(config)) {
            final var path = prepare(fixture, "nonconsecutive-replay");
            final var queued = task("nonconsecutive-replay", "U1", TaskStatus.queued, System.currentTimeMillis());
            actions.admitted(queued, TOKEN, queued.thread(), System.currentTimeMillis());
            fixture.response = ignored -> """
                    {"ok":true,"user":{"id":"U1","team_id":"T1","deleted":false,"is_bot":false,
                    "profile":{"display_name":"Ada","real_name":"Ada Lovelace"}}}
                    """;
            try (final var attempt = new SlackContextAttempt(
                    path,
                    fixture.json,
                    config,
                    fixture.api,
                    actions.claim(queued, 0, System.currentTimeMillis()).orElseThrow(),
                    Clock.systemUTC(),
                    dispatcher)) {
                final var a = request(path, fixture, Map.of("operation", "resolve_user", "userId", "U1"));
                assertEquals("ok", exchange(attempt, path, fixture, a).get("status"));
                assertEquals(
                        "ok",
                        exchange(attempt, path, fixture, request(path, fixture, Map.of("operation", "summary")))
                                .get("status"));
                assertEquals(Map.of("status", "denied"), exchange(attempt, path, fixture, a));
                assertEquals(1, fixture.exchanges.size(), "A -> B -> A must never redispatch A");
            }
        }
    }

    private Path prepare(final SlackDomainFixture fixture, final String name) throws Exception {
        final var path = Files.createDirectory(root.resolve(name));
        Files.createDirectory(path.resolve("storage"));
        Files.writeString(
                path.resolve("configuration.json"),
                fixture.json.toString(new AgentConfigurationDocument(Map.of("provider", "fake"), Map.of())));
        return path;
    }

    private static SlackContextMailbox.Request request(
            final Path path, final SlackDomainFixture fixture, final Map<String, Object> arguments) throws Exception {
        final var document = fixture.json.fromString(
                AgentConfigurationDocument.class, Files.readString(path.resolve("configuration.json")));
        final var binding = (String) ((Map<?, ?>) document.configuration().get("slackContext")).get("channel");
        return new SlackContextMailbox.Request(
                1, binding, UUID.randomUUID().toString(), System.currentTimeMillis() + 10000, arguments);
    }

    private static void publish(
            final Path path, final SlackDomainFixture fixture, final SlackContextMailbox.Request request)
            throws Exception {
        final var mailbox = path.resolve("storage/slack-context");
        Files.deleteIfExists(mailbox.resolve("response.json"));
        final var file = mailbox.resolve("request.json");
        Files.deleteIfExists(file);
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(file, fixture.json.toString(request));
    }

    private static Map<String, Object> exchange(
            final SlackContextAttempt attempt,
            final Path path,
            final SlackDomainFixture fixture,
            final SlackContextMailbox.Request request)
            throws Exception {
        publish(path, fixture, request);
        final var responseFile = path.resolve("storage/slack-context/response.json");
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(responseFile) && System.nanoTime() < deadline) {
            attempt.poll();
            Thread.onSpinWait();
        }
        assertTrue(Files.exists(responseFile), "Attempt must settle the bounded request");
        final var response =
                fixture.json.fromString(SlackContextMailbox.Response.class, Files.readString(responseFile));
        assertEquals(request.id(), response.id());
        assertEquals(request.channel(), response.channel());
        return response.body();
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        private void advance(final long seconds) {
            now.updateAndGet(value -> value.plusSeconds(seconds));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private static SlackContextConfiguration configuration(final int maxConcurrent) {
        return new SlackContextConfiguration(
                true,
                true,
                true,
                List.of("C1"),
                30,
                32,
                12,
                20,
                131072,
                20,
                maxConcurrent,
                600,
                false,
                new SlackThreadConfiguration(false, List.of(), 20, 100, 65536, 3, 24),
                false);
    }
}
