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
package io.yupiik.ia.agent.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentEvent.CompactionPass;
import io.yupiik.ia.agent.core.context.CompactionResult.Mode;
import io.yupiik.ia.agent.core.context.ContextMeasurement;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.tools.FileMutation;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.builtin.ApplyPatchTool;
import io.yupiik.ia.agent.core.tools.builtin.EditFileTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AcpUpdatesTest {
    @TempDir
    private static Path eventWorkdir;

    private static final Map<String, ?> ALL = Map.of("session", Map.of("notices", Map.of(), "compaction", Map.of()));

    @Test
    void diagnosticIsToolDisplayContentNeverRawOutputAndKeepsCanonicalScope() {
        try (final var f = new Fixture(ALL)) {
            f.updates.tool(
                    "child-call",
                    new AgentEvent.ToolStarted("mcp_example", "{}", 42, "s", "child", "Worker", eventWorkdir),
                    null);
            f.updates.tool(
                    "child-call",
                    new AgentEvent.ToolDiagnostic(
                            "mcp_example", "private failure", 42, "s", "child", "Worker", eventWorkdir),
                    null);
            final var diagnostic = f.wire.getLast();
            assertEquals("child-call", diagnostic.get("toolCallId"));
            assertTrue(f.mapper.toString(diagnostic.get("content")).contains("private failure"));
            assertFalse(diagnostic.containsKey("rawOutput"));
            f.updates.tool(
                    "child-call",
                    new AgentEvent.ToolFinished(
                            "mcp_example", "MCP failed", false, false, 42, "s", "child", "Worker", eventWorkdir),
                    null);
            final var finished = f.wire.getLast();
            assertEquals("MCP failed", finished.get("rawOutput"));
            assertTrue(f.mapper.toString(finished.get("content")).contains("private failure"));
            assertEquals("failed", finished.get("status"));
        }
    }

    @Test
    void eachCapabilityRequiresExplicitNestedObjectsIncludingEmptyObjects() {
        final List<Map<String, ?>> unsupported = Arrays.asList(
                null,
                Map.of(),
                Collections.singletonMap("session", null),
                Map.of("session", Map.of()),
                Map.of("session", Collections.singletonMap("notices", null)),
                Map.of("session", Collections.singletonMap("compaction", null)),
                Map.of("session", Map.of("notices", false, "compaction", true)),
                Map.of("session", Map.of("notices", "{}", "compaction", List.of())),
                Map.of("session", true),
                Map.of("notices", Map.of(), "compaction", Map.of()));
        for (final var capabilities : unsupported) {
            assertFalse(AcpUpdates.supports(capabilities, "notices"));
            assertFalse(AcpUpdates.supports(capabilities, "compaction"));
            try (final var f = new Fixture(capabilities)) {
                f.updates.event(AgentEvent.Notice.warn("Continuing without MCP", "s", eventWorkdir));
                f.updates.compaction(new CompactionPass(
                        "p", CompactionPass.Status.STARTED, null, null, "s", null, null, eventWorkdir));
                f.updates.compaction(new CompactionPass(
                        "p",
                        CompactionPass.Status.SKIPPED,
                        Mode.NONE,
                        "Compaction skipped; nothing to reclaim.",
                        "s",
                        null,
                        null,
                        eventWorkdir));
                assertEquals(3, f.wire.size());
                assertTrue(f.wire.stream().allMatch(u -> "agent_message_chunk".equals(u.get("sessionUpdate"))));
                assertTrue(f.mapper.toString(f.wire).contains("Continuing without MCP"));
                assertTrue(f.mapper.toString(f.wire).contains("nothing to reclaim"));
            }
        }
        assertTrue(AcpUpdates.supports(ALL, "notices"));
        assertTrue(AcpUpdates.supports(ALL, "compaction"));
        assertTrue(AcpUpdates.supports(Map.of("session", Map.of("notices", Map.of("_meta", Map.of()))), "notices"));
        assertFalse(AcpUpdates.supports(Map.of("session", Map.of("notices", Map.of())), "compaction"));
        assertFalse(AcpUpdates.supports(Map.of("session", Map.of("compaction", Map.of())), "notices"));
    }

    @Test
    void capabilitiesAreCapturedAndGatedIndependently() {
        final var session = new LinkedHashMap<String, Object>();
        session.put("notices", Map.of());
        session.put("compaction", null);
        try (final var f = new Fixture(Map.of("session", session))) {
            session.put("notices", null);
            session.put("compaction", Map.of());
            f.updates.compaction(
                    new CompactionPass("p", CompactionPass.Status.STARTED, null, null, "s", null, null, eventWorkdir));
            f.updates.compaction(new CompactionPass(
                    "p",
                    CompactionPass.Status.SUCCEEDED,
                    Mode.SUMMARY,
                    "Context compacted (SUMMARY).",
                    "s",
                    null,
                    null,
                    eventWorkdir));
            assertEquals(2, f.ofType("notice").size());
            assertTrue(f.ofType("compaction_update").isEmpty());
        }
        try (final var f = new Fixture(Map.of("session", Map.of("compaction", Map.of())))) {
            f.updates.compaction(
                    new CompactionPass("p", CompactionPass.Status.STARTED, null, null, "s", null, null, eventWorkdir));
            f.updates.compaction(new CompactionPass(
                    "p",
                    CompactionPass.Status.SUCCEEDED,
                    Mode.SUMMARY,
                    "Context compacted (SUMMARY).",
                    "s",
                    null,
                    null,
                    eventWorkdir));
            assertEquals(2, f.ofType("compaction_update").size());
            assertTrue(f.ofType("notice").isEmpty());
            assertTrue(f.ofType("agent_message_chunk").isEmpty());
        }
    }

    @Test
    void toolInputStreamsAndAuthoritativeOutputHaveExactWireShapes() {
        try (final var f = new Fixture(Map.of())) {
            f.updates.tool(
                    "server:turn-1:main:7",
                    new AgentEvent.ToolStarted(
                            "bash",
                            "{\"command\":\"printf x\",\"timeout_seconds\":3}",
                            7,
                            "s",
                            null,
                            null,
                            eventWorkdir),
                    null);
            f.assertWire("""
                    [{"sessionUpdate":"tool_call","toolCallId":"server:turn-1:main:7",
                      "title":"bash","name":"bash","kind":"execute","status":"pending",
                      "rawInput":{"command":"printf x","timeout_seconds":3}}]
                    """);
            f.updates.tool(
                    "server:turn-1:main:7",
                    new AgentEvent.ToolStream("bash", "$ printf x", 7, "s", null, null, eventWorkdir),
                    null);
            f.updates.tool(
                    "server:turn-1:main:7",
                    new AgentEvent.ToolStream("bash", "x", 7, "s", null, null, eventWorkdir),
                    null);
            f.assertUpdate(2, """
                    {"sessionUpdate":"tool_call_update","toolCallId":"server:turn-1:main:7",
                     "status":"in_progress","content":[{"type":"content","content":{"type":"text","text":"$ printf x\\nx"}}]}
                    """);
            // A retained earlier map must remain a snapshot rather than change with the stream buffer.
            f.assertUpdate(1, """
                    {"sessionUpdate":"tool_call_update","toolCallId":"server:turn-1:main:7",
                     "status":"in_progress","content":[{"type":"content","content":{"type":"text","text":"$ printf x"}}]}
                    """);
            f.updates.tool(
                    "server:turn-1:main:7",
                    new AgentEvent.ToolFinished("bash", "x\n", true, false, 7, "s", null, null, eventWorkdir),
                    null);
            f.assertUpdate(3, """
                    {"sessionUpdate":"tool_call_update","toolCallId":"server:turn-1:main:7",
                     "status":"completed","rawOutput":"x\\n",
                     "content":[{"type":"content","content":{"type":"text","text":"x\\n"}}]}
                    """);
            f.updates.endTurn(null, false);
            assertEquals(4, f.wire.size()); // completed state was removed
        }
    }

    @Test
    void canonicalIdsSeparateTurnsChildrenAndLegacyOrdinalsWithoutGuessingCorrelation() {
        try (final var f = new Fixture(Map.of())) {
            f.updates.tool(
                    "turn-1:main:1", new AgentEvent.ToolStarted("read", "{}", 1, "s", null, null, eventWorkdir), null);
            f.updates.tool(
                    "turn-1:child-a:1",
                    new AgentEvent.ToolStarted(
                            "read",
                            "{\"path\":\"src/A.java\",\"offset\":42}",
                            1,
                            "s",
                            "child-a",
                            "Reviewer",
                            eventWorkdir),
                    new AcpUpdates.ToolSnapshot(Path.of("/child"), null, List.of(), List.of()));
            f.assertUpdate(1, """
                    {"sessionUpdate":"tool_call","toolCallId":"turn-1:child-a:1","title":"read","name":"read",
                     "kind":"read","status":"pending","rawInput":{"path":"src/A.java","offset":42},
                     "locations":[{"path":"/child/src/A.java","line":42}],
                     "_meta":{"yca":{"subagentId":"child-a","subagentName":"Reviewer"}}}
                    """);
            f.updates.tool(
                    "turn-1:child-a:1",
                    new AgentEvent.ToolStream("read", "visible fragment", 1, "s", "child-a", "Reviewer", eventWorkdir),
                    null);
            assertEquals("turn-1:child-a:1", f.wire.getLast().get("toolCallId"));
            f.updates.tool(
                    "turn-1:child-a:1",
                    new AgentEvent.ToolFinished(
                            "read", "child result", true, false, 1, "s", "child-a", "Reviewer", eventWorkdir),
                    null);
            f.updates.tool(
                    "turn-1:main:1",
                    new AgentEvent.ToolFinished("read", "main result", true, false, 1, "s", null, null, eventWorkdir),
                    null);
            f.updates.endTurn(null, false);
            f.updates.tool(
                    "turn-2:main:1", new AgentEvent.ToolStarted("read", "{}", 1, "s", null, null, eventWorkdir), null);
            f.updates.tool("server-legacy-a", new AgentEvent.ToolStarted("read", "{}", "s", eventWorkdir), null);
            f.updates.tool("server-legacy-b", new AgentEvent.ToolStarted("read", "{}", "s", eventWorkdir), null);
            assertEquals(
                    List.of("turn-1:main:1", "turn-1:child-a:1", "turn-2:main:1", "server-legacy-a", "server-legacy-b"),
                    f.ofType("tool_call").stream().map(u -> u.get("toolCallId")).toList());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> f.updates.tool(
                            "server-legacy-a",
                            new AgentEvent.ToolStream("read", "wrong child", -1, "s", "other", "Other", eventWorkdir),
                            null));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> f.updates.tool(
                            "server-legacy-a",
                            new AgentEvent.ToolStream("bash", "wrong tool", "s", eventWorkdir),
                            null));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> f.updates.tool(
                            "server-legacy-a", new AgentEvent.ToolStarted("read", "{}", "s", eventWorkdir), null));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> f.updates.tool("", new AgentEvent.ToolStarted("read", "{}", "s", eventWorkdir), null));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> f.updates.event(new AgentEvent.ToolStarted("read", "{}", "s", eventWorkdir)));
        }
    }

    @Test
    void pendingIsStillWorkingNotFailureAndEarlyRejectionsCanCreateTerminalCalls() {
        try (final var f = new Fixture(Map.of())) {
            f.updates.tool(
                    "collect",
                    new AgentEvent.ToolFinished(
                            "check_subagents", "Still working", false, true, 2, "s", null, null, eventWorkdir),
                    null);
            f.assertUpdate(0, """
                    {"sessionUpdate":"tool_call","toolCallId":"collect","title":"check_subagents","name":"check_subagents",
                     "kind":"other","status":"in_progress","rawOutput":"Still working",
                     "content":[{"type":"content","content":{"type":"text","text":"Still working"}}]}
                    """);
            f.updates.tool(
                    "collect",
                    new AgentEvent.ToolFinished(
                            "check_subagents", "Done", true, false, 2, "s", null, null, eventWorkdir),
                    null);
            assertEquals("completed", f.wire.getLast().get("status"));
            f.updates.tool(
                    "rejected",
                    new AgentEvent.ToolFinished("not_registered", "Unknown tool", false, "s", eventWorkdir),
                    null);
            f.assertUpdate(2, """
                    {"sessionUpdate":"tool_call","toolCallId":"rejected","title":"not_registered","name":"not_registered",
                     "kind":"other","status":"failed","rawOutput":"Unknown tool",
                     "content":[{"type":"content","content":{"type":"text","text":"Unknown tool"}}]}
                    """);
            f.updates.endTurn(null, false);
            assertEquals(3, f.wire.size());
        }
    }

    @Test
    void lateStreamsUseCanonicalIdAndNullFinalOutputPreservesTheExistingPreview() {
        try (final var f = new Fixture(Map.of())) {
            f.updates.tool("server-id", new AgentEvent.ToolStream("custom", "a", "s", eventWorkdir), null);
            f.updates.tool("server-id", new AgentEvent.ToolStream("custom", "b", "s", eventWorkdir), null);
            f.updates.tool("server-id", new AgentEvent.ToolFinished("custom", null, true, "s", eventWorkdir), null);
            f.assertWire("""
                    [{"sessionUpdate":"tool_call","toolCallId":"server-id","title":"custom","name":"custom",
                      "kind":"other","status":"in_progress",
                      "content":[{"type":"content","content":{"type":"text","text":"a"}}]},
                     {"sessionUpdate":"tool_call_update","toolCallId":"server-id","status":"in_progress",
                      "content":[{"type":"content","content":{"type":"text","text":"ab"}}]},
                     {"sessionUpdate":"tool_call_update","toolCallId":"server-id","status":"completed",
                      "content":[{"type":"content","content":{"type":"text","text":"ab"}}]}]
                    """);
            f.updates.endTurn(null, false);
            assertEquals(3, f.wire.size());
        }
    }

    @Test
    void rawJsonInputsKeepTheirTypesWhileTextualOutputsAreNeverReparsed() {
        try (final var f = new Fixture(Map.of())) {
            for (final var json : List.of("[1,true,\"x\"]", "\"text\"", "42", "false", "null", "{\"optional\":null}")) {
                f.updates.tool(json, new AgentEvent.ToolStarted("custom", json, "s", eventWorkdir), null);
                final var update = (Map<?, ?>) f.mapper.fromString(Object.class, f.mapper.toString(f.wire.getLast()));
                if ("null".equals(json)) {
                    assertFalse(update.containsKey("rawInput"));
                } else {
                    assertEquals(f.mapper.fromString(Object.class, json), update.get("rawInput"));
                }
                f.updates.tool(
                        json, new AgentEvent.ToolFinished("custom", "{\"ok\":true}", true, "s", eventWorkdir), null);
                assertEquals("{\"ok\":true}", f.wire.getLast().get("rawOutput"));
            }
            f.updates.tool("malformed", new AgentEvent.ToolStarted("custom", "{bad", "s", eventWorkdir), null);
            assertEquals("{bad", f.wire.getLast().get("rawInput"));
            f.updates.tool("no-input", new AgentEvent.ToolStarted("custom", null, "s", eventWorkdir), null);
            assertFalse(f.wire.getLast().containsKey("rawInput"));
        }
    }

    @Test
    void kindsUseActualBuiltinNamesAndNeverGuessOpaqueMcpNames() {
        try (final var f = new Fixture(Map.of())) {
            final var kinds = Map.ofEntries(
                    Map.entry("read", "read"),
                    Map.entry("read_symbol", "read"),
                    Map.entry("skill", "read"),
                    Map.entry("code_check_graph", "read"),
                    Map.entry("edit_file", "edit"),
                    Map.entry("write_file", "edit"),
                    Map.entry("apply_patch", "edit"),
                    Map.entry("memory_write", "edit"),
                    Map.entry("glob", "search"),
                    Map.entry("grep", "search"),
                    Map.entry("websearch", "search"),
                    Map.entry("webfetch", "fetch"),
                    Map.entry("bash", "execute"),
                    Map.entry("code_find_code", "search"),
                    Map.entry("code_resolve", "search"),
                    Map.entry("code_trace_calls", "search"),
                    Map.entry("maven_repository_knowledge", "search"),
                    Map.entry("maven_knowledge_content", "read"),
                    Map.entry("compress", "think"),
                    Map.entry("write_todo", "think"),
                    Map.entry("list_subagent_options", "think"),
                    Map.entry("mcp__read_file", "other"),
                    Map.entry("spawn_subagent", "other"));
            for (final var entry : kinds.entrySet()) {
                f.updates.tool(
                        entry.getKey(), new AgentEvent.ToolStarted(entry.getKey(), "{}", "s", eventWorkdir), null);
                assertEquals(entry.getValue(), f.wire.getLast().get("kind"), entry.getKey());
                assertEquals(entry.getKey(), f.wire.getLast().get("name"));
            }
            f.updates.tool(
                    "known-custom",
                    new AgentEvent.ToolStarted("opaque", "{}", "s", eventWorkdir),
                    new AcpUpdates.ToolSnapshot(null, "move", List.of(), List.of()));
            assertEquals("move", f.wire.getLast().get("kind"));
            f.updates.tool(
                    "mcp__read_file",
                    new AgentEvent.ToolFinished("mcp__read_file", "Read data", true, "s", eventWorkdir),
                    new AcpUpdates.ToolSnapshot(null, "read", List.of(), List.of()));
            assertEquals("read", f.wire.getLast().get("kind")); // captured metadata can correct a default
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AcpUpdates.ToolSnapshot(null, "shell", List.of(), List.of()));
        }
    }

    @Test
    void locationsNeverUseJvmCwdOrParseAmbiguousArguments() {
        try (final var f = new Fixture(Map.of())) {
            f.updates.tool(
                    "relative-unknown",
                    new AgentEvent.ToolStarted("read", "{\"path\":\"a\"}", "s", eventWorkdir),
                    null);
            assertFalse(f.wire.getLast().containsKey("locations"));
            f.updates.tool(
                    "absolute",
                    new AgentEvent.ToolStarted("read", "{\"path\":\"/explicit/a\",\"offset\":0}", "s", eventWorkdir),
                    null);
            assertEquals(
                    List.of(Map.of("path", "/explicit/a")), f.wire.getLast().get("locations"));
            f.updates.tool(
                    "grep",
                    new AgentEvent.ToolStarted("grep", "{\"path\":\"a b\",\"pattern\":\"x\"}", "s", eventWorkdir),
                    new AcpUpdates.ToolSnapshot(Path.of("/root"), null, List.of(), List.of()));
            assertFalse(f.wire.getLast().containsKey("locations"));
            f.updates.tool(
                    "captured",
                    new AgentEvent.ToolStarted("apply_patch", "{\"patch\":\"opaque patch\"}", "s", eventWorkdir),
                    new AcpUpdates.ToolSnapshot(
                            Path.of("/root"),
                            null,
                            List.of(new AcpUpdates.Location(Path.of("/root/a b"), 4294967295L)),
                            List.of()));
            f.assertUpdate(3, """
                    {"sessionUpdate":"tool_call","toolCallId":"captured","title":"apply_patch","name":"apply_patch",
                     "kind":"edit","status":"pending","rawInput":{"patch":"opaque patch"},
                     "locations":[{"path":"/root/a b","line":4294967295}]}
                    """);
            assertThrows(IllegalArgumentException.class, () -> new AcpUpdates.Location(Path.of("relative"), 1L));
            assertThrows(IllegalArgumentException.class, () -> new AcpUpdates.Location(Path.of("/a"), 0L));
            assertThrows(IllegalArgumentException.class, () -> new AcpUpdates.Location(Path.of("/a"), 4294967296L));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AcpUpdates.ToolSnapshot(Path.of("relative"), null, List.of(), List.of()));
        }
    }

    @Test
    void writeAndEditProposalsCannotInventDiffsOrReadExistingFileContents(@TempDir final Path root) throws IOException {
        final var file = root.resolve("existing.txt");
        Files.writeString(file, "private before contents");
        try (final var f = new Fixture(Map.of())) {
            final var snapshot = new AcpUpdates.ToolSnapshot(root, null, List.of(), List.of());
            f.updates.tool(
                    "write",
                    new AgentEvent.ToolStarted(
                            "write_file", "{\"path\":\"existing.txt\",\"content\":\"new\"}", "s", eventWorkdir),
                    snapshot);
            f.updates.tool(
                    "write",
                    new AgentEvent.ToolFinished("write_file", "Wrote 1 lines", true, "s", eventWorkdir),
                    snapshot);
            f.updates.tool(
                    "edit",
                    new AgentEvent.ToolStarted(
                            "edit_file",
                            "{\"path\":\"existing.txt\",\"oldString\":\"before\",\"newString\":\"after\"}",
                            "s",
                            eventWorkdir),
                    snapshot);
            f.updates.tool(
                    "edit",
                    new AgentEvent.ToolFinished("edit_file", "Replaced 1 occurrence", true, "s", eventWorkdir),
                    snapshot);
            final var json = f.mapper.toString(f.wire);
            assertFalse(json.contains("\"type\":\"diff\""));
            assertFalse(json.contains("oldText"));
            assertFalse(json.contains("private before contents"));
            assertEquals("private before contents", Files.readString(file));
            assertTrue(f.wire.getFirst().containsKey("locations"));
            assertThrows(IllegalArgumentException.class, () -> new AcpUpdates.FileEdit(file, false, null, "new"));
            assertThrows(IllegalArgumentException.class, () -> new AcpUpdates.FileEdit(file, true, "existing", "new"));
        }
    }

    @Test
    void onlyExplicitExactCapturedContentsProduceDiffsIncludingRealNewFiles() {
        try (final var f = new Fixture(Map.of())) {
            f.updates.tool(
                    "edit",
                    new AgentEvent.ToolFinished("apply_patch", "Applied", true, "s", eventWorkdir),
                    new AcpUpdates.ToolSnapshot(
                            null,
                            null,
                            List.of(),
                            List.of(
                                    new AcpUpdates.FileEdit(Path.of("/root/existing"), false, "before\n", "after\n"),
                                    new AcpUpdates.FileEdit(Path.of("/root/new"), true, null, "created\n"))));
            f.assertWire("""
                    [{"sessionUpdate":"tool_call","toolCallId":"edit","title":"apply_patch","name":"apply_patch",
                      "kind":"edit","status":"completed","rawOutput":"Applied",
                      "content":[{"type":"content","content":{"type":"text","text":"Applied"}},
                                 {"type":"diff","path":"/root/existing","oldText":"before\\n","newText":"after\\n"},
                                 {"type":"diff","path":"/root/new","oldText":null,"newText":"created\\n"}],
                      "locations":[{"path":"/root/existing"},{"path":"/root/new"}]}]
                    """);
        }
    }

    @Test
    void capturedMutationDiffsSurviveStreamsAndFinalReplacementOutput(@TempDir final Path root) throws IOException {
        final var path = root.resolve("a.txt");
        Files.writeString(path, "\ufeffhead\r\nold\r\ntail\r\n");
        final var result = new EditFileTool()
                .execute(
                        Map.of("path", "a.txt", "oldString", "old", "newString", "new"),
                        ToolContext.empty(root, new DisabledSandbox(root)));
        assertTrue(result.success(), result.output());
        final var id = "externally-supplied:turn-5:child:9";
        try (final var f = new Fixture(Map.of())) {
            f.updates.tool(id, new AgentEvent.ToolStarted("edit_file", "{}", 9, "s", "child", "Worker", root), null);
            f.updates.tool(
                    id, new AgentEvent.ToolStream("edit_file", "preview", 9, "s", "child", "Worker", root), null);
            f.updates.files(
                    id,
                    new AgentEvent.ToolFilesChanged(
                            "edit_file", result.fileMutations(), 9, "s", "child", "Worker", root),
                    UnaryOperator.identity());
            final var diff = Map.of(
                    "type",
                    "diff",
                    "path",
                    path.toString(),
                    "oldText",
                    "\ufeffhead\r\nold\r\ntail\r\n",
                    "newText",
                    Files.readString(path));
            assertEquals(id, f.wire.getLast().get("toolCallId"));
            assertEquals(
                    List.of(Map.of("type", "content", "content", Map.of("type", "text", "text", "preview")), diff),
                    f.wire.getLast().get("content"));
            final var retained = f.mapper.toString(f.wire.getLast());
            f.updates.tool(id, new AgentEvent.ToolStream("edit_file", "+later", 9, "s", "child", "Worker", root), null);
            assertTrue(((List<?>) f.wire.getLast().get("content")).contains(diff));
            f.updates.tool(
                    id,
                    new AgentEvent.ToolFinished(
                            "edit_file", "final output", true, false, 9, "s", "child", "Worker", root),
                    null);
            assertEquals(
                    List.of(Map.of("type", "content", "content", Map.of("type", "text", "text", "final output")), diff),
                    f.wire.getLast().get("content"));
            assertEquals(
                    List.of(Map.of("path", path.toString())), f.wire.getLast().get("locations"));
            assertEquals("final output", f.wire.getLast().get("rawOutput"));
            assertEquals(retained, f.mapper.toString(f.wire.get(2)), "earlier wire maps are immutable snapshots");
            assertEquals(
                    Map.of("yca", Map.of("subagentId", "child", "subagentName", "Worker")),
                    f.wire.getLast().get("_meta"));
            f.updates.endTurn("child", false);
            assertEquals(5, f.wire.size(), "terminal state is evicted, not failed again at turn end");
        }
    }

    @Test
    void creationAndDeletionUseOnlyStableV1ShapesAndLabelDeletedFiles() {
        final var created = Path.of("/root/new.txt");
        final var deleted = Path.of("/root/deleted.txt");
        try (final var f = new Fixture(Map.of())) {
            f.updates.files(
                    "canonical",
                    new AgentEvent.ToolFilesChanged(
                            "apply_patch",
                            List.of(
                                    new FileMutation(
                                            created,
                                            FileMutation.Kind.CREATE,
                                            new FileMutation.Content(null, "created\r\n")),
                                    new FileMutation(
                                            deleted,
                                            FileMutation.Kind.DELETE,
                                            new FileMutation.Content("old\r\n", ""))),
                            4,
                            "s",
                            null,
                            null,
                            eventWorkdir),
                    UnaryOperator.identity());
            f.assertUpdate(0, """
                    {"sessionUpdate":"tool_call","toolCallId":"canonical","title":"apply_patch","name":"apply_patch",
                     "kind":"edit","status":"in_progress","content":[
                       {"type":"diff","path":"/root/new.txt","oldText":null,"newText":"created\\r\\n"},
                       {"type":"diff","path":"/root/deleted.txt","oldText":"old\\r\\n","newText":""},
                       {"type":"content","content":{"type":"text","text":"Deleted /root/deleted.txt."}}],
                     "locations":[{"path":"/root/new.txt"},{"path":"/root/deleted.txt"}]}
                    """);
            f.updates.tool(
                    "canonical", new AgentEvent.ToolFinished("apply_patch", "Applied", true, "s", eventWorkdir), null);
            assertEquals(4, ((List<?>) f.wire.getLast().get("content")).size());
            assertFalse(f.mapper.toString(f.wire).contains("\"deleted\":"));
        }
    }

    @Test
    void failedMultiFilePatchKeepsOnlyCompletedMutations(@TempDir final Path root) throws IOException {
        Files.writeString(root.resolve("blocked"), "not a directory");
        final var result = new ApplyPatchTool(null)
                .execute(Map.of("patch", """
                *** Begin Patch
                *** Add File:done.txt
                +done
                *** Add File:blocked/fail.txt
                +not written
                *** End Patch
                """), ToolContext.empty(root, new DisabledSandbox(root)));
        assertTrue(result.error());
        assertEquals(1, result.fileMutations().size());
        try (final var f = new Fixture(Map.of())) {
            f.updates.tool("patch", new AgentEvent.ToolStarted("apply_patch", "{}", "s", root), null);
            f.updates.files(
                    "patch",
                    new AgentEvent.ToolFilesChanged("apply_patch", result.fileMutations(), -1, "s", null, null, root),
                    UnaryOperator.identity());
            f.updates.tool(
                    "patch", new AgentEvent.ToolFinished("apply_patch", result.output(), false, "s", root), null);
            assertEquals("failed", f.wire.getLast().get("status"));
            assertEquals(
                    List.of(Map.of("path", root.resolve("done.txt").toString())),
                    f.wire.getLast().get("locations"));
            final var diffs = ((List<?>) f.wire.getLast().get("content"))
                    .stream()
                            .filter(c -> c instanceof Map<?, ?> m && "diff".equals(m.get("type")))
                            .toList();
            assertEquals(1, diffs.size());
            assertEquals(root.resolve("done.txt").toString(), ((Map<?, ?>) diffs.getFirst()).get("path"));
        }
    }

    @Test
    void unknownAndRedactedBaselinesFallBackWithoutInventingOrLeakingDiffs() {
        final var unchangedPath = Path.of("/root/visible.txt");
        final var hiddenPath = Path.of("/root/hidden-credential.txt");
        final var files = List.of(
                new FileMutation(
                        unchangedPath,
                        FileMutation.Kind.UPDATE,
                        new FileMutation.Content("private-old-value", "private-new-value")),
                new FileMutation(Path.of("/root/unknown.txt"), FileMutation.Kind.UPDATE, null),
                new FileMutation(hiddenPath, FileMutation.Kind.DELETE, null));
        try (final var f = new Fixture(Map.of())) {
            f.updates.files(
                    "redacted",
                    new AgentEvent.ToolFilesChanged("apply_patch", files, 1, "s", null, null, eventWorkdir),
                    value -> value.replace("private-", "MASKED-").replace("hidden-credential", "REDACTED"));
            assertEquals(
                    List.of(Map.of("path", unchangedPath.toString()), Map.of("path", "/root/unknown.txt")),
                    f.wire.getLast().get("locations"));
            final var wire = f.mapper.toString(f.wire);
            assertFalse(wire.contains("private-"));
            assertFalse(wire.contains("hidden-credential"));
            assertFalse(wire.contains("\"type\":\"diff\""));
            assertFalse(wire.contains("oldText"));
            assertTrue(wire.contains("exact file diff unavailable"));
            assertEquals(
                    "private-old-value", files.getFirst().content().oldText(), "borrowed metadata stays immutable");
            final var count = f.wire.size();
            f.updates.files(
                    "all-redacted",
                    new AgentEvent.ToolFilesChanged(
                            "apply_patch", List.of(files.getLast()), 2, "s", null, null, eventWorkdir),
                    value -> value.replace("hidden-credential", "REDACTED"));
            assertEquals(
                    count,
                    f.wire.size(),
                    "a hidden path must not clear earlier locations/content or invent a different path");
        }
    }

    @Test
    void mutationStateIsSessionLocalAndEvictedOnScopeEndAndClose() {
        final var event = new AgentEvent.ToolFilesChanged(
                "edit_file",
                List.of(new FileMutation(
                        Path.of("/root/a"), FileMutation.Kind.UPDATE, new FileMutation.Content("before", "after"))),
                1,
                "s",
                "child",
                "Worker",
                eventWorkdir);
        try (final var a = new Fixture(Map.of());
                final var b = new Fixture(Map.of())) {
            a.updates.files("id", event, UnaryOperator.identity());
            assertTrue(b.wire.isEmpty());
            a.updates.endTurn(null, false);
            assertEquals(1, a.wire.size(), "parent turn does not evict the child");
            a.updates.endTurn("child", true);
            assertEquals("failed", a.wire.getLast().get("status"));
            assertTrue(a.mapper.toString(a.wire.getLast()).contains("\"type\":\"diff\""));
            a.updates.tool(
                    "id", new AgentEvent.ToolStarted("edit_file", "{}", 1, "s", "child", "Worker", eventWorkdir), null);
            a.updates.tool(
                    "id",
                    new AgentEvent.ToolFinished(
                            "edit_file", "new result", true, false, 1, "s", "child", "Worker", eventWorkdir),
                    null);
            assertFalse(a.mapper.toString(a.wire.getLast()).contains("before"));
            a.updates.close();
            final var count = a.wire.size();
            a.updates.files("id", event, value -> {
                throw new AssertionError("closed projection must not invoke borrowed policy");
            });
            assertEquals(count, a.wire.size());
            assertEquals("\"still usable\"", a.mapper.toString("still usable"));
            b.updates.files(
                    "id",
                    new AgentEvent.ToolFilesChanged(
                            "edit_file", event.files(), 1, "other-session", "child", "Worker", eventWorkdir),
                    value -> {
                        throw new AssertionError("wrong session");
                    });
            assertTrue(b.wire.isEmpty());
            assertThrows(IllegalArgumentException.class, () -> b.updates.event(event));
            assertThrows(IllegalArgumentException.class, () -> b.updates.files("", event, UnaryOperator.identity()));
            assertThrows(
                    IllegalStateException.class,
                    () -> b.updates.files("id", event, value -> {
                        throw new IllegalStateException("blocked by display policy");
                    }));
            assertTrue(b.wire.isEmpty());
        }
    }

    @Test
    void plansAreCompleteStoreSnapshotsWithLegalStatusesAndLabelledCancelledHistory() {
        try (final var f = new Fixture(Map.of())) {
            f.updates.plan(List.of(new TodoItem("Investigate", "pending"), new TodoItem("Obsolete", "cancelled")));
            f.updates.plan(List.of(new TodoItem("Investigate", "in_progress"), new TodoItem("Obsolete", "cancelled")));
            f.updates.plan(List.of(
                    new TodoItem("Investigate", "completed"),
                    new TodoItem("Obsolete", "cancelled"),
                    new TodoItem("Verify", "pending")));
            f.assertUpdate(0, """
                    {"sessionUpdate":"plan","entries":[
                      {"content":"Investigate","priority":"medium","status":"pending"},
                      {"content":"[Cancelled] Obsolete","priority":"medium","status":"completed"}]}
                    """);
            f.assertUpdate(1, """
                    {"sessionUpdate":"plan","entries":[
                      {"content":"Investigate","priority":"medium","status":"in_progress"},
                      {"content":"[Cancelled] Obsolete","priority":"medium","status":"completed"}]}
                    """);
            f.assertUpdate(2, """
                    {"sessionUpdate":"plan","entries":[
                      {"content":"Investigate","priority":"medium","status":"completed"},
                      {"content":"[Cancelled] Obsolete","priority":"medium","status":"completed"},
                      {"content":"Verify","priority":"medium","status":"pending"}]}
                    """);
            f.updates.plan(null);
            assertEquals(3, f.wire.size());
            f.updates.plan(List.of());
            assertEquals(Map.of("sessionUpdate", "plan", "entries", List.of()), f.wire.getLast());
        }
    }

    @Test
    void usageReportsCapturedWindowAndPublishedContextNotCumulativeOrPaddedTokens() {
        try (final var f = new Fixture(ALL)) {
            final var event = usage(
                    new Usage(1000, 200, 1200, 10, 20, 40, 5000, 1000),
                    120,
                    60,
                    new ContextMeasurement.Snapshot(120, 144, "provider-anchored", 100, 20, 1.2));
            f.updates.usage(event, 200000L);
            f.assertWire("""
                    [{"sessionUpdate":"usage_update","used":120,"size":200000,
                      "_meta":{"yca":{"contextSource":"provider-anchored","promptTokens":1000,"completionTokens":200,
                                       "cacheReadTokens":10,"cacheWriteTokens":20}}}]
                    """);
            assertFalse(f.wire.getFirst().containsKey("cost"));
            // A synthetic context refresh can publish a newer gauge than the last measurement.
            f.updates.usage(usage(Usage.EMPTY, 75, 1, event.contextMeasurement()), 200000L);
            f.assertUpdate(1, """
                    {"sessionUpdate":"usage_update","used":75,"size":200000,"_meta":{"yca":{"contextSource":"estimated"}}}
                    """);
        }
    }

    @Test
    void unknownUsageAndWindowsNeverBecomeInventedZerosMaximaOrMoney() {
        try (final var f = new Fixture(Map.of())) {
            f.updates.usage(usage(Usage.EMPTY, 0, 0, null), 1000L);
            f.updates.usage(usage(null, -1, Double.NaN, null), null);
            f.updates.usage(usage(new Usage(-1, -1, -1, -1, -1), -1, 50, null), 1000L);
            assertTrue(f.wire.isEmpty());
            f.updates.usage(usage(new Usage(100, 20, 120), 50, 50, null), null);
            f.assertUpdate(0, """
                    {"sessionUpdate":"agent_message_chunk","content":{"type":"text",
                     "text":"\\n[info] Context: 50 tokens (estimated). Token usage: 100 input 20 output.\\n"}}
                    """);
            f.updates.usage(usage(new Usage(100, 20, 120), 50, 50, null), 0L);
            assertEquals(1, f.wire.size()); // identical meaningful fallback is not spammed
            f.updates.usage(
                    usage(Usage.EMPTY, -1, 0, new ContextMeasurement.Snapshot(0, 300, "heuristic", -1, 0, -1)), 1000L);
            f.assertUpdate(1, """
                    {"sessionUpdate":"usage_update","used":0,"size":1000,"_meta":{"yca":{"contextSource":"heuristic"}}}
                    """);
            final var quota = new AgentEvent.UsageUpdated.Quota(true, true, "Pro", 20, 3600, 1, "hour", "2.50", false);
            f.updates.usage(
                    new AgentEvent.UsageUpdated(
                            Usage.EMPTY, 10, 1, 0, -1, "model", "s", null, null, quota, eventWorkdir),
                    1000L);
            assertFalse(f.wire.getLast().containsKey("cost")); // balance != cumulative spend
            assertFalse(f.mapper.toString(f.wire).contains("USD"));
            f.updates.usage(
                    new AgentEvent.UsageUpdated(
                            Usage.EMPTY, 50, 5, 0, -1, "child-model", "s", "child", "Child", quota, eventWorkdir),
                    1000L);
            assertEquals(3, f.wire.size());
        }
    }

    @Test
    void noticesUseOnlyEmittedPublicTextNeverThrowableOrTurnFailureInference() {
        try (final var f = new Fixture(ALL)) {
            f.updates.event(new AgentEvent.Notice(
                    AgentEvent.Notice.Level.WARN,
                    "Continuing without MCP",
                    "max_turns",
                    "s",
                    null,
                    null,
                    new IllegalStateException("secret cause", new RuntimeException("secret root")),
                    AgentEvent.Notice.CompactionLifecycle.NONE,
                    eventWorkdir));
            f.updates.event(AgentEvent.Notice.error("Recoverable side operation failed", "s", eventWorkdir));
            f.assertWire("""
                    [{"sessionUpdate":"notice","severity":"warning","title":"Continuing without MCP"},
                     {"sessionUpdate":"notice","severity":"error","title":"Recoverable side operation failed"}]
                    """);
            assertFalse(f.mapper.toString(f.wire).contains("secret"));
            assertFalse(f.mapper.toString(f.wire).contains("max_turns"));
            f.updates.event(AgentEvent.Notice.warn("Continuing without MCP", "s", eventWorkdir));
            assertEquals(3, f.wire.size()); // notices are live, not a deduplicated/replay history
        }
    }

    @Test
    void transientStatusAndPauseFallbackIsConciseAndNonduplicating() {
        try (final var f = new Fixture(Map.of())) {
            f.updates.event(new AgentEvent.Status("Working", "s", eventWorkdir));
            f.updates.event(new AgentEvent.Status("Working", "s", eventWorkdir));
            f.updates.event(AgentEvent.Pause.retry("Working", 100, "s", eventWorkdir));
            f.assertWire("""
                    [{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"\\n[info] Working\\n"}}]
                    """);
            f.updates.event(AgentEvent.Pause.throttle("Provider requested a pause", 200, "s", eventWorkdir));
            f.updates.event(AgentEvent.Pause.throttle("Provider requested a pause", 200, "s", eventWorkdir));
            assertEquals(2, f.wire.size());
            f.updates.event(new AgentEvent.TextDelta("Reply", "s", eventWorkdir));
            f.updates.event(new AgentEvent.Status("Working", "s", eventWorkdir));
            assertEquals(4, f.wire.size());
        }
        try (final var f = new Fixture(ALL)) {
            f.updates.event(AgentEvent.Pause.retry("Retrying", 100, "s", eventWorkdir));
            f.updates.event(AgentEvent.Pause.retry("Retrying", 100, "s", eventWorkdir));
            f.assertWire("""
                    [{"sessionUpdate":"notice","severity":"info","title":"Retrying"}]
                    """);
        }
    }

    @Test
    void authoritativePassUsesCoreIdAndNoLegacyEventCanFinishOrDuplicateIt() {
        try (final var f = new Fixture(ALL)) {
            final var start = new CompactionPass(
                    "core-id", CompactionPass.Status.STARTED, null, null, "s", null, null, eventWorkdir);
            f.updates.compaction(start);
            f.updates.compaction(start);
            f.updates.event(AgentEvent.Notice.compactionStart("Legacy indicator", "s", eventWorkdir));
            f.updates.event(AgentEvent.Notice.compacted("First legacy completion", "s", eventWorkdir));
            f.updates.event(AgentEvent.Notice.compacted("Second legacy completion", "s", eventWorkdir));
            f.updates.event(new AgentEvent.Compacted(
                    List.of("prune", "llm"),
                    "SUMMARY",
                    "s",
                    null,
                    null,
                    new AgentEvent.Compacted.CompactionTokens(120, 100),
                    eventWorkdir));
            f.assertUpdate(0, """
                    {"sessionUpdate":"compaction_update","compactionId":"core-id","status":"in_progress"}
                    """);
            assertEquals(1, f.ofType("compaction_update").size());
            final var terminal = new CompactionPass(
                    "core-id",
                    CompactionPass.Status.SUCCEEDED,
                    Mode.SUMMARY,
                    "Context compacted (SUMMARY).",
                    "s",
                    null,
                    null,
                    eventWorkdir);
            f.updates.compaction(terminal);
            f.updates.compaction(terminal);
            f.updates.endTurn(null, true);
            f.assertUpdate(2, """
                    {"sessionUpdate":"compaction_update","compactionId":"core-id","status":"completed",
                     "_meta":{"yca":{"outcome":"SUCCEEDED","mode":"SUMMARY"}}}
                    """);
            assertEquals(3, f.wire.size()); // the real merged reduction remains visible as a notice
            f.updates.event(new AgentEvent.Compacted("prune", "CLEARED", "s", eventWorkdir));
            assertEquals(
                    2,
                    f.ofType("compaction_update").size(),
                    "merged reductions must not manufacture a compaction entity");
            assertTrue(f.ofType("compaction_summary_chunk").isEmpty());
            assertTrue(f.wire.stream()
                    .noneMatch(u -> u.containsKey("summary")
                            || u.containsKey("cost")
                            || f.mapper.toString(u).contains("estimatedTokensBefore")));
        }
    }

    @Test
    void exactStableStatusesDistinguishSkippedAndWarningsDoNotEndPasses() {
        try (final var f = new Fixture(ALL)) {
            for (final var status : List.of(
                    CompactionPass.Status.SKIPPED,
                    CompactionPass.Status.FAILED,
                    CompactionPass.Status.CANCELLED,
                    CompactionPass.Status.SUCCEEDED)) {
                final var id = status.name();
                f.updates.compaction(new CompactionPass(
                        id, CompactionPass.Status.STARTED, null, null, "s", null, null, eventWorkdir));
                final var before = f.ofType("compaction_update").size();
                f.updates.event(AgentEvent.Notice.warn("Retrying with smaller chunks", "s", eventWorkdir));
                f.updates.event(
                        AgentEvent.Notice.compactionGateEscalated("Context still too large", "s", eventWorkdir));
                assertEquals(before, f.ofType("compaction_update").size());
                f.updates.compaction(
                        new CompactionPass(id, status, Mode.NONE, "Safe result detail", "s", null, null, eventWorkdir));
                final var expected = new LinkedHashMap<String, Object>(Map.of(
                        "sessionUpdate",
                        "compaction_update",
                        "compactionId",
                        id,
                        "status",
                        status == CompactionPass.Status.FAILED
                                ? "failed"
                                : status == CompactionPass.Status.CANCELLED ? "cancelled" : "completed",
                        "_meta",
                        Map.of("yca", Map.of("outcome", status.name(), "mode", "NONE"))));
                if (status == CompactionPass.Status.FAILED) {
                    expected.put("error", "Safe result detail");
                }
                assertEquals(expected, f.ofType("compaction_update").getLast());
                final var count = f.wire.size();
                f.updates.endTurn(null, false);
                assertEquals(count, f.wire.size());
            }
        }
        try (final var f = new Fixture(Map.of())) {
            f.updates.compaction(new CompactionPass(
                    "noop", CompactionPass.Status.STARTED, null, null, "s", "child", "Worker", eventWorkdir));
            f.updates.compaction(new CompactionPass(
                    "noop",
                    CompactionPass.Status.SKIPPED,
                    Mode.NONE,
                    "Compaction skipped; nothing to reclaim.",
                    "s",
                    "child",
                    "Worker",
                    eventWorkdir));
            f.assertUpdate(1, """
                    {"sessionUpdate":"agent_message_chunk","content":{"type":"text",
                     "text":"\\n[info] [Worker] Compaction skipped; nothing to reclaim.\\n"}}
                    """);
            assertFalse(f.mapper.toString(f.wire).contains("compaction_update"));
        }
    }

    @Test
    void overlappingIdsScopesSessionsAndLateDuplicatesDoNotCrossComplete() {
        try (final var f = new Fixture(ALL)) {
            for (final var id : List.of("main-1", "main-2", "child")) {
                f.updates.compaction(new CompactionPass(
                        id,
                        CompactionPass.Status.STARTED,
                        null,
                        null,
                        "s",
                        "child".equals(id) ? "child" : null,
                        "child".equals(id) ? "Worker" : null,
                        eventWorkdir));
            }
            f.updates.compaction(new CompactionPass(
                    "main-1",
                    CompactionPass.Status.SUCCEEDED,
                    Mode.SUMMARY,
                    "Wrong session",
                    "B",
                    null,
                    null,
                    eventWorkdir));
            f.updates.compaction(new CompactionPass(
                    "main-1",
                    CompactionPass.Status.SUCCEEDED,
                    Mode.SUMMARY,
                    "Wrong scope",
                    "s",
                    "child",
                    "Worker",
                    eventWorkdir));
            f.updates.compaction(new CompactionPass(
                    "main-1",
                    CompactionPass.Status.SUCCEEDED,
                    Mode.SUMMARY,
                    "Wrong root",
                    "s",
                    null,
                    null,
                    eventWorkdir.resolve("other")));
            assertEquals(3, f.wire.size());
            f.updates.compaction(new CompactionPass(
                    "child",
                    CompactionPass.Status.SUCCEEDED,
                    Mode.TRUNCATE,
                    "Context compacted (TRUNCATE).",
                    "s",
                    "child",
                    "Worker",
                    eventWorkdir));
            f.assertUpdate(3, """
                    {"sessionUpdate":"compaction_update","compactionId":"child","status":"completed",
                     "_meta":{"yca":{"subagentId":"child","subagentName":"Worker","outcome":"SUCCEEDED","mode":"TRUNCATE"}}}
                    """);
            f.updates.compaction(new CompactionPass(
                    "main-1",
                    CompactionPass.Status.SKIPPED,
                    Mode.NONE,
                    "Nothing to reclaim",
                    "s",
                    null,
                    null,
                    eventWorkdir));
            f.updates.endTurn(null, true);
            assertEquals("main-2", f.wire.getLast().get("compactionId"));
            assertEquals("cancelled", f.wire.getLast().get("status"));
            final var count = f.wire.size();
            f.updates.compaction(new CompactionPass(
                    "main-1",
                    CompactionPass.Status.FAILED,
                    Mode.NONE,
                    "Late duplicate",
                    "s",
                    null,
                    null,
                    eventWorkdir));
            f.updates.endTurn("child", false);
            f.updates.endTurn(null, false);
            assertEquals(count, f.wire.size());
        }
    }

    @Test
    void completionCallbackCanStartTheNextPassWithoutOldCleanupRemovingIt() {
        try (final var mapper = new JsonMapperImpl(List.of(), key -> Optional.empty())) {
            final var wire = new ArrayList<Map<String, Object>>();
            final var holder = new AtomicReference<AcpUpdates>();
            final var startedNext = new AtomicBoolean();
            try (final var updates = new AcpUpdates("s", mapper, ALL, update -> {
                wire.add(update);
                if ("compaction_update".equals(update.get("sessionUpdate"))
                        && "completed".equals(update.get("status"))
                        && startedNext.compareAndSet(false, true)) {
                    holder.get()
                            .compaction(new CompactionPass(
                                    "next", CompactionPass.Status.STARTED, null, null, "s", null, null, eventWorkdir));
                }
            })) {
                holder.set(updates);
                updates.compaction(new CompactionPass(
                        "first", CompactionPass.Status.STARTED, null, null, "s", null, null, eventWorkdir));
                updates.compaction(new CompactionPass(
                        "first",
                        CompactionPass.Status.SUCCEEDED,
                        Mode.SUMMARY,
                        "Context compacted",
                        "s",
                        null,
                        null,
                        eventWorkdir));
                updates.endTurn(null, false);
                assertEquals("next", wire.getLast().get("compactionId"));
                assertEquals("failed", wire.getLast().get("status"));
                assertEquals(
                        "Compaction outcome was not reported before the turn ended.",
                        wire.getLast().get("error"));
            }
        }
    }

    @Test
    void closeHasTruthfulTextOrNoticeFallbackAndCannotRetainLatePasses() {
        for (final var capabilities :
                List.of(Map.<String, Object>of(), Map.of("session", Map.of("notices", Map.of())))) {
            try (final var f = new Fixture(capabilities)) {
                f.updates.compaction(new CompactionPass(
                        "child-pass", CompactionPass.Status.STARTED, null, null, "s", "child", "Worker", eventWorkdir));
                f.updates.close();
                assertEquals(2, f.wire.size());
                assertTrue(f.mapper.toString(f.wire.getLast()).contains("Compaction cancelled; session closed."));
                if (AcpUpdates.supports(capabilities, "notices")) {
                    assertEquals(
                            Map.of(
                                    "sessionUpdate",
                                    "notice",
                                    "severity",
                                    "info",
                                    "title",
                                    "[Worker] Compaction cancelled; session closed.",
                                    "_meta",
                                    Map.of("yca", Map.of("subagentId", "child", "subagentName", "Worker"))),
                            f.wire.getLast());
                }
                f.updates.compaction(new CompactionPass(
                        "late", CompactionPass.Status.STARTED, null, null, "s", null, null, eventWorkdir));
                f.updates.endTurn("child", false);
                f.updates.close();
                assertEquals(2, f.wire.size());
            }
        }
    }

    @Test
    void endingScopeTerminatesOnlyUnfinishedToolsAndCloseCannotReplayLiveUpdates() {
        try (final var f = new Fixture(ALL)) {
            f.updates.tool("main", new AgentEvent.ToolStarted("bash", "{}", "s", eventWorkdir), null);
            f.updates.tool(
                    "child", new AgentEvent.ToolStarted("read", "{}", 1, "s", "child", "Child", eventWorkdir), null);
            f.updates.endTurn(null, true);
            assertEquals("main", f.wire.getLast().get("toolCallId"));
            assertEquals("failed", f.wire.getLast().get("status"));
            assertTrue(f.mapper.toString(f.wire.getLast()).contains("interrupted before a final result"));
            f.updates.tool(
                    "child",
                    new AgentEvent.ToolFinished(
                            "read", "child done", true, false, 1, "s", "child", "Child", eventWorkdir),
                    null);
            f.updates.event(new AgentEvent.TurnDone("not a second copy of streamed content", "s", eventWorkdir));
            final var count = f.wire.size();
            f.updates.compaction(new CompactionPass(
                    "unfinished", CompactionPass.Status.STARTED, null, null, "s", null, null, eventWorkdir));
            f.updates.tool(
                    "pending",
                    new AgentEvent.ToolFinished(
                            "check_subagents", "Waiting", false, true, 3, "s", null, null, eventWorkdir),
                    null);
            assertTrue(f.wire.size() > count);
            final var beforeClose = f.wire.size();
            f.updates.close();
            assertEquals(beforeClose + 1, f.wire.size());
            assertEquals(
                    Map.of(
                            "sessionUpdate",
                            "compaction_update",
                            "compactionId",
                            "unfinished",
                            "status",
                            "cancelled",
                            "_meta",
                            Map.of("yca", Map.of("outcome", "CANCELLED"))),
                    f.wire.getLast());
            f.updates.close();
            f.updates.endTurn(null, false);
            f.updates.plan(List.of(new TodoItem("Must not replay", "pending")));
            f.updates.event(AgentEvent.Notice.warn("Must not replay", "s", eventWorkdir));
            f.updates.tool("late", new AgentEvent.ToolStarted("read", "{}", "s", eventWorkdir), null);
            f.updates.usage(usage(new Usage(1, 1, 2), 1, 1, null), 100L);
            f.updates.compaction(new CompactionPass(
                    "unfinished",
                    CompactionPass.Status.FAILED,
                    Mode.NONE,
                    "Must not replay",
                    "s",
                    null,
                    null,
                    eventWorkdir));
            assertEquals(beforeClose + 1, f.wire.size());
            assertEquals("{}", f.mapper.toString(Map.of())); // borrowed mapper is not closed
        }
    }

    @Test
    void onlyExistingMainThoughtDeltasAreProjectedAndOtherSessionsAreIgnored() {
        try (final var f = new Fixture(ALL)) {
            f.updates.event(new AgentEvent.ThinkingDelta("already public thought", "s", eventWorkdir));
            f.updates.event(new AgentEvent.TextDelta("visible reply", "s", eventWorkdir));
            f.updates.event(new AgentEvent.ThinkingDelta("child private stream", "s", "child", "Child", eventWorkdir));
            f.updates.event(new AgentEvent.TextDelta("child stream", "s", "child", "Child", eventWorkdir));
            f.updates.event(new AgentEvent.TurnDone("hidden extras must not be inferred", "s", eventWorkdir));
            f.updates.event(AgentEvent.Notice.error("another session", "other", eventWorkdir));
            f.updates.tool("other-tool", new AgentEvent.ToolStarted("read", "{}", "other", eventWorkdir), null);
            f.updates.usage(usage(new Usage(1, 2, 3), 10, 10, null), null);
            f.assertUpdate(0, """
                    {"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"already public thought"}}
                    """);
            f.assertUpdate(1, """
                    {"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"visible reply"}}
                    """);
            assertFalse(f.mapper.toString(f.wire).contains("hidden extras"));
            assertFalse(f.mapper.toString(f.wire).contains("child private"));
            assertFalse(f.mapper.toString(f.wire).contains("another session"));
            assertEquals(3, f.wire.size());
        }
    }

    private static AgentEvent.UsageUpdated usage(
            final Usage usage, final int tokens, final double percent, final ContextMeasurement.Snapshot measurement) {
        return new AgentEvent.UsageUpdated(
                usage,
                tokens,
                percent,
                0,
                -1,
                "model",
                "s",
                null,
                null,
                AgentEvent.UsageUpdated.Quota.NONE,
                measurement,
                eventWorkdir);
    }

    private static final class Fixture implements AutoCloseable {
        private final JsonMapperImpl mapper = new JsonMapperImpl(List.of(), key -> Optional.empty());
        private final List<Map<String, Object>> wire = new ArrayList<>();
        private final AcpUpdates updates;

        private Fixture(final Map<String, ?> capabilities) {
            updates = new AcpUpdates("s", mapper, capabilities, wire::add);
        }

        private List<Map<String, Object>> ofType(final String type) {
            return wire.stream()
                    .filter(u -> type.equals(u.get("sessionUpdate")))
                    .toList();
        }

        private void assertWire(final String json) {
            assertEquals(mapper.fromString(Object.class, json), mapper.fromString(Object.class, mapper.toString(wire)));
        }

        private void assertUpdate(final int index, final String json) {
            assertEquals(
                    mapper.fromString(Object.class, json),
                    mapper.fromString(Object.class, mapper.toString(wire.get(index))));
        }

        @Override
        public void close() {
            updates.close();
            mapper.close();
        }
    }
}
