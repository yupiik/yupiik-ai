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
package io.yupiik.maven.knowledge.mcp.llm;

import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.mcp.model.Content;
import io.yupiik.fusion.mcp.model.CreateMessageResponse;
import io.yupiik.fusion.mcp.model.CreateSamplingMessageParameters;
import io.yupiik.fusion.mcp.model.Role;
import io.yupiik.fusion.mcp.model.SamplingMessage;
import io.yupiik.fusion.mcp.protocol.MCPSessions;
import io.yupiik.maven.knowledge.Gav;
import io.yupiik.maven.knowledge.llm.KnowledgeLlm;
import io.yupiik.maven.knowledge.llm.LlmText;
import io.yupiik.maven.knowledge.llm.MvnProjectDeps;
import io.yupiik.maven.knowledge.llm.SkillPrompts;
import io.yupiik.maven.knowledge.process.ProcessService;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link KnowledgeLlm} that delegates every LLM call to the MCP client through the sampling protocol
 * ({@code sampling/createMessage}) instead of talking to a provider directly. The host decides the model and the
 * flavour (OpenAI, Anthropic, ...), so the server works with any MCP client that declared the {@code sampling}
 * capability. Structured output is requested through the prompt and parsed back with {@link JsonMapper}.
 */
public class MCPKnowledgeLlm implements KnowledgeLlm {

    private static final long MVN_TIMEOUT_MS = 300_000;

    private final MCPSessions sessions;
    private final Request request;
    private final JsonMapper mapper;
    private final String projectDir;
    private final Path dependencyCache;
    private final ProcessService processes;
    private final long timeoutSeconds;

    public MCPKnowledgeLlm(
            final MCPSessions sessions,
            final Request request,
            final JsonMapper mapper,
            final String projectDir,
            final Path dependencyCache,
            final ProcessService processes) {
        this.sessions = sessions;
        this.request = request;
        this.mapper = mapper;
        this.projectDir = projectDir;
        this.dependencyCache = dependencyCache;
        this.processes = processes;
        this.timeoutSeconds = 300;
    }

    @Override
    public boolean available() {
        return true; // sampling is resolved at call time by the client; a failed call degrades the run
    }

    @Override
    public List<Gav> resolveProjectDeps() {
        return MvnProjectDeps.resolve(Path.of(resolveProjectDir()), MVN_TIMEOUT_MS, dependencyCache, processes);
    }

    /** Project directory used to resolve the deps: configured value, else the process working directory. */
    private String resolveProjectDir() {
        if (projectDir != null && !projectDir.isBlank()) {
            return projectDir;
        }
        final var userDir = System.getProperty("user.dir");
        return userDir == null || userDir.isBlank() ? "" : userDir;
    }

    @Override
    public Map<Gav, List<String>> entryPoints(final Map<Gav, String> contentByGav) {
        final var out = new LinkedHashMap<Gav, List<String>>();
        if (contentByGav.isEmpty()) {
            return out;
        }
        final var sb = new StringBuilder();
        contentByGav.forEach((gav, content) -> sb.append(gav.coordinate())
                .append(":\n")
                .append(content == null ? "" : (content.length() > 60_000 ? content.substring(0, 60_000) : content))
                .append("\n\n"));
        final var prompt = "Analyse the following Maven artifacts. For each one list its core entry points: entry "
                + "classes, main methods, @Service/@Config/@Application style initialisation points or the functional "
                + "interfaces to use, as fully-qualified type names. Respond ONLY with a JSON object of the exact "
                + "shape {\"items\":[{\"gav\":string,\"entryPoints\":[string]}]}, one item per artifact, preserving "
                + "the input coordinates verbatim.\n\n" + sb;
        final var text = sample(prompt, "You extract Maven artifact entry points. Always answer with valid JSON only.");
        if (text == null || text.isBlank()) {
            return out;
        }
        try {
            final var parsed = mapper.read(Root.class, new StringReader(LlmText.trimJson(text)));
            if (parsed.items() != null) {
                for (final var item : parsed.items()) {
                    if (item != null && item.gav() != null) {
                        out.put(Gav.parse(item.gav()), item.entryPoints() == null ? List.of() : item.entryPoints());
                    }
                }
            }
        } catch (final RuntimeException e) {
            // malformed answer: degrade to no entry points for this batch
        }
        return out;
    }

    @Override
    public List<Gav> suggestEntryGavs(final List<Gav> candidates) {
        return candidates; // no project context in the MCP shell: suggest all not-yet-indexed candidates
    }

    @Override
    public String synthesizeSkill(
            final Gav gav, final String name, final String content, final List<String> entryPoints) {
        final var prompt = SkillPrompts.systemFor(gav, entryPoints)
                + "\n\nArtifact content:\n"
                + (content == null ? "" : (content.length() > 60_000 ? content.substring(0, 60_000) : content));
        final var text = sample(
                prompt,
                "You write concise SKILL.md files for coding agents. Output the SKILL.md "
                        + "content directly, without a markdown code fence.");
        if (text == null || text.isBlank()) {
            return fallback(gav, name, entryPoints);
        }
        return LlmText.stripMarkdownFence(text);
    }

    private String sample(final String user, final String system) {
        try {
            final var stage = sessions.of(request)
                    .createMessage(new CreateSamplingMessageParameters(
                            null,
                            8192,
                            List.of(new SamplingMessage(Role.user, Content.text(user))),
                            null,
                            null,
                            null,
                            system,
                            null));
            final CreateMessageResponse response = stage.toCompletableFuture().get(timeoutSeconds, TimeUnit.SECONDS);
            return response.content() == null ? null : response.content().text();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (final ExecutionException | TimeoutException e) {
            return null;
        }
    }

    private static String fallback(final Gav gav, final String name, final List<String> entryPoints) {
        final var sb = new StringBuilder();
        sb.append("---\nname: ")
                .append(name)
                .append("\ndescription: ")
                .append(name)
                .append(" - ")
                .append(gav.coordinate())
                .append(".\n---\n\n# ")
                .append(name)
                .append('\n');
        if (!entryPoints.isEmpty()) {
            sb.append("\n## Entry points\n");
            for (final var ep : entryPoints) {
                sb.append("- `").append(ep).append("`\n");
            }
        }
        return sb.toString();
    }
}
