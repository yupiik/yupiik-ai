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
package io.yupiik.ia.agent.core.usage;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.llm.CodexAuthTemplateHelper;
import io.yupiik.ia.agent.core.llm.CodexAuthTemplateHelper.CodexCredentials;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

/**
 * Client for the Codex account usage/quota endpoint, mirroring the Codex CLI's {@code wham/usage}
 * (ChatGPT style) call. Authenticates with the provider's OAuth session through
 * {@link CodexAuthTemplateHelper} (the access token is never persisted here and never leaves yca).
 * The endpoint is reached via {@code {codexRoot}/wham/usage} where the root is the provider base URL
 * with any {@code /codex} route suffix stripped (eg. {@code https://chatgpt.com/backend-api}).
 */
public class CodexUsageClient {
    private Duration requestTimeout = Duration.ofMinutes(2);

    /** Sets the finite HTTP request budget, including OAuth token exchange (never retried here). */
    public CodexUsageClient requestTimeout(final Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("A positive finite timeout is required");
        }
        timeout.toNanos();
        this.requestTimeout = timeout;
        return this;
    }

    private final JsonMapper mapper;
    private final ExtendedHttpClient httpClient;
    private final CodexAuthTemplateHelper codexAuth;

    public CodexUsageClient(
            final JsonMapper mapper, final ExtendedHttpClient httpClient, final CodexAuthTemplateHelper codexAuth) {
        this.mapper = mapper;
        this.httpClient = httpClient;
        this.codexAuth = codexAuth;
    }

    /**
     * Fetches the current usage snapshot for {@code providerName}. The base URL of the Codex root is
     * derived from the provider id is not needed here: the endpoint host comes from the codex root
     * passed by the caller (which knows the provider baseUrl). Throws on a 401/403 (needs login) or
     * when no session exists.
     *
     * @param codexRoot the Codex API root (provider base URL with the {@code /codex} route stripped),
     *     eg. {@code https://chatgpt.com/backend-api}.
     * @param providerName the provider id used to resolve the OAuth session.
     * @return the decoded usage snapshot, never {@code null}.
     */
    public CodexUsageSnapshot fetch(final String codexRoot, final String providerName) {
        final CodexCredentials credentials = codexAuth.resolveCredentials(providerName);
        final var request = HttpRequest.newBuilder()
                .timeout(requestTimeout)
                .uri(URI.create(trimTrailingSlash(codexRoot) + "/wham/usage"))
                .header("accept", "application/json")
                .header("authorization", "Bearer " + credentials.accessToken());
        if (credentials.accountId() != null && !credentials.accountId().isBlank()) {
            request.header("ChatGPT-Account-Id", credentials.accountId());
        }
        final HttpResponse<String> response = send(request.build());
        final var status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new NeedsLoginException(providerName, status);
        }
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Codex usage request failed (" + status + "): " + response.body());
        }
        return decode(response.body());
    }

    private HttpResponse<String> send(final HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
        } catch (final IOException e) {
            throw new IllegalStateException("Codex usage request failed: " + e.getMessage(), e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during Codex usage request", e);
        } catch (final CompletionException e) {
            final var cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("Codex usage request failed: " + cause.getMessage(), cause);
        }
    }

    private CodexUsageSnapshot decode(final String body) {
        final Map<String, Object> root = mapper.fromString(Map.class, body);
        final String planType = root.get("plan_type") instanceof String s && !s.isBlank() ? s : null;

        final List<CodexUsageSnapshot.RateLimit> limits = new ArrayList<>();
        if (root.get("rate_limit") instanceof Map<?, ?> rateLimit) {
            addWindow(limits, CodexUsageSnapshot.RateLimit.PRIMARY, "primary", rateLimit.get("primary_window"));
            addWindow(limits, CodexUsageSnapshot.RateLimit.SECONDARY, "secondary", rateLimit.get("secondary_window"));
        }
        if (root.get("additional_rate_limits") instanceof List<?> additional) {
            for (final Object entry : additional) {
                if (!(entry instanceof Map<?, ?> m)) {
                    continue;
                }
                final Object window = m.get("rate_limit") != null ? m.get("rate_limit") : m.get("limit");
                addWindow(limits, CodexUsageSnapshot.RateLimit.ADDITIONAL, labelOf(m), window);
            }
        }

        CodexUsageSnapshot.Credits credits = CodexUsageSnapshot.Credits.EMPTY;
        if (root.get("credits") instanceof Map<?, ?> c) {
            credits = new CodexUsageSnapshot.Credits(
                    asBoolean(c.get("has_credits")),
                    c.get("balance") == null ? null : String.valueOf(c.get("balance")),
                    asBoolean(c.get("unlimited")));
        }

        return new CodexUsageSnapshot(planType, limits, credits, asBoolean(root.get("ordinary_usage_allowed")), null);
    }

    private static void addWindow(
            final List<CodexUsageSnapshot.RateLimit> limits, final String kind, final String label, final Object raw) {
        if (!(raw instanceof Map<?, ?> w)) {
            return;
        }
        limits.add(new CodexUsageSnapshot.RateLimit(
                label,
                kind,
                asDouble(w.get("used_percent")),
                asLong(w.get("limit_window_seconds")),
                asLong(w.get("reset_at"))));
    }

    private static String labelOf(final Map<?, ?> m) {
        if (m.get("label") instanceof String s && !s.isBlank()) {
            return s;
        }
        if (m.get("name") instanceof String s && !s.isBlank()) {
            return s;
        }
        return "additional";
    }

    private static String trimTrailingSlash(final String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        var s = value;
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static Boolean asBoolean(final Object value) {
        return value instanceof Boolean b ? b : null;
    }

    private static Double asDouble(final Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s);
            } catch (final NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Long asLong(final Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s);
            } catch (final NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Thrown when the usage backend rejects the session (missing/expired/revoked login). */
    public static class NeedsLoginException extends RuntimeException {
        public NeedsLoginException(final String providerName, final int status) {
            super("Codex usage requires login for provider '" + providerName + "' (HTTP " + status + ")");
        }
    }
}
