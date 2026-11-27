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
package io.yupiik.ia.agent.core.tools.builtin.bashoverride;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.BashOverridesConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/**
 * Recognizes and runs a safe, read-only survey bash command through the in-process {@link JavaCommand}
 * reimplementations instead of a real shell. Only commands made entirely of supported {@link JavaCommand}
 * segments chained by {@code &&}/{@code ;} (no pipe, no substitution, no write redirect, paths resolved against
 * the project root / tracked cwd) are rewritten; anything else returns {@link Optional#empty()} so the caller
 * falls back to the real shell (bash when available, /bin/sh otherwise). Output is byte-faithful to the real
 * command for the supported subset, with truncation at the output cap as the only permitted deviation.
 */
@ApplicationScoped
public class BashOverride {
    private final BashOverridesConfiguration config;
    private final int projectOutputCap;
    private final JavaCommandRegistry registry;
    private final ExecutorService executor;

    public BashOverride(
            final JavaCommandRegistry registry,
            final CodingAgentConfiguration configuration,
            final ExecutorService executor) {
        this.registry = registry == null ? new JavaCommandRegistry(List.of()) : registry;
        this.config = configuration == null
                        || configuration.tools() == null
                        || configuration.tools().bashOverrides() == null
                ? new BashOverridesConfiguration(true, 0, List.of())
                : configuration.tools().bashOverrides();
        this.projectOutputCap = configuration == null || configuration.tools() == null
                ? 65_536
                : configuration.tools().maxOutputChars();
        this.executor = executor;
    }

    /** Testing convenience: explicit overrides config over the injected one; uses the bundle cap when max is 0. */
    BashOverride(
            final JavaCommandRegistry registry,
            final BashOverridesConfiguration overrides,
            final CodingAgentConfiguration configuration,
            final ExecutorService executor) {
        this.registry = registry == null ? new JavaCommandRegistry(List.of()) : registry;
        this.config = overrides == null ? new BashOverridesConfiguration(true, 0, List.of()) : overrides;
        this.projectOutputCap = configuration == null || configuration.tools() == null
                ? 65536
                : configuration.tools().maxOutputChars();
        this.executor = executor;
    }

    /** Testing convenience: no injected registry/configuration. */
    BashOverride() {
        this.registry = new JavaCommandRegistry(List.of());
        this.config = new BashOverridesConfiguration(true, 0, List.of());
        this.projectOutputCap = 65536;
        this.executor = null;
    }

    /** @return the rewritten (capped) output, or empty when the command must run in real bash. */
    public Optional<String> tryRewrite(final String command, final Path projectDir) {
        Objects.requireNonNull(projectDir, "workdir");
        if (!config.enabled() || command == null || command.isBlank()) {
            return Optional.empty();
        }
        final var segments = Tokenizer.segments(command);
        if (segments == null) {
            return Optional.empty();
        }
        final var out = new StringBuilder();
        var cwd = projectDir;

        // Resolve every segment up-front. A `cd` makes the chain stateful (cwd advances) so it forces the
        // sequential path; otherwise all segments are independent read-only commands and can be run in parallel.
        var parallelSafe = true;
        final var resolved = new ArrayList<ResolvedSegment>(segments.size());
        for (final var segment : segments) {
            final var tokens = Tokenizer.tokens(segment);
            if (tokens == null || tokens.isEmpty()) {
                return Optional.empty();
            }
            final var head = tokens.get(0);
            if ("cd".equals(head)) {
                parallelSafe = false;
                if (!config.keeps("cd") || tokens.size() != 2) {
                    return Optional.empty();
                }
                final var target = resolve(cwd, tokens.get(1));
                if (!Files.isDirectory(target)) {
                    return Optional.empty();
                }
                resolved.add(new ResolvedSegment(null, head, List.of(), cwd, target));
                cwd = target;
                continue;
            }
            final var impl = registry.get(head);
            if (impl.isEmpty() || !config.keeps(head)) {
                return Optional.empty();
            }
            final var args = expandGlobs(cwd, tokens.subList(1, tokens.size()));
            if (args == null) {
                return Optional.empty(); // a glob had no in-project match -> let the shell do it
            }
            if (!impl.get().supports(cwd, args)) {
                return Optional.empty(); // unsupported options/args -> never approximate
            }
            resolved.add(new ResolvedSegment(impl.get(), head, args, cwd, null));
        }

        final var cap = cap();
        if (parallelSafe && resolved.size() > 1 && executor != null) {
            // all read-only, no stateful cd, and an executor is available: run each command in parallel
            // (virtual threads) and collect the results in command order.
            final var futures = new ArrayList<Future<String>>(resolved.size());
            for (final var rs : resolved) {
                futures.add(executor.submit(() -> runSegment(rs, cap)));
            }
            for (final var future : futures) {
                try {
                    out.append(future.get());
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                } catch (final ExecutionException e) {
                    return Optional.empty(); // a reimpl failed: fall back is handled by returning empty
                }
            }
            return Optional.of(out.toString());
        }

        for (final var rs : resolved) {
            if (rs.cdTarget() != null) {
                continue; // cd already advanced cwd during resolution
            }
            out.append(runSegment(rs, cap));
        }
        return Optional.of(out.toString());
    }

    /** Runs one non-cd segment into its own buffer, per-command capped, and returns the text. */
    private static String runSegment(final ResolvedSegment rs, final int cap) {
        final var segmentBuffer = new StringBuilder();
        rs.command().run(rs.cwd(), rs.args(), segmentBuffer);
        if (segmentBuffer.length() > cap) {
            segmentBuffer.setLength(cap);
            segmentBuffer
                    .append("\n... (")
                    .append(rs.head())
                    .append(" truncated at ")
                    .append(cap)
                    .append(" chars)");
        }
        return segmentBuffer.toString();
    }

    /** A resolved (parsed, glob-expanded, validated) segment ready to run. */
    private record ResolvedSegment(JavaCommand command, String head, List<String> args, Path cwd, Path cdTarget) {}

    private int cap() {
        return config.maxOutputChars() > 0 ? config.maxOutputChars() : projectOutputCap;
    }

    private static Path resolve(final Path cwd, final String arg) {
        return Paths.resolve(cwd, arg);
    }

    /** Expands simple {@code *}/ {@code ?} globs in path operands within the (dir component of the) pattern. */
    private static List<String> expandGlobs(final Path cwd, final List<String> args) {
        var changed = false;
        final var out = new ArrayList<String>(args.size());
        for (final var a : args) {
            if (a.startsWith("-") || (a.indexOf('*') < 0 && a.indexOf('?') < 0)) {
                out.add(a);
                continue;
            }
            changed = true;
            final var matches = glob(cwd, a);
            if (matches.isEmpty()) {
                return null; // nothing matched: real bash would keep the literal pattern -> fall back
            }
            out.addAll(matches);
        }
        return changed ? out : args;
    }

    /** Resolves one glob pattern (single path component or a plain path ending in a glob component) to matches. */
    private static List<String> glob(final Path cwd, final String pattern) {
        final var slash = Math.max(pattern.lastIndexOf('/'), 0);
        final var dirPart = pattern.substring(0, slash);
        final var filePart = pattern.substring(pattern.lastIndexOf('/') < 0 ? 0 : pattern.lastIndexOf('/') + 1);
        final Path dir = dirPart.isEmpty()
                ? cwd
                : (Path.of(dirPart).isAbsolute() ? Path.of(dirPart) : cwd.resolve(dirPart)).normalize();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        final var regex = globRegex(filePart);
        final var matches = new ArrayList<String>();
        try (final DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (final var p : stream) {
                if (regex.matcher(p.getFileName().toString()).matches()) {
                    matches.add(
                            pattern.isEmpty() || pattern.startsWith("/")
                                    ? p.toString()
                                    : dir.relativize(p).toString());
                }
            }
        } catch (final Exception e) {
            return List.of();
        }
        return matches;
    }

    private static Pattern globRegex(final String glob) {
        final var sb = new StringBuilder();
        for (final var c : glob.toCharArray()) {
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(sb.toString());
    }
}
