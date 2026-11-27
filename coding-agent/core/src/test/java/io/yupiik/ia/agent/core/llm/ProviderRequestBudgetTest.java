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

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.core.configuration.ProviderBudgetConfiguration;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("try") // Explicit close is part of the idempotent reservation lifecycle regression.
class ProviderRequestBudgetTest {
    @TempDir
    Path directory;

    @Test
    void disabledAndStrictTrustedSettings() throws Exception {
        final var disabled = ProviderRequestBudget.fromConfiguration(budgetConfiguration(Map.of()));
        assertFalse(disabled.enabled());
        try (final var lease = disabled.tryAcquire().orElseThrow()) {
            lease.dispatched();
            lease.completed();
            lease.completed();
        }
        assertFalse(
                ProviderRequestBudget.fromConfiguration(budgetConfiguration(Map.of(ProviderRequestBudget.ROOT, " ")))
                        .enabled());
        for (final var settings : List.of(
                Map.of(ProviderRequestBudget.ID, "account"),
                Map.of(ProviderRequestBudget.SLOTS, "1"),
                Map.of(ProviderRequestBudget.ROOT, "", ProviderRequestBudget.SLOTS, "1"),
                settings("../escape", "1"),
                settings("account", "0"),
                settings("account", "01"),
                settings("account", "65537"),
                settings("account", "99999999999999"),
                Map.of(
                        ProviderRequestBudget.ROOT,
                        "relative",
                        ProviderRequestBudget.ID,
                        "a",
                        ProviderRequestBudget.SLOTS,
                        "1"))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ProviderRequestBudget.fromConfiguration(budgetConfiguration(settings)));
        }
    }

    @Test
    void sharesAcrossInstancesAndRetainsUnknownWorkWithoutExpiry() throws Exception {
        final var first = budget("1");
        final var second = budget("1");
        assertTrue(first.enabled());
        try (final var reservation = first.tryAcquire().orElseThrow()) {
            assertTrue(second.tryAcquire().isEmpty());
        }
        try (final var reservation = second.tryAcquire().orElseThrow()) {
            reservation.dispatched();
            reservation.completed();
            reservation.close();
            // An old lease must not clear the next owner's slot.
            try (final var next = first.tryAcquire().orElseThrow()) {
                reservation.completed();
                assertTrue(second.tryAcquire().isEmpty());
            }
        }
        try (final var unknown = first.tryAcquire().orElseThrow()) {
            unknown.dispatched();
        }
        assertTrue(budget("1").tryAcquire().isEmpty());
        assertThrows(IOException.class, () -> budget("2"));
        assertTrue(budget("1").tryAcquire().isEmpty());
    }

    @Test
    void sameJvmContentionNeverOverAdmitsOrOverlapsLocks() throws Exception {
        final var instances = new ArrayList<ProviderRequestBudget>();
        for (int i = 0; i < 12; i++) {
            instances.add(budget("3"));
        }
        final var start = new CountDownLatch(1);
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var results = new ArrayList<Future<Optional<ProviderRequestBudget.Lease>>>();
            for (final var instance : instances) {
                results.add(executor.submit(() -> {
                    start.await();
                    return instance.tryAcquire();
                }));
            }
            start.countDown();
            final var leases = new ArrayList<ProviderRequestBudget.Lease>();
            for (final var result : results) {
                result.get().ifPresent(leases::add);
            }
            assertTrue(leases.size() <= 3);
            while (leases.size() < 3) {
                leases.add(instances.getFirst().tryAcquire().orElseThrow());
            }
            assertTrue(instances.getLast().tryAcquire().isEmpty());
            for (final var lease : leases) {
                lease.close();
            }
            try (final var available = instances.getFirst().tryAcquire().orElseThrow()) {
                assertNotNull(available);
            }
        }
    }

    @Test
    void rejectsUnsafeFilesystemAndCorruptSchema() throws Exception {
        final var root = directory.resolve("root");
        Files.createDirectory(root);
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThrows(IOException.class, () -> budget("1"));
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        final var budget = budget("1");
        final var ledger = root.resolve("account.budget");
        final var alias = directory.resolve("hardlink");
        Files.createLink(alias, ledger);
        assertThrows(IOException.class, budget::tryAcquire);
        Files.delete(alias);
        Files.setPosixFilePermissions(ledger, PosixFilePermissions.fromString("rw-r-----"));
        assertThrows(IOException.class, budget::tryAcquire);
        Files.setPosixFilePermissions(ledger, PosixFilePermissions.fromString("rw-------"));
        try (final var channel = FileChannel.open(ledger, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[] {2}), 8);
        }
        assertThrows(IOException.class, budget::tryAcquire);
        final var link = directory.resolve("link");
        Files.createSymbolicLink(link, root);
        assertThrows(
                IOException.class,
                () -> ProviderRequestBudget.fromConfiguration(budgetConfiguration(Map.of(
                        ProviderRequestBudget.ROOT,
                        link.toString(),
                        ProviderRequestBudget.ID,
                        "a",
                        ProviderRequestBudget.SLOTS,
                        "1"))));
        assertThrows(
                IOException.class,
                () -> ProviderRequestBudget.fromConfiguration(budgetConfiguration(Map.of(
                        ProviderRequestBudget.ROOT,
                        link.resolve("nested").toString(),
                        ProviderRequestBudget.ID,
                        "a",
                        ProviderRequestBudget.SLOTS,
                        "1"))));
        Files.createSymbolicLink(root.resolve("symlink.budget"), ledger);
        assertThrows(
                IOException.class,
                () -> ProviderRequestBudget.fromConfiguration(budgetConfiguration(settings("symlink", "1"))));
        Files.write(root.resolve("partial.budget"), new byte[0]);
        Files.setPosixFilePermissions(root.resolve("partial.budget"), PosixFilePermissions.fromString("rw-------"));
        assertThrows(
                IOException.class,
                () -> ProviderRequestBudget.fromConfiguration(budgetConfiguration(settings("partial", "1"))));
    }

    @Test
    void processLocksAreShared() throws Exception {
        final var budget = budget("1");
        final var process = child("lock");
        try {
            try (final var output = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                assertEquals("locked", output.readLine());
                assertTrue(budget.tryAcquire().isEmpty());
                process.getOutputStream().write(1);
                process.getOutputStream().flush();
                assertTrue(process.waitFor(2, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue());
            }
        } finally {
            process.destroyForcibly();
        }
        try (final var available = budget.tryAcquire().orElseThrow()) {
            assertNotNull(available);
        }
    }

    @Test
    void processCrashDebtIsShared() throws Exception {
        final var budget = budget("1");
        final var crash = child("crash");
        try {
            assertTrue(crash.waitFor(2, TimeUnit.SECONDS));
            assertEquals(0, crash.exitValue());
            assertTrue(budget.tryAcquire().isEmpty());
        } finally {
            crash.destroyForcibly();
        }
    }

    private Process child(final String action) throws Exception {
        final var separator = File.pathSeparator;
        final var classpath = Path.of(ProviderRequestBudgetTest.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                + separator
                + Path.of(ProviderRequestBudget.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI());
        return new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-XX:TieredStopAtLevel=1",
                        "-Duser.name="
                                + (Files.isDirectory(Path.of("/proc/self"))
                                        ? "yca-numeric-user-without-passwd-entry"
                                        : System.getProperty("user.name")),
                        "-cp",
                        classpath,
                        Child.class.getName(),
                        directory.resolve("root").toString(),
                        action)
                .inheritIO()
                .redirectInput(ProcessBuilder.Redirect.PIPE)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .start();
    }

    private ProviderRequestBudget budget(final String slots) throws Exception {
        return ProviderRequestBudget.fromConfiguration(budgetConfiguration(settings("account", slots)));
    }

    private static ProviderBudgetConfiguration budgetConfiguration(final Map<String, String> settings) {
        return new ProviderBudgetConfiguration(
                settings.get(ProviderRequestBudget.ROOT),
                settings.get(ProviderRequestBudget.ID),
                settings.get(ProviderRequestBudget.SLOTS));
    }

    private Map<String, String> settings(final String id, final String slots) {
        return Map.of(
                ProviderRequestBudget.ROOT,
                directory.resolve("root").toString(),
                ProviderRequestBudget.ID,
                id,
                ProviderRequestBudget.SLOTS,
                slots);
    }

    public static final class Child {
        public static void main(final String[] args) throws Exception {
            if ("lock".equals(args[1])) {
                try (final var channel =
                                FileChannel.open(Path.of(args[0], "account.budget"), StandardOpenOption.WRITE);
                        final var lock = channel.lock()) {
                    System.out.println("locked");
                    System.out.flush();
                    System.in.read();
                }
            } else {
                final var budget = ProviderRequestBudget.fromConfiguration(budgetConfiguration(Map.of(
                        ProviderRequestBudget.ROOT,
                        args[0],
                        ProviderRequestBudget.ID,
                        "account",
                        ProviderRequestBudget.SLOTS,
                        "1")));
                final var reservation = budget.tryAcquire().orElseThrow();
                reservation.dispatched();
                Runtime.getRuntime().halt(0); // No close/finally/shutdown hook can repay this reservation.
            }
        }
    }
}
