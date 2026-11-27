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
package io.yupiik.ia.agent.github.service;

import io.yupiik.ia.agent.github.model.AgentResult;

/** Deterministic host layout; supplied Markdown is data, not a section-parsing contract. */
public final class PullRequestRenderer {
    private PullRequestRenderer() {}

    public static String render(final AgentResult result, final String marker) {
        final var body = new StringBuilder("## Summary\n\n");
        if (result.summary() == null || result.summary().isBlank()) {
            body.append("No separate summary was supplied; see the supplied description below.\n");
        } else {
            body.append("Agent-reported summary (not independently verified by the host):\n\n")
                    .append(result.summary())
                    .append('\n');
        }
        body.append("\n## Changes\n\n")
                .append("### Supplied description\n\n")
                .append(
                        "The following Markdown was supplied by the agent and may include user-provided text; it is not host-verified evidence.\n\n")
                .append(result.strictSolve().body())
                .append("\n\n## Validation / test evidence\n\n")
                .append("The host has not independently run or verified the reported tests.\n\n");
        if (result.tests() == null || result.tests().isEmpty()) {
            body.append("No separate test evidence was supplied.\n");
        } else {
            body.append("Agent-reported evidence:\n");
            for (final var test : result.tests()) {
                body.append('\n').append(test).append('\n');
            }
        }
        body.append("\n## Risks / follow-up\n\n")
                .append(
                        "Review the supplied description for reported risks, limitations and follow-up. The host has not independently assessed these or inferred their absence.\n\n")
                .append(marker);
        if (body.length() > 65000) {
            throw new IllegalArgumentException("Rendered pull request body is too large");
        }
        return body.toString();
    }
}
