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
package io.yupiik.ia.agent.core.tools.builtin;

import static io.yupiik.ia.agent.core.tools.ToolArgs.arg;
import static io.yupiik.ia.agent.core.tools.ToolArgs.optInt;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.tools.builtin.bashoverride.BashOverride;
import io.yupiik.ia.agent.core.tools.builtin.bashoverride.JavaCommandRegistry;
import io.yupiik.maven.knowledge.process.ProcessService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@ApplicationScoped
public class BashTool implements EmbeddedTool {
    /** Default command timeout in seconds (120s + 5s headroom so `sleep 120` is not killed on the bound). */
    static final int DEFAULT_TIMEOUT_SECONDS = 125;

    private final Emitter emitter;
    private final ProcessService processes;
    private final int maxOutputChars;

    private final BashOverride bashOverride;
    private final ShellResolver shellResolver;

    public BashTool(
            final Emitter emitter,
            final ProcessService processes,
            final CodingAgentConfiguration configuration,
            final BashOverride bashOverride) {
        this.emitter = emitter;
        this.processes = processes;
        this.maxOutputChars = configuration.tools().maxOutputChars();
        this.bashOverride = bashOverride;
        this.shellResolver = new ShellResolver(
                configuration.tools() == null ? null : configuration.tools().shell());
    }

    public BashTool(
            final Emitter emitter, final ProcessService processes, final CodingAgentConfiguration configuration) {
        this(
                emitter,
                processes,
                configuration,
                new BashOverride(
                        new JavaCommandRegistry(List.of()),
                        configuration,
                        Executors.newVirtualThreadPerTaskExecutor()));
    }

    public BashTool(final Emitter emitter, final ProcessService processes) {
        this.emitter = emitter;
        this.processes = processes;
        this.maxOutputChars = 65536;
        this.bashOverride = null; // no config -> no overrides
        this.shellResolver = new ShellResolver(null);
    }

    protected BashTool() {
        this.emitter = null;
        this.processes = null;
        this.maxOutputChars = 65536;
        this.bashOverride = null;
        this.shellResolver = new ShellResolver(null);
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description(final Path workdir) {
        return "Run a shell command (real bash when available, /bin/sh otherwise) and return its combined "
                + "stdout/stderr (truncated at maxOutputChars, " + toolOutputCapHuman() + "). "
                + "For terminal operations (git, mvn, npm, docker...) and batched read-only surveys; file "
                + "ops use the dedicated read/grep/glob tools. Use the workdir parameter instead of `cd <dir> && <cmd>`.";
    }

    private String toolOutputCapHuman() {
        return maxOutputChars >= 1000 ? (maxOutputChars / 1000) + "k chars" : maxOutputChars + " chars";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.BASH;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(
                Map.of(
                        "command", Schemas.str("The shell command to run."),
                        "timeout_seconds",
                                Schemas.intProp("Command timeout in seconds, default " + DEFAULT_TIMEOUT_SECONDS + "."),
                        "workdir", Schemas.str("Working directory; defaults to the session's project directory.")),
                "command");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        final var command = arg(args, "command");
        if (command == null || command.isBlank()) {
            return ToolResult.error("command is required");
        }
        final var timeout = optInt(args, "timeout_seconds", DEFAULT_TIMEOUT_SECONDS);
        final var workdir = arg(args, "workdir");
        // Real bash by default (yca.tools.shell=auto): the tool is licensed as "bash" so the model may rely on
        // bash-isms (PIPESTATUS, ${VAR:-x}, [[ ]], =~ ...) that a minimal /bin/sh (dash on Debian/Ubuntu) would
        // reject with 'Bad substitution'. When bash is absent (alpine, Windows without Git Bash/WSL) or when the
        // user pinned an unavailable shell, it falls back to /bin/sh and the deviation is surfaced in the output.
        final var shell = shellResolver.resolve();
        final var pbNotice = new StringBuilder(shell.notice());
        final var pb = new ProcessBuilder(shell.binary().toString(), "-c", command);
        pb.redirectErrorStream(true);
        final var workdirNotice = new StringBuilder();
        final var dir = workdir == null || workdir.isBlank()
                ? context.projectDir()
                : context.resolvePath(workdir).normalize();
        if (!Files.isDirectory(dir)) {
            return ToolResult.error("workdir '" + dir + "' does not exist");
        }
        pb.directory(dir.toFile());
        // In-process read-only survey rewrite (yca.tools.bashOverrides): when the command is entirely made
        // of supported read-only survey commands, run it through the Java* reimplementations instead of a
        // real shell - output stays byte-faithful (only capped) and does not spawn a subprocess. Anything
        // not confidently recognized falls back to the real shell below.
        if (!context.sandbox().enabled() && bashOverride != null && !command.isBlank()) {
            final var baseDir = dir;
            final var rewritten = bashOverride.tryRewrite(command, baseDir);
            if (rewritten.isPresent()) {
                final var output = rewritten.get();
                return ToolResult.ok(withNotice(joinNotices(pbNotice, workdirNotice), output));
            }
        }
        final io.yupiik.ia.agent.core.sandbox.Sandbox.Command handle;
        try {
            handle = context.sandbox().launch(pb);
        } catch (final IOException | SecurityException e) {
            return ToolResult.error("Cannot start sandboxed command: " + e.getMessage());
        }
        try (handle) {
            final var process = handle.process();
            if (processes != null) {
                processes.track(process);
            }
            emitter.emit(new AgentEvent.ToolStream(
                    "bash",
                    maskSecretsForDisplay("$ " + command),
                    context.ordinal(),
                    context.sessionId(),
                    context.subagentId(),
                    context.subagentName(),
                    context.projectDir()));
            final var out = new StringBuilder();
            final var streamed = new AtomicInteger();
            final var reader = new Thread(() -> {
                try (BufferedReader r =
                        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (out) {
                            if (out.length() < maxOutputChars) {
                                out.append(line).append('\n');
                            }
                        }
                        if (streamed.incrementAndGet() <= 500 && emitter != null) {
                            emitter.emit(new AgentEvent.ToolStream(
                                    "bash",
                                    line,
                                    context.ordinal(),
                                    context.sessionId(),
                                    context.subagentId(),
                                    context.subagentName(),
                                    context.projectDir()));
                        }
                    }
                } catch (final IOException e) {
                    // stream closed
                }
            });
            reader.setDaemon(true);
            reader.start();
            boolean finished;
            // deadline loop: the command is killed as soon as the running turn is cancelled so an
            // "interrupt" actually stops the subprocess instead of letting it run to completion.
            // waitFor is polled in short slices so both the deadline and the cancellation flag are
            // re-checked until the command actually finishes.
            final var deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
            while (true) {
                if (context.isCancelled() || context.isCallCancelled()) {
                    dispose(process);
                    return ToolResult.error("Interrupted");
                }
                final var remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    finished = false;
                    break;
                }
                try {
                    finished = process.waitFor(Math.clamp(remainingNanos / 1_000_000, 1, 100), TimeUnit.MILLISECONDS);
                    if (finished) {
                        break;
                    }
                    // not finished yet: loop back to re-check cancellation/deadline before the next slice
                } catch (final InterruptedException e) {
                    dispose(process);
                    Thread.currentThread().interrupt();
                    return ToolResult.error("Interrupted");
                }
            }
            if (!finished) {
                dispose(process);
                return ToolResult.error("Command timed out after " + timeout + "s\n"
                        + withNotice(joinNotices(pbNotice, workdirNotice), snapshot(out, maxOutputChars)));
            }
            try {
                reader.join(2_000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            final var code = process.exitValue();
            return ToolResult.ok(withNotice(joinNotices(pbNotice, workdirNotice), snapshot(out, maxOutputChars))
                    + "\nExit code: " + code);
        }
    }

    /**
     * The session-level working-directory candidates to try (in precedence order) when the call carries
     * no explicit {@code workdir} arg: the session's project root, then the configured working directory.
     * Missing or null candidates are skipped by the caller via {@link java.nio.file.Files#isDirectory}.
     */
    /** Prepends the (shell/workdir) deviation notice (when any) as the first line of the tool output. */
    private static String withNotice(final StringBuilder notice, final String output) {
        return notice.length() == 0 ? output : notice + "\n" + output;
    }

    /** Joins the shell-choice notice and the workdir-deviation notice into a single leading notice block. */
    private static StringBuilder joinNotices(final StringBuilder shell, final StringBuilder workdir) {
        if (shell.length() == 0) {
            return workdir;
        }
        if (workdir.length() == 0) {
            return shell;
        }
        return shell.append('\n').append(workdir);
    }

    /** Kills and reaps the whole process tree through the service (null-safe for standalone tests). */
    private void dispose(final Process process) {
        if (processes != null) {
            processes.dispose(process);
        } else if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static String snapshot(final StringBuilder out, final int maxOutputChars) {
        synchronized (out) {
            final var s = out.toString();
            return s.length() > maxOutputChars
                    ? s.substring(0, maxOutputChars) + "\n... output truncated"
                    : s.stripTrailing();
        }
    }

    /** Known secret shapes masked in the DISPLAYED command echo (never in the executed command). */
    private static final Pattern[] DISPLAY_SECRET_PATTERNS = {
        Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+"),
        Pattern.compile("(?i)((?:api[_-]?key|token|password|passwd|secret|client[_-]?secret)\\s*[=:]\\s*)[^\\s'\"]+"),
        Pattern.compile("(?i)((?:authorization|proxy-authorization|x-api-key|x-auth-token)\\s*:\\s*)[^\\s'\"]+"),
    };

    private static String maskSecretsForDisplay(final String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        String out = line;
        for (final var pattern : DISPLAY_SECRET_PATTERNS) {
            out = pattern.matcher(out).replaceAll(m -> {
                final var header = m.groupCount() >= 1 ? m.group(1) : "";
                return header + "***";
            });
        }
        return out;
    }
}
