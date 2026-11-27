package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

/** Trusted pre-load settings, deliberately not part of the project JSON schema. */
@RootConfiguration("yca")
public record BootstrapConfiguration(
        @Property(
                value = "configuration.json",
                documentation =
                        "Global JSON configuration file. When unset, resolves configuration.json under yca.storage.home, or ~/.yupiik/coding-agent. Trusted CLI/environment/system sources only.")
        String configurationFile,

        @Property(
                value = "configuration.project.enabled",
                defaultValue = "true",
                documentation =
                        "Load project-local .yca/configuration.json. Set false in trusted CLI/environment/system configuration to ignore project-controlled configuration.")
        boolean projectEnabled,

        @Property(
                value = "storage.home",
                documentation =
                        "Bootstrap storage directory used to locate the global configuration file; supports a leading ~/. Defaults to ~/.yupiik/coding-agent. Trusted CLI/environment/system sources only; distinct from the typed yca.storageHome setting.")
        String storageHome) {}
