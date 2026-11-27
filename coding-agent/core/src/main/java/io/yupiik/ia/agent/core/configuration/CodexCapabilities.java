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
 * Codex-flavored capability knobs of a provider. These are internal consumer options that are
 * never merged into the wire request payload (unlike {@link ProviderConfiguration#providerSpecificOptions()}),
 * so they cannot live in the wire-passed options bag.
 */
@JsonModel
public record CodexCapabilities(
        @Property(
                defaultValue = "160",
                documentation =
                        "The Codex client version this provider advertises for model gating. Internal only (never sent on the wire). Formerly carried in providerSpecificOptions under the key codex_client_version.")
        int clientVersion,

        @Property(
                defaultValue = "false",
                documentation =
                        "Whether the provider opts into the encrypted-reasoning include directive (requesting the opaque reasoning.encrypted_content blob for multi-turn continuity). The ChatGPT/Codex and stricter Codex/gov edges reject the directive with an unknown-parameter 400, and the base Responses dialect auto-downgrades once when a gateway rejects it anyway. Internal only (never sent on the wire). Formerly carried in providerSpecificOptions under the key reasoning_include.")
        boolean includeReasoning,

        @Property(
                defaultValue = "false",
                documentation =
                        "Whether yca should query this provider's account usage/rate-limit backend. When true (and the provider is a Codex-flavored one - baseUrl pointing at the Codex backend) the /usage slash command and the sidebar quota gauge are available for sessions running on this provider. Enabling it lets yca contact the codex quota endpoint with the session stored in the provider's codex.auth.json (see /codex login).")
        boolean enableUsage) {}
