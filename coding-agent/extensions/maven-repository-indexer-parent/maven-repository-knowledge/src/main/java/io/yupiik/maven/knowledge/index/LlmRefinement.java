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
package io.yupiik.maven.knowledge.index;

import io.yupiik.maven.knowledge.Gav;
import io.yupiik.maven.knowledge.KnowledgeFactory;
import io.yupiik.maven.knowledge.content.ContentAcquired;
import io.yupiik.maven.knowledge.llm.KnowledgeLlm;
import java.util.List;

/**
 * Optional, validated LLM refinement of an artifact's knowledge, used only when {@code useLlm} is enabled.
 * It never produces the knowledge by itself: entry points and the API file are always deterministic, and any
 * LLM skill output that does not look like a real gold-format SKILL.md (a bare placeholder such as a filename
 * or a {@code {"skill": ...}} JSON shell) is discarded.
 */
final class LlmRefinement {

    private LlmRefinement() {
        // no-op
    }

    static List<KnowledgeFactory.SkillDoc> refine(
            final Gav gav,
            final ContentAcquired acquired,
            final List<String> entryPoints,
            final String apiMarkdown,
            final KnowledgeLlm llm) {
        final String content;
        if (acquired != null && acquired.present()) {
            content = acquired.content();
        } else if (apiMarkdown != null && !apiMarkdown.isBlank()) {
            content = apiMarkdown;
        } else {
            content = "";
        }
        final String skill;
        try {
            skill = llm.synthesizeSkill(gav, gav.artifactId(), content, entryPoints);
        } catch (final RuntimeException e) {
            return List.of();
        }
        if (!isPlausibleSkill(skill)) {
            return List.of();
        }
        return List.of(new KnowledgeFactory.SkillDoc(gav.artifactId(), skill));
    }

    /**
     * A SKILL.md is plausible only when it carries the gold-format skeleton: leading {@code ---} frontmatter,
     * a {@code name:} line and at least one {@code #} heading. This rejects both the "echo a filename" failure
     * mode (eg {@code SKILL.md}, {@code byte-buddy}) and a {@code {"skill": ...}} JSON shell some constrained
     * models return.
     */
    static boolean isPlausibleSkill(final String skill) {
        if (skill == null || skill.isBlank()) {
            return false;
        }
        final var trimmed = skill.trim();
        if (trimmed.startsWith("{")) { // JSON shell, e.g. {"skill":"SKILL.md"}
            return false;
        }
        if (!trimmed.startsWith("---\n")) {
            return false;
        }
        if (!containsLine(trimmed, "name:")) {
            return false;
        }
        return trimmed.indexOf('\n' + "#") >= 0 || trimmed.indexOf("\n##") >= 0;
    }

    private static boolean containsLine(final String text, final String prefix) {
        for (final var line : text.split("\n", -1)) {
            if (line.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
