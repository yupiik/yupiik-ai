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
package io.yupiik.ia.agent.acp;

import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.cli.CliCommands;
import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.shell.ConversationTranscript;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellFrontend;
import io.yupiik.ia.agent.core.shell.ShellIO;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellSession;
import io.yupiik.ia.agent.core.shell.ToolOutputVisibility;
import io.yupiik.ia.agent.core.shell.btw.BtwSlashCommands;
import io.yupiik.ia.agent.core.shell.slash.CodexLoginCommands;
import io.yupiik.ia.agent.core.shell.slash.ConfigCommands;
import io.yupiik.ia.agent.core.shell.slash.InspectCommands;
import io.yupiik.ia.agent.core.shell.slash.MaintainCommands;
import io.yupiik.ia.agent.core.shell.slash.PermissionCommands;
import io.yupiik.ia.agent.core.shell.slash.SessionCommands;
import io.yupiik.ia.agent.core.shell.slash.SlashAction;
import io.yupiik.ia.agent.core.shell.slash.SlashCommand;
import io.yupiik.ia.agent.core.shell.slash.SlashCommandSet;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import io.yupiik.ia.agent.core.shell.slash.UsageCommands;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class AcpSlashCommandsTest {
    @Test
    void catalogProjectsActualSpecsAndFiltersUnsafeOrIneffectiveRoutes() {
        final var registry = inventory();
        final var original = registry.specs();
        final var commands = new AcpSlashCommands(registry, noRoutes());
        final var catalog = commands.availableCommands();
        assertEquals(
                Set.of(
                        "mode",
                        "model",
                        "variant",
                        "agent",
                        "permissions",
                        "secrets",
                        "permissions-show",
                        "permissions-add",
                        "permission-remove",
                        "context",
                        "session-id",
                        "session-flush",
                        "memory",
                        "tools",
                        "executing-tools",
                        "todos",
                        "compact",
                        "snapshot",
                        "editplan",
                        "structural-code-graph",
                        "maven-repository-knowledge",
                        "mcp",
                        "session-housekeeping",
                        "subagents",
                        "usage",
                        "quit",
                        "exit",
                        "metrics",
                        "help"),
                names(commands));
        assertTrue(catalog.stream().noneMatch(row -> row.get("name").toString().startsWith("/")));
        assertTrue(catalog.stream()
                .allMatch(row -> !row.get("description").toString().isBlank()));
        assertEquals(
                Map.of("hint", registry.find("model").orElseThrow().spec().usage()),
                entry(commands, "model").get("input"));
        assertEquals(
                Map.of("hint", "status"),
                entry(commands, "maven-repository-knowledge").get("input"));
        assertFalse(entry(commands, "permissions-show").containsKey("input")); // real spec has empty usage
        assertFalse(entry(commands, "usage").containsKey("input")); // real spec has null usage
        assertFalse(entry(commands, "metrics").containsKey("input")); // report-only route
        assertEquals(
                Map.of("sessionUpdate", "available_commands_update", "availableCommands", catalog), commands.update());
        assertEquals(original, registry.specs());
        assertTrue(registry.calls.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> catalog.add(Map.of()));
    }

    @Test
    void effectiveRoutesAreSessionLocalAndKeepRegistrationOrderAndRealHints() {
        final var registry = inventory();
        final SlashAction owned = SlashAction.sync((arg, env) -> ShellOutcome.none());
        final var enabled = new AcpSlashCommands(
                registry,
                new AcpSlashCommands.Routes(true, true, true, true, owned, owned, owned, owned, null, null, null));
        final var disabled = new AcpSlashCommands(registry, noRoutes());
        assertTrue(names(enabled).containsAll(List.of("clear", "thinking", "tool-output", "btw", "provider", "codex")));
        assertFalse(names(disabled).contains("provider"));
        assertFalse(names(disabled).contains("clear"));
        assertFalse(names(enabled).contains("new"));
        assertFalse(names(enabled).contains("resume"));
        assertEquals(
                registry.specs().stream()
                        .map(SlashCommandSpec::command)
                        .filter(names(enabled)::contains)
                        .toList(),
                enabled.availableCommands().stream().map(row -> row.get("name")).toList());
        assertEquals(
                Map.of(
                        "hint",
                        "login [provider] [--device] [--enterprise] [--workspace <org-id>] | enroll [provider] [--device] [--workspace <org-id>] | status [provider]"),
                entry(enabled, "codex").get("input"));
        assertFalse(entry(enabled, "codex").get("input").toString().contains("logout"));
        assertEquals(
                Map.of("hint", registry.find("metrics").orElseThrow().spec().usage()),
                entry(enabled, "metrics").get("input"));
        assertEquals(
                Map.of(
                        "hint",
                        registry.find("maven-repository-knowledge")
                                .orElseThrow()
                                .spec()
                                .usage()),
                entry(enabled, "maven-repository-knowledge").get("input"));
        assertTrue(registry.calls.isEmpty());
    }

    @Test
    void onlyOrdinaryTextMayFallThroughToTheModel() {
        final var registry = inventory();
        final var commands = new AcpSlashCommands(registry, noRoutes());
        final var env = env();
        assertTrue(commands.dispatch(null, env).isEmpty());
        for (final var text : List.of("", " \t\n", "normal text", "please run /help", "`/help`", "text\n/help")) {
            assertTrue(commands.dispatch(text, env).isEmpty(), text);
        }
        for (final var text : List.of(
                "/unknown instruction", "/ help", "//", "/theme dark", "/tabs 2", "/shortcuts", "/not-reviewed")) {
            assertInstanceOf(ShellOutcome.Error.class, result(commands, text, env), text);
        }
        assertTrue(registry.calls.isEmpty());
        assertEquals(0, ((FakeIO) env.io()).inputs);
        assertTrue(((FakeFrontend) env.frontend()).rendered.isEmpty());
    }

    @Test
    void bareSlashAndHelpDescribeOnlyEffectiveCommandsAndNativeLifecycleEquivalents() {
        final var registry = inventory();
        final var commands = new AcpSlashCommands(registry, noRoutes());
        final var env = env();
        final var expected = assertInstanceOf(ShellOutcome.Transcript.class, result(commands, "/help", env));
        assertEquals(expected, result(commands, " \n/ \t", env));
        assertEquals(expected, result(commands, "/HELP", env));
        for (final var name : names(commands)) {
            assertTrue(expected.markdown().contains("**/" + name), name);
        }
        for (final var name : List.of(
                "new",
                "resume",
                "tabs",
                "theme",
                "shortcuts",
                "btw",
                "codex",
                "provider",
                "clear",
                "thinking",
                "tool-output")) {
            assertFalse(expected.markdown().contains("**/" + name), name);
        }
        assertTrue(expected.markdown().contains("`session/new`"));
        assertTrue(expected.markdown().contains("`session/load`"));
        assertTrue(expected.markdown().contains("`session/resume` when advertised"));
        assertFalse(expected.markdown().contains("Ctrl-"));
        assertTrue(registry.calls.isEmpty()); // never registry.helpMarkdown(), with its unfiltered TUI entries
    }

    @Test
    void commandCaseAndWhitespaceAreNormalizedWithoutChangingArgumentsOrTheEnvironment() {
        final var source = new CompletableFuture<ShellOutcome>();
        final var env = env();
        final var seen = new ArrayList<String>();
        final var registry = registry("model", "[name]", (arg, supplied) -> {
            assertSame(env, supplied);
            seen.add(arg);
            return source;
        });
        final var commands = new AcpSlashCommands(registry, noRoutes());
        assertSame(
                source,
                commands.dispatch(" \t/MoDeL\u2003Model-X  Variant-Y\ttemp=0.5 \n", env)
                        .orElseThrow());
        assertEquals(List.of("Model-X  Variant-Y\ttemp=0.5"), seen);
        assertEquals(List.of("/model Model-X  Variant-Y\ttemp=0.5"), registry.calls);
        source.complete(ShellOutcome.status("selected"));
    }

    @Test
    void newAndResumeNeverDispatchOrMutateAnExistingAcpIdentity() {
        final var registry = inventory(); // real SessionCommands would change sessionId on /resume
        final var commands = new AcpSlashCommands(registry, noRoutes());
        final var env = env();
        env.transcript().appendTranscript("kept visible history");
        for (final var text : List.of("/new", " /NEW ignored ", "/resume another-id", "/resume")) {
            final var outcome = assertInstanceOf(ShellOutcome.Status.class, result(commands, text, env));
            assertTrue(outcome.message()
                    .contains(
                            text.strip().toLowerCase(Locale.ROOT).startsWith("/new") ? "session/new" : "session/load"));
            assertEquals("acp-id", env.session().sessionId());
            assertEquals("selected-agent", env.session().activeAgent());
            assertTrue(env.transcript().markdown("", "").contains("kept visible history"));
        }
        assertTrue(registry.calls.isEmpty());
        assertEquals(0, ((FakeIO) env.io()).inputs);
    }

    @Test
    void quitAndExitAreSessionCloseOutcomesNotRegistryOrProcessExitActions() {
        final var registry = inventory();
        final var commands = new AcpSlashCommands(registry, noRoutes());
        final var env = env();
        for (final var name : List.of("quit", "exit")) {
            assertInstanceOf(ShellOutcome.Quit.class, result(commands, "/" + name, env));
            assertTrue(entry(commands, name).get("description").toString().contains("not the agent process"));
        }
        assertEquals("acp-id", env.session().sessionId());
        assertTrue(registry.calls.isEmpty());
        assertTrue(((FakeFrontend) env.frontend()).rendered.isEmpty());
    }

    @Test
    void shellOutcomesAndStagesAreReturnedWithoutRenderingOrWrapping() {
        final var env = env();
        for (final var outcome : List.of(
                ShellOutcome.none(),
                ShellOutcome.status("status"),
                ShellOutcome.transcript("markdown"),
                ShellOutcome.error("error"),
                ShellOutcome.quit())) {
            final var source = CompletableFuture.completedFuture(outcome);
            final var commands =
                    new AcpSlashCommands(registry("tools", "[filter]", (arg, supplied) -> source), noRoutes());
            final var returned = commands.dispatch("/tools", env).orElseThrow();
            assertSame(source, returned);
            assertSame(outcome, returned.toCompletableFuture().join());
        }
        assertTrue(((FakeFrontend) env.frontend()).rendered.isEmpty());
    }

    @Test
    void cancellationReachesTheActualRegistryFuture() {
        final var source = new CompletableFuture<ShellOutcome>();
        final var commands = new AcpSlashCommands(registry("tools", null, (arg, env) -> source), noRoutes());
        final var returned = commands.dispatch("/tools", env()).orElseThrow();
        assertSame(source, returned);
        assertTrue(returned.toCompletableFuture().cancel(true));
        assertTrue(source.isCancelled());
    }

    @Test
    void asynchronousFailuresAreNotConvertedToFalseSuccess() {
        final var failure = new IllegalStateException("failed asynchronously");
        final var source = new CompletableFuture<ShellOutcome>();
        final var commands = new AcpSlashCommands(registry("tools", null, (arg, env) -> source), noRoutes());
        final var returned = commands.dispatch("/tools", env()).orElseThrow();
        source.completeExceptionally(failure);
        assertSame(
                failure,
                assertThrows(
                                CompletionException.class,
                                () -> returned.toCompletableFuture().join())
                        .getCause());
    }

    @Test
    void absentOwnedRoutesExplainTheRealLifecycleGapWithoutStartingDetachedWorkOrForms() {
        final var registry = inventory();
        final var commands = new AcpSlashCommands(registry, noRoutes());
        final var env = env();
        assertTrue(assertInstanceOf(ShellOutcome.Error.class, result(commands, "/btw why?", env))
                .message()
                .contains("side-answer scope"));
        assertTrue(assertInstanceOf(ShellOutcome.Error.class, result(commands, "/provider codex", env))
                .message()
                .contains("authentication"));
        assertTrue(assertInstanceOf(ShellOutcome.Error.class, result(commands, "/codex login p --device", env))
                .message()
                .contains("uncancellable"));
        assertTrue(assertInstanceOf(
                        ShellOutcome.Error.class, result(commands, "/maven-repository-knowledge refresh g:a:1", env))
                .message()
                .contains("completion/error/cancellation"));
        assertTrue(registry.calls.isEmpty());
        assertEquals(0, ((FakeIO) env.io()).inputs);
        assertTrue(((FakeFrontend) env.frontend()).rendered.isEmpty());
    }

    @Test
    void ownedCallbacksReceiveArgumentsAndExactEnvironmentAndRetainTheirCancellableFuture() {
        for (final var text : List.of(
                "/btw Why  now?",
                "/provider Codex-P",
                "/provider",
                "/codex login P --device --enterprise --workspace O",
                "/maven-repository-knowledge refresh   g:a:1")) {
            final var env = env();
            final var seen = new ArrayList<String>();
            final var source = new CompletableFuture<ShellOutcome>();
            final SlashAction owned = (arg, supplied) -> {
                assertSame(env, supplied);
                seen.add(arg);
                return source;
            };
            final var registry = inventory();
            final var commands = new AcpSlashCommands(
                    registry,
                    new AcpSlashCommands.Routes(
                            false, false, false, false, owned, owned, owned, owned, null, null, null));
            final var returned = commands.dispatch(text, env).orElseThrow();
            assertSame(source, returned, text);
            assertFalse(returned.toCompletableFuture().isDone());
            assertEquals(List.of(text.contains(" ") ? text.substring(text.indexOf(' ') + 1) : ""), seen);
            assertTrue(registry.calls.isEmpty());
            assertEquals(0, ((FakeIO) env.io()).inputs); // no authentication/credential form fallback
            assertTrue(returned.toCompletableFuture().cancel(true));
            assertTrue(source.isCancelled());
        }
    }

    @Test
    void ownedAnswerIsAwaitableAndNotLostBehindAnImmediateAcknowledgement() {
        final var source = new CompletableFuture<ShellOutcome>();
        final var registry = inventory();
        final var commands = new AcpSlashCommands(
                registry,
                new AcpSlashCommands.Routes(
                        false, false, false, false, (arg, env) -> source, null, null, null, null, null, null));
        final var env = env();
        final var returned = commands.dispatch("/btw question", env).orElseThrow();
        assertFalse(returned.toCompletableFuture().isDone());
        final var answer = ShellOutcome.transcript("side answer");
        source.complete(answer);
        assertSame(answer, returned.toCompletableFuture().join());
        assertTrue(((FakeFrontend) env.frontend()).rendered.isEmpty()); // server renders before ending prompt
        assertTrue(registry.calls.isEmpty());
    }

    @Test
    void synchronousOwnedFailuresAreOutcomesWhileAsynchronousFailuresKeepTheirStage() {
        final var failed = new CompletableFuture<ShellOutcome>();
        final var commands = new AcpSlashCommands(
                inventory(),
                new AcpSlashCommands.Routes(
                        false,
                        false,
                        false,
                        false,
                        (arg, env) -> {
                            throw new IllegalArgumentException("bad question");
                        },
                        (arg, env) -> failed,
                        (arg, env) -> null,
                        null,
                        null,
                        null,
                        null));
        final var safeFailure = assertInstanceOf(ShellOutcome.Error.class, result(commands, "/btw q", env()))
                .message();
        assertTrue(safeFailure.contains("failed"));
        assertFalse(
                safeFailure.contains("bad question"), "raw callback/provider exceptions must not enter user output");
        assertInstanceOf(ShellOutcome.Error.class, result(commands, "/codex login p", env()));
        final var returned = commands.dispatch("/provider p", env()).orElseThrow();
        assertSame(failed, returned);
        failed.completeExceptionally(new IllegalStateException("login failed"));
        assertThrows(
                CompletionException.class, () -> returned.toCompletableFuture().join());
    }

    @Test
    void mavenStatusStillUsesCoreButRefreshCannotEscapeThroughCoreDispatch() {
        final var registry = registry(
                "maven-repository-knowledge",
                "<refresh [gav]|status>",
                SlashAction.sync((arg, env) -> ShellOutcome.transcript(arg)));
        final var commands = new AcpSlashCommands(registry, noRoutes());
        final var env = env();
        assertEquals(ShellOutcome.transcript("STATUS"), result(commands, "/maven-repository-knowledge STATUS", env));
        assertInstanceOf(ShellOutcome.Error.class, result(commands, "/maven-repository-knowledge ReFrEsH", env));
        assertInstanceOf(ShellOutcome.Status.class, result(commands, "/maven-repository-knowledge", env));
        assertInstanceOf(ShellOutcome.Status.class, result(commands, "/maven-repository-knowledge other", env));
        assertEquals(List.of("/maven-repository-knowledge STATUS"), registry.calls);
    }

    @Test
    void actualNoninteractiveCommandFamiliesReachTheirRegisteredActionsUnchanged() {
        final var env = env();
        final var source = inventory();
        final var registered = source.specs().stream()
                .map(spec -> SlashCommand.of(spec, (arg, supplied) -> {
                    assertSame(env, supplied);
                    return CompletableFuture.completedFuture(ShellOutcome.status(spec.command() + ":" + arg));
                }))
                .toList();
        final var registry = new RecordingRegistry(List.of(() -> registered));
        final var commands = new AcpSlashCommands(registry, noRoutes());
        for (final var name : List.of(
                "mode",
                "model",
                "variant",
                "agent",
                "permissions",
                "secrets",
                "permissions-show",
                "permissions-add",
                "permission-remove",
                "context",
                "session-id",
                "session-flush",
                "memory",
                "tools",
                "executing-tools",
                "todos",
                "compact",
                "snapshot",
                "editplan",
                "structural-code-graph",
                "mcp",
                "session-housekeeping",
                "subagents",
                "usage")) {
            assertEquals(
                    ShellOutcome.status(name + ":Case  Sensitive"),
                    result(commands, "/" + name + " Case  Sensitive", env));
        }
        assertEquals(0, ((FakeIO) env.io()).inputs);
    }

    @Test
    void realDisplayCommandsMutateTheSuppliedSessionAndFrontendOnlyWhenEffective() {
        final var registry = inventory();
        final var commands = new AcpSlashCommands(
                registry,
                new AcpSlashCommands.Routes(false, true, true, true, null, null, null, null, null, null, null));
        final var env = env();
        final var frontend = (FakeFrontend) env.frontend();
        assertEquals(ShellOutcome.status("thinking hidden"), result(commands, "/thinking hide", env));
        assertFalse(env.session().showThinking());
        assertFalse(frontend.thinking);
        assertEquals(ShellOutcome.status("tool output shown"), result(commands, "/tool-output show", env));
        assertEquals(ToolOutputVisibility.SHOWN, env.session().showToolOutput());
        assertEquals(ToolOutputVisibility.SHOWN, frontend.toolOutput);
        assertEquals(ShellOutcome.status("metrics updated"), result(commands, "/metrics hide", env));
        assertFalse(env.session().metrics().showPerTurn());
        assertFalse(env.session().metrics().showSessionTotals());
        assertTrue(frontend.rendered.isEmpty());
        assertEquals(0, ((FakeIO) env.io()).inputs);
    }

    @Test
    void ineffectiveDisplayControlsAreNotDispatchedButMetricsReportRemainsUseful() {
        final var registry = inventory();
        final var commands = new AcpSlashCommands(registry, noRoutes());
        final var env = env();
        final var metrics = env.session().metrics();
        for (final var text : List.of("/thinking hide", "/tool-output hide", "/clear", "/metrics hide")) {
            assertInstanceOf(ShellOutcome.Error.class, result(commands, text, env));
        }
        assertSame(metrics, env.session().metrics());
        assertTrue(env.session().showThinking());
        assertTrue(registry.calls.isEmpty());
        assertInstanceOf(ShellOutcome.Status.class, result(commands, "/metrics", env));
        assertEquals(List.of("/metrics"), registry.calls);
    }

    @Test
    void realClearOnlyClearsVisibleTranscriptAndNeverClaimsToEraseModelContext() {
        final var registry = inventory();
        final var commands = new AcpSlashCommands(
                registry,
                new AcpSlashCommands.Routes(true, false, false, false, null, null, null, null, null, null, null));
        final var env = env();
        env.transcript().appendTranscript("visible history");
        final var metrics = env.session().metrics();
        assertInstanceOf(ShellOutcome.None.class, result(commands, "/clear", env));
        assertEquals("", env.transcript().markdown("", ""));
        assertEquals(1, ((FakeFrontend) env.frontend()).clears);
        assertEquals("acp-id", env.session().sessionId());
        assertEquals("selected-agent", env.session().activeAgent());
        assertSame(metrics, env.session().metrics());
        assertTrue(entry(commands, "clear").get("description").toString().contains("model context are unchanged"));
    }

    @Test
    void realPureValidationStillRunsInCore() {
        final var commands = new AcpSlashCommands(inventory(), noRoutes());
        assertEquals(
                ShellOutcome.status("usage: /compact [force] [urgent] [keep=<n>]"),
                result(commands, "/compact keep=-1", env()));
    }

    @Test
    void interactionsStayOnTheBorrowedAsyncShellIoAndNeverGainImplicitApproval() {
        final var env = env();
        final var io = (FakeIO) env.io();
        final var registry = registry("model", "[name]", (arg, supplied) -> {
            assertSame(env, supplied);
            assertSame(io.gate, supplied.io().approvalGate());
            assertEquals(
                    ApprovalGate.Result.DENY,
                    supplied.io()
                            .approvalGate()
                            .approve(new ApprovalGate.Request(
                                    ApprovalGate.Origin.PERMISSION, "t", "d", "tool", Map.of())));
            return supplied.io()
                    .pick("model", List.of("one", "two"))
                    .thenApply(selected -> ShellOutcome.status(selected.orElse("cancelled")));
        });
        final var commands = new AcpSlashCommands(registry, noRoutes());
        final var pending = commands.dispatch("/model", env).orElseThrow();
        assertFalse(pending.toCompletableFuture().isDone());
        assertEquals(1, io.inputs);
        io.reply.complete(Optional.of("two"));
        assertEquals(ShellOutcome.status("two"), pending.toCompletableFuture().join());
    }

    private static AcpSlashCommands.Routes noRoutes() {
        return new AcpSlashCommands.Routes(false, false, false, false, null, null, null, null, null, null, null);
    }

    private static ShellOutcome result(final AcpSlashCommands commands, final String text, final ShellEnv env) {
        return commands.dispatch(text, env).orElseThrow().toCompletableFuture().join();
    }

    private static Set<String> names(final AcpSlashCommands commands) {
        return commands.availableCommands().stream()
                .map(row -> (String) row.get("name"))
                .collect(toSet());
    }

    private static Map<String, Object> entry(final AcpSlashCommands commands, final String name) {
        return commands.availableCommands().stream()
                .filter(row -> name.equals(row.get("name")))
                .findFirst()
                .orElseThrow();
    }

    private static ShellEnv env() {
        return new ShellEnv(
                new FakeFrontend(),
                new FakeIO(),
                new ShellSession().sessionId("acp-id").activeAgent("selected-agent"),
                new ConversationTranscript());
    }

    private static RecordingRegistry inventory() {
        // Real command specs and pure actions. Null services make accidental auth/network dispatch fail.
        return new RecordingRegistry(List.of(
                new SessionCommands(null, null, null),
                new ConfigCommands(null, null, null, null, null, null, null),
                new InspectCommands(null, null),
                new MaintainCommands(null, null, null),
                new PermissionCommands(),
                new UsageCommands(null),
                new BtwSlashCommands(null),
                new CodexLoginCommands(null),
                new CliCommands(null, null),
                () -> List.of(
                        command("theme", null, SlashAction.sync((arg, env) -> ShellOutcome.none())),
                        command("tabs", "[index]", SlashAction.sync((arg, env) -> ShellOutcome.none())),
                        command("not-reviewed", null, SlashAction.sync((arg, env) -> ShellOutcome.none())))));
    }

    private static RecordingRegistry registry(final String name, final String usage, final SlashAction action) {
        return new RecordingRegistry(List.of(() -> List.of(command(name, usage, action))));
    }

    private static SlashCommand command(final String name, final String usage, final SlashAction action) {
        return SlashCommand.of(
                new SlashCommandSpec("/" + name, usage, "description of " + name, SlashGroup.OTHER), action);
    }

    private static final class RecordingRegistry extends SlashRegistry {
        private final List<String> calls = new ArrayList<>();

        private RecordingRegistry(final List<SlashCommandSet> sets) {
            super(sets);
        }

        @Override
        public CompletionStage<ShellOutcome> dispatch(final String text, final ShellEnv env) {
            calls.add(text);
            return super.dispatch(text, env);
        }
    }

    private static final class FakeIO implements ShellIO {
        private final CompletableFuture<Optional<String>> reply = new CompletableFuture<>();
        private final ApprovalGate gate = request -> ApprovalGate.Result.DENY;
        private int inputs;

        @Override
        public CompletionStage<Optional<String>> readLine(final String prompt) {
            inputs++;
            return reply;
        }

        @Override
        public CompletionStage<Boolean> confirm(final String question) {
            inputs++;
            return CompletableFuture.failedFuture(new UnsupportedOperationException("no automatic confirmation"));
        }

        @Override
        public CompletionStage<Optional<String>> pick(final String title, final List<String> options) {
            inputs++;
            return reply;
        }

        @Override
        public ApprovalGate approvalGate() {
            return gate;
        }

        @Override
        public FeedbackGate feedbackGate() {
            return questions -> null;
        }
    }

    private static final class FakeFrontend implements ShellFrontend {
        private final List<String> rendered = new ArrayList<>();
        private boolean thinking = true;
        private ToolOutputVisibility toolOutput = ToolOutputVisibility.COLLAPSED;
        private int clears;

        @Override
        public void status(final String message) {
            rendered.add(message);
        }

        @Override
        public void notice(final AgentEvent.Notice.Level level, final String message) {
            rendered.add(message);
        }

        @Override
        public void transcript(final String markdown) {
            rendered.add(markdown);
        }

        @Override
        public void error(final String message) {
            rendered.add(message);
        }

        @Override
        public void showThinking(final boolean value) {
            thinking = value;
        }

        @Override
        public void showToolOutput(final ToolOutputVisibility value) {
            toolOutput = value;
        }

        @Override
        public void clear() {
            clears++;
        }
    }
}
