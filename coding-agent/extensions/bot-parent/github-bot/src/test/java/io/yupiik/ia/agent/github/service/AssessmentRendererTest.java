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

import io.yupiik.ia.agent.github.model.AgentResult.Assessment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class AssessmentRendererTest {
    private final AssessmentRenderer renderer = new AssessmentRenderer();

    @Test
    void rendersAdvisorySectionsAndKeepsMarkdownAsLiteralData() {
        assertEquals(
                """
                ## Summary

                Recommendation: **proceed_with_conditions**

                Useful **proposal** {{> main}}

                Advisory assessment only; no code changes, closure or answer selection.
                Based on bounded supplied context; omissions and source availability may limit this assessment.

                ## Concerns

                - Risk <one>
                  Evidence

                ## Suggested approach

                - Start small

                ## Acceptance criteria

                - Observable outcome

                ## Open questions

                - Who owns it?
                """,
                renderer.render(new Assessment(
                        "proceed_with_conditions",
                        "Useful **proposal** {{> main}}",
                        List.of("Risk <one>\nEvidence"),
                        List.of("Start small"),
                        List.of("Observable outcome"),
                        List.of("Who owns it?"))));
    }

    @Test
    void acceptsEveryRecommendationAndOmittedLists() {
        for (final var recommendation :
                List.of("proceed", "proceed_with_conditions", "reconsider", "clarification_needed")) {
            final var rendered =
                    renderer.render(new Assessment(recommendation, "Analysis", null, List.of(), null, null));
            assertTrue(rendered.contains("**" + recommendation + "**"));
            assertFalse(rendered.contains("## Concerns"));
            assertFalse(rendered.contains("## Tests"));
        }
    }

    @Test
    void rejectsMissingUnknownAndUnboundedContentWithoutEchoingIt() {
        assertThrows(IllegalArgumentException.class, () -> renderer.render(null));
        for (final var recommendation : Arrays.asList(null, "", "PROCEED", "proceed ", "secret {{exec}}")) {
            final var error = assertThrows(
                    IllegalArgumentException.class,
                    () -> renderer.render(new Assessment(recommendation, "Analysis", null, null, null, null)));
            assertEquals("Invalid assessment recommendation", error.getMessage());
        }
        for (final var text : Arrays.asList(null, "", "  \n", "x".repeat(16_001))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> renderer.render(new Assessment("proceed", text, null, null, null, null)));
        }
        for (final var entries : List.of(
                Arrays.<String>asList((String) null),
                List.of(" "),
                List.of("x".repeat(2_001)),
                Collections.nCopies(33, "entry"))) {
            // Exercise each optional list, not only the first one.
            for (int i = 0; i < 4; i++) {
                final var fields = new ArrayList<List<String>>(Collections.nCopies(4, null));
                fields.set(i, entries);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> renderer.render(new Assessment(
                                "proceed", "Analysis", fields.get(0), fields.get(1), fields.get(2), fields.get(3))));
            }
        }
    }

    @Test
    void acceptsIndividualBoundsButRejectsOversizedCombinedReport() {
        assertDoesNotThrow(() -> renderer.render(new Assessment(
                "proceed",
                "x".repeat(16_000),
                Collections.nCopies(32, "entry"),
                List.of("x".repeat(2_000)),
                null,
                null)));
        assertThrows(
                IllegalArgumentException.class,
                () -> renderer.render(new Assessment(
                        "proceed", "Analysis", Collections.nCopies(32, "x".repeat(2_000)), null, null, null)));
        assertThrows(
                IllegalArgumentException.class,
                () -> renderer.render(new Assessment(
                        "proceed", "Analysis", Collections.nCopies(32, "\nx".repeat(1_000)), null, null, null)));
    }
}
