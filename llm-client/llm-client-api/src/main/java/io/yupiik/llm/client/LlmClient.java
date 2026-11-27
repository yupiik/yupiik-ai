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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.BooleanSupplier;

/**
 * The single public entry point applications embed. A transport/selection facade that produces
 * {@link LlmResponse}s and {@link LlmStreamEvent} publishers from neutral {@link LlmRequest}s,
 * with no knowledge of which {@link io.yupiik.llm.client.spi.Dialect} implementations are present.
 *
 * <p>Implementations range from simple {@link io.yupiik.llm.client.spi.Dialect} adapters to
 * {@link DialectLlmClient} which applies shared {@link LlmClientResilienceWrapper} resilience around one target,
 * and {@link RoutingLlmClient} which picks a target client per request. Callers {@link #close() close}
 * the clients they own.</p>
 */
public interface LlmClient extends AutoCloseable {

    /**
     * Run a non-streaming completion.
     *
     * @param request the neutral completion request.
     * @return the completed response.
     */
    CompletionStage<LlmResponse> chat(LlmRequest request);

    /**
     * Run a non-streaming completion, stopping early when cancelled returns true.
     *
     * @param request the neutral completion request.
     * @param cancelled cancellation probe.
     * @return the completed response.
     */
    CompletionStage<LlmResponse> chat(LlmRequest request, BooleanSupplier cancelled);

    /**
     * Stream a completion; the publisher emits {@link LlmStreamEvent}s and finishes with an End
     * event.
     *
     * @param request the neutral completion request.
     * @param cancelled cancellation probe.
     * @return a stage completing with the event publisher.
     */
    CompletionStage<Flow.Publisher<LlmStreamEvent>> stream(LlmRequest request, BooleanSupplier cancelled);

    /**
     * List models for this client (empty when it has no models endpoint).
     *
     * @return the listed models.
     */
    CompletionStage<LlmModels> models();

    @Override
    default void close() {}
}
