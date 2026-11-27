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

/**
 * Resilience knobs applied by {@link LlmClient} around every upstream call.
 *
 * @param retriesMaxAttempts max attempts before giving up (default 3).
 * @param retriesBaseDelayMs base exponential backoff delay (default 500).
 * @param rateLimitRequestsPerMinute token-bucket cap per minute, 0 disables throttling.
 * @param maxConcurrent max in-flight requests (default 4).
 * @param retryOnContextOverflow whether a {@link ContextWindowExceededException} retry is attempted.
 * @param retryAfterEnabled whether a {@code retry-after=} delay found on a provider rate-limit error
 *     is honored (raised to from the computed backoff, capped at {@code retryAfterMaxWaitMs}, with a
 *     10s default wait on concurrency-limit errors that carry no header); default on.
 * @param retryAfterMaxWaitMs upper bound honored for a provider {@code retry-after} or
 *     concurrency-limit cooldown (default 5 minutes).
 * @param waitScale multiplier applied to the effective scheduling delay only; the reported reason
 *     keeps the logical wait (default 1.0).
 * @param concurrencyAdaptationMs cooldown before relaxing a concurrency limit adapted from a
 *     provider {@code 429 concurrency_limit_exceeded} response back to the configured cap
 *     (default 15 minutes).
 * @param usageLimitRetryThresholdMs a provider {@code 429} error body reporting {@code usage_limit_reached}
 *     with a recovery window beyond this threshold (via {@code limit_window_minutes} or {@code resets_at})
 *     is treated as non-retryable (the quota will not clear in retry-seconds); default 1 hour. Shorter
 *     windows keep the normal retry behavior.
 * @param concurrencyAcquireTimeoutMs max time a request may wait for a free concurrency slot before
 *     failing with a {@link io.yupiik.llm.client.spi.ConcurrencySlotTimeoutException} (default 5
 *     minutes; {@code 0} or negative waits forever). Without it a permanently saturated gate - a peer
 *     process holding every slot, or a {@code kill -STOP}'d one - would turn a {@code 429} into a silent
 *     hang. The bound applies to <em>one</em> admission, not to the whole retry loop: a slot timeout is
 *     not retried, since the budget is exactly what the provider told us to respect.
 */
public record LlmResilienceConfig(
        int retriesMaxAttempts,
        long retriesBaseDelayMs,
        int rateLimitRequestsPerMinute,
        int maxConcurrent,
        boolean retryOnContextOverflow,
        boolean retryAfterEnabled,
        long retryAfterMaxWaitMs,
        double waitScale,
        long concurrencyAdaptationMs,
        long usageLimitRetryThresholdMs,
        long concurrencyAcquireTimeoutMs) {

    /**
     * The default configuration.
     *
     * @return 3 attempts, 500 ms base delay, throttling off, 4 concurrent, context overflow retry on,
     *     retry-after honoring on capped at 5 minutes, 15 minutes concurrency adaptation cooldown,
     *     1 hour usage-limit window threshold, 5 minutes concurrency-slot wait.
     */
    public static LlmResilienceConfig defaults() {
        return new LlmResilienceConfig(3, 500, 0, 4, true, true, 300_000L, 1.0d, 900_000L, 3_600_000L, 300_000L);
    }
}
