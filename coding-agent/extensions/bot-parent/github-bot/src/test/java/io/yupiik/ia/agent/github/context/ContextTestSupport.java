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

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.configuration.ContextConfiguration;
import io.yupiik.ia.agent.github.configuration.WorkerOptions;
import io.yupiik.ia.agent.github.context.git.TrustedGitStore;
import io.yupiik.ia.agent.github.store.MutableTask;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import io.yupiik.ia.agent.github.support.TestJava;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/** Loopback-only upstream and private native Git fixture shared by the context integration tests. */
final class ContextTestSupport implements AutoCloseable {
    static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");
    static final String PIN = "a".repeat(40);
    static final String OTHER_PIN = "b".repeat(40);
    final JsonMapper json = BotJsonMapper.create(ignored -> Optional.empty());
    final MutableClock clock = new MutableClock();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final AtomicReference<String> additionalPin = new AtomicReference<>(OTHER_PIN);
    final Path root;
    final HttpServer upstream;
    final GitHubClient github;
    final List<ContextService> services = new ArrayList<>();

    ContextTestSupport(final Path root) throws IOException {
        this.root = root;
        Files.createDirectories(root);
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            try (exchange) {
                final var path = exchange.getRequestURI().toString();
                requests.add(path);
                final String body =
                        switch (path) {
                            case "/repos/acme/primary" ->
                                "{\"id\":101,\"full_name\":\"acme/primary\",\"default_branch\":\"main\"}";
                            case "/repos/acme/extra" ->
                                "{\"id\":202,\"full_name\":\"acme/extra\",\"default_branch\":\"release/stable\"}";
                            case "/repos/acme/extra/commits/release%2Fstable",
                                    "/repos/acme/extra/commits/release/stable" ->
                                "{\"sha\":\"" + additionalPin.get() + "\"}";
                            case "/repos/acme/primary/issues/7/comments?per_page=100&page=1" ->
                                "[{\"id\":1,\"body\":\"fixed task comment\",\"html_url\":\"https://untrusted.invalid\"}]";
                            default -> null;
                        };
                final var bytes = (body == null ? "{\"message\":\"unexpected test route\"}" : body)
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        upstream.start();
        github = new GitHubClient(
                URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()),
                json,
                () -> "test-upstream-token",
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    ContextConfiguration configuration(final String repositories) {
        return new ContextConfiguration(
                "127.0.0.1",
                0,
                "https://worker.example.invalid",
                "",
                "/usr/bin/git",
                repositories,
                4096,
                16 * 1024 * 1024,
                67108864L);
    }

    ContextService service(
            final ContextConfiguration conf, final ContextBudget budget, final String pin, final String executable) {
        final var task = new MutableTask(TaskFixtures.task(
                Map.of("id", "fixed-task", "repository", "acme/primary", "number", 7, "kind", "solve")));
        task.strictSolveBaseSha(pin);
        final var store = new TrustedGitStore(
                root.resolve("private-" + services.size()),
                executable,
                Duration.ofSeconds(30),
                8 * 1024 * 1024,
                conf.maxHttpBufferBytes(),
                () -> "test-upstream-token");
        final var service = new ContextService(
                github,
                json,
                task.document(),
                clock,
                NOW.plusSeconds(60),
                budget,
                conf,
                new WorkerOptions.Source(WorkerOptions.SourceMode.git, "", 8, 8 * 1024 * 1024, 5),
                store);
        services.add(service);
        return service;
    }

    ContextService service() {
        return service(
                configuration("acme/extra"), new ContextBudget(4096, 16 * 1024 * 1024), PIN, "/not/executed/git");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(final Map<String, Object> response) {
        assertEquals(true, response.get("ok"));
        return (Map<String, Object>) response.get("body");
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> parse(final String response) {
        return (Map<String, Object>) json.fromString(Object.class, response);
    }

    record GitFixture(Path directory, String head, String old, Path executable) {}

    GitFixture gitFixture() throws Exception {
        return gitFixture(root);
    }

    static GitFixture gitFixture(final Path root) throws Exception {
        Files.createDirectories(root);
        final var repo = Files.createDirectory(root.resolve("repository with 'quotes'"));
        git(repo, "init", "--template=");
        git(
                repo,
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.invalid",
                "commit",
                "--allow-empty",
                "-m",
                "old");
        final var old = git(repo, "rev-parse", "HEAD").strip();
        Files.writeString(repo.resolve("file.txt"), "pinned source\n");
        git(repo, "add", "file.txt");
        git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-m", "head");
        final var head = git(repo, "rev-parse", "HEAD").strip();
        final var wrapper = root.resolve("Transport with 'quotes'");
        transport(wrapper, repo.toUri().toASCIIString(), "https://github.com/acme/primary.git");
        return new GitFixture(repo, head, old, wrapper);
    }

    /** Construct completely before publishing to concurrent readers; callers must not mutate it. */
    static GitFixture sourceFixture(final Path root) throws Exception {
        final var repository = gitFixture(root);
        final var repo = repository.directory();
        Files.createDirectories(repo.resolve("src"));
        Files.createDirectories(repo.resolve("docs"));
        Files.writeString(repo.resolve("src/code.txt"), "source\n");
        Files.writeString(repo.resolve("docs/guide.txt"), "guide\n");
        git(repo, "add", ".");
        git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--amend", "--no-edit");
        git(
                repo,
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.invalid",
                "tag",
                "-a",
                "v1",
                "-m",
                "release",
                repository.old());
        return new GitFixture(repo, git(repo, "rev-parse", "HEAD").strip(), repository.old(), repository.executable());
    }

    private static Path transport(final Path executable, final String repository, final String remotes)
            throws IOException {
        // Rotate positional arguments once: no eval, word splitting or reparsing of Git arguments.
        Files.writeString(executable, """
                #!/bin/sh
                for argument do
                    shift
                    case "$argument" in
                        %s) argument=%s ;;
                    esac
                    set -- "$@" "$argument"
                done
                exec /usr/bin/git -c protocol.file.allow=always "$@"
                """.formatted(remotes, quote(repository)));
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        return executable;
    }

    static Path launcher(final Path executable, final Class<?> main, final String argument) throws IOException {
        if (main == ContextServiceTest.ForkTransport.class) {
            // The fork transport only substitutes these exact arguments. Keep local operations
            // native too, rather than starting a JVM just to forward them unchanged.
            return transport(
                    executable, argument, "https://github.com/acme/primary.git|https://github.com/acme/extra.git");
        }
        // Launch already compiled test classes with the same JVM as Surefire. No source compilation,
        // mutable system properties, ambient PATH lookup or dependency on /usr/bin/java.
        Files.writeString(
                executable,
                "#!/bin/sh\nexec "
                        + TestJava.javaCommand(
                                        "-cp",
                                        System.getProperty(
                                                "surefire.test.class.path", System.getProperty("java.class.path")),
                                        main.getName(),
                                        argument)
                                .stream()
                                .map(ContextTestSupport::quote)
                                .collect(joining(" "))
                        + " \"$@\"\n");
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        return executable;
    }

    private static String quote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    public static final class StalledGit {
        public static void main(final String... args) throws Exception {
            final var pid = Path.of(args[0]);
            final var pending = pid.resolveSibling(pid.getFileName() + ".pending");
            Files.writeString(pending, Long.toString(ProcessHandle.current().pid()));
            Files.move(pending, pid, StandardCopyOption.ATOMIC_MOVE);
            new CountDownLatch(1).await();
        }
    }

    static String git(final Path directory, final String... arguments) throws Exception {
        final var command = new ArrayList<>(List.of("/usr/bin/git", "-c", "core.hooksPath=/dev/null"));
        command.addAll(List.of(arguments));
        final var builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        final var process = builder.start();
        try {
            process.getOutputStream().close();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Native Git exceeded fixture deadline");
            final var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), "Native Git fixture failed");
            return output;
        } finally {
            process.destroyForcibly();
        }
    }

    static void await(final BooleanSupplier condition) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "Condition did not complete before deadline");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    @Override
    public void close() {
        services.forEach(ContextService::close);
        github.close();
        upstream.stop(0);
        json.close();
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(NOW);

        void advance(final Duration duration) {
            now.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
