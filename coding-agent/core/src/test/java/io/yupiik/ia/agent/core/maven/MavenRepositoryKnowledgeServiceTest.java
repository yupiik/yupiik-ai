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
package io.yupiik.ia.agent.core.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.MavenRepositoryKnowledgeConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.maven.knowledge.Gav;
import io.yupiik.maven.knowledge.config.TokenBudget;
import io.yupiik.maven.knowledge.fingerprint.ProjectFingerprint;
import io.yupiik.maven.knowledge.llm.KnowledgeLlm;
import io.yupiik.maven.knowledge.write.KnowledgeWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class MavenRepositoryKnowledgeServiceTest {
    private CodingAgentConfiguration baseline;

    @BeforeEach
    void configure(@Fusion final CodingAgentConfiguration configuration) {
        baseline = configuration;
    }

    @TempDir
    static Path configurationRoot;

    @TempDir
    Path tmp;

    @Nested
    @FusionSupport(modules = KnowledgeModule.class)
    class JobCompletionReportsSuccessAndCannotBeCompletedByAnObserver {
        @Test
        void jobCompletionReportsSuccessAndCannotBeCompletedByAnObserver() throws Exception {
            final var m2 = Files.createDirectories(tmp.resolve("m2-job-success"));
            final var gav = Gav.of("g.com", "artifact-a", "1.0");
            final var root = cachedProject("job-success", gav, "success");
            classJar(m2, gav);
            final var settings = new ChatSettings("provider-a", "model-a", "variant-a", null);
            final var progress = new ArrayList<String>();
            final var summaries = new ArrayList<String>();
            try (final var executor = new ControllableExecutor()) {
                final var service = new MavenRepositoryKnowledgeService(
                        config(m2.toString(), root.toString()), new StubLlm(), executor, null);
                final var job = service.refreshJob(root, gav.coordinate(), progress::add, summaries::add, settings);
                final var settlements = new AtomicInteger();
                job.completion().whenComplete((result, failure) -> settlements.incrementAndGet());
                final var observerFailure = new IllegalStateException("observer failure");
                final var observer = job.completion().thenAccept(result -> {
                    throw observerFailure;
                });
                assertSame(job, service.currentIndex(root));
                assertEquals(root, job.root());
                assertEquals(settings, job.settings());
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> job.messages().add("mutated"));
                assertTrue(job.completion().toCompletableFuture().complete("not the result"));
                assertFalse(job.completion().toCompletableFuture().isDone());
                assertTrue(job.completion().toCompletableFuture().cancel(true));
                assertFalse(job.cancellationRequested(), "cancelling an observer is not cancelling the task");
                executor.run(0);
                final var summary = job.completion().toCompletableFuture().join();
                assertTrue(summary.startsWith("indexed 1, skipped 0"));
                assertEquals(summary, job.summary());
                assertEquals(List.of(summary), summaries);
                assertFalse(progress.isEmpty());
                assertEquals(1, job.doneCount());
                assertEquals(1, settlements.get());
                assertSame(
                        observerFailure,
                        assertThrows(
                                        CompletionException.class,
                                        () -> observer.toCompletableFuture().join())
                                .getCause());
                assertFalse(job.inFlight());
                assertNotSame(job, service.currentIndex(root));
                assertFalse(job.cancel());
                assertFalse(executor.isShutdown(), "the application executor is not owned by a job");
            }
        }
    }

    @Nested
    @FusionSupport(modules = KnowledgeModule.class)
    class WorkerFailureSettlesCompletionAndReleasesAdmission {
        @Test
        void workerFailureSettlesCompletionAndReleasesAdmission() throws Exception {
            final var m2 = Files.createDirectories(tmp.resolve("m2-job-failure"));
            final var gav = Gav.of("g.com", "artifact-a", "1.0");
            final var root = cachedProject("job-failure", gav, "failure");
            classJar(m2, gav);
            final var failure = new IllegalArgumentException("progress failure");
            final var successes = new AtomicInteger();
            try (final var executor = new ControllableExecutor()) {
                final var service = new MavenRepositoryKnowledgeService(
                        config(m2.toString(), root.toString()), new StubLlm(), executor, null);
                final var job = service.startIndexJob(
                        root,
                        List.of(gav.coordinate()),
                        true,
                        line -> {
                            throw failure;
                        },
                        summary -> successes.incrementAndGet(),
                        null);
                executor.run(0);
                assertSame(
                        failure,
                        assertThrows(
                                        CompletionException.class,
                                        () -> job.completion()
                                                .toCompletableFuture()
                                                .join())
                                .getCause());
                assertFalse(job.inFlight());
                assertNull(job.summary());
                assertEquals(0, successes.get());
                assertNotSame(job, service.currentIndex(root));
                final var next = service.startIndexJob(root, List.of(gav.coordinate()), false, null, null, null);
                executor.run(0);
                assertTrue(next.completion().toCompletableFuture().join().startsWith("indexed"));
            }
        }
    }

    @Nested
    @FusionSupport(modules = KnowledgeModule.class)
    class QueuedCancellationPreventsWorkAndSettlesOnceBeforeDispatch {
        @Test
        void queuedCancellationPreventsWorkAndSettlesOnceBeforeDispatch() throws Exception {
            final var m2 = Files.createDirectories(tmp.resolve("m2-job-queued"));
            final var gav = Gav.of("g.com", "artifact-a", "1.0");
            final var root = cachedProject("job-queued", gav, "queued");
            classJar(m2, gav);
            final var callbacks = new AtomicInteger();
            final var settlements = new AtomicInteger();
            try (final var executor = new ControllableExecutor()) {
                final var service = new MavenRepositoryKnowledgeService(
                        config(m2.toString(), root.toString()), new StubLlm(), executor, null);
                final var job = service.startIndexJob(
                        root,
                        List.of(gav.coordinate()),
                        false,
                        line -> callbacks.incrementAndGet(),
                        summary -> callbacks.incrementAndGet(),
                        null);
                job.completion().whenComplete((result, failure) -> settlements.incrementAndGet());
                assertTrue(job.cancel());
                assertTrue(job.cancellationRequested());
                assertFalse(job.cancel());
                assertFalse(job.inFlight());
                assertTrue(job.awaitCompletion(0, TimeUnit.NANOSECONDS));
                assertTrue(
                        assertThrows(
                                                CompletionException.class,
                                                () -> job.completion()
                                                        .toCompletableFuture()
                                                        .join())
                                        .getCause()
                                instanceof CancellationException);
                final var next = service.startIndexJob(root, List.of(gav.coordinate()), false, null, null, null);
                executor.run(0); // cancelled queue entries are inert even for generic executors
                assertFalse(new KnowledgeWriter(m2).alreadyIndexed(gav));
                assertEquals(0, callbacks.get());
                assertEquals(1, settlements.get());
                assertSame(next, service.currentIndex(root));
                executor.run(0);
                assertTrue(next.completion().toCompletableFuture().join().startsWith("indexed 1"));
                assertEquals(1, settlements.get());
            }
        }
    }

    @Nested
    @FusionSupport(modules = KnowledgeModule.class)
    class RunningCancellationInterruptsTheSubmittedWorkerAndSuppressesSuccess {
        @Test
        void runningCancellationInterruptsTheSubmittedWorkerAndSuppressesSuccess() throws Exception {
            final var m2 = Files.createDirectories(tmp.resolve("m2-job-interrupt"));
            final var gav = Gav.of("g.com", "artifact-a", "1.0");
            final var root = cachedProject("job-interrupt", gav, "interrupt");
            classJar(m2, gav);
            final var entered = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            final var interrupted = new CountDownLatch(1);
            final var successes = new AtomicInteger();
            try (final var executor = new ControllableExecutor()) {
                final var service = new MavenRepositoryKnowledgeService(
                        config(m2.toString(), root.toString()), new StubLlm(), executor, null);
                final var job = service.refreshJob(
                        root,
                        gav.coordinate(),
                        line -> {
                            entered.countDown();
                            try {
                                release.await();
                            } catch (final InterruptedException e) {
                                interrupted.countDown();
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(e);
                            }
                        },
                        summary -> successes.incrementAndGet(),
                        null);
                final var worker = Thread.ofPlatform().start(() -> executor.run(0));
                try {
                    entered.await();
                    assertTrue(job.cancel());
                    interrupted.await(); // success requires actual interruption, never a timing assumption
                } finally {
                    release.countDown();
                    worker.join();
                }
                assertFalse(job.inFlight());
                assertTrue(
                        assertThrows(
                                                CompletionException.class,
                                                () -> job.completion()
                                                        .toCompletableFuture()
                                                        .join())
                                        .getCause()
                                instanceof CancellationException);
                assertEquals(0, successes.get());
                assertFalse(executor.isShutdown());
            }
        }
    }

    @Nested
    @FusionSupport(modules = KnowledgeModule.class)
    class UncooperativeWorkerStaysAdmittedUntilItReallyExits {
        @Test
        void uncooperativeWorkerStaysAdmittedUntilItReallyExits() throws Exception {
            final var m2 = Files.createDirectories(tmp.resolve("m2-job-uncooperative"));
            final var gav = Gav.of("g.com", "artifact-a", "1.0");
            final var root = cachedProject("job-uncooperative", gav, "uncooperative");
            classJar(m2, gav);
            final var entered = new CountDownLatch(1);
            final var interrupted = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            final var successes = new AtomicInteger();
            try (final var executor = new ControllableExecutor()) {
                final var service = new MavenRepositoryKnowledgeService(
                        config(m2.toString(), root.toString()), new StubLlm(), executor, null);
                final var job = service.startIndexJob(
                        root,
                        List.of(gav.coordinate()),
                        true,
                        line -> {
                            entered.countDown();
                            var released = false;
                            while (!released) {
                                try {
                                    release.await();
                                    released = true;
                                } catch (final InterruptedException ignored) {
                                    interrupted.countDown(); // deliberately ignore cancellation until released
                                }
                            }
                        },
                        summary -> successes.incrementAndGet(),
                        null);
                final var worker = Thread.ofPlatform().start(() -> executor.run(0));
                try {
                    entered.await();
                    assertTrue(job.cancel());
                    interrupted.await();
                    assertTrue(job.inFlight());
                    assertTrue(job.cancellationRequested());
                    assertFalse(job.awaitCompletion(0, TimeUnit.NANOSECONDS));
                    assertFalse(job.completion().toCompletableFuture().isDone());
                    assertSame(job, service.currentIndex(root));
                    assertTrue(service.status(root).getFirst().startsWith("index in progress:"));
                    assertThrows(
                            IllegalStateException.class,
                            () -> service.startIndexJob(root, List.of(gav.coordinate()), false, null, null, null));
                } finally {
                    release.countDown();
                    worker.join();
                }
                assertFalse(job.inFlight());
                assertNotSame(job, service.currentIndex(root));
                assertNull(job.summary());
                assertEquals(0, successes.get());
                assertFalse(
                        new KnowledgeWriter(m2).alreadyIndexed(gav), "do not start another batch after cancellation");
                assertTrue(
                        assertThrows(
                                                CompletionException.class,
                                                () -> job.completion()
                                                        .toCompletableFuture()
                                                        .join())
                                        .getCause()
                                instanceof CancellationException);
                assertFalse(job.cancel());
            }
        }
    }

    @Nested
    @FusionSupport(modules = KnowledgeModule.class)
    class CancellationDrainsRealIndexerWorkBeforeReleasingAdmission {
        @ParameterizedTest
        @ValueSource(ints = {1, 2})
        @Timeout(5)
        void cancellationDrainsRealIndexerWorkBeforeReleasingAdmission(final int blockedFetch) throws Exception {
            final var m2 = Files.createDirectories(tmp.resolve("m2-real-drain"));
            final var a = Gav.of("g.com", "artifact-a", "1.0");
            final var b = Gav.of("g.com", "artifact-b", "2.0");
            final var rootA = cachedProject("real-drain-a", a, "A");
            final var rootB = cachedProject("real-drain-b", b, "B");
            classJar(m2, a);
            classJar(m2, b);
            documentationPom(m2, a, "https://docs.example.test/a");
            final var entered = new CountDownLatch(1);
            final var interrupted = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            final var blockedThread = new AtomicReference<Thread>();
            final var fetches = new AtomicInteger();
            final var progress = new CopyOnWriteArrayList<String>();
            final var successes = new AtomicInteger();
            final var settlements = new AtomicInteger();
            try (final var delegate = HttpClient.newHttpClient()) {
                final var http = new IndexHttpClient(delegate, request -> {
                    if (fetches.incrementAndGet() == blockedFetch) {
                        blockedThread.set(Thread.currentThread());
                        entered.countDown();
                        awaitIgnoringInterrupt(release, interrupted);
                    }
                    return "<main>artifact documentation</main>".getBytes(StandardCharsets.UTF_8);
                });
                try (http;
                        final var executor = new ControllableExecutor()) {
                    final var service = new MavenRepositoryKnowledgeService(
                            config(m2.toString(), rootA.toString()), new StubLlm(), executor, http);
                    final var job = service.startIndexJob(
                            rootA,
                            List.of(a.coordinate()),
                            true,
                            progress::add,
                            summary -> successes.incrementAndGet(),
                            null);
                    job.completion().whenComplete((result, failure) -> settlements.incrementAndGet());
                    final var worker = Thread.ofPlatform().start(() -> executor.run(0));
                    try {
                        entered.await();
                        if (blockedFetch == 1) {
                            assertSame(
                                    worker,
                                    blockedThread.get(),
                                    "first fetch is outer acquisition, before internal start");
                        } else {
                            assertNotSame(worker, blockedThread.get(), "second fetch is real internal executor work");
                        }
                        final var admittedProgress = progress.size();
                        assertTrue(job.cancel()); // must return even though actual work ignores interruption
                        interrupted.await();
                        assertTrue(job.inFlight());
                        assertFalse(job.awaitCompletion(0, TimeUnit.NANOSECONDS));
                        assertFalse(job.completion().toCompletableFuture().isDone());
                        assertSame(job, service.currentIndex(rootA));
                        assertTrue(service.status(rootA).getFirst().startsWith("index in progress:"));
                        assertThrows(
                                IllegalStateException.class,
                                () -> service.startIndexJob(rootA, List.of(a.coordinate()), false, null, null, null));
                        final var independent =
                                service.startIndexJob(rootB, List.of(b.coordinate()), false, null, null, null);
                        executor.run(0);
                        assertTrue(independent
                                .completion()
                                .toCompletableFuture()
                                .join()
                                .startsWith("indexed 1"));
                        assertSame(job, service.currentIndex(rootA), "B must not release A's admission");
                        assertEquals(admittedProgress, progress.size());
                        assertEquals(0, settlements.get());
                        assertEquals(0, http.closes.get());
                        assertFalse(executor.isShutdown());
                    } finally {
                        release.countDown();
                        worker.join();
                    }
                    assertFalse(job.inFlight());
                    assertEquals(1, settlements.get());
                    assertFalse(job.cancel());
                    assertNull(job.summary());
                    assertEquals(0, successes.get());
                    assertEquals(0, job.doneCount());
                    assertEquals(blockedFetch - 1, progress.size(), "no progress admitted after cancellation");
                    assertTrue(
                            assertThrows(
                                                    CompletionException.class,
                                                    () -> job.completion()
                                                            .toCompletableFuture()
                                                            .join())
                                            .getCause()
                                    instanceof CancellationException);
                    assertEquals(blockedFetch, fetches.get(), "no subsequent content phase after cancellation");
                    assertFalse(new KnowledgeWriter(m2).alreadyIndexed(a));
                    assertNotSame(job, service.currentIndex(rootA));
                    assertEquals("no index has run", service.status(rootA).getFirst(), "no stale success for A");
                    final var next = service.startIndexJob(rootA, List.of(a.coordinate()), false, null, null, null);
                    executor.run(0);
                    assertTrue(next.completion().toCompletableFuture().join().startsWith("indexed 1"));
                    assertEquals(0, http.closes.get(), "jobs must not close the borrowed HTTP client");
                    assertFalse(executor.isShutdown());
                }
                assertEquals(1, http.closes.get(), "only the owner closes the HTTP client");
            }
        }
    }

    @Nested
    @FusionSupport(modules = KnowledgeModule.class)
    class InternalFailureKeepsAdmissionUntilOtherInternalWorkDrains {
        @Test
        void internalFailureKeepsAdmissionUntilOtherInternalWorkDrains() throws Exception {
            final var m2 = Files.createDirectories(tmp.resolve("m2-internal-failure"));
            final var a = Gav.of("g.com", "artifact-a", "1.0");
            final var b = Gav.of("g.com", "artifact-b", "2.0");
            final var root = cachedProject("internal-failure", a, "failure");
            classJar(m2, a);
            classJar(m2, b);
            documentationPom(m2, a, "https://docs.example.test/a");
            documentationPom(m2, b, "https://docs.example.test/b");
            final var aFetches = new AtomicInteger();
            final var bFetches = new AtomicInteger();
            final var bothInternal = new CountDownLatch(2);
            final var fail = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            final var interrupted = new CountDownLatch(1);
            final var failure = new AssertionError("internal index failure");
            final var successes = new AtomicInteger();
            try (final var delegate = HttpClient.newHttpClient();
                    final var http = new IndexHttpClient(delegate, request -> {
                        if (request.uri().getPath().equals("/a")) {
                            if (aFetches.incrementAndGet() == 2) {
                                bothInternal.countDown();
                                awaitIgnoringInterrupt(fail, new CountDownLatch(0));
                                throw failure;
                            }
                        } else if (bFetches.incrementAndGet() == 2) {
                            bothInternal.countDown();
                            awaitIgnoringInterrupt(release, interrupted);
                        }
                        return "<main>artifact documentation</main>".getBytes(StandardCharsets.UTF_8);
                    });
                    final var executor = new ControllableExecutor()) {
                final var service = new MavenRepositoryKnowledgeService(
                        config(m2.toString(), root.toString()), new StubLlm(), executor, http);
                final var job = service.startIndexJob(
                        root,
                        List.of(a.coordinate(), b.coordinate()),
                        true,
                        null,
                        summary -> successes.incrementAndGet(),
                        null);
                final var settlements = new AtomicInteger();
                job.completion().whenComplete((result, error) -> settlements.incrementAndGet());
                final var worker = Thread.ofPlatform().start(() -> executor.run(0));
                try {
                    bothInternal.await();
                    fail.countDown();
                    interrupted.await(); // runBatch failed and requested shutdown of the other real task
                    assertTrue(job.inFlight());
                    assertFalse(job.completion().toCompletableFuture().isDone());
                    assertSame(job, service.currentIndex(root));
                    assertThrows(
                            IllegalStateException.class,
                            () -> service.startIndexJob(root, List.of(a.coordinate()), false, null, null, null));
                    assertEquals(0, settlements.get());
                } finally {
                    fail.countDown();
                    release.countDown();
                    worker.join();
                }
                assertFalse(job.inFlight());
                assertFalse(job.cancellationRequested());
                assertNull(job.summary());
                assertEquals(1, settlements.get());
                final var terminal = assertThrows(
                        CompletionException.class,
                        () -> job.completion().toCompletableFuture().join());
                Throwable cause = terminal;
                while (cause.getCause() != null) {
                    cause = cause.getCause();
                }
                assertSame(failure, cause);
                assertEquals(0, successes.get());
                assertNotSame(job, service.currentIndex(root));
                assertEquals(0, http.closes.get());
                assertFalse(executor.isShutdown());
                final var next = service.startIndexJob(root, List.of(a.coordinate()), false, null, null, null);
                executor.run(0);
                assertTrue(next.completion().toCompletableFuture().join().startsWith("indexed 1"));
            }
        }
    }

    private void documentationPom(final Path m2, final Gav gav, final String url) throws IOException {
        Files.writeString(
                m2.resolve(gav.groupId().replace('.', '/'))
                        .resolve(gav.artifactId())
                        .resolve(gav.version())
                        .resolve(gav.artifactId() + "-" + gav.version() + ".pom"),
                "<project><modelVersion>4.0.0</modelVersion><url>" + url + "</url></project>");
    }

    private static void awaitIgnoringInterrupt(final CountDownLatch release, final CountDownLatch interrupted) {
        var released = false;
        while (!released) {
            try {
                release.await();
                released = true;
            } catch (final InterruptedException ignored) {
                interrupted.countDown();
            }
        }
    }

    private static final class IndexHttpClient extends ExtendedHttpClient {
        private final AtomicInteger closes = new AtomicInteger();
        private final Function<HttpRequest, byte[]> responder;

        private IndexHttpClient(final HttpClient delegate, final Function<HttpRequest, byte[]> responder) {
            super(new ExtendedHttpClientConfiguration().setDelegate(delegate));
            this.responder = responder;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
            return (HttpResponse<T>) new IndexResponse(request, responder.apply(request));
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            super.close();
        }
    }

    private record IndexResponse(HttpRequest request, byte[] body) implements HttpResponse<byte[]> {
        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public Optional<HttpResponse<byte[]>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (key, value) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    @Nested
    @FusionSupport(modules = KnowledgeModule.class)
    class RejectedAndEmptyJobsAlwaysHaveTerminalCompletion {
        @Test
        void rejectedAndEmptyJobsAlwaysHaveTerminalCompletion() throws Exception {
            final var m2 = Files.createDirectories(tmp.resolve("m2-job-rejected"));
            final var gav = Gav.of("g.com", "artifact-a", "1.0");
            final var root = cachedProject("job-rejected", gav, "rejected");
            final var plain = Files.createDirectories(tmp.resolve("job-disabled"));
            try (final var executor = new ControllableExecutor()) {
                final var service = new MavenRepositoryKnowledgeService(
                        config(m2.toString(), root.toString()), new StubLlm(), executor, null);
                final var disabled = service.refreshJob(plain, null, null, null, null);
                assertEquals(
                        "maven repository knowledge is disabled",
                        disabled.completion().toCompletableFuture().join());
                final var empty = service.startIndexJob(root, List.of(), false, null, null, null);
                assertEquals(
                        "nothing to index",
                        empty.completion().toCompletableFuture().join());
                final var invalid = service.startIndexJob(root, List.of("invalid"), false, null, null, null);
                assertEquals(
                        "no valid GAVs to index",
                        invalid.completion().toCompletableFuture().join());
                assertFalse(empty.cancel());
                assertFalse(service.currentIndex(root).inFlight());
                executor.shutdown();
                for (var i = 0; i < 2; i++) {
                    final var rejected =
                            service.startIndexJob(root, List.of(gav.coordinate()), false, null, null, null);
                    assertTrue(
                            assertThrows(
                                                    CompletionException.class,
                                                    () -> rejected.completion()
                                                            .toCompletableFuture()
                                                            .join())
                                            .getCause()
                                    instanceof RejectedExecutionException);
                    assertFalse(rejected.inFlight());
                    assertFalse(rejected.cancel());
                    assertNotSame(rejected, service.currentIndex(root));
                }
            }
        }
    }

    @Nested
    @FusionSupport(modules = KnowledgeModule.class)
    class ProjectsHaveIndependentJobsCancellationAndCapturedSettings {
        @Test
        void projectsHaveIndependentJobsCancellationAndCapturedSettings() throws Exception {
            final var m2 = Files.createDirectories(tmp.resolve("m2-job-projects"));
            final var a = Gav.of("g.com", "artifact-a", "1.0");
            final var b = Gav.of("g.com", "artifact-b", "2.0");
            final var rootA = cachedProject("job-project-a", a, "A");
            final var rootB = cachedProject("job-project-b", b, "B");
            classJar(m2, a);
            classJar(m2, b);
            final var aSettings = new ChatSettings("provider-a", "model-a", null, null);
            final var bSettings = new ChatSettings("provider-b", "model-b", null, null);
            final var selections = new CopyOnWriteArrayList<ChatSettings>();
            final var client = new ChatClient() {
                @Override
                public int contextWindow(final ChatSettings settings) {
                    selections.add(settings);
                    return 32_000;
                }

                @Override
                public int outputTokens(final ChatSettings settings) {
                    return 1_000;
                }

                @Override
                public String model(final ChatSettings settings) {
                    selections.add(settings);
                    return ""; // no remote LLM calls
                }
            };
            final var configuration = config(m2.toString(), rootA.toString());
            final var llm = new YcaKnowledgeLlm(client, null, configuration, null);
            try (final var executor = new ControllableExecutor()) {
                final var service = new MavenRepositoryKnowledgeService(configuration, llm, executor, null);
                selections.clear(); // Service construction probes the process-default LLM before either job
                // exists.
                final var jobA = service.refreshJob(rootA, null, null, null, aSettings);
                final var jobB = service.refreshJob(rootB, null, null, null, bSettings);
                assertTrue(jobA.messages().get(1).contains(a.coordinate()));
                assertTrue(jobB.messages().get(1).contains(b.coordinate()));
                assertEquals(aSettings, jobA.settings());
                assertEquals(bSettings, jobB.settings());
                llm.withSettings(new ChatSettings("unrelated", "other-model", null, null));
                assertTrue(jobA.cancel());
                assertSame(jobB, service.currentIndex(rootB));
                assertTrue(service.status(rootB).getFirst().startsWith("index in progress:"));
                executor.run(0); // cancelled A
                executor.run(0); // B still succeeds
                assertTrue(jobB.completion().toCompletableFuture().join().contains(b.coordinate()));
                assertFalse(jobB.cancellationRequested());
                assertFalse(new KnowledgeWriter(m2).alreadyIndexed(a));
                assertEquals(
                        ProjectFingerprint.of(b, List.of(rootB.resolve("pom.xml"))),
                        new KnowledgeWriter(m2).readFingerprint(b));
                assertTrue(selections.contains(aSettings));
                assertTrue(selections.contains(bSettings));
                // status() also probes default availability; every non-default job selection remains
                // captured.
                assertTrue(selections.stream()
                        .filter(Objects::nonNull)
                        .allMatch(s -> s.equals(aSettings) || s.equals(bSettings)));
                assertEquals("no index has run", service.status(rootA).getFirst());
                assertTrue(service.status(rootB).getFirst().startsWith("index idle (last run):"));
            }
        }
    }

    @Nested
    @FusionSupport(modules = KnowledgeModule.class)
    class ThreadPoolQueueIsRemovedWithoutShuttingDownSharedExecutor {
        @Test
        void threadPoolQueueIsRemovedWithoutShuttingDownSharedExecutor() throws Exception {
            final var m2 = Files.createDirectories(tmp.resolve("m2-job-dequeue"));
            final var gav = Gav.of("g.com", "artifact-a", "1.0");
            final var root = cachedProject("job-dequeue", gav, "dequeue");
            final var release = new CountDownLatch(1);
            final var occupied = new CountDownLatch(1);
            try (final var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>())) {
                executor.execute(() -> {
                    occupied.countDown();
                    try {
                        release.await();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                try {
                    occupied.await();
                    final var service = new MavenRepositoryKnowledgeService(
                            config(m2.toString(), root.toString()), new StubLlm(), executor, null);
                    final var job = service.startIndexJob(root, List.of(gav.coordinate()), false, null, null, null);
                    assertEquals(1, executor.getQueue().size());
                    assertTrue(job.cancel());
                    assertEquals(0, executor.getQueue().size());
                    assertFalse(executor.isShutdown());
                    assertFalse(job.inFlight());
                } finally {
                    release.countDown();
                }
            }
        }
    }

    @Nested
    @FusionSupport(modules = SynchronousModule.class)
    class SynchronousProvisioningWaitsForItsCapturedJobNotAnotherProjectsCompletion {
        @Test
        @Timeout(5)
        void synchronousProvisioningWaitsForItsCapturedJobNotAnotherProjectsCompletion() throws Exception {
            final var m2 = Files.createDirectories(tmp.resolve("m2-job-synchronous"));
            final var a = Gav.of("g.com", "artifact-a", "1.0");
            final var b = Gav.of("g.com", "artifact-b", "2.0");
            final var rootA = cachedProject("job-sync-a", a, "A");
            final var rootB = cachedProject("job-sync-b", b, "B");
            classJar(m2, a);
            classJar(m2, b);
            final var configuration = config(m2.toString(), rootA.toString());
            final var returned = new CountDownLatch(1);
            try (final var executor = new ControllableExecutor()) {
                final var service = new MavenRepositoryKnowledgeService(configuration, new StubLlm(), executor, null);
                final var warmUp = Thread.ofPlatform().start(() -> {
                    try {
                        service.warmUp(rootA);
                    } finally {
                        returned.countDown();
                    }
                });
                try {
                    executor.submitted.await();
                    final var jobA = service.currentIndex(rootA);
                    final var jobB = service.startIndexJob(rootB, List.of(b.coordinate()), false, null, null, null);
                    executor.run(1);
                    assertFalse(jobB.inFlight());
                    assertSame(jobA, service.currentIndex(rootA));
                    assertEquals(1, returned.getCount(), "B completing cannot release A's synchronous wait");
                    executor.run(0);
                    warmUp.join();
                    assertEquals(0, returned.getCount());
                    assertFalse(jobA.inFlight());
                    assertTrue(new KnowledgeWriter(m2).alreadyIndexed(a));
                } finally {
                    service.currentIndex(rootA).cancel();
                    warmUp.interrupt();
                    warmUp.join();
                }
            }
        }
    }

    @Nested
    @FusionSupport(modules = KnowledgeModule.class)
    class ActiveRunsRemainDiscoverableUntilTheirOwnTasksFinish {
        @Test
        void activeRunsRemainDiscoverableUntilTheirOwnTasksFinish() throws Exception {
            final var m2 = Files.createDirectories(tmp.resolve("m2-active"));
            final var a = Gav.of("g.com", "artifact-a", "1.0");
            final var b = Gav.of("g.com", "artifact-b", "2.0");
            final var rootA = cachedProject("active-a", a, "A");
            final var rootB = cachedProject("active-b", b, "B");
            classJar(m2, a);
            classJar(m2, b);
            try (final var executor = new ControllableExecutor()) {
                final var service = new MavenRepositoryKnowledgeService(
                        config(m2.toString(), rootA.toString()), new StubLlm(), executor, null);
                service.startIndex(rootA, List.of(a.coordinate()), false, null, null, null);
                final var runA = service.currentIndex(rootA);
                service.startIndex(rootB, List.of(b.coordinate()), false, null, null, null);
                final var runB = service.currentIndex(rootB);
                assertSame(runA, service.currentIndex(rootA));
                assertSame(runB, service.currentIndex(rootB));
                assertTrue(runA.inFlight());
                assertTrue(runB.inFlight());
                assertTrue(service.currentIndex(rootA).inFlight());
                assertThrows(
                        IllegalStateException.class,
                        () -> service.startIndex(rootA, List.of(a.coordinate()), false, null, null, null));

                executor.run(1);
                assertFalse(runB.inFlight());
                assertTrue(runB.awaitCompletion(0, TimeUnit.SECONDS));
                assertNotSame(runB, service.currentIndex(rootB), "completed runs are not retained per root");
                assertFalse(service.currentIndex(rootB).inFlight());
                assertSame(runA, service.currentIndex(rootA));
                assertSame(runA, service.currentIndex(rootA));
                assertTrue(service.status(rootA).getFirst().startsWith("index in progress:"));
                assertTrue(service.status(rootB).getFirst().startsWith("index idle (last run):"));

                executor.run(0);
                assertFalse(runA.inFlight());
                assertTrue(runA.awaitCompletion(0, TimeUnit.SECONDS));
                assertNotSame(runA, service.currentIndex(rootA));
                assertFalse(service.currentIndex(rootA).inFlight(), "completed root reports the retained last summary");
                assertEquals("no index has run", service.status(rootB).getFirst());
                service.startIndex(rootA, List.of(a.coordinate()), false, null, null, null);
                assertTrue(service.currentIndex(rootA).inFlight(), "completion releases the same-root guard");
                executor.run(0);
            }
        }
    }

    @Nested
    @FusionSupport(modules = KnowledgeModule.class)
    class CallbackFailureReleasesRunOnlyAfterCallbackFinishes {
        @Test
        void callbackFailureReleasesRunOnlyAfterCallbackFinishes() throws Exception {
            final var m2 = Files.createDirectories(tmp.resolve("m2-failure"));
            final var gav = Gav.of("g.com", "artifact-a", "1.0");
            final var root = cachedProject("failure", gav, "A");
            classJar(m2, gav);
            try (final var executor = new ControllableExecutor()) {
                final var service = new MavenRepositoryKnowledgeService(
                        config(m2.toString(), root.toString()), new StubLlm(), executor, null);
                service.startIndex(
                        root,
                        List.of(gav.coordinate()),
                        false,
                        null,
                        summary -> {
                            assertTrue(service.currentIndex(root).inFlight());
                            assertFalse(service.currentIndex(root).cancel(), "success delivery is already committed");
                            assertThrows(
                                    IllegalStateException.class,
                                    () -> service.startIndex(root, List.of(gav.coordinate()), false, null, null, null));
                            throw new IllegalArgumentException("callback failure");
                        },
                        null);
                final var run = service.currentIndex(root);
                executor.run(0);
                assertEquals(
                        "callback failure",
                        assertThrows(
                                        CompletionException.class,
                                        () -> run.completion()
                                                .toCompletableFuture()
                                                .join())
                                .getCause()
                                .getMessage());
                assertFalse(run.inFlight());
                assertNull(run.summary());
                assertNotSame(run, service.currentIndex(root));
                service.startIndex(root, List.of(gav.coordinate()), false, null, null, null);
                executor.run(0);
            }
        }
    }

    @Nested
    @FusionSupport(modules = KnowledgeModule.class)
    class ExecutorRejectionDoesNotLeakActiveRunOrSameRootGuard {
        @Test
        void executorRejectionDoesNotLeakActiveRunOrSameRootGuard() throws Exception {
            final var m2 = Files.createDirectories(tmp.resolve("m2-rejected"));
            final var gav = Gav.of("g.com", "artifact-a", "1.0");
            final var root = cachedProject("rejected", gav, "A");
            try (final var executor = new ControllableExecutor()) {
                final var service = new MavenRepositoryKnowledgeService(
                        config(m2.toString(), root.toString()), new StubLlm(), executor, null);
                executor.shutdown();
                for (var i = 0; i < 2; i++) {
                    assertThrows(
                            RejectedExecutionException.class,
                            () -> service.startIndex(root, List.of(gav.coordinate()), false, null, null, null));
                    assertFalse(service.currentIndex(root).inFlight());
                    assertFalse(service.currentIndex(root).inFlight());
                }
            }
        }
    }

    private static final class ControllableExecutor extends AbstractExecutorService {
        private final List<Runnable> tasks = new CopyOnWriteArrayList<>();
        private final CountDownLatch submitted = new CountDownLatch(1);
        private boolean shutdown;

        private void run(final int index) {
            tasks.remove(index).run();
        }

        @Override
        public void execute(final Runnable command) {
            if (shutdown) {
                throw new RejectedExecutionException();
            }
            tasks.add(command);
            submitted.countDown();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public void close() {
            shutdownNow();
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown();
            final var remaining = List.copyOf(tasks);
            tasks.clear();
            return remaining;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(final long timeout, final TimeUnit unit) {
            return isTerminated();
        }
    }

    @Nested
    @FusionSupport(modules = KnowledgeModule.class)
    class OperationAdaptersCaptureSettingsInsteadOfBindingTheSharedLlm {
        @Test
        void operationAdaptersCaptureSettingsInsteadOfBindingTheSharedLlm() {
            final var client = new ChatClient() {
                @Override
                public int contextWindow(final ChatSettings settings) {
                    return settings == null ? 0 : Integer.parseInt(settings.model());
                }

                @Override
                public int outputTokens(final ChatSettings settings) {
                    return contextWindow(settings) / 10;
                }
            };
            final var llm = new YcaKnowledgeLlm(client, null, null, null);
            final var operationA = llm.forProject(tmp.resolve("a"), new ChatSettings(null, "100", null, null));
            final var operationB = llm.forProject(tmp.resolve("b"), new ChatSettings(null, "200", null, null));
            llm.withSettings(new ChatSettings(null, "300", null, null));
            assertEquals(new TokenBudget(100, 10), operationA.tokenBudget());
            assertEquals(new TokenBudget(200, 20), operationB.tokenBudget());
            assertEquals(new TokenBudget(300, 30), llm.tokenBudget(), "legacy settings binding is unchanged");
            llm.withSettings(null);
            assertEquals(new TokenBudget(100, 10), operationA.tokenBudget());
            assertEquals(new TokenBudget(200, 20), operationB.tokenBudget());
        }
    }

    @Nested
    @FusionSupport(modules = KnowledgeModule.class)
    class ExplicitRootsControlBuildDetectionWithoutChangingOtherRoots {
        @Test
        void explicitRootsControlBuildDetectionWithoutChangingOtherRoots() throws IOException {
            final var m2 = Files.createDirectories(tmp.resolve("m2-roots"));
            final var plain = Files.createDirectories(tmp.resolve("plain"));
            final var gradle = Files.createDirectories(tmp.resolve("gradle"));
            Files.writeString(gradle.resolve("build.gradle.kts"), "plugins {}\n");
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var service = new MavenRepositoryKnowledgeService(
                        config(m2.toString(), plain.toString()), new StubLlm(), executor, null);
                assertFalse(service.knowledgeEnabled(plain));
                assertThrows(NullPointerException.class, () -> service.knowledgeEnabled(null));
                assertTrue(service.knowledgeEnabled(gradle));
                assertEquals("maven repository knowledge is disabled", service.knowledge(plain, "g:a:1", null));
                assertEquals("no knowledge indexed for g:a:1", service.knowledge(gradle, "g:a:1", null));
                assertTrue(service.sessionSkillBlocks(plain).isEmpty());
                assertTrue(service.sessionSkillBlocks(gradle).isEmpty());
                assertFalse(service.knowledgeEnabled(plain), "an explicit root must not mutate the legacy default");
            }
        }
    }

    @Nested
    @FusionSupport(modules = SynchronousModule.class)
    class RootAwareWarmUpProvisionsWithoutMountingSkills {
        @Test
        void rootAwareWarmUpProvisionsWithoutMountingSkills() throws Exception {
            final var m2 = Files.createDirectories(tmp.resolve("m2-warmup"));
            final var plain = Files.createDirectories(tmp.resolve("warmup-plain"));
            final var gav = Gav.of("g.com", "artifact-a", "1.0");
            final var project = cachedProject("warmup-project", gav, "warmup");
            classesOnly(m2, gav.groupId(), gav.artifactId(), gav.version(), "g.com.Main");
            final var configuration = config(m2.toString(), plain.toString());
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var service = new MavenRepositoryKnowledgeService(configuration, new StubLlm(), executor, null);
                final var writer = new KnowledgeWriter(m2);

                assertFalse(writer.alreadyIndexed(gav));
                service.warmUp(project);
                assertTrue(writer.alreadyIndexed(gav));
                assertEquals(
                        ProjectFingerprint.of(gav, List.of(project.resolve("pom.xml"))), writer.readFingerprint(gav));
                assertTrue(service.sessionSkillBlocks(plain).isEmpty());
                assertTrue(service.sessionSkillBlocks(project).isEmpty());
                assertFalse(service.knowledgeEnabled(plain));
            }
        }
    }

    @Nested
    @FusionSupport(modules = KnowledgeModule.class)
    class QueuedRefreshesCaptureDependenciesFingerprintsAndDiscoveryRoots {
        @Test
        void queuedRefreshesCaptureDependenciesFingerprintsAndDiscoveryRoots() throws Exception {
            final var m2 = Files.createDirectories(tmp.resolve("m2-queued"));
            final var a = Gav.of("g.com", "artifact-a", "1.0");
            final var b = Gav.of("g.com", "artifact-b", "2.0");
            final var rootA = cachedProject("root-a", a, "A");
            final var rootB = cachedProject("root-b", b, "B");
            classJar(m2, a);
            classJar(m2, b);
            final var configuration = config(m2.toString(), rootA.toString());
            final var llm = new YcaKnowledgeLlm(null, null, configuration, null);
            assertThrows(
                    NullPointerException.class, llm::resolveProjectDeps, "the shared service must not infer a root");
            final var operationA = llm.forProject(rootA, null);
            final var operationB = llm.forProject(rootB, null);
            assertEquals(List.of(b), operationB.resolveProjectDeps());
            assertEquals(List.of(a), operationA.resolveProjectDeps(), "another operation cannot switch A's root");

            final var release = new CountDownLatch(1);
            try (final var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> {
                    try {
                        release.await();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                try {
                    final var service = new MavenRepositoryKnowledgeService(configuration, llm, executor, null);
                    final var ackA = service.refresh(rootA, null, null, null, null);
                    final var runA = service.currentIndex(rootA);
                    assertTrue(ackA.get(1).contains(a.coordinate()));
                    assertFalse(ackA.get(1).contains(b.coordinate()));
                    assertTrue(runA.inFlight());
                    assertFalse(service.currentIndex(rootB).inFlight());
                    final var ackB = service.refresh(rootB, null, null, null, null);
                    final var runB = service.currentIndex(rootB);
                    assertTrue(ackB.get(1).contains(b.coordinate()));
                    assertFalse(ackB.get(1).contains(a.coordinate()));
                    release.countDown();
                    assertTrue(runA.awaitCompletion(10, TimeUnit.SECONDS));
                    assertTrue(runB.awaitCompletion(10, TimeUnit.SECONDS));
                    assertTrue(runA.summary().contains(a.coordinate()));
                    assertTrue(runB.summary().contains(b.coordinate()));

                    final var writer = new KnowledgeWriter(m2);
                    assertEquals(
                            ProjectFingerprint.of(a, List.of(rootA.resolve("pom.xml"))), writer.readFingerprint(a));
                    assertEquals(
                            ProjectFingerprint.of(b, List.of(rootB.resolve("pom.xml"))), writer.readFingerprint(b));
                    assertEquals(List.of(a.coordinate()), service.indexedArtifacts(rootA));
                    assertEquals(List.of(b.coordinate()), service.indexedArtifacts(rootB));
                    assertEquals(service.indexedArtifacts(rootA), service.indexedArtifacts(rootA));
                    final var symbol = Gav.class.getName();
                    assertEquals(a.coordinate(), service.codeGraphProvider(rootA, symbol, null));
                    assertEquals(b.coordinate(), service.codeGraphProvider(rootB, symbol, null));
                    assertEquals(a.coordinate(), service.codeGraphProvider(rootA, symbol, null));
                    assertEquals(
                            service.knowledge(rootA, a.coordinate(), null),
                            service.knowledge(rootB, a.coordinate(), null),
                            "explicit artifact content remains shared across projects");
                    assertTrue(service.status(rootB).getFirst().contains("index idle"));
                    assertEquals(
                            "no index has run", service.status(rootA).getFirst(), "do not report B's status for A");
                    assertTrue(service.sessionSkillBlocks(rootA).isEmpty());
                    assertTrue(service.sessionSkillBlocks(rootB).isEmpty());
                } finally {
                    release.countDown();
                }
            }
        }
    }

    @Nested
    @FusionSupport(modules = KnowledgeModule.class)
    class SynchronousIndexUsesRequestingRootsBuildFingerprint {
        @Test
        void synchronousIndexUsesRequestingRootsBuildFingerprint() throws Exception {
            final var m2 = Files.createDirectories(tmp.resolve("m2-fingerprints"));
            final var gav = Gav.of("g.com", "artifact-a", "1.0");
            final var rootA = cachedProject("fingerprint-a", gav, "A");
            final var rootB = cachedProject("fingerprint-b", gav, "B");
            classesOnly(m2, gav.groupId(), gav.artifactId(), gav.version(), "g.com.Main");
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var service = new MavenRepositoryKnowledgeService(
                        config(m2.toString(), rootA.toString()), new StubLlm(), executor, null);
                final var writer = new KnowledgeWriter(m2);
                assertTrue(service.index(rootA, List.of(gav.coordinate()), false, null)
                        .startsWith("indexed 1, skipped 0"));
                final var fingerprintA = writer.readFingerprint(gav);
                assertTrue(service.index(rootA, List.of(gav.coordinate()), false, null)
                        .startsWith("indexed 0, skipped 1"));
                assertTrue(service.index(rootB, List.of(gav.coordinate()), false, null)
                        .startsWith("indexed 1, skipped 0"));
                final var fingerprintB = writer.readFingerprint(gav);
                assertNotEquals(fingerprintA, fingerprintB);
                assertEquals(ProjectFingerprint.of(gav, List.of(rootB.resolve("pom.xml"))), fingerprintB);
                assertTrue(service.index(rootA, List.of(gav.coordinate()), false, null)
                        .startsWith("indexed 1, skipped 0"));
                assertEquals(fingerprintA, writer.readFingerprint(gav));
            }
        }
    }

    private Path cachedProject(final String name, final Gav gav, final String marker) throws Exception {
        final var root = Files.createDirectories(tmp.resolve(name));
        final var pom = "<project><!-- " + marker + " --><dependencies><dependency><groupId>" + gav.groupId()
                + "</groupId><artifactId>" + gav.artifactId() + "</artifactId><version>" + gav.version()
                + "</version></dependency></dependencies></project>";
        Files.writeString(root.resolve("pom.xml"), pom);
        // Seed the documented dependency-list cache, so these tests never launch Maven.
        final var digest = MessageDigest.getInstance("SHA-256");
        digest.update("pom.xml".getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(pom.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        final var cache = Files.createDirectories(root.resolve(".yca")).resolve("maven.dependency.list");
        Files.writeString(
                cache, "# fingerprint=" + HexFormat.of().formatHex(digest.digest()) + "\n" + gav.coordinate() + "\n");
        return root;
    }

    private void classJar(final Path m2, final Gav gav) throws IOException {
        final var dir = Files.createDirectories(m2.resolve(gav.groupId().replace('.', '/'))
                .resolve(gav.artifactId())
                .resolve(gav.version()));
        Files.writeString(dir.resolve(gav.artifactId() + "-" + gav.version() + ".pom"), "<project/>");
        final var className = Gav.class.getName().replace('.', '/') + ".class";
        try (final var in = Gav.class.getResourceAsStream("/" + className);
                final var out = new ZipOutputStream(
                        Files.newOutputStream(dir.resolve(gav.artifactId() + "-" + gav.version() + ".jar")))) {
            out.putNextEntry(new ZipEntry(className));
            in.transferTo(out);
            out.closeEntry();
        }
    }

    @Nested
    @FusionSupport(modules = KnowledgeModule.class)
    class AsyncRefreshIndexesArtifactsAndReportsProgress {
        @Test
        void asyncRefreshIndexesArtifactsAndReportsProgress() throws IOException, InterruptedException {
            final var m2 = Files.createDirectories(tmp.resolve("m2"));
            final var proj = Files.createDirectories(tmp.resolve("proj"));
            Files.writeString(proj.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
            classesOnly(m2, "g.com", "artifact-a", "1.0", "g.com.a.Main");
            classesOnly(m2, "g.com", "artifact-b", "2.0", "g.com.b.Main");

            final var config = config(m2.toString(), proj.toString());
            final var progress = new CopyOnWriteArrayList<String>();
            final var executor = Executors.newVirtualThreadPerTaskExecutor();
            final var service = new MavenRepositoryKnowledgeService(config, new StubLlm(), executor, null);
            try {
                final var ack = service.refresh(proj, null, progress::add, null, null);
                assertFalse(ack.isEmpty(), "the refresh should acknowledge starting");
                assertTrue(ack.get(0).contains("started indexing"), () -> "unexpected ack: " + ack);
                assertTrue(
                        ack.stream()
                                .anyMatch(l -> l.contains("starting to index") && l.contains("g.com:artifact-a:1.0")),
                        () -> "ack should preview the artifacts: " + ack);

                final var run = service.currentIndex(proj);
                assertTrue(run.inFlight(), "indexing should be in flight right after starting");
                assertTrue(run.awaitCompletion(5, TimeUnit.SECONDS), "indexing should have completed");
                assertEquals(run.total(), run.doneCount(), "all artifacts must have been processed");
                final var summary = run.summary();
                assertTrue(summary.contains("indexed"), () -> "summary should report indexed: " + summary);
                final var last = progress.get(progress.size() - 1);
                assertTrue(
                        last.contains("(2/2)") || last.contains("100%"),
                        () -> "final progress should be 100%: " + last);

                assertTrue(
                        Files.exists(m2.resolve("g/com/artifact-a/1.0/artifact-a-1.0-knowledge-index.md")),
                        () -> "artifact-a knowledge index missing; summary: " + summary);
                assertTrue(Files.exists(m2.resolve("g/com/artifact-a/1.0/artifact-a-1.0-knowledge-api.md")));
                assertTrue(Files.exists(m2.resolve("g/com/artifact-b/2.0/artifact-b-2.0-knowledge-index.md")));
            } finally {
                executor.shutdown();
            }
        }
    }

    @Nested
    @FusionSupport(modules = KnowledgeModule.class)
    class DisabledWithoutBuildFileKeepsQuiet {
        @Test
        void disabledWithoutBuildFileKeepsQuiet() {
            final var m2 = tmp.resolve("m2");
            final var plain = tmp.resolve("plain");
            final var executor = Executors.newVirtualThreadPerTaskExecutor();
            final var service = new MavenRepositoryKnowledgeService(
                    config(m2.toString(), plain.toString()), new StubLlm(), executor, null);
            try {
                final var res = service.refresh(plain, "g.com:artifact-a:1.0", null, null, null);
                assertEquals(List.of("maven repository knowledge is disabled"), res);
            } finally {
                executor.shutdown();
            }
        }
    }

    @Nested
    @FusionSupport(modules = KnowledgeModule.class)
    class StatusReportsIdleThenRunProgressAndLastSummary {
        @Test
        void statusReportsIdleThenRunProgressAndLastSummary() throws IOException, InterruptedException {
            final var m2 = Files.createDirectories(tmp.resolve("m2-status"));
            final var proj = Files.createDirectories(tmp.resolve("proj-status"));
            Files.writeString(proj.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
            classesOnly(m2, "g.com", "artifact-a", "1.0", "g.com.a.Main");
            classesOnly(m2, "g.com", "artifact-b", "2.0", "g.com.b.Main");

            final var config = config(m2.toString(), proj.toString());
            final var executor = Executors.newVirtualThreadPerTaskExecutor();
            final var service = new MavenRepositoryKnowledgeService(config, new StubLlm(), executor, null);
            try {
                assertTrue(
                        service.status(proj).stream().anyMatch(l -> l.contains("no index has run")),
                        () -> "before any run the status should say nothing ran: " + service.status(proj));

                service.refresh(proj, null, null, null, null);
                final var run = service.currentIndex(proj);
                assertTrue(run.inFlight(), "indexing should be in flight right after starting");
                assertTrue(
                        service.status(proj).stream().anyMatch(l -> l.startsWith("index in progress:")),
                        () -> "while running the status should report progress: " + service.status(proj));

                assertTrue(run.awaitCompletion(5, TimeUnit.SECONDS), "indexing should have completed");
                assertTrue(
                        service.status(proj).stream().anyMatch(l -> l.contains("index idle") && l.contains("indexed")),
                        () -> "after the run the status should show the last summary: " + service.status(proj));
            } finally {
                executor.shutdown();
            }
        }
    }

    @Nested
    @FusionSupport(modules = KnowledgeModule.class)
    class BaselineScopesToProjectDependenciesOnly {
        @Test
        void baselineScopesToProjectDependenciesOnly() throws IOException, InterruptedException {
            final var m2 = Files.createDirectories(tmp.resolve("m2-cohort"));
            final var proj = Files.createDirectories(tmp.resolve("proj-cohort"));
            Files.writeString(proj.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
            classesOnly(m2, "g.com", "artifact-a", "1.0", "g.com.a.Main");
            classesOnly(m2, "g.com", "artifact-b", "2.0", "g.com.b.Main");
            classesOnly(m2, "g.com", "artifact-other", "3.0", "g.com.other.Main");

            final var config = config(m2.toString(), proj.toString());
            final var executor = Executors.newVirtualThreadPerTaskExecutor();
            final var service = new MavenRepositoryKnowledgeService(config, new StubLlm(), executor, null);
            try {
                final var ack = service.refresh(proj, null, null, null, null);
                assertTrue(ack.get(0).contains("indexing 2"), () -> "only project deps should be indexed: " + ack);
                assertFalse(
                        ack.stream().anyMatch(l -> l.contains("artifact-other")),
                        () -> "a local artifact not in the project deps must be ignored: " + ack);

                final var run = service.currentIndex(proj);
                assertEquals(2, run.total(), "cohort must match project deps");
                assertTrue(run.awaitCompletion(5, TimeUnit.SECONDS), "indexing should have completed");
                assertTrue(Files.exists(m2.resolve("g/com/artifact-a/1.0/artifact-a-1.0-knowledge-index.md")));
                assertTrue(Files.exists(m2.resolve("g/com/artifact-b/2.0/artifact-b-2.0-knowledge-index.md")));
                assertFalse(Files.exists(m2.resolve("g/com/artifact-other/3.0/artifact-other-3.0-knowledge-index.md")));
            } finally {
                executor.shutdown();
            }
        }
    }

    @Nested
    @FusionSupport(modules = KnowledgeModule.class)
    class SessionSkillBlocksRemainEmptyForLazyMountAfterIndexing {
        @Test
        void sessionSkillBlocksRemainEmptyForLazyMountAfterIndexing() throws IOException, InterruptedException {
            final var m2 = Files.createDirectories(tmp.resolve("m2-lazy"));
            final var proj = Files.createDirectories(tmp.resolve("proj-lazy"));
            Files.writeString(proj.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
            classesOnly(m2, "g.com", "artifact-a", "1.0", "g.com.a.Main");
            classesOnly(m2, "g.com", "artifact-b", "2.0", "g.com.b.Main");

            final var config = config(m2.toString(), proj.toString());
            final var executor = Executors.newVirtualThreadPerTaskExecutor();
            final var service = new MavenRepositoryKnowledgeService(config, new StubLlm(), executor, null);
            try {
                service.refresh(proj, null, null, null, null);
                final var run = service.currentIndex(proj);
                assertTrue(run.awaitCompletion(5, TimeUnit.SECONDS), "indexing should have completed");

                assertTrue(
                        service.sessionSkillBlocks(proj).isEmpty(),
                        "with the lazy mount design no skill block is pre-mounted into the prompt");

                final var index = service.knowledge(proj, "g.com:artifact-a:1.0", null);
                assertTrue(index.contains("## Knowledge"), () -> "on-demand index still available: " + index);
            } finally {
                executor.shutdown();
            }
        }
    }

    @Nested
    @FusionSupport(modules = KnowledgeModule.class)
    class KnowledgeReadsIndexAndReferencedSibling {
        @Test
        void knowledgeReadsIndexAndReferencedSibling() throws IOException, InterruptedException {
            final var m2 = Files.createDirectories(tmp.resolve("m2-content"));
            final var proj = Files.createDirectories(tmp.resolve("proj-content"));
            Files.writeString(proj.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
            classesOnly(m2, "g.com", "artifact-a", "1.0", "g.com.a.Main");

            final var config = config(m2.toString(), proj.toString());
            final var executor = Executors.newVirtualThreadPerTaskExecutor();
            final var service = new MavenRepositoryKnowledgeService(config, new StubLlm(), executor, null);
            try {
                service.refresh(proj, null, null, null, null);
                final var run = service.currentIndex(proj);
                assertTrue(run.awaitCompletion(5, TimeUnit.SECONDS), "indexing should have completed");

                final var index = service.knowledge(proj, "g.com:artifact-a:1.0", null);
                assertTrue(index.contains("## Knowledge"), () -> "index content expected: " + index);
                final var api = service.knowledge(proj, "g.com:artifact-a:1.0", "api");
                assertTrue(
                        api.contains("Artifact API") || api.contains("No public API detected."),
                        () -> "api sibling content expected: " + api);
                assertTrue(
                        service.knowledge(proj, "g.com:artifact-a:1.0", "unknown")
                                .contains("no knowledge file"),
                        () -> "missing sibling should be reported");
                assertTrue(
                        service.knowledge(proj, "g.com:notindexed:1.0", null).contains("no knowledge indexed"),
                        () -> "unindexed coordinate should be reported");
            } finally {
                executor.shutdown();
            }
        }
    }

    @Nested
    @FusionSupport(modules = KnowledgeModule.class)
    class KnowledgeCacheInvalidatedByFileMtime {
        @Test
        void knowledgeCacheInvalidatedByFileMtime() throws IOException, InterruptedException {
            final var m2 = Files.createDirectories(tmp.resolve("m2-cache"));
            final var proj = Files.createDirectories(tmp.resolve("proj-cache"));
            Files.writeString(proj.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
            classesOnly(m2, "g.com", "artifact-a", "1.0", "g.com.a.Main");

            final var config = config(m2.toString(), proj.toString());
            final var executor = Executors.newVirtualThreadPerTaskExecutor();
            final var service = new MavenRepositoryKnowledgeService(config, new StubLlm(), executor, null);
            try {
                service.refresh(proj, null, null, null, null);
                final var run = service.currentIndex(proj);
                assertTrue(run.awaitCompletion(5, TimeUnit.SECONDS), "indexing should have completed");

                final var indexFile = m2.resolve("g/com/artifact-a/1.0/artifact-a-1.0-knowledge-index.md");
                final var first = service.knowledge(proj, "g.com:artifact-a:1.0", null);
                assertTrue(first.contains("## Knowledge"), () -> "first read should succeed: " + first);

                Files.writeString(indexFile, "## Modified\nchanged content\n");
                final var future = FileTime.fromMillis(System.currentTimeMillis() + 2000);
                Files.setLastModifiedTime(indexFile, future);

                final var second = service.knowledge(proj, "g.com:artifact-a:1.0", null);
                assertTrue(second.contains("## Modified"), () -> "cache should invalidate on mtime change: " + second);
            } finally {
                executor.shutdown();
            }
        }
    }

    private void classesOnly(
            final Path m2, final String group, final String artifact, final String version, final String mainClass)
            throws IOException {
        final var dir = m2.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(artifact + "-" + version + ".pom"), "<project/>");
        final var jar = dir.resolve(artifact + "-" + version + ".jar");
        try (final var zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            zos.putNextEntry(new ZipEntry(mainClass.replace('.', '/') + ".class"));
            zos.write(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            zos.closeEntry();
        }
    }

    private CodingAgentConfiguration config(final String localRepository, final String projectDir) {
        final var knowledge = new MavenRepositoryKnowledgeConfiguration(
                baseline.mavenRepositoryKnowledge().enabled(),
                localRepository,
                baseline.mavenRepositoryKnowledge().concurrency(),
                baseline.mavenRepositoryKnowledge().batchSize(),
                baseline.mavenRepositoryKnowledge().maxBatchChars(),
                baseline.mavenRepositoryKnowledge().maxContentBytes(),
                baseline.mavenRepositoryKnowledge().runContentBudget(),
                baseline.mavenRepositoryKnowledge().remoteManifest(),
                baseline.mavenRepositoryKnowledge().force(),
                baseline.mavenRepositoryKnowledge().skillsAtSessionStart(),
                baseline.mavenRepositoryKnowledge().useLlm(),
                baseline.mavenRepositoryKnowledge().maxSourceFiles(),
                baseline.mavenRepositoryKnowledge().maxSourceFileChars(),
                baseline.mavenRepositoryKnowledge().maxJavadocPages(),
                baseline.mavenRepositoryKnowledge().maxSessionSkillChars(),
                baseline.mavenRepositoryKnowledge().synchronous(),
                baseline.mavenRepositoryKnowledge().codegraphIndexEnabled(),
                baseline.mavenRepositoryKnowledge().codegraphIndexCacheBytes());
        return new CodingAgentConfiguration(
                baseline.storageHome(),
                baseline.home(),
                baseline.workingDir(),
                projectDir,
                baseline.provider(),
                baseline.model(),
                baseline.llm(),
                baseline.compaction(),
                baseline.pruning(),
                baseline.compression(),
                baseline.memory(),
                baseline.todos(),
                baseline.optimize(),
                baseline.permissions(),
                baseline.sandbox(),
                baseline.mcp(),
                knowledge,
                baseline.structuralCodeGraph(),
                baseline.sessions(),
                baseline.skills(),
                baseline.agents(),
                baseline.mode(),
                baseline.httpClient(),
                baseline.tools(),
                baseline.subagents(),
                baseline.schedulerThreads(),
                baseline.metrics(),
                baseline.maxLineLength(),
                baseline.resumeAliases(),
                baseline.slashcommands(),
                baseline.prompt(),
                baseline.labels(),
                baseline.logging(),
                baseline.hooks(),
                baseline.notifications(),
                baseline.secrets(),
                null);
    }

    public static final class StubLlm implements KnowledgeLlm {
        @Override
        public boolean available() {
            return true;
        }

        @Override
        public List<Gav> resolveProjectDeps() {
            return List.of(Gav.of("g.com", "artifact-a", "1.0"), Gav.of("g.com", "artifact-b", "2.0"));
        }

        @Override
        public Map<Gav, List<String>> entryPoints(final Map<Gav, String> contentByGav) {
            final var out = new HashMap<Gav, List<String>>();
            for (final var gav : contentByGav.keySet()) {
                out.put(gav, List.of("Main"));
            }
            return out;
        }

        @Override
        public List<Gav> suggestEntryGavs(final List<Gav> candidates) {
            return candidates;
        }

        @Override
        public String synthesizeSkill(
                final Gav gav, final String name, final String content, final List<String> entryPoints) {
            return """
          ---
          name: %s
          description: generated for %s
          ---
          # %s
          ## Entry points
          """.formatted(name, gav.coordinate(), name);
        }
    }

    public static class KnowledgeModule extends ConfigurationTestModule {
        public KnowledgeModule() {
            super(Map.of(
                    "yca.storageHome", configurationRoot.resolve("storage").toString(),
                    "yca.mavenRepositoryKnowledge.enabled", "true",
                    "yca.mavenRepositoryKnowledge.concurrency", "2",
                    "yca.mavenRepositoryKnowledge.batchSize", "2",
                    "yca.mavenRepositoryKnowledge.maxBatchChars", "-1"));
        }
    }

    public static class SynchronousModule extends ConfigurationTestModule {
        public SynchronousModule() {
            super(Map.of(
                    "yca.storageHome", configurationRoot.resolve("storage").toString(),
                    "yca.mavenRepositoryKnowledge.enabled", "true",
                    "yca.mavenRepositoryKnowledge.concurrency", "2",
                    "yca.mavenRepositoryKnowledge.batchSize", "2",
                    "yca.mavenRepositoryKnowledge.maxBatchChars", "-1",
                    "yca.mavenRepositoryKnowledge.skillsAtSessionStart", "true",
                    "yca.mavenRepositoryKnowledge.synchronous", "true"));
        }
    }
}
