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
package io.yupiik.ia.agent.core.shell.slash;

import static java.util.concurrent.CompletableFuture.completedStage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.Tool;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.ConversationTranscript;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellFrontend;
import io.yupiik.ia.agent.core.shell.ShellIO;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellSession;
import io.yupiik.ia.agent.core.shell.ToolOutputVisibility;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@FusionSupport(modules = ConfigurationTestModule.class)
class SessionCommandsTest {
    @Fusion
    private CodingAgentConfiguration configuration;

    @TempDir
    private static Path testWorkdir;

    private final List<String> picked = new ArrayList<>();

    private ShellEnv env() {
        return new ShellEnv(
                new ShellFrontend() {
                    @Override
                    public void status(final String message) {}

                    @Override
                    public void notice(final AgentEvent.Notice.Level level, final String message) {}

                    @Override
                    public void transcript(final String markdown) {}

                    @Override
                    public void error(final String message) {}

                    @Override
                    public void showThinking(final boolean value) {}

                    @Override
                    public void showToolOutput(final ToolOutputVisibility value) {}
                },
                new ShellIO() {
                    @Override
                    public CompletionStage<Optional<String>> readLine(final String prompt) {
                        return completedStage(Optional.empty());
                    }

                    @Override
                    public CompletionStage<Boolean> confirm(final String question) {
                        return completedStage(true);
                    }

                    @Override
                    public CompletionStage<Optional<String>> pick(final String title, final List<String> options) {
                        picked.addAll(options);
                        return completedStage(Optional.empty());
                    }

                    @Override
                    public ApprovalGate approvalGate() {
                        return null;
                    }

                    @Override
                    public FeedbackGate feedbackGate() {
                        return null;
                    }
                },
                new ShellSession(),
                new ConversationTranscript());
    }

    private ShellOutcome resume(final ShellEnv env) {
        final var store = new SessionStore() {
            @Override
            public List<SessionInfo> list() {
                // deliberately unsorted: the command must order most-recent first
                return List.of(
                        new SessionInfo("old", 1000, 10),
                        new SessionInfo("new", 3000, 20),
                        new SessionInfo("mid", 2000, 30));
            }
        };
        final var commands = new SessionCommands(store, null);
        final var cmd = commands.commands().stream()
                .filter(c -> c.spec().name().equals("/resume"))
                .findFirst()
                .orElseThrow();
        return cmd.action().apply("", env).toCompletableFuture().join();
    }

    @Test
    void resumePicksSessionsMostRecentFirst() {
        final var outcome = resume(env());
        // the io.pick returns empty: the wizard reports a cancellation, no resume happens
        assertEquals("resume cancelled", ((ShellOutcome.Status) outcome).message());
        assertEquals(3, picked.size());
        assertTrue(picked.get(0).startsWith("new"), picked.toString());
        assertTrue(picked.get(1).startsWith("mid"), picked.toString());
        assertTrue(picked.get(2).startsWith("old"), picked.toString());
    }

    @Test
    void resumeWithNoSessionsReportsStatus() {
        final var store = new SessionStore() {};
        final var commands = new SessionCommands(store, null);
        final var cmd = commands.commands().stream()
                .filter(c -> c.spec().name().equals("/resume"))
                .findFirst()
                .orElseThrow();
        final var outcome = cmd.action().apply("", env()).toCompletableFuture().join();
        assertEquals("no session to resume", ((ShellOutcome.Status) outcome).message());
        assertTrue(picked.isEmpty(), "the picker must not open when there is nothing to pick");
    }

    @Test
    void resumeOffersEverySessionSortedMostRecentFirst() {
        // well above the historical 25-entry cap: every session must reach the picker (which owns
        // live search + 50-per-page pagination), and the whole list must be most-recent first
        final var sessions = new ArrayList<SessionStore.SessionInfo>();
        for (int i = 0; i < 60; i++) {
            sessions.add(new SessionStore.SessionInfo("s" + (100 - i), 1000L * (i + 1), 10));
        }
        final var store = new SessionStore() {
            @Override
            public List<SessionInfo> list() {
                return sessions;
            }
        };
        final var env = env();
        final var outcome = new SessionCommands(store, null)
                .commands().stream()
                        .filter(c -> c.spec().name().equals("/resume"))
                        .findFirst()
                        .orElseThrow()
                        .action()
                        .apply("", env)
                        .toCompletableFuture()
                        .join();
        assertEquals("resume cancelled", ((ShellOutcome.Status) outcome).message());
        assertEquals(60, picked.size(), "the picker must see every session, not a capped recent window");
        for (int i = 0; i < picked.size(); i++) {
            assertTrue(picked.get(i).startsWith("s" + (41 + i)), picked.get(i));
        }
    }

    @Test
    void resumeLabelsIncludeTheFirstTitleLine() {
        picked.clear();
        final var store = new SessionStore() {
            @Override
            public List<SessionInfo> list() {
                return List.of(new SessionInfo("s9", 1000, 10));
            }

            @Override
            public String summary(final String id) {
                return "my very first prompt title\nmore lines";
            }
        };
        final var outcome = new SessionCommands(store, null)
                .commands().stream()
                        .filter(c -> c.spec().name().equals("/resume"))
                        .findFirst()
                        .orElseThrow()
                        .action()
                        .apply("", env())
                        .toCompletableFuture()
                        .join();
        assertEquals("resume cancelled", ((ShellOutcome.Status) outcome).message());
        assertEquals(1, picked.size());
        assertTrue(picked.get(0).startsWith("s9"), picked.get(0));
        assertTrue(picked.get(0).contains("my very first prompt title"), picked.get(0));
        assertFalse(picked.get(0).contains("\n"), "only the first summary line becomes the title");
    }

    @Test
    void contextArgSplitsModeFromPathAndKeepsTheLegacyForm() {
        assertEquals(new SessionCommands.ContextRequest("transcript", ""), SessionCommands.parseContext(null));
        assertEquals(new SessionCommands.ContextRequest("transcript", ""), SessionCommands.parseContext(""));
        assertEquals(new SessionCommands.ContextRequest("transcript", ""), SessionCommands.parseContext("   "));
        // a token is a mode only when it is one, otherwise the whole argument is the file
        assertEquals(
                new SessionCommands.ContextRequest("transcript", "out.txt"), SessionCommands.parseContext("out.txt"));
        assertEquals(
                new SessionCommands.ContextRequest("transcript", "my file.txt"),
                SessionCommands.parseContext(" my file.txt "));
        assertEquals(
                new SessionCommands.ContextRequest("transcript", "out.txt"),
                SessionCommands.parseContext(" transcript out.txt"));
        assertEquals(
                new SessionCommands.ContextRequest("llm-payload", "dump/payload.md"),
                SessionCommands.parseContext(" llm-payload dump/payload.md "));
        assertEquals(
                new SessionCommands.ContextRequest("llm-payload", ""), SessionCommands.parseContext("LLM-Payload"));
        assertEquals(
                new SessionCommands.ContextRequest("next-request", ""), SessionCommands.parseContext("next-request"));
        assertEquals(new SessionCommands.ContextRequest("compact", ""), SessionCommands.parseContext("compact"));
        assertEquals(new SessionCommands.ContextRequest("prune", ""), SessionCommands.parseContext("prune"));
    }

    @Test
    void contextCompletionOffersModesAndPaths() {
        final var completion = contextCompletion();
        assertEquals(
                List.of("compact", "llm-payload", "next-request", "prune", "transcript"),
                completion.complete("", "", new ArgCompletion.Env() {}).items());
        assertEquals(
                "mode", completion.complete("", "", new ArgCompletion.Env() {}).label());
        assertEquals(
                List.of("llm-payload"),
                completion.complete("", "llm", new ArgCompletion.Env() {}).items());

        // a path is proposed before any mode is typed, so /context out.txt still completes
        final var withPaths = completion.complete("", "ou", pathsEnv());
        assertEquals(List.of("out.txt"), withPaths.items());
        assertEquals("path", withPaths.label(), "no mode matches the token: the label must say path");

        // once a mode is there, the next token is a file
        assertEquals(
                List.of("out.txt"),
                completion.complete("llm-payload ", "ou", pathsEnv()).items());
    }

    @Test
    void llmPayloadWithoutAnyRequestExplainsHowToGetOne() {
        final var outcome = context("llm-payload", contextEnv(null));
        assertEquals(
                "no LLM request was sent yet in this session - run a turn, or use /context transcript",
                ((ShellOutcome.Status) outcome).message());
    }

    @Test
    void llmPayloadRendersTheWholeRequestNotTheStoredTranscript(@TempDir final Path dir) throws IOException {
        final var outcome = context("llm-payload", contextEnv(payload()));
        final var markdown = ((ShellOutcome.Transcript) outcome).markdown();
        assertTrue(markdown.contains("# LLM payload"), markdown);
        assertTrue(markdown.contains("**Model** test-model"), markdown);
        assertTrue(markdown.contains("**Messages** 5"), markdown);
        assertTrue(markdown.contains("**Tool choice** auto"), markdown);
        assertTrue(markdown.contains("### 1. system"), markdown);
        assertTrue(markdown.contains("system prompt"), "the system message is dumped in full: " + markdown);
        assertTrue(markdown.contains("### 2. user"), markdown);
        assertTrue(markdown.contains("do it"), markdown);
        // the tail the store strips is the whole point of this view
        assertTrue(markdown.contains("### 3. user"), markdown);
        assertTrue(markdown.contains("<session-state>"), markdown);
        assertTrue(markdown.contains("### 4. assistant"), markdown);
        // a tool result is labelled with the tool name recovered from the assistant call
        assertTrue(markdown.contains("### 5. tool (read) [h1] [error]"), markdown);
        assertTrue(markdown.contains("boom"), markdown);
        assertTrue(markdown.contains("**tool_call** read [h1]"), markdown);
        assertTrue(markdown.contains("## Tools (1)"), markdown);
        assertTrue(markdown.contains("~42 tokens estimated for all the tool schemas"), markdown);
        assertTrue(markdown.contains("### read (function)"), markdown);
        assertTrue(markdown.contains("read a file from disk"), markdown);
        // two-part header: the aggregate estimate is stated once, each per-tool header carries its own
        assertTrue(markdown.contains("**parameters** (~"), markdown);
        assertEquals(1, count(markdown, "estimated for all the tool schemas"), markdown);
        assertEquals(1, count(markdown, "tokens estimated)"), markdown);

        // the very same dump, written to a file
        final var target = dir.resolve("sub").resolve("payload.md");
        final var written = (ShellOutcome.Status) context("llm-payload " + target, contextEnv(payload()));
        assertEquals("context written to " + target.toAbsolutePath(), written.message());
        assertEquals(markdown, Files.readString(target), "the file must hold the very same dump");
    }

    @Test
    void aMessageQuotingBackticksCannotBreakOutOfItsFence() {
        final var payload = new AgentLoop.LlmPayload(
                "s1", "test-model", List.of(Message.user("before ``` after")), List.of(), "auto", 0);
        final var markdown = SessionCommands.renderLlmPayload(payload, "note", null, null);
        assertTrue(markdown.contains("````text\nbefore ``` after\n````"), markdown);
    }

    @Test
    void contextOperationsRejectAPath() {
        assertEquals(
                "/context transcript|llm-payload|next-request|compact|prune [path]",
                ((ShellOutcome.Status) context("compact out.txt", contextEnv(null))).message());
        assertEquals(
                "/context transcript|llm-payload|next-request|compact|prune [path]",
                ((ShellOutcome.Status) context("prune out.txt", contextEnv(null))).message());
    }

    /** The captured payload the {@code llm-payload} tests start from. */
    private AgentLoop.LlmPayload payload() {
        // the volatile tail is how the loop really sends its state: a trailing user message the
        // store never persists, so only this view shows it
        final var tail = new Message(
                "user",
                "<session-state>\ncontext 12%\n</session-state>",
                null,
                null,
                null,
                Map.of("volatileTail", true));
        return new AgentLoop.LlmPayload(
                "s1",
                "test-model",
                List.of(
                        Message.system("system prompt"),
                        Message.user("do it"),
                        tail,
                        Message.assistant(
                                null,
                                List.of(new ToolCall(
                                        "h1",
                                        "function",
                                        new ToolCall.FunctionCall("read", "{\"path\":\"a.java\"}"),
                                        null))),
                        Message.tool("h1", "boom", true)),
                List.of(Tool.function(
                        "read",
                        "read a file from disk",
                        Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string"))))),
                "auto",
                42);
    }

    private ShellOutcome context(final String arg, final ShellEnv env) {
        return new SessionCommands(new SessionStore() {}, null)
                .commands().stream()
                        .filter(c -> c.spec().name().equals("/context"))
                        .findFirst()
                        .orElseThrow()
                        .action()
                        .apply(arg, env)
                        .toCompletableFuture()
                        .join();
    }

    private ShellEnv contextEnv(final AgentLoop.LlmPayload payload) {
        final var env = env();
        env.session().sessionId("s1");
        env.session()
                .runtime(new SessionRuntime(
                        new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                            {
                                lastLlmPayload = payload;
                            }
                        },
                        new PermissionService(configuration, testWorkdir),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        testWorkdir,
                        new DisabledSandbox(testWorkdir)));
        return env;
    }

    private static ArgCompletion.Env pathsEnv() {
        return new ArgCompletion.Env() {
            @Override
            public List<String> paths(final String token) {
                return List.of("out.txt");
            }
        };
    }

    private ArgCompletion contextCompletion() {
        return new SessionCommands(new SessionStore() {}, null)
                .commands().stream()
                        .filter(c -> c.spec().name().equals("/context"))
                        .findFirst()
                        .orElseThrow()
                        .completion();
    }

    private static int count(final String text, final String needle) {
        int occurrences = 0;
        for (int from = 0; (from = text.indexOf(needle, from)) >= 0; from += needle.length()) {
            occurrences++;
        }
        return occurrences;
    }
}
