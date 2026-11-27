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

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.yupiik.ai.mcp.client.StdioMcpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.maven.knowledge.process.ProcessService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies that closing a stdio MCP client kills and reaps the spawned server through the shared
 * ProcessService (previously {@code close()} only sent SIGTERM without waiting, orphaning servers
 * that ignore it). The fake server answers {@code initialize} then traps TERM and keeps running.
 */
@ExtendWith(TestJsonMapper.class)
class StdioMcpClientTest {
    @ParameterizedTest
    @ValueSource(ints = {4, 60, 0, -1, Integer.MIN_VALUE})
    @Timeout(15)
    void timeoutsAreIsolatedAndNonpositiveValuesRetainLegacyGrace(
            final int seconds, @TempDir final Path tmp, final JsonMapper mapper) throws Exception {
        final var longDir = Files.createDirectory(tmp.resolve("long"));
        final var shortDir = Files.createDirectory(tmp.resolve("short"));
        try (final var longClient = adversary("delayed-call", longDir, mapper, seconds);
                final var shortClient = adversary("delayed-call", shortDir, mapper, 1)) {
            longClient.initialize();
            shortClient.initialize();
            // Constructing a short-lived client must not change an existing client's deadline.
            final var slowCall = CompletableFuture.supplyAsync(() -> {
                try {
                    return longClient.callTool("slow", Map.of());
                } catch (final IOException | InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            });
            assertTimeoutPreemptively(
                    Duration.ofSeconds(5),
                    () -> assertThrows(IOException.class, () -> shortClient.callTool("slow", Map.of())));
            assertEquals("ok", slowCall.get(5, TimeUnit.SECONDS));
            assertReaped(shortDir);
        }
        assertReaped(longDir);
    }

    @Test
    @Timeout(10)
    void discoveryPagesShareConfiguredDeadline(@TempDir final Path tmp, final JsonMapper mapper) throws Exception {
        try (final var client = adversary("slow-pages", tmp, mapper, 1)) {
            client.initialize();
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertThrows(IOException.class, client::listTools));
            assertReaped(tmp);
        }
    }

    @Test
    @Timeout(15)
    void deadlineIncludesBlockedPipeWrite(@TempDir final Path tmp, final JsonMapper mapper) throws Exception {
        try (final var client = adversary("blocked", tmp, mapper, 2)) {
            client.initialize();
            client.listTools();
            assertTimeoutPreemptively(
                    Duration.ofSeconds(6),
                    () -> assertThrows(
                            IOException.class,
                            () -> client.callTool("blocked", Map.of("payload", "x".repeat(4 * 1024 * 1024)))));
            assertReaped(tmp);
        }
    }

    @Test
    @Timeout(20)
    void interruptAbortsBlockedWriterAndLockWaiters(@TempDir final Path tmp, final JsonMapper mapper) throws Exception {
        try (final var client = adversary("blocked", tmp, mapper, 60)) {
            client.initialize();
            client.listTools();
            final var entered = new CountDownLatch(1);
            final var finished = new CompletableFuture<Throwable>();
            final var caller = Thread.ofVirtual().start(() -> {
                entered.countDown();
                try {
                    client.callTool("blocked", Map.of("payload", "x".repeat(4 * 1024 * 1024)));
                    finished.complete(null);
                } catch (final Throwable e) {
                    finished.complete(e);
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            final var waiter = CompletableFuture.supplyAsync(() -> assertThrows(
                    IOException.class,
                    () -> client.callTool("waiting", Map.of("payload", "x".repeat(4 * 1024 * 1024)))));
            caller.interrupt();
            assertInstanceOf(InterruptedException.class, finished.get(5, TimeUnit.SECONDS));
            assertNotNull(waiter.get(5, TimeUnit.SECONDS));
            assertReaped(tmp);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"eof", "malformed"})
    @Timeout(15)
    void readerTerminationClosesIdleConnection(final String mode, @TempDir final Path tmp, final JsonMapper mapper)
            throws Exception {
        try (final var client = adversary(mode, tmp, mapper, 60)) {
            client.initialize();
            client.listTools();
            final var process = ProcessHandle.of(Long.parseLong(Files.readString(tmp.resolve("pid"))))
                    .orElseThrow();
            final var racing = IntStream.range(0, 16)
                    .mapToObj(i -> CompletableFuture.runAsync(() -> assertThrows(
                            IOException.class, () -> client.callTool("racing-reader-termination", Map.of()))))
                    .toList();
            Files.writeString(tmp.resolve("terminate-reader"), "go");
            process.onExit().get(5, TimeUnit.SECONDS);
            CompletableFuture.allOf(racing.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
            assertTimeoutPreemptively(
                    Duration.ofSeconds(2),
                    () -> assertThrows(IOException.class, () -> client.callTool("after-eof", Map.of())));
        }
    }

    @Test
    @Timeout(15)
    void endlessFreshEmptyPagesAreBoundedAndReaped(@TempDir final Path tmp, final JsonMapper mapper) throws Exception {
        try (final var client = adversary("pages", tmp, mapper, 60)) {
            client.initialize();
            assertThrows(IOException.class, client::listTools);
            assertReaped(tmp);
        }
    }

    private StdioMcpClient adversary(
            final String mode, final Path tmp, final JsonMapper mapper, final int timeoutSeconds) throws IOException {
        final List<String> command;
        if ((mode.equals("delayed-call") || mode.equals("slow-pages"))
                && !System.getProperty("os.name", "").toLowerCase().contains("win")) {
            // A one-second RPC deadline must exercise the delayed reply, not JVM startup/JIT
            // competing with the parallel suite. Keep real process/pipe I/O without a child JVM.
            final var script = tmp.resolve("timed-server.sh");
            try (final var source = StdioMcpClientTest.class.getResourceAsStream("/mcp/timed-server.sh")) {
                assertNotNull(source);
                Files.copy(source, script);
            }
            command = List.of("/bin/sh", script.toString(), tmp.resolve("pid").toString(), mode);
        } else {
            command = List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    AdversarialMcpServer.class.getName(),
                    mode,
                    tmp.toString());
        }
        return new StdioMcpClient("adversary", command, List.of(), null, timeoutSeconds, mapper, null);
    }

    private void assertReaped(final Path tmp) throws Exception {
        final var process = ProcessHandle.of(Long.parseLong(Files.readString(tmp.resolve("pid"))));
        if (process.isPresent()) {
            process.orElseThrow().onExit().get(5, TimeUnit.SECONDS);
        }
        assertFalse(process.map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void closeDisposesTheSpawnedServer(@TempDir final Path tmp, final JsonMapper mapper) throws Exception {
        Assumptions.assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"));
        final var service = new ProcessService();
        service.reapGrace(300);

        final var script = tmp.resolve("mcp-server.sh");
        Files.writeString(
                script,
                "#!/bin/sh\n"
                        + "read line\n"
                        + "id=$(printf '%s' \"$line\" | sed -n 's/.*\"id\":\\([0-9][0-9]*\\).*/\\1/p')\n"
                        + "printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"protocolVersion\":\"2025-06-18\","
                        + "\"capabilities\":{},\"serverInfo\":{\"name\":\"x\",\"version\":\"1\"}}}\\n' \"$id\"\n"
                        + "trap '' TERM\n"
                        + "while :; do sleep 1; done\n");
        script.toFile().setExecutable(true);

        final var client = new StdioMcpClient(
                "test", List.of("/bin/sh", script.toString()), List.of(), null, 60, mapper, McpProcesses.of(service));
        client.initialize();
        assertEquals(1, service.trackedCount(), "the MCP server must be tracked");

        client.close();
        assertEquals(0, service.trackedCount(), "close() must dispose the spawned server through ProcessService");
    }

    @Test
    void honorsConfiguredWorkingDir(@TempDir final Path tmp, final JsonMapper mapper) throws Exception {
        Assumptions.assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"));
        final var service = new ProcessService();

        final var workdir = tmp.resolve("server-cwd");
        Files.createDirectories(workdir);
        // the fake server drops its cwd (pwd) into a sentinel file so the test can assert where it ran,
        // then answers initialize and idles (TERM trapped so close() reaps through ProcessService).
        final var script = tmp.resolve("mcp-server-cwd.sh");
        Files.writeString(
                script,
                "#!/bin/sh\n"
                        + "pwd > \"" + tmp.resolve("started-in").toAbsolutePath() + "\"\n"
                        + "read line\n"
                        + "id=$(printf '%s' \"$line\" | sed -n 's/.*\"id\":\\([0-9][0-9]*\\).*/\\1/p')\n"
                        + "printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"protocolVersion\":\"2025-06-18\","
                        + "\"capabilities\":{},\"serverInfo\":{\"name\":\"x\",\"version\":\"1\"}}}\\n' \"$id\"\n"
                        + "trap '' TERM\n"
                        + "while :; do sleep 1; done\n");
        script.toFile().setExecutable(true);

        final var client = new StdioMcpClient(
                "test",
                List.of("/bin/sh", script.toString()),
                List.of(),
                workdir.toString(),
                60,
                mapper,
                McpProcesses.of(service));
        try {
            client.initialize();
        } finally {
            client.close();
        }
        final var startedIn = Files.readString(tmp.resolve("started-in")).strip();
        assertEquals(expectedCwd(workdir), startedIn, "the MCP server must run in the configured dir");
    }

    /** Normalizes the expected path the same way the client does before handing it to the child. */
    private static String expectedCwd(final Path dir) {
        return dir.toAbsolutePath().normalize().toString();
    }
}
