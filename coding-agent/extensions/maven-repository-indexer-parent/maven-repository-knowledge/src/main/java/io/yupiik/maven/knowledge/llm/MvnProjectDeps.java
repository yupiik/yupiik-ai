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
package io.yupiik.maven.knowledge.llm;

import io.yupiik.maven.knowledge.Gav;
import io.yupiik.maven.knowledge.process.ProcessService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Resolves the effective project dependencies by running the build tool, sharing the exact same Maven parsing
 * logic between every {@link KnowledgeLlm} implementation (yca and the MCP server) so it does not drift apart.
 *
 * <p>{@code mvn dependency:list} reports one line per artifact as {@code g:a:type:version:scope}, optionally
 * followed by a trailing suffix (eg {@code  -- module ... [auto]}), so the matching regex is only anchored at
 * the start. The run does not specify an {@code includeScope} so every scope (provided, compile, test, runtime,
 * ...) is resolved; the returned coordinates are de-duplicated and ordered from the most to the least relevant
 * ({@link DependencyScope#order()} - provided first, then compile, then test) so bounded consumers handle the
 * important artifacts first.</p>
 */
public final class MvnProjectDeps {
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern LOG_PREFIXES = Pattern.compile("^(\\[[^]]+]\\s*)+");

    private static final Pattern COORDINATE = Pattern.compile("^([^:]+):([^:]+):([^:]+):([^:]+):([^:]+)");

    private MvnProjectDeps() {
        // no-op
    }

    /**
     * Resolves the project dependencies, caching the result under the project tree at
     * {@code $projectDir/.yca/maven.dependency.list}, gated by the fingerprint (aggregate content hash) of the
     * project build files so {@code mvn} is only run when a build file actually changed.
     *
     * @param projectDir       the Maven project directory; when it is not an existing directory an empty list is
     *                         returned.
     * @param timeoutMs        how long to wait for {@code mvn} to complete before killing it.
     * @param localRepository  retained for backward compatibility with callers; the cache now lives under the
     *                         project tree and this parameter no longer controls its location.
     * @return the project's resolved, de-duplicated dependency coordinates ordered by {@link DependencyScope#order()},
     *         empty when Maven is not runnable, times out, fails or the directory is absent.
     */
    public static List<Gav> resolve(final Path projectDir, final long timeoutMs, final Path localRepository) {
        return resolve(projectDir, timeoutMs, localRepository, null);
    }

    /**
     * {@code resolve} with the project-local cache under {@code $projectDir/.yca}.
     *
     * @param projectDir the Maven project directory.
     * @param timeoutMs  how long to wait for {@code mvn} to complete before killing it.
     * @return the resolved coordinates (same semantics as {@link #resolve(Path, long, Path)}).
     */
    public static List<Gav> resolve(final Path projectDir, final long timeoutMs) {
        return resolve(projectDir, timeoutMs, null, null);
    }

    /**
     * Resolves the project dependencies, caching the result under the project tree at
     * {@code $projectDir/.yca/maven.dependency.list}, exactly like
     * {@link #resolve(Path, long, Path)} but handing every spawned {@code mvn} process to the
     * given {@link ProcessService} so interruptions and shutdowns can dispose the whole tree.
     *
     * @param projectDir       the Maven project directory; when it is not an existing directory an empty list is
     *                         returned.
     * @param timeoutMs        how long to wait for {@code mvn} to complete before killing it.
     * @param localRepository  retained for backward compatibility with callers; the cache now lives under the
     *                         project tree and this parameter no longer controls its location.
     * @param processes        the {@link ProcessService} to track spawned Maven processes with (may be {@code null}
     *                         for callers that do not need tree disposal).
     * @return the project's resolved, de-duplicated dependency coordinates ordered by {@link DependencyScope#order()},
     *         empty when Maven is not runnable, times out, fails or the directory is absent.
     */
    public static List<Gav> resolve(
            final Path projectDir, final long timeoutMs, final Path localRepository, final ProcessService processes) {
        if (projectDir == null || !Files.isDirectory(projectDir)) {
            return List.of();
        }
        // cache the (slow) dependency:list run under the project tree, gated by the fingerprint of all build
        // files so mvn only runs when a pom/script actually changed and the coordinate list stays portable.
        final var fingerprint = fingerprint(projectDir);
        final var cacheFile = cacheFile(projectDir);
        final var cached = readCache(cacheFile, fingerprint);
        if (cached != null) {
            return cached;
        }
        final var deps = resolveFromMaven(projectDir, timeoutMs, processes);
        if (deps.isEmpty()) {
            // dependency:list failed (build extension not resolvable offline, reactor incoherence, ...): a
            // project with zero resolved dependencies is implausible, so do not cache an empty failure and
            // let the next call retry.
            return List.of();
        }
        writeCache(cacheFile, fingerprint, deps);
        return deps;
    }

    private static List<Gav> resolveFromMaven(
            final Path projectDir, final long timeoutMs, final ProcessService processes) {
        final var isWindows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        final var mvnw = projectDir.resolve(isWindows ? "mvnw.cmd" : "mvnw");
        final var mvn = isWindows ? "mvn.cmd" : "mvn";
        // prefer the project's Maven wrapper when present (it pins the Maven/plugin versions the build expects),
        // otherwise fall back to a plain `mvn`. Windows `.cmd` wrappers must be launched through `cmd /c`.
        final var launcher = new ArrayList<String>();
        if (Files.isRegularFile(mvnw)) {
            if (isWindows) {
                launcher.addAll(List.of("cmd", "/c"));
            }
            launcher.add(mvnw.toString());
        } else {
            launcher.add(mvn);
        }
        // try offline first (fast when everything is cached); if the project's build extensions or plugins are
        // not yet in the local repo, offline resolution aborts before any goal runs, so fall back to online.
        var deps = runMaven(launcher, true, projectDir, timeoutMs, processes);
        if (deps.isEmpty()) {
            deps = runMaven(launcher, false, projectDir, timeoutMs, processes);
        }
        return deps;
    }

    /** Upper bound on the captured Maven output; beyond it the tail is dropped (the pipe still gets drained). */
    private static final int MAX_MAVEN_OUTPUT_CHARS = 2_000_000;

    private static List<Gav> runMaven(
            final List<String> launcher,
            final boolean offline,
            final Path projectDir,
            final long timeoutMs,
            final ProcessService processes) {
        final var command = new ArrayList<>(launcher);
        if (offline) {
            command.add("-o");
        }
        command.add("dependency:list");
        final var pb = new ProcessBuilder(command);
        pb.directory(projectDir.toFile());
        pb.redirectErrorStream(true);
        final Process process;
        try {
            process = pb.start();
        } catch (final IOException e) {
            return List.of();
        }
        if (processes != null) {
            processes.track(process);
        }
        // read the merged stream on a daemon thread with a capped buffer: the main thread polls the
        // deadline (so a wedged mvn that never exits cannot hang the read) and disposes the whole tree
        // on interrupt/timeout instead of leaving an orphaned mvn behind.
        final var output = new AtomicReference<String>();
        final var reader = new Thread(
                () -> {
                    try (final var in = process.getInputStream()) {
                        output.set(readCapped(in, MAX_MAVEN_OUTPUT_CHARS));
                    } catch (final IOException e) {
                        output.set("");
                    }
                },
                "mvn-dependency-list-reader");
        reader.setDaemon(true);
        reader.start();
        var finished = false;
        final var deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            final var remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            try {
                finished = process.waitFor(Math.clamp(remainingNanos / 1_000_000, 1, 100), TimeUnit.MILLISECONDS);
                if (finished) {
                    break;
                }
            } catch (final InterruptedException e) {
                dispose(process, processes);
                Thread.currentThread().interrupt();
                return List.of();
            }
        }
        if (!finished) {
            dispose(process, processes);
            return List.of();
        }
        try {
            reader.join(500);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (process.exitValue() != 0) {
            return List.of();
        }
        final var text = output.get();
        return parse(text == null ? "" : text);
    }

    /** Kills and reaps the process tree through the service when available, else plain destroy. */
    private static void dispose(final Process process, final ProcessService processes) {
        if (processes != null) {
            processes.dispose(process);
        } else if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    /**
     * Reads the stream into a capped buffer while still draining it to the end, so a huge or
     * endless output cannot blow the heap nor block the child on a full pipe.
     */
    private static String readCapped(final InputStream in, final int maxChars) throws IOException {
        final var out = new StringBuilder(Math.min(maxChars, 4096));
        final byte[] buffer = new byte[8192];
        var overflow = false;
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (!overflow) {
                final var chunk = new String(buffer, 0, n, StandardCharsets.UTF_8);
                if (out.length() + chunk.length() > maxChars) {
                    out.append(chunk, 0, Math.max(0, maxChars - out.length()));
                    overflow = true;
                } else {
                    out.append(chunk);
                }
            }
        }
        if (overflow) {
            out.append("\n... output truncated");
        }
        return out.toString();
    }

    // -- dependency-resolution caching (keyed by the project build files fingerprint) --

    /** Cache file layout: {@code # fingerprint=<hex>} header then one coordinate ({@code g:a:v}) per line. */
    private static Path cacheFile(final Path projectDir) {
        return projectDir.resolve(".yca").resolve("maven.dependency.list");
    }

    /** Read the cached coordinates only when the stored build-file fingerprint matches the current one. */
    private static List<Gav> readCache(final Path cacheFile, final String fingerprint) {
        try {
            if (!Files.isRegularFile(cacheFile)) {
                return null;
            }
            final var lines = Files.readAllLines(cacheFile, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).equals("# fingerprint=" + fingerprint)) {
                return null;
            }
            final var out = new ArrayList<Gav>();
            for (final var line : lines.subList(1, lines.size())) {
                final var stripped = line.strip();
                if (!stripped.isBlank()) {
                    try {
                        out.add(Gav.parse(stripped));
                    } catch (final IllegalArgumentException ignored) {
                        // ignore a corrupt line
                    }
                }
            }
            return List.copyOf(out);
        } catch (final IOException e) {
            return null;
        }
    }

    private static void writeCache(final Path cacheFile, final String fingerprint, final List<Gav> deps) {
        try {
            final var sb = new StringBuilder("# fingerprint=");
            sb.append(fingerprint).append('\n');
            for (final var gav : deps) {
                sb.append(gav.coordinate()).append('\n');
            }
            Files.createDirectories(cacheFile.getParent());
            Files.writeString(cacheFile, sb.toString(), StandardCharsets.UTF_8);
        } catch (final IOException ignored) {
            // a cache write failure is not fatal; the caller can always re-run dependency:list
        }
    }

    /** SHA-256 hex of the concatenated content of every project build file (poms + gradle + catalogs). */
    static String fingerprint(final Path projectDir) {
        final var md = digest();
        try (final var stream = Files.walk(projectDir)) {
            final var files = stream.filter(Files::isRegularFile)
                    .filter(path -> isBuildFile(path.getFileName().toString()))
                    .sorted()
                    .toList();
            for (final var file : files) {
                md.update(file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(Files.readAllBytes(file));
                md.update((byte) 0);
            }
        } catch (final IOException e) {
            // fall through to the empty-document fingerprint
        }
        final var bytes = md.digest();
        final var sb = new StringBuilder(bytes.length * 2);
        for (final var b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static boolean isBuildFile(final String name) {
        return name.endsWith("pom.xml")
                || name.equals("build.gradle")
                || name.equals("build.gradle.kts")
                || name.equals("settings.gradle")
                || name.equals("settings.gradle.kts")
                || name.equals("libs.versions.toml");
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<Gav> sorted(final Map<Gav, DependencyScope> byCoordinate) {
        final var out = new ArrayList<>(byCoordinate.keySet());
        out.sort(Comparator.comparingInt(g -> byCoordinate.get(g).order()));
        return List.copyOf(out);
    }

    /** Parses {@code mvn dependency:list} output into de-duplicated coordinates ordered by scope priority. */
    static List<Gav> parse(final String output) {
        final Map<Gav, DependencyScope> byCoordinate = new LinkedHashMap<>();
        for (final var line : LINE_BREAK.split(output)) {
            final var m = COORDINATE.matcher(stripLogPrefix(line));
            if (!m.find() || !"jar".equals(m.group(3))) {
                continue;
            }
            final var gav = Gav.of(m.group(1), m.group(2), m.group(4));
            // the scope can be followed by the trailing Maven suffix (eg " -- module ... [auto]")
            final var scope = DependencyScope.parse(WHITESPACE.split(m.group(5), 2)[0]);
            // keep the most relevant scope when a coordinate is declared in several scopes
            byCoordinate.merge(gav, scope, (a, b) -> a.order() <= b.order() ? a : b);
        }
        return sorted(byCoordinate);
    }

    /** Strips the Maven log prefixes (eg {@code [INFO]    }) so the coordinate starts the line. */
    private static String stripLogPrefix(final String line) {
        return LOG_PREFIXES.matcher(line).replaceAll("").trim();
    }
}
