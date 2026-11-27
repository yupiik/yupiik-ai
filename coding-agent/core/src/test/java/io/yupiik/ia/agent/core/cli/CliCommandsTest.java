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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import io.yupiik.ia.agent.core.agent.SubagentRegistry.SubagentResult;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellSession;
import io.yupiik.ia.agent.core.shell.slash.ArgCompletion;
import io.yupiik.ia.agent.core.shell.slash.SlashCommand;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@FusionSupport(modules = CliCommandsTest.SubagentsModule.class)
class CliCommandsTest {
    @TempDir
    static Path storageHome;

    @Fusion
    CodingAgentConfiguration configuration;

    public static class SubagentsModule extends ConfigurationTestModule {
        public SubagentsModule() {
            super(Map.of(
                    "yca.storageHome", storageHome.toString(),
                    "yca.subagents.enabled", "true",
                    "yca.llm.maxConcurrent", "-1"));
        }
    }

    @Test
    void listsNamesIdsAndStatusesOnlyForCurrentSession() {
        final var registry = registry();
        final var running = registry.start("parent", "task", "review code").orElseThrow();
        final var done = registry.start("parent", "task").orElseThrow();
        registry.complete(done.id(), SubagentResult.success("finished", "conversation", List.of()));
        final var other = registry.start("other", "task", "invisible").orElseThrow();

        final var output = transcript(registry, null);
        assertTrue(output.contains("review code"));
        assertTrue(output.contains(running.id()));
        assertTrue(output.contains("RUNNING"));
        assertTrue(output.contains(done.id()));
        assertTrue(output.contains("SUCCEEDED"));
        assertFalse(output.contains(other.id()));
        assertFalse(output.contains("invisible"));
    }

    @Test
    void emptyListIsDurableOutput() {
        assertTrue(transcript(registry(), " ").contains("No subagents"));
    }

    @Test
    void detailByNameShowsFinalResultAndPrefersFullConversationOverLiveCapture() {
        final var registry = registry();
        final var state = registry.start("parent", "task", "review code", "requested-model")
                .orElseThrow();
        registry.setTranscriptIdentity(state.id(), "provider", "effective-model");
        registry.transcriptOf(state.id()).appendTranscript("stale live capture");
        final var full = "User: inspect all files\n\nAssistant: complete transcript\n\nTool: detailed output";
        registry.complete(
                state.id(), SubagentResult.success("final summary", full, List.of("src/Fix.java", "report.txt")));

        final var output = transcript(registry, "review code");
        for (final var expected : List.of(
                state.id(),
                "review code",
                "SUCCEEDED",
                "effective-model",
                "final summary",
                "src/Fix.java",
                "report.txt",
                full)) {
            assertTrue(output.contains(expected), output);
        }
        assertFalse(output.contains("stale live capture"));
        assertEquals(output, transcript(registry, state.id()));
        assertEquals(1, registry.resolve("parent", state.id()).size(), "inspection must not collect the result");
    }

    @Test
    void runningAndEmptyFinalConversationFallBackToLiveCapture() {
        final var registry = registry();
        final var state =
                registry.start("parent", "task", "review", "requested-model").orElseThrow();
        registry.transcriptOf(state.id()).appendTranscript("live conversation content");
        final var running = transcript(registry, "review");
        assertTrue(running.contains("RUNNING"));
        assertTrue(running.contains("requested-model"));
        assertTrue(running.contains("live conversation content"));

        registry.complete(state.id(), SubagentResult.error("failure reason", ""));
        final var failed = transcript(registry, "review");
        assertTrue(failed.contains("FAILED"));
        assertTrue(failed.contains("failure reason"));
        assertTrue(failed.contains("live conversation content"));
    }

    @Test
    void unknownAndAmbiguousNamesReturnSharedDiagnosticsAndIdsRemainGlobal() {
        final var registry = registry();
        final var first = registry.start("parent", "task", "same").orElseThrow();
        final var second = registry.start("parent", "task", "same").orElseThrow();
        final var other = registry.start("other", "task", "elsewhere").orElseThrow();
        registry.start("parent", "task", other.id()).orElseThrow();

        final var unknown = assertInstanceOf(ShellOutcome.Error.class, invoke(registry, "elsewhere"));
        assertEquals(registry.selectionError("elsewhere", List.of()), unknown.message());
        final var ambiguous = assertInstanceOf(ShellOutcome.Error.class, invoke(registry, "same"));
        assertTrue(ambiguous.message().contains(first.id()));
        assertTrue(ambiguous.message().contains(second.id()));
        assertTrue(ambiguous.message().contains("Ambiguous"));
        assertTrue(transcript(registry, other.id()).contains("elsewhere"));
    }

    @Test
    void completionUsesWholeNameIncludingSpaces() {
        final var completion = command(registry()).completion();
        assertTrue(completion.wholeArgument());
        final var result = completion.complete("", "review co", new ArgCompletion.Env() {
            @Override
            public List<String> subagents() {
                return List.of("review code", "unrelated code", "id-123");
            }
        });
        assertEquals("subagent", result.label());
        assertEquals(List.of("review code"), result.items());
    }

    private SubagentRegistry registry() {
        return new SubagentRegistry(configuration, null);
    }

    private SlashCommand command(final SubagentRegistry registry) {
        return new CliCommands(null, registry)
                .commands().stream()
                        .filter(command -> command.spec().name().equals("/subagents"))
                        .findFirst()
                        .orElseThrow();
    }

    private ShellOutcome invoke(final SubagentRegistry registry, final String reference) {
        return command(registry)
                .action()
                .apply(reference, new ShellEnv(null, null, new ShellSession().sessionId("parent"), null))
                .toCompletableFuture()
                .join();
    }

    private String transcript(final SubagentRegistry registry, final String reference) {
        return assertInstanceOf(ShellOutcome.Transcript.class, invoke(registry, reference))
                .markdown();
    }
}
