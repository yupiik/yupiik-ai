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

import static io.yupiik.ia.agent.github.service.FixPublisher.branch;
import static io.yupiik.ia.agent.github.service.FixPublisher.prefix;
import static io.yupiik.ia.agent.github.service.FixPublisher.repository;
import static io.yupiik.ia.agent.github.service.FixPublisher.sha;
import static io.yupiik.ia.agent.github.service.FixPublisher.target;
import static io.yupiik.ia.agent.github.service.FixPublisher.targetMap;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.client.RestResponses;
import io.yupiik.ia.agent.github.configuration.FixConfiguration;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.model.TaskKind;
import io.yupiik.ia.agent.github.service.FixPublisher.Target;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Host-only request authority shared by trigger pinning and publication, never exposed to an agent. */
@ApplicationScoped
public class FixRequestAuthority {
    private final GitHubClient github;
    private final FixConfiguration configuration;

    public FixRequestAuthority(final GitHubClient github, final FixConfiguration configuration) {
        this.github = github;
        this.configuration = configuration;
    }

    /** Resolves an authorized host draft without requiring a stored task ID. No store lock is held. */
    public Map<String, Object> pinRequest(final TaskDocument draft) {
        validateTask(draft);
        if (draft.recovery() != null && draft.recovery().containsKey("fixTarget")) {
            final var pinned = target(TaskStore.map(draft.recovery().get("fixTarget")));
            verify(draft, pinned, true);
            return targetMap(pinned);
        }
        final var pr = pull(draft);
        final var head = pr.head();
        final var headRepo = head.repo();
        final var baseRepo = pr.base().repo();
        final var target = new Target(
                required(headRepo.node_id()),
                repository(required(headRepo.full_name())),
                branch(required(head.ref())),
                sha(head.sha()),
                required(baseRepo.node_id()),
                draft.repository(),
                draft.number(),
                required(pr.node_id()),
                required(pr.html_url()));
        verify(draft, target, true, pr);
        return targetMap(target);
    }

    void validateTask(final TaskDocument task) {
        if (task == null) {
            throw new IllegalArgumentException("Missing fix task");
        }
        if (task.kind() != TaskKind.fix
                || "discussion".equals(task.sourceType())
                || task.discussionId() != null
                || task.number() == null
                || task.number() <= 0) {
            throw new IllegalArgumentException("Fix requires a pull request task, never a discussion");
        }
        repository(task.repository());
        if (!configuration.enabled()) {
            throw new IllegalStateException("Fix publication is disabled");
        }
    }

    void policy(final String repository) {
        if (!configuration.enabled()
                || configuration.repositories() == null
                || !List.of(configuration.repositories().split(",", -1)).stream()
                        .map(String::strip)
                        .anyMatch(repository::equals)) {
            throw new IllegalStateException("Head repository is not explicitly allowed for fixes");
        }
    }

    private RestResponses.Pull pull(final TaskDocument task) {
        final var pr = github.get("/repos/" + task.repository() + "/pulls/" + task.number(), RestResponses.Pull.class);
        if (!"open".equals(pr.state())
                || Boolean.TRUE.equals(pr.merged())
                || !(pr.number() instanceof Number number)
                || new BigDecimal(number.toString()).compareTo(BigDecimal.valueOf(task.number())) != 0) {
            throw new IllegalStateException("Fix requires the existing open pull request");
        }
        return pr;
    }

    String verify(final TaskDocument task, final Target target, final boolean exactHead) {
        policy(target.repository());
        return verify(task, target, exactHead, pull(task));
    }

    private String verify(
            final TaskDocument task, final Target target, final boolean exactHead, final RestResponses.Pull pr) {
        if (!target.upstream().equals(task.repository())
                || target.number() != task.number()
                || !target.url().equals("https://github.com/" + task.repository() + "/pull/" + task.number())) {
            throw new IllegalStateException("Pinned upstream identity changed");
        }
        policy(target.repository());
        final var head = pr.head();
        final var headRepo = head.repo();
        final var baseRepo = pr.base().repo();
        if (!target.pullRequestId().equals(pr.node_id())
                || !target.url().equals(pr.html_url())
                || !target.repositoryId().equals(headRepo.node_id())
                || !target.repository().equals(headRepo.full_name())
                || !target.upstreamId().equals(baseRepo.node_id())
                || !target.upstream().equals(baseRepo.full_name())
                || !target.branch().equals(head.ref())) {
            throw new IllegalStateException("PR no longer matches pinned fix binding");
        }
        final var repo = github.get(prefix(target), RestResponses.Repository.class);
        if (!target.repositoryId().equals(repo.node_id())
                || !target.repository().equals(repo.full_name())
                || !Boolean.FALSE.equals(repo.archived())
                || !Boolean.FALSE.equals(repo.disabled())
                || repo.permissions() == null
                || !Boolean.TRUE.equals(repo.permissions().push())) {
            throw new IllegalStateException("Fix head repository is unavailable or lacks push permission");
        }
        final var upstream = github.get("/repos/" + target.upstream(), RestResponses.Repository.class);
        if (!target.upstreamId().equals(upstream.node_id())
                || !target.upstream().equals(upstream.full_name())
                || !Boolean.FALSE.equals(upstream.archived())
                || !Boolean.FALSE.equals(upstream.disabled())) {
            throw new IllegalStateException("Pinned upstream repository is unavailable");
        }
        final var ref =
                github.get(prefix(target) + "/git/ref/heads/" + encode(target.branch()), RestResponses.Ref.class);
        final var headSha = sha(ref.object().sha());
        if (!Objects.equals(ref.ref(), "refs/heads/" + target.branch())
                || !headSha.equals(sha(head.sha()))
                || (exactHead && !target.headSha().equals(headSha))) {
            throw new IllegalStateException("PR head moved; fix will not rebase or overwrite it");
        }
        return headSha;
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String required(final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing fix binding field");
        }
        return value;
    }
}
