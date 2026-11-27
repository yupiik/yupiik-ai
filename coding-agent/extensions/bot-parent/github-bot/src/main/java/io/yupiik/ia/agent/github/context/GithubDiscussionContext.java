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

import io.yupiik.fusion.framework.api.container.Types;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.client.RestResponses;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Fixed task discussion authority; neither adapters nor callers can supply an endpoint or repository. */
public final class GithubDiscussionContext {
    private static final Pattern REPOSITORY_NAME = Pattern.compile("[A-Za-z0-9_-]+/[A-Za-z0-9_-][A-Za-z0-9_.-]*");

    private final GitHubClient github;
    private final String repository;
    private final long number;

    public GithubDiscussionContext(final GitHubClient github, final String repository, final long number) {
        if (repository == null
                || !REPOSITORY_NAME.matcher(repository).matches()
                || repository.endsWith("/.")
                || repository.endsWith("/..")
                || number < 1) {
            throw new IllegalArgumentException("Invalid fixed context task");
        }
        this.github = github;
        this.repository = repository;
        this.number = number;
    }

    public Object read(final String operation, final int page) {
        if (operation == null || page < 1 || page > 10) {
            throw new IllegalArgumentException("INVALID_CONTEXT_PAGE: pages range from 1 through 10");
        }
        final var pull = "/repos/" + repository + "/pulls/" + number;
        final String endpoint;
        final String[] fields;
        switch (operation) {
            case "pull" -> {
                if (page != 1) {
                    throw new IllegalArgumentException("Pull is not paginated");
                }
                final var raw = github.get(pull, RestResponses.Pull.class);
                final var result = new LinkedHashMap<String, Object>();
                result.put("id", raw.id());
                result.put("node_id", raw.node_id());
                result.put("number", raw.number());
                result.put("title", raw.title());
                result.put("body", raw.body());
                result.put("state", raw.state());
                final var head = new LinkedHashMap<String, Object>();
                head.put("sha", raw.head().sha());
                result.put("head", head);
                final var base = new LinkedHashMap<String, Object>();
                base.put("sha", raw.base().sha());
                result.put("base", base);
                return result;
            }
            case "files" -> {
                endpoint = pull + "/files";
                fields = new String[] {"sha", "filename", "previous_filename", "status", "patch"};
            }
            case "reviews" -> {
                endpoint = pull + "/reviews";
                fields = new String[] {"id", "node_id", "body", "state", "commit_id", "submitted_at"};
            }
            case "reviewComments" -> {
                endpoint = pull + "/comments";
                fields = new String[] {
                    "id", "node_id", "path", "line", "side", "body", "commit_id", "in_reply_to_id", "updated_at"
                };
            }
            case "issueComments" -> {
                endpoint = "/repos/" + repository + "/issues/" + number + "/comments";
                fields = new String[] {"id", "node_id", "body", "updated_at"};
            }
            default -> throw new IllegalArgumentException("UNKNOWN_CONTEXT_OPERATION");
        }
        final var response = github.<List<RestResponses.ContextEntry>>page(
                endpoint + "?per_page=100&page=" + page,
                new Types.ParameterizedTypeImpl(List.class, RestResponses.ContextEntry.class));
        final var entries = response.body();
        if (entries == null || entries.size() > 100) {
            throw new IllegalArgumentException("Expected bounded list");
        }
        final var result = new ArrayList<Map<String, Object>>();
        for (final var entry : entries) {
            result.add(select(entry, fields));
        }
        // Links are metadata only, never request targets. The next request is always locally constructed.
        return Map.of(
                "entries",
                result,
                "page",
                page,
                "complete",
                response.next() == null,
                "pageLimitReached",
                page == 10 && response.next() != null);
    }

    private static Map<String, Object> select(final RestResponses.ContextEntry source, final String... fields) {
        if (source == null) {
            throw new IllegalArgumentException("Expected object");
        }
        final var result = new LinkedHashMap<String, Object>();
        for (final var field : fields) {
            final var scalar =
                    switch (field) {
                        case "id" -> source.id();
                        case "node_id" -> source.node_id();
                        case "sha" -> source.sha();
                        case "filename" -> source.filename();
                        case "previous_filename" -> source.previous_filename();
                        case "status" -> source.status();
                        case "patch" -> source.patch();
                        case "body" -> source.body();
                        case "state" -> source.state();
                        case "commit_id" -> source.commit_id();
                        case "submitted_at" -> source.submitted_at();
                        case "path" -> source.path();
                        case "line" -> source.line();
                        case "side" -> source.side();
                        case "in_reply_to_id" -> source.in_reply_to_id();
                        case "updated_at" -> source.updated_at();
                        default -> throw new IllegalArgumentException("Unknown context field");
                    };
            if (scalar == null || scalar instanceof String || scalar instanceof Number || scalar instanceof Boolean) {
                result.put(field, scalar);
            }
        }
        return result;
    }
}
