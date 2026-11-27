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

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import io.yupiik.ia.agent.core.configuration.ProviderBudgetConfiguration;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Non-queuing provider admission on a trusted, POSIX shared filesystem with coherent FileChannel
 * locks and durable force operations. All participants for an account must use the same ROOT, ID
 * and SLOTS. These are operator settings, never request/agent input. ROOT must be absolute; its
 * parent must already exist on the trusted gateway mount. The private root is created if missing.
 * Ancestors must not be symlinks; protecting the mount/parent against renames is the operator's duty.
 * All participating processes must run as the same filesystem owner.
 *
 * <p>Stable ledger files are never removed or replaced. Reservations are forced before returning,
 * including reservations whose process crashes before dispatch. There is deliberately no automatic
 * recovery, PID/age expiry, or lease stealing. Operators must reconcile unknown upstream work while
 * all participants are stopped before repairing debt; deleting an active ledger breaks the budget.
 * Corrupt/partial ledgers and mismatched slot counts fail closed. This is not a distributed service
 * for filesystems without coherent advisory locks, nor protection against a malicious same-UID process.
 */
public final class ProviderRequestBudget {
    private static final Pattern BUDGET_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern BUDGET_COUNT = Pattern.compile("[1-9][0-9]{0,4}");

    public static final String ROOT = "YCA_PROVIDER_BUDGET_ROOT";
    public static final String ID = "YCA_PROVIDER_BUDGET_ID";
    public static final String SLOTS = "YCA_PROVIDER_BUDGET_SLOTS";
    private static final int MAGIC = 0x59434231; // YCB1, schema version 1
    private static final int HEADER = 8;
    // Never evict: another instance/thread may still be opening this stable inode.
    private static final Map<BudgetIdentity, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private record BudgetIdentity(Object rootKey, String document) {}

    private final Path root;
    private final Path ledger;
    private final int slots;
    private final Object rootKey;
    private final Object ledgerKey;
    private final ReentrantLock lock;

    private ProviderRequestBudget(
            final Path root, final Path ledger, final int slots, final Object rootKey, final Object ledgerKey) {
        this.root = root;
        this.ledger = ledger;
        this.slots = slots;
        this.rootKey = rootKey;
        this.ledgerKey = ledgerKey;
        this.lock = ledger == null
                ? null
                : LOCKS.computeIfAbsent(
                        new BudgetIdentity(rootKey, ledger.getFileName().toString()), ignored -> new ReentrantLock());
    }

    /** Absent/blank ROOT disables only when ID and SLOTS are absent. Partial settings are errors. */
    public static ProviderRequestBudget fromConfiguration(final ProviderBudgetConfiguration configuration)
            throws IOException {
        final var value = configuration.root();
        if (value == null || value.isBlank()) {
            if (configuration.id() != null || configuration.slots() != null) {
                throw new IllegalArgumentException("Incomplete provider budget settings");
            }
            return new ProviderRequestBudget(null, null, 0, null, null);
        }
        final var id = configuration.id();
        final var count = configuration.slots();
        if (id == null
                || !BUDGET_ID.matcher(id).matches()
                || count == null
                || !BUDGET_COUNT.matcher(count).matches()) {
            throw new IllegalArgumentException("Invalid provider budget settings");
        }
        final int slots = Integer.parseInt(count);
        if (slots > 65536) {
            throw new IllegalArgumentException("Invalid provider budget slot count");
        }
        final var root = Path.of(value);
        if (!root.isAbsolute() || !root.equals(root.normalize())) {
            throw new IllegalArgumentException("Provider budget root must be absolute and normalized");
        }
        checkAncestors(root.getParent());
        try {
            Files.createDirectory(
                    root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            try (final var parent = FileChannel.open(root.getParent(), READ)) {
                parent.force(true);
            }
        } catch (final FileAlreadyExistsException exists) {
            // Validate rather than repair existing permissions.
        }
        final var rootKey = checkPrivate(root, true).fileKey();
        final var ledger = root.resolve(id + ".budget");
        final var local = LOCKS.computeIfAbsent(
                new BudgetIdentity(rootKey, ledger.getFileName().toString()), ignored -> new ReentrantLock());
        if (!local.tryLock()) {
            throw new IOException("Provider budget initialization busy");
        }
        try {
            boolean created = false;
            try {
                Files.createFile(
                        ledger, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                created = true;
            } catch (final FileAlreadyExistsException exists) {
                // Never truncate an existing ledger, including a partially initialized one.
            }
            final var ledgerKey = checkPrivate(ledger, false).fileKey();
            final var budget = new ProviderRequestBudget(root, ledger, slots, rootKey, ledgerKey);
            try (final var channel = FileChannel.open(ledger, READ, WRITE, NOFOLLOW_LINKS);
                    final var held = channel.tryLock()) {
                if (held == null) {
                    throw new IOException("Provider budget initialization busy");
                }
                budget.checkIdentity();
                if (created) {
                    final var bytes = ByteBuffer.allocate(HEADER + slots);
                    bytes.putInt(MAGIC).putInt(slots).position(0);
                    write(channel, bytes, 0);
                    channel.force(true);
                    try (final var directory = FileChannel.open(root, READ)) {
                        directory.force(true);
                    }
                }
                budget.read(channel);
            }
            return budget;
        } finally {
            local.unlock();
        }
    }

    public boolean enabled() {
        return ledger != null;
    }

    /** Returns immediately when busy/full; I/O or schema errors fail closed with IOException. */
    public Optional<Lease> tryAcquire() throws IOException {
        if (!enabled()) {
            return Optional.of(new Lease(null, -1));
        }
        if (!lock.tryLock()) {
            return Optional.empty();
        }
        try {
            checkIdentity();
            try (final var channel = FileChannel.open(ledger, READ, WRITE, NOFOLLOW_LINKS);
                    final var held = channel.tryLock()) {
                if (held == null) {
                    return Optional.empty();
                }
                checkIdentity();
                final var state = read(channel);
                for (int slot = 0; slot < slots; slot++) {
                    if (state.get(HEADER + slot) == 0) {
                        write(channel, ByteBuffer.wrap(new byte[] {1}), HEADER + slot);
                        channel.force(true);
                        return Optional.of(new Lease(this, slot));
                    }
                }
                return Optional.empty();
            }
        } finally {
            lock.unlock();
        }
    }

    private void release(final int slot) throws IOException {
        // Completion is bounded too. Failure to persist completion conservatively retains debt.
        final long deadline = System.nanoTime() + 100_000_000L;
        do {
            if (lock.tryLock()) {
                try {
                    checkIdentity();
                    try (final var channel = FileChannel.open(ledger, READ, WRITE, NOFOLLOW_LINKS);
                            final var held = channel.tryLock()) {
                        if (held != null) {
                            checkIdentity();
                            read(channel);
                            write(channel, ByteBuffer.wrap(new byte[] {0}), HEADER + slot);
                            channel.force(true);
                            return;
                        }
                    }
                } finally {
                    lock.unlock();
                }
            }
            LockSupport.parkNanos(100_000L);
        } while (System.nanoTime() < deadline);
        throw new IOException("Provider budget completion busy; reservation retained");
    }

    private ByteBuffer read(final FileChannel channel) throws IOException {
        if (channel.size() != HEADER + slots) {
            throw new IOException("Provider budget schema or slot count conflict");
        }
        final var bytes = ByteBuffer.allocate(HEADER + slots);
        while (bytes.hasRemaining()) {
            if (channel.read(bytes, bytes.position()) < 0) {
                throw new IOException("Incomplete provider budget ledger");
            }
        }
        bytes.flip();
        if (bytes.getInt() != MAGIC || bytes.getInt() != slots) {
            throw new IOException("Provider budget schema or slot count conflict");
        }
        while (bytes.hasRemaining()) {
            final var state = bytes.get();
            if (state != 0 && state != 1) {
                throw new IOException("Invalid provider budget reservation");
            }
        }
        return bytes;
    }

    private static void write(final FileChannel channel, final ByteBuffer bytes, final long offset) throws IOException {
        while (bytes.hasRemaining()) {
            channel.write(bytes, offset + bytes.position());
        }
    }

    private void checkIdentity() throws IOException {
        checkAncestors(root.getParent());
        if (!rootKey.equals(checkPrivate(root, true).fileKey())
                || !ledgerKey.equals(checkPrivate(ledger, false).fileKey())) {
            throw new IOException("Provider budget filesystem identity changed");
        }
    }

    private static void checkAncestors(final Path path) throws IOException {
        for (var current = path; current != null; current = current.getParent()) {
            if (!Files.isDirectory(current, NOFOLLOW_LINKS)) {
                throw new IOException("Invalid provider budget ancestor");
            }
        }
    }

    private static PosixFileAttributes checkPrivate(final Path path, final boolean directory) throws IOException {
        final var attributes = Files.readAttributes(path, PosixFileAttributes.class, NOFOLLOW_LINKS);
        // Numeric container users need not have an /etc/passwd entry or a usable user.name.
        final var owner = Files.isDirectory(Path.of("/proc/self"))
                ? Files.getOwner(Path.of("/proc/self"))
                : path.getFileSystem()
                        .getUserPrincipalLookupService()
                        .lookupPrincipalByName(System.getProperty("user.name"));
        final var permissions = PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------");
        if (!(directory ? attributes.isDirectory() : attributes.isRegularFile())
                || !attributes.owner().equals(owner)
                || !attributes.permissions().equals(permissions)
                || attributes.fileKey() == null
                || (!directory && ((Number) Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS)).intValue() != 1)) {
            throw new IOException("Provider budget requires private owner-controlled files");
        }
        return attributes;
    }

    /**
     * A single request reservation. The caller must serialize its lifecycle (normally one forwarding
     * thread). Acquisition is already durable: dispatched marks uncertainty before invoking transport.
     * completed confirms actual upstream response EOF, or independently proven no-dispatch; never
     * call it merely because headers arrived, a future was cancelled, or a downstream disconnected.
     * close releases only undispatched reservations. Crashes and dispatched close retain durable debt.
     */
    public static final class Lease implements AutoCloseable {
        private final ProviderRequestBudget budget;
        private final int slot;
        private boolean dispatched;
        private boolean closed;

        private Lease(final ProviderRequestBudget budget, final int slot) {
            this.budget = budget;
            this.slot = slot;
        }

        public void dispatched() {
            if (closed) {
                throw new IllegalStateException("Provider budget lease already closed");
            }
            dispatched = true;
        }

        public void completed() throws IOException {
            if (!closed) {
                // Do not retry an uncertain release and risk clearing a subsequent reservation.
                closed = true;
                if (budget != null) {
                    budget.release(slot);
                }
            }
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                if (!dispatched) {
                    completed();
                } else {
                    closed = true;
                }
            }
        }
    }
}
