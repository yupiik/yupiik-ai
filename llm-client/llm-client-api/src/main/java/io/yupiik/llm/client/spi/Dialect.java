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
package io.yupiik.llm.client.spi;

import io.yupiik.llm.client.model.LlmModels;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmResponse;
import io.yupiik.llm.client.model.LlmStreamEvent;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.BooleanSupplier;

/**
 * A single wire protocol: translates the neutral {@link LlmRequest} into one provider format and
 * back into {@link LlmResponse} / {@link LlmStreamEvent}, and applies provider authentication.
 *
 * <p>Implementations are constructed by the application which injects the shared
 * {@code ExtendedHttpClient}, {@code JsonMapper} and the target {@link ProviderSpec}; because the only
 * coupling is the {@code HttpClient} instance, a synchronous {@link java.net.http.HttpClient} can be
 * adapted for reactive use on the application side without any change in this library.</p>
 *
 * <p>All operations are fully reactive: requests go through the injected {@code ExtendedHttpClient}
 * {@code sendAsync}, and failures (including {@link io.yupiik.llm.client.DialectNotSupportedException}
 * and {@link io.yupiik.llm.client.ContextWindowExceededException}) are reported as exceptional
 * completion of the returned {@link CompletionStage}, never as thrown checked exceptions.</p>
 */
public interface Dialect {

    /**
     * A stable identifier and human label for this wire protocol, used for routing and exceptions.
     *
     * @return the dialect name.
     */
    String name();

    /** Local wire serialization for diagnostics/accounting; performs no network requests. */
    default byte[] requestPayload(final LlmRequest request) {
        throw new UnsupportedOperationException("Local payload projection unavailable for " + name());
    }

    /**
     * Run a single completion.
     *
     * @param request the neutral completion request.
     * @return the completed response.
     */
    CompletionStage<LlmResponse> chat(LlmRequest request);

    /**
     * Run a single completion, stopping early when cancelled returns true.
     *
     * @param request the neutral completion request.
     * @param cancelled cancellation probe.
     * @return the completed response.
     */
    CompletionStage<LlmResponse> chat(LlmRequest request, BooleanSupplier cancelled);

    /**
     * Run a streaming completion, publishing {@link LlmStreamEvent}s and finishing with an End
     * event.
     *
     * @param request the neutral completion request.
     * @param cancelled cancellation probe.
     * @return a stage completing with the event publisher.
     */
    CompletionStage<Flow.Publisher<LlmStreamEvent>> stream(LlmRequest request, BooleanSupplier cancelled);

    /**
     * List provider models; providers without a models endpoint return an empty result.
     *
     * @return the listed models.
     */
    CompletionStage<LlmModels> models();

    /**
     * Run a streaming completion without any response handling: the returned attempt carries the raw
     * byte publisher for 2xx server sent events responses, or the collected body otherwise. Useful
     * for proxying (vendor specific frames can pass through verbatim) and for custom handling.
     *
     * @param request the neutral completion request.
     * @param cancelled cancellation probe.
     * @return the raw stream attempt.
     */
    CompletionStage<StreamAttempt> streamRaw(LlmRequest request, BooleanSupplier cancelled);

    /**
     * Run a buffered completion without any response classification: the HTTP response is returned
     * verbatim (status, headers and body bytes), letting callers handle errors themselves.
     *
     * @param request the neutral completion request.
     * @return the verbatim HTTP response.
     */
    CompletionStage<HttpResponse<byte[]>> postRaw(LlmRequest request);
}
