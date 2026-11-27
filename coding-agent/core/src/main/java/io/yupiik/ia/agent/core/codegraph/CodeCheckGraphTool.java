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
package io.yupiik.ia.agent.core.codegraph;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.nio.file.Path;
import java.util.Map;

@ApplicationScoped
public class CodeCheckGraphTool implements EmbeddedTool {
    private final StructuralCodeGraph codeGraph;

    public CodeCheckGraphTool(final StructuralCodeGraph codeGraph) {
        this.codeGraph = codeGraph;
    }

    @Override
    public String name() {
        return "code_check_graph";
    }

    @Override
    public String description(final Path workdir) {
        return "Report the freshness and health of the structural code graph (symbol/file counts, missing files, "
                + "store location). Use it to decide whether to query the graph with code_find_code / code_trace_calls.";
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
        return Schemas.object(Map.of());
    }

    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        codeGraph.ensureRefreshedReady(context.projectDir());
        return ToolResult.ok(codeGraph.freshness(context.projectDir()));
    }
}
