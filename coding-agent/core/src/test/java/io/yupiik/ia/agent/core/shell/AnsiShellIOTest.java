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
package io.yupiik.ia.agent.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.cli.Symbols;
import io.yupiik.ia.agent.core.cli.prompt.Prompt;
import io.yupiik.ia.agent.core.cli.prompt.PromptInterruptException;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.question.Question;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_OUT)
class AnsiShellIOTest {
    @Test
    void confirmParsesYesNoAndInterrupt() {
        final var interrupts = new AtomicInteger();
        final var io = new AnsiShellIO(
                scripted("y", "no", new PromptInterruptException()), new Symbols(true), interrupts::incrementAndGet);

        assertTrue(io.confirm("go?").toCompletableFuture().join());
        assertFalse(io.confirm("go?").toCompletableFuture().join());
        assertFalse(io.confirm("go?").toCompletableFuture().join());
        assertEquals(1, interrupts.get());
    }

    @Test
    void interactionHooksFireAroundGates() {
        final var calls = new ArrayList<String>();
        final var io = new AnsiShellIO(
                scripted("y", "y", new PromptInterruptException()),
                new Symbols(true),
                () -> {},
                () -> calls.add("start"),
                () -> calls.add("end"));

        assertTrue(io.confirm("go?").toCompletableFuture().join());
        assertEquals(List.of("start", "end"), calls);

        assertEquals(
                ApprovalGate.Result.ALLOW_ONCE,
                io.approvalGate()
                        .approve(new ApprovalGate.Request(
                                ApprovalGate.Origin.PERMISSION, "title", "detail", "bash", Map.of())));
        assertEquals(List.of("start", "end", "start", "end"), calls);

        assertThrows(
                CompletionException.class,
                () -> io.readLine("").toCompletableFuture().join());
        assertEquals(List.of("start", "end", "start", "end"), calls);
    }

    @Test
    void pickReturnsSelectedOptionOrEmptyOnCancel() {
        final var io =
                new AnsiShellIO(scripted("2", "c", "9", new PromptInterruptException()), new Symbols(true), () -> {});

        assertEquals(
                Optional.of("second"),
                io.pick("choose", List.of("first", "second"))
                        .toCompletableFuture()
                        .join());
        assertEquals(
                Optional.empty(),
                io.pick("choose", List.of("first", "second"))
                        .toCompletableFuture()
                        .join());
        assertEquals(
                Optional.empty(),
                io.pick("choose", List.of("first", "second"))
                        .toCompletableFuture()
                        .join());
        assertEquals(
                Optional.empty(),
                io.pick("choose", List.of("first", "second"))
                        .toCompletableFuture()
                        .join());
        assertEquals(
                Optional.empty(),
                io.pick("choose", List.of()).toCompletableFuture().join());
    }

    @Test
    void approvalGateAcceptsOnceAlwaysAndDenies() {
        final var io =
                new AnsiShellIO(scripted("y", "a", "", new PromptInterruptException()), new Symbols(true), () -> {});
        final var gate = io.approvalGate();

        assertEquals(
                ApprovalGate.Result.ALLOW_ONCE,
                gate.approve(
                        new ApprovalGate.Request(ApprovalGate.Origin.PERMISSION, "title", "detail", "bash", Map.of())));
        assertEquals(
                ApprovalGate.Result.ALLOW_SESSION,
                gate.approve(
                        new ApprovalGate.Request(ApprovalGate.Origin.PERMISSION, "title", "detail", "bash", Map.of())));
        assertEquals(
                ApprovalGate.Result.DENY,
                gate.approve(
                        new ApprovalGate.Request(ApprovalGate.Origin.PERMISSION, "title", "detail", "bash", Map.of())));
        assertEquals(
                ApprovalGate.Result.DENY,
                gate.approve(
                        new ApprovalGate.Request(ApprovalGate.Origin.PERMISSION, "title", "detail", "bash", Map.of())));
    }

    @Test
    void feedbackGateCollectsAnswersAndSupportsRestart() {
        final var io = new AnsiShellIO(scripted("1", "", "2", "c"), new Symbols(true), () -> {});
        final var gate = io.feedbackGate();
        final var question = new Question("q1", "what?", List.of("alpha", "beta"), false);

        final Map<String, Object> answers = gate.ask(List.of(question));

        assertEquals(Map.of("q1", "alpha"), answers);
        assertNull(gate.ask(List.of(question)));
    }

    @Test
    void externalReadLineFailsWithInterruptWithoutCallingHook() {
        final var interrupts = new AtomicInteger();
        final var io = new AnsiShellIO(
                scripted(new PromptInterruptException()), new Symbols(true), interrupts::incrementAndGet);

        final var error = assertThrows(
                CompletionException.class,
                () -> io.readLine("prompt").toCompletableFuture().join());

        assertInstanceOf(PromptInterruptException.class, error.getCause());
        assertEquals(0, interrupts.get(), "the interrupt handling belongs to the caller for external reads");
    }

    private static Prompt scripted(final Object... script) {
        final Deque<Object> queue = new ArrayDeque<>(List.of(script));
        return promptText -> {
            final var next = queue.poll();
            if (next == null) {
                throw new AssertionError("no scripted input left");
            }
            if (next instanceof Throwable t) {
                throw (PromptInterruptException) t;
            }
            return Optional.of((String) next);
        };
    }
}
