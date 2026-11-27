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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.github.model.TaskState;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/** Operator-triggered terminal task retention. Durable invocation receipts are never purged. */
@ApplicationScoped
public class BotMaintenance {
    private final TaskStore store;
    private final Clock clock;
    private final TaskDiagnostics diagnostics;

    public BotMaintenance(final TaskStore store, final Clock clock, final TaskDiagnostics diagnostics) {
        this.store = store;
        this.clock = clock;
        this.diagnostics = diagnostics;
    }

    public int purge(final Duration retention) {
        return purge(retention, false);
    }

    public int purge(final Duration retention, final boolean dryRun) {
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("Positive retention required");
        }
        final var cutoff = clock.instant().minus(retention);
        return store.transaction(state -> {
            int removed = 0;
            final var entries = state.tasks().entrySet().iterator();
            while (entries.hasNext()) {
                final var entry = entries.next();
                final var task = entry.getValue();
                if (Set.of(TaskState.done, TaskState.failed).contains(task.state())
                        && task.attemptDirectory() == null
                        && task.completedAt() instanceof String completed
                        && Instant.parse(completed).isBefore(cutoff)) {
                    if (!dryRun) {
                        try {
                            diagnostics.delete(entry.getKey());
                        } catch (final IOException e) {
                            throw new UncheckedIOException("Unable to delete private task diagnostics", e);
                        }
                        entries.remove();
                    }
                    removed++;
                }
            }
            return removed;
        });
    }
}
