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

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonOthers;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Agent-produced result; semantic and head/diff validation remains host-owned. */
@JsonModel
public record AgentResult(
        @Property(documentation = "Structured result schema version.")
        BigDecimal schemaVersion,

        @Property(documentation = "Task correlation identifier.")
        String taskId,

        @Property(documentation = "Agent outcome summary.") String summary,

        @Property(documentation = "Failure explanation, when unsuccessful.")
        String failure,

        @Property(documentation = "Proposed pull request URL, independently verified by the host.")
        String prUrl,

        @Property(documentation = "Proposed native review pinned to a pull head.")
        Review review,

        @Property(documentation = "Credential-free solve changes pinned to a host-selected base.")
        StrictSolve strictSolve,

        @Property(documentation = "Agent-reported test evidence, never trusted as host validation.")
        List<String> tests,

        @Property(documentation = "Previously published native review identifier.")
        BigDecimal reviewId,

        @Property(documentation = "Advisory issue or Discussion assessment, validated before publication.")
        Assessment assessment,

        @JsonOthers @Property(documentation = "Unknown agent evidence preserved for diagnostics.")
        Map<String, Object> evidence) {

    public AgentResult {
        evidence = snapshot(evidence);
        tests = tests == null ? null : List.copyOf(tests);
    }

    /** Source compatibility for callers which do not produce advisory assessments. */
    public AgentResult(
            final BigDecimal schemaVersion,
            final String taskId,
            final String summary,
            final String failure,
            final String prUrl,
            final Review review,
            final StrictSolve strictSolve,
            final List<String> tests,
            final BigDecimal reviewId,
            final Map<String, Object> evidence) {
        this(schemaVersion, taskId, summary, failure, prUrl, review, strictSolve, tests, reviewId, null, evidence);
    }

    @JsonModel
    public record Assessment(
            @Property(
                    documentation =
                            "Recommendation: proceed, proceed_with_conditions, reconsider or clarification_needed.")
            String recommendation,

            @Property(documentation = "Advisory assessment Markdown, required and bounded by the renderer.")
            String assessment,

            @Property(documentation = "Optional bounded concerns.")
            List<String> concerns,

            @Property(documentation = "Optional bounded suggested approach steps.")
            List<String> suggestedApproach,

            @Property(documentation = "Optional bounded acceptance criteria.")
            List<String> acceptanceCriteria,

            @Property(documentation = "Optional bounded open questions.")
            List<String> openQuestions) {
        public Assessment {
            concerns = assessmentList(concerns);
            suggestedApproach = assessmentList(suggestedApproach);
            acceptanceCriteria = assessmentList(acceptanceCriteria);
            openQuestions = assessmentList(openQuestions);
        }

        private static List<String> assessmentList(final List<String> values) {
            // Keep malformed entries for renderer validation rather than failing during JSON decoding.
            return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
        }
    }

    private static Map<String, Object> snapshot(final Map<String, Object> values) {
        if (values == null) {
            return null;
        }
        final var copy = values.entrySet().stream()
                .collect(
                        LinkedHashMap<String, Object>::new,
                        (collected, entry) -> collected.put(entry.getKey(), snapshotValue(entry.getValue())),
                        Map::putAll);
        return Collections.unmodifiableMap(copy);
    }

    private static Object snapshotValue(final Object value) {
        if (value instanceof Map<?, ?> values) {
            final var copy = values.entrySet().stream()
                    .collect(
                            LinkedHashMap<String, Object>::new,
                            (collected, entry) ->
                                    collected.put((String) entry.getKey(), snapshotValue(entry.getValue())),
                            Map::putAll);
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> values) {
            return values.stream().map(AgentResult::snapshotValue).toList();
        }
        return value;
    }

    @JsonModel
    public record Review(
            @Property(documentation = "Pull request head SHA used during analysis.")
            String headSha,

            @Property(documentation = "Review summary Markdown.")
            String body,

            @Property(documentation = "Proposed inline findings, each validated against the diff.")
            List<Finding> comments) {
        public Review {
            comments = comments == null ? null : List.copyOf(comments);
        }
    }

    @JsonModel
    public record Finding(
            @Property(documentation = "Exact changed file path.")
            String path,

            @Property(documentation = "Diff side: LEFT or RIGHT.")
            String side,

            @Property(documentation = "Proposed integral diff line, validated before publication.")
            BigDecimal line,

            @Property(documentation = "Finding Markdown body.")
            String body) {}

    @JsonModel
    public record StrictSolve(
            @Property(documentation = "Host-selected upstream base SHA.")
            String baseSha,

            @Property(documentation = "Pull request title.") String title,

            @Property(documentation = "Pull request Markdown body.")
            String body,

            @Property(documentation = "Proposed file changes, bounded and validated by the host.")
            List<Change> changes) {
        public StrictSolve {
            changes = changes == null ? null : List.copyOf(changes);
        }
    }

    @JsonModel
    public record Change(
            @Property(documentation = "Repository-relative regular file path.")
            String path,

            @Property(documentation = "Canonical base64 contents, empty for deletion.")
            String content,

            @Property(documentation = "Explicit deletion flag; absence is invalid.")
            Boolean delete,

            @JsonOthers
            @Property(documentation = "Unknown change attributes are retained so validation can reject them.")
            Map<String, Object> unknown) {
        public Change {
            unknown = snapshot(unknown);
        }
    }
}
