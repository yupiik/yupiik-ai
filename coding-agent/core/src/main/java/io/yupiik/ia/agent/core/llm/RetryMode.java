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

/**
 * Who owns retries for a chat call.
 *
 * <p>{@link #DELEGATED} is the default: the resilience wrapper keeps its internal retry loop
 * (semaphore throttle, rate limiting and exponential backoff). {@link #COOPERATIVE} keeps the
 * wrapper's concurrency/rate-limit throttle but skips its internal retry loop, so a caller that
 * runs its own retry/fallback loop (the context-compaction coordinator) becomes the single retry
 * authority and a transient failure is surfaced to it immediately instead of being retried twice
 * (once by the wrapper, once by the caller).</p>
 */
public enum RetryMode {
    /** The resilience wrapper owns retries: the default for conversation requests. */
    DELEGATED,
    /** The caller owns retries: the wrapper only throttles (permit + rate limit). */
    COOPERATIVE
}
