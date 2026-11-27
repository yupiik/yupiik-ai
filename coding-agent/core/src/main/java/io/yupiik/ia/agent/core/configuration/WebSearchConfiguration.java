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

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.List;

/**
 * Configuration of the websearch tool: the MCP provider fallback chain and its defaults.
 *
 * <p>By default all four providers are enabled anonymously, in the order EXA, PARALLEL, FIRECRAWL,
 * TINYFISH; a search uses the first available provider and falls back to the next one on failure.
 * An explicit list replaces the defaults entirely and an empty list
 * ({@code yca.tools.websearch.providers.length=0}) disables the tool.</p>
 */
@JsonModel
public record WebSearchConfiguration(
        @Property(
                defaultValue = """
                        java.util.List.of(
                                new WebSearchProviderConfiguration(WebSearchProvider.EXA, true, null, null, null),
                                new WebSearchProviderConfiguration(WebSearchProvider.PARALLEL, true, null, null, null),
                                new WebSearchProviderConfiguration(WebSearchProvider.FIRECRAWL, true, null, null, null),
                                new WebSearchProviderConfiguration(WebSearchProvider.TINYFISH, true, null, null, null))""",
                documentation =
                        "Search providers in fallback order. Default: all four (EXA, PARALLEL, FIRECRAWL, TINYFISH), "
                                + "anonymously. Explicit entries replace the defaults entirely; an empty list "
                                + "(yca.tools.websearch.providers.length=0) disables the websearch tool. "
                                + "Flat configuration format: yca.tools.websearch.providers.length=1, "
                                + "yca.tools.websearch.providers.0.provider=EXA, "
                                + "yca.tools.websearch.providers.0.enabled=false, ...")
        List<WebSearchProviderConfiguration> providers,

        @Property(
                defaultValue = "8",
                documentation =
                        "Number of results to request; providers without a count argument (parallel, tinyfish) ignore it.")
        int numResults,

        @Property(defaultValue = "25", documentation = "MCP request timeout in seconds, per provider call.")
        int timeoutSeconds) {}
