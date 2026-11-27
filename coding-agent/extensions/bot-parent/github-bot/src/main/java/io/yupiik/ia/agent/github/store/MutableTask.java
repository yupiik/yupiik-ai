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
package io.yupiik.ia.agent.github.store;

import io.yupiik.ia.agent.github.model.AgentResult;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.model.TaskDocument.ForkIntent;
import io.yupiik.ia.agent.github.model.TaskDocument.Run;
import io.yupiik.ia.agent.github.model.TaskDocument.SolvePublication;
import io.yupiik.ia.agent.github.model.TaskKind;
import io.yupiik.ia.agent.github.model.TaskState;
import java.util.List;
import java.util.Map;

/** Mutable transaction-owned task, with explicit types for each durable field. */
public final class MutableTask {
    private String id;
    private String repository;
    private Long number;
    private TaskKind kind;
    private String reviewTarget;
    private String request;
    private String createdAt;
    private String reference;
    private TaskState state;
    private Integer attempts;
    private String actorId;
    private String batchId;
    private String sourceType;
    private String discussionId;
    private String commentId;
    private String replyToId;
    private String discussionTitle;
    private String discussionBody;
    private String discussionContext;
    private String attemptDirectory;
    private String executionBackend;
    private String executionFingerprint;
    private String runtimeId;
    private String startedAt;
    private String completedAt;
    private String lastError;
    private String publicationIntent;
    private Boolean publicationFailure;
    private Boolean failureNotified;
    private String reviewIntent;
    private String reviewIntentSha;
    private String discussionPublishIntent;
    private String discussionPublishedUrl;
    private String strictSolveBaseBranch;
    private String strictSolveBaseSha;
    private String strictSolveForkOwner;
    private String commentUrl;
    private String reviewUrl;
    private String prUrl;
    private ForkIntent forkProvisionIntent;
    private SolvePublication strictSolvePublication;
    private List<Run> runs;
    private AgentResult result;
    private TaskDocument.FailureDetail lastFailure;
    private Map<String, Object> recovery;

    public MutableTask(final TaskDocument document) {
        this.id = document.id();
        this.repository = document.repository();
        this.number = document.number();
        this.kind = document.kind();
        this.reviewTarget = document.reviewTarget();
        this.request = document.request();
        this.createdAt = document.createdAt();
        this.reference = document.reference();
        this.state = document.state();
        this.attempts = document.attempts();
        this.actorId = document.actorId();
        this.batchId = document.batchId();
        this.sourceType = document.sourceType();
        this.discussionId = document.discussionId();
        this.commentId = document.commentId();
        this.replyToId = document.replyToId();
        this.discussionTitle = document.discussionTitle();
        this.discussionBody = document.discussionBody();
        this.discussionContext = document.discussionContext();
        this.attemptDirectory = document.attemptDirectory();
        this.executionBackend = document.executionBackend();
        this.executionFingerprint = document.executionFingerprint();
        this.runtimeId = document.runtimeId();
        this.startedAt = document.startedAt();
        this.completedAt = document.completedAt();
        this.lastError = document.lastError();
        this.publicationIntent = document.publicationIntent();
        this.publicationFailure = document.publicationFailure();
        this.failureNotified = document.failureNotified();
        this.reviewIntent = document.reviewIntent();
        this.reviewIntentSha = document.reviewIntentSha();
        this.discussionPublishIntent = document.discussionPublishIntent();
        this.discussionPublishedUrl = document.discussionPublishedUrl();
        this.strictSolveBaseBranch = document.strictSolveBaseBranch();
        this.strictSolveBaseSha = document.strictSolveBaseSha();
        this.strictSolveForkOwner = document.strictSolveForkOwner();
        this.commentUrl = document.commentUrl();
        this.reviewUrl = document.reviewUrl();
        this.prUrl = document.prUrl();
        this.forkProvisionIntent = document.forkProvisionIntent();
        this.strictSolvePublication = document.strictSolvePublication();
        this.runs = document.runs();
        this.result = document.result();
        this.lastFailure = document.lastFailure();
        this.recovery = document.recovery();
    }

    public static MutableTask create() {
        return new MutableTask(new TaskDocument(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null));
    }

    public TaskDocument document() {
        return new TaskDocument(
                id,
                repository,
                number,
                kind,
                reviewTarget,
                request,
                createdAt,
                reference,
                state,
                attempts,
                actorId,
                batchId,
                sourceType,
                discussionId,
                commentId,
                replyToId,
                discussionTitle,
                discussionBody,
                discussionContext,
                attemptDirectory,
                executionBackend,
                executionFingerprint,
                runtimeId,
                startedAt,
                completedAt,
                lastError,
                publicationIntent,
                publicationFailure,
                failureNotified,
                reviewIntent,
                reviewIntentSha,
                discussionPublishIntent,
                discussionPublishedUrl,
                strictSolveBaseBranch,
                strictSolveBaseSha,
                strictSolveForkOwner,
                commentUrl,
                reviewUrl,
                prUrl,
                forkProvisionIntent,
                strictSolvePublication,
                runs,
                result,
                lastFailure,
                recovery);
    }

    public TaskDocument.FailureDetail lastFailure() {
        return lastFailure;
    }

    public void lastFailure(final TaskDocument.FailureDetail value) {
        lastFailure = value;
    }

    public String id() {
        return id;
    }

    public void id(final String value) {
        id = value;
    }

    public String repository() {
        return repository;
    }

    public void repository(final String value) {
        repository = value;
    }

    public Long number() {
        return number;
    }

    public void number(final Long value) {
        number = value;
    }

    public TaskKind kind() {
        return kind;
    }

    public void kind(final TaskKind value) {
        kind = value;
    }

    public String reviewTarget() {
        return reviewTarget;
    }

    public void reviewTarget(final String value) {
        reviewTarget = value;
    }

    public String request() {
        return request;
    }

    public void request(final String value) {
        request = value;
    }

    public String createdAt() {
        return createdAt;
    }

    public void createdAt(final String value) {
        createdAt = value;
    }

    public String reference() {
        return reference;
    }

    public void reference(final String value) {
        reference = value;
    }

    public TaskState state() {
        return state;
    }

    public void state(final TaskState value) {
        state = value;
    }

    public Integer attempts() {
        return attempts;
    }

    public void attempts(final Integer value) {
        attempts = value;
    }

    public String actorId() {
        return actorId;
    }

    public void actorId(final String value) {
        actorId = value;
    }

    public String batchId() {
        return batchId;
    }

    public void batchId(final String value) {
        batchId = value;
    }

    public String sourceType() {
        return sourceType;
    }

    public void sourceType(final String value) {
        sourceType = value;
    }

    public String discussionId() {
        return discussionId;
    }

    public void discussionId(final String value) {
        discussionId = value;
    }

    public String commentId() {
        return commentId;
    }

    public void commentId(final String value) {
        commentId = value;
    }

    public String replyToId() {
        return replyToId;
    }

    public void replyToId(final String value) {
        replyToId = value;
    }

    public String discussionTitle() {
        return discussionTitle;
    }

    public void discussionTitle(final String value) {
        discussionTitle = value;
    }

    public String discussionBody() {
        return discussionBody;
    }

    public void discussionBody(final String value) {
        discussionBody = value;
    }

    public String discussionContext() {
        return discussionContext;
    }

    public void discussionContext(final String value) {
        discussionContext = value;
    }

    public String attemptDirectory() {
        return attemptDirectory;
    }

    public void attemptDirectory(final String value) {
        attemptDirectory = value;
    }

    public String executionBackend() {
        return executionBackend;
    }

    public void executionBackend(final String value) {
        executionBackend = value;
    }

    public String executionFingerprint() {
        return executionFingerprint;
    }

    public void executionFingerprint(final String value) {
        executionFingerprint = value;
    }

    public String runtimeId() {
        return runtimeId;
    }

    public void runtimeId(final String value) {
        runtimeId = value;
    }

    public String startedAt() {
        return startedAt;
    }

    public void startedAt(final String value) {
        startedAt = value;
    }

    public String completedAt() {
        return completedAt;
    }

    public void completedAt(final String value) {
        completedAt = value;
    }

    public String lastError() {
        return lastError;
    }

    public void lastError(final String value) {
        lastError = value;
    }

    public String publicationIntent() {
        return publicationIntent;
    }

    public void publicationIntent(final String value) {
        publicationIntent = value;
    }

    public Boolean publicationFailure() {
        return publicationFailure;
    }

    public void publicationFailure(final Boolean value) {
        publicationFailure = value;
    }

    public Boolean failureNotified() {
        return failureNotified;
    }

    public void failureNotified(final Boolean value) {
        failureNotified = value;
    }

    public String reviewIntent() {
        return reviewIntent;
    }

    public void reviewIntent(final String value) {
        reviewIntent = value;
    }

    public String reviewIntentSha() {
        return reviewIntentSha;
    }

    public void reviewIntentSha(final String value) {
        reviewIntentSha = value;
    }

    public String discussionPublishIntent() {
        return discussionPublishIntent;
    }

    public void discussionPublishIntent(final String value) {
        discussionPublishIntent = value;
    }

    public String discussionPublishedUrl() {
        return discussionPublishedUrl;
    }

    public void discussionPublishedUrl(final String value) {
        discussionPublishedUrl = value;
    }

    public String strictSolveBaseBranch() {
        return strictSolveBaseBranch;
    }

    public void strictSolveBaseBranch(final String value) {
        strictSolveBaseBranch = value;
    }

    public String strictSolveBaseSha() {
        return strictSolveBaseSha;
    }

    public void strictSolveBaseSha(final String value) {
        strictSolveBaseSha = value;
    }

    public String strictSolveForkOwner() {
        return strictSolveForkOwner;
    }

    public void strictSolveForkOwner(final String value) {
        strictSolveForkOwner = value;
    }

    public String commentUrl() {
        return commentUrl;
    }

    public void commentUrl(final String value) {
        commentUrl = value;
    }

    public String reviewUrl() {
        return reviewUrl;
    }

    public void reviewUrl(final String value) {
        reviewUrl = value;
    }

    public String prUrl() {
        return prUrl;
    }

    public void prUrl(final String value) {
        prUrl = value;
    }

    public ForkIntent forkProvisionIntent() {
        return forkProvisionIntent;
    }

    public void forkProvisionIntent(final ForkIntent value) {
        forkProvisionIntent = value;
    }

    public SolvePublication strictSolvePublication() {
        return strictSolvePublication;
    }

    public void strictSolvePublication(final SolvePublication value) {
        strictSolvePublication = value;
    }

    public List<Run> runs() {
        return runs;
    }

    public void runs(final List<Run> value) {
        runs = value;
    }

    public AgentResult result() {
        return result;
    }

    public void result(final AgentResult value) {
        result = value;
    }

    public Map<String, Object> recovery() {
        return recovery;
    }

    public void recovery(final Map<String, Object> value) {
        recovery = value;
    }
}
