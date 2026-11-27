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
package io.yupiik.ia.agent.core.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.BashPermissionsConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.LlmConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.configuration.PermissionsConfiguration;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.session.SessionMetadata;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionSystemPromptTest {
    private static final String WORKSPACE_ELISION = "[...leading workspace context elided...]\n";

    @TempDir
    Path home;

    private SystemPromptBuilder builder(final CodingAgentConfiguration config, final PermissionService permissions) {
        return new SystemPromptBuilder(
                config, null, null, permissions, null, null, new TodoPromptResolver(), null, null, home);
    }

    private static CodingAgentConfiguration promptConfiguration(
            final CodingAgentConfiguration baseline, final int cap, final String hints) {
        return withLlmPermissions(
                baseline,
                withSystemPromptMaxChars(baseline.llm(), cap),
                withBash(
                        baseline.permissions(),
                        withReadOnlyPromptHints(baseline.permissions().bash(), hints)));
    }

    @BeforeEach
    void bindConfiguration(@Fusion final CodingAgentConfiguration configuration) {
        this.configuration =
                withStorageHomeHome(configuration, home.resolve("storage").toString(), home.toString());
    }

    private CodingAgentConfiguration configuration;

    @Nested
    @FusionSupport(modules = AppendAndOverrideAreIsolatedAndKeepWorkspaceGuidanceAndPlanPolicyModule.class)
    class AppendAndOverrideAreIsolatedAndKeepWorkspaceGuidanceAndPlanPolicyTests {
        @TempDir
        static Path root;

        @Test
        void appendAndOverrideAreIsolatedAndKeepWorkspaceGuidanceAndPlanPolicy() throws Exception {
            final var a = Files.createDirectory(home.resolve("A"));
            final var b = Files.createDirectory(home.resolve("B"));
            Files.writeString(a.resolve("AGENTS.md"), "workspace-rule-A");
            Files.writeString(b.resolve("AGENTS.md"), "workspace-rule-B");
            final var config = configuration;
            final var permissions = new PermissionService(config, a);
            permissions.setMode(AgentMode.PLAN);
            permissions.setPermissionMode(PermissionMode.ASK);
            final var append =
                    new SystemPromptBuilder(config, null, null, permissions, null, null, null, null, null, a);
            final var override =
                    new SystemPromptBuilder(config, null, null, permissions, null, null, null, null, null, b);
            append.configureSessionContext(List.of(b), new SessionMetadata.Instructions("append", List.of("client-A")));
            override.configureSessionContext(
                    List.of(), new SessionMetadata.Instructions("override", List.of("client-B")));
            final var first = append.build();
            final var second = override.build();
            assertTrue(first.length() <= append.resolvedSystemPromptCap());
            assertTrue(second.length() <= override.resolvedSystemPromptCap());
            assertFalse(first.contains(WORKSPACE_ELISION));
            assertFalse(second.contains(WORKSPACE_ELISION));
            assertTrue(first.contains("You are yca"));
            assertTrue(first.contains("client-A"));
            assertTrue(first.contains("take precedence on conflict"));
            assertTrue(first.contains("workspace-rule-A"));
            assertTrue(first.contains("Additional workspace directories"));
            assertTrue(first.contains("Directory context grants no access"));
            assertFalse(first.contains("client-B"));
            assertFalse(second.contains("You are yca"));
            assertFalse(second.contains("Tool usage policy:"));
            assertTrue(second.contains("client-B"));
            assertTrue(second.contains("workspace-rule-B"));
            assertTrue(second.contains("Mode: PLAN"));
            assertFalse(second.contains("client-A"));
            assertEquals(AgentMode.PLAN, permissions.mode());
            assertEquals(PermissionMode.ASK, permissions.permissionMode());
            assertTrue(permissions.sessionGrants().isEmpty());
            assertThrows(IllegalStateException.class, () -> append.configureSessionContext(List.of(), null));
            assertEquals(a, append.effectiveWorkdir());
            assertEquals(b, override.effectiveWorkdir());
        }

        @Test
        void acceptedInstructionsSurviveLargeWorkspaceTruncationAndSmallBudgetsRejectUpFront() throws Exception {
            Files.writeString(home.resolve("AGENTS.md"), "workspace-rule-".repeat(3000));
            final var config = promptConfiguration(
                    configuration, 24_000, configuration.permissions().bash().readOnlyPromptHints());
            final var instructions = new SessionMetadata.Instructions(
                    "append", List.of("client-start-" + "x".repeat(7000) + "-client-end"));
            final var builder = new SystemPromptBuilder(config, null, null, null, null, null, null, null, null, home);
            builder.configureSessionContext(List.of(), instructions);
            final var rendered = builder.build();
            assertTrue(rendered.contains(instructions.text().getFirst()));
            assertTrue(rendered.contains("You are yca"));
            assertTrue(rendered.contains(WORKSPACE_ELISION));
            assertEquals(24_000, rendered.length(), "the elision marker must count towards the cap too");
            final var small = promptConfiguration(
                    configuration, 6_000, configuration.permissions().bash().readOnlyPromptHints());
            final var rejected = new SystemPromptBuilder(small, null, null, null, null, null, null, null, null, home);
            assertThrows(
                    IllegalArgumentException.class, () -> rejected.configureSessionContext(List.of(), instructions));
            // Failed setup has not frozen or partially installed the rejected options.
            rejected.configureSessionContext(List.of(), new SessionMetadata.Instructions("override", List.of("short")));
            assertTrue(rejected.build().contains("short"));
            assertFalse(rejected.build().contains("client-start"));
        }

        @Test
        void oversizedPlanPolicyRejectsContextBeforeInstallationEvenWhenStartingInBuild() throws Exception {
            final var extra = Files.createDirectory(home.resolve("extra"));
            final var config = promptConfiguration(configuration, 6000, "policy-".repeat(1500));
            for (final var mode : AgentMode.values()) {
                for (final var instructionMode : List.of("append", "override")) {
                    final var permissions = new PermissionService(config, home);
                    permissions.setMode(mode);
                    final var builder = builder(config, permissions);
                    final var failure = assertThrows(
                            IllegalArgumentException.class,
                            () -> builder.configureSessionContext(
                                    List.of(extra),
                                    new SessionMetadata.Instructions(instructionMode, List.of("rejected-client"))));
                    assertTrue(failure.getMessage().contains("system prompt budget"));
                    // Rejection did not freeze setup, install roots/text, or replace the defaults.
                    builder.configureSessionContext(List.of(), null);
                    permissions.setMode(AgentMode.BUILD);
                    final var rendered = builder.build();
                    assertFalse(rendered.contains("rejected-client"));
                    assertFalse(rendered.contains("Additional workspace directories"));
                    assertEquals(builder(config, permissions).build(), rendered);
                }
            }
        }

        @Test
        void longPlanPolicyAndClientContextFitAcrossModesAndPromptVariants() throws Exception {
            Files.writeString(
                    home.resolve("AGENTS.md"), "workspace-front-" + "workspace-rule-".repeat(3000) + "-workspace-tail");
            final var extra = Files.createDirectory(home.resolve("extra"));
            final var hints = "policy-start-" + "h".repeat(10_000) + "-policy-end";
            final var config = promptConfiguration(configuration, 24000, hints);
            for (final var instructionMode : List.of("append", "override")) {
                final var permissions = new PermissionService(config, home);
                permissions.setMode(AgentMode.BUILD);
                final var builder = builder(config, permissions);
                final var clientText = "client-start-" + "c".repeat(500) + "-client-end";
                builder.configureSessionContext(
                        List.of(extra), new SessionMetadata.Instructions(instructionMode, List.of(clientText)));
                for (final var mode : AgentMode.values()) {
                    permissions.setMode(mode);
                    for (final var request :
                            new String[] {null, "explain the code", "fix the code", "how do I fix the code?"}) {
                        final var rendered = builder.build(request);
                        assertEquals(24_000, rendered.length(), "all retained blocks and the marker must fit");
                        assertTrue(rendered.contains(WORKSPACE_ELISION));
                        assertTrue(rendered.contains(clientText));
                        assertTrue(rendered.contains("- " + extra + '\n'));
                        assertTrue(rendered.contains("Relative paths still resolve against " + home));
                        assertTrue(rendered.contains("Directory context grants no access"));
                        assertFalse(rendered.contains("workspace-front"));
                        assertTrue(
                                rendered.contains("workspace-tail"), "workspace truncation must still keep the tail");
                        assertEquals("append".equals(instructionMode), rendered.startsWith("You are yca"));
                        assertEquals(mode == AgentMode.PLAN, rendered.contains(hints));
                        assertEquals(mode == AgentMode.PLAN, rendered.contains("Repository mutations are forbidden"));
                        if (mode == AgentMode.PLAN) {
                            if ("fix the code".equals(request)) {
                                assertTrue(rendered.contains(
                                        "Analyze the codebase and deliver a detailed, concrete implementation plan"));
                                assertTrue(rendered.endsWith(
                                        "Completed or cancelled items from earlier build sessions are progress"
                                                + " history: keep them.\n"));
                            } else {
                                assertTrue(rendered.endsWith(
                                        "The request is informational: explore the codebase as needed and"
                                                + " answer directly - no plan or todo list is required.\n"));
                            }
                        }
                    }
                }
            }
        }

        @Test
        void exactRetainedBoundaryCountsHeadersRootExplanationDefaultsPolicyAndMarker() throws Exception {
            final var extra = Files.createDirectory(home.resolve("extra"));
            final var hints = "policy-start-" + "h".repeat(10_000) + "-policy-end";
            for (final var instructions : new SessionMetadata.Instructions[] {
                new SessionMetadata.Instructions("append", List.of("first", "", "last")),
                new SessionMetadata.Instructions("override", List.of("first", "", "last")),
                null
            }) {
                final var wide = promptConfiguration(configuration, 64000, hints);
                final var permissions = new PermissionService(wide, home);
                permissions.setMode(AgentMode.PLAN);
                final var reference = builder(wide, permissions);
                reference.configureSessionContext(List.of(extra), instructions);
                final var full = reference.build("fix the code");
                final var defaultsSize = full.indexOf("Environment:\n");
                final var contextStart = full.indexOf("\nAdditional workspace directories (context only):\n");
                assertTrue(defaultsSize >= 0 && contextStart > defaultsSize);
                final var required = defaultsSize + full.length() - contextStart + WORKSPACE_ELISION.length();
                final var tight = promptConfiguration(configuration, required, hints);
                final var exact = builder(tight, permissions);
                exact.configureSessionContext(List.of(extra), instructions);
                final var rendered = exact.build("fix the code");
                assertEquals(required, rendered.length());
                assertEquals(
                        full.substring(0, defaultsSize) + WORKSPACE_ELISION + full.substring(contextStart),
                        rendered,
                        "at the exact boundary only workspace text may be dropped, including its last character");
                final var tooSmall = promptConfiguration(configuration, required - 1, hints);
                final var rejected = builder(tooSmall, permissions);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> rejected.configureSessionContext(List.of(extra), instructions));
                // Failed validation leaves even a root-only setup retryable.
                rejected.configureSessionContext(List.of(), null);
            }
        }

        @Test
        void emptyClientContextKeepsLegacyCappingAndShortPromptsRemainUnelided() {
            for (final var cap : List.of("1200", "32000")) {
                final var config = promptConfiguration(
                        configuration,
                        Integer.parseInt(cap),
                        configuration.permissions().bash().readOnlyPromptHints());
                final var permissions = new PermissionService(config, home);
                for (final var mode : AgentMode.values()) {
                    permissions.setMode(mode);
                    final var unconfigured = builder(config, permissions);
                    final var configured = builder(config, permissions);
                    configured.configureSessionContext(List.of(), null);
                    final var expected = unconfigured.build("fix the code");
                    assertEquals(expected, configured.build("fix the code"));
                    if ("1200".equals(cap)) {
                        assertTrue(expected.startsWith("[...system prompt exceeded the 1200 char budget;"));
                        assertTrue(expected.length() > 1200, "legacy marker overhead is unchanged");
                    } else {
                        assertTrue(expected.startsWith("You are yca"));
                        assertFalse(expected.contains("elided"));
                    }
                }
            }
        }
    }

    public static class AppendAndOverrideAreIsolatedAndKeepWorkspaceGuidanceAndPlanPolicyModule
            extends ConfigurationTestModule {
        public AppendAndOverrideAreIsolatedAndKeepWorkspaceGuidanceAndPlanPolicyModule() {
            super(Map.ofEntries(
                    Map.entry(
                            "yca.home",
                            AppendAndOverrideAreIsolatedAndKeepWorkspaceGuidanceAndPlanPolicyTests.root.toString()),
                    Map.entry(
                            "yca.storageHome",
                            AppendAndOverrideAreIsolatedAndKeepWorkspaceGuidanceAndPlanPolicyTests.root
                                    .resolve("storage")
                                    .toString()),
                    Map.entry("yca.skills.enabled", "false"),
                    Map.entry("yca.memory.enabled", "false")));
        }
    }

    private static LlmConfiguration withSystemPromptMaxChars(
            final LlmConfiguration baseline, final int systemPromptMaxChars) {
        return new LlmConfiguration(
                baseline.retriesMaxAttempts(),
                baseline.retriesBaseDelayMs(),
                baseline.rateLimitRequestsPerMinute(),
                baseline.maxConcurrent(),
                baseline.retryAfterMaxWaitMs(),
                baseline.usageLimitRetryThresholdMs(),
                baseline.concurrencyAdaptationMs(),
                baseline.concurrencyAcquireTimeoutMs(),
                baseline.retryOnContextOverflow(),
                baseline.maxIterations(),
                baseline.plateauRounds(),
                baseline.explorationNudgeRounds(),
                baseline.plateauFeedback(),
                baseline.continueOnTruncation(),
                baseline.truncationContinuesMax(),
                baseline.emptyCompletionRetriesMax(),
                baseline.streamRetriesMaxAttempts(),
                systemPromptMaxChars);
    }

    private static BashPermissionsConfiguration withReadOnlyPromptHints(
            final BashPermissionsConfiguration baseline, final String readOnlyPromptHints) {
        return new BashPermissionsConfiguration(baseline.rules(), readOnlyPromptHints, baseline.maxCombined());
    }

    private static PermissionsConfiguration withBash(
            final PermissionsConfiguration baseline, final BashPermissionsConfiguration bash) {
        return new PermissionsConfiguration(baseline.mode(), baseline.allow(), baseline.deny(), bash);
    }

    private static CodingAgentConfiguration withLlmPermissions(
            final CodingAgentConfiguration baseline,
            final LlmConfiguration llm,
            final PermissionsConfiguration permissions) {
        return new CodingAgentConfiguration(
                baseline.storageHome(),
                baseline.home(),
                baseline.workingDir(),
                baseline.projectDir(),
                baseline.provider(),
                baseline.model(),
                llm,
                baseline.compaction(),
                baseline.pruning(),
                baseline.compression(),
                baseline.memory(),
                baseline.todos(),
                baseline.optimize(),
                permissions,
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

    private static CodingAgentConfiguration withStorageHomeHome(
            final CodingAgentConfiguration baseline, final String storageHome, final String home) {
        return new CodingAgentConfiguration(
                storageHome,
                home,
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
}
