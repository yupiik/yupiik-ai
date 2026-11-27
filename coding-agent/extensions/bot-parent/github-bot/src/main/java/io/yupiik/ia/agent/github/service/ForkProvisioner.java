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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.client.RestResponses;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Explicitly opt-in, durable provisioning of organization forks (not personal-account forks).
 * Invoking {@link #ensure} authorizes creation using GitHub's organization parameter.
 * The caller owns the client lifecycle and any later scheduling or operator recovery.
 */
@ApplicationScoped
public class ForkProvisioner {
    private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]*");
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_.-]+");
    private final GitHubClient github;
    private final TaskStore store;

    public ForkProvisioner(final GitHubClient github, final TaskStore store) {
        this.github = github;
        this.store = store;
    }

    /**
     * Returns the verified fork's owner/name, retaining the upstream repository name.
     * Only an HTTP 404 permits creation; an existing repository must be a fork of upstream.
     * A task's forkProvisionIntent is committed before its single POST and never cleared.
     * After an intent exists, failures (including asynchronous creation's temporary 404) are
     * propagated for later GET-only reconciliation. A crash between commit and POST can
     * therefore require operator recovery; this deliberately favors no duplicate publication.
     * Intent deduplication is per task, not across different tasks targeting the same fork.
     * The destination must be an organization accessible to the credential, not a username.
     */
    public String ensure(final String taskId, final String upstream, final String forkOwner) {
        Objects.requireNonNull(taskId, "taskId");
        final var parts = Objects.requireNonNull(upstream, "upstream").split("/", -1);
        if (parts.length != 2
                || !OWNER.matcher(parts[0]).matches()
                || !REPOSITORY.matcher(parts[1]).matches()
                || parts[1].equals(".")
                || parts[1].equals("..")
                || forkOwner == null
                || !OWNER.matcher(forkOwner).matches()) {
            throw new IllegalArgumentException("Expected upstream owner/repository and destination organization");
        }
        final var fork = forkOwner + "/" + parts[1];
        final var intent = new TaskDocument.ForkIntent(upstream, fork, Map.of());
        store.transaction(state -> {
            checkIntent(state.task(taskId).document(), intent);
            return null;
        });

        final RestResponses.Repository existing;
        try {
            existing = github.get("/repos/" + fork, RestResponses.Repository.class);
        } catch (final GitHubClient.HttpException error) {
            if (error.status() != 404) {
                throw error;
            }
            final boolean publish = store.transaction(state -> {
                final var task = state.task(taskId);
                checkIntent(task.document(), intent);
                if ((task.forkProvisionIntent() != null)) {
                    return false;
                }
                task.forkProvisionIntent(new TaskDocument.ForkIntent(upstream, fork, Map.of()));
                return true;
            });
            if (!publish) {
                throw error;
            }
            github.post(
                    "/repos/" + upstream + "/forks",
                    new GitHubClient.ForkRequest(forkOwner),
                    RestResponses.Repository.class);
            verify(github.get("/repos/" + fork, RestResponses.Repository.class), upstream, fork);
            return fork;
        }
        verify(existing, upstream, fork);
        return fork;
    }

    private void checkIntent(final TaskDocument task, final TaskDocument.ForkIntent expected) {
        if ((task.forkProvisionIntent() != null) && !expected.equals(task.forkProvisionIntent())) {
            throw new IllegalStateException("Task has a different or invalid fork provisioning intent");
        }
    }

    private void verify(final RestResponses.Repository repository, final String upstream, final String fork) {
        if (repository == null
                || !Boolean.TRUE.equals(repository.fork())
                || repository.parent() == null
                || !upstream.equalsIgnoreCase(repository.parent().full_name())) {
            throw new IllegalStateException("Expected " + fork + " to be a fork of " + upstream);
        }
    }
}
