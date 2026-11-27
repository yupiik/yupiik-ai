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
package io.yupiik.ia.agent.github.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.impl.MapConfigSource;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.container.configuration.ConfigurationRegistration;
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.bot.runtime.execution.AttemptRelay;
import io.yupiik.ia.agent.core.cli.RunCommand;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.configuration.ProviderBudgetConfiguration;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.configuration.GithubExecutorConfiguration;
import io.yupiik.ia.agent.github.configuration.PromptConfiguration;
import io.yupiik.ia.agent.github.configuration.ReviewPresentationConfiguration;
import io.yupiik.ia.agent.github.store.MutableTask;
import io.yupiik.ia.agent.github.store.TaskStore;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * In-JVM integration regression: real Fusion wiring, RunCommand, AgentLoop, HTTP client and
 * write_file execution, then host ReviewPublisher validation against a stub GitHub API. Only the
 * model and GitHub endpoints are scripted; no sandbox is launched.
 * The container's /attempt mount paths are mapped to a temporary host directory through trusted
 * configuration overrides, not by modifying the prepared configuration or the process directory.
 */
@Isolated // The real CLI graph owns process-wide terminal/logging lifecycle components.
@FusionSupport(modules = GithubConfigurationTestModule.class)
class GithubAgentLoopTest {
    @Fusion
    private ReviewPresentationConfiguration injectedPresentation;

    @Fusion
    private PromptConfiguration injectedPrompts;

    @Fusion
    private GithubExecutorConfiguration injectedExecutor;

    @TempDir
    Path home;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(30)
    void trustedAttemptRunsRealWriteToolAndReturnsStructuredReview(final boolean invalidFinding) throws Exception {
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            final var trustedFile = home.resolve("trusted.json");
            final var preparation = new AttemptPreparation(json, injectedPrompts, injectedExecutor.shared());
            final var expected = Map.<String, Object>of(
                    "schemaVersion",
                    1,
                    "taskId",
                    "loop-regression",
                    "summary",
                    "Review prepared for host publication",
                    "review",
                    Map.of(
                            "headSha", "0123456789012345678901234567890123456789",
                            "body", "No actionable findings in the supplied fixture",
                            "comments",
                                    invalidFinding
                                            ? List.of(Map.of(
                                                    "path",
                                                    "invented.java",
                                                    "side",
                                                    "RIGHT",
                                                    "line",
                                                    999,
                                                    "body",
                                                    "Invented finding"))
                                            : List.of()),
                    "tests",
                    List.of());
            // The stub endpoint is trusted model configuration and must survive preparation.
            try (final var model = new ModelServer(json, json.toString(expected))) {
                final var trustedText = json.toString(Map.of(
                        "yca",
                        Map.of(
                                "provider",
                                        Map.of(
                                                "baseUrl",
                                                model.endpoint(),
                                                "apiKey",
                                                "regression-key",
                                                "model",
                                                "trusted-regression-model",
                                                "contextWindow",
                                                128000,
                                                "outputTokens",
                                                1024,
                                                "timeoutSeconds",
                                                5),
                                "llm", Map.of("maxIterations", 3, "retriesMaxAttempts", 0),
                                "memory", Map.of("enabled", false),
                                "permissions", Map.of("mode", "ASK"))));
                Files.writeString(trustedFile, trustedText);
                final var attempt = preparation.prepare(trustedFile, home.resolve("attempts"));
                try (final var _ = new AttemptRelay(
                        attempt.resolve("configuration.json"),
                        json,
                        "127.0.0.1",
                        "http://127.0.0.1",
                        new ProviderBudgetConfiguration(null, null, null))) {
                    final var prepared = Files.readString(attempt.resolve("configuration.json"));
                    assertFalse(prepared.contains("regression-key"));
                    final var workspace = attempt.resolve("workspace");
                    final var result = attempt.resolve("output/result.json");
                    model.result = result;
                    final var repositoryConfig = workspace.resolve(".yca/configuration.json");
                    Files.createDirectories(repositoryConfig.getParent());
                    // No inheritance: if project configuration is accidentally loaded, it replaces
                    // the trusted provider/mode and the run cannot satisfy this test.
                    final var hostileText = json.toString(Map.of(
                            "yca",
                            Map.of(
                                    "provider",
                                    Map.of(
                                            "baseUrl", model.endpoint(),
                                            "apiKey", "repository-key",
                                            "model", "untrusted-repository-model"),
                                    "mode",
                                    "PLAN",
                                    "permissions",
                                    Map.of("mode", "ASK"),
                                    "memory",
                                    Map.of("enabled", true))));
                    Files.writeString(repositoryConfig, hostileText);
                    final var task = Map.<String, Object>of(
                            "id", "loop-regression",
                            "repository", "fixture/repository",
                            "number", 42,
                            "kind", "review",
                            "attempts", 1,
                            "request", "Review the supplied fixture; return findings for host publication.");
                    final var prompt = preparation
                            .prompt(TaskFixtures.task(task), "fixture-fork", false)
                            .replace("/attempt/output/result.json", result.toString());

                    try (final var _ = preserveInterruptHandler();
                            final var container = boot(attempt, prompt);
                            final var configuration = container.lookup(CodingAgentConfiguration.class);
                            final var command = container.lookup(RunCommand.class)) {
                        final var config = configuration.instance();
                        assertEquals(AgentMode.BUILD, config.mode());
                        assertEquals(PermissionMode.YOLO, config.permissions().mode());
                        assertFalse(config.memory().enabled());
                        assertFalse(config.skills().enabled());
                        assertFalse(config.subagents().enabled());
                        assertFalse(config.mcp().enabled());
                        assertFalse(config.notifications().enabled());
                        command.instance().run();
                    }

                    assertTrue(Files.isRegularFile(result), "write_file must create the result, not just claim it");
                    assertEquals(
                            json.fromString(Object.class, json.toString(expected)),
                            json.fromString(Object.class, Files.readString(result)));
                    assertEquals(2, model.requests.size(), "one tool round followed by one final answer");
                    final var first = model.requests.getFirst();
                    final var second = model.requests.getLast();
                    for (final var request : model.requests) {
                        assertEquals("trusted-regression-model", request.get("model"));
                    }
                    assertEquals(List.of("Bearer regression-key", "Bearer regression-key"), model.authorizations);
                    assertTrue(objects(first.get("messages")).stream()
                            .anyMatch(message ->
                                    "user".equals(message.get("role")) && prompt.equals(message.get("content"))));
                    assertTrue(objects(first.get("tools")).stream()
                            .anyMatch(tool -> "write_file"
                                    .equals(TaskStore.map(tool.get("function")).get("name"))));
                    final var messages = objects(second.get("messages"));
                    assertTrue(messages.stream()
                            .anyMatch(message -> "assistant".equals(message.get("role"))
                                    && message.get("tool_calls") instanceof List<?> calls
                                    && calls.stream()
                                            .map(TaskStore::map)
                                            .anyMatch(call -> "write-result".equals(call.get("id")))));
                    assertTrue(
                            messages.stream()
                                    .anyMatch(message -> "tool".equals(message.get("role"))
                                            && "write-result".equals(message.get("tool_call_id"))
                                            && String.valueOf(message.get("content"))
                                                    .contains("Wrote ")
                                            && String.valueOf(message.get("content"))
                                                    .contains(result.toString())),
                            "the actual write_file success must be returned to the model with its call id");
                    assertEquals(prepared, Files.readString(attempt.resolve("configuration.json")));
                    assertEquals(hostileText, Files.readString(repositoryConfig));
                    assertEquals(trustedText, Files.readString(trustedFile));
                    publishRealToolResult(json, result, invalidFinding);
                } finally {
                    AttemptPreparation.delete(attempt);
                }
                assertFalse(Files.exists(attempt));
            }
        }
    }

    private void publishRealToolResult(final JsonMapper json, final Path result, final boolean invalidFinding)
            throws Exception {
        final var store = new TaskStore(BotTestConfiguration.home(home.resolve("queue")), json);
        final var id = "loop-regression";
        store.transaction(state -> {
            state.tasks()
                    .put(
                            id,
                            new MutableTask(TaskFixtures.task(Map.of(
                                    "id", id, "repository", "fixture/repository", "number", 42, "kind", "review"))));
            return null;
        });
        final var task =
                TaskFixtures.map(store.transaction(state -> state.task(id).document()));
        final var posts = new CopyOnWriteArrayList<Map<String, Object>>();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var reviewUrl = "https://github.com/fixture/repository/pull/42#pullrequestreview-1";
        server.createContext("/", exchange -> {
            final Object response;
            if ("POST".equals(exchange.getRequestMethod())) {
                posts.add(TaskStore.map(json.fromString(
                        Object.class, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))));
                response = Map.of("html_url", reviewUrl);
            } else if (exchange.getRequestURI().getPath().endsWith("/reviews")
                    || exchange.getRequestURI().getPath().endsWith("/files")) {
                response = List.of();
            } else {
                response = Map.of("head", Map.of("sha", "0123456789012345678901234567890123456789"));
            }
            ModelServer.respond(exchange, "application/json", json.toString(response));
        });
        server.start();
        try (final var github = new GitHubClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                json,
                () -> "host-only-token",
                BotTestConfiguration.CLOCK)) {
            final var produced = TaskStore.map(json.fromString(Object.class, Files.readString(result)));
            assertEquals(id, produced.get("taskId"));
            if (invalidFinding) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> BotTestConfiguration.reviewPublisher(injectedPresentation, github, store, "bot")
                                .publish(TaskFixtures.task(task), TaskFixtures.result(produced)));
                assertTrue(
                        posts.isEmpty(), "a real model/tool result with invented diff locations must not be published");
                assertFalse(store.<Boolean>transaction(state -> (state.task(id).reviewIntent() != null)));
                return;
            }
            assertEquals(
                    reviewUrl,
                    BotTestConfiguration.reviewPublisher(injectedPresentation, github, store, "bot")
                            .publish(TaskFixtures.task(task), TaskFixtures.result(produced)));
            assertEquals(1, posts.size());
            assertEquals("COMMENT", posts.getFirst().get("event"));
            assertEquals(
                    "0123456789012345678901234567890123456789", posts.getFirst().get("commit_id"));
            assertEquals(List.of(), posts.getFirst().get("comments"));
            assertTrue(String.valueOf(posts.getFirst().get("body")).startsWith("<!-- yca-task:" + id + ":review -->"));
            assertTrue(store.<Boolean>transaction(state -> (state.task(id).reviewIntent() != null)));
        } finally {
            server.stop(0);
        }
    }

    private RuntimeContainer boot(final Path attempt, final String prompt) {
        // Same early configuration-source registration as TuiCommandHeadlessTest: these values
        // must be available before YcaConfigurationProducer resolves any file locations.
        final var values = Map.ofEntries(
                Map.entry(
                        "yca.configuration.json",
                        attempt.resolve("configuration.json").toString()),
                Map.entry("yca.configuration.project.enabled", "false"),
                Map.entry("yca.home", attempt.resolve("storage").toString()),
                // The host-publication workflow has its own attempt boundary; this is not an OS-sandbox test.
                Map.entry("yca.sandbox.enabled", "false"),
                Map.entry("yca.storageHome", attempt.resolve("storage").toString()),
                Map.entry("yca.workingDir", attempt.resolve("workspace").toString()),
                Map.entry("yca.projectDir", attempt.resolve("workspace").toString()),
                Map.entry("yca.structuralCodeGraph.enabled", "false"),
                Map.entry("yca.logging.auto", "false"));
        return ConfiguringContainer.of()
                .register(new ProvidedInstanceBean<>(
                        DefaultScoped.class,
                        ArgsConfigSource.class,
                        () -> new ArgsConfigSource(List.of("--run-prompt", prompt))))
                .register(new FusionListener<ConfigurationRegistration>() {
                    @Override
                    public Type eventType() {
                        return ConfigurationRegistration.class;
                    }

                    @Override
                    public int priority() {
                        return Integer.MIN_VALUE;
                    }

                    @Override
                    public void onEvent(
                            final RuntimeContainer container, final ConfigurationRegistration registration) {
                        registration.addSource().accept(new MapConfigSource(values));
                    }
                })
                .start();
    }

    private static List<Map<String, Object>> objects(final Object value) {
        assertTrue(value instanceof List<?>, "expected a JSON array, got " + value);
        return ((List<?>) value).stream().map(TaskStore::map).toList();
    }

    private static AutoCloseable preserveInterruptHandler() throws ReflectiveOperationException {
        // RunCommand installs a process-wide SIGINT handler without restoring it. Preserve the
        // pre-test handler using the same reflective API, so later tests do not inherit it.
        final var signalType = Class.forName("sun.misc.Signal");
        final var handlerType = Class.forName("sun.misc.SignalHandler");
        final var signal = signalType.getConstructor(String.class).newInstance("INT");
        final var handle = signalType.getMethod("handle", signalType, handlerType);
        final var previous =
                handle.invoke(null, signal, handlerType.getField("SIG_IGN").get(null));
        handle.invoke(null, signal, previous);
        return () -> handle.invoke(null, signal, previous);
    }

    private static final class ModelServer implements AutoCloseable {
        private final HttpServer server;
        private final JsonMapper json;
        private final String content;
        private final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
        private final List<String> authorizations = new CopyOnWriteArrayList<>();
        private volatile Path result;

        private ModelServer(final JsonMapper json, final String content) throws IOException {
            this.json = json;
            this.content = content;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", this::complete);
            server.createContext(
                    "/v1/models",
                    exchange -> respond(
                            exchange,
                            "application/json",
                            json.toString(Map.of("data", List.of(Map.of("id", "trusted-regression-model"))))));
            server.start();
        }

        private String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
        }

        private void complete(final HttpExchange exchange) throws IOException {
            final var request = TaskStore.map(json.fromString(
                    Object.class, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            requests.add(request);
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            final var writing = requests.size() == 1;
            final Map<String, Object> message = writing
                    ? Map.of(
                            "role",
                            "assistant",
                            "tool_calls",
                            List.of(Map.of(
                                    "index",
                                    0,
                                    "id",
                                    "write-result",
                                    "type",
                                    "function",
                                    "function",
                                    Map.of(
                                            "name",
                                            "write_file",
                                            "arguments",
                                            json.toString(Map.of("path", result.toString(), "content", content))))))
                    : Map.of("role", "assistant", "content", "Structured review saved for host publication.");
            final var reason = writing ? "tool_calls" : "stop";
            if (Boolean.TRUE.equals(request.get("stream"))) {
                respond(
                        exchange,
                        "text/event-stream",
                        "data: " + chunk(Map.of("index", 0, "delta", message)) + "\n\n"
                                + "data: " + chunk(Map.of("index", 0, "delta", Map.of(), "finish_reason", reason))
                                + "\n\ndata: [DONE]\n\n");
            } else {
                respond(
                        exchange,
                        "application/json",
                        chunk(Map.of("index", 0, "message", message, "finish_reason", reason)));
            }
        }

        private String chunk(final Map<String, Object> choice) {
            return json.toString(Map.of(
                    "id", "completion-regression",
                    "object", "chat.completion.chunk",
                    "model", "trusted-regression-model",
                    "choices", List.of(choice)));
        }

        private static void respond(final HttpExchange exchange, final String type, final String body)
                throws IOException {
            try (exchange) {
                final var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", type);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
