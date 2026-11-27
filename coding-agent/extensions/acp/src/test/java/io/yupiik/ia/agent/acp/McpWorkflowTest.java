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

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.ai.acp.protocol.*;
import io.yupiik.fusion.framework.api.main.CliLauncher;
import io.yupiik.fusion.json.JsonMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class McpWorkflowTest {
    @ParameterizedTest
    @CsvSource({"'', stdio", "default, stdio", "low, stdio", "default, http", "low, sse"})
    @Timeout(60)
    void clientServersAreIsolatedApprovedInvokedAndReaped(
            final String variant, final String transport, @TempDir final Path tmp) throws Exception {
        try (final var mapper = CodecsTest.mapper();
                final var remote = new RemoteMcp(mapper)) {
            final var observed = new CopyOnWriteArrayList<String>();
            final var providerFailure = new AtomicReference<Throwable>();
            final var rejectPrompt = new AtomicBoolean();
            final var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/", exchange -> {
                try (exchange) {
                    final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (rejectPrompt.get()
                            && !exchange.getRequestURI().getPath().endsWith("models")) {
                        final var error =
                                "{\"error\":{\"message\":\"Provider rejected the test prompt\",\"type\":\"invalid_request_error\"}}"
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(400, error.length);
                        exchange.getResponseBody().write(error);
                        return;
                    }
                    final String response;
                    if (exchange.getRequestURI().getPath().endsWith("models")) {
                        response = "{\"data\":[{\"id\":\"fake\"}]}";
                    } else {
                        observed.add(body);
                        @SuppressWarnings("unchecked")
                        final var request = (Map<String, Object>) mapper.fromString(Object.class, body);
                        assertEquals("acp-model", request.get("model"));
                        assertEquals("low".equals(variant) ? "low" : "high", request.get("reasoning_effort"));
                        @SuppressWarnings("unchecked")
                        final var messages = (List<Map<String, Object>>) request.get("messages");
                        final var last = messages.getLast();
                        final String delta;
                        final String finish;
                        if ("tool".equals(last.get("role"))) {
                            delta =
                                    "{\"reasoning_content\":\"Checking the tool result\",\"content\":\"Workflow complete\"}";
                            finish = "stop";
                        } else {
                            @SuppressWarnings("unchecked")
                            final var declarations = (List<Map<String, Object>>) request.get("tools");
                            final var name = declarations.stream()
                                    .map(d -> (Map<?, ?>) d.get("function"))
                                    .map(f -> f.get("name").toString())
                                    .filter(n -> n.startsWith("client_"))
                                    .findFirst()
                                    .orElseThrow();
                            delta =
                                    "{\"tool_calls\":[{\"index\":0,\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":"
                                            + mapper.toString(name) + ",\"arguments\":\"{}\"}}]}";
                            finish = "tool_calls";
                        }
                        response =
                                "data: {\"id\":\"fake1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":"
                                        + delta + ",\"finish_reason\":null}]}\n\n"
                                        + "data: {\"id\":\"fake1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\""
                                        + finish + "\"}]}\n\n"
                                        + "data: [DONE]\n\n";
                        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    }
                    final var bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (final Throwable e) {
                    providerFailure.set(e);
                }
            });
            http.start();
            final var java =
                    Path.of(System.getProperty("java.home"), "bin", "java").toString();
            final var cp = System.getProperty("java.class.path");
            final var command = new ArrayList<String>();
            final var executable = System.getProperty("yca.acp.executable");
            final var jar = System.getProperty("yca.acp.jar");
            if (executable != null) {
                command.addAll(List.of(executable, "-Duser.home=" + tmp));
            } else if (jar != null) {
                command.addAll(List.of(java, "-XX:TieredStopAtLevel=1", "-Duser.home=" + tmp, "-jar", jar));
            } else {
                command.addAll(List.of(
                        java, "-XX:TieredStopAtLevel=1", "-Duser.home=" + tmp, "-cp", cp, CliLauncher.class.getName()));
            }
            command.addAll(List.of(
                    "acp",
                    // Exercise legacy MCP transports and ACP consent, not OS sandbox policy.
                    "--sandbox",
                    "false",
                    "--yca-storageHome",
                    tmp.resolve("storage").toString(),
                    "--yca-home",
                    tmp.toString(),
                    "--yca-workingDir",
                    tmp.toString(),
                    "--yca-provider-baseUrl",
                    "http://127.0.0.1:" + http.getAddress().getPort() + "/v1/chat/completions",
                    "--yca-provider-model",
                    "fake",
                    "--yca-provider-apiKey",
                    "test",
                    "--yca-provider-contextWindow",
                    "32000",
                    "--yca-provider-outputTokens",
                    "1000",
                    "--yca-mavenRepositoryKnowledge-enabled",
                    "false",
                    "--yca-structuralCodeGraph-enabled",
                    "false",
                    "--yca-memory-enabled",
                    "false"));
            command.addAll(List.of(
                    "--yca-provider-providers-length",
                    "2",
                    "--yca-provider-providers-0-id",
                    "decoy",
                    "--yca-provider-providers-0-model",
                    "unused-default",
                    "--yca-provider-providers-0-baseUrl",
                    "http://127.0.0.1:1/v1/chat/completions",
                    "--yca-provider-providers-1-id",
                    "selected",
                    "--yca-provider-providers-1-model",
                    "configured-default",
                    "--yca-provider-providers-1-baseUrl",
                    "http://127.0.0.1:" + http.getAddress().getPort() + "/v1/chat/completions",
                    "--yca-provider-providers-1-apiKey",
                    "test",
                    "--yca-provider-providers-1-contextWindow",
                    "32000",
                    "--yca-provider-providers-1-outputTokens",
                    "1000",
                    "--yca-provider-providers-1-backgroundModelOverrides-compact-name",
                    "configured-compact",
                    "--yca-provider-providers-1-backgroundModelOverrides-memory-name",
                    "configured-memory",
                    "--provider",
                    "selected",
                    "--model",
                    "acp-model"));
            command.addAll(List.of(
                    "--yca-provider-providers-1-tuning-defaultVariant", "low",
                    "--yca-provider-providers-1-models-length", "1",
                    "--yca-provider-providers-1-models-0-model", "acp-model",
                    "--yca-provider-providers-1-models-0-behavior-defaultVariant", "high",
                    "--yca-provider-providers-1-models-0-behavior-variants-length", "2",
                    "--yca-provider-providers-1-models-0-behavior-variants-0-name", "high",
                    "--yca-provider-providers-1-models-0-behavior-variants-0-reasoningEffort", "high",
                    "--yca-provider-providers-1-models-0-behavior-variants-1-name", "low",
                    "--yca-provider-providers-1-models-0-behavior-variants-1-reasoningEffort", "low"));
            if (!variant.isEmpty()) {
                command.addAll(List.of("--variant", variant));
            }
            final var process = new ProcessBuilder(command).start();
            final var readers = Executors.newVirtualThreadPerTaskExecutor();
            final var stderr = readers.submit(() -> {
                try {
                    return new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (final Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            final var pids = new ArrayList<Long>();
            final var writer = new PrintWriter(process.getOutputStream(), true);
            final var reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            try {
                send(mapper, writer, 1, "initialize", new Initialize(1, Map.of("terminal", false), null));
                final var initialized = read(mapper, reader);
                assertNull(initialized.error(), initialized.toString());
                final var capabilities = mapper.fromString(Initialized.class, mapper.toString(initialized.result()))
                        .agentCapabilities()
                        .mcpCapabilities();
                assertTrue(capabilities.http(), "ACP must advertise HTTP MCP support");
                assertTrue(capabilities.sse(), "ACP must advertise SSE MCP support");
                for (int index = 0; index < 2; index++) {
                    final var cwd = Files.createDirectory(tmp.resolve("project" + index));
                    final var pidFile = tmp.resolve("pid" + index);
                    final var value = "session" + index + ":" + cwd;
                    final var server = "stdio".equals(transport)
                            ? McpServer.of(
                                    null,
                                    "air-server",
                                    java,
                                    List.of(
                                            "-XX:TieredStopAtLevel=1",
                                            "-cp",
                                            cp,
                                            FakeMcp.class.getName(),
                                            pidFile.toString()),
                                    List.of(
                                            new Env("ACP_TEST_VALUE", "session" + index),
                                            new Env("ACP_TEST_SECRET", "never-expose-secret")))
                            : new McpServer(
                                    transport,
                                    "air-server",
                                    null,
                                    null,
                                    null,
                                    remote.url(transport),
                                    remote.headers(value).entrySet().stream()
                                            .map(e -> new HttpHeader(e.getKey(), e.getValue()))
                                            .toList());
                    send(mapper, writer, "new" + index, "session/new", new NewSession(cwd.toString(), List.of(server)));
                    Envelope created;
                    do {
                        created = read(mapper, reader);
                        if (created.id() == null) {
                            assertEquals("session/update", created.method(), created.toString());
                        }
                    } while (created.id() == null);
                    assertEquals("new" + index, created.id());
                    assertNull(created.error(), created.toString());
                    final var sessionInfo = mapper.fromString(Created.class, mapper.toString(created.result()));
                    final var session = sessionInfo.sessionId();
                    assertEquals(
                            List.of("provider", "model", "variant", "mode"),
                            sessionInfo.configOptions().stream()
                                    .map(ConfigOption::id)
                                    .toList());
                    if ("stdio".equals(transport)) {
                        pids.add(Long.parseLong(Files.readString(pidFile)));
                    }
                    Envelope message;
                    // Exercise configuration codecs/routing in the existing packaged/native smoke test,
                    // rather than adding another slow subprocess test.
                    for (final var option : sessionInfo.configOptions()) {
                        send(
                                mapper,
                                writer,
                                "select-" + option.id(),
                                "session/set_config_option",
                                new SetConfig(
                                        session,
                                        option.id(),
                                        "mode".equals(option.id()) ? "build" : option.currentValue()));
                        do {
                            message = read(mapper, reader);
                        } while (message.id() == null);
                        assertNull(message.error(), message.toString());
                        assertEquals(
                                4,
                                mapper.fromString(ConfigResult.class, mapper.toString(message.result()))
                                        .configOptions()
                                        .size());
                    }
                    send(mapper, writer, "mode" + index, "session/set_mode", new SetMode(session, "build"));
                    do {
                        message = read(mapper, reader);
                    } while (message.id() == null);
                    assertNull(message.error(), message.toString());
                    send(
                            mapper,
                            writer,
                            "prompt" + index,
                            "session/prompt",
                            new Prompt(
                                    session,
                                    List.of(
                                            Content.text("Use the client tool"),
                                            Content.of(
                                                    "resource_link",
                                                    null,
                                                    cwd.resolve("reference")
                                                            .toUri()
                                                            .toString(),
                                                    "reference",
                                                    null,
                                                    null,
                                                    null))));
                    boolean approved = false;
                    boolean text = false;
                    boolean thought = false;
                    while (true) {
                        message = read(mapper, reader);
                        if ("session/request_permission".equals(message.method())) {
                            assertFalse(approved, message.toString() + " requests=" + observed);
                            assertEquals(session, ((Map<?, ?>) message.params()).get("sessionId"));
                            assertEquals(0, remote.calls(value), "No remote invocation before consent");
                            approved = true;
                            writer.println(mapper.toString(Envelope.of(
                                    "2.0",
                                    message.id(),
                                    null,
                                    null,
                                    new PermissionResult(new Outcome("selected", "allow_once")),
                                    null)));
                        } else if ("session/update".equals(message.method())) {
                            text |= mapper.toString(message.params()).contains("Workflow complete");
                            final var update = mapper.toString(message.params());
                            thought |= update.contains("agent_thought_chunk")
                                    && update.contains("Checking the tool result");
                        } else if (("prompt" + index).equals(message.id())) {
                            assertNull(message.error(), message.toString() + " provider=" + providerFailure.get());
                            assertEquals(
                                    "end_turn",
                                    mapper.fromString(PromptResult.class, mapper.toString(message.result()))
                                            .stopReason()
                                            .name());
                            break;
                        }
                    }
                    assertTrue(approved);
                    assertTrue(text);
                    assertTrue(thought, "Provider reasoning must be forwarded as an ACP thought chunk");
                    final var expected = "MCP_OK:session" + index + ":" + cwd;
                    assertTrue(observed.stream().anyMatch(s -> s.contains(expected)), observed.toString());
                    if (!"stdio".equals(transport)) {
                        assertEquals(1, remote.calls(value));
                        remote.assertSessions(index + 1);
                        // The second ACP session must not inherit the first session's tool result/history.
                        if (index == 1) {
                            assertFalse(observed.getLast().contains("MCP_OK:session0:"), observed.getLast());
                        }
                    }
                    // Multi-turn history, overlapping prompt/mode rejection, and cancellation while awaiting consent.
                    send(
                            mapper,
                            writer,
                            "cancelled" + index,
                            "session/prompt",
                            new Prompt(session, List.of(Content.text("Use it again"))));
                    do {
                        message = read(mapper, reader);
                    } while (!"session/request_permission".equals(message.method()));
                    send(
                            mapper,
                            writer,
                            "overlap",
                            "session/prompt",
                            new Prompt(session, List.of(Content.text("overlap"))));
                    assertEquals(-32602, read(mapper, reader).error().code());
                    send(mapper, writer, "busy-mode", "session/set_mode", new SetMode(session, "plan"));
                    do {
                        message = read(mapper, reader);
                    } while (message.id() == null);
                    assertEquals("busy-mode", message.id());
                    assertNull(message.error(), message.toString());
                    writer.println(mapper.toString(
                            Envelope.of("2.0", null, "session/cancel", new Session(session), null, null)));
                    do {
                        message = read(mapper, reader);
                    } while (message.id() == null);
                    assertEquals("cancelled" + index, message.id());
                    assertEquals(
                            "cancelled",
                            mapper.fromString(PromptResult.class, mapper.toString(message.result()))
                                    .stopReason()
                                    .name());
                    if (!"stdio".equals(transport)) {
                        assertEquals(1, remote.calls(value), "Cancellation while awaiting consent must not invoke MCP");
                    }
                    if (index == 1) {
                        rejectPrompt.set(true);
                        send(
                                mapper,
                                writer,
                                "failed-provider",
                                "session/prompt",
                                new Prompt(session, List.of(Content.text("Trigger the provider failure"))));
                        final var updates = new StringBuilder();
                        do {
                            message = read(mapper, reader);
                            if ("session/update".equals(message.method())) {
                                updates.append(mapper.toString(message.params()));
                            }
                        } while (!"failed-provider".equals(message.id()));
                        assertNull(message.result(), message.toString());
                        assertNotNull(message.error(), message.toString());
                        assertEquals(-32603, message.error().code());
                        assertTrue(
                                message.error().message().contains("Provider rejected the test prompt"),
                                message.toString());
                        assertTrue(
                                updates.toString().contains("Provider rejected the test prompt"), updates.toString());
                    }
                }
                writer.close();
                assertTrue(process.waitFor(15, TimeUnit.SECONDS));
                final var logs = stderr.get(5, TimeUnit.SECONDS);
                assertEquals(0, process.exitValue(), logs);
                assertFalse(logs.contains("never-expose-secret"), logs);
                assertFalse(logs.contains(RemoteMcp.SECRET), "Remote credentials leaked to stderr");
                assertFalse(
                        observed.stream().anyMatch(s -> s.contains(RemoteMcp.SECRET)),
                        "Remote credentials leaked into provider context");
                if ("http".equals(transport)) {
                    remote.assertHttpClosed(2);
                }
                if (executable == null && System.getProperty("yca.acp.jar") == null) {
                    assertTrue(logs.contains("direct-turn"), logs);
                    assertTrue(logs.contains("info-turn"), logs);
                    assertTrue(logs.contains("warning-turn"), logs);
                    assertTrue(
                            logs.contains(
                                    "background-selection:$selected/configured-compact|$selected/configured-memory|decoy"),
                            logs);
                }
                for (final var pid : pids) {
                    assertFalse(
                            ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "leaked MCP " + pid);
                }
                assertNull(providerFailure.get());
            } finally {
                try {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    pids.forEach(pid -> ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly));
                    http.stop(0);
                    assertTrue(process.waitFor(5, TimeUnit.SECONDS), "ACP process leaked");
                    writer.close();
                    reader.close();
                } finally {
                    readers.shutdownNow();
                    assertTrue(readers.awaitTermination(5, TimeUnit.SECONDS), "ACP readers leaked");
                }
            }
        }
    }

    private void send(
            final JsonMapper mapper,
            final PrintWriter writer,
            final Object id,
            final String method,
            final Object params) {
        writer.println(mapper.toString(Envelope.of("2.0", id, method, params, null, null)));
    }

    private Envelope read(final JsonMapper mapper, final BufferedReader reader) throws Exception {
        final var line = reader.readLine();
        assertNotNull(line, "Unexpected ACP EOF");
        return mapper.fromString(Envelope.class, line);
    }
}
