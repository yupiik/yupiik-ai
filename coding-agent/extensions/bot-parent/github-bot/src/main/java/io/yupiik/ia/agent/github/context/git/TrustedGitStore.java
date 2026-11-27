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
package io.yupiik.ia.agent.github.context.git;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Host-only, disposable Git generations. The root and executable are trusted operator inputs;
 * the root MUST be disjoint from all agent mounts and inaccessible to the agent. No agent Git
 * directory is ever opened. Generations must not be modified by callers. The disk limit is soft
 * (sampled during processes), not an OS quota. Each operation has a single wall-clock deadline.
 */
public final class TrustedGitStore implements AutoCloseable {
    private static final Pattern HEADER_LINES = Pattern.compile("\r\n");
    private static final Pattern CONFIG_KEY = Pattern.compile("[a-z][a-z0-9-]*");

    private static final Pattern REPOSITORY =
            Pattern.compile("https://github\\.com/[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_][A-Za-z0-9_.-]*\\.git");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    private static final int CONTROL_LIMIT = 64 * 1024;
    private static final int METADATA_LIMIT = 8 * 1024 * 1024;
    private static final int OBJECT_LIMIT = 100_000;
    private static final int TAG_LIMIT = 10_000;
    private static final byte[] EMPTY = new byte[0];

    private final Path root;
    private final String git;
    private final long timeoutNanos;
    private final long maxBytes;
    private final long maxHttpBufferBytes;
    private final int outputLimit;
    private final Supplier<String> token;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<Process> processes = ConcurrentHashMap.newKeySet();
    private final Map<String, View> views = new HashMap<>();
    // Store-scoped and bounded; repository identity is part of the key. Cleared on close.
    private final Map<String, String> frozenTags = new HashMap<>();
    private Path home;

    public TrustedGitStore(
            final Path root,
            final String git,
            final Duration timeout,
            final long maxBytes,
            final long maxHttpBufferBytes,
            final Supplier<String> token) {
        if (root == null
                || git == null
                || git.isBlank()
                || timeout == null
                || timeout.isNegative()
                || timeout.isZero()
                || maxBytes < 1
                || maxHttpBufferBytes < 1
                || maxHttpBufferBytes > Integer.MAX_VALUE - 8L) {
            throw new IllegalArgumentException(
                    "Private root, Git executable, positive timeout and byte budget, and HTTP buffer cap from 1 to 2147483639 required");
        }
        this.root = root.toAbsolutePath().normalize();
        this.git = git;
        this.timeoutNanos = timeout.toNanos();
        this.maxBytes = maxBytes;
        this.maxHttpBufferBytes = maxHttpBufferBytes;
        this.outputLimit = (int) Math.min(maxBytes, METADATA_LIMIT);
        this.token = token;
    }

    public record View(String id, Path directory, String headSha, int depth) {}

    public record CommandResult(int exitCode, String stdout, boolean truncated) {}

    public record HttpResult(int status, Map<String, String> headers, byte[] body) {}

    /** A completed, private disk spool; the caller MUST close it after delivery or cancellation. */
    public record HttpStream(int status, Map<String, String> headers, InputStream body, long length)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            body.close();
        }
    }

    public View prepare(final String repository, final String sha, final int depth, final boolean tags)
            throws IOException {
        return prepare(repository, sha, depth, tags, null);
    }

    /** BASE must be approved by the caller as a commit in this same repository. */
    public View prepare(
            final String repository, final String sha, final int depth, final boolean tags, final String baseSha)
            throws IOException {
        return prepare(repository, sha, depth, tags, baseSha, repository);
    }

    /** Both repositories and pinned roots must be explicitly approved by the caller. */
    public View prepare(
            final String repository,
            final String sha,
            final int depth,
            final boolean tags,
            final String baseSha,
            final String baseRepository)
            throws IOException {
        if (repository == null
                || !REPOSITORY.matcher(repository).matches()
                || baseRepository == null
                || !REPOSITORY.matcher(baseRepository).matches()
                || sha == null
                || !SHA.matcher(sha).matches()
                || depth < 1
                || (baseSha != null && !SHA.matcher(baseSha).matches())) {
            throw new IllegalArgumentException(
                    "Expected canonical GitHub HTTPS URL, full lowercase SHA-1 and positive depth");
        }
        final var repositoryKey = repository.toLowerCase(Locale.ROOT);
        final long deadline = System.nanoTime() + timeoutNanos;
        acquire(deadline);
        Path generation = null;
        try {
            ensureHome();
            generation = Files.createTempDirectory(home, "generation-");
            final var source = generation.resolve("source.git");
            final var serving = generation.resolve("view.git");
            checked(
                    source.getParent(),
                    List.of("init", "--bare", "--template=", source.toString()),
                    EMPTY,
                    Map.of(),
                    deadline);
            final var auth = authentication(generation);
            try {
                final var args = new ArrayList<>(List.of(
                        "--git-dir=" + source,
                        "fetch",
                        "--no-tags",
                        "--no-recurse-submodules",
                        "--depth=" + depth,
                        "--",
                        repository,
                        sha));
                final var sameRepository = repository.equalsIgnoreCase(baseRepository);
                if (baseSha != null && !sha.equals(baseSha) && sameRepository) {
                    args.add(baseSha);
                }
                checked(generation, args, EMPTY, auth, deadline);
                if (baseSha != null && !sameRepository) {
                    checked(
                            generation,
                            List.of(
                                    "--git-dir=" + source,
                                    "fetch",
                                    "--no-tags",
                                    "--no-recurse-submodules",
                                    "--depth=" + depth,
                                    "--",
                                    baseRepository,
                                    baseSha),
                            EMPTY,
                            auth,
                            deadline);
                }
                final var head = text(source, List.of("rev-parse", "--verify", sha + "^{commit}"), deadline)
                        .strip();
                if (!sha.equals(head)) {
                    throw new IOException("Fetched head does not equal requested commit");
                }
                if (baseSha != null
                        && !baseSha.equals(
                                text(source, List.of("rev-parse", "--verify", baseSha + "^{commit}"), deadline)
                                        .strip())) {
                    throw new IOException("Fetched base does not equal requested commit");
                }
                if (tags) {
                    // Tag acquisition must never deepen or shorten the requested head's boundary.
                    final var boundaryFile = source.resolve("shallow");
                    if (Files.exists(boundaryFile) && Files.size(boundaryFile) > Math.min(maxBytes, METADATA_LIMIT)) {
                        throw new IOException("Shallow metadata exceeds budget");
                    }
                    final var savedBoundary = Files.exists(boundaryFile) ? Files.readAllBytes(boundaryFile) : null;
                    checked(
                            generation,
                            List.of(
                                    "--git-dir=" + source,
                                    "fetch",
                                    "--no-tags",
                                    "--no-recurse-submodules",
                                    "--depth=1",
                                    "--",
                                    repository,
                                    "refs/tags/*:refs/tags/*"),
                            EMPTY,
                            auth,
                            deadline);
                    if (savedBoundary == null) {
                        Files.deleteIfExists(boundaryFile);
                    } else {
                        Files.write(boundaryFile, savedBoundary);
                    }
                }
            } finally {
                Files.deleteIfExists(generation.resolve("credential-helper"));
            }
            final var roots = baseSha == null ? List.of(sha) : List.of(sha, baseSha);
            final var commitArgs = new ArrayList<>(List.of("rev-list"));
            commitArgs.addAll(roots);
            final var objectArgs = new ArrayList<>(List.of("rev-list", "--objects", "--no-object-names"));
            objectArgs.addAll(roots);
            final var commits = lines(text(source, commitArgs, deadline));
            final var objects = lines(text(source, objectArgs, deadline));
            validateIds(commits);
            validateIds(objects);
            final var boundaries = new LinkedHashSet<String>();
            final var shallow = source.resolve("shallow");
            if (Files.exists(shallow)) {
                if (Files.size(shallow) > Math.min(maxBytes, METADATA_LIMIT)) {
                    throw new IOException("Shallow metadata exceeds budget");
                }
                final var sourceBoundaries = lines(Files.readString(shallow));
                validateIds(sourceBoundaries);
                for (final var boundary : sourceBoundaries) {
                    if (commits.contains(boundary)) {
                        boundaries.add(boundary);
                    }
                }
            }
            final var tagRefs = new LinkedHashMap<String, String>();
            final var tagObjects = new LinkedHashSet<String>();
            if (tags) {
                final var tagLines = text(
                                source,
                                List.of("for-each-ref", "--format=%(objectname) %(refname)", "refs/tags/"),
                                deadline)
                        .lines()
                        .toList();
                if (tagLines.size() > TAG_LIMIT) {
                    throw new IOException("Too many Git tags");
                }
                for (final var line : tagLines) {
                    final var fields = line.split(" ", 2);
                    if (fields.length != 2
                            || !SHA.matcher(fields[0]).matches()
                            || !fields[1].startsWith("refs/tags/")
                            || fields[1].length() > 1024) {
                        throw new IOException("Invalid tag metadata");
                    }
                    final var frozen = frozenTags.get(repositoryKey + " " + fields[1]);
                    if (frozen != null && !frozen.equals(fields[0])) {
                        throw new IOException("Previously eligible Git tag has moved");
                    }
                    final var peeled = text(source, List.of("rev-parse", fields[0] + "^{}"), deadline)
                            .strip();
                    if (!commits.contains(peeled)) {
                        continue;
                    }
                    // Preserve original annotated tag objects, including nested tags, never recreate them.
                    var object = fields[0];
                    final var seen = new LinkedHashSet<String>();
                    while (!object.equals(peeled)) {
                        if (!SHA.matcher(object).matches() || seen.size() >= TAG_LIMIT || !seen.add(object)) {
                            throw new IOException("Invalid annotated tag chain");
                        }
                        final var contents = text(source, List.of("cat-file", "tag", object), deadline);
                        if (!contents.startsWith("object ") || contents.length() < 47) {
                            throw new IOException("Invalid annotated tag");
                        }
                        object = contents.substring(7, 47);
                    }
                    tagObjects.addAll(seen);
                    if (tagObjects.size() > TAG_LIMIT) {
                        throw new IOException("Too many annotated Git tag objects");
                    }
                    objects.addAll(seen);
                    validateIds(objects);
                    tagRefs.put(fields[1], fields[0]);
                }
            }
            checked(
                    generation,
                    List.of("init", "--bare", "--template=", serving.toString()),
                    EMPTY,
                    Map.of(),
                    deadline);
            // Explicit object list, no --revs, no thin pack and no reused deltas: no incidental bases can escape.
            final var prefix = generation.resolve("fresh-pack");
            final var packSha = new String(
                            checked(
                                            source,
                                            gitDir(
                                                    source,
                                                    List.of(
                                                            "pack-objects",
                                                            "--no-reuse-delta",
                                                            "--no-reuse-object",
                                                            prefix.toString())),
                                            (String.join("\n", objects) + "\n").getBytes(StandardCharsets.US_ASCII),
                                            Map.of(),
                                            deadline)
                                    .bytes(),
                            StandardCharsets.US_ASCII)
                    .strip();
            if (!SHA.matcher(packSha).matches()) {
                throw new IOException("Invalid freshly packed Git metadata");
            }
            final var packDirectory = serving.resolve("objects/pack");
            Files.move(
                    generation.resolve("fresh-pack-" + packSha + ".pack"),
                    packDirectory.resolve("pack-" + packSha + ".pack"));
            Files.move(
                    generation.resolve("fresh-pack-" + packSha + ".idx"),
                    packDirectory.resolve("pack-" + packSha + ".idx"));
            if (!boundaries.isEmpty()) {
                Files.writeString(serving.resolve("shallow"), String.join("\n", boundaries) + "\n");
            }
            checked(
                    serving,
                    gitDir(
                            serving,
                            List.of(
                                    "index-pack",
                                    "--verify",
                                    packDirectory
                                            .resolve("pack-" + packSha + ".pack")
                                            .toString())),
                    EMPTY,
                    Map.of(),
                    deadline);
            checked(
                    serving,
                    gitDir(serving, List.of("update-ref", "refs/heads/context", sha)),
                    EMPTY,
                    Map.of(),
                    deadline);
            checked(
                    serving,
                    gitDir(serving, List.of("symbolic-ref", "HEAD", "refs/heads/context")),
                    EMPTY,
                    Map.of(),
                    deadline);
            if (baseSha != null) {
                checked(
                        serving,
                        gitDir(serving, List.of("update-ref", "refs/heads/base", baseSha)),
                        EMPTY,
                        Map.of(),
                        deadline);
            }
            for (final var tag : tagRefs.entrySet()) {
                checked(
                        serving,
                        gitDir(serving, List.of("update-ref", tag.getKey(), tag.getValue())),
                        EMPTY,
                        Map.of(),
                        deadline);
            }
            checked(
                    serving,
                    gitDir(serving, List.of("fsck", "--full", "--strict", "--no-reflogs")),
                    EMPTY,
                    Map.of(),
                    deadline);
            if (!sha.equals(
                    text(serving, List.of("rev-parse", "HEAD"), deadline).strip())) {
                throw new IOException("Serving head verification failed");
            }
            delete(source);
            secure(generation);
            budget();
            check(deadline);
            final var view = new View(UUID.randomUUID().toString(), serving, sha, depth);
            if (tagRefs.keySet().stream()
                            .filter(name -> !frozenTags.containsKey(repositoryKey + " " + name))
                            .count()
                    > TAG_LIMIT - frozenTags.size()) {
                throw new IOException("Frozen Git tag metadata budget exceeded");
            }
            tagRefs.forEach((name, oid) -> frozenTags.putIfAbsent(repositoryKey + " " + name, oid));
            views.put(view.id(), view);
            generation = null;
            return view;
        } finally {
            try {
                delete(generation);
            } finally {
                lock.unlock();
            }
        }
    }

    /** Validates the original raw argv at the execution boundary and caps stdout at 64KiB. */
    public CommandResult read(final View view, final List<String> validatedArgv) throws IOException {
        final var canonical = new GitReadPolicy().validate(validatedArgv);
        final long deadline = System.nanoTime() + timeoutNanos;
        acquire(deadline);
        try {
            verify(view);
            final var result = run(
                    view.directory(),
                    gitDir(view.directory(), canonical),
                    EMPTY,
                    Map.of(),
                    deadline,
                    Math.min(CONTROL_LIMIT, outputLimit),
                    true);
            return new CommandResult(
                    result.exit(), new String(result.bytes(), StandardCharsets.UTF_8), result.truncated());
        } finally {
            lock.unlock();
        }
    }

    /** GET + service=git-upload-pack, or POST + empty query. No other CGI routes are exposed. */
    public HttpResult http(
            final View view, final String method, final String query, final byte[] body, final String protocol)
            throws IOException {
        if (body == null || body.length > maxHttpBufferBytes) {
            throw new IllegalArgumentException("Compatibility HTTP input exceeds limit");
        }
        try (final var response = httpStream(view, method, query, body, protocol)) {
            if (response.length() > maxHttpBufferBytes) {
                throw new IOException("Compatibility HTTP output exceeds limit; use httpStream");
            }
            return new HttpResult(
                    response.status(), response.headers(), response.body().readAllBytes());
        }
    }

    /** Spools fully before returning: no process survives this call; length is the actual body byte count. */
    public HttpStream httpStream(
            final View view, final String method, final String query, final byte[] body, final String protocol)
            throws IOException {
        final boolean get = "GET".equals(method) && "service=git-upload-pack".equals(query);
        final boolean post = "POST".equals(method) && (query == null || query.isEmpty());
        if ((!get && !post)
                || body == null
                || body.length > Math.min(maxBytes, maxHttpBufferBytes)
                || (get && body.length != 0)
                || (protocol != null
                        && !protocol.isEmpty()
                        && !"version=1".equals(protocol)
                        && !"version=2".equals(protocol))) {
            throw new IllegalArgumentException("Only bounded Git upload-pack smart HTTP is supported");
        }
        final long deadline = System.nanoTime() + timeoutNanos;
        acquire(deadline);
        Path spool = null;
        try {
            verify(view);
            final var env = new HashMap<String, String>();
            env.put("GIT_PROJECT_ROOT", view.directory().getParent().toString());
            env.put("GIT_HTTP_EXPORT_ALL", "1");
            env.put("PATH_INFO", "/view.git/" + (get ? "info/refs" : "git-upload-pack"));
            env.put("REQUEST_METHOD", method);
            env.put("QUERY_STRING", get ? query : "");
            env.put("CONTENT_TYPE", post ? "application/x-git-upload-pack-request" : "");
            env.put("CONTENT_LENGTH", Integer.toString(body.length));
            env.put("SERVER_PROTOCOL", "HTTP/1.1");
            env.put("REMOTE_ADDR", "127.0.0.1");
            if (protocol != null && !protocol.isEmpty()) {
                env.put("HTTP_GIT_PROTOCOL", protocol);
                env.put("GIT_PROTOCOL", protocol);
            }
            spool = Files.createTempFile(
                    home,
                    "http-",
                    ".spool",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            final var result =
                    run(view.directory().getParent(), List.of("http-backend"), body, env, deadline, 0, false, spool);
            if (result.exit() != 0) {
                throw new IOException("Trusted Git HTTP command failed");
            }
            final var response = parseHttp(spool);
            spool = null;
            return response;
        } finally {
            try {
                if (spool != null) {
                    Files.deleteIfExists(spool);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private HttpStream parseHttp(final Path spool) throws IOException {
        final var stream = new BufferedInputStream(Files.newInputStream(spool));
        try {
            final var header = new ByteArrayOutputStream();
            int tail = 0;
            while (header.size() < CONTROL_LIMIT) {
                final int next = stream.read();
                if (next < 0) {
                    break;
                }
                header.write(next);
                tail = (tail << 8) | next;
                if (tail == 0x0d0a0d0a) {
                    break;
                }
            }
            if (tail != 0x0d0a0d0a) {
                throw new IOException("Invalid or oversized Git CGI headers");
            }
            final var response = header.toByteArray();
            final var metadata = parseHeaders(new String(response, 0, response.length - 4, StandardCharsets.US_ASCII));
            final var deleting = new FilterInputStream(stream) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        Files.deleteIfExists(spool);
                    }
                }
            };
            return new HttpStream(metadata.status(), metadata.headers(), deleting, Files.size(spool) - response.length);
        } catch (final IOException | RuntimeException e) {
            stream.close();
            throw e;
        }
    }

    private HttpResult parseHeaders(final String response) throws IOException {
        int status = 200;
        boolean hasStatus = false;
        final var headers = new LinkedHashMap<String, String>();
        for (final var line : HEADER_LINES.split(response)) {
            final int colon = line.indexOf(':');
            if (colon < 1) {
                throw new IOException("Malformed Git CGI header");
            }
            final var key = line.substring(0, colon).toLowerCase(Locale.ROOT);
            final var value = line.substring(colon + 1).strip();
            if (!CONFIG_KEY.matcher(key).matches() || value.chars().anyMatch(c -> c < 32 || c > 126)) {
                throw new IOException("Unsafe Git CGI header");
            }
            if ("status".equals(key)) {
                if (hasStatus) {
                    throw new IOException("Duplicate Git CGI status");
                }
                hasStatus = true;
                try {
                    status = Integer.parseInt(value.split(" ", 2)[0]);
                } catch (final NumberFormatException e) {
                    throw new IOException("Malformed Git CGI status");
                }
                if (status < 100 || status > 599) {
                    throw new IOException("Invalid Git CGI status");
                }
            } else if (Set.of("content-type", "cache-control", "expires", "pragma")
                    .contains(key)) {
                if (value.chars().anyMatch(c -> c < 32 || c == 127) || headers.putIfAbsent(key, value) != null) {
                    throw new IOException("Unsafe Git CGI header");
                }
            }
        }
        return new HttpResult(status, Map.copyOf(headers), EMPTY);
    }

    private Map<String, String> authentication(final Path generation) throws IOException {
        final var secret = token == null ? null : token.get();
        if (secret == null || secret.isEmpty()) {
            return Map.of();
        }
        if (secret.indexOf('\0') >= 0 || secret.indexOf('\n') >= 0 || secret.indexOf('\r') >= 0) {
            throw new IOException("Invalid Git credential");
        }
        final var helper = generation.resolve("credential-helper");
        Files.writeString(
                helper,
                "case \"$1\" in get) printf 'username=x-access-token\\npassword=%s\\n\\n' \"$YCA_GIT_TOKEN\";; store|erase) :;; *) exit 1;; esac\n");
        Files.setPosixFilePermissions(helper, PosixFilePermissions.fromString("rw-------"));
        // Private writable storage may be mounted noexec. Execute the trusted system shell,
        // not a generated executable. Git credential helpers explicitly support shell commands;
        // askpass executable handling differs across Git versions and must not be relied upon.
        final var command = "!/bin/sh '" + helper.toString().replace("'", "'\\''") + "'";
        return Map.of("YCA_GIT_CREDENTIAL_HELPER", command, "YCA_GIT_TOKEN", secret);
    }

    private String text(final Path repo, final List<String> args, final long deadline) throws IOException {
        final int limit = Set.of("rev-list", "for-each-ref").contains(args.getFirst())
                ? outputLimit
                : Math.min(CONTROL_LIMIT, outputLimit);
        final var result = run(repo, gitDir(repo, args), EMPTY, Map.of(), deadline, limit, false);
        if (result.exit() != 0) {
            throw new IOException("Trusted Git metadata command failed");
        }
        return new String(result.bytes(), StandardCharsets.UTF_8);
    }

    private List<String> gitDir(final Path repo, final List<String> args) {
        final var result = new ArrayList<String>();
        result.add("--git-dir=" + repo);
        result.addAll(args);
        return result;
    }

    private Raw checked(
            final Path cwd,
            final List<String> args,
            final byte[] input,
            final Map<String, String> env,
            final long deadline)
            throws IOException {
        final var result = run(cwd, args, input, env, deadline, Math.min(CONTROL_LIMIT, outputLimit), false);
        if (result.exit() != 0) {
            // Never include upstream stderr, arguments or environment (which can contain credentials).
            throw new IOException("Trusted Git command failed (exit " + result.exit() + ")");
        }
        return result;
    }

    private record Raw(int exit, byte[] bytes, boolean truncated) {}

    private Raw run(
            final Path cwd,
            final List<String> args,
            final byte[] input,
            final Map<String, String> extra,
            final long deadline,
            final int limit,
            final boolean truncate)
            throws IOException {
        return run(cwd, args, input, extra, deadline, limit, truncate, null);
    }

    private Raw run(
            final Path cwd,
            final List<String> args,
            final byte[] input,
            final Map<String, String> extra,
            final long deadline,
            final int limit,
            final boolean truncate,
            final Path spool)
            throws IOException {
        check(deadline);
        if (input.length > maxBytes) {
            throw new IOException("Trusted Git input exceeds byte budget");
        }
        final var command = new ArrayList<>(List.of(git, "--no-pager", "--no-replace-objects"));
        for (final var config : List.of(
                "credential.helper=",
                "core.hooksPath=/dev/null",
                "core.attributesFile=/dev/null",
                "core.pager=cat",
                "protocol.allow=never",
                "protocol.https.allow=" + (args.contains("fetch") ? "always" : "never"),
                "http.followRedirects=false",
                "http.receivepack=false",
                "http.uploadpack=true",
                "http.getanyfile=false",
                "uploadpack.allowFilter=false",
                "uploadpack.allowAnySHA1InWant=false",
                "uploadpack.allowTipSHA1InWant=false",
                "uploadpack.allowReachableSHA1InWant=false",
                "gc.auto=0",
                "maintenance.auto=false",
                "fetch.writeCommitGraph=false",
                "fetch.fsckObjects=true",
                "transfer.fsckObjects=true")) {
            command.add("-c");
            command.add(config);
        }
        final var credential = extra.get("YCA_GIT_CREDENTIAL_HELPER");
        if (credential != null) {
            command.add("-c");
            command.add("credential.helper=" + credential);
        }
        command.addAll(args);
        final var builder =
                new ProcessBuilder(command).directory(cwd.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
        if (spool != null) {
            builder.redirectOutput(spool.toFile());
        }
        final var environment = builder.environment();
        environment.clear();
        environment.putAll(Map.of(
                "PATH",
                "/usr/bin:/bin",
                "HOME",
                "/dev/null",
                "XDG_CONFIG_HOME",
                "/dev/null",
                "GIT_CONFIG_NOSYSTEM",
                "1",
                "GIT_CONFIG_SYSTEM",
                "/dev/null",
                "GIT_CONFIG_GLOBAL",
                "/dev/null",
                "GIT_TERMINAL_PROMPT",
                "0",
                "GIT_ASKPASS",
                "/bin/false",
                "GIT_ATTR_NOSYSTEM",
                "1",
                "GIT_NO_LAZY_FETCH",
                "1"));
        environment.put("GIT_OPTIONAL_LOCKS", "0");
        environment.put("LC_ALL", "C");
        environment.putAll(extra);
        final Process process;
        try {
            process = builder.start();
        } catch (final IOException e) {
            throw new IOException(
                    "Cannot start trusted Git; install Git with http-backend and configure its executable");
        }
        processes.add(process);
        if (closed.get()) {
            kill(process); // Close can race with start(), before admission to the process set.
        }
        final var failure = new AtomicReference<IOException>();
        final var output = new ByteArrayOutputStream();
        final var overflow = new AtomicBoolean();
        final var reader = Thread.ofVirtual().start(() -> {
            try (final var stream = process.getInputStream()) {
                final var buffer = new byte[8192];
                int n;
                while ((n = stream.read(buffer)) >= 0) {
                    final int accepted = Math.min(n, limit - output.size());
                    output.write(buffer, 0, accepted);
                    if (accepted < n) {
                        overflow.set(true);
                        if (!truncate) {
                            kill(process);
                            break;
                        }
                    }
                }
            } catch (final IOException e) {
                failure.compareAndSet(null, e);
            }
        });
        final var writer = Thread.ofVirtual().start(() -> {
            try (final var stream = process.getOutputStream()) {
                stream.write(input);
            } catch (final IOException e) {
                // Early rejection by Git is represented by its exit status, not a broken pipe.
            }
        });
        try {
            while (!process.waitFor(20, TimeUnit.MILLISECONDS)) {
                check(deadline);
                // Git init briefly creates a symlink to "testing" to probe filesystem support.
                // Do not follow it; require all links to be gone in the post-exit budget check.
                budget(args.getFirst().equals("init"));
            }
            while (reader.isAlive() || writer.isAlive()) {
                check(deadline);
                reader.join(10);
                writer.join(10);
            }
            check(deadline);
            budget();
            if (overflow.get() && !truncate) {
                throw new IOException("Trusted Git output exceeds byte budget");
            }
            if (failure.get() != null) {
                throw new IOException("Cannot read trusted Git output", failure.get());
            }
            return new Raw(process.exitValue(), output.toByteArray(), overflow.get());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted trusted Git operation", e);
        } finally {
            kill(process);
            try {
                process.waitFor(1, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            processes.remove(process);
            try {
                process.getInputStream().close();
                process.getOutputStream().close();
            } finally {
                reader.interrupt();
                writer.interrupt();
            }
        }
    }

    private void acquire(final long deadline) throws IOException {
        try {
            if (!lock.tryLock(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                throw new IOException("Timed out waiting for trusted Git store");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for trusted Git store", e);
        }
        try {
            check(deadline);
        } catch (final IOException e) {
            lock.unlock();
            throw e;
        }
    }

    private void check(final long deadline) throws IOException {
        if (closed.get()) {
            throw new IOException("Trusted Git store is closed");
        }
        if (System.nanoTime() - deadline >= 0) {
            throw new IOException("Trusted Git operation timed out");
        }
    }

    private void ensureHome() throws IOException {
        Path current = root.getRoot();
        for (final var segment : root) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Trusted Git root must not contain symbolic links");
            }
        }
        Files.createDirectories(
                root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        // Compare against an inode created by this process, never the forgeable user.name property.
        final var ownerProbe = Files.createTempFile(
                root,
                ".owner-",
                ".probe",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            if (!Files.getOwner(root, LinkOption.NOFOLLOW_LINKS)
                    .equals(Files.getOwner(ownerProbe, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("Trusted Git root must be owned by the current user");
            }
        } finally {
            Files.deleteIfExists(ownerProbe);
        }
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        if (home == null) {
            home = Files.createTempDirectory(
                    root, "store-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
    }

    private void verify(final View view) throws IOException {
        if (view == null || views.get(view.id()) != view) {
            throw new IOException("Unknown trusted Git generation");
        }
    }

    private void budget() throws IOException {
        budget(false);
    }

    private void budget(final boolean initializing) throws IOException {
        if (home == null) {
            return;
        }
        final long[] size = {0};
        Files.walkFileTree(home, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(final Path path, final BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink() && !initializing) {
                    throw new IOException("Symbolic link in trusted Git storage");
                }
                if (attributes.isRegularFile() || attributes.isSymbolicLink()) {
                    if (attributes.size() > maxBytes - size[0]) {
                        throw new IOException("Trusted Git scratch disk budget exceeded");
                    }
                    size[0] += attributes.size();
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(final Path path, final IOException error) throws IOException {
                if (error instanceof NoSuchFileException) {
                    // Git atomically renames/removes temporary files while the soft quota is sampled.
                    return FileVisitResult.CONTINUE;
                }
                throw error;
            }
        });
    }

    private static LinkedHashSet<String> lines(final String value) {
        return new LinkedHashSet<>(value.lines().filter(s -> !s.isEmpty()).toList());
    }

    private static void validateIds(final Set<String> ids) throws IOException {
        if (ids.size() > OBJECT_LIMIT
                || ids.stream().anyMatch(s -> !SHA.matcher(s).matches())) {
            throw new IOException("Invalid Git object metadata");
        }
    }

    private static void secure(final Path directory) throws IOException {
        try (final var paths = Files.walk(directory)) {
            for (final var path : paths.toList()) {
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Symbolic link in trusted Git generation");
                }
                Files.setPosixFilePermissions(
                        path, PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwx------" : "rw-------"));
            }
        }
    }

    private static void kill(final Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static void delete(final Path directory) throws IOException {
        if (directory != null && Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            try (final var paths = Files.walk(directory)) {
                for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        closed.set(true);
        processes.forEach(TrustedGitStore::kill);
        acquireClose();
        try {
            views.clear();
            frozenTags.clear();
            delete(home);
        } finally {
            lock.unlock();
        }
    }

    private void acquireClose() throws IOException {
        try {
            if (!lock.tryLock(Math.min(timeoutNanos, TimeUnit.SECONDS.toNanos(3)), TimeUnit.NANOSECONDS)) {
                throw new IOException("Timed out closing trusted Git store; cleanup must be retried");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted closing trusted Git store");
        }
    }
}
