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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.shell.AnsiShellFrontend;
import io.yupiik.ia.agent.core.shell.ToolOutputVisibility;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
@ExtendWith(TestJsonMapper.class)
class CliCommandTest {
    @TempDir
    private static Path eventWorkdir;

    private PrintStream originalOut;
    private PrintStream originalErr;
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void capture() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(ByteArrayOutputStream.nullOutputStream(), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    void diagnosticIsVisibleEvenWithHiddenTools(final JsonMapper mapper) {
        final var dispatcher = dispatcher("compact", ToolOutputVisibility.HIDDEN, mapper);
        dispatcher.accept(new AgentEvent.ToolDiagnostic(
                "mcp_example", "private failure\nsecond line", 42, "s1", "child", "Worker", eventWorkdir));
        final var stderr = err.toString(StandardCharsets.UTF_8);
        assertTrue(stderr.contains("private failure\nsecond line"), stderr);
        assertTrue(stderr.contains("[Worker]"), stderr);
        assertTrue(stderr.contains("#42"), stderr);
    }

    @Test
    void verbosePrintsFullOutputForNonStreamedTool(final JsonMapper mapper) throws Exception {
        final var dispatcher = dispatcher("verbose", ToolOutputVisibility.SHOWN, mapper);

        dispatcher.accept(new AgentEvent.ToolStarted("read", "{}", "s1", eventWorkdir));
        dispatcher.accept(new AgentEvent.ToolFinished("read", "line1\nline2", true, "s1", eventWorkdir));

        final var stderr = err.toString(StandardCharsets.UTF_8);
        assertTrue(stderr.contains("line1"), "verbose should show the full non-streamed output:\n" + stderr);
        assertTrue(stderr.contains("line2"));
    }

    @Test
    void verboseDoesNotReprintStreamedToolOutput(final JsonMapper mapper) throws Exception {
        final var dispatcher = dispatcher("verbose", ToolOutputVisibility.SHOWN, mapper);

        dispatcher.accept(new AgentEvent.ToolStarted("bash", "{}", "s1", eventWorkdir));
        dispatcher.accept(new AgentEvent.ToolStream("bash", "streamed line", "s1", eventWorkdir));
        dispatcher.accept(new AgentEvent.ToolFinished("bash", "streamed line", true, "s1", eventWorkdir));

        final var stderr = err.toString(StandardCharsets.UTF_8);
        // streamed output must be printed live but not re-printed from ToolFinished.output
        assertTrue(stderr.contains("streamed line"));
        assertFalse(
                stderr.lines()
                                .filter(l -> !l.isBlank() && l.contains("streamed line"))
                                .count()
                        > 2,
                "streamed output should not be duplicated:\n" + stderr);
    }

    @Test
    void cliConfDefaultsAutoOpenBrowserToTrue() {
        final var conf = new CliCommand.CliConf("", "", "", false, false, "compact", false, "collapsed", false);
        assertTrue(conf.autoOpenBrowser(), "cli.autoOpenBrowser must default to true");
    }

    private static Consumer<AgentEvent> dispatcher(
            final String presentation, final ToolOutputVisibility showToolOutput, final JsonMapper mapper) {
        final var frontend = new AnsiShellFrontend(
                new MarkdownColorizer(false),
                null,
                new Symbols(false),
                new ToolPresenter(mapper),
                CliVerbosity.parse(presentation),
                false,
                false,
                showToolOutput);
        return frontend::onEvent;
    }
}
