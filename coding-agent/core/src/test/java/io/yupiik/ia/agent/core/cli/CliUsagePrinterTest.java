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
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.shell.AnsiShellFrontend;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.shell.ShellSession;
import io.yupiik.ia.agent.core.shell.ToolOutputVisibility;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
@FusionSupport(modules = ConfigurationTestModule.class)
class CliUsagePrinterTest {
    @Fusion
    private CodingAgentConfiguration configuration;

    @TempDir
    private static Path eventWorkdir;

    @TempDir
    private static Path testWorkdir;

    @Test
    void cachedPercentageUsesFullContextInput(final JsonMapper mapper) {
        final var tracker = new UsageTracker(null, null, null, Clock.systemUTC());
        final var output = printUsage(mapper, tracker, new Usage(5, 2, 7, 100, 20, 0, 0, 125));

        assertTrue(output.contains("cached 100 (80%)"), output);
    }

    @Test
    void backgroundTokensIncludeFullAuxiliaryInputAndCompletion(final JsonMapper mapper) {
        final var tracker = new UsageTracker(null, null, null, Clock.systemUTC());
        final var foreground = new Usage(5, 2, 7, 100, 20, 0, 0, 125);
        tracker.record(foreground);
        tracker.recordAuxiliary(new Usage(10, 8, 18, 60, 30, 0, 0, 100));
        tracker.recordAuxiliary(new Usage(100, 8, 108, 60, 0, 0, 0, 100));

        final var output = printUsage(mapper, tracker, foreground);

        assertTrue(output.contains("216 background tokens (compaction/memory)"), output);
    }

    private String printUsage(final JsonMapper mapper, final UsageTracker tracker, final Usage usage) {
        final var symbols = new Symbols(true);
        final var frontend = new AnsiShellFrontend(
                new MarkdownColorizer(false),
                null,
                symbols,
                new ToolPresenter(mapper),
                CliVerbosity.COMPACT,
                false,
                false,
                ToolOutputVisibility.COLLAPSED);
        frontend.onEvent(new AgentEvent.UsageUpdated(usage, 68, 14, "s1", eventWorkdir));
        final var session = new ShellSession()
                .runtime(new SessionRuntime(
                        null,
                        new PermissionService(configuration, testWorkdir),
                        null,
                        tracker,
                        null,
                        null,
                        null,
                        null,
                        testWorkdir,
                        new DisabledSandbox(testWorkdir)));
        final var lines = new ArrayList<String>();
        final var printer = new CliUsagePrinter(
                new CliCommand.CliConf("", "", "", false, true, "compact", false, "collapsed", true),
                session,
                frontend,
                symbols,
                ignored -> {},
                () -> {},
                lines::add);

        printer.printUsage();

        assertEquals(1, lines.size());
        return lines.getFirst();
    }
}
