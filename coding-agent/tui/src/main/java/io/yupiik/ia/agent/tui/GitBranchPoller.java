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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Polls the git branch of the working tree on the shared scheduler so the sidebar never reads
 * the disk from the render thread. Runs out of the render thread, compares the resolved HEAD
 * CONTENT (not the mtime: in-place writes do not always touch it) and only notifies the listener -
 * which repaints - when the branch actually changed.
 *
 * <p>All tabs share {@code user.dir} so a single poller serves the whole TUI. The polled value is
 * visible through {@link #branch()} without any caching: the poller is the cache. When no
 * scheduler is available (render-only tests), the TUI falls back to the historical on-demand
 * read with a short TTL.
 */
public class GitBranchPoller implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(GitBranchPoller.class.getName());

    private static final long POLL_INTERVAL_MILLIS = 1_000L;

    private final ScheduledExecutorService scheduler;
    private final String workingDirectory;
    private final long pollIntervalMillis;
    private final Consumer<String> onBranchChanged;
    private final AtomicReference<String> branch = new AtomicReference<>();
    private ScheduledFuture<?> task;
    private volatile String lastContent;

    public GitBranchPoller(final ScheduledExecutorService scheduler, final Consumer<String> onBranchChanged) {
        this(scheduler, onBranchChanged, System.getProperty("user.dir"));
    }

    GitBranchPoller(
            final ScheduledExecutorService scheduler,
            final Consumer<String> onBranchChanged,
            final String workingDirectory) {
        this(scheduler, onBranchChanged, workingDirectory, POLL_INTERVAL_MILLIS);
    }

    /**
     * Full constructor: the interval is injectable so a test can poll fast instead of waiting a
     * production tick, without duplicating the detection logic.
     */
    GitBranchPoller(
            final ScheduledExecutorService scheduler,
            final Consumer<String> onBranchChanged,
            final String workingDirectory,
            final long pollIntervalMillis) {
        this.scheduler = scheduler;
        this.onBranchChanged = onBranchChanged;
        this.workingDirectory = workingDirectory;
        this.pollIntervalMillis = pollIntervalMillis;
    }

    /** Schedules the periodic poll; idempotent. */
    public void start() {
        if (task != null) {
            return;
        }
        task = scheduler.scheduleWithFixedDelay(this::poll, 0, pollIntervalMillis, TimeUnit.MILLISECONDS);
    }

    /** Last polled branch (or {@code null} when not in a git repository yet). */
    public String branch() {
        return branch.get();
    }

    /** Cancels the poll task; safe to call twice. */
    @Override
    public void close() {
        final var current = task;
        if (current != null) {
            current.cancel(false);
            task = null;
        }
    }

    /**
     * Locates the HEAD of the repository containing {@code cwd}, walking up the parents. A plain
     * {@code .git} directory is the classic layout; a {@code .git} FILE (submodule or linked
     * worktree) carries a {@code gitdir: <path>} line resolved relative to the repository root.
     *
     * @return the resolved HEAD path, or {@code null} when {@code cwd} is not in any repository
     */
    static Path resolveHead(final String cwd) throws IOException {
        var dir = Path.of(cwd).toAbsolutePath();
        while (dir != null) {
            final var git = dir.resolve(".git");
            if (Files.isRegularFile(git)) {
                for (final var line :
                        Files.readString(git, StandardCharsets.UTF_8).lines().toList()) {
                    if (line.startsWith("gitdir:")) {
                        return dir.resolve(line.substring("gitdir:".length()).trim())
                                .normalize()
                                .resolve("HEAD");
                    }
                }
            } else if (Files.isDirectory(git)) {
                return git.resolve("HEAD");
            }
            dir = dir.getParent();
        }
        return null;
    }

    /** Parses a HEAD payload: short branch name for {@code ref: refs/heads/...}, 7-char hash otherwise. */
    static String branchOf(final String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        final var value = content.strip();
        if (value.startsWith("ref: refs/heads/")) {
            return value.substring("ref: refs/heads/".length());
        }
        return value.length() > 7 ? value.substring(0, 7) : value;
    }

    private void poll() {
        try {
            final var head = resolveHead(workingDirectory);
            final var content = head != null && Files.isRegularFile(head)
                    ? Files.readString(head, StandardCharsets.UTF_8).strip()
                    : null;
            if (!Objects.equals(content, lastContent)) {
                lastContent = content;
                final var value = branchOf(content);
                branch.set(value);
                onBranchChanged.accept(value);
            }
        } catch (final IOException e) {
            LOG.fine(() -> "git branch poll failed: " + e.getMessage());
        }
    }
}
