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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.SystemPromptBuilder;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.shell.ConversationTranscript;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellSession;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code /compact} option parsing and the busy deferred path: {@code force}, {@code urgent} and
 * {@code keep=<n>} must reach the queued directive exactly as typed (the destructive urgent flag is
 * opt-in and must never leak into the plain {@code /compact} form).
 */
@FusionSupport(modules = ConfigurationTestModule.class)
class MaintainCommandsTest {
    @Fusion
    private CodingAgentConfiguration configuration;

    @TempDir
    private static Path testWorkdir;

    @Test
    void mavenStatusAndRefreshFollowSessionRootsAndSettingsWhenSwitchingBack() {
        final var rootA = Path.of("project-a").toAbsolutePath();
        final var rootB = Path.of("project-b").toAbsolutePath();
        final var settingsA = new ChatSettings("provider-a", "model-a", null, null);
        final var settingsB = new ChatSettings("provider-b", "model-b", null, null);
        final var sessionA = mavenSession(rootA, settingsA);
        final var sessionB = mavenSession(rootB, settingsB);
        final var statusRoots = new ArrayList<Path>();
        final var refreshRoots = new ArrayList<Path>();
        final var refreshGavs = new ArrayList<String>();
        final var refreshSettings = new ArrayList<ChatSettings>();
        final var service = new MavenRepositoryKnowledgeService() {

            @Override
            public List<String> status(final Path root) {
                statusRoots.add(root);
                return List.of("status " + root);
            }

            @Override
            public List<String> refresh(
                    final Path root,
                    final String gav,
                    final Consumer<String> progress,
                    final Consumer<String> completed,
                    final ChatSettings settings) {
                refreshRoots.add(root);
                refreshGavs.add(gav);
                refreshSettings.add(settings);
                return List.of("refresh " + root);
            }
        };
        final var command = mavenCommand(service);
        for (final var session : List.of(sessionA, sessionB, sessionA)) {
            final var root = session == sessionA ? rootA : rootB;
            assertEquals(
                    new ShellOutcome.Transcript("> maven-repository-knowledge:\nstatus " + root),
                    runMaven(command, "status", session));
            assertEquals(
                    new ShellOutcome.Transcript("> maven-repository-knowledge:\nrefresh " + root),
                    runMaven(command, "refresh org.example:demo:1", session));
        }
        assertEquals(List.of(rootA, rootB, rootA), statusRoots);
        assertEquals(List.of(rootA, rootB, rootA), refreshRoots);
        assertEquals(List.of("org.example:demo:1", "org.example:demo:1", "org.example:demo:1"), refreshGavs);
        assertEquals(List.of(settingsA, settingsB, settingsA), refreshSettings);
    }

    @Test
    void mavenCommandsUseRuntimeRootWithoutPromptBuilder() {
        final var roots = new ArrayList<Path>();
        final var service = new MavenRepositoryKnowledgeService() {
            @Override
            public List<String> status(final Path root) {
                roots.add(root);
                return List.of("runtime status");
            }
        };
        final var runtime = new SessionRuntime(
                null,
                new PermissionService(configuration, testWorkdir),
                null,
                null,
                null,
                null,
                null,
                null,
                testWorkdir,
                new DisabledSandbox(testWorkdir));
        assertEquals(
                new ShellOutcome.Transcript("> maven-repository-knowledge:\nruntime status"),
                runMaven(mavenCommand(service), "status", new ShellSession().runtime(runtime)));
        assertEquals(List.of(testWorkdir), roots);
    }

    private static SlashCommand mavenCommand(final MavenRepositoryKnowledgeService service) {
        return new MaintainCommands(null, null, service)
                .commands().stream()
                        .filter(command -> command.spec().name().equals("/maven-repository-knowledge"))
                        .findFirst()
                        .orElseThrow();
    }

    private static ShellOutcome runMaven(final SlashCommand command, final String arg, final ShellSession session) {
        return command.action()
                .apply(arg, new ShellEnv(null, null, session, new ConversationTranscript()))
                .toCompletableFuture()
                .join();
    }

    private ShellSession mavenSession(final Path root, final ChatSettings settings) {
        final var loop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
            @Override
            public ChatSettings chatSettings() {
                return settings;
            }
        };
        final var builder = root == null
                ? null
                : new SystemPromptBuilder(testWorkdir) {
                    @Override
                    public Path effectiveWorkdir() {
                        return root;
                    }
                };
        return new ShellSession()
                .runtime(new SessionRuntime(
                        loop,
                        new PermissionService(configuration, root),
                        null,
                        null,
                        null,
                        null,
                        null,
                        builder,
                        root,
                        new DisabledSandbox(root)));
    }

    /** An agent loop that records the context-operation requests it receives. */
    private static final class RecordingLoop extends AgentLoop {
        private RecordingLoop() {
            super(testWorkdir, new DisabledSandbox(testWorkdir));
        }

        private final List<String> requests = new ArrayList<>();

        @Override
        public boolean requestUserContextOperation(
                final String action,
                final String focus,
                final int keepLast,
                final boolean force,
                final boolean urgent) {
            requests.add(action + "|" + focus + "|" + keepLast + "|" + force + "|" + urgent);
            return true;
        }
    }

    private static SlashCommand compactCommand() {
        return new MaintainCommands(null, null, null)
                .commands().stream()
                        .filter(c -> c.spec().name().equals("/compact"))
                        .findFirst()
                        .orElseThrow();
    }

    private static ShellOutcome runCompact(final String arg, final ShellSession session) {
        final var cmd = compactCommand();
        return cmd.action()
                .apply(arg, new ShellEnv(null, null, session, new ConversationTranscript()))
                .toCompletableFuture()
                .join();
    }

    private ShellSession busySession(final RecordingLoop loop) {
        return new ShellSession()
                .busy(true)
                .runtime(new SessionRuntime(
                        loop,
                        new PermissionService(configuration, testWorkdir),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        testWorkdir,
                        new DisabledSandbox(testWorkdir)));
    }

    @Test
    void busySessionDefersTheFullOptionSet() {
        final var loop = new RecordingLoop();
        final var outcome = runCompact("urgent force keep=10", busySession(loop));
        assertEquals(
                "compaction scheduled - applied at the next agent checkpoint",
                ((ShellOutcome.Status) outcome).message());
        assertEquals(
                List.of("compact|null|10|true|true"),
                loop.requests,
                "the queued directive must carry keep/force/urgent");
    }

    @Test
    void busySessionPlainCompactQueuesNoUrgentFlag() {
        final var loop = new RecordingLoop();
        final var outcome = runCompact("", busySession(loop));
        assertEquals(
                "compaction scheduled - applied at the next agent checkpoint",
                ((ShellOutcome.Status) outcome).message());
        assertEquals(List.of("compact|null|0|false|false"), loop.requests, "a plain /compact must not imply urgent");
    }

    @Test
    void unknownTokenPrintsTheUsageLine() {
        final var outcome = runCompact("bogus", busySession(new RecordingLoop()));
        assertEquals("usage: /compact [force] [urgent] [keep=<n>]", ((ShellOutcome.Status) outcome).message());
    }

    @Test
    void negativeKeepPrintsTheUsageLine() {
        final var outcome = runCompact("keep=-1", busySession(new RecordingLoop()));
        assertEquals("usage: /compact [force] [urgent] [keep=<n>]", ((ShellOutcome.Status) outcome).message());
    }

    @Test
    void completionOffersForceUrgentAndKeep() {
        final var completion = compactCommand().completion();
        assertNotSame(completion, ArgCompletion.NONE, "/compact has options so it must register a completion");
        final var items = completion.complete("", "", null).items();
        assertTrue(items.contains("force"), "completion must offer force: " + items);
        assertTrue(items.contains("urgent"), "completion must offer urgent: " + items);
        assertTrue(items.contains("keep="), "completion must offer keep=: " + items);
    }

    @Test
    void completionFiltersByTokenPrefix() {
        final var completion = compactCommand().completion();
        final var items = completion.complete("", "ur", null).items();
        assertEquals(List.of("urgent"), items, "prefix completion must narrow to the matching option");
    }
}
