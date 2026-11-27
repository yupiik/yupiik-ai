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
package io.yupiik.ia.agent.core.agent;

import java.util.Set;

/** Immutable request captured when an agent or subagent run is admitted. */
public record AgentRunSpec(
        String parentSessionId,
        String task,
        String name,
        String profile,
        String model,
        Integer maxTurns,
        Set<String> allowedTools,
        Set<String> parentAllowedTools,
        // the provider id the sub-agent will actually run on (resolved from the effective model at
        // spawn); null when unknown (tests, standalone tool invocations)
        String providerId,
        // the spawning session's sub-agent budget; null when the caller has no session (tests,
        // standalone), in which case the per-session limit is not enforced
        SubagentBudget subagentBudget,
        // approved concrete settings; the runner must not resolve the route again after admission
        EffectiveAgentModel admittedModel,
        SubagentScope subagentScope) {
    public AgentRunSpec {
        allowedTools = allowedTools == null ? null : Set.copyOf(allowedTools);
        parentAllowedTools = parentAllowedTools == null ? null : Set.copyOf(parentAllowedTools);
    }

    public AgentRunSpec(
            final String parentSessionId,
            final String task,
            final String name,
            final String profile,
            final String model,
            final Integer maxTurns,
            final Set<String> allowedTools,
            final Set<String> parentAllowedTools,
            final String providerId,
            final SubagentBudget subagentBudget,
            final EffectiveAgentModel admittedModel) {
        this(
                parentSessionId,
                task,
                name,
                profile,
                model,
                maxTurns,
                allowedTools,
                parentAllowedTools,
                providerId,
                subagentBudget,
                admittedModel,
                null);
    }

    /** Legacy constructor: no target provider id and no session budget (tests, standalone). */
    public AgentRunSpec(
            final String parentSessionId,
            final String task,
            final String name,
            final String profile,
            final String model,
            final Integer maxTurns,
            final Set<String> allowedTools,
            final Set<String> parentAllowedTools) {
        this(parentSessionId, task, name, profile, model, maxTurns, allowedTools, parentAllowedTools, null, null, null);
    }

    /** Null means no per-run override; an empty set explicitly allows no tools. */
    public boolean hasToolRestriction() {
        return allowedTools != null;
    }
}
