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

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import java.util.List;

/**
 * Typed payloads of the MCP web search providers (the text of the tools/call result content).
 * Exa is not represented here: its answer is a plain text block layout, not JSON.
 */
public final class WebSearchResponses {

    private WebSearchResponses() {
        // holder for the provider payload records
    }

    /** Parallel {@code web_search} answer: results with excerpts and an optional publish date. */
    @JsonModel
    public record ParallelResponse(
            @JsonProperty("search_id") String searchId, List<ParallelItem> results) {
        @JsonModel
        public record ParallelItem(
                String url,
                String title,
                @JsonProperty("publish_date") String publishDate,
                List<String> excerpts) {}
    }

    /** Firecrawl {@code firecrawl_search} answer: a web result list under data. */
    @JsonModel
    public record FirecrawlResponse(boolean success, FirecrawlData data) {
        @JsonModel
        public record FirecrawlData(List<FirecrawlItem> web) {}

        @JsonModel
        public record FirecrawlItem(String url, String title, String description) {}
    }

    /** TinyFish {@code search} answer: results with a snippet per site. */
    @JsonModel
    public record TinyfishResponse(String query, List<TinyfishItem> results) {
        @JsonModel
        public record TinyfishItem(
                int position, @JsonProperty("site_name") String siteName, String snippet, String title, String url) {}
    }
}
