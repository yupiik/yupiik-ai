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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Coordinates one batch of model-requested tool calls. It owns scheduling and ordering only;
 * tool policy, execution, and appending outcomes remain with the session's turn owner.
 */
public final class ToolCallCoordinator {
    @FunctionalInterface
    public interface Awaiter<O> {
        O await(Future<O> future) throws InterruptedException;
    }
    /**
     * Executes calls in model order, grouping adjacent parallelizable calls when parallel execution is enabled.
     * The parallel futures may complete in any order, while {@code append} always receives their results in
     * the original call order.
     */
    public <C, O> boolean execute(
            final List<C> calls,
            final BooleanSupplier cancelled,
            final Predicate<C> parallelizable,
            final boolean parallelEnabled,
            final Function<C, O> guardedExecution,
            final Function<C, Future<O>> parallelExecution,
            final Consumer<O> append,
            final Awaiter<O> await,
            final Runnable checkBudget,
            final LongSupplier nanoTime,
            final LongConsumer recordParallelWallTime,
            final BooleanSupplier informative)
            throws InterruptedException {
        var index = 0;
        while (index < calls.size()) {
            if (cancelled.getAsBoolean()) {
                return informative.getAsBoolean();
            }
            if (parallelizable.test(calls.get(index))) {
                var end = index + 1;
                while (end < calls.size() && parallelizable.test(calls.get(end))) {
                    end++;
                }
                if (end - index > 1 && parallelEnabled) {
                    final var batchStartNanos = nanoTime.getAsLong();
                    final var futures = new ArrayList<Future<O>>(end - index);
                    try {
                        for (var cursor = index; cursor < end; cursor++) {
                            futures.add(parallelExecution.apply(calls.get(cursor)));
                        }
                        for (final var future : futures) {
                            append.accept(await.await(future));
                            checkBudget.run();
                        }
                    } finally {
                        for (final var future : futures) {
                            if (!future.isDone()) {
                                future.cancel(true);
                            }
                        }
                    }
                    recordParallelWallTime.accept((nanoTime.getAsLong() - batchStartNanos) / 1_000_000);
                } else {
                    for (var cursor = index; cursor < end; cursor++) {
                        append.accept(guardedExecution.apply(calls.get(cursor)));
                        checkBudget.run();
                    }
                }
                index = end;
            } else {
                append.accept(guardedExecution.apply(calls.get(index)));
                checkBudget.run();
                index++;
            }
        }
        return informative.getAsBoolean();
    }
}
