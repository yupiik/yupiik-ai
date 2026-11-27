/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.cli.internal.CliCommand;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class WebCommandSandboxTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"false", "true", "typo"})
    void bindsNullableSandboxAndExposesItInCommandHelp(final String sandbox) {
        final Map<String, String> values = sandbox == null ? Map.of() : Map.of("sandbox", sandbox);
        try (final var container = ConfiguringContainer.of()
                        .register(new WebConfigurationTestModule(values))
                        .start();
                final var conf = container.lookup(WebCommand.WebConf.class);
                final var commands = container.lookups(CliCommand.class)) {
            final Boolean expected = sandbox == null ? null : Boolean.parseBoolean(sandbox);
            assertEquals(expected, conf.instance().sandbox());
            final var command = commands.instance().stream()
                    .<CliCommand<?>>map(it -> it)
                    .filter(it -> List.of(it.path()).equals(List.of("web")))
                    .findFirst()
                    .orElseThrow();
            final var option = command.parameters().stream()
                    .filter(it -> "--sandbox".equals(it.cliName()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(option.description().contains("yca.sandbox.enabled"));
            assertTrue(option.description().contains("independent of tool permissions"));
        }
    }

    @Test
    void legacyConstructorLeavesTheGlobalSandboxSettingInCharge() {
        assertNull(new WebCommand.WebConf("127.0.0.1", 0, false, true, false, List.of(), 1024).sandbox());
    }
}
