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
package io.yupiik.ia.agent.core.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ShellResolverTest {

    @Test
    void autoNeverFailsAndResolvesAnExecutable() {
        final var resolved = ShellResolver.auto().resolve();
        assertNotNull(resolved.binary(), "auto must always resolve a binary");
        assertTrue(
                isExecutableShell(resolved.binary()),
                "auto must resolve to an executable shell, got: " + resolved.binary());
    }

    @Test
    void autoPrefersBashWhenPresent() {
        final var resolved = ShellResolver.auto().resolve();
        if (bashPresent()) {
            assertTrue(
                    resolved.binary().toString().endsWith("bash"),
                    "auto must pick real bash when present, got: " + resolved.binary());
            assertEquals("", resolved.notice(), "a normal auto->bash resolution carries no notice");
        } else {
            // bash-less environment (alpine, windows without git-bash): the documented fallback is /bin/sh
            assertTrue(
                    resolved.binary().toString().endsWith("sh"),
                    "auto without bash must fall back to sh, got: " + resolved.binary());
            assertFalse(resolved.notice().isEmpty(), "the bash-less fallback must be reported");
        }
    }

    @Test
    void explicitBashIsHonoredWhenPresent() {
        final var resolved = new ShellResolver("bash").resolve();
        if (bashPresent()) {
            assertTrue(
                    resolved.binary().toString().endsWith("bash"),
                    "explicit bash must resolve to bash, got: " + resolved.binary());
            assertEquals("", resolved.notice(), "an honored pin carries no notice");
        }
        // when bash is absent the pin falls back and reports (covered next test)
    }

    @Test
    void explicitMissingShellFallsBackToShWithANotice() {
        final var missing = "/definitely/not/a/shell/" + System.nanoTime();
        final var resolved = new ShellResolver(missing).resolve();
        assertEquals(Path.of("/bin/sh"), resolved.binary(), "a missing pin must fall back to /bin/sh");
        assertFalse(resolved.notice().isEmpty(), "the fall back must be surfaced");
        assertTrue(resolved.notice().contains(missing), "the notice must name the missing shell");
    }

    @Test
    void resolutionIsCachedAndStable() {
        final var resolver = new ShellResolver("auto");
        final var first = resolver.resolve();
        final var second = resolver.resolve();
        assertEquals(first, second, "resolution must be cached/stable");
        assertEquals(resolver.binary(), first.binary(), "binary() must stay consistent with resolve()");
    }

    private static boolean isExecutableShell(final Path p) {
        // a bare PATH name (deprecated fallback) or an existing regular file
        return p != null && Files.isRegularFile(p) && Files.isExecutable(p);
    }

    private static boolean bashPresent() {
        return Files.isRegularFile(Path.of("/bin/bash")) && Files.isExecutable(Path.of("/bin/bash"))
                || Files.isRegularFile(Path.of("/usr/bin/bash")) && Files.isExecutable(Path.of("/usr/bin/bash"))
                || onPath("bash");
    }

    private static boolean onPath(final String name) {
        final var pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return false;
        }
        final var sep = System.getProperty("path.separator", ":");
        for (final var dir : pathEnv.split(sep)) {
            final var candidate = dir.isEmpty() ? Path.of(name) : Path.of(dir, name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return true;
            }
        }
        return false;
    }
}
