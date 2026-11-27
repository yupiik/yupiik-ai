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
package io.yupiik.maven.knowledge.index;

import io.yupiik.maven.knowledge.Gav;
import io.yupiik.maven.knowledge.KnowledgeFactory;
import io.yupiik.maven.knowledge.config.KnowledgeConfig;
import io.yupiik.maven.knowledge.content.ContentAcquired;
import io.yupiik.maven.knowledge.content.ContentAcquirer;
import io.yupiik.maven.knowledge.llm.KnowledgeLlm;
import io.yupiik.maven.knowledge.write.KnowledgeWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Orchestrates indexing of a set of GAVs:
 * <ol>
 *     <li>Resolve + acquire local content (sources/javadoc/doc/classes) for every GAV, plus a deterministic
 *     bytecode-derived entry-point + API view of the main jar.</li>
 *     <li>Immutable-skip release artifacts that already have knowledge; always (re)generate SNAPSHOTs unless force.</li>
 *     <li>Write the whole {@link KnowledgeFactory} knowledge set for every artifact
 *     ({@code -knowledge-index/api/&lt;kind&gt;/skill-*.md} siblings) - always deterministic, never LLM-gated.</li>
 *     <li>Optional, validated LLM refinement of entry points and gold-format skills only when
 *     {@code config.useLlm()} is true and the LLM is available; invalid/placeholder output is discarded.</li>
 * </ol>
 * The pipeline never depends on the LLM producing the knowledge: without it the output is fully deterministic.
 */
public class Indexer {

    private final ContentAcquirer acquirer;
    private final KnowledgeWriter writer;
    private final KnowledgeFactory factory;
    private final KnowledgeLlm llm;
    private final KnowledgeConfig config;
    private final ExecutorService executor;
    private final ReentrantLock lifecycle = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();

    private final Map<Gav, String> expectedFingerprints;

    public Indexer(
            final ContentAcquirer acquirer,
            final KnowledgeWriter writer,
            final KnowledgeFactory factory,
            final KnowledgeLlm llm,
            final KnowledgeConfig config) {
        this(acquirer, writer, factory, llm, config, null);
    }

    /**
     * @param expectedFingerprints optional per-GAV build-declaration fingerprints; a GAV whose stored
     *                             fingerprint equals its expected fingerprint is skipped as unchanged (so a
     *                             pom/gradle change re-indexes only the affected dependencies). When an
     *                             expected fingerprint is absent for a GAV, the legacy immutable-skip applies.
     */
    public Indexer(
            final ContentAcquirer acquirer,
            final KnowledgeWriter writer,
            final KnowledgeFactory factory,
            final KnowledgeLlm llm,
            final KnowledgeConfig config,
            final Map<Gav, String> expectedFingerprints) {
        this.acquirer = acquirer;
        this.writer = writer;
        this.factory = factory;
        this.llm = llm;
        this.config = config;
        this.expectedFingerprints = expectedFingerprints == null ? Map.of() : expectedFingerprints;
        this.executor = Executors.newFixedThreadPool(Math.max(1, config.concurrency()));
    }

    /**
     * Index the given GAVs, reporting each outcome through {@code onResult}. Blocking.
     */
    public IndexResult.Results index(final List<Gav> gavs, final Consumer<IndexResult> onResult) {
        return index(gavs, onResult, null);
    }

    /**
     * Index the given GAVs, reporting each outcome through {@code onResult}. Blocking.
     *
     * @param beforeBatch optional callback invoked before each entry-points batch is processed, with the
     *                    cumulative number of artifacts already reported (skipped + indexed) so a caller can
     *                    surface progress (eg. a per-batch "~NN% (done/total)" line).
     */
    public IndexResult.Results index(
            final List<Gav> gavs, final Consumer<IndexResult> onResult, final IntConsumer beforeBatch) {
        final var results = new CopyOnWriteArrayList<IndexResult>();
        final var toProcess = new ArrayList<Gav>();
        for (final var gav : gavs) {
            checkOpen();
            final var skip = shouldSkip(gav);
            if (skip != null) {
                final var result = IndexResult.skipped(gav, skip);
                report(result, results, onResult);
            } else {
                toProcess.add(gav);
            }
        }
        // Acquire content window by window (evict each before the next) so the in-memory acquired-content map
        // stays bounded to a window no matter the cohort size.
        final var runBudget = config.runContentBudget();
        long consumedChars = 0;
        for (final var window : BatchSizer.windows(toProcess, config)) {
            checkOpen();
            final var contentByGav = new LinkedHashMap<Gav, ContentAcquired>();
            final var eligible = new ArrayList<Gav>();
            var budgetExhausted = false;
            for (final var gav : window) {
                checkOpen();
                if (runBudget > 0 && consumedChars >= runBudget) {
                    budgetExhausted = true;
                    final var result = IndexResult.skipped(gav, "run content budget exceeded");
                    report(result, results, onResult);
                    continue;
                }
                final var acquired = acquirer.acquire(gav);
                checkOpen();
                if (acquired != null && acquired.content() != null) {
                    consumedChars += acquired.content().length();
                    contentByGav.put(gav, acquired);
                } else {
                    contentByGav.put(gav, ContentAcquired.none(gav));
                }
                eligible.add(gav);
            }
            if (!eligible.isEmpty() || !budgetExhausted) {
                for (final var batch : BatchSizer.batches(eligible, contentByGav, config)) {
                    admitWork();
                    if (beforeBatch != null) {
                        beforeBatch.accept(results.size());
                    }
                    checkOpen();
                    runBatch(batch, contentByGav, results, onResult);
                }
            }
            // evict: content is only held for the current window
        }
        checkOpen();
        return IndexResult.Results.summarize(results);
    }

    /**
     * @return a skip reason, or {@code null} when the GAV must be (re)indexed.
     */
    private String shouldSkip(final Gav gav) {
        if (config.force() || isSnapshot(gav)) {
            return null;
        }
        final var expectedFp = expectedFingerprints.get(gav);
        if (expectedFp != null) {
            // build-declaration aware: re-index only when the declaring files changed since last index
            if (!writer.alreadyIndexed(gav)) {
                return null;
            }
            return expectedFp.equals(writer.readFingerprint(gav))
                    ? "unchanged: already indexed with current dependencies"
                    : null; // declaring build files changed -> re-index
        }
        return writer.alreadyIndexed(gav) ? "immutable-skip: release already indexed" : null;
    }

    static boolean isSnapshot(final Gav gav) {
        return gav.version() != null && gav.version().contains("SNAPSHOT");
    }

    private void runBatch(
            final List<Gav> batch,
            final Map<Gav, ContentAcquired> contentByGav,
            final List<IndexResult> results,
            final Consumer<IndexResult> onResult) {
        final var futures = new ArrayList<Future<?>>();
        var complete = false;
        try {
            lifecycle.lock();
            try {
                for (final var gav : batch) {
                    checkOpen();
                    futures.add(executor.submit(() -> indexArtifact(gav, contentByGav.get(gav), results, onResult)));
                }
            } finally {
                lifecycle.unlock();
            }
            for (final var future : futures) {
                future.get();
            }
            complete = true;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (final ExecutionException e) {
            throw new IllegalStateException(e);
        } finally {
            if (!complete) {
                close();
                // shutdownNow removes queued tasks but does not cancel their FutureTask callables.
                // Also clear callables of tasks dequeued concurrently with close().
                futures.forEach(future -> future.cancel(true));
            }
        }
    }

    private void indexArtifact(
            final Gav gav,
            final ContentAcquired acquired,
            final List<IndexResult> results,
            final Consumer<IndexResult> onResult) {
        // Linearize task admission with close(), including a task dequeued during shutdown.
        admitWork();
        final Path path;
        try {
            path = generate(gav, acquired);
        } catch (final CancellationException e) {
            throw e;
        } catch (final RuntimeException e) {
            report(IndexResult.skipped(gav, "failed to index: " + e.getMessage()), results, onResult);
            return;
        }
        checkOpen();
        final var expectedFp = expectedFingerprints.get(gav);
        if (expectedFp != null) {
            writer.writeFingerprint(gav, expectedFp);
        }
        report(IndexResult.indexed(gav, "written " + path), results, onResult);
    }

    private void report(
            final IndexResult result, final List<IndexResult> results, final Consumer<IndexResult> onResult) {
        // Delivery admitted before close may finish afterwards; never call user code under lifecycle.
        admitWork();
        results.add(result);
        if (onResult != null) {
            onResult.accept(result);
        }
    }

    private void admitWork() {
        lifecycle.lock();
        try {
            checkOpen();
        } finally {
            lifecycle.unlock();
        }
    }

    /**
     * Deterministically generate + write the knowledge set for one artifact, optionally refining it with the
     * LLM when {@code useLlm} is enabled. Returns the index file path.
     */
    private Path generate(final Gav gav, final ContentAcquired acquired) {
        checkOpen();
        final var entryPoints = acquirer.entryPoints(gav);
        checkOpen();
        final var apiMarkdown = acquirer.api(gav);
        checkOpen();
        final var moduleConfig = acquirer.moduleConfig(gav);
        checkOpen();
        final var skills = new ArrayList<KnowledgeFactory.SkillDoc>();
        if (config.useLlm()) {
            final var available = llm.available();
            checkOpen();
            if (available) {
                skills.addAll(LlmRefinement.refine(gav, acquired, entryPoints, apiMarkdown, llm));
            }
        }
        checkOpen();
        final var parts = acquirer.contentParts(gav);
        checkOpen();
        final var path = factory.writeArtifact(gav, parts, apiMarkdown, moduleConfig, entryPoints, skills);
        checkOpen();
        final var graph = acquirer.codeGraph(gav);
        checkOpen();
        writer.writeCodeGraph(gav, graph);
        return path;
    }

    /**
     * Request shutdown of the owned executor once, interrupt running work and cancel queued work.
     * Nonblocking, including when called by an indexing callback on an owned worker.
     * This is not proof of termination: work ignoring interruption can still run.
     * Call {@link #awaitTermination(long, TimeUnit)} from the orchestrator to drain it.
     */
    public void close() {
        // Never wait for the submission gate or user work on a cancelling/frontend thread.
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (final var queued : executor.shutdownNow()) {
            if (queued instanceof Future<?> future) {
                future.cancel(false);
            }
        }
    }

    /**
     * Wait for actual owned-executor termination after close(), not just cancellation of its futures.
     * Returns false on timeout; interruption propagates. Does not close borrowed dependencies.
     * Must not be called from an owned task/callback (it would wait for itself).
     */
    public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
        return executor.awaitTermination(timeout, unit);
    }

    private void checkOpen() {
        if (closed.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("indexer closed or interrupted");
        }
    }
}
