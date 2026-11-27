/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;

@JsonModel
public record SandboxHelperConfiguration(
        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Absolute trusted native sandbox-helper executable. Empty uses the embedded JVM helper for"
                                + " Java YCA, or a sibling sandbox-helper binary for native YCA. See"
                                + " link:/yupiik-ai/coding-agent/sandboxing/helper.html[Sandbox helper].")
        String executable) {}
