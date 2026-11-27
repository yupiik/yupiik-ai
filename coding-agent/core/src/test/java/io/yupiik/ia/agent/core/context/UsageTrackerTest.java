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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.NativeProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderListConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ModelsResponse;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.wiring.HttpClientProducer;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.codex.CodexNotAuthenticatedException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class UsageTrackerTest {

    private HttpServer server;
    private ChatClient client;
    private ExtendedHttpClient httpClient;
    private ProviderManager providerManager;
    private CodingAgentConfiguration configuration;
    private JsonMapper mapper;

    private volatile String modelsBody = "{\"data\":[]}";
    private volatile int modelsStatus = 200;

    @BeforeEach
    void start(final JsonMapper mapper, @Fusion final CodingAgentConfiguration baseline) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/models", exchange -> respond(exchange, modelsStatus, modelsBody));
        server.start();
        this.mapper = mapper;
        configuration =
                withEndpoint(baseline, "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions");
        providerManager = new ProviderManager(configuration, mapper);
        httpClient = new HttpClientProducer().httpClient(configuration, new NativeProviderConfiguration(null, null));
        client = new ChatClient(configuration, mapper, providerManager, Clock.systemUTC(), httpClient);
    }

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
        if (httpClient != null) {
            httpClient.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Nested
    @FusionSupport(modules = ConfiguredWindowWinsModule.class)
    class ConfiguredWindowWins {
        @TempDir
        static Path storageHome;

        @Test
        void configuredWindowWins() {
            final var cfg = configuration;
            final var tracker = new UsageTracker(new ProviderManager(cfg, mapper), null, null, Clock.systemUTC());
            assertEquals(32000, tracker.contextWindowTokenCount().value());
        }
    }

    @Nested
    @FusionSupport(modules = PerModelContextWindowOverridesProviderAndDetectionModule.class)
    class PerModelContextWindowOverridesProviderAndDetection {
        @TempDir
        static Path storageHome;

        @Test
        void perModelContextWindowOverridesProviderAndDetection() {
            // re-resolve with a per-model override the provider's own model name
            final var cfgOverride = configuration;
            final var pm = new ProviderManager(cfgOverride, mapper);
            final var tracker = new UsageTracker(pm, client, null, Clock.systemUTC());
            assertEquals(
                    1_000_000,
                    tracker.contextWindowTokenCount().value(),
                    "the per-model override must win over the provider value");
            assertEquals(
                    "explicit", tracker.windowOrigin(), "a per-model override counts as explicit, never auto-halved");
        }
    }

    @Nested
    @FusionSupport(modules = PerModelOverrideIsNeverHalvedAndPersistedModule.class)
    class PerModelOverrideIsNeverHalvedAndPersisted {
        @TempDir
        static Path storageHome;

        @Test
        void perModelOverrideIsNeverHalvedAndPersisted() throws Exception {
            // provider has no window -> detection via /v1/models (empty here) -> baseline; but the
            // per-model override pins it, and rememberShrinkOnOverflow must not persist the halved value
            final var cfgOverride = configuration;
            final var pm = new ProviderManager(cfgOverride, mapper);
            final var cache = new ContextWindowCache(cfgOverride, mapper, Clock.systemUTC());
            // seed a detected entry so the assertion below proves the override was not persisted over,
            // instead of merely observing that an empty cache stayed empty
            cache.put(
                    "default",
                    "test-model",
                    new ModelsResponse.TokenCount(64_000, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "seed"),
                    null,
                    System.currentTimeMillis());
            final var tracker = new UsageTracker(pm, client, cache, Clock.systemUTC());
            assertEquals(1_000_000, tracker.contextWindowTokenCount().value());
            // an overflow-triggered shrink must not persist (the override is absolute, not a fallback)
            tracker.rememberShrinkOnOverflow();
            assertEquals(
                    64_000,
                    cache.getInt("default", "test-model"),
                    "the per-model override must not be persisted as a halved window");
            // a fresh tracker resolves the same 1M again, never the cached seed
            final var fresh = new UsageTracker(pm, client, cache, Clock.systemUTC());
            assertEquals(
                    1_000_000,
                    fresh.contextWindowTokenCount().value(),
                    "the per-model override must survive overflow auto-halving");
        }
    }

    @Nested
    @FusionSupport(modules = FullInputAccumulatesWithoutChangingRawPromptBillingModule.class)
    class FullInputAccumulatesWithoutChangingRawPromptBilling {
        @TempDir
        static Path storageHome;

        @Test
        void fullInputAccumulatesWithoutChangingRawPromptBilling() {
            final var tracker = new UsageTracker(providerManager, null, null, Clock.systemUTC());
            assertEquals(0, tracker.contextInputTokens());
            // Exclusive cache accounting: raw prompt excludes cache reads and writes.
            tracker.record(new Usage(10, 5, 15, 60, 30, 0, 0, 100));
            assertEquals(100, tracker.contextInputTokens());
            assertEquals(10, tracker.promptTokens());
            // Inclusive cache accounting: raw prompt already contains cached input.
            tracker.record(new Usage(100, 5, 105, 60, 0, 0, 0, 100));
            tracker.record(null);
            assertEquals(200, tracker.contextInputTokens());
            assertEquals(110, tracker.promptTokens());
            assertEquals(10, tracker.completionTokens());
            assertEquals(120, tracker.cacheReadTokens());
            assertEquals(2, tracker.requests());
            assertEquals(0, tracker.auxiliaryContextInputTokens());
        }
    }

    @Nested
    @FusionSupport(modules = AuxiliaryFullInputIncludesCacheWritesAndReadsWithoutChangingBillingModule.class)
    class AuxiliaryFullInputIncludesCacheWritesAndReadsWithoutChangingBilling {
        @TempDir
        static Path storageHome;

        @Test
        void auxiliaryFullInputIncludesCacheWritesAndReadsWithoutChangingBilling() {
            final var tracker = new UsageTracker(providerManager, null, null, Clock.systemUTC());
            tracker.record(new Usage(100, 20, 120));
            tracker.recordAuxiliary(new Usage(10, 8, 18, 60, 30, 0, 0, 100));
            assertEquals(200, tracker.contextInputTokens());
            assertEquals(100, tracker.auxiliaryContextInputTokens());
            assertEquals(10, tracker.auxiliaryPromptTokens());
            assertEquals(40, tracker.auxiliaryFreshTokens(), "cache writes are fresh input too");
            tracker.recordAuxiliary(new Usage(100, 8, 108, 60, 0, 0, 0, 100));
            tracker.recordAuxiliary(null);
            assertEquals(300, tracker.contextInputTokens());
            assertEquals(200, tracker.auxiliaryContextInputTokens());
            assertEquals(210, tracker.promptTokens());
            assertEquals(110, tracker.auxiliaryPromptTokens());
            assertEquals(80, tracker.auxiliaryFreshTokens());
            assertEquals(2, tracker.auxiliaryRequests());
            assertEquals(3, tracker.requests());
        }
    }

    @Nested
    @FusionSupport(modules = AuxiliaryFreshInputCannotBeNegativeModule.class)
    class AuxiliaryFreshInputCannotBeNegative {
        @TempDir
        static Path storageHome;

        @Test
        void auxiliaryFreshInputCannotBeNegative() {
            final var tracker = new UsageTracker(providerManager, null, null, Clock.systemUTC());
            tracker.recordAuxiliary(new Usage(0, 0, 0, 100, 0, 0, 0, 90));
            assertEquals(0, tracker.auxiliaryFreshTokens());
        }
    }

    @Nested
    @FusionSupport(modules = AuxiliarySpendIsCountedGloballyAndSeparatelyModule.class)
    class AuxiliarySpendIsCountedGloballyAndSeparately {
        @TempDir
        static Path storageHome;

        @Test
        void auxiliarySpendIsCountedGloballyAndSeparately() {
            final var tracker = new UsageTracker(providerManager, null, null, Clock.systemUTC());
            tracker.record(new Usage(100, 20, 120));
            tracker.recordAuxiliary(new Usage(11, 7, 18)); // no cache-read
            assertEquals(111, tracker.promptTokens());
            assertEquals(27, tracker.completionTokens());
            assertEquals(2, tracker.requests());
            assertEquals(11, tracker.auxiliaryPromptTokens());
            assertEquals(7, tracker.auxiliaryCompletionTokens());
            assertEquals(0, tracker.auxiliaryCacheReadTokens());
            assertEquals(11, tracker.auxiliaryFreshTokens(), "fresh = prompt - cache-read, all prompt is fresh");
            assertEquals(1, tracker.auxiliaryRequests());
            // reset is not available on base UsageTracker anymore (only on SubagentUsageTracker)
            // use a new instance instead
        }
    }

    @Nested
    @FusionSupport(modules = AuxiliaryFreshTokensSubtractTheAuxCacheReadModule.class)
    class AuxiliaryFreshTokensSubtractTheAuxCacheRead {
        @TempDir
        static Path storageHome;

        @Test
        void auxiliaryFreshTokensSubtractTheAuxCacheRead() {
            final var tracker = new UsageTracker(providerManager, null, null, Clock.systemUTC());
            // (prompt, completion, total, cacheRead, cacheWrite, reasoning, ...)
            tracker.recordAuxiliary(new Usage(100, 8, 108, 60, 0, 0, 0));
            assertEquals(100, tracker.auxiliaryPromptTokens());
            assertEquals(60, tracker.auxiliaryCacheReadTokens());
            assertEquals(40, tracker.auxiliaryFreshTokens(), "background fresh excludes the cached prefix");
            assertEquals(100, tracker.promptTokens(), "aux prompt also counts toward the global total");
            assertEquals(60, tracker.cacheReadTokens(), "aux cache-read also counts toward the global total");
        }
    }

    @Nested
    @FusionSupport(modules = LlamaCppRuntimeContextIsReadModule.class)
    class LlamaCppRuntimeContextIsRead {
        @TempDir
        static Path storageHome;

        @Test
        void llamaCppRuntimeContextIsRead() {
            modelsBody =
                    "{\"data\":[{\"id\":\"test-model\",\"owned_by\":\"llamacpp\",\"meta\":{\"n_ctx\":196608,\"n_ctx_train\":262144}}]}";
            final var tracker = tracker();
            assertEquals(196608, tracker.contextWindowTokenCount().value());
            server.stop(0);
            server = null;
            assertEquals(196608, tracker.contextWindowTokenCount().value());
            assertEquals(
                    Optional.of(196608),
                    new ContextWindowCache(configuration, mapper, Clock.systemUTC())
                            .get("default", "test-model")
                            .map(ModelsResponse.TokenCount::value));
        }
    }

    @Nested
    @FusionSupport(modules = StaleCacheTriggersRefetchAndRefreshModule.class)
    class StaleCacheTriggersRefetchAndRefresh {
        @TempDir
        static Path storageHome;

        @Test
        void staleCacheTriggersRefetchAndRefresh() {
            final var cache = new ContextWindowCache(configuration, mapper, Clock.systemUTC());
            cache.put(
                    "default",
                    "test-model",
                    new ModelsResponse.TokenCount(4096, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "test"),
                    null,
                    System.currentTimeMillis() - 25L * 60L * 60L * 1000L);
            modelsBody = "{\"data\":[{\"id\":\"test-model\",\"context_window\":8192}]}";
            assertEquals(8192, tracker().contextWindowTokenCount().value());
            assertEquals(Optional.of(8192), cache.get("default", "test-model").map(ModelsResponse.TokenCount::value));
        }
    }

    @Nested
    @FusionSupport(modules = FreshCacheSkipsModelsCallModule.class)
    class FreshCacheSkipsModelsCall {
        @TempDir
        static Path storageHome;

        @Test
        void freshCacheSkipsModelsCall() {
            new ContextWindowCache(configuration, mapper, Clock.systemUTC())
                    .put(
                            "default",
                            "test-model",
                            new ModelsResponse.TokenCount(
                                    64000, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "test"),
                            null,
                            System.currentTimeMillis());
            modelsStatus = 500;
            assertEquals(64000, tracker().contextWindowTokenCount().value());
        }
    }

    @Nested
    @FusionSupport(modules = OverflowAdjustedCacheIsNotAuthoritativeOverFreshDetectionModule.class)
    class OverflowAdjustedCacheIsNotAuthoritativeOverFreshDetection {
        @TempDir
        static Path storageHome;

        @Test
        void overflowAdjustedCacheIsNotAuthoritativeOverFreshDetection() {
            modelsStatus = 500;
            final var cache = new ContextWindowCache(configuration, mapper, Clock.systemUTC());
            final var failing = new UsageTracker(providerManager, client, cache, Clock.systemUTC());
            assertEquals(
                    ModelsResponse.AUTO_DETECTED_BASELINE,
                    failing.contextWindowTokenCount().value());
            assertEquals(16_000, failing.rememberShrinkOnOverflow());
            assertEquals(
                    ModelsResponse.SourceType.OVERFLOW_ADJUSTED,
                    cache.get("default", "test-model").orElseThrow().sourceType());

            modelsStatus = 200;
            modelsBody = "{\"data\":[{\"id\":\"test-model\",\"context_length\":1000000,\"max_output_length\":393216}]}";
            final var recovered = new UsageTracker(providerManager, client, cache, Clock.systemUTC());
            assertEquals(1_000_000, recovered.contextWindowTokenCount().value());
            assertEquals("explicit", recovered.windowOrigin());
            assertEquals(1_000_000, cache.getInt("default", "test-model"));
        }
    }

    @Nested
    @FusionSupport(modules = OverflowAdjustedCacheIsReportedAsAutoWhenDetectionFailsModule.class)
    class OverflowAdjustedCacheIsReportedAsAutoWhenDetectionFails {
        @TempDir
        static Path storageHome;

        @Test
        void overflowAdjustedCacheIsReportedAsAutoWhenDetectionFails() {
            modelsStatus = 500;
            final var cache = new ContextWindowCache(configuration, mapper, Clock.systemUTC());
            final var failing = new UsageTracker(providerManager, client, cache, Clock.systemUTC());
            failing.contextWindowTokenCount();
            failing.rememberShrinkOnOverflow();

            final var recovered = new UsageTracker(providerManager, client, cache, Clock.systemUTC());
            assertEquals(16_000, recovered.contextWindowTokenCount().value());
            assertEquals("auto", recovered.windowOrigin());
            assertEquals(12_000, recovered.usableWindow(), "the provisional fallback is still a conservative 12k");
        }
    }

    @Nested
    @FusionSupport(modules = LastResolutionOriginReflectsTheLatestResolutionModule.class)
    class LastResolutionOriginReflectsTheLatestResolution {
        @TempDir
        static Path storageHome;

        @Test
        void lastResolutionOriginReflectsTheLatestResolution() {
            modelsBody = "{\"data\":[{\"id\":\"test-model\",\"context_length\":1000000}]}";
            final var tracker = tracker();
            assertEquals(1_000_000, tracker.contextWindowTokenCount().value());
            assertEquals(1_000_000, tracker.contextWindowTokenCount().value());
            assertEquals(1_000_000, tracker.contextWindowTokenCount().value());
            assertEquals("explicit", tracker.windowOrigin());

            tracker.attach(new ChatSettings("default", "unknown-model", null, null));
            assertEquals(
                    ModelsResponse.AUTO_DETECTED_BASELINE,
                    tracker.contextWindowTokenCount().value());
            assertEquals(
                    "auto",
                    tracker.windowOrigin(),
                    "the latest fallback must not be hidden by earlier explicit resolutions");
        }
    }

    @Nested
    @FusionSupport(modules = OverflowAdjustedCacheIsNotAuthoritativeForBackgroundModelsModule.class)
    class OverflowAdjustedCacheIsNotAuthoritativeForBackgroundModels {
        @TempDir
        static Path storageHome;

        @Test
        void overflowAdjustedCacheIsNotAuthoritativeForBackgroundModels() {
            final var cache = new ContextWindowCache(configuration, mapper, Clock.systemUTC());
            cache.put(
                    "default",
                    "test-model",
                    new ModelsResponse.TokenCount(
                            16_000, false, ModelsResponse.SourceType.OVERFLOW_ADJUSTED, "auto-baseline-halved"),
                    null,
                    System.currentTimeMillis());
            modelsBody = "{\"data\":[{\"id\":\"test-model\",\"context_length\":1000000}]}";

            final var tracker = new UsageTracker(providerManager, client, cache, Clock.systemUTC());
            assertEquals(980_000, tracker.usableWindowForModel("$default/test-model"));
        }
    }

    @Nested
    @FusionSupport(modules = MissingVendorValueFallsBackToAutoBaselineModule.class)
    class MissingVendorValueFallsBackToAutoBaseline {
        @TempDir
        static Path storageHome;

        @Test
        void missingVendorValueFallsBackToAutoBaseline() {
            modelsBody = "{\"data\":[{\"id\":\"test-model\",\"owned_by\":\"x\"}]}";
            assertEquals(
                    ModelsResponse.AUTO_DETECTED_BASELINE,
                    tracker().contextWindowTokenCount().value());
        }
    }

    @Nested
    @FusionSupport(modules = ModelsFailureFallsBackToAutoBaselineModule.class)
    class ModelsFailureFallsBackToAutoBaseline {
        @TempDir
        static Path storageHome;

        @Test
        void modelsFailureFallsBackToAutoBaseline() {
            modelsStatus = 500;
            assertEquals(
                    ModelsResponse.AUTO_DETECTED_BASELINE,
                    tracker().contextWindowTokenCount().value());
        }
    }

    @Nested
    @FusionSupport(modules = ModelsFailureIsCoalescedOnSubsequentResolutionsModule.class)
    class ModelsFailureIsCoalescedOnSubsequentResolutions {
        @TempDir
        static Path storageHome;

        @Test
        void modelsFailureIsCoalescedOnSubsequentResolutions() {
            modelsStatus = 500;
            final var tracker = tracker();
            // first resolution fails -> warning fires, falls back to the conservative baseline
            assertEquals(
                    ModelsResponse.AUTO_DETECTED_BASELINE,
                    tracker.contextWindowTokenCount().value());
            // the failure is remembered, so a rapid follow-up must not re-hit /v1/models: even though the
            // endpoint recovered, the second resolution stays on the baseline instead of re-detecting
            modelsStatus = 200;
            modelsBody = "{\"data\":[{\"id\":\"test-model\",\"context_window\":8192}]}";
            assertEquals(
                    ModelsResponse.AUTO_DETECTED_BASELINE,
                    tracker.contextWindowTokenCount().value(),
                    "a recent auto-detection failure must be coalesced, not re-attempted every resolution");
        }
    }

    @Nested
    @FusionSupport(modules = EffectiveContextFallsBackToRawEstimateWithoutAnchorModule.class)
    class EffectiveContextFallsBackToRawEstimateWithoutAnchor {
        @TempDir
        static Path storageHome;

        @Test
        void effectiveContextFallsBackToRawEstimateWithoutAnchor() {
            final var tracker = calibratedTracker(configuration);
            final var messages = List.of(Message.user("hello world"));
            assertEquals(tracker.estimateContext(messages, 0), tracker.effectiveContext(messages, 0));
        }
    }

    @Nested
    @FusionSupport(modules = AnchorMeasuresUnchangedRequestAndItsVerifiedExtensionModule.class)
    class AnchorMeasuresUnchangedRequestAndItsVerifiedExtension {
        @TempDir
        static Path storageHome;

        @Test
        void anchorMeasuresUnchangedRequestAndItsVerifiedExtension() {
            final var tracker = calibratedTracker(configuration);
            final var base = List.of(Message.user("a".repeat(400)));
            final var payload = tracker.contextPayload(base, 0);
            tracker.anchor(50, payload);
            assertEquals(50, tracker.effectiveContext(base, 0));
            assertEquals("provider-reported", tracker.lastContextMeasurement().source());
            final var extended = List.of(base.getFirst(), Message.user("more input"));
            final var raw = tracker.estimateContext(extended, 0);
            assertEquals(
                    (int) Math.round(50.0 * raw / payload.estimatedTokens()), tracker.effectiveContext(extended, 0));
            assertEquals("provider-anchored", tracker.lastContextMeasurement().source());
        }
    }

    @Nested
    @FusionSupport(modules = RewrittenContextRetainsCalibrationButNotExactAnchorModule.class)
    class RewrittenContextRetainsCalibrationButNotExactAnchor {
        @TempDir
        static Path storageHome;

        @Test
        void rewrittenContextRetainsCalibrationButNotExactAnchor() {
            final var tracker = calibratedTracker(configuration);
            final var base = List.of(Message.user("a".repeat(400)));
            final var payload = tracker.contextPayload(base, 0);
            tracker.anchor(50, payload);
            final var rewritten = List.of(Message.user("b".repeat(400)));
            assertEquals(50, tracker.effectiveContext(rewritten, 0));
            assertEquals("calibrated", tracker.lastContextMeasurement().source());
            tracker.clearAnchor();
            assertEquals(50, tracker.effectiveContext(base, 0));
            assertEquals("calibrated", tracker.lastContextMeasurement().source());
            assertEquals(-1, tracker.lastRealPromptTokens());
        }
    }

    @Nested
    @FusionSupport(modules = MissingUsageDoesNotInventAnObservationModule.class)
    class MissingUsageDoesNotInventAnObservation {
        @TempDir
        static Path storageHome;

        @Test
        void missingUsageDoesNotInventAnObservation() {
            final var tracker = calibratedTracker(configuration);
            final var messages = List.of(Message.user("hello"));
            tracker.anchor(0, tracker.contextPayload(messages, 0));
            assertEquals(tracker.estimateContext(messages, 0), tracker.effectiveContext(messages, 0));
            assertEquals("heuristic", tracker.lastContextMeasurement().source());
        }
    }

    @Nested
    @FusionSupport(modules = UsableWindowReservesCompletionHeadroomModule.class)
    class UsableWindowReservesCompletionHeadroom {
        @TempDir
        static Path storageHome;

        @Test
        void usableWindowReservesCompletionHeadroom() {
            assertEquals(80_000, calibratedTracker(configuration).usableWindow());
        }
    }

    @Nested
    @FusionSupport(modules = SmallWindowReservesCompletionHeadroomModule.class)
    class SmallWindowReservesCompletionHeadroom {
        @TempDir
        static Path storageHome;

        @Test
        void smallWindowReservesCompletionHeadroom() {
            final var small =
                    new UsageTracker(new ProviderManager(configuration, mapper), null, null, Clock.systemUTC());
            assertEquals(3_072, small.usableWindow());
        }
    }

    @Nested
    @FusionSupport(modules = TopProviderOutputCapIsReservedFromTheDetectedWindowModule.class)
    class TopProviderOutputCapIsReservedFromTheDetectedWindow {
        @TempDir
        static Path storageHome;

        @Test
        void topProviderOutputCapIsReservedFromTheDetectedWindow() {
            modelsBody = "{\"data\":[{\"id\":\"test-model\",\"top_provider\":{\"is_moderated\":false,"
                    + "\"context_length\":262144,\"max_completion_tokens\":32768}}]}";
            final var tracker = tracker();
            assertEquals(262_144, tracker.contextWindowTokenCount().value());
            assertEquals(32_768, tracker.declaredOutputTokensTokenCount().value());
            assertEquals(229_376, tracker.usableWindow());
        }
    }

    @Nested
    @FusionSupport(modules = UsableWindowForModelUsesTheReferencedModelWindowModule.class)
    class UsableWindowForModelUsesTheReferencedModelWindow {
        @TempDir
        static Path storageHome;

        @Test
        void usableWindowForModelUsesTheReferencedModelWindow() {
            // a session on the 'main' provider: explicit main window 128k, no output cap configured
            final var tracker =
                    new UsageTracker(new ProviderManager(configuration, mapper), null, null, Clock.systemUTC());
            // the session's own usable window is the main provider's 128k (minus the 20k fallback reserve)
            assertEquals(108_000, tracker.usableWindow());
            // a background compact reference routed to the 'cheap' provider with a smaller window is
            // sized against that smaller window: 32k - min(20k, 32k/4)=8k -> 24k usable
            assertEquals(24_000, tracker.usableWindowForModel("$cheap/compact-model"));
        }
    }

    @Nested
    @FusionSupport(modules = UsableWindowForModelBlankFallsBackToSessionWindowModule.class)
    class UsableWindowForModelBlankFallsBackToSessionWindow {
        @TempDir
        static Path storageHome;

        @Test
        void usableWindowForModelBlankFallsBackToSessionWindow() {
            final var tracker =
                    new UsageTracker(new ProviderManager(configuration, mapper), null, null, Clock.systemUTC());
            assertEquals(
                    tracker.usableWindow(),
                    tracker.usableWindowForModel(null),
                    "a blank/null compact reference must not change the window (summarizer on the conversation"
                            + " model)");
            assertEquals(
                    tracker.usableWindow(),
                    tracker.usableWindowForModel("  "),
                    "a blank compact reference must fall back to the conversation model window");
        }
    }

    @Nested
    @FusionSupport(modules = UsableWindowForModelWithoutAnyEstablishedWindowFallsBackToConservativeModule.class)
    class UsableWindowForModelWithoutAnyEstablishedWindowFallsBackToConservative {
        @TempDir
        static Path storageHome;

        @Test
        void usableWindowForModelWithoutAnyEstablishedWindowFallsBackToConservative() {
            // a provider configured with NO context window and NO /v1/models detection (client is null):
            // no window can be established, so the caller must get the conservative MIN, never a phantom
            final var tracker =
                    new UsageTracker(new ProviderManager(configuration, mapper), null, null, Clock.systemUTC());
            assertEquals(1_024, tracker.usableWindowForModel("$nodata/ghost"));
        }
    }

    @Nested
    @FusionSupport(modules = EqualLiteLLMCapsAreNotATrustedPartition1Module.class)
    class EqualLiteLLMCapsAreNotATrustedPartition1 {
        @TempDir
        static Path storageHome;

        @Test
        void equalLiteLLMCapsAreNotATrustedPartition1() {
            // the user's /v1/models payload: max_input_tokens == max_output_tokens per model
            modelsBody = """
                {"data":[
                 {"id":"qwen3.8-27b","object":"model","created":1677610602,"owned_by":"openai","max_input_tokens":131072,"max_output_tokens":131072},
                 {"id":"gemma4-26b","object":"model","created":1677610602,"owned_by":"openai","max_input_tokens":131072,"max_output_tokens":131072},
                 {"id":"qwen3-8b","object":"model","created":1677610602,"owned_by":"openai","max_input_tokens":40960,"max_output_tokens":40960}
                ],"object":"list"}\
                """;
            // the equal caps are per-side limits, not a partition: the input limit is taken directly
            assertLiteLLMUsable(configuration, 131072, 131072);
        }
    }

    @Nested
    @FusionSupport(modules = EqualLiteLLMCapsAreNotATrustedPartition2Module.class)
    class EqualLiteLLMCapsAreNotATrustedPartition2 {
        @TempDir
        static Path storageHome;

        @Test
        void equalLiteLLMCapsAreNotATrustedPartition2() {
            // the user's /v1/models payload: max_input_tokens == max_output_tokens per model
            modelsBody = """
                {"data":[
                 {"id":"qwen3.8-27b","object":"model","created":1677610602,"owned_by":"openai","max_input_tokens":131072,"max_output_tokens":131072},
                 {"id":"gemma4-26b","object":"model","created":1677610602,"owned_by":"openai","max_input_tokens":131072,"max_output_tokens":131072},
                 {"id":"qwen3-8b","object":"model","created":1677610602,"owned_by":"openai","max_input_tokens":40960,"max_output_tokens":40960}
                ],"object":"list"}\
                """;
            // the equal caps are per-side limits, not a partition: the input limit is taken directly
            assertLiteLLMUsable(configuration, 131072, 131072);
        }
    }

    @Nested
    @FusionSupport(modules = EqualLiteLLMCapsAreNotATrustedPartition3Module.class)
    class EqualLiteLLMCapsAreNotATrustedPartition3 {
        @TempDir
        static Path storageHome;

        @Test
        void equalLiteLLMCapsAreNotATrustedPartition3() {
            // the user's /v1/models payload: max_input_tokens == max_output_tokens per model
            modelsBody = """
                {"data":[
                 {"id":"qwen3.8-27b","object":"model","created":1677610602,"owned_by":"openai","max_input_tokens":131072,"max_output_tokens":131072},
                 {"id":"gemma4-26b","object":"model","created":1677610602,"owned_by":"openai","max_input_tokens":131072,"max_output_tokens":131072},
                 {"id":"qwen3-8b","object":"model","created":1677610602,"owned_by":"openai","max_input_tokens":40960,"max_output_tokens":40960}
                ],"object":"list"}\
                """;
            // the equal caps are per-side limits, not a partition: the input limit is taken directly
            assertLiteLLMUsable(configuration, 40960, 40960);
        }
    }

    private void assertLiteLLMUsable(final CodingAgentConfiguration cfg, final int window, final int usable) {
        final var pm = new ProviderManager(cfg, mapper);
        final var c = new ChatClient(cfg, mapper, pm, Clock.systemUTC(), httpClient);
        try {
            final var tracker =
                    new UsageTracker(pm, c, new ContextWindowCache(cfg, mapper, Clock.systemUTC()), Clock.systemUTC());
            assertEquals(window, tracker.contextWindowTokenCount().value());
            assertEquals(window, tracker.declaredOutputTokensTokenCount().value());
            assertEquals(usable, tracker.usableWindow());
        } finally {
            c.close();
        }
    }

    @Nested
    @FusionSupport(modules = ConfiguredOutputTokensIsReservedFromAConfiguredWindowModule.class)
    class ConfiguredOutputTokensIsReservedFromAConfiguredWindow {
        @TempDir
        static Path storageHome;

        @Test
        void configuredOutputTokensIsReservedFromAConfiguredWindow() {
            final var cfg = configuration;
            final var tracker = new UsageTracker(new ProviderManager(cfg, mapper), null, null, Clock.systemUTC());
            assertEquals(8_000, tracker.declaredOutputTokensTokenCount().value());
            assertEquals(192_000, tracker.usableWindow());
        }
    }

    @Nested
    @FusionSupport(modules = ConfiguredOutputTokensWinsOverTheDetectedCapModule.class)
    class ConfiguredOutputTokensWinsOverTheDetectedCap {
        @TempDir
        static Path storageHome;

        @Test
        void configuredOutputTokensWinsOverTheDetectedCap() {
            modelsBody =
                    "{\"data\":[{\"id\":\"test-model\",\"context_length\":128000,\"max_completion_tokens\":32768}]}";
            final var cfg = configuration;
            final var tracker = new UsageTracker(
                    new ProviderManager(cfg, mapper),
                    client,
                    new ContextWindowCache(cfg, mapper, Clock.systemUTC()),
                    Clock.systemUTC());
            assertEquals(128_000, tracker.contextWindowTokenCount().value());
            assertEquals(4_000, tracker.declaredOutputTokensTokenCount().value());
            assertEquals(124_000, tracker.usableWindow());
        }
    }

    @Nested
    @FusionSupport(modules = OutputCapNotSmallerThanTheWindowFallsBackToTheHeuristicModule.class)
    class OutputCapNotSmallerThanTheWindowFallsBackToTheHeuristic {
        @TempDir
        static Path storageHome;

        @Test
        void outputCapNotSmallerThanTheWindowFallsBackToTheHeuristic() {
            final var cfg = configuration;
            final var tracker = new UsageTracker(new ProviderManager(cfg, mapper), null, null, Clock.systemUTC());
            assertEquals(8_192, tracker.declaredOutputTokensTokenCount().value());
            assertEquals(3_072, tracker.usableWindow(), "a cap larger than the window cannot be honoured");
        }
    }

    @Nested
    @FusionSupport(modules = ContextPercentIsComputedAgainstTheUsableWindowModule.class)
    class ContextPercentIsComputedAgainstTheUsableWindow {
        @TempDir
        static Path storageHome;

        @Test
        void contextPercentIsComputedAgainstTheUsableWindow() {
            assertEquals(10.0, calibratedTracker(configuration).contextPercent(8_000), 0.001);
        }
    }

    @Nested
    @FusionSupport(modules = AuthFailureDoesNotSuppressDetectionAfterRecoveryModule.class)
    class AuthFailureDoesNotSuppressDetectionAfterRecovery {
        @TempDir
        static Path storageHome;

        @Test
        void authFailureDoesNotSuppressDetectionAfterRecovery() throws Exception {
            final var calls = new AtomicInteger();
            final var recovered = new AtomicBoolean();
            // adjustable clock: an auth failure only anti-spams for 60s, so the recovery is observed by
            // advancing the clock past the retry window (as it elapses in real sessions between turns)
            final var now = new AtomicLong(System.currentTimeMillis());
            final Clock clock = new Clock() {
                @Override
                public long millis() {
                    return now.get();
                }

                @Override
                public Instant instant() {
                    return Instant.ofEpochMilli(now.get());
                }

                @Override
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(final ZoneId zone) {
                    return this;
                }
            };
            final var client = new ChatClient(configuration, mapper, providerManager, Clock.systemUTC(), httpClient) {
                @Override
                public ModelsResponse models(final ChatSettings s) {
                    if (!recovered.get()) {
                        calls.incrementAndGet();
                        throw new CodexNotAuthenticatedException("codex-yupiik");
                    }
                    return toModels("{\"data\":[{\"id\":\"test-model\",\"context_window\":272000}]}");
                }
            };
            try {
                final var tracker = new UsageTracker(
                        providerManager,
                        client,
                        new ContextWindowCache(configuration, mapper, Clock.systemUTC()),
                        clock);
                // first resolution: auth failure -> conservative baseline, no permanent backoff
                assertEquals(
                        ModelsResponse.AUTO_DETECTED_BASELINE,
                        tracker.contextWindowTokenCount().value());
                assertEquals("auto", tracker.windowOrigin());
                // auth recovers (user ran /codex login) and the short anti-spam window elapses: the next
                // resolution must retry and succeed, never stay pinned to the baseline
                recovered.set(true);
                now.addAndGet(61_000);
                assertEquals(
                        272_000,
                        tracker.contextWindowTokenCount().value(),
                        "an auth failure must not arm the long backoff: detection retries after login");
                assertEquals("explicit", tracker.windowOrigin());
            } finally {
                client.close();
            }
        }
    }

    @Nested
    @FusionSupport(modules = DetectionFailureBacksOffBrieflyModule.class)
    class DetectionFailureBacksOffBriefly {
        @TempDir
        static Path storageHome;

        @Test
        void detectionFailureBacksOffBriefly() throws Exception {
            final var calls = new AtomicInteger();
            final var now = new AtomicLong(System.currentTimeMillis());
            final Clock clock = new Clock() {
                @Override
                public long millis() {
                    return now.get();
                }

                @Override
                public Instant instant() {
                    return Instant.ofEpochMilli(now.get());
                }

                @Override
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(final ZoneId zone) {
                    return this;
                }
            };
            final var client = new ChatClient(configuration, mapper, providerManager, Clock.systemUTC(), httpClient) {
                @Override
                public ModelsResponse models(final ChatSettings s) {
                    calls.incrementAndGet();
                    throw new IllegalStateException("connection refused");
                }
            };
            try {
                final var tracker = new UsageTracker(
                        providerManager,
                        client,
                        new ContextWindowCache(configuration, mapper, Clock.systemUTC()),
                        clock);
                assertEquals(
                        ModelsResponse.AUTO_DETECTED_BASELINE,
                        tracker.contextWindowTokenCount().value());
                assertEquals(
                        ModelsResponse.AUTO_DETECTED_BASELINE,
                        tracker.contextWindowTokenCount().value());
                assertEquals(1, calls.get(), "a detection failure must not re-attempt on every resolution");
                now.addAndGet(6L * 60L * 1000L);
                assertEquals(
                        ModelsResponse.AUTO_DETECTED_BASELINE,
                        tracker.contextWindowTokenCount().value());
                assertEquals(2, calls.get(), "a transient detection failure must retry after the short backoff");
            } finally {
                client.close();
            }
        }
    }

    @Nested
    @FusionSupport(modules = StaleCacheIsUsedWhenDetectionFailsModule.class)
    class StaleCacheIsUsedWhenDetectionFails {
        @TempDir
        static Path storageHome;

        @Test
        void staleCacheIsUsedWhenDetectionFails() throws Exception {
            final var cfg = configuration;
            final var cache = new ContextWindowCache(cfg, mapper, Clock.systemUTC());
            // seed an entry already out of TTL at resolution time
            cache.put(
                    "default",
                    "test-model",
                    new ModelsResponse.TokenCount(
                            272000, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "/context_window"),
                    System.currentTimeMillis() - 25L * 60L * 60L * 1000L);
            final var client = new ChatClient(cfg, mapper, providerManager, Clock.systemUTC(), httpClient) {
                @Override
                public ModelsResponse models(final ChatSettings s) {
                    throw new IllegalStateException("provider unreachable");
                }
            };
            try {
                final var tracker = new UsageTracker(providerManager, client, cache, Clock.systemUTC());
                assertEquals(
                        272_000,
                        tracker.contextWindowTokenCount().value(),
                        "a stale (expired) cache entry must beat the conservative baseline when detection fails");
            } finally {
                client.close();
            }
        }
    }

    @Nested
    @FusionSupport(modules = DetectionUsesTheSessionProviderSettingsModule.class)
    class DetectionUsesTheSessionProviderSettings {
        @TempDir
        static Path storageHome;

        @Test
        void detectionUsesTheSessionProviderSettings() throws Exception {
            final var seen = new AtomicReference<ChatSettings>();
            final var cfg = configuration;
            final var marker = new ChatSettings("default", "test-model", null, null);
            final var client = new ChatClient(cfg, mapper, providerManager, Clock.systemUTC(), httpClient) {
                @Override
                public ModelsResponse models(final ChatSettings s) {
                    seen.set(s);
                    return toModels("{\"data\":[{\"id\":\"test-model\",\"context_window\":500000}]}");
                }
            };
            try {
                final var tracker = new UsageTracker(
                        providerManager,
                        client,
                        new ContextWindowCache(cfg, mapper, Clock.systemUTC()),
                        Clock.systemUTC());
                tracker.attach(marker);
                assertEquals(500_000, tracker.contextWindowTokenCount().value());
                assertEquals(
                        marker,
                        seen.get(),
                        "detection must resolve the models list against the session's provider settings");
            } finally {
                client.close();
            }
        }
    }

    private ModelsResponse toModels(final String body) {
        return mapper.fromString(ModelsResponse.class, body);
    }

    private UsageTracker calibratedTracker(final CodingAgentConfiguration configuration) {
        return new UsageTracker(new ProviderManager(configuration, mapper), null, null, Clock.systemUTC());
    }

    private UsageTracker tracker() {
        return new UsageTracker(
                providerManager,
                client,
                new ContextWindowCache(configuration, mapper, Clock.systemUTC()),
                Clock.systemUTC());
    }

    private static Map<String, String> usageSettings(
            final Path storageHome, final int contextWindow, final int outputTokens) {
        return Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "default"),
                Map.entry("yca.provider.providers.0.baseUrl", "http://localhost:0/v1/chat/completions"),
                Map.entry("yca.provider.providers.0.apiKey", "test-key"),
                Map.entry("yca.provider.providers.0.model", "test-model"),
                Map.entry("yca.provider.providers.0.api", "auto"),
                Map.entry("yca.provider.providers.0.contextWindow", String.valueOf(contextWindow)),
                Map.entry("yca.provider.providers.0.outputTokens", String.valueOf(outputTokens)),
                Map.entry("yca.provider.providers.0.timeoutSeconds", "5"),
                Map.entry("yca.llm.retriesMaxAttempts", "1"),
                Map.entry("yca.llm.retriesBaseDelayMs", "1"),
                Map.entry("yca.llm.rateLimitRequestsPerMinute", "0"),
                Map.entry("yca.llm.maxConcurrent", "1"),
                Map.entry("yca.llm.retryOnContextOverflow", "true"),
                Map.entry("yca.llm.maxIterations", "250"));
    }

    private static void respond(final HttpExchange exchange, final int status, final String body) {
        try {
            exchange.getRequestBody().readAllBytes();
            final var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (final var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, String> multiWindowSettings(final Path storageHome) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", storageHome.toString());
        entries.put("yca.provider.providers.length", "2");
        entries.put("yca.provider.providers.0.id", "main");
        entries.put("yca.provider.providers.0.baseUrl", "https://main.example/v1/chat/completions");
        entries.put("yca.provider.providers.0.apiKey", "");
        entries.put("yca.provider.providers.0.model", "main-model");
        entries.put("yca.provider.providers.0.api", "auto");
        entries.put("yca.provider.providers.0.contextWindow", "128000");
        entries.put("yca.provider.providers.0.timeoutSeconds", "120");
        entries.put("yca.provider.providers.1.id", "cheap");
        entries.put("yca.provider.providers.1.baseUrl", "https://cheap.example/v1/chat/completions");
        entries.put("yca.provider.providers.1.apiKey", "");
        entries.put("yca.provider.providers.1.model", "compact-model");
        entries.put("yca.provider.providers.1.api", "auto");
        entries.put("yca.provider.providers.1.contextWindow", "32000");
        entries.put("yca.provider.providers.1.timeoutSeconds", "120");
        return entries;
    }

    private static Map<String, String> usableWindowForModelWithoutAnyEstablishedWindowFallsBackToConservativeSettings(
            final Path storageHome) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", storageHome.toString());
        entries.put("yca.provider.providers.length", "1");
        entries.put("yca.provider.providers.0.id", "nodata");
        entries.put("yca.provider.providers.0.baseUrl", "https://nodata.example/v1/chat/completions");
        entries.put("yca.provider.providers.0.apiKey", "");
        entries.put("yca.provider.providers.0.model", "ghost");
        entries.put("yca.provider.providers.0.api", "auto");
        entries.put("yca.provider.providers.0.timeoutSeconds", "120");
        return entries;
    }

    private static Map<String, String> perModelOverrideIsNeverHalvedAndPersistedSettings(final Path storageHome) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", storageHome.toString());
        entries.put("yca.provider.providers.length", "1");
        entries.put("yca.provider.providers.0.id", "default");
        entries.put("yca.provider.providers.0.baseUrl", "http://localhost:0/v1/chat/completions");
        entries.put("yca.provider.providers.0.apiKey", "test-key");
        entries.put("yca.provider.providers.0.model", "test-model");
        entries.put("yca.provider.providers.0.api", "auto");
        entries.put("yca.provider.providers.0.timeoutSeconds", "5");
        entries.put("yca.provider.providers.0.models.length", "1");
        entries.put("yca.provider.providers.0.models.0.model", "test-model");
        entries.put("yca.provider.providers.0.models.0.contextWindow", "1000000");
        return entries;
    }

    private static Map<String, String> perModelContextWindowOverridesProviderAndDetectionSettings(
            final Path storageHome) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", storageHome.toString());
        entries.put("yca.provider.providers.length", "1");
        entries.put("yca.provider.providers.0.id", "default");
        entries.put("yca.provider.providers.0.baseUrl", "http://localhost:0/v1/chat/completions");
        entries.put("yca.provider.providers.0.apiKey", "test-key");
        entries.put("yca.provider.providers.0.model", "test-model");
        entries.put("yca.provider.providers.0.api", "auto");
        entries.put("yca.provider.providers.0.contextWindow", "32000");
        entries.put("yca.provider.providers.0.timeoutSeconds", "5");
        entries.put("yca.provider.providers.0.models.length", "1");
        entries.put("yca.provider.providers.0.models.0.model", "test-model");
        entries.put("yca.provider.providers.0.models.0.contextWindow", "1000000");
        return entries;
    }

    private static Map<String, String> liteLLMSettings(final Path storageHome, final String model) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", storageHome.toString());
        entries.put("yca.provider.providers.length", "1");
        entries.put("yca.provider.providers.0.id", "default");
        entries.put("yca.provider.providers.0.baseUrl", "http://localhost:0/v1/chat/completions");
        entries.put("yca.provider.providers.0.apiKey", "test-key");
        entries.put("yca.provider.providers.0.model", model);
        entries.put("yca.provider.providers.0.api", "auto");
        entries.put("yca.provider.providers.0.timeoutSeconds", "5");
        return entries;
    }

    private static ProviderConfiguration withEndpoint(final ProviderConfiguration baseline, final String endpoint) {
        return new ProviderConfiguration(
                baseline.id(),
                endpoint,
                baseline.apiKey(),
                baseline.model(),
                baseline.api(),
                baseline.contextWindow(),
                baseline.outputTokens(),
                baseline.releaseDate(),
                baseline.tuning(),
                baseline.models(),
                baseline.maxConcurrent(),
                baseline.timeoutSeconds(),
                baseline.providerSpecificOptions(),
                baseline.headers(),
                baseline.dynamicHeaders(),
                baseline.color(),
                baseline.prompt(),
                baseline.subagents(),
                baseline.backgroundModelOverrides(),
                baseline.compaction(),
                baseline.capabilities());
    }

    private static CodingAgentConfiguration withEndpoint(
            final CodingAgentConfiguration baseline, final String endpoint) {
        return new CodingAgentConfiguration(
                baseline.storageHome(),
                baseline.home(),
                baseline.workingDir(),
                baseline.projectDir(),
                new ProviderListConfiguration(
                        baseline.provider().providers().stream()
                                .map(provider -> provider.baseUrl().startsWith("http://localhost:")
                                        ? withEndpoint(provider, endpoint)
                                        : provider)
                                .toList(),
                        baseline.provider().defaultProvider(),
                        baseline.provider().baseUrl(),
                        baseline.provider().apiKey(),
                        baseline.provider().model(),
                        baseline.provider().api(),
                        baseline.provider().contextWindow(),
                        baseline.provider().outputTokens(),
                        baseline.provider().maxConcurrent(),
                        baseline.provider().timeoutSeconds(),
                        baseline.provider().color()),
                baseline.model(),
                baseline.llm(),
                baseline.compaction(),
                baseline.pruning(),
                baseline.compression(),
                baseline.memory(),
                baseline.todos(),
                baseline.optimize(),
                baseline.permissions(),
                baseline.sandbox(),
                baseline.mcp(),
                baseline.mavenRepositoryKnowledge(),
                baseline.structuralCodeGraph(),
                baseline.sessions(),
                baseline.skills(),
                baseline.agents(),
                baseline.mode(),
                baseline.httpClient(),
                baseline.tools(),
                baseline.subagents(),
                baseline.schedulerThreads(),
                baseline.metrics(),
                baseline.maxLineLength(),
                baseline.resumeAliases(),
                baseline.slashcommands(),
                baseline.prompt(),
                baseline.labels(),
                baseline.logging(),
                baseline.hooks(),
                baseline.notifications(),
                baseline.secrets(),
                null);
    }

    public static class ConfiguredWindowWinsModule extends ConfigurationTestModule {
        public ConfiguredWindowWinsModule() {
            super(usageSettings(ConfiguredWindowWins.storageHome, 32000, -1));
        }
    }

    public static class PerModelContextWindowOverridesProviderAndDetectionModule extends ConfigurationTestModule {
        public PerModelContextWindowOverridesProviderAndDetectionModule() {
            super(perModelContextWindowOverridesProviderAndDetectionSettings(
                    PerModelContextWindowOverridesProviderAndDetection.storageHome));
        }
    }

    public static class PerModelOverrideIsNeverHalvedAndPersistedModule extends ConfigurationTestModule {
        public PerModelOverrideIsNeverHalvedAndPersistedModule() {
            super(perModelOverrideIsNeverHalvedAndPersistedSettings(
                    PerModelOverrideIsNeverHalvedAndPersisted.storageHome));
        }
    }

    public static class FullInputAccumulatesWithoutChangingRawPromptBillingModule extends ConfigurationTestModule {
        public FullInputAccumulatesWithoutChangingRawPromptBillingModule() {
            super(usageSettings(FullInputAccumulatesWithoutChangingRawPromptBilling.storageHome, -1, -1));
        }
    }

    public static class AuxiliaryFullInputIncludesCacheWritesAndReadsWithoutChangingBillingModule
            extends ConfigurationTestModule {
        public AuxiliaryFullInputIncludesCacheWritesAndReadsWithoutChangingBillingModule() {
            super(usageSettings(
                    AuxiliaryFullInputIncludesCacheWritesAndReadsWithoutChangingBilling.storageHome, -1, -1));
        }
    }

    public static class AuxiliaryFreshInputCannotBeNegativeModule extends ConfigurationTestModule {
        public AuxiliaryFreshInputCannotBeNegativeModule() {
            super(usageSettings(AuxiliaryFreshInputCannotBeNegative.storageHome, -1, -1));
        }
    }

    public static class AuxiliarySpendIsCountedGloballyAndSeparatelyModule extends ConfigurationTestModule {
        public AuxiliarySpendIsCountedGloballyAndSeparatelyModule() {
            super(usageSettings(AuxiliarySpendIsCountedGloballyAndSeparately.storageHome, -1, -1));
        }
    }

    public static class AuxiliaryFreshTokensSubtractTheAuxCacheReadModule extends ConfigurationTestModule {
        public AuxiliaryFreshTokensSubtractTheAuxCacheReadModule() {
            super(usageSettings(AuxiliaryFreshTokensSubtractTheAuxCacheRead.storageHome, -1, -1));
        }
    }

    public static class LlamaCppRuntimeContextIsReadModule extends ConfigurationTestModule {
        public LlamaCppRuntimeContextIsReadModule() {
            super(usageSettings(LlamaCppRuntimeContextIsRead.storageHome, -1, -1));
        }
    }

    public static class StaleCacheTriggersRefetchAndRefreshModule extends ConfigurationTestModule {
        public StaleCacheTriggersRefetchAndRefreshModule() {
            super(usageSettings(StaleCacheTriggersRefetchAndRefresh.storageHome, -1, -1));
        }
    }

    public static class FreshCacheSkipsModelsCallModule extends ConfigurationTestModule {
        public FreshCacheSkipsModelsCallModule() {
            super(usageSettings(FreshCacheSkipsModelsCall.storageHome, -1, -1));
        }
    }

    public static class OverflowAdjustedCacheIsNotAuthoritativeOverFreshDetectionModule
            extends ConfigurationTestModule {
        public OverflowAdjustedCacheIsNotAuthoritativeOverFreshDetectionModule() {
            super(usageSettings(OverflowAdjustedCacheIsNotAuthoritativeOverFreshDetection.storageHome, -1, -1));
        }
    }

    public static class OverflowAdjustedCacheIsReportedAsAutoWhenDetectionFailsModule extends ConfigurationTestModule {
        public OverflowAdjustedCacheIsReportedAsAutoWhenDetectionFailsModule() {
            super(usageSettings(OverflowAdjustedCacheIsReportedAsAutoWhenDetectionFails.storageHome, -1, -1));
        }
    }

    public static class LastResolutionOriginReflectsTheLatestResolutionModule extends ConfigurationTestModule {
        public LastResolutionOriginReflectsTheLatestResolutionModule() {
            super(usageSettings(LastResolutionOriginReflectsTheLatestResolution.storageHome, -1, -1));
        }
    }

    public static class OverflowAdjustedCacheIsNotAuthoritativeForBackgroundModelsModule
            extends ConfigurationTestModule {
        public OverflowAdjustedCacheIsNotAuthoritativeForBackgroundModelsModule() {
            super(usageSettings(OverflowAdjustedCacheIsNotAuthoritativeForBackgroundModels.storageHome, -1, -1));
        }
    }

    public static class MissingVendorValueFallsBackToAutoBaselineModule extends ConfigurationTestModule {
        public MissingVendorValueFallsBackToAutoBaselineModule() {
            super(usageSettings(MissingVendorValueFallsBackToAutoBaseline.storageHome, -1, -1));
        }
    }

    public static class ModelsFailureFallsBackToAutoBaselineModule extends ConfigurationTestModule {
        public ModelsFailureFallsBackToAutoBaselineModule() {
            super(usageSettings(ModelsFailureFallsBackToAutoBaseline.storageHome, -1, -1));
        }
    }

    public static class ModelsFailureIsCoalescedOnSubsequentResolutionsModule extends ConfigurationTestModule {
        public ModelsFailureIsCoalescedOnSubsequentResolutionsModule() {
            super(usageSettings(ModelsFailureIsCoalescedOnSubsequentResolutions.storageHome, -1, -1));
        }
    }

    public static class EffectiveContextFallsBackToRawEstimateWithoutAnchorModule extends ConfigurationTestModule {
        public EffectiveContextFallsBackToRawEstimateWithoutAnchorModule() {
            super(usageSettings(EffectiveContextFallsBackToRawEstimateWithoutAnchor.storageHome, 100_000, -1));
        }
    }

    public static class AnchorMeasuresUnchangedRequestAndItsVerifiedExtensionModule extends ConfigurationTestModule {
        public AnchorMeasuresUnchangedRequestAndItsVerifiedExtensionModule() {
            super(usageSettings(AnchorMeasuresUnchangedRequestAndItsVerifiedExtension.storageHome, 100_000, -1));
        }
    }

    public static class RewrittenContextRetainsCalibrationButNotExactAnchorModule extends ConfigurationTestModule {
        public RewrittenContextRetainsCalibrationButNotExactAnchorModule() {
            super(usageSettings(RewrittenContextRetainsCalibrationButNotExactAnchor.storageHome, 100_000, -1));
        }
    }

    public static class MissingUsageDoesNotInventAnObservationModule extends ConfigurationTestModule {
        public MissingUsageDoesNotInventAnObservationModule() {
            super(usageSettings(MissingUsageDoesNotInventAnObservation.storageHome, 100_000, -1));
        }
    }

    public static class UsableWindowReservesCompletionHeadroomModule extends ConfigurationTestModule {
        public UsableWindowReservesCompletionHeadroomModule() {
            super(usageSettings(UsableWindowReservesCompletionHeadroom.storageHome, 100_000, -1));
        }
    }

    public static class SmallWindowReservesCompletionHeadroomModule extends ConfigurationTestModule {
        public SmallWindowReservesCompletionHeadroomModule() {
            super(usageSettings(SmallWindowReservesCompletionHeadroom.storageHome, 4_096, -1));
        }
    }

    public static class TopProviderOutputCapIsReservedFromTheDetectedWindowModule extends ConfigurationTestModule {
        public TopProviderOutputCapIsReservedFromTheDetectedWindowModule() {
            super(usageSettings(TopProviderOutputCapIsReservedFromTheDetectedWindow.storageHome, -1, -1));
        }
    }

    public static class UsableWindowForModelUsesTheReferencedModelWindowModule extends ConfigurationTestModule {
        public UsableWindowForModelUsesTheReferencedModelWindowModule() {
            super(multiWindowSettings(UsableWindowForModelUsesTheReferencedModelWindow.storageHome));
        }
    }

    public static class UsableWindowForModelBlankFallsBackToSessionWindowModule extends ConfigurationTestModule {
        public UsableWindowForModelBlankFallsBackToSessionWindowModule() {
            super(multiWindowSettings(UsableWindowForModelBlankFallsBackToSessionWindow.storageHome));
        }
    }

    public static class UsableWindowForModelWithoutAnyEstablishedWindowFallsBackToConservativeModule
            extends ConfigurationTestModule {
        public UsableWindowForModelWithoutAnyEstablishedWindowFallsBackToConservativeModule() {
            super(usableWindowForModelWithoutAnyEstablishedWindowFallsBackToConservativeSettings(
                    UsableWindowForModelWithoutAnyEstablishedWindowFallsBackToConservative.storageHome));
        }
    }

    public static class EqualLiteLLMCapsAreNotATrustedPartition1Module extends ConfigurationTestModule {
        public EqualLiteLLMCapsAreNotATrustedPartition1Module() {
            super(liteLLMSettings(EqualLiteLLMCapsAreNotATrustedPartition1.storageHome, "qwen3.8-27b"));
        }
    }

    public static class EqualLiteLLMCapsAreNotATrustedPartition2Module extends ConfigurationTestModule {
        public EqualLiteLLMCapsAreNotATrustedPartition2Module() {
            super(liteLLMSettings(EqualLiteLLMCapsAreNotATrustedPartition2.storageHome, "gemma4-26b"));
        }
    }

    public static class EqualLiteLLMCapsAreNotATrustedPartition3Module extends ConfigurationTestModule {
        public EqualLiteLLMCapsAreNotATrustedPartition3Module() {
            super(liteLLMSettings(EqualLiteLLMCapsAreNotATrustedPartition3.storageHome, "qwen3-8b"));
        }
    }

    public static class ConfiguredOutputTokensIsReservedFromAConfiguredWindowModule extends ConfigurationTestModule {
        public ConfiguredOutputTokensIsReservedFromAConfiguredWindowModule() {
            super(usageSettings(ConfiguredOutputTokensIsReservedFromAConfiguredWindow.storageHome, 200_000, 8_000));
        }
    }

    public static class ConfiguredOutputTokensWinsOverTheDetectedCapModule extends ConfigurationTestModule {
        public ConfiguredOutputTokensWinsOverTheDetectedCapModule() {
            super(usageSettings(ConfiguredOutputTokensWinsOverTheDetectedCap.storageHome, -1, 4_000));
        }
    }

    public static class OutputCapNotSmallerThanTheWindowFallsBackToTheHeuristicModule extends ConfigurationTestModule {
        public OutputCapNotSmallerThanTheWindowFallsBackToTheHeuristicModule() {
            super(usageSettings(OutputCapNotSmallerThanTheWindowFallsBackToTheHeuristic.storageHome, 4_096, 8_192));
        }
    }

    public static class ContextPercentIsComputedAgainstTheUsableWindowModule extends ConfigurationTestModule {
        public ContextPercentIsComputedAgainstTheUsableWindowModule() {
            super(usageSettings(ContextPercentIsComputedAgainstTheUsableWindow.storageHome, 100_000, -1));
        }
    }

    public static class AuthFailureDoesNotSuppressDetectionAfterRecoveryModule extends ConfigurationTestModule {
        public AuthFailureDoesNotSuppressDetectionAfterRecoveryModule() {
            super(usageSettings(AuthFailureDoesNotSuppressDetectionAfterRecovery.storageHome, -1, -1));
        }
    }

    public static class DetectionFailureBacksOffBrieflyModule extends ConfigurationTestModule {
        public DetectionFailureBacksOffBrieflyModule() {
            super(usageSettings(DetectionFailureBacksOffBriefly.storageHome, -1, -1));
        }
    }

    public static class StaleCacheIsUsedWhenDetectionFailsModule extends ConfigurationTestModule {
        public StaleCacheIsUsedWhenDetectionFailsModule() {
            super(usageSettings(StaleCacheIsUsedWhenDetectionFails.storageHome, -1, -1));
        }
    }

    public static class DetectionUsesTheSessionProviderSettingsModule extends ConfigurationTestModule {
        public DetectionUsesTheSessionProviderSettingsModule() {
            super(usageSettings(DetectionUsesTheSessionProviderSettings.storageHome, -1, -1));
        }
    }
}
