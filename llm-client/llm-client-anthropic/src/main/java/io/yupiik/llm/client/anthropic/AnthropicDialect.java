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
package io.yupiik.llm.client.anthropic;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.llm.client.LlmResponseParseException;
import io.yupiik.llm.client.anthropic.model.AnthropicMessage;
import io.yupiik.llm.client.anthropic.model.AnthropicMessagesRequest;
import io.yupiik.llm.client.anthropic.model.AnthropicMessagesResponse;
import io.yupiik.llm.client.anthropic.model.AnthropicModels;
import io.yupiik.llm.client.anthropic.model.AnthropicStreamEvent;
import io.yupiik.llm.client.anthropic.model.AnthropicTool;
import io.yupiik.llm.client.model.LlmMessage;
import io.yupiik.llm.client.model.LlmModel;
import io.yupiik.llm.client.model.LlmModels;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmResponse;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmToolCall;
import io.yupiik.llm.client.model.Usage;
import io.yupiik.llm.client.model.UsageExtensions;
import io.yupiik.llm.client.spi.AbstractDialect;
import io.yupiik.llm.client.spi.ProviderSpec;
import io.yupiik.llm.client.spi.SseParser;
import io.yupiik.llm.client.spi.Streams;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Logger;

/** Anthropic Messages reactive dialect mapping the neutral model to the Messages wire format. */
public final class AnthropicDialect extends AbstractDialect {

    private static final Logger LOGGER = Logger.getLogger(AnthropicDialect.class.getName());

    private volatile Consumer<String> onRawLine;

    /**
     * Creates the dialect.
     *
     * @param mapper JSON mapper used for wire serialization.
     * @param client HTTP client used for provider calls.
     * @param provider provider coordinates (base URL, authentication, options).
     */
    public AnthropicDialect(final JsonMapper mapper, final ExtendedHttpClient client, final ProviderSpec provider) {
        super(mapper, client, provider);
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
     * Exposes the routing identifier of this dialect.
     *
     * @return the routing identifier of this dialect, {@code anthropic-messages}.
     */
    @Override
    public String name() {
        return "anthropic-messages";
    }

    /**
     * Exposes the chat route appended to the provider base URL.
     *
     * @return the chat route suffix appended to the provider base URL.
     */
    @Override
    protected String chatRouteSuffix() {
        return "/messages";
    }

    @Override
    protected void authenticate(final HttpRequest.Builder builder) {
        // hardcoded Anthropic defaults; the ProviderSpec headersCustomizer is applied by
        // AbstractDialect.http()/modelsRequest() AFTER this method with setHeader semantics,
        // so a customizer x-api-key/anthropic-version overrides these defaults
        if (provider.apiKey() != null && !provider.apiKey().isBlank()) {
            builder.header("x-api-key", provider.apiKey());
        }
        builder.header("anthropic-version", "2023-06-01");
    }

    @Override
    public CompletionStage<LlmResponse> chat(final LlmRequest request) {
        return chat(request, null);
    }

    @Override
    public CompletionStage<LlmResponse> chat(final LlmRequest request, final BooleanSupplier cancelled) {
        try {
            final var payload = mapper.toBytes(toWire(request, false));
            return post(payload).thenCompose(response -> {
                try {
                    return CompletableFuture.completedFuture(
                            isStreaming(response)
                                    ? reconstruct(response.body())
                                    : toLlmResponse(parseResponse(AnthropicMessagesResponse.class, response)));
                } catch (final LlmResponseParseException e) {
                    return failed(e);
                }
            });
        } catch (final RuntimeException e) {
            return failed(e);
        }
    }

    /**
     * Whether the response to a non-streaming {@link #chat(LlmRequest, BooleanSupplier)} is actually an
     * SSE stream. Some Anthropic-compatible gateways (e.g. Azure AI Foundry) ignore {@code stream: false}
     * and always answer with {@code text/event-stream}; the collected body must then be reconstructed
     * from the frames instead of being handed to the JSON mapper (whose first-byte failure on
     * {@code event:}/{@code data:} was the observable "Expected structural character" warning).
     */
    private static boolean isStreaming(final HttpResponse<String> response) {
        return response.headers()
                .firstValue("content-type")
                .orElse("")
                .toLowerCase(Locale.ROOT)
                .contains("text/event-stream");
    }

    /**
     * Reconstructs an {@link LlmResponse} from an SSE body received as the answer to a non-streaming
     * request. Reuses the exact same frame mapping as the streaming path ({@link #mapEvent}) and the
     * same text/tool-call accumulation as the core pump, so an always-streaming gateway produces the
     * same neutral result as a compliant one. A frame set that yields neither text nor tool calls and
     * no terminal {@code message_delta} is treated as a failed reconstruction (the caller surfaces the
     * contextual error), never as an empty completion.
     */
    private LlmResponse reconstruct(final String body) {
        final var text = new StringBuilder();
        final var toolCalls = new ArrayList<LlmToolCall>();
        final var byId = new HashMap<String, PartialToolCall>();
        final var byIndex = new HashMap<Integer, PartialToolCall>();
        final var creationOrder = new ArrayList<PartialToolCall>();
        final var usage = new Usage[] {Usage.EMPTY};
        final var finish = new String[] {null};
        final var started = new boolean[] {false};
        final var ended = new boolean[] {false};
        final var id = new String[] {null};
        final var model = new String[] {null};
        final var sse = new SseParser();
        for (final var rawLine : body.split("\n", -1)) {
            for (final var event : sse.feed(rawLine)) {
                final var wire = parse(AnthropicStreamEvent.class, event.data());
                switch (wire.type()) {
                    case "message_start" -> {
                        started[0] = true;
                        if (wire.message() != null) {
                            id[0] = wire.message().id();
                            model[0] = wire.message().model();
                            usage[0] = toUsage(wire.message().usage());
                        }
                    }
                    case "content_block_delta" -> {
                        if (wire.delta() == null) {
                            break;
                        }
                        final var deltaText = wire.delta().text();
                        if (deltaText != null && !deltaText.isEmpty()) {
                            text.append(deltaText);
                        }
                        if ("input_json_delta".equals(wire.delta().type())
                                && wire.delta().partialJson() != null) {
                            final var index = wire.index() == null ? 0 : wire.index();
                            toolCallDelta(
                                    byId,
                                    byIndex,
                                    creationOrder,
                                    index,
                                    null,
                                    null,
                                    wire.delta().partialJson());
                        }
                    }
                    case "content_block_start" -> {
                        if (wire.contentBlock() != null
                                && "tool_use".equals(wire.contentBlock().type())) {
                            final var index = wire.index() == null ? 0 : wire.index();
                            toolCallDelta(
                                    byId,
                                    byIndex,
                                    creationOrder,
                                    index,
                                    wire.contentBlock().id(),
                                    wire.contentBlock().name(),
                                    "");
                        }
                    }
                    case "message_delta" -> {
                        ended[0] = true;
                        if (wire.delta() != null && wire.delta().stopReason() != null) {
                            finish[0] = finishReason(wire.delta().stopReason());
                        }
                        if (wire.usage() != null) {
                            usage[0] = mergeUsage(usage[0], wire.usage());
                        }
                    }
                    default -> {
                        // content_block_stop / message_stop / ping ignored
                    }
                }
            }
        }
        for (final var call : creationOrder) {
            toolCalls.add(call.toToolCall());
        }
        if (!toolCalls.isEmpty()) {
            final var index = new AtomicInteger();
            final var neutralCalls = new ArrayList<LlmToolCall>(toolCalls.size());
            for (final var call : toolCalls) {
                neutralCalls.add(new LlmToolCall(call.id(), call.type(), call.function(), index.getAndIncrement()));
            }
            // keep the inter-tool narration: a model may stream text both before and between
            // tool_use blocks, and dropping it would lose the explanation accompanying the calls
            final var content = text.toString();
            return new LlmResponse(
                    id[0],
                    model[0],
                    null,
                    List.of(new LlmResponse.LlmChoice(
                            0,
                            new LlmMessage(
                                    "assistant", content.isEmpty() ? null : content, null, neutralCalls, null, null),
                            "tool_calls")),
                    usage[0],
                    null);
        }
        final var content = text.toString();
        if (!started[0] || (!ended[0] && content.isEmpty())) {
            throw new IllegalStateException(
                    "provider answered with a stream for a non-streaming request but produced no complete message (started="
                            + started[0] + ", ended=" + ended[0] + ", chars=" + content.length() + ", tools="
                            + toolCalls.size() + ")");
        }
        final var finishReason = finish[0] == null ? "stop" : finish[0];
        return new LlmResponse(
                id[0],
                model[0],
                null,
                List.of(new LlmResponse.LlmChoice(
                        0, new LlmMessage("assistant", content, null, null, null, null), finishReason)),
                usage[0],
                null);
    }

    /** Registers/appends a tool-call argument fragment, mirroring the streaming-path assembly. */
    private static void toolCallDelta(
            final Map<String, PartialToolCall> byId,
            final Map<Integer, PartialToolCall> byIndex,
            final List<PartialToolCall> creationOrder,
            final int index,
            final String id,
            final String name,
            final String args) {
        final var partial = resolvePartial(byId, byIndex, index, id, creationOrder);
        if (id != null) {
            byId.put(id, partial);
        }
        byIndex.put(index, partial);
        if (id != null && partial.id == null) {
            partial.id = id;
        }
        if (name != null) {
            partial.name = name;
        }
        if (args != null) {
            partial.arguments.append(args);
        }
    }

    /** Minimal partial tool-call accumulator (mirrors the core pump's {@code PartialToolCall}). */
    private static final class PartialToolCall {
        private final int index;
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        private PartialToolCall(final int index) {
            this.index = index;
        }

        private LlmToolCall toToolCall() {
            return new LlmToolCall(id, "function", new LlmToolCall.FunctionCall(name, arguments.toString()), index);
        }

        private String arguments() {
            return arguments.toString();
        }
    }

    private static PartialToolCall resolvePartial(
            final Map<String, PartialToolCall> byId,
            final Map<Integer, PartialToolCall> byIndex,
            final int index,
            final String id,
            final List<PartialToolCall> creationOrder) {
        final var fromId = id != null ? byId.get(id) : null;
        final var fromIndex = byIndex.get(index);
        if (fromId != null
                && fromIndex != null
                && fromIndex != fromId
                && fromIndex.id != null
                && !fromIndex.id.equals(id)) {
            byIndex.put(index, fromId);
            return fromId;
        }
        if (fromId == null && fromIndex != null && fromIndex.id != null && id != null && !fromIndex.id.equals(id)) {
            final var created = new PartialToolCall(index);
            created.id = id;
            creationOrder.add(created);
            byId.put(id, created);
            byIndex.put(index, created);
            return created;
        }
        if (fromId != null) {
            if (fromIndex != null && fromIndex != fromId) {
                fromId.arguments.append(fromIndex.arguments());
                if (fromId.name == null) {
                    fromId.name = fromIndex.name;
                }
                if (fromId.id == null) {
                    fromId.id = fromIndex.id;
                }
                byIndex.put(index, fromId);
            } else if (fromIndex == null) {
                byIndex.put(index, fromId);
            }
            return fromId;
        }
        if (fromIndex != null) {
            if (id != null) {
                byId.put(id, fromIndex);
            }
            return fromIndex;
        }
        final var created = new PartialToolCall(index);
        creationOrder.add(created);
        if (id != null) {
            byId.put(id, created);
        }
        byIndex.put(index, created);
        return created;
    }

    @Override
    public CompletionStage<Flow.Publisher<LlmStreamEvent>> stream(
            final LlmRequest request, final BooleanSupplier cancelled) {
        try {
            final var payload = mapper.toBytes(toWire(request, true));
            final var usage = new Usage[] {Usage.EMPTY};
            return openStream(payload, cancelled)
                    .thenCompose(attempt -> attempt.ok()
                            ? CompletableFuture.completedFuture(Streams.toEvents(
                                    attempt.live(), cancelled, event -> mapEvent(event, usage), () -> null, onRawLine))
                            : streamError(attempt));
        } catch (final RuntimeException e) {
            return failed(e);
        }
    }

    @Override
    public CompletionStage<LlmModels> models() {
        try {
            return collectModels(null);
        } catch (final RuntimeException e) {
            return failed(e);
        }
    }

    /**
     * Paged models collection using the Anthropic cursor contract ({@code after_id}/{@code last_id},
     * page size capped at the provider maximum) to avoid truncating backends that paginate. It stops
     * as soon as a page reports {@code has_more == false}, has no data, carries no {@code last_id},
     * or fails to advance the cursor relative to the requested page. The requested cursor is the
     * {@code after_id} of the current page.
     */
    private CompletionStage<LlmModels> collectModels(final String afterId) {
        return getModels(afterId, "1000").thenCompose(response -> {
            try {
                final var page = parseResponse(AnthropicModels.class, response);
                final var models = toModels(page);
                if (!Boolean.TRUE.equals(page.hasMore())
                        || page.data() == null
                        || page.data().isEmpty()
                        || page.lastId() == null
                        || page.lastId().isBlank()
                        || page.lastId().equals(afterId)) { // no cursor progress -> stop, even if has_more
                    return CompletableFuture.completedFuture(models);
                }
                return collectModels(page.lastId()).thenApply(models::appendDistinct);
            } catch (final LlmResponseParseException e) {
                return failed(e);
            }
        });
    }

    /**
     * Maps a typed Anthropic models page into the neutral model listing, carrying the known
     * capability fields ({@code max_input_tokens}, {@code max_tokens}, {@code display_name}, ...) so
     * the downstream heuristics (context window, output budget) keep working.
     */
    private static LlmModels toModels(final AnthropicModels page) {
        final var data = page.data();
        if (data == null || data.isEmpty()) {
            return new LlmModels(List.of());
        }
        final var models = new ArrayList<LlmModel>(data.size());
        final var seen = new HashSet<String>(data.size());
        for (final var model : data) {
            if (model == null || model.id() == null || model.id().isBlank() || !seen.add(model.id())) {
                continue;
            }
            final var others = new LinkedHashMap<String, Object>();
            if (model.type() != null) {
                others.put("type", model.type());
            }
            if (model.displayName() != null) {
                others.put("display_name", model.displayName());
            }
            if (model.createdAt() != null) {
                others.put("created_at", model.createdAt());
            }
            if (model.maxInputTokens() != null) {
                others.put("max_input_tokens", model.maxInputTokens());
            }
            if (model.maxTokens() != null) {
                others.put("max_tokens", model.maxTokens());
            }
            models.add(new LlmModel(model.id(), null, null, null, others));
        }
        return new LlmModels(models);
    }

    @Override
    protected byte[] encodeRequest(final LlmRequest request, final boolean stream) {
        return mapper.toBytes(toWire(request, stream));
    }

    private AnthropicMessagesRequest toWire(final LlmRequest request, final boolean stream) {
        Map<String, Object> toolChoice = null;
        if (request.toolChoice() instanceof String s) {
            final var choice =
                    switch (s) {
                        case "none" -> "none";
                        case "required", "any" -> "any";
                        default -> "auto";
                    };
            toolChoice = Map.of("type", choice);
        }
        String system = null;
        final List<AnthropicMessage> messages = new ArrayList<>();
        final var requestExtensions = new HashMap<String, Object>(provider.options());
        if (request.extensions() != null) {
            requestExtensions.putAll(request.extensions());
        }
        final var cacheMode = String.valueOf(requestExtensions.get("promptCache"));
        final var promptCache = !"false".equalsIgnoreCase(cacheMode);
        final var systemOnlyCache = "system".equalsIgnoreCase(cacheMode);
        final var splitBreakpoints = new ArrayList<Map<String, Object>>();
        final var splitBlocks = isSplitBlocksEnabled(requestExtensions);
        // Anthropic requires every assistant tool_use block to be immediately answered by its
        // tool_result block(s): an unmatched tool_use (its result never landed, e.g. a queued prompt,
        // an interrupted turn or legacy/corrupted persisted history) makes the gateway reject the whole
        // request with "tool_use ids were found without tool_result blocks immediately after". The
        // agent loop normally preserves the pairing, but this is the wire boundary, so a broken history
        // is sanitized here (Anthropic-specific; OpenAI has no such adjacency requirement): only the
        // tool_use ids answered by a CONTIGUOUS run of tool messages immediately following their
        // assistant message survive, and orphan tool results are dropped. Any dropped block is WARN-logged
        // so silently-lost context stays observable (the agent loop repairs this inline, this is the backstop).
        //
        // The check is bounded to the current tail region (see {@link #verificationStart}): older,
        // already-completed turns are guaranteed-paired by the agent loop and emitted as-is, so the per
        // request cost does not grow with the total accumulated history - only with the live context.
        final var verificationStart = verificationStart(request.messages());
        final var matchedToolCalls =
                LlmMessage.matchedToolCallIds(messagesSlice(request.messages(), verificationStart));
        final var dropped = new ArrayList<String>();
        if (request.messages() != null) {
            for (var i = 0; i < request.messages().size(); i++) {
                final var message = request.messages().get(i);
                final var trusted = i < verificationStart;
                if ("system".equals(message.role())) {
                    final var text = textOf(message.content());
                    // multiple system messages must not be last-one-wins: concatenate them so every
                    // system instruction reaches the wire (Anthropic takes a single system string)
                    system = system == null || system.isEmpty() ? text : system + "\n\n" + text;
                } else if ("assistant".equals(message.role())
                        && message.toolCalls() != null
                        && !message.toolCalls().isEmpty()) {
                    final var content = new ArrayList<Map<String, Object>>();
                    final var text = textOf(message.content());
                    if (text != null && !text.isEmpty()) {
                        content.add(Map.of("type", "text", "text", text));
                    }
                    for (final var call : message.toolCalls()) {
                        if (!trusted && call != null && !matchedToolCalls.contains(call.id())) {
                            // dangling tool_use: skip it so no unmatched block reaches the wire
                            dropped.add(call.id());
                            continue;
                        }
                        content.add(Map.of(
                                "type",
                                "tool_use",
                                "id",
                                call.id(),
                                "name",
                                call.function() == null ? null : call.function().name(),
                                "input",
                                parseRaw(
                                        call.function() == null
                                                ? null
                                                : call.function().arguments())));
                    }
                    messages.add(new AnthropicMessage("assistant", content));
                } else if ("tool".equals(message.role())) {
                    if (!trusted && !matchedToolCalls.contains(message.toolCallId())) {
                        // orphan tool_result: its tool_use was never answered contiguously, drop it
                        dropped.add(message.toolCallId());
                        continue;
                    }
                    final var messageExtensions = message.extensions();
                    final var isError =
                            messageExtensions != null && Boolean.TRUE.equals(messageExtensions.get("is_error"));
                    final var text = textOf(message.content());
                    // the volatile session-state tail is split off the tool result so the cache
                    // breakpoint sits on the tool_result block: only the tail re-reads per request
                    final var split = splitBlocks && isVolatileTail(messageExtensions)
                            ? text.lastIndexOf("\n\n<session-state>")
                            : -1;
                    final var content = new ArrayList<Map<String, Object>>();
                    final var result = new LinkedHashMap<String, Object>();
                    result.put("type", "tool_result");
                    result.put("tool_use_id", message.toolCallId());
                    result.put("content", split >= 0 ? text.substring(0, split) : text);
                    if (isError) {
                        result.put("is_error", true);
                    }
                    if (promptCache && !systemOnlyCache && split >= 0) {
                        splitBreakpoints.add(result);
                    }
                    content.add(result);
                    if (split >= 0) {
                        content.add(Map.of("type", "text", "text", text.substring(split + 2)));
                    }
                    messages.add(new AnthropicMessage("user", content));
                } else {
                    final var messageExtensions = message.extensions();
                    if (promptCache
                            && splitBlocks
                            && isVolatileTail(messageExtensions)
                            && message.content() instanceof String s) {
                        final var split = s.lastIndexOf("\n\n<session-state>");
                        if (split >= 0) {
                            final var stable = new LinkedHashMap<String, Object>();
                            stable.put("type", "text");
                            stable.put("text", s.substring(0, split));
                            if (!systemOnlyCache) {
                                splitBreakpoints.add(stable);
                            }
                            messages.add(new AnthropicMessage(
                                    "user", List.of(stable, Map.of("type", "text", "text", s.substring(split + 2)))));
                            continue;
                        }
                    }
                    messages.add(AnthropicMessage.from(message));
                }
            }
        }
        if (!dropped.isEmpty()) {
            LOGGER.fine(() -> "sanitized " + dropped.size() + " tool block(s) with no contiguously-paired counterpart "
                    + "(tool_use without an immediately-following tool_result, or orphan tool_result) before sending: "
                    + dropped + ". The agent loop should repair these inline; this is the wire backstop.");
        }
        final List<AnthropicTool> tools = new ArrayList<>();
        if (request.tools() != null) {
            for (final var tool : request.tools()) {
                tools.add(new AnthropicTool(tool.name(), tool.description(), tool.parameters()));
            }
        }
        final var maxTokens = request.maxTokens() == null ? 4096 : request.maxTokens();
        Integer topK = null;
        if (requestExtensions.get("top_k") instanceof Number n) {
            topK = n.intValue();
        }
        final var passthrough = new HashMap<>(requestExtensions);
        passthrough.remove("promptCache");
        passthrough.remove("anthropicSplitBlocks");
        passthrough.remove("top_k");
        if (!promptCache) {
            passthrough.remove("cache_control");
            for (var i = 0; i < messages.size(); i++) {
                messages.set(i, withoutCacheControl(messages.get(i)));
            }
        }
        // Configure caching after wire conversion so plain text, tool_use and tool_result all
        // participate, independently of provider ids. Never add a fifth breakpoint or mutate
        // caller-owned content. Explicit controls (including null to suppress automatic caching)
        // take precedence; use their TTL on earlier generated breakpoints too (long TTLs go first).
        final var control = requestExtensions.get("cache_control") instanceof Map<?, ?> explicit
                ? explicit
                : Map.of("type", "ephemeral");
        final var explicitBreakpoints = cacheBreakpoints(messages);
        // A valid caller-supplied 1h block must not acquire a generated 5m system prefix.
        // Leave mixed explicit block TTL ordering to the caller, and don't insert legacy
        // split breakpoints among their controls.
        final var systemControl = explicitBreakpoints.stream().anyMatch(c -> "1h".equals(c.get("ttl")))
                ? Map.of("type", "ephemeral", "ttl", "1h")
                : control;
        var remainingBreakpoints = 4 - explicitBreakpoints.size();
        // Existing block controls already opt into explicit caching. Don't introduce an
        // automatic endpoint with a potentially conflicting TTL; only forward one if the
        // caller explicitly supplies it, reserving its slot before generated breakpoints.
        final var automatic = promptCache
                && !systemOnlyCache
                && (requestExtensions.get("cache_control") instanceof Map<?, ?>
                        || (!requestExtensions.containsKey("cache_control") && explicitBreakpoints.isEmpty()));
        if (automatic) {
            passthrough.put("cache_control", control);
            remainingBreakpoints--;
        } else {
            passthrough.remove("cache_control");
        }
        final var cacheSystem = promptCache && system != null && remainingBreakpoints > 0;
        if (cacheSystem) {
            remainingBreakpoints--;
        }
        for (var i = splitBreakpoints.size() - 1; i >= 0 && remainingBreakpoints > 0; i--) {
            if (explicitBreakpoints.isEmpty()) {
                splitBreakpoints.get(i).put("cache_control", control);
                remainingBreakpoints--;
            }
        }
        return new AnthropicMessagesRequest(
                request.model(),
                maxTokens,
                system == null ? null : toSystemBlocks(system, cacheSystem ? systemControl : null),
                messages,
                request.temperature(),
                request.topP(),
                topK,
                request.stop(),
                stream,
                tools.isEmpty() ? null : tools,
                toolChoice,
                null,
                passthrough);
    }

    /**
     * First index of the region whose pairing is verified before sending: the current turn burst plus,
     * when a dangling {@code tool_use} sits right before it (an interrupted round followed by an injected
     * prompt), that one preceding assistant message. Everything before this index is trusted as-is:
     * the agent loop always pairs each {@code tool_use} with the contiguous {@code tool_result}s of its own
     * round, so completed older rounds cannot become dangling. Bounding the verification to this tail keeps
     * the per-request cost proportional to the live context, not to the total accumulated history.
     *
     * @param messages the neutral messages, may be {@code null}.
     * @return the first index (inclusive) of the verified region; {@code 0} when the whole list is verified.
     */
    private static int verificationStart(final List<LlmMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int lastUserPrompt = -1;
        int lastToolCallsAssistant = -1;
        for (var i = 0; i < messages.size(); i++) {
            final var message = messages.get(i);
            if (message == null) {
                continue;
            }
            if ("user".equals(message.role())) {
                lastUserPrompt = i;
            } else if ("assistant".equals(message.role())
                    && message.toolCalls() != null
                    && !message.toolCalls().isEmpty()) {
                lastToolCallsAssistant = i;
            }
        }
        if (lastToolCallsAssistant < 0) {
            return lastUserPrompt + 1; // orphan tool results after the last prompt are caught; none before
        }
        return Math.max(0, Math.min(lastToolCallsAssistant, lastUserPrompt + 1));
    }

    /** The messages of {@code messages} at or after {@code from}, empty when {@code messages} is null. */
    private static List<LlmMessage> messagesSlice(final List<LlmMessage> messages, final int from) {
        if (messages == null) {
            return List.of();
        }
        final var start = Math.max(0, Math.min(from, messages.size()));
        return messages.subList(start, messages.size());
    }

    /**
     * Anthropic caches the tools + system prefix: sending the system prompt as content blocks with an
     * ephemeral {@code cache_control} breakpoint makes the stable system prompt (rules, project
     * context, skills) a cache hit across turns instead of a full re-read every request.
     */
    private static Object toSystemBlocks(final String system, final Map<?, ?> control) {
        if (control == null) {
            return system;
        }
        return List.of(Map.of("type", "text", "text", system, "cache_control", control));
    }

    private static List<Map<?, ?>> cacheBreakpoints(final List<AnthropicMessage> messages) {
        final var controls = new ArrayList<Map<?, ?>>();
        for (final var message : messages) {
            if (message.content() instanceof List<?> blocks) {
                for (final var block : blocks) {
                    if (block instanceof Map<?, ?> map && map.get("cache_control") instanceof Map<?, ?> control) {
                        controls.add(control);
                    }
                }
            }
        }
        return controls;
    }

    private static AnthropicMessage withoutCacheControl(final AnthropicMessage message) {
        if (!(message.content() instanceof List<?> blocks)) {
            return message;
        }
        final var content = new ArrayList<Object>(blocks.size());
        for (final var block : blocks) {
            if (block instanceof Map<?, ?> map && map.containsKey("cache_control")) {
                final var copy = new LinkedHashMap<>(map);
                copy.remove("cache_control");
                content.add(copy);
            } else {
                content.add(block);
            }
        }
        return new AnthropicMessage(message.role(), content);
    }

    /** Flags the message carrying the volatile session-state tail appended by the agent loop on the wire. */
    private static boolean isVolatileTail(final Map<String, Object> extensions) {
        return extensions != null && Boolean.TRUE.equals(extensions.get("volatileTail"));
    }

    /**
     * Whether the volatile-tail block split is active for this request: only an explicit
     * {@code anthropicSplitBlocks} request extension opts in (Boolean or string "true"/"false");
     * without it the tail stays inline. The split is disabled by default and never auto-detected
     * from the endpoint host: token-level gateways and anthropic.com are served the same way unless
     * the caller explicitly requests the block-granular breakpoint.
     */
    private boolean isSplitBlocksEnabled(final Map<String, Object> extensions) {
        final var value = extensions.get("anthropicSplitBlocks");
        if (value instanceof Boolean b) {
            return b;
        }
        if (value != null) {
            final var s = String.valueOf(value);
            if ("true".equalsIgnoreCase(s)) {
                return true;
            }
            if ("false".equalsIgnoreCase(s)) {
                return false;
            }
        }
        return false;
    }

    private Object parseRaw(final String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return parse(Map.class, raw);
        } catch (final RuntimeException e) {
            return raw;
        }
    }

    private LlmResponse toLlmResponse(final AnthropicMessagesResponse wire) {
        final List<LlmToolCall> toolCalls = new ArrayList<>();
        final var text = new StringBuilder();
        if (wire.content() != null) {
            for (final var block : wire.content()) {
                if (block.text() != null) {
                    text.append(block.text());
                } else if ("tool_use".equals(block.type()) && block.name() != null) {
                    toolCalls.add(new LlmToolCall(
                            block.id(),
                            "function",
                            new LlmToolCall.FunctionCall(block.name(), toJson(block.input())),
                            0));
                }
            }
        }
        final var index = new AtomicInteger();
        final LlmResponse.LlmChoice choice;
        if (!toolCalls.isEmpty()) {
            final var neutralCalls = new ArrayList<LlmToolCall>(toolCalls.size());
            for (final var call : toolCalls) {
                neutralCalls.add(new LlmToolCall(call.id(), call.type(), call.function(), index.getAndIncrement()));
            }
            // keep the inter-tool narration carried by text blocks alongside the tool calls
            final var content = text.toString();
            choice = new LlmResponse.LlmChoice(
                    0,
                    new LlmMessage("assistant", content.isEmpty() ? null : content, null, neutralCalls, null, null),
                    "tool_calls");
        } else {
            choice = new LlmResponse.LlmChoice(
                    0,
                    new LlmMessage("assistant", text.toString(), null, null, null, null),
                    finishReason(wire.stopReason()));
        }
        final var usage = wire.usage() == null ? Usage.EMPTY : toUsage(wire.usage());
        return new LlmResponse(wire.id(), wire.model(), null, List.of(choice), usage, null);
    }

    private String toJson(final Object input) {
        try {
            return mapper.toString(input);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private static String finishReason(final String stopReason) {
        if (stopReason == null) {
            return "stop";
        }
        return switch (stopReason) {
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            default -> "stop";
        };
    }

    private List<LlmStreamEvent> mapEvent(final SseParser.Event event, final Usage[] usage) {
        try {
            if (event.data() == null) {
                return List.of();
            }
            final var wire = parse(AnthropicStreamEvent.class, event.data());
            final var result = new ArrayList<LlmStreamEvent>();
            switch (wire.type()) {
                case "message_start" -> {
                    if (wire.message() != null) {
                        usage[0] = toUsage(wire.message().usage());
                    }
                }
                case "content_block_delta" -> {
                    if (wire.delta() != null) {
                        final var text = wire.delta().text();
                        if (text != null && !text.isEmpty()) {
                            result.add(new LlmStreamEvent.TextDelta(text));
                        }
                        final var thinking = wire.delta().thinking();
                        if (thinking != null && !thinking.isEmpty()) {
                            result.add(new LlmStreamEvent.ReasoningDelta(thinking));
                        }
                        if ("input_json_delta".equals(wire.delta().type())
                                && wire.delta().partialJson() != null) {
                            final var index = wire.index() == null ? 0 : wire.index();
                            result.add(new LlmStreamEvent.ToolCallDelta(
                                    index, null, null, wire.delta().partialJson()));
                        }
                    }
                }
                case "content_block_start" -> {
                    if (wire.contentBlock() != null
                            && "tool_use".equals(wire.contentBlock().type())) {
                        result.add(new LlmStreamEvent.ToolCallDelta(
                                wire.index() == null ? 0 : wire.index(),
                                wire.contentBlock().id(),
                                wire.contentBlock().name(),
                                ""));
                    }
                }
                case "message_delta" -> {
                    usage[0] = mergeUsage(usage[0], wire.usage());
                    if (wire.delta() != null && wire.delta().stopReason() != null) {
                        result.add(
                                new LlmStreamEvent.End(finishReason(wire.delta().stopReason()), usage[0]));
                    }
                }
                default -> {
                    // message_start / content_block_stop / message_stop / ping ignored
                }
            }
            return result;
        } catch (final RuntimeException e) {
            warnMapEventFailure("anthropic", event.data(), e);
            return List.of();
        }
    }

    private static Usage toUsage(final AnthropicMessagesResponse.AnthropicUsage usage) {
        if (usage == null) {
            return Usage.EMPTY;
        }
        final var in = usage.inputTokens() == null ? 0 : usage.inputTokens();
        final var out = usage.outputTokens() == null ? 0 : usage.outputTokens();
        return new Usage(
                in,
                out,
                in + out,
                usage.cacheReadInputTokens(),
                usage.cacheCreationInputTokens(),
                thinkingTokens(usage),
                UsageExtensions.generationTimeMs(usage.others(), out),
                in
                        + (usage.cacheReadInputTokens() == null ? 0 : usage.cacheReadInputTokens())
                        + (usage.cacheCreationInputTokens() == null ? 0 : usage.cacheCreationInputTokens()));
    }

    /** Delta usage is cumulative; omitted input/cache fields retain the message-start values. */
    private static Usage mergeUsage(final Usage previous, final AnthropicMessagesResponse.AnthropicUsage update) {
        if (update == null) {
            return previous;
        }
        if (previous == Usage.EMPTY) {
            return toUsage(update);
        }
        final var input = update.inputTokens() == null ? previous.prompt() : update.inputTokens();
        final var output = update.outputTokens() == null ? previous.completion() : update.outputTokens();
        final var read =
                update.cacheReadInputTokens() == null ? previous.cacheReadTokens() : update.cacheReadInputTokens();
        final var write = update.cacheCreationInputTokens() == null
                ? previous.cacheWriteTokens()
                : update.cacheCreationInputTokens();
        final var thinking = thinkingTokens(update);
        return new Usage(
                input,
                output,
                input + output,
                read,
                write,
                thinking == null ? previous.reasoningTokens() : thinking,
                UsageExtensions.generationTimeMs(update.others(), output),
                input + (read == null ? 0 : read) + (write == null ? 0 : write));
    }

    private static Long thinkingTokens(final AnthropicMessagesResponse.AnthropicUsage usage) {
        if (usage == null || usage.others() == null) {
            return null;
        }
        final var details = usage.others().get("output_tokens_details");
        if (details instanceof Map<?, ?> map) {
            final var tokens = map.get("thinking_tokens");
            if (tokens instanceof Number n) {
                return n.longValue();
            }
        }
        return null;
    }
}
