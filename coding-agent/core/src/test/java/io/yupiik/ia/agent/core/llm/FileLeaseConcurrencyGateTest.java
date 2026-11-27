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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.llm.client.spi.ConcurrencySlotTimeoutException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Covers the cross-process lease semantics: exclusion, peer contention, persistence and lifecycle. */
@Timeout(30)
class FileLeaseConcurrencyGateTest {
    @Test
    void deliversItsCapacityThenQueues(@TempDir final Path home) {
        try (final var gate = new FileLeaseConcurrencyGate(home.resolve("openai"), 2)) {
            final var first = gate.tryAcquire();
            final var second = gate.tryAcquire();
            assertNotNull(first, "a free slot must be granted without waiting");
            assertNotNull(second);
            assertNull(gate.tryAcquire(), "every slot is taken");

            first.run();
            assertTrue(
                    Files.exists(home.resolve("openai").resolve("slot-0.lock")),
                    "the slot files are created on the first acquire");
            assertNotNull(gate.tryAcquire(), "the released lease is available again");
        }
    }

    @Test
    void twoGatesOfTheSameJvmShareTheSlots(@TempDir final Path home) {
        // FileLock is process-scoped: two gates on one directory must not overlap, which is what the
        // static JVM guard enforces (without it, the second tryLock would raise an exception)
        try (final var first = new FileLeaseConcurrencyGate(home.resolve("openai"), 1);
                final var second = new FileLeaseConcurrencyGate(home.resolve("openai"), 1)) {
            final var lease = first.tryAcquire();
            assertNotNull(lease, "the first gate takes the only slot");
            assertNull(second.tryAcquire(), "the same slot cannot be leased twice in one process");

            lease.run();
            final var granted = second.acquire(10_000L, null);
            assertTrue(granted.isDone(), "the freed slot is served");
            granted.join().run();
        }
    }

    @Test
    void aPeerProcessHoldingTheSlotBlocksTheGate(@TempDir final Path home) throws Exception {
        final var dir = home.resolve("openai");
        Files.createDirectories(dir);
        final var slot = dir.resolve("slot-0.lock");
        Files.createFile(slot);

        try (final var peer = FileChannel.open(slot, StandardOpenOption.WRITE);
                final FileLock peerLock = peer.tryLock()) {
            assertNotNull(peerLock, "the peer must get the lock first");

            try (final var gate = new FileLeaseConcurrencyGate(dir, 1)) {
                assertNull(gate.tryAcquire(), "the slot is held by a peer process");
                // a bounded wait gives up instead of hanging forever on a stalled peer
                final var failure = assertThrows(
                        ExecutionException.class, () -> gate.acquire(100L, null).get(10, TimeUnit.SECONDS));
                assertInstanceOf(ConcurrencySlotTimeoutException.class, failure.getCause());
            }
        }
        // a fresh gate sees the freed slot again
        try (final var gate = new FileLeaseConcurrencyGate(dir, 1)) {
            final var lease = gate.tryAcquire();
            assertNotNull(lease, "the lease of a dead peer must not be sticky");
            lease.run();
        }
    }

    @Test
    void aCrashedPeerProcessReturnsItsLease(@TempDir final Path home) throws Exception {
        final var slot = home.resolve("slot-0.lock");
        Files.createFile(slot);
        final var peer = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        Peer.class.getName(),
                        slot.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        try {
            try (final var output = peer.inputReader();
                    final var gate = new FileLeaseConcurrencyGate(home, 1)) {
                assertEquals("locked", output.readLine());
                assertNull(gate.tryAcquire(), "a different JVM owns the OS lock");
                peer.destroyForcibly();
                assertTrue(peer.waitFor(5, TimeUnit.SECONDS));
                gate.acquire(5_000, null).join().run();
            }
        } finally {
            peer.destroyForcibly();
            peer.waitFor(5, TimeUnit.SECONDS);
        }
    }

    public static class Peer {
        public static void main(final String[] args) throws Exception {
            try (final var channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE);
                    final var lock = channel.lock()) {
                if (!lock.isValid()) {
                    throw new IOException("peer lock is invalid");
                }
                System.out.println("locked");
                System.out.flush();
                System.in.read();
            }
        }
    }

    @Test
    void persistedCapacityIsHonouredUntilItExpires(@TempDir final Path home) throws Exception {
        final var dir = home.resolve("freeinference");

        try (final var gate = new FileLeaseConcurrencyGate(dir, 4)) {
            gate.learnCapacity(2);
            assertNotNull(gate.tryAcquire());
            assertNotNull(gate.tryAcquire());
            assertNull(gate.tryAcquire(), "the narrowed capacity is 2");
        }
        assertTrue(Files.exists(dir.resolve("capacity")), "the learned capacity must be persisted");
        assertEquals("2", Files.readString(dir.resolve("capacity")).trim());

        // a fresh gate (a new process) starts narrowed instead of burning a 429 to rediscover it
        try (final var gate = new FileLeaseConcurrencyGate(dir, 4)) {
            assertNotNull(gate.tryAcquire());
            assertNotNull(gate.tryAcquire());
            assertNull(gate.tryAcquire(), "the persisted capacity must be reused");
        }

        // and a stale file is ignored
        Files.setLastModifiedTime(
                dir.resolve("capacity"), FileTime.fromMillis(System.currentTimeMillis() - 7 * 24 * 3600_000L));
        try (final var gate = new FileLeaseConcurrencyGate(dir, 4)) {
            for (var i = 0; i < 4; i++) {
                assertNotNull(gate.tryAcquire(), "a stale capacity must be ignored");
            }
            assertNull(gate.tryAcquire());
        }
    }

    @Test
    void aLocalRelaxationDoesNotEraseWhatAPeerLearned(@TempDir final Path home) throws Exception {
        final var dir = home.resolve("openai");
        try (final var gate = new FileLeaseConcurrencyGate(dir, 3)) {
            gate.learnCapacity(1);
            assertTrue(Files.exists(dir.resolve("capacity")), "a learned limit is persisted for the peers");

            // relaxing to the configured ceiling is a LOCAL policy: it must not delete the file a peer
            // (or a restart) relies on, otherwise a learned account limit is forgotten
            gate.setCapacity(3);
            assertTrue(
                    Files.exists(dir.resolve("capacity")),
                    "a local relaxation must not erase the peer-persisted narrowing");
            assertEquals("1", Files.readString(dir.resolve("capacity")).trim());
        }
    }

    @Test
    void aTimedOutWaiterLeavesNoLeakedSlot(@TempDir final Path home) {
        try (final var gate = new FileLeaseConcurrencyGate(home.resolve("openai"), 1)) {
            final var held = gate.tryAcquire();
            assertNotNull(held, "the only lease is taken");

            // a bounded wait on a fully leased gate gives up, with its own error type
            final var failure = assertThrows(
                    ExecutionException.class, () -> gate.acquire(50L, null).get(10, TimeUnit.SECONDS));
            assertInstanceOf(ConcurrencySlotTimeoutException.class, failure.getCause());

            held.run();
            final var next = gate.acquire(5_000L, null);
            assertTrue(next.isDone(), "the timed-out waiter must not have consumed the released slot");
            next.join().run();
        }
    }

    @Test
    void aCancelledWaiterLeavesNoLeakedSlot(@TempDir final Path home) {
        try (final var gate = new FileLeaseConcurrencyGate(home.resolve("openai"), 1)) {
            final var held = gate.tryAcquire();
            assertNotNull(held);

            final var cancelled = new AtomicBoolean(true); // already abandoned
            final var failure = assertThrows(
                    ExecutionException.class,
                    () -> gate.acquire(0L, cancelled::get).get(10, TimeUnit.SECONDS));
            assertEquals("Cancelled", failure.getCause().getMessage());

            held.run();
            final var next = gate.acquire(5_000L, null);
            assertTrue(next.isDone(), "the cancelled waiter must not have consumed the released slot");
            next.join().run();
        }
    }

    @Test
    void closeRefusesNewAdmissionsAndReturnsTheOutstandingLease(@TempDir final Path home) {
        final var gate = new FileLeaseConcurrencyGate(home.resolve("openai"), 1);
        gate.tryAcquire(); // holds the only slot

        gate.close();

        // a closed gate is a transient failure, not a programming error: the caller may retry elsewhere
        final var failure = assertThrows(
                ExecutionException.class, () -> gate.acquire(100L, null).get(10, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, failure.getCause(), "got: " + failure.getCause());

        // and the lease was returned, so the slot files are usable by another gate of the process
        try (final var other = new FileLeaseConcurrencyGate(home.resolve("openai"), 1)) {
            final var lease = other.tryAcquire();
            assertNotNull(lease, "close() must hand the outstanding leases back");
            lease.run();
        }
    }

    @Test
    void unusableDirectoryDegradesToAnInMemoryBudget(@TempDir final Path home) throws Exception {
        // a regular file where the lease directory should be: every filesystem operation fails, the
        // gate must keep serving requests instead of failing them
        final var path = home.resolve("blocked");
        Files.writeString(path, "not a directory");

        try (final var gate = new FileLeaseConcurrencyGate(path.resolve("openai"), 2)) {
            final var first = gate.acquire(1_000L, null);
            assertTrue(first.isDone(), "the degraded gate serves the request");
            assertTrue(gate.degraded(), "the degradation must be observable, not silent");
            assertNotNull(gate.degradedReason());
            first.join().run();

            // and the degraded budget is still bounded by the configured capacity (2)
            assertNotNull(gate.tryAcquire());
            assertNotNull(gate.tryAcquire());
            assertNull(gate.tryAcquire(), "the degraded budget must keep the configured capacity");
        }
    }

    @Test
    void degradationCountsAlreadyHeldFileLeases(@TempDir final Path home) throws Exception {
        final var dir = home.resolve("provider");
        try (final var gate = new FileLeaseConcurrencyGate(dir, 2)) {
            final var first = gate.tryAcquire();
            assertNotNull(first);
            Files.delete(dir.resolve("slot-1.lock"));
            Files.createDirectory(dir.resolve("slot-1.lock"));
            final var second = gate.tryAcquire();
            assertNotNull(second);
            assertTrue(gate.degraded());
            assertNull(gate.tryAcquire(), "a file lease still consumes the degraded budget");
            final var failure = assertThrows(
                    ExecutionException.class, () -> gate.acquire(50, null).get());
            assertInstanceOf(ConcurrencySlotTimeoutException.class, failure.getCause());
            first.run();
            first.run();
            final var replacement = gate.tryAcquire();
            assertNotNull(replacement);
            assertNull(gate.tryAcquire(), "idempotent release must not inflate capacity");
            second.run();
            replacement.run();
        }
    }

    @Test
    void aPeerLearningALargerLimitDoesNotEraseTheSmallerPersistedLimit(@TempDir final Path home) throws Exception {
        try (final var first = new FileLeaseConcurrencyGate(home, 4);
                final var second = new FileLeaseConcurrencyGate(home, 4)) {
            first.learnCapacity(1);
            second.learnCapacity(3);
            first.learnCapacity(4);
            assertEquals("1", Files.readString(home.resolve("capacity")));
        }
        try (final var restarted = new FileLeaseConcurrencyGate(home, 4)) {
            assertEquals(1, restarted.capacity());
        }
    }
}
