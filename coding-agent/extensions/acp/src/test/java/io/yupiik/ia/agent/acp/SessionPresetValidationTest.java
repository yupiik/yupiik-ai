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

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.session.SessionMetadata;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@FusionSupport(modules = OptionConfigurationTestModule.class)
class SessionPresetValidationTest {
    @Fusion
    private CodingAgentConfiguration configuration;

    @Fusion
    private AcpCommand.Conf commandOptions;

    @Test
    void allSamplingStatesAndEffectiveModelSurviveSnapshotWithoutIdentityGuessing(@TempDir final Path root) {
        try (final var mapper = CodecsTest.mapper();
                final var support = new OptionTestSupport(configuration, commandOptions, mapper, ignored -> {
                    fail("Preset restore must not discover models");
                    return CompletableFuture.completedFuture(null);
                })) {
            final var selection = new ChatSettings(
                    "default",
                    "private-model",
                    "low",
                    new ChatClient.SamplingOverride(Map.of(
                            ChatClient.SamplingField.TEMPERATURE,
                            Optional.of(0.2),
                            ChatClient.SamplingField.TOP_P,
                            Optional.empty(),
                            ChatClient.SamplingField.TOP_K,
                            Optional.of(12.0))));
            final var runtime = support.runtime(selection, root);
            final var preset = support.options.snapshot(runtime, null, null);
            assertEquals(selection, support.options.restore(preset));
            final var roundTrip = mapper.fromString(SessionMetadata.class, mapper.toString(preset));
            assertEquals(selection, support.options.restore(roundTrip));
            assertTrue(support.options
                    .restore(roundTrip)
                    .sampling()
                    .get(ChatClient.SamplingField.TOP_P)
                    .isEmpty());
            assertEquals("a", support.providers.currentProviderId());
            final var defaults = support.options.snapshot(
                    support.runtime(new ChatSettings("a", null, null, null), root), null, null);
            assertEquals("a-main", defaults.model());
            assertEquals("high", defaults.variant());
        }
    }

    @Test
    void removedProviderVariantUnknownVersionAndInvalidSamplingAreRejectedOffline() {
        try (final var mapper = CodecsTest.mapper();
                final var support = new OptionTestSupport(configuration, commandOptions, mapper, ignored -> {
                    fail("Validation must remain offline");
                    return CompletableFuture.completedFuture(null);
                })) {
            final var invalid = List.of(
                    new SessionMetadata(2, "a", "a-main", null, List.of(), null, "plan", null),
                    new SessionMetadata(1, "removed", "a-main", null, List.of(), null, "plan", null),
                    new SessionMetadata(1, "a", "a-main", "removed", List.of(), null, "plan", null),
                    new SessionMetadata(1, "a", "a-main", null, List.of(), null, "yolo", null),
                    new SessionMetadata(
                            1,
                            "a",
                            "a-main",
                            null,
                            List.of(new SessionMetadata.Sampling("PASSWORD", 1.0)),
                            null,
                            "plan",
                            null),
                    new SessionMetadata(
                            1,
                            "a",
                            "a-main",
                            null,
                            List.of(new SessionMetadata.Sampling("TOP_P", 2.0)),
                            null,
                            "plan",
                            null),
                    new SessionMetadata(
                            1,
                            "a",
                            "a-main",
                            null,
                            List.of(new SessionMetadata.Sampling("TOP_K", 1.5)),
                            null,
                            "plan",
                            null),
                    new SessionMetadata(
                            1,
                            "a",
                            "a-main",
                            null,
                            List.of(new SessionMetadata.Sampling("TEMPERATURE", Double.NaN)),
                            null,
                            "plan",
                            null));
            for (final var preset : invalid) {
                assertThrows(IllegalArgumentException.class, () -> support.options.restore(preset));
            }
            assertEquals("a", support.providers.currentProviderId());
        }
    }
}
