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
package io.yupiik.ia.agent.github.execution;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.github.support.TestScript;
import io.yupiik.ia.agent.testing.UnlinkingTempDirDeletionStrategy;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryCacheTest {
    private static final String REPO = "https://github.com/yupiik/example.git";

    @TempDir(deletionStrategy = UnlinkingTempDirDeletionStrategy.class)
    Path temporary;

    @Test
    void clonesThenFetchesAndExportsIndependentRepositoriesWithPublicOrigin() throws Exception {
        final var git = fakeGit("");
        final var root = temporary.resolve("cache");
        final var cache = new RepositoryCache(root, git.toString(), Duration.ofSeconds(5));
        final var first = cache.stage(REPO, temporary.resolve("one"));
        final var second = cache.stage(REPO, temporary.resolve("two"));
        assertEquals(temporary.resolve("one/repository"), first);
        assertEquals(REPO, Files.readString(first.resolve(".git/origin")));
        assertEquals(REPO, Files.readString(second.resolve(".git/origin")));
        assertFalse(Files.exists(first.resolve(".git/objects/info/alternates")));
        final var log = Files.readString(temporary.resolve("calls"));
        assertEquals(1, occurrences(log, "<--mirror>"));
        assertEquals(1, occurrences(log, "<fetch><--prune><--><" + REPO + "><+refs/*:refs/*>"));
        assertEquals(2, occurrences(log, "<clone><--no-hardlinks><--template=><-->"));
        assertEquals(2, occurrences(log, "<remote><set-url><origin><" + REPO + ">"));
        assertFalse(log.contains("--reference"));
        assertFalse(log.contains("--shared"));
        for (final var line : log.lines().toList()) {
            assertTrue(line.contains("<-c><credential.helper=>"), line);
            assertTrue(line.contains("<-c><core.hooksPath=/dev/null>"), line);
            assertTrue(line.contains("<-c><http.followRedirects=false>"), line);
            assertTrue(line.contains("<-c><protocol.allow=never>"), line);
            assertTrue(
                    line.contains("<-c><protocol.file.allow=" + (line.contains("<--no-hardlinks>") ? "always" : "never")
                            + ">"),
                    line);
        }
        final var environment = Files.readAllLines(temporary.resolve("environment"));
        assertTrue(environment.contains("HOME=/dev/null"));
        assertTrue(environment.contains("GIT_CONFIG_NOSYSTEM=1"));
        assertTrue(environment.contains("GIT_CONFIG_GLOBAL=/dev/null"));
        assertTrue(environment.contains("GIT_CONFIG_SYSTEM=/dev/null"));
        assertTrue(environment.contains("GIT_TERMINAL_PROMPT=0"));
        assertTrue(environment.stream()
                .allMatch(line -> List.of(
                                "PATH",
                                "HOME",
                                "XDG_CONFIG_HOME",
                                "GIT_CONFIG_NOSYSTEM",
                                "GIT_CONFIG_SYSTEM",
                                "GIT_CONFIG_GLOBAL",
                                "GIT_TERMINAL_PROMPT",
                                "GIT_ASKPASS",
                                "GIT_ATTR_NOSYSTEM",
                                "GIT_LFS_SKIP_SMUDGE",
                                "PWD")
                        .contains(line.substring(0, line.indexOf('=')))));
        try (final var paths = Files.walk(root)) {
            for (final var path : paths.toList()) {
                assertEquals(
                        PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwx------" : "rw-------"),
                        Files.getPosixFilePermissions(path));
            }
        }
        assertThrows(IOException.class, () -> cache.stage(REPO, temporary.resolve("one")));
    }

    @Test
    void rejectsAnythingOtherThanAnonymousCanonicalGithubHttpsBeforeRunningGit() throws Exception {
        final var cache =
                new RepositoryCache(temporary.resolve("cache"), fakeGit("").toString(), Duration.ofSeconds(2));
        for (final var repo : List.of(
                "o/r",
                "git@github.com:o/r.git",
                "https://github.com/o/r",
                "https://token@github.com/o/r.git",
                "https://github.com.evil/o/r.git",
                "http://github.com/o/r.git",
                "https://github.com/o/../r.git",
                "file:///tmp/repo.git",
                "https://github.com/o/r.git?token=secret",
                "https://github.com/o/r.git#fragment",
                "https://github.com/o/r.git\n",
                "https://github.com/o/-r.git")) {
            assertThrows(IllegalArgumentException.class, () -> cache.stage(repo, temporary.resolve("work")), repo);
        }
        assertFalse(Files.exists(temporary.resolve("calls")));
        assertThrows(IllegalArgumentException.class, () -> cache.stage(REPO, temporary.resolve("cache/work")));
        assertThrows(IllegalArgumentException.class, () -> cache.stage(REPO, temporary));
    }

    @Test
    void rejectsSymlinkedRootsAncestorsWorkspacesAndTargets() throws Exception {
        final var outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("sentinel"), "untouched");
        final var link = Files.createSymbolicLink(temporary.resolve("link"), outside);
        final var git = fakeGit("");
        for (final var root : List.of(link, link.resolve("nested"))) {
            assertThrows(
                    IOException.class,
                    () -> new RepositoryCache(root, git.toString(), Duration.ofSeconds(2))
                            .stage(REPO, temporary.resolve("work")));
        }
        final var cache = new RepositoryCache(temporary.resolve("cache"), git.toString(), Duration.ofSeconds(2));
        assertThrows(IOException.class, () -> cache.stage(REPO, link));
        final var workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.createSymbolicLink(workspace.resolve("repository"), outside);
        assertThrows(IOException.class, () -> cache.stage(REPO, workspace));
        assertEquals("untouched", Files.readString(outside.resolve("sentinel")));
        assertFalse(Files.exists(temporary.resolve("calls")));
    }

    @Test
    void rejectsSymlinkedLockAndMirrorMetadataWithoutTouchingTheirTargets() throws Exception {
        final var root = temporary.resolve("cache");
        final var cache = new RepositoryCache(root, fakeGit("").toString(), Duration.ofSeconds(2));
        cache.stage(REPO, temporary.resolve("first"));
        final var mirror = child(root, ".git");
        final var outside = Files.writeString(temporary.resolve("outside"), "untouched");
        Files.createSymbolicLink(mirror.resolve("config"), outside);
        final var before = Files.readString(temporary.resolve("calls"));
        assertThrows(IOException.class, () -> cache.stage(REPO, temporary.resolve("second")));
        assertEquals(before, Files.readString(temporary.resolve("calls")));
        Files.delete(mirror.resolve("config"));
        final var lock = child(root, ".lock");
        Files.delete(lock);
        Files.createSymbolicLink(lock, outside);
        assertThrows(IOException.class, () -> cache.stage(REPO, temporary.resolve("third")));
        assertEquals("untouched", Files.readString(outside));
    }

    @Test
    void failedInitialCloneIsNotPublishedAndCanBeRetried() throws Exception {
        final var git = fakeGit("case \" $* \" in *' --mirror '*) exit 17;; esac");
        final var root = temporary.resolve("cache");
        final var cache = new RepositoryCache(root, git.toString(), Duration.ofSeconds(2));
        assertThrows(IOException.class, () -> cache.stage(REPO, temporary.resolve("work")));
        try (final var paths = Files.list(root)) {
            assertTrue(paths.allMatch(path -> path.toString().endsWith(".lock")));
        }
        fakeGit("");
        assertTrue(Files.isDirectory(cache.stage(REPO, temporary.resolve("work"))));
    }

    @Test
    void failedFetchDoesNotExportOrUseStaleMirror() throws Exception {
        final var git = fakeGit("");
        final var cache = new RepositoryCache(temporary.resolve("cache"), git.toString(), Duration.ofSeconds(2));
        cache.stage(REPO, temporary.resolve("first"));
        fakeGit("case \" $* \" in *' fetch '*) exit 19;; esac");
        assertThrows(IOException.class, () -> cache.stage(REPO, temporary.resolve("second")));
        assertFalse(Files.exists(temporary.resolve("second/repository")));
    }

    @Test
    void failedExportOrOriginRestoreIsCleanedUp() throws Exception {
        final var git = fakeGit("");
        final var cache = new RepositoryCache(temporary.resolve("cache"), git.toString(), Duration.ofSeconds(2));
        cache.stage(REPO, temporary.resolve("first"));
        for (final var command : List.of("--no-hardlinks", "set-url")) {
            fakeGit("case \" $* \" in *' " + command + " '*) exit 23;; esac");
            assertThrows(IOException.class, () -> cache.stage(REPO, temporary.resolve("second")));
            assertFalse(Files.exists(temporary.resolve("second/repository")));
        }
    }

    @Test
    void timeoutIsBoundedAndDoesNotPublishMirror() throws Exception {
        final var git = fakeGit("exec /bin/sleep 30");
        final var cache = new RepositoryCache(temporary.resolve("cache"), git.toString(), Duration.ofMillis(100));
        assertTimeoutPreemptively(
                Duration.ofSeconds(3),
                () -> assertThrows(IOException.class, () -> cache.stage(REPO, temporary.resolve("work"))));
        assertFalse(Files.exists(temporary.resolve("work/repository")));
    }

    @Test
    void serializesInstancesAndHonorsAnExistingFileLock() throws Exception {
        final var git = fakeGit("");
        final var root = temporary.resolve("cache");
        try (final var executor = Executors.newFixedThreadPool(2)) {
            final Callable<Path> first = () -> new RepositoryCache(root, git.toString(), Duration.ofSeconds(5))
                    .stage(REPO, temporary.resolve("one"));
            final Callable<Path> second = () -> new RepositoryCache(root, git.toString(), Duration.ofSeconds(5))
                    .stage(REPO, temporary.resolve("two"));
            for (final var future : executor.invokeAll(List.of(first, second))) {
                assertTrue(Files.isDirectory(future.get()));
            }
        }
        assertEquals(1, occurrences(Files.readString(temporary.resolve("calls")), "<--mirror>"));
        // An overlapping lock in this JVM models an OS lock held outside the cache implementation.
        try (final var channel = FileChannel.open(child(root, ".lock"), StandardOpenOption.WRITE);
                final var _ = channel.lock()) {
            assertTimeoutPreemptively(
                    Duration.ofSeconds(3),
                    () -> assertThrows(
                            IOException.class,
                            () -> new RepositoryCache(root, git.toString(), Duration.ofMillis(100))
                                    .stage(REPO, temporary.resolve("three"))));
        }
    }

    @Test
    void refusesObjectAlternatesInCacheMetadata() throws Exception {
        final var git = fakeGit("");
        final var root = temporary.resolve("cache");
        final var cache = new RepositoryCache(root, git.toString(), Duration.ofSeconds(2));
        cache.stage(REPO, temporary.resolve("first"));
        final var mirror = child(root, ".git");
        Files.createDirectories(mirror.resolve("objects/info"));
        Files.writeString(mirror.resolve("objects/info/alternates"), "/host/objects");
        assertThrows(IOException.class, () -> cache.stage(REPO, temporary.resolve("second")));
    }

    private Path child(final Path root, final String suffix) throws IOException {
        try (final var paths = Files.list(root)) {
            return paths.filter(path -> path.toString().endsWith(suffix))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private int occurrences(final String value, final String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }

    private Path fakeGit(final String extra) throws IOException {
        final var script = temporary.resolve("git");
        return TestScript.write(script, """
                #!/bin/sh
                set -eu
                {
                  for arg do printf '<%%s>' "$arg"; done
                  printf '\\n'
                } >> %s
                /usr/bin/env > %s
                %s
                while [ "$1" = '-c' ]; do shift 2; done
                case "$1" in
                  clone)
                    mirror=no
                    for arg do
                      if [ "$arg" = '--mirror' ]; then mirror=yes; fi
                      target=$arg
                    done
                    if [ "$mirror" = yes ]; then
                      /bin/mkdir -p "$target/objects"
                    else
                      /bin/mkdir -p "$target/.git/objects/info"
                    fi
                    ;;
                  --git-dir=*) [ "$2" = fetch ] ;;
                  -C) printf '%%s' "$6" > "$2/.git/origin" ;;
                  *) exit 91 ;;
                esac
                """.formatted(
                        quote(temporary.resolve("calls")), quote(temporary.resolve("environment")), extra));
    }

    private String quote(final Path path) {
        return "'" + path.toString().replace("'", "'\"'\"'") + "'";
    }
}
