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
package io.yupiik.ia.agent.github.model;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.github.support.BotJsonMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentResultAssessmentTest {
    @Test
    void decodesTypedAssessmentSeparatelyFromDiagnosticEvidenceAndRoundTrips() {
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            final var result = json.fromString(AgentResult.class, """
                    {"schemaVersion":1,"taskId":"task-1","summary":"Proposal assessment","tests":[],
                     "assessment":{"recommendation":"reconsider","assessment":"Scope is too wide",
                       "concerns":["Risk"],"suggestedApproach":["Split it"],
                       "acceptanceCriteria":["Small scope"],"openQuestions":["Owner?"]},
                     "extra":{"diagnostic":true}}
                    """);
            assertNotNull(result.assessment());
            assertEquals("reconsider", result.assessment().recommendation());
            assertEquals("Scope is too wide", result.assessment().assessment());
            assertEquals(List.of("Risk"), result.assessment().concerns());
            assertEquals(List.of("Split it"), result.assessment().suggestedApproach());
            assertEquals(List.of("Small scope"), result.assessment().acceptanceCriteria());
            assertEquals(List.of("Owner?"), result.assessment().openQuestions());
            assertEquals(Map.of("extra", Map.of("diagnostic", true)), result.evidence());
            assertEquals(result, json.fromString(AgentResult.class, json.toString(result)));
        }
    }

    @Test
    void oldConstructorAndOldJsonKeepAssessmentOptional() {
        assertNull(new AgentResult(null, "task-1", "Summary", null, null, null, null, List.of(), null, Map.of())
                .assessment());
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            assertNull(json.fromString(AgentResult.class, "{\"summary\":\"Old result\"}")
                    .assessment());
        }
    }

    @Test
    void snapshotsAllAssessmentLists() {
        final var source = new ArrayList<>(List.of("original"));
        final var assessment = new AgentResult.Assessment("proceed", "Analysis", source, source, source, source);
        source.set(0, "changed");
        for (final var entries : List.of(
                assessment.concerns(),
                assessment.suggestedApproach(),
                assessment.acceptanceCriteria(),
                assessment.openQuestions())) {
            assertEquals(List.of("original"), entries);
            assertThrows(UnsupportedOperationException.class, () -> entries.add("changed"));
        }
    }
}
