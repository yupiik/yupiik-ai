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

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.github.configuration.ReviewPresentationConfiguration;
import io.yupiik.ia.agent.github.model.AgentResult;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewRendererTest {
    @Nested
    @FusionSupport(modules = GithubConfigurationTestModule.class)
    class Defaults {
        @Fusion
        private ReviewPresentationConfiguration presentation;

        @Test
        void fallbackIncludesEveryFindingButNativeReportDoesNotDuplicateInlineBodies() {
            final var renderer = new ReviewRenderer(presentation);
            final var findings = List.of(
                    new AgentResult.Finding("src/A.java", "LEFT", BigDecimal.TEN, "First **defect** & <T> {{summary}}"),
                    new AgentResult.Finding("src/B.java", "RIGHT", BigDecimal.ONE, "Second defect: `a < b`"));
            final var tests = List.of("unit tests failed", "integration tests not run");
            final var result = result("Review summary", findings, tests);
            final var fallback = renderer.render(result, false);
            assertTrue(fallback.contains("### 1. `src/A.java:10` (LEFT)"));
            assertTrue(fallback.contains("### 2. `src/B.java:1` (RIGHT)"));
            assertTrue(fallback.contains("First **defect** & <T> {{summary}}\n\n### 2."), fallback);
            assertTrue(fallback.contains("\n- unit tests failed\n- integration tests not run"), fallback);
            final var nativeReport = renderer.render(result, true);
            assertTrue(nativeReport.contains("2 finding(s) posted as inline review comments."));
            for (int i = 0; i < findings.size(); i++) {
                final var finding = findings.get(i);
                assertEquals(1, occurrences(fallback, finding.body()));
                assertFalse(nativeReport.contains(finding.body()));
                assertEquals(1, occurrences(renderer.renderFinding(finding, i + 1), finding.body()));
            }
            for (final var test : tests) {
                assertEquals(1, occurrences(fallback, test));
                assertEquals(1, occurrences(nativeReport, test));
            }
            assertFalse(fallback.contains("passed"));
            assertFalse(fallback.contains("&amp;"));
            assertFalse(fallback.contains("&lt;"));
        }

        @Test
        void absentReviewAndEmptyEvidenceNeverInventSuccessOrFindings() {
            final var renderer = new ReviewRenderer(presentation);
            final var result = new AgentResult(null, null, null, null, null, null, null, List.of(), null, null);
            for (final var nativeCommentsPosted : List.of(false, true)) {
                final var report = renderer.render(result, nativeCommentsPosted);
                assertTrue(report.contains("No review summary was provided."));
                assertTrue(report.contains("No inline findings were reported."));
                assertTrue(report.contains("No test evidence was reported."));
                assertFalse(report.contains("posted as inline"));
                assertFalse(report.contains("passed"));
            }
        }
    }

    @Nested
    @FusionSupport(modules = CustomModule.class)
    class Custom {
        @Fusion
        private ReviewPresentationConfiguration presentation;

        @Test
        void customReportReceivesExplicitFindingModelAndNeverReinterpretsData() {
            final var renderer = new ReviewRenderer(presentation);
            final var literal = "{{summary}} & <code> **literal**";
            final var finding = new AgentResult.Finding("{{path}}.java", "RIGHT", BigDecimal.ONE, literal);
            final var result = result(literal, List.of(finding), List.of("{{tests}} not run"));
            assertEquals(
                    literal + "\n1 {{path}}.java:1 RIGHT " + literal + "\n1 false {{tests}} not run",
                    renderer.render(result, false));
            assertEquals("1 " + literal, renderer.renderFinding(finding, 1));
        }
    }

    public static class CustomModule extends GithubConfigurationTestModule {
        public CustomModule() {
            super(Map.of(
                    "github.review.presentation.reportTemplate",
                    "{{{summary}}}\n{{#each findings}}{{number}} {{{path}}}:{{line}} {{side}} {{{body}}}{{/each}}\n"
                            + "{{findingCount}} {{nativeCommentsPosted}} {{#each tests}}{{{this}}}{{/each}}",
                    "github.review.presentation.findingTemplate",
                    "{{number}} {{{body}}}"));
        }
    }

    private AgentResult result(
            final String summary, final List<AgentResult.Finding> findings, final List<String> tests) {
        return new AgentResult(
                null,
                null,
                "unused fallback",
                null,
                null,
                new AgentResult.Review("head", summary, findings),
                null,
                tests,
                null,
                null);
    }

    private int occurrences(final String value, final String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }
}
