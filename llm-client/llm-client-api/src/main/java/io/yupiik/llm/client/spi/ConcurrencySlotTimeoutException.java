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

import java.io.IOException;

/**
 * No concurrency slot became free before the configured admission deadline.
 *
 * <p>A dedicated type on purpose: the retry layer classifies "timed out" messages as transient
 * transport failures, and retrying a saturated budget would multiply a five minute admission wait by
 * the retry count while the provider is telling us to slow down. Callers - and the resilience wrapper
 * - treat this as its own outcome rather than as a flaky network read.</p>
 */
public class ConcurrencySlotTimeoutException extends IOException {
    /**
     * Creates the failure.
     *
     * @param message the diagnosis, including the waited time and the queue state.
     */
    public ConcurrencySlotTimeoutException(final String message) {
        super(message);
    }
}
