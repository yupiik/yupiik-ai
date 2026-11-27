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
import java.util.List;
import java.util.Set;

/** Pure bounded advisory presentation; task/result compatibility and publication remain host-owned. */
public final class AssessmentRenderer {
    private static final Set<String> RECOMMENDATIONS =
            Set.of("proceed", "proceed_with_conditions", "reconsider", "clarification_needed");

    /**
     * Render an assessment, rejecting malformed or oversized fields with a non-agent-controlled diagnostic.
     * Limits are Java string lengths: 16,000 for assessment text, 32 entries per list, 2,000 per entry,
     * and 28,000 for the complete rendered report. Null lists are treated as empty.
     */
    public String render(final AgentResult.Assessment assessment) {
        if (assessment == null) {
            throw new IllegalArgumentException("Missing assessment");
        }
        if (assessment.recommendation() == null || !RECOMMENDATIONS.contains(assessment.recommendation())) {
            throw new IllegalArgumentException("Invalid assessment recommendation");
        }
        validateText(assessment.assessment(), 16_000);
        final var report = new StringBuilder("## Summary\n\nRecommendation: **")
                .append(assessment.recommendation())
                .append("**\n\n")
                .append(assessment.assessment())
                .append("\n\nAdvisory assessment only; no code changes, closure or answer selection.\n")
                .append(
                        "Based on bounded supplied context; omissions and source availability may limit this assessment.\n");
        section(report, "Concerns", assessment.concerns());
        section(report, "Suggested approach", assessment.suggestedApproach());
        section(report, "Acceptance criteria", assessment.acceptanceCriteria());
        section(report, "Open questions", assessment.openQuestions());
        if (report.length() > 28_000) {
            throw new IllegalArgumentException("Assessment report exceeds size limit");
        }
        return report.toString();
    }

    private void section(final StringBuilder report, final String heading, final List<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        if (entries.size() > 32) {
            throw new IllegalArgumentException("Assessment list exceeds entry limit");
        }
        report.append("\n## ").append(heading).append("\n\n");
        for (final var entry : entries) {
            validateText(entry, 2_000);
            report.append("- ").append(entry.replace("\n", "\n  ")).append('\n');
        }
    }

    private void validateText(final String text, final int limit) {
        if (text == null || text.isBlank() || text.length() > limit) {
            throw new IllegalArgumentException("Invalid assessment text or size limit exceeded");
        }
    }
}
