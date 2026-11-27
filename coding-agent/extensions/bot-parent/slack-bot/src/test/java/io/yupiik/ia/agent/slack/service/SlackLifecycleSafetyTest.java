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
package io.yupiik.ia.agent.slack.service;

import static io.yupiik.ia.agent.slack.support.SlackDomainFixture.task;
import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.bot.feedback.FeedbackMailbox;
import io.yupiik.ia.agent.bot.runtime.admission.HostAdmission;
import io.yupiik.ia.agent.bot.runtime.admission.HostAdmissionConfiguration;
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderBudgetConfiguration;
import io.yupiik.ia.agent.core.question.Question;
import io.yupiik.ia.agent.slack.configuration.SlackPrompts;
import io.yupiik.ia.agent.slack.context.SlackContextStorage;
import io.yupiik.ia.agent.slack.model.SlackQuestion;
import io.yupiik.ia.agent.slack.model.SlackQuestionItem;
import io.yupiik.ia.agent.slack.model.SlackQuestionRequest;
import io.yupiik.ia.agent.slack.model.SlackTask;
import io.yupiik.ia.agent.slack.model.TaskStatus;
import io.yupiik.ia.agent.slack.store.SlackStore;
import io.yupiik.ia.agent.slack.support.SlackConfigurationTestModule;
import io.yupiik.ia.agent.slack.support.SlackDomainFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@FusionSupport(modules = SlackConfigurationTestModule.class)
class SlackLifecycleSafetyTest {
    @Fusion
    private SlackPrompts baselinePrompts;

    @TempDir
    Path root;

    @Test
    void cancellationRetainsAttemptAndHostSlotUntilInterruptedLauncherSettlesAndFinalStopCompletes() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            fixture.add(task("task", "U1", TaskStatus.queued, 1));
            final var admission = new HostAdmission(
                    new HostAdmissionConfiguration(root.resolve("host-budget").toString(), 1, ""));
            final var entered = new CountDownLatch(1);
            final var interrupted = new CountDownLatch(1);
            final var firstStop = new CountDownLatch(1);
            final var finalStop = new CountDownLatch(1);
            final var releaseFinalStop = new CompletableFuture<Void>();
            final var releaseLauncher = new CompletableFuture<Void>();
            final var attempt = new CompletableFuture<Path>();
            final List<String> events = new CopyOnWriteArrayList<>();
            final Sandbox sandbox = new Sandbox() {
                @Override
                public int execute(
                        final Path directory,
                        final String runtime,
                        final String prompt,
                        final Path temporaryDirectory) {
                    attempt.complete(directory);
                    entered.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (final InterruptedException cancellation) {
                        interrupted.countDown();
                    }
                    releaseLauncher.join();
                    events.add("launcher-settled");
                    return 0;
                }

                @Override
                public void stop(final String runtime) {
                    events.add("stop");
                    if (firstStop.getCount() != 0) {
                        firstStop.countDown();
                    } else {
                        finalStop.countDown();
                        releaseFinalStop.join();
                    }
                }

                @Override
                public void close() {}
            };
            final var worker = worker(fixture, sandbox, admission);
            final var completed = new CompletableFuture<Boolean>();
            final var thread = Thread.ofVirtual().start(() -> {
                try {
                    completed.complete(worker.runOne());
                } catch (final Throwable failure) {
                    completed.completeExceptionally(failure);
                }
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                change(fixture, current -> current.transition(TaskStatus.cancel_requested, System.currentTimeMillis()));
                assertTrue(interrupted.await(5, TimeUnit.SECONDS), "Cancellation must interrupt the launcher");
                assertTrue(firstStop.await(5, TimeUnit.SECONDS));
                assertFalse(completed.isDone(), "A successful early stop cannot settle a still-running launcher");
                assertTrue(Files.isDirectory(attempt.join()));
                assertEquals(
                        TaskStatus.cancel_requested,
                        fixture.store.list().getFirst().status());
                assertTrue(
                        admission.tryAcquire().isEmpty(),
                        "Host slot must stay reserved while launch can still create a runtime");
                releaseLauncher.complete(null);
                assertTrue(finalStop.await(5, TimeUnit.SECONDS), "Final stop must follow launcher settlement");
                assertFalse(completed.isDone(), "Final stop must finish before the worker settles");
                assertTrue(Files.isDirectory(attempt.join()));
                assertEquals(
                        TaskStatus.cancel_requested,
                        fixture.store.list().getFirst().status());
                assertTrue(admission.tryAcquire().isEmpty(), "Host slot must stay reserved during final stop");
                releaseFinalStop.complete(null);
                assertTrue(completed.get(5, TimeUnit.SECONDS));
                assertEquals(List.of("stop", "launcher-settled", "stop"), events);
                assertEquals(
                        TaskStatus.cancelled, fixture.store.list().getFirst().status());
                assertFalse(Files.exists(attempt.join()));
                assertTrue(fixture.exchanges.isEmpty());
                try (final var lease = admission.tryAcquire().orElseThrow()) {
                    lease.confirmTermination();
                }
            } finally {
                releaseLauncher.complete(null);
                releaseFinalStop.complete(null);
                // On the successful path the launcher already observed cancellation. Interrupting
                // the worker here races its final store write instead of helping launcher cleanup.
                if (interrupted.getCount() != 0) {
                    thread.interrupt();
                }
                assertTrue(thread.join(Duration.ofSeconds(5)), "Worker must exit before fixture teardown");
            }
        }
    }

    @Test
    void cancellationAfterStatusReadWinsOverFinalAnswerPublication() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            final var original = task("task", "U1", TaskStatus.queued, 1);
            fixture.add(original);
            final var stopped = new AtomicBoolean();
            final Sandbox sandbox = new Sandbox() {
                @Override
                public int execute(
                        final Path directory,
                        final String runtime,
                        final String prompt,
                        final Path temporaryDirectory) {
                    try {
                        Files.writeString(
                                directory.resolve("output/result.json"),
                                fixture.json.toString(Map.of("taskId", original.id(), "summary", "Do not publish")));
                    } catch (final IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                    return 0;
                }

                @Override
                public void stop(final String runtime) {
                    stopped.set(true);
                }

                @Override
                public void close() {}
            };
            // Return the pre-cancellation snapshot, then persist cancellation before publication.
            // The publication intent must compare and transition under the store transaction,
            // independently of which JSON reader validates the result.
            final var cancellingStore = new SlackStore(fixture.configuration, fixture.json) {
                @Override
                public List<SlackTask> list() {
                    final var snapshot = fixture.store.list();
                    if (stopped.compareAndSet(true, false)) {
                        change(
                                fixture,
                                current -> current.transition(TaskStatus.cancel_requested, System.currentTimeMillis()));
                    }
                    return snapshot;
                }

                @Override
                public <T> T transaction(final Function<List<SlackTask>, T> operation) {
                    return fixture.store.transaction(operation);
                }
            };
            final var worker = new SlackWorker(
                    fixture.configuration,
                    fixture.executor(root),
                    fixture.json,
                    cancellingStore,
                    new SlackPromptRenderer(SlackDomainFixture.prompts(baselinePrompts), fixture.json),
                    sandbox,
                    feedback(fixture),
                    fixture.api,
                    new HostAdmission(new HostAdmissionConfiguration("", 1, "")),
                    fixture.contextConfiguration,
                    fixture.actions,
                    fixture.contextDispatcher,
                    new SlackContextStorage());
            assertTrue(worker.runOne());
            assertEquals(TaskStatus.cancelled, fixture.store.list().getFirst().status());
            assertTrue(fixture.exchanges.isEmpty());
        }
    }

    @Test
    void pendingQuestionPublicationReceiptCannotEraseCancellation() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            final var original = task("task", "U1", TaskStatus.running, 1);
            fixture.add(original);
            final var mailboxRoot = Files.createDirectory(
                    root.resolve("mailbox"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            try (final var mailbox = new FeedbackMailbox(mailboxRoot, fixture.json)) {
                mailbox.request(
                        List.of(new Question("question", "Choose", List.of("yes", "no"), false)),
                        System.currentTimeMillis() + 30000);
                fixture.response = exchange -> {
                    assertEquals(
                            "publishing",
                            fixture.store.list().getFirst().question().state());
                    change(
                            fixture,
                            current -> current.transition(TaskStatus.cancel_requested, System.currentTimeMillis()));
                    return "{\"ok\":true,\"channel\":\"C1\",\"ts\":\"2.1\"}";
                };
                feedback(fixture).poll(original.id(), mailbox);
                assertEquals(
                        TaskStatus.cancel_requested,
                        fixture.store.list().getFirst().status());
                assertEquals(1, fixture.exchanges.size());
            }
        }
    }

    @Test
    void staleModalFailureCannotReplaceCancelledTaskWithRecoveryBlocked() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            final var nonce = "12345678-1234-4234-8234-123456789abc";
            final var request = new SlackQuestionRequest(
                    nonce,
                    System.currentTimeMillis() + 30000,
                    List.of(new SlackQuestionItem("question", "Choose", List.of("yes", "no"), false)));
            fixture.add(task("task", "U1", TaskStatus.running, 1)
                    .feedback(
                            new SlackQuestion(request, "waiting", "2.1", null, null), TaskStatus.waiting_for_user, 1));
            fixture.response = exchange -> {
                assertEquals(
                        "opening", fixture.store.list().getFirst().question().state());
                change(fixture, current -> current.feedback(null, TaskStatus.cancelled, System.currentTimeMillis()));
                return "{\"ok\":false,\"error\":\"invalid_trigger\"}";
            };
            assertDoesNotThrow(() -> feedback(fixture)
                    .interaction(fixture.payload(Map.of(
                            "type",
                            "block_actions",
                            "team",
                            Map.of("id", "T1"),
                            "user",
                            Map.of("id", "U1"),
                            "channel",
                            Map.of("id", "C1"),
                            "message",
                            Map.of("ts", "2.1"),
                            "trigger_id",
                            "trigger",
                            "actions",
                            List.of(Map.of("action_id", "answer", "value", nonce))))));
            assertEquals(TaskStatus.cancelled, fixture.store.list().getFirst().status());
            assertNull(fixture.store.list().getFirst().question());
            assertEquals(1, fixture.exchanges.size());
        }
    }

    @Test
    void rejectsUnsupportedHandoffBeforeBackendConstructionOrCommands() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            for (final var backend : ExecutorConfiguration.Backend.values()) {
                final var settings = new ExecutorConfiguration(
                        backend,
                        root.resolve("attempts").toString(),
                        60,
                        1,
                        "hidden",
                        new ExecutorConfiguration.Docker("/must-not-run/docker", "agent", "", null, null, false),
                        null,
                        null,
                        null);
                final var failure = assertThrows(
                        IllegalArgumentException.class,
                        () -> new SlackSandbox(
                                settings,
                                null,
                                Configuration.of(Map.of()),
                                fixture.json,
                                root.resolve("state"),
                                new ProviderBudgetConfiguration(null, null, null),
                                false,
                                false));
                assertTrue(failure.getMessage().contains("requires"));
            }
        }
    }

    private SlackWorker worker(final SlackDomainFixture fixture, final Sandbox sandbox, final HostAdmission admission) {
        return new SlackWorker(
                fixture.configuration,
                fixture.executor(root),
                fixture.json,
                fixture.store,
                new SlackPromptRenderer(SlackDomainFixture.prompts(baselinePrompts), fixture.json),
                sandbox,
                feedback(fixture),
                fixture.api,
                admission,
                fixture.contextConfiguration,
                fixture.actions,
                fixture.contextDispatcher,
                new SlackContextStorage());
    }

    private SlackFeedback feedback(final SlackDomainFixture fixture) {
        return new SlackFeedback(
                fixture.configuration, SlackDomainFixture.prompts(baselinePrompts), fixture.store, fixture.api);
    }

    private void change(final SlackDomainFixture fixture, final UnaryOperator<SlackTask> operation) {
        fixture.store.transaction(tasks -> {
            tasks.set(0, operation.apply(tasks.getFirst()));
            return null;
        });
    }
}
