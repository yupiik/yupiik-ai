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
package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.handlebars.helper.HelperContext;
import io.yupiik.fusion.framework.handlebars.helper.SafeString;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Provides the {@code exec} handlebars helper used by the provider {@code dynamicHeaders}:
 * runs a command with an optional child environment and returns its stdout, optionally cached
 * for a TTL.
 *
 * <p>Usage: {@code {{exec "exec_options=ttl=3600,strip=true" "MY_ENV=foo" "MY_OTHER_ENV=bar"
 * "somebin" "somearg" "someotherarg"}}}. Leading {@code KEY=VALUE} arguments are environment
 * entries applied to the child process; the reserved {@code exec_options} argument is a
 * comma-separated {@code key=value} list ({@code ttl=N} caches the stdout for N seconds,
 * {@code strip=true|false} trims the captured output, both optional, {@code strip} defaults to
 * {@code true}); every remaining argument is the command line (command then argv).</p>
 *
 * <p>The command is executed with {@link ProcessBuilder} in the agent's working directory
 * (inherited). The captured stdout is returned as a {@link SafeString} so templates render it
 * raw. When {@code ttl} is set, the raw output is cached in this bean's
 * {@link ConcurrentHashMap} keyed by the normalized env+argv signature: identical exec calls
 * (e.g. the same token command in several dynamic headers) share one execution and one cache
 * entry, and the cache is lazily expired on read with no background sweeper. Because the cache
 * stores the <em>raw</em> output and stripping is applied at render time, the same cached entry
 * serves both {@code strip=true} and {@code strip=false} renders without re-executing.</p>
 *
 * <p>A non-zero exit status fails the render (an {@link IllegalStateException} carrying the
 * stderr tail): a failing exec is a configuration error and must surface loudly rather than
 * silently sending an empty header.</p>
 */
@ApplicationScoped
public class ExecTemplateHelper {

    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * The {@code exec} helper as a handlebars lambda. Registered next to {@code systemProperty}
     * wherever a template may call {@code {{exec ...}}} (provider dynamic headers, and any
     * custom template that wants it).
     *
     * <p>This bean is application-scoped: the TTL cache is shared by every render site of the
     * application (all providers and all templates), so the same token command is executed at
     * most once per TTL window regardless of how many providers/templates reference it.</p>
     *
     * @return the helper function.
     */
    public Function<HelperContext, Object> exec() {
        return this::apply;
    }

    private Object apply(final HelperContext context) {
        final var rawArgs = context.args();
        // a bare helper call ({{exec}}) is a ThisHelperPart: the current data (a Map) is
        // passed as a single "this" argument, so it must not be treated as a command
        final var args = rawArgs.size() == 1 && rawArgs.get(0) instanceof Map ? List.of() : rawArgs;
        if (args.isEmpty()) {
            throw new IllegalArgumentException("exec expects at least one argument (the command)");
        }
        final var env = new HashMap<String, String>();
        long ttlSeconds = 0;
        var strip = true;
        var commandStart = -1;
        for (int i = 0; i < args.size(); i++) {
            final var arg = String.valueOf(args.get(i));
            if (arg.startsWith("exec_options=")) {
                final var options = arg.substring("exec_options=".length());
                for (final var option : options.split(",")) {
                    if (option.isBlank()) {
                        continue;
                    }
                    final var eq = option.indexOf('=');
                    if (eq <= 0) {
                        throw new IllegalArgumentException(
                                "Invalid exec_options entry '" + option + "' (expected key=value)");
                    }
                    final var key = option.substring(0, eq).trim();
                    final var value = option.substring(eq + 1).trim();
                    switch (key) {
                        case "ttl" -> {
                            try {
                                ttlSeconds = Long.parseLong(value);
                            } catch (final NumberFormatException e) {
                                throw new IllegalArgumentException("Invalid exec_options ttl '" + value + "'", e);
                            }
                            if (ttlSeconds < 0) {
                                throw new IllegalArgumentException("exec_options ttl must be >= 0, got " + ttlSeconds);
                            }
                        }
                        case "strip" -> strip = !"false".equalsIgnoreCase(value);
                        default -> throw new IllegalArgumentException("Unknown exec_options key '" + key + "'");
                    }
                }
            } else if (commandStart < 0 && arg.contains("=") && !arg.startsWith("=")) {
                final var eq = arg.indexOf('=');
                env.put(arg.substring(0, eq), arg.substring(eq + 1));
            } else {
                commandStart = i;
                break;
            }
        }
        if (commandStart < 0) {
            throw new IllegalArgumentException("exec expects a command after the environment entries");
        }
        final var command = new ArrayList<String>(args.size() - commandStart);
        for (int i = commandStart; i < args.size(); i++) {
            command.add(String.valueOf(args.get(i)));
        }
        final var key = key(command, env);
        final var now = System.nanoTime();
        if (ttlSeconds > 0) {
            final var cached = cache.get(key);
            if (cached != null && cached.expiresAtNanos() > now) {
                return render(cached.output(), strip);
            }
        }
        final var output = run(command, env);
        if (ttlSeconds > 0) {
            cache.put(key, new Cached(output, now + TimeUnit.SECONDS.toNanos(ttlSeconds)));
        }
        return render(output, strip);
    }

    private static Object render(final String raw, final boolean strip) {
        return new SafeString(strip ? raw.strip() : raw);
    }

    /** One cached execution: the raw captured stdout and when it expires (nanos). */
    private record Cached(String output, long expiresAtNanos) {}

    /** A stable cache key: the argv and the sorted env entries, so reordered env is one entry. */
    private static String key(final List<String> command, final Map<String, String> env) {
        final var sorted = new ArrayList<>(env.entrySet());
        sorted.sort(Comparator.comparing(Map.Entry::getKey));
        final var sb = new StringBuilder();
        for (final var e : sorted) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\u0001');
        }
        sb.append('\u0002');
        for (final var arg : command) {
            sb.append(arg).append('\u0001');
        }
        return sb.toString();
    }

    private static String run(final List<String> command, final Map<String, String> env) {
        final ProcessBuilder pb;
        try {
            pb = new ProcessBuilder(command);
        } catch (final RuntimeException e) {
            throw new IllegalArgumentException("Invalid command " + command + ": " + e.getMessage(), e);
        }
        pb.environment().putAll(env);
        final Process process;
        try {
            process = pb.start();
        } catch (final IOException e) {
            throw new IllegalStateException("Cannot start " + command.get(0) + ": " + e.getMessage(), e);
        }
        final var out = new StringWriter();
        final var err = new StringWriter();
        final var outThread = Thread.ofVirtual().start(() -> {
            try (final var reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                reader.transferTo(out);
            } catch (final IOException e) {
                // ignored: the exit status below is the source of truth
            }
        });
        // the stderr is consumed concurrently on a virtual thread so a chatty command
        // cannot wedge the process on a full pipe
        final var errThread = Thread.ofVirtual().start(() -> {
            try (final var reader =
                    new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                reader.transferTo(err);
            } catch (final IOException e) {
                // ignored
            }
        });
        try {
            final var exit = process.waitFor();
            outThread.join();
            errThread.join();
            if (exit != 0) {
                var message = "exec failed (" + exit + ") for " + command.get(0);
                if (!err.toString().isBlank()) {
                    final var tail = err.toString().strip();
                    message += ": " + (tail.length() > 500 ? tail.substring(0, 497) + "..." : tail);
                }
                throw new IllegalStateException(message);
            }
            return out.toString();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("Interrupted while running " + command.get(0), e);
        }
    }
}
