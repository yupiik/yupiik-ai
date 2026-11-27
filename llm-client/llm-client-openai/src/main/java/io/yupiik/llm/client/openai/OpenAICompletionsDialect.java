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
import io.yupiik.llm.client.model.LlmTool;
import io.yupiik.llm.client.model.LlmToolCall;
import io.yupiik.llm.client.model.Usage;
import io.yupiik.llm.client.openai.model.ChatChunk;
import io.yupiik.llm.client.openai.model.ChatChunk.ChunkChoice;
import io.yupiik.llm.client.openai.model.ChatCompletionRequest;
import io.yupiik.llm.client.openai.model.ChatCompletionResponse;
import io.yupiik.llm.client.openai.model.CompletionsTool;
import io.yupiik.llm.client.openai.model.WireMessage;
import io.yupiik.llm.client.openai.model.WireTool;
import io.yupiik.llm.client.spi.AbstractDialect;
import io.yupiik.llm.client.spi.ProviderSpec;
import io.yupiik.llm.client.spi.SseParser;
import io.yupiik.llm.client.spi.Streams;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Logger;

/** OpenAI {@code /chat/completions} reactive dialect. */
public class OpenAICompletionsDialect extends AbstractDialect {

    private static final Logger LOGGER = Logger.getLogger(OpenAICompletionsDialect.class.getName());

    private volatile Consumer<String> onRawLine;

    /**
     * Creates the dialect.
     *
     * @param mapper JSON mapper used for wire serialization.
     * @param client HTTP client used for provider calls.
     * @param provider provider coordinates (base URL, authentication, options).
     */
    public OpenAICompletionsDialect(
            final JsonMapper mapper, final ExtendedHttpClient client, final ProviderSpec provider) {
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
     * @return the routing identifier of this dialect, {@code openai-completions}.
     */
    @Override
    public String name() {
        return "openai-completions";
    }

    /**
     * Exposes the chat route appended to the provider base URL.
     *
     * @return the chat route suffix appended to the provider base URL.
     */
    @Override
    protected String chatRouteSuffix() {
        return "/chat/completions";
    }

    @Override
    public CompletionStage<LlmResponse> chat(final LlmRequest request) {
        return chat(request, null);
    }

    @Override
    public CompletionStage<LlmResponse> chat(final LlmRequest request, final BooleanSupplier cancelled) {
        try {
            final var payload = encodeRequest(request, false);
            return post(payload).thenCompose(response -> {
                try {
                    return CompletableFuture.completedFuture(
                            toLlmResponse(parseResponse(ChatCompletionResponse.class, response)));
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
            final var payload = encodeRequest(request, true);
            return openStream(payload, cancelled)
                    .thenCompose(attempt -> attempt.ok()
                            ? CompletableFuture.completedFuture(
                                    Streams.toEvents(attempt.live(), cancelled, this::mapEvent, () -> null, onRawLine))
                            : streamError(attempt));
        } catch (final RuntimeException e) {
            return failed(e);
        }
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

    private ChatCompletionRequest toWire(final LlmRequest request, final boolean stream) {
        final var sanitized = request.messages() == null ? null : sanitizeToolCalls(request.messages());
        final var messages = sanitized == null
                ? List.<WireMessage>of()
                : sanitized.stream().map(WireMessage::from).toList();
        final var tools = request.tools() == null
                ? null
                : request.tools().stream()
                        .map(OpenAICompletionsDialect::toWireTool)
                        .toList();
        return new ChatCompletionRequest(
                request.model(),
                messages,
                request.temperature(),
                request.topP(),
                null,
                // explicit intent on both branches: background (non-streaming) calls must be
                // distinguishable from an omitted field, and some OpenAI-compatible servers
                // misbehave when the flag is absent
                stream,
                tools,
                request.toolChoice(),
                request.maxTokens(),
                null,
                request.stop(),
                null,
                null,
                null,
                null,
                request.reasoningEffort(),
                request.responseFormat(),
                // server-side conversation storage opt-out (OpenAI `store`), null = provider default
                request.store(),
                streamExtras(request, stream));
    }

    /**
     * Builds the passthrough extras for the request. When streaming, asks the provider to include
     * a usage summary in the final chunk ({@code stream_options.include_usage}) unless the caller
     * explicitly set {@code "includeUsage": false} or provided their own {@code stream_options}.
     */
    private static Map<String, Object> streamExtras(final LlmRequest request, final boolean stream) {
        final Map<String, Object> extras = new LinkedHashMap<>();
        if (request.extensions() != null) {
            extras.putAll(request.extensions());
        }
        final var explicit = extras.remove("includeUsage");
        final var disabled = explicit != null && !Boolean.parseBoolean(String.valueOf(explicit));
        if (stream && !disabled && !extras.containsKey("stream_options")) {
            extras.put("stream_options", Map.of("include_usage", true));
        }
        return extras;
    }

    private static CompletionsTool toWireTool(final LlmTool tool) {
        return new CompletionsTool(
                "function",
                new WireTool.WireToolFunction(tool.name(), tool.description(), tool.parameters(), tool.strict()));
    }

    /**
     * OpenAI-compatible backends (OpenAI, sglang, Azure, litellm, ...) reject an assistant message whose
     * {@code tool_calls} are not answered by matching tool messages ("An assistant message with 'tool_calls'
     * must be followed by tool messages responding to each 'tool_call_id'"), a hard 400. Anthropic carries a
     * wire backstop for its own pairing rule; this is the symmetric OpenAI one. Older completed turns are
     * trusted as-is (the agent loop always pairs them): only the live tail is verified, so per-request cost
     * stays O(live tail), not O(history). Any assistant tool call whose id was never answered contiguously,
     * and any orphan tool result, is stripped from the wire and FINE-logged - the request then validates
     * instead of failing wholesale.
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
        // most requests carry no tool traffic at all: skip the O(history) scan and rebuild entirely,
        // so the common pure-text path stays zero-cost (the previous unconditional verificationStart pass ran
        // a full-list scan on every request even when there was nothing to sanitize). A lone tool message
        // with no assistant tool_calls anywhere also needs the pass: it is an orphan response that strict
        // backends reject ("messages with role 'tool' must be a response to a preceding message with
        // 'tool_calls'"), so it must be caught even without any assistant tool round.
        var hasToolRound = false;
        for (final var probe : messages) {
            if (probe != null
                    && (("tool".equals(probe.role()) && probe.toolCallId() != null)
                            || ("assistant".equals(probe.role())
                                    && probe.toolCalls() != null
                                    && !probe.toolCalls().isEmpty()))) {
                hasToolRound = true;
                break;
            }
        }
        if (!hasToolRound) {
            return messages;
        }
        final var verificationStart = verificationStart(messages);
        final var unmatched = LlmMessage.unmatchedToolCallIds(
                messages.subList(Math.min(verificationStart, messages.size()), messages.size()));
        final var dropped = new ArrayList<String>();
        var changed = false;
        final var out = new ArrayList<LlmMessage>(messages.size());
        // the tool-call ids of the assistant block currently being walked through the verified region:
        // a tool message is a valid response only while it consumes one of these (the contiguous run rule),
        // anything else is an orphan response with no assistant tool_calls behind it.
        var pendingResponses = new HashSet<String>();
        for (var i = 0; i < messages.size(); i++) {
            final var message = messages.get(i);
            if (i < verificationStart) {
                out.add(message); // trusted: agent-loop paired, emit as-is
                continue;
            }
            if (message == null) {
                out.add(message);
                continue;
            }
            if ("assistant".equals(message.role())
                    && message.toolCalls() != null
                    && !message.toolCalls().isEmpty()) {
                pendingResponses = new HashSet<>();
                final var kept = new ArrayList<LlmToolCall>(message.toolCalls().size());
                for (final var call : message.toolCalls()) {
                    if (call != null && call.id() != null && unmatched.contains(call.id())) {
                        dropped.add(call.id());
                    } else {
                        kept.add(call);
                        if (call.id() != null && pendingResponses.size() < MAX_RESPONSE_PENDING_IDS) {
                            pendingResponses.add(call.id());
                        }
                    }
                }
                if (kept.isEmpty()) {
                    // no surviving call: keep a text-only assistant message (drop the empty toolCalls) so the
                    // plain text answer, if any, is still served
                    changed = true;
                    out.add(new LlmMessage(
                            message.role(),
                            message.content(),
                            message.name(),
                            null,
                            message.toolCallId(),
                            message.extensions()));
                } else if (kept.size() != message.toolCalls().size()) {
                    changed = true;
                    out.add(new LlmMessage(
                            message.role(),
                            message.content(),
                            message.name(),
                            kept,
                            message.toolCallId(),
                            message.extensions()));
                } else {
                    out.add(message);
                }
            } else if ("tool".equals(message.role()) && message.toolCallId() != null) {
                // a `tool` result whose id no contiguous assistant tool_call claims is an ORPHAN and must be
                // dropped: Azure/litellm reject the reverse direction too ("messages with role 'tool' must
                // be a response to a preceding message with 'tool_calls'"), so a lone tool message 400s the
                // whole request. (The Responses API dialect does permit standalone function_call_output
                // items, so only this completions backstop drops them; the agent loop repairs these inline
                // first - this is the wire backstop.)
                if (pendingResponses.remove(message.toolCallId())) {
                    out.add(message);
                } else {
                    dropped.add(message.toolCallId());
                    changed = true;
                }
            } else {
                // any non-tool message closes the contiguous run of the previous assistant block
                pendingResponses = new HashSet<>();
                out.add(message);
            }
        }
        if (!dropped.isEmpty()) {
            LOGGER.fine(() -> "sanitized " + dropped.size() + " orphan tool-call id(s) (an assistant tool_calls with "
                    + "no contiguously-answered tool response, or a tool response no contiguous assistant tool_calls "
                    + "claims - OpenAI-compatible backends such as OpenAI, sglang, Azure and litellm reject both "
                    + "directions) before sending: " + dropped
                    + ". The agent loop should repair these inline; this is the wire backstop.");
        }
        return changed ? out : messages;
    }

    /** Upper bound on the pending response ids tracked while walking the verified region. */
    private static final int MAX_RESPONSE_PENDING_IDS = 256;

    /** First index of the region whose tool-call pairing is verified before sending. Mirrors AnthropicDialect. */
    private static int verificationStart(final List<LlmMessage> messages) {
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
            return lastUserPrompt + 1;
        }
        return Math.max(0, Math.min(lastToolCallsAssistant, lastUserPrompt + 1));
    }

    private LlmResponse toLlmResponse(final ChatCompletionResponse response) {
        final var index = new AtomicInteger();
        final var choices = response.choices() == null
                ? List.<LlmResponse.LlmChoice>of()
                : response.choices().stream()
                        .map(c -> new LlmResponse.LlmChoice(
                                index.getAndIncrement(), WireMessage.toNeutral(c.message()), c.finishReason()))
                        .toList();
        final Usage usage =
                response.usage() == null ? Usage.EMPTY : response.usage().toNeutral();
        return new LlmResponse(response.id(), response.model(), null, choices, usage, null);
    }

    private List<LlmStreamEvent> mapEvent(final SseParser.Event event) {
        final var data = event.data();
        if (data == null || "[DONE]".equals(data)) {
            return List.of();
        }
        final ChatChunk chunk;
        try {
            chunk = mapper.fromString(ChatChunk.class, data);
        } catch (final RuntimeException e) {
            warnMapEventFailure("completions", data, e);
            return List.of();
        }
        final var out = new ArrayList<LlmStreamEvent>();
        final var choices = chunk.choices();
        final var finishPresent = choices != null && choices.stream().anyMatch(c -> c.finishReason() != null);
        if (chunk.usage() != null
                && (finishPresent
                        || choices == null
                        || choices.stream().allMatch(OpenAICompletionsDialect::isUsageOnlyChoice))) {
            // Gateways can retain placeholder choices in their final usage-only chunk.
            // Ignore usage on intermediate content deltas to avoid spurious terminal events.
            out.add(new LlmStreamEvent.End(null, chunk.usage().toNeutral()));
        }
        if (choices != null) {
            for (final var choice : chunk.choices()) {
                final var delta = choice.delta();
                if (delta != null) {
                    final var reasoning =
                            delta.reasoningContent() != null ? delta.reasoningContent() : delta.reasoning();
                    if (reasoning != null && !reasoning.isEmpty()) {
                        out.add(new LlmStreamEvent.ReasoningDelta(reasoning));
                    }
                    if (delta.content() instanceof String s && !s.isEmpty()) {
                        out.add(new LlmStreamEvent.TextDelta(s));
                    }
                    if (delta.toolCalls() != null) {
                        for (final var call : delta.toolCalls()) {
                            final var fn = call.function();
                            out.add(new LlmStreamEvent.ToolCallDelta(
                                    call.index() == null ? 0 : call.index(),
                                    call.id(),
                                    fn == null ? null : fn.name(),
                                    fn == null ? null : fn.arguments()));
                        }
                    }
                }
                if (choice.finishReason() != null) {
                    out.add(new LlmStreamEvent.End(choice.finishReason(), null));
                }
            }
        }
        return out;
    }

    private static boolean isUsageOnlyChoice(final ChunkChoice choice) {
        final var delta = choice.delta();
        return delta == null
                || (delta.role() == null
                        && (delta.content() == null || "".equals(delta.content()))
                        && (delta.reasoningContent() == null
                                || delta.reasoningContent().isEmpty())
                        && (delta.reasoning() == null || delta.reasoning().isEmpty())
                        && (delta.toolCalls() == null || delta.toolCalls().isEmpty()));
    }
}
