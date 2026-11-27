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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import io.yupiik.ia.agent.core.agent.SubagentRunner;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.configuration.PermissionsConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderListConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderSubagentConfiguration;
import io.yupiik.ia.agent.core.configuration.SubagentConfiguration;
import io.yupiik.ia.agent.core.context.BackoffSleeper;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class SubagentRoutePrivacyTest {
    @TempDir
    private static Path testWorkdir;

    private Path home;

    @BeforeEach
    void home(@Fusion final CodingAgentConfiguration configuration) {
        home = Path.of(configuration.resolveStorageHome());
    }

    private JsonMapper mapper;

    @BeforeEach
    void mapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Nested
    @FusionSupport(
            modules = SecureDefaultFollowsLiveSessionProviderAndCatalogIncludesItsConfiguredModelsScenarioModule.class)
    class SecureDefaultFollowsLiveSessionProviderAndCatalogIncludesItsConfiguredModelsScenario {
        @TempDir
        static Path directory;

        @Test
        void secureDefaultFollowsLiveSessionProviderAndCatalogIncludesItsConfiguredModels(
                @Fusion final CodingAgentConfiguration configuration) {
            final var f = new Fixture(configuration);
            final var ctx = f.context("private", "shared");
            final var catalog = f.catalog(ctx);
            assertTrue(catalog.success(), catalog.output());
            assertTrue(catalog.output().contains("$private/shared"), catalog.output());
            assertTrue(catalog.output().contains("\"total\":1"), catalog.output());
            assertFalse(catalog.output().contains("external"), catalog.output());
            assertFalse(catalog.output().contains("reviewer"), catalog.output());
            assertTrue(f.spawn(Map.of(), ctx).success());
            assertEquals("$private/shared", f.started.getFirst().admittedModel().routedReference());
            assertTrue(f.spawn(Map.of(), f.context("private", "unlisted")).success());
            assertEquals(
                    "$private/unlisted", f.started.getLast().admittedModel().routedReference());
            final var unlisted = f.catalog(f.context("private", "unlisted")).output();
            assertTrue(unlisted.contains("$private/unlisted"), unlisted);
            assertTrue(unlisted.contains("$private/shared"), unlisted);
            final var switched = f.catalog(f.context("external", "alt")).output();
            assertTrue(switched.contains("$external/alt"), switched);
            assertFalse(switched.contains("$private/shared"), switched);
        }
    }

    @Nested
    @FusionSupport(modules = ExplicitAlternativeRequiresDedicatedConsentAndSessionAnswerIsReusedScenarioModule.class)
    class ExplicitAlternativeRequiresDedicatedConsentAndSessionAnswerIsReusedScenario {
        @TempDir
        static Path directory;

        @Test
        void explicitAlternativeRequiresDedicatedConsentAndSessionAnswerIsReused(
                @Fusion final CodingAgentConfiguration configuration) {
            final var f = new Fixture(configuration);
            final var ctx = f.context("private", "shared");
            f.permissions.grantSession("spawn_subagent");
            assertFalse(f.spawn(Map.of("model", "$external/shared"), ctx).success());
            assertTrue(f.started.isEmpty());
            final var prompts = new AtomicInteger();
            f.permissions.setGate(request -> {
                final var title = request.title();
                final var detail = request.detail();
                final var tool = request.toolName();
                final var args = request.args();
                assertTrue(f.started.isEmpty(), "no child may be admitted before consent");
                assertTrue(title.contains("$external/shared"));
                assertTrue(detail.contains("$private/shared"));
                assertTrue(detail.contains("project context"));
                assertEquals("subagent_model_route", tool);
                assertEquals("$external/shared", args.get("destination"));
                prompts.incrementAndGet();
                return ApprovalGate.Result.ALLOW_SESSION;
            });
            assertTrue(f.spawn(Map.of("model", "$external/shared"), ctx).success());
            assertTrue(f.spawn(Map.of("model", "$external/shared"), ctx).success());
            assertEquals(1, prompts.get());
            assertFalse(f.catalog(ctx).output().contains("$external/shared"));
            assertTrue(f.catalog(ctx, "external").output().contains("$external/shared"));
            assertTrue(f.catalog(ctx, "external").output().contains("$external/alt"));
            assertEquals(
                    "$external/shared",
                    f.started.getFirst().admittedModel().settings().model());
        }
    }

    @Nested
    @FusionSupport(modules = AllowOnceDoesNotBecomeASessionOrProviderGrantScenarioModule.class)
    class AllowOnceDoesNotBecomeASessionOrProviderGrantScenario {
        @TempDir
        static Path directory;

        @Test
        void allowOnceDoesNotBecomeASessionOrProviderGrant(@Fusion final CodingAgentConfiguration configuration) {
            final var f = new Fixture(configuration);
            final var prompts = new AtomicInteger();
            f.permissions.setGate(request -> {
                prompts.incrementAndGet();
                return ApprovalGate.Result.ALLOW_ONCE;
            });
            final var ctx = f.context("private", "shared");
            assertTrue(f.spawn(Map.of("model", "$external/shared"), ctx).success());
            assertTrue(f.spawn(Map.of("model", "$external/shared"), ctx).success());
            assertTrue(f.spawn(Map.of("model", "$external/alt"), ctx).success());
            assertEquals(3, prompts.get());
            assertTrue(f.permissions.subagentRoutes().routes().isEmpty());
            assertFalse(f.catalog(ctx).output().contains("external"));
        }
    }

    @Nested
    @FusionSupport(modules = DeniesCancelsAndUnavailableGateFailBeforeAdmissionScenarioModule.class)
    class DeniesCancelsAndUnavailableGateFailBeforeAdmissionScenario {
        @TempDir
        static Path directory;

        @Test
        void deniesCancelsAndUnavailableGateFailBeforeAdmission(@Fusion final CodingAgentConfiguration configuration) {
            for (final var answer : List.of(ApprovalGate.Result.DENY, ApprovalGate.Result.ALLOW_SESSION)) {
                final var f = new Fixture(policy(
                        configuration,
                        configuration.subagents(),
                        configuration.provider().providers().get(0).subagents(),
                        configuration.provider().providers().get(1).subagents(),
                        configuration.permissions(),
                        configuration.mode()));
                final var cancelled = new AtomicBoolean();
                f.permissions.setGate(request -> {
                    if (answer == ApprovalGate.Result.ALLOW_SESSION) {
                        cancelled.set(true);
                    }
                    return answer;
                });
                final var ctx = f.context(f.permissions, "private", "shared", cancelled::get);
                assertFalse(f.spawn(Map.of("model", "$external/shared"), ctx).success());
                assertTrue(f.started.isEmpty());
                assertTrue(f.permissions.subagentRoutes().routes().isEmpty());
            }
            final var f = new Fixture(policy(
                    configuration,
                    configuration.subagents(),
                    configuration.provider().providers().get(0).subagents(),
                    configuration.provider().providers().get(1).subagents(),
                    configuration.permissions(),
                    configuration.mode()));
            f.permissions.setGate(request -> {
                throw new IllegalStateException("UI disposed");
            });
            assertFalse(f.spawn(Map.of("model", "$external/shared"), f.context("private", "shared"))
                    .success());
            assertTrue(f.started.isEmpty());
            f.permissions.setGate(request -> null);
            assertFalse(f.spawn(Map.of("model", "$external/shared"), f.context("private", "shared"))
                    .success());
            assertTrue(f.started.isEmpty());
        }
    }

    @Nested
    @FusionSupport(modules = DefaultsAndProfilesNeverPromptButExplicitModelCanOverrideProfileScenarioModule.class)
    class DefaultsAndProfilesNeverPromptButExplicitModelCanOverrideProfileScenario {
        @TempDir
        static Path directory;

        @Test
        void defaultsAndProfilesNeverPromptButExplicitModelCanOverrideProfile(
                @Fusion final CodingAgentConfiguration configuration) {
            final var f = new Fixture(configuration);
            final var prompts = new AtomicInteger();
            f.permissions.setGate(request -> {
                prompts.incrementAndGet();
                return ApprovalGate.Result.ALLOW_SESSION;
            });
            final var ctx = f.context("private", "shared");
            assertFalse(f.spawn(Map.of(), ctx).success());
            assertFalse(f.spawn(Map.of("agent", "reviewer"), ctx).success());
            assertEquals(0, prompts.get());
            assertTrue(f.started.isEmpty());
            assertTrue(f.spawn(Map.of("agent", "reviewer", "model", "$private/shared"), ctx)
                    .success());
            assertEquals("$private/shared", f.started.getFirst().admittedModel().routedReference());
            assertEquals(0, prompts.get());
        }
    }

    @Nested
    @FusionSupport(modules = GlobalOptInProviderOverrideAndIncludeInheritanceApplyToSourceProviderScenarioModule.class)
    class GlobalOptInProviderOverrideAndIncludeInheritanceApplyToSourceProviderScenario {
        @TempDir
        static Path directory;

        @Test
        void globalOptInProviderOverrideAndIncludeInheritanceApplyToSourceProvider(
                @Fusion final CodingAgentConfiguration configuration) {
            final var enabled = new Fixture(policy(
                    configuration,
                    new SubagentConfiguration(
                            configuration.subagents().enabled(),
                            configuration.subagents().maxConcurrent(),
                            configuration.subagents().maxIterations(),
                            true,
                            List.of("$external/shared"),
                            configuration.subagents().excludes(),
                            configuration.subagents().defaultModel()),
                    configuration.provider().providers().get(0).subagents(),
                    configuration.provider().providers().get(1).subagents(),
                    configuration.permissions(),
                    configuration.mode()));
            final var ctx = enabled.context("private", "shared");
            assertTrue(enabled.spawn(Map.of("model", "$external/shared"), ctx).success());
            assertFalse(enabled.spawn(Map.of("model", "$external/alt"), ctx).success());
            assertFalse(enabled.catalog(ctx).output().contains("$external/shared"));
            assertTrue(enabled.catalog(ctx, "external").output().contains("$external/shared"));
            assertFalse(enabled.catalog(ctx).output().contains("$external/alt"));
            final var disabled = new Fixture(policy(
                    configuration,
                    new SubagentConfiguration(
                            configuration.subagents().enabled(),
                            configuration.subagents().maxConcurrent(),
                            configuration.subagents().maxIterations(),
                            true,
                            configuration.subagents().includes(),
                            configuration.subagents().excludes(),
                            configuration.subagents().defaultModel()),
                    configuration.provider().providers().get(0).subagents(),
                    new ProviderSubagentConfiguration(
                            false,
                            configuration
                                    .provider()
                                    .providers()
                                    .get(1)
                                    .subagents()
                                    .includes(),
                            configuration
                                    .provider()
                                    .providers()
                                    .get(1)
                                    .subagents()
                                    .excludes(),
                            configuration
                                    .provider()
                                    .providers()
                                    .get(1)
                                    .subagents()
                                    .defaultModel(),
                            configuration
                                    .provider()
                                    .providers()
                                    .get(1)
                                    .subagents()
                                    .maxDepth()),
                    configuration.permissions(),
                    configuration.mode()));
            assertFalse(disabled.spawn(Map.of("model", "$external/shared"), disabled.context("private", "shared"))
                    .success());
            final var providerEnabled = new Fixture(policy(
                    configuration,
                    configuration.subagents(),
                    configuration.provider().providers().get(0).subagents(),
                    new ProviderSubagentConfiguration(
                            true,
                            List.of("$external/alt"),
                            configuration
                                    .provider()
                                    .providers()
                                    .get(1)
                                    .subagents()
                                    .excludes(),
                            configuration
                                    .provider()
                                    .providers()
                                    .get(1)
                                    .subagents()
                                    .defaultModel(),
                            configuration
                                    .provider()
                                    .providers()
                                    .get(1)
                                    .subagents()
                                    .maxDepth()),
                    configuration.permissions(),
                    configuration.mode()));
            assertTrue(providerEnabled
                    .spawn(Map.of("model", "$external/alt"), providerEnabled.context("private", "shared"))
                    .success());
            assertFalse(providerEnabled
                    .spawn(Map.of("model", "$external/shared"), providerEnabled.context("private", "shared"))
                    .success());
            assertFalse(providerEnabled
                    .catalog(providerEnabled.context("external", "shared"))
                    .output()
                    .contains("$private/shared"));
        }
    }

    @Nested
    @FusionSupport(modules = ExcludesAndAdministrativeCeilingsCannotBeApprovedVariant0ScenarioModule.class)
    class ExcludesAndAdministrativeCeilingsCannotBeApprovedVariant0Scenario {
        @TempDir
        static Path directory;

        @Test
        void excludesAndAdministrativeCeilingsCannotBeApprovedVariant0(
                @Fusion final CodingAgentConfiguration configuration) {

            final var f = new Fixture(configuration);
            final var prompts = new AtomicInteger();
            f.permissions.setGate(request -> {
                prompts.incrementAndGet();
                return ApprovalGate.Result.ALLOW_SESSION;
            });
            assertFalse(f.spawn(Map.of("model", "$external/shared"), f.context("private", "shared"))
                    .success());
            assertTrue(f.started.isEmpty());
            assertEquals(0, prompts.get());
        }
    }

    @Nested
    @FusionSupport(modules = ExcludesAndAdministrativeCeilingsCannotBeApprovedVariant1ScenarioModule.class)
    class ExcludesAndAdministrativeCeilingsCannotBeApprovedVariant1Scenario {
        @TempDir
        static Path directory;

        @Test
        void excludesAndAdministrativeCeilingsCannotBeApprovedVariant1(
                @Fusion final CodingAgentConfiguration configuration) {

            final var f = new Fixture(configuration);
            final var prompts = new AtomicInteger();
            f.permissions.setGate(request -> {
                prompts.incrementAndGet();
                return ApprovalGate.Result.ALLOW_SESSION;
            });
            assertFalse(f.spawn(Map.of("model", "$external/shared"), f.context("private", "shared"))
                    .success());
            assertTrue(f.started.isEmpty());
            assertEquals(0, prompts.get());
        }
    }

    @Nested
    @FusionSupport(modules = ExcludesAndAdministrativeCeilingsCannotBeApprovedVariant2ScenarioModule.class)
    class ExcludesAndAdministrativeCeilingsCannotBeApprovedVariant2Scenario {
        @TempDir
        static Path directory;

        @Test
        void excludesAndAdministrativeCeilingsCannotBeApprovedVariant2(
                @Fusion final CodingAgentConfiguration configuration) {

            final var f = new Fixture(configuration);
            final var prompts = new AtomicInteger();
            f.permissions.setGate(request -> {
                prompts.incrementAndGet();
                return ApprovalGate.Result.ALLOW_SESSION;
            });
            assertFalse(f.spawn(Map.of("model", "$external/shared"), f.context("private", "shared"))
                    .success());
            assertTrue(f.started.isEmpty());
            assertEquals(0, prompts.get());
        }
    }

    @Nested
    @FusionSupport(modules = ExcludesAndAdministrativeCeilingsCannotBeApprovedVariant3ScenarioModule.class)
    class ExcludesAndAdministrativeCeilingsCannotBeApprovedVariant3Scenario {
        @TempDir
        static Path directory;

        @Test
        void excludesAndAdministrativeCeilingsCannotBeApprovedVariant3(
                @Fusion final CodingAgentConfiguration configuration) {

            final var f = new Fixture(configuration);
            final var prompts = new AtomicInteger();
            f.permissions.setGate(request -> {
                prompts.incrementAndGet();
                return ApprovalGate.Result.ALLOW_SESSION;
            });
            assertFalse(f.spawn(Map.of("model", "$external/shared"), f.context("private", "shared"))
                    .success());
            assertTrue(f.started.isEmpty());
            assertEquals(0, prompts.get());
        }
    }

    @Nested
    @FusionSupport(modules = GrantsSurviveModelChangesButNotOtherTabsDisposalOrNewRuntimeScenarioModule.class)
    class GrantsSurviveModelChangesButNotOtherTabsDisposalOrNewRuntimeScenario {
        @TempDir
        static Path directory;

        @Test
        void grantsSurviveModelChangesButNotOtherTabsDisposalOrNewRuntime(
                @Fusion final CodingAgentConfiguration configuration) {
            final var f = new Fixture(configuration);
            f.permissions.setGate(request -> ApprovalGate.Result.ALLOW_SESSION);
            assertTrue(f.spawn(Map.of("model", "$external/alt"), f.context("private", "shared"))
                    .success());
            f.permissions.setGate(null);
            f.permissions.clearSessionGrants();
            assertTrue(f.spawn(Map.of("model", "$external/alt"), f.context("private", "unlisted"))
                    .success());
            final var otherTab = new PermissionService(f.config, (f.config).resolveWorkingDir());
            assertFalse(f.spawn(Map.of("model", "$external/alt"), f.context(otherTab, "private", "shared", () -> false))
                    .success());
            assertFalse(f.catalog(f.context(otherTab, "private", "shared", () -> false))
                    .output()
                    .contains("$external/alt"));
            f.permissions.subagentRoutes().close();
            assertFalse(f.spawn(Map.of("model", "$external/alt"), f.context("private", "shared"))
                    .success());
            assertTrue(f.permissions.subagentRoutes().routes().isEmpty());
        }
    }

    @Nested
    @FusionSupport(modules = ChangedHardPolicyStillRejectsPreviouslyApprovedRouteScenarioModule.class)
    class ChangedHardPolicyStillRejectsPreviouslyApprovedRouteScenario {
        @TempDir
        static Path directory;

        @Test
        void changedHardPolicyStillRejectsPreviouslyApprovedRoute(
                @Fusion final CodingAgentConfiguration configuration) {
            final var f = new Fixture(policy(
                    configuration,
                    configuration.subagents(),
                    configuration.provider().providers().get(0).subagents(),
                    configuration.provider().providers().get(1).subagents(),
                    configuration.permissions(),
                    configuration.mode()));
            f.permissions.setGate(request -> ApprovalGate.Result.ALLOW_SESSION);
            assertTrue(f.spawn(Map.of("model", "$external/alt"), f.context("private", "shared"))
                    .success());
            final var denied = new Fixture(policy(
                    configuration,
                    new SubagentConfiguration(
                            configuration.subagents().enabled(),
                            configuration.subagents().maxConcurrent(),
                            configuration.subagents().maxIterations(),
                            configuration.subagents().allowModelOverride(),
                            configuration.subagents().includes(),
                            List.of("$external/alt"),
                            configuration.subagents().defaultModel()),
                    configuration.provider().providers().get(0).subagents(),
                    configuration.provider().providers().get(1).subagents(),
                    configuration.permissions(),
                    configuration.mode()));
            final var ctx = denied.context(f.permissions, "private", "shared", () -> false);
            assertFalse(denied.spawn(Map.of("model", "$external/alt"), ctx).success());
            assertFalse(denied.catalog(ctx).output().contains("$external/alt"));
        }
    }

    @Nested
    @FusionSupport(modules = NoSessionUnknownProviderAndStaleCatalogCannotBypassPolicyScenarioModule.class)
    class NoSessionUnknownProviderAndStaleCatalogCannotBypassPolicyScenario {
        @TempDir
        static Path directory;

        @Test
        void noSessionUnknownProviderAndStaleCatalogCannotBypassPolicy(
                @Fusion final CodingAgentConfiguration configuration) {
            final var f = new Fixture(configuration);
            assertFalse(f.catalog
                    .execute(Map.of(), ToolContext.empty(home, new DisabledSandbox(home)))
                    .success());
            assertFalse(f.catalog
                    .execute(Map.of(), ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)))
                    .success());
            final var ctx = f.context("private", "shared");
            assertFalse(f.spawn(Map.of("model", "$missing/shared"), ctx).success());
            assertFalse(f.spawn(Map.of("model", "$external/"), ctx).success());
            final var beforeSwitch = f.catalog(f.context("external", "shared"));
            assertTrue(beforeSwitch.output().contains("$external/shared"));
            assertFalse(f.spawn(Map.of("model", "$external/shared"), ctx).success());
            assertTrue(f.started.isEmpty());
        }
    }

    @Nested
    @FusionSupport(
            modules = GlobalDefaultAndExplicitEmptyProviderIncludesAndDefaultAreInheritedCorrectlyScenarioModule.class)
    class GlobalDefaultAndExplicitEmptyProviderIncludesAndDefaultAreInheritedCorrectlyScenario {
        @TempDir
        static Path directory;

        @Test
        void globalDefaultAndExplicitEmptyProviderIncludesAndDefaultAreInheritedCorrectly(
                @Fusion final CodingAgentConfiguration configuration) {
            final var inherited = new Fixture(policy(
                    configuration,
                    new SubagentConfiguration(
                            configuration.subagents().enabled(),
                            configuration.subagents().maxConcurrent(),
                            configuration.subagents().maxIterations(),
                            true,
                            configuration.subagents().includes(),
                            configuration.subagents().excludes(),
                            "$external/alt"),
                    configuration.provider().providers().get(0).subagents(),
                    configuration.provider().providers().get(1).subagents(),
                    configuration.permissions(),
                    configuration.mode()));
            assertEquals(null, inherited.manager.provider("private").subagents().allowModelOverride());
            assertFalse(inherited
                    .spawn(Map.of(), inherited.context("private", "shared"))
                    .success());
            assertTrue(inherited.started.isEmpty());
            final var empty = new Fixture(policy(
                    configuration,
                    new SubagentConfiguration(
                            configuration.subagents().enabled(),
                            configuration.subagents().maxConcurrent(),
                            configuration.subagents().maxIterations(),
                            true,
                            List.of("$external/shared"),
                            configuration.subagents().excludes(),
                            "$external/shared"),
                    configuration.provider().providers().get(0).subagents(),
                    new ProviderSubagentConfiguration(
                            configuration
                                    .provider()
                                    .providers()
                                    .get(1)
                                    .subagents()
                                    .allowModelOverride(),
                            List.of(),
                            configuration
                                    .provider()
                                    .providers()
                                    .get(1)
                                    .subagents()
                                    .excludes(),
                            "",
                            configuration
                                    .provider()
                                    .providers()
                                    .get(1)
                                    .subagents()
                                    .maxDepth()),
                    configuration.permissions(),
                    configuration.mode()));
            assertFalse(empty.spawn(Map.of("model", "$external/alt"), empty.context("private", "shared"))
                    .success());
            assertTrue(empty.spawn(Map.of(), empty.context("private", "shared")).success());
            assertEquals(
                    "$private/shared", empty.started.getLast().admittedModel().routedReference());
        }
    }

    @Nested
    @FusionSupport(
            modules =
                    PermissiveToolModeStillRequiresDestinationConsentAndApprovedUnlistedModelsAreDiscoverableScenarioModule
                            .class)
    class PermissiveToolModeStillRequiresDestinationConsentAndApprovedUnlistedModelsAreDiscoverableScenario {
        @TempDir
        static Path directory;

        @Test
        void permissiveToolModeStillRequiresDestinationConsentAndApprovedUnlistedModelsAreDiscoverable(
                @Fusion final CodingAgentConfiguration configuration) {
            final var f = new Fixture(configuration);
            final var ctx = f.context("private", "shared");
            assertFalse(f.spawn(Map.of("model", "$external/unlisted"), ctx).success());
            f.permissions.setGate(request -> ApprovalGate.Result.ALLOW_SESSION);
            assertTrue(f.spawn(Map.of("model", "$external/unlisted"), ctx).success());
            assertFalse(f.catalog(ctx).output().contains("$external/unlisted"));
            assertTrue(f.catalog(ctx, "external").output().contains("$external/unlisted"));
            assertFalse(f.catalog(ctx).output().contains("$external/alt"));
            f.permissions.setGate(request -> ApprovalGate.Result.DENY);
            assertFalse(f.spawn(Map.of("model", "$external/alt"), ctx).success());
        }
    }

    @Nested
    @FusionSupport(modules = AstraParentDiscoversAndSpawnsSolOnTheSameCodexProviderScenarioModule.class)
    class AstraParentDiscoversAndSpawnsSolOnTheSameCodexProviderScenario {
        @TempDir
        static Path directory;

        @Test
        void astraParentDiscoversAndSpawnsSolOnTheSameCodexProvider(
                @Fusion final CodingAgentConfiguration configuration) {
            final var f = new Fixture(configuration);
            final var ctx = f.context("codex", "gpt-6-astra");
            final var filtered = f.catalog.execute(
                    Map.of("provider", "codex", "kind", "models", "query", "gpt-6.1-sol", "limit", 5), ctx);
            assertModelMetadata(filtered, "$codex/gpt-6.1-sol", true, true, false);
            assertModelMetadata(f.catalog(ctx), "$codex/gpt-6.1-sol", true, true, false);
            assertFalse(f.catalog(ctx).output().contains("$codex/gpt-6-astra"));
            assertTrue(f.spawn(Map.of(), ctx).success());
            assertTrue(f.spawn(Map.of("model", "gpt-6.1-sol"), ctx).success());
            assertEquals(
                    "$codex/gpt-6.1-sol", f.started.getLast().admittedModel().routedReference());
            assertFalse(f.spawn(Map.of("model", "$codex/gpt-6-astra"), ctx).success());
        }
    }

    @Nested
    @FusionSupport(
            modules = AstraSessionListsSolCatalogWithoutAuthorizingItAndPinsBareDuplicateToAstraScenarioModule.class)
    class AstraSessionListsSolCatalogWithoutAuthorizingItAndPinsBareDuplicateToAstraScenario {
        @TempDir
        static Path directory;

        @Test
        void astraSessionListsSolCatalogWithoutAuthorizingItAndPinsBareDuplicateToAstra(
                @Fusion final CodingAgentConfiguration configuration) {
            final var f = new Fixture(configuration);
            final var ctx = f.context("astra", "shared");
            final var catalog = f.catalog(ctx);
            assertTrue(catalog.success(), catalog.output());
            assertTrue(catalog.output().contains("$astra/shared"), catalog.output());
            assertTrue(catalog.output().contains("$astra/alt"), catalog.output());
            assertFalse(catalog.output().contains("$sol/"), catalog.output());
            assertModelMetadata(catalog, "$astra/shared", true, true, false);
            assertModelMetadata(catalog, "$astra/alt", false, false, true);
            assertFalse(f.spawn(Map.of("model", "$astra/alt"), ctx).success());
            assertTrue(f.spawn(Map.of("model", "shared"), ctx).success());
            assertEquals("$astra/shared", f.started.getLast().admittedModel().routedReference());

            final var sol = f.catalog(ctx, "sol");
            assertTrue(sol.success(), sol.output());
            assertModelMetadata(sol, "$sol/shared", false, false, true);
            assertModelMetadata(sol, "$sol/alt", false, false, true);
            assertFalse(f.spawn(Map.of("model", "$sol/shared"), ctx).success());
            final var prompts = new AtomicInteger();
            f.permissions.setGate(request -> {
                final var tool = request.toolName();
                assertEquals("subagent_model_route", tool);
                prompts.incrementAndGet();
                return ApprovalGate.Result.ALLOW_SESSION;
            });
            assertTrue(f.spawn(Map.of("model", "$sol/shared"), ctx).success());
            assertTrue(f.spawn(Map.of("model", "$astra/alt"), ctx).success());
            assertEquals(2, prompts.get());
            assertFalse(f.catalog(ctx).output().contains("$sol/"));
            assertModelMetadata(f.catalog(ctx, "sol"), "$sol/shared", false, true, false);
            assertModelMetadata(f.catalog(ctx, "sol"), "$sol/alt", false, false, true);
            assertModelMetadata(f.catalog(ctx), "$astra/alt", false, true, false);
        }
    }

    @Nested
    @FusionSupport(
            modules =
                    BareExternallyDeclaredModelNeedsConsentForSessionProviderAndItsGrantRemainsDiscoverableScenarioModule
                            .class)
    class BareExternallyDeclaredModelNeedsConsentForSessionProviderAndItsGrantRemainsDiscoverableScenario {
        @TempDir
        static Path directory;

        @Test
        void bareExternallyDeclaredModelNeedsConsentForSessionProviderAndItsGrantRemainsDiscoverable(
                @Fusion final CodingAgentConfiguration configuration) {
            final var f = new Fixture(configuration);
            final var ctx = f.context("private", "shared");
            assertFalse(f.spawn(Map.of("model", "alt"), ctx).success());
            final var prompts = new AtomicInteger();
            f.permissions.setGate(request -> {
                final var tool = request.toolName();
                final var args = request.args();
                assertEquals("$private/alt", args.get("destination"));
                assertEquals("subagent_model_route", tool);
                prompts.incrementAndGet();
                return ApprovalGate.Result.ALLOW_SESSION;
            });
            assertTrue(f.spawn(Map.of("model", "alt"), ctx).success());
            assertEquals("$private/alt", f.started.getLast().admittedModel().routedReference());
            assertEquals(
                    "$private/alt",
                    f.started.getLast().admittedModel().settings().model());
            assertTrue(f.spawn(Map.of("model", "$private/alt"), ctx).success());
            assertEquals(1, prompts.get());
            assertModelMetadata(f.catalog(ctx), "$private/alt", false, true, false);
            assertFalse(f.catalog(ctx).output().contains("$external/"));
        }
    }

    @Nested
    @FusionSupport(
            modules =
                    ConfiguredSameProviderDefaultIsAuthorizedWithOverridesDisabledButDifferentParentIsNotScenarioModule
                            .class)
    class ConfiguredSameProviderDefaultIsAuthorizedWithOverridesDisabledButDifferentParentIsNotScenario {
        @TempDir
        static Path directory;

        @Test
        void configuredSameProviderDefaultIsAuthorizedWithOverridesDisabledButDifferentParentIsNot(
                @Fusion final CodingAgentConfiguration configuration) {
            final var f = new Fixture(configuration);
            final var ctx = f.context("private", "shared");
            assertModelMetadata(f.catalog(ctx), "$private/alt", true, true, false);
            assertModelMetadata(f.catalog(ctx), "$private/shared", false, false, true);
            assertTrue(f.spawn(Map.of(), ctx).success());
            assertTrue(f.spawn(Map.of("model", "$private/alt"), ctx).success());
            assertFalse(f.spawn(Map.of("model", "$private/shared"), ctx).success());
            assertEquals(2, f.started.size());
            f.permissions.setGate(request -> ApprovalGate.Result.ALLOW_SESSION);
            assertTrue(f.spawn(Map.of("model", "$private/shared"), ctx).success());
        }
    }

    @Nested
    @FusionSupport(modules = EmptyOrAbsentIncludesDoesNotAuthorizeAlternativesEvenInYoloVariant0ScenarioModule.class)
    class EmptyOrAbsentIncludesDoesNotAuthorizeAlternativesEvenInYoloVariant0Scenario {
        @TempDir
        static Path directory;

        @Test
        void emptyOrAbsentIncludesDoesNotAuthorizeAlternativesEvenInYoloVariant0(
                @Fusion final CodingAgentConfiguration configuration) {
            final var f = new Fixture(configuration);
            final var ctx = f.context("private", "shared");
            assertModelMetadata(f.catalog(ctx), "$private/alt", false, false, true);
            assertFalse(f.spawn(Map.of("model", "$private/alt"), ctx).success());
            assertFalse(f.spawn(Map.of("model", "$external/alt"), ctx).success());
            assertTrue(f.started.isEmpty());
            final var prompts = new AtomicInteger();
            f.permissions.setGate(request -> {
                final var tool = request.toolName();
                assertEquals("subagent_model_route", tool);
                prompts.incrementAndGet();
                return ApprovalGate.Result.ALLOW_SESSION;
            });
            assertTrue(f.spawn(Map.of("model", "$private/alt"), ctx).success());
            assertTrue(f.spawn(Map.of("model", "$external/alt"), ctx).success());
            assertEquals(2, prompts.get());
            assertModelMetadata(f.catalog(ctx), "$private/alt", false, true, false);
            assertFalse(f.catalog(ctx).output().contains("$external/"));
        }
    }

    @Nested
    @FusionSupport(modules = EmptyOrAbsentIncludesDoesNotAuthorizeAlternativesEvenInYoloVariant1ScenarioModule.class)
    class EmptyOrAbsentIncludesDoesNotAuthorizeAlternativesEvenInYoloVariant1Scenario {
        @TempDir
        static Path directory;

        @Test
        void emptyOrAbsentIncludesDoesNotAuthorizeAlternativesEvenInYoloVariant1(
                @Fusion final CodingAgentConfiguration configuration) {
            final var f = new Fixture(configuration);
            final var ctx = f.context("private", "shared");
            assertModelMetadata(f.catalog(ctx), "$private/alt", false, false, true);
            assertFalse(f.spawn(Map.of("model", "$private/alt"), ctx).success());
            assertFalse(f.spawn(Map.of("model", "$external/alt"), ctx).success());
            assertTrue(f.started.isEmpty());
            final var prompts = new AtomicInteger();
            f.permissions.setGate(request -> {
                final var tool = request.toolName();
                assertEquals("subagent_model_route", tool);
                prompts.incrementAndGet();
                return ApprovalGate.Result.ALLOW_SESSION;
            });
            assertTrue(f.spawn(Map.of("model", "$private/alt"), ctx).success());
            assertTrue(f.spawn(Map.of("model", "$external/alt"), ctx).success());
            assertEquals(2, prompts.get());
            assertModelMetadata(f.catalog(ctx), "$private/alt", false, true, false);
            assertFalse(f.catalog(ctx).output().contains("$external/"));
        }
    }

    @Nested
    @FusionSupport(
            modules =
                    ExactIncludesAuthorizeExplicitAlternativesWithoutPromptsButNotImplicitCrossProviderRoutesScenarioModule
                            .class)
    class ExactIncludesAuthorizeExplicitAlternativesWithoutPromptsButNotImplicitCrossProviderRoutesScenario {
        @TempDir
        static Path directory;

        @Test
        void exactIncludesAuthorizeExplicitAlternativesWithoutPromptsButNotImplicitCrossProviderRoutes(
                @Fusion final CodingAgentConfiguration configuration) {
            final var f = new Fixture(policy(
                    configuration,
                    new SubagentConfiguration(
                            configuration.subagents().enabled(),
                            configuration.subagents().maxConcurrent(),
                            configuration.subagents().maxIterations(),
                            true,
                            List.of("$private/alt", "$external/shared"),
                            configuration.subagents().excludes(),
                            "$external/shared"),
                    configuration.provider().providers().get(0).subagents(),
                    configuration.provider().providers().get(1).subagents(),
                    new PermissionsConfiguration(
                            PermissionMode.YOLO,
                            configuration.permissions().allow(),
                            configuration.permissions().deny(),
                            configuration.permissions().bash()),
                    AgentMode.BUILD));
            final var ctx = f.context("private", "shared");
            final var prompts = new AtomicInteger();
            f.permissions.setGate(request -> {
                prompts.incrementAndGet();
                return ApprovalGate.Result.ALLOW_SESSION;
            });
            assertFalse(f.spawn(Map.of(), ctx).success());
            assertFalse(f.spawn(Map.of("agent", "reviewer"), ctx).success());
            assertTrue(f.spawn(Map.of("model", "$private/alt"), ctx).success());
            assertTrue(f.spawn(Map.of("model", "$external/shared"), ctx).success());
            assertEquals(0, prompts.get());
            // An existing session grant must not turn an implicit cross-provider route into consent.
            assertTrue(f.spawn(Map.of("model", "$external/alt"), ctx).success());
            assertEquals(1, prompts.get());
            final var changedDefault = new Fixture(policy(
                    configuration,
                    new SubagentConfiguration(
                            configuration.subagents().enabled(),
                            configuration.subagents().maxConcurrent(),
                            configuration.subagents().maxIterations(),
                            configuration.subagents().allowModelOverride(),
                            configuration.subagents().includes(),
                            configuration.subagents().excludes(),
                            "$external/alt"),
                    configuration.provider().providers().get(0).subagents(),
                    configuration.provider().providers().get(1).subagents(),
                    configuration.permissions(),
                    configuration.mode()));
            final var granted = changedDefault.context(f.permissions, "private", "shared", () -> false);
            assertFalse(changedDefault.spawn(Map.of(), granted).success());
            assertEquals(1, prompts.get());
            assertTrue(changedDefault.started.isEmpty());
        }
    }

    @Nested
    @FusionSupport(
            modules =
                    GlobalSourceAndDestinationExcludesAreAdditiveAndBeatIncludesAndPreviousGrantsVariant0ScenarioModule
                            .class)
    class GlobalSourceAndDestinationExcludesAreAdditiveAndBeatIncludesAndPreviousGrantsVariant0Scenario {
        @TempDir
        static Path directory;

        @Test
        void globalSourceAndDestinationExcludesAreAdditiveAndBeatIncludesAndPreviousGrantsVariant0(
                @Fusion final CodingAgentConfiguration configuration) {
            final var approved = new Fixture(unrestrictedPolicy(configuration));
            approved.permissions.setGate(request -> ApprovalGate.Result.ALLOW_SESSION);
            assertTrue(approved.spawn(Map.of("model", "$external/shared"), approved.context("private", "shared"))
                    .success());
            final var denied = new Fixture(configuration);
            final var ctx = denied.context(approved.permissions, "private", "shared", () -> false);
            final var prompts = new AtomicInteger();
            approved.permissions.setGate(request -> {
                prompts.incrementAndGet();
                return ApprovalGate.Result.ALLOW_SESSION;
            });
            assertFalse(denied.spawn(Map.of("model", "$external/shared"), ctx).success());
            assertFalse(denied.catalog(ctx, "external").output().contains("$external/shared"));
            assertTrue(denied.started.isEmpty());
            assertEquals(0, prompts.get());
        }
    }

    @Nested
    @FusionSupport(
            modules =
                    GlobalSourceAndDestinationExcludesAreAdditiveAndBeatIncludesAndPreviousGrantsVariant1ScenarioModule
                            .class)
    class GlobalSourceAndDestinationExcludesAreAdditiveAndBeatIncludesAndPreviousGrantsVariant1Scenario {
        @TempDir
        static Path directory;

        @Test
        void globalSourceAndDestinationExcludesAreAdditiveAndBeatIncludesAndPreviousGrantsVariant1(
                @Fusion final CodingAgentConfiguration configuration) {
            final var approved = new Fixture(unrestrictedPolicy(configuration));
            approved.permissions.setGate(request -> ApprovalGate.Result.ALLOW_SESSION);
            assertTrue(approved.spawn(Map.of("model", "$external/shared"), approved.context("private", "shared"))
                    .success());
            final var denied = new Fixture(configuration);
            final var ctx = denied.context(approved.permissions, "private", "shared", () -> false);
            final var prompts = new AtomicInteger();
            approved.permissions.setGate(request -> {
                prompts.incrementAndGet();
                return ApprovalGate.Result.ALLOW_SESSION;
            });
            assertFalse(denied.spawn(Map.of("model", "$external/shared"), ctx).success());
            assertFalse(denied.catalog(ctx, "external").output().contains("$external/shared"));
            assertTrue(denied.started.isEmpty());
            assertEquals(0, prompts.get());
        }
    }

    @Nested
    @FusionSupport(
            modules =
                    GlobalSourceAndDestinationExcludesAreAdditiveAndBeatIncludesAndPreviousGrantsVariant2ScenarioModule
                            .class)
    class GlobalSourceAndDestinationExcludesAreAdditiveAndBeatIncludesAndPreviousGrantsVariant2Scenario {
        @TempDir
        static Path directory;

        @Test
        void globalSourceAndDestinationExcludesAreAdditiveAndBeatIncludesAndPreviousGrantsVariant2(
                @Fusion final CodingAgentConfiguration configuration) {
            final var approved = new Fixture(unrestrictedPolicy(configuration));
            approved.permissions.setGate(request -> ApprovalGate.Result.ALLOW_SESSION);
            assertTrue(approved.spawn(Map.of("model", "$external/shared"), approved.context("private", "shared"))
                    .success());
            final var denied = new Fixture(configuration);
            final var ctx = denied.context(approved.permissions, "private", "shared", () -> false);
            final var prompts = new AtomicInteger();
            approved.permissions.setGate(request -> {
                prompts.incrementAndGet();
                return ApprovalGate.Result.ALLOW_SESSION;
            });
            assertFalse(denied.spawn(Map.of("model", "$external/shared"), ctx).success());
            assertFalse(denied.catalog(ctx, "external").output().contains("$external/shared"));
            assertTrue(denied.started.isEmpty());
            assertEquals(0, prompts.get());
        }
    }

    @Nested
    @FusionSupport(modules = SameProviderExcludesBeatParentDefaultProfileAndIncludesVariant0ScenarioModule.class)
    class SameProviderExcludesBeatParentDefaultProfileAndIncludesVariant0Scenario {
        @TempDir
        static Path directory;

        @Test
        void sameProviderExcludesBeatParentDefaultProfileAndIncludesVariant0(
                @Fusion final CodingAgentConfiguration configuration) {
            final var f = new Fixture(configuration);
            final var ctx = f.context("private", "shared");
            final var prompts = new AtomicInteger();
            f.permissions.setGate(request -> {
                prompts.incrementAndGet();
                return ApprovalGate.Result.ALLOW_SESSION;
            });
            assertFalse(f.spawn(Map.of(), ctx).success());
            assertFalse(f.spawn(Map.of("agent", "reviewer"), ctx).success());
            assertFalse(f.spawn(Map.of("model", "$private/shared"), ctx).success());
            assertFalse(f.catalog(ctx).output().contains("$private/shared"));
            assertTrue(f.started.isEmpty());
            assertEquals(0, prompts.get());
        }
    }

    @Nested
    @FusionSupport(modules = SameProviderExcludesBeatParentDefaultProfileAndIncludesVariant1ScenarioModule.class)
    class SameProviderExcludesBeatParentDefaultProfileAndIncludesVariant1Scenario {
        @TempDir
        static Path directory;

        @Test
        void sameProviderExcludesBeatParentDefaultProfileAndIncludesVariant1(
                @Fusion final CodingAgentConfiguration configuration) {
            final var f = new Fixture(configuration);
            final var ctx = f.context("private", "shared");
            final var prompts = new AtomicInteger();
            f.permissions.setGate(request -> {
                prompts.incrementAndGet();
                return ApprovalGate.Result.ALLOW_SESSION;
            });
            assertFalse(f.spawn(Map.of(), ctx).success());
            assertFalse(f.spawn(Map.of("agent", "reviewer"), ctx).success());
            assertFalse(f.spawn(Map.of("model", "$private/shared"), ctx).success());
            assertFalse(f.catalog(ctx).output().contains("$private/shared"));
            assertTrue(f.started.isEmpty());
            assertEquals(0, prompts.get());
        }
    }

    @SuppressWarnings("unchecked")
    private void assertModelMetadata(
            final ToolResult catalog,
            final String reference,
            final boolean defaultModel,
            final boolean authorized,
            final boolean requiresApproval) {
        assertTrue(catalog.success(), catalog.output());
        final var result = mapper.fromString(Map.class, catalog.output());
        final var options = (List<Map<String, Object>>) result.get("options");
        final var model = options.stream()
                .filter(option -> reference.equals(option.get("reference")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing " + reference + " in " + catalog.output()));
        assertEquals(defaultModel, model.get("default"), catalog.output());
        assertEquals(authorized, model.get("authorized"), catalog.output());
        assertEquals(requiresApproval, model.get("requires_approval"), catalog.output());
    }

    @Nested
    @FusionSupport(modules = NestedConfigurationJsonKeepsOmittedVersusExplicitEmptyIncludesScenarioModule.class)
    class NestedConfigurationJsonKeepsOmittedVersusExplicitEmptyIncludesScenario {
        @TempDir
        static Path directory;

        @Test
        void nestedConfigurationJsonKeepsOmittedVersusExplicitEmptyIncludes(
                @Fusion final CodingAgentConfiguration configuration) {
            final var absent = mapper.fromString(
                    ProviderConfiguration.class, "{\"id\":\"private\",\"subagents\":{\"allowModelOverride\":true}}");
            assertNotNull(absent.subagents());
            assertEquals(1, absent.subagents().maxDepth());
            assertEquals(null, absent.subagents().includes());
            final var empty = mapper.fromString(
                    ProviderConfiguration.class,
                    "{\"id\":\"private\",\"subagents\":{\"includes\":[],\"excludes\":[\"$external/alt\"],\"defaultModel\":\"\"}}");
            assertEquals(List.of(), empty.subagents().includes());
            assertEquals(List.of("$external/alt"), empty.subagents().excludes());
            assertEquals("", empty.subagents().defaultModel());
            assertEquals(
                    3,
                    mapper.fromString(
                                    ProviderConfiguration.class, "{\"id\":\"private\",\"subagents\":{\"maxDepth\":3}}")
                            .subagents()
                            .maxDepth());
        }
    }

    @Nested
    @FusionSupport(modules = DefaultDepthPreservesDirectChildrenOnlyScenarioModule.class)
    class DefaultDepthPreservesDirectChildrenOnlyScenario {
        @TempDir
        static Path directory;

        @Test
        void defaultDepthPreservesDirectChildrenOnly(@Fusion final CodingAgentConfiguration configuration) {
            final var f = new Fixture(configuration);
            final var root = f.context("private", "shared");
            assertTrue(f.spawn(Map.of(), root).success());
            final var child = f.childContext(f.started.getLast(), root);
            final var refused = f.spawn(Map.of(), child);
            assertFalse(refused.success());
            assertTrue(refused.output().contains("maxDepth reached (1)"), refused.output());
            assertEquals(1, f.started.size());
        }
    }

    @Nested
    @FusionSupport(modules = DestinationProviderDepthAllowsModelsToChangeWithoutResettingDepthScenarioModule.class)
    class DestinationProviderDepthAllowsModelsToChangeWithoutResettingDepthScenario {
        @TempDir
        static Path directory;

        @Test
        void destinationProviderDepthAllowsModelsToChangeWithoutResettingDepth(
                @Fusion final CodingAgentConfiguration configuration) {
            final var f = new Fixture(configuration);
            final var root = f.context("private", "shared");
            assertTrue(f.spawn(Map.of("model", "$external/shared"), root).success());
            final var child = f.childContext(f.started.getLast(), root);
            assertTrue(f.spawn(Map.of("model", "alt"), child).success());
            final var grandchildState = f.started.getLast();
            assertEquals("external", grandchildState.admittedModel().settings().providerId());
            assertEquals("$external/alt", grandchildState.admittedModel().routedReference());
            assertEquals(2, grandchildState.spec().subagentScope().depth());
            final var grandchild = f.childContext(grandchildState, child);
            final var refused = f.spawn(Map.of("model", "shared"), grandchild);
            assertFalse(refused.success());
            assertTrue(refused.output().contains("maxDepth reached (2)"), refused.output());
            assertEquals(2, f.started.size());
        }
    }

    @Nested
    @FusionSupport(
            modules = NestedProviderBoundaryCannotBeApprovedOrDiscoveredThroughAProviderFilterScenarioModule.class)
    class NestedProviderBoundaryCannotBeApprovedOrDiscoveredThroughAProviderFilterScenario {
        @TempDir
        static Path directory;

        @Test
        void nestedProviderBoundaryCannotBeApprovedOrDiscoveredThroughAProviderFilter(
                @Fusion final CodingAgentConfiguration configuration) {
            final var f = new Fixture(configuration);
            final var prompts = new AtomicInteger();
            f.permissions.setGate(request -> {
                prompts.incrementAndGet();
                return ApprovalGate.Result.ALLOW_SESSION;
            });
            final var root = f.context("private", "shared");
            assertTrue(f.spawn(Map.of(), root).success());
            final var child = f.childContext(f.started.getLast(), root);
            final var refused = f.spawn(Map.of("model", "$external/shared"), child);
            assertFalse(refused.success());
            assertTrue(refused.output().contains("stay on their parent's provider"), refused.output());
            assertFalse(f.spawn(Map.of("agent", "reviewer"), child).success());
            assertFalse(f.catalog(child, "external").output().contains("$external/"));
            assertEquals(0, prompts.get());
        }
    }

    @Nested
    @FusionSupport(modules = SwitchingProvidersCannotDropAnAncestorsExcludesScenarioModule.class)
    class SwitchingProvidersCannotDropAnAncestorsExcludesScenario {
        @TempDir
        static Path directory;

        @Test
        void switchingProvidersCannotDropAnAncestorsExcludes(@Fusion final CodingAgentConfiguration configuration) {
            final var f = new Fixture(configuration);
            final var root = f.context("private", "shared");
            assertTrue(f.spawn(Map.of("model", "$external/shared"), root).success());
            final var child = f.childContext(f.started.getLast(), root);
            final var prompts = new AtomicInteger();
            child.permissions().setGate(request -> {
                prompts.incrementAndGet();
                return ApprovalGate.Result.ALLOW_SESSION;
            });
            assertFalse(f.spawn(Map.of("model", "alt"), child).success());
            assertFalse(f.catalog(child).output().contains("$external/alt"));
            assertEquals(0, prompts.get());
            assertTrue(f.spawn(Map.of(), child).success());
            assertFalse(f.spawn(Map.of("model", "alt"), f.childContext(f.started.getLast(), child))
                    .success());
        }
    }

    @Nested
    @FusionSupport(
            modules =
                    ChildDefaultsAndIncludesCannotSilentlyExpandAncestorAuthorizationButCanAskTheUserScenarioModule
                            .class)
    class ChildDefaultsAndIncludesCannotSilentlyExpandAncestorAuthorizationButCanAskTheUserScenario {
        @TempDir
        static Path directory;

        @Test
        void childDefaultsAndIncludesCannotSilentlyExpandAncestorAuthorizationButCanAskTheUser(
                @Fusion final CodingAgentConfiguration configuration) {
            final var f = new Fixture(configuration);
            final var prompts = new AtomicInteger();
            f.permissions.setGate(request -> {
                prompts.incrementAndGet();
                return ApprovalGate.Result.ALLOW_ONCE;
            });
            final var root = f.context("private", "shared");
            assertTrue(f.spawn(Map.of("model", "$external/shared"), root).success());
            final var child = f.childContext(f.started.getLast(), root);
            assertFalse(f.spawn(Map.of(), child).success(), "implicit default cannot escalate authorization");
            assertEquals(1, prompts.get());
            assertTrue(f.spawn(Map.of("model", "alt"), child).success());
            assertEquals(2, prompts.get(), "explicit alternative must still ask the inherited user gate");
            assertTrue(f.permissions.subagentRoutes().routes().isEmpty());
        }
    }

    private final class Fixture {
        private final CodingAgentConfiguration config;
        private final ProviderManager manager;
        private final PermissionService permissions;
        private final List<SubagentRegistry.SubagentState> started = new ArrayList<>();
        private final SpawnSubagentTool spawn;
        private final ListSubagentOptionsTool catalog;

        private Fixture(final CodingAgentConfiguration config) {
            this.config = config;
            manager = new ProviderManager(config, mapper);
            permissions = new PermissionService(config, (config).resolveWorkingDir());
            final var registry = new SubagentRegistry(config, manager);
            final var runner =
                    new SubagentRunner(
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            registry,
                            new PrecisionDurationMarkerProvider(),
                            new BackoffSleeper(),
                            null,
                            null) {
                        @Override
                        public void runAsync(
                                final SubagentRegistry.SubagentState state,
                                final SessionStore sessions,
                                final SnapshotStore snapshots,
                                final TodoStore todos,
                                final ToolContext context,
                                final BooleanSupplier cancelled) {
                            started.add(state);
                            registry.complete(
                                    state.id(), SubagentRegistry.SubagentResult.success("done", "", List.of()));
                        }
                    };
            spawn = new SpawnSubagentTool(registry, runner, null, null, config, manager);
            catalog = new ListSubagentOptionsTool(new AgentProvider(config), manager, config, mapper);
        }

        private ToolContext context(final String provider, final String model) {
            return context(permissions, provider, model, () -> false);
        }

        private ToolContext context(
                final PermissionService permissions,
                final String provider,
                final String model,
                final BooleanSupplier cancelled) {
            return new ToolContext(
                    "parent",
                    null,
                    null,
                    permissions,
                    null,
                    new TodoStore() {},
                    null,
                    null,
                    null,
                    cancelled,
                    ChatSettings.EMPTY.withProviderId(provider).withModel(model),
                    null,
                    testWorkdir,
                    new DisabledSandbox(testWorkdir));
        }

        private ToolResult spawn(final Map<String, Object> options, final ToolContext context) {
            final var args = new HashMap<String, Object>(options);
            args.put("task", "Review the private project");
            return spawn.execute(args, context);
        }

        private ToolContext childContext(final SubagentRegistry.SubagentState state, final ToolContext parent) {
            return new ToolContext(
                    state.childSessionId(),
                    state.id(),
                    state.name(),
                    parent.permissions().forkForChild(),
                    null,
                    new TodoStore() {},
                    null,
                    null,
                    null,
                    () -> false,
                    state.admittedModel().settings(),
                    null,
                    testWorkdir,
                    new DisabledSandbox(testWorkdir),
                    0,
                    state.parentAllowedTools(),
                    parent.subagentBudget(),
                    null,
                    state.spec().subagentScope());
        }

        private ToolResult catalog(final ToolContext context) {
            return catalog.execute(Map.of("kind", "all", "limit", 50), context);
        }

        private ToolResult catalog(final ToolContext context, final String provider) {
            return catalog.execute(Map.of("kind", "models", "limit", 50, "provider", provider), context);
        }
    }

    private static Map<String, String> configurationValues(final Path directory, final Map<String, String> overrides) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", directory.toString());
        entries.put("yca.llm.maxConcurrent", "4");
        entries.put("yca.provider.providers.length", "2");
        entries.put("yca.provider.providers.0.id", "external");
        entries.put("yca.provider.providers.0.model", "shared");
        entries.put("yca.provider.providers.0.models.length", "1");
        entries.put("yca.provider.providers.0.models.0.model", "alt");
        entries.put("yca.provider.providers.1.id", "private");
        entries.put("yca.provider.providers.1.model", "shared");
        entries.put("yca.agents.length", "1");
        entries.put("yca.agents.0.name", "reviewer");
        entries.put("yca.agents.0.prompt", "Review changes.");
        entries.put("yca.agents.0.model.name", "$external/shared");
        entries.putAll(overrides);
        return entries;
    }

    public static class SecureDefaultFollowsLiveSessionProviderAndCatalogIncludesItsConfiguredModelsScenarioModule
            extends ConfigurationTestModule {
        public SecureDefaultFollowsLiveSessionProviderAndCatalogIncludesItsConfiguredModelsScenarioModule() {
            super(configurationValues(
                    SecureDefaultFollowsLiveSessionProviderAndCatalogIncludesItsConfiguredModelsScenario.directory,
                    Map.of()));
        }
    }

    public static class ExplicitAlternativeRequiresDedicatedConsentAndSessionAnswerIsReusedScenarioModule
            extends ConfigurationTestModule {
        public ExplicitAlternativeRequiresDedicatedConsentAndSessionAnswerIsReusedScenarioModule() {
            super(configurationValues(
                    ExplicitAlternativeRequiresDedicatedConsentAndSessionAnswerIsReusedScenario.directory, Map.of()));
        }
    }

    public static class AllowOnceDoesNotBecomeASessionOrProviderGrantScenarioModule extends ConfigurationTestModule {
        public AllowOnceDoesNotBecomeASessionOrProviderGrantScenarioModule() {
            super(configurationValues(AllowOnceDoesNotBecomeASessionOrProviderGrantScenario.directory, Map.of()));
        }
    }

    public static class DeniesCancelsAndUnavailableGateFailBeforeAdmissionScenarioModule
            extends ConfigurationTestModule {
        public DeniesCancelsAndUnavailableGateFailBeforeAdmissionScenarioModule() {
            super(configurationValues(DeniesCancelsAndUnavailableGateFailBeforeAdmissionScenario.directory, Map.of()));
        }
    }

    public static class DefaultsAndProfilesNeverPromptButExplicitModelCanOverrideProfileScenarioModule
            extends ConfigurationTestModule {
        public DefaultsAndProfilesNeverPromptButExplicitModelCanOverrideProfileScenarioModule() {
            super(configurationValues(
                    DefaultsAndProfilesNeverPromptButExplicitModelCanOverrideProfileScenario.directory,
                    Map.of(
                            "yca.subagents.allowModelOverride",
                            "true",
                            "yca.subagents.includes",
                            "$private/shared",
                            "yca.provider.providers.1.subagents.defaultModel",
                            "$external/alt")));
        }
    }

    public static class GlobalOptInProviderOverrideAndIncludeInheritanceApplyToSourceProviderScenarioModule
            extends ConfigurationTestModule {
        public GlobalOptInProviderOverrideAndIncludeInheritanceApplyToSourceProviderScenarioModule() {
            super(configurationValues(
                    GlobalOptInProviderOverrideAndIncludeInheritanceApplyToSourceProviderScenario.directory, Map.of()));
        }
    }

    public static class ExcludesAndAdministrativeCeilingsCannotBeApprovedVariant0ScenarioModule
            extends ConfigurationTestModule {
        public ExcludesAndAdministrativeCeilingsCannotBeApprovedVariant0ScenarioModule() {
            super(configurationValues(
                    ExcludesAndAdministrativeCeilingsCannotBeApprovedVariant0Scenario.directory,
                    Map.of("yca.subagents.excludes", "$external/shared")));
        }
    }

    public static class ExcludesAndAdministrativeCeilingsCannotBeApprovedVariant1ScenarioModule
            extends ConfigurationTestModule {
        public ExcludesAndAdministrativeCeilingsCannotBeApprovedVariant1ScenarioModule() {
            super(configurationValues(
                    ExcludesAndAdministrativeCeilingsCannotBeApprovedVariant1Scenario.directory,
                    Map.of("yca.provider.providers.1.subagents.excludes", "$external/shared")));
        }
    }

    public static class ExcludesAndAdministrativeCeilingsCannotBeApprovedVariant2ScenarioModule
            extends ConfigurationTestModule {
        public ExcludesAndAdministrativeCeilingsCannotBeApprovedVariant2ScenarioModule() {
            super(configurationValues(
                    ExcludesAndAdministrativeCeilingsCannotBeApprovedVariant2Scenario.directory,
                    Map.of("yca.tools.spawnSubagent.allowedProviders", "private")));
        }
    }

    public static class ExcludesAndAdministrativeCeilingsCannotBeApprovedVariant3ScenarioModule
            extends ConfigurationTestModule {
        public ExcludesAndAdministrativeCeilingsCannotBeApprovedVariant3ScenarioModule() {
            super(configurationValues(
                    ExcludesAndAdministrativeCeilingsCannotBeApprovedVariant3Scenario.directory,
                    Map.of("yca.tools.spawnSubagent.allowedModels", "$private/shared")));
        }
    }

    public static class GrantsSurviveModelChangesButNotOtherTabsDisposalOrNewRuntimeScenarioModule
            extends ConfigurationTestModule {
        public GrantsSurviveModelChangesButNotOtherTabsDisposalOrNewRuntimeScenarioModule() {
            super(configurationValues(
                    GrantsSurviveModelChangesButNotOtherTabsDisposalOrNewRuntimeScenario.directory, Map.of()));
        }
    }

    public static class ChangedHardPolicyStillRejectsPreviouslyApprovedRouteScenarioModule
            extends ConfigurationTestModule {
        public ChangedHardPolicyStillRejectsPreviouslyApprovedRouteScenarioModule() {
            super(configurationValues(
                    ChangedHardPolicyStillRejectsPreviouslyApprovedRouteScenario.directory, Map.of()));
        }
    }

    public static class NoSessionUnknownProviderAndStaleCatalogCannotBypassPolicyScenarioModule
            extends ConfigurationTestModule {
        public NoSessionUnknownProviderAndStaleCatalogCannotBypassPolicyScenarioModule() {
            super(configurationValues(
                    NoSessionUnknownProviderAndStaleCatalogCannotBypassPolicyScenario.directory, Map.of()));
        }
    }

    public static class GlobalDefaultAndExplicitEmptyProviderIncludesAndDefaultAreInheritedCorrectlyScenarioModule
            extends ConfigurationTestModule {
        public GlobalDefaultAndExplicitEmptyProviderIncludesAndDefaultAreInheritedCorrectlyScenarioModule() {
            super(configurationValues(
                    GlobalDefaultAndExplicitEmptyProviderIncludesAndDefaultAreInheritedCorrectlyScenario.directory,
                    Map.of()));
        }
    }

    public static
    class PermissiveToolModeStillRequiresDestinationConsentAndApprovedUnlistedModelsAreDiscoverableScenarioModule
            extends ConfigurationTestModule {
        public
        PermissiveToolModeStillRequiresDestinationConsentAndApprovedUnlistedModelsAreDiscoverableScenarioModule() {
            super(configurationValues(
                    PermissiveToolModeStillRequiresDestinationConsentAndApprovedUnlistedModelsAreDiscoverableScenario
                            .directory,
                    Map.of("yca.permissions.mode", "yolo", "yca.mode", "build")));
        }
    }

    public static class AstraParentDiscoversAndSpawnsSolOnTheSameCodexProviderScenarioModule
            extends ConfigurationTestModule {
        public AstraParentDiscoversAndSpawnsSolOnTheSameCodexProviderScenarioModule() {
            super(configurationValues(
                    AstraParentDiscoversAndSpawnsSolOnTheSameCodexProviderScenario.directory,
                    Map.of(
                            "yca.provider.providers.1.id", "codex",
                            "yca.provider.providers.1.model", "gpt-6-astra",
                            "yca.provider.providers.1.models.length", "1",
                            "yca.provider.providers.1.models.0.model", "gpt-6.1-sol",
                            "yca.provider.providers.1.subagents.defaultModel", "$codex/gpt-6.1-sol",
                            "yca.provider.providers.1.subagents.excludes", "$codex/gpt-6-astra")));
        }
    }

    public static class AstraSessionListsSolCatalogWithoutAuthorizingItAndPinsBareDuplicateToAstraScenarioModule
            extends ConfigurationTestModule {
        public AstraSessionListsSolCatalogWithoutAuthorizingItAndPinsBareDuplicateToAstraScenarioModule() {
            super(configurationValues(
                    AstraSessionListsSolCatalogWithoutAuthorizingItAndPinsBareDuplicateToAstraScenario.directory,
                    Map.of(
                            "yca.provider.providers.0.id", "sol",
                            "yca.provider.providers.1.id", "astra",
                            "yca.provider.providers.1.models.length", "1",
                            "yca.provider.providers.1.models.0.model", "alt")));
        }
    }

    public static
    class BareExternallyDeclaredModelNeedsConsentForSessionProviderAndItsGrantRemainsDiscoverableScenarioModule
            extends ConfigurationTestModule {
        public BareExternallyDeclaredModelNeedsConsentForSessionProviderAndItsGrantRemainsDiscoverableScenarioModule() {
            super(configurationValues(
                    BareExternallyDeclaredModelNeedsConsentForSessionProviderAndItsGrantRemainsDiscoverableScenario
                            .directory,
                    Map.of()));
        }
    }

    public static
    class ConfiguredSameProviderDefaultIsAuthorizedWithOverridesDisabledButDifferentParentIsNotScenarioModule
            extends ConfigurationTestModule {
        public ConfiguredSameProviderDefaultIsAuthorizedWithOverridesDisabledButDifferentParentIsNotScenarioModule() {
            super(configurationValues(
                    ConfiguredSameProviderDefaultIsAuthorizedWithOverridesDisabledButDifferentParentIsNotScenario
                            .directory,
                    Map.of(
                            "yca.provider.providers.1.models.length", "1",
                            "yca.provider.providers.1.models.0.model", "alt",
                            "yca.provider.providers.1.subagents.defaultModel", "$private/alt",
                            "yca.provider.providers.1.subagents.allowModelOverride", "false")));
        }
    }

    public static class EmptyOrAbsentIncludesDoesNotAuthorizeAlternativesEvenInYoloVariant0ScenarioModule
            extends ConfigurationTestModule {
        public EmptyOrAbsentIncludesDoesNotAuthorizeAlternativesEvenInYoloVariant0ScenarioModule() {
            super(values());
        }

        private static Map<String, String> values() {
            final var overrides = new HashMap<String, String>();
            overrides.put("yca.subagents.allowModelOverride", "true");
            overrides.put("yca.permissions.mode", "yolo");
            overrides.put("yca.mode", "build");
            overrides.put("yca.provider.providers.1.models.length", "1");
            overrides.put("yca.provider.providers.1.models.0.model", "alt");
            if (!"omitted".equals("omitted")) {
                overrides.put("yca.subagents.includes", "omitted");
            }
            return configurationValues(
                    EmptyOrAbsentIncludesDoesNotAuthorizeAlternativesEvenInYoloVariant0Scenario.directory, overrides);
        }
    }

    public static class EmptyOrAbsentIncludesDoesNotAuthorizeAlternativesEvenInYoloVariant1ScenarioModule
            extends ConfigurationTestModule {
        public EmptyOrAbsentIncludesDoesNotAuthorizeAlternativesEvenInYoloVariant1ScenarioModule() {
            super(values());
        }

        private static Map<String, String> values() {
            final var overrides = new HashMap<String, String>();
            overrides.put("yca.subagents.allowModelOverride", "true");
            overrides.put("yca.permissions.mode", "yolo");
            overrides.put("yca.mode", "build");
            overrides.put("yca.provider.providers.1.models.length", "1");
            overrides.put("yca.provider.providers.1.models.0.model", "alt");
            if (!"omitted".equals("")) {
                overrides.put("yca.subagents.includes", "");
            }
            return configurationValues(
                    EmptyOrAbsentIncludesDoesNotAuthorizeAlternativesEvenInYoloVariant1Scenario.directory, overrides);
        }
    }

    public static
    class ExactIncludesAuthorizeExplicitAlternativesWithoutPromptsButNotImplicitCrossProviderRoutesScenarioModule
            extends ConfigurationTestModule {
        public
        ExactIncludesAuthorizeExplicitAlternativesWithoutPromptsButNotImplicitCrossProviderRoutesScenarioModule() {
            super(configurationValues(
                    ExactIncludesAuthorizeExplicitAlternativesWithoutPromptsButNotImplicitCrossProviderRoutesScenario
                            .directory,
                    Map.of()));
        }
    }

    public static
    class GlobalSourceAndDestinationExcludesAreAdditiveAndBeatIncludesAndPreviousGrantsVariant0ScenarioModule
            extends ConfigurationTestModule {
        public GlobalSourceAndDestinationExcludesAreAdditiveAndBeatIncludesAndPreviousGrantsVariant0ScenarioModule() {
            super(values());
        }

        private static Map<String, String> values() {
            final var overrides = new HashMap<String, String>();
            overrides.put("yca.subagents.allowModelOverride", "true");
            overrides.put("yca.subagents.includes", "$external/shared");
            // Non-matching narrower lists must not replace a broader exclusion.
            overrides.put("yca.subagents.excludes", "$private/irrelevant");
            overrides.put("yca.provider.providers.1.subagents.excludes", "$private/irrelevant");
            overrides.put("yca.provider.providers.0.subagents.excludes", "$external/irrelevant");
            overrides.put("yca.subagents.excludes", "$external/shared");
            return configurationValues(
                    GlobalSourceAndDestinationExcludesAreAdditiveAndBeatIncludesAndPreviousGrantsVariant0Scenario
                            .directory,
                    overrides);
        }
    }

    public static
    class GlobalSourceAndDestinationExcludesAreAdditiveAndBeatIncludesAndPreviousGrantsVariant1ScenarioModule
            extends ConfigurationTestModule {
        public GlobalSourceAndDestinationExcludesAreAdditiveAndBeatIncludesAndPreviousGrantsVariant1ScenarioModule() {
            super(values());
        }

        private static Map<String, String> values() {
            final var overrides = new HashMap<String, String>();
            overrides.put("yca.subagents.allowModelOverride", "true");
            overrides.put("yca.subagents.includes", "$external/shared");
            // Non-matching narrower lists must not replace a broader exclusion.
            overrides.put("yca.subagents.excludes", "$private/irrelevant");
            overrides.put("yca.provider.providers.1.subagents.excludes", "$private/irrelevant");
            overrides.put("yca.provider.providers.0.subagents.excludes", "$external/irrelevant");
            overrides.put("yca.provider.providers.1.subagents.excludes", "$external/shared");
            return configurationValues(
                    GlobalSourceAndDestinationExcludesAreAdditiveAndBeatIncludesAndPreviousGrantsVariant1Scenario
                            .directory,
                    overrides);
        }
    }

    public static
    class GlobalSourceAndDestinationExcludesAreAdditiveAndBeatIncludesAndPreviousGrantsVariant2ScenarioModule
            extends ConfigurationTestModule {
        public GlobalSourceAndDestinationExcludesAreAdditiveAndBeatIncludesAndPreviousGrantsVariant2ScenarioModule() {
            super(values());
        }

        private static Map<String, String> values() {
            final var overrides = new HashMap<String, String>();
            overrides.put("yca.subagents.allowModelOverride", "true");
            overrides.put("yca.subagents.includes", "$external/shared");
            // Non-matching narrower lists must not replace a broader exclusion.
            overrides.put("yca.subagents.excludes", "$private/irrelevant");
            overrides.put("yca.provider.providers.1.subagents.excludes", "$private/irrelevant");
            overrides.put("yca.provider.providers.0.subagents.excludes", "$external/irrelevant");
            overrides.put("yca.provider.providers.0.subagents.excludes", "$external/shared");
            return configurationValues(
                    GlobalSourceAndDestinationExcludesAreAdditiveAndBeatIncludesAndPreviousGrantsVariant2Scenario
                            .directory,
                    overrides);
        }
    }

    public static class SameProviderExcludesBeatParentDefaultProfileAndIncludesVariant0ScenarioModule
            extends ConfigurationTestModule {
        public SameProviderExcludesBeatParentDefaultProfileAndIncludesVariant0ScenarioModule() {
            super(values());
        }

        private static Map<String, String> values() {
            final var overrides = new HashMap<String, String>();
            overrides.put("yca.subagents.allowModelOverride", "true");
            overrides.put("yca.subagents.includes", "$private/shared");
            overrides.put("yca.subagents.defaultModel", "$private/shared");
            overrides.put("yca.agents.0.model.name", "$private/shared");
            overrides.put("yca.subagents.excludes", "$private/irrelevant");
            overrides.put("yca.provider.providers.1.subagents.excludes", "$private/irrelevant");
            overrides.put("yca.subagents.excludes", "$private/shared");
            return configurationValues(
                    SameProviderExcludesBeatParentDefaultProfileAndIncludesVariant0Scenario.directory, overrides);
        }
    }

    public static class SameProviderExcludesBeatParentDefaultProfileAndIncludesVariant1ScenarioModule
            extends ConfigurationTestModule {
        public SameProviderExcludesBeatParentDefaultProfileAndIncludesVariant1ScenarioModule() {
            super(values());
        }

        private static Map<String, String> values() {
            final var overrides = new HashMap<String, String>();
            overrides.put("yca.subagents.allowModelOverride", "true");
            overrides.put("yca.subagents.includes", "$private/shared");
            overrides.put("yca.subagents.defaultModel", "$private/shared");
            overrides.put("yca.agents.0.model.name", "$private/shared");
            overrides.put("yca.subagents.excludes", "$private/irrelevant");
            overrides.put("yca.provider.providers.1.subagents.excludes", "$private/irrelevant");
            overrides.put("yca.provider.providers.1.subagents.excludes", "$private/shared");
            return configurationValues(
                    SameProviderExcludesBeatParentDefaultProfileAndIncludesVariant1Scenario.directory, overrides);
        }
    }

    public static class NestedConfigurationJsonKeepsOmittedVersusExplicitEmptyIncludesScenarioModule
            extends ConfigurationTestModule {
        public NestedConfigurationJsonKeepsOmittedVersusExplicitEmptyIncludesScenarioModule() {
            super(configurationValues(
                    NestedConfigurationJsonKeepsOmittedVersusExplicitEmptyIncludesScenario.directory, Map.of()));
        }
    }

    public static class DefaultDepthPreservesDirectChildrenOnlyScenarioModule extends ConfigurationTestModule {
        public DefaultDepthPreservesDirectChildrenOnlyScenarioModule() {
            super(configurationValues(DefaultDepthPreservesDirectChildrenOnlyScenario.directory, Map.of()));
        }
    }

    public static class DestinationProviderDepthAllowsModelsToChangeWithoutResettingDepthScenarioModule
            extends ConfigurationTestModule {
        public DestinationProviderDepthAllowsModelsToChangeWithoutResettingDepthScenarioModule() {
            super(configurationValues(
                    DestinationProviderDepthAllowsModelsToChangeWithoutResettingDepthScenario.directory,
                    Map.of(
                            "yca.subagents.allowModelOverride", "true",
                            "yca.subagents.includes", "$external/shared,$external/alt",
                            "yca.provider.providers.0.subagents.maxDepth", "2")));
        }
    }

    public static class NestedProviderBoundaryCannotBeApprovedOrDiscoveredThroughAProviderFilterScenarioModule
            extends ConfigurationTestModule {
        public NestedProviderBoundaryCannotBeApprovedOrDiscoveredThroughAProviderFilterScenarioModule() {
            super(configurationValues(
                    NestedProviderBoundaryCannotBeApprovedOrDiscoveredThroughAProviderFilterScenario.directory,
                    Map.of("yca.provider.providers.1.subagents.maxDepth", "3")));
        }
    }

    public static class SwitchingProvidersCannotDropAnAncestorsExcludesScenarioModule extends ConfigurationTestModule {
        public SwitchingProvidersCannotDropAnAncestorsExcludesScenarioModule() {
            super(configurationValues(
                    SwitchingProvidersCannotDropAnAncestorsExcludesScenario.directory,
                    Map.of(
                            "yca.subagents.allowModelOverride", "true",
                            "yca.subagents.includes", "$external/shared,$external/alt",
                            "yca.provider.providers.0.subagents.maxDepth", "3",
                            "yca.provider.providers.1.subagents.excludes", "$external/alt")));
        }
    }

    public static class ChildDefaultsAndIncludesCannotSilentlyExpandAncestorAuthorizationButCanAskTheUserScenarioModule
            extends ConfigurationTestModule {
        public ChildDefaultsAndIncludesCannotSilentlyExpandAncestorAuthorizationButCanAskTheUserScenarioModule() {
            super(configurationValues(
                    ChildDefaultsAndIncludesCannotSilentlyExpandAncestorAuthorizationButCanAskTheUserScenario.directory,
                    Map.of(
                            "yca.provider.providers.0.subagents.maxDepth", "2",
                            "yca.provider.providers.0.subagents.allowModelOverride", "true",
                            "yca.provider.providers.0.subagents.includes", "$external/alt",
                            "yca.provider.providers.0.subagents.defaultModel", "alt")));
        }
    }

    private CodingAgentConfiguration policy(
            final CodingAgentConfiguration baseline,
            final SubagentConfiguration global,
            final ProviderSubagentConfiguration first,
            final ProviderSubagentConfiguration second,
            final PermissionsConfiguration permissions,
            final AgentMode mode) {
        return new CodingAgentConfiguration(
                baseline.storageHome(),
                baseline.home(),
                baseline.workingDir(),
                baseline.projectDir(),
                new ProviderListConfiguration(
                        List.of(
                                withProviderPolicy(
                                        baseline.provider().providers().get(0), first),
                                withProviderPolicy(
                                        baseline.provider().providers().get(1), second)),
                        baseline.provider().defaultProvider(),
                        baseline.provider().baseUrl(),
                        baseline.provider().apiKey(),
                        baseline.provider().model(),
                        baseline.provider().api(),
                        baseline.provider().contextWindow(),
                        baseline.provider().outputTokens(),
                        baseline.provider().maxConcurrent(),
                        baseline.provider().timeoutSeconds(),
                        baseline.provider().color()),
                baseline.model(),
                baseline.llm(),
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
                mode,
                baseline.httpClient(),
                baseline.tools(),
                global,
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

    private ProviderConfiguration withProviderPolicy(
            final ProviderConfiguration baseline, final ProviderSubagentConfiguration subagents) {
        return new ProviderConfiguration(
                baseline.id(),
                baseline.baseUrl(),
                baseline.apiKey(),
                baseline.model(),
                baseline.api(),
                baseline.contextWindow(),
                baseline.outputTokens(),
                baseline.releaseDate(),
                baseline.tuning(),
                baseline.models(),
                baseline.maxConcurrent(),
                baseline.timeoutSeconds(),
                baseline.providerSpecificOptions(),
                baseline.headers(),
                baseline.dynamicHeaders(),
                baseline.color(),
                baseline.prompt(),
                subagents,
                baseline.backgroundModelOverrides(),
                baseline.compaction(),
                baseline.capabilities());
    }

    private CodingAgentConfiguration unrestrictedPolicy(final CodingAgentConfiguration baseline) {
        return policy(
                baseline,
                new SubagentConfiguration(
                        baseline.subagents().enabled(),
                        baseline.subagents().maxConcurrent(),
                        baseline.subagents().maxIterations(),
                        false,
                        List.of(),
                        List.of(),
                        ""),
                new ProviderSubagentConfiguration(
                        null,
                        null,
                        List.of(),
                        null,
                        baseline.provider().providers().get(0).subagents().maxDepth()),
                new ProviderSubagentConfiguration(
                        null,
                        null,
                        List.of(),
                        null,
                        baseline.provider().providers().get(1).subagents().maxDepth()),
                baseline.permissions(),
                baseline.mode());
    }
}
