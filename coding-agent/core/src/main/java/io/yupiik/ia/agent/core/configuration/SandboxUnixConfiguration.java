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

@JsonModel
public record SandboxUnixConfiguration(
        @Property(
                defaultValue = "java.util.List.of()",
                documentation = "Explicit Unix stream socket to loopback TCP bridges (Linux only, maximum 32). No"
                        + " environment variables are injected. Peers are trusted capabilities outside"
                        + " sandbox policy.")
        List<SandboxUnixSocketBridgeConfiguration> socketBridges) {
    public SandboxUnixConfiguration {
        socketBridges = List.copyOf(socketBridges);
    }
}
