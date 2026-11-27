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
package io.yupiik.ia.agent.core.context;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

/**
 * Interruptible sleep used by {@link CompactionCoordinator} retry backoff.
 * <p>
 * Production delegates to {@link Thread#sleep(long)}; tests substitute a no-op or virtual-time
 * instance to exercise the retry/backoff logic without real waits (a 5-attempt exponential
 * backoff with a large base delay would otherwise stall the suite for dozens of seconds).
 * Being a Fusion bean it can be overridden in tests; being non-final it can be subclassed
 * directly when hand-wiring a coordinator.
 */
@ApplicationScoped
public class BackoffSleeper {
    /**
     * Sleeps at least {@code millis} milliseconds.
     *
     * @return {@code false} when the sleep was interrupted (the thread interrupt flag is
     *         restored), {@code true} after the full wait
     */
    public boolean sleep(final long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
