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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolEnvironmentTest {
    @Test
    void acceptsExplicitNonsecretToolConfiguration() {
        assertDoesNotThrow(() -> ToolEnvironment.validate(Map.of(
                "MAVEN_OPTS", "-Duser.home=/attempt/storage -Dmaven.repo.local=/attempt/storage/maven",
                "npm_config_cache", "/attempt/storage/npm")));
    }

    @Test
    void rejectsInheritedRuntimeOrCredentialVariables() {
        for (final var key : List.of(
                "HOME",
                "path",
                "JAVA_TOOL_OPTIONS",
                "DOCKER_HOST",
                "LD_PRELOAD",
                "YCA_CONTEXT_TOKEN",
                "GITHUB_TOKEN",
                "GH_TOKEN",
                "GIT_CONFIG",
                "SSH_AUTH_SOCK",
                "KUBERNETES_SERVICE_HOST",
                "CUSTOM_PASSWORD",
                "HTTP_PROXY",
                "invalid-name")) {
            assertThrows(IllegalArgumentException.class, () -> ToolEnvironment.validate(Map.of(key, "value")), key);
        }
        for (final var value : List.of("line\nbreak", "line\rbreak", "nul\0value", "https://user:pass@host/path")) {
            assertThrows(IllegalArgumentException.class, () -> ToolEnvironment.validate(Map.of("MAVEN_OPTS", value)));
        }
    }

    @Test
    void rejectsUnboundedAndNullEnvironmentEntries() {
        assertThrows(IllegalArgumentException.class, () -> ToolEnvironment.validate(null));
        final var environment = new HashMap<String, String>();
        environment.put(null, "value");
        assertThrows(IllegalArgumentException.class, () -> ToolEnvironment.validate(environment));
        environment.clear();
        environment.put("MAVEN_OPTS", null);
        assertThrows(IllegalArgumentException.class, () -> ToolEnvironment.validate(environment));
        environment.clear();
        for (var i = 0; i < 129; i++) {
            environment.put("CACHE_" + i, "value");
        }
        assertThrows(IllegalArgumentException.class, () -> ToolEnvironment.validate(environment));
    }
}
