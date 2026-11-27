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
package io.yupiik.ia.agent.core.tools;

import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.codegraph.CodeResolveTool;
import io.yupiik.ia.agent.core.codegraph.GraphStore;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.mcp.McpManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolRegistryTest {
    @TempDir
    private static Path testWorkdir;

    @TempDir
    static Path fakeRoot;

    private static EmbeddedTool stub(final String name) {
        return new EmbeddedTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description(final Path workdir) {
                return "stub " + name;
            }

            @Override
            public ToolCategory category() {
                return ToolCategory.READ;
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of("type", "object");
            }

            @Override
            public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                return ToolResult.ok("ok");
            }
        };
    }

    private static StructuralCodeGraph graph(final boolean enabled) {
        return new StructuralCodeGraph() {

            @Override
            public boolean enabled() {
                return enabled;
            }

            @Override
            public GraphStore graph(final Path root) {
                return GraphStore.empty();
            }

            @Override
            public Path storeRoot(final Path root) {
                return fakeRoot.resolve("fake-tool-registry-graph");
            }
        };
    }

    private static MavenRepositoryKnowledgeService knowledge(final boolean enabled) {
        return new MavenRepositoryKnowledgeService() {

            @Override
            public boolean knowledgeEnabled(final Path root) {
                return enabled;
            }

            @Override
            public List<String> indexedArtifacts(final Path root) {
                return List.of();
            }

            @Override
            public String knowledge(final Path root, final String coordinate, final String shortName) {
                return "no knowledge indexed for " + coordinate;
            }
        };
    }

    private static ToolRegistry registry(final List<EmbeddedTool> tools, final CodeResolveTool resolve) {
        final var mcp = new McpManager() {
            @Override
            public List<EmbeddedTool> tools() {
                final var all = new ArrayList<EmbeddedTool>(tools);
                if (resolve != null) {
                    all.add(resolve);
                }
                return all;
            }
        };
        return new ToolRegistry(mcp);
    }

    @Test
    void prunesRichToolsWhenResolveUsable() {
        final var resolve = new CodeResolveTool(null, graph(true), knowledge(false));
        final var registry = registry(
                List.of(
                        stub("code_check_graph"),
                        stub("code_find_code"),
                        stub("code_trace_calls"),
                        stub("maven_repository_knowledge"),
                        stub("maven_knowledge_content"),
                        stub("grep")),
                resolve);
        final var wire = registry.toWireTools(testWorkdir);
        final var names = wire.stream().map(t -> t.function().name()).collect(toSet());
        assertEquals(Set.of("code_resolve", "maven_knowledge_content", "grep"), names);
    }

    @Test
    void hidesResolveAndRichToolsWhenUnusable() {
        final var resolve = new CodeResolveTool(null, graph(false), knowledge(false));
        final var registry = registry(
                List.of(
                        stub("code_check_graph"),
                        stub("code_trace_calls"),
                        stub("maven_repository_knowledge"),
                        stub("maven_knowledge_content"),
                        stub("grep")),
                resolve);
        final var wire = registry.toWireTools(testWorkdir);
        final var names = wire.stream().map(t -> t.function().name()).collect(toSet());
        assertEquals(Set.of("maven_knowledge_content", "grep"), names);
    }

    @Test
    void noResolveBeanLeavesToolsUntouched() {
        final var registry =
                registry(List.of(stub("code_trace_calls"), stub("maven_knowledge_content"), stub("grep")), null);
        final var wire = registry.toWireTools(testWorkdir);
        final var names = wire.stream().map(t -> t.function().name()).collect(toSet());
        assertEquals(Set.of("code_trace_calls", "maven_knowledge_content", "grep"), names);
    }

    @Test
    void knowledgeOnlyResolveIsUsable() {
        final var resolve = new CodeResolveTool(null, graph(false), knowledge(true));
        assertTrue(resolve.usable(testWorkdir), "knowledge-only code_resolve should be usable");
        final var registry = registry(
                List.of(stub("code_trace_calls"), stub("maven_repository_knowledge"), stub("maven_knowledge_content")),
                resolve);
        final var names = registry.toWireTools(testWorkdir).stream()
                .map(t -> t.function().name())
                .collect(toSet());
        assertEquals(Set.of("code_resolve", "maven_knowledge_content"), names);
    }

    @Test
    void projectsSessionKnowledgeWithoutUsingOrChangingProcessRoot() {
        final var mavenRoot = fakeRoot.resolve("maven-session");
        final var plainRoot = fakeRoot.resolve("plain-session");
        final var knowledge = new MavenRepositoryKnowledgeService() {

            @Override
            public boolean knowledgeEnabled(final Path root) {
                return mavenRoot.equals(root);
            }
        };
        final var resolve = new CodeResolveTool(null, graph(false), knowledge);
        final var registry = registry(List.of(stub("code_trace_calls"), stub("grep")), resolve);
        assertEquals(
                Set.of("grep"),
                registry.toWireTools(testWorkdir).stream()
                        .map(t -> t.function().name())
                        .collect(toSet()));
        for (final var root : List.of(mavenRoot, plainRoot, mavenRoot)) {
            final var wire = registry.toWireTools(root);
            final var expected = root.equals(mavenRoot) ? Set.of("code_resolve", "grep") : Set.of("grep");
            assertEquals(expected, wire.stream().map(t -> t.function().name()).collect(toSet()));
            assertEquals(root.equals(mavenRoot), registry.describe(root).contains("Maven artifact knowledge"));
            wire.stream()
                    .filter(t -> t.function().name().equals("code_resolve"))
                    .forEach(t -> assertTrue(t.function().description().contains("Maven artifact knowledge")));
            assertTrue(registry.toWireTools(root, t -> !t.name().equals("code_resolve")).stream()
                    .noneMatch(t -> t.function().name().equals("code_trace_calls")));
        }
        assertFalse(registry.describe(testWorkdir).contains("Maven artifact knowledge"));
        assertFalse(resolve.usable(testWorkdir));
    }

    @Test
    void processMavenDecoyDoesNotExposeResolveInPlainSession() {
        final var knowledge = new MavenRepositoryKnowledgeService() {

            @Override
            public boolean knowledgeEnabled(final Path root) {
                return testWorkdir.equals(root);
            }
        };
        final var registry =
                registry(List.of(stub("code_trace_calls")), new CodeResolveTool(null, graph(false), knowledge));
        assertEquals(
                Set.of("code_resolve"),
                registry.toWireTools(testWorkdir).stream()
                        .map(t -> t.function().name())
                        .collect(toSet()));
        assertTrue(registry.toWireTools(fakeRoot.resolve("plain-session")).isEmpty());
        assertFalse(registry.describe(fakeRoot.resolve("plain-session")).contains("code_resolve"));
        assertTrue(registry.describe(testWorkdir).contains("Maven artifact knowledge"));
    }

    @Test
    void rejectsMissingOrUnresolvedRootBeforeProjectingTools() {
        final var registry = registry(List.of(stub("grep")), null);
        assertThrows(NullPointerException.class, () -> registry.toWireTools((Path) null));
        assertThrows(NullPointerException.class, () -> registry.describe(null));
        assertThrows(IllegalArgumentException.class, () -> registry.toWireTools(Path.of("relative")));
        assertThrows(IllegalArgumentException.class, () -> registry.describe(testWorkdir.resolve("child/..")));
    }
}
