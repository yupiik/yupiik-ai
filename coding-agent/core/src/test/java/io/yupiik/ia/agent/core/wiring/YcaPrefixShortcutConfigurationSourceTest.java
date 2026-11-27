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
package io.yupiik.ia.agent.core.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class YcaPrefixShortcutConfigurationSourceTest {
    @Test
    void dottedStripped() {
        final var source = new YcaPrefixShortcutConfigurationSource(
                new ArgsConfigSource(List.of("--todos.continueOnUnfinished", "true")));
        assertEquals("true", source.get("yca.todos.continueOnUnfinished"));
    }

    @Test
    void dashedStripped() {
        final var source = new YcaPrefixShortcutConfigurationSource(
                new ArgsConfigSource(List.of("--todos-continueOnUnfinished", "true")));
        assertEquals("true", source.get("yca.todos.continueOnUnfinished"));
    }

    @Test
    void dashedWithEquals() {
        final var source = new YcaPrefixShortcutConfigurationSource(
                new ArgsConfigSource(List.of("--todos-continueOnUnfinished=true")));
        assertEquals("true", source.get("yca.todos.continueOnUnfinished"));
    }

    @Test
    void prefixedDotted() {
        final var source = new YcaPrefixShortcutConfigurationSource(
                new ArgsConfigSource(List.of("--yca.todos.continueOnUnfinished", "true")));
        assertEquals("true", source.get("yca.todos.continueOnUnfinished"));
    }

    @Test
    void prefixedDashed() {
        final var source = new YcaPrefixShortcutConfigurationSource(
                new ArgsConfigSource(List.of("--yca-todos-continueOnUnfinished", "true")));
        assertEquals("true", source.get("yca.todos.continueOnUnfinished"));
    }

    @Test
    void missingKeyReturnsNull() {
        final var source = new YcaPrefixShortcutConfigurationSource(
                new ArgsConfigSource(List.of("--yca.todos.continueOnUnfinished", "true")));
        assertNull(source.get("yca.todos.maxContinues"));
    }

    @Test
    void prefixedWinsWhenBoth() {
        final var source = new YcaPrefixShortcutConfigurationSource(new ArgsConfigSource(
                List.of("--todos-continueOnUnfinished", "stripped", "--yca.todos.continueOnUnfinished", "prefixed")));
        assertEquals("stripped", source.get("yca.todos.continueOnUnfinished"));
    }

    @Test
    void commandSandboxDoesNotOverrideGlobalPolicy() {
        final var source = new YcaPrefixShortcutConfigurationSource(
                new ArgsConfigSource(List.of("--sandbox=false", "--yca.sandbox.enabled", "true")));
        assertEquals("true", source.get("yca.sandbox.enabled"));
        assertEquals("false", source.get("sandbox"));
    }

    @Test
    void commandSandboxValuesAreNotValidatedByTheShortcutSource() {
        final var source = new YcaPrefixShortcutConfigurationSource(new ArgsConfigSource(List.of("--sandbox", "no")));
        assertNull(source.get("yca.sandbox.enabled"));
        assertEquals("no", source.get("sandbox"));
    }

    @Test
    void nonYcaKeysAreDelegated() {
        final var source = new YcaPrefixShortcutConfigurationSource(new ArgsConfigSource(List.of("--name", "test")));
        assertEquals("test", source.get("name"));
    }
}
