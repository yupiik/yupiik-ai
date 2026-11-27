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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.tools.ToolContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WriteFileToolTest {

    @Test
    void writesAbsolutePathAsGiven(@TempDir final Path root) throws Exception {
        final var target = root.resolve("abs.txt");
        final var result = new WriteFileTool()
                .execute(
                        Map.of("path", target.toString(), "content", "hello"),
                        ToolContext.empty(root, new DisabledSandbox(root)));
        assertTrue(result.success(), result.output());
        assertEquals("hello", Files.readString(target));
    }

    @Test
    void relativePathWritesUnderSessionProjectDir(@TempDir final Path projectDir) throws Exception {
        // a relative path must land inside the session root, NOT the JVM working directory
        final var result = new WriteFileTool()
                .execute(
                        Map.of("path", "analyzis/out.txt", "content", "payload"),
                        ToolContext.empty(projectDir, new DisabledSandbox(projectDir)));
        assertTrue(result.success(), result.output());
        assertEquals("payload", Files.readString(projectDir.resolve("analyzis/out.txt")));
    }

    @Test
    void relativeWriteSurfacesTargetNotice(@TempDir final Path sessionDir) throws Exception {
        // A relative write surfaces its actual session target without using process-global configuration.
        final var tool = new WriteFileTool();
        final var result = tool.execute(
                Map.of("path", "analyzis/out.txt", "content", "payload"),
                ToolContext.empty(sessionDir, new DisabledSandbox(sessionDir)));
        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("target (session root"), result.output());
        assertEquals("payload", Files.readString(sessionDir.resolve("analyzis/out.txt")));
    }

    @Test
    void editRelativePathTargetsSessionProjectDir(@TempDir final Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("edit.txt"), "before\n");
        final var result = new EditFileTool()
                .execute(
                        Map.of("path", "edit.txt", "oldString", "before", "newString", "after"),
                        ToolContext.empty(projectDir, new DisabledSandbox(projectDir)));
        assertTrue(result.success(), result.output());
        assertEquals("after\n", Files.readString(projectDir.resolve("edit.txt")));
    }

    @Test
    void listRelativePathTargetsSessionProjectDir(@TempDir final Path projectDir) throws IOException {
        Files.createDirectories(projectDir.resolve("sub"));
        Files.writeString(projectDir.resolve("sub/a.txt"), "x");
        final var result = new ReadFileTool()
                .execute(Map.of("path", "sub"), ToolContext.empty(projectDir, new DisabledSandbox(projectDir)));
        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("a.txt"), result.output());
    }

    @Test
    void relativePathEscapingSessionRootIsRefused(@TempDir final Path projectDir) throws Exception {
        // ../.. outside the session root must be rejected, not silently written to another tree
        final var result = new WriteFileTool()
                .execute(
                        Map.of("path", "../../escape.txt", "content", "nope"),
                        ToolContext.empty(projectDir, new DisabledSandbox(projectDir)));
        assertFalse(result.success(), result.output());
        assertTrue(result.output().contains("escapes"), result.output());
    }

    @Test
    void editRelativePathEscapingSessionRootIsRefused(@TempDir final Path projectDir) throws Exception {
        final var result = new EditFileTool()
                .execute(
                        Map.of("path", "../../escape.txt", "oldString", "x", "newString", "y"),
                        ToolContext.empty(projectDir, new DisabledSandbox(projectDir)));
        assertFalse(result.success(), result.output());
        assertTrue(result.output().contains("escapes"), result.output());
    }

    @Test
    void absolutePathStillWritesOutsideSessionRoot(@TempDir final Path projectDir, @TempDir final Path outside)
            throws Exception {
        // an explicit absolute path is a deliberate caller choice and must not be blocked
        final var target = outside.resolve("kept.txt");
        final var result = new WriteFileTool()
                .execute(
                        Map.of("path", target.toString(), "content", "ok"),
                        ToolContext.empty(projectDir, new DisabledSandbox(projectDir)));
        assertTrue(result.success(), result.output());
        assertEquals("ok", Files.readString(target));
    }

    @Test
    void missingPathReturnsFriendlyErrorInsteadOfNpe(@TempDir final Path root) {
        // a null/missing "path" must surface a clear "path is required", never Path.of(null) -> NPE
        final var write =
                new WriteFileTool().execute(Map.of("content", "x"), ToolContext.empty(root, new DisabledSandbox(root)));
        assertFalse(write.success(), write.output());
        assertEquals("path is required", write.output());
    }

    @Test
    void missingPathForEditReturnsFriendlyErrorInsteadOfNpe(@TempDir final Path root) {
        final var edit = new EditFileTool()
                .execute(
                        Map.of("oldString", "x", "newString", "y"), ToolContext.empty(root, new DisabledSandbox(root)));
        assertFalse(edit.success(), edit.output());
        assertEquals("path is required", edit.output());
    }
}
