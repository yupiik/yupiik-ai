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
package io.yupiik.ia.agent.core.cli;

import io.yupiik.codegraph.index.CodeGraphIndexWriter;
import io.yupiik.codegraph.index.IndexEdge;
import io.yupiik.codegraph.index.IndexGraph;
import io.yupiik.codegraph.index.IndexSymbol;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.maven.knowledge.bytecode.BytecodeAnalyzer;
import io.yupiik.maven.knowledge.bytecode.ClassApiCodeGraphs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Standalone CLI command that builds a compact binary code graph index from a jar (or comma-separated jars) into a
 * {@code code-graph-index} binary file, optionally excluding classes by package prefix. It is a headless complement to
 * the {@code maven-repository-knowledge} sidecar: same format, manual wiring.
 */
@DefaultScoped
@Command(
        name = "code-graph-index",
        description = "Build a compact binary code graph index from a jar (or comma-separated jars).")
public class CodeGraphIndexCommand implements Runnable {

    private final CodeGraphIndexConf conf;
    private final BytecodeAnalyzer bytecodeAnalyzer;

    public CodeGraphIndexCommand(final CodeGraphIndexConf conf) {
        this(conf, new BytecodeAnalyzer());
    }

    public CodeGraphIndexCommand(final CodeGraphIndexConf conf, final BytecodeAnalyzer bytecodeAnalyzer) {
        this.conf = conf;
        this.bytecodeAnalyzer = bytecodeAnalyzer;
    }

    @RootConfiguration("code-graph-index")
    public record CodeGraphIndexConf(
            @Property(
                    required = true,
                    documentation = "Input jar path, or comma-separated list of jar paths to merge into one index.")
            String jars,

            @Property(
                    required = true,
                    documentation = "Output code graph index path (binary, code-graph-index format).")
            String output,

            @Property(
                    defaultValue = "\"\"",
                    documentation = "Optional comma-separated package prefixes to exclude (a class is skipped when its "
                            + "FQN equals the prefix or starts with prefix + '.').")
            String exclude) {}

    @Override
    public void run() {
        final var outputPath = Path.of(conf.output());
        final var excludes = parseExcludes(conf.exclude());
        final var includes = excludes.isEmpty()
                ? (Predicate<String>) x -> true
                : (Predicate<String>) fqn -> excludes.stream().noneMatch(prefix -> matchesPackage(prefix, fqn));
        try {
            final var sources = split(conf.jars());
            if (sources.isEmpty()) {
                throw new IllegalArgumentException("No jar provided.");
            }
            final var symbols = new ArrayList<IndexSymbol>();
            final var edges = new ArrayList<IndexEdge>();
            for (final var source : sources) {
                final var jar = Path.of(source);
                if (!Files.isRegularFile(jar)) {
                    throw new IllegalArgumentException("Not a regular file: " + jar);
                }
                final var graph = ClassApiCodeGraphs.indexGraph(bytecodeAnalyzer.analyze(jar), includes);
                symbols.addAll(graph.symbols());
                edges.addAll(graph.edges());
            }
            final var merged =
                    new IndexGraph(List.copyOf(symbols), List.copyOf(edges), Map.of(), System.currentTimeMillis());
            CodeGraphIndexWriter.write(merged, outputPath);
            System.out.println("Wrote " + symbols.size() + " symbols / " + edges.size() + " edges to " + outputPath);
        } catch (final IOException ioe) {
            throw new IllegalStateException("Cannot build code graph index: " + ioe.getMessage(), ioe);
        }
    }

    private static List<String> split(final String value) {
        final var out = new ArrayList<String>();
        for (final var part : value.split(",")) {
            final var trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static List<String> parseExcludes(final String exclude) {
        return (exclude == null || exclude.isBlank()) ? List.of() : split(exclude);
    }

    private static boolean matchesPackage(final String prefix, final String fqn) {
        return fqn.equals(prefix) || fqn.startsWith(prefix + ".");
    }
}
