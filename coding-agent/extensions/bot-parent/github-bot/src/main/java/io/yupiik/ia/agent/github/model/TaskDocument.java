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
import java.util.List;
import java.util.Map;

/** A task at a persistence or command-output boundary, including durable recovery extensions. */
@JsonModel
public record TaskDocument(
        @Property(documentation = "Durable task identifier.")
        String id,

        @Property(documentation = "Upstream owner/repository.")
        String repository,

        @Property(documentation = "Issue or pull request number.")
        Long number,

        @Property(documentation = "Task kind: solve, review or help.")
        TaskKind kind,

        @Property(documentation = "Host-resolved review target: pull_request, issue or discussion.")
        String reviewTarget,

        @Property(documentation = "Untrusted request text.") String request,
        @Property(documentation = "Creation instant.") String createdAt,

        @Property(documentation = "Canonical invocation reference.")
        String reference,

        @Property(documentation = "Queue lifecycle state.") TaskState state,

        @Property(documentation = "Number of execution attempts.")
        Integer attempts,

        @Property(documentation = "Immutable requesting GitHub user identity in decimal string form.")
        String actorId,

        @Property(documentation = "Polling batch correlation identifier.")
        String batchId,

        @Property(documentation = "Invocation source, including discussion for GraphQL mentions.")
        String sourceType,

        @Property(documentation = "GraphQL discussion node identity.")
        String discussionId,

        @Property(documentation = "GraphQL invocation comment identity.")
        String commentId,

        @Property(documentation = "Validated GraphQL publication thread identity.")
        String replyToId,

        @Property(documentation = "Untrusted source discussion title.")
        String discussionTitle,

        @Property(documentation = "Untrusted source discussion Markdown.")
        String discussionBody,

        @Property(documentation = "Untrusted discussion invocation context.")
        String discussionContext,

        @Property(documentation = "Retained private attempt directory for stop, recovery and cleanup.")
        String attemptDirectory,

        @Property(documentation = "Selected executor backend pinned for recovery.")
        String executionBackend,

        @Property(documentation = "Trusted execution settings fingerprint pinned for recovery.")
        String executionFingerprint,

        @Property(documentation = "Single-use executor identity.")
        String runtimeId,

        @Property(documentation = "Execution start instant.")
        String startedAt,

        @Property(documentation = "Terminal transition instant.")
        String completedAt,

        @Property(documentation = "Classified host diagnostic, never secret-bearing executor output.")
        String lastError,

        @Property(documentation = "Durable final-comment marker written before the single publication request.")
        String publicationIntent,

        @Property(documentation = "Whether the staged final publication reports a failure.")
        Boolean publicationFailure,

        @Property(documentation = "Whether an operator notification was attempted.")
        Boolean failureNotified,

        @Property(documentation = "Durable native review publication marker.")
        String reviewIntent,

        @Property(documentation = "Pull head SHA pinned before native review publication.")
        String reviewIntentSha,

        @Property(documentation = "Durable discussion publication marker.")
        String discussionPublishIntent,

        @Property(documentation = "Verified discussion publication reference.")
        String discussionPublishedUrl,

        @Property(documentation = "Host-selected upstream default branch.")
        String strictSolveBaseBranch,

        @Property(documentation = "Host-selected upstream base commit SHA.")
        String strictSolveBaseSha,

        @Property(documentation = "Fork owner pinned for credential-free solve publication.")
        String strictSolveForkOwner,

        @Property(documentation = "Durable commentUrl recovery value.")
        String commentUrl,

        @Property(documentation = "Durable reviewUrl recovery value.")
        String reviewUrl,

        @Property(documentation = "Durable prUrl recovery value.")
        String prUrl,

        @Property(documentation = "Durable forkProvisionIntent recovery value.")
        ForkIntent forkProvisionIntent,

        @Property(documentation = "Durable strictSolvePublication recovery value.")
        SolvePublication strictSolvePublication,

        @Property(documentation = "Retained attempt history; legacy entries are opaque.")
        List<Run> runs,

        @Property(documentation = "Staged agent result; malformed legacy values are retained for worker recovery.")
        AgentResult result,

        @Property(
                documentation =
                        "Safe failure metadata; raw messages and logs require explicit private diagnostics inspection.")
        FailureDetail lastFailure,

        @JsonOthers @Property(documentation = "Unknown task extensions preserved during recovery.")
        Map<String, Object> recovery) {

    @JsonModel
    public record FailureDetail(
            String stage,
            String exceptionClass,
            String message,
            String timestamp,
            Integer attempt,
            Integer exitCode,
            String diagnosticFile,
            boolean diagnosticsRetained) {}

    @JsonModel
    public record Run(@JsonOthers Map<String, Object> extensions) {}

    @JsonModel
    public record ForkIntent(
            @Property(documentation = "Pinned upstream recovery value.")
            String upstream,

            @Property(documentation = "Pinned fork recovery value.")
            String fork,

            @JsonOthers @Property(documentation = "Dynamic object stages and unknown recovery attributes.")
            Map<String, Object> extensions) {}

    @JsonModel
    public record SolvePublication(
            @Property(documentation = "Pinned forkOwner recovery value.")
            String forkOwner,

            @Property(documentation = "Pinned upstream recovery value.")
            String upstream,

            @Property(documentation = "Pinned baseSha recovery value.")
            String baseSha,

            @Property(documentation = "Pinned baseBranch recovery value.")
            String baseBranch,

            @Property(documentation = "Pinned branch recovery value.")
            String branch,

            @Property(documentation = "Pinned fingerprint recovery value.")
            String fingerprint,

            @Property(documentation = "Pinned author recovery value.")
            String author,

            @Property(documentation = "Pinned treeSha recovery value.")
            String treeSha,

            @Property(documentation = "Pinned commitSha recovery value.")
            String commitSha,

            @Property(documentation = "Pinned prUrl recovery value.")
            String prUrl,

            @Property(documentation = "Pinned treeIntent recovery value.")
            Boolean treeIntent,

            @Property(documentation = "Pinned commitIntent recovery value.")
            Boolean commitIntent,

            @Property(documentation = "Pinned refIntent recovery value.")
            Boolean refIntent,

            @Property(documentation = "Pinned refDone recovery value.")
            Boolean refDone,

            @Property(documentation = "Pinned prIntent recovery value.")
            Boolean prIntent,

            @Property(documentation = "Blob write progress indexed by stage identifier.")
            Map<String, PublicationObject> blobs,

            @JsonOthers @Property(documentation = "Unknown recovery attributes.")
            Map<String, Object> extensions) {}

    @JsonModel
    public record PublicationObject(Boolean intent, String sha) {}

    @JsonModel
    public record TaskList(
            @Property(documentation = "Tasks matching the requested filters.")
            List<TaskDocument> tasks) {}
}
