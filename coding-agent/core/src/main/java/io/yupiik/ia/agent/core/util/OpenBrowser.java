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
package io.yupiik.ia.agent.core.util;

import java.awt.Desktop;
import java.net.URI;
import java.util.Locale;

/**
 * Best-effort helper to open a URL in the system browser, shared by the TUI and CLI console
 * frontends (and reused by the web command for its startup tab). It never throws: opening the
 * browser is a convenience that must not fail the surrounding command, so any failure is swallowed.
 */
public final class OpenBrowser {

    private OpenBrowser() {
        // utility
    }

    /**
     * Opens {@code url} in the user's default browser, best effort. When {@code java.awt.Desktop}
     * is available (a desktop JVM, not a GraalVM native image) it is preferred; otherwise the
     * portable OS command ({@code rundll32} on Windows, {@code open} on macOS, {@code xdg-open}
     * elsewhere) is spawned. Any failure is silently ignored.
     *
     * @param url the URL/Scheme to open.
     */
    public static void open(final String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            if (System.getProperty("org.graalvm.nativeimage.imagecode") == null && Desktop.isDesktopSupported()) {
                final var desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(URI.create(url));
                    return;
                }
            }
            final var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            final var cmd = os.contains("win")
                    ? new String[] {"rundll32", "url.dll,FileProtocolHandler", url}
                    : os.contains("mac") ? new String[] {"open", url} : new String[] {"xdg-open", url};
            Runtime.getRuntime().exec(cmd);
        } catch (final Throwable ignored) {
            // best-effort: never fail the command because the browser could not open
        }
    }
}
