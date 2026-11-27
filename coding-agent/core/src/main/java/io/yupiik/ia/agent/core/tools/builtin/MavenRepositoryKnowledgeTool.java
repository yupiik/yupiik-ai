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
package io.yupiik.ia.agent.core.tools.builtin;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolArgs;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Embedded {@code maven-repository-knowledge} tool. Lets a coding agent index (or re-index) the knowledge
 * files (skills + remote {@code -skills.md} manifest) of Maven artifacts stored in the local repository,
 * so library usage does not have to be rediscovered. This performs an index refresh and may invoke the
 * configured LLM; it is a maintenance/EDIT action.
 */
@ApplicationScoped
public class MavenRepositoryKnowledgeTool implements EmbeddedTool {

    private final MavenRepositoryKnowledgeService service;

    public MavenRepositoryKnowledgeTool(final MavenRepositoryKnowledgeService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return "maven_repository_knowledge";
    }

    @Override
    public String description(final Path workdir) {
        return "Index or refresh generated knowledge (SKILL.md + -skills.md manifest) for Maven artifacts in the local repository. "
                + "Use refresh with a list of groupId:artifactId[:version] coordinates to generate/re-generate their skills, "
                + "optionally with force=true to bypass the immutable-skip rule for released artifacts, or force=true with no "
                + "coordinate to re-run the baseline index of the current artifacts.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.EDIT;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(
                Map.of(
                        "operation", Schemas.str("index to generate knowledge for the given coordinates."),
                        "gavs",
                                Schemas.arrayOf(
                                        "List of groupId:artifactId[:version] coordinates to index.",
                                        Schemas.str("Maven coordinate.")),
                        "force",
                                Schemas.bool(
                                        "Regenerate even for immutable released artifacts that are already indexed. Default false.")),
                "operation");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        final var op = ToolArgs.arg(args, "operation");
        final Object rawGavs = args.get("gavs");
        final List<String> gavs = rawGavs instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        final var force = ToolArgs.optBool(args, "force", false);
        if (op == null) {
            return ToolResult.error("operation is required (index)");
        }
        try {
            // the triggering session's provider/model selection rides the context, so the index LLM
            // calls resolve it lazily at call time instead of the process default
            return ToolResult.ok(service.index(context.projectDir(), gavs, force, context.settings()));
        } catch (final RuntimeException e) {
            return ToolResult.error("maven repository knowledge index failed: " + e.getMessage());
        }
    }
}
