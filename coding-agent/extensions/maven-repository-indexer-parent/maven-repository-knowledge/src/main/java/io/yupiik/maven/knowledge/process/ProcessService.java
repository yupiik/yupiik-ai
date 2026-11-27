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
package io.yupiik.maven.knowledge.process;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central lifecycle for every subprocess yca spawns (bash tool, MCP stdio servers, maven
 * dependency resolution, clipboard helpers). Starters must hand their {@link Process} to
 * {@link #track(Process)} before using it, so the service can guarantee:
 *
 * <ul>
 *   <li>a running process can always be killed as a <em>tree</em> ({@link ProcessHandle#descendants()}
 *       then the leader), which plain {@code destroyForcibly()} on a shell would miss for orphaned
 *       foreground children;</li>
 *   <li>normal exits, container close (fusion {@code CliAwaiter} at the end of every command) and
 *       JVM shutdown (fusion {@code Launcher} hook when {@code fusion.launcher.useHook=true}) all
 *       funnel through {@link #disposeAll()} ({@code @Destroy}), so no tracked subprocess outlives
 *       the JVM.</li>
 * </ul>
 *
 * <p>Disposal is two-phase so several processes are stopped concurrently instead of being killed
 * and waited for one after another: {@link #disposeAll()} snapshots the registry, kills every
 * tracked tree (no wait in between), then waits for all of them with a single shared deadline
 * ({@code Process.onExit()} futures joined with {@code CompletableFuture.allOf}). The total
 * shutdown time is therefore {@code max(reap)} bounded by {@link #reapGraceMillis()} instead of a
 * per-process sum.</p>
 *
 * <p>Reaping is best-effort: if a process ignores a kill and the grace expires, it is left to the
 * OS to reap rather than blocked forever. {@link #dispose(Process)} and {@link #disposeAll()} are
 * idempotent and safe to call concurrently.</p>
 */
@ApplicationScoped
public class ProcessService {

    private static final Logger LOGGER = Logger.getLogger(ProcessService.class.getName());

    private static final long DEFAULT_REAP_GRACE_MILLIS = 2_000L;

    /** Every tracked, not-yet-finished process. Pid-keyed so the same process is never tracked twice. */
    private final Map<Long, Process> tracked = new ConcurrentHashMap<>();

    /** How long to wait for a killed process tree to exit before giving up and letting the OS reap it. */
    private volatile long reapGraceMillis = DEFAULT_REAP_GRACE_MILLIS;

    /** Test hook: shorten the reap bound so tests never pay the production grace. */
    public void reapGrace(final long millis) {
        this.reapGraceMillis = Math.max(1, millis);
    }

    /**
     * Registers a freshly started process so {@link #dispose(Process)} and {@link #disposeAll()}
     * can reach it. When the process has already finished (fast command, immediately failed
     * start) it is not retained.
     */
    public Process track(final Process process) {
        if (process != null && process.isAlive()) {
            tracked.put(process.pid(), process);
        }
        return process;
    }

    /** Number of currently tracked, still-running processes. */
    public int trackedCount() {
        return tracked.size();
    }

    /**
     * Kills one tracked process tree (all descendants first, then the leader) and waits up to the
     * reap grace for it to exit. Idempotent: an already-dead or unknown process is a no-op.
     *
     * @return the exit value when the process actually exited within the grace, {@code null} otherwise.
     */
    public Integer dispose(final Process process) {
        if (process == null) {
            return null;
        }
        removeTracked(process);
        if (!process.isAlive()) {
            return process.exitValue();
        }
        destroyTree(process);
        try {
            if (process.waitFor(reapGraceMillis, TimeUnit.MILLISECONDS)) {
                return process.exitValue();
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /**
     * Two-phase disposal of every tracked process: first every tree is killed (snapshot the
     * registry so concurrent {@link #dispose(Process)} calls cannot interfere), then all of them
     * are awaited with one shared deadline. No kill+wait loop: the wait happens only after every
     * process got its kill, so the total bound is the slowest single reap.
     *
     * @return the list of processes that were still alive when this call ran (empty when none).
     */
    public List<Process> disposeAll() {
        final var snapshot = new ArrayList<>(tracked.values());
        tracked.clear();
        if (snapshot.isEmpty()) {
            return List.of();
        }
        launchDisposeAsync(snapshot);
        return awaitDisposed(snapshot, reapGraceMillis);
    }

    /**
     * Kills every tracked process tree concurrently without waiting: the caller is expected to
     * {@link #awaitDisposed(List, long)} afterwards (or rely on {@code @Destroy}). Used when the
     * kill must not block the current thread (shutdown backstop).
     */
    public void killAllAsync() {
        final var snapshot = new ArrayList<>(tracked.values());
        tracked.clear();
        launchDisposeAsync(snapshot);
    }

    private void launchDisposeAsync(final List<Process> snapshot) {
        for (final var process : snapshot) {
            if (process.isAlive()) {
                destroyTree(process);
            }
        }
    }

    /**
     * Waits for every {@code onExit()} future of the given processes with a single shared bound.
     *
     * @return {@code null} when every process exited within the bound, else the process list still alive.
     */
    public List<Process> awaitDisposed(final List<Process> processes, final long timeoutMillis) {
        if (processes == null || processes.isEmpty()) {
            return List.of();
        }
        final CompletableFuture<?>[] futures =
                processes.stream().map(Process::onExit).toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(futures).get(timeoutMillis, TimeUnit.MILLISECONDS);
            return List.of();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (final ExecutionException | TimeoutException e) {
            // timeout: the stragglers are left to the OS, they were SIGKILLed already
        }
        return processes.stream().filter(Process::isAlive).toList();
    }

    /**
     * Kills a process and (part of) its descendants. The descendants are fetched once and killed
     * first, then the leader, so a parent cannot fork new children after its own signal arrived.
     * Descendants that already exited are skipped; a descendant that spawns a detached grandchild
     * between the snapshot and the signals can escape (documented limitation of a best-effort
     * reaper - the direct child, which is the common leak, is always covered).
     */
    static void destroyTree(final Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        try {
            process.toHandle().descendants().forEach(descendant -> {
                if (descendant.isAlive()) {
                    descendant.destroyForcibly();
                }
            });
        } catch (final IllegalStateException | UnsupportedOperationException e) {
            LOGGER.log(Level.FINE, "cannot enumerate descendants of pid " + process.pid() + ": " + e.getMessage());
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        LOGGER.log(Level.FINE, "disposed process tree of pid " + process.pid());
    }

    /** Drops one process from the registry once it finished (or when it is explicitly disposed). */
    public void removeTracked(final Process process) {
        if (process != null) {
            tracked.remove(process.pid());
        }
    }

    /**
     * Container-close cleanup: every still-tracked process is killed and awaited (kill-all first,
     * then a single shared wait). Runs on fusion container close - i.e. at the end of every
     * command (CliAwaiter) and on JVM shutdown when the fusion Launcher hook is enabled.
     */
    @Destroy
    public void close() {
        final var start = System.nanoTime();
        final List<Process> stragglers = disposeAll();
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(() -> "[yca-shutdown] ProcessService.close: killed/waiting process(es) done in "
                    + (System.nanoTime() - start) / 1_000_000L + " ms"
                    + (stragglers.isEmpty() ? "" : "; " + stragglers.size() + " SIGKILLed but left to the OS"));
        }
    }

    /** @return the current reap grace (used by tests to assert the bounds without hardcoding). */
    public long reapGraceMillis() {
        return reapGraceMillis;
    }
}
