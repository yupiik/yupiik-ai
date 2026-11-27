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
package io.yupiik.ia.agent.core.maven;

import io.yupiik.codegraph.index.CodeGraphIndex;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.maven.knowledge.Gav;
import io.yupiik.maven.knowledge.KnowledgeFactory;
import io.yupiik.maven.knowledge.config.KnowledgeConfig;
import io.yupiik.maven.knowledge.content.ContentAcquirer;
import io.yupiik.maven.knowledge.content.LocalRepository;
import io.yupiik.maven.knowledge.content.WebFetch;
import io.yupiik.maven.knowledge.fingerprint.ProjectFingerprint;
import io.yupiik.maven.knowledge.index.IndexResult;
import io.yupiik.maven.knowledge.index.Indexer;
import io.yupiik.maven.knowledge.llm.KnowledgeLlm;
import io.yupiik.maven.knowledge.write.KnowledgeWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Shared pipeline for the embedded Maven repository knowledge feature. The embedded tool, the
 * {@code /maven-repository-knowledge} slash command and the session-start skill loader all delegate
 * to this service so the indexing logic lives in a single, framework-agnostic place.
 * All operations require an explicit project path for dependency selection and fingerprints.
 * Artifact storage and file caches are intentionally shared across all projects.
 */
@ApplicationScoped
public class MavenRepositoryKnowledgeService {

    private final CodingAgentConfiguration configuration;
    private final KnowledgeLlm llm;
    private final ExecutorService executor;
    private final ExtendedHttpClient httpClient;
    private final AtomicReference<List<String>> sessionSkillBlocks = new AtomicReference<>(List.of());
    private final AtomicBoolean sessionSkillsRequested = new AtomicBoolean();
    private final Set<IndexRun> activeIndexes = new HashSet<>();
    private volatile IndexRun lastCompletedIndex;
    private final CodeGraphIndexCache codeGraphCache = new CodeGraphIndexCache();
    private final KnowledgeFileCache knowledgeFileCache = new KnowledgeFileCache();

    /** How many coordinates are previewed in the {@code starting to index ...} acknowledgement. */
    private static final int STARTING_LIST_CAP = 20;

    public MavenRepositoryKnowledgeService(
            final CodingAgentConfiguration configuration,
            final KnowledgeLlm llm,
            final ExecutorService executor,
            final ExtendedHttpClient httpClient) {
        this.configuration = configuration;
        this.llm = llm;
        this.executor = executor;
        this.httpClient = httpClient;
    }

    protected MavenRepositoryKnowledgeService() {
        this.configuration = null;
        this.llm = null;
        this.executor = null;
        this.httpClient = null;
    }

    /**
     * Kick off an asynchronous warm-up of the per-session artifact skills once indexed artifacts are
     * discovered, so the first system prompt does not block on a slow LLM call. When the
     * {@code synchronous} config flag is set, the warm-up runs on the calling thread and blocks until the
     * index is fully provisioned (used by tests/benchmarks that must guarantee the index is finished).
     */
    /** Optional session-start provisioning, with the root captured before scheduling any work. */
    public void warmUp(final Path projectRoot) {
        final var root = projectRoot(projectRoot);
        if (!enabled(root)) {
            return;
        }
        if (!configuration.mavenRepositoryKnowledge().skillsAtSessionStart()) {
            return;
        }
        final var operationLlm = operationLlm(root, null);
        if (operationLlm == null || !operationLlm.available()) {
            return;
        }
        if (configuration.mavenRepositoryKnowledge().synchronous()) {
            loadSessionSkillsLazily(root, operationLlm);
        } else {
            executor.execute(() -> loadSessionSkillsLazily(root, operationLlm));
        }
    }

    /**
     * The per-artifact knowledge is intentionally <strong>not</strong> pre-mounted as {@code <skill>} blocks
     * in the system prompt (a "lazy mount"). Mounting every indexed dependency's {@code -knowledge-index.md}
     * added a ~9k token baseline to every message even for local-symbol questions, slowing graph+knowledge
     * sessions. Instead, artifacts are enumerated with {@code code_resolve} (op=resolve/list) and their
     * content dereferenced on demand via {@code maven_knowledge_content}.
     *
     * <p>Indexing still runs (see {@link #onStart(io.yupiik.fusion.framework.api.lifecycle.Start)}) so the
     * on-demand path and {@code code_resolve} have real data to serve; only the eager prompt mounting is
     * disabled. Returns an always-empty list so callers (e.g. {@code SystemPromptBuilder}) mount nothing.
     */
    /** Root-aware prompt entrypoint; knowledge remains on-demand, never eagerly mounted. */
    public List<String> sessionSkillBlocks(final Path root) {
        Objects.requireNonNull(root, "workdir");
        return List.of();
    }

    private void loadSessionSkillsLazily(final Path root, final KnowledgeLlm operationLlm) {
        try {
            synchronouslyProvisioned(root, operationLlm);
            final var suggested = suggest(operationLlm);
            final var config = knowledgeConfig();
            final var writer = new KnowledgeWriter(config.localRepository());
            final var maxChars = configuration.mavenRepositoryKnowledge().maxSessionSkillChars();
            final var blocks = new ArrayList<String>();
            for (final var gav : suggested) {
                final var file = writer.indexFile(gav);
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    final var raw = Files.readString(file, StandardCharsets.UTF_8);
                    blocks.add("<skill name=\""
                            + gav.artifactId()
                            + "-index\">\n"
                            + truncate(raw, maxChars)
                            + "\n</skill>");
                } catch (final IOException e) {
                    // skip unreadable knowledge index
                }
            }
            sessionSkillBlocks.set(List.copyOf(blocks));
        } catch (final RuntimeException e) {
            sessionSkillBlocks.set(List.of());
        }
    }

    /**
     * In synchronous mode, block until the project's artifacts are fully indexed so the mounted per-artifact
     * skills are complete before the prompt starts. When no index is currently in flight, a baseline index is
     * started over the project dependencies and awaited (bounded).
     *
     * @return {@code true} when the index is known to be complete before mounting skills.
     */
    private boolean synchronouslyProvisioned(final Path root, final KnowledgeLlm operationLlm) {
        final var synchronous = configuration != null
                && configuration.mavenRepositoryKnowledge() != null
                && configuration.mavenRepositoryKnowledge().synchronous();
        if (!synchronous) {
            return false;
        }
        var run = currentIndex(root);
        if (!run.inFlight() && !anyIndexed(operationLlm)) {
            final var targets = projectDependencies(operationLlm).stream()
                    .map(Gav::coordinate)
                    .toList();
            if (!targets.isEmpty()) {
                run = doStartIndex(root, operationLlm, targets, false, null, null, null);
            }
        }
        awaitIdle(run);
        return true;
    }

    private boolean anyIndexed(final KnowledgeLlm operationLlm) {
        final var config = knowledgeConfig();
        final var writer = new KnowledgeWriter(config.localRepository());
        return projectDependencies(operationLlm).stream().anyMatch(writer::alreadyIndexed);
    }

    private void awaitIdle(final IndexRun run) {
        try {
            // generous upper bound for a large project
            run.awaitCompletion(15L * 60L, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Keep the first {@code maxChars} chars of an index, adding a marker when it was cut so the rest can be loaded on demand. */
    private static String truncate(final String raw, final int maxChars) {
        if (maxChars <= 0 || raw.length() <= maxChars) {
            return raw;
        }
        final var marker = "\n<!-- index truncated; load the referenced sibling files on demand -->\n";
        return raw.substring(0, maxChars) + marker;
    }

    private Path projectRoot(final Path root) {
        return Objects.requireNonNull(root, "workdir");
    }

    private KnowledgeLlm operationLlm(final Path root, final ChatSettings settings) {
        return llm instanceof YcaKnowledgeLlm yca ? yca.forProject(root, settings) : llm;
    }

    private boolean enabled(final Path root) {
        return configuration != null
                && configuration.mavenRepositoryKnowledge() != null
                && configuration.mavenRepositoryKnowledge().enabled()
                && root != null
                && hasBuildFile(root);
    }

    /**
     * Only act when the current project is a Maven or Gradle project (a pom.xml or a build.gradle).
     * This keeps the embedded knowledge feature quiet when working in a plain directory.
     */
    private boolean hasBuildFile(final Path dir) {
        if (Files.exists(dir.resolve("pom.xml"))) {
            return true;
        }
        return Files.exists(dir.resolve("build.gradle"))
                || Files.exists(dir.resolve("build.gradle.kts"))
                || Files.exists(dir.resolve("settings.gradle"))
                || Files.exists(dir.resolve("settings.gradle.kts"));
    }

    /**
     * Choose the session's main entry-point artifacts to mount in the prompt: only artifacts that exist
     * locally AND are already indexed are considered, so their ({@code -knowledge-index.md}) can be loaded
     * without re-indexing; the LLM picks the main ones (silently returning all when structured output is
     * unsupported).
     */
    private List<Gav> suggest(final KnowledgeLlm operationLlm) {
        final var config = knowledgeConfig();
        final var repo = new LocalRepository(config.localRepository());
        final var writer = new KnowledgeWriter(config.localRepository());
        final var candidates = new ArrayList<Gav>();
        for (final var gav : projectDependencies(operationLlm)) {
            if (writer.alreadyIndexed(gav) && repo.resolve(gav).present()) {
                candidates.add(gav);
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        if (!operationLlm.available()) {
            return candidates;
        }
        try {
            return operationLlm.suggestEntryGavs(candidates);
        } catch (final RuntimeException e) {
            return candidates;
        }
    }

    /**
     * Public flavour of {@link #enabled()}: whether the embedded Maven knowledge source is usable for the
     * current project (feature enabled and a build file is present). Used by tools that must decide whether
     * the knowledge source will actually serve data before advertising it or spending a round trip.
     */
    public boolean knowledgeEnabled(final Path root) {
        return enabled(projectRoot(root));
    }

    /**
     * Enumerates the project's dependency coordinates that are already indexed (an index file exists and the
     * artifact resolves to a local jar). This backs the {@code list} operation of the merged resolve tool so
     * the model can discover which artifacts' knowledge is available without a prompt-time manifest.
     *
     * @return the sorted list of {@code groupId:artifactId:version} coordinates with knowledge already indexed.
     */
    public List<String> indexedArtifacts(final Path projectRoot) {
        final var root = projectRoot(projectRoot);
        if (!enabled(root)) {
            return List.of();
        }
        final var config = knowledgeConfig();
        final var repo = new LocalRepository(config.localRepository());
        final var writer = new KnowledgeWriter(config.localRepository());
        final var out = new ArrayList<String>();
        for (final var gav : projectDependencies(operationLlm(root, null))) {
            if (writer.alreadyIndexed(gav) && repo.resolve(gav).present()) {
                out.add(gav.coordinate());
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    /**
     * Resolve the project's jar-resolving dependency set (direct + transitive + build plugins/extensions)
     * through the LLM, keeping only coordinates that resolve to a regular local {@code .jar}. The returned
     * set drives the no-arg {@code refresh} baseline and the session-start suggestion instead of a whole-repo
     * walk, so the context stays bounded to what the project actually uses.
     */
    private List<Gav> projectDependencies(final KnowledgeLlm operationLlm) {
        if (operationLlm == null) {
            return List.of();
        }
        final var config = knowledgeConfig();
        final var repo = new LocalRepository(config.localRepository());
        final var out = new ArrayList<Gav>();
        for (final var gav : operationLlm.resolveProjectDeps()) {
            final var resolved = repo.resolve(gav);
            final var main = resolved.mainJar();
            if (resolved.present() && main != null && Files.isRegularFile(main)) {
                out.add(gav);
            }
        }
        return out;
    }

    /**
     * Index the given GAVs. Returns a human readable report.
     */
    /**
     * Index the given GAVs with the requesting session's provider/model selection. Returns a human
     * readable report. When {@code settings} is non-null and the backing {@link YcaKnowledgeLlm} is
     * used, an operation-local adapter captures the session's provider/model selection; null falls
     * back to the process default without changing another run's settings.
     */
    public String index(
            final Path projectRoot, final List<String> gavStrings, final boolean force, final ChatSettings settings) {
        final var root = projectRoot(projectRoot);
        if (!enabled(root)) {
            return "maven repository knowledge is disabled";
        }
        return doIndex(root, operationLlm(root, settings), gavStrings, force);
    }

    private String doIndex(
            final Path root, final KnowledgeLlm operationLlm, final List<String> gavStrings, final boolean force) {
        if (!enabled(root)) {
            return "maven repository knowledge is disabled";
        }
        final var config = knowledgeConfig(force, operationLlm);
        final var effective = new KnowledgeConfig(
                config.localRepository(),
                config.concurrency(),
                config.batchSize(),
                config.remoteManifest(),
                force,
                config.boundedFetch(),
                config.maxBatchChars(),
                config.tokenBudget(),
                config.maxContentBytes(),
                config.runContentBudget(),
                config.useLlm(),
                config.maxSourceFiles(),
                config.maxSourceFileChars(),
                config.maxJavadocPages());
        final var gavs = new ArrayList<Gav>();
        final var problems = new StringBuilder();
        for (final var raw : gavStrings) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                gavs.add(Gav.parse(raw));
            } catch (final IllegalArgumentException e) {
                if (problems.length() > 0) {
                    problems.append(", ");
                }
                problems.append(raw).append(" (invalid)");
            }
        }
        final var sb = new StringBuilder();
        if (problems.length() > 0) {
            sb.append("skipped invalid GAVs: ").append(problems).append('\n');
        }
        final var indexer = newIndexer(effective, fingerprints(root, gavs), operationLlm);
        try {
            final var results = indexer.index(gavs, result -> {});
            sb.append("indexed ")
                    .append(results.indexedCount())
                    .append(", skipped ")
                    .append(results.skippedCount());
            if (!results.items().isEmpty()) {
                sb.append('\n');
                final var limit = Math.min(results.items().size(), 50);
                for (var i = 0; i < limit; i++) {
                    final var r = results.items().get(i);
                    sb.append("- ")
                            .append(r.gav().coordinate())
                            .append(r.indexed() ? " indexed" : r.skipped() ? " skipped: " + r.reason() : " missing")
                            .append('\n');
                }
                if (results.items().size() > limit) {
                    sb.append("- ... and ")
                            .append(results.items().size() - limit)
                            .append(" more\n");
                }
            }
            return sb.toString().stripTrailing();
        } finally {
            indexer.close();
        }
    }

    private static void awaitIndexer(final Indexer indexer) {
        // Only the service-owned orchestrator drains. Frontend cancellation must never wait here.
        var interrupted = false;
        try {
            while (true) {
                try {
                    if (indexer.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
                        return;
                    }
                } catch (final InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Starts {@link #refreshJob} and returns only its immediate acknowledgement for detached frontends. */
    public List<String> refresh(
            final Path projectRoot,
            final String gav,
            final Consumer<String> progress,
            final Consumer<String> onFinished,
            final ChatSettings settings) {
        return acknowledgement(refreshJob(projectRoot, gav, progress, onFinished, settings));
    }

    /**
     * Refreshes project dependencies, or force-regenerates one explicit GAV. Returns the exact operation,
     * not a subsequent status lookup. Disabled/empty operations return an already completed handle.
     * Progress and the optional success-only callback run on indexing threads; use {@link IndexRun#completion()}
     * to observe failure/cancellation and to keep a requesting frontend alive until cleanup.
     * Dependency discovery is performed before submission, as in {@link #refresh}.
     */
    public IndexRun refreshJob(
            final Path projectRoot,
            final String gav,
            final Consumer<String> progress,
            final Consumer<String> onFinished,
            final ChatSettings settings) {
        final var root = projectRoot(projectRoot);
        if (!enabled(root)) {
            return new IndexRun(root, 0, settings, List.of("maven repository knowledge is disabled"));
        }
        final var operationLlm = operationLlm(root, settings);
        final List<String> targets;
        final boolean force;
        if (gav == null || gav.isBlank()) {
            targets = projectDependencies(operationLlm).stream()
                    .map(Gav::coordinate)
                    .toList();
            force = false;
        } else {
            targets = List.of(Gav.parse(gav.strip()).coordinate());
            force = true;
        }
        return doStartIndex(root, operationLlm, targets, force, progress, onFinished, settings);
    }

    /** Starts {@link #startIndexJob} and returns only its acknowledgement for detached frontends. */
    public List<String> startIndex(
            final Path projectRoot,
            final List<String> gavs,
            final boolean force,
            final Consumer<String> progress,
            final Consumer<String> onFinished,
            final ChatSettings settings) {
        return acknowledgement(startIndexJob(projectRoot, gavs, force, progress, onFinished, settings));
    }

    /**
     * Starts an asynchronous index with immutable operation-local root/settings. Admission rejects another
     * active run for the same root. Submission failures settle the returned handle exceptionally rather than
     * losing the operation. Invalid coordinates are skipped, as for {@link #startIndex}.
     * Neither this operation nor its cancellation closes the shared executor or HTTP client.
     */
    public IndexRun startIndexJob(
            final Path projectRoot,
            final List<String> gavs,
            final boolean force,
            final Consumer<String> progress,
            final Consumer<String> onFinished,
            final ChatSettings settings) {
        final var root = projectRoot(projectRoot);
        return doStartIndex(root, operationLlm(root, settings), gavs, force, progress, onFinished, settings);
    }

    private List<String> acknowledgement(final IndexRun run) {
        // Preserve immediate failure reporting (notably executor rejection) for detached callers.
        if (run.completion.isCompletedExceptionally()) {
            try {
                run.completion.join();
            } catch (final CompletionException e) {
                if (e.getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (e.getCause() instanceof Error error) {
                    throw error;
                }
                throw e;
            }
        }
        return run.messages();
    }

    private IndexRun doStartIndex(
            final Path root,
            final KnowledgeLlm operationLlm,
            final List<String> gavs,
            final boolean force,
            final Consumer<String> progress,
            final Consumer<String> onFinished,
            final ChatSettings settings) {
        if (!enabled(root)) {
            return new IndexRun(root, 0, settings, List.of("maven repository knowledge is disabled"));
        }
        if (gavs == null || gavs.isEmpty()) {
            return new IndexRun(root, 0, settings, List.of("nothing to index"));
        }
        final var config = knowledgeConfig(force, operationLlm);
        final var effective = new KnowledgeConfig(
                config.localRepository(),
                config.concurrency(),
                config.batchSize(),
                config.remoteManifest(),
                force,
                config.boundedFetch(),
                config.maxBatchChars(),
                config.tokenBudget(),
                config.maxContentBytes(),
                config.runContentBudget());
        final List<Gav> parsed = new ArrayList<>();
        for (final var raw : gavs) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                parsed.add(Gav.parse(raw.strip()));
            } catch (final IllegalArgumentException ignored) {
                // skip invalid coordinates; they surface as nothing to do for them
            }
        }
        if (parsed.isEmpty()) {
            return new IndexRun(root, 0, settings, List.of("no valid GAVs to index"));
        }
        final var total = parsed.size();
        final var run = new IndexRun(
                root,
                total,
                settings,
                List.of("started indexing " + total + " artifact(s)", "starting to index " + coordsPreview(parsed)));
        run.cleanup = () -> finishIndex(run);
        synchronized (activeIndexes) {
            for (final var active : activeIndexes) {
                if (Objects.equals(root, active.root)) {
                    throw new IllegalStateException("index already in progress for " + root);
                }
            }
            activeIndexes.add(run);
        }
        run.start(
                executor,
                () -> {
                    run.checkCancelled();
                    final var indexer = newIndexer(effective, fingerprints(root, parsed), operationLlm);
                    try {
                        run.attachIndexer(indexer);
                        run.checkCancelled();
                        final var results = indexer.index(
                                parsed, result -> run.recordResult(), batch -> fireProgress(run, progress));
                        fireProgress(run, progress);
                        return summarize(results);
                    } finally {
                        run.drainIndexer(indexer);
                    }
                },
                onFinished);
        return run;
    }

    private void finishIndex(final IndexRun run) {
        synchronized (activeIndexes) {
            activeIndexes.remove(run);
            if (run.summary() != null) {
                lastCompletedIndex = run;
            }
        }
    }

    /**
     * Report the current index state: whether a run is in progress with its progress, the last completed
     * run summary (when any), and how many per-artifact skills are loaded for the session.
     */
    /** Reports this project's active run or the single last completed summary when it belongs to this project. */
    public List<String> status(final Path root) {
        return indexStatus(projectRoot(root));
    }

    private List<String> indexStatus(final Path root) {
        final var run = currentIndex(root);
        final var completed = lastCompletedIndex;
        final var list = new ArrayList<String>();
        if (run.inFlight()) {
            final var pct = run.total() > 0 ? (int) (100.0 * run.doneCount() / run.total()) : 0;
            list.add("index in progress: ~" + pct + "% (" + run.doneCount() + "/" + run.total() + ")");
        } else if (completed != null
                && root.equals(completed.root)
                && completed.summary() != null
                && !completed.summary().isBlank()) {
            list.add("index idle (last run): "
                    + completed.summary().lines().findFirst().orElse(""));
        } else {
            list.add("no index has run");
        }
        list.add("session skills loaded: " + sessionSkillBlocks(root).size());
        return list;
    }

    /**
     * Resolve {@code symbol} against the compact per-jar {@code -codegraph.bin} sidecars and return the first
     * indexed dependency coordinate that provides it. This is the fast path for {@code code_resolve}: an O(1)
     * {@link CodeGraphIndex#indexOfFqn} hit instead of a per-artifact markdown substring scan. Returns
     * {@code null} when the code graph cache is disabled, the artifact has no sidecar, or no provider matches.
     *
     * @param symbol the fully-qualified symbol (or unique simple name) to find.
     * @param artifactHint optional {@code group:artifact} (or artifact) hint to narrow the scan; may be null/blank.
     */
    public String codeGraphProvider(final Path projectRoot, final String symbol, final String artifactHint) {
        final var root = projectRoot(projectRoot);
        if (!enabled(root)
                || configuration == null
                || configuration.mavenRepositoryKnowledge() == null
                || !configuration.mavenRepositoryKnowledge().codegraphIndexEnabled()) {
            return null;
        }
        final var want = symbol == null ? null : symbol.strip();
        if (want == null || want.isEmpty()) {
            return null;
        }
        final var config = knowledgeConfig();
        final var repo = new LocalRepository(config.localRepository());
        final var writer = new KnowledgeWriter(config.localRepository());
        for (final var gav : projectDependencies(operationLlm(root, null))) {
            if (artifactHint != null
                    && !artifactHint.isBlank()
                    && !gav.coordinate().contains(artifactHint)) {
                continue;
            }
            if (repo.resolve(gav).mainJar() == null) {
                continue;
            }
            final var file = writer.codeGraphFile(gav);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            final var idx = codeGraphCache.get(file);
            if (idx != null) {
                final boolean hit = idx.indexOfFqn(want) >= 0 || idx.resolveSuffix(want) >= 0;
                if (hit) {
                    return gav.coordinate();
                }
            }
        }
        return null;
    }

    /**
     * Read generated knowledge on demand. With only a {@code coordinate} ({@code g:a:v}) the artifact's
     * {@code -knowledge-index.md} is returned; pass a {@code shortName} (e.g. {@code api},
     * {@code sources-0}, {@code javadoc-<class>}) to read the referenced sibling file instead, so the index
     * mounted at session start can be dereferenced without re-indexing. Returns a human-readable message when
     * the coordinate is unknown, not indexed or the file is missing.
     */
    public String knowledge(final Path projectRoot, final String coordinate, final String shortName) {
        if (!enabled(projectRoot(projectRoot))) {
            return "maven repository knowledge is disabled";
        }
        final var config = knowledgeConfig();
        final var writer = new KnowledgeWriter(config.localRepository());
        final Gav gav;
        try {
            gav = Gav.parse(coordinate);
        } catch (final IllegalArgumentException e) {
            return "invalid coordinate: " + coordinate;
        }
        if (gav.version() == null) {
            return "version required for knowledge lookup: " + coordinate;
        }
        if (!writer.alreadyIndexed(gav)) {
            return "no knowledge indexed for " + gav.coordinate();
        }
        final Path file;
        if (shortName == null || shortName.isBlank() || shortName.equals("index")) {
            file = writer.indexFile(gav);
        } else {
            file = writer.sibling(gav, shortName);
        }
        if (!Files.isRegularFile(file)) {
            return "no knowledge file " + gav.coordinate() + " for '" + shortName + "'";
        }
        final var content = knowledgeFileCache.get(file);
        if (content == null) {
            return "unable to read " + file;
        }
        return content;
    }

    private static String coordsPreview(final List<Gav> gavs) {
        final var sb = new StringBuilder();
        final var limit = Math.min(gavs.size(), STARTING_LIST_CAP);
        for (var i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(gavs.get(i).coordinate());
        }
        if (gavs.size() > limit) {
            sb.append(" ... and ").append(gavs.size() - limit).append(" more");
        }
        return sb.toString();
    }

    /** Returns the active run for this root or a completed idle handle, without per-project retained history. */
    public IndexRun currentIndex(final Path projectRoot) {
        final var root = projectRoot(projectRoot);
        synchronized (activeIndexes) {
            for (final var run : activeIndexes) {
                if (Objects.equals(root, run.root)) {
                    return run;
                }
            }
            return new IndexRun(root, 0, null, List.of());
        }
    }

    private void fireProgress(final IndexRun run, final Consumer<String> progress) {
        final String message;
        run.lifecycle.lock();
        try {
            run.checkCancelled();
            if (progress == null || run.total() <= 0) {
                return;
            }
            final var pct = (int) (100.0 * run.doneCount() / run.total());
            // Admit delivery against cancel(), but never hold the lock while user code runs.
            message = "~" + pct + "% (" + run.doneCount() + "/" + run.total() + ")";
        } finally {
            run.lifecycle.unlock();
        }
        progress.accept(message);
        run.checkCancelled();
    }

    private static String summarize(final IndexResult.Results results) {
        final var sb = new StringBuilder();
        sb.append("indexed ")
                .append(results.indexedCount())
                .append(", skipped ")
                .append(results.skippedCount());
        if (!results.items().isEmpty()) {
            sb.append('\n');
            final var limit = Math.min(results.items().size(), 50);
            for (var i = 0; i < limit; i++) {
                final var r = results.items().get(i);
                sb.append("- ")
                        .append(r.gav().coordinate())
                        .append(r.indexed() ? " indexed" : r.skipped() ? " skipped: " + r.reason() : " missing")
                        .append('\n');
            }
            if (results.items().size() > limit) {
                sb.append("- ... and ").append(results.items().size() - limit).append(" more\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    private Indexer newIndexer(
            final KnowledgeConfig config,
            final Map<Gav, String> expectedFingerprints,
            final KnowledgeLlm operationLlm) {
        final var writer = new KnowledgeWriter(config.localRepository());
        return new Indexer(
                new ContentAcquirer(new LocalRepository(config.localRepository()), webFetch(), config),
                writer,
                new KnowledgeFactory(writer),
                operationLlm,
                config,
                expectedFingerprints);
    }

    /**
     * @return per-GAV expected build-declaration fingerprints, so a pom/gradle change re-indexes only the
     *         dependencies whose declaring build files changed (unchanged, already-indexed ones are skipped).
     */
    private Map<Gav, String> fingerprints(final Path root, final List<Gav> gavs) {
        return ProjectFingerprint.of(gavs, projectBuildFiles(root));
    }

    /**
     * @return the project's Maven/Gradle build files (present in the project dir) that feed the fingerprints.
     */
    private List<Path> projectBuildFiles(final Path dir) {
        final var out = new ArrayList<Path>();
        try (final var stream = Files.walk(dir)) {
            out.addAll(stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        final var name = p.getFileName().toString();
                        return name.equals("pom.xml")
                                || name.equals("build.gradle")
                                || name.equals("build.gradle.kts")
                                || name.equals("settings.gradle")
                                || name.equals("settings.gradle.kts");
                    })
                    .toList());
        } catch (final IOException e) {
            // ignore unreadable project dir
        }
        return out;
    }

    private WebFetch webFetch() {
        return (url, timeoutMs, maxBytes) -> {
            final var uri = URI.create(url);
            final var request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("accept", "text/html,application/xhtml+xml,text/plain,application/json,*/*;q=0.8")
                    .GET()
                    .build();
            final HttpResponse<byte[]> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted fetching " + url, e);
            }
            if (response.statusCode() >= 400) {
                throw new IOException("HTTP " + response.statusCode() + " for " + url);
            }
            final var body = response.body();
            if (body == null || body.length == 0) {
                return "";
            }
            final var size = Math.min(body.length, maxBytes);
            return new String(body, 0, size, StandardCharsets.UTF_8);
        };
    }

    private KnowledgeConfig knowledgeConfig() {
        return knowledgeConfig(configuration.mavenRepositoryKnowledge().force());
    }

    private KnowledgeConfig knowledgeConfig(final boolean force) {
        return knowledgeConfig(force, llm);
    }

    private KnowledgeConfig knowledgeConfig(final boolean force, final KnowledgeLlm operationLlm) {
        final var cfg = configuration.mavenRepositoryKnowledge();
        return new KnowledgeConfig(
                resolveLocalRepository(cfg.localRepository()),
                cfg.concurrency(),
                cfg.batchSize(),
                cfg.remoteManifest(),
                force,
                KnowledgeConfig.BoundedFetch.of(60, 30_000L, 200_000),
                cfg.maxBatchChars(),
                operationLlm == null ? null : operationLlm.tokenBudget(),
                cfg.maxContentBytes(),
                cfg.runContentBudget(),
                cfg.useLlm(),
                cfg.maxSourceFiles(),
                cfg.maxSourceFileChars(),
                cfg.maxJavadocPages());
    }

    private static Path resolveLocalRepository(final String raw) {
        final var override = System.getProperty("yca.mavenRepositoryKnowledge.localRepository");
        final var value = raw == null || raw.isBlank()
                ? (override == null || override.isBlank() ? "~/.m2/repository" : override)
                : raw;
        return Path.of(CodingAgentConfiguration.expandHome(value))
                .toAbsolutePath()
                .normalize();
    }

    /**
     * One indexing operation. Completion is read-only and settles after the submitted worker's finally/close
     * path and admission cleanup, not when interruption is requested. In particular, an interruption-ignoring
     * worker, including library-internal work, stays in flight and keeps same-root admission until actual
     * owned-executor termination. Cancellation requests shutdown; only the orchestrator performs the drain.
     * Frontends should cancel(), then await completion with a bounded close deadline, without closing shared
     * application executors or HTTP resources and without interpreting a timeout as successful termination.
     */
    public static final class IndexRun {
        private final Path root;
        private final ChatSettings settings;
        private final List<String> messages;
        private final int total;
        private final AtomicInteger done = new AtomicInteger();
        private final CountDownLatch completed = new CountDownLatch(1);
        private final CompletableFuture<String> completion = new CompletableFuture<>();
        private final CompletionStage<String> completionView = completion.minimalCompletionStage();
        private final ReentrantLock lifecycle = new ReentrantLock();
        private volatile boolean finished;
        private volatile boolean cancellationRequested;
        private volatile String summary;
        // Protected by lifecycle; released at terminal cleanup, including before-start cancellation/rejection.
        private FutureTask<Void> task;
        private ThreadPoolExecutor queueOwner;
        private Indexer indexer;
        private boolean indexerCloseRequested;
        private Runnable cleanup;
        private boolean started;
        private boolean deliveringSuccess;
        private boolean settling;

        private IndexRun(final Path root, final int total, final ChatSettings settings, final List<String> messages) {
            this.root = root;
            this.total = total;
            this.settings = settings;
            this.messages = List.copyOf(messages);
            if (total == 0) {
                finished = true;
                completed.countDown();
                completion.complete(String.join("\n", messages));
            }
        }

        private void start(
                final ExecutorService executor, final Supplier<String> work, final Consumer<String> onFinished) {
            final FutureTask<Void> submitted;
            lifecycle.lock();
            try {
                if (finished || cancellationRequested || settling) {
                    return;
                }
                submitted = new FutureTask<>(() -> {
                    execute(work, onFinished);
                    return null;
                });
                task = submitted;
                queueOwner = executor instanceof ThreadPoolExecutor pool ? pool : null;
            } finally {
                lifecycle.unlock();
            }
            try {
                executor.execute(submitted);
                // Cancellation can race submission. Generic ExecutorService queues cannot be removed from;
                // their cancelled FutureTask is inert and no longer retains its work/callback callable.
                if (submitted.isCancelled() && executor instanceof ThreadPoolExecutor pool) {
                    pool.remove(submitted);
                }
            } catch (final RuntimeException | Error failure) {
                lifecycle.lock();
                try {
                    if (started || finished || settling) {
                        return;
                    }
                    started = true; // claim the never-started task before releasing the lock
                    submitted.cancel(false);
                } finally {
                    lifecycle.unlock();
                }
                finish(null, failure);
            }
        }

        private void execute(final Supplier<String> work, final Consumer<String> onFinished) {
            lifecycle.lock();
            try {
                if (started || finished || cancellationRequested || settling) {
                    return;
                }
                started = true;
            } finally {
                lifecycle.unlock();
            }
            try {
                final var result = work.get();
                lifecycle.lock();
                try {
                    checkCancelled();
                    // Linearize success delivery against cancel(), without holding a lock during user code.
                    deliveringSuccess = true;
                } finally {
                    lifecycle.unlock();
                }
                if (onFinished != null) {
                    onFinished.accept(result);
                }
                finish(result, null);
            } catch (final RuntimeException | Error failure) {
                finish(null, failure);
            }
        }

        private void checkCancelled() {
            if (cancellationRequested) {
                throw new CancellationException("index cancelled for " + root);
            }
        }

        private void attachIndexer(final Indexer owned) {
            lifecycle.lock();
            try {
                indexer = owned;
                if (cancellationRequested) {
                    closeIndexer();
                }
            } finally {
                lifecycle.unlock();
            }
        }

        // Called with lifecycle held. Indexer.close is nonblocking, even on an internal worker.
        private void closeIndexer() {
            if (indexer != null && !indexerCloseRequested) {
                indexerCloseRequested = true;
                indexer.close();
            }
        }

        private void drainIndexer(final Indexer owned) {
            lifecycle.lock();
            try {
                closeIndexer();
            } finally {
                lifecycle.unlock();
            }
            try {
                awaitIndexer(owned);
            } finally {
                lifecycle.lock();
                try {
                    indexer = null;
                } finally {
                    lifecycle.unlock();
                }
            }
        }

        private void recordResult() {
            lifecycle.lock();
            try {
                checkCancelled();
                done.incrementAndGet();
            } finally {
                lifecycle.unlock();
            }
        }

        private void finish(final String result, final Throwable failure) {
            final Runnable release;
            Throwable terminal = failure;
            lifecycle.lock();
            try {
                if (finished || settling) {
                    return;
                }
                settling = true;
                if (cancellationRequested) {
                    terminal = new CancellationException("index cancelled for " + root);
                    if (failure != null && !(failure instanceof CancellationException)) {
                        terminal.addSuppressed(failure);
                    }
                }
                summary = terminal == null ? result : null;
                task = null;
                queueOwner = null;
                release = cleanup;
                cleanup = null;
            } finally {
                lifecycle.unlock();
            }
            try {
                if (release != null) {
                    release.run();
                }
            } catch (final RuntimeException | Error cleanupFailure) {
                if (terminal == null) {
                    terminal = cleanupFailure;
                } else {
                    terminal.addSuppressed(cleanupFailure);
                }
            } finally {
                finished = true;
                completed.countDown();
                if (terminal == null) {
                    completion.complete(result);
                } else {
                    completion.completeExceptionally(terminal);
                }
            }
        }

        /** Immutable workdir captured at admission; never inferred from a mutable session or global state. */
        public Path root() {
            return root;
        }

        /** Immutable requested model selection; null means process-default selection. */
        public ChatSettings settings() {
            return settings;
        }

        /** Immutable immediate acknowledgement, not a terminal success result. */
        public List<String> messages() {
            return messages;
        }

        /** Final summary, or exceptional completion for failure/cancellation, after worker cleanup. */
        public CompletionStage<String> completion() {
            return completionView;
        }

        /**
         * Requests interruption of the actual submitted task. Before-start cancellation prevents its work
         * from ever running and completes immediately; a running task remains admitted until its finally
         * path has drained the owned indexer. Returns false if cancellation was already requested,
         * cleanup started, or success delivery was committed (including while the success callback runs).
         * No success callback starts
         * after an accepted cancellation. Cancelling a copy of completion() is not operation cancellation.
         */
        public boolean cancel() {
            final boolean queued;
            lifecycle.lock();
            try {
                if (finished || settling || deliveringSuccess || cancellationRequested) {
                    return false;
                }
                cancellationRequested = true;
                queued = !started;
                closeIndexer();
                if (task != null) {
                    task.cancel(true);
                    if (queueOwner != null) {
                        queueOwner.remove(task);
                    }
                }
            } finally {
                lifecycle.unlock();
            }
            if (queued) {
                finish(null, null);
            }
            return true;
        }

        public boolean cancellationRequested() {
            return cancellationRequested;
        }

        /** Waits for real worker cleanup, not just a cancellation request. False does not mean stopped. */
        public boolean awaitCompletion(final long timeout, final TimeUnit unit) throws InterruptedException {
            if (!inFlight()) {
                return true;
            }
            return completed.await(timeout, unit);
        }

        public boolean inFlight() {
            return !finished;
        }

        public int doneCount() {
            return done.get();
        }

        public int total() {
            return total;
        }

        /** Final successful summary, null for an unfinished, failed or cancelled operation. */
        public String summary() {
            return summary;
        }
    }

    /**
     * A size-bounded, least-recently-used cache of memory-mapped dependency code graph sidecars
     * ({@code -codegraph.bin}), keyed by file path. Once the cumulative mapped bytes exceed the configured cap
     * the least-recently-used entries are evicted (their mappings are eligible for GC). Bound is taken from
     * {@code yca.mavenRepositoryKnowledge.codegraphIndexCacheBytes}; a non-positive bound keeps the cache
     * unbounded.
     */
    private final class CodeGraphIndexCache {
        private final LinkedHashMap<Path, Entry> entries;
        private long mappedBytes;

        private CodeGraphIndexCache() {
            this.entries = new LinkedHashMap<>(16, 0.75f, true); // access-order LRU
        }

        private long bound() {
            if (configuration == null || configuration.mavenRepositoryKnowledge() == null) {
                return 0;
            }
            return configuration.mavenRepositoryKnowledge().codegraphIndexCacheBytes();
        }

        private synchronized CodeGraphIndex get(final Path file) {
            final var cached = entries.get(file);
            if (cached != null) {
                return cached.index;
            }
            final long size;
            final CodeGraphIndex index;
            try {
                size = Files.size(file);
                index = CodeGraphIndex.mmap(file);
            } catch (final IOException e) {
                return null;
            }
            mappedBytes += size;
            entries.put(file, new Entry(index, size));
            final var bound = bound();
            if (bound > 0) {
                evict(bound);
            }
            return index;
        }

        private void evict(final long bound) {
            final Iterator<Map.Entry<Path, Entry>> it = entries.entrySet().iterator();
            while (it.hasNext() && mappedBytes > bound) {
                final var e = it.next().getValue();
                mappedBytes -= e.bytes;
                it.remove();
            }
        }

        private record Entry(CodeGraphIndex index, long bytes) {}
    }

    /**
     * A size-bounded, least-recently-used cache of knowledge file contents, keyed by absolute file path.
     * Entries are invalidated when the file's {@code lastModifiedTime} changes (stat cost ~sub-ms per file),
     * so the 60-file appendKnowledge scan in {@code code_resolve} amortizes to a single disk read per
     * modified file across calls within the same JVM process.
     */
    private final class KnowledgeFileCache {
        private final LinkedHashMap<Path, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
        private static final int MAX_ENTRIES = 256;

        synchronized String get(final Path file) {
            final var cached = entries.get(file);
            final long mt;
            try {
                mt = Files.getLastModifiedTime(file).toMillis();
            } catch (final IOException e) {
                return null;
            }
            if (cached != null && cached.lastModified == mt) {
                return cached.content;
            }
            final String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (final IOException e) {
                entries.remove(file);
                return null;
            }
            entries.put(file, new Entry(mt, content));
            while (entries.size() > MAX_ENTRIES) {
                final var it = entries.keySet().iterator();
                it.next();
                it.remove();
            }
            return content;
        }

        private record Entry(long lastModified, String content) {}
    }
}
