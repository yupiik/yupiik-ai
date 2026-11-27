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
package io.yupiik.ia.agent.github.model;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.github.store.MutableTask;
import io.yupiik.ia.agent.github.store.StateDocument;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TaskDocumentTest {
    @Test
    void reviewTargetIsAnExplicitDurableFieldPreservedByMutableSnapshots() {
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            for (final var target : List.of("pull_request", "issue", "discussion")) {
                final var task = MutableTask.create();
                task.kind(TaskKind.review);
                task.reviewTarget(target);
                assertEquals(target, task.reviewTarget());
                final var decoded = json.fromString(TaskDocument.class, json.toString(task.document()));
                assertEquals(target, decoded.reviewTarget());
                assertTrue(decoded.recovery() == null || !decoded.recovery().containsKey("reviewTarget"));
                final var restored = new MutableTask(decoded);
                assertEquals(target, restored.document().reviewTarget());
                restored.reviewTarget(null);
                assertNull(restored.document().reviewTarget());
            }
        }
    }

    @Test
    void roundTripsTaskRecoveryFieldsWithoutChangingDurableShape() {
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            final var source = Map.<String, Object>of(
                    "id",
                    "task-1",
                    "repository",
                    "acme/repo",
                    "kind",
                    "review",
                    "reviewTarget",
                    "pull_request",
                    "state",
                    "publishing",
                    "attempts",
                    2,
                    "reviewIntent",
                    "marker",
                    "runtimeId",
                    "yca-runtime",
                    "result",
                    Map.of("summary", "done"));
            final var document = TaskFixtures.task(source);
            final var encoded = json.toString(document);
            assertFalse(encoded.contains("\"recovery\""));
            final var restored = TaskFixtures.map(json.fromString(TaskDocument.class, encoded));
            assertEquals(source.keySet(), restored.keySet());
            assertEquals("marker", restored.get("reviewIntent"));
            assertEquals("yca-runtime", restored.get("runtimeId"));
            assertEquals(Map.of("summary", "done"), restored.get("result"));
            assertEquals(2, ((Number) restored.get("attempts")).intValue());
            final var state = new StateDocument(
                    1, Map.of("task-1", document), Map.of("cursor", "next"), Map.of("invocation", "task-1"));
            final var decoded = json.fromString(StateDocument.class, json.toString(state));
            assertEquals("marker", decoded.tasks().get("task-1").reviewIntent());
            assertEquals(state.receipts(), decoded.receipts());
        }
    }

    @Test
    void retainsUnknownExtensionsAndRejectsFractionalQueueNumbers() {
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            final var document = TaskFixtures.task(Map.of(
                    "kind",
                    "help",
                    "state",
                    "pending",
                    "number",
                    42,
                    "attempts",
                    1,
                    "futureExtension",
                    Map.of("flag", true)));
            final var decoded = json.fromString(TaskDocument.class, json.toString(document));
            assertEquals(TaskKind.help, decoded.kind());
            assertEquals(TaskState.pending, decoded.state());
            assertEquals(42L, decoded.number());
            assertEquals(Map.of("flag", true), decoded.recovery().get("futureExtension"));
            assertThrows(ArithmeticException.class, () -> TaskFixtures.task(Map.of("number", 1.5)));
            assertThrows(ArithmeticException.class, () -> TaskFixtures.task(Map.of("attempts", 1.5)));
        }
    }

    @Test
    void resultSnapshotDetachesKnownChangesAndUnknownEvidence() {
        final var changes = new ArrayList<AgentResult.Change>();
        changes.add(new AgentResult.Change("file.txt", "b2s=", false, Map.of()));
        final var future = new LinkedHashMap<String, Object>(Map.of("flag", true));
        final var result = new AgentResult(
                null,
                null,
                null,
                null,
                null,
                null,
                new AgentResult.StrictSolve("base", "Fix", "Body", changes),
                null,
                null,
                Map.of("futureEvidence", future));
        changes.clear();
        future.put("flag", false);
        assertEquals("b2s=", result.strictSolve().changes().getFirst().content());
        assertEquals(Map.of("flag", true), result.evidence().get("futureEvidence"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.strictSolve().changes().clear());
    }

    @Test
    void commandListHasDomainRootAndTypedTaskElements() {
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            final var list =
                    new TaskDocument.TaskList(List.of(TaskFixtures.task(Map.of("id", "task-1", "kind", "help"))));
            final var encoded = json.toString(list);
            assertTrue(encoded.startsWith("{\"tasks\":[{"));
            final var decoded = json.fromString(TaskDocument.TaskList.class, encoded);
            assertEquals("task-1", decoded.tasks().getFirst().id());
        }
    }
}
