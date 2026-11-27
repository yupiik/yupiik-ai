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

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.ai.acp.protocol.*;
import io.yupiik.fusion.framework.api.main.CliLauncher;
import io.yupiik.fusion.json.JsonMapper;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class ProjectWorkflowTest {
    @Test
    @Timeout(90)
    void interleavedSessionsKeepProjectPromptsToolsSkillsAndMemoryIsolated(@TempDir final Path tmp) throws Exception {
        final var decoy = project(tmp.resolve("process-default"), "DECOY");
        final var a = project(tmp.resolve("project-a"), "A");
        final var b = project(tmp.resolve("project-b"), "B");
        final var untouched = snapshot(decoy);
        final var turns =
                List.of(new Turn("A1", "A", a, "B"), new Turn("B1", "B", b, "A"), new Turn("A2", "A", a, "B"));
        final var calls = Map.of("A1", new AtomicInteger(), "B1", new AtomicInteger(), "A2", new AtomicInteger());
        final var providerFailure = new AtomicReference<Throwable>();
        try (final var mapper = CodecsTest.mapper()) {
            final var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/", exchange -> {
                try (exchange) {
                    try {
                        final String response;
                        if (exchange.getRequestURI().getPath().endsWith("models")) {
                            response = "{\"data\":[{\"id\":\"fake\"}]}";
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                        } else {
                            final var body =
                                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                            response = respond(mapper, body, turns, calls);
                            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                        }
                        final var bytes = response.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } catch (final Throwable failure) {
                        providerFailure.compareAndSet(null, failure);
                        final var bytes = "{\"error\":{\"message\":\"Project workflow assertion failed\"}}"
                                .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(500, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                } catch (final Exception failure) {
                    providerFailure.compareAndSet(null, failure);
                }
            });
            http.start();
            try (final var client =
                    new Client(mapper, command(tmp, decoy, http.getAddress().getPort()), decoy, providerFailure)) {
                client.request("initialize", "initialize", new Initialize(1, Map.of("terminal", false), null));
                final var sessions = new LinkedHashMap<String, String>();
                // Create both sessions before prompting either: A, B, then A again in this one process.
                for (final var entry : List.of(Map.entry("A", a), Map.entry("B", b))) {
                    final var created = client.request(
                            "new-" + entry.getKey(),
                            "session/new",
                            new NewSession(entry.getValue().toString(), List.of()));
                    final var id = mapper.fromString(Created.class, mapper.toString(created.result()))
                            .sessionId();
                    sessions.put(entry.getKey(), id);
                    client.sessions.add(id);
                    client.awaitInitialUpdates(id);
                    client.request("build-" + entry.getKey(), "session/set_mode", new SetMode(id, "build"));
                }
                assertNotEquals(sessions.get("A"), sessions.get("B"));
                for (final var turn : turns) {
                    client.prompt(turn.id(), sessions.get(turn.project()));
                    assertEquals(turn.steps().size() + 1, calls.get(turn.id()).get(), turn.id());
                    assertEquals(
                            "WRITTEN_" + turn.id(), Files.readString(turn.root().resolve("output.txt")));
                    final var memory = Files.readString(turn.root().resolve(".yca/memory/MEMORY.md"));
                    assertTrue(memory.contains("DURABLE_" + turn.id()), memory);
                    assertFalse(memory.contains("DURABLE_" + turn.other()), memory);
                    assertEquals(untouched, snapshot(decoy), "The process-default project must remain untouched");
                }
                assertEquals("WRITTEN_A2", Files.readString(a.resolve("output.txt")));
                assertEquals("WRITTEN_B1", Files.readString(b.resolve("output.txt")));
                final var memoryA = Files.readString(a.resolve(".yca/memory/MEMORY.md"));
                final var memoryB = Files.readString(b.resolve(".yca/memory/MEMORY.md"));
                assertTrue(memoryA.contains("DURABLE_A1"), memoryA);
                assertTrue(memoryA.contains("DURABLE_A2"), memoryA);
                assertTrue(memoryB.contains("DURABLE_B1"), memoryB);
                assertTrue(memoryA.contains("EXISTING_MEMORY_A"), memoryA);
                assertTrue(memoryB.contains("EXISTING_MEMORY_B"), memoryB);
                assertFalse(memoryA.contains("DURABLE_B"), memoryA);
                assertFalse(memoryB.contains("DURABLE_A"), memoryB);
                assertFalse(Files.exists(tmp.resolve("storage/memory/MEMORY.md")), "No project fact in global memory");
                client.finish();
                assertEquals(untouched, snapshot(decoy));
                assertNull(providerFailure.get(), () -> "Provider assertion: " + providerFailure.get());
            } finally {
                http.stop(0);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String respond(
            final JsonMapper mapper,
            final String body,
            final List<Turn> turns,
            final Map<String, AtomicInteger> calls) {
        final var request = (Map<String, Object>) mapper.fromString(Object.class, body);
        final var messages = (List<Map<String, Object>>) request.get("messages");
        final var user = messages.reversed().stream()
                .filter(m -> "user".equals(m.get("role")))
                .map(m -> mapper.toString(m.get("content")))
                .filter(c -> c.contains("PROJECT_WORKFLOW:"))
                .findFirst()
                .orElseThrow();
        final var turn = turns.stream()
                .filter(t -> user.contains("PROJECT_WORKFLOW:" + t.id()))
                .findFirst()
                .orElseThrow();
        final var system = messages.stream()
                .filter(m -> "system".equals(m.get("role")))
                .map(m -> mapper.toString(m.get("content")))
                .reduce("", String::concat);
        assertTrue(system.contains("AGENTS_ONLY_" + turn.project()), system);
        assertTrue(system.contains("<available_skills>"), system);
        assertTrue(system.contains("skill-" + turn.project()), system);
        assertTrue(system.contains("CATALOG_ONLY_" + turn.project()), system);
        assertFalse(system.contains("AGENTS_ONLY_" + turn.other()), system);
        assertFalse(system.contains("skill-" + turn.other()), system);
        assertFalse(system.contains("CATALOG_ONLY_" + turn.other()), system);
        assertFalse(system.contains("SKILL_BODY_"), "Catalog must contain metadata, not skill bodies: " + system);
        assertFalse(body.contains("AGENTS_ONLY_DECOY"), body);
        assertFalse(body.contains("skill-DECOY"), body);
        assertFalse(body.contains("SEED_" + turn.other()), body);
        assertFalse(body.contains("DURABLE_" + turn.other()), body);
        final var steps = turn.steps();
        final var index = calls.get(turn.id()).getAndIncrement();
        assertTrue(index <= steps.size(), "Unexpected extra provider request for " + turn.id());
        if (index > 0) {
            final var previous = steps.get(index - 1);
            final var last = messages.getLast();
            assertEquals("tool", last.get("role"), body);
            assertEquals(turn.id() + "-" + (index - 1), last.get("tool_call_id"), body);
            final var content = mapper.toString(last.get("content"));
            assertTrue(content.contains(previous.expected()), previous.name() + ": " + content);
            assertFalse(content.contains("SKILL_BODY_" + turn.other()), content);
        } else if ("A2".equals(turn.id())) {
            assertTrue(body.contains("DURABLE_A1"), "A must retain its own first-turn history");
            assertFalse(body.contains("PROJECT_WORKFLOW:B1"), body);
        }
        final String delta;
        final String finish;
        if (index == steps.size()) {
            delta = mapper.toString(Map.of("content", "COMPLETE_" + turn.id()));
            finish = "stop";
        } else {
            final var step = steps.get(index);
            final var declarations = (List<Map<String, Object>>) request.get("tools");
            assertTrue(
                    declarations.stream()
                            .map(d -> (Map<?, ?>) d.get("function"))
                            .anyMatch(f -> step.name().equals(f.get("name"))),
                    "Missing tool " + step.name());
            delta = mapper.toString(Map.of(
                    "tool_calls",
                    List.of(Map.of(
                            "index",
                            0,
                            "id",
                            turn.id() + "-" + index,
                            "type",
                            "function",
                            "function",
                            Map.of("name", step.name(), "arguments", mapper.toString(step.args()))))));
            finish = "tool_calls";
        }
        return "data: {\"id\":\"project\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":"
                + delta + ",\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"project\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\""
                + finish + "\"}]}\n\ndata: [DONE]\n\n";
    }

    private Path project(final Path root, final String name) throws Exception {
        Files.createDirectories(root.resolve("nested"));
        Files.writeString(root.resolve("AGENTS.md"), "Project instructions: AGENTS_ONLY_" + name + "\n");
        Files.writeString(root.resolve("seed.txt"), "SEED_" + name);
        Files.writeString(root.resolve("output.txt"), "UNTOUCHED_" + name);
        final var skill = Files.createDirectories(root.resolve(".yca/skills/skill-" + name));
        Files.writeString(
                skill.resolve("SKILL.md"),
                "---\nname: skill-" + name + "\ndescription: CATALOG_ONLY_" + name + "\n---\nSKILL_BODY_" + name
                        + "\n");
        final var memory = Files.createDirectories(root.resolve(".yca/memory"));
        Files.writeString(memory.resolve("MEMORY.md"), "- EXISTING_MEMORY_" + name + "\n");
        return root.toAbsolutePath().normalize();
    }

    private Map<String, String> snapshot(final Path root) throws Exception {
        final var contents = new LinkedHashMap<String, String>();
        try (final var paths = Files.walk(root)) {
            for (final var path : paths.sorted().toList()) {
                contents.put(
                        root.relativize(path).toString(),
                        Files.isDirectory(path) ? "<directory>" : Files.readString(path));
            }
        }
        return contents;
    }

    private List<String> command(final Path tmp, final Path decoy, final int port) throws Exception {
        final var home = Files.createDirectory(tmp.resolve("home"));
        final var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        final var command = new ArrayList<String>();
        final var executable = System.getProperty("yca.acp.executable");
        final var jar = System.getProperty("yca.acp.jar");
        if (executable != null) {
            command.addAll(List.of(Path.of(executable).toAbsolutePath().toString(), "-Duser.home=" + home));
        } else if (jar != null) {
            command.addAll(List.of(
                    java,
                    "-XX:TieredStopAtLevel=1",
                    "-Duser.home=" + home,
                    "-jar",
                    Path.of(jar).toAbsolutePath().toString()));
        } else {
            // The child really starts in the decoy; resolve relative launch paths in the parent first.
            final var classpath = Arrays.stream(
                            System.getProperty("java.class.path").split(File.pathSeparator))
                    .map(entry -> Path.of(entry).toAbsolutePath().toString())
                    .collect(joining(File.pathSeparator));
            command.addAll(List.of(
                    java,
                    "-XX:TieredStopAtLevel=1",
                    "-Duser.home=" + home,
                    "-cp",
                    classpath,
                    CliLauncher.class.getName()));
        }
        command.addAll(List.of(
                "acp",
                // Exercise project isolation and ACP tool approvals, not OS sandbox policy.
                "--sandbox",
                "false",
                "--yca-storageHome",
                tmp.resolve("storage").toString(),
                "--yca-home",
                home.toString(),
                "--yca-workingDir",
                decoy.toString(),
                "--yca-projectDir",
                decoy.toString(),
                "--yca-provider-baseUrl",
                "http://127.0.0.1:" + port + "/v1/chat/completions",
                "--yca-provider-model",
                "fake",
                "--yca-provider-apiKey",
                "test",
                "--yca-provider-contextWindow",
                "128000",
                "--yca-provider-outputTokens",
                "1000",
                "--yca-llm-retriesMaxAttempts",
                "1",
                "--yca-llm-streamRetriesMaxAttempts",
                "0",
                "--yca-mavenRepositoryKnowledge-enabled",
                "false",
                "--yca-structuralCodeGraph-enabled",
                "false",
                // Disable extraction (including session close) but still exercise explicit memory_write.
                "--yca-memory-enabled",
                "false",
                "--yca-memory-compactEnabled",
                "false",
                "--yca-skills-enabled",
                "true"));
        return command;
    }

    private record Step(String name, Map<String, Object> args, String expected) {}

    private record Turn(String id, String project, Path root, String other) {
        private List<Step> steps() {
            return List.of(
                    new Step("write_file", Map.of("path", "output.txt", "content", "WRITTEN_" + id), "Wrote"),
                    new Step("read", Map.of("path", "output.txt"), "WRITTEN_" + id),
                    new Step("read", Map.of("path", "seed.txt"), "SEED_" + project),
                    new Step("bash", Map.of("command", "pwd", "workdir", ".", "timeout_seconds", 5), root.toString()),
                    new Step(
                            "bash",
                            Map.of("command", "pwd", "workdir", "nested", "timeout_seconds", 5),
                            root.resolve("nested").toString()),
                    new Step("skill", Map.of("name", "skill-" + project), "SKILL_BODY_" + project),
                    new Step("skill", Map.of("name", "skill-" + other), "Skill not found: skill-" + other),
                    new Step(
                            "memory_write",
                            Map.of("fact", "DURABLE_" + id, "scope", "project"),
                            "Remembered (project)"));
        }
    }

    /** Test-only stdio driver: bounded reads and teardown even when the subprocess stops responding. */
    private static final class Client implements AutoCloseable {
        private final JsonMapper mapper;
        private final AtomicReference<Throwable> providerFailure;
        private final Process process;
        private final PrintWriter writer;
        private final ExecutorService readers;
        private final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>(512);
        private final Future<String> stderr;
        private final List<String> sessions = new ArrayList<>();
        private final List<Envelope> delayedConfigUpdates = new ArrayList<>();
        private int backgroundUpdates;

        private Client(
                final JsonMapper mapper,
                final List<String> command,
                final Path cwd,
                final AtomicReference<Throwable> providerFailure)
                throws Exception {
            this.mapper = mapper;
            this.providerFailure = providerFailure;
            this.process = new ProcessBuilder(command).directory(cwd.toFile()).start();
            this.writer = new PrintWriter(process.getOutputStream(), true);
            this.readers = Executors.newVirtualThreadPerTaskExecutor();
            this.stderr =
                    readers.submit(() -> new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            readers.submit(() -> {
                try (final var reader =
                        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!lines.offer(line)) {
                            throw new IllegalStateException("ACP stdout queue overflow");
                        }
                    }
                } catch (final Exception failure) {
                    providerFailure.compareAndSet(null, failure);
                } finally {
                    lines.offer("");
                }
            });
        }

        private Envelope request(final String id, final String method, final Object params) throws Exception {
            send(id, method, params);
            Envelope message;
            do {
                message = read();
                assertNotEquals("session/request_permission", message.method(), message.toString());
                if ("session/update".equals(message.method())
                        && "config_option_update"
                                .equals(((Map<?, ?>) ((Map<?, ?>) message.params()).get("update"))
                                        .get("sessionUpdate"))) {
                    // Defer real configuration notifications until the first turn, deterministically
                    // exercising the same routing as a late asynchronous catalog refresh.
                    delayedConfigUpdates.add(message);
                }
            } while (message.id() == null);
            assertEquals(id, message.id());
            assertNull(message.error(), messageError(message));
            return message;
        }

        private void awaitInitialUpdates(final String session) throws Exception {
            // session/new answers before initialUpdates() runs. A set_mode response is not
            // a barrier for that work: its queued command can finish before the initial plan.
            boolean commands = false;
            boolean plan = false;
            while (!commands || !plan) {
                final var message = read();
                assertNull(message.id(), message.toString());
                assertEquals("session/update", message.method(), message.toString());
                final var params = (Map<?, ?>) message.params();
                final var source = params.get("sessionId");
                assertTrue(sessions.contains(source), message.toString());
                final var update = (Map<?, ?>) params.get("update");
                switch (String.valueOf(update.get("sessionUpdate"))) {
                    case "config_option_update" -> delayedConfigUpdates.add(message);
                    case "available_commands_update" -> {
                        assertEquals(session, source, message.toString());
                        assertFalse(commands, "Duplicate initial commands update");
                        assertFalse(((List<?>) update.get("availableCommands")).isEmpty(), message.toString());
                        commands = true;
                    }
                    case "plan" -> {
                        assertEquals(session, source, message.toString());
                        assertFalse(plan, "Duplicate initial plan update");
                        assertEquals(List.of(), update.get("entries"), message.toString());
                        plan = true;
                    }
                    default -> fail("Unexpected initialization update: " + message);
                }
            }
        }

        private String messageError(final Envelope message) {
            return message + " provider=" + providerFailure.get();
        }

        private void prompt(final String turn, final String session) throws Exception {
            send(turn, "session/prompt", new Prompt(session, List.of(Content.text("PROJECT_WORKFLOW:" + turn))));
            final var delayed = delayedConfigUpdates.iterator();
            int approvals = 0;
            boolean complete = false;
            while (true) {
                final var message = delayed.hasNext() ? delayed.next() : read();
                if ("session/request_permission".equals(message.method())) {
                    final var permission = mapper.fromString(Permission.class, mapper.toString(message.params()));
                    assertEquals(session, permission.sessionId());
                    assertTrue(permission.options().stream().anyMatch(o -> "allow_once".equals(o.optionId())));
                    approvals++;
                    writer.println(mapper.toString(Envelope.of(
                            "2.0",
                            message.id(),
                            null,
                            null,
                            new PermissionResult(new Outcome("selected", "allow_once")),
                            null)));
                } else if ("session/update".equals(message.method())) {
                    final var params = (Map<?, ?>) message.params();
                    final var source = params.get("sessionId");
                    assertTrue(sessions.contains(source), message.toString());
                    if (!session.equals(source)) {
                        // Model discovery refreshes every session, independently of the active turn.
                        assertEquals(
                                "config_option_update",
                                ((Map<?, ?>) params.get("update")).get("sessionUpdate"),
                                message.toString());
                        backgroundUpdates++;
                    } else {
                        complete |= mapper.toString(message.params()).contains("COMPLETE_" + turn);
                    }
                } else {
                    assertEquals(turn, message.id(), message.toString());
                    assertNull(message.error(), messageError(message));
                    assertEquals(
                            "end_turn",
                            mapper.fromString(PromptResult.class, mapper.toString(message.result()))
                                    .stopReason()
                                    .name());
                    break;
                }
            }
            delayedConfigUpdates.clear();
            assertTrue(backgroundUpdates > 0, "Must exercise an inactive session's configuration update");
            assertTrue(approvals > 0, "Writes must be approved through ACP, not YOLO mode");
            assertTrue(complete, "Missing assistant completion for " + turn);
        }

        private void send(final String id, final String method, final Object params) {
            writer.println(mapper.toString(Envelope.of("2.0", id, method, params, null, null)));
        }

        private Envelope read() throws Exception {
            final var line = lines.poll(15, TimeUnit.SECONDS);
            assertNull(providerFailure.get(), () -> "Provider/stdio failure: " + providerFailure.get());
            assertNotNull(line, "Timed out waiting for ACP output");
            assertFalse(line.isEmpty(), "Unexpected ACP EOF");
            return mapper.fromString(Envelope.class, line);
        }

        private void finish() throws Exception {
            writer.close();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "ACP did not exit after stdin EOF");
            assertEquals(0, process.exitValue(), stderr.get(5, TimeUnit.SECONDS));
        }

        @Override
        public void close() throws Exception {
            // Kill before closing readers/writer: neither a blocked pipe read nor a stuck turn can hang teardown.
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "ACP process leaked");
            writer.close();
            process.getInputStream().close();
            process.getErrorStream().close();
            readers.shutdownNow();
            assertTrue(readers.awaitTermination(5, TimeUnit.SECONDS), "ACP readers leaked");
        }
    }
}
