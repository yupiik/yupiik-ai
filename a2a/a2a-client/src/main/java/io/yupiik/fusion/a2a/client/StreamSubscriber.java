/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.fusion.a2a.client;

import io.yupiik.fusion.a2a.model.StreamResponse;
import io.yupiik.fusion.json.JsonMapper;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

/**
 * Turns the {@code text/event-stream} lines of an A2A stream into {@link StreamResponse} events.
 * <p>
 * It is a {@link Flow.Subscriber} so the events are dispatched as the bytes arrive - the HTTP client never buffers the
 * whole stream - which is what makes {@link A2AClient#sendStreamingMessage} usable on a task producing output for
 * minutes.
 * <p>
 * Keep-alive comments and the {@code id}/{@code event} lines are skipped, and the JSON-RPC envelope is unwrapped when
 * there is one so a consumer sees the same events whatever the binding.
 */
class StreamSubscriber implements Flow.Subscriber<String> {
    private static final String DATA_PREFIX = "data:";

    private final JsonMapper jsons;
    private final Consumer<StreamResponse> onEvent;
    private final StringBuilder prelude = new StringBuilder();
    private final StringBuilder data = new StringBuilder();

    private Flow.Subscription subscription;
    private volatile boolean started;

    StreamSubscriber(final JsonMapper jsons, final Consumer<StreamResponse> onEvent) {
        this.jsons = jsons;
        this.onEvent = onEvent;
    }

    /**
     * @return what was received before the first event, which is the whole payload when the agent answered an error
     * instead of a stream.
     */
    String prelude() {
        return prelude.toString();
    }

    @Override
    public void onSubscribe(final Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(1);
    }

    @Override
    public void onNext(final String line) {
        try {
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    final var payload = data.substring(0, data.length() - 1);
                    data.setLength(0);
                    if (!payload.isEmpty()) {
                        onData(payload);
                    }
                }
            } else if (line.startsWith(DATA_PREFIX) || line.equals("data")) {
                started = true;
                final var value = line.equals("data") ? "" : line.substring(DATA_PREFIX.length());
                data.append(value.startsWith(" ") ? value.substring(1) : value).append('\n');
            } else if (!started) {
                // an error answer is a plain JSON body, keep it so the caller can report it
                prelude.append(line);
            }
        } finally {
            subscription.request(1);
        }
    }

    @Override
    public void onError(final Throwable throwable) {
        // the failure is already carried by the promise of the request, nothing to add here
    }

    @Override
    public void onComplete() {
        // the promise of the request completes, nothing to add here
    }

    private void onData(final String json) {
        final var payload = jsons.fromString(Object.class, json);
        if (!(payload instanceof Map<?, ?> map)) {
            return;
        }
        // the JSON-RPC binding wraps every event in the envelope of the originating request
        final var event = map.containsKey("jsonrpc") ? map.get("result") : map;
        if (event != null) {
            onEvent.accept(jsons.fromString(StreamResponse.class, jsons.toString(event)));
        }
    }
}
