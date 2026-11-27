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
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

/** Legacy HTTP+SSE: one persistent GET stream and a same-origin advertised POST endpoint. */
public class SseMcpClient extends RemoteMcpClient {
    private final Map<Long, CompletableFuture<Map<?, ?>>> pending = new ConcurrentHashMap<>();
    private final ReentrantLock connectionLock = new ReentrantLock();
    private volatile URI endpoint;
    private volatile CompletableFuture<URI> endpointReady;
    private volatile Operation stream;
    private volatile Future<?> readerTask;
    private volatile IOException streamFailure;

    public SseMcpClient(
            final String name,
            final String url,
            final List<String> headers,
            final long timeoutSeconds,
            final JsonMapper mapper,
            final ExtendedHttpClient client) {
        super(name, url, null, headers, timeoutSeconds, mapper, client);
    }

    @Override
    protected URI postUri() throws IOException {
        final var target = endpoint;
        if (target == null) {
            throw new IOException("MCP " + name + ": SSE endpoint unavailable");
        }
        return target;
    }

    private void connect() throws IOException, InterruptedException {
        final CompletableFuture<URI> ready;
        connectionLock.lockInterruptibly();
        try {
            if (endpointReady == null) {
                final var connection = new Operation(null);
                track(connection);
                ready = new CompletableFuture<>();
                endpointReady = ready;
                stream = connection;
                try {
                    readerTask = tasks.submit(() -> read(connection, ready));
                    connection.attach(readerTask);
                } catch (final RuntimeException e) {
                    connection.close();
                    untrack(connection);
                    throw new IOException("MCP SSE stream unavailable");
                }
            } else {
                ready = endpointReady;
            }
        } finally {
            connectionLock.unlock();
        }
        await(ready); // Never hold the connection lock while waiting for network or pipe I/O.
        final var failure = streamFailure;
        if (failure != null) {
            throw failure;
        }
    }

    private void read(final Operation connection, final CompletableFuture<URI> ready) {
        try {
            final var response = send(
                    connection,
                    builder(uri, connection)
                            .setHeader("accept", "text/event-stream")
                            .GET()
                            .build());
            if (!response.headers()
                    .firstValue("content-type")
                    .orElse("")
                    .split(";", 2)[0]
                    .strip()
                    .equalsIgnoreCase("text/event-stream")) {
                throw new IOException("MCP " + name + ": expected SSE content type");
            }
            final var reader = new McpSseReader(response.body(), MAX_RESPONSE_SIZE);
            McpSseReader.Event event;
            while ((event = reader.next()) != null) {
                connection.check();
                if ("endpoint".equals(event.type())) {
                    final URI target;
                    try {
                        target = uri.resolve(event.data());
                    } catch (final IllegalArgumentException e) {
                        throw new IOException("MCP " + name + ": invalid SSE endpoint");
                    }
                    validateOrigin(target);
                    connectionLock.lock();
                    try {
                        connection.check();
                        if (stream != connection || endpoint != null) {
                            throw new IOException("MCP SSE endpoint unavailable or duplicated");
                        }
                        endpoint = target;
                        ready.complete(target);
                    } finally {
                        connectionLock.unlock();
                    }
                } else if ("message".equals(event.type())) {
                    if (endpoint == null) {
                        throw new IOException("MCP " + name + ": message before endpoint");
                    }
                    final var incoming = decode(event.data());
                    if (incoming.get("method") instanceof String) {
                        bounded(
                                Duration.ofSeconds(1),
                                op -> {
                                    serverMessage(op, incoming);
                                    return null;
                                },
                                false);
                    } else {
                        final var id = McpWire.numericId(incoming.get("id"));
                        final var responseFuture = id == null ? null : pending.remove(id);
                        if (responseFuture != null) {
                            responseFuture.complete(incoming);
                        }
                        // A cancelled/completed request can still receive a late response. It
                        // must not tear down other calls on this shared legacy SSE connection.
                    }
                }
            }
            throw new IOException("MCP " + name + ": SSE stream ended");
        } catch (final IOException | InterruptedException | RuntimeException error) {
            final var failure = error instanceof IOException io ? io : new IOException("MCP SSE stream failed");
            connectionLock.lock();
            try {
                if (stream == connection) {
                    streamFailure = failure;
                    pending.values().forEach(response -> response.completeExceptionally(failure));
                }
            } finally {
                connectionLock.unlock();
            }
            ready.completeExceptionally(failure);
        } finally {
            connection.close();
            untrack(connection);
        }
    }

    @Override
    protected Object exchange(
            final Operation op, final Map<String, Object> message, final Long id, final boolean initialize)
            throws IOException, InterruptedException {
        if (initialize && streamFailure != null) {
            closeTransport();
            connectionLock.lockInterruptibly();
            try {
                op.check();
                endpointReady = null;
                streamFailure = null;
            } finally {
                connectionLock.unlock();
            }
        }
        op.check();
        connect();
        if (id == null) {
            postOnly(op, message);
            return null;
        }
        final var response = new CompletableFuture<Map<?, ?>>();
        pending.put(id, response);
        try {
            final var failure = streamFailure;
            if (failure != null) {
                throw failure;
            }
            postOnly(op, message);
            return result(await(response), id);
        } finally {
            pending.remove(id);
        }
    }

    private <T> T await(final CompletableFuture<T> future) throws IOException, InterruptedException {
        try {
            return future.get(); // The enclosing full-operation deadline also covers this wait.
        } catch (final ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException("MCP " + name + ": SSE stream failed");
        }
    }

    @Override
    protected void abortTransport() {
        closeTransport();
    }

    @Override
    protected void closeTransport() {
        final Operation connection;
        final Future<?> reader;
        connectionLock.lock();
        try {
            streamFailure = new IOException("MCP " + name + ": SSE stream closed");
            connection = stream;
            reader = readerTask;
            stream = null; // A retiring reader may no longer publish endpoint/failure state.
            readerTask = null;
            endpoint = null;
            final var error = new IOException("MCP " + name + " is closed");
            final var ready = endpointReady;
            if (ready != null) {
                ready.completeExceptionally(error);
            }
            pending.values().forEach(response -> response.completeExceptionally(error));
            pending.clear();
        } finally {
            connectionLock.unlock();
        }
        if (connection != null) {
            connection.close();
            untrack(connection); // A reader cancelled before it starts cannot run its finally block.
        }
        if (reader != null) {
            reader.cancel(true);
        }
    }
}
