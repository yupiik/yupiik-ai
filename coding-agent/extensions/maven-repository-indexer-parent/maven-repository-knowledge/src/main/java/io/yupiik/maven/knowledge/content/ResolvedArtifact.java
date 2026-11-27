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
import java.nio.file.Path;
import java.util.List;

/**
 * Locates an artifact's files inside a local Maven repository layout
 * ({@code <repo>/<groupId-as-path>/<artifactId>/<version>/}). No resolver dependency: the layout is
 * navigated directly, which is exactly the shape of a local {@code ~/.m2/repository}.
 *
 * @param gav       coordinates to locate.
 * @param directory the {@code <artifactId>/<version>} directory holding the artifact files.
 * @param files     the artifact's own files (pom, jar and any classifier jars).
 */
public record ResolvedArtifact(Gav gav, Path directory, List<Path> files) {

    /**
     * True when the artifact is present at all (its version directory exists).
     */
    public boolean present() {
        return directory != null;
    }

    public Path pom() {
        return find(gav.artifactId() + "-" + gav.version(), ".pom");
    }

    public Path mainJar() {
        return find(gav.artifactId() + "-" + gav.version(), ".jar");
    }

    public Path sourcesJar() {
        return find(gav.artifactId() + "-" + gav.version() + "-sources", ".jar");
    }

    public Path javadocJar() {
        return find(gav.artifactId() + "-" + gav.version() + "-javadoc", ".jar");
    }

    private Path find(final String baseName, final String extension) {
        for (final var file : files) {
            final var fileName = file.getFileName().toString();
            if (fileName.equals(baseName + extension)) {
                return file;
            }
        }
        return null;
    }
}
