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

import io.yupiik.ia.agent.github.configuration.ReviewPresentationConfiguration;
import io.yupiik.ia.agent.github.execution.BotTemplates;
import io.yupiik.ia.agent.github.model.AgentResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Pure presentation; agent Markdown is template data, never template source. */
public final class ReviewRenderer {
    private final BotTemplates templates;

    public ReviewRenderer(final ReviewPresentationConfiguration configuration) {
        templates = new BotTemplates(Map.of(
                "report", configuration.reportTemplate(),
                "finding", configuration.findingTemplate()));
    }

    /** Render findings in the report only when they have not been posted as native comments. */
    public String render(final AgentResult result, final boolean nativeCommentsPosted) {
        final var review = result.review();
        final var summary = review != null && review.body() != null ? review.body() : result.summary();
        final var proposed =
                review == null || review.comments() == null ? List.<AgentResult.Finding>of() : review.comments();
        final var findings = new ArrayList<Map<String, Object>>(proposed.size());
        for (int i = 0; i < proposed.size(); i++) {
            findings.add(findingModel(proposed.get(i), i + 1));
        }
        return templates.render(
                "report",
                Map.of(
                        "summary",
                        summary == null ? "" : summary,
                        "findings",
                        findings,
                        "findingCount",
                        findings.size(),
                        "nativeCommentsPosted",
                        nativeCommentsPosted,
                        "tests",
                        result.tests() == null ? List.of() : result.tests()));
    }

    public String renderFinding(final AgentResult.Finding finding, final int number) {
        return templates.render("finding", findingModel(finding, number));
    }

    private Map<String, Object> findingModel(final AgentResult.Finding finding, final int number) {
        return Map.of(
                "number", number,
                "path", finding.path() == null ? "" : finding.path(),
                "side", finding.side() == null ? "" : finding.side(),
                "line", finding.line() == null ? "" : finding.line().toPlainString(),
                "body", finding.body() == null ? "" : finding.body());
    }
}
