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
package io.yupiik.ia.agent.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentEvent.ToolTimingEvent;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.OptimizeConfiguration;
import io.yupiik.ia.agent.core.configuration.SandboxConfiguration;
import io.yupiik.ia.agent.core.context.TokenEstimator;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.optimize.AfterToolExecutionEvent;
import io.yupiik.ia.agent.core.optimize.BeforeToolExecutionEvent;
import io.yupiik.ia.agent.core.optimize.GenericOutputProcessor;
import io.yupiik.ia.agent.core.optimize.LogNoiseOutputProcessor;
import io.yupiik.ia.agent.core.optimize.MavenOutputProcessor;
import io.yupiik.ia.agent.core.optimize.OutputProcessor;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.runtime.Cancellations;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.sandbox.SessionSandbox;
import io.yupiik.ia.agent.core.security.ToolExecutionPrepare;
import io.yupiik.ia.agent.core.tools.builtin.EditFileTool;
import io.yupiik.ia.agent.core.tools.builtin.ListSubagentOptionsTool;
import io.yupiik.ia.agent.core.tools.builtin.SkillTool;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolExecutionServiceTest {
    private CodingAgentConfiguration baseline;

    @BeforeEach
    void configuration(@Fusion final CodingAgentConfiguration configuration) {
        baseline = configuration;
    }

    @TempDir
    private static Path testWorkdir;

    @TempDir
    static Path storageHome;

    /** The status reporter is per-session: it now travels in the tool context, not the service. */
    private ToolContext context(final List<String> reports) {
        final var reporter = new StatusReporter();
        reporter.setSink(reports::add);
        return new ToolContext(
                "s1",
                null,
                null,
                null,
                null,
                null,
                reporter,
                null,
                null,
                Cancellations.NEVER,
                null,
                null,
                testWorkdir,
                new DisabledSandbox(testWorkdir));
    }

    private ToolExecutionService service() {
        final var configuration = baseline;
        return new ToolExecutionService(
                configuration,
                new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        // no-op
                    }
                },
                List.of(new GenericOutputProcessor(configuration)));
    }

    @Nested
    @FusionSupport(modules = Module.class)
    class SandboxDeniesSpoofedBuiltinNamesCategoriesAndReadonlyBeforeAnyEventsScenario {
        @Test
        void sandboxDeniesSpoofedBuiltinNamesCategoriesAndReadonlyBeforeAnyEvents() {
            final var events = new ArrayList<Object>();
            final var configuration = baseline;
            final var service = new ToolExecutionService(
                    configuration,
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            events.add(event);
                        }
                    },
                    List.of());
            try (final var sandbox = new SessionSandbox(
                    configuration.sandbox(),
                    testWorkdir,
                    testWorkdir,
                    new PermissionService(testWorkdir) {},
                    storageHome.resolve("scratch"))) {
                for (final var name : List.of("read", "bash", "docs_lookup", "client_read_text_file")) {
                    final var result = service.execute(
                            tool(name, "must not run"), Map.of(), ToolContext.empty(testWorkdir, sandbox));
                    assertFalse(result.success());
                    assertTrue(result.output().contains("Sandbox restriction"), result.output());
                    assertTrue(result.output().contains("Trust is NOT confinement"), result.output());
                }
                assertTrue(events.isEmpty(), "denied integrations must not reach preparation or optimization hooks");
            }
        }
    }

    @Nested
    @FusionSupport(modules = Module.class)
    class SandboxTrustMatchesImplementationIdentityNotToolNameAndDoesNotAcceptWildcardsScenario {
        @Test
        void sandboxTrustMatchesImplementationIdentityNotToolNameAndDoesNotAcceptWildcards() {
            final var tool = tool("read", "integration ran");
            final var id = "java:" + tool.getClass().getName();
            for (final var trust : List.of("read", "READ", "java:*", "mcp:read", id)) {
                final var configuration = new CodingAgentConfiguration(
                        baseline.storageHome(),
                        baseline.home(),
                        baseline.workingDir(),
                        baseline.projectDir(),
                        baseline.provider(),
                        baseline.model(),
                        baseline.llm(),
                        baseline.compaction(),
                        baseline.pruning(),
                        baseline.compression(),
                        baseline.memory(),
                        baseline.todos(),
                        baseline.optimize(),
                        baseline.permissions(),
                        new SandboxConfiguration(
                                baseline.sandbox().enabled(),
                                baseline.sandbox().helper(),
                                baseline.sandbox().filesystem(),
                                baseline.sandbox().network(),
                                List.of(trust),
                                baseline.sandbox().macOs(),
                                baseline.sandbox().environment(),
                                baseline.sandbox().unix()),
                        baseline.mcp(),
                        baseline.mavenRepositoryKnowledge(),
                        baseline.structuralCodeGraph(),
                        baseline.sessions(),
                        baseline.skills(),
                        baseline.agents(),
                        baseline.mode(),
                        baseline.httpClient(),
                        baseline.tools(),
                        baseline.subagents(),
                        baseline.schedulerThreads(),
                        baseline.metrics(),
                        baseline.maxLineLength(),
                        baseline.resumeAliases(),
                        baseline.slashcommands(),
                        baseline.prompt(),
                        baseline.labels(),
                        baseline.logging(),
                        baseline.hooks(),
                        baseline.notifications(),
                        baseline.secrets(),
                        null);
                final var service = new ToolExecutionService(
                        configuration,
                        new Emitter() {
                            @Override
                            public <T> void emit(final T event) {}
                        },
                        List.of());
                try (final var sandbox = new SessionSandbox(
                        configuration.sandbox(),
                        testWorkdir,
                        testWorkdir,
                        new PermissionService(testWorkdir) {},
                        storageHome.resolve("scratch"))) {
                    final var result = service.execute(tool, Map.of(), ToolContext.empty(testWorkdir, sandbox));
                    assertEquals(id.equals(trust), result.success(), trust);
                    if (result.success()) {
                        assertEquals("integration ran", result.output());
                    }
                }
            }
        }
    }

    @Nested
    @FusionSupport(modules = Module.class)
    class SandboxRestoresCatalogButKeepsUnadaptedSkillsDeniedScenario {
        @Test
        void sandboxRestoresCatalogButKeepsUnadaptedSkillsDenied() {
            final var configuration = baseline;
            try (final var sandbox = new SessionSandbox(
                    configuration.sandbox(),
                    testWorkdir,
                    testWorkdir,
                    new PermissionService(testWorkdir) {},
                    storageHome.resolve("scratch"))) {
                final var context = ToolContext.empty(testWorkdir, sandbox);
                // Input validation is observable proof the real catalog was reached, without external
                // services.
                final var catalog = new ListSubagentOptionsTool(null, null, configuration, null, null);
                assertEquals(
                        "kind must be agents, models, or all",
                        service()
                                .execute(catalog, Map.of("kind", "invalid"), context)
                                .output());
                final var skill = service().execute(new SkillTool(configuration), Map.of("name", "any"), context);
                assertFalse(skill.success());
                assertTrue(skill.output().contains("Sandbox restriction"), skill.output());
            }
        }
    }

    private CodingAgentConfiguration config(final int genericMaxLines) {
        final var optimize = baseline.optimize();
        return new CodingAgentConfiguration(
                baseline.storageHome(),
                baseline.home(),
                baseline.workingDir(),
                baseline.projectDir(),
                baseline.provider(),
                baseline.model(),
                baseline.llm(),
                baseline.compaction(),
                baseline.pruning(),
                baseline.compression(),
                baseline.memory(),
                baseline.todos(),
                new OptimizeConfiguration(
                        optimize.enabled(),
                        genericMaxLines,
                        optimize.maxEntries(),
                        optimize.minReductionPercent(),
                        optimize.skipCommandWrapper(),
                        optimize.disabled(),
                        optimize.toolOutputs(),
                        optimize.logNoise()),
                baseline.permissions(),
                baseline.sandbox(),
                baseline.mcp(),
                baseline.mavenRepositoryKnowledge(),
                baseline.structuralCodeGraph(),
                baseline.sessions(),
                baseline.skills(),
                baseline.agents(),
                baseline.mode(),
                baseline.httpClient(),
                baseline.tools(),
                baseline.subagents(),
                baseline.schedulerThreads(),
                baseline.metrics(),
                baseline.maxLineLength(),
                baseline.resumeAliases(),
                baseline.slashcommands(),
                baseline.prompt(),
                baseline.labels(),
                baseline.logging(),
                baseline.hooks(),
                baseline.notifications(),
                baseline.secrets(),
                null);
    }

    private EmbeddedTool tool(final String name, final String output) {
        return new EmbeddedTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description(final Path workdir) {
                return "fake " + name;
            }

            @Override
            public ToolCategory category() {
                return ToolCategory.READ;
            }

            @Override
            public boolean readonly() {
                return true;
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of();
            }

            @Override
            public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                if (output == null) {
                    throw new ToolDiagnosticException("MCP tool test failed", "UI-only quota detail");
                }
                return ToolResult.ok(output);
            }
        };
    }

    @Nested
    @FusionSupport(modules = Module.class)
    class UiFailureDetailIsNotInToolResultOrOptimizationHooksScenario {
        @Test
        void uiFailureDetailIsNotInToolResultOrOptimizationHooks() {
            final var events = new ArrayList<Object>();
            final var service = new ToolExecutionService(
                    baseline,
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            events.add(event);
                        }
                    },
                    List.of());
            final var result = service.execute(tool("test", null), Map.of(), context(List.of()));
            assertFalse(result.success());
            assertEquals("MCP tool test failed", result.output());
            final var diagnostic = events.stream()
                    .filter(AgentEvent.ToolDiagnostic.class::isInstance)
                    .map(AgentEvent.ToolDiagnostic.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertEquals("UI-only quota detail", diagnostic.detail());
            assertEquals("s1", diagnostic.sessionId());
            assertEquals(testWorkdir, diagnostic.workdir());
            assertFalse(diagnostic.toString().contains("UI-only quota detail"));
            assertTrue(events.stream()
                    .filter(AfterToolExecutionEvent.class::isInstance)
                    .map(AfterToolExecutionEvent.class::cast)
                    .allMatch(event -> event.output().equals(result.output())));
            assertTrue(events.stream().noneMatch(event -> event.toString().contains("UI-only quota detail")));
        }
    }

    @Nested
    @FusionSupport(modules = Module.class)
    class DiagnosticObserverFailureDoesNotLeakIntoToolResultScenario {
        @Test
        void diagnosticObserverFailureDoesNotLeakIntoToolResult() {
            final var service = new ToolExecutionService(
                    baseline,
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            if (event instanceof AgentEvent.ToolDiagnostic diagnostic) {
                                throw new IllegalStateException(diagnostic.detail());
                            }
                        }
                    },
                    List.of());
            assertEquals(
                    "MCP tool test failed",
                    service.execute(tool("test", null), Map.of(), context(List.of()))
                            .output());
        }
    }

    @Nested
    @FusionSupport(modules = Module.class)
    class NonBashToolsAreNotReprocessedScenario {
        @Test
        void nonBashToolsAreNotReprocessed() {
            final var output = "line\n".repeat(50);
            final var reports = new ArrayList<String>();
            final var result = service().execute(tool("read", output), Map.of(), context(reports));
            assertEquals(output, result.output(), "read output must not be re-truncated by processors");
            assertTrue(reports.isEmpty(), reports.toString());
        }
    }

    @Nested
    @FusionSupport(modules = Module.class)
    class FatalPreparationFailureIsNotConvertedIntoAModelResultScenario {
        @Test
        void fatalPreparationFailureIsNotConvertedIntoAModelResult() {
            final var fatal = new LinkageError("fatal preparation failure");
            final var service = new ToolExecutionService(
                    baseline,
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            if (event instanceof ToolExecutionPrepare) {
                                throw fatal;
                            }
                        }
                    },
                    List.of());
            assertSame(
                    fatal,
                    assertThrows(
                            LinkageError.class,
                            () -> service.execute(tool("test", "not executed"), Map.of(), context(List.of()))));
        }
    }

    @Nested
    @FusionSupport(modules = Module.class)
    class BashTinyReductionIsIgnoredScenario {
        @Test
        void bashTinyReductionIsIgnored() {
            // 1 trailing whitespace char trimmed over a 100-char line: ~1% reduction, below the 10% floor
            final var output = "x".repeat(100) + " ";
            final var reports = new ArrayList<String>();
            final var result =
                    service().execute(tool("bash", output), Map.of("command", "cat big.txt"), context(reports));
            assertEquals(output, result.output(), "sub-threshold reductions must keep the original output");
            assertTrue(reports.isEmpty(), reports.toString());
        }
    }

    @Nested
    @FusionSupport(modules = Module.class)
    class BashMeaningfulReductionIsAppliedScenario {
        @Test
        void bashMeaningfulReductionIsApplied() {
            final var sb = new StringBuilder();
            for (var i = 0; i < 100; i++) {
                sb.append("kept line ").append(i).append('\n');
                sb.append("   \n");
            }
            final var reports = new ArrayList<String>();
            final var result =
                    service().execute(tool("bash", sb.toString()), Map.of("command", "cat big.txt"), context(reports));
            assertTrue(result.output().length() < sb.length() / 2, result.output());
            assertTrue(result.output().contains("kept line 0"), result.output());
            assertFalse(result.output().contains("   \n"), "blank runs must be compacted");
            assertEquals(1, reports.size(), reports.toString());
            assertEquals(
                    "output optimized by GenericOutputProcessor (" + TokenEstimator.estimate(sb.toString()) + " -> "
                            + TokenEstimator.estimate(result.output()) + " estimated tokens)",
                    reports.get(0));
        }
    }

    @Nested
    @FusionSupport(modules = Module.class)
    class OptimizationEstimatesBothTextsRatherThanDividingSavedCharactersScenario {
        @Test
        void optimizationEstimatesBothTextsRatherThanDividingSavedCharacters() {
            final var output = "value={key:[1,2,3]};\n".repeat(100);
            final var usage = new UsageTracker(null, null, null, Clock.systemUTC());
            final var context = new ToolContext(
                    "s1",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    usage,
                    null,
                    Cancellations.NEVER,
                    null,
                    null,
                    testWorkdir,
                    new DisabledSandbox(testWorkdir));
            final var result = service().execute(tool("bash", output), Map.of("command", "cat big.txt"), context);
            assertEquals(1, usage.optimizedOutputs());
            assertEquals(output.length() - result.output().length(), usage.optimizedCharsSaved());
            assertEquals(
                    TokenEstimator.estimate(output) - TokenEstimator.estimate(result.output()),
                    usage.optimizedTokensSaved());
            assertTrue(usage.optimizedTokensSaved() > usage.optimizedCharsSaved() / 4);
        }
    }

    @Nested
    @FusionSupport(modules = PerToolDisabledProcessorBehavesAsIdentityScenarioModule.class)
    class PerToolDisabledProcessorBehavesAsIdentityScenario {
        @Test
        void perToolDisabledProcessorBehavesAsIdentity() {
            final var configuration = baseline;
            final var service = new ToolExecutionService(
                    configuration,
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            // no-op
                        }
                    },
                    List.of(new GenericOutputProcessor(configuration)));
            final var output = "kept line 0\nkept line 1\n".repeat(50);
            final var reports = new ArrayList<String>();
            final var result =
                    service.execute(tool("bash", output), Map.of("command", "cat big.txt"), context(reports));
            assertEquals(output, result.output(), "disabled processor must leave the output byte-identical");
            assertTrue(reports.isEmpty(), reports.toString());
        }
    }

    @Nested
    @FusionSupport(modules = GlobalDisabledStillWinsScenarioModule.class)
    class GlobalDisabledStillWinsScenario {
        @Test
        void globalDisabledStillWins() {
            final var configuration = baseline;
            final var service = new ToolExecutionService(
                    configuration,
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            // no-op
                        }
                    },
                    List.of(new GenericOutputProcessor(configuration)));
            final var output = "line\n".repeat(50);
            final var reports = new ArrayList<String>();
            final var result =
                    service.execute(tool("bash", output), Map.of("command", "cat big.txt"), context(reports));
            assertEquals(output, result.output());
            assertTrue(reports.isEmpty());
        }
    }

    @Nested
    @FusionSupport(modules = CommandWrapperIsUnwrappedBeforeProcessorDispatchScenarioModule.class)
    class CommandWrapperIsUnwrappedBeforeProcessorDispatchScenario {
        @Test
        void commandWrapperIsUnwrappedBeforeProcessorDispatch() {
            // a `sudo timeout 120 mvn ...` command must engage the maven processor (head unwrapped to
            // `mvn`)
            final var configuration = baseline;
            final var service = serviceWith(
                    configuration, List.of(new MavenOutputProcessor(new LogNoiseOutputProcessor(configuration))));
            final var output = String.join(
                    "\n",
                    "[INFO] Scanning for projects...",
                    "[INFO] Building yca-core 1.0.0-SNAPSHOT",
                    "[INFO] Tests run: 4, Failures: 1, Errors: 0, Skipped: 0",
                    "[ERROR] cannot find symbol",
                    "[INFO] BUILD FAILURE");
            final var reports = new ArrayList<String>();
            final var result = service.execute(
                    tool("bash", output), Map.of("command", "sudo timeout 120 mvn package"), context(reports));
            assertEquals(1, reports.size(), reports.toString());
            assertTrue(reports.get(0).contains("output optimized by MavenOutputProcessor"), reports.toString());
            // the maven processor collapsed the run: only the decisive lines survive, noise is gone
            assertTrue(result.output().contains("[INFO] BUILD FAILURE"));
            assertFalse(result.output().contains("Scanning for projects..."));
        }
    }

    @Nested
    @FusionSupport(modules = Module.class)
    class MavenInformationalOutputSurvivesCommandWrapperUnwrappingScenario {
        @Test
        void mavenInformationalOutputSurvivesCommandWrapperUnwrapping() {
            final var service = serviceWithScrambled(config(100));
            final var version = String.join(
                    "\n",
                    "Apache Maven 4.0.0-rc-5",
                    "Maven home: /opt/maven",
                    "Java version: 25.0.2, vendor: Oracle Corporation, runtime: /opt/java",
                    "Default locale: en_US, platform encoding: UTF-8",
                    "OS name: \"linux\", arch: \"amd64\", family: \"unix\"",
                    "Exit code: 0");
            final var help = String.join(
                    "\n",
                    "usage: mvn [options] [<goal(s)>] [<phase(s)>]",
                    "Options:",
                    " -h,--help       Display help information",
                    " -v,--version    Display version information",
                    "Exit code: 0");
            for (final var entry : Map.of(
                            "sudo timeout 120 mvn -v", version,
                            "env MAVEN_OPTS=-Xmx1g mvn --version", version,
                            "command mvn -h", help,
                            "sh -c 'mvn --help'", help)
                    .entrySet()) {
                final var result = service.execute(
                        tool("bash", entry.getValue()), Map.of("command", entry.getKey()), context(new ArrayList<>()));
                assertTrue(result.success(), entry.getKey());
                assertEquals(entry.getValue(), result.output(), entry.getKey());
            }
        }
    }

    @Nested
    @FusionSupport(modules = CommandWrapperUnwrappingCanBeDisabledScenarioModule.class)
    class CommandWrapperUnwrappingCanBeDisabledScenario {
        @Test
        void commandWrapperUnwrappingCanBeDisabled() {
            // without the wrapper stripping the head stays `sudo` and maven's supports() is false, so the
            // output is left unoptimized (no processable command matches, no reduction is reported)
            final var configuration = baseline;
            final var service = serviceWith(
                    configuration, List.of(new MavenOutputProcessor(new LogNoiseOutputProcessor(configuration))));
            final var output = String.join(
                    "\n",
                    "[INFO] Scanning for projects...",
                    "[INFO] Building yca-core 1.0.0-SNAPSHOT",
                    "[INFO] Tests run: 4, Failures: 1, Errors: 0, Skipped: 0",
                    "[ERROR] cannot find symbol",
                    "[INFO] BUILD FAILURE");
            final var reports = new ArrayList<String>();
            final var result = service.execute(
                    tool("bash", output), Map.of("command", "sudo timeout 120 mvn package"), context(reports));
            // maven must not have been engaged (head stayed `sudo`), so nothing meaningful was optimized
            // away
            assertTrue(reports.isEmpty(), reports.toString());
            assertTrue(result.output().contains("Scanning for projects..."));
        }
    }

    @Nested
    @FusionSupport(modules = Module.class)
    class MavenSummarizesWhenStandaloneLogNoiseReductionIsZeroScenario {
        @Test
        void mavenSummarizesWhenStandaloneLogNoiseReductionIsZero() {
            // no framework log-noise lines, so a standalone LogNoise pass reduces nothing; Maven (order
            // 10)
            // must still run first and emit its summary
            final var configuration = baseline;
            final var sb = new StringBuilder();
            sb.append("[INFO] Scanning for projects...\n");
            sb.append("[INFO] Building yca-core 1.0.0-SNAPSHOT\n");
            for (var i = 1; i <= 60; i++) {
                sb.append("[INFO] Running com.example.Test").append(i).append('\n');
                sb.append("[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\n");
            }
            sb.append("[INFO] BUILD SUCCESS");
            final var reports = new ArrayList<String>();
            final var result = serviceWithScrambled(configuration)
                    .execute(tool("bash", sb.toString()), Map.of("command", "mvn test"), context(reports));
            assertTrue(result.output().contains("[INFO] BUILD SUCCESS"), result.output());
            assertTrue(result.output().contains("Passing tests (60)"), result.output());
            assertFalse(result.output().contains("Scanning for projects..."), result.output());
            // only the maven processor reports an optimization, proving it was the accepted first
            // candidate
            assertEquals(1, reports.size(), reports.toString());
            assertTrue(reports.get(0).contains("MavenOutputProcessor"), reports.toString());
        }
    }

    @Nested
    @FusionSupport(modules = Module.class)
    class MavenSummarizesEvenWithAboveThresholdFrameworkNoiseScenario {
        @Test
        void mavenSummarizesEvenWithAboveThresholdFrameworkNoise() {
            // the maven run is interleaved with a mass of framework log lines that a standalone LogNoise
            // pass
            // would drop above minReduction; Maven (order 10) still runs first and filters them itself
            final var configuration = baseline;
            final var sb = new StringBuilder();
            sb.append("[INFO] Scanning for projects...\n");
            sb.append("[INFO] Building yca-core 1.0.0-SNAPSHOT\n");
            for (var i = 1; i <= 60; i++) {
                sb.append("2026-10-28T10:00:0")
                        .append(i % 10)
                        .append(" [main] DEBUG o.e.c.CacheManager - warming ")
                        .append(i)
                        .append('\n');
                sb.append("[INFO] Running com.example.Test").append(i).append('\n');
                sb.append("2026-10-28T10:00:0")
                        .append(i % 10)
                        .append(" [main] INFO  o.e.c.Engine - hydrated ")
                        .append(i)
                        .append('\n');
                sb.append("[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\n");
            }
            sb.append("[INFO] BUILD SUCCESS");
            final var reports = new ArrayList<String>();
            final var result = serviceWithScrambled(configuration)
                    .execute(tool("bash", sb.toString()), Map.of("command", "mvn test"), context(reports));
            assertTrue(result.output().contains("[INFO] BUILD SUCCESS"), result.output());
            assertTrue(result.output().contains("Passing tests (60)"), result.output());
            assertFalse(result.output().contains("Scanning for projects..."), result.output());
            // the framework log noise is gone, and only maven reports the accepted optimization
            assertFalse(result.output().contains("DEBUG"), result.output());
            assertFalse(result.output().contains("o.e.c.Engine"), result.output());
            assertEquals(1, reports.size(), reports.toString());
            assertTrue(reports.get(0).contains("MavenOutputProcessor"), reports.toString());
        }
    }

    @Nested
    @FusionSupport(modules = Module.class)
    class AllCandidatesBelowThresholdReturnExactOriginalScenario {
        @Test
        void allCandidatesBelowThresholdReturnExactOriginal() {
            // no candidate reaches minReduction, so the EXACT original comes back unchanged
            final var configuration = baseline;
            final var output = "[INFO] BUILD SUCCESS";
            final var reports = new ArrayList<String>();
            final var result = serviceWithScrambled(configuration)
                    .execute(tool("bash", output), Map.of("command", "mvn test"), context(reports));
            assertEquals(output, result.output(), "sub-threshold candidates must preserve the exact original");
            assertTrue(reports.isEmpty(), reports.toString());
        }
    }

    @Nested
    @FusionSupport(modules = Module.class)
    class GenericWinsWhenMavenReductionIsBelowThresholdScenario {
        @Test
        void genericWinsWhenMavenReductionIsBelowThreshold() {
            // maven keeps the actionable ERROR lines, so its reduction is below the 10% floor and it is
            // rejected; the later generic truncation (10 lines) then accepts the original.
            final var configuration = baseline;
            final var sb = new StringBuilder();
            sb.append("[INFO] Scanning for projects...\n");
            sb.append("[INFO] BUILD FAILURE\n");
            for (var i = 1; i <= 20; i++) {
                sb.append("[ERROR] Failed to execute goal for module demo-")
                        .append(i)
                        .append(": cannot resolve dependency artifact-")
                        .append(i)
                        .append('\n');
            }
            sb.append("Exit code: 1");
            final var reports = new ArrayList<String>();
            final var result = serviceWithScrambled(configuration)
                    .execute(tool("bash", sb.toString()), Map.of("command", "mvn package"), context(reports));
            assertEquals(1, reports.size(), reports.toString());
            assertTrue(reports.get(0).contains("Generic"), reports.toString());
            assertTrue(result.output().lines().count() <= 11, result.output());
        }
    }

    private ToolExecutionService serviceWith(
            final CodingAgentConfiguration configuration, final List<OutputProcessor> processors) {
        return new ToolExecutionService(
                configuration,
                new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        // no-op
                    }
                },
                processors);
    }

    /**
     * Injects the real maven/logNoise/generic processors in a SCRAMBLED order that the dispatch must
     * re-sort back to maven(10) -> logNoise(100) -> generic(1000) via {@code order()}.
     */
    private ToolExecutionService serviceWithScrambled(final CodingAgentConfiguration configuration) {
        return serviceWith(
                configuration,
                List.of(
                        new GenericOutputProcessor(configuration),
                        new LogNoiseOutputProcessor(configuration),
                        new MavenOutputProcessor(new LogNoiseOutputProcessor(configuration))));
    }

    @Nested
    @FusionSupport(modules = Module.class)
    class EditFileMissingPathSurfacesFriendlyErrorNotNpeScenario {
        @Test
        void editFileMissingPathSurfacesFriendlyErrorNotNpe() {
            // exercising the real edit_file through the execution service (the layer that historically
            // wrapped
            // the Path.of(null) NPE into "Tool edit_file failed: java.lang.NullPointerException"): a
            // missing
            // path must now come back as a clear "path is required" error, never a crash wrapper
            final var reports = new ArrayList<String>();
            final var configuration = baseline;
            final var result = new ToolExecutionService(
                            configuration,
                            new Emitter() {
                                @Override
                                public <T> void emit(final T event) {
                                    // no-op
                                }
                            },
                            List.of(new GenericOutputProcessor(configuration)))
                    .execute(new EditFileTool(), Map.of("oldString", "x", "newString", "y"), context(reports));
            assertFalse(result.success(), result.output());
            assertTrue(result.output().contains("path is required"), result.output());
            assertFalse(result.output().contains("NullPointerException"), result.output());
            assertFalse(result.output().contains("Tool edit_file failed"), result.output());
        }
    }

    @Nested
    @FusionSupport(modules = Module.class)
    class ThrowingEventObserverDoesNotAbortTheToolResultScenario {
        @Test
        void throwingEventObserverDoesNotAbortTheToolResult() {
            // a misbehaving observer on the execution-bus events that AgentLoop depends on to close the
            // tool card
            // (a ToolUsePost hook, the code-graph refresh, a timing listener...) must never lose the tool
            // result: a
            // throw used to propagate out of execute() and skip the AgentLoop tool-finished emission,
            // leaving the TUI
            // card stuck "running" forever. Every one of those emissions is now shielded, so the tool's
            // own outcome
            // must come back intact even if all of them throw. ToolExecutionPrepare is intentionally NOT
            // targeted: it
            // sits inside execute()'s tool body try/catch and is by-design normalized into a failed
            // ToolResult.
            final var configuration = baseline;
            final var service = new ToolExecutionService(
                    configuration,
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            if (event instanceof BeforeToolExecutionEvent
                                    || event instanceof AfterToolExecutionEvent
                                    || event instanceof ToolTimingEvent) {
                                throw new IllegalStateException(
                                        "observer boom: " + event.getClass().getSimpleName());
                            }
                        }
                    },
                    List.of(new GenericOutputProcessor(configuration)));
            final var output = "some bash output";
            final var reports = new ArrayList<String>();
            final var result = service.execute(tool("bash", output), Map.of("command", "mvn test"), context(reports));
            assertTrue(result.success(), result.output());
            assertEquals(
                    output, result.output(), "a throwing post-execution event observer must not lose the tool result");
        }
    }

    public static class Module extends ConfigurationTestModule {
        public Module() {
            super(Map.of("yca.storageHome", storageHome.toString(), "yca.optimize.genericMaxLines", "10"));
        }
    }

    public static class PerToolDisabledProcessorBehavesAsIdentityScenarioModule extends ConfigurationTestModule {
        public PerToolDisabledProcessorBehavesAsIdentityScenarioModule() {
            super(Map.of(
                    "yca.storageHome", storageHome.toString(), "yca.optimize.toolOutputs.generic.enabled", "false"));
        }
    }

    public static class GlobalDisabledStillWinsScenarioModule extends ConfigurationTestModule {
        public GlobalDisabledStillWinsScenarioModule() {
            super(Map.of(
                    "yca.storageHome", storageHome.toString(),
                    "yca.optimize.toolOutputs.generic.enabled", "true",
                    "yca.optimize.disabled", "generic"));
        }
    }

    public static class CommandWrapperIsUnwrappedBeforeProcessorDispatchScenarioModule extends ConfigurationTestModule {
        public CommandWrapperIsUnwrappedBeforeProcessorDispatchScenarioModule() {
            super(Map.of("yca.storageHome", storageHome.toString()));
        }
    }

    public static class CommandWrapperUnwrappingCanBeDisabledScenarioModule extends ConfigurationTestModule {
        public CommandWrapperUnwrappingCanBeDisabledScenarioModule() {
            super(Map.of("yca.storageHome", storageHome.toString(), "yca.optimize.skipCommandWrapper", "false"));
        }
    }
}
