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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.PauseEvent;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import io.yupiik.llm.client.LlmHttpFailureException;
import io.yupiik.llm.client.model.LlmStreamEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ModelRoundProcessor}'s stream retry policy, in particular that a deterministic
 * tool-pairing 400 (Azure/litellm "tool_calls must be followed by tool messages") short-circuits the generic
 * retry budget immediately instead of blindly re-sending the same broken history up to the max attempts.
 */
class ModelRoundProcessorTest {

    @Nested
    @FusionSupport(modules = CancellationModule.class)
    class Cancellation {
        @Test
        void flagCancellationDuringBackoffStopsWithoutAnotherAttempt(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var cancelled = new AtomicBoolean();
            final var sleeping = new CountDownLatch(1);
            final var clientCalls = new AtomicInteger();
            final var chatClient = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancellation)
                        throws IOException {
                    clientCalls.incrementAndGet();
                    throw new IOException("transport failure");
                }
            };
            // The second clock read is inside the sleep loop, after the initial flag check.
            final var reads = new AtomicInteger();
            final var clock = new PrecisionDurationMarkerProvider() {
                @Override
                public long now() {
                    if (reads.incrementAndGet() >= 2) {
                        sleeping.countDown();
                    }
                    return System.nanoTime();
                }
            };
            final var observer = new RecordingObserver();
            final var processor = new ModelRoundProcessor(chatClient, configuration, clock);
            final var result = CompletableFuture.supplyAsync(() -> assertThrows(
                    IOException.class,
                    () -> processor.stream(
                            new ChatCompletionRequest("m", List.of(), null, null, null, null, "auto", null, null, null),
                            new Usage[] {Usage.EMPTY},
                            cancelled::get,
                            pause -> {},
                            delta -> {},
                            observer)));
            try {
                assertTrue(sleeping.await(5, TimeUnit.SECONDS));
                assertFalse(result.isDone());
                cancelled.set(true);
                assertEquals("Cancelled", result.get(2, TimeUnit.SECONDS).getMessage());
                assertEquals(1, clientCalls.get());
                assertEquals(30000, observer.pausedMs);
            } finally {
                cancelled.set(true);
            }
        }
    }

    private static final String PAIRING_BODY =
            "{\"detail\":\"litellm.BadRequestError: AzureException BadRequestError - An assistant message with "
                    + "'tool_calls' must be followed by tool messages responding to each 'tool_call_id'. The "
                    + "following tool_call_ids did not have response messages: call_1.\"}";

    private static final String OTHER_400_BODY = "{\"error\":{\"message\":\"invalid api key\"}}";

    /** Captures every observer callback so the test can assert exactly what the processor fired. */
    private static final class RecordingObserver implements ModelRoundProcessor.Observer {
        final List<String> calls = new ArrayList<>();
        long pausedMs;
        Throwable retryCause;

        @Override
        public void begin() {
            calls.add("begin");
        }

        @Override
        public void resetText() {
            calls.add("resetText");
        }

        @Override
        public void retrying(final String message, final Throwable cause) {
            calls.add("retrying");
            retryCause = cause;
        }

        @Override
        public void malformedToolArguments() {
            calls.add("malformedToolArguments");
        }

        @Override
        public void toolCallPairingRejected() {
            calls.add("toolCallPairingRejected");
        }

        @Override
        public void paused(final long millis) {
            calls.add("paused");
            pausedMs += millis;
        }
    }

    @Nested
    @FusionSupport(modules = RetryModule.class)
    class Retries {
        @Test
        void pairing400ShortCircuitsOnFirstAttemptAndFiresRejectedCallback(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var clientCalls = new AtomicInteger();
            final var chatClient = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest r,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled)
                        throws IOException {
                    clientCalls.incrementAndGet();
                    throw new LlmHttpFailureException(400, PAIRING_BODY, null);
                }
            };
            final var processor =
                    new ModelRoundProcessor(chatClient, configuration, new PrecisionDurationMarkerProvider());
            final var observer = new RecordingObserver();

            assertThrows(
                    LlmHttpFailureException.class,
                    () -> processor.stream(
                            new ChatCompletionRequest("m", List.of(), null, null, null, null, "auto", null, null, null),
                            new Usage[] {Usage.EMPTY},
                            () -> false,
                            pause -> {},
                            delta -> {},
                            observer));

            // the pairing 400 must surface as-is so the caller (AgentLoop) can repair the history first.
            assertEquals(1, clientCalls.get(), "the broken history must not be re-sent (no retry storm)");
            assertTrue(
                    observer.calls.contains("toolCallPairingRejected"), "observer must be told about the pairing 400");
            assertTrue(
                    observer.calls.contains("begin") && observer.calls.contains("toolCallPairingRejected"),
                    "begin + rejection fired: " + observer.calls);
            assertTrue(
                    !observer.calls.contains("retrying") && !observer.calls.contains("paused"),
                    "no generic retry/backoff may run for a deterministic pairing 400: " + observer.calls);
            assertEquals(0, observer.pausedMs, "no backoff sleep may run");
        }

        @Test
        void responsesMissingToolOutput400ShortCircuitsOnFirstAttempt(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            // OpenAI Responses backends reject an unanswered function call with a distinct signature:
            // "No tool output found for function call <id>". Like the Azure/litellm wording, it is a
            // deterministic broken-history rejection, so it must short-circuit the generic retry budget and
            // fire the pairing-rejected callback so the caller repairs the history before retrying once.
            final var clientCalls = new AtomicInteger();
            final var chatClient = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest r,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled)
                        throws IOException {
                    clientCalls.incrementAndGet();
                    throw new LlmHttpFailureException(
                            400, "{\"error\":{\"message\":\"No tool output found for function call call_1.\"}}", null);
                }
            };
            final var processor =
                    new ModelRoundProcessor(chatClient, configuration, new PrecisionDurationMarkerProvider());
            final var observer = new RecordingObserver();

            assertThrows(
                    LlmHttpFailureException.class,
                    () -> processor.stream(
                            new ChatCompletionRequest("m", List.of(), null, null, null, null, "auto", null, null, null),
                            new Usage[] {Usage.EMPTY},
                            () -> false,
                            pause -> {},
                            delta -> {},
                            observer));

            assertEquals(1, clientCalls.get(), "the broken history must not be re-sent (no retry storm)");
            assertTrue(
                    observer.calls.contains("toolCallPairingRejected"), "observer must be told about the pairing 400");
            assertTrue(
                    !observer.calls.contains("retrying") && !observer.calls.contains("paused"),
                    "no generic retry/backoff may run for a deterministic pairing 400: " + observer.calls);
            assertEquals(0, observer.pausedMs, "no backoff sleep may run");
        }

        @Test
        void nonPairing400StillRetriesPerBudget(@Fusion final CodingAgentConfiguration configuration) {
            final var clientCalls = new AtomicInteger();
            final var chatClient = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest r,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled)
                        throws IOException {
                    clientCalls.incrementAndGet();
                    throw new LlmHttpFailureException(400, OTHER_400_BODY, null);
                }
            };
            // streamRetriesMaxAttempts=2 -> maxAttempts=3: a non-pairing 400 is a generic failure and must be
            // retried the full budget (nothing here can know it is deterministic, and status 400 is not retryable
            // so the resilience layer is not involved; the round processor still applies its own budget).
            final var processor =
                    new ModelRoundProcessor(chatClient, configuration, new PrecisionDurationMarkerProvider());
            final var observer = new RecordingObserver();

            assertThrows(
                    LlmHttpFailureException.class,
                    () -> processor.stream(
                            new ChatCompletionRequest("m", List.of(), null, null, null, null, "auto", null, null, null),
                            new Usage[] {Usage.EMPTY},
                            () -> false,
                            pause -> {},
                            delta -> {},
                            observer));

            assertEquals(3, clientCalls.get(), "a generic 400 keeps the normal retry budget");
            assertFalse(
                    observer.calls.contains("toolCallPairingRejected"),
                    "a non-pairing 400 must NOT fire the pairing callback: " + observer.calls);
            assertEquals(2, observer.calls.stream().filter("retrying"::equals).count(), "two retries for three calls");
            assertEquals(
                    2, observer.calls.stream().filter("paused"::equals).count(), "two backoffs for the two retries");
        }
    }

    public static class CancellationModule extends ConfigurationTestModule {
        public CancellationModule() {
            super(Map.of(
                    "yca.llm.streamRetriesMaxAttempts", "2",
                    "yca.llm.retriesBaseDelayMs", "30000",
                    "yca.llm.retryAfterMaxWaitMs", "30000"));
        }
    }

    public static class RetryModule extends ConfigurationTestModule {
        public RetryModule() {
            super(Map.of(
                    "yca.llm.streamRetriesMaxAttempts", "2",
                    "yca.llm.retriesBaseDelayMs", "1",
                    "yca.llm.retryAfterMaxWaitMs", "10"));
        }
    }
}
