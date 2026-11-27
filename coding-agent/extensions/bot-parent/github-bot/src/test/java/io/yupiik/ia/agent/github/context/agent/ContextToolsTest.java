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
package io.yupiik.ia.agent.github.context.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.github.configuration.AgentContextConfiguration;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextToolsTest {
    @TempDir
    private static Path testWorkdir;

    private static final String ENDPOINT = "https://127.0.0.1:1/context/12345678-1234-1234-1234-123456789abc/rpc";

    @Test
    void discoveryIsPureAndRuntimeChecksDisabledMissingSecretsAndInvalidEndpoint() {
        try (final var json = new JsonMapperImpl(List.of(), ignored -> Optional.empty())) {
            for (final var config : List.of(
                    new AgentContextConfiguration(false, ENDPOINT, "PATH", "YCA_CONTEXT_GIT_TOKEN", "o/r"),
                    new AgentContextConfiguration(
                            true, ENDPOINT, "YCA_NONEXISTENT_TEST_TOKEN_92874", "YCA_CONTEXT_GIT_TOKEN", "o/r"),
                    new AgentContextConfiguration(
                            true, "http://127.0.0.1/rpc", "PATH", "YCA_CONTEXT_GIT_TOKEN", "o/r"))) {
                final var sources = Configuration.of(config.enabled() ? Map.of("PATH", "fixture") : Map.of());
                for (final var tool : List.<EmbeddedTool>of(
                        new GitTool(config, json, sources), new GithubContextTool(config, json, sources))) {
                    assertFalse(tool.usable(testWorkdir));
                    assertTrue(tool.readonly());
                    assertEquals(ToolCategory.READ, tool.category());
                    assertTrue(tool.execute(
                                    tool.name().equals("git")
                                            ? Map.of("argv", List.of("log"))
                                            : Map.of("operation", "summary"),
                                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)))
                            .error());
                }
            }
        }
    }

    @Test
    void programmaticConfigurationControlsToolUsabilityWithoutAmbientTokenFallback() {
        try (final var json = new JsonMapperImpl(List.of(), ignored -> Optional.empty())) {
            final var config = new AgentContextConfiguration(true, ENDPOINT, "PATH", "YCA_CONTEXT_GIT_TOKEN", "o/r");
            for (final var sources : List.of(
                    Configuration.of(Map.of()),
                    Configuration.of(Map.of("PATH", "fixture")),
                    Configuration.of(Map.of("PATH", " ")))) {
                final var expected =
                        sources.get("PATH").filter(value -> !value.isBlank()).isPresent();
                for (final var tool : List.<EmbeddedTool>of(
                        new GitTool(config, json, sources), new GithubContextTool(config, json, sources))) {
                    assertEquals(expected, tool.usable(testWorkdir), tool.name());
                }
            }
        }
    }

    @Test
    void effectiveToolCeilingAndBothCancellationProbesAreCheckedPerInvocation() {
        try (final var json = new JsonMapperImpl(List.of(), ignored -> Optional.empty())) {
            final var config = new AgentContextConfiguration(true, ENDPOINT, "PATH", "YCA_CONTEXT_GIT_TOKEN", "o/r");
            for (final var tool : List.<EmbeddedTool>of(
                    new GitTool(config, json, Configuration.of(Map.of("PATH", "fixture"))),
                    new GithubContextTool(config, json, Configuration.of(Map.of("PATH", "fixture"))))) {
                final var args = tool.name().equals("git")
                        ? Map.<String, Object>of("argv", List.of("log"))
                        : Map.<String, Object>of("operation", "summary");
                assertTrue(tool.usable(testWorkdir));
                assertEquals(
                        "Context tool is not allowed.",
                        tool.execute(args, context(false, false, Set.of())).output());
                assertEquals(
                        "Context request cancelled.",
                        tool.execute(args, context(true, false, null)).output());
                assertEquals(
                        "Context request cancelled.",
                        tool.execute(args, context(false, true, null)).output());
                // No previous call's cancellation or ceiling is retained by the singleton tool.
                assertEquals(
                        "Context tool is not allowed.",
                        tool.execute(args, context(false, false, Set.of("other")))
                                .output());
            }
        }
    }

    @Test
    void schemasAndRuntimeTypesAcceptNullableOptionalFieldsButRejectArbitraryOperations() {
        try (final var json = new JsonMapperImpl(List.of(), ignored -> Optional.empty())) {
            final var config = new AgentContextConfiguration(false, "", "", "", "");
            final var git = new GitTool(config, json, Configuration.of(Map.of()));
            final var context = new GithubContextTool(config, json, Configuration.of(Map.of()));
            assertEquals("git", git.name());
            assertEquals("github_context", context.name());
            assertEquals(List.of("argv"), git.schema().get("required"));
            assertEquals(List.of("operation"), context.schema().get("required"));
            final var args = new HashMap<String, Object>();
            args.put("argv", List.of("log"));
            args.put("repository", null);
            assertEquals(
                    "Context capability unavailable.",
                    git.execute(args, ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)))
                            .output());
            assertEquals(
                    "Invalid Git arguments.",
                    git.execute(
                                    Map.of("argv", List.of(1)),
                                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)))
                            .output());
            assertEquals(
                    "Invalid Git arguments.",
                    git.execute(
                                    Map.of("argv", List.of("log"), "shell", "evil"),
                                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)))
                            .output());
            for (final var operation : List.of(
                    "summary",
                    "repositories",
                    "request_repository",
                    "prepare_source",
                    "operation_status",
                    "cancel",
                    "pull",
                    "files",
                    "reviews",
                    "reviewComments",
                    "issueComments")) {
                final var input = new HashMap<String, Object>();
                input.put("operation", operation);
                for (final var field : List.of("repository", "page", "depth", "tags", "revision", "operationId")) {
                    input.put(field, null);
                }
                assertEquals(
                        "Context capability unavailable.",
                        context.execute(input, ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)))
                                .output());
            }
            assertEquals(
                    "Invalid context arguments.",
                    context.execute(
                                    Map.of("operation", "git"),
                                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)))
                            .output());
            assertEquals(
                    "Invalid context arguments.",
                    context.execute(Map.of(), ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)))
                            .output());
            assertEquals(
                    "Invalid context arguments.",
                    context.execute(
                                    new HashMap<>(Map.of("page", 1)),
                                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)))
                            .output());
            assertEquals(
                    "Invalid context arguments.",
                    context.execute(
                                    Map.of("operation", "prepare_source", "depth", 1.5),
                                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)))
                            .output());
            assertEquals(
                    "Invalid context arguments.",
                    context.execute(
                                    Map.of("operation", "prepare_source", "revision", "main"),
                                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)))
                            .output());
            assertEquals(
                    "Invalid context arguments.",
                    context.execute(
                                    Map.of("operation", "summary", "url", "https://example.org"),
                                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)))
                            .output());
        }
    }

    private static ToolContext context(
            final boolean turnCancelled, final boolean callCancelled, final Set<String> allowed) {
        return new ToolContext(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                () -> turnCancelled,
                null,
                () -> callCancelled,
                testWorkdir,
                new DisabledSandbox(testWorkdir),
                -1,
                allowed,
                null,
                null);
    }
}
