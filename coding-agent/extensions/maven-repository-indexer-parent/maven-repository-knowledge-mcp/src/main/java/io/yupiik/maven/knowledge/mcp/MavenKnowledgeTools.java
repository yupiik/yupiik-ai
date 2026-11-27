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
package io.yupiik.maven.knowledge.mcp;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.jsonrpc.JsonRpc;
import io.yupiik.fusion.framework.build.api.jsonrpc.JsonRpcParam;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.mcp.api.MCPTool;
import io.yupiik.fusion.mcp.protocol.MCPSessions;
import io.yupiik.maven.knowledge.Gav;
import io.yupiik.maven.knowledge.KnowledgeFactory;
import io.yupiik.maven.knowledge.config.KnowledgeConfig;
import io.yupiik.maven.knowledge.config.KnowledgeConfig.BoundedFetch;
import io.yupiik.maven.knowledge.content.ContentAcquirer;
import io.yupiik.maven.knowledge.content.LocalRepository;
import io.yupiik.maven.knowledge.content.WebFetch;
import io.yupiik.maven.knowledge.index.Indexer;
import io.yupiik.maven.knowledge.llm.KnowledgeLlm;
import io.yupiik.maven.knowledge.mcp.configuration.MavenKnowledgeConfiguration;
import io.yupiik.maven.knowledge.mcp.llm.MCPKnowledgeLlm;
import io.yupiik.maven.knowledge.mcp.model.IndexResponse;
import io.yupiik.maven.knowledge.process.ProcessService;
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
import java.util.List;

/**
 * Exposes the maven-repository-knowledge pipeline as MCP tools for any MCP client (claude code, opencode, ...).
 * Every LLM interaction goes through the MCP {@code sampling} protocol, so the client (which decides the model) is
 * the one answering: OpenAI and Anthropic clients both work with no server-side provider dependency.
 */
@ApplicationScoped
public class MavenKnowledgeTools {

    private final MavenKnowledgeConfiguration configuration;
    private final MCPSessions sessions;
    private final JsonMapper mapper;
    private final ExtendedHttpClient httpClient;
    private final KnowledgeConfig knowledgeConfig;
    private final LocalRepository localRepository;
    private final ProcessService processes;

    /**
     * Constructor for the {@code @ApplicationScoped} subclass-proxy. The generated proxy instantiates this class by
     * calling {@code super(null, ...)} on the dependency constructor, so the proxies fields are all null and every call
     * is forwarded to the real instance - no real initialization is possible here, which is why the dependency
     * constructor below guards each nullable dependency (see {@link #MavenKnowledgeTools(MavenKnowledgeConfiguration,
     * MCPSessions, JsonMapper, ExtendedHttpClient, ProcessService)}).
     */
    public MavenKnowledgeTools(
            final MavenKnowledgeConfiguration configuration,
            final MCPSessions sessions,
            final JsonMapper mapper,
            final ExtendedHttpClient httpClient,
            final ProcessService processes) {
        this.configuration = configuration;
        this.sessions = sessions;
        this.mapper = mapper;
        this.httpClient = httpClient;
        this.processes = processes;
        // the subclass-proxy reaches this constructor through super(null, ...): the injected params are null then, so
        // every derivation is null-guarded - the real instance (built by the container with non-null params) gets the
        // fully built KnowledgeConfig + LocalRepository.
        this.knowledgeConfig = configuration == null ? null : buildKnowledgeConfig(configuration);
        this.localRepository = knowledgeConfig == null ? null : new LocalRepository(knowledgeConfig.localRepository());
    }

    private static KnowledgeConfig buildKnowledgeConfig(final MavenKnowledgeConfiguration configuration) {
        final var repo = KnowledgeConfig.defaults(
                Path.of(configuration.localRepository().replace("~", System.getProperty("user.home"))));
        return new KnowledgeConfig(
                repo.localRepository(),
                Math.max(1, configuration.concurrency()),
                Math.max(1, configuration.batchSize()),
                configuration.remoteManifest(),
                configuration.force(),
                new BoundedFetch(1, 30_000L, 65536),
                configuration.maxBatchChars(),
                null,
                configuration.maxContentBytes(),
                0,
                configuration.useLlm(),
                configuration.maxSourceFiles(),
                configuration.maxSourceFileChars(),
                configuration.maxJavadocPages());
    }

    /**
     * Index (or re-index) a set of artifacts, returning per-artifact outcomes. Accepts a list of
     * {@code groupId:artifactId[:version]} coordinates; batches many GAVs per single LLM sampling call.
     */
    @MCPTool
    @JsonRpc(
            value = "maven/knowledge/index",
            documentation = "Indexes local Maven artifacts so agents do not "
                    + "rediscover library usage. Accepts groupId:artifactId[:version] coordinates and returns per-artifact "
                    + "indexed/skipped outcomes. Entry-point analysis and skill synthesis are delegated to the client model "
                    + "through the MCP sampling protocol.")
    public IndexResponse index(
            @JsonRpcParam(
                            required = true,
                            documentation = "Artifacts to index as groupId:artifactId[:version] "
                                    + "strings, many are batched in a single LLM call.")
                    final List<String> gavs,
            @JsonRpcParam(documentation = "Force regeneration, bypassing the immutable-skip rule.") final boolean force,
            final Request request) {
        final var gavList = new ArrayList<Gav>();
        for (final var gav : gavs) {
            gavList.add(Gav.parse(gav));
        }
        final var effective = force
                ? new KnowledgeConfig(
                        knowledgeConfig.localRepository(),
                        knowledgeConfig.concurrency(),
                        knowledgeConfig.batchSize(),
                        knowledgeConfig.remoteManifest(),
                        true,
                        knowledgeConfig.boundedFetch(),
                        knowledgeConfig.maxBatchChars(),
                        knowledgeConfig.tokenBudget(),
                        knowledgeConfig.maxContentBytes(),
                        knowledgeConfig.runContentBudget(),
                        knowledgeConfig.useLlm(),
                        knowledgeConfig.maxSourceFiles(),
                        knowledgeConfig.maxSourceFileChars(),
                        knowledgeConfig.maxJavadocPages())
                : knowledgeConfig;
        final var llm = new MCPKnowledgeLlm(
                sessions, request, mapper, configuration.projectDir(), knowledgeConfig.localRepository(), processes);
        final var indexer = newIndexer(effective, llm);
        try {
            final var results = indexer.index(gavList, null);
            final var items = new ArrayList<IndexResponse.Item>();
            for (final var r : results.items()) {
                items.add(new IndexResponse.Item(r.gav().coordinate(), r.indexed(), r.reason()));
            }
            return new IndexResponse(results.indexedCount(), results.skippedCount(), items);
        } finally {
            indexer.close();
        }
    }

    /**
     * List the artifacts already indexed (have a generated knowledge marker) under the configured repository.
     */
    @MCPTool
    @JsonRpc(
            value = "maven/knowledge/status",
            documentation = "Reports which artifacts already have generated "
                    + "knowledge (indexed) versus what is available in the local repository.")
    public IndexResponse status(
            @JsonRpcParam(
                            required = true,
                            documentation = "Artifacts to check, as groupId:artifactId[:version] " + "strings.")
                    final List<String> gavs) {
        final var writer = new KnowledgeWriter(knowledgeConfig.localRepository());
        final var items = new ArrayList<IndexResponse.Item>();
        var indexed = 0;
        var skipped = 0;
        for (final var gavStr : gavs) {
            final var gav = Gav.parse(gavStr);
            if (gav.version() == null) {
                items.add(new IndexResponse.Item(gav.coordinate(), false, "version required for status"));
                skipped++;
            } else if (writer.alreadyIndexed(gav)) {
                items.add(new IndexResponse.Item(gav.coordinate(), true, "already indexed"));
                indexed++;
            } else {
                items.add(new IndexResponse.Item(gav.coordinate(), false, "not indexed"));
                skipped++;
            }
        }
        return new IndexResponse(indexed, skipped, items);
    }

    /**
     * Read generated knowledge on demand. With only a {@code gav} the artifact's {@code -knowledge-index.md}
     * is returned; pass a {@code file} short name to read the referenced sibling instead.
     */
    @MCPTool
    @JsonRpc(
            value = "maven/knowledge/content",
            documentation = "Reads a local artifact's generated knowledge: its index by default, or the "
                    + "referenced sibling file (e.g. api, sources-0, javadoc-<class>) by short name, so agents can "
                    + "load knowledge on demand without re-indexing.")
    public String content(
            @JsonRpcParam(required = true, documentation = "Artifact as groupId:artifactId:version.") final String gav,
            @JsonRpcParam(
                            documentation =
                                    "Optional knowledge file short name read from the index; omit for the index itself.")
                    final String file) {
        final Gav g = Gav.parse(gav);
        if (g.version() == null) {
            return "version required for content lookup: " + g.coordinate();
        }
        final var writer = new KnowledgeWriter(knowledgeConfig.localRepository());
        if (!writer.alreadyIndexed(g)) {
            return "no knowledge indexed for " + g.coordinate();
        }
        final Path target;
        if (file == null || file.isBlank() || file.equals("index")) {
            target = writer.indexFile(g);
        } else {
            target = writer.sibling(g, file);
        }
        if (!Files.isRegularFile(target)) {
            return "no knowledge file " + g.coordinate() + " for '" + file + "'";
        }
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            return "unable to read " + target + ": " + e.getMessage();
        }
    }

    private Indexer newIndexer(final KnowledgeConfig effective, final KnowledgeLlm llm) {
        final WebFetch webfetch = (url, timeout, bytes) -> {
            final var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeout))
                    .header("accept", "text/plain,text/html,text/markdown,application/json,*/*;q=0.8")
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
            final var length = Math.min(body.length, Math.min(bytes, 65536));
            return new String(body, 0, length, StandardCharsets.UTF_8);
        };
        return new Indexer(
                new ContentAcquirer(localRepository, webfetch, effective),
                new KnowledgeWriter(effective.localRepository()),
                new KnowledgeFactory(new KnowledgeWriter(effective.localRepository())),
                llm,
                effective);
    }
}
