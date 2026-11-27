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

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.bot.runtime.DockerControlConfiguration;
import io.yupiik.ia.agent.bot.runtime.admission.HostAdmission;
import io.yupiik.ia.agent.bot.runtime.admission.HostAdmissionConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderBudgetConfiguration;
import io.yupiik.ia.agent.github.command.WorkerCommand.Options;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.configuration.GithubExecutorConfiguration;
import io.yupiik.ia.agent.github.configuration.WorkerOptions;
import io.yupiik.ia.agent.github.model.TaskState;
import io.yupiik.ia.agent.github.store.TaskStore;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD) // The class owns one stable configuration and its fixture paths/transport.
@FusionSupport(modules = WorkerAdmissionTest.Source.class)
class WorkerAdmissionTest {
    @Fusion
    private Options injectedOptions;

    @Fusion
    private GithubExecutorConfiguration injectedExecutor;

    @TempDir
    static Path home;

    @BeforeEach
    void resetState() throws Exception {
        try (final var paths = Files.walk(home)) {
            for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(home)) {
                    Files.delete(path);
                }
            }
        }
    }

    @Test
    void fullHostBudgetLeavesTaskUnclaimedAndDoesNotPrepare() throws IOException {
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var conf = BotTestConfiguration.home(home.resolve("state"));
            final var store = new TaskStore(conf, json);
            final var id = enqueue(store);
            try (final var occupied =
                    new HostAdmission(configuration()).tryAcquire().orElseThrow()) {
                worker(conf, store, json, configuration()).run(id, options());
                assertEquals(
                        TaskState.pending,
                        store.transaction(state -> state.task(id).state()));
                assertEquals(
                        0, store.transaction(state -> state.task(id).attempts()).intValue());
                assertFalse(Files.exists(home.resolve("attempts")));
                occupied.confirmTermination();
            }
        }
    }

    @Test
    void unknownRecoveryTerminationLeavesDebtThatBlocksNewWorkerInstances() throws IOException {
        final var docker = home.resolve("docker");
        Files.writeString(docker, "#!/bin/sh\nexit 97\n");
        Files.setPosixFilePermissions(docker, PosixFilePermissions.fromString("rwx------"));
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var conf = BotTestConfiguration.home(home.resolve("state"));
            final var store = new TaskStore(conf, json);
            final var id = enqueue(store);
            store.transaction(state -> {
                state.task(id).state(TaskState.running);
                state.task(id).attempts(1);
                state.task(id).runtimeId("yca-" + id + "-1");
                state.task(id).executionBackend("docker");
                return null;
            });
            // Executor control failure must never be treated as proof of termination.
            assertThrows(
                    IllegalStateException.class,
                    () -> worker(conf, store, json, configuration()).run(id, options()));
            assertTrue(Files.exists(home.resolve("admission/slot-0.reservation")));
            assertThrows(
                    IllegalStateException.class,
                    () -> worker(conf, store, json, configuration()).run(id, options()));
            assertEquals(
                    TaskState.running, store.transaction(state -> state.task(id).state()));
            assertThrows(IOException.class, () -> new HostAdmission(configuration()).tryAcquire());
        }
    }

    @Test
    void rejectsAdmissionInsideDispatcherStateBeforeClaim() {
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var conf = BotTestConfiguration.home(home.resolve("state"));
            final var store = new TaskStore(conf, json);
            final var id = enqueue(store);
            final var unsafe = new HostAdmissionConfiguration(
                    home.resolve("state/admission").toString(), 1, "");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> worker(conf, store, json, unsafe).run(id, options()));
            assertEquals(
                    TaskState.pending, store.transaction(state -> state.task(id).state()));
        }
    }

    private String enqueue(final TaskStore store) {
        return store.transaction(state -> state.enqueue(
                "admission-task",
                TaskFixtures.task(
                        Map.of("repository", "owner/repo", "number", 1, "kind", "solve", "request", "Do work"))));
    }

    private HostAdmissionConfiguration configuration() {
        return new HostAdmissionConfiguration(home.resolve("admission").toString(), 1, "");
    }

    private WorkerOptions options() {
        return injectedOptions.worker();
    }

    private Worker worker(
            final GithubBotConfiguration conf,
            final TaskStore store,
            final JsonMapper json,
            final HostAdmissionConfiguration admission) {
        final var values = Map.of(
                "github.executor.attemptRoot", home.resolve("attempts").toString(),
                "github.executor.docker.executable", home.resolve("docker").toString());
        final var execution = injectedExecutor.shared();
        return new Worker(
                conf,
                store,
                null,
                json,
                execution,
                BotTestConfiguration.audit(conf, json),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                admission,
                BotTestConfiguration.CLOCK,
                key -> Optional.ofNullable(values.get(key)),
                new ProviderBudgetConfiguration(null, null, null),
                new DockerControlConfiguration("", ""));
    }

    public static class Source extends GithubConfigurationTestModule {
        public Source() {
            super(Map.of(
                    "github.executor.attemptRoot", home.resolve("attempts").toString(),
                    "github.executor.docker.executable", home.resolve("docker").toString()));
        }
    }
}
