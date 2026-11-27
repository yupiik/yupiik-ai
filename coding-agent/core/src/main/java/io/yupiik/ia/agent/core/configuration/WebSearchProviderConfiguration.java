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
 * One entry of the websearch provider chain: the provider to use, whether it participates in the
 * fallback, and optional per-provider endpoint/credential overrides.
 */
@JsonModel
public record WebSearchProviderConfiguration(
        @Property(
                required = true,
                documentation = "Provider name: EXA, PARALLEL, FIRECRAWL or TINYFISH (uppercase constant names).")
        WebSearchProvider provider,

        @Property(defaultValue = "true", documentation = "Enable this provider in the websearch fallback chain.")
        boolean enabled,

        @Property(
                documentation =
                        "Endpoint URL override; defaults to the provider public MCP endpoint (anonymously callable).")
        String url,

        @Property(
                documentation = "Optional API key. Falls back to the provider environment variable when empty "
                        + "(EXA_API_KEY, PARALLEL_API_KEY, FIRECRAWL_API_KEY, TINYFISH_API_KEY), "
                        + "anonymous when unset.")
        String apiKey,

        @Property(documentation = "Extra KEY=VALUE headers appended to every request of this provider.")
        List<String> headers) {}
