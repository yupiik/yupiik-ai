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
import io.yupiik.fusion.framework.build.api.json.JsonOthers;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Same-origin REST/GraphQL transport. Only GETs are retried; long rate limits return to the scheduler. */
public final class GitHubClient implements AutoCloseable {
    private static final Pattern TRAILING_SLASH = Pattern.compile("/$");
    private static final Pattern NEXT = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"next\"");
    private static final int RESPONSE_LIMIT = 8 * 1024 * 1024;
    private static final int CACHE_LIMIT = 8 * 1024 * 1024;
    private final HttpClient http;
    private final URI base;
    private final JsonMapper json;
    private final Clock clock;
    private final Supplier<String> token;

    /**
     * Host-only Git acquisition authentication, borrowed from this client's rotating credential owner.
     * Never serialize, log, or forward the value to an agent; the caller must restrict its Git origin.
     */
    public String sourceToken() {
        return token.get();
    }

    private final Map<String, Cached> cache = new LinkedHashMap<>();
    private final ReentrantLock pacing = new ReentrantLock();
    private final Duration requestInterval;
    private volatile Instant rateLimitUntil = Instant.EPOCH;
    private long previousRequest;
    private boolean requested;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong httpNanos = new AtomicLong();
    private final AtomicLong pacingNanos = new AtomicLong();

    /** Process-local transport totals, including retries and conditional requests, without request data. */
    public Statistics statistics() {
        return new Statistics(requests.get(), httpNanos.get(), pacingNanos.get());
    }

    public record Statistics(long requests, long httpNanos, long pacingNanos) {}

    public GitHubClient(final URI base, final JsonMapper json, final Supplier<String> token, final Clock clock) {
        this(base, json, token, Duration.ZERO, clock);
    }

    public GitHubClient(
            final URI base,
            final JsonMapper json,
            final Supplier<String> token,
            final Duration requestInterval,
            final Clock clock) {
        if (requestInterval.isNegative() || requestInterval.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("Request interval must be between zero and one minute");
        }
        this.requestInterval = requestInterval;
        if (base.getHost() == null
                || !("https".equalsIgnoreCase(base.getScheme()) || "http".equalsIgnoreCase(base.getScheme()))
                || base.getUserInfo() != null
                || base.getFragment() != null
                || base.getQuery() != null) {
            throw new IllegalArgumentException("Invalid GitHub API base");
        }
        if ("http".equalsIgnoreCase(base.getScheme())
                && !("127.0.0.1".equals(base.getHost())
                        || "[::1]".equals(base.getHost())
                        || "::1".equals(base.getHost()))) {
            throw new IllegalArgumentException("GitHub credentials require HTTPS except numeric loopback test servers");
        }
        this.base = base;
        this.json = Objects.requireNonNull(json);
        this.clock = Objects.requireNonNull(clock);
        this.token = Objects.requireNonNull(token);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public <T> T get(final String path, final Class<T> responseType) {
        return get(path, (Type) responseType);
    }

    public <T> T get(final String path, final Type responseType) {
        return this.<T>page(path, responseType).body();
    }

    private void pace() {
        pacing.lock();
        try {
            if (rateLimitUntil.isAfter(clock.instant())) {
                throw new DeferredRequestException(429, rateLimitUntil);
            }
            if (requested) {
                final long remaining = requestInterval.toNanos() - (System.nanoTime() - previousRequest);
                if (remaining > 0) {
                    pause(Duration.ofNanos(remaining));
                }
            }
            previousRequest = System.nanoTime();
            requested = true;
        } finally {
            pacing.unlock();
        }
    }

    public <T> T post(final String path, final MutationRequest body, final Class<T> responseType) {
        return post(path, body, (Type) responseType);
    }

    public <T> T post(final String path, final MutationRequest body, final Type responseType) {
        return this.<T>request("POST", path, body, responseType).body();
    }

    /** Decodes a complete envelope only after checking HTTP-200 partial errors, including errors:null. */
    public <T> T graphql(final String query, final Object variables, final Class<T> responseType) {
        return graphql(query, variables, (Type) responseType);
    }

    public <T> T graphql(final String query, final Object variables, final Type responseType) {
        return this.<T>request("POST", toGraphQlPath(), new GraphqlRequest(query, variables), responseType, true)
                .body();
    }

    public DiscussionClient.Envelope discussionGraphql(final String query, final DiscussionClient.Variables variables) {
        final var path = toGraphQlPath();
        final var envelope = post(path, new GraphqlRequest(query, variables), DiscussionClient.Envelope.class);
        if (envelope == null
                || (envelope.extensions() != null && envelope.extensions().containsKey("errors"))
                || envelope.data() == null) {
            throw new DiscussionGraphqlException();
        }
        return envelope;
    }

    public <T> Page<T> page(final String path, final Class<T> responseType) {
        return page(path, (Type) responseType);
    }

    public <T> Page<T> page(final String path, final Type responseType) {
        return request("GET", path, null, responseType);
    }

    private String toGraphQlPath() {
        return "/api/v3".equals(TRAILING_SLASH.matcher(base.getPath()).replaceAll(""))
                ? base.resolve("/api/graphql").toString()
                : "/graphql";
    }

    private URI uri(final String path) {
        final var candidate = URI.create(path);
        final var uri = candidate.isAbsolute() || candidate.getRawAuthority() != null
                ? base.resolve(candidate)
                : URI.create(TRAILING_SLASH.matcher(base.toString()).replaceAll("")
                        + (path.startsWith("/") ? path : "/" + path));
        if (!base.getScheme().equalsIgnoreCase(uri.getScheme())
                || !base.getHost().equalsIgnoreCase(uri.getHost())
                || port(base) != port(uri)
                || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("Cross-origin GitHub request refused");
        }
        return uri;
    }

    private static int port(final URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
    }

    private <T> Page<T> request(
            final String method, final String path, final MutationRequest body, final Type responseType) {
        return request(method, path, body, responseType, false);
    }

    private <T> Page<T> request(
            final String method,
            final String path,
            final MutationRequest body,
            final Type responseType,
            final boolean graphql) {
        Objects.requireNonNull(responseType, "responseType");
        final var uri = uri(path);
        final var cacheKey = uri.toString();
        final boolean get = "GET".equals(method);
        boolean refreshed = false;
        for (int attempt = 0; ; attempt++) {
            final var credential = token.get();
            if (credential == null || credential.isBlank()) {
                throw new IllegalStateException("Missing GitHub credential");
            }
            final Cached cached;
            synchronized (cache) {
                cached = get ? cache.get(cacheKey) : null;
            }
            final var request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(90))
                    .header("Authorization", "Bearer " + credential)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "yca-github-bot")
                    .method(
                            method,
                            body == null
                                    ? HttpRequest.BodyPublishers.noBody()
                                    : HttpRequest.BodyPublishers.ofString(json.toString(body)));
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            if (cached != null && cached.credential().equals(credential)) {
                request.header("If-None-Match", cached.etag());
            }
            try {
                final var pacingStart = System.nanoTime();
                try {
                    pace();
                } finally {
                    pacingNanos.addAndGet(System.nanoTime() - pacingStart);
                }
                final var built = request.build();
                final var httpStart = System.nanoTime();
                requests.incrementAndGet();
                final HttpResponse<String> response;
                try {
                    response = http.send(built, info -> new BoundedBodySubscriber());
                } finally {
                    httpNanos.addAndGet(System.nanoTime() - httpStart);
                }
                final int status = response.statusCode();
                if (status == 401
                        && get
                        && !refreshed
                        && token instanceof AuthTokenProvider provider
                        && provider.invalidate(credential)) {
                    refreshed = true;
                    continue;
                }
                if (status == 304 && cached != null && cached.credential().equals(credential)) {
                    return decode(cached.page(), responseType);
                }
                if (status < 200 || status >= 300) {
                    final boolean limited = status == 429
                            || (status == 403
                                    && (response.headers()
                                                    .firstValue("Retry-After")
                                                    .isPresent()
                                            || response.headers()
                                                    .firstValue("X-RateLimit-Remaining")
                                                    .orElse("")
                                                    .equals("0")
                                            || response.body()
                                                    .toLowerCase(Locale.ROOT)
                                                    .contains("rate limit")));
                    final boolean retryable =
                            limited || status == 502 || status == 503 || status == 504 || status == 500;
                    if (limited) {
                        final var delay = retryDelay(response, attempt, true);
                        final Instant until;
                        try {
                            until = clock.instant().plus(delay);
                        } catch (final DateTimeException | ArithmeticException overflow) {
                            throw new DeferredRequestException(status, Instant.MAX);
                        }
                        pacing.lock();
                        try {
                            if (until.isAfter(rateLimitUntil)) {
                                rateLimitUntil = until;
                            }
                        } finally {
                            pacing.unlock();
                        }
                    }
                    if (get && retryable) {
                        final var delay = retryDelay(response, attempt, limited);
                        final Instant retryAt;
                        try {
                            retryAt = clock.instant().plus(delay);
                        } catch (final DateTimeException | ArithmeticException e) {
                            throw new DeferredRequestException(status, Instant.MAX);
                        }
                        if (attempt >= 2 || delay.compareTo(Duration.ofSeconds(1)) > 0) {
                            throw new DeferredRequestException(status, retryAt);
                        }
                        pause(delay);
                        continue;
                    }
                    throw new HttpException(
                            status,
                            "GitHub " + method + " returned " + status
                                    + (get ? "" : "; publication status may require reconciliation"));
                }
                String next = null;
                for (final var header : response.headers().allValues("Link")) {
                    final var matcher = NEXT.matcher(header);
                    if (matcher.find()) {
                        next = uri(uri.resolve(matcher.group(1)).toString()).toString();
                    }
                }
                final var raw =
                        new Page<>(response.body(), next, response.body().getBytes(StandardCharsets.UTF_8).length);
                if (graphql) {
                    try {
                        final var statusEnvelope = json.fromString(GraphqlStatus.class, response.body());
                        synchronized (cache) {
                            cache.clear();
                        }
                        if (statusEnvelope == null
                                || !(statusEnvelope.data() instanceof Map<?, ?>)
                                || (statusEnvelope.extensions() != null
                                        && statusEnvelope.extensions().containsKey("errors"))) {
                            throw new IllegalStateException("Invalid envelope");
                        }
                        return decode(raw, responseType);
                    } catch (final RuntimeException invalid) {
                        throw new IllegalStateException("GitHub GraphQL request failed or returned partial data");
                    }
                }
                final Page<T> page = decode(raw, responseType);
                if (get) {
                    synchronized (cache) {
                        cache.remove(cacheKey);
                        response.headers()
                                .firstValue("ETag")
                                .ifPresent(etag -> cache.put(
                                        cacheKey,
                                        new Cached(
                                                etag,
                                                credential,
                                                raw,
                                                response.body().getBytes(StandardCharsets.UTF_8).length)));
                        while (cache.size() > 256
                                || cache.values().stream()
                                                .mapToLong(Cached::bytes)
                                                .sum()
                                        > CACHE_LIMIT) {
                            cache.remove(cache.keySet().iterator().next());
                        }
                    }
                } else {
                    synchronized (cache) {
                        cache.clear();
                    }
                }
                return page;
            } catch (final IOException e) {
                for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                    if (cause instanceof ResponseLimitException) {
                        throw new IllegalStateException("GitHub response exceeds transport byte limit", e);
                    }
                }
                if (get && attempt < 2) {
                    pause(Duration.ofMillis(100L << attempt));
                    continue;
                }
                throw new IllegalStateException(
                        get ? "GitHub transport failure" : "GitHub transport failure; mutation outcome unknown", e);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("GitHub request interrupted", e);
            }
        }
    }

    private Duration retryDelay(final HttpResponse<?> response, final int attempt, final boolean limited) {
        Duration delay = Duration.ofMillis(100L << attempt);
        boolean specified = false;
        final var retry = response.headers().firstValue("Retry-After");
        if (retry.isPresent()) {
            try {
                delay = Duration.ofSeconds(Math.max(0, Long.parseLong(retry.get())));
                specified = true;
            } catch (final RuntimeException ignored) {
                try {
                    delay = Duration.between(
                            clock.instant(),
                            ZonedDateTime.parse(retry.get(), DateTimeFormatter.RFC_1123_DATE_TIME)
                                    .toInstant());
                    specified = true;
                } catch (final RuntimeException invalid) {
                    /* Fall back conservatively below. */
                }
            }
        }
        if (response.headers().firstValue("X-RateLimit-Remaining").orElse("").equals("0")) {
            try {
                final var reset = Duration.between(
                        clock.instant(),
                        Instant.ofEpochSecond(Long.parseLong(response.headers()
                                .firstValue("X-RateLimit-Reset")
                                .orElseThrow())));
                if (!specified || reset.compareTo(delay) > 0) {
                    delay = reset;
                }
                specified = true;
            } catch (final RuntimeException ignored) {
                /* Missing or malformed reset. */
            }
        }
        if (limited && !specified) {
            return Duration.ofMinutes(1);
        }
        return delay.isNegative() ? Duration.ZERO : delay;
    }

    private static void pause(final Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub retry interrupted", e);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                http.close();
            } finally {
                if (token instanceof AuthTokenProvider provider) {
                    provider.close();
                }
            }
        }
    }

    public sealed interface MutationRequest
            permits CommentRequest,
                    ReviewRequest,
                    ForkRequest,
                    AuthTokenProvider.InstallationTokenRequest,
                    BlobRequest,
                    TreeRequest,
                    CommitRequest,
                    RefRequest,
                    PullRequestRequest,
                    GraphqlRequest {}

    // Transport-only preflight: errors must be rejected even when present as null, before
    // decoding an operation-specific typed envelope. The raw data is never returned to callers.
    @JsonModel
    public record GraphqlStatus(Object data, @JsonOthers Map<String, Object> extensions) {}

    @JsonModel
    public record GraphqlRequest(
            @Property(documentation = "Fixed host-owned GraphQL operation.")
            String query,

            @Property(documentation = "Untrusted values bound separately from the operation.")
            Object variables)
            implements MutationRequest {}

    @JsonModel
    public record BlobRequest(
            @Property(documentation = "Base64 encoded regular file contents.")
            String content,

            @Property(documentation = "Content encoding, base64.")
            String encoding)
            implements MutationRequest {}

    @JsonModel
    public record TreeRequest(
            @Property(documentation = "Authoritative base commit's tree SHA.")
            String base_tree,

            @Property(documentation = "Validated regular file updates and deletions.")
            List<TreeEntry> tree)
            implements MutationRequest {}

    @JsonModel
    public record TreeEntry(
            @Property(documentation = "Safe repository-relative file path.")
            String path,

            @Property(documentation = "Regular file mode, 100644 or preserved 100755.")
            String mode,

            @Property(documentation = "Git object type, blob.")
            String type,

            @Property(documentation = "Blob SHA, or explicit JSON null to delete the file.")
            String sha) {}

    @JsonModel
    public record CommitRequest(
            @Property(documentation = "Commit message.") String message,
            @Property(documentation = "New tree SHA.") String tree,

            @Property(documentation = "Single authoritative base commit SHA.")
            List<String> parents)
            implements MutationRequest {}

    @JsonModel
    public record RefRequest(
            @Property(documentation = "Frozen fully qualified branch reference, refs/heads/github-bot/task-id.")
            String ref,

            @Property(documentation = "Published commit SHA.")
            String sha)
            implements MutationRequest {}

    @JsonModel
    public record PullRequestRequest(
            @Property(documentation = "Validated pull request title.")
            String title,

            @Property(documentation = "Pull request Markdown including the durable task marker.")
            String body,

            @Property(documentation = "Exact fork owner and frozen branch, owner:branch.")
            String head,

            @Property(documentation = "Fork repository name for cross-repository pull requests.")
            String head_repo,

            @Property(documentation = "Authoritative upstream base branch.")
            String base,

            @Property(documentation = "Whether upstream maintainers may modify the head branch.")
            boolean maintainer_can_modify)
            implements MutationRequest {}

    @JsonModel
    public record CommentRequest(
            @Property(documentation = "Issue comment Markdown body.")
            String body) implements MutationRequest {}

    @JsonModel
    public record ForkRequest(
            @Property(documentation = "Destination organization, not a personal account.")
            String organization) implements MutationRequest {}

    @JsonModel
    public record ReviewRequest(
            @Property(documentation = "Validated pull request head SHA.")
            String commit_id,

            @Property(documentation = "Native review event, COMMENT.")
            String event,

            @Property(documentation = "Task marker and review summary.")
            String body,

            @Property(documentation = "Validated inline findings.")
            List<ReviewFinding> comments)
            implements MutationRequest {}

    @JsonModel
    public record ReviewFinding(
            @Property(documentation = "Exact changed file path.")
            String path,

            @Property(documentation = "Diff side, LEFT or RIGHT.")
            String side,

            @Property(documentation = "Validated diff line number.")
            int line,

            @Property(documentation = "Finding Markdown body.")
            String body) {}

    private <T> Page<T> decode(final Page<String> raw, final Type responseType) {
        final T body = raw.body().isBlank() ? null : json.fromString(responseType, raw.body());
        return new Page<>(body, raw.next(), raw.responseBytes());
    }

    public record Page<T>(T body, String next, int responseBytes) {}

    private record Cached(String etag, String credential, Page<String> page, int bytes) {}

    private static final class ResponseLimitException extends IOException {
        private ResponseLimitException() {
            super("GitHub response exceeds transport byte limit");
        }
    }

    /** Applies backpressure and cancels before handing oversized chunks to the buffering subscriber. */
    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<String> {
        private final HttpResponse.BodySubscriber<String> delegate =
                HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        private Flow.Subscription subscription;
        private long received;
        private boolean done;

        @Override
        public CompletionStage<String> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(final Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(final List<ByteBuffer> buffers) {
            if (done) {
                return;
            }
            for (final var buffer : buffers) {
                received += buffer.remaining();
            }
            if (received > RESPONSE_LIMIT) {
                done = true;
                subscription.cancel();
                delegate.onError(new ResponseLimitException());
                return;
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(final Throwable error) {
            if (!done) {
                done = true;
                delegate.onError(error);
            }
        }

        @Override
        public void onComplete() {
            if (!done) {
                done = true;
                delegate.onComplete();
            }
        }
    }

    /** Sanitized Discussion GraphQL errors never identify an inaccessible node as deleted. */
    public static final class DiscussionGraphqlException extends IllegalStateException {
        private DiscussionGraphqlException() {
            super("GitHub GraphQL request failed or returned partial data");
        }
    }

    /** An HTTP error response, distinct from transport or JSON failures. */
    public static class HttpException extends IllegalStateException {
        private final int status;

        private HttpException(final int status, final String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** GET was not completed; callers can schedule a later attempt, never replay a POST. */
    public static final class DeferredRequestException extends HttpException {
        private final Instant retryAt;

        private DeferredRequestException(final int status, final Instant retryAt) {
            super(status, "GitHub GET deferred after HTTP " + status + " until " + retryAt);
            this.retryAt = retryAt;
        }

        public Instant retryAt() {
            return retryAt;
        }
    }
}
