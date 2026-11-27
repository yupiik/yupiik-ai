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
package io.yupiik.ia.agent.core.skills;

import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Resolves skills (SKILL.md files) from the project hierarchy (searched upwards from the
 * working directory) and from the global skill directories. Shared by the system prompt
 * builder and the skill tool.
 */
public final class SkillLocator {
    private static final List<String> PROJECT_SKILL_DIRS = List.of(".opencode/skills", ".claude/skills", ".yca/skills");

    private SkillLocator() {}

    /** Uses the required session working directory, normalized before walking its ancestors. */
    public static List<Path> skillDirectories(final CodingAgentConfiguration configuration, final Path workingDir) {
        final var list = new ArrayList<Path>();
        final var base = Objects.requireNonNull(workingDir, "workdir").normalize();
        if (!base.isAbsolute()) {
            throw new IllegalArgumentException("workdir must be absolute: " + workingDir);
        }
        final var walk = new ArrayList<Path>();
        var dir = base;
        walk.add(dir);
        while (dir.getParent() != null) {
            dir = dir.getParent();
            walk.add(dir);
        }
        for (var i = walk.size() - 1; i >= 0; i--) {
            for (final var d : PROJECT_SKILL_DIRS) {
                list.add(walk.get(i).resolve(d));
            }
        }
        list.add(Path.of(configuration.resolveStorageHome(), "skills"));
        final var home = configuration.resolveHome();
        list.add(Path.of(home, ".config", "opencode", "skills"));
        list.add(Path.of(home, ".claude", "skills"));
        list.add(Path.of(home, ".agents", "skills"));
        return list;
    }

    public static List<String> skillNames(final CodingAgentConfiguration configuration, final Path workingDir) {
        final var out = new ArrayList<String>();
        for (final var dir : skillDirectories(configuration, workingDir)) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (final var dirs = Files.list(dir)) {
                for (final var skillDir : dirs.sorted().toList()) {
                    if (Files.isDirectory(skillDir) && Files.isRegularFile(skillDir.resolve("SKILL.md"))) {
                        final var name = skillDir.getFileName().toString();
                        if (!out.contains(name)) {
                            out.add(name);
                        }
                    }
                }
            } catch (final Exception e) {
                // skip unreadable dir
            }
        }
        return out;
    }

    public static Path find(final CodingAgentConfiguration configuration, final String name, final Path workingDir) {
        for (final var dir : skillDirectories(configuration, workingDir)) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (final var dirs = Files.list(dir)) {
                for (final var skillDir : dirs.toList()) {
                    if (Files.isDirectory(skillDir)
                            && skillDir.getFileName().toString().equals(name)) {
                        final var file = skillDir.resolve("SKILL.md");
                        if (Files.isRegularFile(file)) {
                            return file;
                        }
                    }
                }
            } catch (final Exception e) {
                // skip unreadable dir
            }
        }
        return null;
    }

    /**
     * The {@code name} and {@code description} of an available skill, read from the SKILL.md frontmatter
     * (or derived from the directory name and first meaningful line when the frontmatter is absent).
     */
    public record SkillDescriptor(String name, String description) {}

    /**
     * Enumerate every discovered skill with its {@code name} and {@code description} (frontmatter only,
     * never the full SKILL.md body). Mirrors opencode's {@code <available_skills>} discovery so the model
     * can decide which skill to load on demand via the {@code skill} tool.
     */
    public static List<SkillDescriptor> availableSkills(
            final CodingAgentConfiguration configuration, final Path workingDir) {
        final var out = new ArrayList<SkillDescriptor>();
        final var seen = new HashSet<String>();
        for (final var dir : skillDirectories(configuration, workingDir)) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (final var dirs = Files.list(dir)) {
                for (final var skillDir : dirs.sorted().toList()) {
                    if (!Files.isDirectory(skillDir)) {
                        continue;
                    }
                    final var skillFile = skillDir.resolve("SKILL.md");
                    if (!Files.isRegularFile(skillFile)) {
                        continue;
                    }
                    if (!seen.add(skillDir.getFileName().toString())) {
                        continue;
                    }
                    try {
                        final var raw = Files.readString(skillFile);
                        out.add(descriptor(raw, skillDir.getFileName().toString()));
                    } catch (final IOException e) {
                        // skip unreadable
                    }
                }
            } catch (final IOException e) {
                // skip unreadable dir
            }
        }
        return out;
    }

    /**
     * Render the discovered skills as an opencode-style {@code <available_skills>} catalog (name + description),
     * for session-specific prompt context. Returns {@code ""} when no skills are found.
     */
    /** Renders metadata only for the supplied session root; callers enforce the skills enabled setting. */
    public static String availableSkillsCatalog(final CodingAgentConfiguration configuration, final Path workingDir) {
        final var skills = availableSkills(configuration, workingDir);
        if (skills.isEmpty()) {
            return "";
        }
        final var sb = new StringBuilder("<available_skills>\n");
        for (final var skill : skills) {
            sb.append("<skill><name>")
                    .append(skill.name())
                    .append("</name><description>")
                    .append(skill.description() == null ? "" : skill.description())
                    .append("</description></skill>\n");
        }
        return sb.append("</available_skills>").toString();
    }

    private static final String FRONTMATTER_START = "---\n";
    private static final String FRONTMATTER_END = "---";

    private static SkillDescriptor descriptor(final String raw, final String dirName) {
        var content = raw;
        var name = dirName;
        var description = "";
        if (content.startsWith(FRONTMATTER_START)) {
            final var end = content.indexOf('\n' + FRONTMATTER_END + "\n", FRONTMATTER_START.length());
            if (end > 0) {
                final var front = content.substring(FRONTMATTER_START.length(), end);
                for (final var line : front.lines().toList()) {
                    if (line.startsWith("name:") && line.length() > "name:".length()) {
                        final var candidate = line.substring("name:".length()).strip();
                        if (!candidate.isEmpty()) {
                            name = candidate;
                        }
                    } else if (line.startsWith("description:") && line.length() > "description:".length()) {
                        description = line.substring("description:".length()).strip();
                    }
                }
                content = content.substring(end + FRONTMATTER_END.length() + 2);
            }
        }
        if (description.isEmpty()) {
            description = firstMeaningfulLine(content);
        }
        return new SkillDescriptor(name, description);
    }

    private static String firstMeaningfulLine(final String content) {
        for (final var line : content.lines().toList()) {
            final var stripped = line.strip();
            if (!stripped.isEmpty() && !stripped.startsWith("#")) {
                return stripped;
            }
        }
        return content.strip();
    }
}
