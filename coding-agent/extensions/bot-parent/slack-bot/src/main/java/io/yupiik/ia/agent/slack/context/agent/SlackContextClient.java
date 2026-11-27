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
package io.yupiik.ia.agent.slack.context.agent;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.slack.configuration.AgentSlackContextConfiguration;
import io.yupiik.ia.agent.slack.context.SlackContextMailbox;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class SlackContextClient {
    private final AgentSlackContextConfiguration configuration;
    private final JsonMapper json;

    public SlackContextClient(final AgentSlackContextConfiguration configuration, final JsonMapper json) {
        this.configuration = configuration;
        this.json = json;
    }

    @SuppressWarnings("try") // Successfully opening and closing the pinned handle is the capability check.
    public boolean usable() {
        if (!validConfiguration()) {
            return false;
        }
        try (final var ignored =
                new SlackContextMailbox(Path.of(configuration.directory()), json, configuration.channel())) {
            return true;
        } catch (final IOException | RuntimeException invalid) {
            return false;
        }
    }

    public ToolResult call(final Map<String, Object> args, final ToolContext context) {
        if (context.isCancelled()
                || context.callCancelled().getAsBoolean()
                || Thread.currentThread().isInterrupted()) {
            return ToolResult.error("Slack context request cancelled.");
        }
        if (context.allowedTools() != null && !context.allowedTools().contains("slack_context")) {
            return ToolResult.error("Slack context tool is not allowed.");
        }
        if (!validConfiguration()) {
            return ToolResult.error("Slack context capability unavailable.");
        }
        // Omit optional nulls before creating the immutable mailbox envelope.
        final Map<String, Object> arguments = new HashMap<>();
        args.forEach((key, value) -> {
            if (value != null) {
                arguments.put(key, value);
            }
        });
        try (final var mailbox =
                new SlackContextMailbox(Path.of(configuration.directory()), json, configuration.channel())) {
            final var body = mailbox.request(
                    arguments,
                    System.currentTimeMillis() + configuration.timeoutSeconds() * 1000L,
                    () -> context.isCancelled() || context.callCancelled().getAsBoolean());
            final var output = json.toString(body);
            return switch (String.valueOf(body.get("status"))) {
                case "ok", "no_results" -> ToolResult.ok(output);
                default -> ToolResult.error(output);
            };
        } catch (final IOException | RuntimeException unavailable) {
            // Never expose local paths, malformed documents or remote diagnostics through exception messages.
            return ToolResult.error("Slack context request unavailable.");
        }
    }

    private boolean validConfiguration() {
        try {
            return configuration != null
                    && configuration.enabled()
                    && configuration.timeoutSeconds() >= 1
                    && configuration.timeoutSeconds() <= 300
                    && SlackContextMailbox.validChannel(configuration.channel())
                    && configuration.directory() != null
                    && Path.of(configuration.directory()).isAbsolute();
        } catch (final RuntimeException invalid) {
            return false;
        }
    }
}
