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
package io.yupiik.ai.mcp.client;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.mcp.model.ToolResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** Shared remote MCP semantics. The injected HTTP client is never owned by this class. */
abstract class RemoteMcpClient implements McpClient {
    // Bound each buffered response, not the lifetime of a persistent SSE connection.
    protected static final int MAX_RESPONSE_SIZE = 16 * 1024 * 1024;
    private static final int MAX_PAGES = 1_000;
    private static final int MAX_TOOLS = 100_000;
    protected final String name;
    protected final URI uri;
    protected final JsonMapper mapper;
    protected final ExtendedHttpClient client;
    protected final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
    protected volatile String sessionId;
    protected volatile String protocolVersion;
    private final String apiKey;
    private final List<String> headers;
    private final long timeoutSeconds;
    private final AtomicLong ids = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<Operation> operations = ConcurrentHashMap.newKeySet();
    private final ReentrantLock lock = new ReentrantLock();

    protected RemoteMcpClient(
            final String name,
            final String url,
            final String apiKey,
            final List<String> headers,
            final long timeoutSeconds,
            final JsonMapper mapper,
            final ExtendedHttpClient client) {
        this.name = name;
        this.uri = parseUri(url);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("Invalid MCP HTTP URL");
        }
        this.apiKey = apiKey;
        this.headers = headers == null ? List.of() : List.copyOf(headers);
        this.timeoutSeconds = timeoutSeconds;
        this.mapper = mapper;
        this.client = client;
    }

    private static URI parseUri(final String url) {
        try {
            return URI.create(url);
        } catch (final RuntimeException e) {
            throw new IllegalArgumentException("Invalid MCP HTTP URL");
        }
    }

    @Override
    public final void initialize() throws IOException, InterruptedException {
        operation(op -> {
            initialize(op);
            return null;
        });
    }

    private void initialize(final Operation op) throws IOException, InterruptedException {
        lock.lockInterruptibly();
        try {
            initializeLocked(op);
        } finally {
            lock.unlock();
        }
    }

    private void initializeLocked(final Operation op) throws IOException, InterruptedException {
        sessionId = null;
        protocolVersion = null;
        IOException last = null;
        for (final var candidate : SUPPORTED_PROTOCOL_VERSIONS) {
            final Object result;
            try {
                result = request(
                        op,
                        "initialize",
                        Map.of(
                                "protocolVersion",
                                candidate,
                                "capabilities",
                                Map.of(),
                                "clientInfo",
                                Map.of("name", "yca", "version", "1.0.0")));
            } catch (final McpHttpException e) {
                // Only a protocol rejection is a reason to try an older version, not auth/rate limiting.
                if (e.statusCode() != 400) {
                    throw e;
                }
                last = e;
                sessionId = null;
                continue;
            } catch (final RpcException e) {
                last = e;
                sessionId = null;
                continue;
            }
            if (!(result instanceof Map<?, ?> map)
                    || !(map.get("protocolVersion") instanceof String version)
                    || !SUPPORTED_PROTOCOL_VERSIONS.contains(version)) {
                throw new IOException("MCP " + name + ": unsupported or missing protocolVersion");
            }
            op.check();
            protocolVersion = version;
            notification(op, "notifications/initialized", null);
            return;
        }
        throw last == null ? new IOException("MCP initialize failed") : last;
    }

    @Override
    public final List<McpServerTool> listTools() throws IOException, InterruptedException {
        return operation(op -> {
            final var requestedSession = sessionId;
            try {
                return listTools(op);
            } catch (final McpHttpException e) {
                if (e.statusCode() != 404 || requestedSession == null) {
                    throw e;
                }
                recover(op, requestedSession);
                return listTools(op); // One session recovery, never an unbounded retry loop.
            }
        });
    }

    private List<McpServerTool> listTools(final Operation op) throws IOException, InterruptedException {
        final var tools = new ArrayList<McpServerTool>();
        final var cursors = new HashSet<String>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            final var result = request(op, "tools/list", cursor == null ? Map.of() : Map.of("cursor", cursor));
            for (final var descriptor : McpWire.tools(result)) {
                if (tools.size() >= MAX_TOOLS) {
                    throw new IOException("MCP " + name + ": too many tools");
                }
                tools.add(new McpServerTool(
                        descriptor.name(),
                        descriptor.description() == null ? "" : descriptor.description(),
                        descriptor.inputSchema()));
            }
            final var next = McpWire.nextCursor(result);
            if (next == null) {
                return List.copyOf(tools);
            }
            if (!cursors.add(next)) {
                throw new IOException("MCP " + name + ": invalid or repeated tools cursor");
            }
            cursor = next;
        }
        throw new IOException("MCP " + name + ": too many tools pages");
    }

    @Override
    public final String callTool(final String toolName, final Map<String, Object> arguments)
            throws IOException, InterruptedException {
        try {
            return operation(op -> {
                final Object result;
                final var requestedSession = sessionId;
                try {
                    result = request(op, "tools/call", Map.of("name", toolName, "arguments", arguments));
                } catch (final McpHttpException e) {
                    if (e.statusCode() == 404 && requestedSession != null) {
                        try {
                            recover(op, requestedSession);
                        } catch (final IOException recovery) {
                            e.addSuppressed(recovery);
                        }
                    }
                    throw e; // Never replay a potentially mutating call.
                }
                return McpWire.text(mapper, McpWire.payload(mapper, result, ToolResponse.class));
            });
        } catch (final McpFailure failure) {
            throw sanitize(failure);
        }
    }

    @Override
    public final <T> T call(final String method, final Map<String, Object> params, final Class<T> resultType)
            throws IOException, InterruptedException {
        try {
            return operation(op -> McpWire.payload(mapper, request(op, method, params), resultType));
        } catch (final McpFailure failure) {
            throw sanitize(failure);
        }
    }

    private McpFailure sanitize(final McpFailure failure) {
        final var credentials = new ArrayList<>(headers);
        if (uri.getRawQuery() != null) {
            for (final var parameter : uri.getRawQuery().split("&")) {
                credentials.add(parameter);
                credentials.add(URLDecoder.decode(parameter, StandardCharsets.UTF_8));
            }
        }
        return failure.sanitized(apiKey, credentials, sessionId);
    }

    private void recover(final Operation op, final String expiredSession) throws IOException, InterruptedException {
        lock.lockInterruptibly();
        try {
            if (expiredSession.equals(sessionId)) {
                initializeLocked(op);
            }
        } finally {
            lock.unlock();
        }
    }

    private Object request(final Operation op, final String method, final Map<String, Object> params)
            throws IOException, InterruptedException {
        final var id = ids.incrementAndGet();
        // MCP forbids cancelling initialization. Other requests can be cancelled best-effort.
        op.requestId = "initialize".equals(method) ? null : id;
        try {
            return exchange(op, message(id, method, params), id, "initialize".equals(method));
        } finally {
            op.requestId = null;
        }
    }

    protected abstract Object exchange(Operation op, Map<String, Object> message, Long id, boolean initialize)
            throws IOException, InterruptedException;

    protected abstract URI postUri() throws IOException;

    protected void notification(final Operation op, final String method, final Map<String, Object> params)
            throws IOException, InterruptedException {
        exchange(op, message(null, method, params), null, false);
    }

    protected final Map<String, Object> message(final Long id, final String method, final Map<String, Object> params) {
        final var message = new LinkedHashMap<String, Object>();
        message.put("jsonrpc", "2.0");
        if (id != null) {
            message.put("id", id);
        }
        message.put("method", method);
        if (params != null) {
            message.put("params", params);
        }
        return message;
    }

    protected final HttpRequest.Builder builder(final URI target, final Operation op) throws IOException {
        validateOrigin(target);
        final var builder = HttpRequest.newBuilder(target).header("accept", "application/json, text/event-stream");
        for (final var header : headers) {
            final var split = header.indexOf('=');
            if (split > 0) {
                builder.header(
                        header.substring(0, split).strip(),
                        header.substring(split + 1).strip());
            }
        }
        if (apiKey != null && !apiKey.isBlank()) {
            builder.setHeader("authorization", "Bearer " + apiKey);
        }
        if (sessionId != null) {
            builder.setHeader("Mcp-Session-Id", sessionId);
        }
        if (protocolVersion != null) {
            builder.setHeader("MCP-Protocol-Version", protocolVersion);
        }
        if (op.deadline != Long.MAX_VALUE) {
            builder.timeout(Duration.ofNanos(op.remaining()));
        }
        return builder;
    }

    protected final void validateOrigin(final URI target) throws IOException {
        if (target == null
                || target.getUserInfo() != null
                || target.getFragment() != null
                || !uri.getScheme().equalsIgnoreCase(target.getScheme())
                || !uri.getHost().equalsIgnoreCase(target.getHost())
                || port(uri) != port(target)) {
            throw new IOException("MCP " + name + ": cross-origin or invalid endpoint");
        }
    }

    private static int port(final URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    protected final HttpResponse<InputStream> send(final Operation op, final HttpRequest request)
            throws IOException, InterruptedException {
        op.check();
        final HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (final HttpTimeoutException e) {
            throw new HttpTimeoutException("MCP HTTP deadline exceeded");
        } catch (final IOException | RuntimeException e) {
            throw new IOException("MCP HTTP exchange failed");
        }
        op.add(response.body());
        validateOrigin(response.uri());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // Servers may echo URLs, headers, or bodies, including arbitrary secrets. Keep
            // status/retry semantics, but do not surface or drain an untrusted error body.
            op.release(response.body());
            throw new McpHttpException(
                    name,
                    response.statusCode(),
                    "[redacted]",
                    retryAfterMillis(
                            response.headers().firstValue("retry-after").orElse(null)));
        }
        return response;
    }

    protected final Map<?, ?> decode(final String json) throws IOException {
        return McpWire.decode(mapper, json);
    }

    protected final Object result(final Map<?, ?> message, final Long id) throws IOException {
        if (!sameId(message.get("id"), id)
                || message.containsKey("method")
                || message.containsKey("result") == message.containsKey("error")) {
            throw new IOException("MCP " + name + ": invalid JSON-RPC response ID or result/error");
        }
        if (message.containsKey("error")) {
            final var error = (Map<?, ?>) message.get("error");
            throw new RpcException(
                    "MCP request returned an error",
                    "JSON-RPC error " + error.get("code") + ": " + error.get("message"));
        }
        return message.get("result");
    }

    protected final boolean sameId(final Object actual, final Long expected) {
        return expected != null && expected.equals(McpWire.numericId(actual));
    }

    /** Returns true for server requests/notifications, posting a response for requests. */
    protected final boolean serverMessage(final Operation op, final Map<?, ?> message)
            throws IOException, InterruptedException {
        if (!(message.get("method") instanceof String method)) {
            return false;
        }
        if (message.containsKey("result") || message.containsKey("error")) {
            throw new IOException("MCP " + name + ": invalid server request");
        }
        if (message.containsKey("id")) {
            final var id = message.get("id");
            if (!(id instanceof String) && !(id instanceof Number)) {
                throw new IOException("MCP " + name + ": invalid server request ID");
            }
            final var reply = new LinkedHashMap<String, Object>();
            reply.put("jsonrpc", "2.0");
            reply.put("id", id);
            if ("ping".equals(method)) {
                reply.put("result", Map.of());
            } else {
                reply.put("error", Map.of("code", -32601, "message", "Method not found"));
            }
            postOnly(op, reply);
        }
        return true;
    }

    protected final void postOnly(final Operation op, final Map<String, Object> message)
            throws IOException, InterruptedException {
        final var response = send(
                op,
                builder(postUri(), op)
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.toBytes(message)))
                        .build());
        op.release(response.body());
    }

    private <T> T operation(final Work<T> work) throws IOException, InterruptedException {
        if (closed.get()) {
            throw new IOException("MCP " + name + " is closed");
        }
        return bounded(
                timeoutSeconds > 0 ? Duration.ofSeconds(timeoutSeconds) : null,
                op -> {
                    if (closed.get()) {
                        throw new IOException("MCP " + name + " is closed");
                    }
                    return work.run(op);
                },
                true);
    }

    protected final <T> T bounded(final Duration timeout, final Work<T> work, final boolean cancelNotification)
            throws IOException, InterruptedException {
        final var op = new Operation(timeout);
        operations.add(op);
        final Future<T> task;
        try {
            task = tasks.submit(() -> work.run(op));
        } catch (final RuntimeException e) {
            operations.remove(op);
            throw new IOException("MCP " + name + " is closed", e);
        }
        op.attach(task);
        try {
            return timeout == null ? task.get() : task.get(op.remaining(), TimeUnit.NANOSECONDS);
        } catch (final HttpTimeoutException e) {
            if (cancelNotification) {
                abortTransport();
            }
            throw e;
        } catch (final TimeoutException e) {
            if (cancelNotification) {
                abortTransport();
            }
            throw new HttpTimeoutException("MCP " + name + ": operation deadline exceeded");
        } catch (final InterruptedException e) {
            final var id = op.requestId;
            op.close();
            if (cancelNotification && id != null) {
                // The interrupted caller can send cancellation without reusing its cancelled operation.
                try {
                    bounded(
                            Duration.ofSeconds(1),
                            cancellation -> {
                                postOnly(
                                        cancellation,
                                        message(
                                                null,
                                                "notifications/cancelled",
                                                Map.of("requestId", id, "reason", "Client interrupted")));
                                return null;
                            },
                            false);
                } catch (final IOException | InterruptedException ignored) {
                    // Best effort; local cancellation must always happen.
                }
            }
            if (cancelNotification) {
                abortTransport();
            }
            throw e;
        } catch (final CancellationException e) {
            throw new IOException("MCP " + name + ": operation cancelled");
        } catch (final ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                if (cancelNotification && io instanceof HttpTimeoutException) {
                    abortTransport();
                }
                throw io;
            }
            if (e.getCause() instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new IOException("MCP " + name + ": request failed");
        } finally {
            op.close();
            operations.remove(op);
        }
    }

    protected void closeTransport() {}

    protected void abortTransport() {}

    protected final void track(final Operation op) throws IOException {
        operations.add(op);
        if (closed.get()) {
            op.close();
            operations.remove(op);
            throw new IOException("MCP " + name + " is closed");
        }
    }

    protected final void untrack(final Operation op) {
        operations.remove(op);
    }

    protected void terminate(final Operation op) throws IOException, InterruptedException {}

    @Override
    public final void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        final var pendingIds = operations.stream()
                .map(op -> op.requestId)
                .filter(Objects::nonNull)
                .toList();
        if (!pendingIds.isEmpty()) {
            try {
                bounded(
                        Duration.ofSeconds(1),
                        op -> {
                            for (final var id : pendingIds) {
                                postOnly(
                                        op,
                                        message(
                                                null,
                                                "notifications/cancelled",
                                                Map.of("requestId", id, "reason", "Session closed")));
                            }
                            return null;
                        },
                        false);
            } catch (final IOException ignored) {
                // Cancellation is advisory; always complete local teardown.
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        operations.forEach(Operation::close);
        closeTransport();
        if (sessionId != null) {
            try {
                bounded(
                        Duration.ofSeconds(1),
                        op -> {
                            terminate(op);
                            return null;
                        },
                        false);
            } catch (final IOException ignored) {
                // Session deletion is best effort.
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        tasks.shutdownNow();
    }

    private static Long retryAfterMillis(final String value) {
        if (value == null) {
            return null;
        }
        try {
            return Math.max(0, Math.multiplyExact(Long.parseLong(value.strip()), 1_000));
        } catch (final RuntimeException e) {
            try {
                return Math.max(
                        0,
                        DateTimeFormatter.RFC_1123_DATE_TIME
                                        .parse(value, Instant::from)
                                        .toEpochMilli()
                                - System.currentTimeMillis());
            } catch (final RuntimeException ignored) {
                try {
                    return Math.max(0, Instant.parse(value).toEpochMilli() - System.currentTimeMillis());
                } catch (final RuntimeException invalid) {
                    return null;
                }
            }
        }
    }

    @FunctionalInterface
    protected interface Work<T> {
        T run(Operation op) throws IOException, InterruptedException;
    }

    protected static final class Operation implements AutoCloseable {
        private final long deadline;
        private final Set<InputStream> streams = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Long requestId;
        private volatile Future<?> task;

        protected Operation(final Duration duration) {
            deadline = duration == null ? Long.MAX_VALUE : System.nanoTime() + duration.toNanos();
        }

        protected void attach(final Future<?> running) {
            task = running;
            if (cancelled.get()) {
                running.cancel(true); // Covers close racing task publication (also the SSE reader).
            }
        }

        private long remaining() throws HttpTimeoutException {
            final var remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new HttpTimeoutException("MCP operation deadline exceeded");
            }
            return remaining;
        }

        protected void check() throws IOException {
            if (cancelled.get()) {
                throw new IOException("MCP operation cancelled");
            }
            if (deadline != Long.MAX_VALUE) {
                remaining();
            }
        }

        private void add(final InputStream stream) throws IOException {
            streams.add(stream);
            if (cancelled.get()) {
                release(stream);
                throw new IOException("MCP operation cancelled");
            }
        }

        protected void release(final InputStream stream) {
            streams.remove(stream);
            try {
                stream.close();
            } catch (final IOException ignored) {
                // Cleanup only.
            }
        }

        @Override
        public void close() {
            cancelled.set(true);
            final var running = task;
            if (running != null) {
                running.cancel(true);
            }
            streams.forEach(this::release);
        }
    }

    private static final class RpcException extends McpFailure {
        private RpcException(final String message, final String detail) {
            super(message, detail);
        }
    }
}
