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
package io.yupiik.ia.agent.github.context;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** One budget instance is shared by the filesystem, control HTTP and Git adapters. */
public final class ContextBudget {
    private final int maxRequests;
    private final long maxBytes;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicLong bytes = new AtomicLong();

    public ContextBudget(final int maxRequests, final long maxBytes) {
        if (maxRequests < 1 || maxBytes < 1) {
            throw new IllegalArgumentException("Invalid context budget");
        }
        this.maxRequests = maxRequests;
        this.maxBytes = maxBytes;
    }

    public void request() {
        if (requests.incrementAndGet() > maxRequests || bytes.get() >= maxBytes) {
            throw new IllegalStateException("CONTEXT_BUDGET_EXHAUSTED");
        }
    }

    public void response(final long size) {
        if (size < 0 || size > maxBytes || bytes.addAndGet(size) > maxBytes) {
            throw new IllegalStateException("CONTEXT_BUDGET_EXHAUSTED");
        }
    }
}
