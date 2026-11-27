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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BackoffSleeperTest {

    @Test
    void fullSleepReturnsTrue() throws InterruptedException {
        final var sleeper = new BackoffSleeper();
        final var started = System.nanoTime();
        assertTrue(sleeper.sleep(10), "an uninterrupted sleep must report success");
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(5), "the sleep must actually wait");
    }

    @Test
    void interruptedSleepReturnsFalseAndRestoresTheFlag() throws InterruptedException {
        final var sleeper = new BackoffSleeper();
        final var result = new AtomicReference<Boolean>();
        final var flag = new AtomicBoolean();
        final var thread = new Thread(() -> {
            Thread.currentThread().interrupt();
            result.set(sleeper.sleep(60_000));
            flag.set(Thread.currentThread().isInterrupted());
        });
        thread.start();
        thread.join(5_000);
        assertFalse(thread.isAlive(), "the interrupted sleep must return promptly");
        assertFalse(result.get(), "an interrupted sleep must report failure");
        assertTrue(flag.get(), "the interrupt flag must be restored after the interrupted sleep");
    }
}
