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
package io.yupiik.ia.agent.nono.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.nono.TestTempDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class NotifySendApprovalUiTest {
    private Path directory;

    @BeforeEach
    void createTrustedDirectory() throws IOException {
        // /tmp is intentionally rejected by the production ancestor policy.
        directory = TestTempDirs.create(Path.of(System.getProperty("user.home")), ".nono-ui-test-");
    }

    @AfterEach
    void cleanup() throws IOException {
        TestTempDirs.cleanup(directory);
    }

    @Test
    void escapesSanitizedTextAndUsesDocumentedActionFlags() throws Exception {
        final var executable = script("""
                printf '%s\\n' "$@" > "$0.args"
                if read -r line; then exit 9; fi
                i=0
                while [ "$i" -lt 20000 ]; do
                    printf 'discard stderr\\n' >&2
                    i=$((i+1))
                done
                printf 'allow\\n'
                """);
        final var marker = directory.resolve("must-not-exist");
        final var text = "<b>&request</b> $(touch " + marker + ") `id` --wait\u202e\u001b[31m";
        try (final var ui = new NotifySendApprovalUi(executable);
                final var handle = ui.present(text, Duration.ofSeconds(5))) {
            assertTrue(handle.decision().toCompletableFuture().get(7, TimeUnit.SECONDS));
            assertEquals(
                    List.of(
                            "--wait",
                            "--action=allow=Allow",
                            "--action=deny=Deny",
                            "--expire-time=5000",
                            "--",
                            "Nono approval",
                            PlainText.sanitize(text)
                                    .replace("&", "&amp;")
                                    .replace("<", "&lt;")
                                    .replace(">", "&gt;")),
                    Files.readAllLines(Path.of(executable + ".args")));
            assertFalse(Files.exists(marker));
        }
    }

    @Test
    void onlyExactAllowAndSuccessfulExitApprove() throws Exception {
        for (final var output : List.of(
                "allow",
                "allow\n",
                "deny\n",
                "",
                "default\n",
                "Allow\n",
                " allow\n",
                "allow \n",
                "allow\nextra",
                "allow\n\n",
                "allow\r\n",
                "allow\\000")) {
            final var executable = script("printf '" + output + "'");
            try (final var ui = new NotifySendApprovalUi(executable);
                    final var handle = ui.present("test", Duration.ofSeconds(3))) {
                assertEquals(
                        output.equals("allow") || output.equals("allow\n"),
                        handle.decision().toCompletableFuture().get(5, TimeUnit.SECONDS),
                        output);
            }
        }
        for (final var exit : List.of(1, 5, 127)) {
            try (final var ui = new NotifySendApprovalUi(script("printf 'allow\n'; exit " + exit));
                    final var handle = ui.present("test", Duration.ofSeconds(3))) {
                assertFalse(handle.decision().toCompletableFuture().get(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void preservesFullEscapedPresentation() throws Exception {
        final var executable = script("printf '%s' \"$7\" > \"$0.text\"; printf 'allow\n'");
        final var text = "<&>".repeat(1166) + "xy";
        try (final var ui = new NotifySendApprovalUi(executable);
                final var handle = ui.present(text, Duration.ofSeconds(3))) {
            assertTrue(handle.decision().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals("&lt;&amp;&gt;".repeat(1166) + "xy", Files.readString(Path.of(executable + ".text")));
        }
    }

    @Test
    void oversizedOutputFailsClosedWithoutPipeDeadlock() throws Exception {
        final var executable = script("printf 'allow\n'; while :; do printf 'xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx'; done");
        try (final var ui = new NotifySendApprovalUi(executable);
                final var handle = ui.present("test", Duration.ofSeconds(3))) {
            final var pid = awaitPid(executable);
            assertFalse(handle.decision().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    void actionAloneCannotApproveBeforeSuccessfulExitOrAfterDeadline() throws Exception {
        for (final var body : List.of("printf 'allow\\n'; while :; do :; done", "sleep 0.6; printf 'allow\\n'")) {
            final var executable = script(body);
            try (final var ui = new NotifySendApprovalUi(executable);
                    final var handle = ui.present("test", Duration.ofMillis(300))) {
                assertFalse(handle.decision().toCompletableFuture().get(3, TimeUnit.SECONDS));
                // The deadline includes process startup: on a busy runner cancellation can
                // correctly kill the shell before its first instruction publishes a PID.
                // Once the decision completes, any published process must already be reaped.
                final var pidFile = Path.of(executable + ".pid");
                if (Files.exists(pidFile) && !Files.readString(pidFile).isBlank()) {
                    final var pid = Long.parseLong(Files.readString(pidFile));
                    assertFalse(
                            ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
                }
            }
        }
    }

    private Path script(final String body) throws IOException {
        final var path = directory.resolve("notify-send-test");
        Files.deleteIfExists(Path.of(path + ".pid"));
        Files.writeString(path, "#!/bin/sh\nprintf '%s' \"$$\" > \"$0.pid\"\n" + body + "\n");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        return path;
    }

    private long awaitPid(final Path executable) throws Exception {
        final var file = Path.of(executable + ".pid");
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (Files.exists(file)) {
                final var value = Files.readString(file);
                if (!value.isBlank()) {
                    return Long.parseLong(value);
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Subprocess did not publish its PID");
    }
}
