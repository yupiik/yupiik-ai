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
package io.yupiik.ia.agent.core.mcp;

import io.yupiik.ai.mcp.client.HttpMcpClient;
import io.yupiik.ai.mcp.client.McpHttpException;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.WebSearchProvider;
import io.yupiik.ia.agent.core.configuration.WebSearchProviderConfiguration;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Facade over the configured MCP web search providers: lazily builds and initializes one
 * {@link HttpMcpClient} per provider and runs a search through the enabled chain, falling back
 * to the next provider on failure and cooling down rate-limited (HTTP 429) providers for the
 * duration announced by their Retry-After header (60s by default).
 *
 * <p>API keys are resolved once, when the provider client is built: explicit configuration wins,
 * then the provider environment variable (EXA_API_KEY, PARALLEL_API_KEY, FIRECRAWL_API_KEY,
 * TINYFISH_API_KEY), otherwise the request is anonymous (tinyfish requires an explicit keyless
 * header, handled here).</p>
 */
@ApplicationScoped
public class WebSearchManager {
    private static final Pattern EXA_RESULT_SEPARATOR = Pattern.compile("\n\n---\n\n");

    private static final long DEFAULT_COOLDOWN_MILLIS = 60_000L;

    private final JsonMapper mapper;
    private final CodingAgentConfiguration configuration;
    private final ExtendedHttpClient httpClient;
    private final Function<String, String> environment;
    private final LongSupplier clock;

    private final Map<WebSearchProvider, ProviderClient> clients = new ConcurrentHashMap<>();
    private final Map<WebSearchProvider, Cooldown> cooldowns = new ConcurrentHashMap<>();

    public WebSearchManager(
            final JsonMapper mapper,
            final CodingAgentConfiguration configuration,
            final ExtendedHttpClient httpClient) {
        this(mapper, configuration, httpClient, System::getenv, System::currentTimeMillis);
    }

    WebSearchManager(
            final JsonMapper mapper,
            final CodingAgentConfiguration configuration,
            final ExtendedHttpClient httpClient,
            final Function<String, String> environment,
            final LongSupplier clock) {
        this.mapper = mapper;
        this.configuration = configuration;
        this.httpClient = httpClient;
        this.environment = environment;
        this.clock = clock;
    }

    protected WebSearchManager() {
        this.mapper = null;
        this.configuration = null;
        this.httpClient = null;
        this.environment = null;
        this.clock = null;
    }

    /** Whether at least one provider is enabled: the websearch tool is hidden from the model when false. */
    public boolean hasEnabledProviders() {
        return !enabledProviders().isEmpty();
    }

    /**
     * Search the web through the enabled provider chain, in configuration order.
     * An empty result list is a valid answer (no result for the query).
     *
     * @param query     the search query.
     * @param numResults number of results to request; providers without a count argument ignore it.
     * @return the normalized results of the first provider that answered.
     * @throws IOException          when every enabled provider failed (or all are rate limited).
     * @throws InterruptedException when the underlying request is interrupted.
     */
    public List<WebSearchResult> search(final String query, final int numResults)
            throws IOException, InterruptedException {
        final var providers = enabledProviders();
        if (providers.isEmpty()) {
            throw new IOException("No web search provider is enabled (yca.tools.websearch.providers)");
        }
        final var now = clock.getAsLong();
        IOException failure = null;
        for (final var config : providers) {
            final var provider = config.provider();
            final var cooldown = cooldowns.get(provider);
            if (cooldown != null) {
                if (cooldown.until() > now) {
                    failure = new IOException(provider + " is in rate limit cooldown: " + cooldown.message());
                    continue;
                }
                cooldowns.remove(provider);
            }
            try {
                return doSearch(config, query, numResults);
            } catch (final InterruptedException e) {
                throw e;
            } catch (final McpHttpException e) {
                if (e.statusCode() == 429) {
                    final var delay = e.retryAfterMillis() == null ? DEFAULT_COOLDOWN_MILLIS : e.retryAfterMillis();
                    cooldowns.put(provider, new Cooldown(clock.getAsLong() + delay, e.getMessage()));
                }
                failure = e;
            } catch (final IOException e) {
                failure = e;
            } catch (final RuntimeException e) {
                failure = new IOException(provider + ": " + e.getMessage(), e);
            }
        }
        throw failure == null ? new IOException("No web search provider is available") : failure;
    }

    @Destroy
    public void close() {
        clients.values().forEach(client -> client.client.close());
        clients.clear();
    }

    private List<WebSearchResult> doSearch(
            final WebSearchProviderConfiguration config, final String query, final int numResults)
            throws IOException, InterruptedException {
        final var provider = config.provider();
        final var client = clients.computeIfAbsent(provider, p -> new ProviderClient(buildClient(config)));
        if (!client.initialized) {
            client.lock.lock();
            try {
                if (!client.initialized) {
                    client.client.initialize();
                    client.initialized = true;
                }
            } finally {
                client.lock.unlock();
            }
        }
        final var text = client.client.callTool(provider.toolName(), arguments(provider, query, numResults));
        if (text == null || text.isBlank()) {
            throw new IOException("Empty '" + provider + "' response");
        }
        return parse(provider, text);
    }

    private List<WebSearchProviderConfiguration> enabledProviders() {
        final var configured = configuration.tools().websearch().providers();
        if (configured == null) {
            return List.of();
        }
        return configured.stream()
                .filter(WebSearchProviderConfiguration::enabled)
                .toList();
    }

    private HttpMcpClient buildClient(final WebSearchProviderConfiguration config) {
        final var provider = config.provider();
        final var key = resolveKey(config, provider);
        final var base = config.url() != null && !config.url().isBlank() ? config.url() : provider.defaultUrl();
        final var url = key != null && provider.authStrategy() == WebSearchProvider.AuthStrategy.URL_PARAM
                ? base + (base.contains("?") ? "&" : "?") + "exaApiKey=" + key
                : base;
        final var extraHeaders = new ArrayList<String>();
        if (provider.authStrategy() == WebSearchProvider.AuthStrategy.HEADER_KEYLESS) {
            extraHeaders.add(key != null ? "X-API-Key=" + key : "X-TinyFish-Access-Mode=keyless");
        }
        if (config.headers() != null) {
            extraHeaders.addAll(config.headers());
        }
        final var bearer = key != null && provider.authStrategy() == WebSearchProvider.AuthStrategy.BEARER ? key : null;
        return new HttpMcpClient(
                provider.name().toLowerCase(Locale.ROOT),
                url,
                bearer,
                extraHeaders,
                configuration.tools().websearch().timeoutSeconds(),
                mapper,
                httpClient);
    }

    private String resolveKey(final WebSearchProviderConfiguration config, final WebSearchProvider provider) {
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            return config.apiKey();
        }
        final var fromEnv = environment.apply(provider.envVariable());
        return fromEnv == null || fromEnv.isBlank() ? null : fromEnv;
    }

    private Map<String, Object> arguments(final WebSearchProvider provider, final String query, final int numResults) {
        return switch (provider) {
            case EXA -> exaArguments(query, numResults);
            case PARALLEL -> Map.of("objective", query, "search_queries", List.of(query), "model_name", "opencode");
            case FIRECRAWL -> Map.of("query", query, "limit", numResults);
            case TINYFISH -> Map.of("query", query);
        };
    }

    private Map<String, Object> exaArguments(final String query, final int numResults) {
        final var arguments = new LinkedHashMap<String, Object>();
        arguments.put("query", query);
        arguments.put("type", "auto");
        arguments.put("numResults", numResults);
        arguments.put("livecrawl", "fallback");
        arguments.put("textLengthRange", Map.of("minLength", 20));
        return arguments;
    }

    private List<WebSearchResult> parse(final WebSearchProvider provider, final String text) {
        return switch (provider) {
            case EXA -> parseExa(text);
            case PARALLEL -> parsePayload(text, WebSearchResponses.ParallelResponse.class, this::parallelResults);
            case FIRECRAWL -> parsePayload(text, WebSearchResponses.FirecrawlResponse.class, this::firecrawlResults);
            case TINYFISH -> parsePayload(text, WebSearchResponses.TinyfishResponse.class, this::tinyfishResults);
        };
    }

    private <T> List<WebSearchResult> parsePayload(
            final String text, final Class<T> type, final Function<T, List<WebSearchResult>> mapping) {
        final T payload;
        try {
            payload = mapper.fromString(type, text);
        } catch (final RuntimeException e) {
            return rawResult(text);
        }
        if (payload == null) {
            return rawResult(text);
        }
        final var mapped = mapping.apply(payload);
        return mapped == null ? rawResult(text) : mapped;
    }

    private List<WebSearchResult> parallelResults(final WebSearchResponses.ParallelResponse payload) {
        if (payload.results() == null) {
            return null;
        }
        return payload.results().stream()
                .map(item -> new WebSearchResult(
                        item.title() == null ? "" : item.title(),
                        item.url() == null ? "" : item.url(),
                        item.excerpts() == null ? "" : String.join("\n\n", item.excerpts()),
                        item.publishDate()))
                .toList();
    }

    private List<WebSearchResult> firecrawlResults(final WebSearchResponses.FirecrawlResponse payload) {
        if (!payload.success()) {
            throw new IllegalStateException("firecrawl reported a failed search");
        }
        if (payload.data() == null || payload.data().web() == null) {
            return null;
        }
        return payload.data().web().stream()
                .map(item -> new WebSearchResult(
                        item.title() == null ? "" : item.title(),
                        item.url() == null ? "" : item.url(),
                        item.description() == null ? "" : item.description(),
                        null))
                .toList();
    }

    private List<WebSearchResult> tinyfishResults(final WebSearchResponses.TinyfishResponse payload) {
        if (payload.results() == null) {
            return null;
        }
        return payload.results().stream()
                .map(item -> new WebSearchResult(
                        item.title() == null ? "" : item.title(),
                        item.url() == null ? "" : item.url(),
                        item.snippet() == null ? "" : item.snippet(),
                        null))
                .toList();
    }

    private List<WebSearchResult> rawResult(final String text) {
        return List.of(new WebSearchResult("", "", text.strip(), null));
    }

    // exa answers with text blocks, not JSON: "Title:", "URL:", "Published:", "Author:" fields then
    // the content after a "Highlights:" or "Text:" line; blocks are separated by a "---" rule.
    private List<WebSearchResult> parseExa(final String text) {
        final var results = new ArrayList<WebSearchResult>();
        for (final var block : EXA_RESULT_SEPARATOR.split(text)) {
            String url = null;
            String title = null;
            String published = null;
            final var content = new StringBuilder();
            var inContent = false;
            for (final var line : block.split("\n")) {
                final var trimmed = line.strip();
                if (trimmed.startsWith("URL:")) {
                    url = trimmed.substring(4).strip();
                    inContent = false;
                } else if (trimmed.startsWith("Title:")) {
                    final var value = trimmed.substring(6).strip();
                    title = "N/A".equals(value) ? null : value;
                    inContent = false;
                } else if (trimmed.startsWith("Published:")) {
                    final var value = trimmed.substring(10).strip();
                    published = "N/A".equals(value) ? null : value;
                    inContent = false;
                } else if (trimmed.startsWith("Highlights:") || trimmed.startsWith("Text:")) {
                    inContent = true;
                } else if (trimmed.startsWith("Author:")) {
                    inContent = false;
                } else if (inContent) {
                    content.append(line).append('\n');
                }
            }
            if (url != null && !url.isBlank()) {
                results.add(new WebSearchResult(
                        title == null ? "" : title, url, content.toString().strip(), published));
            }
        }
        return results.isEmpty() ? rawResult(text) : results;
    }

    /** Normalized web search result, provider independent. */
    public record WebSearchResult(String title, String url, String snippet, String published) {}

    private record Cooldown(long until, String message) {}

    private static final class ProviderClient {
        private final HttpMcpClient client;
        private final ReentrantLock lock = new ReentrantLock();
        private volatile boolean initialized;

        private ProviderClient(final HttpMcpClient client) {
            this.client = client;
        }
    }
}
