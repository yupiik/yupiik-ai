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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Opt in with -Dgithub.bot.dispatcher.live.image=<already-built-dispatcher-image>. */
class GithubContextBrokerImageTest {
    @TempDir
    Path temporary;

    @Test
    void realDispatcherSupportsSecureMailboxOnTmpfsAndWritableBindMount() throws Exception {
        final var image = System.getProperty("github.bot.dispatcher.live.image");
        assumeTrue(image != null, "Set github.bot.dispatcher.live.image to run the actual dispatcher image");
        assertTrue(!image.isBlank(), "github.bot.dispatcher.live.image must name an image");
        final var testClasses = Path.of(GithubContextBrokerImageTest.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toAbsolutePath();
        assertTrue(Files.isDirectory(testClasses), "Mountable target/test-classes is required");
        for (final var storage : List.of("tmpfs", "bind")) {
            final var name = "yca-broker-image-" + UUID.randomUUID();
            final var command = new ArrayList<>(List.of(
                    "docker",
                    "run",
                    "--rm",
                    "--pull=never",
                    "--name",
                    name,
                    "--read-only",
                    "--cap-drop",
                    "ALL",
                    "--security-opt",
                    "no-new-privileges",
                    "--user",
                    "10001:10001",
                    "--network",
                    "none",
                    "--tmpfs",
                    "/tmp:rw,nosuid,nodev,noexec,size=16m,mode=1777",
                    "--mount",
                    "type=bind,source=" + testClasses + ",target=/probe-tests,readonly"));
            if ("tmpfs".equals(storage)) {
                command.addAll(List.of(
                        "--tmpfs", "/probe-work:rw,nosuid,nodev,noexec,size=16m,mode=0700,uid=10001,gid=10001"));
            } else {
                final var bind = Files.createDirectory(temporary.resolve("bind-work"));
                Files.setPosixFilePermissions(bind, PosixFilePermissions.fromString("rwxrwxrwx"));
                command.addAll(List.of("--mount", "type=bind,source=" + bind + ",target=/probe-work"));
            }
            command.addAll(List.of(
                    "--entrypoint",
                    "java",
                    image,
                    "--add-modules",
                    "jdk.httpserver",
                    "-Djava.io.tmpdir=/tmp",
                    "-cp",
                    "/app/resources:/app/classes:/app/libs/*:/probe-tests",
                    Probe.class.getName(),
                    "/probe-work"));
            try {
                final var result = execute(command, Duration.ofSeconds(90));
                assertEquals(0, result.exitCode(), storage + " dispatcher probe failed:\n" + result.output());
                assertTrue(result.output().contains("BROKER_IMAGE_OK"), storage + ": " + result.output());
            } finally {
                // Host-controlled name only, never any container-supplied identity or command.
                final var cleanup = execute(List.of("docker", "rm", "-f", name), Duration.ofSeconds(15));
                assertTrue(
                        cleanup.exitCode() == 0 || cleanup.output().contains("No such container"),
                        "Container cleanup failed: " + cleanup.output());
            }
        }
    }

    private static CommandResult execute(final List<String> command, final Duration timeout) throws Exception {
        final var process =
                new ProcessBuilder(command).redirectErrorStream(true).start();
        final var output = new ByteArrayOutputStream();
        final var readFailure = new AtomicReference<IOException>();
        final var reader = Thread.ofVirtual().start(() -> {
            try (final var input = process.getInputStream()) {
                final var buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    // Keep draining after the diagnostic limit so a noisy child cannot deadlock.
                    final var retained = Math.min(count, 64 * 1024 - output.size());
                    if (retained > 0) {
                        output.write(buffer, 0, retained);
                    }
                }
            } catch (final IOException failure) {
                readFailure.set(failure);
            }
        });
        try {
            final var completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            reader.join(Duration.ofSeconds(5));
            if (reader.isAlive()) {
                throw new IOException("Subprocess output did not close");
            }
            if (!completed) {
                throw new IOException("Timed out after " + timeout + " running " + command.getFirst() + ":\n"
                        + output.toString(StandardCharsets.UTF_8));
            }
            if (readFailure.get() != null) {
                throw readFailure.get();
            }
            return new CommandResult(process.exitValue(), output.toString(StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            try (final var _ = process.getOutputStream();
                    final var _ = process.getInputStream();
                    final var _ = process.getErrorStream()) {
                // All pipe endpoints close even if closing another endpoint fails.
            } finally {
                reader.join(Duration.ofSeconds(5));
            }
        }
    }

    private record CommandResult(int exitCode, String output) {}

    /** Container entry point: deliberately has no JUnit or compiler/runtime-tool dependency. */
    public static final class Probe {
        public static void main(final String[] arguments) throws Exception {
            final var root = Path.of(arguments[0]);
            try {
                roundtrip(root);
                System.out.println("BROKER_IMAGE_OK");
            } finally {
                // In particular, leave no UID 10001-owned children in the host bind mount.
                try (final var paths = Files.walk(root)) {
                    for (final var path :
                            paths.sorted(Comparator.reverseOrder()).toList()) {
                        if (!path.equals(root)) {
                            Files.delete(path);
                        }
                    }
                }
            }
        }

        private static void roundtrip(final Path root) throws Exception {
            final var attempt = Files.createDirectory(root.resolve("attempt"));
            final var workspace = Files.createDirectory(attempt.resolve("workspace"));
            // Unsupported images fail here, never skip and never weaken the constructor's checks.
            GithubContextBroker.verifyRuntime(workspace);
            final var credential = "synthetic-dispatcher-credential";
            final var secret = "synthetic-outside-secret";
            final var outside = Files.writeString(
                    root.resolve("outside.json"), "{\"operation\":\"files\",\"page\":1,\"secret\":\"" + secret + "\"}");
            final var original = Files.readString(outside);
            final var calls = new AtomicInteger();
            final var serverFailure = new AtomicReference<String>();
            final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    calls.incrementAndGet();
                    if (!"GET".equals(exchange.getRequestMethod())
                            || !"/repos/acme/project/pulls/7/files?per_page=100&page=1"
                                    .equals(exchange.getRequestURI().toString())
                            || !("Bearer " + credential)
                                    .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                        serverFailure.set("Unexpected broker HTTP request");
                    }
                    final var response = ("[{\"filename\":\"a.txt\",\"patch\":\"roundtrip\"," + "\"download_url\":\""
                                    + secret + "\"}]")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                }
            });
            server.start();
            try (final var json = BotJsonMapper.create(key -> Optional.empty());
                    final var client = new GitHubClient(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                            json,
                            () -> credential,
                            Clock.systemUTC())) {
                final var task = json.fromString(
                        TaskDocument.class, "{\"repository\":\"acme/project\",\"kind\":\"review\",\"number\":7}");
                final var aliasAttempt = Files.createDirectory(root.resolve("alias-attempt"));
                Files.createSymbolicLink(aliasAttempt.resolve("workspace"), workspace);
                boolean rejected = false;
                try (final var _ = new GithubContextBroker(client, json, task, aliasAttempt)) {
                    throw new IllegalStateException("Symbolic workspace was accepted");
                } catch (final IOException expected) {
                    rejected = true;
                }
                require(rejected, "Symbolic workspace must be rejected");
                try (final var _ = new GithubContextBroker(client, json, task, attempt)) {
                    final var mailbox = workspace.resolve("github-broker");
                    final var requests = mailbox.resolve("requests");
                    final var responses = mailbox.resolve("responses");
                    publish(requests, 0, "files");
                    final var response = await(responses.resolve("0.json"));
                    require(
                            response.contains("roundtrip") && response.contains("\"ok\":true"),
                            "Real broker roundtrip failed: " + response);
                    safe(response, secret, credential);
                    require(!response.contains("download_url"), "Non-allowlisted field leaked");

                    publish(requests, 1, "../../outside.json");
                    final var traversal = await(responses.resolve("1.json"));
                    require(traversal.contains("\"ok\":false"), "Path operation accepted");
                    safe(traversal, secret, credential);
                    Files.createSymbolicLink(requests.resolve("2.json"), outside);
                    final var symbolic = await(responses.resolve("2.json"));
                    require(symbolic.contains("\"ok\":false"), "Symbolic request accepted");
                    safe(symbolic, secret, credential);

                    Files.createSymbolicLink(responses.resolve("3.json"), outside);
                    publish(requests, 3, "invalid");
                    publish(requests, 4, "invalid");
                    safe(await(responses.resolve("4.json")), secret, credential);
                    require(Files.readString(outside).equals(original), "Response symlink overwrote outside secret");

                    Files.move(responses, mailbox.resolve("pinned-responses"));
                    Files.createSymbolicLink(responses, root);
                    publish(requests, 5, "invalid");
                    final var pinned = await(mailbox.resolve("pinned-responses/5.json"));
                    require(pinned.contains("\"ok\":false"), "Pinned response missing");
                    safe(pinned, secret, credential);
                    require(
                            !Files.exists(root.resolve("5.json"), LinkOption.NOFOLLOW_LINKS),
                            "Response directory substitution redirected a write");
                    require(calls.get() == 1, "Rejected attacks reached HTTP server");
                    require(serverFailure.get() == null, "Loopback HTTP validation failed");
                }
            } finally {
                server.stop(0);
            }
        }

        private static void publish(final Path requests, final int slot, final String operation) throws IOException {
            final var pending =
                    Files.writeString(requests.resolve("pending"), "{\"operation\":\"" + operation + "\",\"page\":1}");
            Files.move(pending, requests.resolve(slot + ".json"));
        }

        private static String await(final Path path) throws Exception {
            final var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    final var text = Files.readString(path);
                    if (text.endsWith("}")) {
                        return text;
                    }
                }
                Thread.sleep(20);
            }
            throw new IOException("Timed out waiting for broker response " + path.getFileName());
        }

        private static void safe(final String response, final String secret, final String credential) {
            require(!response.contains(secret) && !response.contains(credential), "Secret leaked into mailbox");
        }

        private static void require(final boolean condition, final String message) {
            if (!condition) {
                throw new IllegalStateException(message);
            }
        }
    }
}
