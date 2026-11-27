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
package io.yupiik.ia.agent.core.cli;

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.cli.prompt.PromptOutput;
import io.yupiik.ia.agent.core.shell.DiffLayout;
import io.yupiik.ia.agent.core.shell.DiffViewMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CliDiffRendererTest {
    @Test
    void splitWrapsBothSourcesAndPadsTheShorterSide() {
        final var before = "a".repeat(125);
        final var after = "b".repeat(80);
        final var rows = render(List.of("-" + before, "+" + after), DiffViewMode.SIDE_BY_SIDE, 111, true);
        assertEquals(3, rows.size());
        assertTrue(rows.stream()
                .allMatch(it -> it.length() == 111 && it.substring(54, 57).equals(" | ")));
        assertEquals(
                before,
                rows.stream()
                        .map(it -> it.substring(0, 54).stripTrailing().substring(1))
                        .collect(joining()));
        assertEquals(
                after,
                rows.stream()
                        .map(it -> it.substring(57).stripTrailing())
                        .filter(it -> !it.isEmpty())
                        .map(it -> it.substring(1))
                        .collect(joining()));
        assertEquals(" ".repeat(54), rows.getLast().substring(57));
        assertFalse(String.join("\n", rows).contains("..."));
    }

    @Test
    void simpleWrapsWithoutDroppingWhitespaceOrSource() {
        final var source = "  public String value = \"long source text\";   ";
        final var rows = render(List.of("+" + source), DiffViewMode.SIMPLE, 12, true);
        assertTrue(rows.size() > 1);
        assertTrue(rows.stream().allMatch(it -> it.startsWith("+") && it.length() <= 12));
        assertEquals(source, rows.stream().map(it -> it.substring(1)).collect(joining()));
    }

    @Test
    void splitUsesCellWidthsForWideAndCombiningCharacters() {
        final var source = "\u754ce\u0301".repeat(25);
        final var rows = render(List.of("-" + source, "+x"), DiffViewMode.SIDE_BY_SIDE, 113, true);
        assertEquals(2, rows.size());
        assertTrue(rows.getFirst().startsWith("-" + "\u754ce\u0301".repeat(18) + " | "));
        assertTrue(rows.getLast().startsWith("-" + "\u754ce\u0301".repeat(7) + " ".repeat(33) + " | "));
        assertFalse(String.join("\n", rows).contains("..."));
    }

    @Test
    void wrappingKeepsCombiningAndEmojiClustersTogether() {
        final var joined = "\ud83d\udc69\u200d\ud83d\udcbb";
        final var modified = "\ud83d\udc4d\ud83c\udffd";
        assertEquals(
                List.of("+aaaaae\u0301", "+" + joined + modified + "ta", "+il"),
                render(List.of("+aaaaae\u0301" + joined + modified + "tail"), DiffViewMode.SIMPLE, 7, true));
        final var rows = render(List.of("-" + joined.repeat(30), "+x"), DiffViewMode.SIDE_BY_SIDE, 113, true);
        assertTrue(rows.getFirst().startsWith("-" + joined.repeat(27) + " | "));
        assertTrue(rows.getLast().startsWith("-" + joined.repeat(3) + " ".repeat(48) + " | "));
    }

    @Test
    void metadataWrapsRatherThanClipping() {
        final var source = "@@ " + "header ".repeat(20) + "@@";
        final var rows = render(List.of(source), DiffViewMode.SIDE_BY_SIDE, 111, true);
        assertEquals(source, String.join("", rows));
        assertTrue(rows.stream().allMatch(it -> it.length() <= 111));
    }

    @Test
    void redirectedOutputIsLinearUncoloredAndNotTerminalWrapped() {
        final var input = List.of("-" + "old ".repeat(100), "+" + "new ".repeat(100));
        assertEquals(
                input,
                CliDiffRenderer.render(
                        input, "java", DiffViewMode.SIDE_BY_SIDE, 5, false, new MarkdownColorizer(true)));
    }

    @Test
    void capturedControlsCannotInjectTerminalCommands() {
        assertEquals(
                List.of("+a    b\\r?[31m?"), render(List.of("+a\tb\r\u001b[31m\u0000"), DiffViewMode.SIMPLE, 80, true));
    }

    @Test
    void onlyWorkBoundsProduceExplicitTruncationNotices() {
        final var longLine = render(List.of("+" + "x".repeat(5000)), DiffViewMode.SIMPLE, 80, true);
        assertEquals(
                4095,
                longLine.subList(0, longLine.size() - 1).stream()
                        .mapToInt(it -> it.length() - 1)
                        .sum());
        assertEquals("... diff truncated: source line exceeds 4096 characters", longLine.getLast());
        final var wrapped = render(Collections.nCopies(100, "+" + "x".repeat(100)), DiffViewMode.SIMPLE, 12, true);
        assertEquals(DiffLayout.MAX_OUTPUT_LINES + 1, wrapped.size());
        assertEquals("... diff truncated: rendered row limit (240)", wrapped.getLast());
        final var many = render(Collections.nCopies(300, "+x"), DiffViewMode.SIDE_BY_SIDE, 111, true);
        assertEquals(DiffLayout.MAX_OUTPUT_LINES + 1, many.size());
        assertEquals("... diff truncated: rendered row limit (240)", many.getLast());
    }

    @Test
    void widthIsReadAgainForTheNextDiff() {
        final var width = new AtomicInteger(80);
        final var output = new PromptOutput() {
            @Override
            public int columns() {
                return width.get();
            }

            @Override
            public boolean active() {
                return false;
            }

            @Override
            public void print(final String text) {}
        };
        final var input = List.of("-old", "+new");
        assertEquals(input, render(input, DiffViewMode.AUTO, output.columns(), true));
        width.set(140);
        assertEquals(1, render(input, DiffViewMode.AUTO, output.columns(), true).size());
        assertEquals(input, render(input, DiffViewMode.SIMPLE, output.columns(), true));
    }

    @Test
    void diffColorsUseDarkSemanticRolesAndOneDarkSyntaxOnly() {
        final var colors = new MarkdownColorizer(true);
        final var added = colors.diffCell("java", "+public String s = \"x\"; // comment", '+');
        assertTrue(added.startsWith("\u001b[48;2;26;54;29m\u001b[38;2;70;247;47m+"));
        assertTrue(added.contains("\u001b[38;2;198;120;221mpublic"));
        assertTrue(added.contains("\u001b[38;2;152;195;121m\"x\""));
        assertTrue(added.contains("\u001b[38;2;127;132;142m// comment"));
        assertTrue(added.contains("\u001b[0m\u001b[48;2;26;54;29m"));
        final var deleted = colors.diffCell("java", "-return 42;", '-');
        assertTrue(deleted.startsWith("\u001b[48;2;53;34;39m\u001b[38;2;248;113;113m-"));
        assertTrue(deleted.contains("\u001b[38;2;209;154;102m42"));
        assertFalse(colors.diffCell("java", " context", ' ').contains("\u001b[48;"));
        assertFalse(added.contains("38;5;"));
        colors.colorizeLine("```java");
        assertTrue(
                colors.colorizeLine("return \"x\";").contains("\u001b[38;5;215m"),
                "ordinary markdown retains its existing palette");
        assertEquals("+return 42;", new MarkdownColorizer(false).diffCell("java", "+return 42;", '+'));
    }

    @Test
    void noColorAndDumbTerminalAreRespectedEvenWithEnabledColorizer() throws Exception {
        assertChildColor(true);
        assertChildColor(false);
    }

    private void assertChildColor(final boolean noColor) throws Exception {
        final var command = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                getClass().getName());
        command.environment().remove("NO_COLOR");
        command.environment().put("TERM", noColor ? "xterm-256color" : "dumb");
        if (noColor) {
            command.environment().put("NO_COLOR", "");
        }
        final var process = command.redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            final var result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), result);
            assertFalse(result.contains("\u001b["), result);
            assertTrue(result.contains("+return 42;"), result);
        } finally {
            process.destroyForcibly();
        }
    }

    public static void main(final String[] args) {
        CliDiffRenderer.render(
                        List.of("+return 42;"), "java", DiffViewMode.SIMPLE, 80, true, new MarkdownColorizer(true))
                .forEach(System.out::println);
    }

    private List<String> render(final List<String> lines, final DiffViewMode mode, final int width, final boolean tty) {
        return CliDiffRenderer.render(lines, "java", mode, width, tty, new MarkdownColorizer(false));
    }
}
