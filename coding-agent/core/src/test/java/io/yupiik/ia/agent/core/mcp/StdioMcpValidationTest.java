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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.yupiik.ai.mcp.client.McpFailure;
import io.yupiik.ai.mcp.client.StdioMcpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.maven.knowledge.process.ProcessService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ExtendWith(TestJsonMapper.class)
@SuppressWarnings("try") // These lifecycle tests deliberately close the client before the resource scope ends.
@Timeout(10)
class StdioMcpValidationTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"error\":{\"code\":-32001,\"message\":\"Quota exceeded\"}}",
                "{\"result\":{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"Quota exceeded\"}]}}"
            })
    void serverFailureDetailIsAvailableOnlyThroughUiChannel(
            final String body, @TempDir final Path tmp, final JsonMapper mapper) throws Exception {
        Files.writeString(tmp.resolve("reply.jsonl"), "{\"jsonrpc\":\"2.0\",\"id\":$id," + body.substring(1));
        try (final var client = client(tmp, mapper, null)) {
            client.initialize();
            final var failure = assertThrows(McpFailure.class, () -> client.callTool("invalid", Map.of()));
            assertEquals(
                    (body.contains("error") ? "JSON-RPC error -32001: " : "") + "Quota exceeded", failure.detail());
            assertFalse(failure.toString().contains("Quota exceeded"));
            assertNull(failure.getCause());
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"ping\",\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":null,\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{},\"error\":null}",
                "{\"jsonrpc\":\"2.0\",\"id\":$id,\"params\":{},\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":null}",
                "{\"jsonrpc\":\"2.0\",\"id\":$id}",
                "{\"jsonrpc\":\"2.0\",\"id\":null,\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":true,\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1e1000000,\"result\":{\"tools\":[]}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2.5,\"result\":{\"tools\":[]}}",
                "{\"jsonrpc\":\"2.0\",\"id\":$id,\"error\":{\"code\":\"1\",\"message\":\"secret-value\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":$id,\"error\":{\"code\":1.5,\"message\":\"secret-value\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":$id,\"error\":{\"code\":2147483648,\"message\":\"secret-value\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":$id,\"error\":{\"message\":\"secret-value\"}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"params\":null}",
                "{\"jsonrpc\":\"2.0\",\"method\":true}",
                "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"tools\":[null]}}",
                "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"tools\":[{\"name\":1,\"inputSchema\":{\"type\":\"object\"}}]}}",
                "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"tools\":[{\"name\":\"x\",\"inputSchema\":{}}]}}",
                "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"tools\":[{\"name\":\"x\",\"inputSchema\":[]}]}}",
                "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"tools\":[{\"name\":\"x\",\"inputSchema\":{\"type\":\"array\"}}]}}",
                "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"tools\":[{\"name\":\"x\",\"inputSchema\":{\"type\":\"object\",\"properties\":[]}}]}}",
                "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"tools\":[{\"name\":\"x\",\"inputSchema\":{\"type\":\"object\",\"required\":[1]}}]}}",
                "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"tools\":[{\"name\":\"x\",\"inputSchema\":{\"type\":\"object\"},\"outputSchema\":false}]}}",
                "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"tools\":[],\"nextCursor\":false}}"
            })
    void rejectsMalformedRawWireAndPayloads(final String reply, @TempDir final Path tmp, final JsonMapper mapper)
            throws Exception {
        Files.writeString(tmp.resolve("reply.jsonl"), reply);
        try (final var client = client(tmp, mapper, null)) {
            client.initialize();
            final var failure = assertThrows(IOException.class, client::listTools);
            assertFalse(failure.toString().contains("secret-value"));
            assertNull(failure.getCause());
            assertReaped(tmp);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"content\":null}",
                "{\"content\":[null]}",
                "{\"content\":[{\"type\":\"text\",\"text\":42}]}",
                "{\"content\":[{\"type\":\"image\",\"data\":\"AA==\"}]}",
                "{\"content\":[{\"type\":\"audio\",\"data\":false,\"mimeType\":\"audio/wav\"}]}",
                "{\"content\":[{\"type\":\"resource\",\"resource\":{\"uri\":\"test:resource\"}}]}",
                "{\"content\":[{\"type\":\"resource_link\",\"uri\":\"test:resource\"}]}",
                "{\"content\":[{\"type\":\"unknown\"}]}",
                "{\"content\":[],\"isError\":\"false\"}",
                "{\"content\":[],\"structuredContent\":[]}"
            })
    void rejectsMalformedCallResults(final String payload, @TempDir final Path tmp, final JsonMapper mapper)
            throws Exception {
        Files.writeString(tmp.resolve("reply.jsonl"), "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":" + payload + "}");
        try (final var client = client(tmp, mapper, null)) {
            client.initialize();
            assertThrows(IOException.class, () -> client.callTool("invalid", Map.of()));
            assertReaped(tmp);
        }
    }

    @Test
    void correlatesOnlyExactNumericIds(@TempDir final Path tmp, final JsonMapper mapper) throws Exception {
        Files.writeString(tmp.resolve("reply.jsonl"), """
                {"jsonrpc":"2.0","id":"2","result":{"tools":[]}}
                {"jsonrpc":"2.0","id":18446744073709551618,"result":{"tools":[]}}
                {"jsonrpc":"2.0","id":1e100,"result":{"tools":[]}}
                {"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"correct","inputSchema":{"type":"object"}}]}}
                """);
        try (final var client = client(tmp, mapper, null)) {
            client.initialize();
            assertEquals("correct", client.listTools().getFirst().name());
        }
    }

    @Test
    void structuredResultsAndUnlimitedJsonLines(@TempDir final Path tmp, final JsonMapper mapper) throws Exception {
        Files.writeString(tmp.resolve("reply.jsonl"), """
                {"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"image","data":"AA==","mimeType":"image/png"}],"structuredContent":{"answer":42}}}
                """);
        try (final var client = client(tmp, mapper, null)) {
            client.initialize();
            assertEquals("{\"answer\":42}", client.callTool("structured", Map.of()));
            final var text = "large response ".repeat(100_000);
            Files.writeString(
                    tmp.resolve("reply.jsonl"),
                    "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"" + text
                            + "\"}]}}");
            assertEquals(text, client.callTool("large", Map.of()));
        }
    }

    @Test
    void validServerRequestsAndNotificationsDoNotConsumeResponses(@TempDir final Path tmp, final JsonMapper mapper)
            throws Exception {
        Files.writeString(tmp.resolve("reply.jsonl"), """
                {"jsonrpc":"2.0","method":"notifications/progress","params":{}}
                {"jsonrpc":"2.0","id":"server-string","method":"ping"}
                {"jsonrpc":"2.0","id":77,"method":"roots/list","params":{}}
                {"jsonrpc":"2.0","id":$id,"result":{"tools":[]}}
                """);
        try (final var client = client(tmp, mapper, null)) {
            client.initialize();
            assertEquals(List.of(), client.listTools());
            Files.writeString(
                    tmp.resolve("reply.jsonl"), "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"content\":[]}}");
            // Pipe ordering makes this a barrier for the fixture recording both preceding replies.
            assertEquals("", client.callTool("barrier", Map.of()));
            final var replies = Files.readAllLines(tmp.resolve("client-replies.jsonl"));
            assertEquals(2, replies.size());
            final var ping = (Map<?, ?>) mapper.fromString(Object.class, replies.getFirst());
            assertEquals("server-string", ping.get("id"));
            assertEquals(Map.of(), ping.get("result"));
            final var unsupported = (Map<?, ?>) mapper.fromString(Object.class, replies.getLast());
            assertEquals(-32601, ((Number) ((Map<?, ?>) unsupported.get("error")).get("code")).intValue());
        }
    }

    @Test
    void closeBeforeInitializeDoesNotLaunch(@TempDir final Path tmp, final JsonMapper mapper) {
        final var launches = new AtomicInteger();
        final var service = new ProcessService() {
            @Override
            public Process track(final Process process) {
                launches.incrementAndGet();
                return super.track(process);
            }
        };
        try (final var client = client(tmp, mapper, service)) {
            client.close();
            assertThrows(IOException.class, client::initialize);
            assertEquals(0, launches.get());
        }
    }

    @Test
    void closeDuringRegistrationDoesNotWaitForInitializationOrLeakTracking(
            @TempDir final Path tmp, final JsonMapper mapper) throws Exception {
        final var registering = new CompletableFuture<Process>();
        final var release = new CountDownLatch(1);
        final var service = new ProcessService() {
            @Override
            public Process track(final Process process) {
                registering.complete(process);
                try {
                    release.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.track(process);
            }
        };
        try (final var client = client(tmp, mapper, service);
                final var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            final var initializing = workers.submit(() -> assertThrows(IOException.class, client::initialize));
            try {
                final var process = registering.get(5, TimeUnit.SECONDS);
                assertThrows(IOException.class, client::initialize, "a second initialize must not launch a child");
                client.close(); // Must return while initialization is still blocked in track().
                process.onExit().get(5, TimeUnit.SECONDS);
                assertFalse(process.isAlive());
            } finally {
                release.countDown();
            }
            initializing.get(5, TimeUnit.SECONDS);
            assertEquals(0, service.trackedCount());
        } finally {
            release.countDown();
        }
    }

    private StdioMcpClient client(final Path tmp, final JsonMapper mapper, final ProcessService processes) {
        return new StdioMcpClient(
                "wire-test",
                List.of(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        AdversarialMcpServer.class.getName(),
                        "wire",
                        tmp.toString()),
                List.of(),
                null,
                60,
                mapper,
                McpProcesses.of(processes));
    }

    private void assertReaped(final Path tmp) throws Exception {
        final var process = ProcessHandle.of(Long.parseLong(Files.readString(tmp.resolve("pid"))));
        if (process.isPresent()) {
            process.orElseThrow().onExit().get(5, TimeUnit.SECONDS);
        }
        assertFalse(process.map(ProcessHandle::isAlive).orElse(false));
    }
}
