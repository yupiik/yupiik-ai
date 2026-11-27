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

import io.yupiik.ia.agent.core.configuration.ReasoningFormat;
import io.yupiik.ia.agent.core.wiring.SessionAwareLogRecord;
import io.yupiik.llm.client.ContextWindowExceededException;
import io.yupiik.llm.client.DialectNotSupportedException;
import io.yupiik.llm.client.codex.CodexNotAuthenticatedException;
import io.yupiik.llm.client.model.LlmMessage;
import io.yupiik.llm.client.model.LlmModels;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmResponse;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmStreamEvent.End;
import io.yupiik.llm.client.model.LlmStreamEvent.ReasoningDelta;
import io.yupiik.llm.client.model.LlmStreamEvent.ReasoningItem;
import io.yupiik.llm.client.model.LlmStreamEvent.TextDelta;
import io.yupiik.llm.client.model.LlmStreamEvent.ToolCallDelta;
import io.yupiik.llm.client.model.LlmTool;
import io.yupiik.llm.client.model.LlmToolCall;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Neutral model translation and blocking bridge between the core request/response types and the
 * {@code llm-client} library types. Also pumps a streamed publisher into the historical blocking
 * {@code Message} result.
 */
public final class LlmWire {

    private LlmWire() {
        // utility
    }

    private static final Logger LOGGER = Logger.getLogger(LlmWire.class.getName());

    /**
     * Derives the session-scoped cache-affinity identity and injects it as the {@code prompt_cache_key}
     * extension when the provider enables it (the {@code sessionId.enabledKeys} list contains the literal
     * {@code prompt_cache_key}). The value flows into the Responses request body (the {@code @JsonOthers}
     * extensions bag) and is the value the dialect reads back to emit the matching header keys. When the
     * key is absent from {@code enabledKeys} - or the request carries no session id - the extensions are
     * left untouched, so callers never get a prompt cache from an identity-less request.
     *
     * @param request the neutral completion request carrying the transcript session id.
     * @param enabledKeys the provider's resolved {@code capabilities.openai.sessionId.enabledKeys}.
     * @return the wire request, with {@code prompt_cache_key} injected when enabled.
     */
    static LlmRequest toLlmRequest(final ChatCompletionRequest request, final List<String> enabledKeys) {
        return toLlmRequest(request, enabledKeys, false);
    }

    static LlmRequest toLlmRequest(
            final ChatCompletionRequest request, final List<String> enabledKeys, final boolean replayReasoning) {
        final var wire = toLlmRequest(request, replayReasoning);
        if (enabledKeys == null
                || !enabledKeys.contains("prompt_cache_key")
                || request.sessionId() == null
                || request.sessionId().isBlank()) {
            return wire;
        }
        // ChatGPT uses session affinity to route successive rounds to the same prompt cache.
        // Hash the local transcript id: routing needs stability, not the user's session name.
        final var extensions = new LinkedHashMap<>(wire.extensions());
        extensions.putIfAbsent(
                "prompt_cache_key",
                UUID.nameUUIDFromBytes(request.sessionId().getBytes(StandardCharsets.UTF_8))
                        .toString());
        return wire.with(extensions);
    }

    static LlmRequest toLlmRequest(final ChatCompletionRequest request) {
        return toLlmRequest(request, false);
    }

    private static LlmRequest toLlmRequest(final ChatCompletionRequest request, final boolean replayReasoning) {
        final Map<String, Object> extensions = request.others() == null ? Map.of() : stripRetryMode(request.others());
        return new LlmRequest(
                request.model(),
                request.messages() == null
                        ? null
                        : request.messages().stream()
                                .map(message -> toLlmMessage(message, replayReasoning))
                                .toList(),
                request.temperature(),
                request.topP(),
                request.maxTokens(),
                null,
                request.tools() == null
                        ? null
                        : request.tools().stream().map(LlmWire::toLlmTool).toList(),
                request.toolChoice(),
                null,
                request.reasoningEffort(),
                request.stream(),
                // yca opts out of server-side conversation storage (OpenAI `store`): no provider
                // (freeinference included) is asked to retain the chat server-side.
                false,
                extensions);
    }

    /** The {@code others} map without the reserved retry-mode marker key (see {@link ChatClient#RETRY_MODE_OTHERS_KEY}). */
    static Map<String, Object> stripRetryMode(final Map<String, Object> others) {
        if (others == null || others.isEmpty() || !others.containsKey(ChatClient.RETRY_MODE_OTHERS_KEY)) {
            return others == null ? Map.of() : others;
        }
        final var out = new LinkedHashMap<>(others);
        out.remove(ChatClient.RETRY_MODE_OTHERS_KEY);
        return out;
    }

    /** Converts a core neutral {@link Message} to the {@code llm-client} neutral model. Public so the
     * agent loop can reuse the single-source {@link LlmMessage#unmatchedToolCallIds} contiguity matcher
     * while repairing persisted history. */
    public static LlmMessage toLlmMessage(final Message message) {
        return toLlmMessage(message, false);
    }

    private static LlmMessage toLlmMessage(final Message message, final boolean replayReasoning) {
        final var extensions = new LinkedHashMap<>(stripIdentity(message.others()));
        // Raw response aliases must not bypass the destination provider's opt-in.
        extensions.remove("reasoning_content");
        extensions.remove("reasoning");
        if (replayReasoning
                && "assistant".equals(message.role())
                && message.others() != null
                && message.others().get(THINKING_KEY) instanceof String thinking
                && !thinking.isBlank()) {
            extensions.put("reasoning_content", thinking);
        }
        return new LlmMessage(
                message.role(),
                message.content(),
                message.name(),
                message.toolCalls() == null
                        ? null
                        : message.toolCalls().stream()
                                .map(LlmWire::toLlmToolCall)
                                .toList(),
                message.toolCallId(),
                extensions);
    }

    /** The {@code others} map without the reserved UI metadata keys (speaker identity + persisted thinking)
     * that never reach the LLM. */
    static Map<String, Object> stripIdentity(final Map<String, Object> others) {
        if (others == null
                || others.isEmpty()
                || (!others.containsKey(Message.IDENTITY_KEY) && !others.containsKey(THINKING_KEY))) {
            return others == null ? Map.of() : others;
        }
        final var out = new LinkedHashMap<>(others);
        out.remove(Message.IDENTITY_KEY);
        out.remove(THINKING_KEY);
        return out;
    }

    /** Reserved {@code others} key on an assistant message holding persisted reasoning for web replay. */
    static final String THINKING_KEY = "thinking";

    /**
     * Reserved {@code others} key on an assistant message holding the opaque reasoning items to replay as
     * {@code type:reasoning} input items for multi-turn reasoning continuity (see OpenAIResponsesDialect).
     */
    static final String REASONING_ITEMS_KEY = "reasoningItems";

    static LlmTool toLlmTool(final Tool tool) {
        final var fn = tool.function();
        return new LlmTool(fn.name(), fn.description(), fn.parameters(), null);
    }

    static LlmToolCall toLlmToolCall(final ToolCall call) {
        return new LlmToolCall(
                call.id(),
                call.type(),
                call.function() == null
                        ? null
                        : new LlmToolCall.FunctionCall(
                                call.function().name(), call.function().arguments()),
                call.index());
    }

    static ChatResult toChatResult(final LlmResponse response) {
        final var choice = response.first();
        final var message = choice == null ? null : choice.message();
        return new ChatResult(message == null ? Message.user("") : toMessage(message), toUsage(response.usage()));
    }

    static Message toMessage(final LlmMessage message) {
        // Strip inline reasoning (GLM/Qwen whole-line ` think` tags) from assistant output so the
        // non-streaming path persists and resends the same view the streaming path does: reasoning
        // must never leak into the stored history (and thus never re-enter the next request).
        final var content = "assistant".equals(message.role()) && message.content() instanceof final String s
                ? ReasoningStripper.strip(s, ReasoningFormat.thinkTags())
                : message.content();
        final var extensions =
                new LinkedHashMap<String, Object>(message.extensions() == null ? Map.of() : message.extensions());
        final var reasoning = extensions.remove("reasoning_content");
        final var alias = extensions.remove("reasoning");
        if ("assistant".equals(message.role())) {
            final var thinking = reasoning instanceof String s && !s.isBlank() ? reasoning : alias;
            if (thinking instanceof String s && !s.isBlank()) {
                extensions.put(THINKING_KEY, thinking);
            }
        }
        return new Message(
                message.role(),
                content,
                message.name(),
                message.toolCalls() == null
                        ? null
                        : message.toolCalls().stream()
                                .map(LlmWire::fromLlmToolCall)
                                .toList(),
                message.toolCallId(),
                extensions);
    }

    static ToolCall fromLlmToolCall(final LlmToolCall call) {
        return new ToolCall(
                call.id(),
                call.type(),
                call.function() == null
                        ? null
                        : new ToolCall.FunctionCall(
                                call.function().name(), call.function().arguments()),
                call.index());
    }

    static ModelsResponse toModelsResponse(final LlmModels models) {
        return new ModelsResponse(models.data().stream()
                .map(m -> new ModelsResponse.Model(m.id(), m.ownedBy(), m.others()))
                .toList());
    }

    static Usage toUsage(final io.yupiik.llm.client.model.Usage usage) {
        if (usage == null) {
            return Usage.EMPTY;
        }
        return new Usage(
                Math.toIntExact(usage.prompt()),
                Math.toIntExact(usage.completion()),
                Math.toIntExact(usage.total()),
                Math.toIntExact(usage.cacheRead()),
                Math.toIntExact(usage.cacheWrite()),
                Math.toIntExact(usage.reasoning()),
                usage.generationTime(),
                Math.toIntExact(usage.contextInput()));
    }

    /** {@link #pump(Flow.Publisher, Consumer, BooleanSupplier, String)} with no session attribution (global). */
    static Message pump(
            final Flow.Publisher<LlmStreamEvent> publisher,
            final Consumer<LlmStreamEvent> onDelta,
            final BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return pump(publisher, onDelta, cancelled, null);
    }

    static Message pump(
            final Flow.Publisher<LlmStreamEvent> publisher,
            final Consumer<LlmStreamEvent> onDelta,
            final BooleanSupplier cancelled,
            final String sessionId)
            throws IOException, InterruptedException {
        Objects.requireNonNull(cancelled, "cancelled");
        final var done = new CompletableFuture<Message>();
        final var text = new StringBuilder();
        final var toolCalls = new ArrayList<ToolCall>();
        // Streaming dialects emit one delta per fragment. The stable identity of a logical tool call
        // is not uniform across dialects: OpenAI Responses carries the same id (item_id) on every
        // fragment but varies the index, while Anthropic/Completions keep the index stable and only
        // put the id/name on the first fragment. So a fragment belongs with an existing partial when
        // it shares either its id or its index; index -gt id links are resolved lazily on arrival.
        // Reactive publishers must signal sequentially per the Flow.Subscriber contract, but the
        // coalescing state below is guarded by a monitor to stay correct even if the publisher is
        // non-conformant and delivers fragments concurrently.
        final var byId = new HashMap<String, PartialToolCall>();
        final var byIndex = new HashMap<Integer, PartialToolCall>();
        // lookup maps carry no ordering, so the emission order of the logical calls is tracked
        // separately: partials are appended once, when they are created
        final var creationOrder = new ArrayList<PartialToolCall>();
        final var mergeLock = new Object();
        // a transport failure after fragments were delivered (connection cut mid-chunk) is a
        // MidStreamFailureException so the caller can retry the whole request, while a failure
        // before any fragment is left untouched for the resilience layer
        final var delivered = new boolean[] {false};
        // the subscription is captured so the bounded-wait loop below can cancel it on a no-frame
        // interrupt (an abandon path that does not go through onNext)
        final var subscriptionHolder = new AtomicReference<Flow.Subscription>();
        publisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(final Flow.Subscription s) {
                this.subscription = s;
                subscriptionHolder.set(s);
                if (done.isDone() || cancelled.getAsBoolean()) {
                    s.cancel();
                    done.complete(new Message("assistant", "", null, null, null, null));
                } else {
                    s.request(Long.MAX_VALUE);
                }
            }

            @Override
            public void onNext(final LlmStreamEvent event) {
                synchronized (mergeLock) {
                    if (cancelled.getAsBoolean()) {
                        subscription.cancel();
                        done.complete(new Message("assistant", visibleText(text), null, toNull(toolCalls), null, null));
                        return;
                    }
                    if (event instanceof TextDelta(String t)) {
                        delivered[0] = true;
                        text.append(t == null ? "" : t);
                        onDelta.accept(event);
                    } else if (event instanceof ReasoningDelta(final String text) && text != null) {
                        delivered[0] = true;
                        onDelta.accept(event);
                    } else if (event instanceof ReasoningItem) {
                        // a reasoning item carries the opaque encrypted_content blob for multi-turn replay;
                        // it produces no visible text (the summary deltas do) so it does not mark the stream
                        // as delivering content, but it must still reach the loop to be persisted
                        onDelta.accept(event);
                    } else if (event instanceof ToolCallDelta(int index, String id, String name, String args)) {
                        delivered[0] = true;
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
                        onDelta.accept(event);
                    } else if (event instanceof End) {
                        onDelta.accept(event);
                    }
                }
            }

            @Override
            public void onError(final Throwable throwable) {
                synchronized (mergeLock) {
                    if (delivered[0]) {
                        // the wrapped exception's message only carries the cause's toString(), which
                        // is uninformative for a bare NullPointerException; log the full throwable
                        // (with its stack trace) here so the real failing frame survives. The
                        // mid-stream category includes both transport drops and runtime faults that
                        // escaped the caller's onDelta consumer (reactive publishers forward an
                        // onNext exception to this onError).
                        LOGGER.log(SessionAwareLogRecord.of(
                                LOGGER,
                                sessionId,
                                Level.WARNING,
                                "Streaming failed after partial content was delivered",
                                throwable));
                        done.completeExceptionally(new MidStreamFailureException(
                                "stream interrupted after " + text.length() + " chars of partial content: "
                                        + throwable.getClass().getSimpleName()
                                        + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage()),
                                throwable,
                                visibleText(text),
                                creationOrder.isEmpty()
                                        ? null
                                        : creationOrder.stream()
                                                .map(PartialToolCall::toToolCall)
                                                .toList()));
                    } else {
                        done.completeExceptionally(throwable);
                    }
                }
            }

            @Override
            public void onComplete() {
                synchronized (mergeLock) {
                    toolCalls.addAll(creationOrder.stream()
                            .map(PartialToolCall::toToolCall)
                            .toList());
                    done.complete(new Message("assistant", visibleText(text), null, toNull(toolCalls), null, null));
                }
            }
        });
        try {
            // bounded wait: a cancel pressed while the provider streams must surface in ~100ms
            // even when the publisher delivers no further frame (big chunks, silent generation);
            // the onNext handler above already completes done when it sees the flag, this loop
            // only covers the no-frame case
            while (!done.isDone()) {
                try {
                    done.get(100, TimeUnit.MILLISECONDS);
                } catch (final TimeoutException e) {
                    if (cancelled.getAsBoolean()) {
                        // abandon the stream explicitly: completing done() alone would leave the
                        // subscription live, so the wrapper's permit release (which hooks cancel())
                        // never fires and the concurrency slot leaks. Cancel first, like onNext does.
                        final var sub = subscriptionHolder.get();
                        if (sub != null) {
                            sub.cancel();
                        }
                        done.complete(new Message("assistant", visibleText(text), null, toNull(toolCalls), null, null));
                    }
                    // keep waiting for the next frame (or the completion above)
                }
            }
            return done.get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (final ExecutionException e) {
            final var cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
            if (cause instanceof CodexNotAuthenticatedException codex) {
                // rethrow as-is so TurnRunner can trigger the interactive login flow
                throw codex;
            }
            throw translate(cause);
        } finally {
            if (done.cancel(false)) {
                final var subscription = subscriptionHolder.get();
                if (subscription != null) {
                    subscription.cancel();
                }
            }
        }
    }

    /**
     * Strips inline {@code  thinking...} blocks from an assistant completion before it enters the
     * conversation (and from then on every request). Some OpenAI-compatible gateways (GLM-style,
     * Qwen templates without a dedicated reasoning channel) stream the reasoning inside {@code content};
     * keeping it would persist and replay the thoughts verbatim, making the model reconstruct them on
     * every turn. The {@code ReasoningDelta} events (when the channel is dedicated) are untouched: the
     * UI still renders the thinking live, only the stored/resent message loses it.
     */
    static String visibleText(final StringBuilder text) {
        return ReasoningStripper.strip(text == null ? "" : text.toString(), ReasoningFormat.thinkTags());
    }

    private static List<ToolCall> toNull(final List<ToolCall> calls) {
        return calls.isEmpty() ? null : calls;
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
            // a fragment of a known call arriving under an index slot taken over by a newer call:
            // follow the id and re-point the slot, never concatenating the other call's arguments
            byIndex.put(index, fromId);
            return fromId;
        }
        if (fromId == null && fromIndex != null && fromIndex.id != null && id != null && !fromIndex.id.equals(id)) {
            // some OpenAI-compatible servers (llama.cpp style) reuse the fragment index across
            // parallel tool calls: the id is authoritative, so keep the calls separate and take
            // the index slot over - the id-less continuation fragments of the newest call then
            // attach to it instead of bleeding into the previous call's arguments
            final var created = new PartialToolCall(index);
            created.id = id;
            creationOrder.add(created);
            byId.put(id, created);
            byIndex.put(index, created);
            return created;
        }
        if (fromId != null) {
            if (fromIndex != null && fromIndex != fromId) {
                // same logical call surfaced under both identities: merge the grouping so later
                // fragments (which may carry only the index or only the id) find the same partial
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

    /**
     * Mutable accumulator for a single streaming tool call: fragments coalesced by their shared id or
     * index carry the first non-null id and name and the concatenated argument JSON.
     */
    private static final class PartialToolCall {

        private final int index;
        private String id;
        private final StringBuilder arguments = new StringBuilder();
        private String name;

        private PartialToolCall(final int index) {
            this.index = index;
        }

        private String arguments() {
            return arguments.toString();
        }

        private ToolCall toToolCall() {
            return new ToolCall(id, "function", new ToolCall.FunctionCall(name, arguments.toString()), index);
        }
    }

    static <A> A join(final CompletionStage<A> stage) throws IOException, InterruptedException {
        try {
            return stage.toCompletableFuture().join();
        } catch (final CompletionException e) {
            final var cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
            if (cause instanceof CodexNotAuthenticatedException codex) {
                // rethrow as-is so TurnRunner can trigger the interactive login flow
                throw codex;
            }
            throw translate(cause);
        }
    }

    /** Wait without hiding a late result by cancelling a composed transport stage. */
    static <A> A join(final CompletionStage<A> stage, final BooleanSupplier cancelled, final Consumer<A> discard)
            throws IOException, InterruptedException {
        final var future = stage.toCompletableFuture();
        var handedOff = false;
        try {
            while (true) {
                if (cancelled.getAsBoolean()) {
                    throw new IOException("Cancelled");
                }
                try {
                    final var result = future.get(100, TimeUnit.MILLISECONDS);
                    if (cancelled.getAsBoolean()) {
                        throw new IOException("Cancelled");
                    }
                    handedOff = true;
                    return result;
                } catch (final TimeoutException e) {
                    // Poll the flag even while headers, transport setup or a permit are pending.
                } catch (final ExecutionException e) {
                    // Keep the same exception translation as the historical blocking bridge.
                    return join(stage);
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (!handedOff) {
                // Do not cancel the composed future: upstream can still succeed, and dropping its
                // publisher would leak the body subscription and the resilience concurrency permit.
                stage.thenAccept(discard);
            }
        }
    }

    static void discard(final Flow.Publisher<LlmStreamEvent> publisher) {
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(final Flow.Subscription subscription) {
                subscription.cancel();
            }

            @Override
            public void onNext(final LlmStreamEvent event) {}

            @Override
            public void onError(final Throwable throwable) {}

            @Override
            public void onComplete() {}
        });
    }

    /**
     * Maps a library failure to the blocking API: {@link io.yupiik.llm.client.DialectNotSupportedException}
     * and {@link io.yupiik.llm.client.ContextWindowExceededException} flow through as-is (the router
     * fallback and the agent loop rely on their exact type) and other {@link IOException}s keep their
     * message (status prefix parsing depends on it).
     */
    private static IOException translate(final Throwable error) {
        if (error instanceof DialectNotSupportedException || error instanceof ContextWindowExceededException) {
            return (IOException) error;
        }
        if (error instanceof IOException io) {
            return io;
        }
        return new IOException(error.getMessage(), error);
    }
}
