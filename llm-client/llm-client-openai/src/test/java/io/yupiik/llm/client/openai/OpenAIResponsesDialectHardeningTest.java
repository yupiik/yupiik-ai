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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.llm.client.model.LlmMessage;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.spi.ProviderSpec;
import io.yupiik.llm.client.test.TestJsonMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Regression coverage for the per-stream {@code streamedArgIds} isolation: two concurrent streams
 * on the shared dialect must never double-emit assembled tool-call arguments because one stream
 * observed (and/or cleared) the other stream's delta bookkeeping.
 */
@ExtendWith(TestJsonMapper.class)
class OpenAIResponsesDialectHardeningTest {

    private HttpServer server;
    private HttpClient delegate;
    private ExtendedHttpClient client;
    private final CountDownLatch releaseStreamA = new CountDownLatch(1);
    private final AtomicReference<String> seenModel = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        delegate = HttpClient.newBuilder().build();
        client = new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate));
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (client != null) {
            client.close();
            delegate.shutdownNow();
            delegate.close();
            client = null;
            delegate = null;
        }
    }

    @Test
    void concurrentStreamsKeepTheirOwnDeltaBookkeeping(final JsonMapper mapper) throws Exception {
        final var deltaA = "event: response.function_call_arguments.delta\n"
                + "data: {\"item_id\":\"call-A\",\"output_index\":0,\"delta\":\"{\\\"a\\\":\"}\n\n";
        final var doneA = "event: response.function_call_arguments.done\n"
                + "data: {\"item_id\":\"call-A\",\"output_index\":0,\"arguments\":\"{\\\"a\\\":1}\"}\n\n";
        final var deltaB = "event: response.function_call_arguments.delta\n"
                + "data: {\"item_id\":\"call-B\",\"output_index\":0,\"delta\":\"{\\\"b\\\":\"}\n\n";
        final var doneB = "event: response.function_call_arguments.done\n"
                + "data: {\"item_id\":\"call-B\",\"output_index\":0,\"arguments\":\"{\\\"b\\\":2}\"}\n\n";
        final var completed = "event: response.completed\n"
                + "data: {\"response\":{\"id\":\"r1\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1,"
                + "\"total_tokens\":2}}}\n\n";

        server.createContext("/v1/responses", exchange -> {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            final Object parsed = mapper.fromString(Map.class, body);
            final String model = parsed instanceof Map<?, ?> map && map.get("model") instanceof String m ? m : "?";
            seenModel.set(model);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            final var out = exchange.getResponseBody();
            if ("mA".equals(model)) {
                // write the delta, then block until the test has started stream B (in the old
                // code, stream B's start cleared the shared set before stream A's 'done' lands)
                out.write(deltaA.getBytes(StandardCharsets.UTF_8));
                out.flush();
                try {
                    releaseStreamA.await(10, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                out.write(doneA.getBytes(StandardCharsets.UTF_8));
            } else {
                out.write(deltaB.getBytes(StandardCharsets.UTF_8));
                out.write(doneB.getBytes(StandardCharsets.UTF_8));
            }
            out.write(completed.getBytes(StandardCharsets.UTF_8));
            out.flush();
            exchange.close();
        });
        server.start();
        final var url = "http://localhost:" + server.getAddress().getPort() + "/v1/responses";
        final var dialect = new OpenAIResponsesDialect(mapper, client, ProviderSpec.of(url, "key"));

        // stream A: model mA, call-A - subscribe and wait for its delta to be delivered first
        final var streamA = new CopyOnWriteArrayList<LlmStreamEvent>();
        final var aDeltaSeen = new CountDownLatch(1);
        dialect.stream(request("mA"), () -> false)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS)
                .subscribe(collector(streamA, aDeltaSeen));
        assertTrue(aDeltaSeen.await(5, TimeUnit.SECONDS), "stream A must deliver its delta first");

        // stream B starting (and, in the buggy shared-set design, clearing the set) mid-A
        final var streamB = new CopyOnWriteArrayList<LlmStreamEvent>();
        dialect.stream(request("mB"), () -> false)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS)
                .subscribe(collector(streamB, new CountDownLatch(0)));

        // now release A's 'done' - with a shared set it would re-emit the assembled args
        releaseStreamA.countDown();
        waitCompletion(streamA);
        waitCompletion(streamB);

        assertEquals(1, toolCallDeltas(streamA, "call-A"), "stream A must emit ONE delta for call-A, got: " + streamA);
        assertEquals(1, toolCallDeltas(streamB, "call-B"), "stream B must emit ONE delta for call-B, got: " + streamB);
        assertTrue(
                streamA.stream().noneMatch(e -> hasCallId(e, "call-B")),
                "stream A must not observe stream B's call, got: " + streamA);
        assertTrue(
                streamB.stream().noneMatch(e -> hasCallId(e, "call-A")),
                "stream B must not observe stream A's call, got: " + streamB);
    }

    @Test
    void streamedFunctionCallPrefersCallIdAndDoesNotDoubleAppend(final JsonMapper mapper) throws Exception {
        // A streamed Responses function_call carries both item.id (fc_s) and call_id (call_s). The neutral
        // tool-call id delivered on the deltas must be the call_id, never downgraded to the item_id, and
        // the assembled arguments on .done / output_item.done must not be appended a second time after the
        // incremental delta already delivered them.
        final var added = "event: response.output_item.added\ndata: {\"type\":\"response.output_item.added\","
                + "\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc_s\",\"call_id\":\"call_s\","
                + "\"name\":\"read\",\"arguments\":\"\"}}\n\n";
        final var delta = "event: response.function_call_arguments.delta\ndata: "
                + "{\"item_id\":\"fc_s\",\"output_index\":0,\"delta\":\"{\\\"path\\\":\\\"a\\\"}\"}\n\n";
        final var done = "event: response.function_call_arguments.done\ndata: "
                + "{\"item_id\":\"fc_s\",\"output_index\":0,\"arguments\":\"{\\\"path\\\":\\\"a\\\"}\"}\n\n";
        final var itemDone = "event: response.output_item.done\ndata: {\"type\":\"response.output_item.done\","
                + "\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc_s\",\"call_id\":\"call_s\","
                + "\"name\":\"read\",\"arguments\":\"{\\\"path\\\":\\\"a\\\"}\"}}\n\n";
        final var completed = "event: response.completed\ndata: {\"response\":{\"id\":\"r1\","
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}}\n\n";

        server.createContext("/v1/responses", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            final var out = exchange.getResponseBody();
            out.write((added + delta + done + itemDone + completed).getBytes(StandardCharsets.UTF_8));
            out.flush();
            exchange.close();
        });
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        final var events = new CopyOnWriteArrayList<LlmStreamEvent>();
        dialect.stream(request("mA"), () -> false)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS)
                .subscribe(collector(events, new CountDownLatch(0)));
        waitCompletion(events);

        // every delta must carry the call_id, never the item id
        final var deltas = events.stream()
                .filter(e -> e instanceof LlmStreamEvent.ToolCallDelta)
                .map(e -> (LlmStreamEvent.ToolCallDelta) e)
                .toList();
        assertFalse(deltas.isEmpty(), "tool-call deltas must be produced");
        assertTrue(
                deltas.stream().allMatch(d -> "call_s".equals(d.id())),
                "all deltas must use the call_id, got: " + deltas);
        // the incremental delta is the only source of the argument text: the assembled arguments carried by
        // .done and output_item.done must be suppressed (no double-append), so exactly ONE delta carries content
        final var argBearing = events.stream()
                .filter(e -> e instanceof LlmStreamEvent.ToolCallDelta d
                        && d.arguments() != null
                        && !d.arguments().isEmpty())
                .count();
        assertEquals(1, argBearing, "the arguments must be delivered by exactly one delta: " + events);
        // the id streamed must never be the item id fc_s
        assertTrue(
                events.stream().noneMatch(e -> hasCallId(e, "fc_s")),
                "the item id must never leak as the tool-call id");
    }

    private static LlmRequest request(final String model) {
        return new LlmRequest(
                model,
                List.of(LlmMessage.user("hi")),
                null,
                null,
                64,
                null,
                null,
                null,
                null,
                null,
                true,
                false,
                Map.of());
    }

    private static Flow.Subscriber<LlmStreamEvent> collector(
            final CopyOnWriteArrayList<LlmStreamEvent> events, final CountDownLatch deltaSeen) {
        return new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(final Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(final LlmStreamEvent item) {
                if (item instanceof LlmStreamEvent.ToolCallDelta) {
                    deltaSeen.countDown();
                }
                events.add(item);
            }

            @Override
            public void onError(final Throwable throwable) {
                events.add(new LlmStreamEvent.End("error", null));
            }

            @Override
            public void onComplete() {
                events.add(new LlmStreamEvent.End("stop", null));
            }
        };
    }

    private static void waitCompletion(final CopyOnWriteArrayList<LlmStreamEvent> events) throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && events.stream().noneMatch(e -> e instanceof LlmStreamEvent.End)) {
            Thread.sleep(10);
        }
        assertTrue(
                events.stream().anyMatch(e -> e instanceof LlmStreamEvent.End),
                "stream did not complete in time: " + events);
    }

    private static long toolCallDeltas(final List<LlmStreamEvent> events, final String callId) {
        return events.stream()
                .filter(e -> e instanceof LlmStreamEvent.ToolCallDelta delta && callId.equals(delta.id()))
                .count();
    }

    private static boolean hasCallId(final LlmStreamEvent event, final String callId) {
        return event instanceof LlmStreamEvent.ToolCallDelta delta && callId.equals(delta.id());
    }
}
