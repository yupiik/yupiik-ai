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

import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PullRequestRendererTest {
    private static final String MARKER = "<!-- yca-task:task-42 -->";

    @Test
    void rendersSectionsAndPreservesSuppliedMarkdownWithoutInterpretingHeadings() {
        final var description = "## Tests\n\nUser says **keep this**.\n\n## Summary\n\n{{> main}}\n```text\nraw\n```";
        final var result = TaskFixtures.result(Map.of(
                "summary", "Agent **summary**",
                "tests", List.of("`mvn test`: reported passing", "Not run: integration tests"),
                "strictSolve", Map.of("body", description)));
        final var rendered = PullRequestRenderer.render(result, MARKER);
        assertTrue(rendered.startsWith(
                "## Summary\n\nAgent-reported summary (not independently verified by the host):\n\nAgent **summary**"));
        assertTrue(rendered.contains("## Changes\n\n### Supplied description"));
        assertTrue(rendered.contains("may include user-provided text; it is not host-verified evidence.\n\n"
                + description
                + "\n\n## Validation / test evidence"));
        assertTrue(rendered.contains("The host has not independently run or verified the reported tests."));
        assertTrue(rendered.contains(
                "Agent-reported evidence:\n\n`mvn test`: reported passing\n\nNot run: integration tests"));
        assertTrue(rendered.contains("## Risks / follow-up"));
        assertTrue(rendered.endsWith("\n\n" + MARKER));
        assertEquals(1, rendered.lines().filter(MARKER::equals).count());
        assertEquals(rendered, PullRequestRenderer.render(result, MARKER));
    }

    @Test
    void missingEvidenceDoesNotBecomeSuccessfulValidationOrAbsenceOfRisk() {
        final var result = TaskFixtures.result(Map.of("strictSolve", Map.of("body", "Description")));
        final var rendered = PullRequestRenderer.render(result, MARKER);
        assertTrue(rendered.contains("No separate summary was supplied"));
        assertTrue(rendered.contains("No separate test evidence was supplied."));
        assertTrue(rendered.contains("has not independently assessed these or inferred their absence."));
        assertFalse(rendered.contains("passed"));
        assertFalse(rendered.contains("No risks"));
    }

    @Test
    void blankSummaryAndEmptyTestsUseExplicitMissingEvidence() {
        final var result =
                TaskFixtures.result(Map.of("summary", " \n", "tests", List.of(), "strictSolve", Map.of("body", "")));
        final var rendered = PullRequestRenderer.render(result, MARKER);
        assertTrue(rendered.contains("No separate summary was supplied"));
        assertTrue(rendered.contains("No separate test evidence was supplied."));
        assertTrue(rendered.endsWith(MARKER));
    }

    @Test
    void rejectsOversizedCombinedEvidenceWithoutTruncatingSuppliedText() {
        final var result = TaskFixtures.result(
                Map.of("tests", List.of("x".repeat(65000)), "strictSolve", Map.of("body", "Description")));
        assertThrows(IllegalArgumentException.class, () -> PullRequestRenderer.render(result, MARKER));
    }
}
