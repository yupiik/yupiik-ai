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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves which shell binary the {@link BashTool} launches with {@code -c}. The tool is licensed as a "bash"
 * tool, so the model is free to rely on bash-isms (PIPESTATUS, ${VAR:-x}, [[ ]], =~ ...). Under the historical
 * POSIX {@code /bin/sh} - which is dash on Debian/Ubuntu - those fail with e.g. {@code sh: 1: Bad substitution};
 * this resolver lets a real bash win by default ({@code auto}) and keeps a portable {@code /bin/sh} fallback
 * for environments without bash (alpine, minimal containers, Windows without Git Bash/WSL).
 *
 * <p>A pinned value ({@code bash}/{@code sh}/{@code dash}) is honoured as-is when found; otherwise (or when
 * {@code auto} finds no bash) it falls back to {@code /bin/sh} and reports the deviation so the caller can
 * surface it. Resolution is computed once and cached (the shell does not change mid-process).</p>
 */
public final class ShellResolver {
    private static final Path SH = Path.of("/bin/sh");

    private final String configured;
    private final boolean auto;
    private volatile Resolved resolved;

    public ShellResolver(final String configured) {
        this.configured = configured == null ? "" : configured.strip().toLowerCase(Locale.ROOT);
        this.auto = this.configured.isEmpty() || "auto".equals(this.configured);
    }

    /** Testing convenience: a resolver pinned to {@code auto} detection. */
    public static ShellResolver auto() {
        return new ShellResolver("auto");
    }

    /** @return the shell binary to launch plus an optional notice when the resolved value deviated from the ask. */
    public Resolved resolve() {
        final var cached = resolved;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (resolved == null) {
                if (auto) {
                    final var bash = detectBash();
                    resolved = new Resolved(bash.orElse(SH), bash.isPresent() ? "" : noBashNotice());
                } else {
                    final var pinned = toPath(configured);
                    resolved = isExecutable(pinned)
                            ? new Resolved(pinned, "")
                            : new Resolved(SH, pinnedNotice(configured));
                }
            }
            return resolved;
        }
    }

    /** The resolved absolute path for diagnostics / {@code ProcessBuilder}. */
    public Path binary() {
        return resolve().binary();
    }

    private static String noBashNotice() {
        return "no bash found; using /bin/sh (bash-isms like ${PIPESTATUS[0]} are unavailable under dash - install"
                + " bash or set yca.tools.shell on a system that provides it)";
    }

    private static String pinnedNotice(final String wanted) {
        return "shell '" + wanted + "' not found; using /bin/sh (set yca.tools.shell to an available shell)";
    }

    /** @return the first well-known executable bash, {@code auto} only. */
    private static Optional<Path> detectBash() {
        for (final var candidate : wellKnownCandidates()) {
            if (isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Well-known bash locations: canonical absolute paths first, then {@code bash} via PATH. */
    private static List<Path> wellKnownCandidates() {
        return List.of(Path.of("/bin/bash"), Path.of("/usr/bin/bash"));
    }

    /** Resolves a configured value to a shell path: absolute/relative paths as-is, bare names via PATH. */
    private static Path toPath(final String value) {
        if (value.isEmpty() || value.indexOf('/') >= 0) {
            return Path.of(value);
        }
        // a bare name (bash/sh/dash): locate it on PATH like the OS would
        final var pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            final var sep = System.getProperty("path.separator", ":");
            for (final var dir : pathEnv.split(sep)) {
                final var candidate = Path.of(dir, value);
                if (isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        return Path.of(value); // unresolved: caller treats it as missing and falls back to /bin/sh
    }

    private static boolean isExecutable(final Path p) {
        return p != null && Files.isRegularFile(p) && Files.isExecutable(p);
    }

    /** The resolved binary plus an optional human notice when it deviated from what was asked. */
    public record Resolved(Path binary, String notice) {}
}
