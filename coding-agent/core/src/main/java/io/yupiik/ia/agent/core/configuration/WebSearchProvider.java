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
package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.json.JsonModel;

/**
 * MCP web search providers supported by the websearch tool. Each constant carries the fixed
 * metadata of its public MCP endpoint so configuration entries stay minimal.
 *
 * <p>Values are parsed with the standard {@code valueOf}, so flat configuration entries must
 * use the uppercase constant names ({@code yca.tools.websearch.providers.0.provider=EXA}).</p>
 */
@JsonModel
public enum WebSearchProvider {
    EXA("https://mcp.exa.ai/mcp", "EXA_API_KEY", "web_search_exa", AuthStrategy.URL_PARAM, CountArgument.NUM_RESULTS),
    PARALLEL(
            "https://search.parallel.ai/mcp",
            "PARALLEL_API_KEY",
            "web_search",
            AuthStrategy.BEARER,
            CountArgument.NONE),
    FIRECRAWL(
            "https://mcp.firecrawl.dev/v2/mcp",
            "FIRECRAWL_API_KEY",
            "firecrawl_search",
            AuthStrategy.BEARER,
            CountArgument.LIMIT),
    TINYFISH(
            "https://agent.tinyfish.ai/mcp",
            "TINYFISH_API_KEY",
            "search",
            AuthStrategy.HEADER_KEYLESS,
            CountArgument.NONE);

    /** How an optional API key is transmitted to the provider endpoint. */
    public enum AuthStrategy {
        /** Key appended as {@code exaApiKey} URL query parameter. */
        URL_PARAM,
        /** Key sent as {@code Authorization: Bearer} header. */
        BEARER,
        /** Key sent as {@code X-API-Key} header; anonymous requests carry {@code X-TinyFish-Access-Mode: keyless}. */
        HEADER_KEYLESS
    }

    /** How the requested result count maps to the provider search argument. */
    public enum CountArgument {
        /** The provider has no result count argument. */
        NONE,
        /** MCP argument name {@code numResults}. */
        NUM_RESULTS,
        /** MCP argument name {@code limit}. */
        LIMIT
    }

    private final String defaultUrl;
    private final String envVariable;
    private final String toolName;
    private final AuthStrategy authStrategy;
    private final CountArgument countArgument;

    WebSearchProvider(
            final String defaultUrl,
            final String envVariable,
            final String toolName,
            final AuthStrategy authStrategy,
            final CountArgument countArgument) {
        this.defaultUrl = defaultUrl;
        this.envVariable = envVariable;
        this.toolName = toolName;
        this.authStrategy = authStrategy;
        this.countArgument = countArgument;
    }

    /** Public MCP endpoint used when the provider configuration entry has no url override. */
    public String defaultUrl() {
        return defaultUrl;
    }

    /** Environment variable read (once, when the provider client is built) when no apiKey is configured. */
    public String envVariable() {
        return envVariable;
    }

    /** MCP tool name to call on the provider endpoint. */
    public String toolName() {
        return toolName;
    }

    public AuthStrategy authStrategy() {
        return authStrategy;
    }

    public CountArgument countArgument() {
        return countArgument;
    }
}
