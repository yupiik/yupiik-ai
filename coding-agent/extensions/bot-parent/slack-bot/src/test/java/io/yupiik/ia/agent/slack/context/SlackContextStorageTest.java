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
package io.yupiik.ia.agent.slack.context;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.bot.runtime.AttemptPreparation;
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.bot.runtime.model.AgentConfigurationDocument;
import io.yupiik.ia.agent.core.llm.JsonMappers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlackContextStorageTest {
    @TempDir
    Path home;

    @Test
    void acceptsConfigurablePrivateRootAndTmpfsBindSubdirectory() throws Exception {
        final var root = root("custom attempts");
        final var metadata = "81 12 0:30 /some/subdirectory " + escape(root)
                + " rw,nosuid,nodev,noexec shared:7 - tmpfs tmpfs rw,noswap,size=512m";
        assertEquals(root, storage(List.of(disk(), metadata)).validate(configuration(root), false, false));
    }

    @Test
    void decodesAllKernelMountPointEscapes() throws Exception {
        final var root = root("attempt \\ tab\t newline\n");
        assertEquals(
                root,
                storage(List.of(disk(), mount(root, "tmpfs", "rw,nodev,nosuid,noexec", "rw,noswap")))
                        .validate(configuration(root), false, false));
    }

    @Test
    void refusesUnreadableMountMetadataRatherThanFallingBackToFileStore() throws Exception {
        final var root = root("attempts");
        final var helper = new SlackContextStorage() {
            @Override
            protected List<String> mountInfo() throws IOException {
                throw new IOException("unavailable");
            }
        };
        assertThrows(IOException.class, () -> helper.validate(configuration(root), false, false));
    }

    @Test
    void longestMountWinsEvenWhenEarlierOrLaterAncestorIsTmpfs() throws Exception {
        final var root = root("attempts");
        final var parent = mount(home, "tmpfs", "rw,nodev,nosuid,noexec", "rw,noswap");
        final var child = mount(root, "ext4", "rw,nodev,nosuid,noexec", "rw");
        for (final var lines : List.of(List.of(disk(), parent, child), List.of(child, parent, disk()))) {
            for (final var accepted : List.of(false, true)) {
                assertThrows(IOException.class, () -> storage(lines).validate(configuration(root), accepted, false));
            }
        }
        assertEquals(root, storage(List.of(disk(), parent)).validate(configuration(root), false, false));
    }

    @Test
    void rejectsDiskSwapAndMissingPerMountFlags() throws Exception {
        final var root = root("attempts");
        for (final var type : List.of("ext4", "overlay", "ramfs")) {
            for (final var accepted : List.of(false, true)) {
                assertThrows(
                        IOException.class,
                        () -> storage(List.of(mount(root, type, "rw,nodev,nosuid,noexec", "rw,noswap")))
                                .validate(configuration(root), accepted, false));
            }
        }
        for (final var flags :
                List.of("rw,nosuid,noexec", "rw,nodev,noexec", "rw,nodev,nosuid", "ro,nodev,nosuid,noexec")) {
            // Superblock flags cannot compensate for flags missing on a bind mount.
            for (final var accepted : List.of(false, true)) {
                assertThrows(
                        IOException.class,
                        () -> storage(List.of(mount(root, "tmpfs", flags, "rw,noswap,nodev,nosuid,noexec")))
                                .validate(configuration(root), accepted, false));
            }
        }
        for (final var flags : List.of("rw", "rw,swap", "rw,noswap=false")) {
            assertThrows(
                    IOException.class,
                    () -> storage(List.of(mount(root, "tmpfs", "rw,nodev,nosuid,noexec", flags)))
                            .validate(configuration(root), false, false));
        }
    }

    @Test
    void explicitlyAcceptedSwapBackedTmpfsRelaxesOnlyNoswap() throws Exception {
        final var root = root("accepted attempts");
        final var helper = storage(List.of(disk(), mount(root, "tmpfs", "rw,nodev,nosuid,noexec", "rw")));
        assertThrows(IOException.class, () -> helper.validate(configuration(root), false, false));
        assertEquals(root, helper.validate(configuration(root), true, false));
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-x---"));
        assertThrows(IOException.class, () -> helper.validate(configuration(root), true, false));
    }

    @Test
    void rejectsMissingMalformedAmbiguousAndNestedMounts() throws Exception {
        final var root = root("attempts");
        final var valid = mount(root, "tmpfs", "rw,nodev,nosuid,noexec", "rw,noswap");
        for (final var lines : List.of(
                List.<String>of(),
                List.of("invalid"),
                List.of(valid, valid),
                List.of(valid, mount(root.resolve("nested"), "ext4", "rw", "rw")),
                List.of(mount(home.resolve("attempts-other"), "tmpfs", "rw,nodev,nosuid,noexec", "rw,noswap")),
                List.of(valid.replace(escape(root), escape(root) + "\\999")))) {
            for (final var accepted : List.of(false, true)) {
                assertThrows(IOException.class, () -> storage(lines).validate(configuration(root), accepted, false));
            }
        }
    }

    @Test
    void rejectsMissingRelativeUnnormalizedSymlinkAndNonprivateRoots() throws Exception {
        final var root = root("attempts");
        final var helper = storage(List.of(mount(home, "tmpfs", "rw,nodev,nosuid,noexec", "rw,noswap")));
        for (final var path : List.of(Path.of("relative"), root.resolve(".."))) {
            assertThrows(IllegalArgumentException.class, () -> helper.validate(configuration(path), false, false));
        }
        assertThrows(IllegalArgumentException.class, () -> helper.validate(configuration(Path.of("")), false, false));
        assertThrows(IOException.class, () -> helper.validate(configuration(home.resolve("missing")), false, false));
        final var link = Files.createSymbolicLink(home.resolve("link"), root);
        final var nested = Files.createDirectory(root.resolve("nested"));
        assertThrows(IOException.class, () -> helper.validate(configuration(link), true, false));
        assertThrows(
                IOException.class,
                () -> helper.validate(configuration(link.resolve(nested.getFileName())), true, false));
        for (final var permissions : List.of("rwxr-x---", "rwx---rwx", "rw-------")) {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString(permissions));
            assertThrows(IOException.class, () -> helper.validate(configuration(root), false, false));
        }
    }

    @Test
    void rejectsUnsupportedPlatformAndBackendsWithoutReadingMounts() throws Exception {
        final var root = root("attempts");
        for (final var backend :
                List.of(ExecutorConfiguration.Backend.NONO, ExecutorConfiguration.Backend.KUBERNETES)) {
            final var config =
                    new ExecutorConfiguration(backend, root.toString(), 60, 5, "hidden", null, null, null, null);
            for (final var allowLogs : List.of(false, true)) {
                assertThrows(
                        IllegalArgumentException.class, () -> storage(List.of()).validate(config, true, allowLogs));
            }
        }
        final var helper = new SlackContextStorage() {
            @Override
            protected String operatingSystem() {
                return "Mac OS X";
            }
        };
        for (final var allowLogs : List.of(false, true)) {
            assertThrows(IllegalArgumentException.class, () -> helper.validate(configuration(root), false, allowLogs));
        }
    }

    @Test
    void sanitizesWrappedAndPlainTrustedConfigurationWithoutChangingProviderOrPaths() throws Exception {
        try (final var json = JsonMappers.create(ignored -> Optional.empty())) {
            for (final var wrapped : List.of(true, false)) {
                final var source = "\"provider\":\"openai\",\"home\":\"/attempt/storage\","
                        + "\"workingDir\":\"/attempt/workspace\",\"model\":\"trusted\","
                        + "\"memory\":{\"enabled\":true,\"secret\":\"canary\"},"
                        + "\"skills\":{\"enabled\":true},\"subagents\":{\"enabled\":true},"
                        + "\"mcp\":{\"enabled\":true,\"servers\":[{\"url\":\"https://docs.example/mcp\"}]},\"notifications\":{\"enabled\":true},"
                        + "\"hooks\":{\"onStart\":\"canary\"},\"tools\":{\"builtin\":{\"enabled\":true},"
                        + "\"websearch\":{\"providers\":[],\"timeoutSeconds\":12}}";
                final var file = Files.writeString(
                        home.resolve("config-" + wrapped + ".json"),
                        wrapped
                                ? "{\"yca\":{" + source + "},\"run\":{\"result-correction-prompt\":\"completion\"}}"
                                : "{" + source + ",\"run\":{\"result-correction-prompt\":\"completion\"}}");
                final var trustedTools = json.fromString(AgentConfigurationDocument.class, Files.readString(file))
                        .configuration()
                        .get("tools");
                new SlackContextStorage().sanitize(file, json);
                final var output = Files.readString(file);
                assertFalse(output.contains("canary"));
                final var document = json.fromString(AgentConfigurationDocument.class, output);
                final var settings = document.configuration();
                for (final var key : List.of("memory", "skills", "subagents", "notifications")) {
                    assertEquals(Map.of("enabled", false), settings.get(key));
                }
                assertEquals(
                        Map.of("enabled", true, "servers", List.of(Map.of("url", "https://docs.example/mcp"))),
                        settings.get("mcp"));
                assertEquals(Map.of(), settings.get("hooks"));
                assertEquals(trustedTools, settings.get("tools"));
                assertEquals("openai", settings.get("provider"));
                assertEquals("trusted", settings.get("model"));
                assertEquals("/attempt/storage", settings.get("home"));
                assertEquals("/attempt/workspace", settings.get("workingDir"));
                assertEquals(
                        Map.of("run", Map.of("result-correction-prompt", "completion")), document.completionSettings());
                new SlackContextStorage().sanitize(file, json);
                assertEquals(output, Files.readString(file));
            }
        }
    }

    @Test
    void refusesVisibleToolOutputAndMissingServicesRegardlessOfContainerLogsOptIn() {
        final var safe = configuration(home);
        final var docker = safe.docker();
        for (final var unsafe : List.of(
                new ExecutorConfiguration(safe.backend(), safe.attemptRoot(), 60, 5, "shown", docker, null, null, null),
                new ExecutorConfiguration(safe.backend(), safe.attemptRoot(), 60, 5, "hidden", null, null, null, null),
                new ExecutorConfiguration(
                        safe.backend(),
                        safe.attemptRoot(),
                        60,
                        5,
                        "hidden",
                        new ExecutorConfiguration.Docker("docker", "agent:test", "", null, null, true),
                        null,
                        null,
                        null))) {
            for (final var accepted : List.of(false, true)) {
                for (final var allowLogs : List.of(false, true)) {
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> storage(List.of()).validate(unsafe, accepted, allowLogs));
                }
            }
        }
    }

    @Test
    void containerLogsRequireOptInAndDoNotRelaxMountValidation() throws Exception {
        final var root = root("logging attempts");
        final var safe = configuration(root);
        final var logging = new ExecutorConfiguration(
                safe.backend(),
                safe.attemptRoot(),
                60,
                5,
                "hidden",
                new ExecutorConfiguration.Docker(
                        "docker", "agent:test", "", null, safe.docker().services(), false),
                null,
                null,
                null);
        final var valid = mount(root, "tmpfs", "rw,nodev,nosuid,noexec", "rw,noswap");
        final var helper = storage(List.of(disk(), valid));
        for (final var accepted : List.of(false, true)) {
            assertThrows(IllegalArgumentException.class, () -> helper.validate(logging, accepted, false));
            assertEquals(root, helper.validate(logging, accepted, true));
            for (final var lines : List.of(
                    List.<String>of(),
                    List.of("invalid"),
                    List.of(valid, valid),
                    List.of(valid, mount(root.resolve("nested"), "ext4", "rw", "rw")),
                    List.of(mount(root, "ext4", "rw,nodev,nosuid,noexec", "rw,noswap")),
                    List.of(mount(root, "overlay", "rw,nodev,nosuid,noexec", "rw,noswap")),
                    List.of(mount(root, "ramfs", "rw,nodev,nosuid,noexec", "rw,noswap")))) {
                assertThrows(IOException.class, () -> storage(lines).validate(logging, accepted, true));
            }
            for (final var flags :
                    List.of("rw,nosuid,noexec", "rw,nodev,noexec", "rw,nodev,nosuid", "ro,nodev,nosuid,noexec")) {
                assertThrows(
                        IOException.class,
                        () -> storage(List.of(mount(root, "tmpfs", flags, "rw,noswap,nodev,nosuid,noexec")))
                                .validate(logging, accepted, true));
            }
        }
        final var swapBacked = storage(List.of(mount(root, "tmpfs", "rw,nodev,nosuid,noexec", "rw")));
        assertThrows(IOException.class, () -> swapBacked.validate(logging, false, true));
        assertEquals(root, swapBacked.validate(logging, true, true));
        for (final var permissions : List.of("rwxr-x---", "rwx---rwx", "rw-------")) {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString(permissions));
            for (final var accepted : List.of(false, true)) {
                assertThrows(IOException.class, () -> helper.validate(logging, accepted, true));
            }
        }
    }

    @Test
    void preservesTrustedIntegrationsAcrossSerializedPreparationAndSanitization() throws Exception {
        try (final var json = JsonMappers.create(ignored -> Optional.empty())) {
            for (final var wrapped : List.of(true, false)) {
                final var source = """
                        "provider":{"type":"openai","apiKey":"configured-provider-key"},
                        "tools":{"parallelReadonly":false,"websearch":{"timeoutSeconds":12,"providers":[
                          {"provider":"EXA","enabled":true,"apiKey":"configured-search-key","url":"https://search.example/mcp"}]}},
                        "mcp":{"enabled":true,"servers":[{"name":"docs","url":"https://docs.example/mcp",
                          "headers":{"Authorization":"configured-mcp-key"},"allowedTools":["lookup"]}]},
                        "hooks":{"onStart":"blocked-hook"},"memory":{"enabled":true,"token":"blocked-memory"},
                        "skills":{"enabled":true},"subagents":{"enabled":true},"notifications":{"enabled":true},
                        "bot":{"feedback":{"enabled":true}},"httpClient":{"debug":true},
                        "slack":{"token":"blocked-slack"},"enterprise":{"token":"blocked-enterprise"},
                        "project":{"enabled":true}
                        """;
                final var trusted = Files.writeString(
                        home.resolve("trusted-" + wrapped + ".json"),
                        wrapped ? "{\"yca\":{" + source + "}}" : "{" + source + "}");
                final var operator = json.fromString(AgentConfigurationDocument.class, Files.readString(trusted))
                        .configuration();
                final var directory =
                        new AttemptPreparation(json).prepare(trusted, home.resolve("prepared"), "yca-context-");
                try {
                    final var file = directory.resolve("configuration.json");
                    new SlackContextStorage().sanitize(file, json);
                    final var output = Files.readString(file);
                    final var settings = json.fromString(AgentConfigurationDocument.class, output)
                            .configuration();
                    for (final var key : List.of("tools", "mcp", "provider")) {
                        assertEquals(operator.get(key), settings.get(key), key);
                    }
                    for (final var key : List.of("memory", "skills", "subagents", "notifications")) {
                        assertEquals(Map.of("enabled", false), settings.get(key));
                    }
                    assertEquals(Map.of(), settings.get("hooks"));
                    assertEquals(Map.of("feedback", Map.of("enabled", false)), settings.get("bot"));
                    assertEquals(Map.of("debug", false), settings.get("httpClient"));
                    assertEquals("/attempt/storage", settings.get("storageHome"));
                    assertFalse(settings.containsKey("project"));
                    assertFalse(output.contains("blocked-"));
                    new SlackContextStorage().sanitize(file, json);
                    assertEquals(output, Files.readString(file));
                } finally {
                    AttemptPreparation.delete(directory);
                }
            }
        }
    }

    @Test
    void preservesAbsentNullPartialAndDisabledToolPoliciesInSerializedChain() throws Exception {
        try (final var json = JsonMappers.create(ignored -> Optional.empty())) {
            for (final var source : List.of(
                    "{}",
                    "{\"tools\":null,\"mcp\":null}",
                    "{\"tools\":{\"websearch\":null}}",
                    "{\"tools\":{\"websearch\":{\"providers\":null,\"timeoutSeconds\":12}}}",
                    "{\"tools\":{\"websearch\":{\"providers\":[]}},\"mcp\":{\"enabled\":false,\"servers\":[]}}")) {
                final var trusted = Files.writeString(home.resolve("trusted.json"), source);
                final var directory =
                        new AttemptPreparation(json).prepare(trusted, home.resolve("prepared"), "yca-context-");
                try {
                    final var file = directory.resolve("configuration.json");
                    final var trustedTools = json.fromString(AgentConfigurationDocument.class, Files.readString(file))
                            .configuration()
                            .get("tools");
                    new SlackContextStorage().sanitize(file, json);
                    final var settings = json.fromString(AgentConfigurationDocument.class, Files.readString(file))
                            .configuration();
                    assertEquals(Map.of("enabled", false, "servers", List.of()), settings.get("mcp"));
                    assertEquals(trustedTools, settings.get("tools"));
                } finally {
                    AttemptPreparation.delete(directory);
                }
            }
        }
    }

    @Test
    void acceptsOperatorEgressPolicyWithoutAddingHostsOrEnablingIt() throws Exception {
        final var root = root("egress attempts");
        final var safe = configuration(root);
        final var services = safe.docker().services();
        for (final var enabled : List.of(false, true)) {
            for (final var hosts :
                    List.of(List.<String>of(), List.of("repo.maven.apache.org", "mcp.exa.ai", "docs.example"))) {
                final var configuredServices = new ExecutorConfiguration.Docker.Services(
                        services.image(),
                        services.controlNetwork(),
                        services.keyStore(),
                        services.keyStorePasswordEnv(),
                        services.serverName(),
                        services.providerTarget(),
                        new ExecutorConfiguration.Docker.Egress("egress:test", hosts, "127.0.0.1", enabled));
                final var executor = new ExecutorConfiguration(
                        safe.backend(),
                        safe.attemptRoot(),
                        60,
                        5,
                        "hidden",
                        new ExecutorConfiguration.Docker("docker", "agent:test", "", null, configuredServices, true),
                        null,
                        null,
                        null);
                for (final var accepted : List.of(false, true)) {
                    assertEquals(
                            root,
                            storage(List.of(mount(root, "tmpfs", "rw,nodev,nosuid,noexec", "rw,noswap")))
                                    .validate(executor, accepted, false));
                    assertEquals(
                            hosts,
                            executor.docker().services().dependencyEgress().allowedHosts());
                    assertEquals(
                            enabled,
                            executor.docker().services().dependencyEgress().enabled());
                }
            }
        }
    }

    @Test
    void preservesOrdinaryToolDefaultsAndRejectsConfigurationSymlinks() throws Exception {
        try (final var json = JsonMappers.create(ignored -> Optional.empty())) {
            final var file = Files.writeString(home.resolve("config.json"), "{}");
            final var link = Files.createSymbolicLink(home.resolve("config-link.json"), file);
            final var helper = new SlackContextStorage();
            assertThrows(IOException.class, () -> helper.sanitize(link, json));
            assertEquals("{}", Files.readString(file));
            helper.sanitize(file, json);
            final var sanitized = json.fromString(AgentConfigurationDocument.class, Files.readString(file))
                    .configuration();
            assertEquals(Map.of("feedback", Map.of("enabled", false)), sanitized.get("bot"));
            assertEquals(Map.of("debug", false), sanitized.get("httpClient"));
            assertFalse(sanitized.containsKey("tools"));
        }
    }

    private Path root(final String name) throws IOException {
        return Files.createDirectory(
                        home.resolve(name),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                .toRealPath();
    }

    private ExecutorConfiguration configuration(final Path root) {
        final var services = new ExecutorConfiguration.Docker.Services(
                "services:test",
                "control",
                "/private/tls.p12",
                "PASSWORD",
                "attempt-services.yca.internal",
                "http://192.168.1.2:8080/v1",
                new ExecutorConfiguration.Docker.Egress("egress:test", List.of(), "127.0.0.1", false));
        return new ExecutorConfiguration(
                ExecutorConfiguration.Backend.DOCKER,
                root.toString(),
                60,
                5,
                "hidden",
                new ExecutorConfiguration.Docker("docker", "agent:test", "", null, services, true),
                null,
                null,
                null);
    }

    private SlackContextStorage storage(final List<String> lines) {
        return new SlackContextStorage() {
            @Override
            protected List<String> mountInfo() {
                return lines;
            }
        };
    }

    private String disk() {
        return mount(Path.of("/"), "ext4", "rw", "rw");
    }

    private String mount(final Path point, final String type, final String options, final String superOptions) {
        return "20 1 0:30 / " + escape(point) + " " + options + " - " + type + " tmpfs " + superOptions;
    }

    private String escape(final Path path) {
        return path.toString()
                .replace("\\", "\\134")
                .replace(" ", "\\040")
                .replace("\t", "\\011")
                .replace("\n", "\\012");
    }
}
