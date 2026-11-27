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
package io.yupiik.ia.agent.github.context;

import static io.yupiik.ia.agent.github.context.ContextTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.github.configuration.WorkerOptions;
import io.yupiik.ia.agent.github.context.git.TrustedGitStore;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextServiceTest {
    @TempDir
    Path temporary;

    @Test
    void discoveryIsPinnedMetadataOnlyAndSourceIsLazy() throws Exception {
        try (final var fixture = new ContextTestSupport(temporary)) {
            final var service = fixture.service();
            final var metadata = body(service.call(Map.of("operation", "summary")));
            assertEquals("acme/primary", service.primaryRepository());
            assertEquals("fixed-task", metadata.get("taskId"));
            assertEquals(PIN, metadata.get("headSha"));
            assertEquals(PIN, metadata.get("baseSha"));
            assertEquals(false, metadata.get("localWorktree"));
            assertEquals("lazy", metadata.get("source"));
            final var repositories =
                    (List<?>) service.call(Map.of("operation", "repositories")).get("body");
            assertEquals(
                    List.of(Map.of(
                            "repository",
                            "acme/primary",
                            "repositoryId",
                            "101",
                            "headSha",
                            PIN,
                            "baseSha",
                            PIN,
                            "primary",
                            true)),
                    repositories);
            assertEquals(
                    "SOURCE_REQUIRED",
                    body(service.call(Map.of("operation", "git", "argv", List.of("log", "-1"))))
                            .get("status"));
            assertEquals(List.of("/repos/acme/primary"), fixture.requests);
            assertFalse(Files.exists(temporary.resolve("private-0")));
        }
    }

    @Test
    void exactAllowlistResolvesStableIdentityAndDefaultPinOnceWithoutChangingPrimary() throws Exception {
        try (final var fixture = new ContextTestSupport(temporary)) {
            final var service = fixture.service();
            for (final var name : List.of("acme/extra-other", "other/extra", "acme/primary-extra")) {
                assertEquals(
                        "denied",
                        body(service.call(Map.of("operation", "request_repository", "repository", name)))
                                .get("status"));
            }
            assertEquals(List.of("/repos/acme/primary"), fixture.requests);
            final var granted =
                    body(service.call(Map.of("operation", "request_repository", "repository", "acme/extra")));
            assertEquals("granted", granted.get("status"));
            assertEquals("202", granted.get("repositoryId"));
            assertEquals(OTHER_PIN, granted.get("headSha"));
            final var requests = List.copyOf(fixture.requests);
            fixture.additionalPin.set("c".repeat(40));
            assertEquals(
                    "granted",
                    body(service.call(Map.of("operation", "request_repository", "repository", "ACME/EXTRA")))
                            .get("status"));
            assertEquals(requests, fixture.requests);
            final var repositories =
                    (List<?>) service.call(Map.of("operation", "repositories")).get("body");
            assertEquals(2, repositories.size());
            assertTrue(repositories.contains(Map.of(
                    "repository",
                    "acme/extra",
                    "repositoryId",
                    "202",
                    "headSha",
                    OTHER_PIN,
                    "baseSha",
                    OTHER_PIN,
                    "primary",
                    false)));
            assertEquals(PIN, body(service.call(Map.of("operation", "summary"))).get("headSha"));
            assertFalse(Files.exists(temporary.resolve("private-0")));
        }
    }

    @Test
    void ungrantedRepositoriesAndAdditionalRepositoryDiscussionsNeverReachUpstream() throws Exception {
        try (final var fixture = new ContextTestSupport(temporary)) {
            final var service = fixture.service();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.call(Map.of("operation", "prepare_source", "repository", "acme/extra")));
            service.call(Map.of("operation", "request_repository", "repository", "acme/extra"));
            final int count = fixture.requests.size();
            for (final var operation : List.of("pull", "files", "reviews", "reviewComments", "issueComments")) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.call(Map.of("operation", operation, "repository", "acme/extra")));
            }
            assertEquals(count, fixture.requests.size());
            final var discussion = body(service.call(Map.of("operation", "issueComments")));
            assertEquals("acme/primary", discussion.get("repository"));
            assertEquals(NOW.toString(), discussion.get("observedAt"));
            assertFalse(fixture.json.toString(discussion).contains("untrusted.invalid"));
            assertEquals("/repos/acme/primary/issues/7/comments?per_page=100&page=1", fixture.requests.getLast());
        }
    }

    @Test
    void unknownOperationsUrlsAndMalformedFieldsCannotSelectUpstream() throws Exception {
        try (final var fixture = new ContextTestSupport(temporary)) {
            final var service = fixture.service();
            for (final var request : List.<Map<String, Object>>of(
                    Map.of("operation", "https://attacker.invalid"),
                    Map.of("operation", "GET"),
                    Map.of("operation", "summary", "url", "https://attacker.invalid"),
                    Map.of("operation", "summary", "taskId", "another-task"),
                    Map.of("operation", "summary", "headers", Map.of()),
                    Map.of("operation", "prepare_source", "revision", "refs/heads/arbitrary"),
                    Map.of("operation", "request_repository", "repository", "https://github.com/acme/extra"),
                    Map.of("operation", "git", "argv", List.of("fetch")))) {
                assertThrows(IllegalArgumentException.class, () -> service.call(request), request.toString());
            }
            assertEquals(List.of("/repos/acme/primary"), fixture.requests);
        }
    }

    @Test
    void malformedNonpositiveFractionalAndExcessivePagesAreRejectedBeforeUpstream() throws Exception {
        try (final var fixture = new ContextTestSupport(temporary)) {
            final var service = fixture.service();
            for (final var page :
                    List.of("1", true, 0, -1, 11, new BigDecimal("1.5"), new BigDecimal("999999999999999999999"))) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.call(Map.of("operation", "issueComments", "page", page)));
            }
            assertEquals(List.of("/repos/acme/primary"), fixture.requests);
        }
    }

    @Test
    void fixedDeadlineAndCloseRevokeExistingAuthority() throws Exception {
        try (final var fixture = new ContextTestSupport(temporary)) {
            final var service = fixture.service();
            fixture.clock.advance(Duration.ofSeconds(60));
            assertThrows(IllegalStateException.class, () -> service.call(Map.of("operation", "summary")));
            assertThrows(IllegalStateException.class, service::metadata);
            assertThrows(
                    IllegalStateException.class,
                    () -> service.git("unknown", "GET", "service=git-upload-pack", new byte[0], null));
            assertEquals(List.of("/repos/acme/primary"), fixture.requests);
        }
        try (final var fixture = new ContextTestSupport(temporary.resolve("closed"))) {
            final var service = fixture.service();
            service.close();
            assertThrows(IllegalStateException.class, () -> service.call(Map.of("operation", "summary")));
        }
    }

    @Test
    void boundedWaitReturnsPreparationResultAndRejectsInvalidWaits() throws Exception {
        try (final var fixture = new ContextTestSupport(temporary)) {
            final var service = fixture.service(
                    fixture.configuration(""), new ContextBudget(4096, 1024 * 1024), PIN, "/not/executed/git");
            for (final var invalid : List.of(-1, 1001, new BigDecimal("1.5"), "1000", true)) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.call(Map.of("operation", "prepare_source", "waitMillis", invalid)));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.call(Map.of(
                                "operation", "operation_status", "operationId", "unknown", "waitMillis", invalid)));
            }
            final var result = body(service.call(Map.of("operation", "prepare_source", "waitMillis", 1000)));
            assertEquals("failed", result.get("status"));
            assertEquals("SOURCE_PREPARATION_FAILED", result.get("error"));
            assertTrue(result.get("operationId") instanceof String);
            assertEquals(
                    "failed",
                    body(service.call(Map.of(
                                    "operation",
                                    "operation_status",
                                    "operationId",
                                    result.get("operationId"),
                                    "waitMillis",
                                    0)))
                            .get("status"));
        }
    }

    @Test
    void cancellationKillsAdmittedProcessAndReturnsTheFixedCancellationResult() throws Exception {
        try (final var fixture = new ContextTestSupport(temporary)) {
            final var pidFile = temporary.resolve("admitted.pid");
            final var executable = launcher(temporary.resolve("StalledGit"), StalledGit.class, pidFile.toString());
            final var service = fixture.service(
                    fixture.configuration(""), new ContextBudget(4096, 1024 * 1024), PIN, executable.toString());
            final var operation = body(service.call(Map.of("operation", "prepare_source")));
            await(() -> Files.exists(pidFile));
            assertEquals(
                    "pending",
                    assertTimeoutPreemptively(
                                    Duration.ofSeconds(2),
                                    () -> body(service.call(Map.of(
                                            "operation",
                                            "operation_status",
                                            "operationId",
                                            operation.get("operationId"),
                                            "waitMillis",
                                            1))))
                            .get("status"));
            final var process =
                    ProcessHandle.of(Long.parseLong(Files.readString(pidFile))).orElseThrow();
            final var waitFailure = new AtomicReference<Throwable>();
            final var waiter = Thread.ofVirtual().start(() -> {
                try {
                    service.call(Map.of(
                            "operation",
                            "operation_status",
                            "operationId",
                            operation.get("operationId"),
                            "waitMillis",
                            1000));
                } catch (final Throwable failure) {
                    waitFailure.set(failure);
                }
            });
            try {
                // Observe the actual timed wait, not just submission, before cancelling concurrently.
                await(() -> waiter.getState() == Thread.State.TIMED_WAITING);
                final var cancelled = assertTimeoutPreemptively(
                        Duration.ofSeconds(3),
                        () -> body(service.call(
                                Map.of("operation", "cancel", "operationId", operation.get("operationId")))));
                assertEquals("cancelled", cancelled.get("status"));
                waiter.join(2000);
                assertFalse(waiter.isAlive(), "Long-poll survived cancellation");
                assertInstanceOf(IllegalStateException.class, waitFailure.get());
                await(() -> !process.isAlive());
                assertThrows(IllegalStateException.class, () -> service.call(Map.of("operation", "summary")));
            } finally {
                service.close();
                waiter.interrupt();
                waiter.join(2000);
                await(() -> !process.isAlive());
            }
        }
    }

    @Test
    void requestAndResponseLimitsAreSharedByServicesUsingTheSameBudget() throws Exception {
        try (final var fixture = new ContextTestSupport(temporary)) {
            final var budget = new ContextBudget(1, 1024 * 1024);
            final var first = fixture.service(fixture.configuration(""), budget, PIN, "/not/executed/git");
            final var second = fixture.service(fixture.configuration(""), budget, PIN, "/not/executed/git");
            first.call(Map.of("operation", "summary"));
            assertThrows(IllegalStateException.class, () -> second.call(Map.of("operation", "summary")));
            final var bytes = new ContextBudget(10, 1);
            final var limited = fixture.service(fixture.configuration(""), bytes, PIN, "/not/executed/git");
            assertThrows(IllegalStateException.class, () -> limited.call(Map.of("operation", "summary")));
            assertThrows(IllegalStateException.class, () -> limited.call(Map.of("operation", "repositories")));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void forkPreparationIncludesExplicitBaseAndPreservesPrimaryIdentityAndPinnedRuntime() throws Exception {
        try (final var fixture = new ContextTestSupport(temporary)) {
            final var git = fixture.gitFixture();
            final var service = forkService(fixture, git, "", 0);
            final var metadata = service.metadata();
            final var source = (Map<String, Object>) prepared(service, "HEAD").get("source");
            assertEquals("acme/extra", source.get("repository"));
            assertEquals(git.head(), source.get("headSha"));
            final var base = body(service.call(Map.of("operation", "git", "argv", List.of("rev-parse", "BASE"))));
            assertEquals(0, base.get("exitCode"));
            assertEquals(git.old(), base.get("stdout").toString().strip());
            final var diff = body(service.call(Map.of("operation", "git", "argv", List.of("diff", "BASE...HEAD"))));
            assertEquals(0, diff.get("exitCode"));
            assertTrue(diff.get("stdout").toString().contains("pinned source"));
            final var baseSource =
                    (Map<String, Object>) prepared(service, "BASE").get("source");
            assertEquals("acme/extra", baseSource.get("repository"));
            assertEquals(git.old(), baseSource.get("headSha"));
            assertEquals(metadata, service.metadata());
            assertEquals(
                    git.head(),
                    body(service.call(Map.of("operation", "git", "argv", List.of("rev-parse", "HEAD"))))
                            .get("stdout")
                            .toString()
                            .strip());
            assertEquals(
                    5,
                    fixture.requests.stream()
                            .filter("/repos/acme/extra"::equals)
                            .count());
            assertEquals(
                    5,
                    fixture.requests.stream()
                            .filter("/repos/acme/primary"::equals)
                            .count());
        }
    }

    @Test
    void forkPreparationRejectsEitherRepositoryChangingBeforeOrAfterAcquisition() throws Exception {
        for (final var repository : List.of("acme/primary", "acme/extra")) {
            for (final var changedAt : List.of(2, 3)) {
                try (final var fixture =
                        new ContextTestSupport(temporary.resolve(repository.replace('/', '-') + changedAt))) {
                    final var git = fixture.gitFixture();
                    final var service = forkService(fixture, git, repository, changedAt);
                    assertEquals("failed", prepared(service, "HEAD").get("status"));
                    assertEquals(
                            "SOURCE_REQUIRED",
                            body(service.call(Map.of("operation", "git", "argv", List.of("log", "-1"))))
                                    .get("status"));
                }
            }
        }
    }

    private Map<String, Object> prepared(final ContextService service, final String revision) {
        final var operation =
                body(service.call(Map.of("operation", "prepare_source", "depth", 2, "revision", revision)));
        final var result = new AtomicReference<Map<String, Object>>();
        // A fork view runs two acquisitions plus sanitization through independent Java launchers.
        // Leave room for the complete native Git pipeline under parallel-suite CPU contention.
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        do {
            result.set(body(service.call(
                    Map.of("operation", "operation_status", "operationId", operation.get("operationId")))));
            if (!"pending".equals(result.get().get("status"))) {
                return result.get();
            }
            try {
                Thread.sleep(25);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for fork source", interrupted);
            }
        } while (System.nanoTime() < deadline);
        assertFalse("pending".equals(result.get().get("status")), "Fork source preparation timed out");
        return result.get();
    }

    private ContextService forkService(
            final ContextTestSupport fixture, final GitFixture git, final String changedRepository, final int changedAt)
            throws Exception {
        fixture.upstream.createContext("/repos/acme/primary/pulls/7", exchange -> {
            try (exchange) {
                final var response = fixture.json
                        .toString(Map.of(
                                "base",
                                        Map.of(
                                                "sha",
                                                git.old(),
                                                "repo",
                                                Map.of("id", 101, "full_name", "acme/primary")),
                                "head",
                                        Map.of(
                                                "sha",
                                                git.head(),
                                                "repo",
                                                Map.of("id", 202, "full_name", "acme/extra"))))
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        for (final var repository : List.of("acme/primary", "acme/extra")) {
            final var calls = new AtomicInteger();
            fixture.upstream.createContext("/repos/" + repository, exchange -> {
                try (exchange) {
                    fixture.requests.add(exchange.getRequestURI().toString());
                    final var changed = calls.incrementAndGet() == changedAt && repository.equals(changedRepository);
                    final var response = fixture.json
                            .toString(Map.of(
                                    "id",
                                    changed ? 999 : repository.endsWith("primary") ? 101 : 202,
                                    "full_name",
                                    repository))
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                }
            });
        }
        final var executable = launcher(
                fixture.root.resolve("ForkTransport"),
                ForkTransport.class,
                git.directory().toUri().toASCIIString());
        final var store = new TrustedGitStore(
                fixture.root.resolve("fork-private"),
                executable.toString(),
                Duration.ofSeconds(30),
                8 * 1024 * 1024,
                fixture.configuration("").maxHttpBufferBytes(),
                null);
        final var task = TaskFixtures.task(
                Map.of("id", "fork-task", "repository", "acme/primary", "number", 7, "kind", "review"));
        final var service = new ContextService(
                fixture.github,
                fixture.json,
                task,
                fixture.clock,
                NOW.plusSeconds(60),
                new ContextBudget(4096, 16 * 1024 * 1024),
                fixture.configuration(""),
                new WorkerOptions.Source(WorkerOptions.SourceMode.git, "", 8, 8 * 1024 * 1024, 5),
                store);
        fixture.services.add(service);
        return service;
    }

    public static final class ForkTransport {
        public static void main(final String... args) throws Exception {
            final var command = new ArrayList<>(List.of("/usr/bin/git", "-c", "protocol.file.allow=always"));
            for (int i = 1; i < args.length; i++) {
                command.add(
                        List.of("https://github.com/acme/primary.git", "https://github.com/acme/extra.git")
                                        .contains(args[i])
                                ? args[0]
                                : args[i]);
            }
            final var process = new ProcessBuilder(command).inheritIO().start();
            Runtime.getRuntime().addShutdownHook(new Thread(process::destroyForcibly));
            System.exit(process.waitFor());
        }
    }
}
