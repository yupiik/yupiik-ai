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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Best-effort detection of the OS light/dark preference for {@code yca.tui.theme.name=auto}.
 *
 * <p>The detection is a strict best-effort: every probe runs with a short timeout and any
 * failure, missing tool or unknown value yields an empty result so the caller falls back to
 * its normal precedence chain. The probes are platform specific:
 * macOS reads {@code AppleInterfaceStyle}, Windows the {@code AppsUseLightTheme} registry
 * value, Linux tries GNOME ({@code gsettings}), KDE ({@code kreadconfig5}/{@code kreadconfig6}),
 * then the GTK settings file, and any platform finally falls back to the {@code COLORFGBG}
 * terminal variable (xterm convention: background 0-7 is dark, 8-15 is light).
 *
 * <p>{@link #DETECTOR} is the extension point used by headless tests to stub the detection
 * without touching the process environment.
 */
public final class ThemeDetector {
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");
    private static final long TIMEOUT_MILLIS = 500L;

    /**
     * Extension point for tests (and only tests should replace it): the resolver used by
     * {@code yca.tui.theme.name=auto}. Defaults to the real {@link #detect()} probe.
     */
    public static volatile Supplier<Optional<String>> DETECTOR = ThemeDetector::detect;

    private ThemeDetector() {}

    /**
     * @return {@code light} or {@code dark} when the OS preference could be determined, empty otherwise
     */
    public static Optional<String> detect() {
        return detect(System.getProperty("os.name", ""), System.getenv(), ThemeDetector::run);
    }

    /**
     * Resolves the detected light/dark preference to a terminal background reference
     * color used to compute a translucent screen veil: near-white in light mode,
     * near-black otherwise (undetected defaults to dark terminals).
     *
     * @return {@code #f2f2f2} when the preference is light, {@code #0c0c0c} otherwise
     */
    public static String terminalBackground() {
        return "light".equalsIgnoreCase(DETECTOR.get().orElse("dark")) ? "#f2f2f2" : "#0c0c0c";
    }

    /** Package-private for tests: same cascade with an injected environment and command runner. */
    static Optional<String> detect(final String osName, final Map<String, String> env, final CommandRunner runner) {
        final var name = osName.toLowerCase(Locale.ROOT);
        Optional<String> detected = Optional.empty();
        if (name.contains("mac") || name.contains("darwin")) {
            detected = macOs(runner);
        } else if (name.contains("win")) {
            detected = windows(runner);
        } else if (name.contains("linux")) {
            detected = linux(env, runner);
        }
        if (detected.isEmpty()) {
            detected = colorFgBg(env);
        }
        return detected;
    }

    private static Optional<String> macOs(final CommandRunner runner) {
        // the key is absent in light mode, which `defaults` reports as a non-zero exit
        // code: treat an output containing "dark" as dark, any other clean probe
        // (missing key included) as light, and a failed probe (missing tool, timeout)
        // as undetermined
        return runner.run(List.of("defaults", "read", "-g", "AppleInterfaceStyle"))
                .map(result -> result.exitCode() == 0
                                && result.output().toLowerCase(Locale.ROOT).contains("dark")
                        ? Optional.of("dark")
                        : Optional.of("light"))
                .orElse(Optional.empty());
    }

    private static Optional<String> windows(final CommandRunner runner) {
        final var command = List.of(
                "reg",
                "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "/v",
                "AppsUseLightTheme");
        return runner.run(command)
                .map(result -> {
                    if (result.exitCode() != 0) {
                        return Optional.<String>empty();
                    }
                    final var output = result.output();
                    if (output.contains("0x0")) {
                        return Optional.of("dark");
                    }
                    if (output.contains("0x1")) {
                        return Optional.of("light");
                    }
                    return Optional.<String>empty();
                })
                .orElse(Optional.empty());
    }

    private static Optional<String> linux(final Map<String, String> env, final CommandRunner runner) {
        // GNOME (current schema), then KDE 5/6, then the GTK settings file
        final var gsettings = runner.run(List.of("gsettings", "get", "org.gnome.desktop.interface", "color-scheme"));
        if (gsettings.isPresent()) {
            final var output = gsettings.get().output().toLowerCase(Locale.ROOT);
            if (output.contains("prefer-dark")) {
                return Optional.of("dark");
            }
            if (output.contains("default") || output.contains("prefer-light")) {
                return Optional.of("light");
            }
        }
        for (final var tool : List.of("kreadconfig5", "kreadconfig6")) {
            final var kde =
                    runner.run(List.of(tool, "--file", "kdeglobals", "--group", "General", "--key", "ColorScheme"));
            if (kde.isPresent()
                    && kde.get().exitCode() == 0
                    && kde.get().output().toLowerCase(Locale.ROOT).contains("dark")) {
                return Optional.of("dark");
            }
        }
        try {
            final var home = env.getOrDefault("HOME", System.getProperty("user.home", ""));
            if (!home.isBlank()) {
                final var settings = Path.of(home, ".config", "gtk-3.0", "settings.ini");
                if (Files.isRegularFile(settings)) {
                    final var fromGtk = gtkFromSettings(Files.readString(settings));
                    if (fromGtk.isPresent()) {
                        return fromGtk;
                    }
                }
            }
        } catch (final Exception ignore) {
            // unreadable settings file: fall through to COLORFGBG
        }
        return Optional.empty();
    }

    /** Package-private for tests: parses a GTK settings.ini content. */
    static Optional<String> gtkFromSettings(final String content) {
        for (final var rawLine : LINE_BREAK.split(content)) {
            final var line = rawLine.strip();
            if (!line.startsWith("gtk-application-prefer-dark-theme")) {
                continue;
            }
            final var value = line.substring(line.indexOf('=') + 1).strip();
            if ("true".equalsIgnoreCase(value)) {
                return Optional.of("dark");
            }
            if ("false".equalsIgnoreCase(value)) {
                return Optional.of("light");
            }
        }
        return Optional.empty();
    }

    private static Optional<String> colorFgBg(final Map<String, String> env) {
        final var value = env.get("COLORFGBG");
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        final var separator = value.indexOf(';');
        if (separator < 0) {
            return Optional.empty();
        }
        try {
            final var background =
                    Integer.parseInt(value.substring(separator + 1).strip());
            if (background < 0 || background > 255) {
                return Optional.empty();
            }
            return Optional.of(background < 8 ? "dark" : "light");
        } catch (final NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<ProcessResult> run(final List<String> command) {
        try {
            final var process =
                    new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            final var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return Optional.of(new ProcessResult(output, process.exitValue()));
        } catch (final Exception e) {
            return Optional.empty();
        }
    }

    @FunctionalInterface
    interface CommandRunner {
        Optional<ProcessResult> run(List<String> command);
    }

    record ProcessResult(String output, int exitCode) {}
}
