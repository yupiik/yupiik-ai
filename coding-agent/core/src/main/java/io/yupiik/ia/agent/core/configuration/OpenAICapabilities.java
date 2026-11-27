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
 * OpenAI-flavored capability knobs of a provider (Completions, Responses and Codex, as applicable).
 * These are typed internal consumer options
 * that are never merged into the wire request payload (unlike
 * {@link ProviderConfiguration#providerSpecificOptions()}).
 */
@JsonModel
public record OpenAICapabilities(
        @Property(
                documentation =
                        "Session affinity knobs. When the provider keeps a per-session server-side prompt cache "
                                + "(ChatGPT/Codex, SGLang session-aware radix cache, caching gateways), yca routes every "
                                + "request of a session to the same cache by deriving one stable session-scoped UUID from the "
                                + "local transcript id and emitting it under each enabled key. `prompt_cache_key` is emitted as a "
                                + "Responses body field; the other keys are emitted as HTTP headers. Defaults (when unset) are "
                                + "flavor-specific: the codex flavor emits `session-id` + `prompt_cache_key`, the openai-responses "
                                + "flavor emits `x-session-id` + `prompt_cache_key` (the underscore form `session_id` is rejected by "
                                + "hardened Envoy/nginx gateways). Set to an empty list to disable session affinity.")
        SessionIdCapabilities sessionId,

        @Property(
                defaultValue = "false",
                documentation =
                        "Whether to emit a fresh per-request `x-client-request-id` header (a new UUID per request, not the "
                                + "stable session value). Mirrors the Codex CLI's per-thread request marker. Internal only (never sent "
                                + "as a body field).")
        boolean xClientRequestIdEnabled,

        @Property(
                defaultValue = "false",
                documentation =
                        "Replay retained assistant thinking as reasoning_content for Chat Completions only. "
                                + "Opt in only for compatible reasoning servers; disabled by default and ignored by other API flavors.")
        boolean replayReasoning) {

    public OpenAICapabilities(final SessionIdCapabilities sessionId, final boolean xClientRequestIdEnabled) {
        this(sessionId, xClientRequestIdEnabled, false);
    }

    /** The OpenAPI session-affinity knobs: the ordered list of keys under which the session UUID is emitted. */
    @JsonModel
    public record SessionIdCapabilities(
            @Property(
                    documentation =
                            "Ordered list of key names under which the session-scoped UUID is emitted. `prompt_cache_key` names a "
                                    + "Responses body field; any other key names an HTTP header.")
            List<String> enabledKeys) {}
}
