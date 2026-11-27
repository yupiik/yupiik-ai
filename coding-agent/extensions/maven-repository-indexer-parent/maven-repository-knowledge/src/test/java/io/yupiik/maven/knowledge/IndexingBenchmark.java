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
package io.yupiik.maven.knowledge;

import io.yupiik.maven.knowledge.config.KnowledgeConfig;
import io.yupiik.maven.knowledge.config.TokenBudget;
import io.yupiik.maven.knowledge.content.ContentAcquirer;
import io.yupiik.maven.knowledge.content.LocalRepository;
import io.yupiik.maven.knowledge.content.WebFetch;
import io.yupiik.maven.knowledge.index.Indexer;
import io.yupiik.maven.knowledge.llm.KnowledgeLlm;
import io.yupiik.maven.knowledge.write.KnowledgeWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Throwaway benchmark (not a correctness test): exercises the {@link Indexer} hot path over a project-scoped
 * cohort (the "hop" project dependencies that are present in the local repository) and reports how long it
 * takes linearly (concurrency=1) vs concurrently (concurrency=4).
 *
 * <p>No artifacts are written to the real repository: the skill/manifest writers target a temporary directory.
 * The LLM is simulated with a controllable fixed latency so the figures isolate the pipeline structure (network
 * LLM latency dominates the real cost), not provider variance. The diagnostic and the plan drive the upcoming
 * concurrency/virtual-thread change to {@link Indexer}.
 */
@Disabled("benchmark, not a unit test - run explicitly")
class IndexingBenchmark {

    private static final long ENTRY_POINT_MS = 400;
    private static final long SYNTHESIZE_MS = 250;

    @Test
    void sequentialVsConcurrentVsBulkSize(@TempDir final Path output) throws Exception {
        final var hopProject = Path.of(System.getProperty("yca.hopProject", "/opt/rmannibucau/dev/Github/hop"));
        final var m2 = Path.of(System.getProperty("user.home", "."), ".m2", "repository");
        final var cohort = cohort(hopProject, m2);

        System.out.println("[bench] hop project : " + hopProject);
        System.out.println("[bench] local repo  : " + m2);
        System.out.println("[bench] cohort size : " + cohort.size());
        if (cohort.isEmpty()) {
            System.out.println("[bench] no locally-present hop dependencies found - nothing to benchmark");
            return;
        }

        // linear baseline: concurrency=1, old count-based batching
        final long t1 = System.nanoTime();
        final var seq = runIndexer(config(m2, 1, 8, 0), cohort, m2, output);
        final long seqMs = toMs(t1);

        System.out.println();
        System.out.println("=== maven-repository-knowledge indexing benchmark (no writes, hop cohort) ===");
        System.out.printf(
                "  linear  (c=1, batchSize=8,  maxChars=0)   : %6d ms | indexed=%d skipped=%d%n",
                seqMs, seq.indexedCount(), seq.skippedCount());
        System.out.println("  bulk size effect (concurrency=4, batchSize=256, model contextWindow=1000000 tokens):");
        // maxChars is input characters; ~4 chars = 1 token. 128_000 chars ~ 32k tokens (conservative bottom of
        // min(32k, context) guidance); larger pushes toward the model's 1M-token window for the fastest result.
        final var budgets = new int[] {0, 32_000, 128_000, 512_000};
        for (final var maxChars : budgets) {
            final long t = System.nanoTime();
            final var r = runIndexer(config(m2, 4, 256, maxChars), cohort, m2, output);
            final long ms = toMs(t);
            System.out.printf(
                    "    maxBatchChars=%7d : %6d ms | indexed=%d skipped=%d | %3d bulk calls | speedup x%.2f over "
                            + "linear%n",
                    maxChars, ms, r.indexedCount(), r.skippedCount(), r.calls, speedup(seqMs, ms));
        }
        // auto-deduce mode: maxBatchChars=-1 suits each batch to the model budget via the greedy sizer.
        // Mirrors the real yca home config (~/.yupiik/coding-agent/configuration.json): deepseek-v4-flash,
        // contextWindow=1000000, output budget 32000, concurrency=4.
        {
            final long t = System.nanoTime();
            final var r = runIndexer(config(m2, 4, 256, -1, new TokenBudget(1_000_000, 32_000)), cohort, m2, output);
            final long ms = toMs(t);
            System.out.printf(
                    "    maxBatchChars= -1 (auto, ctx=1M, out=32k) : %6d ms | indexed=%d skipped=%d | %3d bulk calls | speedup x%.2f over "
                            + "linear%n",
                    ms, r.indexedCount(), r.skippedCount(), r.calls, speedup(seqMs, ms));
        }
        System.out.printf(
                "  simulated : entryPoints=%dms/bulk-call, synthesize=%dms/artifact%n", ENTRY_POINT_MS, SYNTHESIZE_MS);
        System.out.println();
    }

    private static double speedup(final long base, final long run) {
        return base / (double) Math.max(1, run);
    }

    private static long toMs(final long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private KnowledgeConfig config(final Path m2, final int concurrency, final int batchSize, final int maxBatchChars) {
        return config(m2, concurrency, batchSize, maxBatchChars, null);
    }

    private KnowledgeConfig config(
            final Path m2,
            final int concurrency,
            final int batchSize,
            final int maxBatchChars,
            final TokenBudget budget) {
        final var repo = new LocalRepository(m2);
        return new KnowledgeConfig(
                repo.root(),
                concurrency,
                batchSize,
                true,
                false,
                KnowledgeConfig.BoundedFetch.of(1, 30_000L, 65_536),
                maxBatchChars,
                budget);
    }

    private Results runIndexer(final KnowledgeConfig config, final List<Gav> cohort, final Path m2, final Path parent)
            throws IOException {
        final var output = Files.createTempDirectory(parent, "bench-out-");
        final var llm = new TimedLlm();
        final var writer = new KnowledgeWriter(output);
        final var indexer = new Indexer(
                new ContentAcquirer(new LocalRepository(m2), noOpWebFetch(), config),
                writer,
                new KnowledgeFactory(writer),
                llm,
                config);
        try {
            final var results = indexer.index(cohort, null);
            return new Results(results.indexedCount(), results.skippedCount(), llm.entryPointCalls());
        } finally {
            indexer.close();
        }
    }

    private record Results(int indexedCount, int skippedCount, int calls) {}

    private static WebFetch noOpWebFetch() {
        return (url, timeout, bytes) -> "";
    }

    /**
     * Simulated LLM: fixed latency per call, isolated from any real provider so timing reflects the pipeline
     * structure only.
     */
    private static final class TimedLlm implements KnowledgeLlm {

        private int entryPointCalls;

        int entryPointCalls() {
            return entryPointCalls;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public Map<Gav, List<String>> entryPoints(final Map<Gav, String> contentByGav) {
            entryPointCalls++;
            sleep(ENTRY_POINT_MS);
            final var out = new LinkedHashMap<Gav, List<String>>();
            for (final var gav : contentByGav.keySet()) {
                out.put(gav, List.of(gav.groupId() + "." + gav.artifactId() + ".Main"));
            }
            return out;
        }

        @Override
        public List<Gav> suggestEntryGavs(final List<Gav> candidates) {
            return candidates;
        }

        @Override
        public String synthesizeSkill(
                final Gav gav, final String name, final String content, final List<String> entryPoints) {
            sleep(SYNTHESIZE_MS);
            return "---\nname: " + name + "\ndescription: bench\n---\n# " + name + "\n";
        }

        private static void sleep(final long ms) {
            try {
                Thread.sleep(ms);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * Project-scoped cohort: all distinct {@code groupId:artifactId} pairs declared across the hop project's
     * poms (dependencies, plugins, extensions), each resolved to the highest locally-present version.
     */
    static List<Gav> cohort(final Path hopProject, final Path m2) {
        final var coords = new LinkedHashSet<String>();
        try (final var stream = Files.walk(hopProject)) {
            stream.filter(p ->
                            Files.isRegularFile(p) && p.getFileName().toString().equals("pom.xml"))
                    .forEach(pom -> collectCoordinates(pom, coords));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        final var out = new ArrayList<Gav>();
        for (final var coord : coords) {
            final var slash = coord.indexOf('/');
            final var group = coord.substring(0, slash);
            final var artifact = coord.substring(slash + 1);
            final var version = highestVersion(m2, group, artifact);
            if (version != null) {
                out.add(Gav.of(group, artifact, version));
            }
        }
        out.sort((l, r) -> l.groupId().compareToIgnoreCase(r.groupId()));
        return out;
    }

    private static final Pattern COORD =
            Pattern.compile("<groupId>\\s*([^<]+?)\\s*</groupId>\\s*<artifactId>\\s*([^<]+?)\\s*</artifactId>");

    private static void collectCoordinates(final Path pom, final Set<String> into) {
        final String text;
        try {
            text = new String(Files.readAllBytes(pom), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        final var matcher = COORD.matcher(text);
        while (matcher.find()) {
            addIfExternal(into, matcher.group(1), matcher.group(2));
        }
    }

    private static void addIfExternal(final Set<String> into, final String group, final String artifact) {
        if (group == null
                || group.isBlank()
                || artifact == null
                || artifact.isBlank()
                || artifact.startsWith("${")
                || artifact.equals("hop")
                || artifact.endsWith("-parent")) {
            return;
        }
        into.add(group.trim() + "/" + artifact.trim());
    }

    private static String highestVersion(final Path m2, final String group, final String artifact) {
        final var dir = m2.resolve(group.replace('.', '/')).resolve(artifact);
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (final var stream = Files.list(dir)) {
            return stream.filter(Files::isDirectory)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .sorted()
                    .reduce((l, r) -> r)
                    .orElse(null);
        } catch (final IOException e) {
            return null;
        }
    }
}
