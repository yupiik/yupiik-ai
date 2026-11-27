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
package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.ArrayList;
import java.util.List;

@JsonModel
public record StructuralCodeGraphConfiguration(
        @Property(
                defaultValue = "true",
                documentation =
                        "Enable the structural code graph (symbol/call index of the project). Requires a JDK (not a plain JRE) when the Java parser is in use.")
        boolean enabled,

        @Property(
                defaultValue = "false",
                documentation =
                        "Enrich the structural graph with LLM generated conceptual summaries (concept nodes). Uses the main provider by default, or the provider/model/baseUrl/apiKey overrides below when set.")
        boolean enrich,

        @Property(
                documentation =
                        "Provider base URL for the optional concept-node enrichment pass, when it differs from the main provider. Empty falls back to the main provider.")
        String baseUrl,

        @Property(
                documentation =
                        "API key for the optional concept-node enrichment pass, when it differs from the main provider. Empty falls back to the main provider.")
        String apiKey,

        @Property(
                documentation =
                        "Model for the optional concept-node enrichment pass, when it differs from the main provider. Empty falls back to the main provider.")
        String model,

        @Property(
                defaultValue = "5242880L",
                documentation = "Maximum file size in bytes parsed into the graph. Larger files are skipped.")
        long maxFileBytes,

        @Property(
                defaultValue = "java.util.List.of(\"java\")",
                documentation =
                        "File extensions (without dot) parsed into the graph. Only extensions handled by a registered parser are processed.")
        List<String> languageExtensions,

        @Property(
                defaultValue = "\".yca/generated/graph\"",
                documentation =
                        "Directory, relative to the working directory, where the graph cache is stored. It is regenerable and never committed.")
        String storeDir,

        @Property(
                defaultValue = "true",
                documentation =
                        "Persist the structural graph as a compact hand-rolled binary index (index.bin) and serve tool queries "
                                + "directly off it, instead of materializing a full graph store in memory. The index is built "
                                + "at indexation time, memory-mapped at runtime and re-synced incrementally only on changed files/deps.")
        boolean indexEnabled,

        @Property(
                defaultValue = "true",
                documentation =
                        "Memory-map the binary index file (read-only) when loading, minimising the resident working set for "
                                + "very large graphs. Unchanged files are detected by a cheap timestamp check before any SHA-256.")
        boolean indexMemoryMap,

        @Property(
                defaultValue = "true",
                documentation =
                        "Emit a per-source-file markdown doc tree under the graph store, mirroring the project source layout. The docs are deterministic structural extracts (symbols and edges) that agents can grep instead of reading the graph. When 'enrich' is enabled, each doc also gains an async LLM summary.")
        boolean docs,

        @Property(
                defaultValue = "false",
                documentation =
                        "Deep enrichment: additionally ask the LLM for a one-line summary and a crux excerpt per symbol, cached by body content hash so only changed symbols are re-sent. Requires 'enrich'.")
        boolean deep,

        @Property(
                defaultValue = "false",
                documentation =
                        "Disable the automatic per-query graph refresh that stats the tree against the last build fingerprint. Equivalent to Graft's GRAFT_NO_REFRESH=1.")
        boolean noRefresh,

        @Property(
                defaultValue = "true",
                documentation =
                        "Watch the project directory for external file changes (IDE saves, git checkout, etc.) and re-index affected files automatically. Uses WatchService; disabled on platforms without support.")
        boolean watch,

        @Property(
                defaultValue = "10000L",
                documentation =
                        "Milliseconds to coalesce rapid single-file edits into one batched re-index before parsing, for parsers "
                                + "that pay a high per-parse startup cost (the spawned-JVM parser in native image). A lower value "
                                + "makes edits visible sooner at the cost of more parses; the JVM in-process javac parser ignores it "
                                + "and re-indexes each edit immediately.")
        long batchRefreshDebounceMillis,

        @Property(
                defaultValue =
                        "java.util.List.of(\".yca/**\", \"target/**\", \"build/**\", \".git/**\", \".idea/**\", \"node_modules/**\", \"dist/**\", \"generated-sources/**\", \"**/*.class\", \"**/*.jar\")",
                documentation =
                        "Glob patterns (gitignore-ish, relative to the project root) whose files and directories are excluded from "
                                + "the structural code graph: indexing, freshness checks and per-file refresh. A directory matching a "
                                + "pattern causes the whole subtree to be skipped. The defaults skip build outputs and tool metadata "
                                + "(the pattern list merged with the project root .gitignore).")
        List<String> ignoreGlobs,

        @Property(
                documentation =
                        "JDK selection and JVM options for the spawned-JVM Java parser (native image only). Groups "
                                + "{@code jdkHome}, {@code jdkFeature} and {@code javacOptions} under "
                                + "{@code yca.structuralCodeGraph.javac.*}.")
        JavacConfiguration javac) {

    public static final List<String> DEFAULT_IGNORE_GLOBS = List.of(
            ".yca/**",
            "target/**",
            "build/**",
            ".git/**",
            ".idea/**",
            "node_modules/**",
            "dist/**",
            "generated-sources/**",
            "**/*.class",
            "**/*.jar");

    public String resolvedStoreDir(final String workingDir) {
        final var value = storeDir == null || storeDir.isBlank() ? ".yca/generated/graph" : storeDir;
        return workingDir + "/" + value;
    }

    /** The effective ignore list: built-in defaults merged with any config-supplied globs (config wins, appended). */
    public List<String> effectiveIgnoreGlobs() {
        if (ignoreGlobs == null || ignoreGlobs.isEmpty()) {
            return DEFAULT_IGNORE_GLOBS;
        }
        final var merged = new ArrayList<>(DEFAULT_IGNORE_GLOBS);
        merged.addAll(ignoreGlobs);
        return merged;
    }
}
