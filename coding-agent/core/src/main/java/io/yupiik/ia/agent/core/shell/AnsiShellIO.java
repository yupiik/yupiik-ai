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

import io.yupiik.ia.agent.core.cli.MarkdownColorizer;
import io.yupiik.ia.agent.core.cli.Symbols;
import io.yupiik.ia.agent.core.cli.prompt.LinedPrompt;
import io.yupiik.ia.agent.core.cli.prompt.Prompt;
import io.yupiik.ia.agent.core.cli.prompt.PromptInterruptException;
import io.yupiik.ia.agent.core.cli.prompt.PromptOutput;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.question.Question;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Terminal {@link ShellIO} backed by a blocking {@link Prompt}: returned
 * stages are already completed when handed back (the read is synchronous by
 * nature) so callers can chain them without ever blocking an event loop.
 * Interrupts (Ctrl-C) are forwarded to the {@code onInterrupt} hook and turn
 * the pending read into a cancellation (deny / {@code null} answer).
 */
public class AnsiShellIO implements ShellIO {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private final Prompt prompt;
    private final LinedPrompt lined;
    private final Symbols symbols;
    private final Runnable onInterrupt;
    private final Runnable onInteractionStart;
    private final Runnable onInteractionEnd;
    /**
     * Serializes this card/prompt rendering with the stderr spinner so the two
     * cursor-mutating writers never interleave on the shared terminal line.
     */
    private final Lock renderLock;

    /**
     * The single write path for the interaction cards (option lists, approval
     * box, question, answer summary). Routing them through the prompt sink -
     * rather than {@code System.out.println} - is what keeps them from landing
     * on the row a line editor has just painted: the sink decides, atomically
     * with the read lifecycle, between a raw write and a {@code printAbove} plus
     * prompt repaint.
     */
    private final PromptOutput output;

    public AnsiShellIO(final Prompt prompt, final Symbols symbols, final Runnable onInterrupt) {
        this(prompt, symbols, onInterrupt, () -> {}, () -> {}, new ReentrantLock());
    }

    public AnsiShellIO(
            final Prompt prompt, final Symbols symbols, final Runnable onInterrupt, final Runnable onInteractionStart) {
        this(prompt, symbols, onInterrupt, onInteractionStart, () -> {}, new ReentrantLock());
    }

    public AnsiShellIO(
            final Prompt prompt,
            final Symbols symbols,
            final Runnable onInterrupt,
            final Runnable onInteractionStart,
            final Runnable onInteractionEnd) {
        this(prompt, symbols, onInterrupt, onInteractionStart, onInteractionEnd, new ReentrantLock());
    }

    public AnsiShellIO(
            final Prompt prompt,
            final Symbols symbols,
            final Runnable onInterrupt,
            final Runnable onInteractionStart,
            final Runnable onInteractionEnd,
            final Lock renderLock) {
        this.prompt = prompt;
        this.lined = prompt instanceof LinedPrompt lp ? lp : null;
        this.symbols = symbols;
        this.onInterrupt = onInterrupt;
        this.onInteractionStart = onInteractionStart;
        this.onInteractionEnd = onInteractionEnd;
        this.renderLock = renderLock;
        final var sink = prompt.externalOutput();
        this.output = sink == null ? PromptOutput.raw() : sink;
    }

    /** Prints one line of interaction chrome on stdout through the prompt sink. */
    private void println(final String text) {
        output.print(text + "\n");
    }

    private <T> CompletionStage<T> interact(final Supplier<CompletionStage<T>> interaction) {
        onInteractionStart.run();
        renderLock.lock();
        try {
            return interaction.get();
        } finally {
            renderLock.unlock();
            onInteractionEnd.run();
        }
    }

    /**
     * Inline interaction reads go through the dedicated higher-priority lane when
     * the underlying prompt queue-reads while a turn runs, so an approval answer
     * is never handed to the REPL as a queued prompt.
     */
    private Optional<String> interactionLine(final String promptText) throws IOException {
        return lined == null ? prompt.readLine(promptText) : lined.readInteractionLine(promptText);
    }

    @Override
    public CompletionStage<Optional<String>> readLine(final String promptText) {
        try {
            return CompletableFuture.completedStage(prompt.readLine(promptText));
        } catch (final PromptInterruptException e) {
            return CompletableFuture.failedFuture(e);
        } catch (final IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletionStage<Boolean> confirm(final String question) {
        return interact(() -> {
            try {
                final var yes = interactionLine(MarkdownColorizer.status(question + " [y/N] "))
                        .map(line -> line.strip().toLowerCase(Locale.ROOT))
                        .map(a -> a.equals("y") || a.equals("yes"))
                        .orElse(false);
                return CompletableFuture.completedStage(yes);
            } catch (final PromptInterruptException e) {
                onInterrupt.run();
                return CompletableFuture.completedStage(false);
            } catch (final IOException e) {
                return CompletableFuture.completedStage(false);
            }
        });
    }

    @Override
    public CompletionStage<Optional<String>> pick(final String title, final List<String> options) {
        if (options == null || options.isEmpty()) {
            return CompletableFuture.completedStage(Optional.empty());
        }
        return interact(() -> {
            println(MarkdownColorizer.status(title + ":"));
            for (int i = 0; i < options.size(); i++) {
                println((i + 1) + ". " + options.get(i));
            }
            try {
                final var choice = interactionLine(
                                MarkdownColorizer.accent("select [1-" + options.size() + "] or Enter/c to cancel: "))
                        .map(line -> line.strip().toLowerCase(Locale.ROOT))
                        .orElse("");
                if (choice.isBlank() || choice.equals("c") || choice.equals("cancel")) {
                    return CompletableFuture.completedStage(Optional.empty());
                }
                final int index;
                try {
                    index = Integer.parseInt(choice) - 1;
                } catch (final NumberFormatException e) {
                    println(MarkdownColorizer.status("invalid selection"));
                    return CompletableFuture.completedStage(Optional.empty());
                }
                if (index < 0 || index >= options.size()) {
                    println(MarkdownColorizer.status("invalid selection"));
                    return CompletableFuture.completedStage(Optional.empty());
                }
                return CompletableFuture.completedStage(Optional.of(options.get(index)));
            } catch (final PromptInterruptException e) {
                onInterrupt.run();
                return CompletableFuture.completedStage(Optional.empty());
            } catch (final IOException e) {
                return CompletableFuture.completedStage(Optional.empty());
            }
        });
    }

    @Override
    public CompletionStage<Optional<String>> pick(final String title, final List<String> options, final int pageSize) {
        if (options == null || options.isEmpty()) {
            return CompletableFuture.completedStage(Optional.empty());
        }
        final var size = Math.max(1, pageSize);
        return interact(() -> {
            try {
                int page = 0;
                while (true) {
                    final var total = options.size();
                    final var pages = (total + size - 1) / size;
                    final var from = Math.min(page * size, total);
                    final var to = Math.min(total, from + size);
                    println(MarkdownColorizer.status(
                            title + (pages > 1 ? " (page " + (page + 1) + "/" + pages + ")" : "") + ":"));
                    for (int i = from; i < to; i++) {
                        println((i + 1) + ". " + options.get(i));
                    }
                    final var nav = new StringBuilder("select [");
                    if (from > 0) {
                        nav.append("p=prev ");
                    }
                    nav.append((from + 1)).append('-').append(to);
                    if (to < total) {
                        nav.append(" n=next");
                    }
                    nav.append("] or c/Enter to cancel: ");
                    final var choice = interactionLine(MarkdownColorizer.accent(nav.toString()))
                            .map(line -> line.strip().toLowerCase(Locale.ROOT))
                            .orElse("");
                    if (choice.isBlank() || choice.equals("c") || choice.equals("cancel")) {
                        return CompletableFuture.completedStage(Optional.empty());
                    }
                    if (choice.equals("n") && to < total) {
                        page++;
                        continue;
                    }
                    if (choice.equals("p") && from > 0) {
                        page--;
                        continue;
                    }
                    final int index;
                    try {
                        index = Integer.parseInt(choice) - 1;
                    } catch (final NumberFormatException e) {
                        println(MarkdownColorizer.status("invalid selection"));
                        return CompletableFuture.completedStage(Optional.empty());
                    }
                    if (index < 0 || index >= total) {
                        println(MarkdownColorizer.status("invalid selection"));
                        return CompletableFuture.completedStage(Optional.empty());
                    }
                    return CompletableFuture.completedStage(Optional.of(options.get(index)));
                }
            } catch (final PromptInterruptException e) {
                onInterrupt.run();
                return CompletableFuture.completedStage(Optional.empty());
            } catch (final IOException e) {
                return CompletableFuture.completedStage(Optional.empty());
            }
        });
    }

    @Override
    public ApprovalGate approvalGate() {
        return request -> {
            final var title =
                    (request.origin() == ApprovalGate.Origin.SANDBOX ? "Sandbox restriction: " : "Tool permission: ")
                            + request.title();
            final var detail = request.detail();
            onInteractionStart.run();
            renderLock.lock();
            try {
                return approvalDecision(title, detail);
            } finally {
                renderLock.unlock();
                onInteractionEnd.run();
            }
        };
    }

    private ApprovalGate.Result approvalDecision(final String title, final String detail) {
        printApprovalBox(title, detail);
        try {
            final var c = interactionLine(
                            MarkdownColorizer.accent("allow? [y/n/a] (y=once, n=no, a=always this session) "))
                    .map(line -> line.strip().toLowerCase(Locale.ROOT))
                    .orElse("");
            if (c.equals("y") || c.equals("yes") || c.equals("o") || c.equals("ok")) {
                return ApprovalGate.Result.ALLOW_ONCE;
            }
            if (c.equals("a") || c.equals("always")) {
                return ApprovalGate.Result.ALLOW_SESSION;
            }
        } catch (final PromptInterruptException e) {
            onInterrupt.run();
        } catch (final IOException ignored) {
            // treat as deny
        }
        return ApprovalGate.Result.DENY;
    }

    @Override
    public FeedbackGate feedbackGate() {
        return questions -> {
            if (questions == null || questions.isEmpty()) {
                return Map.of();
            }
            onInteractionStart.run();
            renderLock.lock();
            try {
                return collectAnswers(questions);
            } finally {
                renderLock.unlock();
                onInteractionEnd.run();
            }
        };
    }

    private Map<String, Object> collectAnswers(final List<Question> questions) {
        final Map<String, Object> answers = new HashMap<>();
        boolean restart;
        do {
            restart = false;
            answers.clear();
            for (final var question : questions) {
                final var answer = askOne(question);
                if (answer == null) {
                    return null;
                }
                answers.put(question.id(), answer);
            }
            // summary
            println(MarkdownColorizer.status("your answers:"));
            for (final var question : questions) {
                println("- " + question.prompt() + " -> " + answers.get(question.id()));
            }
            final var c = readAnswer(MarkdownColorizer.status("confirm? (a=accept, e=restart, c=cancel) [a] "))
                    .map(line -> line.strip().toLowerCase(Locale.ROOT))
                    .orElse("");
            if (c.equals("e") || c.equals("edit") || c.equals("restart")) {
                restart = true;
            } else if (c.equals("c") || c.equals("cancel")) {
                return null;
            }
        } while (restart);
        return answers;
    }

    private Object askOne(final Question question) {
        output.print("\n");
        println(MarkdownColorizer.accent(question.prompt()));
        final var options = question.options() == null ? List.<String>of() : question.options();
        for (int i = 0; i < options.size(); i++) {
            println("  " + (i + 1) + ". " + options.get(i));
        }
        final var ownIndex = options.size(); // 1-based id = options.size()
        if (question.multiple()) {
            println("  " + (ownIndex + 1) + ". (type your own answer)");
            println(MarkdownColorizer.status("enter comma-separated numbers"));
        } else {
            println("  " + (ownIndex + 1) + ". (type your own answer)");
            println(MarkdownColorizer.status("enter a number, or " + (ownIndex + 1) + " then your text"));
        }
        final var answer = readAnswer(MarkdownColorizer.status("> "));
        if (answer.isEmpty()) {
            return null;
        }
        final var trimmed = answer.get().strip();
        if (trimmed.isBlank()) {
            return question.multiple() ? List.of() : "";
        }
        if (question.multiple()) {
            final var values = new ArrayList<String>();
            for (final var part : trimmed.split(",")) {
                final var v = part.strip();
                if (v.isEmpty()) {
                    continue;
                }
                int id;
                try {
                    id = Integer.parseInt(v);
                } catch (final NumberFormatException nfe) {
                    values.add(v);
                    continue;
                }
                if (id >= 1 && id <= options.size()) {
                    values.add(options.get(id - 1));
                } else if (id == ownIndex + 1) {
                    readAnswer(MarkdownColorizer.status("your answer> "))
                            .filter(own -> !own.isBlank())
                            .ifPresent(values::add);
                }
            }
            return values;
        }
        int id;
        try {
            id = Integer.parseInt(trimmed);
        } catch (final NumberFormatException nfe) {
            return trimmed;
        }
        if (id >= 1 && id <= options.size()) {
            return options.get(id - 1);
        }
        if (id == ownIndex + 1) {
            return readAnswer(MarkdownColorizer.status("your answer> ")).orElse("");
        }
        return "";
    }

    /**
     * Reads a line for an inline interaction; a Ctrl-C runs the interrupt hook
     * and reads as a cancellation (empty optional, like EOF).
     */
    private Optional<String> readAnswer(final String promptText) {
        try {
            return interactionLine(promptText);
        } catch (final PromptInterruptException e) {
            onInterrupt.run();
            return Optional.empty();
        } catch (final IOException e) {
            return Optional.empty();
        }
    }

    private void printApprovalBox(final String title, final String detail) {
        final var lines = new ArrayList<String>();
        lines.add("Permission required");
        lines.add(title);
        if (detail != null && !detail.isBlank()) {
            detail.lines().forEach(line -> {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            });
        }
        var width = 0;
        for (final var line : lines) {
            width = Math.max(width, line.length());
        }
        width = Math.clamp(width, 32, 72);
        final var rule = symbols.boxHorizontal().repeat(width);
        println(MarkdownColorizer.accent(symbols.boxTop() + rule + symbols.boxCorner()));
        for (int i = 0; i < lines.size(); i++) {
            for (final var fragment : wrapLine(lines.get(i), width)) {
                final var styled = i == 0
                        ? MarkdownColorizer.bold(padTo(fragment, width))
                        : i == 1
                                ? MarkdownColorizer.warning(padTo(fragment, width))
                                : MarkdownColorizer.status(padTo(fragment, width));
                println(MarkdownColorizer.accent(symbols.boxLine()) + styled);
            }
        }
        println(MarkdownColorizer.accent(symbols.boxBottom() + rule + symbols.boxCornerBottom()));
    }

    private static List<String> wrapLine(final String text, final int width) {
        if (text.isEmpty()) {
            return List.of("");
        }
        final var out = new ArrayList<String>();
        final var words = WHITESPACE.split(text);
        final var sb = new StringBuilder();
        for (final var word : words) {
            if (!sb.isEmpty() && sb.length() + 1 + word.length() > width) {
                out.add(sb.toString());
                sb.setLength(0);
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(word);
        }
        out.add(sb.toString());
        return out;
    }

    private static String padTo(final String text, final int width) {
        if (text.length() > width) {
            return text.substring(0, Math.max(0, width - 1)) + "...";
        }
        final var sb = new StringBuilder(text);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
