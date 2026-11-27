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

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.configuration.ContextConfiguration;
import io.yupiik.ia.agent.github.configuration.WorkerOptions;
import io.yupiik.ia.agent.github.context.git.TrustedGitStore;
import io.yupiik.ia.agent.github.execution.AttemptPreparation;
import io.yupiik.ia.agent.github.model.TaskDocument;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/** Attempt-local ownership of private Git storage, authority, credentials and the HTTP listener. */
public final class BrokerAttempt implements AutoCloseable {
    private static final Pattern TASK_ID = Pattern.compile("[A-Za-z0-9-]{1,100}");

    private final ContextService service;
    private final ContextHttpServer http;
    private final Path directory;
    private final String bind;

    public BrokerAttempt(
            final ContextConfiguration conf,
            final WorkerOptions.Source source,
            final GitHubClient github,
            final JsonMapper json,
            final TaskDocument task,
            final Path home,
            final Path attempt,
            final Path attemptRoot,
            final Clock clock,
            final Duration lifetime)
            throws IOException {
        if (conf.advertised().isBlank()) {
            throw new IllegalArgumentException(
                    "Broker mode requires github.context.advertised and explicit per-worker HTTPS routing");
        }
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Broker attempt lifetime must be between one second and one day");
        }
        final var root = (conf.storageRoot().isBlank() ? home.resolve("context") : Path.of(conf.storageRoot()))
                .toAbsolutePath()
                .normalize();
        final var visible = attempt.toAbsolutePath().normalize();
        if (root.startsWith(visible)
                || visible.startsWith(root)
                || (attemptRoot != null
                        && root.startsWith(attemptRoot.toAbsolutePath().normalize()))) {
            throw new IllegalArgumentException("Private broker storage must be outside agent attempt mounts");
        }
        Path current = root.getRoot();
        for (final var segment : root) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Private broker storage must not contain symlinks");
            }
        }
        final var permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
        Files.createDirectories(root, permissions);
        if (!Files.getPosixFilePermissions(root).equals(PosixFilePermissions.fromString("rwx------"))
                || !Files.getOwner(root, LinkOption.NOFOLLOW_LINKS)
                        .equals(Files.getOwner(home, LinkOption.NOFOLLOW_LINKS))) {
            throw new IllegalArgumentException(
                    "Private broker storage requires owner-only permissions and dispatcher ownership");
        }
        if (task.id() == null
                || !TASK_ID.matcher(task.id()).matches()
                || task.attempts() < 1
                || !("yca-" + task.id() + "-" + task.attempts()).equals(task.runtimeId())) {
            throw new IllegalArgumentException("Broker storage requires its exact task, attempt and runtime binding");
        }
        final var privateRoot =
                Files.createTempDirectory(root, "context-" + task.id() + "-" + task.attempts() + "-", permissions);
        directory = privateRoot;
        bind = conf.bind();
        Files.writeString(
                directory.resolve(".owner"),
                task.runtimeId(),
                StandardOpenOption.CREATE_NEW,
                LinkOption.NOFOLLOW_LINKS);
        Files.setPosixFilePermissions(directory.resolve(".owner"), PosixFilePermissions.fromString("rw-------"));
        final var git = new TrustedGitStore(
                privateRoot,
                conf.git(),
                Duration.ofSeconds(source.commandTimeoutSeconds()),
                source.maxBytes(),
                conf.maxHttpBufferBytes(),
                github::sourceToken);
        ContextService owned = null;
        try {
            owned = new ContextService(
                    github,
                    json,
                    task,
                    clock,
                    clock.instant().plus(lifetime),
                    new ContextBudget(conf.maxRequests(), conf.maxResponseBytes()),
                    conf,
                    source,
                    git);
            service = owned;
            http = new ContextHttpServer(conf, service, json);
        } catch (final RuntimeException failure) {
            try {
                if (owned != null) {
                    owned.close();
                } else {
                    git.close();
                }
                AttemptPreparation.delete(directory);
            } catch (final IOException | RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public ContextService service() {
        return service;
    }

    public String endpoint() {
        return http.endpoint();
    }

    /** Fixed worker-side address for the trusted private gateway, never for agent bootstrap. */
    public String privateEndpoint() {
        return "http://" + (bind.contains(":") ? "[" + bind + "]" : bind) + ":" + http.port() + http.route() + "/rpc";
    }

    public Map<String, String> credentials() {
        return http.credentials();
    }

    public Map<String, Object> storageBinding(final TaskDocument task) {
        return Map.of(
                "path",
                directory.toString(),
                "taskId",
                task.id(),
                "attempt",
                task.attempts(),
                "runtime",
                task.runtimeId());
    }

    /** Only called after runtime-stop confirmation. The binding is private host recovery state. */
    public static void cleanup(final TaskDocument task, final Path storageRoot) throws IOException {
        if (task.recovery() == null || !(task.recovery().get("contextStorage") instanceof Map<?, ?> binding)) {
            return;
        }
        if (!task.id().equals(binding.get("taskId"))
                || !(binding.get("attempt") instanceof Number attempt)
                || !(binding.get("runtime") instanceof String runtime)
                || !("yca-" + task.id() + "-" + new BigDecimal(attempt.toString()).intValueExact()).equals(runtime)
                || !(binding.get("path") instanceof String path)) {
            throw new IOException("Invalid private context recovery binding");
        }
        final var root = Path.of(path).toAbsolutePath().normalize();
        if (!root.toString().equals(path)
                || !root.getParent().equals(storageRoot.toAbsolutePath().normalize())
                || !root.getFileName().toString().startsWith("context-" + task.id() + "-" + attempt.intValue() + "-")) {
            throw new IOException("Invalid private context recovery path");
        }
        Path current = root.getRoot();
        for (final var segment : root) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Symbolic private context recovery path");
            }
        }
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (new BigDecimal(attempt.toString()).intValueExact() != task.attempts()
                || !runtime.equals(task.runtimeId())) {
            throw new IOException("Private context recovery attempt mismatch");
        }
        final var marker = root.resolve(".owner");
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(root).equals(PosixFilePermissions.fromString("rwx------"))
                || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || Files.size(marker) > 256
                || !runtime.equals(Files.readString(marker))) {
            throw new IOException("Private context recovery ownership mismatch");
        }
        AttemptPreparation.delete(root);
    }

    @Override
    public void close() {
        http.close();
        try {
            AttemptPreparation.delete(directory);
        } catch (final IOException failure) {
            throw new UncheckedIOException("Private context storage cleanup failed", failure);
        }
    }
}
