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
package io.yupiik.ia.agent.github.client;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Host-side credentials only. Never expose these suppliers to an agent executor. */
public final class AuthTokenProvider implements Supplier<String>, AutoCloseable {
    private static final Pattern REPOSITORY_NAME = Pattern.compile("[A-Za-z0-9_-]+/[A-Za-z0-9_-][A-Za-z0-9_.-]*");
    private static final Pattern ORGANIZATION_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    private final Supplier<String> pat;
    private final String appId;
    private long installationId;
    private final String installationPath;
    private final PrivateKey key;
    private final JsonMapper json;
    private final Clock clock;
    private final GitHubClient app;
    private final ReentrantLock lock = new ReentrantLock();
    private String cached;
    private Instant expiresAt = Instant.MIN;

    private AuthTokenProvider(
            final Supplier<String> pat,
            final URI base,
            final JsonMapper json,
            final String appId,
            final long installationId,
            final String installationPath,
            final PrivateKey key,
            final Clock clock) {
        this.pat = pat;
        this.appId = appId;
        this.installationId = installationId;
        this.installationPath = installationPath;
        this.key = key;
        this.json = json;
        this.clock = clock;
        this.app = pat == null ? new GitHubClient(base, json, this::jwt, clock) : null;
    }

    /** PAT supplier is re-read for every request, allowing external rotation. */
    public static AuthTokenProvider pat(final Supplier<String> token) {
        return new AuthTokenProvider(Objects.requireNonNull(token), null, null, null, 0, null, null, null);
    }

    /** Accepts an RSA private key (for example loaded from PKCS#8 using JDK KeyFactory). */
    public static AuthTokenProvider app(
            final URI base,
            final JsonMapper json,
            final String appId,
            final long installationId,
            final PrivateKey key,
            final Clock clock) {
        if (appId == null
                || appId.isBlank()
                || installationId <= 0
                || key == null
                || !"RSA".equals(key.getAlgorithm())) {
            throw new IllegalArgumentException("App ID, installation ID and RSA private key are required");
        }
        return new AuthTokenProvider(null, base, json, appId, installationId, null, key, Objects.requireNonNull(clock));
    }

    /** Resolves the App installation for one exact owner/repository on first use. */
    public static AuthTokenProvider appForRepository(
            final URI base,
            final JsonMapper json,
            final String appId,
            final String repository,
            final PrivateKey key,
            final Clock clock) {
        if (repository == null || !REPOSITORY_NAME.matcher(repository).matches()) {
            throw new IllegalArgumentException("App installation discovery requires one exact owner/repository");
        }
        return discovering(base, json, appId, "/repos/" + repository + "/installation", key, clock);
    }

    /** Resolves the App installation for one organization on first use. */
    public static AuthTokenProvider appForOrganization(
            final URI base,
            final JsonMapper json,
            final String appId,
            final String organization,
            final PrivateKey key,
            final Clock clock) {
        if (organization == null || !ORGANIZATION_NAME.matcher(organization).matches()) {
            throw new IllegalArgumentException("App installation discovery requires one exact organization");
        }
        return discovering(base, json, appId, "/orgs/" + organization + "/installation", key, clock);
    }

    private static AuthTokenProvider discovering(
            final URI base,
            final JsonMapper json,
            final String appId,
            final String path,
            final PrivateKey key,
            final Clock clock) {
        if (appId == null || appId.isBlank() || key == null || !"RSA".equals(key.getAlgorithm())) {
            throw new IllegalArgumentException("App ID and RSA private key are required");
        }
        return new AuthTokenProvider(null, base, json, appId, 0, path, key, Objects.requireNonNull(clock));
    }

    @Override
    public String get() {
        if (pat != null) {
            return requireToken(pat.get());
        }
        lock.lock();
        try {
            if (cached != null && clock.instant().isBefore(expiresAt.minusSeconds(60))) {
                return cached;
            }
            if (installationId == 0) {
                final Installation installation;
                try {
                    installation = app.get(installationPath, Installation.class);
                } catch (final GitHubClient.HttpException transport) {
                    throw transport;
                } catch (final RuntimeException malformed) {
                    throw new IllegalStateException("GitHub installation lookup must return a positive integer id");
                }
                if (installation == null || !(installation.id() instanceof Number id)) {
                    throw new IllegalStateException("GitHub installation lookup must return a positive integer id");
                }
                final long resolved;
                try {
                    resolved = new BigDecimal(id.toString()).longValueExact();
                } catch (final ArithmeticException | NumberFormatException e) {
                    throw new IllegalStateException("GitHub installation lookup must return a positive integer id", e);
                }
                if (resolved <= 0) {
                    throw new IllegalStateException("GitHub installation lookup must return a positive integer id");
                }
                installationId = resolved;
            }
            final var response = app.post(
                    "/app/installations/" + installationId + "/access_tokens",
                    new InstallationTokenRequest(),
                    InstallationToken.class);
            if (response == null
                    || !(response.token() instanceof String value)
                    || !(response.expires_at() instanceof String expires)) {
                throw new IllegalStateException("Invalid installation token response");
            }
            final var token = requireToken(value);
            final var expiry = Instant.parse(expires);
            if (!expiry.isAfter(clock.instant())) {
                throw new IllegalStateException("GitHub returned an expired installation token");
            }
            cached = token;
            expiresAt = expiry;
            return cached;
        } finally {
            lock.unlock();
        }
    }

    private static String requireToken(final String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Missing GitHub credential");
        }
        return token;
    }

    /** Invalidates only the installation token rejected by a read, without discarding discovery. */
    public boolean invalidate(final String rejected) {
        if (pat != null) {
            return false;
        }
        lock.lock();
        try {
            if (Objects.equals(cached, rejected)) {
                cached = null;
                expiresAt = Instant.MIN;
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    private String jwt() {
        final var now = clock.instant();
        final var header = encode(json.toString(new JwtHeader("RS256", "JWT")));
        final var payload = encode(json.toString(new JwtClaims(
                now.minusSeconds(60).getEpochSecond(), now.plusSeconds(540).getEpochSecond(), appId)));
        final var unsigned = header + "." + payload;
        try {
            final var signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key);
            signer.update(unsigned.getBytes(StandardCharsets.US_ASCII));
            return unsigned + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("Unable to sign GitHub App JWT", e);
        }
    }

    @JsonModel
    public record InstallationTokenRequest() implements GitHubClient.MutationRequest {}

    @JsonModel
    public record JwtHeader(
            @Property(documentation = "JWT signing algorithm, RS256.")
            String alg,

            @Property(documentation = "Token type, JWT.") String typ) {}

    @JsonModel
    public record JwtClaims(
            @Property(documentation = "Issued-at epoch seconds with clock skew allowance.")
            long iat,

            @Property(documentation = "Expiration epoch seconds, within GitHub's ten minute limit.")
            long exp,

            @Property(documentation = "GitHub App identifier.")
            String iss) {}

    private static String encode(final String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        if (app != null) {
            app.close();
        }
    }

    @JsonModel
    public record Installation(Object id) {}

    @JsonModel
    public record InstallationToken(Object token, Object expires_at) {}
}
