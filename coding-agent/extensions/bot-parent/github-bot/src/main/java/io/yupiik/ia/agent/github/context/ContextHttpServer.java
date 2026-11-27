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

import static io.yupiik.fusion.framework.build.api.http.HttpMatcher.PathMatching.STARTS_WITH;

import com.sun.net.httpserver.HttpExchange;
import io.yupiik.fusion.framework.build.api.http.HttpMatcher;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.http.server.api.Response;
import io.yupiik.fusion.http.server.spi.Endpoint;
import io.yupiik.fusion.httpserver.jre.JreHttpServer;
import io.yupiik.fusion.httpserver.jre.JreHttpServerConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.github.configuration.ContextConfiguration;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Explicitly owned, isolated JRE listener. TLS and per-worker routing belong to the deployment. */
public final class ContextHttpServer implements AutoCloseable {
    private static final Pattern GIT_SERVICE_PATH =
            Pattern.compile("[A-Za-z0-9-]{1,80}\\.git/(info/refs|git-upload-pack)");
    private static final Pattern CAPABILITY_TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern CONTENT_LENGTH = Pattern.compile("[0-9]{1,10}");

    public static final String CONTROL_TOKEN_ENV = "YCA_CONTEXT_TOKEN";
    public static final String GIT_TOKEN_ENV = "YCA_CONTEXT_GIT_TOKEN";
    private final ContextService service;
    private final JreHttpServer server;
    private final JsonMapper json;
    private final String path = "/context/" + UUID.randomUUID();
    private final String advertised;
    private final byte[] controlHash;
    private final byte[] gitHash;
    private volatile Map<String, String> credentials;
    private final Semaphore concurrent = new Semaphore(4);
    private final Set<RequestLease> active = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("context-http-deadlines").factory());
    private final AtomicBoolean closed = new AtomicBoolean();

    public ContextHttpServer(final ContextConfiguration conf, final ContextService service, final JsonMapper json) {
        if (conf.advertised().isBlank()) {
            throw new IllegalArgumentException(
                    "Broker mode requires github.context.advertised HTTPS origin and explicit sandbox routing");
        }
        this.service = service;
        this.json = json;
        advertised = ContextConfiguration.origin(conf.advertised()).toASCIIString();
        final var control = token();
        final var git = token();
        controlHash = hash(control);
        gitHash = hash(git);
        credentials = Map.of(CONTROL_TOKEN_ENV, control, GIT_TOKEN_ENV, git);
        try {
            // Do not discover all DI endpoints: only this explicitly constructed context endpoint is served.
            server = new JreHttpServer(
                    new JreHttpServerConfiguration(conf.bind(), conf.port(), true),
                    List.of(Endpoint.of(
                            request -> request.path() != null && request.path().startsWith("/context/"),
                            request -> CompletableFuture.completedFuture(handle(request)),
                            1000)));
            server.start();
            timer.scheduleWithFixedDelay(this::checkAuthority, 100, 100, TimeUnit.MILLISECONDS);
        } catch (final RuntimeException failure) {
            timer.shutdownNow();
            credentials = Map.of();
            throw failure;
        }
    }

    private void checkAuthority() {
        if (closed.get()) {
            return;
        }
        try {
            service.check();
        } catch (final RuntimeException unavailable) {
            // Fail closed even for an unexpected check failure: never silently lose the watchdog.
            try {
                revoke();
            } catch (final RuntimeException cleanupFailure) {
                // The owner must still close this server and retry bounded private-store cleanup.
                // Never log exception messages, paths, process diagnostics or credentials here.
            }
        }
    }

    /** Secret values travel only through the backend's allowlisted private environment/Secret transport. */
    public Map<String, String> credentials() {
        return credentials;
    }

    public String endpoint() {
        return advertised + path + "/rpc";
    }

    public int port() {
        return server.port();
    }

    public String route() {
        return path;
    }

    @HttpMatcher(path = "/context/", pathMatching = STARTS_WITH)
    public Response handle(final Request request) {
        if (closed.get()) {
            return failure(401, "CONTEXT_REVOKED");
        }
        final boolean control = request.path().equals(path + "/rpc");
        final boolean git = request.path().startsWith(path + "/git/");
        if ((!control && !git) || !authenticate(request.header("authorization"), control)) {
            return Response.of()
                    .status(401)
                    .header("www-authenticate", control ? "Bearer" : "Basic realm=\"context\"")
                    .header("cache-control", "no-store")
                    .body("Unauthorized")
                    .build();
        }
        // Authentication precedes body reading, JSON parsing, CGI dispatch and any upstream operation.
        try {
            service.check();
        } catch (final IllegalStateException expired) {
            return failure(401, "CONTEXT_REVOKED_OR_EXPIRED");
        }
        if (!headers(request) || request.header("origin") != null) {
            return failure(400, "INVALID_CONTEXT_HEADERS");
        }
        if (!concurrent.tryAcquire()) {
            return failure(429, "CONTEXT_BUSY");
        }
        final var exchange = request.unwrapOrNull(HttpExchange.class);
        final var lease = new RequestLease(exchange);
        active.add(lease);
        try {
            lease.start();
            if (control) {
                if (!"POST".equals(request.method()) || request.query() != null) {
                    return failure(405, "CONTEXT_POST_REQUIRED");
                }
                if (!"application/json".equals(mediaType(request.header("content-type")))
                        || request.header("content-encoding") != null) {
                    return failure(415, "CONTEXT_JSON_REQUIRED");
                }
                final var bytes = body(request, 16 * 1024);
                final Object parsed;
                try {
                    parsed = json.fromString(Object.class, new String(bytes, StandardCharsets.UTF_8));
                } catch (final RuntimeException malformed) {
                    return failure(400, "CONTEXT_JSON_INVALID");
                }
                if (!(parsed instanceof Map<?, ?> map)
                        || map.keySet().stream().anyMatch(it -> !(it instanceof String))) {
                    return failure(400, "CONTEXT_OBJECT_REQUIRED");
                }
                @SuppressWarnings("unchecked")
                final var input = (Map<String, Object>) map;
                final var response = service.call(input);
                return Response.of()
                        .status(200)
                        .header("content-type", "application/json;charset=utf-8")
                        .header("cache-control", "no-store")
                        .body(json.toString(new RpcResponse(true, response.get("body"))))
                        .build();
            }
            return git(request, lease);
        } catch (final IllegalArgumentException invalid) {
            // Only policy classifications are returned; never exception messages from clients/processes.
            return failure(400, "CONTEXT_REQUEST_REJECTED");
        } catch (final IOException | IllegalStateException unavailable) {
            return failure(409, "CONTEXT_UNAVAILABLE_OR_LIMITED");
        } finally {
            if (!lease.streaming) {
                lease.release();
            }
        }
    }

    private Response git(final Request request, final RequestLease lease) throws IOException {
        final var suffix = request.path().substring((path + "/git/").length());
        if (!GIT_SERVICE_PATH.matcher(suffix).matches()) {
            return failure(404, "UNKNOWN_GIT_ROUTE");
        }
        final var generation = suffix.substring(0, suffix.indexOf(".git/"));
        final boolean discovery = suffix.endsWith("/info/refs");
        if ((discovery && (!"GET".equals(request.method()) || !"service=git-upload-pack".equals(request.query())))
                || (!discovery && (!"POST".equals(request.method()) || request.query() != null))) {
            return failure(405, "GIT_READ_ONLY");
        }
        if (!discovery && !"application/x-git-upload-pack-request".equals(mediaType(request.header("content-type")))) {
            return failure(415, "GIT_UPLOAD_PACK_REQUIRED");
        }
        final var protocol = request.header("git-protocol");
        if (protocol != null && !List.of("version=1", "version=2").contains(protocol)) {
            return failure(400, "UNSUPPORTED_GIT_PROTOCOL");
        }
        byte[] body = discovery ? new byte[0] : body(request, 64 * 1024);
        final var encoding = request.header("content-encoding");
        if (encoding != null) {
            if (!"gzip".equals(encoding) || discovery) {
                return failure(415, "UNSUPPORTED_GIT_ENCODING");
            }
            try (final var input = new GZIPInputStream(new ByteArrayInputStream(body))) {
                body = bounded(input, 64 * 1024);
            }
        }
        final var result = service.gitStream(generation, request.method(), request.query(), body, protocol);
        lease.bind(result);
        final var response = Response.of().status(result.status()).header("cache-control", "no-store");
        result.headers().forEach((key, value) -> {
            if (List.of("content-type", "expires", "pragma").contains(key.toLowerCase(Locale.ROOT))) {
                response.header(key, value);
            }
        });
        final var built = response.bytesBody(out -> {
                    try (result) {
                        final var buffer = new byte[8192];
                        int length;
                        while (true) {
                            lease.check();
                            service.check();
                            length = result.body().read(buffer);
                            if (length < 0) {
                                break;
                            }
                            out.write(buffer, 0, length);
                        }
                    } catch (final IOException | RuntimeException unavailable) {
                        throw new IOException("Context transfer unavailable");
                    } finally {
                        lease.release();
                    }
                })
                .build();
        lease.streaming = true;
        return built;
    }

    private final class RequestLease {
        private final HttpExchange exchange;
        private final AtomicBoolean released = new AtomicBoolean();
        private volatile ScheduledFuture<?> deadline;
        private volatile AutoCloseable resource;
        private volatile boolean streaming;

        private RequestLease(final HttpExchange exchange) {
            this.exchange = exchange;
        }

        private void start() {
            if (closed.get()) {
                abort();
                throw new IllegalStateException("Context listener closed");
            }
            deadline = timer.schedule(this::abort, 150, TimeUnit.SECONDS);
            if (released.get()) {
                deadline.cancel(false);
            }
        }

        private void bind(final AutoCloseable resource) throws IOException {
            this.resource = resource;
            if (released.get()) {
                try {
                    resource.close();
                } catch (final Exception ignored) {
                    // The attempt's private storage retains cleanup ownership too.
                }
                throw new IOException("Context transfer admission expired");
            }
        }

        private void check() throws IOException {
            if (released.get() || closed.get()) {
                throw new IOException("Context transfer admission expired");
            }
        }

        private void abort() {
            try {
                if (exchange != null) {
                    exchange.close();
                }
            } finally {
                release();
            }
        }

        private void release() {
            if (released.compareAndSet(false, true)) {
                final var scheduled = deadline;
                if (scheduled != null) {
                    scheduled.cancel(false);
                }
                final var owned = resource;
                if (owned != null) {
                    try {
                        owned.close();
                    } catch (final Exception ignored) {
                        // Do not expose filesystem paths or process diagnostics on transport cancellation.
                    }
                }
                active.remove(this);
                concurrent.release();
            }
        }
    }

    private boolean authenticate(final String authorization, final boolean control) {
        if (authorization == null || authorization.length() > 256) {
            return false;
        }
        final String token;
        if (control) {
            if (!authorization.startsWith("Bearer ")) {
                return false;
            }
            token = authorization.substring(7);
        } else {
            if (!authorization.startsWith("Basic ")) {
                return false;
            }
            final String decoded;
            try {
                decoded = new String(Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.US_ASCII);
            } catch (final IllegalArgumentException malformed) {
                return false;
            }
            if (!decoded.startsWith("context:")) {
                return false;
            }
            token = decoded.substring(8);
        }
        return CAPABILITY_TOKEN.matcher(token).matches()
                && MessageDigest.isEqual(hash(token), control ? controlHash : gitHash);
    }

    private static boolean headers(final Request request) {
        long size = 0;
        if (request.headers().size() > 64
                || request.path().length() > 512
                || (request.query() != null && request.query().length() > 128)) {
            return false;
        }
        for (final var entry : request.headers().entrySet()) {
            size += entry.getKey().length();
            for (final var value : entry.getValue()) {
                size += value.length();
            }
            if (size > 16 * 1024) {
                return false;
            }
        }
        return true;
    }

    private static byte[] body(final Request request, final int max) throws IOException {
        final var length = request.header("content-length");
        if (length != null && (!CONTENT_LENGTH.matcher(length).matches() || Long.parseLong(length) > max)) {
            throw new IOException("Oversized context request");
        }
        final var input = request.unwrapOrNull(InputStream.class);
        if (input == null) {
            throw new IOException("JRE request input unavailable");
        }
        return bounded(input, max);
    }

    private static byte[] bounded(final InputStream input, final int max) throws IOException {
        final var bytes = input.readNBytes(max + 1);
        if (bytes.length > max) {
            throw new IOException("Oversized context request");
        }
        return bytes;
    }

    private static String mediaType(final String value) {
        return value == null ? "" : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private Response failure(final int status, final String code) {
        return Response.of()
                .status(status)
                .header("content-type", "application/json;charset=utf-8")
                .header("cache-control", "no-store")
                .body(json.toString(new RpcFailure(false, code)))
                .build();
    }

    /** Operation-specific context payloads deliberately remain dynamic within the stable envelope. */
    @JsonModel
    public record RpcResponse(boolean ok, Object body) {}

    @JsonModel
    public record RpcFailure(boolean ok, String error) {}

    private static String token() {
        final var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] hash(final String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void revoke() {
        closed.set(true);
        credentials = Map.of();
        RuntimeException failure = null;
        for (final var lease : active) {
            try {
                lease.abort();
            } catch (final RuntimeException abortFailure) {
                if (failure == null) {
                    failure = abortFailure;
                } else {
                    failure.addSuppressed(abortFailure);
                }
            }
        }
        // Retrying close must retry private storage cleanup after a previous bounded close failure.
        try {
            service.close();
        } catch (final RuntimeException cleanupFailure) {
            if (failure == null) {
                failure = cleanupFailure;
            } else {
                failure.addSuppressed(cleanupFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void close() {
        try {
            revoke();
        } finally {
            // Only the owner stops the listener: stopping it on a service/request thread can deadlock.
            try {
                server.close();
            } finally {
                timer.shutdownNow();
            }
        }
    }
}
