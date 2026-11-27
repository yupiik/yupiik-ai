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
package io.yupiik.ia.agent.core.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared containment checks for file tools. The tools deliberately allow absolute paths
 * (an explicit caller choice, see {@link ToolContext#resolvePath(String, Path)}); what they
 * never allow is a path that normalizes or resolves outside the session project root - in
 * particular a symlink inside the project pointing at a file outside it, which the lexical
 * {@code startsWith} checks alone would miss.
 */
public final class ToolContainment {
    private ToolContainment() {
        // no-op
    }

    /**
     * Returns an error message when {@code path} escapes {@code projectDir} through a symlink
     * (the resolved real path is outside the real project root), or {@code null} when it is
     * contained. A missing file cannot be verified: only the lexical containment applies then
     * (callers owning the write-creation path apply their own lexical guard). When
     * {@code projectDir} is null no containment applies.
     */
    public static String symlinkEscapeError(final Path projectDir, final Path path) {
        if (projectDir == null || path == null) {
            return null;
        }
        final var base = projectDir.toAbsolutePath().normalize();
        try {
            if (!Files.exists(path)) {
                return null; // nothing to resolve yet; the lexical guard already ran
            }
            final var real = path.toRealPath();
            final var realBase = base.toRealPath();
            return real.startsWith(realBase)
                    ? null
                    : "path '" + path + "' resolves outside the session project directory";
        } catch (final IOException e) {
            // unreadable (permissions/vanishing file): refuse rather than guess
            return "path '" + path + "' cannot be verified as inside the session project directory";
        }
    }
}
