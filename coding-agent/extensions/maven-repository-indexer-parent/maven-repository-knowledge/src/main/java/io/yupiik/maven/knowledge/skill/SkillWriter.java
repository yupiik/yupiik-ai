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
package io.yupiik.maven.knowledge.skill;

import io.yupiik.maven.knowledge.Gav;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes the per-artifact knowledge as a {@code SKILL.md} in the gold format consumed by yca's
 * {@code SystemPromptBuilder} and other agents: YAML frontmatter ({@code name}, {@code description})
 * followed by Markdown sections ({@code # title}, {@code ## Entry points}, {@code ## Module rules},
 * {@code ## Working in this module}).
 *
 * <p>Entry-point content is strategy B only and is never overridden once written unless force is set.</p>
 */
public final class SkillWriter {

    public static final String MARKER_DIR = "yca";

    private final Path localRepository;

    public SkillWriter(final Path localRepository) {
        this.localRepository = localRepository;
    }

    /**
     * The skill directory for a GAV: {@code <repo>/<g-path>/<a>/<v>/yca/<name>}.
     */
    public Path skillDirectory(final Gav gav, final String name) {
        return localRepository
                .resolve(gav.groupId().replace('.', '/'))
                .resolve(gav.artifactId())
                .resolve(gav.version())
                .resolve(MARKER_DIR)
                .resolve(name);
    }

    /**
     * Whether an immutable (release) artifact already has knowledge written (the presence of any
     * {@code SKILL.md} under its {@code yca/} dir is the marker). Releases are immutable: once present it
     * is never regenerated unless force is requested.
     */
    public boolean alreadyIndexed(final Gav gav) {
        return resolvedMarkers(gav).stream().anyMatch(path -> Files.isRegularFile(path.resolve("SKILL.md")));
    }

    /**
     * Write the skill content for a GAV. Returns the written {@code SKILL.md} path.
     */
    public Path write(final Gav gav, final String name, final String skill) {
        final var dir = skillDirectory(gav, name);
        try {
            Files.createDirectories(dir);
            final var file = dir.resolve("SKILL.md");
            Files.writeString(file, skill);
            return file;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The sidecar file holding the build-declaration fingerprint captured when the artifact was last
     * indexed: {@code <repo>/<g-path>/<a>/<v>/.maven-knowledge-fingerprint}. Used to re-index only the
     * dependencies whose declaring build files changed.
     */
    public Path fingerprintPath(final Gav gav) {
        return versionDir(gav).resolve(".maven-knowledge-fingerprint");
    }

    /**
     * The stored fingerprint for a GAV, or {@code null} when none was recorded.
     */
    public String readFingerprint(final Gav gav) {
        final var file = fingerprintPath(gav);
        try {
            return Files.isRegularFile(file) ? Files.readString(file).strip() : null;
        } catch (final IOException e) {
            return null;
        }
    }

    /**
     * Record the build-declaration fingerprint for a GAV. A {@code null} fingerprint is stored as clear,
     * so an artifact that was indexed without tracking can later be re-tracked once the caller provides
     * a fingerprint.
     */
    public void writeFingerprint(final Gav gav, final String fingerprint) {
        final var file = fingerprintPath(gav);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, fingerprint == null ? "" : fingerprint);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path versionDir(final Gav gav) {
        return localRepository
                .resolve(gav.groupId().replace('.', '/'))
                .resolve(gav.artifactId())
                .resolve(gav.version());
    }

    /**
     * All {@code yca/} skill directories under an artifact (including a marker-less one), for listing /
     * manifest purposes. May contain a single dir in practice.
     */
    public List<Path> resolvedMarkers(final Gav gav) {
        try {
            final var versionDir = localRepository
                    .resolve(gav.groupId().replace('.', '/'))
                    .resolve(gav.artifactId())
                    .resolve(gav.version());
            final var marker = versionDir.resolve(MARKER_DIR);
            if (!Files.isDirectory(marker)) {
                return List.of();
            }
            try (final var stream = Files.list(marker)) {
                return stream.filter(Files::isDirectory).sorted().toList();
            }
        } catch (final IOException e) {
            return List.of();
        }
    }
}
