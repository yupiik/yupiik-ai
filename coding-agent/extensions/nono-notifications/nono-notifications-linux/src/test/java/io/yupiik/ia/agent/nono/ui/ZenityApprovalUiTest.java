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
class ZenityApprovalUiTest {
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
    void passesUntrustedTextAsOneArgumentAndDiscardsAllIo() throws Exception {
        final var executable = script("""
                printf '%s\\n' "$@" > "$0.args"
                if read -r line; then exit 9; fi
                i=0
                while [ "$i" -lt 20000 ]; do
                    printf 'discard stdout discard stderr\\n'
                    printf 'discard stderr\\n' >&2
                    i=$((i+1))
                done
                exit 0
                """);
        final var marker = directory.resolve("must-not-exist");
        final var text = "<b>request</b> $(touch " + marker + ") `id` --timeout=0\u202e";
        try (final var ui = new ZenityApprovalUi(executable);
                final var handle = ui.present(text, Duration.ofSeconds(5))) {
            assertTrue(handle.decision().toCompletableFuture().get(7, TimeUnit.SECONDS));
            assertEquals(
                    List.of(
                            "--question",
                            "--no-markup",
                            "--title=Nono approval",
                            "--ok-label=Allow",
                            "--cancel-label=Deny",
                            "--text=" + PlainText.sanitize(text),
                            "--timeout=5"),
                    Files.readAllLines(Path.of(executable + ".args")));
            assertFalse(Files.exists(marker));
        }
    }

    @Test
    void onlyExitZeroApproves() throws Exception {
        for (final var exit : List.of(1, 5, 127)) {
            try (final var ui = new ZenityApprovalUi(script("exit " + exit));
                    final var handle = ui.present("test", Duration.ofSeconds(3))) {
                assertFalse(handle.decision().toCompletableFuture().get(5, TimeUnit.SECONDS));
            }
        }
    }

    private Path script(final String body) throws IOException {
        final var path = directory.resolve("zenity-test");
        Files.writeString(path, "#!/bin/sh\nprintf '%s' \"$$\" > \"$0.pid\"\n" + body + "\n");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        return path;
    }
}
