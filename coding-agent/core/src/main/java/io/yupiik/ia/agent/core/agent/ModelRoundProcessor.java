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
package io.yupiik.ia.agent.core.agent;

import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.PauseEvent;
import io.yupiik.ia.agent.core.llm.ToolCallPairingRepair;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import io.yupiik.llm.client.LlmHttpFailureException;
import io.yupiik.llm.client.model.LlmStreamEvent;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Owns one model round's stream retry policy; the turn supplies event and counter updates. */
public final class ModelRoundProcessor {
    public interface Observer {
        void begin();

        void resetText();

        void retrying(String message, Throwable cause);

        void malformedToolArguments();

        void toolCallPairingRejected();

        void paused(long millis);
    }

    private final ChatClient chatClient;
    private final CodingAgentConfiguration configuration;
    private final PrecisionDurationMarkerProvider clock;

    public ModelRoundProcessor(
            final ChatClient chatClient,
            final CodingAgentConfiguration configuration,
            final PrecisionDurationMarkerProvider clock) {
        this.chatClient = chatClient;
        this.configuration = configuration;
        this.clock = clock;
    }

    public Message stream(
            final ChatCompletionRequest request,
            final Usage[] usage,
            final BooleanSupplier cancelled,
            final Consumer<PauseEvent> pauseListener,
            final Consumer<LlmStreamEvent> onDelta,
            final Observer observer)
            throws IOException, InterruptedException {
        final var maxAttempts = 1 + Math.max(0, configuration.llm().streamRetriesMaxAttempts());
        IOException last = null;
        observer.begin();
        for (var attempt = 1; attempt <= maxAttempts; attempt++) {
            if (cancelled.getAsBoolean()) {
                throw new IOException("Cancelled");
            }
            try {
                return chatClient.stream(request, pauseListener, onDelta, cancelled);
            } catch (final IOException e) {
                // Azure / litellm strict validators reject an assistant message whose tool_calls are not
                // answered by a tool message carrying each tool_call_id ("An assistant message with
                // 'tool_calls' must be followed by tool messages responding to each 'tool_call_id'. ...").
                // The offending history is unchanged between attempts, so re-sending it can never succeed:
                // retrying it here is pure waste (same doomed retry, with a backoff sleep, up to the whole
                // budget). Instead flag the caller (AgentLoop owns the history and repairs it) and rethrow
                // immediately so the repair runs on the first attempt instead of after a retry storm.
                if (e instanceof LlmHttpFailureException failure && ToolCallPairingRepair.isPairingRejection(failure)) {
                    observer.toolCallPairingRejected();
                    throw e;
                }
                if (e instanceof LlmHttpFailureException failure && isToolCallArgumentsRejected(failure)) {
                    observer.malformedToolArguments();
                }
                last = e;
                if (attempt >= maxAttempts) {
                    break;
                }
                observer.resetText();
                observer.retrying(
                        "Stream interrupted (" + causeSummary(e) + "); retrying (" + attempt + '/' + maxAttempts + ").",
                        e);
                final var backoffMs = streamRetryBackoffMs(attempt);
                observer.paused(backoffMs);
                if (!interruptibleSleep(backoffMs, cancelled)) {
                    throw new IOException("Cancelled");
                }
            }
        }
        throw last;
    }

    private long streamRetryBackoffMs(final int attempt) {
        final var base = Math.max(0, configuration.llm().retriesBaseDelayMs());
        final var cap = Math.max(base, configuration.llm().retryAfterMaxWaitMs());
        var delay = base;
        for (var i = 1; i < attempt && delay < cap; i++) {
            delay *= 2;
        }
        return Math.min(delay, cap);
    }

    private boolean interruptibleSleep(final long millis, final BooleanSupplier cancelled) {
        final var deadline = clock.now() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (true) {
            if (cancelled.getAsBoolean()) {
                return false;
            }
            final var remaining = deadline - clock.now();
            if (remaining <= 0) {
                return true;
            }
            try {
                Thread.sleep(Math.clamp(remaining / 1_000_000, 1, 100));
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static String causeSummary(final Throwable e) {
        final var cause = e.getCause();
        final var message = cause == null
                        || cause.getMessage() == null
                        || cause.getMessage().isBlank()
                ? e.getMessage()
                : cause.getMessage();
        return message == null ? "unknown transport failure" : message;
    }

    private static boolean isToolCallArgumentsRejected(final LlmHttpFailureException e) {
        final var body = e.body();
        return body != null && body.contains("parse_error.101");
    }

    /**
     * Tells whether an LLM HTTP failure is the strict-validator rejection of an assistant
     * {@code tool_calls} block that has no matching {@code tool}-role result, on either wire signature
     * (Azure/litellm "An assistant message with 'tool_calls' must be followed by tool messages responding
     * to each 'tool_call_id'...", or OpenAI Responses "No tool output found for function call ..."). The
     * signature check is owned by {@link ToolCallPairingRepair#isPairingRejection} (single source of
     * truth); this is a delegating convenience for existing callers of the status-code-typed failure. The
     * offending history is unchanged between attempts, so a matching failure must surface on the first
     * attempt so the caller repairs before it can be retried.
     *
     * @param e the http failure reported by the provider.
     * @return whether the failure body is a tool-call pairing rejection that the interrupted-tool repair can fix.
     */
    public static boolean isToolCallPairingRejected(final LlmHttpFailureException e) {
        return ToolCallPairingRepair.isPairingRejection(e);
    }
}
