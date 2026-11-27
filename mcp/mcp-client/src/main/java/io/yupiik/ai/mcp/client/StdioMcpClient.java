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

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.mcp.model.InitializeResponse;
import io.yupiik.fusion.mcp.model.JsonRpcMessage;
import io.yupiik.fusion.mcp.model.ToolResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public class StdioMcpClient implements McpClient {
    private final String name;
    private final List<String> command;
    private final List<String> env;
    private final String dir;
    private final JsonMapper mapper;
    private final McpProcessLifecycle processes;
    private final long timeoutNanos;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonRpcMessage>> pending = new ConcurrentHashMap<>();
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile Process process;
    private volatile OutputStream writer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private static final int MAX_DISCOVERY_PAGES = 128;

    public StdioMcpClient(
            final String name,
            final List<String> command,
            final List<String> env,
            final String dir,
            final int callTimeoutSeconds,
            final JsonMapper mapper,
            final McpProcessLifecycle processes) {
        this.name = name;
        this.command = List.copyOf(command);
        this.env = List.copyOf(env);
        this.dir = dir;
        this.timeoutNanos = TimeUnit.SECONDS.toNanos(callTimeoutSeconds > 0 ? callTimeoutSeconds : 60);
        this.mapper = mapper;
        this.processes = processes;
    }

    @Override
    public void initialize() throws IOException, InterruptedException {
        if (closed.get() || !started.compareAndSet(false, true)) {
            throw new IOException("MCP connection closed or already started");
        }
        Process launched = null;
        try {
            final var pb = new ProcessBuilder(command).redirectErrorStream(false);
            if (dir != null && !dir.isBlank()) {
                pb.directory(Path.of(dir).toFile());
            }
            for (final var kv : env) {
                final var i = kv.indexOf('=');
                if (i <= 0) {
                    throw new IOException("Invalid MCP environment variable");
                }
                pb.environment().put(kv.substring(0, i), kv.substring(i + 1));
            }
            // No lifecycle lock spans start(), tracking, pipe I/O, or protocol initialization.
            // Until publication/registration completes, the initializer still owns cleanup.
            launched = pb.start();
            process = launched;
            if (processes != null) {
                processes.track(launched);
            }
            if (closed.get()) {
                throw new IOException("MCP connection closed");
            }
            writer = launched.getOutputStream();
            // Untrusted stderr can contain supplied credentials. Drain bytes, never log or persist them.
            final var stderr = launched.getErrorStream();
            Thread.ofVirtual().name("mcp-stderr").start(() -> {
                try (stderr) {
                    stderr.transferTo(OutputStream.nullOutputStream());
                } catch (final IOException ignored) {
                    // Process teardown.
                }
            });
            final var input = launched.getInputStream();
            Thread.ofVirtual().name("mcp-reader").start(() -> read(input));
            final var result = request(
                    "initialize",
                    Map.of(
                            "protocolVersion",
                            SUPPORTED_PROTOCOL_VERSIONS.getFirst(),
                            "capabilities",
                            Map.of(),
                            "clientInfo",
                            Map.of("name", "yupiik-ai", "version", "1.0.0")),
                    InitializeResponse.class);
            if (!SUPPORTED_PROTOCOL_VERSIONS.contains(result.protocolVersion())) {
                throw new IOException("Unsupported MCP protocol version");
            }
            send(new JsonRpcMessage("2.0", null, "notifications/initialized", null, null, null));
        } catch (final IOException | InterruptedException | RuntimeException e) {
            close();
            // close() may already have run before publication or before track() returned.
            if (launched != null) {
                dispose(launched);
            }
            if (e instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new IOException("MCP initialization failed");
        }
    }

    @Override
    public List<McpServerTool> listTools() throws IOException, InterruptedException {
        final var deadline = System.nanoTime() + timeoutNanos;
        try {
            final var out = new ArrayList<McpServerTool>();
            final var cursors = new HashSet<String>();
            String cursor = null;
            int pages = 0;
            do {
                if (++pages > MAX_DISCOVERY_PAGES) {
                    throw new IOException("Too many MCP discovery pages");
                }
                final var result = request(
                        "tools/list", cursor == null ? Map.of() : Map.of("cursor", cursor), Object.class, deadline);
                final var tools = McpWire.tools(result);
                if (tools == null) {
                    throw new IOException("Invalid MCP tool list");
                }
                for (final var t : tools) {
                    if (t.name() == null || t.name().isBlank() || out.size() >= 10000) {
                        throw new IOException("Invalid MCP tool descriptor or too many tools");
                    }
                    out.add(new McpServerTool(t.name(), t.description(), t.inputSchema()));
                }
                cursor = McpWire.nextCursor(result);
                if (cursor != null && !cursors.add(cursor)) {
                    throw new IOException("Repeated MCP pagination cursor");
                }
            } while (cursor != null);
            return List.copyOf(out);
        } catch (final IOException | InterruptedException | RuntimeException e) {
            close();
            throw e;
        }
    }

    private void read(final InputStream input) {
        try (final var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final var message = McpWire.envelope(mapper, line);
                if (message.method() != null) {
                    if (message.id() != null) {
                        // No sampling/roots/elicitation capability is negotiated.
                        send(new JsonRpcMessage(
                                "2.0",
                                message.id(),
                                null,
                                null,
                                "ping".equals(message.method()) ? Map.of() : null,
                                "ping".equals(message.method())
                                        ? null
                                        : new JsonRpcMessage.Error(-32601, "Method not supported", null)));
                    }
                } else {
                    final var id = McpWire.numericId(message.id());
                    final var future = id == null ? null : pending.remove(id);
                    if (future != null) {
                        future.complete(message);
                    }
                }
            }
        } catch (final IOException | RuntimeException ignored) {
            // Never include untrusted server messages or launch environment in diagnostics.
        } finally {
            close();
        }
    }

    @Override
    public String callTool(final String toolName, final Map<String, Object> arguments)
            throws IOException, InterruptedException {
        try {
            return McpWire.text(
                    mapper,
                    request("tools/call", Map.of("name", toolName, "arguments", arguments), ToolResponse.class));
        } catch (final McpFailure failure) {
            throw failure.sanitized(null, env, null);
        }
    }

    @Override
    public <T> T call(final String method, final Map<String, Object> params, final Class<T> resultType)
            throws IOException, InterruptedException {
        try {
            return request(method, params, resultType);
        } catch (final McpFailure failure) {
            throw failure.sanitized(null, env, null);
        }
    }

    private <T> T request(final String method, final Object params, final Class<T> type)
            throws IOException, InterruptedException {
        return request(method, params, type, System.nanoTime() + timeoutNanos);
    }

    private <T> T request(final String method, final Object params, final Class<T> type, final long deadline)
            throws IOException, InterruptedException {
        if (closed.get() || process == null || !process.isAlive()) {
            throw new IOException("MCP connection closed");
        }
        final var id = ids.incrementAndGet();
        final var future = new CompletableFuture<JsonRpcMessage>();
        pending.put(id, future);
        try {
            if (closed.get()) {
                throw new IOException("MCP connection closed");
            }
            send(new JsonRpcMessage("2.0", id, method, params, null, null), deadline);
            final var response = future.get(remaining(deadline), TimeUnit.NANOSECONDS);
            if (response.error() != null) {
                throw new McpFailure(
                        "MCP request failed: " + method,
                        "JSON-RPC error " + response.error().code() + ": "
                                + response.error().message());
            }
            if (response.result() == null) {
                throw new IOException("MCP request failed: " + method);
            }
            try {
                return McpWire.payload(mapper, response.result(), type);
            } catch (final IOException e) {
                close();
                throw e;
            }
        } catch (final TimeoutException e) {
            close();
            throw new McpFailure("MCP request timed out", "MCP request timed out: " + method);
        } catch (final ExecutionException e) {
            close();
            throw new IOException("MCP request failed: " + method);
        } catch (final InterruptedException e) {
            // Interrupting a call destroys the owned connection so a blocked server/process cannot leak.
            close();
            throw e;
        } finally {
            pending.remove(id);
        }
    }

    private void send(final JsonRpcMessage message) throws IOException {
        try {
            send(message, System.nanoTime() + timeoutNanos);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("MCP write interrupted");
        }
    }

    private long remaining(final long deadline) throws TimeoutException {
        final var remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new TimeoutException();
        }
        return remaining;
    }

    private void send(final JsonRpcMessage message, final long deadline) throws IOException, InterruptedException {
        final var sent = new CompletableFuture<Void>();
        // Pipe writes are not reliably interruptible. Keep the caller interruptible and able to
        // kill the transport even while the writer is blocked in the OS or waiting for writeLock.
        final var task = Thread.ofVirtual().name("mcp-writer").start(() -> {
            try {
                write(message);
                sent.complete(null);
            } catch (final IOException | RuntimeException e) {
                sent.completeExceptionally(e);
            }
        });
        try {
            sent.get(remaining(deadline), TimeUnit.NANOSECONDS);
        } catch (final ExecutionException | TimeoutException e) {
            close();
            throw new IOException("MCP write failed");
        } catch (final InterruptedException e) {
            close();
            throw e;
        } finally {
            task.interrupt();
        }
    }

    private void write(final JsonRpcMessage message) throws IOException {
        final var bytes = mapper.toString(message).getBytes(StandardCharsets.UTF_8);
        writeLock.lock();
        try {
            if (closed.get() || writer == null) {
                throw new IOException("MCP connection closed");
            }
            writer.write(bytes);
            writer.write('\n');
            writer.flush();
        } finally {
            writeLock.unlock();
        }
    }

    private void failPending() {
        pending.values().forEach(f -> f.completeExceptionally(new IOException("MCP connection closed")));
        pending.clear();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        failPending();
        final var current = process;
        if (current != null) {
            dispose(current);
        }
    }

    private void dispose(final Process current) {
        // Terminate before touching stream locks: a blocked pipe write may hold one.
        current.descendants().forEach(ProcessHandle::destroyForcibly);
        current.destroyForcibly();
        if (processes != null) {
            processes.dispose(current);
        } else {
            try {
                current.waitFor(3, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
