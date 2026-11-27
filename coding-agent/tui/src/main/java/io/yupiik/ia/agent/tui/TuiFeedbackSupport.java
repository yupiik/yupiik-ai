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
package io.yupiik.ia.agent.tui;

/**
 * Pure keyboard-navigation logic of the ask_user/confirm feedback modal: tab cycling, question
 * scrolling, option moving/focus and checkbox activation. Operates only on the prompt/question
 * state objects - no rendering and no I/O.
 */
final class TuiFeedbackSupport {
    private TuiFeedbackSupport() {
        // utility
    }

    static void toggleTab(final TuiCommand.FeedbackPrompt prompt, final boolean forward) {
        final var tabCount = prompt.questions().size() + 1;
        final var next = forward ? prompt.tab() + 1 : prompt.tab() - 1;
        prompt.tab(Math.floorMod(next, tabCount));
    }

    /**
     * Scrolls the ask_user question (or confirm summary) by {@code delta} rows. A no-op unless the
     * render reported overflow ({@code questionMaxScroll() > 0}), which keeps PgUp/PgDn harmless on
     * the common case where the modal already fits its content.
     */
    static void scrollFeedbackQuestion(final TuiCommand.FeedbackPrompt prompt, final int delta) {
        if (prompt.questionMaxScroll() > 0) {
            prompt.questionScroll(Math.max(0, prompt.questionScroll() + delta));
        }
    }

    static void moveOption(final TuiCommand.FeedbackPrompt prompt, final int qIndex, final int delta) {
        final var fq = prompt.questions().get(qIndex);
        final var opts = optionCount(fq);
        final var total = opts + (opts == 0 ? 0 : 1);
        if (!fq.question.multiple() && opts > 0) {
            final var current = fq.selectedIndex();
            fq.selectedIndex(Math.floorMod((Math.max(current, 0)) + delta, total));
            setOptionFocus(prompt, fq, fq.selectedIndex());
        } else if (fq.question.multiple() && opts > 0) {
            final var current = prompt.optionsFocus();
            prompt.optionsFocus(Math.floorMod(Math.max(0, current) + delta, total));
        }
    }

    static int optionCount(final TuiCommand.FeedbackQuestion fq) {
        return fq.question.options() == null ? 0 : fq.question.options().size();
    }

    static boolean ownEntrySelected(final TuiCommand.FeedbackQuestion fq) {
        final var opts = optionCount(fq);
        return !fq.question.multiple() && opts > 0 && fq.selectedIndex() == opts;
    }

    static void setOptionFocus(
            final TuiCommand.FeedbackPrompt prompt, final TuiCommand.FeedbackQuestion fq, final int idx) {
        if (idx >= 0) {
            prompt.optionsFocus(idx);
        }
    }

    static void activateCheckbox(final TuiCommand.FeedbackQuestion fq, final int idx) {
        if (!fq.question.multiple()) {
            return;
        }
        final var opts = optionCount(fq);
        if (opts > 0 && idx == opts) {
            fq.ownBox().toggle();
            return;
        }
        if (idx >= 0 && idx < fq.boxes().length && idx < opts) {
            fq.boxes()[idx].toggle();
        }
    }
}
