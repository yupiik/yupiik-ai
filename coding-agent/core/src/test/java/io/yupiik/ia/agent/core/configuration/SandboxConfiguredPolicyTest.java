/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.yupiik.ia.agent.core.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@FusionSupport(modules = SandboxConfiguredPolicyTest.ConfiguredModule.class)
class SandboxConfiguredPolicyTest {
    @Test
    void initializationPersistsTheInjectedPolicyRatherThanAnIndependentPreset(
            @Fusion final SandboxConfigurationInitializer initializer,
            @Fusion final JsonMapper mapper,
            @TempDir final Path directory)
            throws IOException {
        final var path = directory.resolve("configuration.json");
        final var preview = initializer.initialize(path, false);
        assertFalse(Files.exists(path));
        final var expected = Map.of(
                "enabled",
                false,
                "helper",
                Map.of("executable", "/trusted/helper"),
                "macOs",
                Map.of("experimental", true),
                "environment",
                Map.of(
                        "inherit",
                        List.of("TOKEN"),
                        "set",
                        Map.of("DOCKER_HOST", "tcp://127.0.0.1:2375", "NO_PROXY", "127.0.0.1,localhost")),
                "unix",
                Map.of("socketBridges", List.of(Map.of("path", "/run/docker.sock", "port", BigDecimal.valueOf(2375)))),
                "filesystem",
                Map.of(
                        "allowRead", List.of("/configured/read"),
                        "allowWrite", List.of("/configured/write"),
                        "denyRead", List.of("/configured/secret"),
                        "denyWrite", List.of("/configured/control")),
                "network",
                Map.of("allowedDomains", List.of("example.invalid:443")),
                "trustedIntegrations",
                List.of("mcp:reviewed"));
        assertEquals(expected, mapper.fromString(Object.class, preview.defaultsJson()));
        initializer.initialize(path, true);
        final var document = (Map<?, ?>) mapper.fromString(Object.class, Files.readString(path));
        assertEquals(expected, ((Map<?, ?>) document.get("yca")).get("sandbox"));
    }

    public static class ConfiguredModule extends ConfigurationTestModule {
        public ConfiguredModule() {
            super(Map.ofEntries(
                    Map.entry("yca.sandbox.enabled", "false"),
                    Map.entry("yca.sandbox.helper.executable", "/trusted/helper"),
                    Map.entry("yca.sandbox.macOs.experimental", "true"),
                    Map.entry("yca.sandbox.environment.inherit", "TOKEN"),
                    Map.entry(
                            "yca.sandbox.environment.set",
                            "DOCKER_HOST=tcp://127.0.0.1:2375\nNO_PROXY=127.0.0.1,localhost"),
                    Map.entry("yca.sandbox.unix.socketBridges.length", "1"),
                    Map.entry("yca.sandbox.unix.socketBridges.0.path", "/run/docker.sock"),
                    Map.entry("yca.sandbox.unix.socketBridges.0.port", "2375"),
                    Map.entry("yca.sandbox.filesystem.allowRead", "/configured/read"),
                    Map.entry("yca.sandbox.filesystem.allowWrite", "/configured/write"),
                    Map.entry("yca.sandbox.filesystem.denyRead", "/configured/secret"),
                    Map.entry("yca.sandbox.filesystem.denyWrite", "/configured/control"),
                    Map.entry("yca.sandbox.network.allowedDomains", "example.invalid:443"),
                    Map.entry("yca.sandbox.trustedIntegrations", "mcp:reviewed")));
        }
    }
}
