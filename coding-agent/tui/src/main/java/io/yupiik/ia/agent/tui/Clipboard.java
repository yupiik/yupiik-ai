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

import io.yupiik.maven.knowledge.process.ProcessService;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort system clipboard writes with a fallback ladder: AWT system
 * clipboard first (the jline2 approach - works headlessly on macOS, Windows
 * and X11), then a native tool for Wayland/WSL setups. The caller always also
 * sends OSC 52 which covers ssh/tmux where no system channel is reachable.
 */
public final class Clipboard {
    /**
     * True when running in a GraalVM native image. GraalVM native-image does not
     * ship java.awt, so {@link Toolkit} is unreachable there and invoking it aborts
     * the process with a native failure (not a catchable exception). Native-image
     * sets {@code org.graalvm.nativeimage.imagecode} to "runtime" at run time and
     * leaves it absent in a JVM; reading that property touches no AWT class, so this
     * constant is safe to evaluate in both a JVM and a native image.
     */
    private static final boolean NATIVE_IMAGE = System.getProperty("org.graalvm.nativeimage.imagecode") != null;

    private Clipboard() {}

    /**
     * Copies the text and returns the name of the channel that worked
     * ('system clipboard', 'pbcopy', 'wl-copy', 'xclip' or 'clip.exe'),
     * or null when no system-level channel is available.
     *
     * @param processes the shared {@link ProcessService}; when non-null the native tool is tracked
     *                  (and disposed on timeout / JVM exit) instead of being left to a detached thread.
     */
    public static String copy(final String text, final ProcessService processes) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        if (!NATIVE_IMAGE) {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
                return "system clipboard";
            } catch (final Throwable ignore) {
                // headless JVM, Wayland-only session, restricted desktop: try native tools
            }
        }
        final var command = nativeCommand(System.getProperty("os.name", ""), System.getenv());
        if (command == null) {
            return null;
        }
        try {
            final var process = new ProcessBuilder(command).start();
            if (processes != null) {
                processes.track(process);
            }
            try (var stdin = process.getOutputStream()) {
                stdin.write(text.getBytes(StandardCharsets.UTF_8));
            }
            // synchronous bounded reap: never leave a wedged tool running, and never let it
            // outlive the JVM (the detached-thread version could race with process exit)
            if (process.isAlive()) {
                final var reaped = processes != null
                        ? processes.dispose(process)
                        : (process.waitFor(2, TimeUnit.SECONDS) ? process.exitValue() : null);
                if (reaped == null) {
                    return null;
                }
            }
            return command[0];
        } catch (final Exception ignore) {
            // tool missing or failed: caller falls back to OSC 52
            return null;
        }
    }

    /**
     * Resolves the native clipboard tool for the given OS/env, most specific first:
     * macOS pbcopy, Wayland wl-copy, WSL clip.exe, X11 xclip, else null.
     */
    static String[] nativeCommand(final String osName, final Map<String, String> env) {
        final var os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return new String[] {"pbcopy"};
        }
        if (nonBlank(env.get("WAYLAND_DISPLAY"))) {
            return new String[] {"wl-copy"};
        }
        if (nonBlank(env.get("WSL_DISTRO_NAME")) || nonBlank(env.get("WSLENV"))) {
            return new String[] {"clip.exe"};
        }
        if (nonBlank(env.get("DISPLAY"))) {
            return new String[] {"xclip", "-selection", "clipboard"};
        }
        return null;
    }

    private static boolean nonBlank(final String value) {
        return value != null && !value.isBlank();
    }
}
