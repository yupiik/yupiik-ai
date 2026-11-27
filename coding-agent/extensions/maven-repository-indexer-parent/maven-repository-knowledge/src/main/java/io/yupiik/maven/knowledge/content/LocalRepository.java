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
package io.yupiik.maven.knowledge.content;

import io.yupiik.maven.knowledge.Gav;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Navigates a local Maven repository layout directly (no resolver). Answers "is this GAV present and where",
 * and lists the artifact's own files.
 */
public record LocalRepository(Path root) {

    /**
     * Resolve a GAV to its {@code <a>/<v>} directory and the artifact files in it. Returns a
     * {@link ResolvedArtifact} with {@code present()==false} when the version directory is absent.
     */
    public ResolvedArtifact resolve(final Gav gav) {
        if (gav.version() == null || gav.version().isBlank()) {
            return new ResolvedArtifact(gav, versionDirectory(gav, latestVersion(gav)), List.of());
        }
        final var dir = versionDirectory(gav, gav.version());
        if (dir == null || !Files.isDirectory(dir)) {
            return new ResolvedArtifact(gav, null, List.of());
        }
        final var files = new ArrayList<Path>();
        try (final var stream = Files.list(dir)) {
            files.addAll(stream.toList());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        files.sort(Path::compareTo);
        return new ResolvedArtifact(gav, dir, files);
    }

    private Path versionDirectory(final Gav gav, final String version) {
        if (version == null) {
            return null;
        }
        return root.resolve(gav.groupId().replace('.', '/'))
                .resolve(gav.artifactId())
                .resolve(version);
    }

    /**
     * Highest available version directory for a GAV (lexical is not correct for numbers); we use the
     * newest directory by last-modified time as a reasonable proxy when only groupId:artifactId is given.
     */
    private String latestVersion(final Gav gav) {
        final var dir = root.resolve(gav.groupId().replace('.', '/')).resolve(gav.artifactId());
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (final var stream = Files.list(dir)) {
            return stream.filter(Files::isDirectory)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .sorted()
                    .reduce((a, b) -> b)
                    .orElse(null);
        } catch (final IOException e) {
            return null;
        }
    }
}
