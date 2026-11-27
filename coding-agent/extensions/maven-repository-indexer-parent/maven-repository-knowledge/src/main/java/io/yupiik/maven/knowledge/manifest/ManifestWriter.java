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
package io.yupiik.maven.knowledge.manifest;

import io.yupiik.maven.knowledge.Gav;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes the remote-friendly manifest for an artifact following a Maven artifact convention:
 * {@code <groupId-as-path>/<artifactId>/<version>/<artifactId>-<version>-skills.md}. Consumers on the
 * same Maven repository dereference that single artifact-shaped path to resolve the generated per-artifact
 * skills (a "main entry point GAV dereferences the manifest, the manifest lists the skill URLs").
 *
 * <p>Each skill is either <em>inlined</em> directly in the manifest when its {@code SKILL.md} is simple
 * (content length at most {@link #INLINE_MAX_CHARS}), so a consumer loading just the manifest gets the whole
 * skill without an extra fetch, or <em>referenced</em> by a path that is relative to the manifest itself
 * ({@code yca/<name>/SKILL.md}) when it is too large. Relative references keep the manifest portable: it
 * resolves the same whether the artifact (and manifest) live on a remote Maven repository or a local one.
 */
public final class ManifestWriter {

    /** A {@code SKILL.md} at most this many characters is considered "simple" and inlined into the manifest. */
    static final int INLINE_MAX_CHARS = 4096;

    private final Path localRepository;

    public ManifestWriter(final Path localRepository) {
        this.localRepository = localRepository;
    }

    public Path manifestPath(final Gav gav) {
        return localRepository
                .resolve(gav.groupId().replace('.', '/'))
                .resolve(gav.artifactId())
                .resolve(gav.version())
                .resolve(gav.artifactId() + "-" + gav.version() + "-skills.md");
    }

    /**
     * Write the manifest covering each generated skill of the artifact. Each skill is inlined when its
     * {@code SKILL.md} is simple (content length at most {@link #INLINE_MAX_CHARS}); otherwise it is
     * referenced by a path relative to the manifest itself ({@code yca/<name>/SKILL.md}).
     */
    public void write(final Gav gav, final List<String> skillNames) {
        final var path = manifestPath(gav);
        try {
            Files.createDirectories(path.getParent());
            final var sb = new StringBuilder();
            sb.append("---\n");
            sb.append("name: ").append(gav.artifactId()).append("-skills\n");
            sb.append("description: Generated knowledge index for ")
                    .append(gav.coordinate())
                    .append(".\n");
            sb.append("---\n\n");
            sb.append("# ").append(gav.artifactId()).append(" skills\n\n");
            sb.append("Artifact: `").append(gav.coordinate()).append("`\n\n");
            if (skillNames.isEmpty()) {
                sb.append("_No skills generated yet._\n");
            } else {
                sb.append("## Skills\n");
                for (final var name : skillNames) {
                    sb.append("\n### ").append(name).append('\n');
                    final var skillFile = skillFile(gav, name);
                    final var content = skillFile == null ? null : readSkill(skillFile);
                    if (content != null && content.length() <= INLINE_MAX_CHARS) {
                        sb.append('\n').append(content.strip()).append('\n');
                    } else {
                        sb.append("\nReferenced from `yca/").append(name).append("/SKILL.md`.\n");
                    }
                }
            }
            Files.writeString(path, sb.toString());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path skillFile(final Gav gav, final String name) {
        final var file =
                manifestPath(gav).getParent().resolve("yca").resolve(name).resolve("SKILL.md");
        return Files.isRegularFile(file) ? file : null;
    }

    private static String readSkill(final Path file) {
        try {
            return Files.readString(file);
        } catch (final IOException e) {
            return null;
        }
    }
}
