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
package io.yupiik.ia.agent.core.agent;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class AgentProvider {
    private static final int MAX_PROMPT_LENGTH = 8000;

    private final CodingAgentConfiguration configuration;

    public AgentProvider(final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
    }

    protected AgentProvider() {
        this.configuration = null;
    }

    public List<AgentConfiguration> agents() {
        final var agents = configuration.agents();
        return agents == null ? List.of() : agents;
    }

    public AgentConfiguration byName(final String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (final var agent : agents()) {
            if (name.equals(agent.name())) {
                return agent;
            }
        }
        return null;
    }

    /** Returns the configured profile tool ceiling, or null when the profile has no restriction. */
    public Set<String> allowedTools(final String name) {
        final var agent = byName(name);
        return agent == null || agent.tools() == null ? null : Set.copyOf(agent.tools());
    }

    /**
     * Inflates the agent prompt. A value is treated as a file path when it expands to an existing
     * regular file; otherwise it is used as inline text. Returns null when the agent is unknown.
     */
    public String resolvePrompt(final AgentConfiguration agent) {
        final var raw = agent.prompt() == null ? "" : agent.prompt();
        if (raw.isBlank()) {
            return "";
        }
        final var path = expandPath(raw);
        if (Files.isRegularFile(path)) {
            try {
                var content = Files.readString(path);
                if (content.length() > MAX_PROMPT_LENGTH) {
                    content = content.substring(0, MAX_PROMPT_LENGTH) + "\n[...truncated...]";
                }
                return content;
            } catch (final IOException e) {
                return "Agent prompt file unreadable: " + path;
            }
        }
        return raw.length() > MAX_PROMPT_LENGTH ? raw.substring(0, MAX_PROMPT_LENGTH) : raw;
    }

    private static Path expandPath(final String value) {
        final var result = CodingAgentConfiguration.expandHome(value == null ? null : value.strip());
        return result == null ? null : Path.of(result).toAbsolutePath().normalize();
    }
}
