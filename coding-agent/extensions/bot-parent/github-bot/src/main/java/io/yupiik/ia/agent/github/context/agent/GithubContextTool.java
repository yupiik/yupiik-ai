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
package io.yupiik.ia.agent.github.context.agent;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.github.configuration.AgentContextConfiguration;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class GithubContextTool implements EmbeddedTool {
    private static final List<String> OPERATIONS = List.of(
            "summary",
            "repositories",
            "request_repository",
            "prepare_source",
            "operation_status",
            "cancel",
            "pull",
            "files",
            "reviews",
            "reviewComments",
            "issueComments",
            "issue",
            "discussion",
            "discussionComments",
            "discussionReplies");
    private final AgentContextConfiguration configuration;
    private final JsonMapper json;
    private final Configuration sources;

    public GithubContextTool(
            final AgentContextConfiguration configuration, final JsonMapper json, final Configuration sources) {
        this.configuration = configuration;
        this.json = json;
        this.sources = sources;
    }

    @Override
    public String name() {
        return "github_context";
    }

    @Override
    public String description(final Path workdir) {
        return "Read task-scoped GitHub context and request bounded source generations. prepare_source is asynchronous: retain operationId and query operation_status; pending is not failure. No upstream credentials or arbitrary URLs.";
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
    public boolean usable(final Path workdir) {
        return ContextClient.usable(configuration, sources);
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "type",
                "object",
                "additionalProperties",
                false,
                "required",
                List.of("operation"),
                "properties",
                Map.of(
                        "operation",
                        Map.of("type", "string", "enum", OPERATIONS),
                        "repository",
                        Map.of("type", List.of("string", "null")),
                        "page",
                        Map.of("type", List.of("integer", "null")),
                        "depth",
                        Map.of("type", List.of("integer", "null")),
                        "tags",
                        Map.of("type", List.of("boolean", "null")),
                        "revision",
                        Map.of("type", List.of("string", "null"), "enum", Arrays.asList("HEAD", "BASE", null)),
                        "operationId",
                        Map.of("type", List.of("string", "null")),
                        "parentId",
                        Map.of(
                                "type",
                                List.of("string", "null"),
                                "description",
                                "Previously observed Discussion top-level comment ID for discussionReplies only.")));
    }

    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        if (args == null
                || !(args.get("operation") instanceof String operation)
                || !OPERATIONS.contains(operation)
                || !Set.of("operation", "repository", "page", "depth", "tags", "revision", "operationId", "parentId")
                        .containsAll(args.keySet())) {
            return ToolResult.error("Invalid context arguments.");
        }
        for (final var field : List.of("repository", "operationId", "revision", "parentId")) {
            if (args.get(field) != null && !(args.get(field) instanceof String)) {
                return ToolResult.error("Invalid context arguments.");
            }
        }
        for (final var field : List.of("page", "depth")) {
            final var value = args.get(field);
            if (value != null) {
                try {
                    if (!(value instanceof Number) || new BigDecimal(value.toString()).intValueExact() < 1) {
                        return ToolResult.error("Invalid context arguments.");
                    }
                } catch (final ArithmeticException | NumberFormatException invalid) {
                    return ToolResult.error("Invalid context arguments.");
                }
            }
        }
        if ((args.get("tags") != null && !(args.get("tags") instanceof Boolean))
                || (args.get("revision") != null && !List.of("HEAD", "BASE").contains(args.get("revision")))) {
            return ToolResult.error("Invalid context arguments.");
        }
        return ContextClient.execute(name(), configuration, json, args, context, sources);
    }
}
