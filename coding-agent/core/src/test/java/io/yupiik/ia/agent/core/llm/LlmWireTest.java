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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.wiring.SessionAwareLogRecord;
import io.yupiik.llm.client.model.LlmMessage;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmStreamEvent.TextDelta;
import io.yupiik.llm.client.model.Usage;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LlmWireTest {
    private static final Pattern DANGLING_CAUSE_SEPARATOR = Pattern.compile(".*NullPointerException:\\s.*");

    @Test
    void usageConversionPreservesFullInputWithoutChangingRawCounters() {
        final var usage = LlmWire.toUsage(new Usage(5L, 2L, 7L, 100L, 20L, null, null, 125L));
        assertEquals(5, usage.promptTokens());
        assertEquals(125, usage.contextInputTokens());
        assertEquals(100, usage.cacheReadTokens());
        assertEquals(20, usage.cacheWriteTokens());
        assertEquals(7, usage.totalTokens());
    }

    @Test
    void reasoningReplayIsOptInAndKeepsToolPairingAndVisibleContent() {
        final var assistant = new Message(
                "assistant",
                "Checking",
                null,
                List.of(new ToolCall("call-1", "function", new ToolCall.FunctionCall("read", "{}"), 0)),
                null,
                Map.of("thinking", "Inspect the file", Message.IDENTITY_KEY, Map.of("provider", "qwen")));
        final var request = new ChatCompletionRequest(
                "qwen",
                List.of(assistant, new Message("tool", "file contents", null, null, "call-1", null)),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of());
        assertTrue(
                LlmWire.toLlmRequest(request).messages().getFirst().extensions().isEmpty());
        final var replayed = LlmWire.toLlmRequest(request, List.of(), true).messages();
        assertEquals(
                Map.of("reasoning_content", "Inspect the file"),
                replayed.getFirst().extensions());
        assertEquals("Checking", replayed.getFirst().content());
        assertEquals("call-1", replayed.getFirst().toolCalls().getFirst().id());
        assertEquals("call-1", replayed.getLast().toolCallId());
        assertTrue(LlmWire.toLlmRequest(request, List.of(), false)
                .messages()
                .getFirst()
                .extensions()
                .isEmpty());
        assertEquals("Inspect the file", assistant.others().get("thinking"));
    }

    @Test
    void bufferedReasoningBecomesInternalThinkingNotAnUnconditionalWireExtension() {
        for (final var alias : List.of("reasoning_content", "reasoning")) {
            final var message = LlmWire.toMessage(
                    new LlmMessage("assistant", "answer", null, null, null, Map.of(alias, "thought")));
            assertEquals(Map.of("thinking", "thought"), message.others());
            assertTrue(LlmWire.toLlmMessage(message).extensions().isEmpty());
        }
        assertTrue(LlmWire.toLlmMessage(new Message(
                        "assistant",
                        "answer",
                        null,
                        null,
                        null,
                        Map.of("reasoning_content", "raw", "reasoning", "raw")))
                .extensions()
                .isEmpty());
    }

    @Test
    void publisherWaitPollsCancellationAndDiscardsLatePublisher() throws Exception {
        final var transport = new CompletableFuture<Flow.Publisher<LlmStreamEvent>>();
        // Model the dependent stage returned by the dialect/resilience chain.
        final var stage = transport.thenApply(publisher -> publisher);
        final var cancelled = new AtomicBoolean();
        final var waiting = new CountDownLatch(1);
        final var result = CompletableFuture.supplyAsync(() -> assertThrows(
                IOException.class,
                () -> LlmWire.join(
                        stage,
                        () -> {
                            waiting.countDown();
                            return cancelled.get();
                        },
                        LlmWire::discard)));
        try {
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            assertFalse(result.isDone());
            cancelled.set(true);
            assertEquals("Cancelled", result.get(2, TimeUnit.SECONDS).getMessage());
            assertFalse(stage.isCancelled(), "late publishers must remain observable");
            final var cancellations = new AtomicInteger();
            final var requests = new AtomicInteger();
            transport.complete(subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(final long n) {
                    requests.incrementAndGet();
                }

                @Override
                public void cancel() {
                    cancellations.incrementAndGet();
                }
            }));
            assertEquals(1, cancellations.get(), "release the late transport and concurrency permit exactly once");
            assertEquals(0, requests.get(), "never consume an abandoned response");
        } finally {
            cancelled.set(true);
            transport.completeExceptionally(new IOException("test cleanup"));
        }
    }

    @Test
    void publisherWaitPreservesSuccessAndFailure() throws Exception {
        final var discarded = new AtomicBoolean();
        assertEquals(
                "ready",
                LlmWire.join(CompletableFuture.completedFuture("ready"), () -> false, v -> discarded.set(true)));
        final var failure = new IOException("transport failure");
        assertEquals(
                failure,
                assertThrows(
                        IOException.class,
                        () -> LlmWire.join(
                                CompletableFuture.failedFuture(failure), () -> false, v -> discarded.set(true))));
        assertFalse(discarded.get());
    }

    @Test
    void interruptedPumpCancelsLateSubscription() throws Exception {
        final var subscribed = new CompletableFuture<Flow.Subscriber<? super LlmStreamEvent>>();
        final var result = new CompletableFuture<Throwable>();
        final var thread = Thread.ofVirtual().start(() -> {
            try {
                LlmWire.pump(subscribed::complete, event -> {}, () -> false);
                result.complete(new AssertionError("pump returned without interruption"));
            } catch (final Throwable e) {
                result.complete(e);
            }
        });
        try {
            final var subscriber = subscribed.get(5, TimeUnit.SECONDS);
            thread.interrupt();
            assertTrue(result.get(2, TimeUnit.SECONDS) instanceof InterruptedException);
            final var cancellations = new AtomicInteger();
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(final long n) {
                    throw new AssertionError("abandoned stream must not request data");
                }

                @Override
                public void cancel() {
                    cancellations.incrementAndGet();
                }
            });
            assertEquals(1, cancellations.get());
        } finally {
            thread.interrupt();
            thread.join(2000);
        }
    }

    @Test
    void interruptedPublisherWaitDiscardsLatePublisherAndRestoresInterrupt() throws Exception {
        final var stage = new CompletableFuture<Flow.Publisher<LlmStreamEvent>>();
        final var waiting = new CountDownLatch(1);
        final var interrupted = new AtomicBoolean();
        final var failure = new CompletableFuture<Throwable>();
        final var thread = Thread.ofVirtual().start(() -> {
            try {
                LlmWire.join(
                        stage,
                        () -> {
                            waiting.countDown();
                            return false;
                        },
                        LlmWire::discard);
                failure.complete(new AssertionError("wait returned without interruption"));
            } catch (final InterruptedException e) {
                interrupted.set(Thread.currentThread().isInterrupted());
                failure.complete(e);
            } catch (final Throwable e) {
                failure.complete(e);
            }
        });
        try {
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            thread.interrupt();
            assertTrue(failure.get(2, TimeUnit.SECONDS) instanceof InterruptedException);
            assertTrue(interrupted.get());
            final var released = new AtomicBoolean();
            stage.complete(subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(final long n) {}

                @Override
                public void cancel() {
                    released.set(true);
                }
            }));
            assertTrue(released.get());
        } finally {
            thread.interrupt();
            stage.completeExceptionally(new IOException("test cleanup"));
            thread.join(2000);
        }
    }

    @Test
    void codexCacheAffinityIsStableAndSessionScoped() {
        final var request = new ChatCompletionRequest(
                "model", List.of(Message.user("hello")), null, null, true, null, null, null, null, Map.of());
        final var enabledKeys = List.of("session_id", "prompt_cache_key");
        final var first = LlmWire.toLlmRequest(request.withSessionId("first"), enabledKeys);
        final var repeated = LlmWire.toLlmRequest(request.withSessionId("first"), enabledKeys);
        final var other = LlmWire.toLlmRequest(request.withSessionId("other"), enabledKeys);
        Assertions.assertEquals(first.extensions(), repeated.extensions());
        Assertions.assertNotEquals(first.extensions(), other.extensions());
        assertNotEquals("first", first.extensions().get("prompt_cache_key"));
        Assertions.assertEquals(
                "prompt_cache_key", first.extensions().keySet().iterator().next());
        // no session id, or a key list without prompt_cache_key, leaves the extensions untouched
        assertTrue(LlmWire.toLlmRequest(request, enabledKeys).extensions().isEmpty());
        assertTrue(LlmWire.toLlmRequest(request.withSessionId("first"), List.of("session_id"))
                .extensions()
                .isEmpty());
        assertTrue(LlmWire.toLlmRequest(request.withSessionId("first"), List.of())
                .extensions()
                .isEmpty());
        assertTrue(LlmWire.toLlmRequest(request.withSessionId("first"), null)
                .extensions()
                .isEmpty());
    }

    @Test
    void wireNeverContainsTheSpeakerIdentity() {
        final var m = Message.withIdentity(Message.assistant("a", List.of()), "p", "m", null);
        final var wire = LlmWire.toLlmMessage(m);
        assertFalse(wire.extensions().containsKey(Message.IDENTITY_KEY), "identity must be stripped on the wire");
        // unrelated others keys are preserved
        final var withOther = new Message(
                "user", "x", null, null, null, Map.of("is_error", true, Message.IDENTITY_KEY, Map.of("provider", "p")));
        final var otherWire = LlmWire.toLlmMessage(withOther);
        assertFalse(otherWire.extensions().containsKey(Message.IDENTITY_KEY));
        assertFalse(otherWire.extensions().isEmpty());
    }

    /**
     * The O2 retry-mode marker (a reserved {@code others} key set by
     * {@code ChatCompletionRequest#withRetryMode}) is a transport-only flag: it must be carried on
     * the request, then stripped before the payload reaches the provider so it can never leak into
     * the wire {@code extensions}.
     */
    @Test
    void wireNeverContainsTheRetryModeMarker() {
        final var request = new ChatCompletionRequest(
                "model", List.of(Message.user("hello")), null, null, null, null, null, null, null, Map.of("user", 1));
        final var cooperative = request.withRetryMode(RetryMode.COOPERATIVE);
        // the marker rides the request until the wire boundary
        Assertions.assertEquals(
                RetryMode.COOPERATIVE,
                cooperative.others().get(ChatClient.RETRY_MODE_OTHERS_KEY),
                "withRetryMode must record the mode on the request");
        // ... and is stripped on the wire, co-existing keys preserved
        final var wire = LlmWire.toLlmRequest(cooperative, null);
        assertFalse(wire.extensions().containsKey(ChatClient.RETRY_MODE_OTHERS_KEY), "marker must not reach the wire");
        Assertions.assertEquals(1, wire.extensions().get("user"), "unrelated keys are preserved");
        // absent marker (delegated default) leaves the extensions untouched
        assertFalse(LlmWire.toLlmRequest(request, null).extensions().containsKey(ChatClient.RETRY_MODE_OTHERS_KEY));
    }

    /**
     * P4: inline {@code  thinking} blocks (GLM-style / Qwen templates without a dedicated
     * reasoning channel) must be stripped before the assistant message enters the conversation,
     * otherwise the thoughts are persisted and replayed verbatim on the next request, making the
     * model reconstruct them. The visible text around the blocks is preserved.
     */
    @Test
    void visibleTextStripsInlineThinkingBlocks() {
        final var raw = new StringBuilder(
                "let me check the pom\n think the build fails because of the plugin\n think\nso I edit it");
        final var stripped = LlmWire.visibleText(raw);
        Assertions.assertFalse(stripped.contains("think"), "inline thinking must be removed: " + stripped);
        Assertions.assertTrue(stripped.contains("let me check the pom"), stripped);
        Assertions.assertTrue(stripped.contains("so I edit it"), stripped);
    }

    /**
     * Item 3: the non-streaming {@code toChatResult} path must apply the same reasoning strip as the
     * streaming one, so a stored / resumed assistant message never carries inline GLM/Qwen reasoning.
     */
    @Test
    void toMessageStripsInlineReasoningOnTheNonStreamPath() {
        final var wire =
                new LlmMessage("assistant", "head\n think reasoning here\n response tail", null, null, null, Map.of());
        final var message = LlmWire.toMessage(wire);
        Assertions.assertEquals("head\n response tail", message.content());
    }

    /**
     * An interrupt pressed while the provider delivers no further frame (silent generation / a long
     * single chunk) must abandon the stream through the bounded-wait path: {@code pump} must cancel
     * the subscription before returning a partial result, so the caller's concurrency permit release
     * (which hooks {@code cancel()}) fires instead of silently leaking the slot.
     */
    @Test
    void pumpAbortsAndCancelsSubscriptionOnNoFrameInterrupt() throws Exception {
        final var cancelled = new AtomicBoolean(false);
        final var cancellationChecks = new AtomicInteger();
        final var uncancelledTimeout = new CountDownLatch(1);
        final var subscriptionCancelled = new AtomicBoolean(false);
        final Flow.Publisher<LlmStreamEvent> silent = subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(final long n) {
                // never emit a frame, never complete: only the bounded-wait abort path ends it
            }

            @Override
            public void cancel() {
                subscriptionCancelled.set(true);
            }
        });

        final var result = CompletableFuture.supplyAsync(() -> {
            try {
                return LlmWire.pump(silent, event -> {}, () -> {
                    final var abort = cancelled.get();
                    // The first check is onSubscribe; the second follows the first 100ms timeout.
                    if (cancellationChecks.incrementAndGet() == 2) {
                        uncancelledTimeout.countDown();
                    }
                    return abort;
                });
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        });

        try {
            assertTrue(uncancelledTimeout.await(5, TimeUnit.SECONDS), "pump must check cancellation after a timeout");
            assertFalse(result.isDone(), "with no cancel the silent stream must keep waiting");
        } finally {
            // Also release the silent pump if an assertion fails.
            cancelled.set(true);
        }
        // The next no-frame timeout must cancel the subscription and end the pump.
        final var message = result.get(5, TimeUnit.SECONDS);
        assertNotNull(message, "pump must return a partial message on interrupt");
        assertTrue(subscriptionCancelled.get(), "the no-frame abort must cancel the subscription");
    }

    /**
     * A mid-stream failure after partial content must surface a {@link MidStreamFailureException}
     * whose message names the cause class (and its message when present) rather than a bare
     * {@code throwable.toString()}: the latter is uninformative for a message-less NPE and reads like
     * a transport drop even when the fault escaped the caller's {@code onDelta} consumer.
     */
    @Test
    void midStreamFailureMessageNamesTheCauseClass() {
        final Flow.Publisher<LlmStreamEvent> failing = subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(final long n) {
                subscriber.onNext(new TextDelta("partial answer"));
                subscriber.onError(new NullPointerException("boom"));
            }

            @Override
            public void cancel() {
                // nothing to release
            }
        });

        final var ex = Assertions.assertThrows(
                MidStreamFailureException.class, () -> LlmWire.pump(failing, event -> {}, () -> false));
        assertTrue(ex.getMessage().contains("stream interrupted after"), ex.getMessage());
        assertTrue(ex.getMessage().contains("NullPointerException"), ex.getMessage());
        assertTrue(ex.getMessage().contains("boom"), ex.getMessage());
    }

    /**
     * A mid-stream failure carrying a cause without a message (eg. an auto-unbox bare NPE) must not
     * append a dangling ': ' to the surfaced message.
     */
    @Test
    void midStreamFailureWithoutCauseMessageHasNoTrailingColon() {
        final Flow.Publisher<LlmStreamEvent> failing = subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(final long n) {
                subscriber.onNext(new TextDelta("partial answer"));
                subscriber.onError(new NullPointerException());
            }

            @Override
            public void cancel() {
                // nothing to release
            }
        });

        final var ex = Assertions.assertThrows(
                MidStreamFailureException.class, () -> LlmWire.pump(failing, event -> {}, () -> false));
        assertTrue(ex.getMessage().contains("NullPointerException"), ex.getMessage());
        assertFalse(
                DANGLING_CAUSE_SEPARATOR.matcher(ex.getMessage()).matches(),
                "no dangling ': ' after the cause class: " + ex.getMessage());
    }

    /**
     * A {@link TextDelta} with a null fragment must not blow up {@code text.append(null)} and must
     * not be swallowed that would crash the consumer mid-stream: it streams through as an empty delta.
     */
    @Test
    void textDeltaWithNullTextDoesNotNpeAndIsForwarded() {
        final var seen = new AtomicInteger();
        final Flow.Publisher<LlmStreamEvent> publisher = subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(final long n) {
                subscriber.onNext(new TextDelta(null));
                subscriber.onComplete();
            }

            @Override
            public void cancel() {
                // nothing to release
            }
        });

        final var message = Assertions.assertDoesNotThrow(
                () -> LlmWire.pump(publisher, event -> seen.incrementAndGet(), () -> false));
        Assertions.assertEquals(1, seen.get(), "the null-text delta must still be forwarded");
    }

    /**
     * The streaming {@code onError} after partial content must emit a
     * {@link io.yupiik.ia.agent.core.wiring.SessionAwareLogRecord} carrying the session id the pump
     * was given (the turn's session), so the TUI routes the failure to the owning tab instead of the
     * active one. The record travels with the request data - the callback may run on the HTTP
     * client's thread, never on the turn thread.
     */
    @Test
    void midStreamFailureLogsWithTheSessionAttribution() {
        final Flow.Publisher<LlmStreamEvent> failing = subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(final long n) {
                subscriber.onNext(new TextDelta("partial answer"));
                subscriber.onError(new NullPointerException("boom"));
            }

            @Override
            public void cancel() {
                // nothing to release
            }
        });
        final var logger = Logger.getLogger("io.yupiik.ia.agent.core.llm.LlmWire");
        final var captured = new AtomicReference<LogRecord>();
        final var previousUseParentHandlers = logger.getUseParentHandlers();
        final var handler = new Handler() {
            {
                setLevel(Level.ALL);
            }

            @Override
            public void publish(final LogRecord record) {
                captured.set(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        try {
            Assertions.assertThrows(
                    MidStreamFailureException.class,
                    () -> LlmWire.pump(failing, event -> {}, () -> false, "session-42"));
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(previousUseParentHandlers);
        }
        final var record = captured.get();
        Assertions.assertNotNull(record, "the streaming failure must be logged");
        Assertions.assertInstanceOf(
                SessionAwareLogRecord.class, record, "the record must carry the session attribution");
        Assertions.assertEquals("session-42", ((SessionAwareLogRecord) record).sessionId());
    }

    /**
     * The {@code sessionId} carrier must never reach the wire: {@link LlmWire#toLlmRequest} maps the
     * core request field by field and skips the internal attribution exactly like {@code settings}.
     */
    @Test
    void wireRequestNeverContainsTheSessionId() {
        final var request = new ChatCompletionRequest(
                        "m", List.of(Message.user("hi")), null, null, false, null, null, null, null, null)
                .withSessionId("session-42");
        final var wire = LlmWire.toLlmRequest(request);
        assertTrue(wire.extensions().isEmpty(), "no extras may leak: " + wire.extensions());
        // there is no sessionId field on the wire type at all - the mapping is field-by-field,
        // so the only risk is it leaking through others(); nothing else to assert here besides
        // that the call above builds a request (no NPE on the null-to-null mapping).
        Assertions.assertEquals("m", wire.model());
    }
}
