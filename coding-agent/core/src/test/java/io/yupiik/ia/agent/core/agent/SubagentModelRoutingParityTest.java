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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.ModelVariantConfiguration;
import io.yupiik.ia.agent.core.configuration.SpawnSubagentToolConfiguration;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves the "preflight == execution" invariant for subagent model selection: the effective
 * model resolved once at spawn time (used by {@code SpawnSubagentTool} and {@code SubagentRunner}
 * identically) is what the policy gate approves AND what the run settings actually carry. Any
 * drift between the approved tuple and the routed reference would fail here.
 */
@ExtendWith(TestJsonMapper.class)
@FusionSupport(modules = SubagentModelRoutingParityTest.TestModule.class)
class SubagentModelRoutingParityTest {
    private JsonMapper mapper;

    private ProviderManager manager(final CodingAgentConfiguration cfg) {
        return new ProviderManager(cfg, mapper);
    }

    private ProviderManager configuredPm(final CodingAgentConfiguration cfg) {
        final var pm = manager(cfg);
        pm.setProvider("child");
        return pm;
    }

    @Test
    void gateApprovedModelIsWhatRunSettingsRouteTo(
            final JsonMapper mapper, @Fusion final CodingAgentConfiguration cfg) {
        this.mapper = mapper;
        final var pm = configuredPm(cfg);
        final var resolver = new AgentModelResolver(pm);
        final var policy = new SpawnSubagentToolConfiguration(List.of("reviewer"), List.of("child"), List.of());

        // an explicit $provider/model: the gate sees the routed reference and approves it
        final var effective = resolver.resolve(ChatSettings.EMPTY, null, "$child/child-model", null);
        assertTrue(SubagentModelPolicy.allows(policy, effective), "gate must approve a routed explicit model");
        // the approved reference and the settings the runner would send must agree
        assertEquals("$child/child-model", effective.routedReference());
        assertEquals("child", effective.settings().providerId());
        assertEquals("child-model", effective.settings().model());
    }

    @Test
    void profileModelRoutesOnTheProviderManagerAndPassesGate(
            final JsonMapper mapper, @Fusion final CodingAgentConfiguration cfg) {
        this.mapper = mapper;
        final var pm = configuredPm(cfg);
        final var resolver = new AgentModelResolver(pm);
        final var profile = new AgentConfiguration(
                "reviewer",
                "Review",
                null,
                new ModelVariantConfiguration("child-model", null, null, null, null, null, null),
                null);
        final var policy = new SpawnSubagentToolConfiguration(List.of("reviewer"), List.of("child"), List.of());

        final var effective = resolver.resolve(ChatSettings.EMPTY, profile, null, null);
        assertTrue(SubagentModelPolicy.allows(policy, effective), "a profile model on the selected provider passes");
        assertEquals("child", effective.settings().providerId());
        assertEquals("child-model", effective.settings().model());
    }

    @Test
    void bareDeclaredModelPinsToItsProviderNotTheProcessCurrent(
            final JsonMapper mapper, @Fusion final CodingAgentConfiguration cfg) {
        this.mapper = mapper;
        // process current = "default" (no setProvider): a bare "child-model" must still route to
        // the provider that declares it, not to the process current provider.
        final var pm = manager(cfg);
        final var resolver = new AgentModelResolver(pm);
        final var policy = new SpawnSubagentToolConfiguration(List.of(), List.of("child"), List.of());

        final var effective = resolver.resolve(ChatSettings.EMPTY, null, "child-model", null);
        assertEquals(
                "$child/child-model",
                effective.routedReference(),
                "the declaring provider must win over the process current");
        assertEquals("child", effective.settings().providerId());
        assertEquals("child-model", effective.settings().model());
        assertTrue(SubagentModelPolicy.allows(policy, effective));
    }

    @Test
    void undeclaredBareModelFallsBackToInheritedProviderNotProcessCurrent(
            final JsonMapper mapper, @Fusion final CodingAgentConfiguration cfg) {
        this.mapper = mapper;
        // the user-reported case: the session runs on "child" (codex), the process current is
        // "default", and the bare model is declared by no configured provider. It must follow the
        // spawning session's provider, not the process current.
        final var pm = manager(cfg);
        final var resolver = new AgentModelResolver(pm);
        final var policy = new SpawnSubagentToolConfiguration(List.of(), List.of("child"), List.of());
        final var inherited = ChatSettings.EMPTY.withProviderId("child").withModel("parent-model");

        final var effective = resolver.resolve(inherited, null, "gpt-6.1-sol", null);
        assertEquals(
                "child",
                effective.settings().providerId(),
                "an undeclared bare model must follow the spawning session's provider");
        assertEquals("gpt-6.1-sol", effective.settings().model());
        assertTrue(SubagentModelPolicy.allows(policy, effective));
    }

    @Test
    void policyRejectsWhenProviderNotAllowedDespiteAModelThatResolves(
            final JsonMapper mapper, @Fusion final CodingAgentConfiguration cfg) {
        this.mapper = mapper;
        final var pm = configuredPm(cfg);
        final var resolver = new AgentModelResolver(pm);
        // allowedProviders only lists "default", but the model routes to "child": the gate must reject.
        final var policy = new SpawnSubagentToolConfiguration(List.of(), List.of("default"), List.of());

        final var effective = resolver.resolve(ChatSettings.EMPTY, null, "$child/child-model", null);
        assertEquals("$child/child-model", effective.routedReference());
        assertFalse(SubagentModelPolicy.allows(policy, effective), "a provider outside the allowlist must be rejected");
    }

    @Test
    void inheritedModelIsKeptWhenNothingElseIsConfigured(
            final JsonMapper mapper, @Fusion final CodingAgentConfiguration cfg) {
        this.mapper = mapper;
        final var pm = configuredPm(cfg);
        final var resolver = new AgentModelResolver(pm);
        final var inherited = ChatSettings.EMPTY.withProviderId("child").withModel("child-model");

        final var effective = resolver.resolve(inherited, null, null, null);
        final var policy = new SpawnSubagentToolConfiguration(List.of(), List.of("child"), List.of());
        assertTrue(SubagentModelPolicy.allows(policy, effective), "the inherited selection remains usable");
        assertEquals("child-model", effective.settings().model());
        assertEquals("child", effective.settings().providerId());
    }

    public static class TestModule extends ConfigurationTestModule {
        public TestModule() {
            super(values());
        }

        private static Map<String, String> values() {
            final var entries = new HashMap<String, String>();
            entries.put("yca.provider.providers.length", "2");
            entries.put("yca.provider.providers.0.id", "default");
            entries.put("yca.provider.providers.0.model", "parent-model");
            entries.put("yca.provider.providers.0.models.length", "1");
            entries.put("yca.provider.providers.0.models.0.model", "parent-model");
            entries.put("yca.provider.providers.1.id", "child");
            entries.put("yca.provider.providers.1.model", "child-model");
            entries.put("yca.provider.providers.1.models.length", "1");
            entries.put("yca.provider.providers.1.models.0.model", "child-model");
            return entries;
        }
    }
}
