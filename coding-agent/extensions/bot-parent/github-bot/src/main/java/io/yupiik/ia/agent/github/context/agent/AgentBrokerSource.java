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
package io.yupiik.ia.agent.github.context.agent;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.configuration.ConfigurationImpl;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.llm.JsonMappers;
import io.yupiik.ia.agent.github.configuration.AgentContextConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Packaged, secret-free entry point. All network Git uses native smart HTTPS against the broker only. */
public final class AgentBrokerSource {
    private static final Pattern OPERATION_ID = Pattern.compile("[A-Za-z0-9-]{1,80}");
    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-fA-F]{40}");
    private static final Pattern GIT_PATH = Pattern.compile("[A-Za-z0-9_.-][A-Za-z0-9_./ -]*");
    private static final Pattern REPOSITORY_NAME =
            Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}/[A-Za-z0-9_.-]{1,100}");
    private static final Pattern HEAD_SHA = Pattern.compile("[a-fA-F0-9]{40}");
    private static final Pattern GIT_GRANT_SUFFIX = Pattern.compile("[A-Za-z0-9-]{1,80}\\.git");

    private static final String CONTROL_TOKEN = "YCA_CONTEXT_TOKEN";
    private static final String GIT_TOKEN = "YCA_CONTEXT_GIT_TOKEN";
    private static final String STATE = "yca-broker.properties";
    private final Options options;
    private final Path repository;
    private final long deadline;
    private final JsonMapper json;
    private final Configuration sources;
    private final Properties state = new Properties();
    private String stage = "validation";

    private AgentBrokerSource(final Options options, final JsonMapper json, final Configuration sources) {
        this.options = options;
        this.json = json;
        this.sources = sources;
        this.repository = Path.of("repository").toAbsolutePath().normalize();
        this.deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(options.timeout());
    }

    public static void main(final String... args) {
        AgentBrokerSource source = null;
        try (final var json = JsonMappers.forModels(ignored -> Optional.empty(), ContextClient.RpcResponse.class)) {
            final var sources = new ConfigurationImpl(List.of());
            final var options = Options.parse(args);
            if (options.command().equals("credential")) {
                credential(options, System.in, sources);
            } else {
                source = new AgentBrokerSource(options, json, sources);
                source.run();
            }
        } catch (final Exception failure) {
            // Git and HTTP exceptions can contain credentials, URLs or server-controlled text.
            final var code = failure instanceof SourceFailure classified
                    ? classified.code
                    : failure instanceof InterruptedException ? FailureCode.CANCELLED : FailureCode.REJECTED;
            System.err.println("Broker source operation failed: stage=" + (source == null ? "validation" : source.stage)
                    + " code=" + code + " processors=" + Runtime.getRuntime().availableProcessors()
                    + " commonPoolParallelism=" + ForkJoinPool.getCommonPoolParallelism());
            System.exit(1);
        }
    }

    private void run() throws Exception {
        final var command = options.command();
        if (command.equals("init")) {
            if (Files.exists(repository, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Repository already exists.");
            }
            final var paths = paths(options.arguments());
            Files.createDirectory(repository);
            git(List.of("init", "--template=", "."), null);
            state.setProperty("endpoint", options.endpoint().toASCIIString());
            state.setProperty("repository", options.repository());
            state.setProperty("head", options.head());
            state.setProperty("depth", "1");
            state.setProperty("paths", String.join("\n", paths));
            save();
            git(
                    List.of(
                            "config",
                            "remote.origin.url",
                            options.endpoint().resolve(".").toASCIIString()),
                    null);
            git(List.of("config", "remote.origin.tagOpt", "--no-tags"), null);
            git(List.of("sparse-checkout", "init", "--cone"), null);
            git(List.of("sparse-checkout", "set", "--stdin"), lines(paths));
            final var source = prepare(1, false, "HEAD");
            fetch(source, "HEAD", false);
            git(List.of("checkout", "--detach", options.head()), null);
            checkHead();
        } else {
            load();
            validateConfig();
            checkHead();
            switch (command) {
                case "expand" -> {
                    if (options.arguments().isEmpty()) {
                        throw new IOException("Paths required.");
                    }
                    // Refuse dirty, untracked AND ignored files: sparse checkout can otherwise overwrite them.
                    if (!git(List.of("status", "--porcelain=v1", "--untracked-files=all", "--ignored=matching"), null)
                            .isEmpty()) {
                        throw new IOException("Worktree must be clean.");
                    }
                    final var merged = new TreeSet<String>();
                    if (!state.getProperty("paths", "").isEmpty()) {
                        merged.addAll(List.of(state.getProperty("paths").split("\n")));
                    }
                    merged.addAll(paths(options.arguments()));
                    paths(List.copyOf(merged));
                    for (final var path : merged) {
                        rejectSymlinkParents(path);
                    }
                    git(List.of("sparse-checkout", "set", "--stdin"), lines(merged));
                    state.setProperty("paths", String.join("\n", merged));
                    save();
                }
                case "deepen" -> {
                    final int delta = positive(options.arguments().getFirst(), options.maxDepth());
                    final int depth = Math.addExact(Integer.parseInt(state.getProperty("depth")), delta);
                    if (depth > options.maxDepth()) {
                        throw new IOException("Depth limit.");
                    }
                    fetch(prepare(depth, false, "HEAD"), "HEAD", false);
                    state.setProperty("depth", Integer.toString(depth));
                    save();
                }
                case "tags" -> fetch(prepare(Integer.parseInt(state.getProperty("depth")), true, "HEAD"), "HEAD", true);
                case "base" ->
                    fetch(prepare(Integer.parseInt(state.getProperty("depth")), false, "BASE"), "BASE", false);
                default -> throw new IOException("Unknown operation.");
            }
            checkHead();
        }
        bounds();
    }

    private Map<String, Object> prepare(final int depth, final boolean tags, final String revision) throws Exception {
        stage = "context_prepare";
        bounds();
        final var client = new ContextClient(
                new AgentContextConfiguration(
                        true, options.endpoint().toASCIIString(), CONTROL_TOKEN, GIT_TOKEN, options.repository()),
                json,
                sources);
        long requestedAt = System.nanoTime();
        var status = client.call(
                Map.of(
                        "operation",
                        "prepare_source",
                        "repository",
                        options.repository(),
                        "depth",
                        depth,
                        "tags",
                        tags,
                        "revision",
                        revision,
                        "waitMillis",
                        1000),
                this::expired);
        if (!(status.get("operationId") instanceof String id)
                || !OPERATION_ID.matcher(id).matches()) {
            throw new IOException("Invalid preparation.");
        }
        // Long-polling returns fast completions immediately. Slow/legacy servers still get at most
        // one request/sec and never more than 256 requests including prepare_source.
        for (int request = 1; request <= 256; request++) {
            if (expired()) {
                bounds();
            }
            if ("pending".equals(status.get("status"))) {
                if (request == 256) {
                    break;
                }
                final long pause = TimeUnit.SECONDS.toNanos(1) - (System.nanoTime() - requestedAt);
                if (pause > 0) {
                    TimeUnit.NANOSECONDS.sleep(
                            Math.min(pause, TimeUnit.MILLISECONDS.toNanos(Math.max(1, remainingMillis()))));
                }
                bounds();
                requestedAt = System.nanoTime();
                status = client.call(
                        Map.of("operation", "operation_status", "operationId", id, "waitMillis", 1000), this::expired);
                continue;
            }
            if (!"complete".equals(status.get("status"))
                    || !(status.get("source") instanceof Map<?, ?> source)
                    || !options.repository().equals(source.get("repository"))
                    || !revision.equals(source.get("revision"))
                    || !(source.get("headSha") instanceof String sha)
                    || !COMMIT_SHA.matcher(sha).matches()
                    || (revision.equals("HEAD") && !options.head().equalsIgnoreCase(sha))
                    || !(source.get("generation") instanceof String generation)
                    || !OPERATION_ID.matcher(generation).matches()
                    || !("git/" + generation + ".git").equals(source.get("gitPath"))
                    || !(source.get("depth") instanceof Number actualDepth)
                    || new BigDecimal(actualDepth.toString()).intValueExact() != depth) {
                throw new IOException("Invalid source generation.");
            }
            @SuppressWarnings("unchecked")
            final var result = (Map<String, Object>) source;
            return result;
        }
        throw new IOException("Request budget exceeded.");
    }

    private void fetch(final Map<String, Object> source, final String revision, final boolean tags) throws Exception {
        final var url = options.endpoint().resolve(".").resolve((String) source.get("gitPath"));
        final var sha = (String) source.get("headSha");
        // The credential callback accepts precisely this attempt's currently prepared generation.
        state.setProperty("gitPath", url.getRawPath().substring(1));
        save();
        final var args = new ArrayList<>(List.of(
                "fetch",
                "--atomic",
                "--update-shallow",
                "--no-tags",
                "--depth=" + source.get("depth"),
                url.toASCIIString(),
                sha + ":refs/remotes/broker/" + revision));
        if (tags) {
            // No '+' and no --force: existing tags must never be silently replaced.
            args.add("refs/tags/*:refs/tags/*");
        }
        git(args, null);
        if (!git(List.of("rev-parse", "refs/remotes/broker/" + revision), null)
                .strip()
                .equalsIgnoreCase(sha)) {
            throw new IOException("Pinned object mismatch.");
        }
        // Origin records the actual broker generation, never github.com or another upstream.
        git(List.of("config", "remote.origin.url", url.toASCIIString()), null);
    }

    private void checkHead() throws Exception {
        // Local commits are valid agent work. History/source expansion must not reset them, but the
        // host-pinned base must remain an ancestor, not an unrelated checkout or rewritten history.
        git(List.of("merge-base", "--is-ancestor", options.head(), "HEAD"), null);
    }

    private void load() throws IOException {
        bounds();
        if (Files.isSymbolicLink(repository)
                || !Files.isDirectory(repository.resolve(".git"), LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(repository.resolve(".git"))
                || Files.exists(repository.resolve(".git/commondir"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(repository.resolve(".git/gitdir"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(repository.resolve(".git/objects/info/http-alternates"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Independent repository required.");
        }
        final var path = repository.resolve(".git/" + STATE);
        if (Files.isSymbolicLink(path) || Files.size(path) > 16384) {
            throw new IOException("Invalid state.");
        }
        try (final var in = Files.newInputStream(path)) {
            state.load(in);
        }
        identity(state, options);
        positive(state.getProperty("depth"), options.maxDepth());
        if (!state.getProperty("paths", "").isEmpty()) {
            paths(List.of(state.getProperty("paths").split("\n")));
        }
        try (final var metadata = Files.walk(repository.resolve(".git"))) {
            if (metadata.anyMatch(Files::isSymbolicLink)) {
                throw new IOException("Unsafe repository metadata.");
            }
        }
        bounds();
    }

    private void save() throws IOException {
        final var path = repository.resolve(".git/" + STATE);
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Invalid state.");
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        try (final var out = Files.newOutputStream(path, StandardOpenOption.TRUNCATE_EXISTING)) {
            state.store(out, "Non-secret broker source identity");
        }
    }

    private static void identity(final Properties state, final Options options) throws IOException {
        if (!options.endpoint().toASCIIString().equals(state.getProperty("endpoint"))
                || !options.repository().equals(state.getProperty("repository"))
                || !options.head().equals(state.getProperty("head"))) {
            throw new IOException("Source identity mismatch.");
        }
    }

    private void validateConfig() throws Exception {
        // Reject executable/indirect config before any worktree or fetch operation. -c overrides do not
        // neutralize arbitrary filter.<name> drivers or include files, so an allowlist is intentional.
        for (final var key : git(List.of("config", "--local", "--no-includes", "--name-only", "--list"), null)
                .lines()
                .toList()) {
            if (!List.of(
                            "core.repositoryformatversion",
                            "core.filemode",
                            "core.bare",
                            "core.logallrefupdates",
                            "core.sparsecheckout",
                            "core.sparsecheckoutcone",
                            "extensions.worktreeconfig",
                            "remote.origin.url",
                            "remote.origin.tagopt",
                            "user.name",
                            "user.email")
                    .contains(key)) {
                throw new IOException("Unsafe repository configuration.");
            }
        }
        // Sparse checkout normally enables a secondary config file. Check it without processing includes.
        final var worktree = repository.resolve(".git/config.worktree");
        if (Files.exists(worktree, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(worktree)) {
                throw new IOException("Unsafe worktree config.");
            }
            for (final var key : git(
                            List.of("config", "--file", worktree.toString(), "--no-includes", "--name-only", "--list"),
                            null)
                    .lines()
                    .toList()) {
                if (!List.of("core.sparsecheckout", "core.sparsecheckoutcone", "index.sparse")
                        .contains(key)) {
                    throw new IOException("Unsafe worktree configuration.");
                }
            }
        }
        final var origin =
                git(List.of("config", "--get", "remote.origin.url"), null).strip();
        final var base = options.endpoint().resolve(".").toASCIIString();
        final var prefix = base + "git/";
        if (!origin.equals(base)
                && (!origin.startsWith(prefix)
                        || !GIT_GRANT_SUFFIX
                                .matcher(origin.substring(prefix.length()))
                                .matches())) {
            throw new IOException("Unsafe origin.");
        }
        try (final var objects = Files.walk(repository.resolve(".git/objects"))) {
            if (objects.anyMatch(it -> Files.isSymbolicLink(it)
                    || it.endsWith("alternates")
                    || it.toString().endsWith(".promisor"))) {
                throw new IOException("Independent non-promisor objects required.");
            }
        }
    }

    private String git(final List<String> arguments, final String input) throws Exception {
        // Only fixed operation names are ever exposed; never arguments, paths, stderr or URLs.
        stage = switch (arguments.getFirst()) {
            case "sparse-checkout" -> "git_sparse_checkout";
            case "init", "config", "fetch", "checkout", "status", "rev-parse", "merge-base" ->
                "git_" + arguments.getFirst().replace('-', '_');
            default -> "git_read";
        };
        bounds();
        final var command = new ArrayList<>(List.of(
                options.git(),
                "-c",
                "core.hooksPath=/dev/null",
                "-c",
                "core.fsmonitor=false",
                "-c",
                "core.untrackedCache=false",
                "-c",
                "core.attributesFile=/dev/null",
                "-c",
                "protocol.allow=never",
                "-c",
                "protocol.https.allow=always",
                "-c",
                "http.followRedirects=false",
                "-c",
                "http.proxy=",
                "-c",
                "http.sslVerify=true",
                "-c",
                "credential.helper=",
                "-c",
                "credential.helper=" + helper(),
                "-c",
                "credential.useHttpPath=" + true,
                "-c",
                "credential.interactive=" + false,
                "-c",
                "fetch.writeCommitGraph=false",
                "-c",
                "gc.auto=0",
                "-c",
                "maintenance.auto=false",
                "-c",
                "fetch.recurseSubmodules=false",
                "-c",
                "submodule.recurse=false"));
        if (options.caFile() != null) {
            command.addAll(List.of("-c", "http.sslCAInfo=" + options.caFile()));
        }
        command.addAll(arguments);
        final var builder = new ProcessBuilder(command).directory(repository.toFile());
        final var env = builder.environment();
        env.clear();
        env.put(
                "PATH",
                Path.of(options.git()).isAbsolute()
                        ? Path.of(options.git()).getParent() + ":/usr/bin:/bin"
                        : "/usr/bin:/bin");
        env.put("HOME", repository.resolve(".git").toString());
        env.put("LANG", "C");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_NO_LAZY_FETCH", "1");
        env.put("GIT_NO_REPLACE_OBJECTS", "1");
        env.put("GIT_ATTR_NOSYSTEM", "1");
        env.put("GIT_LFS_SKIP_SMUDGE", "1");
        final var token = sources.get(GIT_TOKEN).orElse(null);
        if (token != null) {
            env.put(GIT_TOKEN, token);
        }
        final Process process;
        try {
            process = builder.start();
        } catch (final IOException failure) {
            throw new SourceFailure(FailureCode.PROCESS_START);
        }
        final var children = new LinkedHashMap<Long, ProcessHandle>();
        // Blocking pipe readers must never starve the stdin writer on a CPU-sized common pool.
        // Do not use ExecutorService.close(): pipe-holding descendants must not turn cleanup into an unbounded wait.
        final var io = Executors.newVirtualThreadPerTaskExecutor();
        try {
            final var stdout = io.submit(() -> drain(process.getInputStream(), 512 * 1024));
            final var stderr = io.submit(() -> drain(process.getErrorStream(), 64 * 1024));
            final var writer = io.submit(() -> {
                try (final var stdin = process.getOutputStream()) {
                    if (input != null) {
                        stdin.write(input.getBytes(StandardCharsets.UTF_8));
                    }
                } catch (final IOException failure) {
                    throw new UncheckedIOException(new IOException("Git input unavailable."));
                }
            });
            try {
                while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    process.descendants().forEach(child -> children.put(child.pid(), child));
                    bounds();
                }
                writer.get(Math.max(1, remainingMillis()), TimeUnit.MILLISECONDS);
                if (process.exitValue() != 0) {
                    throw new SourceFailure(FailureCode.GIT_EXIT);
                }
                final var output = stdout.get(Math.max(1, remainingMillis()), TimeUnit.MILLISECONDS);
                final var errors = stderr.get(Math.max(1, remainingMillis()), TimeUnit.MILLISECONDS);
                if (output == null || errors == null) {
                    throw new SourceFailure(FailureCode.OUTPUT_LIMIT);
                }
                bounds();
                return new String(output, StandardCharsets.UTF_8);
            } catch (final InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new SourceFailure(FailureCode.CANCELLED);
            } catch (final TimeoutException failure) {
                throw new SourceFailure(FailureCode.DEADLINE_EXCEEDED);
            } catch (final ExecutionException failure) {
                throw new SourceFailure(FailureCode.PROCESS_IO);
            } finally {
                stdout.cancel(true);
                stderr.cancel(true);
                writer.cancel(true);
            }
        } finally {
            cleanup(process, children, io);
        }
    }

    private static void cleanup(
            final Process process, final Map<Long, ProcessHandle> children, final ExecutorService io)
            throws IOException {
        boolean interrupted = Thread.interrupted();
        final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        try {
            process.descendants().forEach(child -> children.put(child.pid(), child));
            children.values().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            io.shutdownNow();
            // Process.waitFor reaps the direct child. All waits share a separate, bounded cleanup allowance.
            while (process.isAlive() || !io.isTerminated()) {
                final long remaining = until - System.nanoTime();
                if (remaining <= 0) {
                    throw new SourceFailure(FailureCode.CLEANUP_FAILED);
                }
                try {
                    process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
                    io.awaitTermination(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
                } catch (final InterruptedException failure) {
                    interrupted = true;
                }
            }
        } finally {
            io.shutdownNow();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private enum FailureCode {
        REJECTED,
        DEADLINE_EXCEEDED,
        CANCELLED,
        GIT_EXIT,
        OUTPUT_LIMIT,
        DISK_LIMIT,
        DISK_SCAN_IO,
        PROCESS_START,
        PROCESS_IO,
        CLEANUP_FAILED
    }

    private static final class SourceFailure extends IOException {
        private final FailureCode code;

        private SourceFailure(final FailureCode code) {
            super(code.name());
            this.code = code;
        }
    }

    private static byte[] drain(final InputStream input, final int limit) {
        try (input;
                final var output = new ByteArrayOutputStream()) {
            final var buffer = new byte[8192];
            boolean exceeded = false;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > limit - output.size()) {
                    exceeded = true;
                } else if (!exceeded) {
                    output.write(buffer, 0, count);
                }
            }
            return exceeded ? null : output.toByteArray();
        } catch (final IOException failure) {
            throw new UncheckedIOException(new IOException("Git output unavailable."));
        }
    }

    private String helper() {
        final var args = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp",
                absoluteClasspath(),
                AgentBrokerSource.class.getName()));
        args.addAll(options.flags());
        args.add("credential");
        // Git also invokes helpers with store/erase after authentication. These are no-ops:
        // do not launch another JVM (or persist anything) for either notification.
        return "!f() { if [ \"$1\" = get ]; then exec "
                + args.stream().map(AgentBrokerSource::quote).collect(Collectors.joining(" "))
                + " \"$@\"; fi; }; f";
    }

    private static String absoluteClasspath() {
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(it -> Path.of(it).toAbsolutePath().normalize().toString())
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static String quote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static void credential(final Options options, final InputStream input, final Configuration sources)
            throws IOException {
        if (!options.arguments().equals(List.of("get"))) {
            return; // store/erase never persist credentials
        }
        final var cwd = Path.of("").toAbsolutePath().normalize();
        final var statePath = cwd.resolve(".git/" + STATE);
        if (Files.isSymbolicLink(cwd.resolve(".git"))
                || Files.isSymbolicLink(statePath)
                || Files.size(statePath) > 16384) {
            throw new IOException("Invalid credential scope.");
        }
        final var state = new Properties();
        try (final var in = Files.newInputStream(statePath)) {
            state.load(in);
        }
        identity(state, options);
        final var io = Executors.newVirtualThreadPerTaskExecutor();
        final var pending = io.submit(() -> {
            try {
                final var bytes = new ByteArrayOutputStream();
                boolean blank = true;
                int value;
                while ((value = input.read()) >= 0) {
                    if (bytes.size() >= 16384) {
                        throw new IOException("Invalid credential request.");
                    }
                    bytes.write(value);
                    if (value == '\n') {
                        if (blank) {
                            break;
                        }
                        blank = true;
                    } else {
                        blank = false;
                    }
                }
                return bytes.toByteArray();
            } catch (final IOException failure) {
                throw new UncheckedIOException(new IOException("Credential input unavailable."));
            }
        });
        final byte[] bytes;
        try {
            bytes = pending.get(options.timeout(), TimeUnit.SECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Credential input cancelled.");
        } catch (final Exception failure) {
            throw new IOException("Credential input unavailable.");
        } finally {
            pending.cancel(true);
            io.shutdownNow();
        }
        final var fields = new LinkedHashMap<String, String>();
        for (final var line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty()) {
                break;
            }
            final int eq = line.indexOf('=');
            if (eq < 1) {
                throw new IOException("Invalid credential request.");
            }
            final var key = line.substring(0, eq);
            // Modern Git advertises repeated optional capabilities and authentication state.
            // This helper implements only Basic username/password, so neither consume nor echo them.
            if (List.of("capability[]", "state[]", "wwwauth[]").contains(key)) {
                continue;
            }
            if (fields.putIfAbsent(key, line.substring(eq + 1)) != null) {
                throw new IOException("Invalid credential request.");
            }
        }
        final var gitPath = state.getProperty("gitPath", "");
        final var prefix = options.endpoint().resolve(".").getRawPath().substring(1) + "git/";
        if (!"https".equals(fields.get("protocol"))
                || !options.endpoint().getRawAuthority().equals(fields.get("host"))
                || !gitPath.equals(fields.get("path"))
                || !gitPath.startsWith(prefix)
                || !GIT_GRANT_SUFFIX.matcher(gitPath.substring(prefix.length())).matches()) {
            throw new IOException("Credential scope denied.");
        }
        final var secret = sources.get(GIT_TOKEN).orElse(null);
        if (secret == null || secret.isBlank() || secret.indexOf('\r') >= 0 || secret.indexOf('\n') >= 0) {
            throw new IOException("Git credentials unavailable.");
        }
        System.out.print("username=context\npassword=" + secret + "\n\n");
    }

    private List<String> paths(final List<String> values) throws IOException {
        final var paths = new TreeSet<String>();
        int size = 0;
        if (values.size() > 256) {
            throw new IOException("Too many paths.");
        }
        for (final var value : values) {
            size += value.length() + 1;
            if (size > 8192) {
                throw new IOException("Sparse path budget exceeded.");
            }
            if (value.length() > 1024
                    || value.startsWith("-")
                    || !GIT_PATH.matcher(value).matches()
                    || Arrays.stream(value.split("/", -1))
                            .anyMatch(it ->
                                    it.isEmpty() || it.equals(".") || it.equals("..") || it.equalsIgnoreCase(".git"))) {
                throw new IOException("Invalid sparse path.");
            }
            rejectSymlinkParents(value);
            paths.add(value);
        }
        return List.copyOf(paths);
    }

    private void rejectSymlinkParents(final String value) throws IOException {
        var path = repository;
        for (final var part : value.split("/")) {
            path = path.resolve(part);
            if (Files.isSymbolicLink(path)
                    || (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(path))) {
                throw new IOException("Sparse path collision.");
            }
        }
    }

    private static String lines(final Iterable<String> paths) {
        final var result = new StringBuilder();
        paths.forEach(it -> result.append(it).append('\n'));
        return result.toString();
    }

    private boolean expired() {
        return Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline;
    }

    private long remainingMillis() {
        return TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
    }

    private void bounds() throws IOException {
        checkDeadline();
        if (!Files.exists(repository, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        // Native Git can rename/remove lock files and temporary pack directories during this scan.
        // Use the traversal's attributes rather than statting a potentially vanished entry again.
        try {
            Files.walkFileTree(repository, new SimpleFileVisitor<>() {
                private long total;

                @Override
                public FileVisitResult preVisitDirectory(final Path directory, final BasicFileAttributes attributes)
                        throws IOException {
                    checkDeadline();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes)
                        throws IOException {
                    checkDeadline();
                    if (attributes.isRegularFile()) {
                        if (attributes.size() > options.maxBytes() - total) {
                            throw new SourceFailure(FailureCode.DISK_LIMIT);
                        }
                        total += attributes.size();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(final Path file, final IOException failure) throws IOException {
                    checkDeadline();
                    if (failure instanceof NoSuchFileException) {
                        return FileVisitResult.CONTINUE;
                    }
                    throw failure;
                }

                @Override
                public FileVisitResult postVisitDirectory(final Path directory, final IOException failure)
                        throws IOException {
                    checkDeadline();
                    if (failure != null && !(failure instanceof NoSuchFileException)) {
                        throw failure;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final SourceFailure failure) {
            throw failure;
        } catch (final IOException failure) {
            throw new SourceFailure(FailureCode.DISK_SCAN_IO);
        }
        checkDeadline();
    }

    private void checkDeadline() throws IOException {
        if (expired()) {
            throw new SourceFailure(
                    Thread.currentThread().isInterrupted() ? FailureCode.CANCELLED : FailureCode.DEADLINE_EXCEEDED);
        }
    }

    private static int positive(final String value, final int max) {
        final int result = Integer.parseInt(value);
        if (result < 1 || result > max) {
            throw new IllegalArgumentException("Invalid bound.");
        }
        return result;
    }

    private record Options(
            URI endpoint,
            String repository,
            String head,
            int maxDepth,
            long maxBytes,
            int timeout,
            String git,
            Path caFile,
            String command,
            List<String> arguments) {
        private static Options parse(final String[] args) {
            final var values = new LinkedHashMap<String, String>();
            int i = 0;
            while (i < args.length && args[i].startsWith("--")) {
                final var key = args[i++];
                if (!List.of(
                                        "--endpoint",
                                        "--repository",
                                        "--head",
                                        "--max-depth",
                                        "--max-bytes",
                                        "--timeout",
                                        "--git",
                                        "--ca-file")
                                .contains(key)
                        || i >= args.length
                        || values.putIfAbsent(key, args[i++]) != null) {
                    throw new IllegalArgumentException("Invalid options.");
                }
            }
            if (!values.keySet()
                            .containsAll(List.of(
                                    "--endpoint", "--repository", "--head", "--max-depth", "--max-bytes", "--timeout"))
                    || i >= args.length) {
                throw new IllegalArgumentException("Missing options.");
            }
            final var endpoint = URI.create(values.get("--endpoint"));
            // Production constructor validates HTTPS and the full attempt-specific RPC path, without I/O.
            new ContextClient(
                    new AgentContextConfiguration(true, endpoint.toASCIIString(), CONTROL_TOKEN, GIT_TOKEN, ""),
                    null,
                    Configuration.of(Map.of()));
            final var repo = values.get("--repository");
            if (!REPOSITORY_NAME.matcher(repo).matches()
                    || repo.endsWith("/.")
                    || repo.endsWith("/..")
                    || !HEAD_SHA.matcher(values.get("--head")).matches()) {
                throw new IllegalArgumentException("Invalid repository identity.");
            }
            final long bytes = Long.parseLong(values.get("--max-bytes"));
            if (bytes < 1 || bytes > (1L << 40)) {
                throw new IllegalArgumentException("Invalid disk bound.");
            }
            final var command = args[i++];
            final var rest = List.copyOf(Arrays.asList(args).subList(i, args.length));
            if (!List.of("init", "expand", "deepen", "tags", "base", "credential")
                            .contains(command)
                    || ((command.equals("tags") || command.equals("base")) && !rest.isEmpty())
                    || (command.equals("deepen") && rest.size() != 1)
                    || (command.equals("credential")
                            && (rest.size() != 1
                                    || !List.of("get", "store", "erase").contains(rest.getFirst())))) {
                throw new IllegalArgumentException("Invalid command.");
            }
            final var git = values.getOrDefault("--git", "git");
            if ((!"git".equals(git) && !Path.of(git).isAbsolute())
                    || git.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Git must be a trusted executable name or absolute path.");
            }
            final var caFile = values.containsKey("--ca-file") ? Path.of(values.get("--ca-file")) : null;
            if (caFile != null && (!caFile.isAbsolute() || !Files.isRegularFile(caFile, LinkOption.NOFOLLOW_LINKS))) {
                throw new IllegalArgumentException("Native TLS trust requires an absolute regular CA bundle.");
            }
            return new Options(
                    endpoint,
                    repo,
                    values.get("--head").toLowerCase(Locale.ROOT),
                    positive(values.get("--max-depth"), 100000),
                    bytes,
                    positive(values.get("--timeout"), 3600),
                    git,
                    caFile,
                    command,
                    rest);
        }

        private List<String> flags() {
            final var result = new ArrayList<>(List.of(
                    "--endpoint",
                    endpoint.toASCIIString(),
                    "--repository",
                    repository,
                    "--head",
                    head,
                    "--max-depth",
                    Integer.toString(maxDepth),
                    "--max-bytes",
                    Long.toString(maxBytes),
                    "--timeout",
                    Integer.toString(timeout),
                    "--git",
                    git));
            if (caFile != null) {
                result.addAll(List.of("--ca-file", caFile.toString()));
            }
            return List.copyOf(result);
        }
    }
}
