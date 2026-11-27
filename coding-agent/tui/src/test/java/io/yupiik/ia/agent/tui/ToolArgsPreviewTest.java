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
package io.yupiik.ia.agent.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.cli.ToolPresenter;
import io.yupiik.ia.agent.core.shell.DiffViewMode;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
class ToolArgsPreviewTest {
    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    private ToolArgsPreview preview() {
        return new ToolArgsPreview(
                new ToolOutputFormat(mapper), new SyntaxHighlighter(), new DiffRenderer(), new ToolPresenter(mapper));
    }

    @Test
    void writeFileRendersPathAndHighlightedContent() {
        final var lines = preview()
                .preview(
                        "write_file",
                        "{\"path\":\"src/main/java/Foo.java\",\"content\":\"class Foo {}\\n\"}",
                        80,
                        PaletteTest.palette(),
                        DiffViewMode.SIMPLE);
        assertNotNull(lines);
        final var text = lines.stream()
                .flatMap(l -> l.spans().stream())
                .map(s -> s.content())
                .reduce("", (a, b) -> a + b);
        assertTrue(text.contains("src/main/java/Foo.java"), text);
        assertTrue(text.contains("class Foo"), text);
        assertFalse(text.contains("+++"), text);
        assertFalse(text.contains(" + class Foo"), text);
    }

    @Test
    void diffOnlyPreviewNeverRevealsOrdinaryOrMalformedArguments() {
        final var preview = preview();
        for (final var mode : DiffViewMode.values()) {
            assertNull(preview.diffPreview(
                    "write_file", "{\"path\":\"a\",\"content\":\"secret body\"}", 120, PaletteTest.palette(), mode));
            assertNull(preview.diffPreview(
                    "edit_file", "{\"path\":\"a\",\"extra\":\"secret body\"}", 120, PaletteTest.palette(), mode));
            assertNull(preview.diffPreview("edit_file", "malformed secret body", 120, PaletteTest.palette(), mode));
            assertNull(preview.diffPreview(
                    "edit_file",
                    "{\"path\":\"a\",\"oldString\":\"\",\"newString\":\"body\"}",
                    120,
                    PaletteTest.palette(),
                    mode));
            final var diff = preview.diffPreview(
                    "edit_file",
                    "{\"path\":\"a\",\"oldString\":\"old\",\"newString\":\"new\"}",
                    120,
                    PaletteTest.palette(),
                    mode);
            assertNotNull(diff);
            assertTrue(text(diff).contains("Requested changes"));
        }
    }

    @Test
    void editFileRendersSyntheticDiff() {
        final var lines = preview()
                .preview(
                        "edit_file",
                        "{\"path\":\"README.md\",\"oldString\":\"old\",\"newString\":\"new\"}",
                        80,
                        PaletteTest.palette(),
                        DiffViewMode.SIMPLE);
        assertNotNull(lines);
        final var text = lines.stream()
                .flatMap(l -> l.spans().stream())
                .map(s -> s.content())
                .reduce("", (a, b) -> a + b);
        assertTrue(text.contains("a/README.md"), text);
        assertTrue(text.contains(" - old"), text);
        assertTrue(text.contains(" + new"), text);
    }

    @Test
    void applyPatchRendersRawPatch() {
        final var lines = preview()
                .preview(
                        "apply_patch",
                        "{\"patch\":\"--- a/a.txt\\n+++ b/a.txt\\n@@ -1 +1 @@\\n-x\\n+y\\n\"}",
                        80,
                        PaletteTest.palette(),
                        DiffViewMode.SIMPLE);
        assertNotNull(lines);
        final var text = lines.stream()
                .flatMap(l -> l.spans().stream())
                .map(s -> s.content())
                .reduce("", (a, b) -> a + b);
        assertTrue(text.contains("a/a.txt"), text);
        assertTrue(text.contains(" - x"), text);
        assertTrue(text.contains(" + y"), text);
    }

    @Test
    void malformedOrIncompleteArgumentsRemainVisible() {
        final var preview = preview();
        assertTrue(text(preview.preview("bash", "{\"command\":\"ls\"}", 80, PaletteTest.palette(), DiffViewMode.SIMPLE))
                .contains("ls"));
        assertTrue(text(preview.preview("write_file", "not json", 80, PaletteTest.palette(), DiffViewMode.SIMPLE))
                .contains("not json"));
        assertNull(preview.preview("write_file", null, 80, PaletteTest.palette(), DiffViewMode.SIMPLE));
        assertTrue(text(preview.preview(
                        "write_file", "{\"path\":\"a.txt\"}", 80, PaletteTest.palette(), DiffViewMode.SIMPLE))
                .contains("a.txt"));
    }

    @Test
    void editsPreserveWhitespaceDeletionScopeAndFinalNewline() {
        final var args =
                mapper.toString(Map.of("path", "a.txt", "oldString", " \t  \n", "newString", "", "replaceAll", true));
        final var lines = preview().preview("edit_file", args, 100, PaletteTest.palette(), DiffViewMode.SIMPLE);
        assertTrue(lines.stream()
                .map(ToolArgsPreviewTest::plain)
                .anyMatch(line -> line.startsWith(" - ") && line.substring(3).isBlank()));
        final var text = text(lines);
        assertTrue(text.contains("all occurrences"), text);
        assertTrue(text.contains("new: empty"), text);
        assertFalse(text.contains(" + "), text);

        final var newlineOnly = text(preview()
                .preview(
                        "edit_file",
                        mapper.toString(Map.of("path", "a", "oldString", "x", "newString", "x\n")),
                        100,
                        PaletteTest.palette(),
                        DiffViewMode.SIMPLE));
        assertTrue(newlineOnly.contains("old: 1 characters; no final newline"), newlineOnly);
        assertTrue(newlineOnly.contains("new: 2 characters; ends with newline"), newlineOnly);
        assertTrue(newlineOnly.contains("\\ No newline"), newlineOnly);
        assertTrue(newlineOnly.contains("single occurrence"), newlineOnly);
    }

    @Test
    void invalidFieldTypesDoNotInventAnEdit() {
        for (final var args : List.of(
                Map.of("path", "a", "oldString", 12, "newString", "x"),
                Map.of("path", "a", "oldString", "x", "newString", false),
                Map.of("path", "a", "oldString", "x", "newString", "y", "replaceAll", "true"),
                Map.of("path", "a", "newString", "x"))) {
            final var text = text(preview()
                    .preview("edit_file", mapper.toString(args), 100, PaletteTest.palette(), DiffViewMode.SIMPLE));
            assertFalse(text.contains("--- a/"), text);
            assertFalse(text.contains("Requested changes"), text);
        }
    }

    @Test
    void writePreservesBlankContentTrailingSpacesAndNewlines() {
        for (final var content : List.of("", "   ", "\n", "a  \n\n", "a\r\n")) {
            final var lines = preview()
                    .preview(
                            "write_file",
                            mapper.toString(Map.of("path", "a.txt", "content", content)),
                            100,
                            PaletteTest.palette(),
                            DiffViewMode.SIMPLE);
            assertEquals(content.replace("\r\n", "\n"), text(lines.subList(4, lines.size())));
            assertFalse(text(lines).contains("+++ requested"));
        }
    }

    @Test
    void allNonFileBuiltinsUseSharedDetails() {
        final var args = "{\"value\":\"  spaced  \",\"nested\":{\"enabled\":false,\"items\":[1,2]}}";
        final var presenter = new ToolPresenter(mapper);
        for (final var name : List.of(
                "bash",
                "read",
                "grep",
                "glob",
                "write_todo",
                "memory_write",
                "ask_user",
                "webfetch",
                "websearch",
                "skill",
                "spawn_subagent",
                "check_subagents",
                "list_subagent_options",
                "maven_knowledge_content",
                "maven_repository_knowledge",
                "compress",
                "code_resolve",
                "code_trace_calls",
                "code_check_graph",
                "read_symbol",
                "code_find_code")) {
            assertEquals(
                    presenter.request(name, args, Integer.MAX_VALUE),
                    preview().preview(name, args, 200, PaletteTest.palette(), DiffViewMode.SIMPLE).stream()
                            .map(ToolArgsPreviewTest::plain)
                            .toList(),
                    name);
        }
    }

    @Test
    void externalNestedJsonIsPrettyAndHighlighted() {
        final var lines = preview()
                .preview(
                        "mcp_external",
                        "{\"outer\":{\"items\":[true,{\"x\":2}]}}",
                        100,
                        PaletteTest.palette(),
                        DiffViewMode.SIMPLE);
        assertTrue(lines.size() > 5);
        final var text = text(lines);
        assertTrue(text.contains("\"outer\""), text);
        assertTrue(text.contains("\"x\""), text);
        assertTrue(lines.stream()
                .flatMap(line -> line.spans().stream())
                .anyMatch(span -> !span.style().equals(Style.EMPTY)));
    }

    @Test
    void openCodeOperationsAndLongPatchPayloadsAreNotTruncated() {
        final var longLine = "0123456789".repeat(40);
        final var patch = "*** Begin Patch\n*** Add File: a.txt\n+" + longLine
                + "\n*** Update File: b.txt\n*** Move to: c.txt\n@@ section\n-old  \n+new  \n*** Delete File: d.txt\n*** End Patch";
        final var lines = preview()
                .preview(
                        "apply_patch",
                        mapper.toString(Map.of("patch", patch)),
                        30,
                        PaletteTest.palette(),
                        DiffViewMode.SIMPLE);
        final var joined = lines.stream().map(ToolArgsPreviewTest::plain).reduce("", String::concat);
        assertTrue(joined.replace(" ", "").contains(longLine), joined);
        assertTrue(joined.contains("*** Add File: a.txt (+1 -0)"), joined);
        assertTrue(joined.contains("*** Update File: b.txt (+1 -1)"), joined);
        assertTrue(joined.contains("*** Move to: c.txt"), joined);
        assertTrue(joined.contains("*** Delete File: d.txt (+0 -0)"), joined);
    }

    @Test
    void highlightingFailureRetainsRequestedContent() {
        final var preview = new ToolArgsPreview(
                new ToolOutputFormat(mapper),
                new SyntaxHighlighter() {
                    @Override
                    public List<Line> highlightLines(final String lang, final String code) {
                        throw new IllegalArgumentException("cannot highlight");
                    }
                },
                new DiffRenderer(),
                new ToolPresenter(mapper));
        final var args = "{\"path\":\"a\",\"content\":\"test\"}";
        final var text = text(preview.preview("write_file", args, 100, PaletteTest.palette(), DiffViewMode.SIMPLE));
        assertEquals(args, text);
        assertTrue(text.contains("test"), text);
    }

    private static String plain(final Line line) {
        return line.spans().stream().map(Span::content).reduce("", String::concat);
    }

    private static String text(final List<Line> lines) {
        return String.join("\n", lines.stream().map(ToolArgsPreviewTest::plain).toList());
    }
}
