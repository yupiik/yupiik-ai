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
package io.yupiik.maven.knowledge.llm;

import io.yupiik.maven.knowledge.Gav;
import java.util.List;

/**
 * Shared system-prompt wording for SKILL.md synthesis, so every {@link KnowledgeLlm} flavor (embedded yca and MCP)
 * asks the model the same thing and the instruction text lives in a single place.
 */
public final class SkillPrompts {

    private SkillPrompts() {}

    /** The system instruction asking the model to produce a SKILL.md for the given artifact (+ its entry points). */
    public static String systemFor(final Gav gav, final List<String> entryPoints) {
        final var sb = new StringBuilder("Write a concise SKILL.md for the artifact ")
                .append(gav.coordinate())
                .append(". Use YAML frontmatter with a `name` (short) and `description`, then a '# ' title and")
                .append(" '## Entry points' (list of fully qualified classes), '## Module rules' and")
                .append(" '## Working in this module' (build commands) sections when relevant. Focus on what a")
                .append(" coding agent needs to use/mount the library without rediscovering it. Output only the")
                .append(" SKILL.md content.");
        if (entryPoints != null && !entryPoints.isEmpty()) {
            sb.append("\nMain entry points:\n");
            for (final var ep : entryPoints) {
                sb.append("- ").append(ep).append('\n');
            }
        }
        return sb.toString();
    }
}
