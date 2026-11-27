/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.List;
import java.util.Map;

@JsonModel
public record SandboxEnvironmentConfiguration(
        @Property(
                defaultValue = "java.util.List.of()",
                documentation = "Exact ambient variable names to inherit when present. Launcher-owned variables are"
                        + " forbidden.")
        List<String> inherit,

        @Property(
                defaultValue = "java.util.Map.of()",
                documentation = "Literal command environment values, overriding explicit inheritance. No"
                        + " interpolation; launcher-owned variables are forbidden.")
        Map<String, String> set) {
    public SandboxEnvironmentConfiguration {
        inherit = List.copyOf(inherit);
        set = Map.copyOf(set);
    }
}
