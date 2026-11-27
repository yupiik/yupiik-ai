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
package io.yupiik.ia.agent.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.cli.CliDiffRenderer;
import io.yupiik.ia.agent.core.cli.CliVerbosity;
import io.yupiik.ia.agent.core.cli.MarkdownColorizer;
import io.yupiik.ia.agent.core.cli.Symbols;
import io.yupiik.ia.agent.core.cli.ToolPresenter;
import io.yupiik.ia.agent.core.cli.prompt.PromptOutput;
import io.yupiik.ia.agent.core.security.SecretRedactor;
import io.yupiik.ia.agent.core.shell.slash.ConfigCommands;
import io.yupiik.ia.agent.core.tools.FileMutation;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
class DiffViewTest {
    private static final Path ROOT = Path.of("/diff-test").toAbsolutePath().normalize();

    @Test
    void modesAndCommandContract(final JsonMapper mapper) {
        assertNull(DiffViewMode.parse(null));
        assertNull(DiffViewMode.parse("split"));
        assertNull(DiffViewMode.parse(""));
        for (final var mode : DiffViewMode.values()) {
            assertSame(mode, DiffViewMode.parse(mode.value()));
        }
        final var session = new ShellSession();
        assertEquals(DiffViewMode.AUTO, session.diffViewMode());
        final var output = new Sink();
        final var frontend = frontend(mapper, output, false, null);
        final var env = new ShellEnv(frontend, null, session, new ConversationTranscript());
        final var command = new ConfigCommands(null, null, null, null, null, null, null)
                .commands().stream()
                        .filter(c -> c.spec().name().equals("/diff-view"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(((ShellOutcome.Status)
                        command.action().apply("", env).toCompletableFuture().join())
                .message()
                .contains("auto - usage:"));
        for (final var mode : DiffViewMode.values()) {
            command.action().apply(mode.value(), env).toCompletableFuture().join();
            assertEquals(mode, session.diffViewMode());
        }
        command.action().apply("invalid", env).toCompletableFuture().join();
        assertEquals(DiffViewMode.SIMPLE, session.diffViewMode());
        frontend.onEvent(capture(new FileMutation.Content("before\n", "after\n")));
        assertTrue(output.text().contains("-before"), output.text()); // Command also updated the frontend.
        assertSame(session, session.diffViewMode(DiffViewMode.AUTO));
        assertTrue(command.completion().complete("", "", null).toString().contains("side-by-side"));
    }

    @Test
    void visibilityMatrixAndMissingContent(final JsonMapper mapper) {
        for (final var mode : DiffViewMode.values()) {
            for (final var visibility : ToolOutputVisibility.values()) {
                final var sink = new Sink();
                final var frontend = frontend(mapper, sink, false, null);
                frontend.diffViewMode(mode);
                frontend.showToolOutput(visibility);
                frontend.onEvent(capture(new FileMutation.Content("old\n", "new\n")));
                final var expected = visibility != ToolOutputVisibility.HIDDEN;
                assertEquals(expected, sink.text().contains("-old"), mode + "/" + visibility + sink.text());
            }
        }
        final var sink = new Sink();
        final var frontend = frontend(mapper, sink, false, null);
        frontend.diffViewMode(DiffViewMode.SIMPLE);
        frontend.onEvent(capture(null));
        assertTrue(sink.text().contains("applied update:"));
        assertTrue(sink.text().contains("content unavailable"));
        assertFalse(sink.text().contains("-old"));
    }

    @Test
    void capturesAreRedactedBeforeDiffingAndNeverReadDisk(final JsonMapper mapper) {
        final var sink = new Sink();
        final var redactor = new SecretRedactor(null, null) {
            @Override
            public String redact(final String text, final String sessionId) {
                assertEquals("session", sessionId);
                return text == null ? null : text.replace("secret", "REDACTED");
            }
        };
        final var frontend = frontend(mapper, sink, false, redactor);
        frontend.diffViewMode(DiffViewMode.SIMPLE);
        frontend.onEvent(capture(new FileMutation.Content("secret old\n", "secret new\n")));
        assertTrue(sink.text().contains("exact diff unavailable"));
        assertFalse(sink.text().contains("REDACTED old"));
        assertFalse(sink.text().contains("REDACTED new"));
        assertFalse(sink.text().contains("secret"));
        assertFalse(sink.text().contains("\u001b"));
        sink.parts.clear();
        frontend.onEvent(capture(new FileMutation.Content("x".repeat(DiffLayout.MAX_CHARS + 1), "new")));
        assertTrue(sink.text().contains("content limit"));
    }

    @Test
    void requestsAndOutputPatchesAreNotClaimsOfAppliedChanges(final JsonMapper mapper) {
        final var sink = new Sink();
        final var frontend = frontend(mapper, sink, false, null);
        frontend.diffViewMode(DiffViewMode.SIMPLE);
        frontend.onEvent(new AgentEvent.ToolStarted(
                "edit_file",
                "{\"path\":\"a.java\",\"oldString\":\"old\",\"newString\":\"new\"}",
                1,
                "session",
                null,
                null,
                ROOT));
        assertTrue(sink.text().contains("request preview (not applied)"), sink.text());
        assertFalse(sink.text().contains("applied update"));
        frontend.onEvent(new AgentEvent.ToolFinished(
                "edit_file",
                "--- a/a\n+++ b/a\n@@ -1 +1 @@\n-old\n+new",
                false,
                false,
                1,
                "session",
                null,
                null,
                ROOT));
        assertTrue(sink.text().contains("result patch (reported output, not verified)"));
        assertTrue(sink.text().contains("+new"));
    }

    @Test
    void responsiveLayoutColorsAndControlSafety() {
        final var lines = List.of("@@ -1 +1 @@", "-return 1;", "+return 2;", "+added", " context", "-deleted");
        final var plain = new MarkdownColorizer(false);
        final var wide = CliDiffRenderer.render(lines, "java", DiffViewMode.AUTO, 110, true, plain);
        assertTrue(wide.get(1).contains("-return 1;"));
        assertTrue(wide.get(1).contains(" | +return 2;"));
        assertTrue(wide.stream().anyMatch(l -> l.contains(" | +added")));
        assertTrue(wide.stream().anyMatch(l -> l.startsWith("-deleted") && l.contains(" | ")));
        assertFalse(String.join("\n", CliDiffRenderer.render(lines, "java", DiffViewMode.AUTO, 109, true, plain))
                .contains(" | "));
        assertFalse(String.join("\n", CliDiffRenderer.render(lines, "java", DiffViewMode.SIMPLE, 200, true, plain))
                .contains(" | "));
        assertFalse(
                String.join("\n", CliDiffRenderer.render(lines, "java", DiffViewMode.SIDE_BY_SIDE, 200, false, plain))
                        .contains(" | "));
        final var colors = new MarkdownColorizer(true);
        assertFalse(String.join("", CliDiffRenderer.render(lines, "java", DiffViewMode.AUTO, 200, false, colors))
                .contains("\u001b"));
        assertTrue(colors.diffCell("java", "+return 1;", '+').contains("48;2;26;54;29"));
        assertTrue(colors.diffCell("java", "-return 1;", '-').contains("48;2;53;34;39"));
        assertFalse(colors.diffCell("java", " return 1;", ' ').contains("48;"));
        assertTrue(colors.diffCell("java", " return 1;", ' ').contains("38;2;198;120;221"));
        final var controls =
                CliDiffRenderer.render(List.of("+\u001b[2J\tline\r"), "", DiffViewMode.SIMPLE, 80, false, plain);
        assertFalse(controls.getFirst().contains("\u001b"));
        assertTrue(controls.getFirst().contains("\\r"));
    }

    @Test
    void boundedLayoutAndNewlines() {
        assertTrue(DiffLayout.between(null, "created\n").contains("+created"));
        assertTrue(DiffLayout.between("deleted\n", "").contains("-deleted"));
        assertEquals(List.of("(no text changes)"), DiffLayout.between("same", "same"));
        assertTrue(DiffLayout.between("same", "same\n").stream().anyMatch(l -> l.contains("No newline")));
        assertTrue(DiffLayout.between("a\r\n", "a\n").contains("-a\r"));
        assertTrue(DiffLayout.between("x\n".repeat(2001), "new").getFirst().contains("line limit"));
        final var replacement = DiffLayout.between("old\n".repeat(1000), "new\n".repeat(1000));
        assertTrue(replacement.size() <= DiffLayout.MAX_OUTPUT_LINES);
        assertTrue(replacement.contains("-old"));
        assertTrue(replacement.contains("+new"));
        assertTrue(replacement.contains("... additions truncated"));
    }

    private static AgentEvent.ToolFilesChanged capture(final FileMutation.Content content) {
        return new AgentEvent.ToolFilesChanged(
                "edit_file",
                List.of(new FileMutation(ROOT.resolve("not-on-disk.java"), FileMutation.Kind.UPDATE, content)),
                1,
                "session",
                "child",
                "worker",
                ROOT);
    }

    @Test
    void hiddenFailurePatchesStayHiddenAndRedactionFailureStaysClosed(final JsonMapper mapper) {
        final var sink = new Sink();
        final var frontend = frontend(mapper, sink, false, null);
        frontend.diffViewMode(DiffViewMode.SIMPLE);
        frontend.showToolOutput(ToolOutputVisibility.HIDDEN);
        frontend.onEvent(new AgentEvent.ToolFinished(
                "apply_patch", "--- a/a\n+++ b/a\n-private\n+secret", false, false, 1, "session", null, null, ROOT));
        assertFalse(sink.text().contains("private"));
        assertFalse(sink.text().contains("secret"));
        assertTrue(sink.text().contains("failed"));
        sink.parts.clear();
        final var failing = frontend(mapper, sink, false, new SecretRedactor(null, null) {
            @Override
            public String redact(final String text, final String sessionId) {
                throw new IllegalStateException("private diagnostic");
            }
        });
        failing.diffViewMode(DiffViewMode.SIMPLE);
        failing.onEvent(capture(new FileMutation.Content("private old", "private new")));
        assertFalse(sink.text().contains("private"));
        assertTrue(sink.text().contains("redacted content unavailable"));
    }

    @Test
    void terminalWidthIsQueriedForEachCaptureAndWideCharactersFit(final JsonMapper mapper) {
        final var sink = new Sink();
        final var frontend = frontend(mapper, sink, true, null);
        frontend.diffViewMode(DiffViewMode.SIDE_BY_SIDE);
        frontend.onEvent(capture(new FileMutation.Content("old\n", "new\n")));
        assertTrue(sink.text().contains(" | +new"), sink.text());
        assertTrue(sink.text().contains("[worker]"), sink.text());
        sink.parts.clear();
        sink.width = 100;
        frontend.onEvent(capture(new FileMutation.Content("old\n", "new\n")));
        assertFalse(sink.text().contains(" | "), sink.text());
        final var wide = CliDiffRenderer.render(
                List.of("-\u754c".repeat(30), "+new"),
                "",
                DiffViewMode.SIDE_BY_SIDE,
                110,
                true,
                new MarkdownColorizer(false));
        assertTrue(wide.size() > 1, wide.toString());
        assertTrue(wide.getFirst().contains(" | +new"), wide.toString());
        assertFalse(wide.stream().anyMatch(line -> line.contains("...")), wide.toString());
        assertEquals(
                30L,
                wide.stream()
                        .flatMapToInt(String::codePoints)
                        .filter(point -> point == 0x754c)
                        .count());
    }

    private static AnsiShellFrontend frontend(
            final JsonMapper mapper, final Sink sink, final boolean tty, final SecretRedactor redactor) {
        return new AnsiShellFrontend(
                new MarkdownColorizer(false),
                redactor,
                new Symbols(true),
                new ToolPresenter(mapper),
                CliVerbosity.VERBOSE,
                tty,
                false,
                ToolOutputVisibility.COLLAPSED,
                sink);
    }

    private static final class Sink implements PromptOutput {
        private final List<String> parts = new ArrayList<>();
        private int width = 160;

        @Override
        public boolean active() {
            return false;
        }

        @Override
        public int columns() {
            return width;
        }

        @Override
        public void print(final String text) {
            parts.add(text);
        }

        private String text() {
            return String.join("", parts);
        }
    }
}
