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
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Lazy host repository selection, independent of operation options and queue state. */
@ApplicationScoped
public class RepositoryDiscovery {
    private static final Pattern REPOSITORY_NAME = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private final GithubBotConfiguration conf;
    private final GitHubClient github;
    private final Clock clock;

    public RepositoryDiscovery(final GithubBotConfiguration conf, final GitHubClient github, final Clock clock) {
        this.conf = conf;
        this.github = github;
        this.clock = clock;
    }

    public List<String> repositories() {
        final var sources = csv(conf.repositories().discovery()).stream()
                .map(value -> {
                    final var parts = value.split(":", -1);
                    if (parts.length != 2) {
                        throw new IllegalArgumentException("Expected org:login or user:login discovery source");
                    }
                    final var kind =
                            switch (parts[0]) {
                                case "org" -> RepositorySelector.SourceKind.ORGANIZATION;
                                case "user" -> RepositorySelector.SourceKind.USER;
                                default -> throw new IllegalArgumentException("Expected org or user discovery source");
                            };
                    return new RepositorySelector.Source(kind, parts[1]);
                })
                .toList();
        final var visibility = Set.copyOf(conf.repositories().visibility());
        if (sources.isEmpty()
                && !conf.repositories().excludeArchived()
                && !conf.repositories().excludeForks()
                && conf.repositories().includes().isBlank()
                && conf.repositories().excludes().isBlank()
                && visibility.size() == RepositorySelector.Visibility.values().length) {
            final var explicit = csv(conf.repositories().explicit());
            for (final var repo : explicit) {
                if (!REPOSITORY_NAME.matcher(repo).matches()) {
                    throw new IllegalArgumentException("Invalid explicit repository");
                }
            }
            return explicit.stream().distinct().toList();
        }
        final var includes = new ArrayList<>(csv(conf.repositories().explicit()));
        includes.addAll(csv(conf.repositories().includes()));
        return new RepositorySelector(
                        github,
                        sources,
                        includes,
                        csv(conf.repositories().excludes()),
                        visibility,
                        Duration.ZERO,
                        clock,
                        conf.repositories().excludeArchived(),
                        conf.repositories().excludeForks())
                .select().stream().map(RepositorySelector.Repository::fullName).toList();
    }

    private static List<String> csv(final String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(it -> !it.isEmpty())
                .toList();
    }
}
