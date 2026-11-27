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

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.List;

@JsonModel
public record McpServerConfiguration(
        @Property(
                required = true,
                documentation = "Server name, used to prefix its tools (eg. context7_resolve-library-id).")
        String name,

        @Property(documentation = "Command to spawn for a stdio server (eg. npx). Mandatory when url is not set.")
        String command,

        @Property(
                documentation =
                        "Command arguments, comma separated in flat configuration, JSON array in configuration.json.")
        List<String> args,

        @Property(documentation = "Environment variables of the spawned process, KEY=VALUE entries.")
        List<String> env,

        @Property(
                documentation = "Working directory for the spawned stdio server process. Defaults to the agent process"
                        + " directory; set it to root the server (eg. a project-local npx/deno script) in a"
                        + " specific directory. Ignored for HTTP (url) servers.")
        String dir,

        @Property(documentation = "URL of a streamable HTTP server, alternative to command.")
        String url,

        @Property(documentation = "Optional API key sent as Authorization: Bearer header for HTTP servers.")
        String apiKey,

        @Property(documentation = "Extra HTTP headers for HTTP servers, KEY=VALUE entries.")
        List<String> headers,

        @Property(defaultValue = "true", documentation = "Enable this server.")
        boolean enabled,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Tool names of this server treated as read-only (the only MCP tools allowed in plan mode). `*` enables all tools of the server.")
        List<String> readonly,

        @Property(
                defaultValue =
                        "io.yupiik.ia.agent.core.configuration.McpServerConfiguration.DEFAULT_AVOID_ON_ERROR_PATTERNS",
                documentation =
                        "Regular expressions searched in sanitized failed MCP tool diagnostics. A match advises "
                                + "the model to avoid all tools of this server for the current task, without disabling them. "
                                + "Defaults match explicit daily/monthly quota or request-limit exhaustion. Custom values "
                                + "replace the defaults; an empty list disables this advice. Successful results are never matched.")
        List<String> avoidOnErrorPatterns) {

    public McpServerConfiguration {
        avoidOnErrorPatterns =
                avoidOnErrorPatterns == null || DEFAULT_AVOID_ON_ERROR_PATTERNS.equals(avoidOnErrorPatterns)
                        ? DEFAULT_AVOID_ON_ERROR_PATTERNS
                        : List.copyOf(avoidOnErrorPatterns);
    }

    public McpServerConfiguration(
            final String name,
            final String command,
            final List<String> args,
            final List<String> env,
            final String dir,
            final String url,
            final String apiKey,
            final List<String> headers,
            final boolean enabled,
            final List<String> readonly) {
        this(name, command, args, env, dir, url, apiKey, headers, enabled, readonly, null);
    }

    public static final List<String> DEFAULT_AVOID_ON_ERROR_PATTERNS = List.of(
            "(?i)\\b(?:daily|monthly)\\s+quota\\s+(?:reached|exceeded|exhausted)\\b",
            "(?i)\\b(?:daily|monthly)\\s+(?:request|API request)\\s+limit\\s+(?:reached|exceeded|exhausted)\\b");

    public boolean isReadOnly(final String toolName) {
        return readonly != null && (readonly.contains("*") || readonly.contains(toolName));
    }
}
