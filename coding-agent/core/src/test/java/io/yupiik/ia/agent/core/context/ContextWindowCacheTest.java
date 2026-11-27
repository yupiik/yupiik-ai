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
package io.yupiik.ia.agent.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.llm.ModelsResponse;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class ContextWindowCacheTest {
    private final Clock clock = Clock.systemUTC();

    private ContextWindowCache cache(final JsonMapper mapper, final CodingAgentConfiguration configuration) {
        return new ContextWindowCache(configuration, mapper, clock);
    }

    private Path cacheFile(final CodingAgentConfiguration configuration) {
        return Path.of(configuration.resolveStorageHome(), "context-window", "windows.jsonl");
    }

    private long now() {
        return clock.millis();
    }

    @Nested
    @FusionSupport(modules = HoldingAtMostOneEntryPerProviderAndModel.Module.class)
    class HoldingAtMostOneEntryPerProviderAndModel {
        @TempDir
        static Path storageHome;

        public static class Module extends ConfigurationTestModule {
            public Module() {
                super(Map.of("yca.storageHome", storageHome.toString()));
            }
        }

        @Test
        void holdingAtMostOneEntryPerProviderAndModel(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var cache = cache(mapper, configuration);
            cache.put(
                    "freeinference",
                    "deepseek-v4-flash",
                    new ModelsResponse.TokenCount(1_000_000, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "test"),
                    new ModelsResponse.TokenCount(393_216, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "test"),
                    now());
            cache.put(
                    "freeinference",
                    "glm-5.3",
                    new ModelsResponse.TokenCount(1_000_000, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "test"),
                    new ModelsResponse.TokenCount(131_072, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "test"),
                    now());
            // re-fetch of the same pair must replace, not append
            cache.put(
                    "freeinference",
                    "deepseek-v4-flash",
                    new ModelsResponse.TokenCount(1_000_000, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "test"),
                    new ModelsResponse.TokenCount(393_216, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "test"),
                    now() + 1);

            assertSingleEntryPerKey(cache, mapper, configuration);
            assertEquals(1_000_000, cache.getInt("freeinference", "deepseek-v4-flash"));
            assertEquals(1_000_000, cache.getInt("freeinference", "glm-5.3"));
        }
    }

    @Nested
    @FusionSupport(modules = FreshestOverflowHalvingReplacesTheDetectedWindow.Module.class)
    class FreshestOverflowHalvingReplacesTheDetectedWindow {
        @TempDir
        static Path storageHome;

        public static class Module extends ConfigurationTestModule {
            public Module() {
                super(Map.of("yca.storageHome", storageHome.toString()));
            }
        }

        @Test
        void freshestOverflowHalvingReplacesTheDetectedWindow(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var cache = cache(mapper, configuration);
            cache.put(
                    "freeinference",
                    "deepseek-v4-flash",
                    new ModelsResponse.TokenCount(500_000, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "test"),
                    new ModelsResponse.TokenCount(131_072, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "test"),
                    now()); // /models detection
            // overflow halving writes the corrected (fresher) value for the same pair
            cache.put(
                    "freeinference",
                    "deepseek-v4-flash",
                    new ModelsResponse.TokenCount(250_000, false, ModelsResponse.SourceType.OVERFLOW_ADJUSTED, "test"),
                    null,
                    now() + 1);

            assertSingleEntryPerKey(cache, mapper, configuration);
            assertEquals(250_000, cache.getInt("freeinference", "deepseek-v4-flash"));
            assertEquals(Optional.empty(), cache.getMaxTokens("freeinference", "deepseek-v4-flash"));
        }
    }

    @Nested
    @FusionSupport(modules = StaleOverflowAdjustmentIsNotReturnedAsADetectedFallback.Module.class)
    class StaleOverflowAdjustmentIsNotReturnedAsADetectedFallback {
        @TempDir
        static Path storageHome;

        public static class Module extends ConfigurationTestModule {
            public Module() {
                super(Map.of("yca.storageHome", storageHome.toString()));
            }
        }

        @Test
        void staleOverflowAdjustmentIsNotReturnedAsADetectedFallback(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
            final var cache = cache(mapper, configuration);
            final var expired = now() - 25L * 60L * 60L * 1000L;
            cache.put(
                    "freeinference",
                    "deepseek-v4-flash",
                    new ModelsResponse.TokenCount(
                            16_000, false, ModelsResponse.SourceType.OVERFLOW_ADJUSTED, "auto-baseline-halved"),
                    null,
                    expired);
            assertEquals(
                    Optional.empty(),
                    cache.getStale("freeinference", "deepseek-v4-flash"),
                    "a stale overflow adjustment must not become an authoritative fallback");

            cache.put(
                    "freeinference",
                    "glm-5.3",
                    new ModelsResponse.TokenCount(
                            272_000, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "/context_window"),
                    null,
                    expired);
            assertEquals(
                    Optional.of(272_000),
                    cache.getStale("freeinference", "glm-5.3").map(ModelsResponse.TokenCount::value),
                    "a stale detected window is still better than the conservative baseline");
        }
    }

    @Nested
    @FusionSupport(modules = DistinctProvidersAndModelsArePreserved.Module.class)
    class DistinctProvidersAndModelsArePreserved {
        @TempDir
        static Path storageHome;

        public static class Module extends ConfigurationTestModule {
            public Module() {
                super(Map.of("yca.storageHome", storageHome.toString()));
            }
        }

        @Test
        void distinctProvidersAndModelsArePreserved(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var cache = cache(mapper, configuration);
            cache.put(
                    "freeinference",
                    "deepseek-v4-flash",
                    new ModelsResponse.TokenCount(1_000_000, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "test"),
                    new ModelsResponse.TokenCount(393_216, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "test"),
                    now());
            cache.put(
                    "pro",
                    "deepseek-v4-flash",
                    new ModelsResponse.TokenCount(128_000, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "test"),
                    new ModelsResponse.TokenCount(32_768, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "test"),
                    now()); // same model, other provider
            cache.put(
                    "pro",
                    "other-model",
                    new ModelsResponse.TokenCount(64_000, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "test"),
                    new ModelsResponse.TokenCount(16_384, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "test"),
                    now());

            assertSingleEntryPerKey(cache, mapper, configuration);
            assertEquals(1_000_000, cache.getInt("freeinference", "deepseek-v4-flash"));
            assertEquals(128_000, cache.getInt("pro", "deepseek-v4-flash"));
            assertEquals(64_000, cache.getInt("pro", "other-model"));
        }
    }

    @Nested
    @FusionSupport(modules = LegacyDuplicateLinesSelfHealOnNextWrite.Module.class)
    class LegacyDuplicateLinesSelfHealOnNextWrite {
        @TempDir
        static Path storageHome;

        public static class Module extends ConfigurationTestModule {
            public Module() {
                super(Map.of("yca.storageHome", storageHome.toString()));
            }
        }

        @Test
        void legacyDuplicateLinesSelfHealOnNextWrite(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var file = cacheFile(configuration);
            Files.createDirectories(file.getParent());
            // duplicate lines for the same pair (as legacy appends produce), both fresh enough to be read
            Files.writeString(file, """
                {"providerId":"freeinference","model":"deepseek-v4-flash","window":{"value":1000000,"absolute":true,"sourceType":"DETECTED_ABSOLUTE","sourcePointer":"test"},"maxTokens":{"value":393216,"absolute":true,"sourceType":"DETECTED_ABSOLUTE","sourcePointer":"test"},"fetchedAt":%s}
                {"providerId":"freeinference","model":"deepseek-v4-flash","window":{"value":1000000,"absolute":true,"sourceType":"DETECTED_ABSOLUTE","sourcePointer":"test"},"maxTokens":{"value":393216,"absolute":true,"sourceType":"DETECTED_ABSOLUTE","sourcePointer":"test"},"fetchedAt":%s}
                """.formatted(now(), now() + 1));

            final var cache = cache(mapper, configuration);
            // read still resolves to the freshest duplicate
            assertEquals(1_000_000, cache.getInt("freeinference", "deepseek-v4-flash"));
            // a write to a *different* key must collapse the duplicates for a key that is re-written too
            cache.put(
                    "freeinference",
                    "glm-5.3",
                    new ModelsResponse.TokenCount(1_000_000, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "test"),
                    new ModelsResponse.TokenCount(131_072, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "test"),
                    now());
            assertSingleEntryPerKey(cache, mapper, configuration);
            assertEquals(1_000_000, cache.getInt("freeinference", "deepseek-v4-flash"));
            assertEquals(1_000_000, cache.getInt("freeinference", "glm-5.3"));
        }
    }

    @Nested
    @FusionSupport(modules = TmpFileIsNotLeftBehind.Module.class)
    class TmpFileIsNotLeftBehind {
        @TempDir
        static Path storageHome;

        public static class Module extends ConfigurationTestModule {
            public Module() {
                super(Map.of("yca.storageHome", storageHome.toString()));
            }
        }

        @Test
        void tmpFileIsNotLeftBehind(final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var cache = cache(mapper, configuration);
            cache.put(
                    "freeinference",
                    "deepseek-v4-flash",
                    new ModelsResponse.TokenCount(1_000_000, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "test"),
                    new ModelsResponse.TokenCount(393_216, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "test"),
                    now());
            try (final var files = Files.list(cacheFile(configuration).getParent())) {
                assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
            }
        }
    }

    private void assertSingleEntryPerKey(
            final ContextWindowCache cache, final JsonMapper mapper, final CodingAgentConfiguration configuration)
            throws Exception {
        final var file = cacheFile(configuration);
        assertTrue(Files.isRegularFile(file), "cache file must exist after a write");
        final var lines =
                Files.readAllLines(file).stream().filter(l -> !l.isBlank()).toList();
        final var keys = lines.stream()
                .map(l -> mapper.fromString(ContextWindowCache.Entry.class, l))
                .map(e -> e.providerId() + "/" + e.model())
                .toList();
        assertEquals(keys.stream().distinct().toList(), keys, "no duplicate (providerId, model) pair may appear");
    }
}
