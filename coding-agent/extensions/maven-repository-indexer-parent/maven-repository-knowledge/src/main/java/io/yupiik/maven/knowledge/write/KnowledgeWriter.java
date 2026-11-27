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
package io.yupiik.maven.knowledge.write;

import io.yupiik.maven.knowledge.Gav;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Writes the generated per-artifact knowledge as a flat set of {@code -knowledge-*.md} files directly in the
 * Maven version directory ({@code <repo>/<g>/<a>/<v>/}), each prefixed with the artifact's
 * {@code <artifactId>-<version>} so they read as proper Maven-convention sibling artifacts:
 * <ul>
 *     <li>{@code <a>-<v>-knowledge-index.md} - the entry point (marker + discovery).</li>
 *     <li>{@code <a>-<v>-knowledge-api.md} - bytecode-derived public API (entry points + classes + methods).</li>
 *     <li>{@code <a>-<v>-knowledge-<kind>.md} - one file per acquired content kind (sources chunked).</li>
 *     <li>{@code <a>-<v>-knowledge-skill.md} / {@code -knowledge-skill-<short>.md} - optional LLM/hand-written skills.</li>
 * </ul>
 * The presence of {@code -knowledge-index.md} is the "already indexed" marker; there is no {@code yca/} wrapper.
 */
public final class KnowledgeWriter {

    public static final String SUFFIX = "-knowledge";

    private final Path localRepository;

    public KnowledgeWriter(final Path localRepository) {
        this.localRepository = localRepository;
    }

    private Path versionDir(final Gav gav) {
        return localRepository
                .resolve(gav.groupId().replace('.', '/'))
                .resolve(gav.artifactId())
                .resolve(gav.version());
    }

    /**
     * {@code <repo>/<g>/<a>/<v>/<a>-<v>-knowledge-index.md}.
     */
    public Path indexFile(final Gav gav) {
        return versionDir(gav).resolve(filePrefix(gav) + SUFFIX + "-index.md");
    }

    /**
     * Shared prefix for all knowledge files of an artifact: {@code <artifactId>-<version>}.
     */
    public static String filePrefix(final Gav gav) {
        return gav.artifactId() + "-" + gav.version();
    }

    /**
     * Whether an artifact already has knowledge (index file present). Releases are immutable: once present it is
     * not regenerated unless force is requested.
     */
    public boolean alreadyIndexed(final Gav gav) {
        return Files.isRegularFile(indexFile(gav));
    }

    /**
     * {@code -knowledge-<short>.md} sibling under the artifact version dir for a short name (no extension).
     */
    public Path sibling(final Gav gav, final String shortName) {
        return versionDir(gav).resolve(filePrefix(gav) + SUFFIX + "-" + shortName + ".md");
    }

    /**
     * Write a text file under the artifact version dir (creating directories as needed). Returns the path.
     */
    public Path write(final Gav gav, final String shortName, final String content) {
        final var file = sibling(gav, shortName);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content == null ? "" : content);
            return file;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@code <repo>/<g>/<a>/<v>/<a>-<v>-codegraph.bin}: the compact binary code-graph sidecar for the artifact's
     * main jar, written at index time so the runtime can mmap it without re-parsing bytecode.
     */
    public Path codeGraphFile(final Gav gav) {
        return versionDir(gav).resolve(filePrefix(gav) + "-codegraph.bin");
    }

    /**
     * Atomically write the compact binary code-graph sidecar ({@code -codegraph.bin}) under the artifact version
     * dir. A zero-length payload removes the file. Returns the written path (or {@code null} when removed).
     */
    public Path writeCodeGraph(final Gav gav, final byte[] bytes) {
        final var file = codeGraphFile(gav);
        try {
            if (bytes == null || bytes.length == 0) {
                Files.deleteIfExists(file);
                return null;
            }
            Files.createDirectories(file.getParent());
            final var tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return file;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The sidecar file holding the build-declaration fingerprint captured when the artifact was last indexed:
     * {@code <repo>/<g>/<a>/<v>/.maven-knowledge-fingerprint}. Used to re-index only dependencies whose declaring
     * build files changed.
     */
    public Path fingerprintPath(final Gav gav) {
        return versionDir(gav).resolve(".maven-knowledge-fingerprint");
    }

    public String readFingerprint(final Gav gav) {
        final var file = fingerprintPath(gav);
        try {
            return Files.isRegularFile(file) ? Files.readString(file).strip() : null;
        } catch (final IOException e) {
            return null;
        }
    }

    public void writeFingerprint(final Gav gav, final String fingerprint) {
        final var file = fingerprintPath(gav);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, fingerprint == null ? "" : fingerprint);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Every {@code -knowledge-*.md} file present under the artifact version dir (sorted), used to build the
     * {@code ## Knowledge} index and by tooling to enumerate siblings.
     */
    public List<Path> knowledgeFiles(final Gav gav) {
        final var dir = versionDir(gav);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (final var stream = Files.list(dir)) {
            final var prefix = filePrefix(gav) + SUFFIX;
            return stream.filter(f ->
                            Files.isRegularFile(f) && f.getFileName().toString().startsWith(prefix))
                    .sorted()
                    .toList();
        } catch (final IOException e) {
            return List.of();
        }
    }
}
