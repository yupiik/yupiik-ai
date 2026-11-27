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
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.client.RestResponses;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** TTL-cached discovery; exact includes add repositories, globs only filter discovered repositories. */
public final class RepositorySelector {
    private static final Pattern OWNER_NAME = Pattern.compile("[A-Za-z0-9-]+");
    private static final Pattern REPOSITORY_GLOB = Pattern.compile("[A-Za-z0-9_*?.-]+/[A-Za-z0-9_*?.-]+");
    private static final Pattern REPOSITORY_NAME = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");

    @JsonModel
    public enum SourceKind {
        ORGANIZATION,
        USER
    }

    @JsonModel
    public enum Visibility {
        PUBLIC,
        PRIVATE,
        INTERNAL
    }

    public record Source(SourceKind kind, String login) {
        public Source {
            if (kind == null || login == null || !OWNER_NAME.matcher(login).matches()) {
                throw new IllegalArgumentException("Invalid repository source");
            }
        }
    }

    public record Repository(String fullName, Visibility visibility, boolean archived, boolean fork) {}

    private final GitHubClient client;
    private final List<Source> sources;
    private final List<String> includes;
    private final List<Pattern> excludes;
    private final Set<Visibility> visibility;
    private final Duration ttl;
    private final Clock clock;
    private final boolean excludeArchived;
    private final boolean excludeForks;
    private Instant expiresAt = Instant.MIN;
    private List<Repository> cached;

    public RepositorySelector(
            final GitHubClient client,
            final List<Source> sources,
            final List<String> includes,
            final List<String> excludes,
            final Set<Visibility> visibility,
            final Duration ttl,
            final Clock clock) {
        this(client, sources, includes, excludes, visibility, ttl, clock, false, false);
    }

    public RepositorySelector(
            final GitHubClient client,
            final List<Source> sources,
            final List<String> includes,
            final List<String> excludes,
            final Set<Visibility> visibility,
            final Duration ttl,
            final Clock clock,
            final boolean excludeArchived,
            final boolean excludeForks) {
        if (ttl.isNegative()) {
            throw new IllegalArgumentException("Negative discovery TTL");
        }
        this.client = client;
        this.sources = List.copyOf(sources);
        this.includes = List.copyOf(includes);
        this.excludes = excludes.stream().map(RepositorySelector::glob).toList();
        this.visibility = Set.copyOf(visibility);
        this.ttl = ttl;
        this.clock = clock;
        this.excludeArchived = excludeArchived;
        this.excludeForks = excludeForks;
        for (final var include : includes) {
            if (!REPOSITORY_GLOB.matcher(include).matches()) {
                throw new IllegalArgumentException("Expected owner/repository include");
            }
        }
    }

    /** Returns an immutable, name-sorted snapshot; failed/incomplete discovery is never cached. */
    public synchronized List<Repository> select() {
        if (cached != null && clock.instant().isBefore(expiresAt)) {
            return cached;
        }
        final var discovered = new LinkedHashMap<String, Repository>();
        for (final var source : sources) {
            final var prefix = source.kind() == SourceKind.ORGANIZATION ? "/orgs/" : "/users/";
            String next = prefix + source.login() + "/repos?type=all&per_page=100";
            final var visited = new HashSet<String>();
            while (next != null) {
                if (!visited.add(next) || visited.size() > 1000) {
                    throw new IllegalStateException("Incomplete repository discovery");
                }
                final var page = client.<List<RestResponses.Repository>>page(
                        next, new Types.ParameterizedTypeImpl(List.class, RestResponses.Repository.class));
                for (final var item : page.body()) {
                    final var repo = repository(item);
                    discovered.put(repo.fullName().toLowerCase(Locale.ROOT), repo);
                }
                next = page.next();
            }
        }
        final var selected = new LinkedHashMap<String, Repository>();
        final var patterns = includes.stream().map(RepositorySelector::glob).toList();
        for (final var entry : discovered.entrySet()) {
            if (includes.isEmpty()
                    || patterns.stream().anyMatch(p -> p.matcher(entry.getKey()).matches())) {
                selected.put(entry.getKey(), entry.getValue());
            }
        }
        for (final var include : includes) {
            if (include.indexOf('*') < 0 && include.indexOf('?') < 0) {
                final var name = include.toLowerCase(Locale.ROOT);
                selected.put(
                        name,
                        discovered.containsKey(name)
                                ? discovered.get(name)
                                : repository(client.get("/repos/" + include, RestResponses.Repository.class)));
            }
        }
        final var result = new ArrayList<Repository>();
        for (final var repo : selected.values()) {
            if (excludes.stream().noneMatch(p -> p.matcher(repo.fullName()).matches())
                    && (!excludeArchived || !repo.archived())
                    && (!excludeForks || !repo.fork())
                    && (visibility.isEmpty() || visibility.contains(repo.visibility()))) {
                result.add(repo);
            }
        }
        result.sort(Comparator.comparing(Repository::fullName, String.CASE_INSENSITIVE_ORDER));
        cached = List.copyOf(result);
        expiresAt = clock.instant().plus(ttl);
        return cached;
    }

    private static Repository repository(final RestResponses.Repository value) {
        final var name = value.full_name();
        if (name == null || !REPOSITORY_NAME.matcher(name).matches()) {
            throw new IllegalStateException("Invalid repository response");
        }
        final var raw = value.visibility();
        final var visibility = raw == null
                ? (Boolean.TRUE.equals(value.privateRepository()) ? Visibility.PRIVATE : Visibility.PUBLIC)
                : Visibility.valueOf(raw.toString().toUpperCase(Locale.ROOT));
        return new Repository(
                name, visibility, Boolean.TRUE.equals(value.archived()), Boolean.TRUE.equals(value.fork()));
    }

    private static Pattern glob(final String value) {
        final var regex = new StringBuilder("^");
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            regex.append(c == '*' ? "[^/]*" : c == '?' ? "[^/]" : Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(regex.append('$').toString(), Pattern.CASE_INSENSITIVE);
    }
}
