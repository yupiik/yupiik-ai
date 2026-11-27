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
package io.yupiik.ia.agent.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ModelVariantConfiguration;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import org.junit.jupiter.api.Test;

class EffectiveAgentModelTest {
    @Test
    void resolvesExplicitThenProfileThenProviderDefaultThenInherited() {
        final var profile = new AgentConfiguration(
                "reviewer",
                "Review",
                null,
                new ModelVariantConfiguration("profile-model", null, null, null, null, null, null),
                null);
        final var inherited = ChatSettings.EMPTY.withProviderId("parent").withModel("inherited");
        final var resolver = new AgentModelResolver(null);

        assertEquals(
                "explicit",
                resolver.resolve(inherited, profile, " explicit ", "provider-default")
                        .reference());
        assertEquals(
                "profile-model",
                resolver.resolve(inherited, profile, null, "provider-default").reference());
        assertEquals(
                "provider-default",
                resolver.resolve(inherited, null, null, "provider-default").reference());
        assertEquals("inherited", resolver.resolve(inherited, null, null, null).reference());
    }

    @Test
    void anUnsetProviderDefaultFallsThroughToTheInheritedModel() {
        final var inherited = ChatSettings.EMPTY.withProviderId("parent").withModel("inherited");
        final var resolver = new AgentModelResolver(null);
        // "default" is the implicit model-variant placeholder, not a real model: it must not win.
        assertEquals(
                "inherited", resolver.resolve(inherited, null, null, "default").reference());
    }
}
