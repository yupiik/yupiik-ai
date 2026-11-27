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
package io.yupiik.ia.agent.core.events.hooks;

import static java.util.stream.Collectors.toMap;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.HookRegistration;
import io.yupiik.ia.agent.core.configuration.HooksConfiguration;
import io.yupiik.ia.agent.core.events.HookResponse;
import io.yupiik.maven.knowledge.process.ProcessService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Executes the configured hook callbacks for an event and resolves their ack.
 *
 * <p>The callback contract (identical for every event):</p>
 * <ul>
 *   <li>the event {@code @JsonModel} record is serialized (camelCase) and piped on the callback's stdin;</li>
 *   <li>a non-empty valid JSON on stdout is parsed as a {@link HookResponse} and its fields apply
 *       ({@code forbid} blocks, {@code allow} lets through, {@code context} appends for the model);</li>
 *   <li>stdout {@code strip()} empty → no action (noop);</li>
 *   <li>invalid JSON, a non-zero exit or a timeout → the callback raises an error (logged, no decision applies).</li>
 * </ul>
 * Only valid JSON or empty output is "ok"; anything else for a single callback is an error for that callback.
 *
 * <p>Callbacks run concurrently on {@link ExecutorService} (never the caller's thread — in particular never
 * the TUI render thread) and each subprocess is tracked by {@link ProcessService} so a cancelled turn kills its
 * tree. {@link #run(String, Object, int, Path)} blocks until every callback settles and returns their responses, so a
 * blocking event can gate on the aggregate verdict.</p>
 */
@ApplicationScoped
public class CallbackRunner {
    private static final Logger LOGGER = Logger.getLogger(CallbackRunner.class.getName());

    /** Per-event registrations indexed once at construction: {@code eventId -> callbacks}. */
    private final Map<String, List<HookRegistration>> registrations;

    private final ExecutorService executor;
    private final ProcessService processes;
    private final JsonMapper mapper;

    private static Map<String, List<HookRegistration>> index(final HooksConfiguration hooks) {
        if (hooks == null || hooks.hooks() == null || hooks.hooks().isEmpty()) {
            return Map.of();
        }
        return hooks.hooks().stream()
                .filter(h -> h != null && h.event() != null)
                .collect(toMap(HookRegistration::event, List::of, (a, b) -> concat(a, b), LinkedHashMap::new));
    }

    private static List<HookRegistration> concat(final List<HookRegistration> a, final List<HookRegistration> b) {
        final var merged = new ArrayList<>(a);
        merged.addAll(b);
        return merged;
    }

    public CallbackRunner(
            final CodingAgentConfiguration configuration,
            final ExecutorService executor,
            final ProcessService processes,
            final JsonMapper mapper) {
        this(configuration == null ? null : configuration.hooks(), executor, processes, mapper);
    }

    /** Test-facing constructor with the hooks configuration resolved up front (the container injects the config form). */
    public CallbackRunner(
            final HooksConfiguration hooks,
            final ExecutorService executor,
            final ProcessService processes,
            final JsonMapper mapper) {
        this.registrations = index(hooks);
        this.executor = executor;
        this.processes = processes;
        this.mapper = mapper;
    }

    protected CallbackRunner() { // no-arg ctor for container-less gradle/tests
        this.registrations = Map.of();
        this.executor = null;
        this.processes = null;
        this.mapper = null;
    }

    /**
     * Runs every callback registered for {@code eventId} with {@code input} (an event {@code @JsonModel} record)
     * and joins them, returning their parsed acks in declaration order. When no callback is configured, or every
     * callback printed nothing, the result is empty.
     *
     * @param inheritedTimeoutSeconds the event's own budget used when a callback does not set its own
     *                                {@link HookRegistration#timeoutSeconds()}.
     */
    public List<HookResponse> run(
            final String eventId, final Object input, final int inheritedTimeoutSeconds, final Path workdir) {
        Objects.requireNonNull(workdir, "workdir");
        final var calls =
                eventId == null ? List.<HookRegistration>of() : registrations.getOrDefault(eventId, List.of());
        if (calls.isEmpty()) {
            return List.of();
        }
        final byte[] payload;
        try {
            payload = mapper.toBytes(input);
        } catch (final RuntimeException e) {
            LOGGER.log(Level.SEVERE, "cannot serialize event " + eventId + " for hook callback", e);
            return List.of();
        }
        final var futures = new ArrayList<Future<HookResponse>>(calls.size());
        final var timeouts = new ArrayList<Integer>(calls.size());
        for (final var hook : calls) {
            final var timeoutSeconds = hook.effectiveTimeoutSeconds(inheritedTimeoutSeconds);
            try {
                futures.add(executor.submit(() -> runOne(eventId, hook, timeoutSeconds, payload, workdir)));
                timeouts.add(timeoutSeconds);
            } catch (final RejectedExecutionException e) {
                // the executor is shutting down (app close): no point retrying, do not fail the turn
                LOGGER.log(Level.WARNING, () -> "hook callback for " + eventId + " rejected: " + e.getMessage());
            }
        }
        final var responses = new ArrayList<HookResponse>(calls.size());
        for (var i = 0; i < futures.size(); i++) {
            final var future = futures.get(i);
            final var timeoutSeconds = timeouts.get(i);
            try {
                final var response = future.get(timeoutSeconds > 0 ? timeoutSeconds : 1, TimeUnit.SECONDS);
                if (response != null) {
                    responses.add(response);
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.log(Level.WARNING, "hook callback for " + eventId + " interrupted", e);
            } catch (final ExecutionException e) {
                LOGGER.log(Level.WARNING, () -> "hook callback for " + eventId + " failed: " + e.getCause());
            } catch (final TimeoutException e) {
                // cancel the callback: interrupt the drain threads (a verbose child holding a pipe open
                // can otherwise outlive the timeout and pin the executor thread); the child process is
                // already killed by runOne's finally (processes.dispose/untracked destroyForcibly), and a
                // running inference killed here is bounded by the hook budget by design
                future.cancel(true);
                LOGGER.log(Level.WARNING, () -> "hook callback for " + eventId + " timed out: " + e.getMessage());
            }
        }
        return responses;
    }

    /**
     * @return the parsed {@link HookResponse} when the callback printed valid JSON (and exited 0), else
     * {@code null} (empty stdout → noop) — invalid JSON, non-zero exit or timeout raise (logged here by {@link #run}).
     */
    private HookResponse runOne(
            final String eventId,
            final HookRegistration hook,
            final int timeoutSeconds,
            final byte[] payload,
            final Path workdir)
            throws InterruptedException {
        final var pb = new ProcessBuilder(hook.command());
        pb.redirectErrorStream(false);
        final var directory = hook.dir() == null || hook.dir().isBlank()
                ? workdir
                : workdir.resolve(hook.dir()).normalize();
        pb.directory(directory.toFile());
        final Process process;
        try {
            process = pb.start();
        } catch (final IOException e) {
            throw new IllegalStateException(
                    "hook " + eventId + " cannot start " + hook.command() + ": " + e.getMessage(), e);
        }
        if (processes != null) {
            processes.track(process);
        }
        try {
            // feed the event JSON on stdin, then close so the callback can read EOF
            try (final var stdin = process.getOutputStream()) {
                stdin.write(payload);
                stdin.flush();
            } catch (final IOException e) {
                if (!process.isAlive() && process.exitValue() == 0) { // process exited intentionally (toggle for ex)
                    return new HookResponse(null, null, null);
                }
                throw new IllegalStateException("hook " + eventId + " stdin write failed: " + e.getMessage(), e);
            }
            // drain stdout (ack) and stderr (error detail) concurrently on virtual threads so a verbose callback
            // cannot deadlock (a full pipe would otherwise block the child while we poll waitFor). Virtual-thread
            // blocking reads park cheaply (no carrier pinning) and in-memory buffers avoid any temp files.
            final var stdout = new ByteArrayOutputStream();
            final var stderr = new ByteArrayOutputStream();
            final var drainOut = executor.submit(() -> {
                drain(process.getInputStream(), stdout);
                return null;
            });
            final var drainErr = executor.submit(() -> {
                drain(process.getErrorStream(), stderr);
                return null;
            });
            final var finished = await(process, timeoutSeconds);
            try {
                drainOut.get(timeoutSeconds > 0 ? timeoutSeconds : 1, TimeUnit.SECONDS);
                drainErr.get(timeoutSeconds > 0 ? timeoutSeconds : 1, TimeUnit.SECONDS);
            } catch (final TimeoutException ignored) {
                // output not fully drained after the process ended (eg. a spawned child kept a pipe open): keep what we
                // have
            } catch (final ExecutionException ignored) {
                // a drain read failed (stream closed); the ack/stderr captured so far is still usable
            }
            if (!finished) {
                throw new HookTimeoutException("hook " + eventId + " timed out after " + timeoutSeconds + "s");
            }
            final var code = process.exitValue();
            if (processes != null) {
                processes.removeTracked(process);
            }
            if (code != 0) {
                throw new IllegalStateException("hook " + eventId + " exited with status " + code
                        + (stderr.size() == 0
                                ? ""
                                : ": " + stderr.toString(StandardCharsets.UTF_8).strip()));
            }
            return ack(eventId, stdout.toString(StandardCharsets.UTF_8));
        } finally {
            if (processes != null) {
                processes.dispose(process);
            } else if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private boolean await(final Process process, final int timeoutSeconds) throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
        while (process.isAlive()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            process.waitFor(
                    Math.min(100, TimeUnit.NANOSECONDS.toMillis(Math.max(1_000_000, deadline - System.nanoTime()))),
                    TimeUnit.MILLISECONDS);
        }
        return true;
    }

    /** Parses the ack: empty → noop ({@code null}); otherwise a valid {@link HookResponse} or an error. */
    private HookResponse ack(final String eventId, final String stdout) {
        final var stripped = stdout.strip();
        if (stripped.isEmpty()) {
            return null; // noop
        }
        try {
            final var response = mapper.fromString(HookResponse.class, stripped);
            return response;
        } catch (final RuntimeException e) {
            throw new IllegalStateException("hook " + eventId + " printed invalid JSON: " + e.getMessage(), e);
        }
    }

    private static void drain(final InputStream in, final ByteArrayOutputStream out) {
        try (in) {
            final var buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } catch (final IOException ignored) {
            // stream closed
        }
    }

    /** Marker for a hook that exceeded its budget (kept as a subtype of RuntimeException, not a checked TimeoutException). */
    private static final class HookTimeoutException extends RuntimeException {
        private HookTimeoutException(final String message) {
            super(message);
        }
    }
}
