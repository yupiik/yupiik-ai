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

import io.yupiik.ia.agent.core.llm.JsonMappers;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BotAuditTest {
    @TempDir
    Path home;

    @Test
    void julEventContainsOnlyFixedCodeAndTaskIdentity() throws Exception {
        final var logger = Logger.getLogger(BotAudit.class.getName());
        final var previous = logger.getLevel();
        final var messages = new ArrayList<String>();
        final var testThread = Thread.currentThread();
        final var handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                if (Thread.currentThread() == testThread) {
                    messages.add(record.getMessage());
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        logger.setLevel(Level.FINE);
        try (final var json = JsonMappers.create(key -> Optional.empty())) {
            final var audit = new BotAudit(BotTestConfiguration.home(home), json, BotTestConfiguration.CLOCK);
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                executor.submit(() -> audit.record("worker-selected", TaskFixtures.task(Map.of("id", "other-task"))))
                        .get();
            }
            audit.record("completed", TaskFixtures.task(Map.of("id", "task-id", "request", "sensitive-request")));
            assertEquals(List.of("GitHub bot event=completed task=task-id"), messages);
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
    }

    @Test
    void concurrentRecordsAreCompleteJsonWithoutTaskTextOrCredentials() throws Exception {
        try (final var json = JsonMappers.create(key -> Optional.empty())) {
            final var audit = new BotAudit(BotTestConfiguration.home(home), json, BotTestConfiguration.CLOCK);
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var futures = IntStream.range(0, 20)
                        .mapToObj(i -> executor.submit(() -> audit.record(
                                "created",
                                TaskFixtures.task(Map.of(
                                        "id", "task-" + i, "request", "secret-request", "token", "secret-token")))))
                        .toList();
                for (final var future : futures) {
                    future.get();
                }
            }
            final var lines = Files.readAllLines(home.resolve("audit.jsonl"));
            assertEquals(20, lines.size());
            for (final var line : lines) {
                final var entry = (Map<?, ?>) json.fromString(Object.class, line);
                assertEquals(BotTestConfiguration.CLOCK.instant().toString(), entry.get("at"));
                assertFalse(line.contains("secret"));
            }
            Files.delete(home.resolve("audit.jsonl"));
            final var outside = Files.writeString(home.resolve("outside"), "keep");
            Files.createSymbolicLink(home.resolve("audit.jsonl"), outside);
            assertThrows(UncheckedIOException.class, () -> audit.record("created", TaskFixtures.task(Map.of())));
            assertEquals("keep", Files.readString(outside));
        }
    }
}
