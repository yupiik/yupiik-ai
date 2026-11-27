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
package io.yupiik.ia.agent.acp;

import static java.util.stream.Collectors.joining;

import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.SandboxIntegration;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Session-local stable ACP v1 client tools, not MCP tools and not a transport.
 * Add {@link #tools()} to the session's ToolRegistry.withTools overlay; local tools keep their names.
 * Execute on the turn/tool worker, never on the connection reader.
 *
 * <p>The connection owns JSON-RPC IDs, pending requests, deadlines, cancellation and JsonMapper encoding/decoding.
 * This adapter only consumes decoded JSON values, performs semantic validation, and owns active terminals.
 * No executor, timer, local filesystem operation, subprocess, or shared resource is created or closed here.</p>
 *
 * <p>Path confinement is lexical. Only the client can check its actual filesystem (including symlinks and editor
 * buffers) at use time. PermissionService remains authoritative; capability support is not authorization.
 * Commands are sent as an executable and an argument array, never as a constructed shell script.
 * Neither command-family rules nor lexical path checks constitute an execution/egress sandbox.</p>
 */
public final class AcpClientTools implements AutoCloseable {
    /**
     * Nonblocking connection-owned request channel. Params/results are JSON objects/values encoded/decoded using
     * JsonMapper by the connection, not JSON strings and not JSON-RPC envelopes.
     *
     * <p>The channel MUST enforce the positive timeout and observe cancellation without relying on the reader
     * thread waiting for this tool. Complete exceptionally on rejection, timeout, cancellation or disconnect, and
     * evict pending entries/listeners at their bounded lifecycle end. Cleanup calls use an independent false
     * cancellation probe and a separate short timeout, even after session cancellation.</p>
     *
     * <p>For terminal/create, lateResult receives a successful result arriving AFTER the returned stage has already
     * failed (for example after timeout/cancel). Normal successes are published through the stage, not lateResult.
     * Keep this late-publication hook only for the connection's bounded late-response window and evict it on
     * disconnect. The adapter will attempt kill/release without resurrecting a tool. If the peer never publishes
     * a created ID or the transport is gone, cleanup cannot be guaranteed by an agent-side adapter.</p>
     */
    @FunctionalInterface
    public interface Requests {
        CompletionStage<Object> request(
                String method,
                Map<String, Object> params,
                Duration timeout,
                BooleanSupplier cancelled,
                Consumer<Object> lateResult);
    }

    private static final BooleanSupplier NEVER_CANCELLED = () -> false;
    private static final Pattern POLICY_WORD = Pattern.compile("[a-zA-Z0-9_./-]+");
    private static final Consumer<Object> IGNORE_LATE = ignored -> {};
    private final String sessionId;
    private final Path workdir;
    private List<Path> additionalDirectories = List.of();
    private boolean rootsConfigured;
    private final Duration requestTimeout;
    private final Duration cleanupTimeout;
    private final Requests requests;
    private final BiConsumer<ToolContext, String> terminalUpdate;
    private final List<EmbeddedTool> tools;
    private final ReentrantLock lifecycle = new ReentrantLock();
    private final Set<Operation> active = new HashSet<>();
    private volatile boolean closed;

    /**
     * @param workdir required, already absolute and normalized session root; never replaced by an ambient root
     * @param clientCapabilities decoded stable v1 clientCapabilities; only literal boolean true enables a method
     * @param requestTimeout positive per-request bound (terminal timeout_seconds additionally bounds the command)
     * @param cleanupTimeout independent cleanup bound, positive and at most five seconds
     * @param terminalUpdate required session-local, nonblocking callback receiving context/ordinal and terminalId;
     *                       emit a session/update tool_call_update with terminal content, without command/args
     */
    public AcpClientTools(
            final String sessionId,
            final Path workdir,
            final Map<String, ?> clientCapabilities,
            final Duration requestTimeout,
            final Duration cleanupTimeout,
            final Requests requests,
            final BiConsumer<ToolContext, String> terminalUpdate) {
        this.sessionId = text(sessionId, false);
        this.workdir = Objects.requireNonNull(workdir, "workdir");
        if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
            throw new IllegalArgumentException("workdir must be absolute and normalized");
        }
        this.requestTimeout = positive(requestTimeout);
        this.cleanupTimeout = positive(cleanupTimeout);
        if (cleanupTimeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("cleanup timeout must be at most five seconds");
        }
        this.requests = Objects.requireNonNull(requests, "requests");
        this.terminalUpdate = Objects.requireNonNull(terminalUpdate, "terminalUpdate");
        final var available = new ArrayList<EmbeddedTool>(3);
        final var fs =
                clientCapabilities != null && clientCapabilities.get("fs") instanceof Map<?, ?> map ? map : Map.of();
        if (Boolean.TRUE.equals(fs.get("readTextFile"))) {
            available.add(new ClientTool(Kind.READ));
        }
        if (Boolean.TRUE.equals(fs.get("writeTextFile"))) {
            available.add(new ClientTool(Kind.WRITE));
        }
        if (clientCapabilities != null && Boolean.TRUE.equals(clientCapabilities.get("terminal"))) {
            available.add(new ClientTool(Kind.TERMINAL));
        }
        this.tools = List.copyOf(available);
    }

    public List<EmbeddedTool> tools() {
        return tools;
    }

    /** Called once during session setup, before tools are published. Permissions remain independently mandatory. */
    public void configureAdditionalDirectories(final List<Path> roots) {
        if (rootsConfigured) {
            throw new IllegalStateException("Session roots are immutable");
        }
        if (roots.stream().anyMatch(root -> root == null || !root.isAbsolute() || !root.equals(root.normalize()))) {
            throw new IllegalArgumentException("Invalid session roots");
        }
        additionalDirectories = List.copyOf(roots);
        rootsConfigured = true;
    }

    /** Initiates independent bounded cleanup for every operation; never waits for a client response. */
    @Override
    public void close() {
        final List<Operation> snapshot;
        lifecycle.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            snapshot = List.copyOf(active);
            snapshot.forEach(operation -> operation.stopped = true);
        } finally {
            lifecycle.unlock();
        }
        for (final var operation : snapshot) {
            operation.stop.complete(null);
            cleanup(operation, true);
        }
    }

    private enum Kind {
        READ("client_read_text_file", "read", ToolCategory.READ),
        WRITE("client_write_text_file", "write_file", ToolCategory.EDIT),
        TERMINAL("client_terminal", "bash", ToolCategory.BASH);

        private final String name;
        private final String localName;
        private final ToolCategory category;

        Kind(final String name, final String localName, final ToolCategory category) {
            this.name = name;
            this.localName = localName;
            this.category = category;
        }
    }

    private final class ClientTool implements SandboxIntegration {
        @Override
        public String sandboxIntegrationId() {
            return switch (kind) {
                case READ -> "acp:fs.readTextFile";
                case WRITE -> "acp:fs.writeTextFile";
                case TERMINAL -> "acp:terminal";
            };
        }

        private final Kind kind;

        private ClientTool(final Kind kind) {
            this.kind = kind;
        }

        @Override
        public String name() {
            return kind.name;
        }

        @Override
        public String description(final Path root) {
            Objects.requireNonNull(root, "workdir");
            return switch (kind) {
                case READ ->
                    "Read text through the ACP client, including unsaved editor buffers. Prefer this over local "
                            + "read for editor-buffer operations. Relative paths use cwd; absolute paths must be within "
                            + "cwd or an additional session directory, and still require permission; "
                            + "line is 1-based and limit bounds lines. Client errors never fall back to disk.";
                case WRITE ->
                    "Write complete text through the ACP client, preserving editor-buffer semantics. Prefer "
                            + "this for editor-buffer operations; never retry client rejection as a local disk write. "
                            + "Relative paths use cwd; absolute paths may use additional session directories but still require permission. Unavailable in plan mode.";
                case TERMINAL ->
                    "Run an executable with a separate args array in an ACP client terminal, not a shell "
                            + "command string. cwd defaults to the primary session root; additional session directories "
                            + "are also eligible, subject to permissions. Returns output "
                            + "and exit status, then releases the terminal. No local fallback; unavailable in plan mode. "
                            + "Environment overrides are not supported.";
            };
        }

        @Override
        public ToolCategory category() {
            return kind.category;
        }

        @Override
        public boolean readonly() {
            return kind == Kind.READ;
        }

        @Override
        public boolean usable(final Path root) {
            return workdir.equals(Objects.requireNonNull(root, "workdir")) && !closed;
        }

        @Override
        public Map<String, Object> schema() {
            final var schema =
                    switch (kind) {
                        case READ ->
                            Schemas.object(
                                    Map.of(
                                            "path",
                                                    Schemas.str(
                                                            "Required file path within the session roots; relative paths use primary cwd."),
                                            "line",
                                                    integerSchema(
                                                            "First line, 1-based; default 1.", 1, Integer.MAX_VALUE),
                                            "limit",
                                                    integerSchema(
                                                            "Maximum lines; default 2000.", 1, Integer.MAX_VALUE)),
                                    "path");
                        case WRITE ->
                            Schemas.object(
                                    Map.of(
                                            "path",
                                                    Schemas.str(
                                                            "Required file path within the session roots; relative paths use primary cwd."),
                                            "content",
                                                    Schemas.str("Complete text to write, including an empty string.")),
                                    "path",
                                    "content");
                        case TERMINAL ->
                            Schemas.object(
                                    Map.of(
                                            "command", Schemas.str("Executable, not a command plus shell arguments."),
                                            "args",
                                                    Schemas.arrayOf(
                                                            "Separate executable arguments.", Schemas.str("Argument.")),
                                            "cwd",
                                                    Schemas.str(
                                                            "Working directory within the session roots; defaults/relative paths use primary cwd."),
                                            "timeout_seconds",
                                                    integerSchema(
                                                            "Whole command timeout; default 120 seconds.", 1, 3600),
                                            "outputByteLimit",
                                                    integerSchema("Retained output bytes; default 65536.", 1, 1048576)),
                                    "command");
                    };
            final var strict = new LinkedHashMap<String, Object>(schema);
            strict.put("additionalProperties", false);
            return Map.copyOf(strict);
        }

        @Override
        public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
            Operation operation = null;
            var completed = false;
            try {
                requireContext(context, kind);
                final var params = parameters(kind, Objects.requireNonNull(args, "args"));
                authorize(kind, params, context);
                operation = begin(context, kind);
                final var result =
                        switch (kind) {
                            case READ -> {
                                final var value =
                                        object(request(operation, "fs/read_text_file", params, requestTimeout, false));
                                yield ToolResult.ok(text(value.get("content"), true));
                            }
                            case WRITE -> {
                                empty(request(operation, "fs/write_text_file", params, requestTimeout, false));
                                yield ToolResult.ok("Client text file written.");
                            }
                            case TERMINAL -> terminal(operation, params, args);
                        };
                completed = result.success();
                return result;
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return ToolResult.error("ACP client operation interrupted.");
            } catch (final Exception failure) {
                // Client/permission exceptions can contain credentials, command arguments or file contents.
                // Do not echo exception messages, causes, arguments or stack traces to the model or a logger.
                return ToolResult.error(failureMessage(failure));
            } finally {
                if (operation != null) {
                    finish(operation, !completed);
                }
            }
        }
    }

    private void requireContext(final ToolContext context, final Kind kind) {
        if (context == null
                || !sessionId.equals(context.sessionId())
                || !workdir.equals(context.projectDir())
                || context.permissions() == null) {
            throw new IllegalArgumentException("session context mismatch");
        }
        if (context.allowedTools() != null && !context.allowedTools().contains(kind.name)) {
            throw new IllegalArgumentException("tool ceiling");
        }
        if (closed
                || context.isCancelled()
                || context.isCallCancelled()
                || Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
        // PermissionService.check does not by itself prohibit all edits in PLAN (e.g. YOLO/explicit allows).
        // Client terminals are deliberately conservative: none is advertised as a readonly executable.
        if (kind != Kind.READ
                && context.permissions().mode() == AgentMode.PLAN
                && context.permissions().permissionMode() != PermissionMode.FULL_YOLO) {
            throw new IllegalArgumentException("plan mode");
        }
    }

    private Map<String, Object> parameters(final Kind kind, final Map<String, Object> args) {
        final var allowed =
                switch (kind) {
                    case READ -> Set.of("path", "line", "limit");
                    case WRITE -> Set.of("path", "content");
                    case TERMINAL -> Set.of("command", "args", "cwd", "timeout_seconds", "outputByteLimit");
                };
        if (!allowed.containsAll(args.keySet())) {
            throw new IllegalArgumentException("unknown arguments");
        }
        final var params = new LinkedHashMap<String, Object>();
        params.put("sessionId", sessionId);
        if (kind == Kind.TERMINAL) {
            params.put("command", text(args.get("command"), false));
            final var arguments = new ArrayList<String>();
            if (args.containsKey("args")) {
                if (!(args.get("args") instanceof List<?> list)) {
                    throw new IllegalArgumentException("args must be an array");
                }
                for (final var argument : list) {
                    final var word = text(argument, true);
                    if (word.indexOf('\0') >= 0) {
                        throw new IllegalArgumentException("invalid argument");
                    }
                    arguments.add(word);
                }
            }
            params.put("args", List.copyOf(arguments));
            params.put("cwd", args.containsKey("cwd") ? path(args.get("cwd")).toString() : workdir.toString());
            params.put("outputByteLimit", integer(args, "outputByteLimit", 65536, 1, 1048576));
            integer(args, "timeout_seconds", 120, 1, 3600);
        } else {
            params.put("path", path(args.get("path")).toString());
            if (kind == Kind.READ) {
                params.put("line", integer(args, "line", 1, 1, Integer.MAX_VALUE));
                params.put("limit", integer(args, "limit", 2000, 1, Integer.MAX_VALUE));
            } else {
                params.put("content", text(args.get("content"), true));
            }
        }
        return Map.copyOf(params);
    }

    private Path path(final Object raw) {
        final var parsed = Path.of(text(raw, false));
        final var resolved = (parsed.isAbsolute() ? parsed : workdir.resolve(parsed)).normalize();
        if (!resolved.startsWith(workdir) && additionalDirectories.stream().noneMatch(resolved::startsWith)) {
            throw new IllegalArgumentException("path outside session roots");
        }
        return resolved;
    }

    private void authorize(final Kind kind, final Map<String, Object> params, final ToolContext context) {
        final var checked = new LinkedHashMap<>(params);
        if (kind == Kind.TERMINAL) {
            // Lossless, quoted POLICY PROJECTION ONLY for the existing shell-string PermissionService API.
            // In particular, checking just the executable would authorize 'git push' as if it were bare 'git'.
            // The wire params remain structured and unchanged; this projection is NEVER executed.
            final var words = Stream.concat(Stream.of(params.get("command")), ((List<?>) params.get("args")).stream());
            checked.put(
                    "command",
                    words.map(String.class::cast)
                            .map(AcpClientTools::policyWord)
                            .collect(joining(" ")));
            checked.put("executable", params.get("command"));
            checked.put("workdir", params.get("cwd"));
        }
        // Preserve configured denials/approvals for the existing local operation as well as the explicit new name.
        // Full absolute path, executable, arguments and cwd are available to the actual session permission service.
        for (final var name : List.of(kind.localName, kind.name)) {
            if (!context.permissions()
                    .check(name, kind.category, Map.copyOf(checked), kind == Kind.READ)
                    .allowed()) {
                throw new IllegalArgumentException("permission denied");
            }
        }
    }

    private ToolResult terminal(
            final Operation operation, final Map<String, Object> params, final Map<String, Object> args)
            throws InterruptedException, ExecutionException, TimeoutException {
        final var seconds = integer(args, "timeout_seconds", 120, 1, 3600);
        final var deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        final var created = object(request(operation, "terminal/create", params, remaining(deadline), true));
        final var terminalId = text(created.get("terminalId"), false);
        lifecycle.lock();
        try {
            requireActive(operation);
            terminalUpdate.accept(operation.context, terminalId);
        } finally {
            lifecycle.unlock();
        }
        final var terminalParams = Map.<String, Object>of("sessionId", sessionId, "terminalId", terminalId);
        final var exited =
                exitStatus(request(operation, "terminal/wait_for_exit", terminalParams, remaining(deadline), false));
        final var output = object(request(operation, "terminal/output", terminalParams, remaining(deadline), false));
        final var content = text(output.get("output"), true);
        if (!(output.get("truncated") instanceof Boolean truncated)) {
            throw new IllegalArgumentException("invalid truncation flag");
        }
        if (output.get("exitStatus") != null && !exited.equals(exitStatus(output.get("exitStatus")))) {
            throw new IllegalArgumentException("inconsistent exit status");
        }
        final var rendered = content + (truncated ? "\n[Client terminal output truncated]" : "")
                + "\nExit code: " + (exited.code() == null ? "unknown" : exited.code())
                + (exited.signal() == null ? "" : "\nSignal: " + exited.signal());
        return Integer.valueOf(0).equals(exited.code()) && exited.signal() == null
                ? ToolResult.ok(rendered)
                : ToolResult.error(rendered);
    }

    private Operation begin(final ToolContext context, final Kind kind) {
        lifecycle.lock();
        try {
            // An approval callback can change mode/ceiling or close/cancel the session before it returns.
            requireContext(context, kind);
            final var operation = new Operation(context);
            requireActive(operation);
            active.add(operation);
            return operation;
        } finally {
            lifecycle.unlock();
        }
    }

    private Object request(
            final Operation operation,
            final String method,
            final Map<String, Object> params,
            final Duration timeout,
            final boolean create)
            throws InterruptedException, ExecutionException {
        final var response = new CompletableFuture<Object>();
        lifecycle.lock();
        try {
            requireActive(operation);
            final var stage = Objects.requireNonNull(
                    requests.request(
                            method,
                            params,
                            timeout,
                            () -> cancelled(operation),
                            create ? value -> publish(operation, value) : IGNORE_LATE),
                    "request stage");
            stage.whenComplete((value, failure) -> {
                if (failure == null) {
                    if (create) {
                        publish(operation, value);
                    }
                    response.complete(value);
                } else {
                    response.completeExceptionally(failure);
                }
            });
            operation.stop.thenRun(() -> response.completeExceptionally(new CancellationException()));
        } finally {
            lifecycle.unlock();
        }
        // Only a tool worker waits. Connection callbacks merely complete/publish and never block.
        final var result = response.get();
        requireActive(operation);
        return result;
    }

    private void publish(final Operation operation, final Object result) {
        if (!(result instanceof Map<?, ?> map)
                || !(map.get("terminalId") instanceof String id)
                || id.isBlank()
                || id.indexOf('\0') >= 0) {
            return;
        }
        final boolean dispose;
        lifecycle.lock();
        try {
            if (operation.terminalId != null) {
                return; // normal/late publication of the same response must not double-release
            }
            operation.terminalId = id;
            dispose = cancelled(operation);
        } finally {
            lifecycle.unlock();
        }
        if (dispose) {
            cleanup(operation, true);
        }
    }

    private void finish(final Operation operation, final boolean abnormal) {
        lifecycle.lock();
        try {
            operation.finished = true;
            operation.stopped |= abnormal;
            if (operation.terminalId == null) {
                active.remove(operation);
            }
        } finally {
            lifecycle.unlock();
        }
        cleanup(
                operation,
                abnormal || closed || operation.context.isCancelled() || operation.context.isCallCancelled());
    }

    private void cleanup(final Operation operation, final boolean kill) {
        final String id;
        lifecycle.lock();
        try {
            id = operation.terminalId;
            if (id == null || operation.cleanupStarted) {
                return;
            }
            operation.cleanupStarted = true;
            // Ownership ends when cleanup is dispatched. The connection owns the bounded in-flight request;
            // keeping a completed tool/context here while release is pending would serve no retry purpose.
            active.remove(operation);
        } finally {
            lifecycle.unlock();
        }
        final var params = Map.<String, Object>of("sessionId", sessionId, "terminalId", id);
        if (kill) {
            cleanupRequest("terminal/kill", params);
        }
        // Do NOT chain release to kill: even a stuck/failed kill must not prevent release or other terminals' cleanup.
        cleanupRequest("terminal/release", params);
    }

    private CompletionStage<Object> cleanupRequest(final String method, final Map<String, Object> params) {
        try {
            return Objects.requireNonNull(
                            requests.request(method, params, cleanupTimeout, NEVER_CANCELLED, IGNORE_LATE),
                            "cleanup stage")
                    .thenApply(value -> {
                        empty(value);
                        return value;
                    });
        } catch (final RuntimeException failure) {
            // A disconnected/rejecting connection is not a reason to skip the independent release attempt.
            return CompletableFuture.failedFuture(new IllegalStateException("ACP cleanup unavailable"));
        }
    }

    private boolean cancelled(final Operation operation) {
        return closed
                || operation.stopped
                || operation.finished
                || operation.context.isCancelled()
                || operation.context.isCallCancelled();
    }

    private void requireActive(final Operation operation) {
        if (cancelled(operation) || Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }

    private Duration remaining(final long deadline) throws TimeoutException {
        final var nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw new TimeoutException();
        }
        final var remaining = Duration.ofNanos(nanos);
        return remaining.compareTo(requestTimeout) < 0 ? remaining : requestTimeout;
    }

    private static Duration positive(final Duration value) {
        Objects.requireNonNull(value, "timeout");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return value;
    }

    private static String text(final Object value, final boolean emptyAllowed) {
        if (!(value instanceof String string) || (!emptyAllowed && (string.isBlank() || string.indexOf('\0') >= 0))) {
            throw new IllegalArgumentException("invalid string");
        }
        return string;
    }

    private static Map<?, ?> object(final Object value) {
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new IllegalArgumentException("invalid object");
        }
        return map;
    }

    private static void empty(final Object value) {
        final var map = object(value);
        if (!map.isEmpty() && !(map.size() == 1 && map.get("_meta") instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("invalid empty result");
        }
    }

    private static int integer(
            final Map<String, Object> args, final String key, final int fallback, final int min, final int max) {
        final var value = args.containsKey(key) ? exactInteger(args.get(key)) : fallback;
        if (value < min || value > max) {
            throw new IllegalArgumentException("integer outside range");
        }
        return value;
    }

    private static int exactInteger(final Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.intValueExact();
        }
        if (value instanceof BigInteger integer) {
            return integer.intValueExact();
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return ((Number) value).intValue();
        }
        if (value instanceof Long number) {
            return Math.toIntExact(number);
        }
        // Floating-point, strings, booleans and null are not exact integral JSON values.
        throw new IllegalArgumentException("invalid integer");
    }

    private record Exit(Integer code, String signal) {}

    private static Exit exitStatus(final Object value) {
        final var map = object(value);
        return new Exit(
                map.get("exitCode") == null ? null : exactInteger(map.get("exitCode")),
                map.get("signal") == null ? null : text(map.get("signal"), false));
    }

    private static Map<String, Object> integerSchema(final String description, final int min, final int max) {
        return Map.of("type", "integer", "description", description, "minimum", min, "maximum", max);
    }

    private static String policyWord(final String word) {
        return POLICY_WORD.matcher(word).matches() ? word : "'" + word.replace("'", "'\"'\"'") + "'";
    }

    private static String failureMessage(final Throwable failure) {
        Throwable cause = failure;
        for (var depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            if (cause instanceof TimeoutException) {
                return "ACP client operation timed out.";
            }
            if (cause instanceof CancellationException) {
                return "ACP client operation cancelled.";
            }
        }
        return "ACP client operation denied, unavailable, or returned an invalid result.";
    }

    private static final class Operation {
        private final ToolContext context;
        private final CompletableFuture<Void> stop = new CompletableFuture<>();
        private volatile boolean stopped;
        private volatile boolean finished;
        // Remaining fields are guarded by lifecycle; no completed-terminal history is retained.
        private String terminalId;
        private boolean cleanupStarted;

        private Operation(final ToolContext context) {
            this.context = context;
        }
    }
}
