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
package io.yupiik.maven.knowledge.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Process lifecycle tests. They spawn only tiny/ignored processes (a shell that traps TERM, a
 * child that writes a pid file) so none of them waits on a real timeout: the reap grace
 * configured in these tests is in the tens of milliseconds.
 */
class ProcessServiceTest {

    @Test
    void trackAndDisposeKillTheWholeTree(@TempDir final Path tmp) throws Exception {
        Assumptions.assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"));
        final var pidFile = tmp.resolve("child.pid");
        final var service = new ProcessService();
        service.reapGrace(200);
        // `sh -c 'sleep 30 & echo $! > pid; wait'`: the shell spawns a foreground child and waits.
        final var process = new ProcessBuilder("sh", "-c", "sleep 30 & echo $! > " + pidFile + "; wait").start();
        service.track(process);
        assertTrue(process.isAlive());
        assertEquals(1, service.trackedCount());

        // the child must be a descendant of the shell
        final var childPid = awaitPid(pidFile);
        assertNotNull(childPid);

        final var exit = service.dispose(process);
        assertNotNull(exit, "dispose must reap the shell");
        // the foreground child must be gone too (tree kill), not orphaned
        awaitGone(childPid, tmp.resolve("child-gone"));
        assertEquals(0, service.trackedCount());
    }

    @Test
    void disposeIsIdempotentAndKillsUnknownProcess() throws Exception {
        Assumptions.assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"));
        final var service = new ProcessService();
        service.reapGrace(100);
        final var process = new ProcessBuilder("sh", "-c", "sleep 30").start();
        service.track(process);
        // not tracked -> still killed and reaped by dispose()
        service.removeTracked(process);
        final var exit = service.dispose(process);
        assertNotNull(exit);
        final var second = service.dispose(process);
        assertNotNull(second, "dispose of an already-dead process returns its exit value");
    }

    @Test
    void disposeAllKillsAllTrackedTreesConcurrently(@TempDir final Path tmp) throws Exception {
        Assumptions.assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"));
        final var service = new ProcessService();
        // grace is short; the kill-all-first then wait-all design means two processes each reaped
        // within the grace complete in ~one grace, not two (the wait is bounded by allOf, not a loop)
        service.reapGrace(300);

        final var pidFile1 = tmp.resolve("p1.pid");
        final var pidFile2 = tmp.resolve("p2.pid");
        final var p1 = service.track(
                new ProcessBuilder("sh", "-c", "trap '' TERM; sleep 5 & echo $! > " + pidFile1 + "; wait").start());
        final var p2 = service.track(
                new ProcessBuilder("sh", "-c", "trap '' TERM; sleep 5 & echo $! > " + pidFile2 + "; wait").start());
        final var child1 = awaitPid(pidFile1);
        final var child2 = awaitPid(pidFile2);
        assertNotNull(child1);
        assertNotNull(child2);

        final var start = System.nanoTime();
        final var remaining = service.disposeAll();
        assertTrue(remaining.isEmpty(), "both must exit within the grace: " + remaining);
        final var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // two trees killed + waited with a single shared bound: must stay well under 2x grace
        assertTrue(
                elapsedMs < 900,
                "disposeAll must wait for all in one bound, not sequentially: took " + elapsedMs + "ms");
        awaitGone(child1, tmp.resolve("c1-gone"));
        awaitGone(child2, tmp.resolve("c2-gone"));
        assertEquals(0, service.trackedCount());
    }

    @Test
    void destroyDisposesTrackedProcessesOnContainerClose(@TempDir final Path tmp) throws Exception {
        Assumptions.assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"));
        final var service = new ProcessService();
        service.reapGrace(200);
        final var pidFile = tmp.resolve("p.pid");
        final var process =
                service.track(new ProcessBuilder("sh", "-c", "sleep 30 & echo $! > " + pidFile + "; wait").start());
        assertNotNull(process);
        awaitPid(pidFile);

        // simulates fusion container close (CliAwaiter/Launcher hook): the @Destroy method kills everything
        service.close();
        assertEquals(0, service.trackedCount());
        assertFalse(process.isAlive(), "spawned process must be killed by @Destroy");
    }

    private static Long awaitPid(final Path pidFile) throws Exception {
        final var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(pidFile)) {
                final var raw = Files.readString(pidFile).trim();
                if (!raw.isEmpty()) {
                    return Long.parseLong(raw);
                }
            }
            Thread.sleep(10);
        }
        return null;
    }

    private static void awaitGone(final long pid, final Path marker) throws IOException, InterruptedException {
        final var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("process " + pid + " survived the tree kill");
    }
}
