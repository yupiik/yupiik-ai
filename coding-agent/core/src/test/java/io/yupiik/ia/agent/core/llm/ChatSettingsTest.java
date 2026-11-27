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
package io.yupiik.ia.agent.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class ChatSettingsTest {

    @Test
    void concreteDefaultProviderRouteRemainsPinnedWhenSettingsAreRebased() {
        final var pinned = new ChatSettings("default", "$default/model", "balanced", null);
        assertEquals("default", pinned.providerId());
        assertEquals(
                "default",
                pinned.inheritFrom(ChatSettings.EMPTY.withProviderId("external"))
                        .providerId());
        assertEquals("default", pinned.withVariant("high").providerId());
        assertEquals("default", pinned.withoutModelOverrides().providerId());
    }

    @Test
    void providerOverrideLifecycle() {
        final var settings = ChatSettings.EMPTY;
        assertNull(settings.providerId(), "no override by default (process default applies)");

        assertEquals("ollama", settings.withProviderId("ollama").providerId());
        // "default" and blank clear the override so the session falls back to the process default
        assertNull(settings.withProviderId("default").providerId());
        assertNull(settings.withProviderId("  ").providerId());
    }

    @Test
    void withoutModelOverridesKeepsTheProvider() {
        final var settings = ChatSettings.EMPTY
                .withProviderId("ollama")
                .withModel("qwen3-coder")
                .withVariant("balanced");

        final var cleared = settings.withoutModelOverrides();

        assertEquals("ollama", cleared.providerId(), "a provider switch must survive the model reset");
        assertNull(cleared.model());
        assertNull(cleared.variant());
    }

    @Test
    void withModelDropsSamplingOverrides() {
        final var settings = ChatSettings.EMPTY.withModel("qwen3-coder");
        assertEquals("qwen3-coder", settings.model());
    }

    @Test
    void immutableValuesAreNeverMutatedInPlace() {
        final var settings = ChatSettings.EMPTY.withProviderId("ollama");
        final var copy = settings.withModel("qwen3-coder");
        assertEquals("ollama", settings.providerId());
        assertNull(settings.model(), "withModel returns a new value, it must not mutate the original");
        assertEquals("qwen3-coder", copy.model());
    }

    @Test
    void inheritFromMergesTheParentTabSelection() {
        final var parent = ChatSettings.EMPTY
                .withProviderId("ollama")
                .withModel("qwen3-coder")
                .withVariant("balanced");

        final var child = ChatSettings.EMPTY.inheritFrom(parent);

        assertEquals("ollama", child.providerId());
        assertEquals("qwen3-coder", child.model());
        assertEquals("balanced", child.variant());

        assertSame(ChatSettings.EMPTY, ChatSettings.EMPTY.inheritFrom(null), "null parent is a no-op");
    }
}
