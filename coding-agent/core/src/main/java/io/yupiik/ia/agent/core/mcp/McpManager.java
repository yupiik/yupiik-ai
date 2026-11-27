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
package io.yupiik.ia.agent.core.mcp;

import static java.util.stream.Collectors.toList;

import io.yupiik.ai.mcp.client.HttpMcpClient;
import io.yupiik.ai.mcp.client.McpClient;
import io.yupiik.ai.mcp.client.SseMcpClient;
import io.yupiik.ai.mcp.client.StdioMcpClient;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.McpServerConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.SandboxIntegration;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolDiagnosticException;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.wiring.HttpClientProducer.SessionCapableClient;
import io.yupiik.maven.knowledge.process.ProcessService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@ApplicationScoped
public class McpManager implements AutoCloseable {
    // Non-static: resolved per instance so it binds to the ACTIVE runtime LogManager/registry.
    // In the GraalVM-native build the static form is initialized at image-build-time against the
    // legacy logging registry, while at runtime yca uses -Djava.util.logging.manager=YupiikLogManager;
    // those two registries each printed the same LogRecord -> identical-timestamp duplicated lines.
    // A per-instance logger re-resolves lazily, so only the active runtime registry emits.
    private final Logger logger = Logger.getLogger(McpManager.class.getName());

    private static final Pattern UNSAFE_SERVER_NAME = Pattern.compile("[^A-Za-z0-9_.-]");
    private static final List<Pattern> DEFAULT_AVOID_ON_ERROR_PATTERNS =
            McpServerConfiguration.DEFAULT_AVOID_ON_ERROR_PATTERNS.stream()
                    .map(Pattern::compile)
                    .toList();

    private final CodingAgentConfiguration configuration;
    private final Map<String, List<Pattern>> avoidOnErrorPatterns;
    private final JsonMapper mapper;
    private final List<EmbeddedTool> localTools;
    private final SessionCapableClient httpClient;
    private final ProcessService processes;
    private final ExecutorService executor;
    private final boolean async;
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, List<EmbeddedTool>> toolsByServer = new ConcurrentHashMap<>();
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    // In-flight remote MCP server initialize handshakes, keyed by server name. They are kicked off
    // asynchronously from start() so an explicit warmup never blocks on a network round-trip or
    // subprocess spawn; they are joined lazily on first real
    // use (tools()/toolsByServer()/call()) and awaited in close() so the process does not exit
    // mid-handshake or leak a subprocess.
    private final Map<String, CompletableFuture<Void>> pending = new ConcurrentHashMap<>();

    public McpManager(
            final CodingAgentConfiguration configuration,
            final JsonMapper mapper,
            final List<EmbeddedTool> localTools,
            final SessionCapableClient httpClient,
            final ProcessService processes) {
        this(configuration, mapper, localTools, httpClient, processes, null);
    }

    public McpManager(
            final CodingAgentConfiguration configuration,
            final JsonMapper mapper,
            final List<EmbeddedTool> localTools,
            final SessionCapableClient httpClient,
            final ProcessService processes,
            final ExecutorService executor) {
        this.configuration = configuration;
        final var patterns = new LinkedHashMap<String, List<Pattern>>();
        if (configuration != null
                && configuration.mcp() != null
                && configuration.mcp().servers() != null) {
            for (final var server : configuration.mcp().servers()) {
                patterns.put(server.name(), compileAvoidOnErrorPatterns(server.name(), server.avoidOnErrorPatterns()));
            }
        }
        this.avoidOnErrorPatterns = Map.copyOf(patterns);
        this.mapper = mapper;
        this.localTools = localTools;
        this.httpClient = httpClient;
        this.processes = processes;
        this.executor = executor;
        // A provided (app-managed, injected) executor is the shared application pool: register async
        // handshakes so container startup is never blocked. Without one (tests/programmatic use) the
        // handshakes run synchronously, preserving the historical blocking semantics.
        this.async = executor != null;
    }

    private static List<Pattern> compileAvoidOnErrorPatterns(final String server, final List<String> patterns) {
        if (McpServerConfiguration.DEFAULT_AVOID_ON_ERROR_PATTERNS.equals(patterns)) {
            return DEFAULT_AVOID_ON_ERROR_PATTERNS;
        }
        try {
            return patterns.stream().map(Pattern::compile).toList();
        } catch (final PatternSyntaxException e) {
            // Do not include the expression or the regex exception: configuration can contain secrets.
            throw new IllegalArgumentException("Invalid avoidOnErrorPatterns for MCP server '"
                    + UNSAFE_SERVER_NAME.matcher(server).replaceAll("_") + "'");
        }
    }

    ToolDiagnosticException failureForUi(final String server, final String tool, final Throwable failure) {
        return McpDiagnostics.forUi(
                tool, server, avoidOnErrorPatterns.getOrDefault(server, DEFAULT_AVOID_ON_ERROR_PATTERNS), failure);
    }

    protected McpManager() {
        this.avoidOnErrorPatterns = Map.of();
        this.configuration = null;
        this.mapper = null;
        this.localTools = null;
        this.httpClient = null;
        this.processes = null;
        this.executor = null;
        this.async = false;
    }

    /**
     * True once every configured server has been {@link #start() started} (or skipped because
     * disabled) at least once. Guards against re-entrant starts: the GraalVM-native build can
     * resolve the same {@code @ApplicationScoped} graph twice (two {@code ApplicationFusionContext}s
     * or two containers), which would otherwise reconnect every MCP server and duplicate the
     * "ready" startup logs. {@link #reset()} is the explicit re-start entry point and clears this
     * flag so a manual reset still works after an automatic start.
     */
    private volatile boolean started;

    /** Opens client-supplied servers without changing application configuration or tools. */
    public SessionTools openSession(final List<? extends SessionServer> servers, final Path cwd)
            throws IOException, InterruptedException {
        final var owned = new ArrayList<McpClient>();
        final var tools = new ArrayList<EmbeddedTool>();
        final var names = new HashSet<String>();
        // Validate the entire request before starting any process or contacting any destination.
        for (final var server : servers) {
            if (server == null || server.name() == null || server.name().isBlank() || !names.add(server.name())) {
                throw new IllegalArgumentException("Invalid or duplicate MCP server name");
            }
            switch (server) {
                case StdioServer stdio -> {
                    if (stdio.command() == null
                            || stdio.command().isEmpty()
                            || stdio.command().getFirst() == null
                            || stdio.command().getFirst().isBlank()
                            || stdio.command().stream().anyMatch(it -> it == null || it.indexOf('\0') >= 0)) {
                        throw new IllegalArgumentException("Invalid MCP command");
                    }
                }
                case RemoteServer remote -> validateRemote(remote);
            }
        }
        try {
            for (final var server : servers) {
                final McpClient client =
                        switch (server) {
                            case StdioServer stdio ->
                                new StdioMcpClient(
                                        stdio.name(),
                                        stdio.command(),
                                        stdio.env(),
                                        cwd.toString(),
                                        configuration.mcp().callTimeoutSeconds(),
                                        mapper,
                                        McpProcesses.of(processes));
                            case RemoteServer remote -> remoteClient(remote);
                        };
                owned.add(client);
                client.initialize();
                for (final var descriptor : client.listTools()) {
                    final var qualified = "client_"
                            + UUID.nameUUIDFromBytes((server.name().length() + ":" + server.name() + descriptor.name())
                                            .getBytes(StandardCharsets.UTF_8))
                                    .toString()
                                    .replace("-", "");
                    tools.add(new SandboxIntegration() {
                        @Override
                        public String sandboxIntegrationId() {
                            return "client-mcp:" + server.name();
                        }

                        @Override
                        public String name() {
                            return qualified;
                        }

                        @Override
                        public String description(final Path workdir) {
                            return "[" + server.name() + "] "
                                    + (descriptor.description() == null ? descriptor.name() : descriptor.description());
                        }

                        @Override
                        public ToolCategory category() {
                            return ToolCategory.MCP;
                        }

                        @Override
                        public Map<String, Object> schema() {
                            return descriptor.inputSchema() == null
                                    ? Map.of("type", "object")
                                    : descriptor.inputSchema();
                        }

                        @Override
                        public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                            if (context.permissions() == null
                                    || (context.permissions().mode() == AgentMode.PLAN
                                            && context.permissions().permissionMode() != PermissionMode.FULL_YOLO)) {
                                return ToolResult.error("Client MCP tools are unavailable in PLAN mode");
                            }
                            if (context.cancelled().getAsBoolean()
                                    || context.callCancelled().getAsBoolean()) {
                                return ToolResult.error("Client MCP call cancelled");
                            }
                            try {
                                return ToolResult.ok(client.callTool(descriptor.name(), args));
                            } catch (final InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return ToolResult.error("Client MCP call interrupted");
                            } catch (final IOException | RuntimeException e) {
                                throw McpDiagnostics.forUi(
                                        qualified, server.name(), DEFAULT_AVOID_ON_ERROR_PATTERNS, e);
                            }
                        }
                    });
                }
            }
            return new SessionTools(List.copyOf(tools), List.copyOf(owned));
        } catch (final IOException | InterruptedException | RuntimeException e) {
            owned.forEach(McpClient::close);
            throw e;
        }
    }

    public sealed interface SessionServer permits StdioServer, RemoteServer {
        String name();
    }

    public record StdioServer(String name, List<String> command, List<String> env) implements SessionServer {}

    public enum RemoteTransport {
        HTTP,
        SSE
    }

    public record RemoteServer(String name, RemoteTransport transport, String url, Map<String, String> headers)
            implements SessionServer {
        public RemoteServer {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        @Override
        public String toString() {
            return "RemoteServer[name=" + name + ", transport=" + transport + "]";
        }
    }

    private static void validateRemote(final RemoteServer server) {
        if (server.transport() == null || server.url() == null) {
            throw new IllegalArgumentException("Invalid MCP transport");
        }
        final URI uri;
        try {
            uri = URI.create(server.url());
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid MCP URL");
        }
        if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null
                || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Invalid MCP URL");
        }
        final var names = new HashSet<String>();
        for (final var entry : server.headers().entrySet()) {
            if (entry.getKey() == null
                    || entry.getValue() == null
                    || !names.add(entry.getKey().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Invalid or duplicate MCP header");
            }
            try {
                HttpRequest.newBuilder(uri).header(entry.getKey(), entry.getValue());
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid MCP header");
            }
            if (Set.of(
                            "content-length",
                            "host",
                            "connection",
                            "expect",
                            "upgrade",
                            "mcp-session-id",
                            "mcp-protocol-version")
                    .contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Reserved MCP header");
            }
        }
    }

    private McpClient remoteClient(final RemoteServer server) {
        final var headers = server.headers().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList();
        final var timeout = configuration.mcp().callTimeoutSeconds();
        final var sessionHttp = httpClient.openSession(server.headers().keySet());
        return switch (server.transport()) {
            case HTTP -> new HttpMcpClient(server.name(), server.url(), null, headers, timeout, mapper, sessionHttp);
            case SSE -> new SseMcpClient(server.name(), server.url(), headers, timeout, mapper, sessionHttp);
        };
    }

    public record SessionTools(List<EmbeddedTool> tools, List<McpClient> clients) implements AutoCloseable {
        @Override
        public void close() {
            clients.forEach(McpClient::close);
        }
    }

    public void start() {
        lifecycleLock.lock();
        try {
            if (started) {
                return;
            }
            // not set until every server (or its no-op disabled skip) has gone through startServer once,
            // so a partial failure is retried on a subsequent start rather than silently skipped
            if (configuration.tools().builtin().enabled()) {
                startLocal(); // in-process only: no I/O, stays synchronous
            }
            for (final var server : configuration.mcp().servers()) {
                if (server.enabled()) {
                    if (async) {
                        startServerAsync(server);
                    } else {
                        startServer(server);
                    }
                }
            }
            started = true;
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Restart every enabled {@link McpClient} (built-in local tools included):
     * the previous client is closed and removed then a fresh one is created and
     * initialized, and its tools are re-imported.
     *
     * <p>Unlike the automatic {@link #start()} (async, non-blocking), an explicit reset runs the
     * initializations synchronously: the caller drives it and expects the servers to be usable when it
     * returns.</p>
     */
    public void reset() {
        lifecycleLock.lock();
        try {
            awaitAllPending();
            started = false; // an explicit reset may re-run even after an automatic start (see start())
            if (configuration.tools().builtin().enabled()) {
                startLocal();
            }
            for (final var server : configuration.mcp().servers()) {
                if (server.enabled()) {
                    startServer(server);
                }
            }
            started = true;
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Restart a single server by name (either a configured one or the built-in
     * {@code yca} one). Existing tools keep working during the swap since they
     * delegate to {@link #call(String, String, Map)}.
     *
     * <p>Synchronous like {@link #reset()}: the caller expects the server usable when it returns.</p>
     *
     * @param name the server name.
     */
    public void reset(final String name) {
        lifecycleLock.lock();
        try {
            awaitPending(name);
            if ("yca".equals(name)) {
                if (!configuration.tools().builtin().enabled()) {
                    throw new IllegalArgumentException("the built-in 'yca' MCP server is disabled");
                }
                startLocal();
                return;
            }
            final var server = configuration.mcp().servers().stream()
                    .filter(candidate -> name.equals(candidate.name()))
                    .findFirst()
                    .orElseThrow(() ->
                            new IllegalArgumentException("unable to reset MCP server '" + name + "': no such server"));
            if (!server.enabled()) {
                throw new IllegalArgumentException("MCP server '" + name + "' is disabled");
            }
            startServer(server);
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void startLocal() {
        close("yca");
        toolsByServer.remove("yca");
        // Built-in tools are served in-process: no MCP round-trip, error ToolResult(s) keep their exact output.
        // An optional allow/deny-list slims the wire tool schemas (see features/provider-tuning).
        final var builtin = configuration.tools().builtin();
        final var filtered = localTools == null
                ? List.<EmbeddedTool>of()
                : localTools.stream().filter(t -> builtin.keeps(t.name())).collect(toList());
        toolsByServer.put("yca", new ArrayList<>(filtered));
    }

    /**
     * Kicks off the remote server handshake asynchronously so container startup is not blocked by a
     * network round-trip or a subprocess spawn. The returned future is joined lazily on first use and
     * awaited at shutdown.
     */
    private void startServerAsync(final McpServerConfiguration server) {
        // register the in-flight future BEFORE the task is handed to the executor, and never remove it
        // here: a first-use join (tools()/toolsByServer()/call()) must be able to observe the future as
        // soon as it is scheduled, otherwise a fast first-use can run while the async body is still
        // populating toolsByServer and observe a half-initialized server (or miss the join entirely).
        final var future = new CompletableFuture<Void>();
        pending.put(server.name(), future);
        executor.execute(() -> {
            try {
                startServer(server);
                future.complete(null);
            } catch (final Throwable t) {
                future.completeExceptionally(t);
            } finally {
                // the handshake has settled: drop the in-flight marker so serverStatuses() reports
                // ENABLED (client present) or FAILED (absent) instead of staying PENDING forever.
                pending.remove(server.name(), future);
            }
        });
    }

    private void startServer(final McpServerConfiguration server) {
        closePreviousClient(server.name());
        toolsByServer.remove(server.name());
        try {
            final var client = buildClient(server);
            client.initialize();
            clients.put(server.name(), client);
            final var imported = new ArrayList<EmbeddedTool>();
            for (final var tool : client.listTools()) {
                imported.add(new McpEmbeddedTool(this, server.name(), tool));
            }
            toolsByServer.put(server.name(), imported);
            logger.info("MCP server " + server.name() + " ready, " + imported.size() + " tool(s)");
        } catch (final IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.warning("MCP server " + server.name() + " failed: " + e.getMessage());
        }
    }

    private McpClient buildClient(final McpServerConfiguration server) {
        if (server.url() != null && !server.url().isBlank()) {
            return new HttpMcpClient(
                    server.name(),
                    server.url(),
                    server.apiKey(),
                    server.headers(),
                    configuration.mcp().callTimeoutSeconds(),
                    mapper,
                    httpClient);
        }
        if (server.command() != null && !server.command().isBlank()) {
            final var command = new ArrayList<String>();
            command.add(server.command());
            if (server.args() != null) {
                command.addAll(server.args());
            }
            return new StdioMcpClient(
                    server.name(),
                    command,
                    server.env() == null ? List.of() : server.env(),
                    server.dir(),
                    configuration.mcp().callTimeoutSeconds(),
                    mapper,
                    McpProcesses.of(processes));
        }
        throw new IllegalArgumentException("MCP server '" + server.name() + "' has neither url nor command");
    }

    private void close(final String name) {
        pending.remove(name);
        closePreviousClient(name);
    }

    /**
     * Closes and removes any previously running client for a server, without touching the in-flight
     * {@link #pending} marker. {@link #startServer} uses this instead of {@link #close(String)}: on the
     * async path ({@link #startServerAsync}) the pending marker is what {@link #serverStatuses()} uses to
     * report {@link ServerState#PENDING} while the handshake is still blocked, so removing it prematurely
     * made a concurrent status read report FAILED instead of PENDING (a flaky
     * startIsNonBlockingButFirstUseJoins). The marker is removed by the async task itself once it settles.
     */
    private void closePreviousClient(final String name) {
        final var previous = clients.remove(name);
        if (previous != null) {
            try {
                previous.close();
            } catch (final RuntimeException e) {
                logger.warning("MCP server " + name + " close failed: " + e.getMessage());
            }
        }
    }

    public List<EmbeddedTool> tools() {
        start();
        awaitAllPending();
        final var out = new ArrayList<EmbeddedTool>();
        out.addAll(toolsByServer.values().stream().flatMap(List::stream).toList());
        return out;
    }

    public Map<String, List<EmbeddedTool>> toolsByServer() {
        start();
        awaitAllPending();
        return toolsByServer.entrySet().stream()
                .collect(
                        LinkedHashMap<String, List<EmbeddedTool>>::new,
                        (result, entry) -> result.put(entry.getKey(), List.copyOf(entry.getValue())),
                        Map::putAll);
    }

    public boolean isReadOnly(final String serverName, final String toolName) {
        if (configuration.mcp() == null || configuration.mcp().servers() == null) {
            return false;
        }
        return configuration.mcp().servers().stream()
                .filter(server -> serverName.equals(server.name()))
                .findFirst()
                .map(server -> server.isReadOnly(toolName))
                .orElse(false);
    }

    public enum ServerState {
        NOT_STARTED,
        ENABLED,
        DISABLED,
        // the handshake is still in flight (start() fired it asynchronously); it will transition to
        // ENABLED or FAILED as soon as it completes. Status reads never join or start a handshake.
        PENDING,
        FAILED
    }

    public record ServerStatus(String name, ServerState state) {}

    public List<ServerStatus> serverStatuses() {
        final var out = new ArrayList<ServerStatus>();
        final var mcp = configuration.mcp();
        if (configuration.tools().builtin().enabled()) {
            out.add(new ServerStatus(
                    "yca", toolsByServer.containsKey("yca") ? ServerState.ENABLED : ServerState.NOT_STARTED));
        }
        if (mcp != null && mcp.servers() != null) {
            for (final var server : mcp.servers()) {
                if (!server.enabled()) {
                    out.add(new ServerStatus(server.name(), ServerState.DISABLED));
                } else {
                    out.add(new ServerStatus(
                            server.name(),
                            clients.containsKey(server.name())
                                    ? ServerState.ENABLED
                                    : pending.containsKey(server.name())
                                            ? ServerState.PENDING
                                            : started ? ServerState.FAILED : ServerState.NOT_STARTED));
                }
            }
        }
        return out;
    }

    public String call(final String serverName, final String toolName, final Map<String, Object> args) {
        start();
        awaitPending(serverName);
        final var client = clients.get(serverName);
        if (client == null) {
            throw new IllegalStateException("MCP server '" + serverName + "' is not started");
        }
        try {
            return client.callTool(toolName, args);
        } catch (final IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new UncheckedIOException(new IOException(e.getMessage(), e));
        }
    }

    /**
     * Waits for every in-flight remote handshake. Used by the read paths ({@link #tools()} and
     * {@link #toolsByServer()}) so the tool list observed is always the settled one; pending futures
     * are always guaranteed to settle (the underlying task never leaks a blocked thread beyond a
     * request/timeout), so a plain join has no unbounded hang.
     */
    private void awaitAllPending() {
        pending.values().forEach(McpManager::join);
    }

    private void awaitPending(final String serverName) {
        final var future = pending.get(serverName);
        if (future != null) {
            join(future);
            pending.remove(serverName);
        }
    }

    private static void join(final CompletableFuture<Void> future) {
        future.join();
    }

    /**
     * Close every running {@link McpClient} (stdio subprocesses and stream-backed clients included)
     * and drop the cached tool metadata. Invoked as an application-scoped bean destroy so MCP
     * subprocesses are torn down on application shutdown, not only on restart/reset.
     */
    @Override
    @Destroy
    public void close() {
        lifecycleLock.lock();
        try {
            final var start = System.nanoTime();
            // await still-in-flight handshakes so we do not tear down clients while an initialize is in
            // the middle of its setup (and so a subprocess-originated client is disposed and reaped). Give
            // it a bounded grace: the handshake is bounded by the request timeout, but a remote that never
            // answers would otherwise keep the JVM alive at exit thanks to an unsettled common-pool task.
            final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            for (final var future : pending.values()) {
                try {
                    future.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (final ExecutionException | TimeoutException e) {
                    logger.warning("MCP handshake not fully settled at shutdown: " + e.getMessage());
                }
            }
            pending.clear();
            clients.values().forEach(this::quietClose);
            clients.clear();
            toolsByServer.clear();
            if (logger.isLoggable(Level.FINE)) {
                logger.fine(() -> "[yca-shutdown] McpManager.close: MCP clients closed in "
                        + (System.nanoTime() - start) / 1_000_000L + " ms");
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void quietClose(final McpClient client) {
        try {
            client.close();
        } catch (final RuntimeException e) {
            logger.warning("MCP client close failed: " + e.getMessage());
        }
    }
}
