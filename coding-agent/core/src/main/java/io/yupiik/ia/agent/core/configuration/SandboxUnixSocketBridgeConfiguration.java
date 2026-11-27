/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;

@JsonModel
public record SandboxUnixSocketBridgeConfiguration(
        @Property(documentation = "Existing absolute canonical Unix stream socket path (no symlink aliases).")
        String path,

        @Property(documentation = "Sandbox loopback TCP port, 1024..65535 except the reserved proxy port 3128.")
        int port) {}
