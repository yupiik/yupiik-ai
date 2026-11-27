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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.NativeProviderConfiguration;
import io.yupiik.ia.agent.core.wiring.HttpClientProducer;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class ChatClientCompressionBoundaryTest {
    private final List<ExtendedHttpClient> httpClients = new ArrayList<>();

    @AfterEach
    void stop() {
        httpClients.forEach(ExtendedHttpClient::close);
    }

    @TempDir
    static Path storageHome;

    @Nested
    @FusionSupport(modules = AutoUsesBoundaryPairsForAllVariantsAndModelsModule.class)
    class AutoUsesBoundaryPairsForAllVariantsAndModels {
        @Test
        void autoUsesBoundaryPairsForAllVariantsAndModels(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) throws Exception {
            try (final var client = client(mapper, configuration)) {
                assertTrue(client.compressionBoundaryOnly(request(null, ChatSettings.EMPTY)));
                assertTrue(client.compressionBoundaryOnly(request(null, ChatSettings.EMPTY.withVariant("max"))));
                assertTrue(client.compressionBoundaryOnly(request(null, ChatSettings.EMPTY.withVariant("low"))));
                assertTrue(client.compressionBoundaryOnly(request(null, ChatSettings.EMPTY.withVariant("missing"))));
                assertTrue(
                        client.compressionBoundaryOnly(request("other-model", ChatSettings.EMPTY.withVariant("high"))));
                assertTrue(client.compressionBoundaryOnly(request("other-model", ChatSettings.EMPTY)));
            }
        }
    }

    @Nested
    @FusionSupport(modules = RoutingAndSelectionsStayWithinEachRequestModule.class)
    class RoutingAndSelectionsStayWithinEachRequest {
        @Test
        void routingAndSelectionsStayWithinEachRequest(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) throws Exception {
            try (final var client = client(mapper, configuration)) {
                final var first = request(null, ChatSettings.EMPTY);
                final var second = request(null, new ChatSettings("second", null, null, null));
                final var routed = request("$second/second-model", ChatSettings.EMPTY);
                final var explicit =
                        request("$first/deepseek-v4-flash", new ChatSettings("second", "second-model", null, null));
                assertTrue(client.compressionBoundaryOnly(first));
                assertFalse(client.compressionBoundaryOnly(second));
                assertFalse(client.compressionBoundaryOnly(routed));
                assertTrue(client.compressionBoundaryOnly(explicit));
                assertTrue(client.compressionBoundaryOnly(first));
                assertFalse(client.compressionBoundaryOnly(second));
                assertFalse(client.compressionBoundaryOnly(
                        request(null, routed.settings().withModel("$second/second-model"))));
            }
        }
    }

    @Nested
    @FusionSupport(modules = ModelOverridesProviderAndAutoInheritsModule.class)
    class ModelOverridesProviderAndAutoInherits {
        @Test
        void modelOverridesProviderAndAutoInherits(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) throws Exception {
            try (final var client = client(mapper, configuration)) {
                assertFalse(client.compressionBoundaryOnly(request("disabled-model", ChatSettings.EMPTY)));
                assertTrue(client.compressionBoundaryOnly(request("auto-model", ChatSettings.EMPTY)));
                assertTrue(client.compressionBoundaryOnly(request("enabled-model", ChatSettings.EMPTY)));
                assertTrue(client.compressionBoundaryOnly(request("unmatched-model", ChatSettings.EMPTY)));
                assertFalse(client.compressionBoundaryOnly(
                        request("disabled-model", new ChatSettings(null, "enabled-model", null, null))));
            }
        }
    }

    @Nested
    @FusionSupport(modules = EnabledModelOverridesDisabledProviderModule.class)
    class EnabledModelOverridesDisabledProvider {
        @Test
        void enabledModelOverridesDisabledProvider(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) throws Exception {
            try (final var client = client(mapper, configuration)) {
                assertTrue(client.compressionBoundaryOnly(request("enabled-model", ChatSettings.EMPTY)));
                assertFalse(client.compressionBoundaryOnly(request("other-model", ChatSettings.EMPTY)));
            }
        }
    }

    private ChatClient client(final JsonMapper mapper, final CodingAgentConfiguration config) {
        final var http = new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null));
        httpClients.add(http);
        return new ChatClient(config, mapper, new ProviderManager(config, mapper), Clock.systemUTC(), http);
    }

    private ChatCompletionRequest request(final String model, final ChatSettings settings) {
        return new ChatCompletionRequest(
                        model, List.of(Message.user("hello")), null, null, null, null, null, null, null, null)
                .withSettings(settings);
    }

    public static class EnabledModelOverridesDisabledProviderModule extends ConfigurationTestModule {
        public EnabledModelOverridesDisabledProviderModule() {
            super(values());
        }

        private static Map<String, String> values() {
            final var values = new HashMap<String, String>();
            values.put("yca.storageHome", storageHome.toString());
            values.put("yca.provider.providers.length", "1");
            values.put("yca.provider.providers.0.id", "first");
            values.put("yca.provider.providers.0.model", "deepseek-v4-flash");
            values.putAll(Map.ofEntries(
                    Map.entry("yca.provider.providers.0.tuning.compressionBoundaryOnly", "DISABLED"),
                    Map.entry("yca.provider.providers.0.models.length", "1"),
                    Map.entry("yca.provider.providers.0.models.0.model", "enabled-model"),
                    Map.entry("yca.provider.providers.0.models.0.behavior.compressionBoundaryOnly", "ENABLED")));
            return values;
        }
    }

    public static class ModelOverridesProviderAndAutoInheritsModule extends ConfigurationTestModule {
        public ModelOverridesProviderAndAutoInheritsModule() {
            super(values());
        }

        private static Map<String, String> values() {
            final var values = new HashMap<String, String>();
            values.put("yca.storageHome", storageHome.toString());
            values.put("yca.provider.providers.length", "1");
            values.put("yca.provider.providers.0.id", "first");
            values.put("yca.provider.providers.0.model", "deepseek-v4-flash");
            values.putAll(Map.ofEntries(
                    Map.entry("yca.provider.providers.0.tuning.compressionBoundaryOnly", "ENABLED"),
                    Map.entry("yca.provider.providers.0.models.length", "3"),
                    Map.entry("yca.provider.providers.0.models.0.model", "disabled-model"),
                    Map.entry("yca.provider.providers.0.models.0.behavior.compressionBoundaryOnly", "DISABLED"),
                    Map.entry("yca.provider.providers.0.models.1.model", "auto-model"),
                    Map.entry("yca.provider.providers.0.models.1.behavior.compressionBoundaryOnly", "AUTO"),
                    Map.entry("yca.provider.providers.0.models.2.model", "enabled-model"),
                    Map.entry("yca.provider.providers.0.models.2.behavior.compressionBoundaryOnly", "ENABLED")));
            return values;
        }
    }

    public static class RoutingAndSelectionsStayWithinEachRequestModule extends ConfigurationTestModule {
        public RoutingAndSelectionsStayWithinEachRequestModule() {
            super(values());
        }

        private static Map<String, String> values() {
            final var values = new HashMap<String, String>();
            values.put("yca.storageHome", storageHome.toString());
            values.put("yca.provider.providers.length", "1");
            values.put("yca.provider.providers.0.id", "first");
            values.put("yca.provider.providers.0.model", "deepseek-v4-flash");
            values.putAll(Map.ofEntries(
                    Map.entry("yca.provider.providers.length", "2"),
                    Map.entry("yca.provider.providers.0.tuning.compressionBoundaryOnly", "ENABLED"),
                    Map.entry("yca.provider.providers.1.id", "second"),
                    Map.entry("yca.provider.providers.1.model", "second-model"),
                    Map.entry("yca.provider.providers.1.tuning.compressionBoundaryOnly", "DISABLED")));
            return values;
        }
    }

    public static class AutoUsesBoundaryPairsForAllVariantsAndModelsModule extends ConfigurationTestModule {
        public AutoUsesBoundaryPairsForAllVariantsAndModelsModule() {
            super(values());
        }

        private static Map<String, String> values() {
            final var values = new HashMap<String, String>();
            values.put("yca.storageHome", storageHome.toString());
            values.put("yca.provider.providers.length", "1");
            values.put("yca.provider.providers.0.id", "first");
            values.put("yca.provider.providers.0.model", "deepseek-v4-flash");
            values.putAll(Map.ofEntries(
                    Map.entry("yca.provider.providers.0.tuning.variants.length", "3"),
                    Map.entry("yca.provider.providers.0.tuning.variants.0.name", "high"),
                    Map.entry("yca.provider.providers.0.tuning.variants.1.name", "max"),
                    Map.entry("yca.provider.providers.0.tuning.variants.2.name", "low"),
                    Map.entry("yca.provider.providers.0.tuning.defaultVariant", "high")));
            return values;
        }
    }
}
