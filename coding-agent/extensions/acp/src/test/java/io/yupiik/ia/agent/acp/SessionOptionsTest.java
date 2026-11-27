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
package io.yupiik.ia.agent.acp;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ai.acp.protocol.*;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatClient.SamplingField;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.ModelsResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionOptionsTest {

    @TempDir
    private Path workdir;

    private ChatClient.SamplingOverride sampling() {
        return new ChatClient.SamplingOverride(Map.of(SamplingField.TEMPERATURE, Optional.of(0.25)));
    }

    @Nested
    @FusionSupport(modules = UnconfiguredModelModule.class)
    class UnconfiguredModel {

        @Test
        void unconfiguredModelStillHasAnHonestSelectableCurrentValue(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final AcpCommand.Conf commandOptions) {
            try (final var mapper = CodecsTest.acpMapper();
                    final var support = new OptionTestSupport(configuration, commandOptions, mapper, settings -> {
                        fail("Descriptors must remain offline");
                        return null;
                    })) {
                final var runtime = support.runtime(new ChatSettings("a", null, null, null), workdir);
                final var model = support.options.describe(runtime, List.of()).get(1);
                assertEquals("", model.currentValue());
                assertTrue(
                        model.options().stream().anyMatch(value -> value.value().equals(model.currentValue())));
            }
        }
    }

    public static class UnconfiguredModelModule extends OptionConfigurationTestModule {
        public UnconfiguredModelModule() {
            super(Map.of("yca.provider.providers.0.model", ""));
        }
    }

    @Nested
    @FusionSupport(modules = OptionConfigurationTestModule.class)
    class Defaults {
        @Fusion
        private CodingAgentConfiguration configuration;

        @Fusion
        private AcpCommand.Conf commandOptions;

        @Test
        void descriptorsAreOfflineTypedOrderedAndOnlyContainKnownModels() {
            try (final var mapper = CodecsTest.acpMapper();
                    final var support = support(mapper)) {
                final var runtime = support.runtime(new ChatSettings("a", "private-model", "low", null), workdir);
                final var options = support.options.describe(runtime, List.of("a-main", "new-model", "new-model"));
                assertEquals(
                        List.of("provider", "model", "variant", "mode"),
                        options.stream().map(ConfigOption::id).toList());
                assertEquals(
                        List.of("_provider", "model", "model_config", "mode"),
                        options.stream().map(ConfigOption::category).toList());
                assertTrue(options.stream()
                        .allMatch(it -> "select".equals(it.type())
                                && it.options().stream()
                                        .anyMatch(value -> value.value().equals(it.currentValue()))));
                assertEquals(
                        List.of("a-main", "private-model", "new-model"),
                        options.get(1).options().stream()
                                .map(ConfigValue::value)
                                .toList());
                assertEquals("low", options.get(2).currentValue());
                final var created = new Created("session", new Modes("plan", List.of()), options);
                assertEquals(created, mapper.fromString(Created.class, mapper.toString(created)));
                final var update = new ConfigUpdate("config_option_update", options);
                assertEquals(update, mapper.fromString(ConfigUpdate.class, mapper.toString(update)));
            }
        }

        @Test
        void descriptorSnapshotDoesNotRereadChangedRuntimeState() {
            try (final var mapper = CodecsTest.acpMapper();
                    final var support = support(mapper)) {
                final var runtime = support.runtime(new ChatSettings("a", "private-model", "low", null), workdir);
                final var settings = runtime.chatSettings();
                final var mode = support.options.mode(runtime);
                runtime.updateChatSettings(new ChatSettings("default", "b-main", "high", null));
                runtime.permissions().setMode(AgentMode.BUILD);
                final var descriptors = support.options.describe(settings, mode, List.of());
                assertEquals(
                        List.of("a", "private-model", "low", "plan"),
                        descriptors.stream().map(ConfigOption::currentValue).toList());
                assertEquals("default", runtime.chatSettings().providerId());
            }
        }

        @Test
        void realChangesResetOnlyTheirDependentOverridesAndDefaultRestoresConfiguredPreset() {
            try (final var mapper = CodecsTest.acpMapper();
                    final var support = support(mapper)) {
                final var runtime = support.runtime(new ChatSettings("a", "a-main", "low", sampling()), workdir);
                support.options.apply(
                        "s", runtime, support.options.describe(runtime, List.of("another")), "model", "another");
                assertEquals(new ChatSettings("a", "another", null, null), runtime.chatSettings());
                support.options.apply("s", runtime, support.options.describe(runtime, List.of()), "variant", "low");
                assertEquals("low", runtime.chatSettings().variant());
                runtime.updateChatSettings(runtime.chatSettings().withSampling(sampling()));
                support.options.apply("s", runtime, support.options.describe(runtime, List.of()), "variant", "default");
                assertEquals(new ChatSettings("a", "another", null, null), runtime.chatSettings());
                assertEquals("high", support.chat.resolvedVariant(runtime.chatSettings()));
                support.options.apply(
                        "s", runtime, support.options.describe(runtime, List.of()), "provider", "default");
                assertEquals(new ChatSettings("default", null, null, null), runtime.chatSettings());
                assertEquals("b-main", support.chat.model(runtime.chatSettings()));
                assertEquals("a", support.providers.currentProviderId());
            }
        }

        @Test
        void noOpDetectionUsesActualStateWhenAnAgentChangePrecedesItsNotification() {
            try (final var mapper = CodecsTest.acpMapper();
                    final var support = support(mapper)) {
                final var runtime = support.runtime(new ChatSettings("a", null, null, null), workdir);
                final var advertised = support.options.describe(runtime, List.of());
                runtime.updateChatSettings(new ChatSettings("default", "b-main", "high", sampling()));
                support.options.apply("s", runtime, advertised, "provider", "a");
                assertEquals(new ChatSettings("a", null, null, null), runtime.chatSettings());
            }
        }

        @Test
        void selectingCurrentValuesPreservesAllOverrides() {
            final var initial = new ChatSettings("a", "private-model", "low", sampling());
            try (final var mapper = CodecsTest.acpMapper();
                    final var support = support(mapper)) {
                final var runtime = support.runtime(initial, workdir);
                for (final var option : support.options.describe(runtime, List.of())) {
                    support.options.apply(
                            "s",
                            runtime,
                            support.options.describe(runtime, List.of()),
                            option.id(),
                            option.currentValue());
                    assertEquals(initial, runtime.chatSettings());
                }
            }
        }

        @Test
        void variantChangeDropsSamplingAndDoesNotAffectOtherSessionsOrBackgroundModels() {
            try (final var mapper = CodecsTest.acpMapper();
                    final var support = support(mapper)) {
                final var first = support.runtime(new ChatSettings("a", "private-model", "low", sampling()), workdir);
                final var second = support.runtime(new ChatSettings("default", null, null, null), workdir);
                final var secondSettings = second.chatSettings();
                final var before = support.configuration.model();
                support.options.apply("s", first, support.options.describe(first, List.of()), "variant", "high");
                assertEquals(new ChatSettings("a", "private-model", "high", null), first.chatSettings());
                assertSame(secondSettings, second.chatSettings());
                assertSame(before, support.configuration.model());
                assertEquals("$a/compact-only", before.compact().name());
                assertEquals("a", support.providers.currentProviderId());
            }
        }

        @Test
        void invalidTypesIdsAndUnadvertisedValuesCannotMutateSelection() {
            final var initial = new ChatSettings("a", null, null, null);
            try (final var mapper = CodecsTest.acpMapper();
                    final var support = support(mapper)) {
                final var runtime = support.runtime(initial, workdir);
                final var advertised = support.options.describe(runtime, List.of());
                for (final var value : List.of(true, 42, Map.of("name", "low"), List.of("low"))) {
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> support.options.apply("s", runtime, advertised, "variant", value));
                }
                assertThrows(
                        IllegalArgumentException.class,
                        () -> support.options.apply("s", runtime, advertised, "variant", null));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> support.options.apply("s", runtime, advertised, "missing", "low"));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> support.options.apply("s", runtime, advertised, "model", "matching-prefix"));
                assertEquals(initial, runtime.chatSettings());
            }
        }

        @Test
        void vetoedRuntimeUpdateIsReflectedRatherThanOptimisticallyReportingRequestedValue() {
            try (final var mapper = CodecsTest.acpMapper();
                    final var support = support(mapper)) {
                final var runtime =
                        support.runtime(new ChatSettings("a", null, null, null), settings -> false, workdir);
                support.options.apply(
                        "s", runtime, support.options.describe(runtime, List.of()), "provider", "default");
                assertEquals(
                        "a",
                        support.options.describe(runtime, List.of()).getFirst().currentValue());
            }
        }

        @Test
        void modeChangesKeepExplicitApprovalAndProviderDefaultsArePinned() {
            try (final var mapper = CodecsTest.acpMapper();
                    final var support = support(mapper)) {
                final var runtime = support.runtime(support.options.pin(ChatSettings.EMPTY), workdir);
                assertEquals("a", runtime.chatSettings().providerId());
                support.providers.setProvider("default");
                assertEquals("a", support.options.providerId(runtime.chatSettings()));
                runtime.permissions().setMode(AgentMode.PLAN);
                support.options.apply("s", runtime, support.options.describe(runtime, List.of()), "mode", "build");
                assertEquals(AgentMode.BUILD, runtime.permissions().mode());
                assertEquals(PermissionMode.ASK, runtime.permissions().permissionMode());
                runtime.permissions().setPermissionMode(PermissionMode.YOLO);
                support.options.apply("s", runtime, support.options.describe(runtime, List.of()), "mode", "build");
                assertEquals(PermissionMode.ASK, runtime.permissions().permissionMode());
                support.options.apply("s", runtime, support.options.describe(runtime, List.of()), "mode", "plan");
                assertEquals(AgentMode.PLAN, runtime.permissions().mode());
                assertEquals(PermissionMode.ASK, runtime.permissions().permissionMode());
            }
        }

        private OptionTestSupport support(final JsonMapper mapper) {
            return new OptionTestSupport(configuration, commandOptions, mapper, settings -> {
                fail("Descriptor generation must not discover models");
                return CompletableFuture.completedFuture(new ModelsResponse(List.of()));
            });
        }
    }
}
