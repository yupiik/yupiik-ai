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

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GithubContextBrokerTest {
    @TempDir
    Path temporary;

    @Test
    void verifiesPreparedWorkspaceRuntimeWithoutCreatingMailbox() throws Exception {
        final var workspace = Files.createDirectory(temporary.resolve("workspace"));
        assertDoesNotThrow(() -> GithubContextBroker.verifyRuntime(workspace));
        try (final var entries = Files.list(workspace)) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void rejectsUnsupportedFilesystemWithActionableDispatcherDiagnostic() throws Exception {
        try (final var zip = FileSystems.newFileSystem(temporary.resolve("workspace.zip"), Map.of("create", "true"))) {
            final var workspace = Files.createDirectory(zip.getPath("/workspace"));
            final var failure = assertThrows(IOException.class, () -> GithubContextBroker.verifyRuntime(workspace));
            assertTrue(failure.getMessage().contains("dispatcher runtime"));
            assertTrue(failure.getMessage().contains("SecureDirectoryStream-compatible glibc Java 25 runtime"));
            assertTrue(failure.getMessage().contains("filesystem"));
            assertTrue(failure.getMessage().contains("cannot be disabled"));
            // Calling preflight is not required to enforce the constructor's security boundary.
            try (final var json = BotJsonMapper.create(key -> Optional.empty());
                    final var client = new GitHubClient(
                            URI.create("http://127.0.0.1:1"), json, () -> "", BotTestConfiguration.CLOCK)) {
                final var constructorFailure = assertThrows(
                        IOException.class,
                        () -> new GithubContextBroker(
                                client,
                                json,
                                TaskFixtures.task(Map.of("repository", "acme/project", "kind", "review", "number", 7)),
                                zip.getPath("/")));
                assertEquals(failure.getMessage(), constructorFailure.getMessage());
            }
        }
    }

    @Test
    void boundedMailboxIsReadOnlyAndPinnedToTask() throws Exception {
        final var calls = new AtomicInteger();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            assertEquals("GET", exchange.getRequestMethod());
            assertEquals("Bearer host-secret", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals(
                    "/repos/acme/project/pulls/7/files?per_page=100&page=2",
                    exchange.getRequestURI().toString());
            final var content = "[{\"filename\":\"a.txt\",\"patch\":\"hello\",\"download_url\":\"secret\"}]"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });
        server.start();
        try (final var json = BotJsonMapper.create(key -> Optional.empty());
                final var client = new GitHubClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        json,
                        () -> "host-secret",
                        BotTestConfiguration.CLOCK)) {
            Files.createDirectory(temporary.resolve("workspace"));
            try (final var _ = new GithubContextBroker(
                    client,
                    json,
                    TaskFixtures.task(Map.of("repository", "acme/project", "kind", "review", "number", 7)),
                    temporary)) {
                request(0, "{\"operation\":\"files\",\"page\":2}");
                final var response = await(0);
                assertTrue(response.contains("hello"));
                assertFalse(response.contains("download_url"));
                assertFalse(response.contains("host-secret"));
                assertFalse(response.contains("secret"));
                assertEquals(1, calls.get());
                request(1, "{\"operation\":\"https://evil.invalid\",\"page\":1}");
                assertTrue(await(1).contains("false"));
                request(2, "{\"operation\":\"files\",\"page\":11}");
                assertTrue(await(2).contains("false"));
                request(3, "x".repeat(4097));
                assertTrue(await(3).contains("false"));
                final var secret = temporary.resolve("outside");
                Files.writeString(secret, "{\"operation\":\"files\",\"page\":2}");
                Files.createSymbolicLink(temporary.resolve("workspace/github-broker/requests/4.json"), secret);
                assertTrue(await(4).contains("false"));
                assertEquals(1, calls.get());
                // Replacing the exposed directory cannot redirect the host's pinned response handle.
                final var root = temporary.resolve("workspace/github-broker");
                Files.move(root.resolve("responses"), root.resolve("old-responses"));
                Files.createSymbolicLink(root.resolve("responses"), temporary);
                request(5, "{\"operation\":\"invalid\",\"page\":1}");
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    while (!Files.exists(root.resolve("old-responses/5.json"))) {
                        Thread.sleep(20);
                    }
                });
                assertFalse(Files.exists(temporary.resolve("5.json")));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsInvalidIdentityBeforeCreatingMailbox() throws Exception {
        Files.createDirectory(temporary.resolve("workspace"));
        try (final var json = BotJsonMapper.create(key -> Optional.empty());
                final var client = new GitHubClient(
                        URI.create("http://127.0.0.1:1"), json, () -> "", BotTestConfiguration.CLOCK)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GithubContextBroker(
                            client,
                            json,
                            TaskFixtures.task(Map.of("repository", "acme/../private", "kind", "review", "number", 1)),
                            temporary));
            assertFalse(Files.exists(temporary.resolve("workspace/github-broker")));
        }
    }

    private void request(final int slot, final String request) throws Exception {
        final var root = temporary.resolve("workspace/github-broker/requests");
        final var temp = Files.writeString(root.resolve("pending"), request);
        Files.move(temp, root.resolve(slot + ".json"));
    }

    private String await(final int slot) {
        return assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            final var response = temporary.resolve("workspace/github-broker/responses/" + slot + ".json");
            while (true) {
                if (Files.exists(response)) {
                    final var text = Files.readString(response);
                    if (text.endsWith("}")) {
                        return text;
                    }
                }
                Thread.sleep(20);
            }
        });
    }
}
