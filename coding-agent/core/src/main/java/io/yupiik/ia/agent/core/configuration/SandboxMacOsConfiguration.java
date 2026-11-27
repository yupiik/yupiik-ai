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
public record SandboxMacOsConfiguration(
        @Property(
                defaultValue = "false",
                documentation = "Opt into the experimental macOS Seatbelt command launcher. Not runtime-qualified or"
                        + " equivalent to the Linux boundary: lexical aliases and process-tree revocation have"
                        + " weaker guarantees. Requires /usr/bin/sandbox-exec and an empty network.allowedDomains"
                        + " list; network access remains denied. Failures never retry on the host.")
        boolean experimental) {}
