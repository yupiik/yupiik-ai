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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.shell.ConversationTranscript;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellFrontend;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellSession;
import io.yupiik.ia.agent.core.shell.ToolOutputVisibility;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlashRegistryTest {
    private SlashRegistry registry() {
        final SlashCommandSet set = () -> List.of(
                SlashCommand.of(
                        new SlashCommandSpec("/alpha", "", "alpha cmd", SlashGroup.SESSION),
                        (arg, env) -> completedStage(ShellOutcome.status("alpha:" + arg))),
                SlashCommand.of(
                        new SlashCommandSpec("/beta", "", "beta cmd", SlashGroup.CONFIG),
                        ArgCompletion.options("m1", "m2"),
                        (arg, env) -> completedStage(ShellOutcome.quit())));
        return new SlashRegistry(List.of(set));
    }

    private ShellEnv env() {
        return new ShellEnv(
                new RecordingFrontend(new ArrayList<>()), null, new ShellSession(), new ConversationTranscript());
    }

    @Test
    void dispatchesToRegisteredCommand() {
        final var outcome =
                registry().dispatch("/alpha hello", env()).toCompletableFuture().join();
        assertEquals("alpha:hello", ((ShellOutcome.Status) outcome).message());
    }

    @Test
    void dispatchPreservesRemainderEmptyArgumentsAndAsciiWhitespaceSemantics() {
        final var registry = registry();
        for (final var input : List.of("/alpha", "/alpha ", "/alpha\t\r\n")) {
            final var outcome =
                    registry.dispatch(input, env()).toCompletableFuture().join();
            assertEquals("alpha:", ((ShellOutcome.Status) outcome).message());
        }
        final var outcome = registry.dispatch("/ALPHA\t one  two\nthree \t", env())
                .toCompletableFuture()
                .join();
        assertEquals("alpha:one  two\nthree", ((ShellOutcome.Status) outcome).message());
        final var unicode = registry.dispatch("/alpha\u2003one", env())
                .toCompletableFuture()
                .join();
        assertEquals("unknown command /alpha\u2003one - type /help", ((ShellOutcome.Status) unicode).message());
    }

    @Test
    void unknownCommandGivesStatus() {
        final var outcome =
                registry().dispatch("/nope", env()).toCompletableFuture().join();
        assertTrue(((ShellOutcome.Status) outcome).message().contains("unknown command /nope"));
    }

    @Test
    void helpIsBuiltInAndRendersGroups() {
        final var registry = registry();
        assertTrue(registry.names().contains("/help"));
        final var outcome =
                registry.dispatch("/help", env()).toCompletableFuture().join();
        final var md = ((ShellOutcome.Transcript) outcome).markdown();
        assertTrue(md.contains("### session") && md.contains("### config"));
        assertTrue(md.contains("- **/alpha** - alpha cmd"));
    }

    @Test
    void namesAndCompletionComeFromSpecs() {
        final var registry = registry();
        assertTrue(registry.names().containsAll(List.of("/alpha", "/beta", "/help")));
        assertEquals(
                List.of("m1", "m2"),
                registry.completionFor("beta")
                        .complete("", "", new ArgCompletion.Env() {})
                        .items());
        assertEquals(ArgCompletion.NONE, registry.completionFor("alpha"));
    }

    @Test
    void quitOutcomeIsPassedThrough() {
        final var outcome =
                registry().dispatch("/beta", env()).toCompletableFuture().join();
        assertEquals(ShellOutcome.quit(), outcome);
    }

    @Test
    void applyToRendersTheDispatchOutcome() {
        final var calls = new ArrayList<String>();
        final var frontend = new RecordingFrontend(calls);
        registry().dispatch("/alpha hi", env()).toCompletableFuture().join().applyTo(frontend);
        assertTrue(calls.contains("status:alpha:hi"), calls.toString());
    }

    private record RecordingFrontend(List<String> calls) implements ShellFrontend {

        @Override
        public void status(final String message) {
            calls.add("status:" + message);
        }

        @Override
        public void notice(final AgentEvent.Notice.Level level, final String message) {
            calls.add("notice:" + message);
        }

        @Override
        public void transcript(final String markdown) {
            calls.add("transcript:" + markdown);
        }

        @Override
        public void error(final String message) {
            calls.add("error:" + message);
        }

        @Override
        public void showThinking(final boolean value) {
            calls.add("showThinking:" + value);
        }

        @Override
        public void showToolOutput(final ToolOutputVisibility value) {
            calls.add("showToolOutput:" + value);
        }
    }
}
