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
public record BuiltinToolsConfiguration(
        @Property(
                defaultValue = "true",
                documentation =
                        "Register the embedded local tool server (bash, read, write_file, edit_file, glob, grep, write_todo, ask_user, memory_write, webfetch, websearch, apply_patch, skill, ...). Set to false to expose tools only through configured MCP servers.")
        boolean enabled,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Allow-list of built-in tool names exposed to the model (exact names, eg. read, edit_file, glob, grep, bash, write_file, apply_patch). Empty keeps every enabled tool. A lean set shrinks the wire tool schemas that ride every request - the main per-call token lever after the system prompt (see features/provider-tuning).")
        List<String> enabledTools,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Deny-list of built-in tool names hidden from the model (exact names). Takes precedence over enabledTools; empty keeps all enabled tools.")
        List<String> disabledTools) {

    public boolean keeps(final String name) {
        if (disabledTools != null && disabledTools.contains(name)) {
            return false;
        }
        return enabledTools == null || enabledTools.isEmpty() || enabledTools.contains(name);
    }
}
