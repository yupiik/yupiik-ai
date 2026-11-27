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
package io.yupiik.maven.knowledge.fingerprint;

import io.yupiik.maven.knowledge.Gav;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds per-artifact fingerprints from the project build files so the pipeline can re-index only the
 * dependencies whose declaration changed. A dependency's fingerprint is the SHA-256 of the concatenated
 * content of every build file that mentions that dependency's {@code groupId:artifactId}. Files that do
 * not mention an artifact leave that artifact's fingerprint untouched, so adding a dependency to a pom
 * re-generates only that dependency while the already-indexed siblings stay untouched.
 *
 * <p>The whole set of build files is still part of the fingerprint signature for each artifact
 * (via the shared file order), so touching <em>any</em> declaring file of an artifact invalidates its
 * knowledge and forces a re-index on the next run.</p>
 */
public final class ProjectFingerprint {

    private ProjectFingerprint() {
        // no-op
    }

    /**
     * Compute the SHA-256 (hex) fingerprint for the given candidates over the given build files. Returns
     * a map with an entry for every candidate; the fingerprint is null when no build file references the
     * artifact (kept deterministic and null for absolute-missing) so the caller can decide whether to
     * track it.
     */
    public static Map<Gav, String> of(final List<Gav> candidates, final List<Path> buildFiles) {
        final var out = new LinkedHashMap<Gav, String>();
        for (final var gav : candidates) {
            out.put(gav, of(gav, buildFiles));
        }
        return out;
    }

    /**
     * The fingerprint of a single artifact: SHA-256 of the concatenated content of the build files that
     * mention its {@code groupId:artifactId}. {@code null} when no build file references the artifact
     * (meaning there is nothing declaration-scoped to re-trigger on, so it is not tracked).
     */
    public static String of(final Gav gav, final List<Path> buildFiles) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            boolean touched = false;
            for (final var file : sorted(buildFiles)) {
                if (Files.isRegularFile(file) && mentions(file, gav)) {
                    collectFile(digest, file);
                    touched = true;
                }
            }
            return touched ? toHex(digest.digest()) : null;
        } catch (final NoSuchAlgorithmException | IOException e) {
            return null;
        }
    }

    /**
     * A project-wide fingerprint covering all the given build files; changes whenever <em>any</em> of them
     * changes. Useful as the coarse "build changed" trigger, in addition to the per-artifact fingerprints.
     */
    public static String ofAll(final List<Path> buildFiles) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            for (final var file : sorted(buildFiles)) {
                if (Files.isRegularFile(file)) {
                    collectFile(digest, file);
                }
            }
            return toHex(digest.digest());
        } catch (final NoSuchAlgorithmException | IOException e) {
            return null;
        }
    }

    private static boolean mentions(final Path file, final Gav gav) throws IOException {
        final var content = Files.readString(file, StandardCharsets.UTF_8);
        return content.contains(gav.artifactId())
                // a bare dependency on a sibling project module is often only the artifactId (no groupId),
                // so mention by artifactId is sufficient to tie the knowledge to that declaring file
                || content.contains("<artifactId>" + gav.artifactId() + "</artifactId>");
    }

    private static List<Path> sorted(final List<Path> files) {
        final var copy = new ArrayList<Path>(files);
        copy.sort(Path::compareTo);
        return copy;
    }

    private static void collectFile(final MessageDigest digest, final Path file) throws IOException {
        final var bytes = Files.readAllBytes(file);
        digest.update(bytes);
        // separate files so content boundaries (abc + def) do not collide with (a + bcdef)
        digest.update((byte) 0);
    }

    private static String toHex(final byte[] bytes) {
        final var sb = new StringBuilder(bytes.length * 2);
        for (final var b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
