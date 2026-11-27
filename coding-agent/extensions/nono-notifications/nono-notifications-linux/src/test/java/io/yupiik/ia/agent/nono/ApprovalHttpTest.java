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
package io.yupiik.ia.agent.nono;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.FusionModule;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.http.server.spi.Endpoint;
import io.yupiik.fusion.httpserver.jre.JreHttpServer;
import io.yupiik.fusion.httpserver.jre.JreHttpServerConfiguration;
import io.yupiik.ia.agent.nono.ui.ApprovalHandle;
import io.yupiik.ia.agent.nono.ui.ApprovalUi;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ApprovalHttpTest {
    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1"})
    void authenticatesCorrelatesAndAuditsWithoutSecrets(final String host) throws Exception {
        final var root = TestTempDirs.create(Path.of(System.getProperty("user.home")), ".nono-http-test-");
        final var prompts = new AtomicInteger();
        final ApprovalUi ui = (text, budget) -> {
            prompts.incrementAndGet();
            return handle(CompletableFuture.completedFuture(true));
        };
        try (final var mapper = ApprovalProtocolTest.mapper();
                final var executor = Executors.newFixedThreadPool(8);
                final var timer = Executors.newSingleThreadScheduledExecutor();
                final var client = HttpClient.newHttpClient();
                final var state = new PrivateState(root.resolve("launch"), mapper)) {
            try (final var coordinator = new ApprovalCoordinator(ui, message -> {}, state, 3);
                    final var server = new JreHttpServer(
                            new JreHttpServerConfiguration(host, 0, false, executor),
                            List.of(Endpoint.of(
                                    request -> true,
                                    new ApprovalEndpoint(state, coordinator, timer, mapper)::handle,
                                    0)))) {
                server.start();
                state.publish(host, server.port(), 3);
                final var backend = Files.readString(root.resolve("launch/backend.json"));
                final var token = backend.substring(backend.indexOf("http://") + 7, backend.indexOf('@'));
                final var authorization =
                        "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.US_ASCII));
                final var authority = host.contains(":") ? "[" + host + "]" : host;
                assertTrue(backend.contains("@" + authority + ":" + server.port() + "/approve"));
                assertTrue(Files.readString(root.resolve("launch/ready.json")).contains("\"host\":\"" + host + "\""));
                final var uri = URI.create("http://" + authority + ":" + server.port() + "/approve");
                final var body = ApprovalProtocolTest.body("r1", "s1", "/private/target");
                assertEquals(401, post(client, uri, "Basic wrong", body).statusCode());
                assertEquals(0, prompts.get());
                assertEquals(
                        401,
                        post(client, uri.resolve("/missing"), "Basic wrong", body)
                                .statusCode());
                assertEquals(
                        404,
                        post(client, uri.resolve("/missing"), authorization, body)
                                .statusCode());
                assertEquals(
                        404,
                        post(client, URI.create(uri + "?query"), authorization, body)
                                .statusCode());
                assertEquals(
                        404,
                        client.send(
                                        HttpRequest.newBuilder(uri)
                                                .header("Authorization", authorization)
                                                .GET()
                                                .build(),
                                        HttpResponse.BodyHandlers.ofString())
                                .statusCode());
                assertEquals(
                        400,
                        post(client, uri, authorization, new byte[ApprovalProtocol.MAX_BODY + 1])
                                .statusCode());
                assertTrue(post(client, uri, authorization, body).body().contains("granted"));
                assertTrue(post(client, uri, authorization, body).body().contains("granted"));
                assertEquals(1, prompts.get());
                assertTrue(post(client, uri, authorization, ApprovalProtocolTest.body("r1", "s1", "/conflict"))
                        .body()
                        .contains("denied"));
                assertTrue(post(client, uri, authorization, ApprovalProtocolTest.body("r2", "other-session", "/target"))
                        .body()
                        .contains("denied"));
                assertEquals(1, prompts.get());
                assertEquals(
                        400,
                        post(client, uri, authorization, "{}".getBytes(StandardCharsets.UTF_8))
                                .statusCode());
                final var audit = Files.readString(root.resolve("launch/decisions.jsonl"));
                assertFalse(audit.contains(token));
                assertFalse(audit.contains("target"));
                assertFalse(audit.contains("r1"));
                assertEquals(
                        PosixFilePermissions.fromString("rw-------"),
                        Files.getPosixFilePermissions(root.resolve("launch/backend.json")));
                assertEquals(
                        PosixFilePermissions.fromString("rwx------"),
                        Files.getPosixFilePermissions(root.resolve("launch")));
                assertThrows(FileAlreadyExistsException.class, () -> new PrivateState(root.resolve("launch"), mapper));
            }
        } finally {
            TestTempDirs.cleanup(root);
        }
    }

    @Test
    void deadlineCancelsUiAndRejectsLateGrant() throws Exception {
        final var root = TestTempDirs.create(Path.of(System.getProperty("user.home")), ".nono-deadline-test-");
        final var answer = new CompletableFuture<Boolean>();
        final var closed = new AtomicInteger();
        try (final var mapper = ApprovalProtocolTest.mapper();
                final var state = new PrivateState(root.resolve("launch"), mapper)) {
            final ApprovalUi ui = (text, duration) -> new ApprovalHandle() {
                @Override
                public CompletionStage<Boolean> decision() {
                    return answer;
                }

                @Override
                public void close() {
                    closed.incrementAndGet();
                }
            };
            try (final var coordinator = new ApprovalCoordinator(ui, message -> {}, state, 2)) {
                final var request =
                        new ApprovalProtocol(mapper).parse(ApprovalProtocolTest.body("r1", "s1", "/target"));
                assertEquals("timeout", coordinator.decide(request, 1, System.nanoTime()));
                answer.complete(true);
                assertNotEquals("granted", coordinator.decide(request, 1, System.nanoTime()));
                assertEquals(1, closed.get());
            }
        } finally {
            TestTempDirs.cleanup(root);
        }
    }

    @Test
    void concurrentRetriesShareDialogAndShutdownDenies() throws Exception {
        final var root = TestTempDirs.create(Path.of(System.getProperty("user.home")), ".nono-concurrent-test-");
        final var entered = new CountDownLatch(1);
        final var answer = new CompletableFuture<Boolean>();
        final var prompts = new AtomicInteger();
        final ApprovalUi ui = (text, duration) -> {
            prompts.incrementAndGet();
            entered.countDown();
            return handle(answer);
        };
        try (final var mapper = ApprovalProtocolTest.mapper();
                final var workers = Executors.newVirtualThreadPerTaskExecutor();
                final var state = new PrivateState(root.resolve("launch"), mapper)) {
            try (final var coordinator = new ApprovalCoordinator(ui, message -> {}, state, 3)) {
                final var request =
                        new ApprovalProtocol(mapper).parse(ApprovalProtocolTest.body("r1", "s1", "/target"));
                final var first = workers.submit(() -> coordinator.decide(request, 3, System.nanoTime()));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                final var retry = workers.submit(() -> coordinator.decide(request, 3, System.nanoTime()));
                Assertions.assertDoesNotThrow(coordinator::close);
                answer.complete(true);
                assertEquals("denied", first.get(2, TimeUnit.SECONDS));
                assertEquals("denied", retry.get(2, TimeUnit.SECONDS));
                assertEquals(1, prompts.get());
            }
        } finally {
            TestTempDirs.cleanup(root);
        }
    }

    @Test
    void admits128PendingUniqueRequestsAndRejectsOneExcessWithoutOpeningAnotherDialog() throws Exception {
        final var root = TestTempDirs.create(Path.of(System.getProperty("user.home")), ".nono-capacity-test-");
        final var answer = new CompletableFuture<Boolean>();
        final var entered = new CountDownLatch(1);
        final var prompts = new AtomicInteger();
        final var visible = new AtomicInteger();
        final var maximumVisible = new AtomicInteger();
        final ApprovalUi ui = (text, budget) -> {
            prompts.incrementAndGet();
            maximumVisible.accumulateAndGet(visible.incrementAndGet(), Math::max);
            entered.countDown();
            return new ApprovalHandle() {
                private final AtomicBoolean closed = new AtomicBoolean();

                @Override
                public CompletionStage<Boolean> decision() {
                    return answer;
                }

                @Override
                public void close() {
                    if (closed.compareAndSet(false, true)) {
                        visible.decrementAndGet();
                    }
                }
            };
        };
        try (final var mapper = ApprovalProtocolTest.mapper();
                final var executor = Executors.newVirtualThreadPerTaskExecutor();
                final var timer = Executors.newSingleThreadScheduledExecutor();
                final var client = HttpClient.newHttpClient();
                final var state = new PrivateState(root.resolve("launch"), mapper);
                final var coordinator = new ApprovalCoordinator(ui, message -> {}, state, 60);
                final var server = new JreHttpServer(
                        new JreHttpServerConfiguration("127.0.0.1", 0, false, executor),
                        List.of(Endpoint.of(
                                request -> true,
                                new ApprovalEndpoint(state, coordinator, timer, mapper)::handle,
                                0)))) {
            try {
                server.start();
                state.publish("127.0.0.1", server.port(), 60);
                final var backend = Files.readString(state.directory().resolve("backend.json"));
                final var credentials = backend.substring(backend.indexOf("http://") + 7, backend.indexOf('@'));
                final var authorization =
                        "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.US_ASCII));
                final var uri = URI.create("http://127.0.0.1:" + server.port() + "/approve");
                final var pending = new ArrayList<CompletableFuture<HttpResponse<String>>>();
                // Arrival order is not guaranteed: the first response from 129 requests must
                // be the capacity rejection while the other 128 still await the UI.
                for (var index = 0; index < 129; index++) {
                    final var request = HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(60))
                            .header("Authorization", authorization)
                            .header("Content-Type", "application/json")
                            .header("X-Nono-Timeout-Secs", "60")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(
                                    ApprovalProtocolTest.body("r" + index, "s1", "/target")))
                            .build();
                    final var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
                    pending.add(response);
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                CompletableFuture.anyOf(pending.toArray(CompletableFuture[]::new))
                        .get(10, TimeUnit.SECONDS);
                final var rejected =
                        pending.stream().filter(CompletableFuture::isDone).toList();
                assertEquals(1, rejected.size(), "Only the excess request may complete before the UI answers");
                final var excess = rejected.getFirst().get();
                assertEquals(200, excess.statusCode());
                assertEquals("{\"decision\":\"denied\"}", excess.body());
                assertEquals(1, prompts.get());
                assertEquals(
                        401,
                        post(client, uri, "Basic wrong", ApprovalProtocolTest.body("unauthorized", "s1", "/target"))
                                .statusCode());
                answer.complete(true);
                for (final var response : pending) {
                    final var result = response.get(10, TimeUnit.SECONDS);
                    assertEquals(200, result.statusCode());
                    assertEquals(
                            response == rejected.getFirst()
                                    ? "{\"decision\":\"denied\"}"
                                    : "{\"decision\":\"granted\"}",
                            result.body());
                }
                assertEquals(128, prompts.get());
                assertEquals(1, maximumVisible.get());
                assertEquals(0, visible.get());
                assertTrue(post(client, uri, authorization, ApprovalProtocolTest.body("after-drain", "s1", "/target"))
                        .body()
                        .contains("granted"));
            } finally {
                Assertions.assertDoesNotThrow(coordinator::close);
                answer.complete(false);
            }
        } finally {
            TestTempDirs.cleanup(root);
        }
    }

    @Test
    void rejectsUnsupportedApprovalUi() {
        for (final var ui : List.of("", "notification", "auto", "Notify-Send", "zenity", "notify-send")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> configuration(key -> "ui".equals(key) ? Optional.of(ui) : Optional.empty()));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new ServeCommand.Conf(
                        "unused", "/usr/bin/zenity", 60, "127.0.0.1", null, "/usr/bin/notify-send"));
    }

    @Test
    void defaultsApprovalUiToZenity() {
        assertEquals(
                ServeCommand.Ui.ZENITY, configuration(key -> Optional.empty()).ui());
    }

    @ParameterizedTest
    @EnumSource(ServeCommand.Ui.class)
    void parsesApprovalUi(final ServeCommand.Ui ui) {
        assertEquals(
                ui,
                configuration(key -> "ui".equals(key) ? Optional.of(ui.name()) : Optional.empty())
                        .ui());
    }

    @Test
    void rejectsNonNumericOrNonLoopbackHosts() {
        for (final var host : List.of("localhost", "0.0.0.0", "::", "192.0.2.1", "[::1]", "::1%lo", "")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ServeCommand.Conf(
                            "unused", "/usr/bin/zenity", 60, host, ServeCommand.Ui.ZENITY, "/usr/bin/notify-send"));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new ServeCommand.Conf(
                        "unused", "/usr/bin/zenity", 60, null, ServeCommand.Ui.ZENITY, "/usr/bin/notify-send"));
        assertEquals(
                "::1",
                new ServeCommand.Conf(
                                "unused", "/usr/bin/zenity", 60, "::1", ServeCommand.Ui.ZENITY, "/usr/bin/notify-send")
                        .host());
        assertEquals(
                "127.0.0.1",
                new ServeCommand.Conf(
                                "unused",
                                "/usr/bin/zenity",
                                60,
                                "127.0.0.1",
                                ServeCommand.Ui.ZENITY,
                                "/usr/bin/notify-send")
                        .host());
    }

    private ApprovalHandle handle(final CompletableFuture<Boolean> result) {
        return new ApprovalHandle() {
            @Override
            public CompletionStage<Boolean> decision() {
                return result;
            }

            @Override
            public void close() {}
        };
    }

    private HttpResponse<String> post(
            final HttpClient client, final URI uri, final String authorization, final byte[] body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(5))
                        .header("Authorization", authorization)
                        .header("Content-Type", "application/json")
                        .header("X-Nono-Timeout-Secs", "3")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static ServeCommand.Conf configuration(final Configuration properties) {
        try (final var container = ConfiguringContainer.of()
                        .register(
                                new ProvidedInstanceBean<>(DefaultScoped.class, Configuration.class, () -> properties))
                        .register(new FusionModule() {
                            @Override
                            public BiPredicate<RuntimeContainer, FusionBean<?>> beanFilter() {
                                return (container, bean) -> bean.type() == ServeCommand.Conf.class;
                            }

                            @Override
                            public BiPredicate<RuntimeContainer, FusionListener<?>> listenerFilter() {
                                return (container, listener) -> false;
                            }
                        })
                        .start();
                final var instance = container.lookup(ServeCommand.Conf.class)) {
            return instance.instance();
        }
    }
}
