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
public record ProviderSubagentConfiguration(
        @Property(
                defaultValue = "null",
                documentation =
                        "Allow automatic alternative-model routing only with a nonempty effective exact includes list; empty grants no additional autonomy. Null inherits yca.subagents.allowModelOverride (false by default). The effective same-provider default remains authorized when false; discovery is not authorization.")
        Boolean allowModelOverride,

        @Property(
                documentation =
                        "Exact $provider/model alternatives authorized only when overrides are enabled and this list is nonempty. Null inherits global includes; empty grants no additional autonomy. Does not restrict discovery of configured models on the session provider or an explicitly filtered provider.")
        List<String> includes,

        @Property(
                documentation =
                        "Exact routed $provider/model hard denies, additive with global, source-provider and destination-provider excludes for discovery and spawning. Applies even to inherited parents and defaults; neither user approval nor exact-route session grants can override these.")
        List<String> excludes,

        @Property(
                documentation =
                        "Same-provider default sub-agent route, authorized even when overrides are disabled, subject to hard denies and administrative restrictions. Null inherits the global defaultModel; blank inherits the parent route. A different default removes the parent's automatic exemption. Bare names are pinned to the session provider; profiles/defaults cannot implicitly cross providers or initiate approvals. Cross-provider selection requires explicit $other/model routing and authorization.")
        String defaultModel,

        @Property(
                defaultValue = "1",
                documentation =
                        "Maximum sub-agent depth on this provider: 1 allows only direct children of the main agent. "
                                + "Nested agents stay on this provider and inherit their parent's permission and tool ceilings.")
        Integer maxDepth) {
    public ProviderSubagentConfiguration {
        maxDepth = maxDepth == null ? 1 : maxDepth;
        if (maxDepth < 1) {
            throw new IllegalArgumentException("Provider subagents.maxDepth must be at least 1.");
        }
        includes = includes == null ? null : List.copyOf(includes);
        excludes = excludes == null ? null : List.copyOf(excludes);
    }
}
