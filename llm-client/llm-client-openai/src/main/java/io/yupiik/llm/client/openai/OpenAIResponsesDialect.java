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
package io.yupiik.llm.client.openai;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.llm.client.LlmResponseParseException;
import io.yupiik.llm.client.model.LlmMessage;
import io.yupiik.llm.client.model.LlmModels;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmResponse;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmToolCall;
import io.yupiik.llm.client.model.Usage;
import io.yupiik.llm.client.model.UsageExtensions;
import io.yupiik.llm.client.openai.model.ResponsesInput;
import io.yupiik.llm.client.openai.model.ResponsesRequest;
import io.yupiik.llm.client.openai.model.ResponsesTool;
import io.yupiik.llm.client.spi.AbstractDialect;
import io.yupiik.llm.client.spi.ProviderSpec;
import io.yupiik.llm.client.spi.SseParser;
import io.yupiik.llm.client.spi.StreamAttempt;
import io.yupiik.llm.client.spi.Streams;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

/** OpenAI {@code /responses} reactive dialect mapping the neutral model to the Responses wire format. */
public class OpenAIResponsesDialect extends AbstractDialect {

    private static final Logger LOGGER = Logger.getLogger(OpenAIResponsesDialect.class.getName());

    private volatile Consumer<String> onRawLine;
    // Per-stream mapping of Responses output_index -> coalescing bookkeeping for one tool call: the best
    // known neutral tool-call id (the Responses `call_id` when present, the item `id`/`item_id` fallback
    // otherwise) and whether nonempty arguments were already delivered (so the completion events only emit
    // assembled arguments as a fallback and never double-append). Keyed by output index because each stream
    // may contain several parallel function calls and the argument-delta events carry only the item_id,
    // which must not overwrite the canonical call_id. The map is created fresh per stream()/assemble() call
    // and captured by a per-call mapper, so two concurrent streams on the shared dialect can never observe
    // each other's bookkeeping (the old instance-level mutable set was a cross-stream data race).

    // Set once a backend rejects the encrypted-reasoning include (reasoning.include) and a retry without it
    // succeeded: from then on the dialect stops sending the directive for this provider, so each later request
    // no longer pays a guaranteed 400 + retry round-trip.
    private final AtomicBoolean encryptedReasoningRejected = new AtomicBoolean();

    /**
     * Ordered header key names under which the session-scoped {@code prompt_cache_key} (read back from the
     * request payload) is emitted as an HTTP header, eg. {@code session_id} or {@code x-session-id}. The
     * literal {@code prompt_cache_key} entry of the provider's {@code enabledKeys} is not a header (it names
     * the body field) and is filtered out here. Empty disables session-affinity header emission.
     */
    private final List<String> sessionIdHeaderKeys;

    /**
     * Whether to emit a fresh per-request {@code x-client-request-id} header (a new UUID per request, not the
     * stable session value) - mirrors the Codex CLI's per-thread request marker.
     */
    private final boolean xClientRequestIdEnabled;

    /**
     * Extension key on an assistant {@link LlmMessage} carrying the opaque reasoning items to replay as
     * {@code type:reasoning} input items (see {@link #replayReasoningItems}). Produced by the loop from
     * the {@code encrypted_content} blobs it captured and persisted; consumed here so the same model can
     * recover its multi-turn reasoning continuity on the Responses wire.
     */
    static final String REASONING_ITEMS_KEY = "reasoningItems";

    /** Provider-option key toggling the {@code include:["reasoning.encrypted_content"]} directive (shared by
     *  every Responses dialect - used by Codex and any Responses gateway that rejects it by default). */
    public static final String REASONING_INCLUDE_KEY = "reasoning_include";

    /**
     * Creates the dialect.
     *
     * @param mapper JSON mapper used for wire serialization.
     * @param client HTTP client used for provider calls.
     * @param provider provider coordinates (base URL, authentication, options).
     */
    public OpenAIResponsesDialect(
            final JsonMapper mapper, final ExtendedHttpClient client, final ProviderSpec provider) {
        this(mapper, client, provider, List.of(), false);
    }

    /**
     * Creates the dialect with explicit session-affinity knobs.
     *
     * @param mapper JSON mapper used for wire serialization.
     * @param client HTTP client used for provider calls.
     * @param provider provider coordinates (base URL, authentication, options).
     * @param enabledKeys the provider's resolved {@code capabilities.openai.sessionId.enabledKeys}: the
     *     {@code prompt_cache_key} entry names the body field (ignored here, it is injected upstream in the
     *     wire request), every other entry names an HTTP header emitted with the session-scoped value.
     * @param xClientRequestIdEnabled whether to emit a fresh per-request {@code x-client-request-id} header.
     */
    public OpenAIResponsesDialect(
            final JsonMapper mapper,
            final ExtendedHttpClient client,
            final ProviderSpec provider,
            final List<String> enabledKeys,
            final boolean xClientRequestIdEnabled) {
        super(mapper, client, provider);
        this.sessionIdHeaderKeys = enabledKeys == null
                ? List.of()
                : enabledKeys.stream()
                        .filter(k -> !"prompt_cache_key".equals(k))
                        .toList();
        this.xClientRequestIdEnabled = xClientRequestIdEnabled;
    }

    /**
     * Registers an optional tee consuming every raw SSE line before parsing (mainly for wire
     * debugging). When null (the default) raw lines are discarded.
     *
     * @param onRawLine the raw-line consumer or null.
     */
    public void setOnRawLine(final Consumer<String> onRawLine) {
        this.onRawLine = onRawLine;
    }

    /**
     * Whether this dialect can only call its backend in streaming mode. Some providers (eg. the
     * ChatGPT Codex backend) reject any request whose {@code stream} field is not {@code true}, so
     * single-shot {@link #chat(LlmRequest)} callers must still mark the request as streaming and
     * reassemble the SSE back into a single {@link LlmResponse}. Defaults to {@code false}.
     *
     * @return {@code true} to force the wire {@code stream:true} flag even for non-streaming requests.
     */
    protected boolean forceStream() {
        return false;
    }

    /**
     * Emits the session-affinity headers on the wire request. When the payload carries a
     * {@code prompt_cache_key} (injected upstream from the session id) and any enabled header key
     * ({@code session_id}, {@code x-session-id}, ...) is not already present, the key is set to that same
     * session-scoped value, so the provider routes every request of the session to the same prompt cache.
     * An explicitly configured caller header for the same name wins. When
     * {@link #xClientRequestIdEnabled} is set, adds a fresh per-request {@code x-client-request-id} UUID
     * (never reused across requests).
     */
    @Override
    protected HttpRequest http(final byte[] payload) {
        var request = super.http(payload);
        if (!sessionIdHeaderKeys.isEmpty()) {
            final var body = mapper.fromString(Object.class, new String(payload, StandardCharsets.UTF_8));
            if (body instanceof Map<?, ?> map
                    && map.get("prompt_cache_key") instanceof final String key
                    && !key.isBlank()) {
                for (final var header : sessionIdHeaderKeys) {
                    if (request.headers().firstValue(header).isEmpty()) {
                        request = HttpRequest.newBuilder(request, (_, _) -> true)
                                .setHeader(header, key)
                                .build();
                    }
                }
            }
        }
        if (xClientRequestIdEnabled
                && request.headers().firstValue("x-client-request-id").isEmpty()) {
            request = HttpRequest.newBuilder(request, (_, _) -> true)
                    .setHeader("x-client-request-id", UUID.randomUUID().toString())
                    .build();
        }
        return request;
    }

    /**
     * Whether the reasoning {@code include} directive should request the opaque {@code encrypted_content}
     * reasoning blob from the backend, so a stateless client can replay it verbatim on later turns for
     * multi-turn reasoning continuity (decrypted only server-side). Defaults to {@code true}: any
     * Responses-capable reasoning backend (ChatGPT Codex, Azure AI, gateways) mints the blob when asked
     * and the directive is advisory on providers that do not support it, so requesting it only adds
     * continuity where available.
     *
     * @return {@code true} to request the encrypted reasoning blob.
     */
    protected boolean includeEncryptedReasoning() {
        return true;
    }

    /**
     * Whether the first attempt of a request should carry the {@code reasoning.include} directive. Memoizes a
     * backend that rejected the directive (a retry-without-it already succeeded): once {@link
     * #encryptedReasoningRejected} is set the directive is never sent again for this provider, avoiding the
     * guaranteed 400 + downgrade retry on every subsequent request.
     *
     * @return {@code false} once a {@code reasoning.include} rejection was observed and auto-recovered.
     */
    private boolean firstAttemptIncludeEncryptedReasoning() {
        return !encryptedReasoningRejected.get() && includeEncryptedReasoning();
    }

    /**
     * The {@code reasoning} request config: the effort plus the summary mode (which is what makes the
     * backend stream the displayable reasoning-summary deltas at all). {@code detailed} is required here:
     * several backends (notably the ChatGPT Codex {@code /responses} edge) emit an *empty* summary and no
     * {@code response.reasoning_summary_text.delta} events when asked for {@code auto}, so gpt-6* would
     * reason server-side while the client shows no thinking. {@code detailed} forces the backend to stream
     * the displayable summary parts. When {@link #includeEncryptedReasoning()} is true, also requests the
     * opaque replayable {@code encrypted_content} blob via {@code include}.
     */
    private Map<String, Object> reasoningConfig(final String effort, final boolean includeEncryptedReasoning) {
        if (!includeEncryptedReasoning) {
            return Map.of("effort", effort, "summary", "detailed");
        }
        return Map.of("effort", effort, "summary", "detailed", "include", List.of("reasoning.encrypted_content"));
    }

    /**
     * Exposes the routing identifier of this dialect.
     *
     * @return the routing identifier of this dialect, {@code openai-responses}.
     */
    @Override
    public String name() {
        return "openai-responses";
    }

    /**
     * Exposes the chat route appended to the provider base URL.
     *
     * @return the chat route suffix appended to the provider base URL.
     */
    @Override
    protected String chatRouteSuffix() {
        return "/responses";
    }

    @Override
    public CompletionStage<LlmResponse> chat(final LlmRequest request) {
        return chat(request, null);
    }

    @Override
    public CompletionStage<LlmResponse> chat(final LlmRequest request, final BooleanSupplier cancelled) {
        try {
            final var include = firstAttemptIncludeEncryptedReasoning();
            final var payload = mapper.toBytes(toWire(request, false, include));
            return post(payload).thenCompose(response -> {
                if (response.statusCode() == 400
                        && include
                        && request.reasoningEffort() != null
                        && isReasoningIncludeRejectedBody(response.body())) {
                    // a gateway rejected the encrypted-reasoning include: downgrade once and resend
                    return post(mapper.toBytes(toWire(request, false, false))).thenCompose(retry -> {
                        encryptedReasoningRejected.set(true);
                        try {
                            return CompletableFuture.completedFuture(
                                    toLlmResponse(asMap(parseResponse(Map.class, retry))));
                        } catch (final LlmResponseParseException e) {
                            return failed(e);
                        }
                    });
                }
                try {
                    return CompletableFuture.completedFuture(toLlmResponse(asMap(parseResponse(Map.class, response))));
                } catch (final LlmResponseParseException e) {
                    return failed(e);
                }
            });
        } catch (final RuntimeException e) {
            return failed(e);
        }
    }

    @Override
    public CompletionStage<Flow.Publisher<LlmStreamEvent>> stream(
            final LlmRequest request, final BooleanSupplier cancelled) {
        try {
            final var include = firstAttemptIncludeEncryptedReasoning();
            final var payload = mapper.toBytes(toWire(request, true, include));
            return openStream(payload, cancelled).thenCompose(attempt -> {
                if (attempt.ok()) {
                    return CompletableFuture.completedFuture(
                            Streams.toEvents(attempt.live(), cancelled, mapEventCapture(), () -> null, onRawLine));
                }
                if (include && request.reasoningEffort() != null && isReasoningIncludeRejected(attempt)) {
                    // a gateway/edge rejected the encrypted-reasoning include: transparently
                    // downgrade once and resend without it (bounded single retry)
                    return retryWithoutEncryptedReasoning(request, true, cancelled)
                            .thenCompose(retry -> {
                                if (retry.ok()) {
                                    encryptedReasoningRejected.set(true);
                                    return CompletableFuture.completedFuture(Streams.toEvents(
                                            retry.live(), cancelled, mapEventCapture(), () -> null, onRawLine));
                                }
                                return streamError(attempt);
                            });
                }
                return streamError(attempt);
            });
        } catch (final RuntimeException e) {
            return failed(e);
        }
    }

    /**
     * Runs a request through the streaming pipeline but reassembles the resulting SSE events into a
     * single {@link LlmResponse}. This is the single-shot equivalent of {@link #stream(LlmRequest,
     * BooleanSupplier)} and is used by backends (eg. the ChatGPT Codex dialect) that reject any
     * request unless {@code stream} is {@code true}, so a plain chat call still reaches them. Text is
     * concatenated, tool-call argument deltas are coalesced by their output index, and the terminal
     * event contributes the usage and finish reason.
     *
     * @param request the request to run as a single-shot completion.
     * @param cancelled cancellation probe checked between frames.
     * @return a stage completing with the assembled single-shot response.
     */
    protected CompletionStage<LlmResponse> assemble(final LlmRequest request, final BooleanSupplier cancelled) {
        try {
            final var include = firstAttemptIncludeEncryptedReasoning();
            final var payload = mapper.toBytes(toWire(request, true, include));
            return openStream(payload, cancelled).thenCompose(attempt -> {
                if (!attempt.ok()) {
                    if (include && request.reasoningEffort() != null && isReasoningIncludeRejected(attempt)) {
                        // a strict backend rejected the encrypted-reasoning include: downgrade once and retry
                        return retryWithoutEncryptedReasoning(request, true, cancelled)
                                .thenCompose(retry -> {
                                    if (retry.ok()) {
                                        encryptedReasoningRejected.set(true);
                                        return assembleEvents(retry.live(), cancelled);
                                    }
                                    return assembleFailure(attempt);
                                });
                    }
                    return assembleFailure(attempt);
                }
                return assembleEvents(attempt.live(), cancelled);
            });
        } catch (final RuntimeException e) {
            return failed(e);
        }
    }

    /**
     * Assembles a live SSE stream into a single {@link LlmResponse}.
     */
    private CompletionStage<LlmResponse> assembleEvents(
            final Flow.Publisher<List<ByteBuffer>> live, final BooleanSupplier cancelled) {
        final var future = new CompletableFuture<LlmResponse>();
        Streams.toEvents(live, cancelled, mapEventCapture(), () -> null, onRawLine)
                .subscribe(new LlmResponseAssembler(future));
        return future;
    }

    /**
     * Binds a fresh per-stream tool-call coalescing map to {@link #mapEvent(SseParser.Event, Map)}:
     * the map is created ONCE and captured by the returned mapper, so every event of THIS stream (and only
     * this stream) shares the same bookkeeping while concurrently-streaming calls on the shared dialect can
     * never observe each other's state.
     *
     * @return a mapper function bound to a fresh per-stream state map.
     */
    private Function<SseParser.Event, List<LlmStreamEvent>> mapEventCapture() {
        final var streamArgState = new LinkedHashMap<Integer, MutableToolCallState>();
        return event -> mapEvent(event, streamArgState);
    }

    /**
     * Surfaces a failed single-shot stream attempt as a classified {@link LlmResponse} failure.
     */
    private CompletionStage<LlmResponse> assembleFailure(final StreamAttempt attempt) {
        return streamError(attempt)
                .thenCompose(ignored -> failed(new LlmResponseParseException(
                        name(),
                        provider.baseUrl(),
                        attempt.statusCode(),
                        attempt.headers().firstValue("content-type").orElse(null),
                        null,
                        new IllegalStateException("failed to assemble single-shot response"))));
    }

    /**
     * True when a failed stream attempt is the OpenAI Responses {@code reasoning.include} unknown-parameter
     * rejection: some backends (eg. strict Codex/gov edges or Responses-compliant gateways) reject the
     * {@code include:["reasoning.encrypted_content"]} directive with a 400.
     *
     * @param attempt the failed stream attempt.
     * @return {@code true} when the error is a {@code reasoning.include} unknown-parameter rejection.
     */
    protected boolean isReasoningIncludeRejected(final StreamAttempt attempt) {
        if (attempt.statusCode() != 400 || attempt.errorBody() == null || attempt.errorBody().length == 0) {
            return false;
        }
        final var body = new String(attempt.errorBody(), StandardCharsets.UTF_8);
        return isReasoningIncludeRejectedBody(body);
    }

    /**
     * True when a transparent retry is needed because the request carries the encrypted-reasoning
     * {@code include} directive and the backend rejected it. The body is deserialized through the
     * {@code JsonMapper} and the structured {@code error} object inspected ({@code code}, {@code param},
     * {@code message}); when the body does not parse as the expected shape the rejection is NOT classified
     * as a {code reasoning.include} one, so the original error surfaces unchanged instead of a wrong retry.
     */
    private boolean isReasoningIncludeRejectedBody(final String body) {
        if (body == null) {
            return false;
        }
        try {
            final Object parsed = mapper.fromString(Map.class, body);
            if (!(parsed instanceof Map<?, ?> root)) {
                return false;
            }
            final Object error = root.get("error");
            if (!(error instanceof Map<?, ?> err)) {
                return false;
            }
            final Object code = err.get("code");
            final Object param = err.get("param");
            final Object message = err.get("message");
            final boolean reasoningParam = param != null && "reasoning.include".equals(String.valueOf(param));
            final boolean unknownReasoningInclude = "unknown_parameter".equals(String.valueOf(code))
                    && (reasoningParam || String.valueOf(message).contains("reasoning.include"));
            return reasoningParam || unknownReasoningInclude;
        } catch (final RuntimeException e) {
            // the body is not the expected JSON error shape: report the original error, no silent retry
            return false;
        }
    }

    /**
     * Re-encodes a request without the encrypted-reasoning {@code include} directive and resends it once.
     * Used to self-heal a {@code reasoning.include} rejection transparently.
     *
     * @param request the neutral request to re-encode.
     * @param stream {@code true} to produce a streaming retry payload.
     * @param cancelled cancellation probe.
     * @return a stage completing with the retried stream attempt.
     */
    protected CompletionStage<StreamAttempt> retryWithoutEncryptedReasoning(
            final LlmRequest request, final boolean stream, final BooleanSupplier cancelled) {
        final var payload = mapper.toBytes(toWire(request, stream, false));
        return openStream(payload, cancelled);
    }

    /** Subscriber accumulating streamed events into a single {@link LlmResponse}. */
    private final class LlmResponseAssembler implements Flow.Subscriber<LlmStreamEvent> {
        private final CompletableFuture<LlmResponse> future;
        private final StringBuilder text = new StringBuilder();
        private final Map<Integer, MutableToolCall> calls = new LinkedHashMap<>();
        private Usage usage;
        private String finishReason = "stop";
        private String errorMessage;

        private LlmResponseAssembler(final CompletableFuture<LlmResponse> future) {
            this.future = future;
        }

        @Override
        public void onSubscribe(final Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(final LlmStreamEvent event) {
            if (event instanceof LlmStreamEvent.TextDelta(String text1)) {
                text.append(text1);
            } else if (event
                    instanceof LlmStreamEvent.ToolCallDelta(int index, String id, String name, String arguments)) {
                final var mutable = calls.computeIfAbsent(index, i -> new MutableToolCall());
                if (name != null) {
                    mutable.name = name;
                }
                if (id != null) {
                    mutable.id = id;
                }
                if (arguments != null) {
                    mutable.arguments.append(arguments);
                }
            } else if (event instanceof LlmStreamEvent.End(String reason, Usage usage1, String error)) {
                if (usage1 != null) {
                    usage = usage1;
                }
                if (reason != null) {
                    finishReason = reason;
                }
                if (error != null && !error.isBlank()) {
                    errorMessage = error;
                }
            }
        }

        @Override
        public void onError(final Throwable throwable) {
            future.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            final var toolCalls = new ArrayList<LlmToolCall>();
            calls.forEach((index, call) -> toolCalls.add(new LlmToolCall(
                    call.id,
                    "function",
                    new LlmToolCall.FunctionCall(call.name == null ? "" : call.name, call.arguments.toString()),
                    index)));
            final var message = new LlmMessage(
                    "assistant", text.toString(), null, toolCalls.isEmpty() ? null : toolCalls, null, Map.of());
            final var choice = new LlmResponse.LlmChoice(0, message, finishReason);
            future.complete(new LlmResponse(
                    null,
                    null,
                    null,
                    List.of(choice),
                    usage,
                    errorMessage == null ? Map.of() : Map.of("error", Map.of("message", errorMessage))));
        }
    }

    /** Mutable tool-call accumulator, keyed by output index across streamed deltas. */
    private static final class MutableToolCall {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }

    @Override
    public CompletionStage<LlmModels> models() {
        try {
            return getModels().thenCompose(response -> {
                try {
                    return CompletableFuture.completedFuture(toModels(parseResponse(Map.class, response)));
                } catch (final LlmResponseParseException e) {
                    return failed(e);
                }
            });
        } catch (final RuntimeException e) {
            return failed(e);
        }
    }

    @Override
    protected byte[] encodeRequest(final LlmRequest request, final boolean stream) {
        return mapper.toBytes(toWire(request, stream));
    }

    private ResponsesRequest toWire(final LlmRequest request, final boolean stream) {
        return toWire(request, stream, includeEncryptedReasoning());
    }

    private ResponsesRequest toWire(
            final LlmRequest request, final boolean stream, final boolean includeEncryptedReasoning) {
        final var instructions = new ArrayList<String>();
        final var input = new ArrayList<ResponsesInput>();
        final var sanitized = request.messages() == null ? null : sanitizeToolCalls(request.messages());
        if (sanitized != null) {
            for (final var message : sanitized) {
                switch (message.role()) {
                    case "system", "developer" -> instructions.add(str(message.content()));
                    case "assistant" -> {
                        replayReasoningItems(message, input);
                        final var text = str(message.content());
                        if (text != null && !text.isBlank()) {
                            input.add(ResponsesInput.message("assistant", text));
                        }
                        if (message.toolCalls() != null) {
                            for (final var call : message.toolCalls()) {
                                if (call.function() != null) {
                                    input.add(ResponsesInput.functionCall(
                                            call.id(),
                                            call.function().name(),
                                            call.function().arguments()));
                                }
                            }
                        }
                    }
                    case "tool" -> {
                        final var extensions = message.extensions();
                        final var isError = extensions != null && Boolean.TRUE.equals(extensions.get("is_error"));
                        input.add(ResponsesInput.functionCallOutput(
                                message.toolCallId(), str(message.content()), isError));
                    }
                    default -> input.add(ResponsesInput.message(message.role(), str(message.content())));
                }
            }
        }
        final var tools = new ArrayList<ResponsesTool>();
        if (request.tools() != null) {
            for (final var tool : request.tools()) {
                tools.add(new ResponsesTool(
                        "function", tool.name(), tool.description(), tool.parameters(), tool.strict()));
            }
        }
        // 'summary' is required by the official API to stream reasoning deltas at all; 'detailed' forces
        // the backend to actually emit the displayable summary parts (with 'auto' the ChatGPT Codex edge
        // returns an empty summary and no reasoning deltas, hiding gpt-6* thinking entirely)
        final var reasoning = request.reasoningEffort() == null
                ? null
                : reasoningConfig(request.reasoningEffort(), includeEncryptedReasoning);
        return new ResponsesRequest(
                request.model(),
                input,
                instructions.isEmpty() ? null : String.join("\n\n", instructions),
                request.maxTokens(),
                request.temperature(),
                request.topP(),
                (stream || forceStream()) ? Boolean.TRUE : null,
                tools.isEmpty() ? null : tools,
                request.toolChoice(),
                request.responseFormat(),
                false,
                reasoning,
                request.extensions() == null ? Map.of() : request.extensions());
    }

    /**
     * OpenAI-compatible backends (OpenAI, sglang, Azure, litellm, ...) reject an assistant message whose
     * {@code tool_calls} are not answered
     * by matching tool responses ("An assistant message with 'tool_calls' must be followed by tool messages
     * responding to each 'tool_call_id'", or Responses "No tool output found for function call ..."),
     * a hard 400. Anthropic carries a wire backstop for its own pairing rule; this is the symmetric OpenAI
     * one for the Responses wire. The WHOLE serialized history is verified (not just the live tail): a
     * persisted, compressed, transformed or externally supplied history can carry an older unanswered call,
     * and the backend validates the entire request, so one stale dangling call anywhere must be stripped.
     * Any assistant tool call whose id was never answered contiguously is stripped from the wire and
     * FINE-logged - the request then validates instead of failing wholesale. Standalone {@code tool}
     * results are intentionally kept verbatim (an orphan result in history is not rejected; only an
     * unanswered assistant call is).
     *
     * <p>Verification is block-local and allocation-light: for each assistant {@code tool_calls} block the
     * pending ids are tracked in a small reusable set and matched against the immediately-following
     * contiguous run of {@code tool} results only. The output list and replacement call lists are created
     * lazily, only once a change is found, so a fully valid history costs one linear pass with no extra
     * allocations.</p>
     *
     * <p>This is a backstop: the primary repair lives in the agent loop (it inserts an honest lost-result
     * marker). This only fires when a broken pairing still reaches the wire (a third-party caller, a
     * corrupted persisted session, an interrupted round the loop missed).</p>
     *
     * @param messages the neutral messages, mutated nowhere (a new list is returned when anything changed).
     * @return the sanitized message list, or the input when nothing needed stripping.
     */
    private List<LlmMessage> sanitizeToolCalls(final List<LlmMessage> messages) {
        if (messages.isEmpty()) {
            return messages;
        }
        // most requests carry no assistant tool calls at all: skip the O(history) scan and rebuild entirely,
        // so the common pure-text path stays zero-cost.
        var hasToolRound = false;
        for (final var probe : messages) {
            if (probe != null
                    && "assistant".equals(probe.role())
                    && probe.toolCalls() != null
                    && !probe.toolCalls().isEmpty()) {
                hasToolRound = true;
                break;
            }
        }
        if (!hasToolRound) {
            return messages;
        }
        final var dropped = new ArrayList<String>();
        var out = (List<LlmMessage>) null;
        for (var i = 0; i < messages.size(); i++) {
            final var message = messages.get(i);
            if (message == null) {
                if (out != null) {
                    out.add(null);
                }
                continue;
            }
            if ("assistant".equals(message.role())
                    && message.toolCalls() != null
                    && !message.toolCalls().isEmpty()) {
                // collect this block's nonnull ids, then match against the contiguous following tool results
                final var pending = new HashSet<String>();
                var overCap = false;
                for (final var call : message.toolCalls()) {
                    if (call != null && call.id() != null) {
                        if (pending.size() < MAX_PENDING_TOOL_IDS) {
                            pending.add(call.id());
                        } else {
                            overCap = true;
                        }
                    }
                }
                var j = i + 1;
                while (j < messages.size() && "tool".equals(messages.get(j).role())) {
                    final var result = messages.get(j).toolCallId();
                    if (result != null) {
                        pending.remove(result);
                    }
                    j++;
                }
                if (overCap) {
                    // an oversized block cannot be trusted to be fully paired: leave it untouched and keep
                    // its contiguous results, so the backend can still reject rather than silently corrupt
                    if (out != null) {
                        for (var k = i; k < j; k++) {
                            out.add(messages.get(k));
                        }
                    }
                    i = j - 1;
                    continue;
                }
                if (pending.isEmpty()) {
                    // all calls answered contiguously; copy the block unchanged
                    if (out != null) {
                        for (var k = i; k < j; k++) {
                            out.add(messages.get(k));
                        }
                    }
                    i = j - 1;
                    continue;
                }
                // some calls are unanswered: strip them; the contiguous results are still preserved below
                final var kept = new ArrayList<LlmToolCall>(message.toolCalls().size());
                for (final var call : message.toolCalls()) {
                    if (call == null || call.id() == null || pending.contains(call.id())) {
                        if (call != null && call.id() != null && dropped.size() < MAX_DROPPED_SAMPLE_IDS) {
                            dropped.add(call.id());
                        }
                        continue;
                    }
                    kept.add(call);
                }
                if (out == null) {
                    out = new ArrayList<>(messages.size());
                    out.addAll(messages.subList(0, i));
                }
                if (kept.isEmpty()) {
                    // no surviving call: keep a text-only assistant message (drop the empty toolCalls) so
                    // the plain text answer, if any, is still served
                    out.add(new LlmMessage(
                            message.role(),
                            message.content(),
                            message.name(),
                            null,
                            message.toolCallId(),
                            message.extensions()));
                } else {
                    out.add(new LlmMessage(
                            message.role(),
                            message.content(),
                            message.name(),
                            kept,
                            message.toolCallId(),
                            message.extensions()));
                }
                // copy the streamed (contiguous) tool results that answer the surviving calls verbatim
                for (var k = i + 1; k < j; k++) {
                    out.add(messages.get(k));
                }
                i = j - 1;
                continue;
            }
            if (out != null) {
                out.add(message);
            }
        }
        if (dropped.isEmpty()) {
            return messages; // nothing changed (out is null when unchanged)
        }
        LOGGER.fine(() -> "sanitized " + dropped.size() + " assistant tool call(s) with no contiguously-answered "
                + "tool response (OpenAI-compatible backends such as OpenAI, sglang, Azure and litellm reject "
                + "an assistant 'tool_calls' with no matching tool response) before sending: " + dropped
                + ". The agent loop should repair these inline; this is the wire backstop.");
        return out;
    }

    /** Upper bound on pending tool ids tracked per assistant block during wire sanitization. */
    private static final int MAX_PENDING_TOOL_IDS = 256;

    /** Upper bound on distinct dropped call ids recorded for the FINE diagnostic log. */
    private static final int MAX_DROPPED_SAMPLE_IDS = 8;

    private LlmResponse toLlmResponse(final Map<String, Object> root) {
        final var messageText = new StringBuilder();
        final var calls = new ArrayList<LlmToolCall>();
        if (root.get("output") instanceof List<?> output) {
            final var index = new AtomicInteger();
            for (final Object item : output) {
                if (item instanceof Map<?, ?> map) {
                    final var type = str(map.get("type"));
                    if ("message".equals(type)) {
                        final var content = map.get("content");
                        if (content instanceof List<?> parts) {
                            for (final Object part : parts) {
                                if (part instanceof Map<?, ?> p
                                        && "output_text".equals(str(p.get("type")))
                                        && p.get("text") instanceof String text) {
                                    messageText.append(text);
                                }
                            }
                        }
                    } else if ("function_call".equals(type)) {
                        // keep every call: parallel tool calls must land in one assistant message
                        final var itemId = str(map.get("id"));
                        final var callId = nonBlank(map.get("call_id")) ? str(map.get("call_id")) : itemId;
                        calls.add(new LlmToolCall(
                                callId,
                                "function",
                                new LlmToolCall.FunctionCall(str(map.get("name")), str(map.get("arguments"))),
                                index.getAndIncrement()));
                    }
                }
            }
        }
        final LlmResponse.LlmChoice choice;
        if (!calls.isEmpty()) {
            choice = new LlmResponse.LlmChoice(
                    0, new LlmMessage("assistant", messageText.toString(), null, calls, null, null), "tool_calls");
        } else {
            choice = new LlmResponse.LlmChoice(
                    0, new LlmMessage("assistant", messageText.toString(), null, null, null, null), "stop");
        }
        final var usage = usage(root.get("usage"));
        return new LlmResponse(str(root.get("id")), str(root.get("model")), null, List.of(choice), usage, null);
    }

    private List<LlmStreamEvent> mapEvent(
            final SseParser.Event event, final Map<Integer, MutableToolCallState> streamArgState) {
        try {
            final var type = event.event() == null ? "" : event.event();
            if (event.data() == null) {
                return List.of();
            }
            final var root = asMap(parse(Map.class, event.data()));
            final var result = new ArrayList<LlmStreamEvent>();
            switch (type) {
                case "response.output_text.delta" -> {
                    final var text = str(root.get("delta"));
                    if (text != null && !text.isEmpty()) {
                        result.add(new LlmStreamEvent.TextDelta(text));
                    }
                }
                case "response.reasoning_text.delta", "response.reasoning_summary_text.delta" -> {
                    // reasoning models stream either the full reasoning text or (more
                    // commonly on gateways) only its summary: both are the model thinking
                    final var text = str(root.get("delta"));
                    if (text != null && !text.isEmpty()) {
                        result.add(new LlmStreamEvent.ReasoningDelta(text));
                    }
                }
                case "response.function_call_arguments.delta" -> {
                    final var text = str(root.get("delta"));
                    // use the item's real output_index (not a constant) so the coalescing subscriber
                    // can match this fragment to the output_item that carries the name; index 0 would
                    // collide across tool calls in a multi-call turn and drop the leading arguments
                    // (eg. the 'command' of a bash call), leaving command empty.
                    final var outputIndex = root.get("output_index") instanceof Number n ? n.intValue() : 0;
                    final var state = streamArgState.computeIfAbsent(outputIndex, i -> new MutableToolCallState());
                    // the delta event carries only item_id (a provisional id, never the canonical call_id):
                    // record it as a fallback but never overwrite an authoritative id already known.
                    state.offerItemId(str(root.get("item_id")));
                    final var id = state.bestId();
                    // a delta delivery signals the arguments streamed incrementally, so the assembled
                    // arguments on a later .done / output_item.done must be suppressed, never double-appended.
                    if (id != null) {
                        state.markArgumentsDelivered();
                    }
                    result.add(new LlmStreamEvent.ToolCallDelta(outputIndex, id, null, text == null ? "" : text));
                }
                case "response.function_call_arguments.done" -> {
                    final var outputIndex = root.get("output_index") instanceof Number n ? n.intValue() : 0;
                    final var state = streamArgState.computeIfAbsent(outputIndex, i -> new MutableToolCallState());
                    state.offerItemId(str(root.get("item_id")));
                    final var assembled = str(root.get("arguments"));
                    if (assembled != null
                            && !assembled.isEmpty()
                            && !state.argumentsDelivered()
                            && state.bestId() != null) {
                        state.markArgumentsDelivered();
                        result.add(new LlmStreamEvent.ToolCallDelta(outputIndex, state.bestId(), null, assembled));
                    }
                }
                case "response.output_item.added", "response.output_item.done" -> {
                    if (root.get("item") instanceof Map<?, ?> item) {
                        final var itemType = str(item.get("type"));
                        if ("function_call".equals(itemType)) {
                            // output_item.added carries the name; output_item.done carries the fully
                            // assembled arguments.
                            final var outputIndex = root.get("output_index") instanceof Number n ? n.intValue() : 0;
                            final var state =
                                    streamArgState.computeIfAbsent(outputIndex, i -> new MutableToolCallState());
                            // the item carries the authoritative call_id (when present) which upgrades any
                            // provisional item_id seen on argument-delta events; fall back to item.id for
                            // gateways that only emit id.
                            state.offerAuthoritativeCallId(str(item.get("call_id")));
                            state.offerItemId(str(item.get("id")));
                            final var id = state.bestId();
                            result.add(new LlmStreamEvent.ToolCallDelta(outputIndex, id, str(item.get("name")), null));
                            final var assembled = str(item.get("arguments"));
                            if ("response.output_item.done".equals(type)
                                    && assembled != null
                                    && !assembled.isEmpty()
                                    && id != null
                                    && !state.argumentsDelivered()) {
                                // no incremental delta was seen for this call: emit the assembled
                                // arguments so the coalesced tool call is complete (prevents an empty
                                // 'command is required' failure when a provider skips the delta events).
                                state.markArgumentsDelivered();
                                result.add(new LlmStreamEvent.ToolCallDelta(outputIndex, id, null, assembled));
                            }
                        } else if ("response.output_item.done".equals(type) && "reasoning".equals(itemType)) {
                            // A completed reasoning item: the displayable summaries already streamed as
                            // response.reasoning_summary_text.delta, but the item also carries the opaque
                            // encrypted_content blob that must be replayed verbatim on later turns for the
                            // same model to recover its reasoning continuity. Surface it so the loop can
                            // persist and re-emit it (never displayed).
                            final var enc = str(item.get("encrypted_content"));
                            if (enc != null && !enc.isEmpty()) {
                                result.add(new LlmStreamEvent.ReasoningItem(str(item.get("id")), summaryOf(item), enc));
                            }
                        }
                    }
                }
                case "response.completed" -> {
                    streamArgState.clear();
                    result.add(new LlmStreamEvent.End("stop", terminalUsage(root)));
                }
                case "response.incomplete" -> {
                    // Only a genuine output-token exhaustion is a truncation. A response.incomplete
                    // with any other reason (content_filter, max_messages, steered, ...) is NOT the
                    // model being cut off by its output budget: surfacing it as finish_reason=length
                    // would make the agent loop retry it as a truncation (continueOnTruncation) and
                    // burn the truncation budget hiding a real problem. Map only max_output_tokens to
                    // "length"; keep the upstream reason verbatim otherwise so isTruncation() leaves it
                    // alone and the turn can end/route normally.
                    final var incompleteReason = incompleteReason(root);
                    streamArgState.clear();
                    result.add(new LlmStreamEvent.End(
                            "max_output_tokens".equals(incompleteReason) ? "length" : incompleteReason,
                            terminalUsage(root)));
                }
                case "response.failed", "response.error", "error" -> {
                    // An HTTP-layer/backend failure is not output-token exhaustion: map it to "error"
                    // (a never-truncating reason) so it is not retried through the truncation path.
                    // Keep optional provider details without changing the bounded empty-completion retry.
                    streamArgState.clear();
                    result.add(new LlmStreamEvent.End("error", null, streamErrorMessage(root)));
                }
                default -> {
                    // ignore unknown event types
                }
            }
            return result;
        } catch (final RuntimeException e) {
            warnMapEventFailure("responses", event.data() == null ? null : event.data(), e);
            return List.of();
        }
    }

    private static String streamErrorMessage(final Map<String, Object> root) {
        final var source = root.get("response") instanceof Map<?, ?> response ? response : root;
        final var error = source.get("error") instanceof Map<?, ?> details ? details : root;
        final var message = error.get("message") instanceof String text && !text.isBlank() ? text : null;
        final var code = error.get("code") instanceof String text && !text.isBlank() ? text : null;
        if (message == null) {
            return code;
        }
        return code == null ? message : code + ": " + message;
    }

    /**
     * Per-output-index coalescing state for a streaming Responses tool call, keyed by output index in
     * {@link #mapEvent(SseParser.Event, Map)}. Tracks the best known neutral tool-call id (an
     * authoritative {@code call_id} from a function_call item wins over the provisional {@code item_id}
     * carried by argument-delta events) and whether nonempty argument content has already been delivered,
     * so the completion events only emit assembled arguments as a fallback and never double-append.
     */
    private static final class MutableToolCallState {
        private String bestId;
        private boolean argumentsDelivered;

        /** Registers an authoritative {@code call_id}; upgrades any provisional id, never downgrades. */
        void offerAuthoritativeCallId(final CharSequence callId) {
            if (callId != null && !callId.isEmpty()) {
                bestId = callId.toString();
            }
        }

        /** Registers a provisional {@code item_id} only when no authoritative id is known yet. */
        void offerItemId(final CharSequence itemId) {
            if (bestId == null && itemId != null && !itemId.isEmpty()) {
                bestId = itemId.toString();
            }
        }

        String bestId() {
            return bestId;
        }

        boolean argumentsDelivered() {
            return argumentsDelivered;
        }

        void markArgumentsDelivered() {
            argumentsDelivered = true;
        }
    }

    /**
     * Replays the opaque reasoning items persisted on an assistant message as {@code type:reasoning}
     * input items, in their original order (before the assistant's own message), so the same model recovers
     * its multi-turn reasoning continuity. The persisted payload is the list of maps written by the loop
     * ({@code id}, {@code summary}, {@code encrypted_content}). Entries without an {@code encrypted_content}
     * blob are skipped.
     */
    private static void replayReasoningItems(final LlmMessage message, final List<ResponsesInput> input) {
        final var raw =
                message.extensions() == null ? null : message.extensions().get(REASONING_ITEMS_KEY);
        if (!(raw instanceof List<?> items)) {
            return;
        }
        for (final var entry : items) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            String id = null;
            Object summary = null;
            String encrypted = null;
            for (final var e : map.entrySet()) {
                final var key = String.valueOf(e.getKey());
                switch (key) {
                    case "id" -> {
                        if (e.getValue() instanceof final String s) {
                            id = s;
                        }
                    }
                    case "summary" -> summary = e.getValue();
                    case "encrypted_content" -> {
                        if (e.getValue() instanceof final String s) {
                            encrypted = s;
                        }
                    }
                    default -> {}
                }
            }
            if (encrypted == null || encrypted.isEmpty()) {
                continue;
            }
            input.add(ResponsesInput.reasoning(id, summary, encrypted, Map.of()));
        }
    }

    /**
     * Extracts the displayable {@code summary} array of a reasoning item as wire-shaped map parts
     * ({@code {type:"summary_text",text:"..."}}), for replaying the reasoning item verbatim. Returns an
     * empty list when the item carries no summary.
     */
    private static List<Map<String, Object>> summaryOf(final Map<?, ?> item) {
        final var raw = item.get("summary");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        final var out = new ArrayList<Map<String, Object>>();
        for (final var part : list) {
            if (part instanceof Map<?, ?> map) {
                final var asMap = new LinkedHashMap<String, Object>();
                for (final var e : map.entrySet()) {
                    asMap.put(String.valueOf(e.getKey()), e.getValue());
                }
                out.add(asMap);
            }
        }
        return out;
    }

    /**
     * Resolves the usage of a terminal {@code response.completed}/{@code response.incomplete} event.
     * The official Responses API and the Codex backend nest the usage under {@code response.usage},
     * while some gateways and older clients emit it at the event root. Prefer the nested shape (the
     * canonical one) and fall back to the top-level key.
     *
     * @param root the decoded event payload.
     * @return the resolved usage, {@link Usage#EMPTY} when neither shape carries one.
     */
    private static Usage terminalUsage(final Map<String, Object> root) {
        if (root.get("response") instanceof Map<?, ?> response && response.get("usage") instanceof Map<?, ?> nested) {
            return usage(nested);
        }
        return usage(root.get("usage"));
    }

    /**
     * Resolves the {@code incomplete_details.reason} of a terminal {@code response.incomplete} event.
     * The official Responses API and the Codex backend nest it under {@code response.incomplete_details},
     * while some gateways emit it at the event root. {@code null} when neither shape carries one (the
     * absence of details is intentionally indistinguishable from an unknown reason: neither is a
     * truncation).
     *
     * @param root the decoded event payload.
     * @return the reason string, or {@code null} when unknown/absent.
     */
    private static String incompleteReason(final Map<String, Object> root) {
        final Object details = root.get("response") instanceof Map<?, ?> response
                ? response.get("incomplete_details")
                : root.get("incomplete_details");
        if (details instanceof Map<?, ?> map) {
            final var reason = map.get("reason");
            return reason == null ? null : str(reason);
        }
        return null;
    }

    private static Usage usage(final Object value) {
        if (value instanceof Map<?, ?> map) {
            final Long cacheRead = map.get("input_tokens_details") instanceof Map<?, ?> details
                            && details.get("cached_tokens") instanceof Number n
                    ? n.longValue()
                    : null;
            final var completion = number(map.get("output_tokens"));
            final Long reasoning = map.get("output_tokens_details") instanceof Map<?, ?> details
                            && details.get("reasoning_tokens") instanceof Number n
                    ? Long.valueOf(n.longValue())
                    : map.get("reasoning_tokens") instanceof Number n ? n.longValue() : null;
            return new Usage(
                    number(map.get("input_tokens")),
                    completion,
                    number(map.get("total_tokens")),
                    cacheRead,
                    null,
                    reasoning,
                    UsageExtensions.generationTimeMs(extensions(map), completion));
        }
        return Usage.EMPTY;
    }

    /**
     * The usage payload seen as a plain string keyed map, so the non standard timing fields published
     * by some gateways can be read back. Only the entries of the usage object itself are kept.
     *
     * @param map the usage payload.
     * @return the non standard fields, {@code null} when the payload is not a plain object.
     */
    private static Map<String, Object> extensions(final Map<?, ?> map) {
        final var extensions = new LinkedHashMap<String, Object>(map.size());
        map.forEach((key, value) -> {
            if (key instanceof final String name) {
                extensions.put(name, value);
            }
        });
        return extensions;
    }

    private static long number(final Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }

    /** Whether a value stringifies to a nonblank string. */
    private static boolean nonBlank(final Object value) {
        final var s = value == null ? null : value.toString();
        return s != null && !s.isBlank();
    }
}
