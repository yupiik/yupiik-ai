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

import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.LlmWire;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ModelsResponse;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.llm.client.codex.CodexNotAuthenticatedException;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

public class UsageTracker {
    private static final Logger LOGGER = Logger.getLogger(UsageTracker.class.getName());

    private final ProviderManager providerManager;
    private final ChatClient chatClient;
    private final ContextWindowCache contextWindowCache;
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong contextInputTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicLong reasoningTokens = new AtomicLong();
    private final AtomicLong cacheReadTokens = new AtomicLong();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong auxiliaryPromptTokens = new AtomicLong();
    private final AtomicLong auxiliaryContextInputTokens = new AtomicLong();
    private final AtomicLong auxiliaryCompletionTokens = new AtomicLong();
    private final AtomicLong auxiliaryCacheReadTokens = new AtomicLong();
    private final AtomicLong auxiliaryRequests = new AtomicLong();
    // rtk-style output-optimization counters: how many good tool outputs were trimmed and by how much.
    private final AtomicLong optimizedOutputs = new AtomicLong();
    private final AtomicLong optimizedCharsBefore = new AtomicLong();
    private final AtomicLong optimizedCharsAfter = new AtomicLong();
    private final AtomicLong optimizedTokenSavings = new AtomicLong();
    protected final Clock clock;
    /** Whether the most recent context-window resolution used a fallback rather than a configured/detected value. */
    private volatile boolean lastResolutionAuto;
    /**
     * Anti-spam window for a failed auto-detection of the context window: a flaky/unreachable
     * {@code /v1/models} must not re-fail (and re-warn) on every resolution, but a transient outage
     * must still recover within minutes instead of pinning the conservative baseline.
     */
    private static final long DETECTION_FAILURE_TTL_MILLIS = 5L * 60L * 1000L;
    /**
     * Anti-spam window for auth-related detection failures ({@code CodexNotAuthenticatedException}):
     * unlike other failures these are recoverable within the session (the user can run
     * {@code /codex login}), so retrying after a short backoff is correct; arming the long failure
     * backoff here pinned the conservative auto baseline and inflated the displayed context usage
     * (historical 1341% bug).
     */
    private static final long AUTH_DETECTION_RETRY_TTL_MILLIS = 60_000L;
    /** Last time auto-detection of the context window failed for a (provider id, model) key. */
    private final Map<Key, Long> detectionFailedAt = new ConcurrentHashMap<>();
    /** Last time auto-detection failed for an auth (recoverable) reason: short retry window only. */
    private final Map<Key, Long> authDetectionFailedAt = new ConcurrentHashMap<>();

    /** Stable identity for the (provider id, model) dimension over which auto-detection is coalesced. */
    private record Key(String providerId, String model) {}

    private final ContextMeasurement measurement = new ContextMeasurement();
    private volatile BiFunction<List<Message>, Integer, ContextMeasurement.Payload> contextProjection;

    /** Installs a pure projection of active history to model-visible request content. */
    public void contextProjection(final BiFunction<List<Message>, Integer, ContextMeasurement.Payload> projection) {
        contextProjection = projection;
    }

    public ContextMeasurement.Payload contextPayload(final List<Message> messages, final int toolTokens) {
        final var projection = contextProjection;
        if (projection != null) {
            return projection.apply(messages, toolTokens);
        }
        return payload(String.valueOf(settings), Integer.toString(toolTokens), messages, toolTokens);
    }

    public ContextMeasurement.Payload payload(
            final String modelKey, final String toolsKey, final List<Message> messages, final int toolTokens) {
        return new ContextMeasurement.Payload(
                modelKey,
                toolsKey,
                messages == null
                        ? List.of()
                        : messages.stream()
                                .map(LlmWire::toLlmMessage)
                                .map(Object::toString)
                                .toList(),
                estimateContext(messages, toolTokens));
    }

    public ContextMeasurement.Snapshot contextMeasurement(final List<Message> messages, final int toolTokens) {
        return measurement.measure(contextPayload(messages, toolTokens));
    }

    public ContextMeasurement.Snapshot lastContextMeasurement() {
        return measurement.latest();
    }

    public int budgetContext(final List<Message> messages, final int toolTokens) {
        return contextMeasurement(messages, toolTokens).budgetTokens();
    }

    /** The session's per-tab selection (provider/model): when set, context window resolution follows it. */
    protected volatile ChatSettings settings;

    /** Attach the owning session's model/provider selection so this tracker resolves the right window. */
    public void attach(final ChatSettings sessionSettings) {
        if (!Objects.equals(settings, sessionSettings)) {
            clearAnchor();
        }
        this.settings = sessionSettings;
    }

    public UsageTracker(
            final ProviderManager providerManager,
            final ChatClient chatClient,
            final ContextWindowCache contextWindowCache,
            final Clock clock) {
        this.providerManager = providerManager;
        this.chatClient = chatClient;
        this.contextWindowCache = contextWindowCache;
        this.clock = clock;
    }

    protected UsageTracker() {
        this.providerManager = null;
        this.chatClient = null;
        this.contextWindowCache = null;
        this.clock = null;
    }

    public void record(final Usage usage) {
        if (usage == null) {
            return;
        }
        promptTokens.addAndGet(usage.promptTokens());
        contextInputTokens.addAndGet(usage.contextInputTokens());
        completionTokens.addAndGet(usage.completionTokens());
        reasoningTokens.addAndGet(usage.reasoningTokens());
        cacheReadTokens.addAndGet(usage.cacheReadTokens());
        requests.incrementAndGet();
    }

    /**
     * Records spend of background LLM calls (context compaction summarizer, memory extraction)
     * which never appear as conversation turns: the tokens are added to the session totals so
     * the usage line stays honest about real cost, and mirrored into dedicated auxiliary
     * counters so the UI can break background spend out.
     */
    public void recordAuxiliary(final Usage usage) {
        if (usage == null) {
            return;
        }
        promptTokens.addAndGet(usage.promptTokens());
        contextInputTokens.addAndGet(usage.contextInputTokens());
        completionTokens.addAndGet(usage.completionTokens());
        reasoningTokens.addAndGet(usage.reasoningTokens());
        cacheReadTokens.addAndGet(usage.cacheReadTokens());
        requests.incrementAndGet();
        auxiliaryPromptTokens.addAndGet(usage.promptTokens());
        auxiliaryContextInputTokens.addAndGet(usage.contextInputTokens());
        auxiliaryCompletionTokens.addAndGet(usage.completionTokens());
        auxiliaryCacheReadTokens.addAndGet(usage.cacheReadTokens());
        auxiliaryRequests.incrementAndGet();
    }

    /**
     * Records one accepted tool-output optimization (rtk style): a meaningful character reduction
     * applied to a bash/tool result before it reaches the model. Only called for reductions that
     * actually replaced the original output. Token savings are estimated from each text separately,
     * preserving negative differences when a shorter output is more token-dense.
     *
     * @param beforeChars the original tool output length in characters.
     * @param afterChars  the optimized output length in characters.
     * @param tokensSaved signed difference between original and optimized token estimates.
     */
    public void recordOptimization(final long beforeChars, final long afterChars, final long tokensSaved) {
        optimizedOutputs.incrementAndGet();
        optimizedCharsBefore.addAndGet(beforeChars);
        optimizedCharsAfter.addAndGet(afterChars);
        optimizedTokenSavings.addAndGet(tokensSaved);
    }

    /** Number of tool outputs that were actually optimized (accepted reductions). */
    public long optimizedOutputs() {
        return optimizedOutputs.get();
    }

    /** Total characters of the original tool outputs that were optimized. */
    public long optimizedCharsBefore() {
        return optimizedCharsBefore.get();
    }

    /** Total characters of the optimized tool outputs. */
    public long optimizedCharsAfter() {
        return optimizedCharsAfter.get();
    }

    /** Total characters saved by output optimization (before minus after). */
    public long optimizedCharsSaved() {
        return Math.max(0, optimizedCharsBefore.get() - optimizedCharsAfter.get());
    }

    /** Estimated tokens removed once from tool outputs, not cumulative billed savings. */
    public long optimizedTokensSaved() {
        return Math.max(0, optimizedTokenSavings.get());
    }

    /**
     * Merges aggregated optimization counters as-is (used by {@code SubagentUsageTracker} to fold its
     * pre-aggregated totals into the parent without recounting each output as a single one).
     *
     * @param outputs the number of optimized outputs already counted by the caller.
     * @param before  the caller's total optimized-input characters.
     * @param after   the caller's total optimized-output characters.
     * @param tokensSaved the caller's signed estimated token savings.
     */
    public void mergeOptimizationCounters(
            final long outputs, final long before, final long after, final long tokensSaved) {
        if (outputs <= 0) {
            return;
        }
        optimizedOutputs.addAndGet(outputs);
        optimizedCharsBefore.addAndGet(before);
        optimizedCharsAfter.addAndGet(after);
        optimizedTokenSavings.addAndGet(tokensSaved);
    }

    /** Associates normalized provider input occupancy with the immutable request captured before sending. */
    public void anchor(final int realPromptTokens, final ContextMeasurement.Payload payload) {
        measurement.observe(payload, realPromptTokens);
    }

    /** Drops exact request identity while retaining model-specific tokenizer calibration. */
    public void clearAnchor() {
        measurement.invalidate();
    }

    public int lastRealPromptTokens() {
        return measurement.latest().lastInputTokens();
    }

    public double calibrationRatio() {
        return measurement.latest().correction();
    }

    public int effectiveContext(final List<Message> messages, final int extraToolTokens) {
        return contextMeasurement(messages, extraToolTokens).tokens();
    }

    /**
     * {@link #estimateContext(List, int)} plus an explicit contribution for the injected tool schema
     * (which is never part of the message list but IS part of every request). Callers building a
     * {@code ChatCompletionRequest} should pass an estimate of their wire tools to avoid the
     * thresholds firing late because tool definitions were uncounted.
     */
    public int estimateContext(final List<Message> messages) {
        return estimateContext(messages, 0);
    }

    /**
     * Heuristic token estimation for a message list using TokenEstimator, plus an explicit contribution
     * for the injected tool schema (which is never part of the message list but IS part of every request).
     * Callers building a {@code ChatCompletionRequest} should pass an estimate of their wire tools to avoid
     * the thresholds firing late because tool definitions were uncounted.
     */
    public int estimateContext(final List<Message> messages, final int extraToolTokens) {
        var total = Math.max(0, extraToolTokens);
        if (messages != null) {
            for (final var m : messages) {
                total += 8;
                total += TokenEstimator.estimate(m.role());
                total += TokenEstimator.estimate(m.content() instanceof String s ? s : String.valueOf(m.content()));
                total += TokenEstimator.estimate(m.name());
                total += TokenEstimator.estimate(m.toolCallId());
                if (m.toolCalls() != null) {
                    for (final var tc : m.toolCalls()) {
                        total += TokenEstimator.estimate(
                                tc.function() == null ? null : tc.function().name());
                        total += TokenEstimator.estimate(
                                tc.function() == null ? null : tc.function().arguments());
                    }
                }
            }
        }
        return total;
    }

    /**
     * The effective context window as a TokenCount (value + absolute flag + source).
     * Falls back to AUTO_DETECTED_BASELINE with absolute=false when nothing is configured/detected.
     */
    public ModelsResponse.TokenCount contextWindowTokenCount() {
        final var current = currentProvider();
        if (current == null) {
            return rememberAuto(new ModelsResponse.TokenCount(
                    ModelsResponse.AUTO_DETECTED_BASELINE,
                    false,
                    ModelsResponse.SourceType.AUTO_BASELINE,
                    "auto-baseline"));
        }
        // per-model override wins over the provider value: resolve it first when a concrete model is known
        if (chatClient != null && providerManager != null) {
            final var model = chatClient.model(settings);
            if (model != null && !model.isBlank()) {
                final var override = providerManager.contextWindowOverride(current, model);
                if (override > 0) {
                    return rememberExplicit(new ModelsResponse.TokenCount(
                            override, true, ModelsResponse.SourceType.CONFIGURED, "model-config"));
                }
            }
        }
        if (current.contextWindow() > 0) {
            return rememberExplicit(new ModelsResponse.TokenCount(
                    current.contextWindow(), true, ModelsResponse.SourceType.CONFIGURED, "provider-config"));
        }
        if (chatClient == null || contextWindowCache == null) {
            return rememberAuto(new ModelsResponse.TokenCount(
                    ModelsResponse.AUTO_DETECTED_BASELINE,
                    false,
                    ModelsResponse.SourceType.AUTO_BASELINE,
                    "auto-baseline"));
        }
        final var model = chatClient.model(settings);
        if (model == null || model.isBlank()) {
            return rememberAuto(new ModelsResponse.TokenCount(
                    ModelsResponse.AUTO_DETECTED_BASELINE,
                    false,
                    ModelsResponse.SourceType.AUTO_BASELINE,
                    "auto-baseline"));
        }
        final var providerId = current.id() == null || current.id().isBlank() ? "default" : current.id();
        final var cached = contextWindowCache.get(providerId, model);
        if (cached.isPresent() && !isOverflowAdjusted(cached.get())) {
            return rememberExplicit(cached.get());
        }
        if (!failedAutoDetectionRecently(providerId, model)) {
            final var detected = detectTokenCount(providerId, model);
            if (detected.isPresent()) {
                return rememberExplicit(detected.get());
            }
        }
        if (cached.isPresent()) {
            // a provisional value learned from an overflow is better than the generic baseline, but it
            // must be reported as auto so the next successful detection can replace it
            return rememberAuto(cached.get());
        }
        return rememberAuto(new ModelsResponse.TokenCount(
                ModelsResponse.AUTO_DETECTED_BASELINE,
                false,
                ModelsResponse.SourceType.AUTO_BASELINE,
                "auto-baseline"));
    }

    /**
     * The output budget the current model declares, as a TokenCount (value + absolute flag + source),
     * or null when unknown.
     */
    public ModelsResponse.TokenCount declaredOutputTokensTokenCount() {
        final var provider = currentProvider();
        if (provider == null) {
            return null;
        }
        final var model = currentModel(provider);
        final var override = providerManager == null ? 0 : providerManager.outputTokensOverride(provider, model);
        if (override > 0) {
            return new ModelsResponse.TokenCount(override, true, ModelsResponse.SourceType.CONFIGURED, "model-config");
        }
        if (provider.outputTokens() > 0) {
            return new ModelsResponse.TokenCount(
                    provider.outputTokens(), true, ModelsResponse.SourceType.CONFIGURED, "provider-config");
        }
        if (contextWindowCache == null || model == null || model.isBlank()) {
            return null;
        }
        return contextWindowCache.getMaxTokens(providerId(provider), model).orElse(null);
    }

    /** The context window minus the completion headroom: the budget the input may actually grow into
     * before the request risks overflowing. Thresholds compare against this, not the raw window. The
     * headroom is the output cap the model declares (per-model, per-provider or detected from
     * {@code /v1/models}, see {@link #declaredOutputTokensTokenCount()}) so a window of 262144 declaring 32768
     * completion tokens leaves 229376 for the input; without a declared cap the historical
     * {@code min(20000, window / 4)} heuristic applies (see {@link ContextBudget}). */
    public int usableWindow() {
        final var window = contextWindowTokenCount();
        final var output = declaredOutputTokensTokenCount();
        return ContextBudget.usableWindow(window, output);
    }

    /**
     * Resolve a routable background model reference to its (provider, model) pair, or null when the
     * reference is blank/null (no dedicated background model -> the session's conversation model)
     * or names an unknown provider/model.
     */
    private ResolvedRef resolveModelRef(final String modelReference) {
        if (providerManager == null || modelReference == null || modelReference.isBlank()) {
            return null;
        }
        final var resolved = providerManager.resolve(modelReference);
        if (resolved.provider() == null) {
            return null;
        }
        final var model = resolved.model();
        return model == null || model.isBlank() ? null : new ResolvedRef(resolved.provider(), model);
    }

    private record ResolvedRef(ProviderConfiguration provider, String model) {}

    /**
     * The usable window of an explicit background model reference (e.g. the compaction summarizer),
     * mirroring {@link #usableWindow()} but keyed to the model the reference routes to rather than
     * the session's conversation model. So a background summarizer smaller than the conversation
     * model sizes its input chunks against its own window instead of the conversation model's.
     *
     * <p>When the reference is blank/null there is no dedicated background model (the summarizer runs
     * on the conversation model), so the session's usual usable window applies. When the reference
     * cannot be resolved or no window is established, the conservative {@link ContextBudget#MIN_USABLE_WINDOW}
     * is returned so the caller never over-sizes against a phantom large window; the compaction halving
     * retry stays the safety net for an underestimated window.
     */
    public int usableWindowForModel(final String modelReference) {
        // a blank/null reference means no dedicated background model: the summarizer runs on the
        // conversation model, so its window is the session's own
        if (modelReference == null || modelReference.isBlank()) {
            return usableWindow();
        }
        final var ref = resolveModelRef(modelReference);
        if (ref == null) {
            return ContextBudget.MIN_USABLE_WINDOW;
        }
        final var window = resolveContextWindow(ref.provider(), ref.model());
        if (window == null) {
            return ContextBudget.MIN_USABLE_WINDOW;
        }
        final var output = resolveDeclaredOutputTokens(ref.provider(), ref.model());
        return ContextBudget.usableWindow(window, output);
    }

    /**
     * The context window of an explicit (provider, model) pair, sharing the precedence chain of
     * {@link #contextWindowTokenCount()} (per-model override, provider config, cached /v1/models,
     * fresh detection) but without counting the resolution toward the session's auto/explicit gauge.
     * {@code null} when no source establishes a window.
     */
    private ModelsResponse.TokenCount resolveContextWindow(final ProviderConfiguration provider, final String model) {
        if (providerManager != null && model != null && !model.isBlank()) {
            final var override = providerManager.contextWindowOverride(provider, model);
            if (override > 0) {
                return new ModelsResponse.TokenCount(
                        override, true, ModelsResponse.SourceType.CONFIGURED, "model-config");
            }
        }
        if (provider.contextWindow() > 0) {
            return new ModelsResponse.TokenCount(
                    provider.contextWindow(), true, ModelsResponse.SourceType.CONFIGURED, "provider-config");
        }
        if (chatClient == null || contextWindowCache == null || model == null || model.isBlank()) {
            return null;
        }
        final var providerId = providerId(provider);
        final var cached = contextWindowCache.get(providerId, model);
        if (cached.isPresent() && !isOverflowAdjusted(cached.get())) {
            return cached.get();
        }
        if (!failedAutoDetectionRecently(providerId, model)) {
            final var detected = detectTokenCount(providerId, model);
            if (detected.isPresent()) {
                return detected.get();
            }
        }
        return cached.orElse(null);
    }

    /**
     * The declared output budget of an explicit (provider, model) pair, mirroring
     * {@link #declaredOutputTokensTokenCount()}; {@code null} when unknown.
     */
    private ModelsResponse.TokenCount resolveDeclaredOutputTokens(
            final ProviderConfiguration provider, final String model) {
        final var override = providerManager == null ? 0 : providerManager.outputTokensOverride(provider, model);
        if (override > 0) {
            return new ModelsResponse.TokenCount(override, true, ModelsResponse.SourceType.CONFIGURED, "model-config");
        }
        if (provider.outputTokens() > 0) {
            return new ModelsResponse.TokenCount(
                    provider.outputTokens(), true, ModelsResponse.SourceType.CONFIGURED, "provider-config");
        }
        if (contextWindowCache == null || model == null || model.isBlank()) {
            return null;
        }
        return contextWindowCache.getMaxTokens(providerId(provider), model).orElse(null);
    }

    public long promptTokens() {
        return promptTokens.get();
    }
    /** Cumulative full input, including cached input, across normal and auxiliary calls. */
    public long contextInputTokens() {
        return contextInputTokens.get();
    }

    public long completionTokens() {
        return completionTokens.get();
    }

    public long reasoningTokens() {
        return reasoningTokens.get();
    }

    public long cacheReadTokens() {
        return cacheReadTokens.get();
    }

    public long requests() {
        return requests.get();
    }

    public long auxiliaryPromptTokens() {
        return auxiliaryPromptTokens.get();
    }
    /** Cumulative full input, including cached input, for auxiliary calls only. */
    public long auxiliaryContextInputTokens() {
        return auxiliaryContextInputTokens.get();
    }

    public long auxiliaryCompletionTokens() {
        return auxiliaryCompletionTokens.get();
    }

    public long auxiliaryCacheReadTokens() {
        return auxiliaryCacheReadTokens.get();
    }

    public long auxiliaryRequests() {
        return auxiliaryRequests.get();
    }

    public long auxiliaryFreshTokens() {
        return Math.max(0, auxiliaryContextInputTokens() - auxiliaryCacheReadTokens());
    }

    /**
     * Whether the last {@link #contextWindowTokenCount()} resolution (if any) fell back to the auto-detected
     * baseline or a provisional overflow-adjusted value rather than an explicit/explicitly detected value.
     * Useful to avoid spamming a 'context window auto-detected' notice on every turn.
     */
    public boolean lastResolutionWasAuto() {
        return lastResolutionAuto;
    }

    /**
     * Where the last {@link #contextWindowTokenCount()} resolution came from: {@code "explicit"} for a
     * provider-configured or vendor-detected/cached value, {@code "auto"} for the
     * {@link ModelsResponse#AUTO_DETECTED_BASELINE} fallback or a provisional overflow-adjusted value.
     */
    public String windowOrigin() {
        return lastResolutionWasAuto() ? "auto" : "explicit";
    }

    private ModelsResponse.TokenCount rememberExplicit(final ModelsResponse.TokenCount window) {
        lastResolutionAuto = false;
        return window;
    }

    private ModelsResponse.TokenCount rememberAuto(final ModelsResponse.TokenCount window) {
        lastResolutionAuto = true;
        return window;
    }

    /**
     * Usage percentage against the {@link #usableWindow()} (context window minus the reserved
     * output headroom), so display and triggers agree on the same denominator.
     */
    public double contextPercent(final int estimatedTokens) {
        return 100.0 * estimatedTokens / usableWindow();
    }

    /**
     * Learns from a {@code ContextWindowExceededException}: the assumed window was too high, so
     * halve it and return the corrected value. When the current window is not absolute (a fallback
     * or relative estimate rather than a configured/vendor-declared absolute limit), the corrected
     * value is also persisted so later turns benefit; absolute windows are left untouched so the
     * provider/config figure remains authoritative. No-op when the cache/client is unavailable.
     *
     * @return the new (halved) window, or the current window if it could not be adjusted.
     */
    public int rememberShrinkOnOverflow() {
        if (chatClient == null || contextWindowCache == null) {
            return contextWindowTokenCount().value();
        }
        final var current = currentProvider();
        if (current == null) {
            return contextWindowTokenCount().value();
        }
        final var model = currentModel(current);
        if (model == null || model.isBlank()) {
            return contextWindowTokenCount().value();
        }
        final var providerId = providerId(current);
        final var currentWindow = contextWindowTokenCount();
        final var halvedValue = Math.max(4_096, currentWindow.value() / 2);
        final var halved = new ModelsResponse.TokenCount(
                halvedValue,
                false,
                ModelsResponse.SourceType.OVERFLOW_ADJUSTED,
                currentWindow.sourcePointer() + "-halved");
        if (!currentWindow.absolute()) {
            try {
                contextWindowCache.put(providerId, model, halved, clock.millis());
            } catch (final RuntimeException e) {
                LOGGER.log(
                        Level.WARNING,
                        e,
                        () -> "Could not persist reduced context window for " + providerId + "/" + model);
            }
        }
        return halvedValue;
    }

    /** An overflow adjustment is a provisional guess, never an authoritative detected/configured value. */
    private static boolean isOverflowAdjusted(final ModelsResponse.TokenCount window) {
        return window != null && window.sourceType() == ModelsResponse.SourceType.OVERFLOW_ADJUSTED;
    }

    private Optional<ModelsResponse.TokenCount> detectTokenCount(final String providerId, final String model) {
        try {
            // session-aware: resolve against the session's provider (per-tab selection), not the
            // process-current one - a null settings made the /models call target the wrong provider
            // (whose catalog does not contain the session model), arming a bogus detection backoff
            // keyed to this (providerId, model) and pinning the conservative auto baseline
            final var response = chatClient.models(settings);
            if (response == null || response.data() == null) {
                return Optional.empty();
            }
            for (final var entry : response.data()) {
                if (model.equals(entry.id())) {
                    final var window = entry.contextWindow();
                    final var maxTokens = entry.maxTokens();
                    if (window != null && window.value() > 0) {
                        contextWindowCache.put(providerId, model, window, maxTokens, clock.millis());
                        return Optional.of(window);
                    }
                    break;
                }
            }
        } catch (final CodexNotAuthenticatedException e) {
            // recoverable within the session (login can happen after the first resolution attempt):
            // only apply a short anti-spam backoff so the detection retries once the user signs in,
            // unlike a transient failure which must not re-fail on every resolution
            authDetectionFailedAt.put(new Key(providerId, model), clock.millis());
            LOGGER.log(
                    Level.INFO,
                    e,
                    () -> "Context window auto-detection postponed for " + providerId + "/" + model
                            + ": provider not authenticated yet; will retry (short backoff) after login");
            return staleWindow(providerId, model);
        } catch (final IOException | RuntimeException e) {
            detectionFailedAt.put(new Key(providerId, model), clock.millis());
            LOGGER.log(
                    Level.WARNING,
                    e,
                    () -> "Context window auto-detection failed for " + providerId + "/" + model
                            + " (" + e.getClass().getSimpleName() + ")"
                            + "; falling back to conservative default " + ModelsResponse.AUTO_DETECTED_BASELINE
                            + " (no retry for 5 minutes)");
            return staleWindow(providerId, model);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return staleWindow(providerId, model);
    }

    /**
     * Last-resort stale cache read: when a fresh detection failed but an out-of-TTL entry exists for
     * the (provider id, model) pair, it is still vastly better than the conservative auto baseline
     * (a day-old 500000 beats a guessed 32000). Returns empty when the cache is unavailable.
     */
    private Optional<ModelsResponse.TokenCount> staleWindow(final String providerId, final String model) {
        if (contextWindowCache == null) {
            return Optional.empty();
        }
        final var stale = contextWindowCache.getStale(providerId, model);
        if (stale.isPresent()) {
            LOGGER.info(() -> "Using stale (expired) cached context window for " + providerId + "/" + model + ": "
                    + stale.get().value() + " (detection failed, no fresher source)");
        }
        return stale;
    }

    /** Whether auto-detection already failed for the key within the retention window, so we stop re-attempting. */
    private boolean failedAutoDetectionRecently(final String providerId, final String model) {
        if (clock == null) {
            return false;
        }
        final var failedAt = detectionFailedAt.get(new Key(providerId, model));
        if (failedAt != null && clock.millis() - failedAt < DETECTION_FAILURE_TTL_MILLIS) {
            return true;
        }
        final var authFailedAt = authDetectionFailedAt.get(new Key(providerId, model));
        return authFailedAt != null && clock.millis() - authFailedAt < AUTH_DETECTION_RETRY_TTL_MILLIS;
    }

    /** The provider targeted by the session selection, else the process current provider; {@code null} when unknown. */
    private ProviderConfiguration currentProvider() {
        if (providerManager == null) {
            return null;
        }
        if (settings == null || settings.providerId() == null) {
            return providerManager.current();
        }
        return providerManager.provider(settings.providerId());
    }

    /** The effective model name of the session, falling back to the provider default without a client. */
    private String currentModel(final ProviderConfiguration provider) {
        if (chatClient != null) {
            final var model = chatClient.model(settings);
            if (model != null && !model.isBlank()) {
                return model;
            }
        }
        return provider == null ? null : provider.model();
    }

    private static String providerId(final ProviderConfiguration provider) {
        return provider.id() == null || provider.id().isBlank() ? "default" : provider.id();
    }
}
