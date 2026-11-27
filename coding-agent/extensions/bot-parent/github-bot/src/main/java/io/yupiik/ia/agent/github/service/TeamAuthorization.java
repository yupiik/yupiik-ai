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
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Explicit immutable-ID overrides, or effective repository writers subject to optional team restrictions. */
@ApplicationScoped
public class TeamAuthorization {
    private static final Logger LOGGER = Logger.getLogger(TeamAuthorization.class.getName());
    private static final Pattern TEAM_SLUG = Pattern.compile("[A-Za-z0-9-]+/[A-Za-z0-9_-]+");
    private static final Pattern ACTOR_ID = Pattern.compile("[0-9]+");
    private static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9-]+");
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_-]+/[A-Za-z0-9_-][A-Za-z0-9_.-]*");

    private final GitHubClient github;
    private final Clock clock;
    private final GithubBotConfiguration configuration;
    private final ReentrantLock cacheLock = new ReentrantLock();
    private final LinkedHashMap<CacheKey, CachedDecision> cache = new LinkedHashMap<>();

    public TeamAuthorization(final GitHubClient github, final GithubBotConfiguration configuration, final Clock clock) {
        this.github = github;
        this.clock = clock;
        this.configuration = configuration;
    }

    public boolean allows(final String repository, final Object actorId, final String suppliedLogin) {
        final var id = String.valueOf(actorId);
        if (!ACTOR_ID.matcher(id).matches()) {
            return false;
        }
        final var authorization = configuration.authorization();
        // An explicit ID always wins, without repository, identity, or team API calls.
        if (Arrays.stream(authorization.userIds().split(",")).map(String::trim).anyMatch(id::equals)) {
            return true;
        }
        if (!authorization.allowRepositoryWriters()) {
            return false;
        }
        if (repository == null
                || !REPOSITORY.matcher(repository).matches()
                || (suppliedLogin != null && !LOGIN.matcher(suppliedLogin).matches())) {
            return false;
        }
        final var teams = Arrays.stream(authorization.teams().split(","))
                .map(String::trim)
                .filter(it -> !it.isEmpty())
                .toList();
        for (final var team : teams) {
            if (!TEAM_SLUG.matcher(team).matches()) {
                throw new IllegalArgumentException("Expected org/team authorization restriction");
            }
        }
        final var key = new CacheKey(repository.toLowerCase(Locale.ROOT), id);
        final var cacheTtl = Duration.ofSeconds(authorization.permissionsCacheTtlSeconds());
        final var cached = cached(key, cacheTtl);
        if (cached != null) {
            return cached.allows(suppliedLogin);
        }
        final Decision decision;
        try {
            decision = lookup(key, teams);
        } catch (final GitHubClient.DeferredRequestException error) {
            // DeferredRequestException extends HttpException and can carry a rate-limit 403.
            throw error;
        } catch (final GitHubClient.HttpException error) {
            if (error.status() == 401 || error.status() == 403) {
                LOGGER.info(
                        () -> "GitHub token lacks capability to check repository permissions or team membership for "
                                + key.repository() + " (HTTP " + error.status() + "); denying actor " + id);
                return false;
            }
            if (error.status() == 404) {
                return false;
            }
            throw error;
        }
        // Cache only completed lookups, never token-capability or transient failures.
        remember(key, decision, cacheTtl);
        return decision.allows(suppliedLogin);
    }

    private Decision lookup(final CacheKey key, final List<String> teams) {
        // Resolve the current login from immutable identity: renamed/reused logins cannot authorize another user.
        final var user = github.get("/user/" + key.actorId(), RestResponses.User.class);
        if (!key.actorId().equals(String.valueOf(user.id()))) {
            return new Decision(null, false);
        }
        final var login = user.login();
        if (login == null || !LOGIN.matcher(login).matches()) {
            return new Decision(null, false);
        }
        final var permission = github.get(
                "/repos/" + key.repository() + "/collaborators/" + login + "/permission",
                RestResponses.Permission.class);
        final var identity = permission.user();
        if (identity == null
                || !key.actorId().equals(String.valueOf(identity.id()))
                || !login.equalsIgnoreCase(identity.login())) {
            return new Decision(login, false);
        }
        // GitHub's permission field is the effective base permission (maintain -> write, triage -> read).
        // role_name is display/custom-role information and is not an authorization signal.
        final var role = permission.permission();
        if (!"write".equals(role) && !"admin".equals(role)) {
            return new Decision(login, false);
        }
        if (teams.isEmpty()) {
            return new Decision(login, true);
        }
        for (final var team : teams) {
            final var parts = team.split("/");
            final RestResponses.Membership membership;
            try {
                membership = github.get(
                        "/orgs/" + parts[0] + "/teams/" + parts[1] + "/memberships/" + login,
                        RestResponses.Membership.class);
            } catch (final GitHubClient.DeferredRequestException error) {
                throw error;
            } catch (final GitHubClient.HttpException error) {
                if (error.status() == 404) {
                    continue;
                }
                throw error;
            }
            if ("active".equals(membership.state())) {
                return new Decision(login, true);
            }
        }
        return new Decision(login, false);
    }

    private Decision cached(final CacheKey key, final Duration cacheTtl) {
        if (cacheTtl.isZero()) {
            return null;
        }
        cacheLock.lock();
        try {
            final var value = cache.get(key);
            if (value == null) {
                return null;
            }
            if (!clock.instant().isBefore(value.expiresAt())) {
                cache.remove(key);
                return null;
            }
            return value.decision();
        } finally {
            cacheLock.unlock();
        }
    }

    private void remember(final CacheKey key, final Decision decision, final Duration cacheTtl) {
        if (cacheTtl.isZero()) {
            return;
        }
        cacheLock.lock();
        try {
            final var now = clock.instant();
            cache.values().removeIf(value -> !now.isBefore(value.expiresAt()));
            if (!cache.containsKey(key)
                    && cache.size() >= configuration.authorization().permissionsCacheMaxEntries()) {
                cache.pollFirstEntry();
            }
            cache.put(key, new CachedDecision(decision, now.plus(cacheTtl)));
        } finally {
            cacheLock.unlock();
        }
    }

    private record CacheKey(String repository, String actorId) {}

    private record CachedDecision(Decision decision, Instant expiresAt) {}

    private record Decision(String login, boolean allowed) {
        private boolean allows(final String suppliedLogin) {
            return allowed && (suppliedLogin == null || suppliedLogin.equalsIgnoreCase(login));
        }
    }
}
