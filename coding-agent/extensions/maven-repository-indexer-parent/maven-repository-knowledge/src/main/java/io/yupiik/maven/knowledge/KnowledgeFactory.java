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
package io.yupiik.maven.knowledge;

import io.yupiik.maven.knowledge.content.ContentPart;
import io.yupiik.maven.knowledge.write.KnowledgeWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the complete generated knowledge set for an artifact from its content parts, deterministic
 * entry points and (optional) LLM skills, writing it as flat {@code -knowledge-*.md} siblings in the Maven
 * version directory:
 * <ol>
 *     <li>{@code -knowledge-index.md}: frontmatter + entry points + module rules + working-in-module + the list
 *     of knowledge siblings (short names) + an optional {@code ## Skill} note.</li>
 *     <li>{@code -knowledge-api.md}: bytecode-derived public API (always written while the main jar exists).</li>
 *     <li>one {@code -knowledge-<short>.md} per content part (sources chunked as {@code sources-N}, javadoc per
 *     class as {@code javadoc-<qualifiedClass>}).</li>
 *     <li>{@code -knowledge-skill-<short>.md} (+ {@code -knowledge-skill.md} index) for LLM or hand-written
 *     skills; an existing hand-written skill file is never overwritten.</li>
 * </ol>
 * Everything is deterministic and framework-free; the LLM is only ever an optional, validated enrichment.
 */
public record KnowledgeFactory(KnowledgeWriter writer) {

    /**
     * Write the whole knowledge set for an artifact.
     *
     * @param gav          the artifact.
     * @param parts        the acquired content parts (sources chunked, javadoc per-class, ...); may be empty when
     *                     the surface is entirely bytecode or a plugin/extension (then only the {@code api}
     *                     sibling plus the folded {@code moduleConfig} section are produced).
     * @param apiMarkdown  the bytecode-derived public-API markdown (or blank when no main jar); written to the
     *                     {@code api} sibling.
     * @param moduleConfig plugin/extension configuration markdown folded into the index (blank for ordinary
     *                     artifacts).
     * @param entryPoints  deterministic entry-point classes (list of FQNs).
     * @param skills       optional validated skills (short name + full gold-format text); written and indexed.
     * @return the index file path.
     */
    public Path writeArtifact(
            final Gav gav,
            final List<ContentPart> parts,
            final String apiMarkdown,
            final String moduleConfig,
            final List<String> entryPoints,
            final List<SkillDoc> skills) {
        final var knowledgeShortNames = new ArrayList<String>();
        knowledgeShortNames.add("api");
        if (parts != null) {
            for (final var part : parts) {
                writer.write(gav, part.shortName(), part.content());
                knowledgeShortNames.add(part.shortName());
            }
        }
        writeApi(gav, apiMarkdown);
        final var writtenSkills = writeSkills(gav, skills);
        knowledgeShortNames.addAll(writtenSkills);
        final var hasContentParts = parts != null && !parts.isEmpty();
        final var index = indexMarkdown(
                gav, hasContentParts, moduleConfig, entryPoints, knowledgeShortNames, !writtenSkills.isEmpty());
        return writer.write(gav, "index", index);
    }

    private void writeApi(final Gav gav, final String apiMarkdown) {
        final var markdown = apiMarkdown == null || apiMarkdown.isBlank() ? "No public API detected.\n" : apiMarkdown;
        writer.write(gav, "api", markdown);
    }

    private List<String> writeSkills(final Gav gav, final List<SkillDoc> skills) {
        final var written = new ArrayList<String>();
        if (skills == null || skills.isEmpty()) {
            return written;
        }
        final var index = new StringBuilder();
        index.append("---\nname: ").append(KnowledgeWriter.filePrefix(gav)).append("-skills\n");
        index.append("description: Skills available for ")
                .append(gav.coordinate())
                .append(".\n---\n\n");
        index.append("## Skills\n\n");
        for (final var skill : skills) {
            final var existing = writer.sibling(gav, "skill-" + skill.shortName());
            if (Files.isRegularFile(existing)) {
                continue; // never overwrite a hand-written skill
            }
            writer.write(gav, "skill-" + skill.shortName(), skill.content());
            index.append("- `").append("skill-").append(skill.shortName()).append("`\n");
            written.add("skill-" + skill.shortName());
        }
        if (!written.isEmpty()) {
            writer.write(gav, "skill", index.toString());
        }
        return written;
    }

    private String indexMarkdown(
            final Gav gav,
            final boolean hasContentParts,
            final String moduleConfig,
            final List<String> entryPoints,
            final List<String> knowledgeShortNames,
            final boolean hasSkills) {
        final var sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(gav.artifactId()).append('\n');
        sb.append("description: ")
                .append(gav.artifactId())
                .append(" - ")
                .append(gav.coordinate())
                .append(".\n");
        sb.append("---\n\n");
        sb.append("# ").append(gav.artifactId()).append('\n');
        if (entryPoints != null && !entryPoints.isEmpty()) {
            sb.append("\n## Entry points\n");
            for (final var ep : entryPoints) {
                sb.append("- `").append(ep).append("`\n");
            }
        }
        sb.append("\n## Module rules\n");
        sb.append("Generated from the local Maven repository; the artifact API is in the `api` sibling");
        if (hasContentParts) {
            sb.append(", detailed content in the siblings listed under Knowledge");
        }
        sb.append(".\n");
        if (moduleConfig != null && !moduleConfig.isBlank()) {
            sb.append('\n').append(moduleConfig.strip()).append('\n');
        }
        sb.append("\n## Working in this module\n");
        sb.append("Add `")
                .append(gav.groupId())
                .append(':')
                .append(gav.artifactId())
                .append(':')
                .append(gav.version())
                .append("` as a dependency to use this module.\n");
        if (!knowledgeShortNames.isEmpty()) {
            sb.append("\n## Knowledge\n");
            for (final var name : knowledgeShortNames) {
                sb.append("- `").append(name).append("`\n");
            }
        }
        if (hasSkills) {
            sb.append("\n## Skill\n");
            sb.append("A ready-to-use skill is available; see the `skill` sibling.\n");
        }
        return sb.toString();
    }

    /**
     * A candidate skill for the artifact: its short name (used to build {@code -knowledge-skill-<short>.md})
     * and the full gold-format markdown.
     */
    public record SkillDoc(String shortName, String content) {}
}
