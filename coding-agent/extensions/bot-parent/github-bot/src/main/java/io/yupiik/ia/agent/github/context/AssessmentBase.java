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
package io.yupiik.ia.agent.github.context;

import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.client.RestResponses;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.model.TaskKind;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/** Host-only default-branch snapshot for advisory issue/Discussion reviews, never publication authority. */
public record AssessmentBase(String branch, String sha, boolean emptyRepository) {
    private static final Pattern REPOSITORY_NAME =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}");
    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-f]{40}");

    /**
     * Resolve once before execution and persist recovery() in the task under the worker lease.
     * Subsequent attempts consume the saved pin without resolving a moving branch again.
     */
    public static AssessmentBase pin(final GitHubClient github, final TaskDocument task) {
        if (!isAssessment(task)
                || task.repository() == null
                || !REPOSITORY_NAME.matcher(task.repository()).matches()
                || task.repository().endsWith("/.")
                || task.repository().endsWith("/..")) {
            throw new IllegalArgumentException("Expected an issue or Discussion review with a valid repository");
        }
        final var recovery = task.recovery() == null ? Map.<String, Object>of() : task.recovery();
        if (recovery.containsKey("assessmentBaseSha")
                || recovery.containsKey("assessmentBaseBranch")
                || recovery.containsKey("assessmentEmptyRepository")) {
            final var empty = recovery.get("assessmentEmptyRepository");
            if (empty != null && !(empty instanceof Boolean)) {
                throw new IllegalArgumentException("Invalid assessment empty-repository pin");
            }
            return new AssessmentBase(
                    string(recovery.get("assessmentBaseBranch")),
                    string(recovery.get("assessmentBaseSha")),
                    Boolean.TRUE.equals(empty));
        }
        final var repository = github.get("/repos/" + task.repository(), RestResponses.Repository.class);
        final var branch = repository.default_branch();
        validateBranch(branch);
        try {
            final var commit = github.get(
                    "/repos/" + task.repository() + "/commits/"
                            + URLEncoder.encode(branch, StandardCharsets.UTF_8).replace("+", "%20"),
                    RestResponses.Commit.class);
            return new AssessmentBase(branch, commit.sha(), false);
        } catch (final GitHubClient.HttpException failure) {
            // Only an accessible zero-sized repository plus a commit conflict means no source.
            // Never downgrade a 401/403/404, rate limit or malformed success to source absence.
            if (failure.status() != 409
                    || !(repository.size() instanceof Number size)
                    || new BigDecimal(size.toString()).compareTo(BigDecimal.ZERO) != 0) {
                throw failure;
            }
            return new AssessmentBase(branch, null, true);
        }
    }

    public AssessmentBase {
        validateBranch(branch);
        if (emptyRepository
                ? sha != null
                : sha == null || !COMMIT_SHA.matcher(sha).matches()) {
            throw new IllegalArgumentException("Invalid assessment snapshot pin");
        }
    }

    public Map<String, Object> recovery() {
        return emptyRepository
                ? Map.of("assessmentBaseBranch", branch, "assessmentEmptyRepository", true)
                : Map.of("assessmentBaseBranch", branch, "assessmentBaseSha", sha, "assessmentEmptyRepository", false);
    }

    public static boolean isAssessment(final TaskDocument task) {
        return task.kind() == TaskKind.review
                && ("issue".equals(task.reviewTarget()) || "discussion".equals(task.reviewTarget()));
    }

    private static void validateBranch(final String branch) {
        if (branch == null
                || branch.isBlank()
                || branch.length() > 255
                || branch.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid assessment default branch");
        }
    }

    private static String string(final Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Invalid assessment pin field");
        }
        return text;
    }
}
