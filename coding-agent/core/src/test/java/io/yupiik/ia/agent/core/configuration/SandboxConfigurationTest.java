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
package io.yupiik.ia.agent.core.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.container.configuration.ConfigurationImpl;
import io.yupiik.fusion.framework.api.container.configuration.ConfigurationRegistration;
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.wiring.LazyJsonConfigurationSource;
import io.yupiik.ia.agent.core.wiring.YcaConfigurationProducer;
import io.yupiik.ia.agent.core.wiring.YcaPrefixShortcutConfigurationSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SandboxConfigurationTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"sandbox\":{\"trustedIntegrations\":[\"mcp:evil\"]}}",
                "{\"yca.sandbox.trustedIntegrations\":[\"java:evil\"]}",
                "{\"yca-sandbox-trustedIntegrations\":[\"acp:terminal\"]}",
                "{\"sandbox\":{\"environment\":{\"set\":{\"TOKEN\":\"secret\"}}}}",
                "{\"sandbox\":{\"unix\":{\"socketBridges\":[{\"path\":\"/run/docker.sock\",\"port\":2375}]}}}",
                "{\"yca-sandbox-environment-inherit\":[\"TOKEN\"]}",
                "{\"sandbox\":{\"macOs\":{\"experimental\":true}}}",
                "{\"sandbox\":{\"helper\":{\"executable\":\"/untrusted/helper\"}}}",
                "{\"yca\":{\"sandbox\":{\"helper\":{\"executable\":\"/untrusted/helper\"}}}}",
                "{\"yca.sandbox.helper.executable\":\"/untrusted/helper\"}",
                "{\"yca-sandbox-helper-executable\":\"/untrusted/helper\"}",
                "{\"yca.sandbox.unix.socketBridges\":[{\"path\":\"/run/docker.sock\",\"port\":2375}]}",
                "{\"yca.sandbox.environment.set.TOKEN\":\"secret\"}",
                "{\"yca.sandbox.macOs.experimental\":true}",
                "{\"yca-sandbox-macOs-experimental\":true}",
                "{\"tui\":{\"sandbox\":false}}",
                "{\"yca\":{\"cli\":{\"sandbox\":false}}}",
                "{\"run.sandbox\":false}",
                "{\"yca-web-sandbox\":false}",
                "{\"acp.sandbox\":false}",
                "{\"future\":{\"command\":{\"sandbox\":{\"enabled\":false}}}}"
            })
    void projectCannotOptInTrustedSandboxExceptions(final String json) throws IOException {
        Files.createDirectory(root.resolve(".yca"));
        Files.writeString(root.resolve(".yca/configuration.json"), json);
        assertThrows(IllegalArgumentException.class, () -> fileConfiguration().get("yca.sandbox.enabled"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    void trustedGlobalOptInsSurviveProjectInheritance(final String inherit) throws IOException {
        Files.writeString(
                root.resolve("global.json"),
                "{\"sandbox\":{\"trustedIntegrations\":[\"mcp:docs\",\"acp:fs.readTextFile\"]}}");
        Files.createDirectory(root.resolve(".yca"));
        Files.writeString(root.resolve(".yca/configuration.json"), "{\"$inherit\":" + inherit + "}");
        final var configuration = fileConfiguration();
        assertEquals(
                "mcp:docs, acp:fs.readTextFile",
                configuration.get("yca.sandbox.trustedIntegrations").orElseThrow());
    }

    @Test
    void trustedCommandSandboxSettingSurvivesProjectWithoutInheritance() throws IOException {
        Files.writeString(root.resolve("global.json"), "{\"tui\":{\"sandbox\":false}}");
        Files.createDirectory(root.resolve(".yca"));
        Files.writeString(root.resolve(".yca/configuration.json"), "{\"$inherit\":false,\"tui\":{\"theme\":\"dark\"}}");
        final var configuration = fileConfiguration();
        assertEquals("false", configuration.get("yca.tui.sandbox").orElseThrow());
        assertEquals("dark", configuration.get("yca.tui.theme").orElseThrow());
    }

    @Test
    void readsNestedHelperAndMacOsFromTrustedGlobalJson() throws IOException {
        Files.writeString(
                root.resolve("global.json"),
                "{\"yca\":{\"sandbox\":{\"helper\":{\"executable\":\"/trusted/helper\"},"
                        + "\"macOs\":{\"experimental\":true}}}}");
        final var configuration = fileConfiguration();
        assertEquals(
                "/trusted/helper",
                configuration.get("yca.sandbox.helper.executable").orElseThrow());
        assertEquals("true", configuration.get("yca.sandbox.macOs.experimental").orElseThrow());
    }

    private Configuration fileConfiguration() {
        final var args = new ArgsConfigSource(List.of(
                "--yca.workingDir",
                root.toString(),
                "--yca.configuration.json",
                root.resolve("global.json").toString()));
        final var sources = new ArrayList<ConfigurationSource>();
        sources.add(new YcaPrefixShortcutConfigurationSource(args));
        new YcaConfigurationProducer(args)
                .register(new ConfigurationRegistration(new ConfigurationImpl(List.copyOf(sources)), sources::add));
        return new ConfigurationImpl(sources);
    }

    @Nested
    @FusionSupport(modules = ConfigurationTestModule.class)
    class Defaults {
        @Test
        void defaultsAreSafeAndLoadedThroughTheRootConfiguration(@Fusion final CodingAgentConfiguration configuration) {
            final var nested = configuration.sandbox();
            assertTrue(nested.enabled());
            assertEquals("", nested.helper().executable());
            assertFalse(nested.macOs().experimental());
            assertEquals(List.of(), nested.environment().inherit());
            assertEquals(Map.of(), nested.environment().set());
            assertEquals(List.of(), nested.unix().socketBridges());
            assertEquals(List.of(), nested.trustedIntegrations());
            final var directories = List.of("${projectdir}", "${workdir}", "${tempdir}");
            assertEquals(directories, nested.filesystem().allowRead());
            assertEquals(directories, nested.filesystem().allowWrite());
            assertEquals(
                    List.of(
                            "~/.ssh",
                            "~/.aws",
                            "~/.gnupg",
                            "glob:${projectdir}/.env*",
                            "glob:${projectdir}/**/.env*",
                            "glob:~/.m2/settings*.xml",
                            "~/.yupiik/coding-agent",
                            "~/.bashrc"),
                    nested.filesystem().denyRead());
            assertEquals(
                    List.of(
                            "~/.ssh",
                            "~/.aws",
                            "~/.gnupg",
                            "~/.yupiik",
                            "~/.config",
                            "${projectdir}/.yca",
                            "${workdir}/.yca",
                            "${projectdir}/.git",
                            "${workdir}/.git",
                            "${projectdir}/AGENTS.md",
                            "glob:${projectdir}/**/AGENTS.md",
                            "${workdir}/AGENTS.md",
                            "glob:~/.m2/settings*.xml",
                            "~/.yupiik/coding-agent",
                            "~/.bashrc"),
                    nested.filesystem().denyWrite());
            assertEquals(
                    List.of(
                            "repo.maven.apache.org:443",
                            "registry.npmjs.org:443",
                            "services.gradle.org:443",
                            "plugins.gradle.org:443",
                            "registry-1.docker.io:443",
                            "auth.docker.io:443",
                            "production.cloudfront.docker.com:443"),
                    nested.network().allowedDomains());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cli", "run", "web", "tui", "acp"})
    void globalSandboxEnabledUsesGenericShortcutsAcrossModes(final String mode) {
        for (final var enabled : List.of("true", "false")) {
            for (final var option : List.of("--sandbox.enabled", "--sandbox-enabled", "--yca.sandbox.enabled")) {
                final var source =
                        new YcaPrefixShortcutConfigurationSource(new ArgsConfigSource(List.of(mode, option, enabled)));
                assertEquals(enabled, source.get("yca.sandbox.enabled"), mode + " " + option);
            }
        }
    }

    @Nested
    @FusionSupport(modules = OverrideModule.class)
    class Overrides {
        @Test
        void launchOverrideChangesOnlyEnabled(@Fusion final CodingAgentConfiguration configuration) {
            final var policy = configuration.sandbox();
            assertSame(policy, policy.withEnabled(null));
            assertSame(policy, policy.withEnabled(false));
            final var enabled = policy.withEnabled(true);
            assertTrue(enabled.enabled());
            assertEquals(policy, enabled.withEnabled(false));
            assertSame(policy.helper(), enabled.helper());
            assertSame(policy.filesystem(), enabled.filesystem());
            assertSame(policy.network(), enabled.network());
            assertSame(policy.trustedIntegrations(), enabled.trustedIntegrations());
            assertSame(policy.macOs(), enabled.macOs());
            assertSame(policy.environment(), enabled.environment());
            assertSame(policy.unix(), enabled.unix());
            assertFalse(policy.enabled(), "the trusted global policy must remain unchanged");
        }

        @Test
        void bindsTheConfiguredPolicy(@Fusion final CodingAgentConfiguration configuration) {
            final var sandbox = new SandboxConfiguration(
                    false,
                    new SandboxHelperConfiguration("/trusted/helper"),
                    new SandboxFilesystemConfiguration(
                            List.of("/read"), List.of("/write"), List.of("/secret"), List.of("/control")),
                    new SandboxNetworkConfiguration(List.of("example.org:443")),
                    List.of("mcp:docs", "acp:fs.readTextFile"),
                    new SandboxMacOsConfiguration(true),
                    new SandboxEnvironmentConfiguration(List.of("TOKEN"), Map.of()),
                    new SandboxUnixConfiguration(List.of()));
            assertEquals(sandbox, configuration.sandbox());
        }

        @Test
        void trustedConfigurationCanDisableSandbox(@Fusion final CodingAgentConfiguration configuration) {
            assertFalse(configuration.sandbox().enabled());
        }
    }

    @Nested
    @FusionSupport(modules = GenericJsonModule.class)
    class GenericJson {
        @Test
        void bindsNestedEnvironmentAndSocketBridgeJson(@Fusion final CodingAgentConfiguration configuration) {
            assertEquals(
                    new SandboxEnvironmentConfiguration(
                            List.of("TOKEN"),
                            Map.of(
                                    "DOCKER_HOST",
                                    "tcp://127.0.0.1:2375",
                                    "NO_PROXY",
                                    "127.0.0.1,localhost",
                                    "LITERAL",
                                    "${HOME} $(touch injected)")),
                    configuration.sandbox().environment());
            assertEquals(
                    List.of(new SandboxUnixSocketBridgeConfiguration("/run/docker.sock", 2375)),
                    configuration.sandbox().unix().socketBridges());
        }
    }

    public static class GenericJsonModule extends ConfigurationTestModule {
        @Override
        public Stream<FusionBean<?>> beans() {
            return Stream.of(new ProvidedInstanceBean<>(DefaultScoped.class, ConfigurationSource.class, () -> {
                try {
                    final var file = Files.createTempFile("yca-generic-policy-", ".json");
                    try {
                        Files.writeString(file, """
                    {"yca":{"sandbox":{
                      "environment":{"inherit":["TOKEN"],"set":{
                        "DOCKER_HOST":"tcp://127.0.0.1:2375","NO_PROXY":"127.0.0.1,localhost","LITERAL":"${HOME} $(touch injected)"}},
                      "unix":{"socketBridges":[{"path":"/run/docker.sock","port":2375}]}
                    }}}
                    """);
                        final var source = new LazyJsonConfigurationSource(
                                new ConfigurationImpl(List.of((ConfigurationSource) key -> switch (key) {
                                    case "yca.configuration.json" -> file.toString();
                                    case "yca.configuration.project.enabled" -> "false";
                                    default -> null;
                                })));
                        source.get("yca.sandbox.enabled");
                        return source;
                    } finally {
                        Files.deleteIfExists(file);
                    }
                } catch (final IOException error) {
                    throw new UncheckedIOException(error);
                }
            }));
        }
    }

    public static class OverrideModule extends ConfigurationTestModule {
        public OverrideModule() {
            super(Map.of(
                    "yca.sandbox.enabled", "false",
                    "yca.sandbox.helper.executable", "/trusted/helper",
                    "yca.sandbox.macOs.experimental", "true",
                    "yca.sandbox.environment.inherit", "TOKEN",
                    "yca.sandbox.filesystem.allowRead", "/read",
                    "yca.sandbox.filesystem.allowWrite", "/write",
                    "yca.sandbox.filesystem.denyRead", "/secret",
                    "yca.sandbox.filesystem.denyWrite", "/control",
                    "yca.sandbox.network.allowedDomains", "example.org:443",
                    "yca.sandbox.trustedIntegrations", "mcp:docs,acp:fs.readTextFile"));
        }
    }
}
