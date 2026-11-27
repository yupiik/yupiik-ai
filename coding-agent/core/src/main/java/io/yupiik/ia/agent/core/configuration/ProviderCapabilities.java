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

/**
 * Declarative capability knobs of a provider. Unlike {@link ProviderConfiguration#providerSpecificOptions()}
 * these are typed, internal consumer options: they drive yca behaviour and are never merged into the
 * wire request payload.
 */
@JsonModel
public record ProviderCapabilities(
        @Property(documentation = "Codex-flavored capability knobs (client version, reasoning include, usage quota).")
        CodexCapabilities codex,

        @Property(
                documentation =
                        "OpenAI-flavored capability knobs (shared by the responses and codex flavors): session affinity keys and "
                                + "the per-request x-client-request-id marker.")
        OpenAICapabilities openai,

        @Property(documentation = "Experimental OpenCode free inference transport settings.")
        OpenCodeCapabilities opencode,

        @Property(
                documentation =
                        "Concurrency budget knobs: sharing the provider budget across the yca processes of the machine.")
        ConcurrencyCapabilities concurrency) {
    public ProviderCapabilities(final CodexCapabilities codex, final OpenAICapabilities openai) {
        this(codex, openai, null, null);
    }
}
