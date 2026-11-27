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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.fusion.framework.handlebars.HandlebarsCompiler;
import io.yupiik.fusion.framework.handlebars.helper.HelperContext;
import io.yupiik.fusion.framework.handlebars.spi.Template;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.BoundaryOnlyRefMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ModelVariantConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.ReasoningFormatMode;
import io.yupiik.ia.agent.core.context.ContextMeasurement;
import io.yupiik.ia.agent.core.context.ContextWindowCache;
import io.yupiik.ia.agent.core.context.TokenEstimator;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.runtime.Cancellations;
import io.yupiik.ia.agent.core.wiring.OpenCodeHttpClient;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.core.wiring.SessionAwareLogRecord;
import io.yupiik.llm.client.DialectLlmClient;
import io.yupiik.llm.client.DialectNotSupportedException;
import io.yupiik.llm.client.LlmClientResilienceWrapper;
import io.yupiik.llm.client.LlmHttpFailureException;
import io.yupiik.llm.client.LlmResilienceConfig;
import io.yupiik.llm.client.LlmResponseParseException;
import io.yupiik.llm.client.RoutingLlmClient;
import io.yupiik.llm.client.anthropic.AnthropicDialect;
import io.yupiik.llm.client.codex.CodexDialect;
import io.yupiik.llm.client.codex.CodexNotAuthenticatedException;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.openai.OpenAICompletionsDialect;
import io.yupiik.llm.client.openai.OpenAIResponsesDialect;
import io.yupiik.llm.client.opencode.OpenCodeFreeDialect;
import io.yupiik.llm.client.spi.Dialect;
import io.yupiik.llm.client.spi.ProviderSpec;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Blocking facade over the shared {@code llm-client} library. Requests are dispatched through a
 * {@link RoutingLlmClient} whose selector resolves, per request, the resilient
 * {@link DialectLlmClient} matching the current provider and {@link ApiFlavor}; concurrency,
 * throttling, retries and {@code retry-after} handling are delegated to the library resilience layer
 * ({@link LlmClientResilienceWrapper}).
 *
 * <p>Keeps the historical public API ({@link #chat}, {@link #stream}, {@link #models}, {@link #model},
 * {@link ChatSettings}, {@link #flavor} and {@link #providerId}) and owns the concerns
 * the library must not know about: model/provider resolution, provider-specific option merging, the
 * per-request output budget and the AUTO flavor detection and responses-to-completions fallback.</p>
 */
@ApplicationScoped
public class ChatClient implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(ChatClient.class.getName());

    private final CodingAgentConfiguration configuration;
    private final JsonMapper mapper;
    private final ScopedValue<Consumer<ContextMeasurement.Payload>> contextCapture = ScopedValue.newInstance();

    /** Scoped to the synchronous request owner, including every repaired/fallback attempt. */
    public <T, X extends Throwable> T withContextCapture(
            final Consumer<ContextMeasurement.Payload> capture, final ScopedValue.CallableOp<T, X> operation) throws X {
        return ScopedValue.where(contextCapture, capture).call(operation);
    }

    private final ProviderManager providerManager;
    private final Clock clock;
    private final ToolCallArgumentsRepairer toolCallArgumentsRepairer;
    private final InBandToolCallExtractor inBandToolCallExtractor;
    private final ContextWindowCache contextWindowCache;
    private final ExtendedHttpClient httpClient;
    /** Shared application scheduler parking the resilient retry waits; {@code null} falls back to the JDK delayed executor. */
    private final ScheduledExecutorService scheduler;
    /** Provides the {@code {{exec}}} handlebars helper for dynamic provider headers; {@code null} in tests/programmatic use. */
    private final ExecTemplateHelper execTemplateHelper;
    /** Provides the {@code {{codexAuth}}} handlebars helper for Codex-flavored providers; injected so never null. */
    private final CodexAuthTemplateHelper codexAuthTemplateHelper;
    /** User-registered custom dynamic-header helpers, injected after the built-in ones; empty when none. */
    private final List<CustomDynamicHeaderTemplateHelper> customDynamicHeaderTemplateHelpers;
    /**
     * Concurrency gates shared by every client of a provider - the {@code /btw} dedicated client and
     * this one must not each get their own copy of the limit. {@code null} only for the legacy
     * no-arg subclass constructor, which builds the lazily-created gates in memory.
     */
    private final ProviderConcurrencyGates concurrencyGates;
    /** Whether {@link #concurrencyGates} was created here and must therefore be closed here. */
    private final boolean ownsConcurrencyGates;

    private final Map<ClientKey, DialectLlmClient> dialectClients = new ConcurrentHashMap<>();
    private final Map<ClientKey, LlmClientResilienceWrapper> resiliences = new ConcurrentHashMap<>();
    private final Set<String> budgetLogged = ConcurrentHashMap.newKeySet();
    /** Dangling defaultVariant warn-once keys: provider + '\u0000' + model + '\u0000' + name. */
    private final Set<String> warnedDefaultVariants = ConcurrentHashMap.newKeySet();
    // per-provider caches: the flavor AUTO fallback, structured-output support and the model list are
    // provider-scoped facts. With per-session providers (one per tab) a single process-wide slot would
    // leak a tab A provider observation (AUTO fell back to completions, structured unsupported) to tab B
    private final ConcurrentHashMap<String, ApiFlavor> resolvedFlavors = new ConcurrentHashMap<>();
    // structured-output rejection memoization: key (provider:model:schema) -> wall-clock millis of the
    // rejection. Bounded in time so a single transient 400 (a gateway hiccup, a body-JSON/max_tokens
    // slip) can never permanently cripple the structured path for an otherwise structured-capable
    // provider/model - after the TTL the structured request is retried.
    private static final long STRUCTURED_REJECTION_TTL_MILLIS = 10L * 60 * 1000;
    private final Map<String, Long> structuredOutputRejected = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ModelsResponse> cachedModelsByProvider = new ConcurrentHashMap<>();
    // a failed best-effort catalog lookup is retried after a short delay so an unconfigured model
    // request never pays a failing /models round-trip on every call
    private static final long MODEL_CATALOG_FAILURE_BACKOFF_MILLIS = 60_000L;
    private final Map<String, Long> modelCatalogRetryAfter = new ConcurrentHashMap<>();

    /** Uses the caller-owned HTTP client, which must be closed by its owner. */
    public ChatClient(
            final CodingAgentConfiguration configuration,
            final JsonMapper mapper,
            final ProviderManager providerManager,
            final Clock clock,
            final ExtendedHttpClient httpClient,
            final ToolCallArgumentsRepairer toolCallArgumentsRepairer,
            final InBandToolCallExtractor inBandToolCallExtractor,
            final ScheduledExecutorService scheduler,
            final ExecTemplateHelper execTemplateHelper,
            final CodexAuthTemplateHelper codexAuthTemplateHelper,
            final List<CustomDynamicHeaderTemplateHelper> customDynamicHeaderTemplateHelpers,
            final ProviderConcurrencyGates concurrencyGates) {
        this.configuration = configuration;
        this.mapper = mapper;
        this.providerManager = providerManager;
        this.clock = clock;
        this.toolCallArgumentsRepairer = toolCallArgumentsRepairer;
        this.inBandToolCallExtractor = inBandToolCallExtractor == null
                ? new InBandToolCallExtractor(new MarkupToolCallScanner())
                : inBandToolCallExtractor;
        this.contextWindowCache = new ContextWindowCache(configuration, mapper, clock);
        this.httpClient = httpClient;
        this.scheduler = scheduler;
        this.execTemplateHelper = execTemplateHelper;
        this.codexAuthTemplateHelper = codexAuthTemplateHelper;
        this.customDynamicHeaderTemplateHelpers = customDynamicHeaderTemplateHelpers == null
                ? List.of()
                : List.copyOf(customDynamicHeaderTemplateHelpers);
        this.concurrencyGates =
                concurrencyGates == null ? new ProviderConcurrencyGates(configuration) : concurrencyGates;
        // a registry created here is ours: it holds the file-lease gates of this client and has to be
        // closed with it, otherwise a recycled client leaves its gates (and their leases) behind
        this.ownsConcurrencyGates = concurrencyGates == null;
        // context overflow is handled by the agent loop (context compaction), retries after are
        // honored by the library resilience layer; per-provider clients get their own resilience
        // wrapper so the concurrency budget is scoped to one provider base URL
    }

    public ChatClient(
            final CodingAgentConfiguration configuration,
            final JsonMapper mapper,
            final ProviderManager providerManager,
            final Clock clock,
            final ExtendedHttpClient httpClient,
            final ToolCallArgumentsRepairer toolCallArgumentsRepairer) {
        this(configuration, mapper, providerManager, clock, httpClient, toolCallArgumentsRepairer, null);
    }

    /** Same as the 6-arg constructor, with a caller-owned shared concurrency gate registry. */
    public ChatClient(
            final CodingAgentConfiguration configuration,
            final JsonMapper mapper,
            final ProviderManager providerManager,
            final Clock clock,
            final ExtendedHttpClient httpClient,
            final ToolCallArgumentsRepairer toolCallArgumentsRepairer,
            final ProviderConcurrencyGates concurrencyGates) {
        this(
                configuration,
                mapper,
                providerManager,
                clock,
                httpClient,
                toolCallArgumentsRepairer,
                null,
                null,
                null,
                null,
                List.of(),
                concurrencyGates);
    }

    /** Convenience constructor using a caller-owned HTTP client. */
    public ChatClient(
            final CodingAgentConfiguration configuration,
            final JsonMapper mapper,
            final ProviderManager providerManager,
            final Clock clock,
            final ExtendedHttpClient httpClient) {
        this(configuration, mapper, providerManager, clock, httpClient, new ToolCallArgumentsRepairer(mapper));
    }

    private LlmClientResilienceWrapper resilience(final ProviderConfiguration provider, final int providerBounds) {
        final var compaction = provider == null ? null : provider.compaction();
        final var retries = compaction != null && compaction.retriesMaxAttempts() > 0
                ? compaction.retriesMaxAttempts()
                : configuration.llm().retriesMaxAttempts();
        final var baseDelay = compaction != null && compaction.retriesBaseDelayMs() > 0
                ? compaction.retriesBaseDelayMs()
                : configuration.llm().retriesBaseDelayMs();
        final var rateLimit = compaction != null && compaction.rateLimitRequestsPerMinute() > 0
                ? compaction.rateLimitRequestsPerMinute()
                : configuration.llm().rateLimitRequestsPerMinute();
        return new LlmClientResilienceWrapper(
                new LlmResilienceConfig(
                        retries,
                        baseDelay,
                        rateLimit,
                        providerBounds,
                        // the summarizer has its own halving loop for context overflow (the agent
                        // loop owns overflow recovery for conversation requests); the library must
                        // not absorb the exception into its own retry, or halving would never see it
                        false,
                        true,
                        configuration.llm().retryAfterMaxWaitMs(),
                        1.0d,
                        configuration.llm().concurrencyAdaptationMs(),
                        configuration.llm().usageLimitRetryThresholdMs(),
                        configuration.llm().concurrencyAcquireTimeoutMs()),
                null,
                // the shared scheduler makes the retry waits cancellable (interrupt during a
                // 429/retry-after backoff ends the wait early); null keeps the JDK fallback
                scheduler,
                // injected clock + mapper let the wrapper soft-parse a 429 usage_limit_reached body
                // and skip pointless retries when the quota window still holds
                clock,
                mapper,
                // one gate per provider, shared by every client of the process (and, when the
                // provider opts in, by the other yca processes of the machine); the registry is null
                // only for the legacy no-arg subclass constructor, where the wrapper keeps its own
                // in-memory gate
                concurrencyGates == null ? null : capacity -> concurrencyGates.gate(provider, capacity));
    }

    protected ChatClient() {
        this.configuration = null;
        this.mapper = null;
        this.providerManager = null;
        this.clock = null;
        this.toolCallArgumentsRepairer = null;
        this.inBandToolCallExtractor = new InBandToolCallExtractor(new MarkupToolCallScanner());
        this.contextWindowCache = null;
        this.httpClient = null;
        this.scheduler = null;
        this.execTemplateHelper = null;
        this.codexAuthTemplateHelper = null;
        this.customDynamicHeaderTemplateHelpers = List.of();
        this.concurrencyGates = null;
        this.ownsConcurrencyGates = false;
    }

    /** Cache key: a resilient dialect client is built per (flavor, provider) pair. */
    private record ClientKey(ApiFlavor flavor, ProviderConfiguration provider) {}

    public ApiFlavor flavor() {
        // report the flavor actually used for routing (AUTO resolves to a concrete route for bare /v1)
        return flavorForRouting();
    }

    /**
     * The flavor actually used for routing a <em>session</em>, honoring its per-tab provider override
     * (AUTO resolves to a concrete route for bare /v1). Mirrors {@link #model(ChatSettings)} and
     * {@link #providerId(ChatSettings)} so the sidebar's flavor row follows a {@code /provider} switch
     * instead of always reflecting the process-default provider.
     */
    public ApiFlavor flavor(final ChatSettings settings) {
        return flavorForRouting(settings);
    }

    /**
     * The flavor used to select a dialect: an explicitly resolved flavor wins, then the configured
     * provider flavor, then a flavor deduced from the base URL route; {@link ApiFlavor#AUTO} when it
     * cannot be pinned (in which case the responses route is probed first and falls back).
     * The effective provider is the session's (per-tab) one when a settings override exists.
     */
    private ApiFlavor effectiveFlavor() {
        return effectiveFlavor(null);
    }

    private ApiFlavor effectiveFlavor(final ChatSettings settings) {
        final var provider = provider(settings);
        if (provider == null) {
            return ApiFlavor.AUTO;
        }
        final var configured = provider.api();
        if (configured != null && configured != ApiFlavor.AUTO) {
            return configured;
        }
        final var detected = ChatRoutes.detectFlavor(provider.baseUrl());
        return detected == null ? ApiFlavor.AUTO : detected;
    }

    /** The concrete flavor handed to the router: AUTO resolves to probing the responses route first. */
    private ApiFlavor flavorForRouting() {
        return flavorForRouting(null);
    }

    private ApiFlavor flavorForRouting(final ChatSettings settings) {
        final var flavor = effectiveFlavor(settings);
        if (flavor != ApiFlavor.AUTO) {
            return flavor;
        }
        // a provider that already fell back once (AUTO probed /responses, got DialectNotSupported)
        // is pinned to chat completions: re-probing each retry would loop on the same failure
        final var provider = provider(settings);
        if (provider != null) {
            final var pinned = resolvedFlavors.get(providerIdOf(provider));
            if (pinned != null && pinned != ApiFlavor.AUTO) {
                return pinned;
            }
        }
        // A bare base URL (no recognizable /responses, /chat/completions or /messages route) cannot be
        // pinned reliably; default to chat completions, the common OpenAI-compatible layout opencode
        // uses, so reasoning_content flows without requiring an explicit reasoning_effort.
        if (provider != null && ChatRoutes.detectFlavor(provider.baseUrl()) == null) {
            return ApiFlavor.COMPLETIONS;
        }
        return ApiFlavor.RESPONSES;
    }

    /** The resilient dialect client for a (flavor, provider) pair, built on first use and cached. */
    private DialectLlmClient dialectClient(final ApiFlavor flavor, final ProviderConfiguration provider) {
        if (flavor == null || flavor == ApiFlavor.AUTO || provider == null) {
            throw new IllegalStateException("resolved flavor and provider must be concrete");
        }
        return dialectClients.computeIfAbsent(new ClientKey(flavor, provider), key -> {
            final var headers = headersCustomizer(provider);
            final Consumer<HttpRequest.Builder> transport;
            if (flavor == ApiFlavor.OPENCODE_FREE && httpClient.delegate() instanceof OpenCodeHttpClient selector) {
                final var caps = provider.capabilities();
                transport = selector.profile(
                        caps == null || caps.opencode() == null
                                ? null
                                : caps.opencode().cipherSuites());
            } else {
                if (flavor == ApiFlavor.OPENCODE_FREE) {
                    throw new IllegalStateException("OpenCode requires the shared OpenCode-selecting HTTP transport");
                }
                transport = ignored -> {};
            }
            final var spec = new ProviderSpec(
                    ChatRoutes.chatEndpoint(provider.baseUrl(), flavor),
                    flavor == ApiFlavor.OPENCODE_FREE ? "public" : provider.resolveApiKey(),
                    Duration.ofSeconds(provider.timeoutSeconds()),
                    providerManager.providerOptions(provider),
                    builder -> {
                        headers.accept(builder);
                        transport.accept(builder);
                    });
            final var bounds = provider.maxConcurrent() > 0
                    ? provider.maxConcurrent()
                    : configuration.llm().maxConcurrent();
            return new DialectLlmClient(
                    dialect(flavor, spec, provider),
                    resiliences.computeIfAbsent(key, _ -> resilience(provider, bounds)));
        });
    }

    /**
     * The per-request header customizer for a provider: the static {@code headers} merged with the
     * rendered {@code dynamicHeaders} handlebars templates. Templates are compiled once per provider
     * (lazily, cached in a {@link ConcurrentHashMap}) and rendered at each request, so
     * {@code {{exec ...}}} outputs refresh per its TTL cache; rendered blank values are skipped.
     * Applied by the dialect AFTER its defaults with {@code setHeader}, so these headers win over
     * the apiKey/version defaults when they target the same name.
     */
    private Consumer<HttpRequest.Builder> headersCustomizer(final ProviderConfiguration provider) {
        final var staticHeaders = provider.headers() == null ? Map.<String, String>of() : provider.headers();
        final var dynamic = provider.dynamicHeaders() == null ? Map.<String, String>of() : provider.dynamicHeaders();
        if (staticHeaders.isEmpty() && (dynamic.isEmpty() || execTemplateHelper == null)) {
            return _ -> {};
        }
        final var compiled = new ConcurrentHashMap<String, Template>();
        final var exec = execTemplateHelper.exec();
        final var helpers = new LinkedHashMap<String, Function<HelperContext, Object>>();
        helpers.put("exec", exec);
        // the codex helpers are injected, so they can never be null here
        helpers.put("codexAuth", codexAuthTemplateHelper.codexAuth());
        helpers.put("codexAgentAssertion", codexAuthTemplateHelper.codexAgentAssertion());
        helpers.put("codexAccountId", codexAuthTemplateHelper.codexAccountId());
        helpers.put("codexFedramp", codexAuthTemplateHelper.codexFedramp());
        // user-registered helpers come after the built-ins: the built-ins win on a name conflict
        for (final var custom : customDynamicHeaderTemplateHelpers) {
            helpers.putIfAbsent(custom.name(), custom);
        }
        return builder -> {
            staticHeaders.forEach((k, v) -> {
                if (k != null && !k.isBlank() && v != null && !v.isBlank()) {
                    builder.setHeader(k, v);
                }
            });
            dynamic.forEach((k, template) -> {
                if (k == null || k.isBlank()) {
                    return;
                }
                final var t = compiled.computeIfAbsent(
                        k,
                        name -> new HandlebarsCompiler()
                                .compile(new HandlebarsCompiler.CompilationContext(
                                        new HandlebarsCompiler.Settings().helpers(helpers),
                                        template == null ? "" : template)));
                final String rendered;
                try {
                    rendered = t.render(Map.of());
                } catch (final CodexNotAuthenticatedException notAuthed) {
                    // rethrow as-is so TurnRunner can trigger the interactive login flow
                    throw notAuthed;
                } catch (final RuntimeException e) {
                    throw new IllegalArgumentException(
                            "Cannot render dynamic header '" + k + "': " + e.getMessage(), e);
                }
                if (rendered != null && !rendered.isBlank()) {
                    builder.setHeader(k, rendered);
                }
            });
        };
    }

    private Dialect dialect(final ApiFlavor flavor, final ProviderSpec spec, final ProviderConfiguration provider) {
        final var debugLogger = configuration.httpClient().debug() ? Logger.getLogger("yca.http") : null;
        return switch (flavor) {
            case OPENCODE_FREE -> new OpenCodeFreeDialect(mapper, httpClient, spec);
            case COMPLETIONS -> {
                final var d = new OpenAICompletionsDialect(mapper, httpClient, spec);
                if (debugLogger != null) {
                    d.setOnRawLine(debugLogger::fine);
                }
                yield d;
            }
            case RESPONSES -> {
                final var d = new OpenAIResponsesDialect(
                        mapper,
                        httpClient,
                        spec,
                        openaiSessionIdEnabledKeys(provider, ApiFlavor.RESPONSES),
                        openaiXClientRequestId(provider));
                if (debugLogger != null) {
                    d.setOnRawLine(debugLogger::fine);
                }
                yield d;
            }
            case CODEX -> {
                final var providerId = provider == null ? null : provider.id();
                final var d = new CodexDialect(
                        mapper,
                        httpClient,
                        spec,
                        codexClientVersion(provider),
                        // 3p delegated-access (workspace policy) auto-upgrade: on a
                        // 3p_delegated_access_policy_denied 4xx the dialect lazily enrolls the
                        // enterprise Agent Identity and retries once with the signed assertion.
                        providerId == null || providerId.isBlank()
                                ? null
                                : ignored -> codexAuthTemplateHelper.resolveAgentAssertion(providerId),
                        reasoningInclude(provider),
                        openaiSessionIdEnabledKeys(provider, ApiFlavor.CODEX),
                        openaiXClientRequestId(provider));
                if (providerId != null && !providerId.isBlank()) {
                    d.setUnauthorizedRecovery(
                            authorization -> codexAuthTemplateHelper.recoverUnauthorized(providerId, authorization));
                }
                if (debugLogger != null) {
                    d.setOnRawLine(debugLogger::fine);
                }
                yield d;
            }
            case ANTHROPIC -> {
                final var d = new AnthropicDialect(mapper, httpClient, spec);
                if (debugLogger != null) {
                    d.setOnRawLine(debugLogger::fine);
                }
                yield d;
            }
            case AUTO -> throw new IllegalStateException("resolved flavor must not be AUTO");
        };
    }

    /**
     * The Codex client version a provider advertises for model gating, read from its
     * {@code capabilities.codex.clientVersion} (formerly {@code providerSpecificOptions} key
     * {@code codex_client_version}) and defaulting to 160. Only meaningful for codex-flavored
     * providers; the value is internal (never sent on the wire).
     */
    private int codexClientVersion(final ProviderConfiguration provider) {
        if (provider == null) {
            return 160;
        }
        final var version = provider.codexCapabilities().clientVersion();
        return version > 0 ? version : 160;
    }

    /**
     * Whether a provider opts into the encrypted-reasoning {@code include} directive (requesting the
     * opaque {@code reasoning.encrypted_content} blob for multi-turn continuity). Read from
     * {@code capabilities.codex.includeReasoning} (formerly {@code providerSpecificOptions} key
     * {@code reasoning_include}); default {@code false} because the ChatGPT/Codex and stricter Codex/gov
     * edges reject the directive with an unknown-parameter 400, and the base Responses dialect
     * auto-downgrades once when a gateway rejects it anyway. Internal only (never sent on the wire).
     */
    private boolean reasoningInclude(final ProviderConfiguration provider) {
        return provider != null && provider.codexCapabilities().includeReasoning();
    }

    /**
     * The resolved session-cache-affinity key list for a (provider, flavor) pair, read from
     * {@code capabilities.openai.sessionId.enabledKeys}. When the provider does not configure it, the
     * flavor-specific default applies: the codex flavor emits {@code session-id} + {@code prompt_cache_key}
     * (matching the Codex CLI), the openai-responses flavor emits {@code x-session-id} + {@code prompt_cache_key}
     * ({@code session_id} with an underscore is rejected by hardened Envoy/nginx gateways), and the other
     * flavors (completions/anthropic) emit nothing.
     */
    private List<String> openaiSessionIdEnabledKeys(final ProviderConfiguration provider, final ApiFlavor flavor) {
        final var openai = provider == null ? null : provider.openaiCapabilities();
        final var keys = openai == null || openai.sessionId() == null
                ? null
                : openai.sessionId().enabledKeys();
        if (keys != null) {
            return keys;
        }
        return switch (flavor) {
            case OPENCODE_FREE -> List.of("prompt_cache_key");
            case CODEX -> List.of("session-id", "prompt_cache_key");
            case RESPONSES -> List.of("x-session-id", "prompt_cache_key");
            default -> List.of();
        };
    }

    /**
     * Whether a provider requests the per-request {@code x-client-request-id} marker, read from
     * {@code capabilities.openai.xClientRequestIdEnabled} and defaulting to {@code false}.
     */
    private boolean openaiXClientRequestId(final ProviderConfiguration provider) {
        return provider != null && provider.openaiCapabilities().xClientRequestIdEnabled();
    }

    /**
     * Translate a library pause into the core {@link PauseEvent} and hand it to the per-call listener
     * (see {@link #chat(ChatCompletionRequest, Consumer, BooleanSupplier)}). The listener
     * travels with the call so concurrent sessions each get their own notices instead of fighting over
     * a single process-wide slot.
     */
    private void onLibraryPause(final io.yupiik.llm.client.PauseEvent event, final Consumer<PauseEvent> target) {
        if (target == null) {
            return;
        }
        try {
            if (event.reason() != null && event.reason().startsWith("concurrency limit")) {
                target.accept(PauseEvent.retry(event.reason(), 0));
            } else if (event.attempt() <= 0) {
                target.accept(PauseEvent.throttle(
                        "Rate limit reached, waiting ~" + (event.waitMs() / 1000) + "s", event.waitMs()));
            } else {
                final var max = configuration.llm().retriesMaxAttempts();
                final var summary = failureSummary(event.reason());
                final var detail = summary.isEmpty() ? "" : " (" + summary + ")";
                target.accept(PauseEvent.retry(
                        "LLM call failed" + detail + ", retrying in ~" + (event.waitMs() / 1000) + "s (attempt "
                                + (event.attempt() + 1) + "/" + max + ")",
                        event.waitMs()));
            }
        } catch (final RuntimeException e) {
            // listener failures must not break the request
        }
    }

    /** Compact "status + provider message" for the retry notice; empty when not parseable. */
    private static String failureSummary(final String reason) {
        if (reason == null || !reason.startsWith("LLM request failed (")) {
            return "";
        }
        final var end = reason.indexOf(')');
        if (end < 0) {
            return "";
        }
        final var status = reason.substring("LLM request failed (".length(), end);
        final var body = reason.substring(end + 2).trim();
        final var cut = bodyName(body);
        return status + (cut.isEmpty() ? "" : " " + cut);
    }

    private static String bodyName(final String body) {
        // keep the provider "message" field when present, else a short head of the payload
        final var marker = "\"message\":\"";
        final var i = body.indexOf(marker);
        if (i >= 0) {
            final var start = i + marker.length();
            final var j = body.indexOf('"', start);
            return j >= 0 ? clamp(body.substring(start, j)) : clamp(body.substring(start));
        }
        return clamp(body);
    }

    private static String clamp(final String value) {
        return value == null || value.isBlank()
                ? ""
                : (value.length() <= 120 ? value : value.substring(0, 117) + "...");
    }

    /** The configured model of the active provider (no per-session override applied). */
    public String model() {
        return model(null);
    }

    /**
     * The effective model for a session: its explicit override when set, else the active
     * provider's model. {@code settings} is the per-session {@link ChatSettings}, may be null.
     */
    public String model(final ChatSettings settings) {
        final var override = settings == null ? null : settings.model();
        if (override != null && !override.isBlank()) {
            return override;
        }
        final var provider = provider(settings);
        return provider == null || provider.model() == null ? "" : provider.model();
    }

    /** The currently selected variant name, or null when none is forced (implicit family defaults apply). */
    public String variant() {
        return null;
    }

    /** The variant selected by a session, or null when none is forced. */
    public String variant(final ChatSettings settings) {
        return settings == null ? null : settings.variant();
    }

    /**
     * The in-memory sampling overrides for the current model (session only, never persisted), or
     * null when none are set.
     */
    public SamplingOverride sampling() {
        return null;
    }

    /** The sampling overrides of a session, or null when none are set. */
    public SamplingOverride sampling(final ChatSettings settings) {
        return settings == null ? null : settings.sampling();
    }

    /** Variants available for the current model, provider-level or per-model, else the implicit tuner presets. */
    public List<ModelVariantConfiguration> variants() {
        return variants((ChatSettings) null);
    }

    /** Variants available for the session's current model. */
    public List<ModelVariantConfiguration> variants(final ChatSettings settings) {
        final var provider = provider(settings);
        if (provider == null) {
            return List.of();
        }
        var model = settings == null ? null : settings.model();
        if (model == null || model.isBlank()) {
            model = provider.model();
        }
        return effectiveVariants(provider, model);
    }

    /** Variants available for the specific model name against the session's provider, without changing the
     * active model. Used to resolve the variants of a model being typed or picked in {@code /model}
     * before it becomes the active one. Blank model falls back to the current model.
     */
    public List<ModelVariantConfiguration> variants(final String model) {
        return variants(null, model);
    }

    /** Variants for {@code model} against the session's provider ({@code settings}), blank model falls back to the current one. */
    public List<ModelVariantConfiguration> variants(final ChatSettings settings, final String model) {
        final var provider = provider(settings);
        if (provider == null) {
            return List.of();
        }
        final var effective = model == null || model.isBlank() ? model(settings) : model.trim();
        return effectiveVariants(provider, effective);
    }

    /**
     * The effective implicit "default" variant for the current model, i.e. the sampling values the
     * tuner would apply when no named variant and no override is set, or null when the model has no
     * implicit defaults. The trio respects a current in-memory override: a width-omitted field (none)
     * surfaces as null so callers know it would not be sent; a forced value surfaces as that value.
     */
    public ModelVariantConfiguration implicitVariant() {
        return implicitVariant((ChatSettings) null);
    }

    /**
     * The effective implicit "default" variant for the session's current model, i.e. the sampling values the
     * tuner would apply when no named variant and no override is set, or null when the model has no
     * implicit defaults. The trio respects the session's override: a width-omitted field (none)
     * surfaces as null so callers know it would not be sent; a forced value surfaces as that value.
     */
    public ModelVariantConfiguration implicitVariant(final ChatSettings settings) {
        final var provider = provider(settings);
        final var providerId = provider == null ? null : provider.id();
        var model = settings == null ? null : settings.model();
        if (model == null || model.isBlank()) {
            model = provider == null ? null : provider.model();
        }
        if (model == null) {
            return null;
        }
        Double temperature = DefaultModelTuner.temperature(model, providerId);
        Double topP = DefaultModelTuner.topP(model, providerId);
        Double topK = DefaultModelTuner.topK(model, providerId);
        final var sampling = sampling(settings);
        if (sampling != null) {
            final var t = sampling.get(SamplingField.TEMPERATURE);
            if (t != null) {
                temperature = t.orElse(null);
            }
            final var p = sampling.get(SamplingField.TOP_P);
            if (p != null) {
                topP = p.orElse(null);
            }
            final var k = sampling.get(SamplingField.TOP_K);
            if (k != null) {
                topK = k.orElse(null);
            }
        }
        if (temperature == null && topP == null && topK == null) {
            return null;
        }
        return new ModelVariantConfiguration("default", temperature, topP, topK, null, null, null);
    }

    /**
     * The variant name that will actually be applied for a session: its explicit selection when set, else the
     * configured defaultVariant of the effective model when it exists in the effective variant list, else null
     * (implicit family defaults). Display/status helper; the request path applies the same rule in selectedVariant.
     */
    public String resolvedVariant(final ChatSettings settings) {
        final var explicit = settings == null ? null : settings.variant();
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        final var provider = provider(settings);
        final var model = model(settings);
        final var configured = provider == null ? null : providerManager.defaultVariant(provider, model);
        if (configured == null || configured.isBlank()) {
            return null;
        }
        for (final var variant : effectiveVariants(provider, model)) {
            if (configured.equals(variant.name())) {
                return configured;
            }
        }
        return null;
    }

    /**
     * The implicit {@code default} variant for a specific model name against the current provider,
     * without changing the active model. A blank model falls back to the current model. The current
     * in-memory sampling override is not applied here since the model is not yet the active one.
     */
    public ModelVariantConfiguration implicitVariant(final String model) {
        final var provider = provider(null);
        final var providerId = provider == null ? null : provider.id();
        if (model == null || model.isBlank()) {
            return implicitVariant();
        }
        final var m = model.trim();
        Double temperature = DefaultModelTuner.temperature(m, providerId);
        Double topP = DefaultModelTuner.topP(m, providerId);
        Double topK = DefaultModelTuner.topK(m, providerId);
        if (temperature == null && topP == null && topK == null) {
            return null;
        }
        return new ModelVariantConfiguration("default", temperature, topP, topK, null, null, null);
    }

    public void setProvider(final String id) {
        providerManager.setProvider(id);
        final var providerId = providerManager.currentProviderId();
        // invalidate only the entries of the provider being switched (per-key, not a clear()):
        // concurrent request threads may still be using other providers' clients, and a bare
        // clear() racing with computeIfAbsent() could resurrect a stale client under the new
        // provider configuration. The next dialectClient() re-builds this provider's client.
        dialectClients
                .keySet()
                .removeIf(key -> key.provider() != null
                        && providerId != null
                        && providerId.equals(key.provider().id()));
        resiliences
                .keySet()
                .removeIf(key -> key.provider() != null
                        && providerId != null
                        && providerId.equals(key.provider().id()));
        // the new provider never observed the response route or structured output yet
        this.resolvedFlavors.remove(providerId);
        // clear the structured-output memoization entries of this provider (keys are
        // "provider:model:schema", so a plain remove(providerId) would never match)
        this.structuredOutputRejected.keySet().removeIf(k -> k.startsWith(providerId + ":"));
        this.cachedModelsByProvider.remove(providerId);
    }

    public String providerId() {
        return providerId(null);
    }

    /** The effective provider id for a session: the per-tab override when set, else the process default. */
    public String providerId(final ChatSettings settings) {
        final var provider = provider(settings);
        if (provider == null || provider.id() == null || provider.id().isBlank()) {
            return providerManager == null ? "" : providerManager.currentProviderId();
        }
        return provider.id();
    }

    /** The currently active provider configuration (may be null when nothing is configured). */
    public ProviderConfiguration currentProvider() {
        return provider(null);
    }

    public ProviderConfiguration currentProvider(final ChatSettings settings) {
        return provider(settings);
    }

    /**
     * The effective provider for a session: its explicit override when set, else the process-wide
     * current provider. {@code settings} is the per-session {@link ChatSettings}, may be null.
     */
    public ProviderConfiguration provider(final ChatSettings settings) {
        final var override = settings == null ? null : settings.providerId();
        if (override != null && !override.isBlank()) {
            return providerManager.provider(override);
        }
        return providerManager == null ? null : providerManager.current();
    }

    /**
     * The resolved context window (tokens) for the current provider + model: an explicit per-model
     * {@code models[].contextWindow} wins, then the per-provider {@code contextWindow}, then the value
     * detected from {@code /v1/models}/cached; {@code 0} when unknown. Used by callers that must stay
     * under the model input budget (e.g. batch sizing).
     */
    public int contextWindow() {
        return contextWindow(null);
    }

    /** The session's context window when the settings carry a provider override, else the process default's. */
    public int contextWindow(final ChatSettings settings) {
        final var provider = provider(settings);
        if (provider == null) {
            return 0;
        }
        final var model = model(settings);
        final var override = providerManager == null ? 0 : providerManager.contextWindowOverride(provider, model);
        if (override > 0) {
            return override;
        }
        if (provider.contextWindow() > 0) {
            return provider.contextWindow();
        }
        final var id = providerIdOf(provider);
        return contextWindowCache == null ? 0 : contextWindowCache.getInt(id, model);
    }

    /**
     * The resolved output token budget for the current provider + model, using the same rules as
     * {@link #applyOutputBudget(ChatCompletionRequest, ApiFlavor)} but with no explicit request cap
     * (per-model override, per-provider default, /v1/models-detected, then {@code 32000}).
     */
    public int outputTokens() {
        return outputTokens(null);
    }

    /** The session's output budget when the settings carry a provider override, else the process default's. */
    public int outputTokens(final ChatSettings settings) {
        final var provider = provider(settings);
        if (provider == null) {
            return OutputBudgetResolver.DEFAULT_MAX_TOKEN;
        }
        final var model = model(settings);
        final var id = providerIdOf(provider);
        final var detectedMax = contextWindowCache == null
                ? null
                : contextWindowCache.getMaxTokens(id, model).orElse(null);
        final Integer contextWindow = provider.contextWindow() > 0 ? provider.contextWindow() : null;
        final var resolvedWindow = contextWindow != null
                ? contextWindow
                : (contextWindowCache == null ? null : contextWindowCache.getInt(id, model));
        return OutputBudgetResolver.resolve(
                null,
                providerManager.outputTokensOverride(provider, model),
                provider.outputTokens(),
                detectedMax == null ? null : detectedMax.value(),
                resolvedWindow);
    }

    /** The request's session selection, never null (defaults to {@link ChatSettings#EMPTY}). */
    private static ChatSettings settings(final ChatCompletionRequest request) {
        return request == null || request.settings() == null ? ChatSettings.EMPTY : request.settings();
    }

    /** The provider currently targeted by the in-flight request: the session's effective provider
     * (per-tab override, then process current) unless a {@code $provider} ref routed it. */
    private ProviderConfiguration target(final ChatCompletionRequest request) {
        return provider(settings(request));
    }

    /**
     * Resolve the concrete model for a request: an explicit request model wins (plain name or
     * {@code $provider/model}), then the request's session selection ({@link ChatSettings#model()}),
     * then the effective provider's configured model. A {@code $provider/model} reference
     * additionally routes the request to that provider, returned as the rebased request carrying a
     * routing {@link ChatSettings} (request-scoped: no shared mutable routing state, so two tabs on
     * different providers never route each other's calls).
     */
    private RoutedRequest resolveModel(final ChatCompletionRequest request) throws IOException {
        final var settings = settings(request);
        final var provider = provider(settings);
        final var explicit = request.model();
        final var override = settings.model();
        final var ref = explicit != null && !explicit.isBlank()
                ? explicit
                : (override != null && !override.isBlank() ? override : (provider == null ? null : provider.model()));
        final var resolvedModel =
                providerManager == null ? new ProviderManager.ResolvedModel(null, ref) : providerManager.resolve(ref);
        var routed = request;
        final var routedProviderId = resolvedModel.provider() == null ? null : providerIdOf(resolvedModel.provider());
        if (ref != null && ref.startsWith("$") && routedProviderId != null) {
            // an explicit $provider/model reference routes this request to that provider: record it
            // in the request's own settings so every later read (target(), applyOutputBudget, ...)
            // sees the same routing without any cross-request shared state
            routed = request.withSettings(
                    new ChatSettings(routedProviderId, settings.model(), settings.variant(), settings.sampling()));
        }
        final var model = resolvedModel.model();
        if (model == null || model.isBlank()) {
            throw new IOException(
                    "no model configured: set yca.provider.model (--yca-provider-model, configuration.json or YCA_PROVIDER_MODEL)");
        }
        return new RoutedRequest(routed, model.trim());
    }

    /** A request rebased onto its resolved routing (provider in the settings) plus the concrete model name. */
    private record RoutedRequest(ChatCompletionRequest request, String model) {}

    /** Resolve the presentation against the very same session routing and variant as the request. */
    public boolean compressionBoundaryOnly(final ChatCompletionRequest request) throws IOException {
        if (providerManager == null) {
            return false;
        }
        final var routed = resolveModel(request);
        final var provider = target(routed.request());
        final var mode = providerManager.compressionBoundaryOnly(provider, routed.model());
        return mode != BoundaryOnlyRefMode.DISABLED;
    }

    /** Local projection only: never invokes outbound observers or a token-count endpoint. */
    public ContextMeasurement.Payload contextPayload(final ChatCompletionRequest request, final UsageTracker tracker)
            throws IOException {
        if (providerManager == null || mapper == null) {
            return tracker.payload(
                    String.valueOf(request.settings()) + ":" + request.model(),
                    String.valueOf(request.tools()),
                    request.messages(),
                    TokenEstimator.estimate(String.valueOf(request.tools())));
        }
        final var defaults = useModel(request);
        final var provider = provider(defaults.settings());
        final var flavor = flavorForRouting(defaults.settings());
        final var effective = applyClaudeThinking(applyOutputBudget(defaults, flavor), flavor);
        return contextPayloadEffective(effective, provider, flavor);
    }

    private ContextMeasurement.Payload contextPayloadEffective(
            final ChatCompletionRequest effective, final ProviderConfiguration provider, final ApiFlavor flavor) {
        final var wire = LlmWire.toLlmRequest(
                effective,
                openaiSessionIdEnabledKeys(provider, flavor),
                flavor == ApiFlavor.COMPLETIONS && provider.openaiCapabilities().replayReasoning());
        final var bytes = dialectClient(flavor, provider).requestPayload(wire);
        final var body = mapper.fromString(Map.class, new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        final var input = body.containsKey("input") ? body.get("input") : body.get("messages");
        final var messages = new ArrayList<String>();
        for (final var key : List.of("system", "instructions")) {
            if (body.get(key) != null) {
                messages.add(mapper.toString(Map.of(key, body.get(key))));
            }
        }
        if (input instanceof List<?> list) {
            messages.addAll(list.stream().map(mapper::toString).toList());
        } else if (input != null) {
            messages.add(mapper.toString(input));
        }
        final var tools = mapper.toString(body.getOrDefault("tools", List.of()));
        final var shape = tools + ":" + mapper.toString(body.get("tool_choice"));
        long estimate = TokenEstimator.estimate(tools);
        for (final var message : messages) {
            estimate += 4L + TokenEstimator.estimate(message);
        }
        return new ContextMeasurement.Payload(
                providerIdOf(provider) + ":" + effective.model() + ":" + flavor, shape, messages, (int)
                        Math.min(Integer.MAX_VALUE, estimate));
    }

    private ChatCompletionRequest useModel(final ChatCompletionRequest request) throws IOException {
        return withPromotedExtras(applyModelDefaults(request));
    }

    /**
     * Enrich a request with the resolved model plus its effective tuning defaults (provider-level and
     * per-model {@code providerSpecificOptions}), an optional selected named variant, and the implicit
     * per-family sampling defaults. Every facet follows a put-if-absent semantic: an explicit request
     * value wins, then the selected variant, then the implicit family default; nothing is forced.
     * The provider routing is resolved here so the variant and defaults are those of the target model.
     */
    private ChatCompletionRequest applyModelDefaults(final ChatCompletionRequest request) throws IOException {
        final var routed = resolveModel(request);
        final var model = routed.model();
        final var request_ = routed.request();
        final var provider = target(request_);
        final var providerId = provider == null ? null : provider.id();
        final var others = new LinkedHashMap<String, Object>();
        if (request.others() != null) {
            others.putAll(request.others());
        }
        final var providerOptions = providerManager.providerOptions(provider);
        if (!providerOptions.isEmpty()) {
            // every providerSpecificOptions entry rides the wire: the previously-excluded internal
            // consumer options (codex_client_version, reasoning_include) now live in capabilities
            others.putAll(providerOptions);
        }
        final var tuning = providerManager.tuning(provider, model);
        final var tuningOptions = providerManager.tuningOptions(tuning);
        deepMergePutIfAbsent(others, tuningOptions);
        final var variant = selectedVariant(provider, model, request);
        if (variant != null && variant.extra() != null && variant.extra().startsWith("{")) {
            // A reasoning config carried by providerSpecificOptions (provider- or model-level) fully
            // overrides the selected variant's one: the variant's thinking/output_config extras must not
            // fill in missing sub-keys of an explicitly configured reasoning shape (explicit config wins).
            deepMergePutIfAbsent(others, withoutUserReasoningConfig(parseRaw(variant.extra()), others));
        }

        final var temperature = resolveSampling(
                SamplingField.TEMPERATURE,
                request.temperature(),
                "temperature",
                false,
                others,
                variant == null ? null : variant::temperature,
                DefaultModelTuner::temperature,
                model,
                providerId,
                request);
        final var topP = resolveSampling(
                SamplingField.TOP_P,
                request.topP(),
                "top_p",
                false,
                others,
                variant == null ? null : variant::topP,
                DefaultModelTuner::topP,
                model,
                providerId,
                request);
        final var topK = resolveSampling(
                SamplingField.TOP_K,
                null,
                "top_k",
                true,
                others,
                variant == null ? null : variant::topK,
                DefaultModelTuner::topK,
                model,
                providerId,
                request);
        if (topK != null) {
            others.put("top_k", topK);
        }
        Integer maxTokens = request.maxTokens();
        if (maxTokens == null && variant != null && variant.maxTokens() != null && variant.maxTokens() > 0) {
            final var flavor = effectiveFlavor();
            if (flavor == ApiFlavor.ANTHROPIC || flavor == ApiFlavor.AUTO) {
                maxTokens = variant.maxTokens();
            } else {
                others.putIfAbsent("max_completion_tokens", variant.maxTokens());
            }
        }
        final var reasoningEffort = resolveReasoningEffort(
                request.reasoningEffort(),
                others,
                model,
                variant == null ? null : variant.reasoningEffort(),
                providerManager.reasoningEffort(provider, model),
                provider);
        // the thinking budget depends on the resolved max_tokens (the API requires
        // 1024 <= budget < max_tokens), so the injection happens in doWithFallback after
        // applyOutputBudget rather than here
        return new ChatCompletionRequest(
                model,
                applyCaching(request_.messages(), providerId),
                temperature,
                topP,
                request_.stream(),
                request_.tools(),
                request_.toolChoice(),
                maxTokens,
                reasoningEffort,
                others,
                request_.sessionId(),
                request_.settings());
    }

    /**
     * Resolves the effective value of one sampling field for the request, folding in any in-memory
     * override plus the selected variant and the implicit per-family tuner default. Precedence within a
     * field: an in-memory override of {@code none} (force-omit) beats everything; then the explicit
     * request value; then, when a raw value was already supplied through {@code others} and the caller
     * opts in via {@code respectOthersGuard}, that raw value is kept; then the override value; then the
     * variant value; finally the {@link DefaultModelTuner} value. When the override is {@code none} any
     * previously injected raw value for {@code wireKey} is dropped so the payload omits the field and the
     * provider default applies.
     */
    private Double resolveSampling(
            final SamplingField field,
            final Double requestValue,
            final String wireKey,
            final boolean respectOthersGuard,
            final Map<String, Object> others,
            final Supplier<Double> variantValue,
            final BiFunction<String, String, Double> tuner,
            final String model,
            final String providerId,
            final ChatCompletionRequest request) {
        final var sampling = sampling(request.settings());
        final var overridden = sampling == null ? null : sampling.get(field);
        if (overridden != null && overridden.isEmpty()) {
            // none: force-omit, so the server default applies; drop any request/raw value
            if (wireKey != null) {
                others.remove(wireKey);
            }
            return null;
        }
        if (requestValue != null) {
            return requestValue;
        }
        if (respectOthersGuard && wireKey != null && others.containsKey(wireKey)) {
            return null;
        }
        if (overridden != null && overridden.isPresent()) {
            return overridden.get();
        }
        if (variantValue != null) {
            return variantValue.get();
        }
        return tuner.apply(model, providerId);
    }

    /**
     * Message-level prompt caching, highly inspired from opencode's applyCaching: the first two system
     * messages and the last two non-system messages carry a provider-specific cache breakpoint so the
     * stable prefix (system prompt, tools) and the tail (turning context) become cache hits across turns.
     * The cache key depends on the provider family. Array-content messages are cached at each block level,
     * plain-text messages at the message level. Disabled for providers without a caching story.
     */
    private static List<Message> applyCaching(final List<Message> messages, final String providerId) {
        if (messages == null || messages.size() <= 1 || providerId == null) {
            return messages;
        }
        final var cache = cacheMarker(providerId);
        if (cache == null) {
            return messages;
        }
        final var systems = new ArrayList<Message>();
        final var others = new ArrayList<Message>();
        for (final var m : messages) {
            if ("system".equals(m.role())) {
                systems.add(m);
            } else {
                others.add(m);
            }
        }
        if (systems.isEmpty()) {
            return messages;
        }
        final var marked = new HashSet<Integer>();
        for (int i = 0; i < systems.size() && i < 2; i++) {
            marked.add(i);
        }
        final int lastIdx = messages.size() - 1;
        if (others.size() > 1) {
            // within the flat list, mark the two non-system tail messages
            for (int i = 0; i < others.size() && i < 2; i++) {
                marked.add(lastIdx - i);
            }
        }
        if (marked.isEmpty()) {
            return messages;
        }
        var changed = false;
        final var out = new Message[messages.size()];
        for (final var idx : marked) {
            final var m = messages.get(idx);
            final var updated = cacheMessage(m, cache);
            out[idx] = updated;
            changed |= updated != m;
        }
        if (!changed) {
            return messages;
        }
        for (int i = 0; i < messages.size(); i++) {
            if (out[i] == null) {
                out[i] = messages.get(i);
            }
        }
        return List.of(out);
    }

    /**
     * The provider-specific cache breakpoint marker, or null when this provider does not expose message
     * caching. The key mirrors the target dialect's wire field: <ul>
     * <li>Anthropic-like and most OpenAI-compatible proxies use {@code cache_control} with
     * {@code {"type":"ephemeral"}};</li>
     * <li>Bedrock providers use a {@code cachePoint} flag.</li></ul>
     */
    private static Map<String, Object> cacheMarker(final String providerId) {
        final var id = providerId.toLowerCase(Locale.ROOT);
        if (id.contains("bedrock")) {
            return Map.of("cachePoint", true, "mapping", "cachePoint");
        }
        if (id.contains("anthropic") || id.contains("openrouter") || id.contains("alibaba")) {
            return Map.of("cache_control", Map.of("type", "ephemeral"), "mapping", "cache_control");
        }
        return null;
    }

    private static Message cacheMessage(final Message message, final Map<String, Object> cache) {
        final var key = String.valueOf(cache.get("mapping"));
        final Object marker = cache.get(key);
        final var content = message.content();
        if (content instanceof List<?> blocks && !blocks.isEmpty()) {
            final Object last = blocks.get(blocks.size() - 1);
            if (last instanceof Map<?, ?> lastBlock) {
                final var outBlocks = new ArrayList<Map<String, Object>>();
                for (int i = 0; i < blocks.size() - 1; i++) {
                    @SuppressWarnings("unchecked")
                    final var m = (Map<String, Object>) blocks.get(i);
                    outBlocks.add(m);
                }
                final var block = new LinkedHashMap<String, Object>();
                block.putAll(lastBlock.entrySet().stream()
                        .collect(
                                LinkedHashMap<String, Object>::new,
                                (collected, entry) -> collected.put(String.valueOf(entry.getKey()), entry.getValue()),
                                Map::putAll));
                block.put(key, marker);
                outBlocks.add(block);
                return new Message(
                        message.role(),
                        outBlocks,
                        message.name(),
                        message.toolCalls(),
                        message.toolCallId(),
                        message.others());
            }
        }
        final var others = message.others();
        if (others != null && others.containsKey(key)) {
            return message;
        }
        final Map<String, Object> merged = new LinkedHashMap<>();
        if (others != null) {
            merged.putAll(others);
        }
        merged.put(key, marker);
        return new Message(
                message.role(), message.content(), message.name(), message.toolCalls(), message.toolCallId(), merged);
    }

    /** The named variant requested via {@code /model}, validated against the configured variants, else null. When the
     * session selected none, the provider-configured {@code defaultVariant} of the model applies when it exists in the
     * effective variant list; when it matches nothing a warning is logged once and the implicit defaults apply (never a failure). */
    private ModelVariantConfiguration selectedVariant(
            final ProviderConfiguration provider, final String model, final ChatCompletionRequest request) {
        final var explicit =
                request.settings() == null ? null : request.settings().variant();
        final var configured = providerManager.defaultVariant(provider, model);
        final var selected = (explicit == null || explicit.isBlank()) && configured != null && !configured.isBlank()
                ? configured
                : explicit;
        if (selected == null || selected.isBlank()) {
            return null;
        }
        for (final var variant : effectiveVariants(provider, model)) {
            if (selected.equals(variant.name())) {
                return variant;
            }
        }
        if (explicit == null || explicit.isBlank()) { // only config-sourced defaults get the warn-once treatment
            final var key = (provider == null ? "?" : provider.id()) + '\u0000' + model + '\u0000' + selected;
            if (warnedDefaultVariants.add(key)) {
                LOGGER.warning(() -> "defaultVariant '" + selected + "' of model '" + model + "' of provider '"
                        + (provider == null ? "?" : provider.id())
                        + "' matches no variant; falling back to the implicit defaults.");
            }
        }
        return null;
    }

    /**
     * The variants to consider for a model: the configured ones (per-model or provider-level), or,
     * when none are configured, the implicit reasoning-effort presets derived from the model family
     * and the API flavor of the target provider.
     */
    private List<ModelVariantConfiguration> effectiveVariants(
            final ProviderConfiguration provider, final String model) {
        final var configured = providerManager.variants(provider, model);
        if (!configured.isEmpty()) {
            return configured;
        }
        final var flavor = flavorOf(provider);
        final var releaseDate = provider == null ? null : provider.releaseDate();
        final var budget = outputTokens(provider, model);
        final var catalog = catalogModel(provider, model);
        final var catalogEfforts = catalog == null ? List.<String>of() : catalog.supportedReasoningLevels();
        return DefaultModelTuner.variants(new DefaultModelTuner.ModelContext(
                model, provider == null ? null : provider.id(), flavor, budget, releaseDate, catalogEfforts));
    }

    /**
     * Resolve the request flavor from a provider: an explicitly resolved flavor wins, then the configured
     * provider flavor, then the flavor deduced from the provider base URL route, defaulting to the plain
     * OpenAI-compatible chat wire (chat completions) when it cannot be pinned.
     */
    private static ApiFlavor flavorOf(final ProviderConfiguration provider) {
        if (provider == null) {
            return ApiFlavor.COMPLETIONS;
        }
        final var configured = provider.api();
        if (configured != null && configured != ApiFlavor.AUTO) {
            return configured;
        }
        final var detected = ChatRoutes.detectFlavor(provider.baseUrl());
        return detected == null ? ApiFlavor.COMPLETIONS : detected;
    }

    /** The resolved output budget for an explicit provider + model, like {@link #outputTokens()} but unbound to the active model. */
    private int outputTokens(final ProviderConfiguration provider, final String model) {
        if (provider == null) {
            return OutputBudgetResolver.DEFAULT_MAX_TOKEN;
        }
        final var detectedMax = contextWindowCache == null
                ? null
                : contextWindowCache.getMaxTokens(provider.id(), model).orElse(null);
        final Integer contextWindow = provider.contextWindow() > 0 ? provider.contextWindow() : null;
        final var resolvedWindow = contextWindow != null
                ? contextWindow
                : (contextWindowCache == null ? null : contextWindowCache.getInt(provider.id(), model));
        return OutputBudgetResolver.resolve(
                null,
                providerManager.outputTokensOverride(provider, model),
                provider.outputTokens(),
                detectedMax == null ? null : detectedMax.value(),
                resolvedWindow);
    }

    /**
     * Resolve the effective {@code reasoning_effort} with put-if-absent: an explicit request value wins,
     * then a value already merged in the extra payload is left untouched, then the selected variant default,
     * then the per-model/provider tuning default, then the implicit per-family default. When the payload
     * already carries one, null keeps the dedicated field unset so the provider option flows to the wire
     * through the extras.
     */
    private String resolveReasoningEffort(
            final String requestEffort,
            final Map<String, Object> merged,
            final String model,
            final String variantEffort,
            final String configEffort,
            final ProviderConfiguration provider) {
        if (requestEffort != null && !requestEffort.isBlank()) {
            return requestEffort;
        }
        if (merged != null && merged.containsKey("reasoning_effort")) {
            return null;
        }
        if (variantEffort != null && !variantEffort.isBlank()) {
            return variantEffort;
        }
        if (configEffort != null && !configEffort.isBlank()) {
            return configEffort;
        }
        final var catalog = catalogModel(provider, model);
        final var catalogEffort = catalog == null ? null : catalog.defaultReasoningLevel();
        if (catalogEffort != null && !catalogEffort.isBlank()) {
            return catalogEffort;
        }
        // The Codex backend (chatgpt.com) streams no reasoning unless the request asks for it, and the
        // upstream Codex CLI always sends one. Default an unconfigured codex session to "medium" so a
        // thought block is actually produced; this matches the user-selected alignment with OpenAI's
        // documented best starting point and is put-if-absent (an explicit variant/config still wins above).
        if (flavorOf(provider) == ApiFlavor.CODEX) {
            return "medium";
        }
        // opencode parity: do not force an implicit reasoning effort on the wire. The provider's
        // own default applies unless the user selected a variant or configured one explicitly
        // (an unset deepeesk-v4 used to fall back to "max", which made freeinference emit tens of
        // thousands of reasoning characters per call). The implicit per-family effort remains
        // available for display/selection through DefaultModelTuner variants, not as a wire default.
        return null;
    }

    /**
     * Whether a claude id can use extended thinking: everything from 3.7 on, but the plain 3.x
     * family has no thinking support at all (a {@code thinking} request field would be rejected).
     * Unknown/newer ids stay optimistic.
     */
    private static boolean isThinkingCapableClaude(final String model) {
        if (model == null || !model.startsWith("claude")) {
            return false;
        }
        final var lower = model.toLowerCase(Locale.ROOT);
        return !(lower.startsWith("claude-3-") || lower.startsWith("claude-3.") || lower.equals("claude-3"));
    }

    /**
     * Injects an Anthropic thinking budget derived from the resolved reasoning effort and the
     * effective output budget, honoring the API invariants: {@code 1024 <= budget_tokens < max_tokens}.
     * An explicit {@code thinking} entry (a selected variant, a user/tuning config) always wins, and a
     * budget that cannot satisfy the invariant skips thinking rather than failing the request.
     */
    private static void applyClaudeThinking(
            final Map<String, Object> others, final String effort, final Integer maxTokens) {
        if (others.containsKey("thinking")) {
            return; // a variant/config-provided thinking configuration owns the field
        }
        final var target =
                switch (effort) {
                    case "low" -> 1024;
                    case "high", "xhigh", "max" -> 8192;
                    default -> 2048;
                };
        final var budget = maxTokens == null || maxTokens <= 0 ? target : Math.min(target, maxTokens - 1);
        if (budget < 1024) {
            return; // no room for thinking within the output budget
        }
        others.put("thinking", Map.of("type", "enabled", "budget_tokens", budget));
    }

    /**
     * Parse a raw JSON object string into a map, empty when not parseable as an object.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseRaw(final String json) {
        if (json == null || json.isBlank() || !json.startsWith("{")) {
            return Map.of();
        }
        final var parsed = mapper.fromString(Object.class, json);
        return parsed instanceof Map ? (Map<String, Object>) parsed : Map.of();
    }

    /** Deep-merge {@code overrides} into {@code target}: nested maps are merged recursively, leaves are put-if-absent. */
    @SuppressWarnings("unchecked")
    private static void deepMergePutIfAbsent(final Map<String, Object> target, final Map<String, Object> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return;
        }
        for (final var entry : overrides.entrySet()) {
            final var existing = target.get(entry.getKey());
            if (existing instanceof Map && entry.getValue() instanceof Map) {
                deepMergePutIfAbsent((Map<String, Object>) existing, (Map<String, Object>) entry.getValue());
            } else if (!target.containsKey(entry.getKey())) {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Reasoning-config keys for which an explicitly configured value (provider- or model-level
     * {@code providerSpecificOptions}) fully overrides the selected variant extras. When the variant
     * extras (eg. the implicit anthropic adaptive-thinking shape) and an explicit reasoning config both
     * target these keys, the explicit one wins verbatim: the variant must not fill in missing sub-keys
     * (such as a {@code display}) of an explicitly configured object.
     */
    private static final Set<String> REASONING_CONFIG_KEYS = Set.of("thinking", "output_config");

    /**
     * The variant extras without the reasoning-config keys the user already supplied in {@code merged}.
     * This enforces the "explicit provider config overrides the variant reasoning shape" rule: keys the
     * user configured are left to the user's value (full replacement), everything else from the variant
     * still applies.
     *
     * @param variant map parsed from a variant {@code extra} payload.
     * @param merged the request extras already merged with the provider/tuning options, may be empty.
     * @return a shallow copy of {@code variant} without its reasoning-config keys when {@code merged}
     *         already carries them, otherwise {@code variant} unchanged.
     */
    private static Map<String, Object> withoutUserReasoningConfig(
            final Map<String, Object> variant, final Map<String, Object> merged) {
        if (variant == null || variant.isEmpty()) {
            return variant;
        }
        boolean override = false;
        for (final var key : REASONING_CONFIG_KEYS) {
            if (merged.containsKey(key)) {
                override = true;
                break;
            }
        }
        if (!override) {
            return variant;
        }
        final var filtered = new HashMap<>(variant);
        REASONING_CONFIG_KEYS.forEach(filtered::remove);
        return filtered;
    }

    /**
     * Promote the well-known generic options carried in the request extras ({@code temperature} and
     * {@code max_tokens}/{@code max_output_tokens}, eg. from {@code providerSpecificOptions}) into the
     * typed request fields so every dialect honours them - notably the output-budget resolution which
     * only reads the typed field - and strip them from the extras so the wire payload never carries
     * duplicate keys. A typed field wins when both are set.
     */
    private static ChatCompletionRequest withPromotedExtras(final ChatCompletionRequest request) {
        final var extras = request.others();
        final var hasPromoted = extras != null
                && (extras.containsKey("temperature")
                        || extras.containsKey("max_tokens")
                        || extras.containsKey("max_output_tokens")
                        || extras.containsKey("top_p"));
        final var hasReserved = extras != null && RESERVED_WIRE_KEYS.stream().anyMatch(extras::containsKey);
        if (extras == null || extras.isEmpty() || (!hasPromoted && !hasReserved)) {
            return request;
        }
        Double temperature = request.temperature();
        if (temperature == null && extras.get("temperature") instanceof Number n) {
            temperature = n.doubleValue();
        }
        Integer maxTokens = request.maxTokens();
        if (maxTokens == null) {
            maxTokens = extrasNumber(extras, "max_tokens", "max_output_tokens");
        }
        Double topP = request.topP();
        if (topP == null && extras.get("top_p") instanceof Number n) {
            topP = n.doubleValue();
        }
        final var stripped = new LinkedHashMap<String, Object>(extras.size());
        extras.forEach((k, v) -> {
            if ("temperature".equals(k)
                    || "max_tokens".equals(k)
                    || "max_output_tokens".equals(k)
                    || "top_p".equals(k)) {
                return; // promoted into the typed fields, never sent twice
            }
            if (RESERVED_WIRE_KEYS.contains(k)) {
                // a free-form option must never override a protocol field: the payload would carry
                // both the typed value and a duplicate from the extras (the dialect encoder flattens
                // the latter last), silently flipping behavior like streaming for every call
                LOGGER.warning(() -> "ignoring reserved provider option '" + k
                        + "' (provider/providerOptions/variant config): it would override the request field");
                return;
            }
            stripped.put(k, v);
        });
        return new ChatCompletionRequest(
                request.model(),
                request.messages(),
                temperature,
                topP,
                request.stream(),
                request.tools(),
                request.toolChoice(),
                maxTokens,
                request.reasoningEffort(),
                stripped,
                request.sessionId(),
                request.settings());
    }

    /** Protocol fields that free-form options must never override (duplicate-key protection). */
    private static final Set<String> RESERVED_WIRE_KEYS =
            Set.of("stream", "model", "messages", "system", "tools", "tool_choice", "tool_calls");

    private static Integer extrasNumber(final Map<String, Object> extras, final String... keys) {
        for (final var key : keys) {
            if (extras.get(key) instanceof Number n) {
                final var value = n.intValue();
                return value > 0 ? value : null;
            }
        }
        return null;
    }

    /**
     * Resolve the effective output budget and, when it differs from the request, return a copy carrying
     * it. Anthropic always requires a {@code max_tokens}, resolved from the request, the configuration,
     * the provider metadata or the 32000 fallback. OpenAI flavors are only given a value when one is
     * explicitly set (request, per-model override or provider default): a detected or fallback value is
     * deliberately not force-sent there so proxies governing their own server-side default keep working.
     */
    /**
     * Injects the Anthropic thinking budget once the output budget is resolved (the API requires
     * {@code 1024 <= budget_tokens < max_tokens}). Runs only for the Anthropic flavor and only for
     * thinking-capable claude ids, so an OpenAI-compatible gateway fronting a claude never receives
     * a non-standard {@code thinking} field, and an explicit variant/config thinking entry wins.
     */
    private ChatCompletionRequest applyClaudeThinking(final ChatCompletionRequest request, final ApiFlavor flavor) {
        if (flavor != ApiFlavor.ANTHROPIC
                || request.reasoningEffort() == null
                || !isThinkingCapableClaude(request.model())
                || request.others().containsKey("thinking")) {
            return request;
        }
        final var others = new LinkedHashMap<String, Object>(request.others());
        applyClaudeThinking(others, request.reasoningEffort(), request.maxTokens());
        if (others.size() == request.others().size()) {
            return request; // no room for thinking within the resolved budget
        }
        return new ChatCompletionRequest(
                request.model(),
                request.messages(),
                request.temperature(),
                request.topP(),
                request.stream(),
                request.tools(),
                request.toolChoice(),
                request.maxTokens(),
                request.reasoningEffort(),
                others,
                request.sessionId(),
                request.settings());
    }

    private ChatCompletionRequest applyOutputBudget(final ChatCompletionRequest request, final ApiFlavor flavor) {
        if (flavor == null || flavor == ApiFlavor.AUTO) {
            return request;
        }
        final var provider = target(request);
        final var model = request.model();
        final var perModel = providerManager.outputTokensOverride(provider, model);
        final var perProvider = provider.outputTokens();
        final var hasRequestBudget = request.maxTokens() != null && request.maxTokens() > 0;
        if (flavor != ApiFlavor.ANTHROPIC && perModel <= 0 && perProvider <= 0 && !hasRequestBudget) {
            return request;
        }
        final var providerId = providerIdOf(provider);
        Integer contextWindow = provider.contextWindow() > 0 ? provider.contextWindow() : null;
        if (contextWindow == null) {
            contextWindow = contextWindowCache.getInt(providerId, model);
        }
        final var detectedMaxTokens = flavor == ApiFlavor.ANTHROPIC
                ? contextWindowCache.getMaxTokens(providerId, model).orElse(null)
                : null;
        final var decision = OutputBudgetResolver.resolveTracked(
                request.maxTokens(),
                perModel,
                perProvider,
                detectedMaxTokens == null ? null : detectedMaxTokens.value(),
                contextWindow);
        // the Anthropic API caps max_tokens per model family; only auto-resolved budgets (fallback or
        // detected /v1/models value) are clamped, an explicit request/configured value always wins
        final var budget = flavor == ApiFlavor.ANTHROPIC ? anthropicCeiling(model, decision) : decision.budget();
        logBudgetOnce(flavor, providerId, model, decision, request.sessionId());
        final var maxTokens = request.maxTokens();
        if (maxTokens != null && budget == maxTokens) {
            return request;
        }
        return new ChatCompletionRequest(
                model,
                request.messages(),
                request.temperature(),
                request.topP(),
                request.stream(),
                request.tools(),
                request.toolChoice(),
                budget,
                request.reasoningEffort(),
                request.others(),
                request.sessionId(),
                request.settings());
    }

    /**
     * Clamps an auto-resolved Anthropic output budget to the documented per-family ceiling: sending
     * the generic 32 000 fallback to a claude with a smaller cap is an immediate 400. Explicit
     * request/configured budgets never go through here, and unknown/non-claude ids keep the generic
     * default so only the documented families are touched.
     */
    private static int anthropicCeiling(final String model, final OutputBudgetResolver.Decision decision) {
        final var source = decision.source();
        if (!(source.startsWith("fallback-") || source.startsWith("detected"))) {
            return decision.budget(); // request/configured value: always wins
        }
        final var ceiling = claudeOutputCeiling(model);
        return ceiling > 0 ? Math.min(decision.budget(), ceiling) : decision.budget();
    }

    /** Documented {@code max_tokens} ceilings for claude ids, 0 when unknown (no clamping). */
    private static int claudeOutputCeiling(final String model) {
        if (model == null || !model.startsWith("claude")) {
            return 0;
        }
        final var lower = model.toLowerCase(Locale.ROOT);
        if (lower.contains("claude-3-5") || lower.contains("claude-3.5")) {
            return 8_192; // claude 3.5 sonnet/haiku
        }
        if (lower.contains("claude-3-")) {
            return 4_096; // claude 3 opus/sonnet/haiku
        }
        if (lower.contains("claude-4-0")
                || lower.contains("claude-4.0")
                || lower.contains("claude-4-1")
                || lower.contains("claude-4.1")) {
            return lower.contains("opus") ? 32_000 : 8_192;
        }
        if (lower.contains("claude-4-5")
                || lower.contains("claude-4.5")
                || lower.contains("claude-4-6")
                || lower.contains("claude-4.6")) {
            return 64_000;
        }
        if (lower.contains("claude-4-7") || lower.contains("claude-4.7") || lower.contains("claude-5")) {
            return 128_000;
        }
        return 0; // unknown claude: keep the generic budget
    }

    /**
     * One-shot INFO line per (flavor, provider, model) exposing the resolved output budget and where
     * it comes from, so a misconfigured budget can be diagnosed without tracing requests.
     */
    private void logBudgetOnce(
            final ApiFlavor flavor,
            final String providerId,
            final String model,
            final OutputBudgetResolver.Decision decision,
            final String sessionId) {
        if (budgetLogged.add(flavor.name() + '|' + providerId + '|' + model)) {
            LOGGER.log(SessionAwareLogRecord.of(
                    LOGGER,
                    sessionId,
                    Level.INFO,
                    "Output budget for " + providerId + '/' + model + " ["
                            + flavor.name().toLowerCase(Locale.ROOT) + "]: " + decision.budget()
                            + " tokens (source: " + decision.source() + ")"));
        }
    }

    public ChatResult chat(final ChatCompletionRequest request) throws IOException, InterruptedException {
        return chat(request, () -> false);
    }

    public ChatResult chat(final ChatCompletionRequest request, final BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return chat(request, null, cancelled);
    }

    public ChatResult chat(
            final ChatCompletionRequest request,
            final Consumer<PauseEvent> pauseListener,
            final BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        // the retry-mode marker rides the request's own `others` map (see withRetryMode); it is
        // stripped before the request reaches the wire / model resolution so it can never leak
        // into the provider payload. COOPERATIVE skips the wrapper's internal retry loop (the
        // caller owns retries); DELEGATED (absent) is the default conversation behavior.
        final var others = request.others();
        final Object mode = others == null ? null : others.get(RETRY_MODE_OTHERS_KEY);
        if (mode == null || mode == RetryMode.DELEGATED) {
            return doWithFallback(useModel(request), pauseListener, cancelled, false, null, false, false, false);
        }
        final var stripped = new ChatCompletionRequest(
                request.model(),
                request.messages(),
                request.temperature(),
                request.topP(),
                request.stream(),
                request.tools(),
                request.toolChoice(),
                request.maxTokens(),
                request.reasoningEffort(),
                stripRetryMode(others),
                request.sessionId(),
                request.settings());
        return doWithFallback(useModel(stripped), pauseListener, cancelled, false, null, false, false, true);
    }

    /**
     * Reserved {@code others} key carrying the call's {@link RetryMode} (see
     * {@link ChatCompletionRequest#withRetryMode(RetryMode)}); stripped in {@link #chat} before the
     * request reaches model resolution or the wire, so it never leaks into the provider payload.
     */
    static final String RETRY_MODE_OTHERS_KEY = "_yca_retry_mode";

    private static Map<String, Object> stripRetryMode(final Map<String, Object> others) {
        final var out = new LinkedHashMap<>(others);
        out.remove(RETRY_MODE_OTHERS_KEY);
        return out;
    }

    /**
     * Result of a structured-output call. {@code structured} is true when the provider honoured the
     * JSON Schema and the response is expected to be a conformant JSON object; false when the schema
     * was unsupported or rejected and the caller must fall back to its own parsing.
     */
    public record StructuredResult(ChatResult result, boolean structured) {}

    /**
     * Run a non-streaming completion asking the provider for structured output matching the given JSON
     * Schema. The flavor-specific request field is composed here (response_format for completions,
     * text.format for the responses API, output_config for Anthropic) and carried to the wire through
     * the request extras. If the provider rejects the schema with a 400 it is retried once without it;
     * the rejection is cached temporarily per provider, model and schema, so subsequent calls go
     * straight to the plain path until the cache entry expires.
     */
    public StructuredResult chatStructured(
            final ChatCompletionRequest request, final String schemaName, final Map<String, Object> schema)
            throws IOException, InterruptedException {
        final var routed = resolveModel(request);
        final var model = routed.model();
        final var routedReq = routed.request();
        final var flavor = effectiveFlavor(routedReq.settings());
        final var base = new LinkedHashMap<String, Object>();
        if (routedReq.others() != null) {
            base.putAll(routedReq.others());
        }
        final var plainReq = new ChatCompletionRequest(
                model,
                routedReq.messages(),
                routedReq.temperature(),
                routedReq.topP(),
                false,
                routedReq.tools(),
                routedReq.toolChoice(),
                routedReq.maxTokens(),
                routedReq.reasoningEffort(),
                base,
                routedReq.sessionId(),
                routedReq.settings());
        final var structuredReq = new ChatCompletionRequest(
                model,
                routedReq.messages(),
                routedReq.temperature(),
                routedReq.topP(),
                false,
                routedReq.tools(),
                routedReq.toolChoice(),
                routedReq.maxTokens(),
                routedReq.reasoningEffort(),
                withStructuredField(base, flavor, schemaName, schema),
                routedReq.sessionId(),
                routedReq.settings());
        final var providerId = providerIdFor(routedReq);
        final var rejectionKey = providerId + ":" + model + ":" + schemaName;
        if (isStructuredRejected(rejectionKey)) {
            return new StructuredResult(chatInternal(withPromotedExtras(applyModelDefaults(plainReq))), false);
        }
        try {
            return new StructuredResult(chatInternal(withPromotedExtras(applyModelDefaults(structuredReq))), true);
        } catch (final IOException first) {
            if (isSchemaRejection(first)) {
                // the provider rejected the JSON Schema (e.g. Anthropic 400 'json was malformed' or a
                // response-parse failure of the structured attempt): degrade once to the plain request
                // and remember it per (provider, model, schema) so a model that does not support
                // structured output stops burning a rejection round-trip per call. The memory is
                // time-bounded (see STRUCTURED_REJECTION_TTL_MILLIS): a transient 400 can never
                // permanently disable structured output for a working provider/model.
                structuredOutputRejected.put(rejectionKey, nowMillis());
                return new StructuredResult(chatInternal(withPromotedExtras(applyModelDefaults(plainReq))), false);
            }
            throw first;
        }
    }

    /**
     * Whether an {@link IOException} from the structured attempt is a JSON-Schema rejection (the plain
     * fallback is the right recovery) rather than a transport/config failure that must surface.
     *
     * <p>A 400 whose body talks about the request body JSON being unparseable, a missing/malformed
     * {@code max_tokens}, or a generic gateway "Invalid JSON or schema" must NOT be treated as a
     * structured-output schema rejection: the plain fallback reuses the same (invalid) request, so
     * degrading cannot help and only hides the real cause. Only the markers that specifically name a
     * JSON-Schema/structured-output incompatibility trigger the plain fallback.</p>
     */
    private static boolean isSchemaRejection(final IOException e) {
        if (isStatus400(e)) {
            final var m = e.getMessage();
            final var lower = m == null ? "" : m.toLowerCase(Locale.ROOT);
            // body markers of a schema/structured-output rejection (Anthropic 'json was malformed',
            // output_config/output_format, an explicit json_schema/invalid-schema marker, and
            // OpenAI-compatible gateways that cannot provide constrained output - eg. NInfer's
            // 'response_format_not_supported ... requires constrained output ... only {"type":"text"}'
            // - which must degrade to the plain request exactly like a schema rejection).
            final var schemaRejection = lower.contains("json was malformed")
                    || lower.contains("output_config")
                    || lower.contains("output_format")
                    || lower.contains("json_schema")
                    || lower.contains("invalid schema")
                    || lower.contains("not a valid json schema")
                    || lower.contains("unsupported schema")
                    || lower.contains("response_format_not_supported")
                    || lower.contains("constrained output")
                    || lower.contains("only {\"type\":\"text\"}");
            if (schemaRejection) {
                // guard: the generic "Invalid JSON or schema in request body" (a body-JSON parse /
                // max_tokens / config error, e.g. freeinference rejecting max_tokens:-1) contains the
                // word 'schema' but is NOT a structured-output schema rejection. Exclude the body-parse
                // and token-budget phrases explicitly.
                return !isBodyParseOrBudgetError(lower);
            }
            return false;
        }
        // a response-parse failure (e.g. the gateway answered SSE with no usable frame) is also a
        // structured-output failure of this provider/model/schema: degrade once
        return e instanceof LlmResponseParseException;
    }

    /** Whether a 400 message reflects an unparseable/malformed request body or a token-budget/config error. */
    private static boolean isBodyParseOrBudgetError(final String lower) {
        return lower.contains("invalid json")
                || lower.contains("not valid json")
                || lower.contains("body is not valid")
                || lower.contains("max_tokens")
                || lower.contains("max tokens");
    }

    /**
     * Whether the structured-output rejection memoization currently holds {@code key}. Entries older
     * than {@link #STRUCTURED_REJECTION_TTL_MILLIS} are forgotten on read, so a provider/model whose
     * structured support recovers (or which was transiently rejected) is retried instead of being
     * permanently degraded.
     */
    private boolean isStructuredRejected(final String key) {
        final var recorded = structuredOutputRejected.get(key);
        if (recorded == null) {
            return false;
        }
        if (nowMillis() - recorded >= STRUCTURED_REJECTION_TTL_MILLIS) {
            structuredOutputRejected.remove(key, recorded);
            return false;
        }
        return true;
    }

    private long nowMillis() {
        return clock == null ? System.currentTimeMillis() : clock.millis();
    }

    private static boolean isStatus400(final IOException e) {
        final var m = e.getMessage();
        return m != null && m.startsWith("LLM request failed (400)");
    }

    /**
     * Transforms a caller JSON Schema into the subset Anthropic structured outputs accepts (the same
     * normalization the official SDKs apply): unsupported validation keywords are removed and folded
     * into the sibling {@code description}, every object gains {@code additionalProperties:false}
     * (required by the API), string formats are restricted to the supported set and the recursion
     * covers {@code properties}/{@code items}/{@code $defs}. Idempotent: a schema already in that
     * shape comes back unchanged.
     */
    public static Map<String, Object> sanitizeSchema(final Map<String, Object> schema) {
        if (schema == null) {
            return null;
        }
        final var out = new LinkedHashMap<String, Object>();
        final var unsupported = new LinkedHashMap<String, Object>();
        for (final var entry : schema.entrySet()) {
            final var key = entry.getKey();
            final var value = entry.getValue();
            switch (key) {
                case "minimum",
                        "maximum",
                        "exclusiveMinimum",
                        "exclusiveMaximum",
                        "minLength",
                        "maxLength",
                        "minItems",
                        "maxItems",
                        "minProperties",
                        "maxProperties",
                        "pattern",
                        "multipleOf",
                        "uniqueItems",
                        "default" -> unsupported.put(key, value);
                case "format" -> {
                    if (value instanceof String s && SUPPORTED_FORMATS.contains(s)) {
                        out.put(key, value);
                    } else {
                        unsupported.put(key, value);
                    }
                }
                case "properties" -> out.put(key, sanitizeProperties(value));
                case "items" -> out.put(key, sanitizeItems(value));
                case "$defs", "definitions" -> out.put(key, sanitizeProperties(value));
                case "additionalProperties" -> {
                    // only meaningful on objects: keep the caller value when boolean, force false otherwise
                    if (value instanceof Boolean b) {
                        out.put(key, b);
                    } else {
                        unsupported.put("additionalProperties", value);
                    }
                }
                default -> out.put(key, value);
            }
        }
        // required by Anthropic: every object must set additionalProperties:false
        if ("object".equals(out.get("type")) && !out.containsKey("additionalProperties")) {
            out.put("additionalProperties", false);
        }
        // surface dropped constraints to the model instead of silently losing them
        if (!unsupported.isEmpty()) {
            final var description = new StringBuilder(out.get("description") instanceof String s ? s : "");
            if (description.length() > 0) {
                description.append(' ');
            }
            description.append("Constraints: ").append(unsupported);
            out.put("description", description.toString());
        }
        return out;
    }

    private static Map<String, Object> sanitizeProperties(final Object properties) {
        if (!(properties instanceof Map<?, ?> map)) {
            return Map.of();
        }
        final var out = new LinkedHashMap<String, Object>();
        for (final var entry : map.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> sub) {
                out.put(String.valueOf(entry.getKey()), sanitizeSchema(castMap(sub)));
            } else {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static Object sanitizeItems(final Object items) {
        if (items instanceof Map<?, ?> map) {
            return sanitizeSchema(castMap(map));
        }
        return items; // tuple/array forms are passed through (rare, and the API rejects unknown shapes)
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(final Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /**
     * Transforms a caller JSON Schema into the OpenAI strict-mode shape that Responses-wire backends
     * (the OpenAI {@code /responses} API and the ChatGPT Codex backend) enforce when {@code strict:
     * true}: every object must set {@code additionalProperties:false} and every property in {@code
     * properties} must be listed in {@code required}, with fields the caller did not actually require
     * expressed as a nullable type ({@code ["<type>", "null"]}) so the model still emits them but may
     * set them to {@code null}. Unsupported strict-mode keywords are dropped (see the strict-output
     * keyword list) and the recursion covers {@code properties}/{@code items}/{$defs}. Idempotent: a
     * schema already in that shape comes back unchanged.
     */
    public static Map<String, Object> strictSchema(final Map<String, Object> schema) {
        if (schema == null) {
            return null;
        }
        final var rawRequired = schema.get("required");
        final var required = new HashSet<String>();
        if (rawRequired instanceof List<?> list) {
            for (final var r : list) {
                if (r instanceof String s) {
                    required.add(s);
                }
            }
        }
        final var out = new LinkedHashMap<String, Object>();
        for (final var entry : schema.entrySet()) {
            final var key = entry.getKey();
            final var value = entry.getValue();
            switch (key) {
                // strict mode: unsupported validation keywords are dropped (they would be a 400)
                case "minProperties",
                        "maxProperties",
                        "unevaluatedProperties",
                        "propertyNames",
                        "unevaluatedItems",
                        "contains",
                        "minContains",
                        "maxContains",
                        "uniqueItems" -> {
                    // dropped
                }
                case "properties" -> out.put(key, strictProperties(value, required));
                case "items", "$defs", "definitions" -> {
                    if (value instanceof Map<?, ?> sub) {
                        out.put(key, strictSchema(castMap(sub)));
                    } else {
                        out.put(key, value);
                    }
                }
                case "required" -> {
                    // recomputed below from properties, guaranteeing every property is listed
                }
                default -> out.put(key, value);
            }
        }
        // every object must opt out of additional properties and require all its fields
        if ("object".equals(out.get("type"))) {
            out.put("additionalProperties", false);
            final var props = out.get("properties");
            if (props instanceof Map<?, ?> map) {
                final var names = map.keySet().stream().map(String::valueOf).toList();
                out.put("required", names);
            } else if (!required.isEmpty()) {
                out.put("required", new ArrayList<>(required));
            }
        }
        return out;
    }

    /** Normalize every property value, widening non-required fields to a nullable type. */
    private static Map<String, Object> strictProperties(final Object properties, final Set<String> required) {
        if (!(properties instanceof Map<?, ?> map)) {
            return Map.of();
        }
        final var out = new LinkedHashMap<String, Object>();
        for (final var entry : map.entrySet()) {
            final var name = String.valueOf(entry.getKey());
            final var value = entry.getValue();
            if (value instanceof Map<?, ?> sub) {
                final var normalized = strictSchema(castMap(sub));
                if (!required.contains(name)) {
                    out.put(name, nullableType(normalized));
                } else {
                    out.put(name, normalized);
                }
            } else {
                out.put(name, value);
            }
        }
        return out;
    }

    /**
     * Widen a strict-normalized property schema to a nullable type ({@code type: ["<type>", "null"]})
     * so the field satisfies the all-properties-required rule while staying semantically optional.
     */
    private static Map<String, Object> nullableType(final Map<String, Object> schema) {
        if (schema == null) {
            return new LinkedHashMap<>(Map.of("type", "null"));
        }
        final var out = new LinkedHashMap<String, Object>(schema);
        final var t = schema.get("type");
        if (t instanceof String s) {
            out.put("type", List.of(s, "null"));
        } else if (t instanceof List<?> list) {
            final var widened = new ArrayList<Object>(list);
            if (!widened.contains("null")) {
                widened.add("null");
            }
            out.put("type", widened);
        }
        return out;
    }

    private static final Set<String> SUPPORTED_FORMATS = Set.of(
            "date-time",
            "time",
            "date",
            "duration",
            "email",
            "hostname",
            "ipv4",
            "ipv6",
            "uuid",
            "uri",
            "uri-reference",
            "binary",
            "byte",
            "password",
            "int32",
            "int64",
            "float",
            "double");

    /** Compose the flavor-specific JSON Schema field into a copy of {@code others}. */
    private static Map<String, Object> withStructuredField(
            final Map<String, Object> others,
            final ApiFlavor flavor,
            final String schemaName,
            final Map<String, Object> schema) {
        final var out = new LinkedHashMap<String, Object>(others);
        switch (flavor) {
            case ANTHROPIC -> {
                // deep-merge into a possibly existing output_config (eg. an effort or thinking
                // setting coming from providerSpecificOptions/variant extras): replacing the whole
                // object would silently drop those options
                final var outputConfig = new LinkedHashMap<String, Object>();
                final var existing = out.get("output_config");
                if (existing instanceof Map<?, ?> map) {
                    for (final var entry : map.entrySet()) {
                        outputConfig.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                outputConfig.put("format", Map.of("type", "json_schema", "schema", sanitizeSchema(schema)));
                out.put("output_config", outputConfig);
            }
            // RESPONSES and CODEX share the Responses wire ({@code text.format}): both backends
            // enforce OpenAI strict-mode JSON Schemas (every object additionalProperties:false and
            // every property in required), so the schema is normalized with strictSchema().
            case RESPONSES, CODEX ->
                out.put(
                        "text",
                        Map.of(
                                "format",
                                Map.of(
                                        "type",
                                        "json_schema",
                                        "name",
                                        schemaName,
                                        "schema",
                                        strictSchema(schema),
                                        "strict",
                                        true)));
            // COMPLETIONS and the AUTO fallback use the chat-completions response_format wire, which enforces
            // OpenAI strict-mode JSON Schemas (every object additionalProperties:false and every property in
            // required), so the schema is normalized with strictSchema() like RESPONSES/CODEX - otherwise a
            // strict validator (eg. Azure / litellm) rejects it with a 400 'required is required ... Missing p'.
            case COMPLETIONS ->
                out.put(
                        "response_format",
                        Map.of(
                                "type",
                                "json_schema",
                                "json_schema",
                                Map.of("name", schemaName, "schema", strictSchema(schema), "strict", true)));
            default -> {
                // AUTO already resolved to a concrete flavor by the caller; treat as completions fallback
                out.put(
                        "response_format",
                        Map.of(
                                "type",
                                "json_schema",
                                "json_schema",
                                Map.of("name", schemaName, "schema", strictSchema(schema), "strict", true)));
            }
        }
        return out;
    }

    /** Route a request through the current flavor, reusing the shared dispatch logic (process default settings). */
    /** Route a request through the session's flavor/provider (the request carries the selection). */
    private ChatResult chatInternal(final ChatCompletionRequest request) throws IOException, InterruptedException {
        return doWithFallback(request, null, () -> false, false, null);
    }

    /**
     * Applies the configured tool call arguments repair to an assistant message when the effective
     * provider/model enable it, and hoists in-band tool-call markup (DSML and its manglings) leaked
     * into the text channel into structured tool calls when the provider produced no native one.
     * Never changes the message when nothing was repaired.
     */
    private Message repairToolCalls(
            final Message message, final ChatCompletionRequest effective, final ProviderConfiguration provider) {
        final var model = effective.model();
        var toolCalls = message.toolCalls();
        Object content = message.content();
        // Reasoning strip (single source of truth with the display): remove the model's inline
        // reasoning from the stored/resent content so it is never replayed on the next turn. The
        // resolved format drives how reasoning is delimited; the default think-tags handling is
        // idempotent on the stream path (which already stripped via visibleText) and fixes the
        // non-stream gap.
        if (content instanceof String s && providerManager != null) {
            final var format = provider == null
                    ? ReasoningFormatMode.AUTO.resolve()
                    : providerManager.reasoningFormat(provider, model).resolve();
            final var stripped = ReasoningStripper.strip(s, format);
            if (!stripped.isEmpty() && !stripped.equals(s)) {
                content = stripped;
            }
        }
        // In-band markup hoist: when the provider emitted no native tool call but the model wrote
        // one in the text channel (DSML-style), extract it and strip the markup from the stored
        // content. Structured calls always win; the extractor's fast path makes a clean prose
        // answer free (parse returns empty and nothing changes).
        if ((toolCalls == null || toolCalls.isEmpty()) && inBandEnabled(provider, model)) {
            final var raw = content instanceof String s ? s : content == null ? "" : content.toString();
            final var inBand = inBandToolCallExtractor.parse(raw);
            if (!inBand.isEmpty()) {
                toolCalls = inBand;
                final var stripped = inBandToolCallExtractor.strip(raw);
                if (!stripped.equals(raw)) {
                    content = stripped.isBlank() ? null : stripped;
                }
            }
        }
        if (toolCalls == null || toolCalls.isEmpty()) {
            // probe-only answer: the only possible change is the reasoning strip, surfaced here so a
            // prose-only completion still gets its inline reasoning removed from the stored content
            return content == message.content() ? message : newMessage(message, content, null);
        }
        var changed = content != message.content();
        final var repaired = new ArrayList<ToolCall>(toolCalls.size());
        final var repairArgs =
                provider != null && providerManager != null && providerManager.repairToolCallArguments(provider, model);
        for (final var call : toolCalls) {
            final var fn = call == null ? null : call.function();
            if (call == null || fn == null) {
                repaired.add(call);
                continue;
            }
            final var arguments =
                    repairArgs ? toolCallArgumentsRepairer.repair(fn.arguments(), true, model) : fn.arguments();
            if (Objects.equals(arguments, fn.arguments())) {
                repaired.add(call);
            } else {
                changed = true;
                repaired.add(new ToolCall(
                        call.id(), call.type(), new ToolCall.FunctionCall(fn.name(), arguments), call.index()));
            }
        }
        return changed ? newMessage(message, content, repaired) : message;
    }

    /**
     * Whether in-band tool-call markup should be extracted for the effective provider/model. The
     * AUTO mode (default) always attempts extraction when no native call was produced; it is a pure
     * safety net that leaves clean prose untouched (the extractor returns empty and the message is
     * byte-identical).
     */
    private boolean inBandEnabled(final ProviderConfiguration provider, final String model) {
        return provider == null || providerManager == null || providerManager.inBandToolCalls(provider, model);
    }

    /** Rebuilds an assistant message with a (possibly null) content and tool-call list, preserving identity metadata. */
    private static Message newMessage(final Message message, final Object content, final List<ToolCall> toolCalls) {
        return new Message(
                message.role(),
                content,
                message.name(),
                toolCalls == null || toolCalls.isEmpty() ? null : toolCalls,
                message.toolCallId(),
                message.others());
    }

    private ChatResult repairToolCalls(
            final ChatResult result, final ChatCompletionRequest effective, final ProviderConfiguration provider) {
        final var message = repairToolCalls(result.message(), effective, provider);
        return message == result.message() ? result : new ChatResult(message, result.usage());
    }

    public Message stream(final ChatCompletionRequest request, final Consumer<LlmStreamEvent> onDelta)
            throws IOException, InterruptedException {
        return stream(request, onDelta, Cancellations.NEVER);
    }

    public Message stream(
            final ChatCompletionRequest request,
            final Consumer<LlmStreamEvent> onDelta,
            final BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return stream(request, null, onDelta, cancelled);
    }

    /** Stream a completion for the request's selection ({@link ChatCompletionRequest#settings()}). */
    public Message stream(
            final ChatCompletionRequest request,
            final Consumer<PauseEvent> pauseListener,
            final Consumer<LlmStreamEvent> onDelta,
            final BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        final var routed = resolveModel(request);
        final var effective = applyModelDefaults(new ChatCompletionRequest(
                routed.model(),
                request.messages(),
                request.temperature(),
                request.topP(),
                true,
                request.tools(),
                request.toolChoice(),
                request.maxTokens(),
                request.reasoningEffort(),
                request.others(),
                request.sessionId(),
                routed.request().settings()));
        return doWithFallback(withPromotedExtras(effective), pauseListener, cancelled, true, onDelta);
    }

    private <A> A doWithFallback(
            final ChatCompletionRequest request,
            final Consumer<PauseEvent> pauseListener,
            final BooleanSupplier cancelled,
            final boolean stream,
            final Consumer<LlmStreamEvent> onDelta)
            throws IOException, InterruptedException {
        return doWithFallback(request, pauseListener, cancelled, stream, onDelta, false, false, false);
    }

    @SuppressWarnings("unchecked")
    private <A> A doWithFallback(
            final ChatCompletionRequest request,
            final Consumer<PauseEvent> pauseListener,
            final BooleanSupplier cancelled,
            final boolean stream,
            final Consumer<LlmStreamEvent> onDelta,
            final boolean reasoningAlreadyDropped,
            final boolean argumentsAlreadyRepaired,
            final boolean cooperative)
            throws IOException, InterruptedException {
        final Consumer<LlmStreamEvent> onDeltaSafe = onDelta == null ? event -> {} : onDelta;
        final var settings = request.settings();
        final var provider = provider(settings);
        final var flavor = flavorForRouting(request.settings());
        final var effective = applyClaudeThinking(applyOutputBudget(request, flavor), flavor);
        final Consumer<io.yupiik.llm.client.PauseEvent> libraryListener =
                pauseListener == null ? null : event -> onLibraryPause(event, pauseListener);
        final var dialect = dialectClient(flavorForRouting(settings), provider);
        try {
            if (contextCapture.isBound()) {
                final var capture = contextCapture.get();
                capture.accept(contextPayloadEffective(effective, provider, flavor));
            }
            if (stream) {
                final var publisher = LlmWire.join(
                        dialect.stream(
                                LlmWire.toLlmRequest(
                                        effective,
                                        openaiSessionIdEnabledKeys(provider, flavor),
                                        flavor == ApiFlavor.COMPLETIONS
                                                && provider.openaiCapabilities().replayReasoning()),
                                cancelled,
                                libraryListener),
                        cancelled,
                        LlmWire::discard);
                return (A) repairToolCalls(
                        LlmWire.pump(publisher, onDeltaSafe, cancelled, effective.sessionId()), effective, provider);
            }
            return (A) repairToolCalls(
                    LlmWire.toChatResult(LlmWire.join(dialect.chat(
                            LlmWire.toLlmRequest(
                                    effective,
                                    openaiSessionIdEnabledKeys(provider, flavor),
                                    flavor == ApiFlavor.COMPLETIONS
                                            && provider.openaiCapabilities().replayReasoning()),
                            cancelled,
                            libraryListener,
                            cooperative))),
                    effective,
                    provider);
        } catch (final DialectNotSupportedException e) {
            final var providerId = providerIdOf(provider);
            if (resolvedFlavors.getOrDefault(providerId, ApiFlavor.AUTO) != ApiFlavor.AUTO) {
                throw e; // explicitly configured flavor: surface the incompatibility
            }
            // AUTO probed the responses route first: pin the fallback for this provider
            resolvedFlavors.put(providerId, ApiFlavor.COMPLETIONS);
            return doWithFallback(
                    request,
                    pauseListener,
                    cancelled,
                    stream,
                    onDelta,
                    reasoningAlreadyDropped,
                    argumentsAlreadyRepaired,
                    cooperative);
        } catch (final IOException e) {
            // The Codex backend rejects a request carrying a stale reasoning blob (its encryption keys
            // rotated on the server, or the model changed) with 400 invalid_encrypted_content. Drop the
            // opaque reasoning items from the history and retry once without them rather than failing the
            // whole turn; the model simply re-derives its reasoning.
            if (!reasoningAlreadyDropped && isInvalidEncryptedContent(flavor, e)) {
                return doWithFallback(
                        stripReasoningItems(request),
                        pauseListener,
                        cancelled,
                        stream,
                        onDelta,
                        true,
                        argumentsAlreadyRepaired,
                        cooperative);
            }
            if (!argumentsAlreadyRepaired
                    && provider != null
                    && providerManager != null
                    && providerManager.repairToolCallArguments(provider, effective.model())
                    && e instanceof LlmHttpFailureException failure
                    && failure.status() == 400
                    && failure.body() != null
                    && failure.body().contains("tool_calls")
                    && failure.body().contains("arguments")) {
                final var repaired = repairHistoryArguments(request, effective.model());
                if (repaired != request) {
                    return doWithFallback(
                            repaired,
                            pauseListener,
                            cancelled,
                            stream,
                            onDelta,
                            reasoningAlreadyDropped,
                            true,
                            cooperative);
                }
            }
            throw e;
        }
    }

    /**
     * True when the failure is a stale encrypted-reasoning rejection on a Responses-family wire (the blob
     * was minted under server keys that rotated, or the model changed): the backend refuses to decrypt it.
     * Applies to any Responses dialect now that {@code include:["reasoning.encrypted_content"]} is requested
     * uniformly (ChatGPT Codex, Azure AI, gateways).
     */
    private static boolean isInvalidEncryptedContent(final ApiFlavor flavor, final IOException error) {
        if ((flavor != ApiFlavor.CODEX && flavor != ApiFlavor.RESPONSES)
                || !(error instanceof LlmHttpFailureException failure)) {
            return false;
        }
        return failure.status() == 400
                && failure.body() != null
                && failure.body().contains("invalid_encrypted_content");
    }

    /** Returns {@code request} with the opaque reasoning items stripped from every assistant message. */
    private static ChatCompletionRequest stripReasoningItems(final ChatCompletionRequest request) {
        if (request.messages() == null || request.messages().isEmpty()) {
            return request;
        }
        var changed = false;
        final var cleaned = new ArrayList<Message>(request.messages().size());
        for (final var message : request.messages()) {
            final var others = message.others();
            if (others != null && !others.isEmpty() && others.containsKey(LlmWire.REASONING_ITEMS_KEY)) {
                final var reduced = new LinkedHashMap<String, Object>();
                for (final var e : others.entrySet()) {
                    if (!LlmWire.REASONING_ITEMS_KEY.equals(e.getKey())) {
                        reduced.put(e.getKey(), e.getValue());
                    }
                }
                cleaned.add(new Message(
                        message.role(),
                        message.content(),
                        message.name(),
                        message.toolCalls(),
                        message.toolCallId(),
                        reduced.isEmpty() ? null : reduced));
                changed = true;
            } else {
                cleaned.add(message);
            }
        }
        return changed
                ? new ChatCompletionRequest(
                        request.model(),
                        cleaned,
                        request.temperature(),
                        request.topP(),
                        request.stream(),
                        request.tools(),
                        request.toolChoice(),
                        request.maxTokens(),
                        request.reasoningEffort(),
                        request.others(),
                        request.sessionId(),
                        request.settings())
                : request;
    }

    /** Repairs replayed arguments only after the provider reports invalid tool-call arguments. */
    private ChatCompletionRequest repairHistoryArguments(final ChatCompletionRequest request, final String model) {
        if (request.messages() == null || request.messages().isEmpty()) {
            return request;
        }
        var changed = false;
        final var messages = new ArrayList<Message>(request.messages().size());
        for (final var message : request.messages()) {
            if (message == null
                    || message.toolCalls() == null
                    || message.toolCalls().isEmpty()) {
                messages.add(message);
                continue;
            }
            var messageChanged = false;
            final var calls = new ArrayList<ToolCall>(message.toolCalls().size());
            for (final var call : message.toolCalls()) {
                final var fn = call == null ? null : call.function();
                if (fn == null) {
                    calls.add(call);
                    continue;
                }
                final var arguments = toolCallArgumentsRepairer.repair(fn.arguments(), true, model);
                if (Objects.equals(arguments, fn.arguments())) {
                    calls.add(call);
                } else {
                    calls.add(new ToolCall(
                            call.id(), call.type(), new ToolCall.FunctionCall(fn.name(), arguments), call.index()));
                    messageChanged = true;
                }
            }
            if (messageChanged) {
                messages.add(new Message(
                        message.role(),
                        message.content(),
                        message.name(),
                        calls,
                        message.toolCallId(),
                        message.others()));
                changed = true;
            } else {
                messages.add(message);
            }
        }
        return changed
                ? new ChatCompletionRequest(
                        request.model(),
                        messages,
                        request.temperature(),
                        request.topP(),
                        request.stream(),
                        request.tools(),
                        request.toolChoice(),
                        request.maxTokens(),
                        request.reasoningEffort(),
                        request.others(),
                        request.sessionId(),
                        request.settings())
                : request;
    }

    /** The provider id used to key the per-provider caches; {@code "default"} when the provider is unresolved. */
    private String providerIdOf(final ProviderConfiguration provider) {
        final var id = provider == null || provider.id() == null ? "" : provider.id();
        return id.isBlank() ? "default" : id;
    }

    /** The provider id for a request's effective provider, for the per-provider caches. */
    private String providerIdFor(final ChatCompletionRequest request) {
        return providerIdOf(provider(request.settings()));
    }

    /**
     * Best-effort catalog entry for a model of the target provider, used to honor the reasoning
     * capabilities advertised by OpenAI-compatible {@code /models} payloads. The regular model cache
     * is reused; on a miss the listing is fetched lazily so {@code /variant}, persisted variants and
     * direct requests work even when the context window is configured (and {@code UsageTracker} never
     * lists the models). Failures are swallowed and retried after a short backoff: catalog metadata is
     * optional and must never break a request.
     */
    private ModelsResponse.Model catalogModel(final ProviderConfiguration provider, final String model) {
        if (provider == null || model == null || model.isBlank()) {
            return null;
        }
        final var flavor = flavorOf(provider);
        if (flavor != ApiFlavor.CODEX && flavor != ApiFlavor.RESPONSES && flavor != ApiFlavor.COMPLETIONS) {
            return null;
        }
        final var providerId = providerIdOf(provider);
        var cached = cachedModelsByProvider.get(providerId);
        if (cached == null) {
            final var retryAfter = modelCatalogRetryAfter.get(providerId);
            if (retryAfter != null && retryAfter > clock.millis()) {
                return null;
            }
            try {
                cached = LlmWire.toModelsResponse(
                        LlmWire.join(dialectClient(flavor, provider).models()));
                cachedModelsByProvider.put(providerId, cached);
                modelCatalogRetryAfter.remove(providerId);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                modelCatalogRetryAfter.put(providerId, clock.millis() + MODEL_CATALOG_FAILURE_BACKOFF_MILLIS);
                return null;
            } catch (final IOException | RuntimeException e) {
                modelCatalogRetryAfter.put(providerId, clock.millis() + MODEL_CATALOG_FAILURE_BACKOFF_MILLIS);
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(() ->
                            "Unable to load the model catalog of provider '" + providerId + "': " + e.getMessage());
                }
                return null;
            }
        }
        return findCatalogModel(cached, model);
    }

    private static ModelsResponse.Model findCatalogModel(final ModelsResponse response, final String model) {
        if (response == null || response.data() == null) {
            return null;
        }
        ModelsResponse.Model caseInsensitive = null;
        for (final var candidate : response.data()) {
            if (candidate == null || candidate.id() == null) {
                continue;
            }
            if (candidate.id().equals(model)) {
                return candidate;
            }
            if (caseInsensitive == null && candidate.id().equalsIgnoreCase(model)) {
                caseInsensitive = candidate;
            }
        }
        if (caseInsensitive != null) {
            return caseInsensitive;
        }
        // routing refs ($provider/model) and gateways exposing qualified ids
        for (final var candidate : response.data()) {
            if (candidate == null || candidate.id() == null) {
                continue;
            }
            if (model.endsWith("/" + candidate.id()) || candidate.id().endsWith("/" + model)) {
                return candidate;
            }
        }
        return null;
    }

    public ModelsResponse models() throws IOException, InterruptedException {
        return models(null);
    }

    /** The provider's model list; the effective provider is the session's when a settings override exists. */
    public ModelsResponse models(final ChatSettings settings) throws IOException, InterruptedException {
        return LlmWire.join(modelsAsync(settings));
    }

    /** Non-blocking catalog lookup. Cancellation is forwarded to the provider's model-list stage. */
    public CompletableFuture<ModelsResponse> modelsAsync(final ChatSettings settings) {
        final var provider = provider(settings);
        final var providerId = providerIdOf(provider);
        final var cached = cachedModelsByProvider.get(providerId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        final var flavor = flavorForRouting(settings);
        try {
            final var transport = dialectClient(flavor == ApiFlavor.AUTO ? ApiFlavor.COMPLETIONS : flavor, provider)
                    .models()
                    .toCompletableFuture();
            final var result = new CompletableFuture<ModelsResponse>();
            result.whenComplete((value, failure) -> {
                if (result.isCancelled()) {
                    transport.cancel(true);
                }
            });
            transport.whenComplete((value, failure) -> {
                if (result.isDone()) {
                    return;
                }
                var cause = failure;
                while (cause instanceof CompletionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                if (cause instanceof DialectNotSupportedException) {
                    result.complete(new ModelsResponse(List.of()));
                } else if (cause != null) {
                    result.completeExceptionally(cause);
                } else {
                    try {
                        final var fetched = LlmWire.toModelsResponse(value);
                        cachedModelsByProvider.put(providerId, fetched);
                        result.complete(fetched);
                    } catch (final RuntimeException e) {
                        result.completeExceptionally(e);
                    }
                }
            });
            return result;
        } catch (final RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    @Destroy
    public void close() {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("[yca-shutdown] ChatClient.close: closing dialect clients");
        }
        final var start = System.nanoTime();
        try {
            if (dialectClients != null) {
                dialectClients.values().forEach(DialectLlmClient::close);
            }
        } finally {
            if (ownsConcurrencyGates && concurrencyGates != null) {
                // release the leases even when closing a dialect client fails
                concurrencyGates.close();
            }
        }
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(() ->
                    "[yca-shutdown] ChatClient.close: done in " + (System.nanoTime() - start) / 1_000_000L + " ms");
        }
    }

    /**
     * In-memory sampling overrides keyed by field. Semantics per field:
     * <ul>
     * <li>key absent - not overridden; normal request -&gt; variant -&gt; implicit tuner precedence applies;</li>
     * <li>{@link Setting#none()} - force-omit: the field is dropped from the wire payload and the variant and
     * implicit tuner defaults are skipped, letting the server default apply;</li>
     * <li>a concrete {@link Setting#of(double)} - the field is set to that value.</li>
     * </ul>
     */
    public record SamplingOverride(Map<SamplingField, Optional<Double>> values) {

        public static SamplingOverride of(final Map<SamplingField, Optional<Double>> values) {
            return new SamplingOverride(values == null ? Map.of() : values);
        }

        public boolean isEmpty() {
            return values == null || values.isEmpty();
        }

        /** The overridden value for the field: null if unset, empty Optional for force-omit, present for a value. */
        public Optional<Double> get(final SamplingField field) {
            if (values == null) {
                return null;
            }
            return values.get(field);
        }
    }

    /** The sampling fields a user can override in-memory. */
    public enum SamplingField {
        TEMPERATURE,
        TOP_K,
        TOP_P
    }

    /** A setting value: {@link #none()} force-omits the field, {@link #of(double)} sets it. */
    public record Setting(Double value) {

        public static Setting none() {
            return new Setting(null);
        }

        public static Setting of(final double value) {
            return new Setting(value);
        }

        public boolean isNone() {
            return value == null;
        }

        public Optional<Double> asOptional() {
            return value == null ? Optional.empty() : Optional.of(value);
        }
    }
}
