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

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Optional, asynchronous, serialized LLM pass that produces the tier-2 enrichment for the doc tree:
 * a per-file {@code ## Summary} and, when {@code deep} is enabled, a one-line summary plus crux excerpt
 * per symbol ({@code ## Per-symbol}), plus the curated concept-node pass that groups files into graph-style
 * concept cards with typed links. Runs on its own single-thread executor so only one indexation job executes
 * at a time and never blocks the request thread. All results are persisted by content hash in
 * {@link DocEnricherCache} so unchanged sources/symbols are never sent to the LLM again, across restarts.
 */
public class DocEnricher implements AutoCloseable, DocTreeWriter.Enrichment {
    @JsonModel
    public record ConceptDtos(List<ConceptDto> concepts) {
        public ConceptDtos {
            concepts = concepts == null ? List.of() : concepts;
        }
    }

    @JsonModel
    public record ConceptDto(
            String name, String slug, String type, String summary, List<String> sources, List<ConceptLinkDto> links) {
        public ConceptDto {
            sources = sources == null ? List.of() : sources;
            links = links == null ? List.of() : links;
        }
    }

    @JsonModel
    public record ConceptLinkDto(String to, String relation, String description) {}

    private static final Logger LOGGER = Logger.getLogger(DocEnricher.class.getName());
    private static final String GRAFT_NOTE =
            "This concept card groups several source files into one curated, LLM-written node, inspired by "
                    + "Graft (https://github.com/trailhq/Graft). Links reference other concept cards by slug.";

    private final CodingAgentConfiguration configuration;
    private final ChatClient chatClient;
    private final ProviderManager providerManager;
    private final DocTreeWriter writer;
    private final JsonMapper mapper;
    private final DocEnricherCache cache;
    private final Path storeRoot;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile Runnable rewireHook;

    public DocEnricher(
            final CodingAgentConfiguration configuration,
            final ChatClient chatClient,
            final DocTreeWriter writer,
            final JsonMapper mapper,
            final Path storeRoot,
            final ProviderManager providerManager) {
        this.configuration = configuration;
        this.chatClient = chatClient;
        this.providerManager = providerManager;
        this.writer = writer;
        this.mapper = mapper;
        this.cache = new DocEnricherCache(mapper, storeRoot);
        this.storeRoot = storeRoot;
        this.executor = Executors.newSingleThreadExecutor(task -> {
            final var t = new Thread(task, "yca-doc-enricher");
            t.setDaemon(true);
            return t;
        });
    }

    protected DocEnricher() {
        this.configuration = null;
        this.chatClient = null;
        this.providerManager = null;
        this.writer = null;
        this.mapper = null;
        this.cache = null;
        this.storeRoot = null;
        this.executor = null;
    }

    public DocEnricherCache cache() {
        return cache;
    }

    public boolean enabled() {
        final var cfg = configuration.structuralCodeGraph();
        return configuration != null && cfg != null && cfg.enabled() && cfg.enrich() && writer != null;
    }

    /**
     * Returns a future that completes once every task previously queued on the enricher's single-thread
     * executor has finished. When the enricher is disabled no task is ever queued and the returned future
     * is already completed.
     *
     * @return a future completing when queued enrichment work is drained.
     */
    public CompletableFuture<Void> awaitIdle() {
        if (executor == null) {
            return CompletableFuture.completedFuture(null);
        }
        final var done = new CompletableFuture<Void>();
        executor.execute(() -> done.complete(null));
        return done;
    }

    /**
     * Schedule asynchronous enrichment for a single parsed source file. Serially queued; each file/symbol is
     * skipped when its content hash is unchanged in the persistent cache.
     */
    public void maybeEnrich(final String relSource, final GraphModel model) {
        if (!enabled() || model == null) {
            return;
        }
        final var digest = DocTreeWriter.structuralDigest(model);
        final var prev = cache.file(relSource);
        if (prev == null || !prev.digest().equals(digest)) {
            executor.execute(() -> safe(() -> enrichFile(relSource, model, digest), relSource));
        }
        if (deepEnabled()) {
            for (final var s : model.symbols()) {
                final var key = cache.symbolKey(relSource, s.fqn());
                final var symDigest = DocEnricherCache.symbolBodyDigest(s);
                final var prevSym = cache.symbol(key);
                if (prevSym == null || !prevSym.digest().equals(symDigest)) {
                    executor.execute(() -> safe(() -> enrichSymbol(relSource, model, s, key, symDigest), relSource));
                }
            }
        }
    }

    private void safe(final Runnable runnable, final String relSource) {
        try {
            runnable.run();
        } catch (final RuntimeException e) {
            LOGGER.warning("Enrichment failed for " + relSource + ": " + e);
        }
    }

    /**
     * Remove a source file (and its symbols) from the persistence cache. Called when the file was deleted.
     */
    public void drop(final String relSource) {
        if (cache == null) {
            return;
        }
        cache.dropFile(relSource);
        cache.flush();
    }

    /**
     * Run the concept-node pass on the current store: feed per-file summaries (from the cache) plus structural
     * facts to the LLM and write curated concept cards at the store root. Skips when there is no cached
     * summary data to group, or when deep-only. Returns the number of concept cards written.
     */
    public int enrichConcepts() {
        if (!enabled() || cache == null) {
            return 0;
        }
        if (cache.files().isEmpty() && storeHasNoSummaries()) {
            return 0;
        }
        final var cards = conceptSummaries();
        LOGGER.info("concept pass: parsed " + cards.size() + " nodes from "
                + cache.files().size() + " files");
        var written = 0;
        for (final var node : cards) {
            if (writer.writeConcept(storeRoot, node) != null) {
                written++;
            }
        }
        writer.writeIndex(storeRoot, cards);
        fireRewire();
        return written;
    }

    /** Enqueues the concept enrichment to run asynchronously on the enricher's executor. */
    public void enqueueConceptEnrichment() {
        if (!enabled() || cache == null) {
            return;
        }
        if (cache.files().isEmpty() && storeHasNoSummaries()) {
            return;
        }
        executor.execute(() -> {
            try {
                enrichConcepts();
            } catch (final RuntimeException e) {
                LOGGER.warning("Async concept enrichment failed: " + e.getMessage());
            }
        });
    }

    /** Attach the callback that re-emits the wiring graph once enrichment data is available. */
    public void onRewire(final Runnable runnable) {
        this.rewireHook = runnable;
    }

    private void fireRewire() {
        final var hook = rewireHook;
        if (hook != null) {
            try {
                hook.run();
            } catch (final RuntimeException e) {
                LOGGER.warning("Rewire of wiring graph failed: " + e.getMessage());
            }
        }
    }

    @Override
    public String fileSummary(final String relSource) {
        final var e = cache == null ? null : cache.file(relSource);
        return e == null ? null : e.summary();
    }

    @Override
    public String symbolOneLiner(final String relSource, final String fqn) {
        final var e = cache == null ? null : cache.symbol(cache.symbolKey(relSource, fqn));
        return e == null ? null : e.summary();
    }

    private boolean storeHasNoSummaries() {
        if (cache.files().isEmpty()) {
            return true;
        }
        return cache.files().values().stream()
                .noneMatch(e -> e.summary() != null && !e.summary().isBlank());
    }

    private List<DocTreeWriter.ConceptNode> conceptSummaries() {
        final var inputs = new StringBuilder();
        inputs.append("You group source files into curated concept nodes for a coding agent, inspired by Graft.\n");
        inputs.append("For each file below you get its path, its symbols and its LLM summary. Group them into a small "
                + "number (2..8) of cohesive concept nodes (e.g. a 'parsing' node, an 'http' node). ");
        inputs.append("Each node has: name, slug (url-safe ascii, lower, hyphenated), type (e.g. 'module', 'feature', "
                        + "'library'), a 2-5 line summary, and typed links to other nodes by their slug with relation such as "
                        + "DEPENDS_ON, CONTAINS, RELATED_TO. Keep descriptions one short line.\n\n")
                .append(GRAFT_NOTE)
                .append("\n\n");
        final var files = new ArrayList<>(cache.files().entrySet());
        files.sort(Map.Entry.comparingByKey());
        for (final var e : files) {
            final var summary = e.getValue().summary();
            inputs.append("FILE ").append(e.getKey()).append('\n');
            inputs.append(summary == null || summary.isBlank() ? "(no summary)" : summary)
                    .append("\n\n");
        }
        inputs.append("Return ONLY concise JSON, no prose, no markdown, matching exactly "
                + "{\"concepts\":[{\"name\":\"..\",\"slug\":\"..\",\"type\":\"..\",\"summary\":\"..\","
                + "\"sources\":[\"..\"],\"links\":[{\"to\":\"..\",\"relation\":\"..\",\"description\":\"..\"}]}]}.\n");
        final var raw = chat(inputs.toString());
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return parseConcepts(raw);
    }

    private List<DocTreeWriter.ConceptNode> parseConcepts(final String raw) {
        final var result = new ArrayList<DocTreeWriter.ConceptNode>();
        final Object parsed;
        try {
            parsed = mapper.fromString(ConceptDtos.class, raw);
        } catch (final Exception e) {
            LOGGER.warning("Concept JSON parse failed: " + e.getMessage());
            return result;
        }
        if (!(parsed instanceof ConceptDtos(List<ConceptDto> concepts))) {
            return result;
        }
        for (final var c : concepts) {
            final var slug = c.slug();
            if (slug == null || slug.isBlank()) {
                continue;
            }
            final var sources = c.sources() == null
                    ? new ArrayList<String>()
                    : new ArrayList<>(
                            c.sources().stream().map(this::cleanSource).toList());
            final var links = new ArrayList<DocTreeWriter.ConceptLink>();
            if (c.links() != null) {
                for (final var l : c.links()) {
                    if (l.to() != null && !l.to().isBlank()) {
                        links.add(new DocTreeWriter.ConceptLink(
                                l.to(),
                                l.relation() == null || l.relation().isBlank() ? "RELATED_TO" : l.relation(),
                                l.description()));
                    }
                }
            }
            final var name = c.name();
            final var type = c.type();
            result.add(new DocTreeWriter.ConceptNode(
                    name == null || name.isBlank() ? slug : name,
                    slug,
                    type == null || type.isBlank() ? "module" : type,
                    c.summary(),
                    sources,
                    DocTreeWriter.structuralDigest(GraphModel.empty("concept")),
                    links));
        }
        return result;
    }

    private String cleanSource(final String src) {
        var v = src;
        if (v.startsWith("docs/")) {
            // wiring cards store the source under docs/<rel>; keep the relative source path form
            v = v.substring("docs/".length());
        }
        if (v.endsWith(".md")) {
            v = v.substring(0, v.length() - 3);
        }
        return v;
    }

    private void enrichFile(final String relSource, final GraphModel model, final String digest) {
        if (!running.get()) {
            return;
        }
        final var summary = summarizeFile(relSource, model);
        if (summary == null) {
            return;
        }
        cache.putFile(relSource, new DocEnricherCache.FileEntry(digest, summary));
        cache.flush();
        rewriteCard(relSource, model);
    }

    private void enrichSymbol(
            final String relSource,
            final GraphModel model,
            final GraphModel.Symbol symbol,
            final String key,
            final String symDigest) {
        if (!running.get()) {
            return;
        }
        final var deep = summarizeSymbol(relSource, symbol);
        if (deep == null) {
            return;
        }
        cache.putSymbol(key, new DocEnricherCache.SymbolEntry(symDigest, deep.summary(), deep.crux()));
        cache.flush();
        rewriteCard(relSource, model);
    }

    /**
     * Re-render the wiring card for a file from the current cache contents, then re-emit the wiring graph so it
     * reflects the freshly available summaries and per-symbol one-liners.
     */
    private void rewriteCard(final String relSource, final GraphModel model) {
        writer.write(relSource, model);
        fireRewire();
    }

    private boolean deepEnabled() {
        return configuration.structuralCodeGraph().deep();
    }

    private String summarizeFile(final String relSource, final GraphModel model) {
        final var prompt = new StringBuilder();
        prompt.append("You are summarizing a single source file for a coding agent.\n");
        prompt.append("File: ").append(relSource).append('\n');
        prompt.append("Symbols:\n");
        for (final var s : model.symbols()) {
            prompt.append("- ").append(s.fqn()).append(" (").append(s.kind()).append(')');
            if (s.signature() != null && !s.signature().isBlank()) {
                prompt.append(' ').append(s.signature());
            }
            if (s.startLine() > 0) {
                prompt.append(" lines ").append(s.startLine()).append('-').append(s.endLine());
            }
            prompt.append('\n');
        }
        prompt.append("Answer in a concise markdown summary of max 10 lines: purpose, key types and dependencies.\n");
        return chat(prompt.toString());
    }

    private Deep summarizeSymbol(final String relSource, final GraphModel.Symbol symbol) {
        final var prompt = new StringBuilder();
        prompt.append("You give a fellow developer one-line notes about a single symbol.\n");
        prompt.append("File: ").append(relSource).append('\n');
        prompt.append("Symbol: ")
                .append(symbol.fqn())
                .append(" (")
                .append(symbol.kind())
                .append(')');
        if (symbol.signature() != null && !symbol.signature().isBlank()) {
            prompt.append(' ').append(symbol.signature());
        }
        if (symbol.startLine() > 0) {
            prompt.append(" lines ").append(symbol.startLine()).append('-').append(symbol.endLine());
        }
        prompt.append("\n\nAnswer exactly two short lines with no markdown headers:\n");
        prompt.append("summary: <one-line purpose>\n");
        prompt.append("crux: <one-line trick/contract/behavior a reader must know>\n");
        final var raw = chat(prompt.toString());
        if (raw == null) {
            return null;
        }
        var summary = "";
        var crux = "";
        for (final var line : raw.split("\n")) {
            final var l = line.strip();
            if (l.startsWith("summary:") || l.startsWith("summary :")) {
                summary = l.substring(l.indexOf(':') + 1).strip();
            } else if (l.startsWith("crux:") || l.startsWith("crux :")) {
                crux = l.substring(l.indexOf(':') + 1).strip();
            }
        }
        if (summary.isEmpty() && crux.isEmpty()) {
            summary = raw.strip();
        }
        return new Deep(summary, crux);
    }

    private String chat(final String prompt) {
        try {
            final var request = new ChatCompletionRequest(
                            resolveModel(),
                            List.of(Message.user(prompt)),
                            null,
                            null,
                            false,
                            null,
                            null,
                            800,
                            null,
                            null)
                    .withTuning(providerManager.resolvedBackgroundTuning(null, "compact"));
            final var result = chatClient.chat(request);
            return result.message() == null ? null : text(result.message().content());
        } catch (final Exception e) {
            LOGGER.warning("Doc enrichment call failed: " + e.getMessage());
            return null;
        }
    }

    private String resolveModel() {
        final var cfg = configuration.structuralCodeGraph();
        final var override = cfg == null ? null : cfg.model();
        if (override != null && !override.isBlank()) {
            // keep the $provider/ prefix (or pin a bare name to its provider) so enrichment is
            // not routed to a session's provider that does not host the model
            return providerManager.toRoutedReference(override);
        }
        // no explicit enrichment model: the process-default provider's backgroundModelOverrides
        // decide (enrichment is not session-bound), then the global yca.model chain
        final var resolved = providerManager.resolvedCompact(null);
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }
        final var current = providerManager.current();
        return current == null
                ? "default"
                : (current.model() == null || current.model().isBlank() ? "default" : current.model());
    }

    private static String text(final Object content) {
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> parts) {
            final var sb = new StringBuilder();
            for (final var p : parts) {
                if (p instanceof Map<?, ?> m && m.get("text") != null) {
                    sb.append(m.get("text"));
                }
            }
            return sb.toString();
        }
        return content == null ? "" : content.toString();
    }

    record Deep(String summary, String crux) {}

    @Override
    public void close() {
        running.set(false);
        if (cache != null) {
            cache.flush();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
