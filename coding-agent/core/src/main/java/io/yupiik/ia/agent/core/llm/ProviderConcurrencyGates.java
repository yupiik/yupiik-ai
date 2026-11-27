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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.llm.client.spi.ConcurrencyGate;
import io.yupiik.llm.client.spi.InMemoryGate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Owns one concurrency budget per provider id across clients, until application shutdown.
 *
 * <p>File leases require both an opt-in capability and a configured positive provider or global
 * maximum. A learned limit alone never activates filesystem access. The first lookup selects the
 * implementation: replacing a live gate would split the budget and strand outstanding leases.
 * Conflicting modes and requested capacities are each warned about once per id.
 *
 * <p>Lookups reconcile to the minimum positive bound requested so far (nonpositive means unbounded),
 * without widening a learned narrowing. Explicit capacity changes still support adaptation recovery,
 * but cannot exceed the reconciled ceiling. Registry shutdown closes its gates and rejects lookups.
 */
@ApplicationScoped
public class ProviderConcurrencyGates implements AutoCloseable {
    private static final Pattern SAFE_PROVIDER_ID = Pattern.compile("[a-zA-Z0-9_.-]+");

    private static final Logger LOGGER = Logger.getLogger(ProviderConcurrencyGates.class.getName());
    private static final String HASH_PREFIX = "~sha256-";

    private final CodingAgentConfiguration configuration;
    private final Map<String, SharedGate> gates = new HashMap<>();
    private final ReentrantLock lifecycle = new ReentrantLock();
    private boolean closed;

    public ProviderConcurrencyGates(final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
    }

    /**
     * @param provider the provider, may be {@code null} (falls back to the default id).
     * @param bound requested capacity; nonpositive means unbounded.
     * @return the shared gate, never {@code null}.
     * @throws IllegalStateException when the registry has been closed.
     */
    public ConcurrencyGate gate(final ProviderConfiguration provider, final int bound) {
        lifecycle.lock();
        try {
            if (closed) {
                throw new IllegalStateException("provider concurrency gates are closed");
            }
            final var id =
                    provider == null || provider.id() == null || provider.id().isBlank() ? "default" : provider.id();
            final var capacity = normalize(bound);
            final var fileLock = useFileLock(provider);
            final var existing = gates.get(id);
            if (existing != null) {
                existing.reconcile(id, capacity, fileLock);
                return existing;
            }
            final ConcurrencyGate delegate = fileLock
                    ? new FileLeaseConcurrencyGate(
                            Path.of(configuration.resolveStorageHome(), "llm-concurrency", directoryKey(id)), capacity)
                    : new InMemoryGate(capacity);
            final var gate = new SharedGate(delegate, capacity, fileLock);
            gates.put(id, gate);
            return gate;
        } finally {
            lifecycle.unlock();
        }
    }

    @Override
    @Destroy
    public void close() {
        lifecycle.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            gates.values().forEach(gate -> {
                try {
                    gate.close();
                } catch (final Exception e) {
                    LOGGER.log(Level.FINE, e, () -> "cannot close a provider concurrency gate");
                }
            });
            gates.clear();
        } finally {
            lifecycle.unlock();
        }
    }

    private boolean useFileLock(final ProviderConfiguration provider) {
        final var effectiveMax = provider == null || provider.maxConcurrent() <= 0
                ? configuration.llm().maxConcurrent()
                : provider.maxConcurrent();
        return provider != null
                && effectiveMax > 0
                && provider.capabilities() != null
                && provider.capabilities().concurrency() != null
                && provider.capabilities().concurrency().useFileLock();
    }

    /** Safe ids retain their directory; all other ids use a disjoint, bounded SHA-256 namespace. */
    private static String directoryKey(final String id) {
        if (id.length() <= 120 && SAFE_PROVIDER_ID.matcher(id).matches() && !id.equals(".") && !id.equals("..")) {
            return id;
        }
        try {
            return HASH_PREFIX
                    + HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for provider directory keys", e);
        }
    }

    private static int normalize(final int capacity) {
        return capacity <= 0 ? ConcurrencyGate.UNBOUNDED : capacity;
    }

    private static int minimum(final int left, final int right) {
        return left == ConcurrencyGate.UNBOUNDED
                ? right
                : right == ConcurrencyGate.UNBOUNDED ? left : Math.min(left, right);
    }

    /** Serializes policy changes, including learning, so lookups never undo an adaptation. */
    private static final class SharedGate implements ConcurrencyGate {
        private final ConcurrencyGate delegate;
        private final boolean fileLock;
        private final int initialCapacity;
        private final ReentrantLock policy = new ReentrantLock();
        private int ceiling;
        private int current;
        private boolean modeWarned;
        private boolean capacityWarned;

        private SharedGate(final ConcurrencyGate delegate, final int capacity, final boolean fileLock) {
            this.delegate = delegate;
            this.fileLock = fileLock;
            this.initialCapacity = capacity;
            this.ceiling = capacity;
            // A file gate can already have loaded a persisted learned narrowing.
            this.current = delegate instanceof FileLeaseConcurrencyGate file ? file.capacity() : capacity;
        }

        private void reconcile(final String id, final int capacity, final boolean requestedFileLock) {
            policy.lock();
            try {
                if (fileLock != requestedFileLock && !modeWarned) {
                    modeWarned = true;
                    LOGGER.warning(() -> "Concurrency gate mode mismatch for provider '" + id
                            + "': retaining " + (fileLock ? "file leases" : "in-memory")
                            + "; use consistent useFileLock and configured bounds for this id");
                }
                if (initialCapacity != capacity && !capacityWarned) {
                    capacityWarned = true;
                    LOGGER.warning(() -> "Concurrency gate capacity mismatch for provider '" + id
                            + "': initial=" + initialCapacity + ", requested=" + capacity
                            + "; retaining the minimum positive requested bound without widening learned limits");
                }
                ceiling = minimum(ceiling, capacity);
                final var narrowed = minimum(current, ceiling);
                if (narrowed != current) {
                    delegate.setCapacity(narrowed);
                    current = narrowed;
                }
            } finally {
                policy.unlock();
            }
        }

        @Override
        public CompletableFuture<Runnable> acquire(final long timeoutMs, final BooleanSupplier cancelled) {
            return delegate.acquire(timeoutMs, cancelled);
        }

        @Override
        public Runnable tryAcquire() {
            return delegate.tryAcquire();
        }

        @Override
        public boolean isSaturated() {
            return delegate.isSaturated();
        }

        @Override
        public void setCapacity(final int capacity) {
            update(capacity, false);
        }

        @Override
        public void learnCapacity(final int capacity) {
            update(capacity, true);
        }

        private void update(final int capacity, final boolean learned) {
            policy.lock();
            try {
                final var requested = minimum(normalize(capacity), ceiling);
                final var bounded = learned ? minimum(current, requested) : requested;
                if (learned) {
                    delegate.learnCapacity(bounded);
                } else {
                    delegate.setCapacity(bounded);
                }
                current = bounded;
            } finally {
                policy.unlock();
            }
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
