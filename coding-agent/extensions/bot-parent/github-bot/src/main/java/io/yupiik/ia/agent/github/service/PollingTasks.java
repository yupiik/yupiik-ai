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
package io.yupiik.ia.agent.github.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.ToIntFunction;

final class PollingTasks {
    private PollingTasks() {}

    static <T> int sum(final int parallelism, final List<T> repositories, final ToIntFunction<T> operation) {
        if (parallelism < 1 || parallelism > 16) {
            throw new IllegalArgumentException("Poll parallelism must be between 1 and 16");
        }
        if (parallelism == 1) {
            int total = 0;
            for (final var repository : repositories) {
                total += operation.applyAsInt(repository);
            }
            return total;
        }
        try (final var executor =
                Executors.newFixedThreadPool(parallelism, Thread.ofVirtual().factory())) {
            final var completion = new ExecutorCompletionService<Integer>(executor);
            final var jobs = new ArrayList<Future<Integer>>(repositories.size());
            boolean success = false;
            try {
                for (final var repository : repositories) {
                    jobs.add(completion.submit(() -> operation.applyAsInt(repository)));
                }
                int total = 0;
                // Observe failures as they complete, even when an earlier repository is still blocked.
                for (int i = 0; i < jobs.size(); i++) {
                    total += completion.take().get();
                }
                success = true;
                return total;
            } catch (final InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Polling interrupted", error);
            } catch (final ExecutionException error) {
                if (error.getCause() instanceof RuntimeException cause) {
                    throw cause;
                }
                if (error.getCause() instanceof Error cause) {
                    throw cause;
                }
                throw new IllegalStateException("Polling failed", error.getCause());
            } finally {
                if (!success) {
                    for (final var job : jobs) {
                        job.cancel(true);
                    }
                }
            }
        }
    }
}
