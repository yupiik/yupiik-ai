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

/**
 * Configuration for the embedded maven-repository-knowledge tool / slash command inside yca. Mirrors the
 * knowledge pipeline settings; the embedded tool maps this onto {@link io.yupiik.maven.knowledge.config.KnowledgeConfig}.
 */
@JsonModel
public record MavenRepositoryKnowledgeConfiguration(
        @Property(
                defaultValue = "true",
                documentation =
                        "Enable the embedded maven-repository-knowledge tool, slash command and session-start skill loading.")
        boolean enabled,

        @Property(defaultValue = "\"~/.m2/repository\"", documentation = "Local Maven repository root to index.")
        String localRepository,

        @Property(defaultValue = "4", documentation = "Maximum artifacts processed concurrently.")
        int concurrency,

        @Property(
                defaultValue = "256",
                documentation =
                        "Hard maximum GAVs per single LLM call. This is the primary bulk-size lever: raising it "
                                + "collapses many serial entry-points calls into few, giving the largest speedup. It also "
                                + "bounds the JSON output size per call.")
        int batchSize,

        @Property(
                defaultValue = "-1",
                documentation = "Entry-points batch sizing: -1 (default) suits each batch greedily to the model's "
                        + "known context window and output budget (from provider config or /v1/models), with a "
                        + "conservative margin so batches under-fill rather than overflow; any positive value is a "
                        + "hard maximum of input content characters per batch. Ignored in favour of the token budget "
                        + "only when a strict character cap is wanted. The token budget is resolved automatically from "
                        + "the model yca is configured with, so -1 needs no tuning.")
        int maxBatchChars,

        @Property(
                defaultValue = "1048576",
                documentation = "Maximum size (in characters) of the content kept per artifact when extracting "
                        + "sources or Javadoc. Larger sources/javadoc content is truncated to this budget. A value "
                        + "<= 0 disables the per-artifact cap.")
        int maxContentBytes,

        @Property(
                defaultValue = "0",
                documentation = "Maximum cumulative content characters acquired across a whole indexing run; when "
                        + "reached the pipeline stops acquiring and reports the remainder as skipped. 0 (default) "
                        + "keeps the run unlimited.")
        int runContentBudget,

        @Property(defaultValue = "true", documentation = "Write the remote -skills.md manifest per artifact.")
        boolean remoteManifest,

        @Property(defaultValue = "false", documentation = "Always regenerate, bypassing the immutable-skip rule.")
        boolean force,

        @Property(
                defaultValue = "false",
                documentation =
                        "Load per-artifact skills at session start by dereferencing the -skills.md manifests of the session's main entry-point artifacts. Off by default so no Maven/LLM work happens before the first prompt; when enabled the warm-up runs (asynchronously unless synchronous is set).")
        boolean skillsAtSessionStart,

        @Property(
                defaultValue = "false",
                documentation =
                        "When true and an LLM is available, enrich per-artifact knowledge with an LLM-written skill "
                                + "file (validated and discarded when not a plausible SKILL.md). Off by default so most "
                                + "changes keep working without the LLM; enable it on instances where you want prose.")
        boolean useLlm,

        @Property(
                defaultValue = "500",
                documentation = "Maximum source files scanned per artifact when building the sources content part.")
        int maxSourceFiles,

        @Property(
                defaultValue = "65536",
                documentation =
                        "Maximum characters kept per source file when building the sources content part; larger files "
                                + "are truncated.")
        int maxSourceFileChars,

        @Property(
                defaultValue = "500",
                documentation = "Maximum javadoc class pages rendered per artifact when building the javadoc parts.")
        int maxJavadocPages,

        @Property(
                defaultValue = "32768",
                documentation =
                        "Maximum characters kept per knowledge-index file when loading the per-artifact knowledge "
                                + "index into the session prompt at startup. Larger indexes are truncated with a marker so "
                                + "the referenced files can still be loaded on demand. 0 or negative disables the cap.")
        int maxSessionSkillChars,

        @Property(
                defaultValue = "false",
                documentation =
                        "Run session-start skill loading synchronously on the caller thread and block until the "
                                + "knowledge index for the project's artifacts is fully provisioned, instead of returning "
                                + "immediately from the async executor. Intended for tests/benchmarks that must ensure the "
                                + "index is completely built (and mounted into the session prompt) before the prompt starts.")
        boolean synchronous,

        @Property(
                defaultValue = "true",
                documentation = "Serve dependency symbol lookups from the compact binary per-jar code graph sidecars "
                        + "(-codegraph.bin) that the indexer writes next to the -knowledge-*.md files, memory-mapped "
                        + "and bounded by codegraphIndexCacheBytes. When disabled, knowledge matching falls back to "
                        + "scanning the -knowledge-index.md text.")
        boolean codegraphIndexEnabled,

        @Property(
                defaultValue = "134217728L",
                documentation =
                        "Upper bound, in bytes, of the resident memory-mapped dependency code graph cache. The cache "
                                + "evicts least-recently-used jars once the total mapped size exceeds this limit. 0 or a "
                                + "negative value keeps the cache unbounded.")
        long codegraphIndexCacheBytes) {}
