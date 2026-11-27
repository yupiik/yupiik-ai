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
package io.yupiik.ia.agent.core.llm;

import static io.yupiik.ia.agent.core.llm.CodexLoginService.LoginState.CANCELLED;
import static io.yupiik.ia.agent.core.llm.CodexLoginService.LoginState.FAILED;
import static io.yupiik.ia.agent.core.llm.CodexLoginService.LoginState.SUCCEEDED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.codex.CodexOAuthConfig;
import io.yupiik.llm.client.codex.model.CodexTokenStore;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/** All issuer/enrollment I/O is fake; the browser callback tests use loopback only. */
@ExtendWith(TestJsonMapper.class)
class CodexLoginAttemptTest {
    @TempDir
    static Path root;

    private Path storage;
    private CodingAgentConfiguration configuration;

    private HttpClient delegate;
    private FakeHttpClient http;

    @BeforeEach
    void setUp(@Fusion final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
        storage = Path.of(configuration.resolveStorageHome());
        delegate = HttpClient.newHttpClient();
        http = new FakeHttpClient(delegate);
    }

    @AfterEach
    void tearDown() {
        try {
            assertEquals(0, http.closes.get(), "attempts must not close an application-owned client");
        } finally {
            http.close();
            delegate.close();
        }
    }

    @Nested
    @FusionSupport(modules = LoginModule.class)
    class DeviceSuccessHasStructuredChallengeAndExactlyOnceCompletion {
        @Test
        void deviceSuccessHasStructuredChallengeAndExactlyOnceCompletion(final JsonMapper mapper) throws Exception {
            final var service = service(mapper);
            final var callbacks = new Callbacks();
            final var attempt = service.newAttempt(request(true, false), callbacks);
            final var completions = new AtomicInteger();
            final var releases = new AtomicInteger();
            final var completed = attempt.completion().thenAccept(r -> completions.incrementAndGet());
            final var released = attempt.drained().thenRun(releases::incrementAndGet);
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                attempt.start(executor);
                final var result = await(attempt.completion());
                await(completed);
                await(released);
                assertEquals(new CodexLoginService.LoginResult(SUCCEEDED, true), result);
                assertEquals(SUCCEEDED, attempt.state());
                assertEquals(
                        List.of(CodexLoginService.LoginStatus.AUTHORIZING, CodexLoginService.LoginStatus.SAVING),
                        callbacks.statuses);
                assertEquals(
                        new CodexLoginService.DeviceChallenge(CodexOAuthConfig.DEFAULT.verificationUrl(), "USER-CODE"),
                        callbacks.challenge.get());
                assertNull(callbacks.browser.get());
                assertEquals("access-secret", service.load("test").accessToken());
                assertEquals(
                        CodexOAuthConfig.DEFAULT.issuer(), service.load("test").issuer());
                assertFalse(attempt.cancel(), "a committed login is not rolled back by late cancellation");
                attempt.close();
                attempt.close();
                assertEquals(1, completions.get());
                assertEquals(1, releases.get());
                assertEquals(
                        "still usable", executor.submit(() -> "still usable").get(5, TimeUnit.SECONDS));
                assertOnlyCredentialFile();
            }
        }
    }

    @Nested
    @FusionSupport(modules = LoginModule.class)
    class EnterpriseSuccessCommitsOnlyFullyEnrolledStore {
        @Test
        void enterpriseSuccessCommitsOnlyFullyEnrolledStore(final JsonMapper mapper) throws Exception {
            final var service = service(mapper);
            final var callbacks = new Callbacks();
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var attempt =
                        service.newAttempt(request(true, true), callbacks).start(executor);
                assertEquals(SUCCEEDED, await(attempt.completion()).state());
                await(attempt.drained());
                final var saved = service.load("test");
                assertNotNull(saved.agentIdentity());
                assertEquals("runtime", saved.agentIdentity().agentRuntimeId());
                assertEquals("task", saved.agentIdentity().taskId());
                assertNotNull(saved.agentIdentity().agentPrivateKeyPkcs8Base64());
                assertEquals(
                        List.of(
                                CodexLoginService.LoginStatus.AUTHORIZING,
                                CodexLoginService.LoginStatus.ENROLLING,
                                CodexLoginService.LoginStatus.SAVING),
                        callbacks.statuses);
                assertOnlyCredentialFile();
            }
        }
    }

    @Nested
    @FusionSupport(modules = LoginModule.class)
    class ProviderFailureDoesNotEchoSecretsOrPublishCredentials {
        @Test
        void providerFailureDoesNotEchoSecretsOrPublishCredentials(final JsonMapper mapper) throws Exception {
            http.responder = request ->
                    new Response(request, 401, "{\"password\":\"password-secret\",\"access_token\":\"access-secret\"}");
            final var service = service(mapper);
            final var callbacks = new Callbacks();
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var attempt =
                        service.newAttempt(request(true, false), callbacks).start(executor);
                final var result = await(attempt.completion());
                await(attempt.drained());
                assertEquals(new CodexLoginService.LoginResult(FAILED, false), result);
                assertFalse(result.toString().contains("secret"));
                assertFalse(result.toString().contains("password"));
                assertEquals(List.of(CodexLoginService.LoginStatus.AUTHORIZING), callbacks.statuses);
                assertNull(callbacks.challenge.get());
                assertNull(service.load("test"));
                assertFalse(attempt.cancel());
                attempt.close();
            }
        }
    }

    @Nested
    @FusionSupport(modules = LoginModule.class)
    class CallbackFailureAndRejectedStartupAreSafeTerminalFailures {
        @Test
        void callbackFailureAndRejectedStartupAreSafeTerminalFailures(final JsonMapper mapper) throws Exception {
            final var service = service(mapper);
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var attempt = service.newAttempt(request(true, false), new CodexLoginService.LoginListener() {
                            @Override
                            public void status(final CodexLoginService.LoginStatus status) {
                                throw new IllegalStateException("password-secret");
                            }
                        })
                        .start(executor);
                assertEquals(new CodexLoginService.LoginResult(FAILED, false), await(attempt.completion()));
                await(attempt.drained());
            }
            try (final var executor = new QueuedExecutor()) {
                executor.shutdown();
                final var attempt =
                        service.newAttempt(request(true, false), null).start(executor);
                assertEquals(new CodexLoginService.LoginResult(FAILED, false), await(attempt.completion()));
                await(attempt.drained());
            }
            assertEquals(0, http.requests.size());
            assertNull(service.load("test"));
        }
    }

    @Nested
    @FusionSupport(modules = LoginModule.class)
    class CancelledBeforeStartAndWhileQueuedCannotStartOAuth {
        @Test
        void cancelledBeforeStartAndWhileQueuedCannotStartOAuth(final JsonMapper mapper) throws Exception {
            final var service = service(mapper);
            final var callbacks = new Callbacks();
            try (final var executor = new QueuedExecutor()) {
                final var before = service.newAttempt(request(true, false), callbacks);
                assertTrue(before.cancel());
                before.start(executor);
                assertEquals(0, executor.queue.size());
                assertEquals(CANCELLED, await(before.completion()).state());
                await(before.drained());

                final var queued =
                        service.newAttempt(request(true, false), callbacks).start(executor);
                assertEquals(CodexLoginService.LoginState.QUEUED, queued.state());
                assertTrue(queued.cancel());
                assertFalse(queued.cancel());
                assertEquals(CANCELLED, await(queued.completion()).state());
                await(queued.drained());
                executor.runNext();
                queued.close();
                assertTrue(callbacks.statuses.isEmpty());
                assertEquals(0, http.requests.size());
                assertNull(service.load("test"));
            }
        }
    }

    @Nested
    @FusionSupport(modules = LoginModule.class)
    class DevicePollingCancellationInterruptsRealWorker {
        @Test
        void devicePollingCancellationInterruptsRealWorker(final JsonMapper mapper) throws Exception {
            final var block = new Block(false);
            http.responder = request -> {
                if (request.uri().getPath().endsWith("/deviceauth/token")) {
                    block.waitForRelease();
                }
                return success(request);
            };
            final var service = service(mapper);
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var attempt =
                        service.newAttempt(request(true, false), null).start(executor);
                try {
                    await(block.entered);
                    assertTrue(attempt.cancel());
                    await(block.interrupted);
                    assertEquals(CANCELLED, await(attempt.completion()).state());
                    await(attempt.drained());
                    assertNull(service.load("test"));
                    assertEquals(0, callsEndingWith("/oauth/token"));
                } finally {
                    attempt.cancel();
                    block.release.countDown();
                }
            }
        }
    }

    @Nested
    @FusionSupport(modules = LoginModule.class)
    class BlockedRuntimeEnrollmentCannotProceedToTaskOrCommitAfterCancel {
        @Test
        void blockedRuntimeEnrollmentCannotProceedToTaskOrCommitAfterCancel(final JsonMapper mapper) throws Exception {
            cancelBlockedEnrollment(mapper, false);
        }
    }

    @Nested
    @FusionSupport(modules = LoginModule.class)
    class BlockedTaskEnrollmentDoesNotPretendCancelledFutureIsDrained {
        @Test
        void blockedTaskEnrollmentDoesNotPretendCancelledFutureIsDrained(final JsonMapper mapper) throws Exception {
            cancelBlockedEnrollment(mapper, true);
        }
    }

    private void cancelBlockedEnrollment(final JsonMapper mapper, final boolean task) throws Exception {
        final var block = new Block(true);
        http.responder = request -> {
            if (request.uri().getPath().endsWith(task ? "/task/register" : "/v1/agent/register")) {
                block.waitForRelease();
            }
            return success(request);
        };
        final var service = service(mapper);
        final var previous = new CodexTokenStore("previous", "refresh", "account", 1L, null);
        service.save("test", previous);
        final var callbacks = new Callbacks();
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var attempt =
                    service.newAttempt(request(true, true), callbacks).start(executor);
            final var completions = new AtomicInteger();
            final var releases = new AtomicInteger();
            final var completed = attempt.completion().thenRun(completions::incrementAndGet);
            final var drained = attempt.drained().thenRun(releases::incrementAndGet);
            try {
                await(block.entered);
                assertEquals("previous", service.load("test").accessToken(), "no intermediate OAuth save");
                assertTrue(attempt.cancel());
                await(block.interrupted);
                assertEquals(new CodexLoginService.LoginResult(CANCELLED, false), await(attempt.completion()));
                await(completed);
                assertFalse(attempt.drained().toCompletableFuture().isDone(), "SDK work is still blocked");
                final var notifications = callbacks.statuses.size();
                assertFalse(attempt.cancel());
                attempt.close();
                block.release.countDown();
                await(drained);
                assertEquals(1, completions.get());
                assertEquals(1, releases.get());
                assertEquals(notifications, callbacks.statuses.size());
                assertEquals("previous", service.load("test").accessToken());
                assertEquals(task ? 1 : 0, callsEndingWith("/task/register"));
                assertOnlyCredentialFile();
            } finally {
                attempt.cancel();
                block.release.countDown();
            }
        }
    }

    @Nested
    @FusionSupport(modules = LoginModule.class)
    class CancelDuringFinalSerializationPreventsLateCredentialReplacement {
        @Test
        void cancelDuringFinalSerializationPreventsLateCredentialReplacement(final JsonMapper mapper) throws Exception {
            final var block = new Block(true);
            final var previousService = service(mapper);
            previousService.save("test", new CodexTokenStore("previous", "refresh", "account", 1L, null));
            final var service = service(interceptSerialization(mapper, block::waitForRelease));
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var attempt =
                        service.newAttempt(request(true, false), null).start(executor);
                try {
                    await(block.entered);
                    assertTrue(attempt.cancel());
                    await(block.interrupted);
                    assertEquals(CANCELLED, await(attempt.completion()).state());
                    assertFalse(attempt.drained().toCompletableFuture().isDone());
                    block.release.countDown();
                    await(attempt.drained());
                    assertEquals("previous", previousService.load("test").accessToken());
                    assertOnlyCredentialFile();
                } finally {
                    attempt.cancel();
                    block.release.countDown();
                }
            }
        }
    }

    @Nested
    @FusionSupport(modules = LoginModule.class)
    class ConcurrentAttemptsAndServicesUseIndependentStagingFiles {
        @Test
        void concurrentAttemptsAndServicesUseIndependentStagingFiles(final JsonMapper mapper) throws Exception {
            final var bothSerializing = new CountDownLatch(2);
            final var release = new CountDownLatch(1);
            final var blockingMapper = interceptSerialization(mapper, () -> {
                bothSerializing.countDown();
                await(release);
            });
            final var token = new AtomicInteger();
            http.responder = request -> request.uri().getPath().endsWith("/oauth/token")
                    ? new Response(request, 200, "{\"access_token\":\"access-" + token.incrementAndGet() + "\"}")
                    : success(request);
            final var service = service(blockingMapper);
            final var otherService = service(blockingMapper);
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var first = service.newAttempt(request(true, false), null).start(executor);
                final var second =
                        otherService.newAttempt(request(true, false), null).start(executor);
                try {
                    await(bothSerializing);
                    try (final var files = Files.list(storage.resolve("providers/test"))) {
                        assertEquals(
                                2,
                                files.filter(p -> p.toString().endsWith(".tmp")).count());
                    }
                    release.countDown();
                    assertEquals(SUCCEEDED, await(first.completion()).state());
                    assertEquals(SUCCEEDED, await(second.completion()).state());
                    await(first.drained());
                    await(second.drained());
                    assertTrue(List.of("access-1", "access-2")
                            .contains(service(mapper).load("test").accessToken()));
                    assertOnlyCredentialFile();
                } finally {
                    first.cancel();
                    second.cancel();
                    release.countDown();
                }
            }
        }
    }

    @Nested
    @FusionSupport(modules = LoginModule.class)
    class CancellingOneConcurrentAttemptDoesNotCancelAnother {
        @Test
        void cancellingOneConcurrentAttemptDoesNotCancelAnother(final JsonMapper mapper) throws Exception {
            final var block = new Block(true);
            final var blockedService = service(interceptSerialization(mapper, block::waitForRelease));
            final var otherService = service(mapper);
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var first =
                        blockedService.newAttempt(request(true, false), null).start(executor);
                try {
                    await(block.entered);
                    final var second =
                            otherService.newAttempt(request(true, false), null).start(executor);
                    assertEquals(SUCCEEDED, await(second.completion()).state());
                    await(second.drained());
                    final var committed = Files.readAllBytes(storage.resolve("providers/test/codex.auth.json"));
                    assertTrue(first.cancel());
                    await(block.interrupted);
                    block.release.countDown();
                    await(first.drained());
                    assertEquals(CANCELLED, await(first.completion()).state());
                    assertEquals(
                            new String(committed, StandardCharsets.UTF_8),
                            Files.readString(storage.resolve("providers/test/codex.auth.json")));
                    assertOnlyCredentialFile();
                } finally {
                    first.cancel();
                    block.release.countDown();
                }
            }
        }
    }

    @Nested
    @FusionSupport(modules = LoginModule.class)
    class BrowserCancellationReleasesLoopbackListener {
        @Test
        @ResourceLock("codex-oauth-default-callback-port")
        void browserCancellationReleasesLoopbackListener(final JsonMapper mapper) throws Exception {
            final var authorizeUrl = new CompletableFuture<String>();
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var attempt = service(mapper)
                        .newAttempt(request(false, false), new CodexLoginService.LoginListener() {
                            @Override
                            public void browser(final String url) {
                                authorizeUrl.complete(url);
                            }
                        })
                        .start(executor);
                try {
                    final var redirect = URI.create(query(await(authorizeUrl)).get("redirect_uri"));
                    assertTrue(attempt.cancel());
                    assertEquals(CANCELLED, await(attempt.completion()).state());
                    await(attempt.drained());
                    try (final var socket = new ServerSocket()) {
                        socket.setReuseAddress(true);
                        socket.bind(new InetSocketAddress("127.0.0.1", redirect.getPort()));
                    }
                    assertEquals(0, http.requests.size());
                    assertNull(service(mapper).load("test"));
                } finally {
                    attempt.cancel();
                }
            }
        }
    }

    @Nested
    @FusionSupport(modules = LoginModule.class)
    class BrowserExchangeRunsOnOwnedWorkerAndDrainWaitsForIt {
        @Test
        @ResourceLock("codex-oauth-default-callback-port")
        void browserExchangeRunsOnOwnedWorkerAndDrainWaitsForIt(final JsonMapper mapper) throws Exception {
            final var block = new Block(true);
            final var callbackUrl = new CompletableFuture<String>();
            final var worker = new AtomicReference<Thread>();
            final var transportWorker = new AtomicReference<Thread>();
            http.responder = request -> {
                transportWorker.set(Thread.currentThread());
                block.waitForRelease();
                return success(request);
            };
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor();
                    final var callbackClient = HttpClient.newHttpClient()) {
                final var attempt = service(mapper)
                        .newAttempt(request(false, false), new CodexLoginService.LoginListener() {
                            @Override
                            public void browser(final String url) {
                                worker.set(Thread.currentThread());
                                callbackUrl.complete(url);
                            }
                        })
                        .start(executor);
                try {
                    final var params = query(await(callbackUrl));
                    final var redirect =
                            params.get("redirect_uri") + "?state=" + params.get("state") + "&code=fake-code";
                    // This is loopback only; issuer/SDK requests are intercepted by FakeHttpClient.
                    final var callback = callbackClient.sendAsync(
                            HttpRequest.newBuilder(URI.create(redirect)).GET().build(),
                            HttpResponse.BodyHandlers.discarding());
                    await(block.entered);
                    assertSame(worker.get(), transportWorker.get());
                    assertTrue(attempt.cancel());
                    await(block.interrupted);
                    assertFalse(attempt.drained().toCompletableFuture().isDone());
                    block.release.countDown();
                    await(attempt.drained());
                    assertEquals(CANCELLED, await(attempt.completion()).state());
                    assertEquals(1, callsEndingWith("/oauth/token"), "no optional API key exchange after cancellation");
                    await(callback.handle((response, error) -> null));
                    assertNull(service(mapper).load("test"));
                } finally {
                    attempt.cancel();
                    block.release.countDown();
                }
            }
        }
    }

    @Nested
    @FusionSupport(modules = LoginModule.class)
    class CancellationDuringOptionalApiKeyExchangeCannotSave {
        @Test
        @ResourceLock("codex-oauth-default-callback-port")
        void cancellationDuringOptionalApiKeyExchangeCannotSave(final JsonMapper mapper) throws Exception {
            final var block = new Block(true);
            final var authorizeUrl = new CompletableFuture<String>();
            final var exchanges = new AtomicInteger();
            http.responder = request -> {
                if (exchanges.incrementAndGet() == 1) {
                    return new Response(
                            request, 200, "{\"access_token\":\"access-secret\",\"id_token\":\"id-secret\"}");
                }
                block.waitForRelease();
                return new Response(request, 200, "{\"access_token\":\"api-key-secret\"}");
            };
            final var service = service(mapper);
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor();
                    final var callbackClient = HttpClient.newHttpClient()) {
                final var attempt = service.newAttempt(request(false, false), new CodexLoginService.LoginListener() {
                            @Override
                            public void browser(final String url) {
                                authorizeUrl.complete(url);
                            }
                        })
                        .start(executor);
                try {
                    final var params = query(await(authorizeUrl));
                    final var redirect =
                            params.get("redirect_uri") + "?state=" + params.get("state") + "&code=fake-code";
                    final var callback = callbackClient.sendAsync(
                            HttpRequest.newBuilder(URI.create(redirect)).GET().build(),
                            HttpResponse.BodyHandlers.discarding());
                    await(block.entered);
                    assertEquals(200, await(callback).statusCode());
                    assertTrue(attempt.cancel());
                    await(block.interrupted);
                    assertEquals(CANCELLED, await(attempt.completion()).state());
                    assertFalse(attempt.drained().toCompletableFuture().isDone());
                    block.release.countDown();
                    await(attempt.drained());
                    assertNull(service.load("test"));
                    assertEquals(2, exchanges.get());
                } finally {
                    attempt.cancel();
                    block.release.countDown();
                }
            }
        }
    }

    @Nested
    @FusionSupport(modules = LoginModule.class)
    class SerializationFailureReleasesStagingFileWithoutEcho {
        @Test
        void serializationFailureReleasesStagingFileWithoutEcho(final JsonMapper mapper) throws Exception {
            final var service = service(interceptSerialization(mapper, () -> {
                throw new IllegalStateException("password-secret");
            }));
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var attempt =
                        service.newAttempt(request(true, false), null).start(executor);
                assertEquals(new CodexLoginService.LoginResult(FAILED, false), await(attempt.completion()));
                await(attempt.drained());
                assertNull(service.load("test"));
                try (final var files = Files.list(storage.resolve("providers/test"))) {
                    assertEquals(0, files.count());
                }
            }
        }
    }

    @Nested
    @FusionSupport(modules = LoginModule.class)
    class LegacyCancellationProbeIsCheckedAfterOAuthBeforeSaving {
        @Test
        void legacyCancellationProbeIsCheckedAfterOAuthBeforeSaving(final JsonMapper mapper) {
            final var cancelled = new AtomicBoolean();
            http.responder = request -> {
                final var response = success(request);
                if (request.uri().getPath().endsWith("/oauth/token")) {
                    cancelled.set(true);
                }
                return response;
            };
            final var service = service(mapper);
            assertThrows(CancellationException.class, () -> service.signIn("test", true, null, b -> cancelled.get()));
            assertNull(service.load("test"));
        }
    }

    @Nested
    @FusionSupport(modules = LoginModule.class)
    class ProviderPathCannotEscapeOwnedStore {
        @Test
        void providerPathCannotEscapeOwnedStore(final JsonMapper mapper) {
            for (final var provider :
                    List.of("..", "../outside", "/tmp/outside", "nested/provider", "nested\\provider")) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service(mapper)
                                .newAttempt(new CodexLoginService.LoginRequest(provider, true, false, null), null));
            }
        }
    }

    private CodexLoginService service(final JsonMapper mapper) {
        return new CodexLoginService(mapper, http, configuration);
    }

    public static class LoginModule extends ConfigurationTestModule {
        public LoginModule() {
            super(Map.of(
                    "yca.storageHome",
                    root.resolve(UUID.randomUUID().toString()).toString()));
        }
    }

    private CodexLoginService.LoginRequest request(final boolean device, final boolean enterprise) {
        return new CodexLoginService.LoginRequest("test", device, enterprise, null);
    }

    private void assertOnlyCredentialFile() throws IOException {
        try (final var files = Files.list(storage.resolve("providers/test"))) {
            assertEquals(
                    List.of("codex.auth.json"),
                    files.map(p -> p.getFileName().toString()).toList());
        }
    }

    private long callsEndingWith(final String suffix) {
        return http.requests.stream()
                .filter(r -> r.uri().getPath().endsWith(suffix))
                .count();
    }

    private static <T> T await(final CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static void await(final CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "latch did not arrive");
    }

    private static Map<String, String> query(final String url) {
        final var values = new HashMap<String, String>();
        for (final var part : URI.create(url).getRawQuery().split("&")) {
            final var entry = part.split("=", 2);
            values.put(entry[0], URLDecoder.decode(entry[1], StandardCharsets.UTF_8));
        }
        return values;
    }

    private static JsonMapper interceptSerialization(final JsonMapper mapper, final InterruptibleAction before) {
        return (JsonMapper) Proxy.newProxyInstance(
                JsonMapper.class.getClassLoader(), new Class<?>[] {JsonMapper.class}, (proxy, method, args) -> {
                    if ("toBytes".equals(method.getName()) && args != null && args[0] instanceof CodexTokenStore) {
                        before.run();
                    }
                    try {
                        return method.invoke(mapper, args);
                    } catch (final InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static Response success(final HttpRequest request) {
        final var path = request.uri().getPath();
        if (path.endsWith("/deviceauth/usercode")) {
            return new Response(
                    request,
                    200,
                    "{\"device_auth_id\":\"private-device-id\",\"user_code\":\"USER-CODE\",\"interval\":1}");
        }
        if (path.endsWith("/deviceauth/token")) {
            return new Response(
                    request, 200, "{\"authorization_code\":\"fake-code\",\"code_verifier\":\"private-verifier\"}");
        }
        if (path.endsWith("/oauth/token")) {
            return new Response(
                    request, 200, "{\"access_token\":\"access-secret\",\"refresh_token\":\"refresh-secret\"}");
        }
        if (path.endsWith("/v1/agent/register")) {
            return new Response(request, 200, "{\"agent_runtime_id\":\"runtime\"}");
        }
        if (path.endsWith("/task/register")) {
            return new Response(request, 200, "{\"task_id\":\"task\"}");
        }
        throw new AssertionError("Unexpected fake HTTP request path");
    }

    private static final class Callbacks implements CodexLoginService.LoginListener {
        private final List<CodexLoginService.LoginStatus> statuses = new CopyOnWriteArrayList<>();
        private final AtomicReference<CodexLoginService.DeviceChallenge> challenge = new AtomicReference<>();
        private final AtomicReference<String> browser = new AtomicReference<>();

        @Override
        public void status(final CodexLoginService.LoginStatus status) {
            statuses.add(status);
        }

        @Override
        public void device(final CodexLoginService.DeviceChallenge value) {
            challenge.set(value);
        }

        @Override
        public void browser(final String value) {
            browser.set(value);
        }
    }

    /** Simulates either an interruptible HTTP operation or a provider SDK ignoring interruption. */
    private static final class Block {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final boolean ignoreInterrupts;

        private Block(final boolean ignoreInterrupts) {
            this.ignoreInterrupts = ignoreInterrupts;
        }

        private void waitForRelease() throws InterruptedException {
            entered.countDown();
            while (true) {
                try {
                    await(release);
                    return;
                } catch (final InterruptedException e) {
                    interrupted.countDown();
                    if (!ignoreInterrupts) {
                        throw e;
                    }
                }
            }
        }
    }

    @FunctionalInterface
    private interface InterruptibleAction {
        void run() throws InterruptedException;
    }

    @FunctionalInterface
    private interface Responder {
        Response respond(HttpRequest request) throws IOException, InterruptedException;
    }

    private static final class FakeHttpClient extends ExtendedHttpClient {
        private final List<HttpRequest> requests = new CopyOnWriteArrayList<>();
        private final AtomicInteger closes = new AtomicInteger();
        private Responder responder = CodexLoginAttemptTest::success;

        private FakeHttpClient(final HttpClient delegate) {
            super(new ExtendedHttpClientConfiguration().setDelegate(delegate));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(final HttpRequest request, final HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            requests.add(request);
            return (HttpResponse<T>) responder.respond(request);
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            super.close();
        }
    }

    private record Response(HttpRequest request, int statusCode, String body) implements HttpResponse<String> {
        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of("content-type", List.of("application/json")), (k, v) -> true);
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

    /** No background thread or timing race: tests explicitly run the queued task. */
    private static final class QueuedExecutor extends AbstractExecutorService {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        private boolean shutdown;

        @Override
        public void execute(final Runnable command) {
            if (shutdown) {
                throw new RejectedExecutionException("password-secret");
            }
            queue.add(command);
        }

        private void runNext() {
            queue.remove().run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown();
            final var remaining = new ArrayList<>(queue);
            queue.clear();
            return remaining;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && queue.isEmpty();
        }

        @Override
        public boolean awaitTermination(final long timeout, final TimeUnit unit) {
            return isTerminated();
        }
    }
}
