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

import io.yupiik.codegraph.index.CodeGraphIndexWriter;
import io.yupiik.codegraph.index.IndexGraph;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.codegraph.index.CodeGraphIndex;
import io.yupiik.ia.agent.core.codegraph.index.GraphToIndex;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.optimize.AfterToolExecutionEvent;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.tools.FileIgnore;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The structural code graph of the agent: a symbol-and-edge graph extracted from the working tree by the
 * registered {@link GraphParser}s (all deterministic, model-free, cheap per-query refresh by file fingerprint),
 * plus an optional tier-2 LLM enrichment (per-file and per-symbol summaries, and curated concept nodes).
 *
 * <p>The wiring model is heavily inspired by Graft ({@link https://github.com/trailhq/Graft}): the raw graph
 * is a tier-1 structural extract persisted to {@code .graph/wiring.json}, per-file cards mirror the source
 * tree under the doc store, and concept nodes group those files into curated LLM-written cards at the store
 * root with typed links between them.
 */
@ApplicationScoped
public class StructuralCodeGraph implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(StructuralCodeGraph.class.getName());
    private static final Set<String> PROJECT_MARKERS = Set.of(
            "pom.xml",
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
            "build.xml",
            "Makefile",
            "package.json",
            "requirements.txt",
            "setup.py",
            "pyproject.toml",
            "Cargo.toml",
            "go.mod",
            "README.md",
            "README.adoc");

    private final CodingAgentConfiguration configuration;
    private final List<GraphParser> registeredParsers;
    private volatile List<GraphParser> parsers;
    /** Extension (lowercased, no dot, e.g. {@code java}) to the parser that owns it; built once from the available
     * parsers so {@link #parserFor} is an O(1) lookup instead of scanning the parser list on every refresh. */
    private volatile Map<String, GraphParser> byExtension;

    private final JsonMapper mapper;
    /** Background re-index notices have no session sink and are dropped when no turn is running. */
    private final StatusReporter statusReporter = new StatusReporter();

    private final ChatClient chatClient;
    private final Path storeRoot;
    private final Path indexFile;
    private final Path indexMetaFile;
    private final DocTreeWriter docWriter;
    private final DocEnricher docEnricher;
    private final ProviderManager providerManager;
    private final Path projectDir;
    private final Clock clock;
    private final List<FileIgnore.Rule> ignoreRules;
    private final boolean configNoRefresh;
    private final long refreshAwaitMs;
    private final long batchRefreshDebounceMs;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final AtomicLong refreshPending = new AtomicLong();
    private volatile long latestRefreshJob;
    private final ReentrantLock batchRefreshMonitor = new ReentrantLock();
    private final Set<Path> pendingBatchRefresh = new LinkedHashSet<>();
    private volatile boolean batchFlushScheduled;
    private volatile GraphStore graph;
    private volatile CodeGraphIndex index;
    private final AtomicBoolean readyStarted = new AtomicBoolean();
    private final ReentrantLock readinessLock = new ReentrantLock();
    private volatile CompletableFuture<Void> readyFuture;
    /**
     * Bounded, refresh-aware query indexes for non-default (per-session) roots, keyed by absolute root. Each entry
     * caches the read-only {@link CodeGraphIndex} plus the {@code rel -> lastModified} snapshot of the indexed files,
     * so a later query for the same root re-validates against the live tree (cheap mtime compare) instead of serving
     * a stale snapshot forever. The cache is bounded (least-recently-used eviction): many distinct web session roots
     * no longer grow unboundedly.
     */
    private final RootIndexCache rootIndexes = new RootIndexCache(16);

    /** A cached per-root index and the mtime snapshot it was built from; {@code builtAt} is the graph clock. */
    record RootCacheEntry(CodeGraphIndex index, Map<String, Long> mtimes, long builtAt) {}

    private final ConcurrentLinkedQueue<PersistJob> persistQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean persistScheduled = new AtomicBoolean();
    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile WatchService watchService;
    private volatile Thread watchThread;
    private final AtomicBoolean watching = new AtomicBoolean();

    public StructuralCodeGraph(
            final CodingAgentConfiguration configuration,
            final List<GraphParser> parsers,
            final JsonMapper mapper,
            final ChatClient chatClient,
            final ProviderManager providerManager,
            final Clock clock,
            final ScheduledExecutorService scheduler) {
        this.configuration = configuration;
        this.registeredParsers = List.copyOf(parsers);
        this.parsers = List.of();
        this.byExtension = Map.of();
        this.mapper = mapper;
        this.chatClient = chatClient;
        this.providerManager = providerManager;
        this.clock = clock;
        final var workingDir = configuration.resolveProjectDir().toString();
        this.projectDir = configuration.resolveProjectDir().toAbsolutePath().normalize();
        this.storeRoot = Path.of(configuration.structuralCodeGraph().resolvedStoreDir(workingDir))
                .toAbsolutePath()
                .normalize();
        this.indexFile = storeRoot.resolve("index.bin");
        this.indexMetaFile = storeRoot.resolve("index.meta");
        final var scg = configuration.structuralCodeGraph();
        this.ignoreRules = FileIgnore.of(projectDir, scg.effectiveIgnoreGlobs()).rules();
        this.configNoRefresh = scg.noRefresh();
        this.refreshAwaitMs = 3000L; // bound for refreshPrimary() when a background refresh is pending
        this.batchRefreshDebounceMs = scg.batchRefreshDebounceMillis();
        this.scheduler = scheduler;
        this.ownsScheduler = false;
        this.docWriter = new DocTreeWriter(configuration, storeRoot);
        this.docEnricher = new DocEnricher(configuration, chatClient, docWriter, mapper, storeRoot, providerManager);
        this.docWriter.enrichment(docEnricher);
        this.docEnricher.onRewire(this::rewireWiring);
    }

    protected StructuralCodeGraph() {
        this.configuration = null;
        this.parsers = null;
        this.registeredParsers = null;
        this.byExtension = null;
        this.mapper = null;
        this.chatClient = null;
        this.providerManager = null;
        this.projectDir = null;
        this.clock = null;
        this.storeRoot = null;
        this.indexFile = null;
        this.indexMetaFile = null;
        this.ignoreRules = null;
        this.configNoRefresh = true;
        this.refreshAwaitMs = 0;
        this.batchRefreshDebounceMs = 0L;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final var t = new Thread(r, "yca-graph-test");
            t.setDaemon(true);
            return t;
        });
        this.ownsScheduler = true;
        this.docWriter = null;
        this.docEnricher = null;
    }

    private void prepareParsers() {
        final var available =
                registeredParsers.stream().filter(GraphParser::available).toList();
        final var extensions = new HashMap<String, GraphParser>();
        for (final var parser : available) {
            for (final var extension : parser.extensions()) {
                extensions.put(extension.toLowerCase(Locale.ROOT), parser);
            }
        }
        parsers = available;
        byExtension = Map.copyOf(extensions);
    }

    public void ensureReady(final Path root) {
        Objects.requireNonNull(root, "workdir");
        if (root.equals(projectDir)) {
            preparePrimary();
        } else if (enabled()) {
            queryIndex(root);
        }
    }

    public void ensureReadyAsync(final Path root) {
        Objects.requireNonNull(root, "workdir");
        if (root.equals(projectDir)) {
            preparePrimaryAsync();
        } else if (enabled()) {
            scheduler.execute(() -> queryIndex(root));
        }
    }

    public GraphStore graph(final Path root) {
        Objects.requireNonNull(root, "workdir");
        if (!enabled()) {
            return GraphStore.empty();
        }
        return root.equals(projectDir) ? primaryGraph() : queryIndex(root).toStore();
    }

    public Path storeRoot(final Path root) {
        Objects.requireNonNull(root, "workdir");
        return Path.of(configuration.structuralCodeGraph().resolvedStoreDir(root.toString()))
                .toAbsolutePath()
                .normalize();
    }

    public String freshness(final Path root) {
        Objects.requireNonNull(root, "workdir");
        if (!enabled()) {
            return "disabled (enable yca.structural-code-graph.enabled)";
        }
        if (root.equals(projectDir)) {
            return primaryFreshness();
        }
        final var current = queryIndex(root);
        return "root " + root + ": symbols=" + current.symbolCount() + " edges=" + current.edgeCount();
    }

    public void ensureRefreshedReady(final Path root) {
        Objects.requireNonNull(root, "workdir");
        if (root.equals(projectDir)) {
            refreshPrimary();
        } else {
            ensureReady(root);
        }
    }

    public void flushPersist(final Path root) {
        Objects.requireNonNull(root, "workdir");
        if (root.equals(projectDir)) {
            persistPrimary();
        }
    }

    private record PersistJob(GraphStore store, Map<String, Long> mtimes) {}

    public boolean enabled() {
        return configuration != null && configuration.structuralCodeGraph().enabled() && !registeredParsers.isEmpty();
    }

    private Path primaryStoreRoot() {
        return storeRoot;
    }

    /** Absolute, normalized project root the graph is indexed from; symbol {@code file()} paths are relative to it. */
    private Path primaryProjectDir() {
        return projectDir;
    }

    @Destroy
    @Override
    public void close() {
        if (docEnricher != null) {
            try {
                docEnricher.close();
            } catch (final Exception e) {
                LOGGER.fine("Closing doc enricher failed: " + e.getMessage());
            }
        }
        stopWatchService();
        if (ownsScheduler && scheduler != null) {
            scheduler.shutdownNow();
        }
        persistPrimary();
    }

    private void startWatchService() {
        if (!configuration.structuralCodeGraph().watch() || watching.get() || !isRecognizedProject(projectDir)) {
            return;
        }
        try {
            watchService = FileSystems.getDefault().newWatchService();
            projectDir.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            watching.set(true);
            watchThread = new Thread(this::watchLoop, "yca-graph-watcher");
            watchThread.setDaemon(true);
            watchThread.start();
            LOGGER.info("File watcher started for " + projectDir);
        } catch (final IOException e) {
            LOGGER.warning("Cannot start file watcher: " + e.getMessage());
        }
    }

    private void stopWatchService() {
        final var wasWatching = watching.compareAndSet(true, false);
        try {
            if (watchService != null) {
                watchService.close();
                watchService = null;
            }
        } catch (final IOException e) {
            LOGGER.fine("Closing watch service: " + e.getMessage());
        }
        if (watchThread != null) {
            watchThread.interrupt();
            try {
                watchThread.join(2000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            watchThread = null;
        }
        if (wasWatching) {
            LOGGER.fine("File watcher stopped");
        }
    }

    private void watchLoop() {
        while (watching.get()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (final ClosedWatchServiceException e) {
                return;
            }
            for (final WatchEvent<?> event : key.pollEvents()) {
                final var kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }
                final var path = (Path) event.context();
                final var absolute = projectDir.resolve(path);
                if (Files.isRegularFile(absolute) && absolute.toString().endsWith(".java")) {
                    final var rel = primaryRel(absolute);
                    if (!isIgnored(rel, absolute)) {
                        enqueueRefresh(absolute);
                    }
                }
            }
            if (!key.reset()) {
                break;
            }
        }
    }

    /**
     * Ensures the code graph index is ready before serving a query. Synchronous and blocking so graph tools see an
     * authoritative index: the background preload (see {@link #preparePrimaryAsync()}) is awaited - short bound first, then
     * a generous ceiling while there is still nothing to serve - because an empty graph handed to a tool is strictly
     * worse than waiting a few extra seconds for a cold build (a timeout used to let {@code primaryGraph()} and
     * {@code primaryIndex()} serve an empty store whenever the build outran the first bound). When nothing has been
     * scheduled yet, schedules it now.
     */
    private void preparePrimary() {
        if (!enabled() || index != null) {
            return;
        }
        preparePrimaryAsync();
        final var future = readyFuture;
        if (future == null) {
            return;
        }
        if (awaitReady(future, refreshAwaitMs / 2)) {
            return;
        }
        if (graph != null || index != null) {
            return; // the build landed between the timeout check and now
        }
        // Still nothing to serve (both graph and index null): the build is in flight - keep awaiting it up to a
        // generous ceiling instead of handing an empty store to the caller (the first, short bound used to expire
        // under a cold start or heavy parallel load and primaryGraph()/primaryIndex() then served an empty graph).
        if (!awaitReady(future, refreshAwaitMs * 10L)) {
            throw new IllegalStateException("Timed out waiting for structural code graph readiness");
        }
    }

    /**
     * Bounded wait on the ready future. Returns {@code true} when the caller can proceed (the build completed, failed
     * or the wait was interrupted), {@code false} only on timeout - meaning the build is still in flight.
     */
    private boolean awaitReady(final CompletableFuture<Void> future, final long timeoutMs) {
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (final TimeoutException e) {
            return false;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        } catch (final ExecutionException e) {
            LOGGER.log(Level.WARNING, e.getCause(), () -> "Code graph index preparation failed: " + e.getMessage());
            throw new IllegalStateException("Code graph preparation failed", e.getCause());
        }
    }

    /**
     * Kicks off the code-graph index load in the background on the shared refresher thread, so the load overlaps the
     * provider's first LLM round-trip (the dominant cost of a session's first call). Idempotent: only the first call
     * schedules the work, and the scheduled job no-ops once an index is already present. Never blocks.
     */
    private void preparePrimaryAsync() {
        readinessLock.lock();
        try {
            if (!enabled()
                    || index != null
                    || indexFile == null
                    || !isRecognizedProject(projectDir)
                    || readyStarted.get()) {
                return;
            }
            final var future = new CompletableFuture<Void>();
            readyFuture = future;
            readyStarted.set(true);
            scheduler.execute(() -> {
                try {
                    final var available = registeredParsers.stream()
                            .filter(GraphParser::available)
                            .toList();
                    if (available.isEmpty()) {
                        throw new IllegalStateException("No structural graph parser is available in this runtime");
                    }
                    final var byExt = new HashMap<String, GraphParser>();
                    for (final var parser : available) {
                        for (final var ext : parser.extensions()) byExt.put(ext.toLowerCase(Locale.ROOT), parser);
                    }
                    parsers = available;
                    byExtension = Map.copyOf(byExt);
                    ensureReadyNow();
                    future.complete(null);
                } catch (final Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } finally {
            readinessLock.unlock();
        }
    }

    /**
     * The synchronous load body, run on the background thread by {@link #preparePrimaryAsync()} (and never re-run once an
     * index is present). A test double short-circuits here because it has no persisted-index machinery
     * (storeRoot/indexFile/projectDir are null), leaving {@code primaryIndex()} to build a transient index over {@code primaryGraph()}.
     */
    private void ensureReadyNow() {
        if (!enabled() || index != null) {
            return;
        }
        final var t0 = System.nanoTime();
        if (indexFile == null || !isRecognizedProject(projectDir)) {
            return;
        }
        // Fast path: a current binary index exists -> memory-map/load it and serve queries directly off it,
        // avoiding any full-tree rescan or JSON materialisation on this session start. When noRefresh trusts the
        // on-disk index as authoritative, attempt this FIRST and skip the expensive hasIndexableContent() tree
        // walk, but only after checking the root's project markers. For the default
        // noRefresh=false the tree walk still runs first to decide whether there is anything to index at all.
        if (configNoRefresh && loadIndex()) {
            LOGGER.info("CodeGraph ensureReady fast-path took " + (System.nanoTime() - t0) / 1_000_000L
                    + "ms (session-start load)");
            startWatchService();
            return;
        }
        // For a fresh or non-code target directory (e.g. scaffolding a new project) there is nothing
        // meaningful to index. Skip building an unrelated graph so the model is not handed one.
        if (!hasIndexableContent()) {
            return;
        }
        if (!configNoRefresh && loadIndex()) {
            LOGGER.info("CodeGraph ensureReady fast-path took " + (System.nanoTime() - t0) / 1_000_000L
                    + "ms (session-start load)");
            startWatchService();
            return;
        }
        final var loaded = load();
        if (loaded != null) {
            LOGGER.info("CodeGraph ensureReady slow-path(load) took " + (System.nanoTime() - t0) / 1_000_000L + "ms");
            final var patched = patch(loaded);
            graph = patched;
            assignIndexFrom(patched);
            startWatchService();
            return;
        }
        LOGGER.info("CodeGraph ensureReady slow-path(rebuild) took " + (System.nanoTime() - t0) / 1_000_000L + "ms");
        final var built = rebuild();
        graph = built;
        assignIndexFrom(built);
        startWatchService();
    }

    /** A3: after a slow-path (patch/rebuild) indexation, assign the in-memory index directly from the in-memory store so
     * the first tool call never pays a transient index build (and never trips the disk freshness gate again). */
    private void assignIndexFrom(final GraphStore store) {
        try {
            index = CodeGraphIndex.load(CodeGraphIndexWriter.toBytes(toIndexGraph(store)));
        } catch (final RuntimeException e) {
            LOGGER.warning("Cannot build in-memory code graph index after indexation: " + e.getMessage());
            index = null;
            reloadIndex();
        }
    }

    private GraphStore primaryGraph() {
        if (!enabled() || !isRecognizedProject(projectDir)) {
            return GraphStore.empty();
        }
        preparePrimary();
        final var g = graph;
        if (g != null) {
            return g;
        }
        // When only the binary index is loaded (fast path), materialise the store on demand for legacy callers/tests;
        // the structured tools use primaryIndex() and never pay this decode at query time.
        final var idx = index;
        return idx == null ? GraphStore.empty() : idx.toStore();
    }

    /**
     * The fast, canonical query surface for the structured graph tools: a {@link CodeGraphIndex} held in memory and,
     * when configured, memory-mapped straight off the on-disk {@code index.bin}. Query tools must use this, never
     * {@link #primaryGraph()} (which stays the transient ingest scratch), so no full graph is materialised at query time.
     */
    private CodeGraphIndex primaryIndex() {
        if (configuration != null && !isRecognizedProject(projectDir)) {
            return CodeGraphIndex.load(CodeGraphIndexWriter.toBytes(toIndexGraph(GraphStore.empty())));
        }
        preparePrimary();
        var current = index;
        if (current == null) {
            // A background patch/rebuild is still running after the bounded wait: serve the last persisted
            // index - slightly stale but complete - instead of an empty graph. assignIndexFrom() swaps in
            // the fresh one when the build finishes.
            current = loadPersistedIndex();
            if (current != null) {
                index = current;
            }
        }
        if (current == null) {
            // No persisted binary index is available at all (fresh project, or a test double exposing only
            // primaryGraph()): serve queries off a transient index built from the current GraphStore so tools keep
            // a single query API. Cache it only when it is non-empty - caching an empty index would make
            // preparePrimary() believe an index exists and serve empty results until the next reload.
            final var scratch = primaryGraph();
            if (scratch != null) {
                current = CodeGraphIndex.load(CodeGraphIndexWriter.toBytes(toIndexGraph(scratch)));
                if (!scratch.fingerprints().isEmpty()) {
                    index = current;
                }
            }
        }
        return current;
    }

    /**
     * Root-aware variant of {@link #primaryIndex()} for a per-session working directory. When
     * {@code root} equals the process-wide project root, this is exactly the
     * default {@link #primaryIndex()} (byte-identical behaviour, one shared index). For any other root
     * a read-only {@link CodeGraphIndex} is built lazily from that root's files (a fresh parse, no
     * persistence, no file watcher) and cached per root, so sessions working on different projects
     * resolve symbols against their own tree without disturbing the default graph. Unlike the plain
     * default graph, the per-root cache has no live file watcher, so each distinct staged root is
     * re-validated on access: the cached index is served only while its {@code rel -> lastModified}
     * snapshot still matches the live tree, otherwise the root is re-indexed so edits made under a
     * per-session root (web {@code session.setProject} / replacement runtimes) become visible on the
     * next query instead of an ever-stale snapshot.
     */
    public CodeGraphIndex queryIndex(final Path root) {
        Objects.requireNonNull(root, "workdir");
        if (!root.isAbsolute() || !root.equals(root.normalize())) {
            throw new IllegalArgumentException("workdir must be absolute and normalized: " + root);
        }
        if (configuration == null) {
            return CodeGraphIndex.load(CodeGraphIndexWriter.toBytes(toIndexGraph(graph(root))));
        }
        if (root.equals(projectDir)) {
            return primaryIndex();
        }
        prepareParsers();
        final var normalized = root;
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("workdir is not a directory: " + root);
        }
        if (!isRecognizedProject(normalized)) {
            rootIndexes.remove(normalized);
            return CodeGraphIndex.load(CodeGraphIndexWriter.toBytes(toIndexGraph(GraphStore.empty())));
        }
        var entry = rootIndexes.get(normalized);
        if (entry == null || isRootStale(normalized, entry)) {
            entry = indexRoot(normalized);
            rootIndexes.put(normalized, entry);
        }
        return entry.index();
    }

    /**
     * True when the cached per-root index no longer reflects the live tree: a timestamp-first, stat-only
     * comparison between the snapshot the index was built from and the root files on disk now (same fast
     * guard as the default root's {@link #indexIsFresh()}). Files added or deleted offline change the
     * indexed-file count; edits advance a {@code lastModified}; either invalidates so the caller rebuilds.
     * A transient walk failure returns {@code true} (conservative: re-index) rather than serving a stale graph.
     */
    private boolean isRootStale(final Path root, final RootCacheEntry entry) {
        final var walk = new RootMtimes(root);
        if (walk.snapshot == null) {
            return true; // a failed walk cannot prove freshness: re-index (idempotent, bounded)
        }
        final var now = walk.snapshot;
        if (now.size() != entry.mtimes().size()) {
            return true;
        }
        for (final var e : now.entrySet()) {
            final var before = entry.mtimes().get(e.getKey());
            if (before == null || before.longValue() != e.getValue().longValue()) {
                return true;
            }
        }
        return false;
    }

    /** Root-aware relativization used to render symbol file paths against a per-session root. */
    public String rel(final Path root, final Path file) {
        Objects.requireNonNull(root, "workdir");
        if (root.equals(projectDir)) {
            return primaryRel(file);
        }
        try {
            final var r = Objects.requireNonNull(root, "workdir");
            final var abs = (file.isAbsolute() ? file : r.resolve(file)).normalize();
            return abs.startsWith(r) ? r.relativize(abs).toString().replace('\\', '/') : file.toString();
        } catch (final RuntimeException e) {
            return file.toString();
        }
    }

    /**
     * Builds a fresh read-only {@link CodeGraphIndex} for an arbitrary root without touching the
     * default graph's state: a full watch-free, persistence-free parse of that root's indexable
     * files, relativized against the root, plus the {@code rel -> lastModified} snapshot those files
     * were built from (used by {@link #isRootStale} to detect later edits and re-index on access).
     * Used to answer per-session code-graph queries for roots other than the process default.
     */
    private RootCacheEntry indexRoot(final Path root) {
        final var snapshot = new HashMap<String, Long>();
        try {
            final var symbols = new ArrayList<GraphModel.Symbol>();
            final var edges = new ArrayList<GraphModel.Edge>();
            final var ignore = FileIgnore.of(
                            root, configuration.structuralCodeGraph().effectiveIgnoreGlobs())
                    .rules();
            final var files = new ArrayList<Path>();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                    return isRootIgnored(root, ignore, dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()
                            && parserFor(file) != null
                            && sizeOk(file)
                            && !isRootIgnored(root, ignore, file)) {
                        files.add(file);
                        snapshot.put(rootRelative(root, file), lastModified(file));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(final Path file, final IOException exc) {
                    LOGGER.fine(() -> "Skipping unreadable path in per-session root " + root + ": " + file);
                    return FileVisitResult.CONTINUE;
                }
            });
            files.sort(Comparator.comparing(Path::toString));
            final var javaParser = javaParser();
            if (javaParser != null) {
                final var batch = javaParser.parseBatch(files);
                for (final var entry : batch.entrySet()) {
                    final var model = entry.getValue();
                    if (model == null) {
                        continue;
                    }
                    final var rel = rootRelative(root, entry.getKey());
                    final var relModel = relativizeModel(model, rel);
                    symbols.addAll(relModel.symbols());
                    edges.addAll(relModel.edges());
                }
            } else {
                for (final var file : files) {
                    final var parser = parserFor(file);
                    if (parser == null) {
                        continue;
                    }
                    try {
                        final var relModel = relativizeModel(parser.parse(file), rootRelative(root, file));
                        symbols.addAll(relModel.symbols());
                        edges.addAll(relModel.edges());
                    } catch (final RuntimeException e) {
                        LOGGER.fine(() -> "Cannot index " + file + " for root " + root + ": " + e.getMessage());
                    }
                }
            }
            final var store = new GraphStore(
                    List.copyOf(symbols), List.copyOf(edges), Map.of(), clock == null ? 0 : clock.millis());
            return new RootCacheEntry(
                    CodeGraphIndex.load(CodeGraphIndexWriter.toBytes(toIndexGraph(store))),
                    Map.copyOf(snapshot),
                    store.builtAt());
        } catch (final IOException e) {
            LOGGER.warning("Cannot index per-session root " + root + ": " + e.getMessage());
            return new RootCacheEntry(
                    CodeGraphIndex.load(CodeGraphIndexWriter.toBytes(toIndexGraph(GraphStore.empty()))),
                    Map.copyOf(snapshot),
                    clock == null ? 0 : clock.millis());
        }
    }

    /**
     * Stat-only snapshot of a per-session root's indexable files ({@code rel -> lastModified}), used by
     * {@link #isRootStale} as a cheap freshness guard. Reuses exactly the file selection of
     * {@link #indexRoot} (parser, size and ignore-filtered). A failed walk leaves {@link #snapshot} null so
     * callers can treat the root as stale and re-index instead of serving a possibly-incomplete graph.
     */
    private final class RootMtimes {
        private Map<String, Long> snapshot;

        private RootMtimes(final Path root) {
            final var collect = new HashMap<String, Long>();
            try {
                final var ignore = FileIgnore.of(
                                root, configuration.structuralCodeGraph().effectiveIgnoreGlobs())
                        .rules();
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                        return isRootIgnored(root, ignore, dir)
                                ? FileVisitResult.SKIP_SUBTREE
                                : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                        if (attrs.isRegularFile()
                                && parserFor(file) != null
                                && sizeOk(file)
                                && !isRootIgnored(root, ignore, file)) {
                            collect.put(rootRelative(root, file), lastModified(file));
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(final Path file, final IOException exc) {
                        LOGGER.fine(() -> "Skipping unreadable path in per-session root " + root + ": " + file);
                        return FileVisitResult.CONTINUE;
                    }
                });
                snapshot = Map.copyOf(collect);
            } catch (final IOException | RuntimeException e) {
                snapshot = null;
            }
        }
    }

    /** Root-relative, '/'-separated path of {@code file} under {@code root}. */
    private static String rootRelative(final Path root, final Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /**
     * Root-local ignore check mirroring {@link #isIgnored} but against a per-session root's own
     * rule set (git-like last-match-wins, {@code !} negation re-includes).
     */
    private static boolean isRootIgnored(final Path root, final List<FileIgnore.Rule> ignore, final Path path) {
        if (ignore == null || ignore.isEmpty()) {
            return false;
        }
        final var rel = Path.of(rootRelative(root, path));
        boolean ignored = false;
        for (final var rule : ignore) {
            if (FileIgnore.matchesRule(rule, rel)) {
                ignored = !rule.negated();
            }
        }
        return ignored;
    }

    /**
     * Raw load of the on-disk index, bypassing the freshness gate: used by {@link #primaryIndex()} while a
     * background patch/rebuild is still running so tools serve a complete (slightly stale) graph instead of
     * an empty one. */
    private CodeGraphIndex loadPersistedIndex() {
        final var conf = configuration == null ? null : configuration.structuralCodeGraph();
        if (conf == null || !conf.indexEnabled() || indexFile == null || !Files.isRegularFile(indexFile)) {
            return null;
        }
        try {
            return conf.indexMemoryMap() ? CodeGraphIndex.mmap(indexFile) : CodeGraphIndex.load(indexFile);
        } catch (final IOException | RuntimeException e) {
            LOGGER.fine(() -> "Cannot serve the persisted code graph index while rebuilding: " + e.getMessage());
            return null;
        }
    }

    /**
     * Timestamp-first freshness: cheap {@code lastModified} comparison per indexed file is the fast guard; only files
     * whose timestamp changed (and whose SHA would then differ) drive a re-index. This avoids the O(symbols) stat
     * churn of the previous per-file freshness and the full-tree SHA walk on session start.
     */
    private String primaryFreshness() {
        if (!enabled()) {
            return "disabled (enable yca.structural-code-graph.enabled)";
        }
        preparePrimary();
        final var g = graph == null ? GraphStore.empty() : graph;
        return "symbols="
                + (index != null ? index.symbolCount() : g.symbols().size())
                + " edges="
                + (index != null ? index.edgeCount() : g.edges().size())
                + " files="
                + g.fingerprints().size()
                + " storedAt="
                + (index != null ? index.builtAt() : g.builtAt())
                + " dir="
                + storeRoot
                + " graph="
                + (indexFile != null ? indexFile.getFileName() : "-")
                + " wiring="
                + WiringGraphWriter.pathFor(storeRoot).getFileName();
    }

    /**
     * Force a re-sync of the structural code graph (symbols, edges, fingerprints and the per-file
     * markdown doc tree) against the current working tree, regardless of the last build timestamp.
     * Used by the {@code /structural-code-graph} command to refresh the graph on demand.
     *
     * @return a short human-readable status describing the resync.
     */
    private String resyncPrimary() {
        if (!enabled()) {
            return "disabled (enable yca.structural-code-graph.enabled)";
        }
        if (!isRecognizedProject(projectDir)) {
            return "not a recognized project: " + projectDir;
        }
        preparePrimary();
        final var loaded = load();
        final GraphStore rebuilt;
        if (loaded != null) {
            rebuilt = patch(loaded);
        } else {
            rebuilt = rebuild();
        }
        graph = rebuilt;
        assignIndexFrom(rebuilt);
        return primaryFreshness();
    }

    /**
     * Root-aware re-sync: for the process default root this is exactly {@link #resyncPrimary()}; for any
     * other root it force-rebuilds that root's cached per-session index (invalidate + re-parse) regardless of the
     * mtime freshness gate, so a web caller can explicitly refresh a session's active project root on demand. The
     * default root keeps its full persisted+watched path; only the per-root cache entry is touched here.
     *
     * @return a short human-readable status describing the resync.
     */
    public String resync(final Path root) {
        Objects.requireNonNull(root, "workdir");
        if (!enabled()) {
            return "disabled (enable yca.structural-code-graph.enabled)";
        }
        if (root.equals(projectDir)) {
            return resyncPrimary();
        }
        prepareParsers();
        final var normalized = root;
        if (!Files.isDirectory(normalized)) {
            return "not a directory: " + normalized;
        }
        if (!isRecognizedProject(normalized)) {
            rootIndexes.remove(normalized);
            return "not a recognized project: " + normalized;
        }
        final var entry = indexRoot(normalized);
        rootIndexes.put(normalized, entry);
        return "resynced per-session root " + normalized + ": symbols="
                + entry.index().symbolCount() + " edges=" + entry.index().edgeCount();
    }

    public void onToolExecuted(@OnEvent final AfterToolExecutionEvent event) {
        if (!enabled() || !isFileTool(event.tool()) || !event.success()) {
            return;
        }
        if (!event.workdir().equals(projectDir)) {
            rootIndexes.remove(event.workdir());
            return;
        }
        final var paths = extractPaths(event.tool(), event.args());
        final var root = event.workdir();
        for (final var path : paths) {
            if (path != null && !path.isBlank()) {
                enqueueRefresh(root.resolve(path).normalize());
            }
        }
    }

    /** Target files of a write/edit/patch tool call: the {@code path} arg, or the diff headers of a patch. */
    private static List<String> extractPaths(final String tool, final Map<String, Object> args) {
        if (args == null) {
            return List.of();
        }
        if (args.get("path") instanceof final String path) {
            return List.of(path);
        }
        if ("apply_patch".equals(tool) && args.get("patch") instanceof final String patch) {
            final var out = new ArrayList<String>();
            for (final var line : patch.replace("\r\n", "\n").split("\n", -1)) {
                final var l = line.strip();
                if (l.startsWith("+++ ")) {
                    final var file = patchHeaderFile(l.substring(4));
                    if (file != null && !out.contains(file)) {
                        out.add(file);
                    }
                }
            }
            return out;
        }
        return List.of();
    }

    private static String patchHeaderFile(final String raw) {
        var file = raw.strip();
        if (file.isEmpty() || file.equals("/dev/null")) {
            return null;
        }
        if (file.startsWith("b/")) {
            file = file.substring(2);
        }
        final var tab = file.indexOf('\t');
        return (tab >= 0 ? file.substring(0, tab) : file).strip();
    }

    /**
     * A5: asynchronously re-index a single source file in the background (single-thread executor, FIFO, daemon).
     * Same-path jobs coalesce; writes landed via write_file/edit_file never block the agent loop.
     */
    private void enqueueRefresh(final Path file) {
        if (configNoRefresh || !isRecognizedProject(projectDir) || ignoreRules == null) {
            return;
        }
        final var absolute = file.toAbsolutePath().normalize();
        if (!absolute.startsWith(projectDir)) {
            return;
        }
        final var rel = primaryRel(absolute);
        if (isIgnored(rel, absolute)) {
            return;
        }
        // Only files owned by a registered parser enter the queue: an unrelated file written by a file tool
        // (e.g. pom.xml, README, an asset) must not reach the Java batch parsers. Mirrors refreshFileNow's guard so
        // the immediate and batched paths stay invariant-identical.
        if (parserFor(absolute) == null) {
            return;
        }
        // For a batch-capable parser (the spawned-JVM parser in native) coalesce rapid single-file edits into one
        // re-parse after a short quiet window, so we never pay a JVM spawn per file. The JVM in-process javac parser
        // keeps the existing immediate per-file refresh.
        if (useBatchRefresh()) {
            refreshBatchAdd(absolute);
            return;
        }
        refreshPending.incrementAndGet();
        final var job = ++latestRefreshJob;
        scheduler.execute(() -> {
            try {
                refreshFileNow(absolute);
            } finally {
                refreshPending.decrementAndGet();
            }
        });
    }

    /**
     * True when a batch-capable parser other than the JVM's in-process javac owns {@code .java}, which is only the
     * native spawned-JVM case. Such a parser pays a JVM startup per {@code parse}, so refreshes are debounced and
     * batched instead of re-parsed one file at a time.
     */
    private boolean useBatchRefresh() {
        final var parser = javaParser();
        return parser != null && !(parser instanceof JavaGraphParser);
    }

    /** Accumulates a changed file for the debounced batch; a single flush is (re)scheduled after the quiet window. */
    private void refreshBatchAdd(final Path absolute) {
        refreshPending.incrementAndGet();
        batchRefreshMonitor.lock();
        try {
            pendingBatchRefresh.add(absolute);
            if (!batchFlushScheduled) {
                batchFlushScheduled = true;
                scheduler.schedule(this::flushBatchRefresh, batchRefreshDebounceMs, TimeUnit.MILLISECONDS);
            }
        } finally {
            batchRefreshMonitor.unlock();
        }
    }

    /**
     * Parses every accumulated changed file with a single {@code parseBatch} and re-bases the store/persist path for
     * each. Safe to run from both the scheduler (debounced) and {@link #ensureRefreshedReady} (synchronous flush before
     * a tool query): the pending set is taken atomically, so a concurrent second invocation sees an empty set. Caller
     * guarantees {@code refreshPending} was incremented once per file added and never decrements more than it added.
     */
    private void flushBatchRefresh() {
        final List<Path> files = new ArrayList<>();
        batchRefreshMonitor.lock();
        try {
            files.addAll(pendingBatchRefresh);
            pendingBatchRefresh.clear();
            batchFlushScheduled = false;
        } finally {
            batchRefreshMonitor.unlock();
        }
        if (files.isEmpty()) {
            return;
        }
        refreshLock.lock();
        try {
            final var parser = javaParser();
            if (parser == null || !isRecognizedProject(projectDir)) {
                refreshPending.addAndGet(-files.size());
                return;
            }
            // Defensive: only hand the java parser files it owns. Files belonging to no parser (or a different one)
            // would otherwise abort the whole batch on the first non-SOURCE unit (e.g. a pom.xml), discarding every
            // legitimately indexed file. enqueueRefresh already filters these out; this guard self-heals anyway.
            final List<Path> owned =
                    files.stream().filter(f -> parserFor(f) == parser).toList();
            if (owned.size() < files.size()) {
                refreshPending.addAndGet(owned.size() - files.size());
            }
            final Map<Path, GraphModel> models;
            try {
                models = parser.parseBatch(owned);
            } catch (final RuntimeException e) {
                LOGGER.warning("Batch re-index failed for " + owned.size() + " file(s): " + e.getMessage());
                refreshPending.addAndGet(-owned.size());
                return;
            }
            for (final var file : owned) {
                final var rel = primaryRel(file);
                final var model = models.getOrDefault(
                        file, GraphModel.empty(parser.extensions().iterator().next()));
                if (model == null || (model.symbols().isEmpty() && model.edges().isEmpty())) {
                    refreshPending.decrementAndGet();
                    continue;
                }
                try {
                    applyRefreshedFile(
                            rel, fingerprint(file), Map.of(rel, lastModified(file)), relativizeModel(model, rel));
                } catch (final RuntimeException e) {
                    LOGGER.warning("Cannot batch re-index " + rel + ": " + e.getMessage());
                } finally {
                    refreshPending.decrementAndGet();
                }
            }
        } finally {
            refreshLock.unlock();
        }
    }

    /** A5: graph-reading tools call this before primaryIndex() so a pending background refresh is seen as authoritative
     * (single-thread FIFO: awaiting the latest enqueued job implies all earlier ones finished too). Bounded by
     * {@link #refreshAwaitMs} so a slow/broken re-index never hangs a tool - on timeout we proceed with the current index. */
    private void refreshPrimary() {
        if (configNoRefresh) {
            return;
        }
        // For the debounced batch path (native spawned-JVM parser), flush accumulated edits synchronously so a
        // graph-reading tool never sees a stale store; the keep-alive re-schedules a flush if more edits land.
        if (useBatchRefresh()) {
            flushBatchRefresh();
        }
        if (latestRefreshJob == 0) {
            return;
        }
        final var deadline =
                System.nanoTime() + Duration.ofMillis(refreshAwaitMs).toNanos();
        // await until no refresh job is pending (single-thread FIFO means once pending hits 0, the latest is done)
        while (refreshPending.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean isFileTool(final String name) {
        return "write_file".equals(name) || "edit_file".equals(name) || "apply_patch".equals(name);
    }

    /** Synchronous refresh body (used by the async executor; a test seam may call it directly). Public for tests. */
    public void refreshFile(final Path file) {
        refreshFileNow(file == null ? null : file.toAbsolutePath().normalize());
    }

    private void refreshFileNow(final Path absolute) {
        if (!enabled() || absolute == null || !isRecognizedProject(projectDir)) {
            return;
        }
        // only files inside the project dir (any depth) belong to the graph; writes
        // targeting another folder must not pollute the index
        if (!absolute.startsWith(projectDir)) {
            return;
        }
        final var rel = primaryRel(absolute);
        if (isIgnored(rel, absolute)) {
            return;
        }
        final var parser = parserFor(absolute);
        if (parser == null) {
            return;
        }
        // the whole read-modify-write is serialized: the watcher emits MODIFY events from its own
        // thread, and an async refresh interleaving with a synchronous delete-refresh could
        // otherwise resurrect the symbols of a file deleted in between (its fingerprint was
        // captured before the deletion and its persist can land after the delete-refresh one)
        refreshLock.lock();
        try {
            refreshFileLocked(absolute, rel, parser);
        } finally {
            refreshLock.unlock();
        }
    }

    /** Caller holds {@code refreshLock}; the fingerprint is (re-)read inside the critical section. */
    private void refreshFileLocked(final Path absolute, final String rel, final GraphParser parser) {
        final var fp = fingerprint(absolute);
        // snapshot the mtime together with the content hash so the sidecar can never record an mtime
        // newer than the indexed content (see patch())
        final var mtimes = Map.of(rel, lastModified(absolute));
        final GraphModel model;
        try {
            model = fp == null
                    ? GraphModel.empty(parser.extensions().iterator().next())
                    : relativizeModel(parser.parse(absolute), rel);
        } catch (final RuntimeException e) {
            LOGGER.warning("Cannot re-index " + rel + ": " + e.getMessage());
            return;
        }
        applyRefreshedFile(rel, fp, mtimes, model);
    }

    /**
     * Re-base the in-memory store and persist path for a single re-indexed file whose model is already parsed. Caller
     * holds {@code refreshLock} and re-reads the fingerprint/mtime snapshot before invoking.
     */
    private void applyRefreshedFile(
            final String rel, final String fp, final Map<String, Long> mtimes, final GraphModel model) {
        final var current = graph == null ? loadOrEmpty() : graph;
        final var symbols = new ArrayList<>(current.symbols());
        final var edges = new ArrayList<>(current.edges());
        dropFile(rel, symbols, edges);
        final var fingerprints = new HashMap<>(current.fingerprints());
        final var removed = fingerprints.remove(rel) != null;
        if (fp != null && (removed || !fp.equals(current.fingerprints().get(rel)))) {
            fingerprints.put(rel, fp);
            symbols.addAll(model.symbols());
            edges.addAll(model.edges());
        }
        final var updated =
                new GraphStore(List.copyOf(symbols), List.copyOf(edges), Map.copyOf(fingerprints), clock.millis());
        graph = updated;
        persist(updated, mtimes);
        reloadIndex();
        if (fp == null) {
            docWriter.delete(rel);
            docEnricher.drop(rel);
        } else {
            docWriter.write(rel, model);
            docEnricher.maybeEnrich(rel, model);
        }
        statusReporter.report("structural code graph re-indexed " + rel);
    }

    private void dropFile(final String rel, final List<GraphModel.Symbol> symbols, final List<GraphModel.Edge> edges) {
        final var owned = new HashSet<String>();
        symbols.removeIf(s -> {
            // suffix fallback: stores built before symbol files were relativized hold absolute paths
            if (s.file().equals(rel) || s.file().endsWith("/" + rel)) {
                owned.add(s.fqn());
                return true;
            }
            return false;
        });
        edges.removeIf(e -> owned.contains(e.from()));
    }

    /**
     * Parsers report symbol files as absolute paths ({@code SourceFile.getName()}), but the store keys them by
     * project-relative path (fingerprints, docs, dropFile): rewrite each parsed symbol onto the walked file so
     * incremental drops match and tools expose relative paths.
     */
    private static GraphModel relativizeModel(final GraphModel model, final String rel) {
        return new GraphModel(
                model.language(),
                model.symbols().stream()
                        .map(s -> new GraphModel.Symbol(
                                s.fqn(), s.kind(), rel, s.startLine(), s.endLine(), s.signature()))
                        .toList(),
                model.edges());
    }

    private GraphStore load() {
        // The canonical persisted form is the compact binary index.bin (graph.json is no longer written). When the
        // index is stale (some file changed since it was built) we still need its previous symbols/edges/fingerprints
        // to run the incremental patch() diff, so reconstruct the ingest store from whatever index is on disk.
        if (!configuration.structuralCodeGraph().indexEnabled()
                || indexFile == null
                || !Files.isRegularFile(indexFile)) {
            return null;
        }
        final var conf = configuration.structuralCodeGraph();
        try {
            final var idx = conf.indexMemoryMap() ? CodeGraphIndex.mmap(indexFile) : CodeGraphIndex.load(indexFile);
            return idx == null ? null : idx.toStore();
        } catch (final IOException | RuntimeException e) {
            LOGGER.warning("Cannot load binary code graph index from " + indexFile + ": " + e.getMessage());
            return null;
        }
    }

    private GraphStore loadOrEmpty() {
        final var loaded = load();
        return loaded == null ? GraphStore.empty() : loaded;
    }

    private GraphStore patch(final GraphStore current) {
        final var fingerprints = new HashMap<>(current.fingerprints());
        final var symbols = new ArrayList<>(current.symbols());
        final var edges = new ArrayList<>(current.edges());
        // mtimes snapshotted when each file is hashed/parsed, never after the whole build: a stat taken at
        // persist time could store an mtime newer than the indexed content and mask the change forever
        final var mtimes = new HashMap<String, Long>();
        var changed = false;
        final var visited = new HashSet<String>();

        // Collect files to parse in batch
        final var filesToParse = new ArrayList<Path>();
        final var fileRels = new HashMap<Path, String>();
        for (final Path file : candidateFiles()) {
            final var rel = primaryRel(file);
            visited.add(rel);
            mtimes.put(rel, lastModified(file));
            final var fp = fingerprint(file);
            if (fp == null) {
                // vanished between the tree walk and the hash: drop it fully, like an offline deletion
                changed |= fingerprints.remove(rel) != null;
                dropFile(rel, symbols, edges);
                docWriter.delete(rel);
                docEnricher.drop(rel);
                continue;
            }
            if (fp.equals(fingerprints.get(rel))) {
                continue;
            }
            changed = true;
            fingerprints.put(rel, fp);
            dropFile(rel, symbols, edges);
            final var parser = parserFor(file);
            if (parser == null) {
                docWriter.delete(rel);
                docEnricher.drop(rel);
                continue;
            }
            filesToParse.add(file);
            fileRels.put(file, rel);
        }

        // Batch parse all changed files
        if (!filesToParse.isEmpty()) {
            final var javaParser = javaParser();
            if (javaParser != null) {
                final var batchResults = javaParser.parseBatch(filesToParse);
                for (final var entry : batchResults.entrySet()) {
                    final var file = entry.getKey();
                    final var model = entry.getValue();
                    final var rel = fileRels.get(file);
                    if (rel != null && model != null) {
                        final var relModel = relativizeModel(model, rel);
                        symbols.addAll(relModel.symbols());
                        edges.addAll(relModel.edges());
                        docWriter.write(rel, relModel);
                        docEnricher.maybeEnrich(rel, relModel);
                    }
                }
            } else {
                // Fallback to individual parsing
                for (final var file : filesToParse) {
                    final var rel = fileRels.get(file);
                    final var parser = parserFor(file);
                    try {
                        final var model = relativizeModel(parser.parse(file), rel);
                        symbols.addAll(model.symbols());
                        edges.addAll(model.edges());
                        docWriter.write(rel, model);
                        docEnricher.maybeEnrich(rel, model);
                    } catch (final RuntimeException e) {
                        LOGGER.warning("Cannot re-index " + rel + ": " + e.getMessage());
                    }
                }
            }
        }
        // Offline deletions: files present in the previous store but gone from disk are never visited by the
        // walk above, so reconcile explicitly - otherwise their symbols/edges would ghost in the index forever.
        for (final var rel : current.fingerprints().keySet()) {
            if (!visited.contains(rel) && fingerprints.remove(rel) != null) {
                changed = true;
                dropFile(rel, symbols, edges);
                docWriter.delete(rel);
                docEnricher.drop(rel);
            }
        }
        if (!changed) {
            // gate-stale but hash-identical (e.g. a touch without a content change): refresh the sidecar so
            // the next session does not redo the whole walk over the same drifted timestamps
            try {
                writeIndexMeta(current, mtimes);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
            return current;
        }
        final var updated =
                new GraphStore(List.copyOf(symbols), List.copyOf(edges), Map.copyOf(fingerprints), clock.millis());
        persist(updated, mtimes);
        statusReporter.report(
                "structural code graph refreshed (" + updated.symbols().size() + " symbols)");
        enqueueConceptEnrichment();
        return updated;
    }

    private GraphStore rebuild() {
        final var symbols = new ArrayList<GraphModel.Symbol>();
        final var edges = new ArrayList<GraphModel.Edge>();
        final var fingerprints = new HashMap<String, String>();
        // mtimes snapshotted when each file is hashed, never after the whole build (see patch())
        final var mtimes = new HashMap<String, Long>();
        var count = 0;

        // Collect all files to parse in batch
        final var filesToParse = new ArrayList<Path>();
        final var fileRels = new HashMap<Path, String>();
        for (final Path file : candidateFiles()) {
            final var rel = primaryRel(file);
            final var fp = fingerprint(file);
            if (fp == null) {
                continue;
            }
            mtimes.put(rel, lastModified(file));
            fingerprints.put(rel, fp);
            final var parser = parserFor(file);
            if (parser == null) {
                docWriter.delete(rel);
                docEnricher.drop(rel);
                continue;
            }
            filesToParse.add(file);
            fileRels.put(file, rel);
        }

        // Batch parse all files
        if (!filesToParse.isEmpty()) {
            final var javaParser = javaParser();
            if (javaParser != null) {
                final var batchResults = javaParser.parseBatch(filesToParse);
                for (final var entry : batchResults.entrySet()) {
                    final var file = entry.getKey();
                    final var model = entry.getValue();
                    final var rel = fileRels.get(file);
                    if (rel != null && model != null) {
                        final var relModel = relativizeModel(model, rel);
                        symbols.addAll(relModel.symbols());
                        edges.addAll(relModel.edges());
                        docWriter.write(rel, relModel);
                        docEnricher.maybeEnrich(rel, relModel);
                        count++;
                    }
                }
            } else {
                // Fallback to individual parsing
                for (final var file : filesToParse) {
                    final var rel = fileRels.get(file);
                    final var parser = parserFor(file);
                    try {
                        final var model = relativizeModel(parser.parse(file), rel);
                        symbols.addAll(model.symbols());
                        edges.addAll(model.edges());
                        docWriter.write(rel, model);
                        docEnricher.maybeEnrich(rel, model);
                        count++;
                    } catch (final RuntimeException e) {
                        LOGGER.warning("Cannot parse " + rel + ": " + e.getMessage());
                    }
                }
            }
        }
        final var built =
                new GraphStore(List.copyOf(symbols), List.copyOf(edges), Map.copyOf(fingerprints), clock.millis());
        persist(built, mtimes);
        statusReporter.report(
                "structural code graph built (" + built.symbols().size() + " symbols, " + count + " files)");
        enqueueConceptEnrichment();
        return built;
    }

    private GraphParser parserFor(final Path file) {
        final var ext = extension(file);
        if (ext == null
                || !configuration.structuralCodeGraph().languageExtensions().contains(ext)) {
            return null;
        }
        return byExtension.get(ext);
    }

    /**
     * The primary, batch-capable parser for {@code .java} files (javac on a JVM, the spawned-JVM parser in native),
     * {@code null} when none is available. The batch path uses this so a single compiler/subprocess warms once for
     * many files instead of re-initialising per file.
     */
    private GraphParser javaParser() {
        for (final var parser : parsers) {
            if (parser.extensions().contains("java")) {
                return parser;
            }
        }
        return null;
    }

    private List<Path> candidateFiles() {
        final var result = new ArrayList<Path>();
        if (!walkCandidates(result::add)) {
            // A transient walk failure must not masquerade as "nothing to index": an empty-but-successful
            // graph would silently hide every symbol. Retry once before settling for an empty candidate set.
            result.clear();
            if (!walkCandidates(result::add)) {
                LOGGER.warning("Structural scan failed twice for " + projectDir + "; falling back to no candidates");
                return List.of();
            }
        }
        result.sort(Comparator.comparing(Path::toString));
        return result;
    }

    /** Number of indexable candidate files on disk: a stat-only walk (no hashing, no parsing) used by the
     * freshness gate to detect files added offline. A partial walk under-counts, which only makes the gate
     * conservative (report stale), so completeness is not required here. */
    private long countCandidateFiles() {
        final var count = new AtomicInteger();
        walkCandidates(file -> count.incrementAndGet());
        return count.get();
    }

    /** Shared tree walk behind {@link #candidateFiles()} and {@link #countCandidateFiles()}: honours ignore
     * rules (subtree pruning) and the size filter. Returns {@code false} if the walk failed midway. */
    private boolean walkCandidates(final Consumer<Path> sink) {
        final Path root;
        try {
            root = projectDir;
        } catch (final RuntimeException e) {
            return false;
        }
        if (!Files.isDirectory(root)) {
            return false;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                    return isSkipped(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && sizeOk(file)) {
                        sink.accept(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(final Path file, final IOException exc) {
                    LOGGER.fine(() -> "Skipping unreadable path during structural scan: " + file + " (" + exc + ")");
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException | UncheckedIOException e) {
            LOGGER.warning("Structural scan of " + root + " failed: " + e);
            return false;
        }
        return true;
    }

    private boolean hasIndexableContent() {
        // Only index roots with a build file, manifest, README or explicit local YCA configuration.
        // Incidental metadata in a random working directory must not authorize a recursive scan.
        if (!isRecognizedProject(projectDir)) {
            return false;
        }
        for (final var file : candidateFiles()) {
            if (parserFor(file) != null) {
                return true;
            }
        }
        return false;
    }

    private boolean isRecognizedProject(final Path root) {
        if (root == null) {
            return false;
        }
        // Probe, memory and graph caches also create .yca; only an explicit local configuration
        // identifies a project without another marker. Never inspect descendants or global configuration.
        if (Files.isRegularFile(root.resolve(".yca/configuration.json"))) {
            return true;
        }
        for (final var marker : PROJECT_MARKERS) {
            if (Files.exists(root.resolve(marker))) {
                return true;
            }
        }
        return false;
    }

    private boolean sizeOk(final Path p) {
        try {
            return Files.size(p) <= configuration.structuralCodeGraph().maxFileBytes();
        } catch (final IOException e) {
            return false;
        }
    }

    private boolean isSkipped(final Path dir) {
        return isIgnored(primaryRel(dir), dir);
    }

    /** A1: true when {@code path} (relative path {@code relPath}, or absolute) matches any configured ignore rule.
     * Git-like last-match-wins: a '!' negation can re-include a path previously ignored. */
    private boolean isIgnored(final String relPath, final Path absolute) {
        if (ignoreRules == null || ignoreRules.isEmpty()) {
            return false;
        }
        final var rel = (relPath == null || relPath.isEmpty()) ? primaryRel(absolute) : relPath;
        if (rel == null || rel.isEmpty()) {
            return false;
        }
        final Path relP = Path.of(rel.replace('\\', '/'));
        boolean ignored = false;
        for (final var rule : ignoreRules) {
            if (FileIgnore.matchesRule(rule, relP)) {
                ignored = !rule.negated();
            }
        }
        return ignored;
    }

    private String extension(final Path p) {
        final var name = p.getFileName().toString();
        final var idx = name.lastIndexOf('.');
        return idx <= 0 || idx == name.length() - 1
                ? null
                : name.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private String primaryRel(final Path file) {
        try {
            return projectDir.relativize(file).toString();
        } catch (final RuntimeException e) {
            return file.toString();
        }
    }

    private String fingerprint(final Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            try (final var in = Files.newInputStream(file)) {
                final var buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    digest.update(buf, 0, n);
                }
            }
            final var bytes = digest.digest();
            final var sb = new StringBuilder(bytes.length * 2);
            for (final var b : bytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (final IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    /** Enqueues the concept enrichment to run asynchronously on the enricher's executor. */
    private void enqueueConceptEnrichment() {
        if (!enabled() || !configuration.structuralCodeGraph().enrich()) {
            return;
        }
        docEnricher.enqueueConceptEnrichment();
    }

    /**
     * Queues a persist job to be written asynchronously with debouncing.
     * Multiple rapid calls are coalesced into a single write.
     */
    private void persist(final GraphStore store, final Map<String, Long> mtimeSnapshot) {
        if (configNoRefresh) {
            return;
        }
        persistQueue.offer(new PersistJob(store, mtimeSnapshot));
        schedulePersist();
    }

    /** Flushes any pending persist job immediately (used on shutdown and resync). */
    private void persistPrimary() {
        PersistJob job;
        while ((job = persistQueue.poll()) != null) {
            writePersist(job.store(), job.mtimes());
        }
    }

    private void schedulePersist() {
        if (persistScheduled.compareAndSet(false, true)) {
            scheduler.schedule(this::drainPersistQueue, 500, TimeUnit.MILLISECONDS);
        }
    }

    private void drainPersistQueue() {
        persistScheduled.set(false);
        PersistJob last = null;
        PersistJob job;
        while ((job = persistQueue.poll()) != null) {
            last = job;
        }
        if (last != null) {
            writePersist(last.store(), last.mtimes());
        }
        // If more jobs arrived while we were writing, schedule another round
        if (!persistQueue.isEmpty()) {
            schedulePersist();
        }
    }

    private void writePersist(final GraphStore store, final Map<String, Long> mtimeSnapshot) {
        try {
            Files.createDirectories(storeRoot);
            if (configuration.structuralCodeGraph().indexEnabled()) {
                CodeGraphIndexWriter.write(toIndexGraph(store), indexFile);
                writeIndexMeta(store, mtimeSnapshot);
            }
            WiringGraphWriter.write(mapper, storeRoot, store, docEnricher.cache());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Bridge the core {@link GraphStore} into the shared, framework-free {@link IndexGraph} binary format. */
    private static IndexGraph toIndexGraph(final GraphStore store) {
        return GraphToIndex.toIndex(store);
    }

    /**
     * Persist a small {@code rel \t lastModifiedMillis} sidecar used by the timestamp-first freshness gate,
     * prefixed with a {@code #count=N} header recording the indexed file count so the gate can also detect
     * files added offline. Mtimes come from the caller's snapshot taken when each file was hashed - never
     * from a stat after the whole build, which could store an mtime newer than the indexed content and mask
     * that change forever.
     */
    private void writeIndexMeta(final GraphStore store, final Map<String, Long> mtimeSnapshot) throws IOException {
        final var buf = new StringBuilder(store.fingerprints().size() * 48 + 32);
        buf.append("#count=").append(store.fingerprints().size()).append('\n');
        for (final var entry : store.fingerprints().entrySet()) {
            final var snapshotted = mtimeSnapshot.get(entry.getKey());
            final var mtime = snapshotted != null ? snapshotted : lastModified(projectDir.resolve(entry.getKey()));
            if (mtime >= 0) {
                buf.append(entry.getKey()).append('\t').append(mtime).append('\n');
            }
        }
        Files.writeString(indexMetaFile, buf, StandardCharsets.UTF_8);
    }

    private long lastModified(final Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (final IOException e) {
            return -1;
        }
    }

    /**
     * Timestamp-first freshness gate for the cached {@code index.bin}. Compares the number of indexable files
     * on disk against the sidecar {@code #count} header - catching files added offline - then each indexed
     * file's {@code lastModified} against the stored sidecar value. Only when one of these cheap checks
     * actually differs (or the sidecar is missing) is the index considered stale and a SHA-verified
     * incremental re-index triggered. This avoids the full-tree SHA walk on every session start.
     */
    private boolean indexIsFresh() {
        if (indexMetaFile == null || !Files.isRegularFile(indexMetaFile)) {
            return false;
        }
        Long storedCount = null;
        final var storedMtimes = new HashMap<String, Long>();
        try {
            for (var line : Files.readAllLines(indexMetaFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                if (line.startsWith("#count=")) {
                    try {
                        storedCount = Long.parseLong(line.substring("#count=".length()));
                    } catch (final NumberFormatException e) {
                        return false;
                    }
                    continue;
                }
                final var tab = line.indexOf('\t');
                if (tab < 0) {
                    return false;
                }
                try {
                    storedMtimes.put(line.substring(0, tab), Long.parseLong(line.substring(tab + 1)));
                } catch (final NumberFormatException e) {
                    return false;
                }
            }
        } catch (final IOException e) {
            return false;
        }
        // sidecars written before the #count header existed cannot prove no file was added offline:
        // treat them as stale once, the next persist rewrites the sidecar with the header
        if (storedCount == null || countCandidateFiles() != storedCount) {
            return false;
        }
        for (final var entry : storedMtimes.entrySet()) {
            if (lastModified(projectDir.resolve(entry.getKey())) != entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Load a current binary index from disk into memory (memory-mapped when configured). Serves as the session-start
     * fast path in {@link #preparePrimary()}: when present and current it avoids the full-tree re-scan / index.bin
     * deserialisation. Returns {@code true} on success.
     */
    private boolean loadIndex() {
        final var conf = configuration.structuralCodeGraph();
        if (!conf.indexEnabled() || indexFile == null || !Files.isRegularFile(indexFile)) {
            return false;
        }
        // noRefresh means the on-disk index is authoritative: skip the per-file lastModified stat walk
        // (thousands of stats on a big tree) that would otherwise make every session start several seconds
        // slower even when nothing changed. The freshness gate is still honoured when refreshing is enabled.
        if (!configNoRefresh && !indexIsFresh()) {
            return false;
        }
        try {
            final var t0 = System.nanoTime();
            index = conf.indexMemoryMap() ? CodeGraphIndex.mmap(indexFile) : CodeGraphIndex.load(indexFile);
            LOGGER.info("CodeGraph loadIndex (mmap=" + conf.indexMemoryMap() + ") at wall="
                    + (System.currentTimeMillis() / 1000) + " took "
                    + (System.nanoTime() - t0) / 1_000_000L + "ms, file=" + indexFile);
            return true;
        } catch (final IOException | RuntimeException e) {
            LOGGER.warning("Cannot load binary code graph index from " + indexFile + ": " + e.getMessage());
            return false;
        }
    }

    /** Re-read the index file after the in-memory {@link GraphStore} has been mutated by {@link #persist}. */
    private void reloadIndex() {
        if (!loadIndex()) {
            index = null;
        }
    }

    /**
     * Re-emit the wiring graph from the current in-memory graph, now that enrichment data is available. Safe to
     * call from the async enrichment thread; the write is atomic so it never corrupts the file.
     */
    private void rewireWiring() {
        final var g = graph;
        if (g == null) {
            return;
        }
        WiringGraphWriter.write(mapper, storeRoot, g, docEnricher.cache());
    }

    /**
     * A small bounded, access-ordered cache of per-root code graph indexes. Reads refresh LRU recency; the oldest
     * entry (by last access) is evicted once the size exceeds the bound, so a web server switching many sessions
     * across distinct project roots never accumulates an unbounded set of in-memory indexes. Lookups/builds that
     * read the disk are done by the caller outside the lock; only the map get/put is locked (contention is a
     * single cache slot touch per tool call, negligible compared with the index builds themselves).
     */
    static final class RootIndexCache {
        private final int max;
        private final LinkedHashMap<Path, RootCacheEntry> map;
        private final ReentrantLock lock = new ReentrantLock();

        RootIndexCache(final int max) {
            this.max = Math.max(1, max);
            this.map = new LinkedHashMap<>(16, 0.75f, true);
        }

        RootCacheEntry get(final Path root) {
            lock.lock();
            try {
                return map.get(root);
            } finally {
                lock.unlock();
            }
        }

        void put(final Path root, final RootCacheEntry entry) {
            lock.lock();
            try {
                map.put(root, entry);
                while (map.size() > max) {
                    final var eldest = map.keySet().iterator().next();
                    map.remove(eldest);
                }
            } finally {
                lock.unlock();
            }
        }

        private void remove(final Path root) {
            lock.lock();
            try {
                map.remove(root);
            } finally {
                lock.unlock();
            }
        }

        int size() {
            lock.lock();
            try {
                return map.size();
            } finally {
                lock.unlock();
            }
        }
    }
}
