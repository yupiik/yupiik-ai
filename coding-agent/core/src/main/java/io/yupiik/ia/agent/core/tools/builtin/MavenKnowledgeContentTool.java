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
import java.util.Map;

/**
 * Embedded read-only tool that dereferences generated Maven knowledge on demand. The session prompt only
 * mounts each artifact's {@code -knowledge-index.md}; this tool loads the sibling files it references (the
 * API surface, sources chunks, javadoc pages, ...) for a given {@code g:a:v} coordinate.
 */
@ApplicationScoped
public class MavenKnowledgeContentTool implements EmbeddedTool {

    private final MavenRepositoryKnowledgeService service;

    public MavenKnowledgeContentTool(final MavenRepositoryKnowledgeService service) {
        this.service = service;
    }

    protected MavenKnowledgeContentTool() {
        this.service = null;
    }

    @Override
    public String name() {
        return "maven_knowledge_content";
    }

    @Override
    public String description(final Path workdir) {
        return "Read the generated knowledge of a local Maven artifact on demand. With only a "
                + "groupId:artifactId:version coordinate returns the artifact's knowledge index; pass a file short name "
                + "(e.g. api, sources-0, javadoc-<class>, doc) to read the referenced sibling listed in that index.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(
                Map.of(
                        "gav", Schemas.str("Maven coordinate (groupId:artifactId:version) whose knowledge to read."),
                        "file",
                                Schemas.str("Optional knowledge file short name to read instead of the index "
                                        + "(e.g. api, sources-0, javadoc-<class>). Omit to read the index.")),
                "gav");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        final var gav = ToolArgs.arg(args, "gav");
        if (gav == null) {
            return ToolResult.error("gav is required (groupId:artifactId:version)");
        }
        final var file = ToolArgs.arg(args, "file");
        try {
            return ToolResult.ok(service.knowledge(context.projectDir(), gav, file));
        } catch (final RuntimeException e) {
            return ToolResult.error("maven knowledge content failed: " + e.getMessage());
        }
    }
}
