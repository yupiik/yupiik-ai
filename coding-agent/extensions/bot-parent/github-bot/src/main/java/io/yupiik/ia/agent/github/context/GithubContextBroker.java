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

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.model.TaskKind;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Per-attempt read-only mailbox. Pinned directory handles prevent agent-controlled path substitution. */
public final class GithubContextBroker implements AutoCloseable {
    private static final Pattern REPOSITORY_NAME =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}");

    private static final int REQUEST_LIMIT = 4096;
    private static final int RESPONSE_LIMIT = 4 * 1024 * 1024;
    private final GitHubClient github;
    private final TaskDiscussionContext discussions;
    private final ContextService service;
    private final JsonMapper json;
    private final String repository;
    private final long number;
    private final SecureDirectoryStream<Path> requests;
    private final SecureDirectoryStream<Path> responses;
    private final Thread worker;
    private volatile boolean closed;
    private long bytes;

    public GithubContextBroker(
            final GitHubClient github, final JsonMapper json, final TaskDocument task, final Path attempt)
            throws IOException {
        this(github, null, json, task, attempt);
    }

    public GithubContextBroker(
            final ContextService service, final JsonMapper json, final TaskDocument task, final Path attempt)
            throws IOException {
        this(null, Objects.requireNonNull(service), json, task, attempt);
    }

    private GithubContextBroker(
            final GitHubClient github,
            final ContextService service,
            final JsonMapper json,
            final TaskDocument task,
            final Path attempt)
            throws IOException {
        this.github = github;
        this.discussions = service == null ? new TaskDiscussionContext(github, task) : null;
        this.service = service;
        this.json = json;
        final var repo = task.repository();
        if (!(task.kind() == TaskKind.review)
                || task.repository() == null
                || !REPOSITORY_NAME.matcher(repo).matches()
                || repo.endsWith("/.")
                || repo.endsWith("/..")
                || task.number() == null) {
            throw new IllegalArgumentException("Expected a fixed review task");
        }
        repository = repo;
        number = new BigDecimal(task.number().toString()).longValueExact();
        if (number <= 0) {
            throw new IllegalArgumentException("Expected a positive task number");
        }
        final var workspace = attempt.toAbsolutePath().normalize().resolve("workspace");
        Path current = workspace.getRoot();
        for (final var part : workspace) {
            current = current.resolve(part);
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Broker requires existing non-symbolic workspace");
            }
        }
        final var root = Files.createDirectory(workspace.resolve("github-broker"));
        final var input = Files.createDirectory(root.resolve("requests"));
        final var output = Files.createDirectory(root.resolve("responses"));
        requests = open(input);
        try {
            responses = open(output);
        } catch (final IOException | RuntimeException e) {
            requests.close();
            throw e;
        }
        worker = Thread.ofVirtual().name("github-context-broker").start(this::run);
    }

    /**
     * Checks the dispatcher's secure directory-handle support on the prepared workspace filesystem.
     * This does not replace the mandatory checks when constructing each broker.
     *
     * @param workspace existing prepared workspace, before snapshot or credential access
     * @throws IOException if the workspace cannot be opened securely
     */
    public static void verifyRuntime(final Path workspace) throws IOException {
        try (final var ignored = open(workspace)) {
            // Acquiring and closing the same handle used by the broker is the capability check.
        }
    }

    private static SecureDirectoryStream<Path> open(final Path path) throws IOException {
        final DirectoryStream<Path> stream = Files.newDirectoryStream(path);
        if (stream instanceof SecureDirectoryStream<Path> secure) {
            return secure;
        }
        stream.close();
        throw new IOException("GitHub context broker requires SecureDirectoryStream in the dispatcher runtime. "
                + "Use a SecureDirectoryStream-compatible glibc Java 25 runtime and a workspace filesystem "
                + "provider supporting secure directory handles; verify the actual dispatcher image and filesystem. "
                + "Security checks cannot be disabled.");
    }

    private void run() {
        final var used = new boolean[32];
        try {
            while (!closed) {
                for (int slot = 0; slot < used.length && !closed; slot++) {
                    if (used[slot]) {
                        continue;
                    }
                    final var name = Path.of(slot + ".json");
                    final byte[] request;
                    try (final var channel = requests.newByteChannel(
                            name,
                            Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                        used[slot] = true;
                        request = read(channel);
                    } catch (final NoSuchFileException absent) {
                        continue;
                    } catch (final IOException | RuntimeException invalid) {
                        used[slot] = true;
                        respond(name, new Response(false, null, "Invalid request"));
                        continue;
                    }
                    Response response;
                    try {
                        if (bytes >= 16L * 1024 * 1024) {
                            throw new IllegalArgumentException("Budget exhausted");
                        }
                        response = new Response(
                                true,
                                fetch(json.fromString(Request.class, new String(request, StandardCharsets.UTF_8))),
                                null);
                    } catch (final RuntimeException invalid) {
                        response = new Response(false, null, "Request unavailable or rejected");
                    }
                    respond(name, response);
                }
                Thread.sleep(Duration.ofMillis(100));
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                requests.close();
            } catch (final IOException ignored) {
                // No owned file descriptors remain after close below or worker completion.
            }
            try {
                responses.close();
            } catch (final IOException ignored) {
                // Closing a failed filesystem handle cannot safely be retried.
            }
        }
    }

    private static byte[] read(final SeekableByteChannel channel) throws IOException {
        if (channel.size() <= 0 || channel.size() > REQUEST_LIMIT) {
            throw new IOException("Expected bounded regular request data");
        }
        final var buffer = ByteBuffer.allocate(REQUEST_LIMIT + 1);
        while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
            // Bound actual bytes, not only the mutable size metadata.
        }
        if (buffer.position() > REQUEST_LIMIT) {
            throw new IOException("Oversized broker request");
        }
        return Arrays.copyOf(buffer.array(), buffer.position());
    }

    private Object fetch(final Request request) {
        if (request == null) {
            throw new IllegalArgumentException("Invalid broker request");
        }
        final Object result;
        if (service == null) {
            result = discussions.read(request.operation(), request.page(), null);
        } else {
            final var response = service.call(Map.of("operation", request.operation(), "page", request.page()));
            final var body = (Map<?, ?>) response.get("body");
            result = body.get("data");
        }
        // Preserve the legacy filesystem list shape while both transports share the fixed task reader.
        return result instanceof Map<?, ?> page && page.containsKey("entries") ? page.get("entries") : result;
    }

    private void respond(final Path name, final Response response) {
        byte[] encoded = json.toString(response).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > RESPONSE_LIMIT || bytes + encoded.length > 16L * 1024 * 1024) {
            encoded = json.toString(new Response(false, null, "Response budget exhausted"))
                    .getBytes(StandardCharsets.UTF_8);
        }
        bytes += encoded.length;
        try (final var output = responses.newByteChannel(
                name, Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS))) {
            final var data = ByteBuffer.wrap(encoded);
            while (data.hasRemaining()) {
                output.write(data);
            }
        } catch (final IOException | RuntimeException ignored) {
            // An agent-created or unavailable response is never overwritten and never retried.
        }
    }

    @Override
    public void close() {
        closed = true;
        worker.interrupt();
        try {
            worker.join(Duration.ofSeconds(5));
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Broker shutdown interrupted", interrupted);
        }
        if (worker.isAlive()) {
            throw new IllegalStateException("Broker shutdown not confirmed");
        }
    }

    @JsonModel
    public record Request(
            @Property(documentation = "One of pull, files, reviews, reviewComments or issueComments.")
            String operation,

            @Property(documentation = "Locally constructed page number between 1 and 10.")
            int page) {}

    @JsonModel
    public record Response(
            @Property(documentation = "Whether the read-only request succeeded.")
            boolean ok,

            @Property(documentation = "Allowlisted context fields, always untrusted task data.")
            Object body,

            @Property(documentation = "Generic failure classification without credential details.")
            String error) {}
}
