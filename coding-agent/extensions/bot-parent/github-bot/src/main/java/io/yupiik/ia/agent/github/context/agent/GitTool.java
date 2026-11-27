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
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only remote snapshot Git. Deliberately has no process or shell fallback. */
@ApplicationScoped
public class GitTool implements EmbeddedTool {
    private final AgentContextConfiguration configuration;
    private final JsonMapper json;
    private final Configuration sources;

    public GitTool(final AgentContextConfiguration configuration, final JsonMapper json, final Configuration sources) {
        this.configuration = configuration;
        this.json = json;
        this.sources = sources;
    }

    @Override
    public String name() {
        return "git";
    }

    @Override
    public String description(final Path workdir) {
        return "Read the WORKER-PINNED Git snapshot, not local edits. Use argv arrays, never a shell string. "
                + "Examples: {argv:[\"log\",\"-5\",\"--oneline\"]}, {argv:[\"show\",\"HEAD:pom.xml\"]}, "
                + "{argv:[\"diff\",\"BASE...HEAD\",\"--stat\"]}, {argv:[\"grep\",\"-n\",\"-e\",\"TODO\",\"HEAD\"]}. "
                + "Omit repository or use null for the primary grant. Supports bounded log/show/diff/grep/blame/ls-tree/"
                + "rev-parse/merge-base/rev-list/cat-file and branch/tag listings. Unknown flags, mutations, network, "
                + "host paths and executable configuration are rejected. Use LOCAL Git/Bash for status, staged diffs, "
                + "commits and builds. If SOURCE_REQUIRED, call github_context prepare_source then operation_status. "
                + "Deepen incomplete history explicitly; never substitute a two-dot comparison for a missing merge base.";
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
                List.of("argv"),
                "properties",
                Map.of(
                        "argv",
                        Map.of(
                                "type",
                                "array",
                                "items",
                                Map.of("type", "string"),
                                "description",
                                "Literal Git arguments, for example [\"log\",\"-5\",\"--oneline\"]. No shell syntax."),
                        "repository",
                        Map.of(
                                "type",
                                List.of("string", "null"),
                                "description",
                                "Previously granted canonical owner/repository; omitted/null selects primary.")));
    }

    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        if (args == null
                || !Set.of("argv", "repository").containsAll(args.keySet())
                || !(args.get("argv") instanceof List<?> argv)
                || argv.isEmpty()
                || argv.stream().anyMatch(it -> !(it instanceof String))
                || (args.get("repository") != null && !(args.get("repository") instanceof String))) {
            return ToolResult.error("Invalid Git arguments.");
        }
        final var request = new LinkedHashMap<String, Object>();
        request.put("operation", "git");
        request.put("argv", args.get("argv"));
        request.put("repository", args.get("repository"));
        return ContextClient.execute(name(), configuration, json, request, context, sources);
    }
}
