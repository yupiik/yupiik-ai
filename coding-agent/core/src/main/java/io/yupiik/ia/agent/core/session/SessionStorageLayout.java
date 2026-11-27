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
package io.yupiik.ia.agent.core.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Single source of truth for the on-disk storage layout:
 *
 * <ul>
 *   <li>new layout: one folder per session {@code <storageHome>/sessions/<id>/} holding the session
 *       JSONL ({@code <id>.jsonl}, gzipped in place to {@code <id>.jsonl.gz}) and the session-tied
 *       artifacts ({@code todos.json}, {@code plan.md}, {@code snapshot.snap});</li>
 *   <li>legacy layout (still read for backward compatibility): flat
 *       {@code <storageHome>/sessions/<id>.jsonl}, root {@code todos-<key>.json},
 *       {@code plans/<title>-<id>.plan.md} and {@code snapshots/<id>.snap}.</li>
 * </ul>
 *
 * <p>The folder layout applies to any session id; the legacy sidecar locations are keyed
 * differently (all at the storage root), so only ids matching the generated session shape
 * ({@code s}<em>hex</em>) migrate there - anything else keeps the legacy file.</p>
 */
public final class SessionStorageLayout {
    private static final Pattern SESSION_ID = Pattern.compile("s[0-9a-f]+");

    private SessionStorageLayout() {
        // no-op
    }

    /**
     * @param id candidate session id.
     * @return {@code true} when the id has the generated session shape ({@code s} + lowercase
     *         hex, e.g. {@code s1a0e47e830a}) and can use the folder layout.
     */
    public static boolean isGeneratedSessionId(final String id) {
        return id != null && SESSION_ID.matcher(id).matches();
    }

    /** @return the folder owning all state of a session ({@code <storageHome>/sessions/<id>}). */
    public static Path sessionDir(final Path storageHome, final String id) {
        return storageHome.resolve("sessions").resolve(id);
    }

    /**
     * @return {@code true} when any session data exists for {@code id}, in the folder or the
     *         legacy flat layout ({@code sessions/<id>.jsonl[.gz]}).
     */
    public static boolean hasSessionData(final Path storageHome, final String id) {
        final var sessions = storageHome.resolve("sessions");
        return Files.isDirectory(sessions.resolve(id))
                || Files.isRegularFile(sessions.resolve(id + ".jsonl"))
                || Files.isRegularFile(sessions.resolve(id + ".jsonl.gz"));
    }

    /**
     * Removes root-level session artifacts whose session is gone (best-effort, never throws):
     * {@code todos-<key>.json} files and {@code snapshots/<id>.snap} files whose generated
     * session id has no session data left anywhere. The shared {@code todos.json} is never
     * touched.
     */
    public static void sweepOrphans(final Path storageHome) {
        try (final var files = Files.list(storageHome)) {
            for (final var f : files.toList()) {
                final var name = f.getFileName().toString();
                if (name.startsWith("todos-") && name.endsWith(".json")) {
                    final var key = name.substring("todos-".length(), name.length() - ".json".length());
                    if (isGeneratedSessionId(key) && !hasSessionData(storageHome, key)) {
                        Files.deleteIfExists(f);
                    }
                }
            }
        } catch (final IOException ignored) {
            // best-effort sweep, never breaks startup
        }
        final var snapshots = storageHome.resolve("snapshots");
        if (Files.isDirectory(snapshots)) {
            try (final var files = Files.list(snapshots)) {
                for (final var f : files.toList()) {
                    final var name = f.getFileName().toString();
                    if (name.endsWith(".snap")) {
                        final var id = name.substring(0, name.length() - ".snap".length());
                        if (isGeneratedSessionId(id) && !hasSessionData(storageHome, id)) {
                            Files.deleteIfExists(f);
                        }
                    }
                }
            } catch (final IOException ignored) {
                // best-effort sweep, never breaks startup
            }
        }
    }
}
