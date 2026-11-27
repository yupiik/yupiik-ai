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
public record SandboxConfiguration(
        @Property(
                defaultValue = "true",
                documentation = "Enable OS sandbox enforcement independently of ordinary tool permissions. full_yolo"
                        + " explicitly bypasses this boundary and is not recommended. Trusted settings"
                        + " only; --sandbox true|false works in all modes. See"
                        + " link:/yupiik-ai/coding-agent/sandboxing/overview.html[Sandboxing] and"
                        + " link:/yupiik-ai/coding-agent/sandboxing/helper.html[Linux sandbox helper] for"
                        + " backend requirements, helper packaging and current limitations.")
        boolean enabled,

        @Property(documentation = "Native sandbox helper configuration.")
        SandboxHelperConfiguration helper,

        @Property(
                documentation = "Filesystem sandbox policy. Denies take precedence over allows; paths accept ~/ and"
                        + " ${projectdir}, ${workdir}, ${tempdir} placeholders. Only paths prefixed with"
                        + " glob: are glob patterns. Linux uses Bubblewrap-only snapshots: each command"
                        + " expands globs against the current nofollow filesystem and masks existing deny"
                        + " matches or mounts write-deny matches read-only. Missing literal denies do not"
                        + " mask an ancestor. Future matching paths and absent targets are not dynamically"
                        + " protected until the next command snapshot. Existing literal allow directories"
                        + " generally permit future children; glob allows grant only existing matching"
                        + " roots, never broad static prefixes. Unsupported canonical/symlink roots fail"
                        + " closed. Java path checks retain TOCTOU limitations. See"
                        + " link:/yupiik-ai/coding-agent/features/sandboxes.html[Sandbox limitations].")
        SandboxFilesystemConfiguration filesystem,

        @Property(
                documentation = "Network sandbox policy. Destinations not explicitly allowed, and"
                        + " private/loopback/link-local destinations even when allowlisted, must be"
                        + " rejected.")
        SandboxNetworkConfiguration network,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation = "Additional trusted integration exceptions while sandboxed. Each list entry is a"
                        + " complete literal integration ID, for example acp:fs.readTextFile,"
                        + " acp:terminal, or java:com.example.MyTool; the colon is part of the literal ID,"
                        + " not property syntax. Configured and client-supplied MCP servers are trusted by"
                        + " default and need no entry. Exact matching only, no wildcards. Trusted"
                        + " global/CLI/environment/system configuration only, never project configuration."
                        + " Trust is NOT filesystem, process or network confinement; normal tool"
                        + " permissions still apply. Review server configuration and name reuse.")
        List<String> trustedIntegrations,

        @Property(documentation = "macOS sandbox configuration.")
        SandboxMacOsConfiguration macOs,

        @Property(
                documentation =
                        "Trusted command-only environment policy. No ambient variables are inherited by" + " default.")
        SandboxEnvironmentConfiguration environment,

        @Property(documentation = "Trusted Linux Unix socket capabilities, outside filesystem/network confinement.")
        SandboxUnixConfiguration unix) {
    /** Applies a launch-time override without changing the trusted sandbox policy. */
    public SandboxConfiguration withEnabled(final Boolean override) {
        if (override == null || override == enabled) {
            return this;
        }
        return new SandboxConfiguration(
                override, helper, filesystem, network, trustedIntegrations, macOs, environment, unix);
    }
}
