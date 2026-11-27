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
package io.yupiik.ia.agent.bot.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.bot.runtime.model.AgentConfigurationDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AttemptPreparationTest {
    @TempDir
    Path home;

    @Test
    void preparesPrivateNeutralAttemptWithLegacyConfigurationPolicy() throws IOException {
        final var trusted = Files.writeString(home.resolve("trusted.json"), """
                {"yca":{"provider":{"type":"openai","apiKey":"secret"},"model":"trusted-model",
                "home":"/host","workingDir":"/host/work","hooks":{"before":"unsafe"},
                "permissions":{"mode":"ASK"},"githubContext":{"enabled":true},
                "skills":{"enabled":true},"mcp":{"enabled":true,"servers":[]}},
                "run":{"result-correction-prompt":"correct-result","unsafe":"drop"}}
                """);
        try (final var json = RuntimeJsonMapper.create(key -> Optional.empty())) {
            final var directory = new AttemptPreparation(json).prepare(trusted, home.resolve("attempts"), "yca-slack-");
            try {
                assertTrue(directory.getFileName().toString().startsWith("yca-slack-"));
                assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
                assertEquals(
                        PosixFilePermissions.fromString("rw-------"),
                        Files.getPosixFilePermissions(directory.resolve("configuration.json")));
                assertEquals("yca-prepared-v1\n", Files.readString(directory.resolve(".prepared-only")));
                for (final var name : List.of("storage", "workspace", "output")) {
                    assertTrue(Files.isDirectory(directory.resolve(name)));
                }
                final var document = json.fromString(
                        AgentConfigurationDocument.class, Files.readString(directory.resolve("configuration.json")));
                final var config = document.configuration();
                assertEquals("trusted-model", config.get("model"));
                assertEquals("secret", JsonObjects.map(config.get("provider")).get("apiKey"));
                assertEquals("/attempt/storage", config.get("home"));
                assertEquals("/attempt/workspace", config.get("workingDir"));
                assertEquals("BUILD", config.get("mode"));
                assertEquals(Map.of("mode", "YOLO"), config.get("permissions"));
                assertEquals(Map.of(), config.get("hooks"));
                assertFalse(config.containsKey("githubContext"));
                assertEquals(Map.of("enabled", true), config.get("skills"));
                assertEquals(Map.of("enabled", false), config.get("memory"));
                assertEquals(Map.of("run", Map.of("result-correction-prompt", "correct-result")), document.settings());
            } finally {
                AttemptPreparation.delete(directory);
            }
        }
    }

    @Test
    void preservesOnlyTrustedOperatorToolsAndMcpInSerializedHandoff() throws IOException {
        try (final var json = RuntimeJsonMapper.create(key -> Optional.empty())) {
            final var tools = Map.of(
                    "websearch",
                    Map.of("providers", List.of(Map.of("provider", "EXA", "apiKey", "search-key"))),
                    "parallelReadonly",
                    false);
            final var mcp = Map.of(
                    "enabled",
                    true,
                    "servers",
                    List.of(Map.of("url", "https://docs.example/mcp", "headers", Map.of("Authorization", "mcp-key"))));
            final var trusted = Files.writeString(
                    home.resolve("operator.json"),
                    json.toString(new AgentConfigurationDocument(
                            Map.of(
                                    "tools",
                                    tools,
                                    "mcp",
                                    mcp,
                                    "slack",
                                    Map.of("token", "slack-canary"),
                                    "enterprise",
                                    Map.of("token", "enterprise-canary"),
                                    "project",
                                    Map.of("enabled", true)),
                            Map.of("tools", Map.of("shell", "repository-canary")))));
            final var directory = new AttemptPreparation(json).prepare(trusted, home.resolve("attempts"), "yca-test-");
            try {
                Files.writeString(
                        directory.resolve("workspace/.yca.json"), "{\"tools\":{\"shell\":\"repository-canary\"}}");
                final var output = Files.readString(directory.resolve("configuration.json"));
                final var configuration = json.fromString(AgentConfigurationDocument.class, output)
                        .configuration();
                assertEquals(tools, configuration.get("tools"));
                assertEquals(mcp, configuration.get("mcp"));
                for (final var canary : List.of("slack-canary", "enterprise-canary", "repository-canary")) {
                    assertFalse(output.contains(canary));
                }
                assertFalse(configuration.containsKey("project"));
            } finally {
                AttemptPreparation.delete(directory);
            }
        }
    }

    @Test
    void doesNotInventToolsOrEnableMcpWhenAbsentOrNull() throws IOException {
        try (final var json = RuntimeJsonMapper.create(key -> Optional.empty())) {
            for (final var source : List.of("{}", "{\"tools\":null,\"mcp\":null}")) {
                final var trusted = Files.writeString(home.resolve("operator.json"), source);
                final var directory =
                        new AttemptPreparation(json).prepare(trusted, home.resolve("attempts"), "yca-test-");
                try {
                    final var configuration = json.fromString(
                                    AgentConfigurationDocument.class,
                                    Files.readString(directory.resolve("configuration.json")))
                            .configuration();
                    assertFalse(configuration.containsKey("tools"));
                    assertEquals(Map.of("enabled", false, "servers", List.of()), configuration.get("mcp"));
                } finally {
                    AttemptPreparation.delete(directory);
                }
            }
        }
    }

    @Test
    void rejectsSymlinksAndCleansUpInvalidConfiguration() throws IOException {
        final var trusted = Files.writeString(home.resolve("trusted.json"), "{}");
        final var linked = Files.createSymbolicLink(home.resolve("linked.json"), trusted);
        final var root = Files.createDirectory(home.resolve("attempts"));
        final var linkedRoot = Files.createSymbolicLink(home.resolve("linked-root"), root);
        try (final var json = RuntimeJsonMapper.create(key -> Optional.empty())) {
            final var preparation = new AttemptPreparation(json);
            assertThrows(IllegalArgumentException.class, () -> preparation.prepare(linked, root, "yca-test-"));
            assertThrows(IllegalArgumentException.class, () -> preparation.prepare(trusted, linkedRoot, "yca-test-"));
            assertThrows(IllegalArgumentException.class, () -> preparation.prepare(trusted, root, "../unsafe"));
            Files.writeString(trusted, "invalid-json");
            assertThrows(RuntimeException.class, () -> preparation.prepare(trusted, root, "yca-test-"));
            try (final var children = Files.list(root)) {
                assertEquals(0L, children.count());
            }
        }
    }

    @Test
    void deletionNeverFollowsAttemptLinks() throws IOException {
        final var external = Files.writeString(home.resolve("external"), "keep");
        final var attempt = Files.createDirectory(home.resolve("attempt"));
        Files.createSymbolicLink(attempt.resolve("link"), external);
        AttemptPreparation.delete(attempt);
        assertFalse(Files.exists(attempt));
        assertEquals("keep", Files.readString(external));
    }
}
