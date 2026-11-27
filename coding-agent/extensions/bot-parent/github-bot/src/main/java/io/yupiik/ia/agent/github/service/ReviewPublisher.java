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

import io.yupiik.fusion.framework.api.container.Types;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.client.RestResponses;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.configuration.ReviewPresentationConfiguration;
import io.yupiik.ia.agent.github.model.AgentResult;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Native reviews are pinned to a head SHA and every proposed inline location is checked. */
@ApplicationScoped
public class ReviewPublisher {
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");
    private final GitHubClient github;
    private final TaskStore store;
    private final GithubBotConfiguration configuration;
    private final ReviewRenderer renderer;
    /** Fusion lazy proxy constructor; the contextual instance owns the compiled presentation templates. */
    protected ReviewPublisher() {
        github = null;
        store = null;
        configuration = null;
        renderer = null;
    }

    public ReviewPublisher(
            final GitHubClient github,
            final TaskStore store,
            final GithubBotConfiguration configuration,
            final ReviewPresentationConfiguration presentation) {
        this.github = github;
        this.store = store;
        this.configuration = configuration;
        this.renderer = new ReviewRenderer(presentation);
    }

    public String publish(final TaskDocument taskDocument, final AgentResult agentResult) {
        if (!"pull_request".equals(taskDocument.reviewTarget()) || agentResult.assessment() != null) {
            throw new IllegalArgumentException("Native review publication requires a pull request review target");
        }
        final var task = taskDocument;
        final var id = String.valueOf(task.id());
        final var prefix = "/repos/" + task.repository() + "/pulls/" + task.number();
        final var marker = "<!-- yca-task:" + id + ":review -->";
        final var pr = github.get(prefix, RestResponses.Pull.class);
        final var sha = pr.head().sha();
        final var intendedSha = store.transaction(state -> state.task(id).reviewIntentSha());
        final var reconciliationSha = intendedSha instanceof String persisted ? persisted : sha;
        String next = prefix + "/reviews?per_page=100";
        while (next != null) {
            final var page = github.<List<RestResponses.Review>>page(
                    next, new Types.ParameterizedTypeImpl(List.class, RestResponses.Review.class));
            for (final var review : page.body()) {
                if (String.valueOf(review.body()).contains(marker)
                        && reconciliationSha.equals(review.commit_id())
                        && "COMMENTED".equals(review.state())
                        && review.user() != null
                        && configuration.login().equalsIgnoreCase(review.user().login())) {
                    return String.valueOf(review.html_url());
                }
            }
            next = page.next();
        }
        if (agentResult.review() == null) {
            throw new IllegalStateException("No current task-correlated native review was found");
        }
        if (store.<Boolean>transaction(state -> (state.task(id).reviewIntent() != null))) {
            throw new IllegalStateException("Ambiguous prior review publication; reconcile before another write");
        }
        final var review = agentResult.review();
        if (!sha.equals(review.headSha())) {
            throw new IllegalArgumentException("Review head changed; re-analysis is required");
        }
        final var valid = new HashSet<String>();
        next = prefix + "/files?per_page=100";
        int files = 0;
        while (next != null) {
            final var page = github.<List<RestResponses.File>>page(
                    next, new Types.ParameterizedTypeImpl(List.class, RestResponses.File.class));
            for (final var file : page.body()) {
                if (++files > 3000) {
                    throw new IllegalArgumentException("PR file listing exceeded supported limit");
                }
                if (file.patch() != null) {
                    valid.addAll(locations(String.valueOf(file.filename()), file.patch()));
                }
            }
            next = page.next();
        }
        final var comments = new ArrayList<GitHubClient.ReviewFinding>();
        final var proposed = review.comments() == null ? List.<AgentResult.Finding>of() : review.comments();
        if (proposed.size() > 30) {
            throw new IllegalArgumentException("At most 30 inline findings are supported per review");
        }
        for (final var item : proposed) {
            final var finding = item;
            final var path = finding.path();
            final var side = finding.side();
            final var line = finding.line();
            final var body = finding.body();
            if (line == null
                    || line.compareTo(BigDecimal.valueOf(line.intValue())) != 0
                    || !valid.contains(path + ":" + side + ":" + line.intValue())
                    || body == null
                    || body.isBlank()
                    || body.length() > 10000) {
                throw new IllegalArgumentException("Invalid inline finding location or body; nothing published");
            }
            // Inline findings are independently polled, so they need the same loop-prevention marker.
            final var rendered = renderer.renderFinding(finding, comments.size() + 1);
            if (rendered.length() > 10000) {
                throw new IllegalArgumentException("Inline finding presentation too large; nothing published");
            }
            comments.add(new GitHubClient.ReviewFinding(path, side, line.intValue(), marker + "\n" + rendered));
        }
        final var summary = review.body() != null ? review.body() : agentResult.summary();
        if (summary != null && summary.length() > 30000) {
            throw new IllegalArgumentException("Review summary too large");
        }
        final var body = renderer.render(agentResult, true);
        if (body.length() > 30000) {
            throw new IllegalArgumentException("Review summary too large");
        }
        final var current = github.get(prefix, RestResponses.Pull.class);
        if (!sha.equals(current.head().sha())) {
            throw new IllegalArgumentException("Review head changed during validation");
        }
        store.transaction(state -> {
            state.task(id).reviewIntent(marker);
            state.task(id).reviewIntentSha(sha);
            return null;
        });
        final var posted = github.post(
                prefix + "/reviews",
                new GitHubClient.ReviewRequest(sha, "COMMENT", marker + "\n" + body, comments),
                RestResponses.Review.class);
        return String.valueOf(posted.html_url());
    }

    public static Set<String> locations(final String path, final String patch) {
        final var result = new HashSet<String>();
        int left = -1;
        int right = -1;
        for (final var line : patch.split("\n", -1)) {
            final var hunk = HUNK.matcher(line);
            if (hunk.matches()) {
                left = Integer.parseInt(hunk.group(1));
                right = Integer.parseInt(hunk.group(3));
            } else if (left >= 0 && !line.isEmpty()) {
                switch (line.charAt(0)) {
                    case '-' -> result.add(path + ":LEFT:" + left++);
                    case '+' -> result.add(path + ":RIGHT:" + right++);
                    case ' ' -> {
                        result.add(path + ":LEFT:" + left++);
                        result.add(path + ":RIGHT:" + right++);
                    }
                    default -> {
                        /* no-newline marker or unsupported metadata */
                    }
                }
            }
        }
        return result;
    }
}
