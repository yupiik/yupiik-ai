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
package io.yupiik.maven.knowledge.config;

import java.nio.file.Path;

/**
 * Configuration for the {@code maven-repository-knowledge} pipeline. Plain Java on purpose so the
 * knowledge module stays free of any runtime framework; both the embedded yca tool and the MCP server
 * map their own configuration onto this record.
 *
 * @param localRepository local Maven repository root, typically {@code ~/.m2/repository}.
 * @param concurrency     maximum number of artifacts processed concurrently (batching happens before this).
 * @param batchSize       maximum number of GAVs per single LLM call (also the hard fence bounding JSON output).
 * @param remoteManifest  whether to also write the remote {@code -skills.md} manifest per artifact.
 * @param force           always regenerate, bypassing the immutable-skip rule.
 * @param boundedFetch    webfetch limits (when the pom url fallback is used).
 * @param maxBatchChars   batch sizing mode: {@code -1} suits the batch greedily to the model {@code tokenBudget}
 *                        (context input + estimated JSON output, with a conservative margin, falling back to
 *                        GAV-count batching when the budget is unknown); {@code > 0} is a hard character cap on
 *                        the cumulative input content; {@code 0} keeps GAV-count batching only. Whichever limit
 *                        ({@code batchSize} or the selected character/budget limit) is reached first closes a batch.
 * @param tokenBudget     model token limits for the auto ({@code -1}) mode; ignored otherwise.
 * @param maxContentBytes maximum content size kept per artifact (sources/javadoc extraction and budget stop);
 *                        an artifact whose acquired content still exceeds it is skipped. {@code <= 0} disables the cap.
 * @param runContentBudget maximum cumulative content characters to acquire across a whole run; when exceeded the
 *                        indexer stops and reports the remainder as skipped. {@code 0} keeps it unlimited.
 * @param useLlm          whether to optionally refine knowledge with the LLM (gold-format skills). When
 *                        {@code false} (default) no LLM call happens; validation always discards placeholder
 *                        output so a real deterministic doc is guaranteed either way.
 * @param maxSourceFiles  maximum number of source files acquired per artifact from a {@code -sources.jar}.
 * @param maxSourceFileChars maximum characters kept per source file.
 * @param maxJavadocPages maximum javadoc class pages extracted per artifact from a {@code -javadoc.jar}.
 */
public record KnowledgeConfig(
        Path localRepository,
        int concurrency,
        int batchSize,
        boolean remoteManifest,
        boolean force,
        BoundedFetch boundedFetch,
        int maxBatchChars,
        TokenBudget tokenBudget,
        int maxContentBytes,
        int runContentBudget,
        boolean useLlm,
        int maxSourceFiles,
        int maxSourceFileChars,
        int maxJavadocPages) {

    public KnowledgeConfig(
            final Path localRepository,
            final int concurrency,
            final int batchSize,
            final boolean remoteManifest,
            final boolean force,
            final BoundedFetch boundedFetch,
            final int maxBatchChars,
            final TokenBudget tokenBudget,
            final int maxContentBytes,
            final int runContentBudget) {
        this(
                localRepository,
                concurrency,
                batchSize,
                remoteManifest,
                force,
                boundedFetch,
                maxBatchChars,
                tokenBudget,
                maxContentBytes,
                runContentBudget,
                false,
                500,
                64 * 1024,
                500);
    }

    public KnowledgeConfig(
            final Path localRepository,
            final int concurrency,
            final int batchSize,
            final boolean remoteManifest,
            final boolean force,
            final BoundedFetch boundedFetch,
            final int maxBatchChars,
            final TokenBudget tokenBudget) {
        this(
                localRepository,
                concurrency,
                batchSize,
                remoteManifest,
                force,
                boundedFetch,
                maxBatchChars,
                tokenBudget,
                1024 * 1024,
                0);
    }

    public static KnowledgeConfig defaults(final Path localRepository) {
        return new KnowledgeConfig(
                resolveLocalRepository(localRepository),
                4,
                8,
                true,
                false,
                BoundedFetch.of(1, 30_000L, 65536),
                0,
                null,
                1024 * 1024,
                0,
                false,
                500,
                64 * 1024,
                500);
    }

    public KnowledgeConfig withUseLlm(final boolean useLlm) {
        return new KnowledgeConfig(
                localRepository,
                concurrency,
                batchSize,
                remoteManifest,
                force,
                boundedFetch,
                maxBatchChars,
                tokenBudget,
                maxContentBytes,
                runContentBudget,
                useLlm,
                maxSourceFiles,
                maxSourceFileChars,
                maxJavadocPages);
    }

    private static Path resolveLocalRepository(final Path localRepository) {
        if (localRepository != null) {
            return localRepository;
        }
        final var home = System.getProperty("user.home", ".");
        return Path.of(home, ".m2", "repository");
    }

    /**
     * Limits applied when the indexer falls back to fetching documentation from a pom {@code <url>}.
     *
     * @param maxPerMinute maximum webfetch requests per minute; &lt;= 0 disables the rate limit.
     * @param timeoutMs    per-request timeout in milliseconds.
     * @param maxBytes     maximum bytes kept of a fetched page.
     */
    public record BoundedFetch(int maxPerMinute, long timeoutMs, int maxBytes) {

        public static BoundedFetch of(final int maxPerMinute, final long timeoutMs, final int maxBytes) {
            return new BoundedFetch(maxPerMinute, timeoutMs, maxBytes);
        }
    }
}
