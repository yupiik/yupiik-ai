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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
class ToolPresenterTest {
    private JsonMapper mapper;
    private ToolPresenter presenter;

    @BeforeEach
    void setUp(final JsonMapper mapper) {
        this.mapper = mapper;
        presenter = new ToolPresenter(mapper);
    }

    @Test
    void askUserResultRendersKeyValueLines() {
        assertEquals("q1: yes\nq2: a, b", presenter.result("ask_user", "{\"q1\":\"yes\",\"q2\":[\"a\",\"b\"]}", true));
    }

    @Test
    void malformedAskUserResultFallsBackToTruncation() {
        final var rendered = presenter.result("ask_user", "{oops", true);
        assertTrue(rendered.contains("{oops"));
    }

    @Test
    void summarizesBashCommand() {
        assertEquals("bash(mvn -q test)", presenter.summarize("bash", "{\"command\":\"mvn -q test\"}"));
    }

    @Test
    void summarizesRepositoryGavs() {
        assertEquals(
                "maven_repository_knowledge([g:a:1, g:b:2])",
                presenter.summarize("maven_repository_knowledge", "{\"gavs\":[\"g:a:1\",\"g:b:2\"]}"));
    }

    @Test
    void executionStatusOnlyRecognizesNonzeroBashTrailer() {
        assertEquals("execution exit code: 7", presenter.executionDiagnostic("bash", "output\nExit code: 7"));
        assertEquals("", presenter.executionDiagnostic("bash", "output\nExit code: 0"));
        assertEquals("", presenter.executionDiagnostic("read", "Exit code: 7"));
        assertEquals("", presenter.executionDiagnostic("bash", "Exit code: 7\nmore output"));
        assertEquals("", presenter.executionDiagnostic("bash", null));
    }

    @Test
    void significantCommandKeepsSimpleCommand() {
        assertEquals("mvn -q test", ToolPresenter.significantCommand("mvn -q test"));
    }

    @Test
    void significantCommandSkipsNavigationPreamble() {
        assertEquals("ls -la", ToolPresenter.significantCommand("cd /foo && ls -la"));
        assertEquals("mvn -q test", ToolPresenter.significantCommand("cd /foo && cd bar && mvn -q test"));
        assertEquals("ls -la", ToolPresenter.significantCommand("export FOO=1; ls -la"));
    }

    @Test
    void significantCommandKeepsFirstRealSegment() {
        assertEquals("mvn test", ToolPresenter.significantCommand("mvn test && echo done"));
    }

    @Test
    void significantCommandDropsEnvAssignments() {
        assertEquals("mvn test", ToolPresenter.significantCommand("FOO=bar mvn test"));
        assertEquals("mvn test", ToolPresenter.significantCommand("FOO=\"a b\" BAR=2 mvn test"));
    }

    @Test
    void significantCommandIgnoresSeparatorsInsideQuotes() {
        assertEquals("echo \"a && b\"", ToolPresenter.significantCommand("echo \"a && b\""));
        assertEquals("ls -la", ToolPresenter.significantCommand("cd /a; ls -la"));
    }

    @Test
    void significantCommandFlattensLineContinuations() {
        assertEquals("mvn test", ToolPresenter.significantCommand("cd /w && \\\n  mvn test"));
    }

    @Test
    void significantCommandIsBlankSafe() {
        assertEquals("", ToolPresenter.significantCommand(null));
        assertEquals("", ToolPresenter.significantCommand("   \n  "));
    }

    @Test
    void summarizesBashWithWorkdir() {
        assertEquals("bash(ls · build)", presenter.summarize("bash", "{\"command\":\"ls\",\"workdir\":\"build\"}"));
    }

    @Test
    void summarizesFileTools() {
        assertEquals(
                "read(src/Foo.java: 500 lines)",
                presenter.summarize("read", "{\"path\":\"src/Foo.java\",\"limit\":500}"));
        assertEquals(
                "write_file(pom.xml)", presenter.summarize("write_file", "{\"path\":\"pom.xml\",\"content\":\"x\"}"));
        assertEquals(
                "edit_file(pom.xml)",
                presenter.summarize("edit_file", "{\"path\":\"pom.xml\",\"oldString\":\"a\",\"newString\":\"b\"}"));
    }

    @Test
    void summarizesSearchTools() {
        assertEquals("grep(Foo in src)", presenter.summarize("grep", "{\"pattern\":\"Foo\",\"path\":\"src\"}"));
        assertEquals("glob(**/*.java)", presenter.summarize("glob", "{\"pattern\":\"**/*.java\"}"));
        assertEquals("read(src/main)", presenter.summarize("read", "{\"path\":\"src/main\"}"));
    }

    @Test
    void summarizesTodosAndUnknown() {
        assertEquals(
                "write_todo(2 items)",
                presenter.summarize("write_todo", "{\"todos\":[{\"content\":\"a\"},{\"content\":\"b\"}]}"));
        final var unknown = presenter.summarize("some_mcp_tool", "{\"query\":\"hello world\"}");
        assertTrue(unknown.startsWith("some_mcp_tool("));
    }

    @Test
    void editDiffShowsAddAndRemove() {
        final var diff = presenter.diff(
                "edit_file", "{\"path\":\"a\",\"oldString\":\"old line\",\"newString\":\"new line\"}", 5);
        assertEquals(List.of("requested replacement: a (single match)", "- old line", "+ new line"), diff);
    }

    @Test
    void editDiffTruncatesLong() {
        final var diff = presenter.diff(
                "edit_file", "{\"path\":\"a\",\"oldString\":\"l1\\nl2\\nl3\\nl4\\nl5\\nl6\",\"newString\":\"\"}", 5);
        assertEquals(6, diff.size());
        assertTrue(diff.get(5).contains("more"));
    }

    @Test
    void writeDiffCapsAtMaxLines() {
        final var diff = presenter.diff("write_file", "{\"path\":\"a\",\"content\":\"1\\n2\\n3\\n4\\n5\\n6\\n7\"}", 3);
        assertTrue(diff.isEmpty(), "write content is not evidence of added lines");
    }

    @Test
    void nonEditToolsHaveNoDiff() {
        assertTrue(presenter.diff("bash", "{\"command\":\"ls\"}", 5).isEmpty());
    }

    @Test
    void resultDigestPicksTailOnSuccess() {
        assertEquals("BUILD SUCCESS", presenter.result("bash", "line1\nBUILD SUCCESS\n    \n", true));
    }

    @Test
    void resultDigestPicksHeadOnFailure() {
        assertEquals("Error: boom", presenter.result("bash", "Error: boom\ntrace\ntrace", false));
    }

    @Test
    void resultDigestHandlesBlank() {
        assertEquals("done", presenter.result("bash", "   ", true));
        assertEquals("failed", presenter.result("bash", "", false));
    }

    @Test
    void malformedArgsDoNotThrow() {
        assertEquals("read(?)", presenter.summarize("read", "{not json"));
        assertTrue(presenter.diff("edit_file", "{not json}", 5).isEmpty());
    }

    @Test
    void requestsKeepEverySuppliedFieldAndWhitespaceForAllBuiltins() {
        final var names = List.of(
                "bash",
                "read",
                "write_file",
                "edit_file",
                "apply_patch",
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
                "code_find_code");
        final var args =
                "{\"scope\":\"project\",\"fact\":\"  fact  \n\",\"enabled\":false,\"limit\":0}".replace("\n", "\\n");
        for (final var name : names) {
            final var lines = presenter.request(name, args, 100);
            assertTrue(lines.contains("scope: project"), name + lines);
            assertTrue(lines.contains("    fact  "), name + lines);
            assertTrue(lines.contains("enabled: false"), name + lines);
            assertTrue(lines.contains("limit: 0"), name + lines);
        }
    }

    @Test
    void requestsRenderRealMemoryAndQuestionSchemas() {
        assertEquals("memory_write(use braces)", presenter.summarize("memory_write", "{\"fact\":\"use braces\"}"));
        final var questions = "{\"questions\":[{\"id\":\"q1\",\"question\":\"Which?\",\"options\":[\"one\",\"two\"]}]}";
        final var rendered = String.join("\n", presenter.request("ask_user", questions, 100));
        assertTrue(rendered.contains("question: Which?"), rendered);
        assertTrue(rendered.contains("[1]: two"), rendered);
        assertEquals("ask_user(1 questions)", presenter.summarize("ask_user", questions));
    }

    @Test
    void externalRequestsArePrettyNestedJsonAndMalformedRequestsRemainVisible() {
        final var rendered =
                String.join("\n", presenter.request("mcp_search", "{\"nested\":{\"items\":[1,false]}}", 100));
        assertEquals("{\"nested\":{\"items\":[1,false]}}", mapper.toString(mapper.fromString(Object.class, rendered)));
        assertTrue(rendered.contains("\n    \"items\":"), rendered);
        assertEquals(List.of("requested arguments (raw):", "{bad"), presenter.request("external", "{bad", 100));
        assertTrue(
                presenter.request("external", "{\"a\":1,\"b\":2}", 1).getLast().contains("more lines"));
    }

    @Test
    void requestedDiffPreservesWhitespaceScopeAndPatchDialects() {
        final var edit = presenter.diff(
                "edit_file",
                mapper.toString(Map.of("path", "a", "oldString", " \n", "newString", "\t\n", "replaceAll", true)),
                100);
        assertEquals(List.of("requested replacement: a (all matches)", "-  ", "- ", "+ \t", "+ "), edit);
        for (final var patch : List.of(
                "--- a/x\n+++ b/x\n@@ -1 +1 @@\n- a\n+ b\n",
                "*** Begin Patch\n*** Update File: x\n*** Move to: y\n@@\n- a\n+ b\n*** End Patch")) {
            final var lines = presenter.diff("apply_patch", mapper.toString(Map.of("patch", patch)), 100);
            assertEquals("requested patch:\n" + patch, String.join("\n", lines));
        }
    }
}
