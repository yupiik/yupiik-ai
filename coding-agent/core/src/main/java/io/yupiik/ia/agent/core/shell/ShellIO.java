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

import io.yupiik.ia.agent.core.cli.prompt.PromptInterruptException;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

/**
 * Interaction side of a shell: the primitives commands use to get user input.
 * Every input method is asynchronous so asynchronous frontends (TamboUI)
 * never block their event loop: they complete the returned stage when the
 * user answers a modal. Terminal frontends simply return already-completed
 * stages (their read is synchronous by nature). Callers chain on the stage
 * instead of joining it whenever possible.
 */
public interface ShellIO {
    /** Captures cancellation of the current command for a settings commit after blocking hooks. */
    default BooleanSupplier settingsUpdateCancellation() {
        return () -> false;
    }

    /**
     * Reads a line entered by the user. The stage completes with the entered
     * line, with an empty optional on end of input (Ctrl-D) or exceptionally
     * with {@link PromptInterruptException} when the user interrupts (Ctrl-C).
     */
    CompletionStage<Optional<String>> readLine(String prompt);

    /**
     * Asks a yes/no question. The stage is completed with the user answer,
     * {@code false} also standing for cancellation.
     */
    CompletionStage<Boolean> confirm(String question);

    /**
     * Asks the user to select one option among {@code options}.
     *
     * @return a stage completed with the picked option, an empty optional
     *         meaning cancelled.
     */
    CompletionStage<Optional<String>> pick(String title, List<String> options);

    /**
     * Selects one option among {@code options} with an explicit page size.
     * <p>
     * Implementations may expose {@code pageSize} as scrolling/paging unit (and, where the
     * frontend has an inline filter box, search across the whole {@code options} list - not just
     * the visible page). The default delegates to {@link #pick(String, List)} so existing callers
     * keep their exact behaviour; interactive/CLI frontends override it to page long lists.
     *
     * @param title picker title
     * @param options candidate labels, already in display (sorted) order
     * @param pageSize number of options shown per page
     * @return a stage completed with the picked option, an empty optional meaning cancelled
     */
    default CompletionStage<Optional<String>> pick(final String title, final List<String> options, final int pageSize) {
        return pick(title, options);
    }

    /** Tool execution approval gate of this shell (modal, inline box, auto, ...). */
    ApprovalGate approvalGate();

    /** User feedback/question gate of this shell. */
    FeedbackGate feedbackGate();
}
