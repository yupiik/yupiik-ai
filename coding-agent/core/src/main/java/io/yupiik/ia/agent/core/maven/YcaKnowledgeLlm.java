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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.maven.json.EntryPointsRoot;
import io.yupiik.ia.agent.core.maven.json.SkillRoot;
import io.yupiik.ia.agent.core.maven.json.SuggestRoot;
import io.yupiik.maven.knowledge.Gav;
import io.yupiik.maven.knowledge.config.TokenBudget;
import io.yupiik.maven.knowledge.llm.KnowledgeLlm;
import io.yupiik.maven.knowledge.llm.LlmText;
import io.yupiik.maven.knowledge.llm.MvnProjectDeps;
import io.yupiik.maven.knowledge.llm.SkillPrompts;
import io.yupiik.maven.knowledge.process.ProcessService;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * {@link KnowledgeLlm} backed by the yca {@link ChatClient}. Uses a single bulk,
 * JSON-Schema constrained call per batch so several artifacts are resolved in one
 * LLM round trip (low call count and cost). Silently defers to the caller whenever
 * structured output is not supported by the provider.
 */
@ApplicationScoped
public class YcaKnowledgeLlm implements KnowledgeLlm {

    private static final long MVN_TIMEOUT_MS = 300_000;

    private final ChatClient chatClient;
    private final JsonMapper mapper;
    private final CodingAgentConfiguration configuration;
    private final ProcessService processes;
    private final Path projectRoot;
    /** The triggering session's provider/model selection, set by the caller around one index run and
     * resolved lazily at each LLM call (never captured at construction). Null = process default. */
    private volatile ChatSettings runSettings;

    public YcaKnowledgeLlm(
            final ChatClient chatClient,
            final JsonMapper mapper,
            final CodingAgentConfiguration configuration,
            final ProcessService processes) {
        this(chatClient, mapper, configuration, processes, null, null);
    }

    private YcaKnowledgeLlm(
            final ChatClient chatClient,
            final JsonMapper mapper,
            final CodingAgentConfiguration configuration,
            final ProcessService processes,
            final Path projectRoot,
            final ChatSettings settings) {
        this.chatClient = chatClient;
        this.mapper = mapper;
        this.configuration = configuration;
        this.processes = processes;
        this.projectRoot = projectRoot;
        this.runSettings = settings;
    }

    /** Captures a project's root and settings for one operation, including indexer worker threads. */
    public KnowledgeLlm forProject(final Path root, final ChatSettings settings) {
        final var effectiveRoot = Objects.requireNonNull(root, "workdir");
        return new YcaKnowledgeLlm(chatClient, mapper, configuration, processes, effectiveRoot, settings);
    }

    /** Binds one index run to the requesting session's provider/model; pass null to fall back to process default. */
    public void withSettings(final ChatSettings settings) {
        this.runSettings = settings;
    }

    @Override
    public boolean available() {
        final var model = chatClient == null ? "" : chatClient.model(runSettings);
        return model != null && !model.isBlank();
    }

    @Override
    public TokenBudget tokenBudget() {
        return new TokenBudget(
                chatClient == null ? 0 : chatClient.contextWindow(runSettings),
                chatClient == null ? 0 : chatClient.outputTokens(runSettings));
    }

    @Override
    public List<Gav> resolveProjectDeps() {
        final var root = Objects.requireNonNull(projectRoot, "workdir");
        return MvnProjectDeps.resolve(root, MVN_TIMEOUT_MS, dependencyCacheDir(), processes);
    }

    /** Where the {@code mvn dependency:list} result is cached (the local repository, by default {@code ~/.m2}). */
    private Path dependencyCacheDir() {
        final var cfg = configuration.mavenRepositoryKnowledge();
        final var raw = cfg == null || cfg.localRepository() == null ? null : cfg.localRepository();
        final var value = raw == null || raw.isBlank() ? "~/.m2/repository" : raw;
        return Path.of(CodingAgentConfiguration.expandHome(value));
    }

    @Override
    public Map<Gav, List<String>> entryPoints(final Map<Gav, String> contentByGav) {
        final var out = new LinkedHashMap<Gav, List<String>>();
        if (contentByGav.isEmpty()) {
            return out;
        }
        final var sb = new StringJoiner("\n\n");
        final List<Gav> order = new ArrayList<>(contentByGav.keySet());
        for (final var gav : order) {
            sb.add(gav.coordinate() + '\n' + contentByGav.get(gav));
        }
        final var prompt = "Identify the main entry points for each of these Maven artifacts. A main"
                + " entry point is a class a consumer would most likely use first: the API facade, the"
                + " module entry class, the @Service/@Config/factory/FunctionalInterface, or the main()"
                + " class. Prefer fully qualified class names. Return one object per artifact in the"
                + " same order they were provided.\n\n" + sb;
        final var schema = Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                        "items",
                        Map.of(
                                "type",
                                "array",
                                "items",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "gav", Map.of("type", "string"),
                                                "entryPoints",
                                                        Map.of("type", "array", "items", Map.of("type", "string"))),
                                        "required",
                                        List.of("gav", "entryPoints")))));
        final String suffix =
                "\nAnswer only with the JSON object {\"items\":[{\"gav\": string," + " \"entryPoints\":[string]}]}.";
        try {
            final var res = chatClient.chatStructured(
                    new ChatCompletionRequest(
                                    null,
                                    List.of(Message.system(prompt + suffix)),
                                    null,
                                    null,
                                    false,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null)
                            .withSettings(runSettings),
                    "entry_points",
                    schema);
            dumpSample(
                    order.size() + " artifact(s)",
                    prompt + suffix,
                    sb.toString(),
                    res.result().message() == null
                            ? null
                            : LlmText.rawContent(res.result().message().content()));
            if (!res.structured()) {
                return out;
            }
            final var raw = LlmText.rawContent(
                    res.result().message() == null
                            ? null
                            : res.result().message().content());
            final var parsed = mapper.read(EntryPointsRoot.class, new StringReader(raw));
            if (parsed.items() != null) {
                for (final var item : parsed.items()) {
                    if (item == null || item.gav() == null) {
                        continue;
                    }
                    out.put(Gav.parse(item.gav()), item.entryPoints() == null ? List.of() : item.entryPoints());
                }
            }
            return out;
        } catch (final IOException | InterruptedException e) {
            if (e instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return out;
        }
    }

    @Override
    public List<Gav> suggestEntryGavs(final List<Gav> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        final var sb = new StringJoiner("\n");
        for (final var gav : candidates) {
            sb.add(gav.coordinate());
        }
        final var schema = Map.of(
                "type",
                "object",
                "properties",
                Map.of("gavs", Map.of("type", "array", "items", Map.of("type", "string"))),
                "required",
                List.of("gavs"));
        try {
            final var res = chatClient.chatStructured(
                    new ChatCompletionRequest(
                                    null,
                                    List.of(Message.system(
                                            "Given this session/project, select the Maven artifacts (from this list) that are"
                                                    + " the main entry points a coding agent should know about first. Return only"
                                                    + " the chosen coordinates in the same format as received.\n\n"
                                                    + sb)),
                                    null,
                                    null,
                                    false,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null)
                            .withSettings(runSettings),
                    "suggested_gavs",
                    schema);
            if (!res.structured()) {
                return candidates;
            }
            final var raw = LlmText.rawContent(
                    res.result().message() == null
                            ? null
                            : res.result().message().content());
            final var parsed = mapper.read(SuggestRoot.class, new StringReader(raw));
            final var out = new ArrayList<Gav>();
            if (parsed.gavs() != null) {
                final var wanted = new HashSet<>(parsed.gavs());
                for (final var gav : candidates) {
                    if (wanted.contains(gav.coordinate())) {
                        out.add(gav);
                    }
                }
            }
            return out;
        } catch (final IOException | InterruptedException e) {
            if (e instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return candidates;
        }
    }

    @Override
    public String synthesizeSkill(
            final Gav gav, final String name, final String content, final List<String> entryPoints) {
        final var schema = Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                        "skill",
                        Map.of(
                                "type",
                                "string",
                                "description",
                                "Markdown SKILL.md content with YAML frontmatter "
                                        + "(name, description) then sections. No surrounding markdown fence.")),
                "required",
                List.of("skill"));
        try {
            final var system = SkillPrompts.systemFor(gav, entryPoints);
            final var res = chatClient.chatStructured(
                    new ChatCompletionRequest(
                                    null,
                                    List.of(Message.system(system), Message.user(content == null ? "" : content)),
                                    null,
                                    null,
                                    false,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null)
                            .withSettings(runSettings),
                    "skill",
                    schema);
            dumpSample(
                    gav,
                    system,
                    content == null ? "" : content,
                    res.result().message() == null
                            ? null
                            : LlmText.rawContent(res.result().message().content()));
            if (!res.structured()) {
                return null;
            }
            final var raw = LlmText.rawContent(
                    res.result().message() == null
                            ? null
                            : res.result().message().content());
            final var parsed = mapper.read(SkillRoot.class, new StringReader(raw));
            final var skill = parsed.skill() == null ? raw : parsed.skill();
            return LlmText.stripMarkdownFence(skill);
        } catch (final IOException | InterruptedException e) {
            if (e instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /**
     * When the {@code yca.dumpKnowledge} system property points at an existing directory, writes the exact prompt
     * sent and the raw response received for one artifact into {@code <dir>/sample.<run>.req} and
     * {@code <dir>/sample.<run>.resp}. Used to capture a real request/response sample for debugging.
     */
    private void dumpSample(final Gav gav, final String system, final String user, final String response) {
        dumpSample(gav.coordinate(), system, user, response);
    }

    private void dumpSample(final String label, final String system, final String user, final String response) {
        if (system == null || !System.getProperties().containsKey("yca.dumpKnowledge")) {
            return;
        }
        try {
            final var dir = Path.of(System.getProperty("yca.dumpKnowledge"));
            final var run = Long.toHexString(System.nanoTime());
            Files.createDirectories(dir);
            final var req = dir.resolve("sample." + run + ".req");
            Files.writeString(
                    req,
                    "GAV: " + label
                            + "\n\n==== system ====\n"
                            + system
                            + "\n\n==== user (content, truncated 6000) ====\n"
                            + (user.length() > 6000 ? user.substring(0, 6000) + "\n...[truncated]" : user));
            final var resp = dir.resolve("sample." + run + ".resp");
            Files.writeString(resp, response == null ? "<null response>" : response);
        } catch (final Exception e) {
            // dump is best-effort, never breaks indexing
        }
    }
}
