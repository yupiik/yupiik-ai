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
package io.yupiik.llm.client.codex.model;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import java.util.List;

/**
 * The model catalog returned by the ChatGPT Codex backend at {@code /codex/models?client_version=N}.
 * This is a different shape than the public OpenAI {@code {data:[...]}} models endpoint: an array of
 * per-model objects carrying eligibility fields (visibility, supported_in_api, minimal_client_version)
 * that the public endpoint does not expose.
 *
 * @param models the catalog entries.
 */
@JsonModel
public record CodexModels(List<CodexModel> models) {

    /** A single Codex catalog entry carrying the fields relevant to model discovery. */
    @JsonModel
    public record CodexModel(
            @JsonProperty("slug") String slug,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("description") String description,
            @JsonProperty("visibility") String visibility,
            @JsonProperty("supported_in_api") Boolean supportedInApi,
            @JsonProperty("minimal_client_version") List<Integer> minimalClientVersion,
            @JsonProperty("context_window") Long contextWindow,
            @JsonProperty("max_context_window") Long maxContextWindow,
            @JsonProperty("default_reasoning_level") String defaultReasoningLevel,
            @JsonProperty("supported_reasoning_levels") List<ReasoningLevel> supportedReasoningLevels) {

        /** The model id handed to the Responses API {@code model} field. */
        public String id() {
            return slug;
        }

        /** True when the model is publicly selectable (listed, API-backed and not internal). */
        public boolean selectable() {
            return !Boolean.FALSE.equals(supportedInApi) && "list".equalsIgnoreCase(visibility);
        }

        /**
         * True when this model can be used by a client advertising {@code clientVersion} (the whole
         * part of {@code 0.<clientVersion>.0}). The {@code minimal_client_version} list is
         * {@code [major, minor, patch]}; a model is usable when it requires no newer client than the
         * one advertising. Codex's own versions are {@code 0.x.y} (major 0), so the effective bar is
         * the minor: a model requiring a later major (eg. {@code [200,0,0]}) is gated, a minor
         * ({@code [0,98,0]}) is usable only when {@code minor <= clientVersion}.
         */
        public boolean usableWith(final int clientVersion) {
            if (minimalClientVersion == null || minimalClientVersion.isEmpty()) {
                return true;
            }
            final Integer major = minimalClientVersion.get(0);
            if (major != null && major > 0) {
                // requires a (future) major-version client this client does not advertise
                return false;
            }
            final Integer minor = minimalClientVersion.size() > 1 ? minimalClientVersion.get(1) : 0;
            return minor == null || minor <= clientVersion;
        }
    }

    /** A reasoning effort advertised by the catalog for a model. */
    @JsonModel
    public record ReasoningLevel(
            @JsonProperty("effort") String effort,
            @JsonProperty("description") String description) {}
}
