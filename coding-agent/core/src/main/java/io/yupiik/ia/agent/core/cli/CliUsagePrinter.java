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

import io.yupiik.ia.agent.core.configuration.MetricsDisplayMode;
import io.yupiik.ia.agent.core.shell.AnsiShellFrontend;
import io.yupiik.ia.agent.core.shell.DurationFormat;
import io.yupiik.ia.agent.core.shell.MetricsFormat;
import io.yupiik.ia.agent.core.shell.ShellSession;
import io.yupiik.ia.agent.core.todo.TodoItem;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Post-turn CLI reporting: the per-turn usage/metrics footer ({@code printUsage}) and the
 * todo-list diff notice ({@code printTodosIfChanged}). Pure rendering - it reads the session
 * state handed in and writes through the provided sinks, owning no state of its own.
 */
public final class CliUsagePrinter {
    private final CliCommand.CliConf conf;
    private final ShellSession session;
    private final AnsiShellFrontend frontend;
    private final Symbols symbols;
    private final Consumer<String> println;
    private final Runnable printlnBlank;
    private final Consumer<String> writeLineErr;

    public CliUsagePrinter(
            final CliCommand.CliConf conf,
            final ShellSession session,
            final AnsiShellFrontend frontend,
            final Symbols symbols,
            final Consumer<String> println,
            final Runnable printlnBlank,
            final Consumer<String> writeLineErr) {
        this.conf = conf;
        this.session = session;
        this.frontend = frontend;
        this.symbols = symbols;
        this.println = println;
        this.printlnBlank = printlnBlank;
        this.writeLineErr = writeLineErr;
    }

    public void printTodosIfChanged(final List<TodoItem> before) {
        final var after = session.runtime().todos().all();
        if (after.equals(before)) {
            return;
        }
        printlnBlank.run();
        println.accept(MarkdownColorizer.status("todos:"));
        frontend.renderTodos(after).forEach(println);
    }

    public void printUsage() {
        final var stats = frontend.stats();
        final var usage = stats.lastUsage();
        if (usage == null) {
            return;
        }
        final var lastTurnMetrics = stats.lastTurnMetrics();
        final var toolsRun = stats.toolsRun();
        final var sb = new StringBuilder();
        sb.append(symbols.infoMarker());
        if (conf.showUsage()) {
            sb.append(String.format(
                    Locale.ROOT,
                    "~%,d tokens (%.1f%% of %,d)",
                    usage.estimatedContextTokens(),
                    usage.contextPercent(),
                    session.runtime().usage().usableWindow()));
            if (usage.usage() != null && usage.usage().cacheReadTokens() > 0) {
                final var cached = MetricsFormat.cachedTokens(
                        usage.usage().cacheReadTokens(), usage.usage().contextInputTokens());
                if (!cached.isEmpty()) {
                    sb.append(" · ").append(cached);
                }
            }
            final var backgroundTokens = session.runtime().usage().auxiliaryContextInputTokens()
                    + session.runtime().usage().auxiliaryCompletionTokens();
            if (backgroundTokens > 0) {
                sb.append(String.format(Locale.ROOT, " · %,d background tokens (compaction/memory)", backgroundTokens));
            }
        }
        if (session.metrics().showPerTurn() && lastTurnMetrics != null) {
            if (conf.showUsage()) {
                sb.append(" · ");
            }
            sb.append(lastTurnMetrics.llmCalls()).append(" LLM");
            if (lastTurnMetrics.thinkingDurationMs() > 0) {
                sb.append(" (")
                        .append(DurationFormat.humanize(lastTurnMetrics.thinkingDurationMs()))
                        .append(" think)");
            }
            final var tokPerSec = MetricsFormat.tokPerSec(lastTurnMetrics.throughput());
            if (!tokPerSec.isEmpty()) {
                sb.append(" · ").append(tokPerSec);
            }
            if (session.metrics().displayMode() == MetricsDisplayMode.FULL) {
                sb.append(" · ")
                        .append(lastTurnMetrics.toolsRun())
                        .append(" tools ")
                        .append("(")
                        .append(DurationFormat.humanize(lastTurnMetrics.toolDurationSumMs()))
                        .append(" sum")
                        .append("/")
                        .append(DurationFormat.humanize(lastTurnMetrics.toolDurationWallMs()))
                        .append(" wall)");
            } else {
                sb.append(" · ")
                        .append(lastTurnMetrics.toolsRun())
                        .append(" tools (")
                        .append(DurationFormat.humanize(lastTurnMetrics.toolDurationSumMs()))
                        .append("/")
                        .append(DurationFormat.humanize(lastTurnMetrics.toolDurationWallMs()))
                        .append(")");
            }
        } else if (toolsRun > 0) {
            if (conf.showUsage()) {
                sb.append(" · ");
            }
            sb.append(toolsRun).append(toolsRun == 1 ? " tool" : " tools");
        }
        if (session.metrics().showSessionTotals() && session.runtime().usage().requests() > 0) {
            if (conf.showUsage() || toolsRun > 0 || (session.metrics().showPerTurn() && lastTurnMetrics != null)) {
                sb.append(" · ");
            }
            sb.append("session: ").append(session.runtime().usage().requests()).append(" LLM calls");
        }
        final var elapsedMillis = (System.nanoTime() - stats.turnStartNanos()) / 1_000_000L;
        if (conf.showUsage()
                || toolsRun > 0
                || (session.metrics().showPerTurn() && lastTurnMetrics != null)
                || (session.metrics().showSessionTotals()
                        && session.runtime().usage().requests() > 0)) {
            sb.append(" · ");
        }
        sb.append(DurationFormat.humanize(elapsedMillis));
        writeLineErr.accept(MarkdownColorizer.status(sb.toString()));
    }
}
