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

@JsonModel
public record SubagentConfiguration(
        @Property(defaultValue = "true", documentation = "Enable sub-agent tools (spawn_subagent, check_subagents).")
        boolean enabled,

        @Property(
                defaultValue = "4",
                documentation = "Maximum number of simultaneous sub-agent AgentLoops allowed (a process-local budget, "
                        + "independent of the provider's in-flight LLM budget enforced by the shared client). "
                        + "Cap is also bounded by the model provider's maxConcurrent. Sub-agents queue on the "
                        + "shared LLM client when their requests would exceed the provider's concurrency.")
        int maxConcurrent,

        @Property(
                defaultValue = "250",
                documentation =
                        "Maximum number of model round-trips a sub-agent can execute in one run. A per-spawn max_turns can lower this limit; provider/model iteration caps can lower it further. When unset, the global yca.llm.maxIterations applies.")
        Integer maxIterations,

        @Property(
                defaultValue = "false",
                documentation =
                        "Allow automatic alternative-model routing only with a nonempty exact includes list. The effective same-provider default is authorized even when false; otherwise the parent is the default. Explicit alternatives outside authorization require dedicated route approval, including in YOLO; no approval UI fails closed. Discovery is not authorization.")
        boolean allowModelOverride,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Exact $provider/model alternatives authorized only when overrides are enabled and this list is nonempty. Empty grants no additional autonomy (formerly unrestricted). Does not restrict discovery: all configured models on the active session provider are discoverable by default; an explicit provider filter can inspect another catalog, subject to hard restrictions.")
        List<String> includes,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Exact routed $provider/model hard denies for discovery and every sub-agent, additive with source-provider and destination-provider excludes. Applies even to inherited parents and defaults; provider settings, user approval and exact-route session grants cannot override these.")
        List<String> excludes,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Same-provider default sub-agent route, authorized independently of overrides and includes, subject to hard denies and administrative restrictions. Blank inherits the exact parent route; a different default removes the parent's automatic exemption. Bare names are pinned to the session provider; profiles/defaults cannot implicitly cross providers, bypass authorization or initiate approvals. Cross-provider selection requires explicit $other/model routing and authorization.")
        String defaultModel) {
    public SubagentConfiguration {
        includes = includes == null ? List.of() : List.copyOf(includes);
        excludes = excludes == null ? List.of() : List.copyOf(excludes);
    }
}
