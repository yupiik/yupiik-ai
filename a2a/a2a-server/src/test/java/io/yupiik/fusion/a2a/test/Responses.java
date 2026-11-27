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
package io.yupiik.fusion.a2a.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.yupiik.fusion.http.server.api.Response;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow;

/**
 * Reads the body of a {@link Response} the way the HTTP layer would.
 * <p>
 * A response body is a {@code Flow.Publisher}, so asserting on it without a server means subscribing to it - which is
 * what this does, unbounded since the payloads a binding answers are already in memory.
 */
public final class Responses {
    private Responses() {
        // no-op
    }

    /**
     * @param response a response built by a binding.
     * @return its body, or an empty string when it carries none.
     */
    public static String body(final Response response) {
        final var body = response.body();
        if (body == null) {
            return "";
        }
        final var out = new StringBuilder();
        body.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(final Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(final ByteBuffer item) {
                out.append(UTF_8.decode(item));
            }

            @Override
            public void onError(final Throwable throwable) {
                throw new IllegalStateException(throwable);
            }

            @Override
            public void onComplete() {
                // nothing to do, the buffers were appended as they came
            }
        });
        return out.toString();
    }

    /**
     * @param response a response built by a binding.
     * @param name     the header to read.
     * @return its first value, {@code null} when the response carries none.
     */
    public static String header(final Response response, final String name) {
        final var values = response.headers().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}
