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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.shell.ShellIO;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class ShellInputRoutingTest {
    private final SlashRegistry registry = new SlashRegistry(List.of(() ->
            List.of(SlashCommand.of(new SlashCommandSpec("/model", "[id]", "model", SlashGroup.CONFIG), (arg, env) -> {
                throw new AssertionError("Classification must never execute a command");
            }))));

    @Test
    void onlyFirstTokenControlsPathDetection() {
        for (final var input : List.of(
                "hello /model",
                "/tmp/file explain this",
                " /model/file\tmore text",
                "//host",
                "/btw/files text",
                "/",
                "/file.txt explain",
                "/tmp/file\n/model")) {
            assertEquals(
                    ShellInputRouting.Decision.PROMPT,
                    ShellInputRouting.resolve(input, registry, null)
                            .toCompletableFuture()
                            .join(),
                    input);
        }
        for (final var input : List.of("/model", "/MODEL /some/path", "/model\t/foo/bar\nmore", "/help")) {
            assertEquals(
                    ShellInputRouting.Decision.COMMAND,
                    ShellInputRouting.resolve(input, registry, null)
                            .toCompletableFuture()
                            .join(),
                    input);
        }
    }

    @Test
    void asksWithoutDispatchingAndPreservesAnExplicitInputChoice() {
        final var io = new Picker();
        final var decision = ShellInputRouting.resolve("/modle keep  arguments\nunchanged", registry, io)
                .toCompletableFuture();
        assertFalse(decision.isDone());
        assertTrue(io.title.contains("/model"), io.title);
        assertEquals(List.of("Send as normal input", "Mistyped command - I'll fix it"), io.options);
        io.answer.complete(Optional.of(io.options.getFirst()));
        assertEquals(ShellInputRouting.Decision.PROMPT, decision.join());
    }

    @Test
    void correctionAndCancellationNeverBecomeCommands() {
        final var correction = new Picker();
        final var decision =
                ShellInputRouting.resolve("/modle", registry, correction).toCompletableFuture();
        correction.answer.complete(Optional.of(correction.options.get(1)));
        assertEquals(ShellInputRouting.Decision.CORRECT, decision.join());

        final var cancellation = new Picker();
        final var cancelled = ShellInputRouting.resolve("/tmp some text", registry, cancellation)
                .toCompletableFuture();
        cancellation.answer.complete(Optional.empty());
        assertEquals(ShellInputRouting.Decision.CANCEL, cancelled.join());
    }

    @Test
    void unknownNamesStillAskWithoutCloseSuggestionsAndBoundVeryLongSuggestions() {
        for (final var token : List.of("unrelated", "a".repeat(200))) {
            final var io = new Picker();
            final var decision =
                    ShellInputRouting.resolve("/" + token, registry, io).toCompletableFuture();
            assertFalse(io.title.contains("did you mean"));
            io.answer.complete(Optional.of(io.options.getFirst()));
            assertEquals(ShellInputRouting.Decision.PROMPT, decision.join());
        }
    }

    private static final class Picker implements ShellIO {
        private final CompletableFuture<Optional<String>> answer = new CompletableFuture<>();
        private String title;
        private List<String> options;

        @Override
        public CompletionStage<Optional<String>> pick(final String title, final List<String> options) {
            this.title = title;
            this.options = options;
            return answer;
        }

        @Override
        public CompletionStage<Optional<String>> readLine(final String prompt) {
            throw new AssertionError("Unexpected readLine");
        }

        @Override
        public CompletionStage<Boolean> confirm(final String question) {
            throw new AssertionError("Unexpected confirm");
        }

        @Override
        public ApprovalGate approvalGate() {
            throw new AssertionError("Unexpected approval");
        }

        @Override
        public FeedbackGate feedbackGate() {
            throw new AssertionError("Unexpected feedback");
        }
    }
}
