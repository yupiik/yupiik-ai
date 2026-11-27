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
package io.yupiik.llm.client;

import io.yupiik.llm.client.model.LlmModels;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmResponse;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.spi.Dialect;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * An {@link LlmClient} applying the shared {@link LlmClientResilienceWrapper} resilience around a single
 * {@link Dialect}.
 *
 * <p>This is the resilient building block handed to a {@link RoutingLlmClient}: each dialect is
 * usually wrapped in its own {@code DialectLlmClient} so concurrency-throttling, rate limiting and
 * backoff apply per dialect, while the router stays a pure selector that never deals with
 * resilience itself.</p>
 */
public final class DialectLlmClient implements LlmClient {

    private final Dialect delegate;
    private final LlmClientResilienceWrapper helper;

    /**
     * Creates a resilient client around a dialect with an explicit helper.
     *
     * @param delegate the underlying dialect to wrap.
     * @param helper shared resilience layer.
     */
    public DialectLlmClient(final Dialect delegate, final LlmClientResilienceWrapper helper) {
        this.delegate = delegate;
        this.helper = helper;
    }

    /** Serializes through the actual dialect without transport, retries, or observers. */
    public byte[] requestPayload(final LlmRequest request) {
        return delegate.requestPayload(request);
    }

    /**
     * Creates a resilient client around a dialect, building the resilience layer from a config.
     *
     * @param delegate the underlying dialect to wrap.
     * @param config resilience knobs (may be {@code null} for defaults).
     * @param pauseListener listener notified of throttle/backoff pauses (may be {@code null}).
     */
    public DialectLlmClient(
            final Dialect delegate, final LlmResilienceConfig config, final Consumer<PauseEvent> pauseListener) {
        this(delegate, new LlmClientResilienceWrapper(config, pauseListener));
    }

    @Override
    public CompletionStage<LlmResponse> chat(final LlmRequest request) {
        return chat(request, null);
    }

    @Override
    public CompletionStage<LlmResponse> chat(final LlmRequest request, final BooleanSupplier cancelled) {
        return helper.chat(delegate, request, cancelled);
    }

    /**
     * Chat with a pause listener scoped to this call: throttle, concurrency-cap and retry-backoff
     * notifications go to {@code listener} instead of the shared one.
     */
    public CompletionStage<LlmResponse> chat(
            final LlmRequest request, final BooleanSupplier cancelled, final Consumer<PauseEvent> listener) {
        return helper.chat(delegate, request, cancelled, listener);
    }

    /**
     * Chat with a per-call pause listener and the cooperative (retry-skipping) flag: the concurrency
     * permit and rate-limit throttle still apply, but transient failures surface immediately so a
     * caller with its own retry loop becomes the single retry authority.
     */
    public CompletionStage<LlmResponse> chat(
            final LlmRequest request,
            final BooleanSupplier cancelled,
            final Consumer<PauseEvent> listener,
            final boolean skipRetries) {
        return helper.chat(delegate, request, cancelled, listener, skipRetries);
    }

    @Override
    public CompletionStage<Flow.Publisher<LlmStreamEvent>> stream(
            final LlmRequest request, final BooleanSupplier cancelled) {
        return helper.stream(delegate, request, cancelled);
    }

    /** Stream with a pause listener scoped to this call (see {@link #chat(LlmRequest, BooleanSupplier, Consumer)}). */
    public CompletionStage<Flow.Publisher<LlmStreamEvent>> stream(
            final LlmRequest request, final BooleanSupplier cancelled, final Consumer<PauseEvent> listener) {
        return helper.stream(delegate, request, cancelled, listener);
    }

    @Override
    public CompletionStage<LlmModels> models() {
        return helper.models(delegate);
    }

    @Override
    public void close() {
        // the dialect is owned by the caller; nothing to release here
    }
}
