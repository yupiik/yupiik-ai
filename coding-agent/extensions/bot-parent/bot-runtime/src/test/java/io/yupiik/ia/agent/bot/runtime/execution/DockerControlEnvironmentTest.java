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
package io.yupiik.ia.agent.bot.runtime.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DockerControlEnvironmentTest {
    @Test
    void preservesExplicitConnectionSettingsButDropsAmbientSecretsAndOverrides() {
        final var builder = new ProcessBuilder("unused");
        final var environment = builder.environment();
        environment.clear();
        final var expected = Map.ofEntries(
                Map.entry("PATH", "/usr/bin"),
                Map.entry("HOME", "/dispatcher"),
                Map.entry("DOCKER_HOST", "ssh://docker-host"),
                Map.entry("DOCKER_CONTEXT", "rootless"),
                Map.entry("DOCKER_CONFIG", "/dispatcher/docker"),
                Map.entry("DOCKER_TLS", "1"),
                Map.entry("DOCKER_TLS_VERIFY", "1"),
                Map.entry("DOCKER_CERT_PATH", "/dispatcher/certs"),
                Map.entry("DOCKER_API_VERSION", "1.51"),
                Map.entry("XDG_RUNTIME_DIR", "/run/user/1001"),
                Map.entry("XDG_CONFIG_HOME", "/dispatcher/config"),
                Map.entry("XDG_DATA_HOME", "/dispatcher/data"),
                Map.entry("XDG_STATE_HOME", "/dispatcher/state"),
                Map.entry("SSH_AUTH_SOCK", "/run/user/1001/ssh"));
        environment.putAll(expected);
        for (final var name : List.of(
                "GH_TOKEN",
                "GITHUB_BOT_FORK_TOKEN",
                "GITHUB_SOURCE_TOKEN",
                "YCA_CONTEXT_TOKEN",
                "YCA_CONTEXT_GIT_TOKEN",
                "AWS_SECRET_ACCESS_KEY",
                "DOCKER_AUTH_CONFIG",
                "DOCKER_CUSTOM_SECRET",
                "HTTP_PROXY",
                "JAVA_TOOL_OPTIONS",
                "LD_PRELOAD",
                "BASH_ENV")) {
            environment.put(name, "must-not-inherit");
        }
        assertSame(builder, DockerControlEnvironment.sanitize(builder));
        assertEquals(expected, environment);
        DockerControlEnvironment.sanitize(builder);
        assertEquals(expected, environment);
    }
}
