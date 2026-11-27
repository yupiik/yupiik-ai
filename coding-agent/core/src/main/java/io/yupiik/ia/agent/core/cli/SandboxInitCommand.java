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
package io.yupiik.ia.agent.core.cli;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.ia.agent.core.configuration.SandboxConfigurationInitializer;
import io.yupiik.ia.agent.core.wiring.LazyJsonConfigurationSource;
import java.io.IOException;
import java.io.UncheckedIOException;

@DefaultScoped
@Command(
        name = {"sandbox", "init"},
        description =
                "Preview safe global sandbox defaults; --sandbox-init-apply true backs up and atomically updates the global JSON configuration. Existing conflicting choices are never overwritten. All agent modes accept --sandbox true|false (yca.sandbox.enabled), independently of tool permissions.")
public class SandboxInitCommand implements Runnable {
    private final Options options;
    private final Configuration configuration;
    private final SandboxConfigurationInitializer initializer;

    public SandboxInitCommand(
            final Options options,
            final Configuration configuration,
            final SandboxConfigurationInitializer initializer) {
        this.options = options;
        this.configuration = configuration;
        this.initializer = initializer;
    }

    @RootConfiguration("sandbox-init")
    public record Options(
            @Property(
                    defaultValue = "false",
                    documentation =
                            "Apply the previewed defaults to the trusted global file (yca.configuration.json, otherwise yca.storageHome/configuration.json). Default false is a read-only preview. Backs up an existing file, preserves unknown JSON and refuses conflicts or unsafe paths.")
            boolean apply) {}

    @Override
    public void run() {
        try {
            final var result = initializer.initialize(
                    LazyJsonConfigurationSource.resolveGlobalConfigurationFile(configuration), options.apply());
            if (!result.changed()) {
                System.out.println("Sandbox defaults already initialized: " + result.path());
            } else if (!options.apply()) {
                System.out.println("Preview only; no files changed. Global configuration: " + result.path());
                System.out.println("Sandbox defaults: " + result.defaultsJson());
                System.out.println("Apply with: yca sandbox init --sandbox-init-apply true");
            } else {
                if (result.backup() != null) {
                    System.out.println("Backup: " + result.backup());
                }
                System.out.println("Initialized sandbox defaults: " + result.path());
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot initialize sandbox configuration: " + e.getMessage(), e);
        }
    }
}
