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
package io.yupiik.ia.agent.github.command;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.service.TaskDiagnostics;
import io.yupiik.ia.agent.github.store.TaskStore;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

// Capture stdout and JUL in-process without other test classes writing into the capture.
// CommandMetadataTest separately verifies the generated CLI registration.
@Isolated
class ShowCommandTest {
    @TempDir
    Path home;

    @Test
    void privateInspectionIsExplicitAndBypassesApplicationLogging() throws Exception {
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            final var id = store.transaction(state -> state.enqueue(
                    "show-test", TaskFixtures.task(Map.of("repository", "acme/repo", "number", 12, "kind", "review"))));
            final var reference = new TaskDiagnostics(home, json, BotTestConfiguration.CLOCK)
                    .capture(
                            store.transaction(state -> state.task(id).document()),
                            "preparation",
                            new IllegalArgumentException("private-original-secret"),
                            null);
            store.transaction(state -> {
                state.task(id)
                        .lastFailure(new TaskDocument.FailureDetail(
                                "preparation",
                                "IllegalArgumentException",
                                "Unable to prepare the private attempt configuration.",
                                BotTestConfiguration.CLOCK.instant().toString(),
                                1,
                                null,
                                reference,
                                true));
                return null;
            });
            final var ordinary = invoke(json, store, id, false);
            assertFalse((ordinary.stdout() + ordinary.logs()).contains("private-original-secret"));
            assertTrue(ordinary.logs().contains("lastFailure"));
            assertTrue(ordinary.logs().contains("preparation"));
            assertEquals("", ordinary.stdout());
            final var explicit = invoke(json, store, id, true);
            assertTrue(explicit.stdout().contains("private-original-secret"));
            assertEquals("", explicit.logs());
        }
    }

    @Test
    void legacyTaskWithoutPrivateFilesCanBeInspected() throws Exception {
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            final var id = store.transaction(state -> state.enqueue(
                    "legacy",
                    TaskFixtures.task(Map.of(
                            "repository", "acme/repo", "kind", "help", "lastError", "IllegalArgumentException"))));
            final var result = invoke(json, store, id, true);
            assertEquals("[]", result.stdout().trim());
            assertEquals("", result.logs());
            assertFalse(Files.exists(home.resolve("diagnostics")));
        }
    }

    @Test
    void defaultShowDoesNotReadUnsafePrivateStorage() throws Exception {
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            final var id = store.transaction(state -> state.enqueue(
                    "unsafe-private", TaskFixtures.task(Map.of("repository", "acme/repo", "kind", "help"))));
            Files.createSymbolicLink(home.resolve("diagnostics"), Files.createDirectory(home.resolve("outside")));
            final var result = invoke(json, store, id, false);
            assertTrue(result.logs().contains(id));
            assertEquals("", result.stdout());
        }
    }

    private Output invoke(final JsonMapper json, final TaskStore store, final String id, final boolean diagnostics) {
        final var originalOut = System.out;
        final var logger = Logger.getLogger(ShowCommand.class.getName());
        final var originalLevel = logger.getLevel();
        final var originalParentHandlers = logger.getUseParentHandlers();
        final var output = new ByteArrayOutputStream();
        final var logs = new ByteArrayOutputStream();
        final var handler = new StreamHandler(logs, new SimpleFormatter());
        handler.setLevel(Level.ALL);
        try (final var stdout = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            try {
                System.setOut(stdout);
                logger.setLevel(Level.ALL);
                logger.setUseParentHandlers(false);
                logger.addHandler(handler);
                new ShowCommand(
                                new ShowCommand.Options(id, diagnostics),
                                json,
                                store,
                                new TaskDiagnostics(home, json, BotTestConfiguration.CLOCK))
                        .run();
                handler.flush();
                return new Output(output.toString(StandardCharsets.UTF_8), logs.toString(StandardCharsets.UTF_8));
            } finally {
                System.setOut(originalOut);
                logger.removeHandler(handler);
                logger.setLevel(originalLevel);
                logger.setUseParentHandlers(originalParentHandlers);
                handler.close();
            }
        }
    }

    private record Output(String stdout, String logs) {}
}
