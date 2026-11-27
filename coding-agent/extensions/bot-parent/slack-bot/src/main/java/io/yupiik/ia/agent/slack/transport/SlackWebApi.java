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
package io.yupiik.ia.agent.slack.transport;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.slack.model.SlackJson;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Bounded, same-origin Slack POST transport with typed {@link SlackJson} responses and no retries.
 * Most methods use JSON; users.info and conversations.replies use form-encoded arguments.
 * The parent constructs this object and owns the mapper, HTTP client and token suppliers; close Socket Mode
 * before closing borrowed dependencies. The client must be redirect-never, authenticator-free and cookie-free;
 * listeners must neither log authorization/bodies nor replay POSTs. HTTPS is required except numeric loopback fixtures.
 *
 * <p>Connection discovery uses the app token; message/view publication uses the bot token. Requests have a timeout
 * and request/response byte limits. Failures expose only sanitized {@link SlackApiException} metadata, never bodies,
 * credentials, WebSocket tickets, request URIs or nested transport exceptions. The parent must defer on 429 using
 * {@link SlackApiException#retryAfter()} and never blindly retry a publication with an uncertain outcome.
 */
public final class SlackWebApi {
    private static final Pattern TOKEN_CHARACTERS = Pattern.compile("[A-Za-z0-9-]+");

    private static final Set<String> SAFE_ERRORS = Set.of(
            "invalid_auth",
            "not_authed",
            "token_revoked",
            "account_inactive",
            "not_allowed_token_type",
            "missing_scope",
            "ratelimited",
            "channel_not_found",
            "not_in_channel",
            "is_archived",
            "invalid_arguments",
            "invalid_trigger",
            "trigger_expired",
            "internal_error",
            "service_unavailable",
            "link_disabled",
            "disabled",
            "not_enabled",
            "restricted_action",
            "invalid_action_token",
            "action_token_expired",
            "expired_action_token",
            "user_not_found",
            "message_too_long");
    private final JsonMapper json;
    private final HttpClient client;
    private final Settings settings;
    private final Supplier<String> appToken;
    private final Supplier<String> botToken;

    public SlackWebApi(
            final JsonMapper json,
            final HttpClient client,
            final Settings settings,
            final Supplier<String> appToken,
            final Supplier<String> botToken) {
        this.json = Objects.requireNonNull(json);
        this.client = Objects.requireNonNull(client);
        this.settings = Objects.requireNonNull(settings);
        this.appToken = Objects.requireNonNull(appToken);
        this.botToken = Objects.requireNonNull(botToken);
        if (client.followRedirects() != HttpClient.Redirect.NEVER
                || client.authenticator().isPresent()
                || client.cookieHandler().isPresent()) {
            throw new IllegalArgumentException(
                    "Slack requires a redirect-never, authenticator-free, cookie-free client");
        }
    }

    public record Settings(URI base, Duration requestTimeout, int maxRequestBytes, int maxResponseBytes) {
        public Settings {
            Objects.requireNonNull(base);
            Objects.requireNonNull(requestTimeout);
            final var loopback =
                    Set.of("127.0.0.1", "[::1]", "::1").contains(base.getHost() == null ? "" : base.getHost());
            if (base.getHost() == null
                    || base.getUserInfo() != null
                    || base.getRawQuery() != null
                    || base.getRawFragment() != null
                    || !base.getPath().endsWith("/")
                    || !("https".equalsIgnoreCase(base.getScheme())
                            || (loopback && "http".equalsIgnoreCase(base.getScheme())))) {
                throw new IllegalArgumentException("Invalid Slack API base (HTTPS and trailing slash required)");
            }
            if (requestTimeout.isZero() || requestTimeout.isNegative() || maxRequestBytes < 1 || maxResponseBytes < 1) {
                throw new IllegalArgumentException("Slack timeout and byte limits must be positive");
            }
        }
    }

    public CompletionStage<URI> openConnection() {
        final var source =
                post("apps.connections.open", Map.of(), appToken, "xapp-", SlackJson.ConnectionResponse.class, false);
        final var result = source.thenApply(body -> {
            try {
                if (body.url() == null) {
                    throw failure(200, "invalid_response", Optional.empty());
                }
                final var uri = URI.create(body.url());
                final var loopback =
                        Set.of("127.0.0.1", "[::1]", "::1").contains(uri.getHost() == null ? "" : uri.getHost());
                if (uri.getHost() == null
                        || uri.getUserInfo() != null
                        || uri.getFragment() != null
                        || !("wss".equalsIgnoreCase(uri.getScheme())
                                || (loopback && "ws".equalsIgnoreCase(uri.getScheme())))) {
                    throw failure(200, "invalid_response", Optional.empty());
                }
                return uri;
            } catch (final RuntimeException error) {
                throw failure(200, "invalid_response", Optional.empty());
            }
        });
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                source.cancel(true);
            }
        });
        return result;
    }

    public CompletionStage<SlackJson.ApiResponse> postMessage(final SlackJson.PostMessage payload) {
        return post("chat.postMessage", payload, botToken, "xoxb-", SlackJson.ApiResponse.class, false);
    }

    public CompletionStage<SlackJson.ApiResponse> openView(final SlackJson.OpenView payload) {
        return post("views.open", payload, botToken, "xoxb-", SlackJson.ApiResponse.class, false);
    }

    public CompletionStage<SlackJson.UserInfoResponse> memberInfo(final String userId) {
        return post(
                "users.info",
                "user=" + URLEncoder.encode(userId, StandardCharsets.UTF_8),
                botToken,
                "xoxb-",
                SlackJson.UserInfoResponse.class,
                true);
    }

    public CompletionStage<SlackJson.SearchResponse> searchContext(final SlackJson.SearchRequest payload) {
        return post("assistant.search.context", payload, botToken, "xoxb-", SlackJson.SearchResponse.class, false);
    }

    /** Fetches one page only; the controller owns thread authorization, pagination and rate-limit scheduling. */
    public CompletionStage<SlackJson.ThreadResponse> threadReplies(final SlackJson.ThreadRequest payload) {
        try {
            final var form = new StringBuilder();
            appendThreadArgument(form, "channel", Objects.requireNonNull(payload.channel()));
            appendThreadArgument(form, "ts", Objects.requireNonNull(payload.ts()));
            appendThreadArgument(form, "oldest", payload.oldest());
            appendThreadArgument(form, "latest", payload.latest());
            appendThreadArgument(form, "inclusive", payload.inclusive());
            appendThreadArgument(form, "limit", payload.limit());
            appendThreadArgument(form, "cursor", payload.cursor());
            return post(
                    "conversations.replies", form.toString(), botToken, "xoxb-", SlackJson.ThreadResponse.class, true);
        } catch (final RuntimeException unsafe) {
            return CompletableFuture.failedFuture(failure(0, "request_error", Optional.empty()));
        }
    }

    private void appendThreadArgument(final StringBuilder form, final String name, final Object value) {
        if (value != null) {
            if (!form.isEmpty()) {
                form.append('&');
            }
            form.append(name).append('=').append(URLEncoder.encode(value.toString(), StandardCharsets.UTF_8));
        }
    }

    private <T extends SlackJson.Result> CompletableFuture<T> post(
            final String method,
            final Object payload,
            final Supplier<String> tokenSupplier,
            final String prefix,
            final Class<T> responseType,
            final boolean formEncoded) {
        try {
            final var token = tokenSupplier.get();
            if (token == null
                    || !token.startsWith(prefix)
                    || !TOKEN_CHARACTERS.matcher(token).matches()) {
                return CompletableFuture.failedFuture(failure(0, "invalid_token", Optional.empty()));
            }
            final var body = (formEncoded
                            ? (String) Objects.requireNonNull(payload)
                            : json.toString(Objects.requireNonNull(payload)))
                    .getBytes(StandardCharsets.UTF_8);
            if (body.length > settings.maxRequestBytes()) {
                return CompletableFuture.failedFuture(failure(0, "request_too_large", Optional.empty()));
            }
            // Only constant method names reach this private method. No caller-controlled URLs or
            // response_url support.
            final var request = HttpRequest.newBuilder(settings.base().resolve(method))
                    .timeout(settings.requestTimeout())
                    .header("Authorization", "Bearer " + token)
                    .header(
                            "Content-Type",
                            formEncoded
                                    ? "application/x-www-form-urlencoded; charset=utf-8"
                                    : "application/json; charset=utf-8")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            final var metadata = new AtomicReference<HttpResponse.ResponseInfo>();
            final var exchange = client.sendAsync(request, info -> {
                metadata.set(info);
                return new BoundedBody(settings.maxResponseBytes());
            });
            final var result = new CompletableFuture<T>();
            exchange.whenComplete((response, error) -> {
                if (error != null) {
                    final var info = metadata.get();
                    if (info != null && (info.statusCode() < 200 || info.statusCode() >= 300)) {
                        // Even an oversized/erroring 429 body must not discard its scheduling metadata.
                        result.completeExceptionally(failure(
                                info.statusCode(),
                                info.statusCode() == 429 ? "ratelimited" : "http_error",
                                retryAfter(info.headers())));
                    } else {
                        var cause = error;
                        while (cause instanceof CompletionException && cause.getCause() != null) {
                            cause = cause.getCause();
                        }
                        result.completeExceptionally(
                                cause instanceof SlackApiException safe
                                        ? safe
                                        : failure(0, "transport_error", Optional.empty()));
                    }
                    return;
                }
                try {
                    result.complete(decode(response, responseType));
                } catch (final SlackApiException safe) {
                    result.completeExceptionally(safe);
                } catch (final RuntimeException unsafe) {
                    result.completeExceptionally(failure(response.statusCode(), "invalid_response", Optional.empty()));
                }
            });
            result.whenComplete((value, error) -> {
                if (result.isCancelled()) {
                    exchange.cancel(true);
                }
            });
            return result;
        } catch (final RuntimeException unsafe) {
            return CompletableFuture.failedFuture(failure(0, "request_error", Optional.empty()));
        }
    }

    private <T extends SlackJson.Result> T decode(final HttpResponse<byte[]> response, final Class<T> responseType) {
        final var status = response.statusCode();
        final var retry = retryAfter(response.headers());
        if (status == 429) {
            throw failure(status, "ratelimited", retry);
        }
        if (status < 200 || status >= 300) {
            throw failure(status, "http_error", retry);
        }
        final var body = json.fromString(responseType, new String(response.body(), StandardCharsets.UTF_8));
        if (body == null || body.ok() == null) {
            throw failure(status, "invalid_response", Optional.empty());
        }
        if (!body.ok()) {
            final var code = body.error();
            throw failure(status, code != null && SAFE_ERRORS.contains(code) ? code : "api_error", retry);
        }
        return body;
    }

    private Optional<Duration> retryAfter(final HttpHeaders headers) {
        try {
            final var header = headers.firstValue("Retry-After");
            if (header.isPresent()) {
                final var seconds = Long.parseLong(header.orElseThrow().strip());
                if (seconds >= 0) {
                    return Optional.of(Duration.ofSeconds(seconds));
                }
            }
        } catch (final RuntimeException ignored) {
            // Invalid server metadata is not a reason to leak the response or replay a publication.
        }
        return Optional.empty();
    }

    private SlackApiException failure(final int status, final String code, final Optional<Duration> retry) {
        return new SlackApiException(status, code, retry);
    }

    /** Sanitized failure metadata. A zero status means the remote outcome may be unknown. */
    public static final class SlackApiException extends RuntimeException {
        private final int status;
        private final String error;
        private final Optional<Duration> retryAfter;

        private SlackApiException(final int status, final String error, final Optional<Duration> retryAfter) {
            super("Slack API failure: " + error + " (HTTP " + status + ")");
            this.status = status;
            this.error = error;
            this.retryAfter = retryAfter;
        }

        public int status() {
            return status;
        }

        public String error() {
            return error;
        }

        public Optional<Duration> retryAfter() {
            return retryAfter;
        }
    }

    private final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private BoundedBody(final int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(final Flow.Subscription value) {
            if (subscription != null) {
                value.cancel();
                return;
            }
            subscription = value;
            value.request(1);
        }

        @Override
        public void onNext(final List<ByteBuffer> buffers) {
            for (final var buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(failure(0, "response_too_large", Optional.empty()));
                    return;
                }
                final var chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(final Throwable throwable) {
            result.completeExceptionally(failure(0, "transport_error", Optional.empty()));
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
