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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * An {@link LlmClient} that selects a target client per request.
 *
 * <p>The router is a pure selector: it holds only a {@code clientSelector}
 * ({@code Function<LlmRequest, LlmClient>}) that, given a request, returns the underlying client to
 * call, then delegates the operation to it untouched. Resilience is not its concern - wrap each
 * target in a {@link DialectLlmClient} to apply {@link LlmClientResilienceWrapper}. Selection policy (by
 * dialect name, fallback chains, absence handling) is entirely the caller's concern, supplied at
 * construction. A common default is available through {@link #byApiExtension(Map, LlmClient)} which
 * resolves the dialect named by {@code request.extensions()["api"]}.</p>
 */
public final class RoutingLlmClient implements LlmClient {

    private final Function<LlmRequest, LlmClient> clientSelector;

    /**
     * Creates the router.
     *
     * @param clientSelector returns the target {@link LlmClient} for a request; used with a
     *     {@code null} request by {@link #models()}.
     */
    public RoutingLlmClient(final Function<LlmRequest, LlmClient> clientSelector) {
        this.clientSelector = clientSelector;
    }

    @Override
    public CompletionStage<LlmResponse> chat(final LlmRequest request) {
        return chat(request, null);
    }

    @Override
    public CompletionStage<LlmResponse> chat(final LlmRequest request, final BooleanSupplier cancelled) {
        return clientSelector.apply(request).chat(request, cancelled);
    }

    /**
     * Chat with a pause listener scoped to this call, forwarded to the selected
     * {@link DialectLlmClient} when it supports it (a plain {@link LlmClient} target just ignores
     * it). Lets concurrent callers each observe only their own throttles/backoffs.
     */
    public CompletionStage<LlmResponse> chat(
            final LlmRequest request, final BooleanSupplier cancelled, final Consumer<PauseEvent> listener) {
        final var client = clientSelector.apply(request);
        return client instanceof final DialectLlmClient dialect
                ? dialect.chat(request, cancelled, listener)
                : client.chat(request, cancelled);
    }

    @Override
    public CompletionStage<Flow.Publisher<LlmStreamEvent>> stream(
            final LlmRequest request, final BooleanSupplier cancelled) {
        return clientSelector.apply(request).stream(request, cancelled);
    }

    /** Stream with a pause listener scoped to this call (see {@link #chat(LlmRequest, BooleanSupplier, Consumer)}). */
    public CompletionStage<Flow.Publisher<LlmStreamEvent>> stream(
            final LlmRequest request, final BooleanSupplier cancelled, final Consumer<PauseEvent> listener) {
        final var client = clientSelector.apply(request);
        return client instanceof final DialectLlmClient dialect
                ? dialect.stream(request, cancelled, listener)
                : client.stream(request, cancelled);
    }

    @Override
    public CompletionStage<LlmModels> models() {
        return clientSelector.apply(null).models();
    }

    @Override
    public void close() {
        // children are owned by the caller; nothing to release here
    }

    /**
     * Default selector resolving the dialect named by {@code request.extensions()["api"]} (case
     * insensitive) against {@code byName}, falling back to a registered client for null, blank or
     * unknown values.
     *
     * @param byName dialect name to client mapping.
     * @param fallback client used for null, blank or unknown api values.
     * @return the selector function.
     */
    public static Function<LlmRequest, LlmClient> byApiExtension(
            final Map<String, LlmClient> byName, final LlmClient fallback) {
        return request -> {
            if (request == null
                    || request.extensions() == null
                    || request.extensions().isEmpty()) {
                return fallback;
            }
            final var api = request.extensions().get("api");
            if (api == null) {
                return fallback;
            }
            final var key = api.toString().strip().toLowerCase(Locale.ROOT);
            return byName.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(key))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(fallback);
        };
    }
}
