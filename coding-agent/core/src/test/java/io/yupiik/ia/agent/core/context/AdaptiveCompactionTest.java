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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.PauseEvent;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.ContextWindowExceededException;
import io.yupiik.llm.client.LlmHttpFailureException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Behavior of the adaptive compaction chunking ({@code yca.compaction.summarizer*}): the chunk cap
 * derives from the compact model's usable window (floored at the legacy 12k), a size-caused
 * summarizer failure halves the cap and retries, non-size failures never halve, and both failure
 * paths degrade honestly to local truncation instead of looping.
 */
@ExtendWith(TestJsonMapper.class)
class AdaptiveCompactionTest {
    @TempDir
    private static Path testWorkdir;

    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    private static Map<String, String> configSettings(final int window, final Map<String, String> extra) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", "x");
        entries.put("yca.workingDir", ".");
        entries.put("yca.provider.providers.length", "1");
        entries.put("yca.provider.providers.0.id", "default");
        entries.put("yca.provider.providers.0.baseUrl", "https://api.openai.com/v1/chat/completions");
        entries.put("yca.provider.providers.0.apiKey", "");
        entries.put("yca.provider.providers.0.model", "m");
        entries.put("yca.provider.providers.0.api", "auto");
        entries.put("yca.provider.providers.0.contextWindow", String.valueOf(window));
        entries.put("yca.provider.providers.0.timeoutSeconds", "120");
        entries.put("yca.compaction.tail.budgetTokens", "1");
        entries.put("yca.pruning.keepRecent", "1");
        entries.putAll(extra);
        return entries;
    }

    private ProviderManager providerManager(final CodingAgentConfiguration cfg) {
        return new ProviderManager(cfg, mapper);
    }

    private BackoffSleeper noOpSleeper() {
        return new BackoffSleeper() {
            @Override
            public boolean sleep(final long millis) {
                return true;
            }
        };
    }

    private CompactionCoordinator coordinator(final CodingAgentConfiguration cfg, final ChatClient chat) {
        return new CompactionCoordinator(
                cfg,
                chat,
                new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                providerManager(cfg),
                null,
                "s1",
                noOpSleeper(),
                (cfg).resolveWorkingDir());
    }

    /** A history whose old part is heavy and the retained tail is tiny: compaction must summarize the old part. */
    private static List<Message> oldHeavyHistory(final int oldSteps, final int charsPerStep) {
        final var messages = new ArrayList<Message>();
        messages.add(Message.system("system"));
        for (var i = 0; i < oldSteps; i++) {
            messages.add(Message.user("step " + i + " " + "x".repeat(charsPerStep)));
        }
        messages.add(Message.user("tail"));
        return messages;
    }

    private static String promptOf(final ChatCompletionRequest request) {
        return request.messages().stream()
                .filter(m -> "user".equals(m.role()))
                .map(m -> String.valueOf(m.content()))
                .findFirst()
                .orElse("");
    }

    /** A two-provider config: 'main' (large window) plus a cheaper 'compact' provider (smaller window). */
    private static Map<String, String> twoProviderConfigSettings(final int mainWindow, final int compactWindow) {
        return twoProviderConfigSettings(mainWindow, compactWindow, Map.of());
    }

    /** Like {@link #twoProviderConfig(int, int)} with extra configuration entries applied last. */
    private static Map<String, String> twoProviderConfigSettings(
            final int mainWindow, final int compactWindow, final Map<String, String> extra) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", "x");
        entries.put("yca.workingDir", ".");
        entries.put("yca.provider.providers.length", "2");
        entries.put("yca.provider.providers.0.id", "main");
        entries.put("yca.provider.providers.0.baseUrl", "https://main.example/v1/chat/completions");
        entries.put("yca.provider.providers.0.apiKey", "");
        entries.put("yca.provider.providers.0.model", "main-model");
        entries.put("yca.provider.providers.0.api", "auto");
        entries.put("yca.provider.providers.0.contextWindow", String.valueOf(mainWindow));
        entries.put("yca.provider.providers.0.timeoutSeconds", "120");
        entries.put("yca.provider.providers.0.backgroundModelOverrides.compact.name", "$compact/mini");
        entries.put("yca.provider.providers.1.id", "compact");
        entries.put("yca.provider.providers.1.baseUrl", "https://compact.example/v1/chat/completions");
        entries.put("yca.provider.providers.1.apiKey", "");
        entries.put("yca.provider.providers.1.model", "mini");
        entries.put("yca.provider.providers.1.api", "auto");
        entries.put("yca.provider.providers.1.contextWindow", String.valueOf(compactWindow));
        entries.put("yca.provider.providers.1.timeoutSeconds", "120");
        entries.put("yca.compaction.tail.budgetTokens", "1");
        entries.put("yca.pruning.keepRecent", "1");
        entries.putAll(extra);
        return entries;
    }

    // --- derivation -------------------------------------------------------

    @Test
    void nullConfigFallsBackToDefaults() {
        final var coordinator =
                new CompactionCoordinator(null, null, null, null, null, "s1", noOpSleeper(), testWorkdir);
        assertEquals(12_000, coordinator.effectiveChunkCap(), "null config must use the legacy floor");
        assertEquals(6_000, coordinator.effectiveDigestCap());
        assertEquals(2_000, coordinator.effectiveToolOutputChars());
        assertEquals(500, coordinator.effectiveSpliceChars());
    }

    // --- chunk flattening --------------------------------------------------

    // --- halving escalation -------------------------------------------------

    @Nested
    @FusionSupport(modules = DerivationRespectsTheConfiguredFloorModule.class)
    class DerivationRespectsTheConfiguredFloor {
        @Test
        void derivationRespectsTheConfiguredFloor(@Fusion final CodingAgentConfiguration configuration) {
            // 16k window -> usable ~12k tokens -> chunk cap (usable chars, i.e. 1/4-window at 4 chars/token)
            // is 12k chars, floored at the configured 12k minimum; digest = half the chunk cap.
            final var cfg = configuration;
            final var coordinator = coordinator(cfg, null);
            assertEquals(12_000, coordinator.effectiveChunkCap(), "the floor must clamp the derived cap");
            assertEquals(6_000, coordinator.effectiveDigestCap(), "digest auto = half the chunk cap");
            assertEquals(2_000, coordinator.effectiveToolOutputChars());
            assertEquals(500, coordinator.effectiveSpliceChars());
        }
    }

    public static class DerivationRespectsTheConfiguredFloorModule extends ConfigurationTestModule {
        public DerivationRespectsTheConfiguredFloorModule() {
            super(configSettings(16_000, Map.of()));
        }
    }

    @Nested
    @FusionSupport(modules = ExplicitChunkCharsWinsOverTheDerivationModule.class)
    class ExplicitChunkCharsWinsOverTheDerivation {
        @Test
        void explicitChunkCharsWinsOverTheDerivation(@Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var coordinator = coordinator(cfg, null);
            assertEquals(5_000, coordinator.effectiveChunkCap(), "an explicit cap must win over the window derivation");
            assertEquals(2_500, coordinator.effectiveDigestCap(), "digest auto = half the explicit cap");
        }
    }

    public static class ExplicitChunkCharsWinsOverTheDerivationModule extends ConfigurationTestModule {
        public ExplicitChunkCharsWinsOverTheDerivationModule() {
            super(configSettings(128_000, Map.of("yca.compaction.summarizer.chunkChars", "5000")));
        }
    }

    @Nested
    @FusionSupport(modules = ChunkCompactHonorsTheEffectiveCapModule.class)
    class ChunkCompactHonorsTheEffectiveCap {
        @Test
        void chunkCompactHonorsTheEffectiveCap(@Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var coordinator = coordinator(cfg, null);
            final var messages = new ArrayList<Message>();
            for (var i = 0; i < 10; i++) {
                messages.add(Message.user("step " + i + " " + "x".repeat(50)));
            }
            final var chunks = coordinator.chunkCompact(messages, coordinator.effectiveChunkCap());
            assertTrue(chunks.size() >= 2, "a 10x50 history must be split with a 100-char cap");
            for (final var chunk : chunks) {
                assertTrue(
                        chunk.length() <= 100 + 60,
                        "each chunk must respect the cap (a single oversized line can exceed it)");
            }
            // a single oversized line is its own chunk
            final var one = new ArrayList<Message>();
            one.add(Message.user("x".repeat(500)));
            final var single = coordinator.chunkCompact(one, 100);
            assertEquals(1, single.size());
            assertTrue(single.get(0).length() > 100, "an oversized single message stays one chunk");
        }
    }

    public static class ChunkCompactHonorsTheEffectiveCapModule extends ConfigurationTestModule {
        public ChunkCompactHonorsTheEffectiveCapModule() {
            super(configSettings(128_000, Map.of("yca.compaction.summarizer.chunkChars", "100")));
        }
    }

    @Nested
    @FusionSupport(modules = HalvingModule.class)
    class Halving {
        @Test
        void sizeCausedFailureHalvesTheCapAndRetriesToSuccess(@Fusion final CodingAgentConfiguration configuration) {
            final var calls = new ArrayList<Integer>();
            final var cfg = configuration;
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled)
                        throws IOException {
                    calls.add(promptOf(request).length());
                    if (calls.size() == 1) {
                        // the first attempt is rejected as too large: the cap must be halved and retried
                        throw new ContextWindowExceededException();
                    }
                    return new ChatResult(Message.assistant("digest: recovered", null), Usage.EMPTY);
                }
            };
            final var manager = new ContextManager(
                    cfg, new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()), coordinator(cfg, chat));
            final var result = manager.compact(oldHeavyHistory(60, 200), true);
            assertEquals(CompactionResult.Mode.SUMMARY, result.mode());
            assertTrue(calls.size() >= 2, "one overflow + a retry with the halved cap: " + calls.size() + " calls");
            final var first = calls.get(0);
            assertTrue(
                    calls.get(1) < first,
                    "the first retried chunk must be smaller than the overflowing one (first=" + first + ", retry="
                            + calls.get(1) + ")");
        }

        @Test
        void halvingStopsAtTheFloorAndDegradesToTruncation(@Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled)
                        throws IOException {
                    // always rejected as too large: halving must hit the floor and stop
                    throw new ContextWindowExceededException();
                }
            };
            final var manager = new ContextManager(
                    cfg, new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()), coordinator(cfg, chat));
            final var result = manager.compact(oldHeavyHistory(6, 4_000), true);
            assertEquals(CompactionResult.Mode.TRUNCATE, result.mode());
            assertTrue(
                    result.error() != null && result.error().contains("context window"),
                    "the truncation fallback must report the size cause");
        }

        @Test
        void noLoopOnPersistentSizeFailures(@Fusion final CodingAgentConfiguration configuration) {
            final var attempts = new AtomicInteger();
            final var cfg = configuration;
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled)
                        throws IOException {
                    attempts.incrementAndGet();
                    throw new ContextWindowExceededException();
                }
            };
            final var manager = new ContextManager(
                    cfg, new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()), coordinator(cfg, chat));
            final var result = manager.compact(oldHeavyHistory(6, 4_000), true);
            assertEquals(CompactionResult.Mode.TRUNCATE, result.mode());
            // 4000 -> 2000 -> 1000 (floor): 3 attempts, then stop. Never 4+.
            assertTrue(
                    attempts.get() <= 3 && attempts.get() >= 2,
                    "halving must be bounded by log2(initial/floor): was " + attempts.get());
        }

        /**
         * A server-side 400 with a context-length body is the HTTP mirror of a client-side
         * {@link ContextWindowExceededException}: it must halve the chunk cap and retry, not burn the
         * retry budget and skip keep-raw (which is what stuck a heavy context above its budget).
         */
        @Test
        void httpContextLengthRejectionHalvesTheCapAndRetriesToSuccess(
                @Fusion final CodingAgentConfiguration configuration) {
            final var calls = new ArrayList<Integer>();
            final var cfg = configuration;
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled)
                        throws IOException {
                    calls.add(promptOf(request).length());
                    if (calls.size() == 1) {
                        // the first attempt is rejected by the server as over the model's context length
                        throw new LlmHttpFailureException(
                                400, "This model's maximum context length is X tokens.", null);
                    }
                    return new ChatResult(Message.assistant("digest: recovered", null), Usage.EMPTY);
                }
            };
            final var manager = new ContextManager(
                    cfg, new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()), coordinator(cfg, chat));
            final var result = manager.compact(oldHeavyHistory(60, 200), true);
            assertEquals(CompactionResult.Mode.SUMMARY, result.mode());
            assertTrue(calls.size() >= 2, "one 400 + a retry with the halved cap: " + calls.size() + " calls");
            assertTrue(
                    calls.get(1) < calls.get(0),
                    "the retried chunk must be smaller after the context-length 400 (first=" + calls.get(0) + ", retry="
                            + calls.get(1) + ")");
        }
    }

    public static class HalvingModule extends ConfigurationTestModule {
        public HalvingModule() {
            super(configSettings(
                    128_000,
                    Map.of(
                            "yca.compaction.summarizer.chunkChars", "4000",
                            "yca.compaction.summarizer.minChunkChars", "1000")));
        }
    }

    @Nested
    @FusionSupport(modules = TransientRetriesModule.class)
    class TransientRetries {
        @Test
        void nonSizeFailureNeverHalvesRetriesThenSkipsKeepRaw(@Fusion final CodingAgentConfiguration configuration) {
            final var attempts = new AtomicInteger();
            final var cfg = configuration;
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled)
                        throws IOException {
                    attempts.incrementAndGet();
                    throw new IOException("upstream 500");
                }
            };
            final var history = oldHeavyHistory(6, 4_000);
            final var manager = new ContextManager(
                    cfg, new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()), coordinator(cfg, chat));
            final var result = manager.compact(history, true);
            assertEquals(CompactionResult.Mode.NONE, result.mode(), "transient failures must skip, not truncate");
            assertEquals(2, attempts.get(), "non-size failures use the configured retries, no halving");
            assertEquals(history, result.messages(), "a skipped compaction must keep the raw context verbatim");
            assertTrue(
                    result.error() != null && result.error().contains("upstream 500"),
                    "the skip fallback must report the real cause");
        }

        /** A 5xx LlmHttpFailureException stays transient: it never halves, it retries then skips keep-raw. */
        @Test
        void httpServerErrorSkipsKeepRawWithoutHalving(@Fusion final CodingAgentConfiguration configuration) {
            final var attempts = new AtomicInteger();
            final var cfg = configuration;
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled)
                        throws IOException {
                    attempts.incrementAndGet();
                    throw new LlmHttpFailureException(500, "Internal Server Error", null);
                }
            };
            final var history = oldHeavyHistory(6, 4_000);
            final var manager = new ContextManager(
                    cfg, new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()), coordinator(cfg, chat));
            final var result = manager.compact(history, true);
            assertEquals(CompactionResult.Mode.NONE, result.mode(), "5xx must skip, not halve/truncate");
            assertEquals(2, attempts.get(), "5xx uses the configured retries, no halving");
            assertEquals(history, result.messages(), "a skipped compaction must keep the raw context verbatim");
        }
    }

    public static class TransientRetriesModule extends ConfigurationTestModule {
        public TransientRetriesModule() {
            super(configSettings(
                    128_000,
                    Map.of(
                            "yca.compaction.summarizer.chunkChars", "4000",
                            "yca.compaction.retriesMaxAttempts", "2")));
        }
    }

    @Nested
    @FusionSupport(modules = FailedSummaryWithNoOpLocalTruncationReportsNoChangeModule.class)
    class FailedSummaryWithNoOpLocalTruncationReportsNoChange {
        @Test
        void failedSummaryWithNoOpLocalTruncationReportsNoChange(@Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled)
                        throws IOException {
                    throw new IOException("upstream unavailable");
                }
            };
            final var manager = new ContextManager(
                    cfg, new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()), coordinator(cfg, chat));
            final var messages = oldHeavyHistory(6, 100);

            final var result = manager.compact(messages, true);

            assertEquals(CompactionResult.Mode.NONE, result.mode());
            assertFalse(result.changed(), "a fallback that leaves all messages unchanged must not claim progress");
            assertEquals(messages, result.messages());
            assertTrue(result.error().contains("upstream unavailable"), "retain the original summary failure");
        }
    }

    public static class FailedSummaryWithNoOpLocalTruncationReportsNoChangeModule extends ConfigurationTestModule {
        public FailedSummaryWithNoOpLocalTruncationReportsNoChangeModule() {
            super(configSettings(
                    128_000,
                    Map.of(
                            "yca.compaction.summarizer.chunkChars", "4000",
                            "yca.compaction.tail.budgetTokens", "1",
                            "yca.pruning.toolOutputMaxChars", "20000")));
        }
    }

    @Nested
    @FusionSupport(modules = SingleCallSucceedsByDefaultOnALargeWindowModule.class)
    class SingleCallSucceedsByDefaultOnALargeWindow {
        @Test
        void singleCallSucceedsByDefaultOnALargeWindow(@Fusion final CodingAgentConfiguration configuration) {
            final var calls = new AtomicInteger();
            final var cfg = configuration;
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled) {
                    calls.incrementAndGet();
                    return new ChatResult(Message.assistant("digest: single", null), Usage.EMPTY);
                }
            };
            final var manager = new ContextManager(
                    cfg, new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()), coordinator(cfg, chat));
            final var result = manager.compact(oldHeavyHistory(6, 4_000), true);
            assertEquals(CompactionResult.Mode.SUMMARY, result.mode());
            assertEquals(1, calls.get(), "a large window must compact in a single summarizer call by default");
        }
    }

    public static class SingleCallSucceedsByDefaultOnALargeWindowModule extends ConfigurationTestModule {
        public SingleCallSucceedsByDefaultOnALargeWindowModule() {
            super(configSettings(128_000, Map.of()));
        }
    }

    @Nested
    @FusionSupport(modules = CompactModelWindowModule.class)
    class CompactModelWindow {
        @Test
        void halfWindowSummarizerSizesChunksAgainstItsOwnWindow(@Fusion final CodingAgentConfiguration configuration) {
            // the summarizer (compact provider, 16k window) is smaller than the conversation model
            // (main, 128k): the derived chunk cap must be sized against the compact window, not the main
            final var cfg = configuration;
            final var coordinator = coordinator(cfg, null);
            // main usable == 128k - min(20k,128k/4)=20k -> 108k; if the bug were present the cap would
            // derive from 108k (~ 27k chars). The compact window 16k - min(20k,16k/4)=4k -> 12k usable,
            // floored at the 12k minimum, derives ~12k chars.
            assertEquals(
                    12_000,
                    coordinator.effectiveChunkCap("$compact/mini"),
                    "the cap must derive from the compact model window");
            assertEquals(6_000, coordinator.effectiveDigestCap("$compact/mini"), "digest auto = half the chunk cap");
        }

        @Test
        void halfWindowSummarizerCompactsSingleCallAgainstItsOwnWindow(
                @Fusion final CodingAgentConfiguration configuration) {
            // end-to-end: with the summarizer sized to its own (smaller) window, a history that fits one
            // chunk completes in a single summarizer call with no halving retry (no ContextWindowExceededException)
            final var calls = new AtomicInteger();
            final var cfg = configuration;
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled) {
                    calls.incrementAndGet();
                    return new ChatResult(Message.assistant("digest: single", null), Usage.EMPTY);
                }
            };
            final var coordinator = coordinator(cfg, chat);
            final var manager = new ContextManager(
                    cfg, new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()), coordinator);
            final var result = manager.compact(oldHeavyHistory(6, 500), true);
            assertEquals(CompactionResult.Mode.SUMMARY, result.mode());
            assertEquals(1, calls.get(), "a history fitting one compact-window chunk must not overflow/halve");
        }
    }

    public static class CompactModelWindowModule extends ConfigurationTestModule {
        public CompactModelWindowModule() {
            super(twoProviderConfigSettings(128_000, 16_000));
        }
    }

    @Nested
    @FusionSupport(modules = PerProviderRetriesAndBackoffOverrideGlobalsModule.class)
    class PerProviderRetriesAndBackoffOverrideGlobals {
        /** Per-provider compaction.retriesMaxAttempts overrides the global; unset inherits it. */
        @Test
        void perProviderRetriesAndBackoffOverrideGlobals(@Fusion final CodingAgentConfiguration configuration) {
            // provider-level: retries=5, baseDelay=3000, rateLimit=120 -> backoff(0) >= 3000
            final var cfg = configuration;
            final var attempts = new AtomicInteger();
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled)
                        throws IOException {
                    attempts.incrementAndGet();
                    throw new IOException("500 transient");
                }
            };
            final var manager = new ContextManager(
                    cfg, new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()), coordinator(cfg, chat));
            final var result = manager.compact(oldHeavyHistory(6, 300), true);
            // per-provider retries=5, not the global 2: the summarizer attempts 5 times then skips
            assertEquals(5, attempts.get(), "per-provider retriesMaxAttempts must win over the global");
            assertEquals(CompactionResult.Mode.NONE, result.mode(), "transient failure must skip, not truncate");
        }
    }

    public static class PerProviderRetriesAndBackoffOverrideGlobalsModule extends ConfigurationTestModule {
        public PerProviderRetriesAndBackoffOverrideGlobalsModule() {
            super(configSettings(
                    128_000,
                    Map.of(
                            "yca.compaction.summarizer.chunkChars", "2000",
                            "yca.compaction.summarizer.minChunkChars", "1000",
                            "yca.llm.retriesMaxAttempts", "2",
                            "yca.llm.retriesBaseDelayMs", "500",
                            "yca.llm.rateLimitRequestsPerMinute", "0",
                            "yca.provider.providers.0.compaction.retriesMaxAttempts", "5",
                            "yca.provider.providers.0.compaction.retriesBaseDelayMs", "3000",
                            "yca.provider.providers.0.compaction.rateLimitRequestsPerMinute", "120")));
        }
    }

    @Nested
    @FusionSupport(modules = TransientSummarizerFailureSkipsInsteadOfTruncatingModule.class)
    class TransientSummarizerFailureSkipsInsteadOfTruncating {
        /** Transient 5xx after all retries keeps the raw context (Mode.NONE), never local-truncates. */
        @Test
        void transientSummarizerFailureSkipsInsteadOfTruncating(@Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled)
                        throws IOException {
                    throw new IOException("500 transient");
                }
            };
            final var history = oldHeavyHistory(6, 4_000);
            final var coordinator = coordinator(cfg, chat);
            final var manager = new ContextManager(
                    cfg, new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()), coordinator);
            final var result = manager.compact(history, true);
            assertEquals(CompactionResult.Mode.NONE, result.mode(), "transient failures must skip, not truncate");
            assertEquals(
                    history,
                    result.messages(),
                    "when compaction is skipped the raw context must be preserved verbatim");
            assertTrue(
                    result.error().contains("skipped"), "the result must surface the skip reason: " + result.error());
        }
    }

    public static class TransientSummarizerFailureSkipsInsteadOfTruncatingModule extends ConfigurationTestModule {
        public TransientSummarizerFailureSkipsInsteadOfTruncatingModule() {
            super(configSettings(128_000, Map.of("yca.compaction.summarizer.chunkChars", "2000")));
        }
    }

    @Nested
    @FusionSupport(modules = FallbackEnabledModule.class)
    class FallbackEnabled {
        /** With fallbackToMainModel enabled (global toggle), a transient 5xx retries once on the main model. */
        @Test
        void transientFailureFallsBackToMainModelWhenEnabled(@Fusion final CodingAgentConfiguration configuration) {
            final var calls = new ArrayList<String>();
            final var cfg = configuration;
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled)
                        throws IOException {
                    calls.add(request.model());
                    // fails on the compact model ($compact/mini), succeeds on the main ($main/main-model)
                    if ("$main/main-model".equals(request.model())) {
                        return new ChatResult(Message.assistant("digest: fallback", null), Usage.EMPTY);
                    }

                    throw new IOException("500 transient");
                }
            };
            final var manager = new ContextManager(
                    cfg, new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()), coordinator(cfg, chat));
            final var result = manager.compact(oldHeavyHistory(6, 300), true);
            assertEquals(
                    CompactionResult.Mode.SUMMARY,
                    result.mode(),
                    "fallback must produce a summary; error=" + result.error() + " calls=" + calls);
            assertTrue(
                    calls.stream().anyMatch("$main/main-model"::equals),
                    "the main model must be tried after the compact model fails: " + calls);
        }

        @Test
        void providerFallbackOverrideAbsent(@Fusion final CodingAgentConfiguration configuration) {
            final var override = "absent";
            final var cfg = configuration;
            final var calls = new ArrayList<String>();
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled)
                        throws IOException {
                    calls.add(request.model());
                    if ("$main/main-model".equals(request.model())) {
                        return new ChatResult(Message.assistant("digest", null), Usage.EMPTY);
                    }
                    throw new IOException("500 transient");
                }
            };
            final var manager = new ContextManager(
                    cfg, new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()), coordinator(cfg, chat));
            manager.compact(oldHeavyHistory(6, 300), true);
            assertEquals(!"false".equals(override), calls.contains("$main/main-model"), override + ": " + calls);
        }
    }

    public static class FallbackEnabledModule extends ConfigurationTestModule {
        public FallbackEnabledModule() {
            super(twoProviderConfigSettings(128_000, 16_000, Map.of("yca.compaction.fallbackToMainModel", "true")));
        }
    }

    @Nested
    @FusionSupport(modules = ProviderFallbackOverrideTrueModule.class)
    class ProviderFallbackOverrideTrue {
        @Test
        void providerFallbackOverrideTrue(@Fusion final CodingAgentConfiguration configuration) {
            final var override = "true";
            final var cfg = configuration;
            final var calls = new ArrayList<String>();
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled)
                        throws IOException {
                    calls.add(request.model());
                    if ("$main/main-model".equals(request.model())) {
                        return new ChatResult(Message.assistant("digest", null), Usage.EMPTY);
                    }
                    throw new IOException("500 transient");
                }
            };
            final var manager = new ContextManager(
                    cfg, new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()), coordinator(cfg, chat));
            manager.compact(oldHeavyHistory(6, 300), true);
            assertEquals(!"false".equals(override), calls.contains("$main/main-model"), override + ": " + calls);
        }
    }

    public static class ProviderFallbackOverrideTrueModule extends ConfigurationTestModule {
        public ProviderFallbackOverrideTrueModule() {
            super(twoProviderConfigSettings(
                    128_000,
                    16_000,
                    Map.of(
                            "yca.compaction.fallbackToMainModel",
                            "true",
                            "yca.provider.providers.0.compaction.fallbackToMainModel",
                            "true")));
        }
    }

    @Nested
    @FusionSupport(modules = ProviderFallbackOverrideFalseModule.class)
    class ProviderFallbackOverrideFalse {
        @Test
        void providerFallbackOverrideFalse(@Fusion final CodingAgentConfiguration configuration) {
            final var override = "false";
            final var cfg = configuration;
            final var calls = new ArrayList<String>();
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled)
                        throws IOException {
                    calls.add(request.model());
                    if ("$main/main-model".equals(request.model())) {
                        return new ChatResult(Message.assistant("digest", null), Usage.EMPTY);
                    }
                    throw new IOException("500 transient");
                }
            };
            final var manager = new ContextManager(
                    cfg, new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()), coordinator(cfg, chat));
            manager.compact(oldHeavyHistory(6, 300), true);
            assertEquals(!"false".equals(override), calls.contains("$main/main-model"), override + ": " + calls);
        }
    }

    public static class ProviderFallbackOverrideFalseModule extends ConfigurationTestModule {
        public ProviderFallbackOverrideFalseModule() {
            super(twoProviderConfigSettings(
                    128_000,
                    16_000,
                    Map.of(
                            "yca.compaction.fallbackToMainModel",
                            "true",
                            "yca.provider.providers.0.compaction.fallbackToMainModel",
                            "false")));
        }
    }

    @Nested
    @FusionSupport(modules = DefaultCompactionRetriesIsTwoAttemptsModule.class)
    class DefaultCompactionRetriesIsTwoAttempts {
        /** The default {@code yca.compaction.retriesMaxAttempts} is 2 (1 initial + 1 transient retry per chunk). */
        @Test
        void defaultCompactionRetriesIsTwoAttempts(@Fusion final CodingAgentConfiguration configuration) {
            // no explicit compaction retries: the compaction default (2) applies, NOT the larger
            // conversation default yca.llm.retriesMaxAttempts (set to 4 here to prove compaction is scoped)
            final var cfg = configuration;
            final var attempts = new AtomicInteger();
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled)
                        throws IOException {
                    attempts.incrementAndGet();
                    throw new IOException("500 transient");
                }
            };
            final var manager = new ContextManager(
                    cfg, new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()), coordinator(cfg, chat));
            final var result = manager.compact(oldHeavyHistory(4, 300), true);
            assertEquals(CompactionResult.Mode.NONE, result.mode(), "transient failure must skip, not truncate");
            assertEquals(
                    2,
                    attempts.get(),
                    "one initial attempt + one transient retry = the default compaction budget (2), scoped from"
                            + " yca.llm");
        }
    }

    public static class DefaultCompactionRetriesIsTwoAttemptsModule extends ConfigurationTestModule {
        public DefaultCompactionRetriesIsTwoAttemptsModule() {
            super(configSettings(
                    128_000,
                    Map.of(
                            "yca.compaction.summarizer.chunkChars", "2000",
                            "yca.llm.retriesMaxAttempts", "4")));
        }
    }

    @Nested
    @FusionSupport(modules = MultiChunkRollingStateReplacesNotAppendsModule.class)
    class MultiChunkRollingStateReplacesNotAppends {
        /** Across multiple chunks the rolling digest is REPLACED, never accumulated: only the latest merge survives. */
        @Test
        void multiChunkRollingStateReplacesNotAppends(@Fusion final CodingAgentConfiguration configuration) {
            final var calls = new AtomicInteger();
            final var cfg = configuration;
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled) {
                    final var n = calls.incrementAndGet();
                    return new ChatResult(Message.assistant("merge-chunk-" + n, null), Usage.EMPTY);
                }
            };
            final var manager = new ContextManager(
                    cfg, new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()), coordinator(cfg, chat));
            final var result = manager.compact(oldHeavyHistory(30, 200), true);
            assertEquals(CompactionResult.Mode.SUMMARY, result.mode());
            assertTrue(calls.get() >= 2, "a multi-chunk history must make several summarizer calls: " + calls.get());
            final var digest = result.messages().stream()
                    .filter(m -> m.content() instanceof String s && s.contains("<compacted-context>"))
                    .map(m -> String.valueOf(m.content()))
                    .findFirst()
                    .orElseThrow();
            // replace semantics: only the LAST merge result is the digest, earlier per-chunk lines are gone
            assertTrue(digest.contains("merge-chunk-" + calls.get()), "the digest must reflect the LATEST merge");
            assertFalse(
                    digest.contains("merge-chunk-1"),
                    "replace semantics must drop earlier per-chunk lines, not accumulate them");
        }
    }

    public static class MultiChunkRollingStateReplacesNotAppendsModule extends ConfigurationTestModule {
        public MultiChunkRollingStateReplacesNotAppendsModule() {
            super(configSettings(
                    128_000,
                    Map.of(
                            "yca.compaction.summarizer.chunkChars", "400",
                            "yca.compaction.summarizer.minChunkChars", "200")));
        }
    }
}
