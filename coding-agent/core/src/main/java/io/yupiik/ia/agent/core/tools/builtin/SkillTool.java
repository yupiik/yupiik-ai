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

import static io.yupiik.ia.agent.core.tools.ToolArgs.arg;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.skills.SkillLocator;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@ApplicationScoped
public class SkillTool implements EmbeddedTool {
    private final CodingAgentConfiguration configuration;

    public SkillTool(final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public String name() {
        return "skill";
    }

    @Override
    public String description(final Path workdir) {
        if (configuration == null
                || configuration.skills() == null
                || !configuration.skills().enabled()) {
            return "Load a skill by name and return its SKILL.md instructions. No skills are currently enabled.";
        }
        return "Load a skill by name and return its SKILL.md instructions. "
                + "Available skills are listed in the session's available_skills catalog.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public boolean readonly() {
        return true;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(Map.of("name", Schemas.str("The skill name to load.")), "name");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        if (configuration == null
                || configuration.skills() == null
                || !configuration.skills().enabled()) {
            return ToolResult.error("Skills are disabled.");
        }
        final var name = arg(args, "name");
        if (name == null || name.isBlank()) {
            return ToolResult.error("name is required");
        }
        final var workingDir = context.projectDir();
        final var file = SkillLocator.find(configuration, name, workingDir);
        if (file == null) {
            return ToolResult.error("Skill not found: " + name + ". Available: "
                    + String.join(", ", SkillLocator.skillNames(configuration, workingDir)));
        }
        try {
            return ToolResult.ok(Files.readString(file));
        } catch (final IOException | UncheckedIOException e) {
            return ToolResult.error("Cannot read skill " + name + ": " + e.getMessage());
        }
    }
}
