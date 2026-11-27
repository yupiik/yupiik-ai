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

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Resolved provider connection settings. {@code baseUrl} is the full chat endpoint (e.g.
 * {@code https://api.anthropic.com/v1/messages}); the models endpoint is derived from it.
 *
 * @param baseUrl chat endpoint.
 * @param apiKey secret, resolved (e.g. from env) by the caller.
 * @param timeout per-request timeout.
 * @param options extra fields merged into every wire payload.
 * @param headersCustomizer extra HTTP headers applied to every request, after the dialect
 *     defaults (so it can override the apiKey/version headers with {@code setHeader}). The caller
 *     owns the composition (static and dynamic headers, template rendering, caching).
 */
public record ProviderSpec(
        String baseUrl,
        String apiKey,
        Duration timeout,
        Map<String, Object> options,
        Consumer<HttpRequest.Builder> headersCustomizer) {

    /** Defaults {@code timeout} to 120s and {@code options}/{@code headersCustomizer} to empty/no-op. */
    public ProviderSpec {
        timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
        options = options == null ? Map.of() : options;
        headersCustomizer = headersCustomizer == null ? builder -> {} : headersCustomizer;
    }

    /**
     * A spec with a 120s timeout and no extra options or header customization.
     *
     * @param baseUrl the full chat endpoint.
     * @param apiKey the provider secret.
     * @return the spec.
     */
    public static ProviderSpec of(final String baseUrl, final String apiKey) {
        return new ProviderSpec(baseUrl, apiKey, Duration.ofSeconds(120), Map.of(), null);
    }
}
