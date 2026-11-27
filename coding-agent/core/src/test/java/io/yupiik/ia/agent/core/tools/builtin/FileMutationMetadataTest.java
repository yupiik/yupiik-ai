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
package io.yupiik.ia.agent.core.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.optimize.GenericOutputProcessor;
import io.yupiik.ia.agent.core.runtime.Cancellations;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.security.ToolExecutionPrepare;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.FileMutation;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@FusionSupport(modules = FileMutationMetadataTest.OptimizationModule.class)
class FileMutationMetadataTest {
    private CodingAgentConfiguration configuration;

    @BeforeEach
    void configuration(@Fusion final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
    }

    @TempDir
    Path root;

    @Test
    void newWritesAreExactButOverwritesNeverInventAnOldFile() throws Exception {
        final var path = root.resolve("same.txt");
        final var bus = new RecordingEmitter();
        final var service = service(bus);
        final var tool = new WriteFileTool();
        final var created = service.execute(
                tool, Map.of("path", "same.txt", "content", "\ufeffnew\r\n"), context("a", null, root, 7));
        assertTrue(created.success(), created.output());
        assertEquals(
                new FileMutation(path, FileMutation.Kind.CREATE, new FileMutation.Content(null, "\ufeffnew\r\n")),
                created.fileMutations().getFirst());
        assertEquals("\ufeffnew\r\n", Files.readString(path));
        assertFalse(created.output().contains("\ufeffnew"), "UI data is separate from model-readable output");

        final var overwritten = service.execute(
                tool, Map.of("path", "same.txt", "content", "replacement"), context("a", null, root, 8));
        assertTrue(overwritten.success(), overwritten.output());
        assertEquals(
                new FileMutation(path, FileMutation.Kind.UPDATE, null),
                overwritten.fileMutations().getFirst());
        assertEquals("replacement", Files.readString(path));
        assertEquals(2, bus.fileEvents().size());
        assertEquals(7, bus.fileEvents().getFirst().ordinal());
        assertEquals(8, bus.fileEvents().getLast().ordinal());
    }

    @Test
    void sameRelativeNamesAndOrdinalsNeverShareRootsOrBaselines() throws Exception {
        final var a = Files.createDirectory(root.resolve("a"));
        final var b = Files.createDirectory(root.resolve("b"));
        Files.writeString(a.resolve("same.txt"), "A before\n");
        Files.writeString(b.resolve("same.txt"), "B before\n");
        final var bus = new RecordingEmitter();
        final var service = service(bus);
        final var tool = new EditFileTool();
        final var arguments = Map.<String, Object>of("path", "same.txt", "oldString", "before", "newString", "after");
        final var first = service.execute(tool, arguments, context("session-a", null, a, 1));
        final var second = service.execute(tool, arguments, context("session-b", "child-b", b, 1));
        assertTrue(first.success(), first.output());
        assertTrue(second.success(), second.output());
        assertEquals("A before\n", first.fileMutations().getFirst().content().oldText());
        assertEquals("B before\n", second.fileMutations().getFirst().content().oldText());
        assertEquals(a.resolve("same.txt"), first.fileMutations().getFirst().path());
        assertEquals(b.resolve("same.txt"), second.fileMutations().getFirst().path());
        final var events = bus.fileEvents();
        assertEquals("session-a", events.getFirst().sessionId());
        assertEquals(a, events.getFirst().workdir());
        assertNull(events.getFirst().subagentId());
        assertEquals("session-b", events.getLast().sessionId());
        assertEquals("child-b", events.getLast().subagentId());
        assertEquals("Worker", events.getLast().subagentName());
        assertEquals(b, events.getLast().workdir());
    }

    @Test
    void editCapturesWholeTextIncludingUnchangedSurroundingsBomAndCrLf() throws Exception {
        final var before = "\ufeffhead\r\nold\r\ntail\r\n";
        final var path = root.resolve("edit.txt");
        Files.writeString(path, before);
        final var result = new EditFileTool()
                .execute(
                        Map.of("path", "edit.txt", "oldString", "old", "newString", "new"),
                        ToolContext.empty(root, new DisabledSandbox(root)));
        assertTrue(result.success(), result.output());
        final var content = result.fileMutations().getFirst().content();
        assertEquals(before, content.oldText());
        assertEquals("\ufeffhead\r\nnew\r\ntail\r\n", content.newText());
        assertEquals(Files.readString(path), content.newText());
    }

    @Test
    void absoluteSymlinkDotDotLocationsKeepTheActualAuthorizedPath() throws Exception {
        final var destination = Files.createDirectories(root.resolve("destination/child"));
        Files.createSymbolicLink(root.resolve("link"), destination);
        final var path = root.resolve("link/../a.txt");
        Files.writeString(root.resolve("destination/a.txt"), "old\n");
        Files.writeString(root.resolve("a.txt"), "must stay unchanged\n");
        final var result = new EditFileTool()
                .execute(
                        Map.of("path", path.toString(), "oldString", "old", "newString", "new"),
                        ToolContext.empty(root, new DisabledSandbox(root)));
        assertTrue(result.success(), result.output());
        assertEquals(path, result.fileMutations().getFirst().path());
        assertEquals("new\n", Files.readString(root.resolve("destination/a.txt")));
        assertEquals("must stay unchanged\n", Files.readString(root.resolve("a.txt")));
        assertEquals("new\n", result.fileMutations().getFirst().content().newText());
    }

    @Test
    void patchReportsRealMultiFileCreateUpdateDeleteAndMoveBoundaries() throws Exception {
        Files.writeString(root.resolve("edit.txt"), "\ufeffold\r\nunchanged\r\n");
        Files.writeString(root.resolve("delete.txt"), "not otherwise read\n");
        Files.writeString(root.resolve("move.txt"), "\ufeffsource\r\n");
        final var result = patch("""
                *** Begin Patch
                *** Update File:edit.txt
                @@
                -old
                +new
                 unchanged
                *** Add File:created.txt
                +created
                *** Delete File:delete.txt
                *** Update File:move.txt
                *** Move to:moved.txt
                @@
                -source
                +destination
                *** End Patch
                """);
        assertTrue(result.success(), result.output());
        final var mutations = result.fileMutations();
        assertEquals(5, mutations.size());
        assertEquals(
                new FileMutation(
                        root.resolve("edit.txt"),
                        FileMutation.Kind.UPDATE,
                        new FileMutation.Content("\ufeffold\r\nunchanged\r\n", "\ufeffnew\nunchanged\n")),
                mutations.get(0));
        assertEquals(
                new FileMutation(
                        root.resolve("created.txt"),
                        FileMutation.Kind.CREATE,
                        new FileMutation.Content(null, "created\n")),
                mutations.get(1));
        assertEquals(new FileMutation(root.resolve("delete.txt"), FileMutation.Kind.DELETE, null), mutations.get(2));
        assertEquals(
                new FileMutation(
                        root.resolve("moved.txt"),
                        FileMutation.Kind.CREATE,
                        new FileMutation.Content(null, "\ufeffdestination\n")),
                mutations.get(3));
        assertEquals(
                new FileMutation(
                        root.resolve("move.txt"),
                        FileMutation.Kind.DELETE,
                        new FileMutation.Content("\ufeffsource\r\n", "")),
                mutations.get(4));
        assertFalse(Files.exists(root.resolve("move.txt")));
        assertFalse(Files.exists(root.resolve("delete.txt")));
        for (final var mutation : mutations) {
            if (mutation.kind() != FileMutation.Kind.DELETE) {
                assertEquals(
                        Files.readString(mutation.path()), mutation.content().newText());
            }
        }
    }

    @Test
    void patchAddAndMoveOverwritesUseLocationFallbackNotNewFileDiffs() throws Exception {
        Files.writeString(root.resolve("add.txt"), "unread add baseline");
        Files.writeString(root.resolve("source.txt"), "source\n");
        Files.writeString(root.resolve("target.txt"), "unread destination baseline");
        final var result = patch("""
                *** Begin Patch
                *** Add File:add.txt
                +added
                *** Update File:source.txt
                *** Move to:target.txt
                @@
                -source
                +moved
                *** End Patch
                """);
        assertTrue(result.success(), result.output());
        assertEquals(3, result.fileMutations().size());
        assertEquals(
                new FileMutation(root.resolve("add.txt"), FileMutation.Kind.UPDATE, null),
                result.fileMutations().get(0));
        assertEquals(
                new FileMutation(root.resolve("target.txt"), FileMutation.Kind.UPDATE, null),
                result.fileMutations().get(1));
        assertEquals(FileMutation.Kind.DELETE, result.fileMutations().get(2).kind());
        assertEquals("added\n", Files.readString(root.resolve("add.txt")));
        assertEquals("moved\n", Files.readString(root.resolve("target.txt")));
    }

    @Test
    void invalidPatchAndNoOpsDoNotClaimMutations() throws Exception {
        Files.writeString(root.resolve("a.txt"), "old\n");
        final var invalid = patch("""
                *** Begin Patch
                *** Update File:a.txt
                @@
                -old
                +new
                *** Update File:missing.txt
                @@
                -missing
                +new
                *** End Patch
                """);
        assertTrue(invalid.error());
        assertTrue(invalid.fileMutations().isEmpty());
        assertEquals("old\n", Files.readString(root.resolve("a.txt")));
        final var edit = new EditFileTool()
                .execute(
                        Map.of("path", "a.txt", "oldString", "old", "newString", "old"),
                        ToolContext.empty(root, new DisabledSandbox(root)));
        assertTrue(edit.success(), edit.output());
        assertTrue(edit.fileMutations().isEmpty());
        final var unchanged = patch("""
                *** Begin Patch
                *** Update File:a.txt
                @@
                 old
                *** Delete File:absent.txt
                *** End Patch
                """);
        assertTrue(unchanged.success(), unchanged.output());
        assertTrue(unchanged.fileMutations().isEmpty());
    }

    @Test
    void completedWritesSurviveALaterIoFailureAndStillEmitOnError() throws Exception {
        Files.writeString(root.resolve("blocked"), "a file, not a directory");
        final var bus = new RecordingEmitter();
        final var result =
                service(bus).execute(new ApplyPatchTool(), Map.of("patch", """
                *** Begin Patch
                *** Add File:completed.txt
                +written
                *** Add File:blocked/fails.txt
                +not written
                *** Add File:never.txt
                +not attempted
                *** End Patch
                """), context("s", null, root, 22));
        assertTrue(result.error(), result.output());
        assertEquals(
                List.of(new FileMutation(
                        root.resolve("completed.txt"),
                        FileMutation.Kind.CREATE,
                        new FileMutation.Content(null, "written\n"))),
                result.fileMutations());
        assertEquals("written\n", Files.readString(root.resolve("completed.txt")));
        assertFalse(Files.exists(root.resolve("never.txt")));
        assertEquals(1, bus.fileEvents().size());
        assertEquals(result.fileMutations(), bus.fileEvents().getFirst().files());
        assertEquals(22, bus.fileEvents().getFirst().ordinal());
    }

    @Test
    void aMoveReportsItsWrittenDestinationEvenWhenSourceDeletionFails() throws Exception {
        Files.writeString(root.resolve("source.txt"), "old\n");
        // Phase one reads source.txt as a file. Earlier phase-two operations deterministically replace
        // it by a nonempty directory, so the move writes its destination and then cannot delete source.
        final var result = patch("""
                *** Begin Patch
                *** Delete File:source.txt
                *** Add File:source.txt/child
                +retained
                *** Update File:source.txt
                *** Move to:destination.txt
                @@
                -old
                +new
                *** End Patch
                """);
        assertTrue(result.error(), result.output());
        assertEquals(3, result.fileMutations().size());
        assertEquals(FileMutation.Kind.DELETE, result.fileMutations().get(0).kind());
        assertEquals(
                root.resolve("source.txt/child"), result.fileMutations().get(1).path());
        assertEquals(
                new FileMutation(
                        root.resolve("destination.txt"),
                        FileMutation.Kind.CREATE,
                        new FileMutation.Content(null, "new\n")),
                result.fileMutations().get(2));
        assertEquals("new\n", Files.readString(root.resolve("destination.txt")));
        assertTrue(Files.isDirectory(root.resolve("source.txt")));
    }

    @Test
    void binaryUnknownAndOversizeTextUseFallbackWithoutTruncation() throws Exception {
        Files.write(root.resolve("binary.txt"), new byte[] {'o', 'l', 'd', '\n', (byte) 0xff, '\n'});
        final var binary = patch("""
                *** Begin Patch
                *** Update File:binary.txt
                @@
                -old
                +new
                *** End Patch
                """);
        assertTrue(binary.success(), binary.output());
        assertNull(binary.fileMutations().getFirst().content());
        Files.writeString(root.resolve("nul.txt"), "old\u0000\n");
        final var nul = new EditFileTool()
                .execute(
                        Map.of("path", "nul.txt", "oldString", "old", "newString", "new"),
                        ToolContext.empty(root, new DisabledSandbox(root)));
        assertTrue(nul.success(), nul.output());
        assertNull(nul.fileMutations().getFirst().content());
        final var big = "old" + "x".repeat(256 * 1024);
        Files.writeString(root.resolve("big.txt"), big);
        final var large = new EditFileTool()
                .execute(
                        Map.of("path", "big.txt", "oldString", "old", "newString", "new"),
                        ToolContext.empty(root, new DisabledSandbox(root)));
        assertTrue(large.success(), large.output());
        assertNull(large.fileMutations().getFirst().content());
        final var created = new WriteFileTool()
                .execute(
                        Map.of("path", "large-new.txt", "content", big),
                        ToolContext.empty(root, new DisabledSandbox(root)));
        assertTrue(created.success(), created.output());
        assertEquals(
                FileMutation.Kind.CREATE, created.fileMutations().getFirst().kind());
        assertNull(created.fileMutations().getFirst().content());
        final var unknown = new WriteFileTool()
                .execute(
                        Map.of("path", "binary.txt", "content", "safe"),
                        ToolContext.empty(root, new DisabledSandbox(root)));
        assertTrue(unknown.success(), unknown.output());
        assertNull(unknown.fileMutations().getFirst().content());
    }

    @Test
    void metadataTextBudgetIsBoundedAcrossTheWholePatch() throws Exception {
        final var before = "old\n" + "x".repeat(128 * 1024) + "\n";
        final var patch = new StringBuilder("*** Begin Patch\n");
        for (var i = 0; i < 6; i++) {
            Files.writeString(root.resolve(i + ".txt"), before);
            patch.append("*** Update File:").append(i).append(".txt\n@@\n-old\n+new\n");
        }
        patch.append("*** End Patch\n");
        final var result = patch(patch.toString());
        assertTrue(result.success(), result.output());
        assertEquals(6, result.fileMutations().size());
        var retained = 0;
        var fallbacks = 0;
        for (final var file : result.fileMutations()) {
            if (file.content() == null) {
                fallbacks++;
            } else {
                assertEquals(before, file.content().oldText());
                assertEquals(Files.readString(file.path()), file.content().newText());
                retained += file.content().oldText().length()
                        + file.content().newText().length();
            }
        }
        assertTrue(retained > 0 && retained <= 1024 * 1024);
        assertTrue(fallbacks > 0);
    }

    @Test
    void repeatedPatchPathsNeverReuseStalePhaseOneBeforeText() throws Exception {
        Files.writeString(root.resolve("a.txt"), "old\n");
        final var result = patch("""
                *** Begin Patch
                *** Update File:a.txt
                @@
                -old
                +new
                *** Update File:a.txt
                @@
                 old
                *** End Patch
                """);
        assertTrue(result.success(), result.output());
        assertEquals(2, result.fileMutations().size());
        assertNotNull(result.fileMutations().getFirst().content());
        assertNull(result.fileMutations().getLast().content());
        assertEquals("old\n", Files.readString(root.resolve("a.txt")));
    }

    @Test
    void hardLinkAliasesCannotReuseAnEarlierPhaseOneBaseline() throws Exception {
        Files.writeString(root.resolve("a.txt"), "old\n");
        Files.createLink(root.resolve("alias.txt"), root.resolve("a.txt"));
        final var result = patch("""
                *** Begin Patch
                *** Update File:a.txt
                @@
                -old
                +first
                *** Update File:alias.txt
                @@
                -old
                +second
                *** End Patch
                """);
        assertTrue(result.success(), result.output());
        assertEquals(2, result.fileMutations().size());
        assertNotNull(result.fileMutations().getFirst().content());
        assertNull(result.fileMutations().getLast().content());
        assertEquals("second\n", Files.readString(root.resolve("a.txt")));
    }

    @Test
    void containmentAndPrepareDenialsAndFailedEditsProduceNoMutationEvent() throws Exception {
        final var outside = Files.createDirectory(root.resolve("outside"));
        final var inside = Files.createDirectory(root.resolve("inside"));
        Files.writeString(outside.resolve("a.txt"), "old\n");
        Files.createSymbolicLink(inside.resolve("link"), outside);
        final var bus = new RecordingEmitter();
        final var service = service(bus);
        final var context = context("s", null, inside, 1);
        assertTrue(service.execute(new WriteFileTool(), Map.of("path", "link/a.txt", "content", "bad"), context)
                .error());
        assertTrue(service.execute(
                        new EditFileTool(),
                        Map.of("path", "link/a.txt", "oldString", "old", "newString", "bad"),
                        context)
                .error());
        assertTrue(service.execute(new ApplyPatchTool(), Map.of("patch", """
                *** Begin Patch
                *** Update File:link/a.txt
                @@
                -old
                +bad
                *** End Patch
                """), context)
                .error());
        assertEquals("old\n", Files.readString(outside.resolve("a.txt")));
        assertTrue(bus.fileEvents().isEmpty());

        Files.writeString(inside.resolve("a.txt"), "old\n");
        final var missing = service.execute(
                new EditFileTool(), Map.of("path", "a.txt", "oldString", "absent", "newString", "bad"), context);
        assertTrue(missing.error());
        assertTrue(missing.fileMutations().isEmpty());
        bus.denyPrepare = true;
        final var denied = service.execute(new WriteFileTool(), Map.of("path", "a.txt", "content", "bad"), context);
        assertTrue(denied.error());
        assertTrue(denied.fileMutations().isEmpty());
        assertEquals("old\n", Files.readString(inside.resolve("a.txt")));
        assertTrue(bus.fileEvents().isEmpty());
    }

    @Test
    void optimizationAndThrowingObserversCannotLoseMetadataOrFailACompletedWrite() throws Exception {
        Files.writeString(root.resolve("a.txt"), "old\n");
        final var bus = new RecordingEmitter();
        bus.failFileObserver = true;
        final EmbeddedTool tool = new EditFileTool() {
            @Override
            public String name() {
                return "bash"; // Exercise the existing bash-only output optimizer with a real mutation.
            }

            @Override
            public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                final var result = super.execute(args, context);
                return new ToolResult(result.status(), "line\n".repeat(100), result.fileMutations());
            }
        };
        final var result = service(bus)
                .execute(
                        tool,
                        Map.of("path", "a.txt", "oldString", "old", "newString", "new"),
                        context("s", "child", root, 42));
        assertTrue(result.success(), result.output());
        assertTrue(result.output().length() < "line\n".repeat(100).length());
        assertEquals("new\n", Files.readString(root.resolve("a.txt")));
        assertEquals(1, bus.fileEvents().size());
        assertEquals(result.fileMutations(), bus.fileEvents().getFirst().files());
        assertEquals("old\n", result.fileMutations().getFirst().content().oldText());
        assertTrue(bus.events.stream()
                .noneMatch(e -> e instanceof AgentEvent.ToolStarted || e instanceof AgentEvent.ToolFinished));
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.fileMutations().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> bus.fileEvents().getFirst().files().clear());
    }

    @Test
    void ordinaryResultsRemainOrdinaryAndEventWorkdirIsRequired() {
        assertTrue(ToolResult.ok("ok").fileMutations().isEmpty());
        assertTrue(ToolResult.pending("pending").fileMutations().isEmpty());
        assertTrue(ToolResult.error("error").fileMutations().isEmpty());
        assertTrue(new ToolResult(true, "ok").fileMutations().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> new FileMutation(
                        root.resolve("a"), FileMutation.Kind.UPDATE, new FileMutation.Content(null, "new")));
        assertThrows(
                NullPointerException.class,
                () -> new AgentEvent.ToolFilesChanged("edit_file", List.of(), 1, "s", null, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentEvent.ToolFilesChanged("edit_file", List.of(), 1, "s", null, null, Path.of("relative")));
    }

    private ToolResult patch(final String patch) {
        return new ApplyPatchTool().execute(Map.of("patch", patch), ToolContext.empty(root, new DisabledSandbox(root)));
    }

    private ToolExecutionService service(final RecordingEmitter bus) {
        return new ToolExecutionService(configuration, bus, List.of(new GenericOutputProcessor(configuration)));
    }

    private ToolContext context(final String session, final String child, final Path workdir, final long ordinal) {
        return new ToolContext(
                session,
                child,
                child == null ? null : "Worker",
                null,
                null,
                null,
                null,
                null,
                null,
                Cancellations.NEVER,
                null,
                null,
                workdir,
                new DisabledSandbox(workdir),
                ordinal,
                null,
                null,
                null);
    }

    private static final class RecordingEmitter implements Emitter {
        private final List<Object> events = new ArrayList<>();
        private boolean denyPrepare;
        private boolean failFileObserver;

        @Override
        public <T> void emit(final T event) {
            events.add(event);
            if (denyPrepare && event instanceof ToolExecutionPrepare) {
                throw new SecurityException("denied");
            }
            if (failFileObserver && event instanceof AgentEvent.ToolFilesChanged) {
                throw new IllegalStateException("observer failure must not escape");
            }
        }

        private List<AgentEvent.ToolFilesChanged> fileEvents() {
            return events.stream()
                    .filter(AgentEvent.ToolFilesChanged.class::isInstance)
                    .map(AgentEvent.ToolFilesChanged.class::cast)
                    .toList();
        }
    }

    public static class OptimizationModule extends ConfigurationTestModule {
        public OptimizationModule() {
            super(Map.of("yca.optimize.enabled", "true", "yca.optimize.genericMaxLines", "10"));
        }
    }
}
