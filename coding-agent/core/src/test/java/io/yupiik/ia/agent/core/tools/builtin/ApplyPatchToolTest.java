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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.tools.ToolContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@FusionSupport(modules = ConfigurationTestModule.class)
class ApplyPatchToolTest {
    private CodingAgentConfiguration configuration;

    @BeforeEach
    void configuration(@Fusion final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
    }

    @TempDir
    private static Path testWorkdir;

    @TempDir
    Path dir;

    private ApplyPatchTool tool() {
        return new ApplyPatchTool(configuration);
    }

    @Test
    void unwrapsQuotedAndBareHeredocsWithIndependentDelimiters() throws Exception {
        final var tool = tool();
        for (var i = 0; i < 2; i++) {
            final var marker = i == 0 ? "PATCH_A" : "PATCH_B";
            final var prefix = i == 0 ? "cat <<'" + marker + "'" : "<<" + marker;
            final var name = "wrapped-" + i + ".txt";
            final var patch = prefix + "\n--- /dev/null\n+++ b/" + name + "\n@@\n+hello\n" + marker + "\n";
            final var result = tool.execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
            assertTrue(result.success(), result.output());
            assertEquals("hello\n", Files.readString(dir.resolve(name)));
        }
    }

    // ---------------------------------------------------------------------
    // Unified diff dialect
    // ---------------------------------------------------------------------

    @Test
    void appliesContextChanges() throws Exception {
        final var file = dir.resolve("src/Main.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "line one\nline two\nline three\n");
        final var patch = """
                --- a/src/Main.java
                +++ b/src/Main.java
                @@
                 line one
                -line two
                +line two changed
                 line three
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertTrue(result.success(), () -> result.output());
        assertEquals("line one\nline two changed\nline three\n", Files.readString(file));
    }

    @Test
    void createsNewFileFromDevNull() throws Exception {
        final var patch = """
                --- /dev/null
                +++ b/created.txt
                @@
                +hello
                +world
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertTrue(result.success(), () -> result.output());
        assertTrue(result.output().contains("Created"));
        assertEquals("hello\nworld\n", Files.readString(dir.resolve("created.txt")));
    }

    @Test
    void deletesFileToDevNull() throws Exception {
        final var file = dir.resolve("to-delete.txt");
        Files.writeString(file, "bye\n");
        final var patch = """
                --- a/to-delete.txt
                +++ /dev/null
                @@
                -bye
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertTrue(result.success(), () -> result.output());
        assertTrue(result.output().contains("Deleted"));
        assertFalse(Files.exists(file));
    }

    @Test
    void rejectsContextMismatch() throws Exception {
        final var file = dir.resolve("foo.txt");
        Files.writeString(file, "different\n");
        final var patch = """
                --- a/foo.txt
                +++ b/foo.txt
                @@
                 missing
                -no
                +yes
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertFalse(result.success());
        assertEquals("different\n", Files.readString(file));
    }

    @Test
    void rejectsEscapingPatchPath() throws Exception {
        final var patch = """
                --- a/../../etc/passwd
                +++ b/../../etc/passwd
                @@
                -x
                +y
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertFalse(result.success());
    }

    @Test
    void rejectsDriftedHunkInsteadOfSplicingAtTheFirstMatchingOffset() throws Exception {
        // the @@ header anchors the hunk at line 1 while its context only exists
        // at line 10: the old implementation scanned from offset 0, matched at
        // line 10 and dropped everything before it (head truncation)
        final var sb = new StringBuilder();
        for (var i = 1; i <= 12; i++) {
            sb.append("line ").append(i).append('\n');
        }
        final var file = dir.resolve("drifted.txt");
        Files.writeString(file, sb.toString());
        final var patch = """
                --- a/drifted.txt
                +++ b/drifted.txt
                @@ -1,2 +1,2 @@
                 line 10
                -line 11
                +changed
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertFalse(result.success(), () -> result.output());
        assertEquals(sb.toString(), Files.readString(file));
    }

    @Test
    void appliesAnchoredHunkAtDeclaredPositionWhenBodyRepeatsEarlier() throws Exception {
        // the hunk body also occurs at line 3, but the header pins it to line 13
        final var sb = new StringBuilder();
        for (var i = 1; i <= 15; i++) {
            sb.append("line ").append(i).append('\n');
        }
        final var file = dir.resolve("anchor.txt");
        Files.writeString(file, sb.toString());
        final var patch = """
                --- a/anchor.txt
                +++ b/anchor.txt
                @@ -13,2 +13,2 @@
                 line 13
                -line 14
                +changed
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertTrue(result.success(), () -> result.output());
        final var updated = Files.readString(file);
        assertTrue(updated.contains("line 12\nline 13\nchanged\nline 15\n"), updated);
        assertTrue(updated.contains("\nline 3\nline 4\n"), updated);
    }

    @Test
    void rejectsAmbiguousBareHunk() throws Exception {
        // a bare @@ hunk matching two locations cannot be applied safely
        final var file = dir.resolve("ambig.txt");
        Files.writeString(file, "a\nb\nc\na\nb\nc\n");
        final var patch = """
                --- a/ambig.txt
                +++ b/ambig.txt
                @@
                 a
                -b
                +B
                 c
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertFalse(result.success(), () -> result.output());
        assertEquals("a\nb\nc\na\nb\nc\n", Files.readString(file));
    }

    @Test
    void appliesHunkWithDisagreeingHeaderCounts() throws Exception {
        // counts are reconciled from the body, so a wrong @@ count no longer rejects
        final var file = dir.resolve("counts.txt");
        Files.writeString(file, "a\nb\nc\n");
        final var patch = """
                --- a/counts.txt
                +++ b/counts.txt
                @@ -1,1 +1,3 @@
                -a
                +A
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertTrue(result.success(), () -> result.output());
        assertEquals("A\nb\nc\n", Files.readString(file));
    }

    @Test
    void appliesMultipleHunksWithDrift() throws Exception {
        final var file = dir.resolve("multi.txt");
        Files.writeString(file, "a\nb\nc\nd\ne\n");
        final var patch = """
                --- a/multi.txt
                +++ b/multi.txt
                @@ -2,1 +2,1 @@
                -b
                +B
                @@ -4,1 +4,1 @@
                -d
                +D
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertTrue(result.success(), () -> result.output());
        assertEquals("a\nB\nc\nD\ne\n", Files.readString(file));
    }

    @Test
    void rejectsMultiHunkPatchMissingFileHeaderInsteadOfNpe() throws Exception {
        // a second @@ hunk before any ---/+++ file header used to NPE on the null hunks list
        final var patch = """
                @@ -1,2 +1,3 @@
                 a
                -b
                +B
                @@ -5,1 +5,1 @@
                -c
                +C
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertFalse(result.success(), () -> result.output());
        assertTrue(result.output().contains("file header"), () -> result.output());
    }

    @Test
    void rejectsSingleHunkWithoutFileHeaderInsteadOfSilentNoop() throws Exception {
        final var patch = """
                @@ -1,2 +1,3 @@
                 a
                -b
                +B
                +c2
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertFalse(result.success(), () -> result.output());
        assertTrue(result.output().contains("file header"), () -> result.output());
    }

    @Test
    void toleratesPlusMinusStatSuffixInUnifiedDiff() throws Exception {
        // models like Codex emit `(+N -N)` after the @@ closing marker; it must be ignored
        final var file = dir.resolve("stat.txt");
        Files.writeString(file, "line one\nline two\n");
        final var patch = """
                --- a/stat.txt
                +++ b/stat.txt
                @@ -1,2 +1,2 @@ (+0 -0)
                 line one
                -line two
                +line two changed
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertTrue(result.success(), () -> result.output());
        assertEquals("line one\nline two changed\n", Files.readString(file));
    }

    @Test
    void matchesTrailingWhitespaceLeniently() throws Exception {
        // a context line with trailing whitespace still matches a trimmed patch line
        final var file = dir.resolve("pad.txt");
        Files.writeString(file, "alpha \nbeta\n");
        final var patch = """
                --- a/pad.txt
                +++ b/pad.txt
                @@
                 alpha
                -beta
                +beta changed
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertTrue(result.success(), () -> result.output());
        assertEquals("alpha \nbeta changed\n", Files.readString(file));
    }

    @Test
    void matchesUnicodeNormalized() throws Exception {
        // curly quotes in the file match straight quotes in the patch, like opencode
        final var file = dir.resolve("unicode.txt");
        Files.writeString(file, "say \u2018hi\u2019\n");
        final var patch = """
                --- a/unicode.txt
                +++ b/unicode.txt
                @@
                 say 'hi'
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertTrue(result.success(), () -> result.output());
        assertEquals("say \u2018hi\u2019\n", Files.readString(file));
    }

    @Test
    void appliesWholePatchAtomicallyOnLaterFileFailure() throws Exception {
        // a valid first file must not be written when a later file fails to match
        final var a = dir.resolve("a.txt");
        final var b = dir.resolve("b.txt");
        Files.writeString(a, "a\n");
        Files.writeString(b, "different\n");
        final var patch = """
                --- a/a.txt
                +++ b/a.txt
                @@
                -a
                +A
                --- a/b.txt
                +++ b/b.txt
                @@
                 missing
                -no
                +yes
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertFalse(result.success(), () -> result.output());
        assertEquals("a\n", Files.readString(a));
    }

    @Test
    void rejectsDriftedUnifiedHunkAndKeepsFollowingFilesUnwritten() throws Exception {
        // whole-patch atomicity: a drifted second hunk must not let the first file be written
        final var a = dir.resolve("atomic-a.txt");
        final var anchored = dir.resolve("atomic-b.txt");
        Files.writeString(a, "keep\n");
        Files.writeString(anchored, "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\n12\n");
        final var patch = """
                --- a/atomic-a.txt
                +++ b/atomic-a.txt
                @@
                -keep
                +changed
                --- a/atomic-b.txt
                +++ b/atomic-b.txt
                @@ -1,1 +1,1 @@
                 line 10
                -x
                +y
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertFalse(result.success(), () -> result.output());
        assertEquals("keep\n", Files.readString(a)); // untouched because validation failed before any write
    }

    // ---------------------------------------------------------------------
    // OpenCode dialect
    // ---------------------------------------------------------------------

    @Test
    void appliesOpenCodeDialectAdd() throws Exception {
        final var patch = """
                *** Begin Patch
                *** Add File: incoming.txt
                +hello
                +world
                *** End Patch
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertTrue(result.success(), () -> result.output());
        assertTrue(result.output().contains("Created"));
        assertEquals("hello\nworld\n", Files.readString(dir.resolve("incoming.txt")));
    }

    @Test
    void appliesOpenCodeDialectDelete() throws Exception {
        final var file = dir.resolve("gone.txt");
        Files.writeString(file, "bye\n");
        final var patch = """
                *** Begin Patch
                *** Delete File: gone.txt
                *** End Patch
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertTrue(result.success(), () -> result.output());
        assertTrue(result.output().contains("Deleted"));
        assertFalse(Files.exists(file));
    }

    @Test
    void appliesOpenCodeDialectUpdate() throws Exception {
        final var file = dir.resolve("upd.txt");
        Files.writeString(file, "line one\nline two\nline three\n");
        final var patch = """
                *** Begin Patch
                *** Update File: upd.txt
                @@
                 line one
                -line two
                +line two changed
                 line three
                *** End Patch
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertTrue(result.success(), () -> result.output());
        assertEquals("line one\nline two changed\nline three\n", Files.readString(file));
    }

    @Test
    void appliesOpenCodeDialectMove() throws Exception {
        final var file = dir.resolve("old.txt");
        Files.writeString(file, "old\n");
        final var patch = """
                *** Begin Patch
                *** Update File: old.txt
                *** Move to: newdir/new.txt
                @@
                -old
                +new
                *** End Patch
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertTrue(result.success(), () -> result.output());
        assertFalse(Files.exists(file));
        assertEquals("new\n", Files.readString(dir.resolve("newdir/new.txt")));
    }

    @Test
    void appliesOpenCodeDialectEofAnchor() throws Exception {
        final var file = dir.resolve("eof.txt");
        Files.writeString(file, "a\nb\nc\n");
        final var patch = """
                *** Begin Patch
                *** Update File: eof.txt
                @@
                -c
                +C
                *** End of File
                *** End Patch
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertTrue(result.success(), () -> result.output());
        assertEquals("a\nb\nC\n", Files.readString(file));
    }

    @Test
    void appliesForwardSeekFirstMatch() throws Exception {
        // unlike the unified dialect, the OpenCode dialect applies at the first forward match
        final var file = dir.resolve("fwd.txt");
        Files.writeString(file, "a\nb\nc\na\nb\nc\n");
        final var patch = """
                *** Begin Patch
                *** Update File: fwd.txt
                @@
                 a
                -b
                +B
                *** End Patch
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertTrue(result.success(), () -> result.output());
        assertEquals("a\nB\nc\na\nb\nc\n", Files.readString(file));
    }

    @Test
    void toleratesPlusMinusStatSuffixInOpenCodeDialect() throws Exception {
        // the whole @@ line is opaque change-context, so a (+0 -0) stat suffix is harmless
        final var file = dir.resolve("stat-oc.txt");
        Files.writeString(file, "line one\nline two\n");
        final var patch = """
                *** Begin Patch
                *** Update File: stat-oc.txt
                @@ -1,2 +1,2 @@ (+0 -0)
                 line one
                -line two
                +line two changed
                *** End Patch
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertTrue(result.success(), () -> result.output());
        assertEquals("line one\nline two changed\n", Files.readString(file));
    }

    @Test
    void usesChangeContextAsSeekHint() throws Exception {
        // a change-context line positions the cursor; old lines are then matched from there
        final var file = dir.resolve("ctx.txt");
        Files.writeString(file, "alpha\nbeta\ngamma\n");
        final var patch = """
                *** Begin Patch
                *** Update File: ctx.txt
                @@ alpha
                -beta
                +BETA
                 gamma
                *** End Patch
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertTrue(result.success(), () -> result.output());
        assertEquals("alpha\nBETA\ngamma\n", Files.readString(file));
    }

    @Test
    void rejectsOpenCodeMissingChangeContext() throws Exception {
        final var file = dir.resolve("missing-ctx.txt");
        Files.writeString(file, "alpha\n");
        final var patch = """
                *** Begin Patch
                *** Update File: missing-ctx.txt
                @@ no-such-context
                -alpha
                +beta
                *** End Patch
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertFalse(result.success(), () -> result.output());
        assertEquals("alpha\n", Files.readString(file));
    }

    @Test
    void rejectsOpenCodeMissingBeginningWithoutPartialWrite() throws Exception {
        // OpenCode require begin/end markers; without them the patch is refused
        final var file = dir.resolve("nope.txt");
        Files.writeString(file, "keep\n");
        final var patch = "*** Update File: nope.txt\n@@\n-keep\n+lost\n*** End Patch\n";
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertFalse(result.success(), () -> result.output());
        assertEquals("keep\n", Files.readString(file));
    }

    // ---------------------------------------------------------------------
    // BOM and trailing-newline policy
    // ---------------------------------------------------------------------

    @Test
    void preservesBomOnUpdate() throws Exception {
        final var file = dir.resolve("bom.txt");
        Files.writeString(file, "\uFEFFalpha\nbeta\n", UTF_8);
        final var patch = """
                --- a/bom.txt
                +++ b/bom.txt
                @@
                 alpha
                -beta
                +BETA
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertTrue(result.success(), () -> result.output());
        final var bytes = Files.readAllBytes(file);
        assertTrue(bytes.length >= 3, "missing BOM");
        assertEquals(0xEF, bytes[0] & 0xFF);
        assertEquals(0xBB, bytes[1] & 0xFF);
        assertEquals(0xBF, bytes[2] & 0xFF);
        assertEquals("alpha\nBETA\n", new String(bytes, 3, bytes.length - 3, UTF_8));
    }

    @Test
    void forcesTrailingNewlineOnUpdate() throws Exception {
        // a file lacking a trailing newline gains one after an update
        final var file = dir.resolve("no-newline.txt");
        Files.writeString(file, "alpha\nbeta", UTF_8);
        final var patch = """
                --- a/no-newline.txt
                +++ b/no-newline.txt
                @@
                 alpha
                -beta
                +BETA
                """;
        final var result = tool().execute(Map.of("patch", patch), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertTrue(result.success(), () -> result.output());
        final var updatedContent = Files.readString(file);
        assertTrue(updatedContent.endsWith("\n"), () -> updatedContent);
        assertEquals("alpha\nBETA\n", updatedContent);
    }

    @Test
    void descriptionDocumentsBothDialects() {
        final var description = tool().description(testWorkdir);
        assertTrue(description.contains("--- a/"), description);
        assertTrue(description.contains("+++ b/"), description);
        assertTrue(description.contains("*** Begin Patch"), description);
        assertTrue(description.contains("*** Update File"), description);
    }

    @Test
    void ignoresArrayGarbageInAddBody() throws Exception {
        // a stray "+++ b" pseudo-file line inside an Add body must not be treated as content
        final var patch2 = """
                *** Begin Patch
                *** Add File: odd.txt
                +line1
                +line2
                *** End Patch
                """;
        final var result = tool().execute(Map.of("patch", patch2), ToolContext.empty(dir, new DisabledSandbox(dir)));
        assertTrue(result.success(), () -> result.output());
        assertEquals("line1\nline2\n", Files.readString(dir.resolve("odd.txt")));
    }
}
