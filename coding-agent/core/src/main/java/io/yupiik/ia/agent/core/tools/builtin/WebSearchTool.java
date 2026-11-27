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
package io.yupiik.ia.agent.core.tools.builtin;

import static io.yupiik.ia.agent.core.tools.ToolArgs.arg;
import static io.yupiik.ia.agent.core.tools.ToolArgs.optInt;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.mcp.WebSearchManager;
import io.yupiik.ia.agent.core.sandbox.SandboxHttpTransport;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

@ApplicationScoped
public class WebSearchTool implements EmbeddedTool {
    private static final String NO_RESULTS = "No search results found. Please try a different query.";

    private final WebSearchManager manager;
    private final CodingAgentConfiguration configuration;

    private final JsonMapper mapper;

    public WebSearchTool(
            final WebSearchManager manager, final CodingAgentConfiguration configuration, final JsonMapper mapper) {
        this.manager = manager;
        this.configuration = configuration;
        this.mapper = mapper;
    }

    protected WebSearchTool() {
        this.manager = null;
        this.configuration = null;
        this.mapper = null;
    }

    @Override
    public String name() {
        return "websearch";
    }

    @Override
    public String description(final Path workdir) {
        return "Search the web and return a list of results with title, url, snippet and optional publish date. "
                + "Uses the configured MCP web search providers (exa, parallel, firecrawl, tinyfish) "
                + "with automatic fallback between them.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public boolean readonly() {
        return true;
    }

    /** Whether at least one provider is enabled; the tool is hidden from the model when false. */
    @Override
    public boolean usable(final Path workdir) {
        return manager != null && manager.hasEnabledProviders();
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(
                Map.of(
                        "query", Schemas.str("The search query."),
                        "num_results", Schemas.intProp("Number of results to return, default 8.")),
                "query");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        final var query = arg(args, "query");
        if (query == null || query.isBlank()) {
            return ToolResult.error("query is required");
        }
        if (!usable(context.projectDir())) {
            return ToolResult.error("websearch is disabled: no provider is enabled (yca.tools.websearch.providers)");
        }
        final var numResults = (int) Math.max(
                1, optInt(args, "num_results", configuration.tools().websearch().numResults()));
        try {
            final List<WebSearchManager.WebSearchResult> results;
            if (context.sandbox().enabled()) {
                // Never reuse ambient provider clients or sessions across sandbox boundaries.
                try (final var transport = new SandboxHttpTransport(
                                context.sandbox(),
                                () -> context.isCancelled() || context.isCallCancelled(),
                                2_000_000);
                        final var client =
                                new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(transport));
                        final var scoped = new SearchSession(new WebSearchManager(mapper, configuration, client))) {
                    try {
                        results = scoped.manager.search(query, numResults);
                    } catch (final Exception error) {
                        transport.throwIfDenied();
                        throw error;
                    }
                }
            } else {
                results = manager.search(query, numResults);
            }
            return ToolResult.ok(results.isEmpty() ? NO_RESULTS : format(results));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Web search interrupted");
        } catch (final Exception e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof SecurityException) {
                    return ToolResult.error("Sandbox restriction: " + cause.getMessage());
                }
            }
            return ToolResult.error("Web search failed: " + e.getMessage());
        }
    }

    private record SearchSession(WebSearchManager manager) implements AutoCloseable {
        @Override
        public void close() {
            manager.close();
        }
    }

    private String format(final List<WebSearchManager.WebSearchResult> results) {
        final var sb = new StringBuilder();
        for (final var result : results) {
            sb.append("- ");
            final var title = result.title() == null ? "" : result.title();
            if (!title.isBlank()) {
                sb.append(title).append(' ');
            }
            final var url = result.url() == null ? "" : result.url();
            final var published = result.published() == null ? "" : result.published();
            if (!url.isBlank() || !published.isBlank()) {
                final var parts = new StringJoiner(", ");
                if (!url.isBlank()) {
                    parts.add(url);
                }
                if (!published.isBlank()) {
                    parts.add("published " + published);
                }
                sb.append('(').append(parts).append(')');
            }
            final var snippet = result.snippet() == null ? "" : result.snippet();
            if (!snippet.isBlank()) {
                sb.append('\n')
                        .append(snippet.lines()
                                .map(line -> line.isBlank() ? "" : "  " + line)
                                .collect(Collectors.joining("\n")));
            }
            sb.append('\n');
        }
        return sb.toString().strip();
    }
}
