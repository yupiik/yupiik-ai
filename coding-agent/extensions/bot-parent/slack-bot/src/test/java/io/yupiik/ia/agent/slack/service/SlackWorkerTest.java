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

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.bot.runtime.admission.HostAdmission;
import io.yupiik.ia.agent.bot.runtime.admission.HostAdmissionConfiguration;
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackPrompts;
import io.yupiik.ia.agent.slack.context.SlackContextStorage;
import io.yupiik.ia.agent.slack.model.SlackTask;
import io.yupiik.ia.agent.slack.model.TaskStatus;
import io.yupiik.ia.agent.slack.store.SlackStore;
import io.yupiik.ia.agent.slack.support.SlackConfigurationTestModule;
import io.yupiik.ia.agent.slack.support.SlackDomainFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@FusionSupport(modules = SlackConfigurationTestModule.class)
class SlackWorkerTest {
    @Fusion
    private SlackPrompts baselinePrompts;

    @TempDir
    Path root;

    @Test
    void confirmsStopBeforePublishingThenCleansAttempt() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2);
                final var sandbox = new FakeSandbox(fixture, 0, false)) {
            fixture.add(task("task", "U1", TaskStatus.queued, 1));
            fixture.response = exchange -> {
                sandbox.events.add("publish");
                assertEquals(List.of("execute", "stop", "publish"), sandbox.events);
                assertTrue(Files.isDirectory(sandbox.path));
                assertEquals(
                        TaskStatus.publishing, fixture.store.list().getFirst().status());
                final var body = (Map<?, ?>) fixture.json.fromString(Object.class, exchange.body());
                assertEquals("C1", body.get("channel"));
                assertEquals("1.1", body.get("thread_ts"));
                assertEquals("Assistant response", body.get("text"));
                final var section = (Map<?, ?>) ((List<?>) body.get("blocks")).getFirst();
                assertEquals(Map.of("type", "plain_text", "text", "Safe answer"), section.get("text"));
                assertEquals(
                        "Bearer xoxb-test",
                        exchange.request().headers().firstValue("Authorization").orElseThrow());
                return "{\"ok\":true,\"channel\":\"C1\",\"ts\":\"2.1\"}";
            };
            assertTrue(worker(fixture, sandbox).runOne());
            final var saved = fixture.store.list().getFirst();
            assertEquals(TaskStatus.done, saved.status());
            assertEquals("Safe answer", saved.answer());
            assertEquals("2.1", saved.messageTs());
            assertFalse(Files.exists(sandbox.path));
        }
    }

    @Test
    void ambiguousPublicationPreservesEvidenceAndDoesNotReplay() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2);
                final var sandbox = new FakeSandbox(fixture, 0, false)) {
            fixture.add(task("task", "U1", TaskStatus.queued, 1));
            fixture.response = exchange -> {
                throw new IllegalStateException("Unknown remote outcome");
            };
            final var worker = worker(fixture, sandbox);
            assertTrue(worker.runOne());
            assertEquals(
                    TaskStatus.recovery_blocked, fixture.store.list().getFirst().status());
            assertTrue(Files.isRegularFile(sandbox.path.resolve("output/result.json")));
            assertFalse(worker.runOne());
            assertEquals(1, fixture.exchanges.size());
        }
    }

    @Test
    void simultaneousWorkerCannotClaimNextTaskInSameConversation() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2);
                final var sandbox = new FakeSandbox(fixture, 0, false)) {
            fixture.add(task("first", "U1", TaskStatus.queued, 1), task("next", "U1", TaskStatus.queued, 2));
            final var worker = worker(fixture, sandbox);
            sandbox.duringExecution =
                    () -> assertFalse(worker.runOne(), "Concurrent worker must leave next task queued");
            assertTrue(worker.runOne());
            sandbox.duringExecution = () -> {};
            assertTrue(worker.runOne());
            assertEquals(
                    List.of(TaskStatus.done, TaskStatus.done),
                    fixture.store.list().stream().map(SlackTask::status).toList());
            assertEquals(2, sandbox.events.stream().filter("execute"::equals).count());
        }
    }

    @Test
    void unknownStopBlocksPublicationAndCleanup() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2);
                final var sandbox = new FakeSandbox(fixture, 0, true)) {
            fixture.add(task("task", "U1", TaskStatus.queued, 1));
            assertTrue(worker(fixture, sandbox).runOne());
            assertEquals(
                    TaskStatus.recovery_blocked, fixture.store.list().getFirst().status());
            assertTrue(fixture.exchanges.isEmpty());
            assertTrue(Files.isDirectory(sandbox.path));
            assertTrue(Files.isRegularFile(sandbox.path.resolve("output/result.json")));
            assertTrue(sandbox.events.contains("stop"));
        }
    }

    @Test
    void nonzeroExitNeverPublishes() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2);
                final var sandbox = new FakeSandbox(fixture, 7, false)) {
            fixture.add(task("task", "U1", TaskStatus.queued, 1));
            assertTrue(worker(fixture, sandbox).runOne());
            assertTrue(sandbox.events.contains("stop"));
            assertTrue(fixture.exchanges.isEmpty(), "Failed sandbox output must not be published");
            assertEquals(TaskStatus.failed, fixture.store.list().getFirst().status());
        }
    }

    @Test
    void queuedConversationWaitsUntilPriorTaskIsTerminal() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2);
                final var sandbox = new FakeSandbox(fixture, 0, false)) {
            fixture.add(task("active", "U1", TaskStatus.waiting_for_user, 1), task("next", "U1", TaskStatus.queued, 2));
            final var worker = worker(fixture, sandbox);
            assertFalse(worker.runOne());
            assertTrue(sandbox.events.isEmpty());
            fixture.store.transaction(tasks -> {
                tasks.set(0, tasks.getFirst().publication("Previous answer", "2.1", TaskStatus.done, 3));
                return null;
            });
            assertTrue(worker.runOne());
            assertEquals(1, sandbox.events.stream().filter("execute"::equals).count());
            assertEquals(TaskStatus.done, fixture.store.list().get(1).status());
        }
    }

    @Test
    void recoveryStopsKnownRuntimeWithoutReplayingPublicationOrDeletingEvidence() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2);
                final var sandbox = new FakeSandbox(fixture, 0, false)) {
            final var executor = fixture.executor(root);
            final var directory =
                    Files.createDirectories(Path.of(executor.attemptRoot()).resolve("yca-slack-evidence"));
            final var evidence = Files.writeString(directory.resolve("result"), "preserve");
            final var original = task("task", "U1", TaskStatus.queued, 1);
            fixture.add(original.running(directory.toString(), runtime(original), fingerprint(executor), false, 2)
                    .publication("answer", null, TaskStatus.publishing, 3));
            worker(fixture, sandbox).recover();
            assertEquals(List.of("stop"), sandbox.events);
            assertEquals(
                    TaskStatus.recovery_blocked, fixture.store.list().getFirst().status());
            assertEquals("preserve", Files.readString(evidence));
            assertTrue(fixture.exchanges.isEmpty());
        }
    }

    @Test
    void recoveryDoesNotStopUntrustedRuntimeAndDoesNotClaimQueuedTask() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2);
                final var sandbox = new FakeSandbox(fixture, 0, false)) {
            final var original = task("task", "U1", TaskStatus.queued, 1);
            fixture.add(
                    original.running(
                            root.resolve("outside").toString(), runtime(original), "wrong-fingerprint", false, 2),
                    task("queued", "U2", TaskStatus.queued, 1));
            worker(fixture, sandbox).recover();
            assertTrue(sandbox.events.isEmpty());
            assertEquals(
                    List.of(TaskStatus.recovery_blocked, TaskStatus.queued),
                    fixture.store.list().stream().map(SlackTask::status).toList());
        }
    }

    @Test
    void malformedRecoveryPathIsQuarantined() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2);
                final var sandbox = new FakeSandbox(fixture, 0, false)) {
            final var original = task("task", "U1", TaskStatus.queued, 1);
            fixture.add(original.running("/", runtime(original), fingerprint(fixture.executor(root)), false, 2));
            assertDoesNotThrow(() -> worker(fixture, sandbox).recover());
            assertEquals(
                    TaskStatus.recovery_blocked, fixture.store.list().getFirst().status());
            assertTrue(sandbox.events.isEmpty());
        }
    }

    private SlackWorker worker(final SlackDomainFixture fixture, final Sandbox sandbox) {
        return new SlackWorker(
                fixture.configuration,
                fixture.executor(root),
                fixture.json,
                fixture.store,
                new SlackPromptRenderer(SlackDomainFixture.prompts(baselinePrompts), fixture.json),
                sandbox,
                new SlackFeedback(
                        fixture.configuration, SlackDomainFixture.prompts(baselinePrompts), fixture.store, fixture.api),
                fixture.api,
                new HostAdmission(new HostAdmissionConfiguration("", 1, "")),
                fixture.contextConfiguration,
                fixture.actions,
                fixture.contextDispatcher,
                new SlackContextStorage());
    }

    private String runtime(final SlackTask task) {
        return "yca-slack-" + task.id().substring(0, 32) + "-1";
    }

    private String fingerprint(final ExecutorConfiguration executor) {
        return SlackStore.identity("runtime-ownership-v2:" + SlackExecutionPolicy.POLICY + ':' + executor);
    }

    private static final class FakeSandbox implements Sandbox {
        private final SlackDomainFixture fixture;
        private final int exit;
        private final boolean unknownStop;
        private final List<String> events = new CopyOnWriteArrayList<>();
        private volatile Path path;
        private Runnable duringExecution = () -> {};

        private FakeSandbox(final SlackDomainFixture fixture, final int exit, final boolean unknownStop) {
            this.fixture = fixture;
            this.exit = exit;
            this.unknownStop = unknownStop;
        }

        @Override
        public int execute(
                final Path directory, final String runtime, final String prompt, final Path temporaryDirectory) {
            path = directory;
            events.add("execute");
            final var durable = fixture.store.list().stream()
                    .filter(task -> runtime.equals(task.runtimeId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(TaskStatus.running, durable.status());
            assertEquals(directory.toString(), durable.attemptPath());
            assertTrue(prompt.contains(durable.id()));
            assertFalse(Files.exists(directory.resolve(".prepared-only")));
            duringExecution.run();
            try {
                Files.writeString(
                        directory.resolve("output/result.json"),
                        fixture.json.toString(Map.of("taskId", durable.id(), "summary", "Safe answer")));
            } catch (final IOException error) {
                throw new IllegalStateException(error);
            }
            return exit;
        }

        @Override
        public void stop(final String runtime) {
            events.add("stop");
            if (unknownStop) {
                throw new IllegalStateException("Termination not confirmed");
            }
        }

        @Override
        public void close() {}
    }
}
