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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.tools.ToolContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeFindCodeToolTest {

    @Test
    void findsBySimpleNameAndKind() {
        final var tool = new CodeFindCodeTool(fake(new GraphStore(
                List.of(
                        new GraphModel.Symbol(
                                "org.hop.RowSet", GraphModel.Kind.INTERFACE, "src/RowSet.java", 10, 12, null),
                        new GraphModel.Symbol(
                                "org.hop.RowSetImpl", GraphModel.Kind.TYPE, "src/RowSetImpl.java", 20, 25, null),
                        new GraphModel.Symbol(
                                "org.hop.Pipeline", GraphModel.Kind.TYPE, "src/Pipeline.java", 33, 40, null)),
                List.of(),
                Map.of(),
                1L)));
        final var simple =
                tool.execute(Map.of("query", "RowSet"), ToolContext.empty(fakeRoot, new DisabledSandbox(fakeRoot)));
        assertTrue(simple.success());
        final var simpleText = simple.output();
        assertTrue(simpleText.contains("INTERFACE  org.hop.RowSet"), simpleText);
        assertTrue(simpleText.contains("TYPE  org.hop.RowSetImpl"), simpleText);
        assertFalse(simpleText.contains("Pipeline"), simpleText);

        final var byKind = tool.execute(
                Map.of("query", "rowset", "kind", "interface"),
                ToolContext.empty(fakeRoot, new DisabledSandbox(fakeRoot)));
        final var kindText = byKind.output();
        assertTrue(kindText.contains("INTERFACE  org.hop.RowSet"), kindText);
        assertFalse(kindText.contains("RowSetImpl"), kindText);
    }

    @Test
    void reportsNoSymbolWhenMissing() {
        final var tool = new CodeFindCodeTool(fake(GraphStore.empty()));
        final var result = tool.execute(
                Map.of("query", "doesNotExist"), ToolContext.empty(fakeRoot, new DisabledSandbox(fakeRoot)));
        assertTrue(result.success());
        assertTrue(result.output().contains("No symbol matching 'doesNotExist'."));
    }

    @Test
    void requiresQuery() {
        final var tool = new CodeFindCodeTool(fake(GraphStore.empty()));
        final var result = tool.execute(Map.of(), ToolContext.empty(fakeRoot, new DisabledSandbox(fakeRoot)));
        assertFalse(result.success());
    }

    @Test
    void capsAtMax() {
        final var tool = new CodeFindCodeTool(fake(new GraphStore(
                List.of(
                        new GraphModel.Symbol("a.Alpha", GraphModel.Kind.TYPE, "fa", 1, 2, null),
                        new GraphModel.Symbol("b.Beta", GraphModel.Kind.TYPE, "fb", 1, 2, null),
                        new GraphModel.Symbol("c.Gamma", GraphModel.Kind.TYPE, "fc", 1, 2, null)),
                List.of(),
                Map.of(),
                1L)));
        final var result = tool.execute(
                Map.of("query", "a", "max", "1"), ToolContext.empty(fakeRoot, new DisabledSandbox(fakeRoot)));
        assertTrue(result.output().contains("capped at 1"));
    }

    private static StructuralCodeGraph fake(final GraphStore store) {
        return new StructuralCodeGraph() {
            @Override
            public GraphStore graph(final Path root) {
                return store;
            }

            @Override
            public Path storeRoot(final Path root) {
                return fakeRoot.resolve("fake-code-graph");
            }
        };
    }

    @TempDir
    static Path fakeRoot;
}
