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
public record SandboxFilesystemConfiguration(
        @Property(
                defaultValue = "java.util.List.of(\"${projectdir}\", \"${workdir}\", \"${tempdir}\")",
                documentation =
                        "Readable paths. Defaults to the project, working directory and temporary directory; denyRead"
                                + " always takes precedence.")
        List<String> allowRead,

        @Property(
                defaultValue = "java.util.List.of(\"${projectdir}\", \"${workdir}\", \"${tempdir}\")",
                documentation =
                        "Writable paths. Defaults to the project, working directory and temporary directory; denyWrite"
                                + " always takes precedence.")
        List<String> allowWrite,

        @Property(
                defaultValue = "java.util.List.of(\"~/.ssh\", \"~/.aws\", \"~/.gnupg\", \"glob:${projectdir}/.env*\","
                        + " \"glob:${projectdir}/**/.env*\", \"glob:~/.m2/settings*.xml\","
                        + " \"~/.yupiik/coding-agent\", \"~/.bashrc\")",
                documentation =
                        "Paths forbidden for reading, including credential directories and root/nested project .env"
                                + " files.")
        List<String> denyRead,

        @Property(
                defaultValue = "java.util.List.of(\"~/.ssh\", \"~/.aws\", \"~/.gnupg\", \"~/.yupiik\", \"~/.config\","
                        + " \"${projectdir}/.yca\", \"${workdir}/.yca\", \"${projectdir}/.git\","
                        + " \"${workdir}/.git\", \"${projectdir}/AGENTS.md\", \"glob:${projectdir}/**/AGENTS.md\","
                        + " \"${workdir}/AGENTS.md\", \"glob:~/.m2/settings*.xml\", \"~/.yupiik/coding-agent\","
                        + " \"~/.bashrc\")",
                documentation = "Paths forbidden for writing, protecting credentials, global configuration, project"
                        + " configuration, git control data and agent instructions.")
        List<String> denyWrite) {}
