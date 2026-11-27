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
package io.yupiik.ia.agent.core.permissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.BashPermissionsConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CommandOptionsConfiguration;
import io.yupiik.ia.agent.core.configuration.CommandPermissionConfiguration;
import io.yupiik.ia.agent.core.configuration.CommandStartMatcherConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.configuration.PermissionsConfiguration;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@FusionSupport(modules = PermissionServiceTest.Sources.class)
class PermissionServiceTest {

    @TempDir
    static Path storageHome;

    @Fusion
    private CodingAgentConfiguration baseline;

    public static class Sources extends ConfigurationTestModule {
        public Sources() {
            super(Map.of("yca.storageHome", storageHome.toString()));
        }
    }

    private PermissionService service(final PermissionMode mode, final List<String> allow, final List<String> deny) {
        return service(mode, allow, deny, baseline.permissions().bash().rules());
    }

    private PermissionService service(
            final PermissionMode mode,
            final List<String> allow,
            final List<String> deny,
            final List<CommandPermissionConfiguration> rules) {
        final var bash = baseline.permissions().bash();
        final var configuration = withPermissions(
                baseline,
                new PermissionsConfiguration(
                        mode,
                        allow,
                        deny,
                        new BashPermissionsConfiguration(rules, bash.readOnlyPromptHints(), bash.maxCombined())));
        final var service = new PermissionService(configuration, configuration.resolveWorkingDir());
        // these build-mode behaviour tests rely on a BUILD baseline; plan mode is
        // exercised explicitly via setMode(AgentMode.PLAN) in dedicated tests
        service.setMode(AgentMode.BUILD);
        return service;
    }

    @Test
    void fullYoloParsingAndPermissionBypass() {
        for (final var value : List.of("full_yolo", "full-yolo", " FULL_YOLO ")) {
            assertTrue(PermissionMode.isValue(value));
            assertEquals(PermissionMode.FULL_YOLO, PermissionMode.fromConfigurationString(value));
        }
        assertTrue(PermissionMode.VALUES.contains("full_yolo"));
        assertEquals(PermissionMode.YOLO, PermissionMode.fromConfigurationString("all"));
        final var service = service(PermissionMode.YOLO, List.of(), List.of("write", "rm *", "bash:git *"));
        service.setMode(AgentMode.PLAN);
        assertFalse(service.check("write", ToolCategory.EDIT, Map.of()).allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "rm file"))
                .allowed());
        service.setGate(request -> {
            throw new AssertionError("FULL_YOLO must never ask");
        });
        service.setPermissionMode(PermissionMode.FULL_YOLO);
        assertTrue(service.check("write", ToolCategory.EDIT, Map.of()).allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "rm file; git push"))
                .allowed());
        assertTrue(service.check("other", ToolCategory.EDIT, Map.of()).allowed());
        service.setPermissionMode(PermissionMode.YOLO);
        assertFalse(service.check("write", ToolCategory.EDIT, Map.of()).allowed());
    }

    @Test
    void permissionModeChangesExpirePendingGrantsIncludingChildren() throws Exception {
        for (final var result : List.of(ApprovalGate.Result.ALLOW_ONCE, ApprovalGate.Result.ALLOW_SESSION)) {
            for (final var child : List.of(false, true)) {
                final var service = service(PermissionMode.ASK, List.of(), List.of());
                final var target = child ? service.forkForChild() : service;
                final var entered = new CountDownLatch(1);
                final var release = new CountDownLatch(1);
                target.setGate(request -> {
                    entered.countDown();
                    try {
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                    } catch (final InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                    return result;
                });
                try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    final var pending = executor.submit(() -> target.check("write", ToolCategory.EDIT, Map.of()));
                    try {
                        assertTrue(entered.await(10, TimeUnit.SECONDS));
                        service.setPermissionMode(PermissionMode.FULL_YOLO);
                        service.setPermissionMode(PermissionMode.ASK);
                    } finally {
                        release.countDown();
                    }
                    assertFalse(pending.get(10, TimeUnit.SECONDS).allowed());
                    assertTrue(target.sessionGrants().isEmpty());
                }
            }
        }
    }

    @Test
    void childCannotRetainFullYoloOrSessionGrantsAfterParentModeChange() {
        final var parent = service(PermissionMode.FULL_YOLO, List.of(), List.of("write"));
        final var child = parent.forkForChild();
        child.setPermissionMode(PermissionMode.FULL_YOLO);
        child.grantSession("other");
        assertTrue(child.check("write", ToolCategory.EDIT, Map.of()).allowed());
        parent.setPermissionMode(PermissionMode.ASK);
        assertEquals(PermissionMode.ASK, child.permissionMode());
        assertFalse(child.check("write", ToolCategory.EDIT, Map.of()).allowed());
        assertFalse(child.check("other", ToolCategory.EDIT, Map.of()).allowed());
        assertTrue(child.sessionGrants().isEmpty());
    }

    @Test
    void sandboxApprovalIsIndependentOfToolPermissionMode() {
        for (final var mode : PermissionMode.values()) {
            final var service = service(mode, List.of("*"), List.of());
            final var request = new AtomicReference<ApprovalGate.Request>();
            service.setGate(approval -> {
                request.set(approval);
                return ApprovalGate.Result.ALLOW_SESSION;
            });
            assertEquals(
                    ApprovalGate.Result.ALLOW_SESSION,
                    service.approveSandbox("Network access", "Sandbox blocked host", Map.of("host", "example.org")));
            assertEquals(ApprovalGate.Origin.SANDBOX, request.get().origin());
            assertEquals("sandbox", request.get().toolName());
            assertEquals("Network access", request.get().title());
            assertEquals("Sandbox blocked host", request.get().detail());
            assertEquals(Map.of("host", "example.org"), request.get().args());
            service.setGate(approval -> ApprovalGate.Result.DENY);
            assertEquals(ApprovalGate.Result.DENY, service.approveSandbox("Read", "Outside root", Map.of()));
            service.setGate(null);
            assertEquals(ApprovalGate.Result.DENY, service.approveSandbox("Read", "Outside root", Map.of()));
            service.setGate(approval -> null);
            assertEquals(ApprovalGate.Result.DENY, service.approveSandbox("Read", "Outside root", Map.of()));
        }
    }

    @Test
    void toolApprovalRetainsPermissionOrigin() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        final var origin = new AtomicReference<ApprovalGate.Origin>();
        service.setGate(request -> {
            origin.set(request.origin());
            return ApprovalGate.Result.ALLOW_ONCE;
        });
        assertTrue(service.check("write", ToolCategory.EDIT, Map.of("path", "file"))
                .allowed());
        assertEquals(ApprovalGate.Origin.PERMISSION, origin.get());
    }

    private static List<CommandPermissionConfiguration> rules(
            final PermissionMode plan, final String... commandStarts) {
        return Arrays.stream(commandStarts)
                .map(start -> new CommandPermissionConfiguration(
                        new CommandStartMatcherConfiguration(
                                new CommandOptionsConfiguration(List.of(start)), List.of()),
                        plan,
                        null))
                .toList();
    }

    private static List<CommandPermissionConfiguration> ruleWithExcludes(
            final PermissionMode plan, final String commandStart, final String... excludedOptions) {
        return List.of(new CommandPermissionConfiguration(
                new CommandStartMatcherConfiguration(
                        new CommandOptionsConfiguration(List.of(commandStart)),
                        Arrays.stream(excludedOptions)
                                .map(option -> new CommandOptionsConfiguration(List.of(option)))
                                .toList()),
                plan,
                null));
    }

    private static CodingAgentConfiguration withPermissions(
            final CodingAgentConfiguration baseline, final PermissionsConfiguration permissions) {
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

    @Test
    void readAlwaysAllowed() {
        final var decision =
                service(PermissionMode.ASK, List.of(), List.of()).check("grep", ToolCategory.READ, Map.of());
        assertTrue(decision.allowed());
    }

    @Test
    void defaultModeIsPlanWhenConfigUnset() {
        final var configuration = baseline;
        final var service = new PermissionService(configuration, configuration.resolveWorkingDir());
        assertEquals(AgentMode.PLAN, service.mode());
    }

    @Test
    void denyBeatsAllow() {
        final var service = service(PermissionMode.YOLO, List.of("bash"), List.of("bash:rm *"));
        assertFalse(service.check("bash:rm -rf /", ToolCategory.BASH, Map.of("command", "rm -rf /"))
                .allowed());
        assertTrue(service.check("bash:ls", ToolCategory.BASH, Map.of("command", "ls"))
                .allowed());
    }

    @Test
    void configAllowBypassesGate() {
        final var service = service(PermissionMode.ASK, List.of("write_file"), List.of());
        assertTrue(service.check("write_file", ToolCategory.EDIT, Map.of()).allowed());
    }

    @Test
    void runtimePermissionModeOverridesStaticConfig() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        // statically ASK so without a gate the edit tool would fall through to the gate
        assertFalse(service.check("write_file", ToolCategory.EDIT, Map.of()).allowed());
        service.setPermissionMode(PermissionMode.YOLO);
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "rm -rf /"))
                .allowed());
        assertTrue(service.check("write_file", ToolCategory.EDIT, Map.of()).allowed());
    }

    @Test
    void runtimePermissionModeAsyncGivesAutoEdit() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setPermissionMode(PermissionMode.AUTO_EDIT);
        assertTrue(service.check("write_file", ToolCategory.EDIT, Map.of()).allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "mvn install"))
                .allowed());
    }

    @Test
    void clearPermissionModeRestoresConfiguredDefault() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setPermissionMode(PermissionMode.YOLO);
        assertSame(service.permissionMode(), PermissionMode.YOLO);
        service.setPermissionMode(null);
        assertEquals(PermissionMode.ASK, service.permissionMode());
    }

    @Test
    void permissionModeValuesRecogniseAcceptedForms() {
        assertTrue(PermissionMode.isValue("ask"));
        assertTrue(PermissionMode.isValue("auto_edit"));
        assertTrue(PermissionMode.isValue("autoedit"));
        assertTrue(PermissionMode.isValue("yolo"));
        // `all` is accepted as a yolo alias in configuration (rule modes and yca.permissions.mode)
        assertTrue(PermissionMode.isValue("all"));
        assertEquals(PermissionMode.YOLO, PermissionMode.fromConfigurationString("all"));
        assertFalse(PermissionMode.isValue(""));
        assertFalse(PermissionMode.isValue(null));
        assertFalse(PermissionMode.isValue("bogus"));
        // the completion/usage hints are derived from the enum, so they must be self consistent
        assertTrue(PermissionMode.VALUES.contains("auto_edit"));
        assertTrue(PermissionMode.VALUES.contains("yolo"));
        assertTrue(PermissionMode.VALUES.contains("ask"));
    }

    @Test
    void askDeniesWithoutGate() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        final var decision = service.check("write_file", ToolCategory.EDIT, Map.of("path", "a.txt"));
        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("no interactive approval"));
        assertTrue(decision.reason().contains("ask"));
        assertTrue(decision.reason().contains("yca.permissions.mode"));
    }

    @Test
    void bashGateIsEnrichedWithTheFailingSegmentCauseAndStillPrompts() {
        // git add is not a read-only family: the gate must prompt (not hard-deny) and its detail must
        // explain why it is asked, in both plan and build modes
        final var command = "cd /opt/x && git add -A && git status --short";
        for (final var agentMode : List.of(AgentMode.BUILD, AgentMode.PLAN)) {
            final var service = service(PermissionMode.ASK, List.of(), List.of());
            service.setMode(agentMode);
            final var detail = new AtomicReference<String>();
            service.setGate(request -> {
                final var title = request.title();
                final var d = request.detail();
                final var tool = request.toolName();
                final var args = request.args();
                detail.set(d);
                return ApprovalGate.Result.ALLOW_ONCE;
            });
            assertTrue(
                    service.check("bash", ToolCategory.BASH, Map.of("command", command))
                            .allowed(),
                    "an unclassified bash segment must still be gate-approvable (" + agentMode + ')');
            assertTrue(
                    detail.get() != null
                            && detail.get().contains("Why asked")
                            && detail.get().contains("git add -A")
                            && detail.get().contains("read-only bash families"),
                    "the gate detail must explain the failing segment (" + agentMode + "): " + detail.get());
        }
    }

    @Test
    void bashUserDenialCarriesTheFailingSegmentCause() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setMode(AgentMode.PLAN);
        service.setGate(request -> ApprovalGate.Result.DENY);
        final var decision = service.check("bash", ToolCategory.BASH, Map.of("command", "git add -A"));
        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("refused by the user"));
        assertTrue(decision.reason().contains("git add -A"));
        assertTrue(decision.reason().contains("read-only bash families"));
    }

    @Test
    void headlessBashDenialAppendsTheFailingSegmentCause() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setMode(AgentMode.PLAN);
        final var decision = service.check("bash", ToolCategory.BASH, Map.of("command", "git add -A"));
        assertFalse(decision.allowed());
        // the existing message is kept and the cause is appended, not replaced
        assertTrue(decision.reason().contains("no interactive approval"));
        assertTrue(decision.reason().contains("git add -A"));
        assertTrue(decision.reason().contains("read-only bash families"));
    }

    @Test
    void gateApprovedAndRefused() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setGate(request -> ApprovalGate.Result.ALLOW_ONCE);
        assertTrue(service.check("write_file", ToolCategory.EDIT, Map.of()).allowed());

        final var refused = service(PermissionMode.ASK, List.of(), List.of());
        refused.setGate(request -> ApprovalGate.Result.DENY);
        final var decision = refused.check("write_file", ToolCategory.EDIT, Map.of());
        assertFalse(decision.allowed());
        assertEquals("refused by the user", decision.reason());
    }

    @Test
    void sessionGrantAppliesOnceGranted() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of()).allowed());
        service.grantSession("bash");
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of()).allowed());
    }

    @Test
    void yoloAllowsEverything() {
        final var service = service(PermissionMode.YOLO, List.of(), List.of());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of()).allowed());
        assertTrue(service.check("write_file", ToolCategory.EDIT, Map.of()).allowed());
    }

    @Test
    void autoEditAllowsEditsOnly() {
        final var service = service(PermissionMode.AUTO_EDIT, List.of(), List.of());
        assertTrue(service.check("write_file", ToolCategory.EDIT, Map.of()).allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of()).allowed());
    }

    @Test
    void planModeBlocksEditAndBashWithoutGate() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setMode(AgentMode.PLAN);
        // no gate installed: state-changing tools fall through to the approval flow
        // which refuses without an interactive gate
        assertFalse(service.check("write_file", ToolCategory.EDIT, Map.of()).allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of()).allowed());
    }

    @Test
    void planModeConsultsGateForStateChangingBash() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        final AtomicReference<String> asked = new AtomicReference<>();
        service.setMode(AgentMode.PLAN);
        service.setGate(request -> {
            final var title = request.title();
            asked.set(title);
            return ApprovalGate.Result.ALLOW_ONCE;
        });
        // non-readonly bash in plan mode is not hard-denied: it is routed to the
        // approval gate so the user can approve it
        final var decision = service.check("bash", ToolCategory.BASH, Map.of("command", "cd /somewhere && doThing"));
        assertTrue(decision.allowed());
        assertNotNull(asked.get(), "the approval gate must be consulted in plan mode for state-changing tools");
    }

    @Test
    void planModeConsultsGateForEdit() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        final AtomicReference<String> asked = new AtomicReference<>();
        service.setMode(AgentMode.PLAN);
        service.setGate(request -> {
            final var title = request.title();
            asked.set(title);
            return ApprovalGate.Result.ALLOW_ONCE;
        });
        final var decision = service.check("edit_file", ToolCategory.EDIT, Map.of("path", "a.txt"));
        assertTrue(decision.allowed());
        assertNotNull(asked.get(), "the approval gate must be consulted for edit tools in plan mode");
    }

    @Test
    void planModeAllowsAndBlocksBashByPrefix() {
        final var service =
                service(PermissionMode.ASK, List.of(), List.of(), rules(PermissionMode.YOLO, "git status", "ls"));
        service.setMode(AgentMode.PLAN);
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "git status --short"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "ls -la"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "rm -rf /"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "git push"))
                .allowed());
    }

    @Test
    void planModeBashSegmentsCompoundCommands() {
        final var service = service(
                PermissionMode.ASK,
                List.of(),
                List.of(),
                rules(PermissionMode.YOLO, "git status", "git diff", "ls", "grep", "cat"));
        service.setMode(AgentMode.PLAN);
        // cd prefix is tolerated, every other segment must be allowlisted
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "cd core && grep -n foo pom.xml"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "git status && git diff HEAD"))
                .allowed());
        // a harmful segment anywhere denies the whole command
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "git diff HEAD && rm -rf /"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "ls; git push"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "cat pom.xml | sh"))
                .allowed());
    }

    @Test
    void planModeBashRejectsRedirectionAndSubstitution() {
        final var service = service(
                PermissionMode.ASK, List.of(), List.of(), rules(PermissionMode.YOLO, "grep", "echo", "git diff"));
        service.setMode(AgentMode.PLAN);
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "grep foo bar > out.txt"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "echo $(rm -rf /)"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "echo `rm -rf /`"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "cd $(git rev-parse) && ls"))
                .allowed());
        // harmless stderr silencing stays allowed
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "grep foo bar 2>/dev/null"))
                .allowed());
    }

    @Test
    void planModeAllowsReadOnlyGitInspectionsWithoutGate() {
        // no gate, no explicit rules: plan mode + the built-in default rules must
        // let read-only git inspection pipelines through (regression for `git show ... | head -60`
        // denied headless because git show/merge-base/xargs were missing from the defaults)
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setMode(AgentMode.PLAN);
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "git show --stat 7483271 | head -60"))
                .allowed());
        assertTrue(service.check(
                        "bash",
                        ToolCategory.BASH,
                        Map.of("command", "git merge-base HEAD master | xargs git log --oneline -1"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "grep -m1 '<version>' pom.xml"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "git remote add origin https://x"))
                .allowed());
    }

    @Test
    void planModeAllowsReadOnlyTextProcessingWithoutGate() {
        // no gate, no explicit rules: plan mode + built-in default rules must let
        // read-only text processing pipelines through (regression for `cat X | awk ...; echo ...; grep -n ...`
        // denied headless because awk was missing from the defaults)
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setMode(AgentMode.PLAN);
        final var inspection =
                "cat fusion-handlebars/src/main/java/io/yupiik/fusion/framework/handlebars/helper/Truthiness.java |"
                        + " awk 'NR>=16'; echo ====NEW-IF; grep -n newIf"
                        + " fusion-handlebars/src/main/java/io/yupiik/fusion/framework/handlebars/helper/Truthiness.java";
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", inspection))
                .allowed());
        // harmless read-only forms stay allowed
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "diff -u pom.xml pom.xml.orig"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "date +%s"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "sha256sum pom.xml"))
                .allowed());
        // write/impure forms are carved out (redirection, diff -o, date -s)
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "awk 'NR>=16' pom.xml > out.txt"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "diff -o out.txt a b"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "date -s '2026-01-01'"))
                .allowed());
    }

    @Test
    void planModeBashToleratesEnvPrefixes() {
        final var service = service(PermissionMode.ASK, List.of(), List.of(), rules(PermissionMode.YOLO, "grep"));
        service.setMode(AgentMode.PLAN);
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "LC_ALL=C grep foo bar"))
                .allowed());
        // env prefix does not whitelist a non allowlisted command
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "LC_ALL=C rm -rf /"))
                .allowed());
    }

    @Test
    void planModeAllowsArchiveInspectionLoops() {
        // regression: `for j in *.jar; do unzip -l $j 2>/dev/null; done` was denied headless in plan
        // mode because `unzip -l` was not a read-only family (the `do`/`done` keywords are syntax,
        // and the tolerated 2>/dev/null redirect stays allowed)
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setMode(AgentMode.PLAN);
        assertTrue(service.check(
                        "bash",
                        ToolCategory.BASH,
                        Map.of("command", "for j in *.jar; do unzip -l $j 2>/dev/null; done"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "unzip -Z dist.zip | head -20"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "jar tf app.jar | grep Main"))
                .allowed());
        // extraction writes are not part of the family
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "unzip dist.zip -d out/"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "jar xf app.jar"))
                .allowed());
    }

    @Test
    void denyListMatchesNormalizedLoopSegments() {
        // `do rm -rf $f 2>/dev/null` must not bypass a `rm *` deny through the `do` keyword
        // and the tolerated redirect: the segment is normalized to `rm -rf $f` before matching
        final var service = service(PermissionMode.ASK, List.of(), List.of("rm *"));
        final var decision = service.check(
                "bash", ToolCategory.BASH, Map.of("command", "for f in *.jar; do rm -rf $f 2>/dev/null; done"));
        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("denied by configuration"));
        assertTrue(decision.reason().contains("rm -rf $f"));
        // the harmless loop body stays allowed
        final var ok = service(PermissionMode.ASK, List.of(), List.of("rm *"));
        assertTrue(ok.check("bash", ToolCategory.BASH, Map.of("command", "for f in *.jar; do ls -la $f; done"))
                .allowed());
    }

    @Test
    void planModeBashAllowsReadOnlySedButNotInPlace() {
        final var service =
                service(PermissionMode.ASK, List.of(), List.of(), ruleWithExcludes(PermissionMode.YOLO, "sed", "-i"));
        service.setMode(AgentMode.PLAN);
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "sed -n 1,30p pom.xml"))
                .allowed());
        // -i rewrites files even behind a `sed -n` prefix
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "sed -n -i 's/a/b/p' pom.xml"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "sed -i 's/a/b/' pom.xml"))
                .allowed());
    }

    @Test
    void planModeAllowsReadAndAgent() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setMode(AgentMode.PLAN);
        assertTrue(service.check("grep", ToolCategory.READ, Map.of()).allowed());
        assertTrue(service.check("write_todo", ToolCategory.AGENT, Map.of()).allowed());
    }

    @Test
    void planModeAllowsReadOnlyMcpTool() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setMode(AgentMode.PLAN);
        assertTrue(service.check("ctx_query", ToolCategory.MCP, Map.of(), true).allowed());
        assertFalse(
                service.check("ctx_write", ToolCategory.MCP, Map.of(), false).allowed());
    }

    @Test
    void buildModeUsesPermissionModeForMcp() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setGate(request -> ApprovalGate.Result.DENY);
        assertFalse(
                service.check("ctx_write", ToolCategory.MCP, Map.of(), false).allowed());
    }

    @Test
    void sessionGrantAppliesToCommandFamilyInProject() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "mvn install"))
                .allowed());
        service.grantSession("bash", Map.of("command", "mvn install"));
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "mvn install"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "mvn test"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "gradle build"))
                .allowed());
    }

    @Test
    void sessionGrantDoesNotCrossProjectDir() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.grantSession("bash", Map.of("command", "mvn install"));
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "rm -rf /"))
                .allowed());
        assertTrue(service.sessionGrants().stream().anyMatch(g -> g.contains("|bash:mvn")));
    }

    @Test
    void sessionGrantViaGate() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setGate(request -> ApprovalGate.Result.ALLOW_SESSION);
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "mvn install"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "mvn test"))
                .allowed());
    }

    // ------------------------------------------------------------------
    // bash rules (yca.permissions.bash.rules)
    // ------------------------------------------------------------------

    @Test
    void buildModeAutoAllowsReadOnlyBashByDefaultRules() {
        // no gate: every assertion below would fail if the command was routed to the approval flow
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "git status --short --branch"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "ls -la"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "cat pom.xml"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "grep -n foo pom.xml"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "sed -n 1,30p pom.xml"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "git log 2>&1 | head -5"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "rm -rf /"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "git push"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "mvn install"))
                .allowed());
    }

    @Test
    void readOnlyCompoundCommandsAreSilentlyAllowed() {
        // the exact validation example from the design: 7 segments, all read-only
        final var command = "test -f ~/.m2/settings.xml && echo \"settings.xml exists\" || echo \"no settings.xml\""
                + "; ls ~/.m2 2>/dev/null | head -20; java -version 2>&1 | head -2";
        final var build = service(PermissionMode.ASK, List.of(), List.of());
        assertTrue(build.check("bash", ToolCategory.BASH, Map.of("command", command))
                .allowed());
        final var plan = service(PermissionMode.ASK, List.of(), List.of());
        plan.setMode(AgentMode.PLAN);
        assertTrue(plan.check("bash", ToolCategory.BASH, Map.of("command", command))
                .allowed());
    }

    @Test
    void bareJavaIsNotAutoAllowed() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "java -version"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "java -help"))
                .allowed());
        // bare java can execute arbitrary code (-jar, -cp, MainClass): it must ask
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "java -jar app.jar"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "java MainClass"))
                .allowed());
    }

    @Test
    void ruleDefaultOverridesPlan() {
        // docker run: plan asks (no gate -> denied), build mode default=all allows silently
        final var configuredRules = List.of(new CommandPermissionConfiguration(
                new CommandStartMatcherConfiguration(new CommandOptionsConfiguration(List.of("docker run")), List.of()),
                PermissionMode.ASK,
                PermissionMode.YOLO));
        final var service = service(PermissionMode.ASK, List.of(), List.of(), configuredRules);
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "docker run nginx"))
                .allowed());
        service.setMode(AgentMode.PLAN);
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "docker run nginx"))
                .allowed());
    }

    @Test
    void ruleDefaultInheritsPlan() {
        // no `default` key: build mode inherits the plan value (all)
        final var service = service(PermissionMode.ASK, List.of(), List.of(), rules(PermissionMode.YOLO, "git status"));
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "git status --short"))
                .allowed());
    }

    @Test
    void rulePlanFallsBackToDefaultWhenPlanAbsent() {
        // no `plan` key: plan mode resolves through `default`
        final var configuredRules = List.of(new CommandPermissionConfiguration(
                new CommandStartMatcherConfiguration(new CommandOptionsConfiguration(List.of("docker run")), List.of()),
                null,
                PermissionMode.YOLO));
        final var service = service(PermissionMode.ASK, List.of(), List.of(), configuredRules);
        service.setMode(AgentMode.PLAN);
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "docker run nginx"))
                .allowed());
    }

    @Test
    void ruleAskConsultsGateInBothModes() {
        final var configuredRules = List.of(new CommandPermissionConfiguration(
                new CommandStartMatcherConfiguration(new CommandOptionsConfiguration(List.of("docker run")), List.of()),
                PermissionMode.ASK,
                null));
        for (final var agentMode : List.of(AgentMode.BUILD, AgentMode.PLAN)) {
            final var service = service(PermissionMode.ASK, List.of(), List.of(), configuredRules);
            service.setMode(agentMode);
            final AtomicReference<String> asked = new AtomicReference<>();
            service.setGate(request -> {
                final var title = request.title();
                asked.set(title);
                return ApprovalGate.Result.ALLOW_ONCE;
            });
            assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "docker run nginx"))
                    .allowed());
            assertTrue(
                    asked.get() != null && asked.get().contains("docker run"),
                    "an ask rule must route " + agentMode + " mode to the gate");
        }
    }

    @Test
    void denyListBeatsBashRules() {
        // rm is denied by configuration even inside an otherwise allowed compound command
        final var service = service(PermissionMode.ASK, List.of(), List.of("rm *"));
        final var decision = service.check("bash", ToolCategory.BASH, Map.of("command", "git status && rm -rf /"));
        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("denied by configuration"));
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "rm -rf /"))
                .allowed());
        // the rules still allow the harmless part
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "git status"))
                .allowed());
    }

    @Test
    void denyListSupportsBashDecoratedPatterns() {
        final var service = service(PermissionMode.ASK, List.of(), List.of("bash:ls *"));
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "ls -la"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "git status"))
                .allowed());
    }

    @Test
    void allowListCommandPatternsApplyPerSegment() {
        final var service = service(PermissionMode.ASK, List.of("mvn *"), List.of());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "mvn clean install"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "mvn clean && mvn install"))
                .allowed());
        // a segment not matching the allow patterns makes the whole command ask again
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "mvn clean && rm -rf /"))
                .allowed());
    }

    @Test
    void sessionGrantUsesMatchedRuleCommandStart() {
        final var configuredRules = List.of(new CommandPermissionConfiguration(
                new CommandStartMatcherConfiguration(new CommandOptionsConfiguration(List.of("docker run")), List.of()),
                PermissionMode.ASK,
                null));
        final var service = service(PermissionMode.ASK, List.of(), List.of(), configuredRules);
        service.setGate(request -> ApprovalGate.Result.ALLOW_SESSION);
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "docker run nginx"))
                .allowed());
        assertTrue(
                service.sessionGrants().stream().anyMatch(g -> g.endsWith("|bash:docker run")),
                "the grant must be keyed by the rule commandStart, not the first word");

        // the fine grained grant unlocks docker run commands without consulting the gate...
        service.setGate(request -> ApprovalGate.Result.DENY);
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "docker run -p 80:80 nginx"))
                .allowed());
        // ...but not other docker commands nor compound commands
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "docker ps"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "docker run nginx && ls"))
                .allowed());
    }

    @Test
    void dynamicRulesExtendTheEffectiveRulesAndAreSessionScoped() {
        final var registry = new BashRulesRegistry(baseline);
        final var service = new PermissionService(baseline, null, registry, baseline.resolveWorkingDir());
        service.setMode(AgentMode.BUILD);
        // mvn is not part of the default read-only families: denied without an override
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "mvn install"))
                .allowed());
        service.addDynamicRule(new CommandPermissionConfiguration(
                new CommandStartMatcherConfiguration(new CommandOptionsConfiguration(List.of("mvn")), List.of()),
                PermissionMode.YOLO,
                null));
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "mvn install"))
                .allowed());
        // the dynamic rule participates in nested substitution verification too
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "echo \"a $(mvn install) b\""))
                .allowed());
        service.clearDynamicRules();
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "mvn install"))
                .allowed());
    }
}
