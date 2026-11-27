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
package io.yupiik.ia.agent.core.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ToolCallCoordinatorTest {
    @Test
    void interruptingParallelBatchStopsAllItsWorkers() throws Exception {
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var started = new CountDownLatch(2);
            final var stopped = new CountDownLatch(2);
            final var awaiting = new CountDownLatch(1);
            final var owner = Thread.ofVirtual().start(() -> {
                try {
                    new ToolCallCoordinator()
                            .execute(
                                    List.of("one", "two"),
                                    () -> false,
                                    _ -> true,
                                    true,
                                    value -> value,
                                    value -> executor.submit(() -> {
                                        started.countDown();
                                        try {
                                            new CountDownLatch(1).await();
                                            return value;
                                        } finally {
                                            stopped.countDown();
                                        }
                                    }),
                                    _ -> {},
                                    (Future<String> future) -> {
                                        awaiting.countDown();
                                        try {
                                            return future.get();
                                        } catch (final ExecutionException e) {
                                            throw new IllegalStateException(e);
                                        }
                                    },
                                    () -> {},
                                    System::nanoTime,
                                    _ -> {},
                                    () -> false);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertTrue(awaiting.await(5, TimeUnit.SECONDS));
                owner.interrupt();
                assertTrue(stopped.await(5, TimeUnit.SECONDS), "both parallel tools must stop, not just their awaiter");
                owner.join(5_000);
                assertTrue(!owner.isAlive());
            } finally {
                owner.interrupt();
                executor.shutdownNow();
            }
        }
    }
}
