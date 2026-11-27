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

/**
 * YCA configuration file secret harvesting: the {@code configuration.json} files (global and
 * project-local) whose {@code apiKey} and secret HTTP header values are redacted on the wire.
 */
@JsonModel
public record YcaConfigSecretConfiguration(
        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Explicit paths to the YCA configuration.json files to scan for secrets (apiKey values and "
                                + "secret HTTP header values). '~' expands to the user home and a relative path is "
                                + "resolved against the working directory. Empty (the default) automatically scans the "
                                + "project-local '<workingDir>/.yca/configuration.json' (if present) and the global "
                                + "'<home>/.yupiik/coding-agent/configuration.json' (or the yca.configuration.json / "
                                + "storage-home override). Note the global file is harvested even when a project-local "
                                + "file without '$inherit' would otherwise shadow it in the resolved configuration.")
        List<String> locations) {}
