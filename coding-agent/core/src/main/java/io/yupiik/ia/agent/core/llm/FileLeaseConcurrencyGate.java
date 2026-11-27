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

import io.yupiik.llm.client.spi.ConcurrencyGate;
import io.yupiik.llm.client.spi.ConcurrencySlotTimeoutException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Concurrency gate sharing a provider budget across the {@code yca} processes of one machine.
 *
 * <p>An account-wide {@code 429} ({@code "Too many concurrent requests (limit: 2)"}) applies to every
 * session, not to one JVM: a purely in-memory gate multiplies the budget by the number of running
 * processes (two sessions at {@code maxConcurrent = 2} issue four concurrent requests) and the
 * provider rejects the excess. This gate keeps one lease file per slot, under
 * {@code <storageHome>/llm-concurrency/<providerId>/slot-<n>.lock}, and takes an exclusive
 * {@link FileLock} on a slot for the duration of the request: the OS makes the lease exclusive across
 * processes and - crucially - drops it when its holder dies, so there is no stale-slot recovery, no
 * pid bookkeeping and no time-to-live to tune.</p>
 *
 * <h2>Why not a POSIX semaphore</h2>
 * <p>A named semaphore ({@code sem_open}, reachable through the FFM API) is the textbook primitive
 * here, but it is not crash-resilient: a process killed while holding a count leaves the semaphore
 * permanently decremented - the kernel has no owner to give the token back to - which bricks the
 * budget until an operator removes it. Record locks are released by the kernel on process death, so
 * the simpler file-lease implementation is also the more robust one. That trade-off is deliberate:
 * leases cost a few {@code open}/{@code tryLock} syscalls per request, which is noise next to an LLM
 * round-trip.</p>
 *
 * <h2>Platform requirements and degradation</h2>
 * <p>Record locks are advisory and reliable on a local POSIX filesystem; on NFS/CIFS they may be
 * ignored or coalesced by the client. Since the storage home can be network-mounted, every filesystem
 * failure degrades this gate to a per-process in-memory budget <em>once and for good</em> instead of
 * failing requests: the cross-process protection is lost, the sessions keep working. The degradation
 * is surfaced through {@link #degraded()} and {@link #degradedReason()} (the first failure is logged
 * as a warning) because silently replacing an account-wide budget with a per-process one is exactly
 * the situation which produces a provider {@code 429}.</p>
 *
 * <h2>Implementation notes</h2>
 * <ul>
 * <li>{@link FileLock} is a per-JVM resource: two gates of the same process targeting the same slot
 *     raise {@link OverlappingFileLockException}. A static per-canonical-path guard is therefore taken
 *     before opening the channel, and slot files are never deleted - a recreated inode would be a
 *     different lock domain, so the new file would look free while an old lease is still held.</li>
 * <li>{@link #acquire(long, BooleanSupplier)} blocks the calling thread, like the per-minute throttle
 *     of the resilience wrapper already does: the caller which gets the lease is the one which issues
 *     the request, so no request ever runs on a gate-owned thread (a slow request cannot stall the
 *     other waiters) and there is no poller thread, no waiter deque and no late-grant race to reason
 *     about. A contended attempt retries the leases with a jittered backoff, and a slot freed by a peer
 *     is picked up on the next attempt.</li>
 * <li>a lease is taken with a {@link Semaphore} ({@link java.util.concurrent.locks.ReentrantLock}
 *     would refuse a release from another thread, and a stream releases its slot from the subscriber
 *     thread).</li>
 * <li>the capacity a provider announced is persisted in a sibling {@code capacity} file so a restart
 *     does not have to burn a {@code 429} to rediscover a smaller limit; the file expires after a
 *     time-to-live so a transient provider hiccup does not cap the budget forever. Only
 *     {@link #learnCapacity(int)} writes it: a purely local relaxation ({@link #setCapacity(int)})
 *     must not erase what a peer process learned.</li>
 * </ul>
 */
public class FileLeaseConcurrencyGate implements ConcurrencyGate {
    private static final Logger LOGGER = Logger.getLogger(FileLeaseConcurrencyGate.class.getName());

    /**
     * Guards the slot files of the same path inside this JVM: {@link FileLock} is process-scoped.
     * A semaphore, not a {@link java.util.concurrent.locks.ReentrantLock}: a lease is released from
     * whichever thread ends the request, and an owner-bound lock would then be unlocked by a thread
     * which does not own it.
     */
    private static final ConcurrentHashMap<Path, Semaphore> JVM_GUARDS = new ConcurrentHashMap<>();

    /** How long a persisted capacity stays authoritative before it is ignored as stale. */
    private static final long CAPACITY_TTL_MILLIS = TimeUnit.HOURS.toMillis(24);

    /** First and last re-attempt delays when a slot is leased by a peer. */
    private static final long MIN_POLL_MS = 10L;

    private static final long MAX_POLL_MS = 200L;

    /** Cadence at which a parked caller re-checks its deadline and the cancellation probe. */
    private static final long WAIT_SLICE_MS = 50L;

    private static final Runnable NOOP = () -> {};

    private final Path directory;
    /** Leases handed out and not returned yet: released by {@link #close()} so a dir is not locked out. */
    private final List<Runnable> outstanding = new ArrayList<>();

    /** Admission state: the in-flight count, the effective capacity and the closed flag. */
    private final Object slots = new Object();

    private int capacity;
    private int inFlight;
    private boolean closed;
    private final AtomicReference<String> degradedReason = new AtomicReference<>();

    /**
     * Creates a gate.
     *
     * @param directory the provider lease directory, e.g.
     *     {@code <storageHome>/llm-concurrency/openai}; created on the first acquire.
     * @param capacity the configured slot count, clamped to {@code >= 1}.
     */
    public FileLeaseConcurrencyGate(final Path directory, final int capacity) {
        this.directory = canonical(directory);
        final var configured = Math.max(1, capacity);
        final var persisted = readPersistedCapacity();
        this.capacity = persisted == null ? configured : Math.min(configured, persisted);
    }

    /** The provider lease directory, mostly for diagnostics. */
    public Path directory() {
        return directory;
    }

    /** Whether filesystem access failed and this gate now only bounds this process. */
    public boolean degraded() {
        return degradedReason.get() != null;
    }

    /**
     * Why the cross-process budget was abandoned, when it was.
     *
     * @return a human readable diagnosis, or {@code null} while the file leases are in use.
     */
    public String degradedReason() {
        return degradedReason.get();
    }

    /** The capacity currently applied (the configured one, narrowed by a provider-announced limit). */
    public int capacity() {
        synchronized (slots) {
            return capacity;
        }
    }

    @Override
    public CompletableFuture<Runnable> acquire(final long timeoutMs, final BooleanSupplier cancelled) {
        final var deadline = timeoutMs <= 0
                ? Long.MAX_VALUE
                : saturatedAdd(System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(timeoutMs));
        var backoff = MIN_POLL_MS;
        while (true) {
            synchronized (slots) {
                if (closed) {
                    return failed(new IOException("concurrency gate is closed"));
                }
                if (cancelled != null && cancelled.getAsBoolean()) {
                    return failed(new IOException("Cancelled"));
                }
                if (remainingMs(deadline) <= 0) {
                    return failed(slotTimeout(timeoutMs));
                }
                if (capacity == ConcurrencyGate.UNBOUNDED) {
                    // no bound configured: nothing to lease, nothing to return
                    return CompletableFuture.completedFuture(NOOP);
                }
                if (inFlight < capacity) {
                    // reserve the slot before the I/O: the attempt runs outside the monitor, and a
                    // failed attempt releases the reservation
                    inFlight++;
                } else {
                    try {
                        TimeUnit.MILLISECONDS.timedWait(slots, sliceMs(remainingMs(deadline)));
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return failed(new InterruptedIOException("interrupted while waiting for a lease"));
                    }
                    continue;
                }
            }
            final Runnable lease;
            try {
                lease = lease();
            } catch (final RuntimeException e) {
                // release the reservation, then re-check the loop conditions (the attempt may well
                // have degraded the gate): no lease was taken, so nothing else to undo
                synchronized (slots) {
                    if (inFlight > 0) {
                        inFlight--;
                    }
                    slots.notifyAll();
                }
                throw e;
            }
            if (lease != null) {
                final var tracked = track(lease);
                if (tracked != null
                        && ((cancelled != null && cancelled.getAsBoolean()) || remainingMs(deadline) <= 0)) {
                    tracked.run();
                    return failed(
                            cancelled != null && cancelled.getAsBoolean()
                                    ? new IOException("Cancelled")
                                    : slotTimeout(timeoutMs));
                }
                return tracked == null
                        ? failed(new IOException("concurrency gate is closed"))
                        : CompletableFuture.completedFuture(tracked);
            }
            synchronized (slots) {
                if (inFlight > 0) {
                    inFlight--;
                }
                slots.notifyAll();
            }
            // every slot is leased by a peer: back off a little (jittered, so a pool of waiters does
            // not hammer the same files in lockstep) and re-attempt
            try {
                Thread.sleep(Math.min(jitter(backoff), sliceMs(remainingMs(deadline))));
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return failed(new InterruptedIOException("interrupted while waiting for a lease"));
            }
            backoff = Math.min(MAX_POLL_MS, backoff * 2);
        }
    }

    @Override
    public Runnable tryAcquire() {
        synchronized (slots) {
            if (closed) {
                return null;
            }
            if (capacity == ConcurrencyGate.UNBOUNDED) {
                return NOOP;
            }
            if (inFlight >= capacity) {
                return null;
            }
            inFlight++;
        }
        final Runnable lease;
        try {
            lease = lease();
        } catch (final RuntimeException e) {
            releaseReservation();
            throw e;
        }
        if (lease == null) {
            releaseReservation(); // every slot is held by a peer: the caller falls back to waiting
            return null;
        }
        return track(lease);
    }

    private void releaseReservation() {
        synchronized (slots) {
            if (inFlight > 0) {
                inFlight--;
            }
            slots.notifyAll();
        }
    }

    @Override
    public void setCapacity(final int capacity) {
        final var bound = normalize(capacity);
        synchronized (slots) {
            this.capacity = bound;
            // a shrink only applies to future leases (a stream can run for minutes and is never
            // preempted, so the outstanding leases are absorbed as they come back); a growth serves
            // the parked waiters immediately
            slots.notifyAll();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation persists the announced value for its peers and for the next restarts,
     * since it is a fact about the account rather than a local policy.</p>
     */
    @Override
    public void learnCapacity(final int capacity) {
        final var bound = normalize(capacity);
        final int learned;
        synchronized (slots) {
            learned = this.capacity <= 0 ? bound : bound <= 0 ? this.capacity : Math.min(this.capacity, bound);
            this.capacity = learned;
            slots.notifyAll();
        }
        persistCapacity(learned);
    }

    @Override
    public boolean isSaturated() {
        synchronized (slots) {
            return capacity > 0 && inFlight >= capacity;
        }
    }

    @Override
    public void close() {
        final List<Runnable> toRelease;
        synchronized (slots) {
            if (closed) {
                return;
            }
            closed = true;
            toRelease = List.copyOf(outstanding);
            outstanding.clear();
            slots.notifyAll();
        }
        // returning the outstanding leases matters beyond politeness: the in-JVM guard and the record
        // lock would otherwise keep the slot files of this directory unusable for any other gate of
        // the process
        toRelease.forEach(Runnable::run);
    }

    /** Registers a lease so {@link #close()} can return it, and wires the release bookkeeping. */
    private Runnable track(final Runnable lease) {
        final Runnable release = new Runnable() {
            private final AtomicBoolean released = new AtomicBoolean();

            @Override
            public void run() {
                if (released.compareAndSet(false, true)) {
                    try {
                        lease.run();
                    } finally {
                        synchronized (slots) {
                            inFlight--;
                            outstanding.remove(this);
                            slots.notifyAll();
                        }
                    }
                }
            }
        };
        synchronized (slots) {
            if (closed) {
                release.run();
                return null;
            }
            outstanding.add(release);
        }
        return release;
    }

    private IOException slotTimeout(final long timeoutMs) {
        return new ConcurrencySlotTimeoutException(
                "no concurrency lease available within " + timeoutMs + "ms (capacity " + capacity + ")");
    }

    /**
     * Tries every slot in turn and returns the release action of the first free one.
     *
     * @return the release action, or {@code null} when all the slots are leased.
     */
    private Runnable lease() {
        if (degraded()) {
            return NOOP; // the same reservation count also accounts for existing file leases
        }
        final List<Path> slotsToTry;
        final int bound;
        synchronized (slots) {
            bound = capacity;
        }
        try {
            Files.createDirectories(directory);
            slotsToTry = ensureSlots(bound);
        } catch (final IOException e) {
            degrade(e);
            return NOOP;
        }
        for (final var slot : slotsToTry) {
            final var guard = JVM_GUARDS.computeIfAbsent(slot, path -> new Semaphore(1));
            if (!guard.tryAcquire()) {
                continue; // another gate of this JVM already leases it: FileLock would overlap
            }
            FileChannel channel = null;
            try {
                channel = FileChannel.open(slot, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                final FileLock lock = channel.tryLock();
                if (lock == null) {
                    channel.close();
                    guard.release();
                    continue; // leased by a peer process
                }
                final var state = new LeaseState(channel, lock, guard);
                return state;
            } catch (final OverlappingFileLockException e) {
                closeQuietly(channel);
                guard.release();
                LOGGER.log(Level.FINE, e, () -> "overlapping lease on " + slot + ", retrying another slot");
            } catch (final IOException e) {
                closeQuietly(channel);
                guard.release();
                degrade(e);
                return NOOP;
            } catch (final RuntimeException e) {
                closeQuietly(channel);
                guard.release();
                throw e;
            }
        }
        return null;
    }

    /**
     * Lists the slot files, creating the missing ones. The list is sorted so the scan order is stable
     * and fair across processes; slots are never deleted (see the class javadoc).
     */
    private List<Path> ensureSlots(final int bound) throws IOException {
        final var slots = new ArrayList<Path>(bound);
        for (var i = 0; i < bound; i++) {
            final var slot = directory.resolve("slot-" + i + ".lock");
            if (!Files.exists(slot)) {
                Files.write(slot, new byte[0], StandardOpenOption.CREATE);
            }
            slots.add(slot);
        }
        return slots;
    }

    private Integer readPersistedCapacity() {
        final var file = directory.resolve("capacity");
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            final var age =
                    System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis();
            if (age > CAPACITY_TTL_MILLIS) {
                return null; // stale: a provider hiccup must not cap the budget forever
            }
            final var value = Files.readString(file, StandardCharsets.UTF_8).trim();
            final var parsed = value.isEmpty() ? 0 : Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (final IOException | NumberFormatException e) {
            LOGGER.log(Level.FINE, e, () -> "ignoring unreadable concurrency capacity file " + file);
            return null;
        }
    }

    private void persistCapacity(final int value) {
        if (value <= 0) {
            return;
        }
        final var guard = JVM_GUARDS.computeIfAbsent(directory.resolve("capacity.lock"), path -> new Semaphore(1));
        guard.acquireUninterruptibly();
        try {
            Files.createDirectories(directory);
            try (final var channel = FileChannel.open(
                            directory.resolve("capacity.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    final var lock = channel.lock()) {
                final var previous = readPersistedCapacity();
                final var narrowed = previous == null ? value : Math.min(value, previous);
                final var temporary = Files.createTempFile(directory, "capacity-", ".tmp");
                try {
                    Files.writeString(temporary, Integer.toString(narrowed), StandardCharsets.UTF_8);
                    Files.move(
                            temporary,
                            directory.resolve("capacity"),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
        } catch (final IOException e) {
            LOGGER.log(Level.FINE, e, () -> "cannot persist concurrency capacity of " + directory);
        } finally {
            guard.release();
        }
    }

    /** Degrades to an in-memory budget after a filesystem failure, once and for good. */
    private void degrade(final IOException error) {
        if (degradedReason.compareAndSet(null, String.valueOf(error))) {
            LOGGER.log(
                    Level.WARNING,
                    error,
                    () -> "file leases unavailable under " + directory
                            + ", falling back to a per-process budget (the cross-process limit is no longer enforced)");
            synchronized (slots) {
                slots.notifyAll();
            }
        }
    }

    private static void closeQuietly(final FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (final IOException e) {
                LOGGER.log(Level.FINEST, e, () -> "cannot close a concurrency slot channel");
            }
        }
    }

    /** {@link ConcurrencyGate#UNBOUNDED} stays unbounded, anything else is at least one slot. */
    private static int normalize(final int capacity) {
        return capacity <= 0 ? ConcurrencyGate.UNBOUNDED : capacity;
    }

    private static long remainingMs(final long deadline) {
        return deadline == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
    }

    private static long sliceMs(final long remainingMs) {
        return Math.max(1L, Math.min(remainingMs, WAIT_SLICE_MS));
    }

    private static long jitter(final long backoff) {
        return ThreadLocalRandom.current().nextLong(backoff / 2, backoff + 1);
    }

    private static long saturatedAdd(final long left, final long right) {
        final var sum = left + right;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    private static <A> CompletableFuture<A> failed(final Throwable error) {
        final var future = new CompletableFuture<A>();
        future.completeExceptionally(error);
        return future;
    }

    /** Canonicalises the lease directory so the in-JVM guard is keyed on the real path. */
    private static Path canonical(final Path directory) {
        final var absolute = directory.toAbsolutePath().normalize();
        try {
            var ancestor = absolute;
            while (ancestor != null && !Files.exists(ancestor)) {
                ancestor = ancestor.getParent();
            }
            return ancestor == null ? absolute : ancestor.toRealPath().resolve(ancestor.relativize(absolute));
        } catch (final IOException e) {
            // the directory is created on the first acquire: the normalized path is the best key
            // available, and a symlinked storage home is only a problem for two gates created before
            // that directory exists
            LOGGER.log(Level.FINE, e, () -> "cannot canonicalize " + absolute + ", using it as is");
            return absolute;
        }
    }

    /** Strongly held lease resources, owned by the gate and released explicitly. */
    private static final class LeaseState implements Runnable {
        private final FileChannel channel;
        private final FileLock lock;
        private final Semaphore guard;
        private final AtomicBoolean released = new AtomicBoolean();

        private LeaseState(final FileChannel channel, final FileLock lock, final Semaphore guard) {
            this.channel = channel;
            this.lock = lock;
            this.guard = guard;
        }

        @Override
        public void run() {
            if (released.compareAndSet(false, true)) {
                try {
                    if (lock.isValid()) {
                        lock.release();
                    }
                } catch (final IOException e) {
                    LOGGER.log(Level.FINEST, e, () -> "cannot release a concurrency lease");
                } finally {
                    try {
                        channel.close();
                    } catch (final IOException e) {
                        LOGGER.log(Level.FINEST, e, () -> "cannot close a concurrency slot channel");
                    } finally {
                        // closing the channel may drop EVERY record lock of the file in this process,
                        // which is why the JVM guard is held for the whole lease
                        guard.release();
                    }
                }
            }
        }
    }
}
