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
package io.yupiik.ia.agent.github.context.git;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrustedGitStoreTest {
    private static final Pattern TREE_FIELD_SEPARATOR = Pattern.compile("[\t ]");
    private static final String URL = "https://github.com/yupiik/fixture.git";
    private static final long BUDGET = 8 * 1024 * 1024;

    @TempDir
    static Path seedTemporary;

    private static Fixture seed;

    @BeforeAll
    static void buildSeed() throws Exception {
        seed = buildFixture(seedTemporary);
    }

    @TempDir
    Path temporary;

    @Test
    void buildsExactIndependentShallowClosureAndOriginalEligibleTags() throws Exception {
        final var fixture = fixture();
        final var root = temporary.resolve("private");
        final Path serving;
        try (final var store = new TrustedGitStore(
                root, transport(fixture).toString(), Duration.ofSeconds(45), BUDGET, 67108864L, () -> "secret-value")) {
            final var view = store.prepare(URL, fixture.head(), 2, true);
            serving = view.directory();
            assertEquals(fixture.head(), view.headSha());
            assertEquals(2, view.depth());
            assertEquals(fixture.head(), read(store, view, "rev-parse", "HEAD").strip());
            assertEquals(2, read(store, view, "rev-list", "HEAD").lines().count());
            assertEquals(
                    fixture.parent(),
                    Files.readString(serving.resolve("shallow")).strip());
            assertEquals(
                    fixture.tag(),
                    read(store, view, "rev-parse", "refs/tags/inside").strip());
            assertEquals(
                    fixture.nested(),
                    read(store, view, "rev-parse", "refs/tags/nested").strip());
            assertEquals("", read(store, view, "tag", "--list", "outside"));
            assertNotEquals(
                    0,
                    store.read(view, List.of("cat-file", "-t", fixture.unrelated()))
                            .exitCode());
            assertNotEquals(
                    0,
                    store.read(view, List.of("cat-file", "-t", fixture.old())).exitCode());
            assertEquals(
                    git(fixture.directory(), "cat-file", "tag", fixture.tag()),
                    read(store, view, "cat-file", "-p", fixture.tag()));
            assertFalse(Files.exists(serving.resolve("objects/info/alternates")));
            assertFalse(Files.readString(serving.resolve("config")).contains("remote"));
            assertFalse(Files.exists(serving.getParent().resolve("source.git")));
            assertFalse(Files.exists(serving.getParent().resolve("credential-helper")));
            final var expected = Set.copyOf(git(
                            fixture.directory(),
                            "rev-list",
                            "--objects",
                            "--no-object-names",
                            fixture.head(),
                            "^" + fixture.old())
                    .lines()
                    .toList());
            final var actual =
                    new HashSet<>(git(serving, "cat-file", "--batch-all-objects", "--batch-check=%(objectname)")
                            .lines()
                            .toList());
            // rev-list exclusions can also exclude trees shared with older history; use the actual
            // shallow traversal.
            final var reachable = new HashSet<>(git(serving, "rev-list", "--objects", "--no-object-names", "HEAD")
                    .lines()
                    .toList());
            assertTrue(reachable.containsAll(expected));
            reachable.add(fixture.tag());
            reachable.add(fixture.nested());
            assertEquals(reachable, actual);
            try (final var paths = Files.walk(root)) {
                for (final var path : paths.toList()) {
                    assertEquals(
                            PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwx------" : "rw-------"),
                            Files.getPosixFilePermissions(path));
                    if (Files.isRegularFile(path)) {
                        assertFalse(new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1)
                                .contains("secret-value"));
                    }
                }
            }
            final var next = store.prepare(URL, fixture.head(), 1, false);
            assertNotEquals(view.id(), next.id());
            assertNotEquals(view.directory(), next.directory());
            assertEquals(1, read(store, next, "rev-list", "HEAD").lines().count());
            assertEquals(2, read(store, view, "rev-list", "HEAD").lines().count());
            assertEquals("", read(store, next, "tag"));
            assertThrows(
                    IOException.class,
                    () -> store.read(new TrustedGitStore.View(view.id(), serving, view.headSha(), 2), List.of("log")));
        }
        assertFalse(Files.exists(serving));
        try (final var paths = Files.list(root)) {
            assertEquals(0, paths.count());
        }
    }

    @Test
    void authenticatesThroughNonExecutableHelperAtQuotedStoragePath() throws Exception {
        final var fixture = fixture();
        try (final var store = new TrustedGitStore(
                temporary.resolve("private with 'quotes'"),
                transport(fixture).toString(),
                Duration.ofSeconds(15),
                BUDGET,
                67108864L,
                () -> "secret-value")) {
            final var view = store.prepare(URL, fixture.head(), 1, false);
            assertEquals(fixture.head(), view.headSha());
            assertFalse(Files.exists(view.directory().getParent().resolve("credential-helper")));
        }
    }

    @Test
    void servesOnlySmartUploadPackWithoutCredentialsAndSupportsBinaryPost() throws Exception {
        final var fixture = fixture();
        try (final var store = new TrustedGitStore(
                temporary.resolve("private"),
                transport(fixture).toString(),
                Duration.ofSeconds(15),
                BUDGET,
                67108864L,
                () -> "secret-value")) {
            final var view = store.prepare(URL, fixture.head(), 1, false);
            final var info = store.http(view, "GET", "service=git-upload-pack", new byte[0], null);
            assertEquals(200, info.status());
            assertEquals(
                    "application/x-git-upload-pack-advertisement",
                    info.headers().get("content-type"));
            final var advertisement = new String(info.body(), StandardCharsets.ISO_8859_1);
            assertTrue(advertisement.contains(fixture.head()));
            assertFalse(advertisement.contains("filter"));
            assertFalse(advertisement.contains("allow-tip-sha1-in-want"));
            assertFalse(advertisement.contains("allow-reachable-sha1-in-want"));
            final var v2 = store.http(view, "GET", "service=git-upload-pack", new byte[0], "version=2");
            assertEquals(200, v2.status());
            assertTrue(new String(v2.body(), StandardCharsets.US_ASCII).contains("version 2"));
            final var want = "want " + fixture.head() + "\n";
            final var request = (String.format("%04x", want.length() + 4) + want + "00000009done\n")
                    .getBytes(StandardCharsets.US_ASCII);
            final var response = store.http(view, "POST", "", request, null);
            assertEquals(200, response.status());
            assertEquals(
                    "application/x-git-upload-pack-result", response.headers().get("content-type"));
            assertTrue(new String(response.body(), StandardCharsets.ISO_8859_1).contains("PACK"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> store.http(view, "GET", "service=git-receive-pack", new byte[0], null));
            assertThrows(IllegalArgumentException.class, () -> store.http(view, "GET", "", new byte[0], null));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> store.http(view, "POST", "", new byte[(int) BUDGET + 1], null));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> store.http(view, "POST", "", new byte[0], "version=2\nEVIL=1"));
            final var environment = Files.readString(temporary.resolve("serving-environment"));
            assertFalse(environment.contains("secret-value"));
            assertFalse(environment.contains("YCA_GIT_TOKEN="));
            assertFalse(environment.contains("YCA_GIT_CREDENTIAL_HELPER="));
            assertTrue(Files.readString(temporary.resolve("fetch-auth")).contains("secret-value"));
        }
    }

    @Test
    void rejectsUntrustedInputsGlobalsAndWriteOrExecutionOptions() throws Exception {
        try (final var store = new TrustedGitStore(
                temporary.resolve("private"), "/does/not/exist/git", Duration.ofSeconds(1), BUDGET, 67108864L, null)) {
            for (final var url : List.of(
                    "file:///tmp/r",
                    "https://token@github.com/o/r.git",
                    "http://github.com/o/r.git",
                    "https://github.com.evil/o/r.git",
                    "https://github.com/o/r.git?token=x",
                    "https://github.com/o/../r.git",
                    "o/r")) {
                assertThrows(IllegalArgumentException.class, () -> store.prepare(url, "a".repeat(40), 1, false));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> store.prepare(URL, "a".repeat(40), 1, false, "b".repeat(40), url));
            }
            assertThrows(
                    IllegalArgumentException.class, () -> store.prepare(URL, "a".repeat(40), 1, false, "BASE", URL));
            assertThrows(IllegalArgumentException.class, () -> store.prepare(URL, "HEAD", 1, false));
            assertThrows(IllegalArgumentException.class, () -> store.prepare(URL, "a".repeat(40), 0, false));
            for (final var argv : List.of(
                    List.of("-c", "core.pager=evil", "log"),
                    List.of("fetch"),
                    List.of("show", "--output=/tmp/x"),
                    List.of("diff", "--ext-diff"),
                    List.of("show", "--textconv"),
                    List.of("--git-dir=/tmp/agent", "log"))) {
                assertThrows(IllegalArgumentException.class, () -> store.read(null, argv));
            }
            final var error = assertThrows(IOException.class, () -> store.prepare(URL, "a".repeat(40), 1, false));
            assertTrue(error.getMessage().contains("install Git"));
        }
        final var outside = Files.createDirectory(temporary.resolve("outside"));
        final var link = Files.createSymbolicLink(temporary.resolve("link"), outside);
        try (final var store =
                new TrustedGitStore(link.resolve("private"), "git", Duration.ofSeconds(1), BUDGET, 67108864L, null)) {
            assertThrows(IOException.class, () -> store.prepare(URL, "a".repeat(40), 1, false));
        }
        assertFalse(Files.exists(outside.resolve("private")));
    }

    @Test
    void wrongHeadAndDiskOverflowAreNotPublished() throws Exception {
        final var fixture = fixture();
        final var wrapper = transport(fixture);
        // Deliberately make a faulty trusted transport return a different commit, to test the
        // independent head check.
        Files.writeString(
                wrapper,
                Files.readString(wrapper)
                        .replace(
                                "case \"$argument\" in",
                                "case \"$argument\" in\n        " + "a".repeat(40) + ") argument="
                                        + shell(fixture.head()) + " ;;"));
        final var root = temporary.resolve("private");
        try (final var store =
                new TrustedGitStore(root, wrapper.toString(), Duration.ofSeconds(15), BUDGET, 67108864L, null)) {
            final var error = assertThrows(IOException.class, () -> store.prepare(URL, "a".repeat(40), 1, false));
            assertTrue(error.getMessage().contains("failed"));
        }
        final var random = new byte[200_000];
        new Random(13).nextBytes(random);
        Files.write(fixture.directory().resolve("large"), random);
        git(fixture.directory(), "add", "large");
        git(fixture.directory(), "commit", "-m", "large");
        final var head = git(fixture.directory(), "rev-parse", "HEAD").strip();
        try (final var store = new TrustedGitStore(
                root, transport(fixture).toString(), Duration.ofSeconds(15), 64 * 1024, 67108864L, null)) {
            assertThrows(IOException.class, () -> store.prepare(URL, head, 1, false));
        }
        try (final var paths = Files.list(root)) {
            assertEquals(0, paths.count());
        }
    }

    @Test
    void timeoutAndCloseCancelActiveProcessAndCleanScratch() throws Exception {
        final var slow = temporary.resolve("slow-git");
        final var started = temporary.resolve("slow-git-started");
        Files.writeString(slow, "#!/bin/sh\nprintf started > " + shell(started.toString()) + "\nexec /bin/sleep 30\n");
        Files.setPosixFilePermissions(slow, PosixFilePermissions.fromString("rwx------"));
        final var root = temporary.resolve("private");
        try (final var store =
                new TrustedGitStore(root, slow.toString(), Duration.ofMillis(100), BUDGET, 67108864L, null)) {
            assertTimeoutPreemptively(
                    Duration.ofSeconds(3),
                    () -> assertThrows(IOException.class, () -> store.prepare(URL, "a".repeat(40), 1, false)));
        }
        Files.deleteIfExists(started);
        final var store = new TrustedGitStore(root, slow.toString(), Duration.ofSeconds(30), BUDGET, 67108864L, null);
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor();
                store) {
            final var future = executor.submit(() ->
                    assertThrows(IOException.class, () -> store.prepare(URL, "a".repeat(40), 1, false)));
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                while (!Files.exists(started)) {
                    Thread.sleep(1);
                }
            });
            assertTimeoutPreemptively(Duration.ofSeconds(3), store::close);
            future.get(3, TimeUnit.SECONDS);
            assertThrows(IOException.class, () -> store.prepare(URL, "a".repeat(40), 1, false));
        }
    }

    @Test
    void parsesOnlySafeCgiHeadersAndBoundsHeadersAndBinaryOutput() throws Exception {
        final var fixture = fixture();
        final var executable = transport(fixture);
        try (final var store = new TrustedGitStore(
                temporary.resolve("private"),
                executable.toString(),
                Duration.ofSeconds(15),
                128 * 1024,
                67108864L,
                null)) {
            final var view = store.prepare(URL, fixture.head(), 1, false);
            Files.write(
                    temporary.resolve("cgi-response"),
                    ("Status: 418 Teapot\r\n"
                                    + "Content-Type: application/octet-stream\r\n"
                                    + "X-Secret: hidden\r\n"
                                    + "Location: https://evil.invalid\r\n"
                                    + "WWW-Authenticate: Basic\r\n\r\n"
                                    + "\u0000\u00ff")
                            .getBytes(StandardCharsets.ISO_8859_1));
            final var result = store.http(view, "GET", "service=git-upload-pack", new byte[0], null);
            assertEquals(418, result.status());
            assertEquals(Map.of("content-type", "application/octet-stream"), result.headers());
            assertArrayEquals(new byte[] {0, (byte) 255}, result.body());
            Files.writeString(temporary.resolve("cgi-response"), "Content-Type: a\r\nContent-Type: b\r\n\r\nbody");
            assertThrows(
                    IOException.class, () -> store.http(view, "GET", "service=git-upload-pack", new byte[0], null));
            Files.writeString(temporary.resolve("cgi-response"), "Status: 200 OK\r\nStatus: 403 Forbidden\r\n\r\nbody");
            assertThrows(
                    IOException.class,
                    () -> store.httpStream(view, "GET", "service=git-upload-pack", new byte[0], null));
            Files.writeString(temporary.resolve("cgi-response"), "Content-Type: " + "x".repeat(65536) + "\r\n\r\nbody");
            assertThrows(
                    IOException.class, () -> store.http(view, "GET", "service=git-upload-pack", new byte[0], null));
            Files.writeString(
                    temporary.resolve("cgi-response"),
                    "Content-Type: application/octet-stream\r\n\r\n" + "x".repeat(200000));
            assertThrows(
                    IOException.class, () -> store.http(view, "GET", "service=git-upload-pack", new byte[0], null));
        }
    }

    @Test
    void boundsControlOutputAndSerializesConcurrentGenerations() throws Exception {
        final var fixture = fixture();
        Files.writeString(fixture.directory().resolve("large"), "x".repeat(100_000));
        git(fixture.directory(), "add", "large");
        git(fixture.directory(), "commit", "-m", "large blob");
        final var head = git(fixture.directory(), "rev-parse", "HEAD").strip();
        try (final var store = new TrustedGitStore(
                        temporary.resolve("private"),
                        transport(fixture).toString(),
                        Duration.ofSeconds(15),
                        BUDGET,
                        67108864L,
                        null);
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var first = executor.submit(() -> store.prepare(URL, head, 1, false));
            final var second = executor.submit(() -> store.prepare(URL, head, 2, false));
            final var one = first.get(15, TimeUnit.SECONDS);
            final var two = second.get(15, TimeUnit.SECONDS);
            assertNotEquals(one.id(), two.id());
            assertEquals(1, read(store, one, "rev-list", "HEAD").lines().count());
            assertEquals(2, read(store, two, "rev-list", "HEAD").lines().count());
            final var result = store.read(one, List.of("show", "HEAD:large"));
            assertEquals(0, result.exitCode());
            assertTrue(result.truncated());
            assertEquals("x".repeat(64 * 1024), result.stdout());
            assertEquals(head, read(store, one, "rev-parse", "HEAD").strip());
        }
    }

    @Test
    void executesCanonicalGrammarAcrossAllTwelveReadFamilies() throws Exception {
        final var fixture = fixture();
        final var policy = new GitReadPolicy();
        try (final var store = new TrustedGitStore(
                temporary.resolve("private"),
                transport(fixture).toString(),
                Duration.ofSeconds(15),
                BUDGET,
                67108864L,
                null)) {
            final var view = store.prepare(URL, fixture.head(), 2, true, fixture.parent());
            for (final var argv : List.of(
                    List.of("log", "-2", "--format=%h %s", "BASE..HEAD"),
                    List.of("show", "--stat", "HEAD"),
                    List.of("diff", "-U3", "BASE", "HEAD"),
                    List.of("grep", "-A2", "-B", "1", "-m1", "content"),
                    List.of("blame", "-L1,1", "--", "file"),
                    List.of("ls-tree", "-r", "--format=%(objecttype) %(objectsize) %(path)"),
                    List.of("rev-parse", "--short=12", "BASE"),
                    List.of("merge-base", "--is-ancestor", "HEAD", "HEAD"),
                    List.of("rev-list", "--count", "HEAD"),
                    List.of("cat-file", "-p", "HEAD:file"),
                    List.of("branch", "--format=%(refname:short)", "--contains=HEAD"),
                    List.of("tag", "--format=%(refname:short)"))) {
                final var canonical = policy.validate(argv);
                assertEquals(canonical, policy.validate(canonical), argv.toString());
                final var result = store.read(view, canonical);
                assertEquals(0, result.exitCode(), argv.toString());
                assertFalse(result.truncated(), argv.toString());
                if (!"merge-base".equals(argv.getFirst())) {
                    assertFalse(result.stdout().isEmpty(), argv.toString());
                }
            }
            assertEquals("content-2", read(store, view, "show", "HEAD:file"));
            assertEquals("content-1", read(store, view, "show", "BASE:file"));
            assertTrue(read(store, view, "grep", "content").startsWith("HEAD:file:"));
            assertEquals("unchanged content\n", read(store, view, "show", "HEAD:unchanged/deep/shared"));
            assertEquals("unchanged content\n", read(store, view, "show", "BASE:unchanged/deep/shared"));
            assertFalse(Files.exists(view.directory().resolve("index")));
            for (final var argv : List.of(
                    List.of("ls-tree", "--format=%(rest)"),
                    List.of("show", "--output=/tmp/leak"),
                    List.of("for-each-ref"),
                    List.of("rev-list", "--objects", "HEAD"),
                    List.of("tag", "-d", "inside"))) {
                assertThrows(IllegalArgumentException.class, () -> store.read(view, argv));
            }
        }
    }

    @Test
    void combinedRootsHaveExactFullTreesAndNativeShallowBoundaries() throws Exception {
        final var fixture = fixture();
        try (final var store = new TrustedGitStore(
                temporary.resolve("private"),
                transport(fixture).toString(),
                Duration.ofSeconds(15),
                BUDGET,
                67108864L,
                null)) {
            final var view = store.prepare(URL, fixture.head(), 1, true, fixture.unrelated());
            assertEquals(fixture.head(), read(store, view, "rev-parse", "HEAD").strip());
            assertEquals(
                    fixture.unrelated(), read(store, view, "rev-parse", "BASE").strip());
            assertEquals("content-2", read(store, view, "show", "HEAD:file"));
            assertEquals("private-unrelated", read(store, view, "show", "BASE:file"));
            assertEquals("unchanged content\n", read(store, view, "show", "HEAD:unchanged/deep/shared"));
            final var expected = new HashSet<String>(List.of(fixture.head(), fixture.unrelated()));
            for (final var sha : List.of(fixture.head(), fixture.unrelated())) {
                expected.add(
                        git(fixture.directory(), "rev-parse", sha + "^{tree}").strip());
                git(fixture.directory(), "ls-tree", "-rt", sha)
                        .lines()
                        .forEach(line -> expected.add(TREE_FIELD_SEPARATOR.split(line)[2]));
            }
            expected.add(
                    git(fixture.directory(), "rev-parse", "refs/tags/outside").strip());
            final var actual =
                    Set.copyOf(git(view.directory(), "cat-file", "--batch-all-objects", "--batch-check=%(objectname)")
                            .lines()
                            .toList());
            assertEquals(expected, actual);
            assertEquals("outside\n", read(store, view, "tag"));
            assertNotEquals(
                    0,
                    store.read(view, List.of("cat-file", "-t", fixture.parent()))
                            .exitCode());
            assertNotEquals(
                    0,
                    store.read(view, List.of("cat-file", "-t", fixture.old())).exitCode());
            assertFalse(read(store, view, "diff", "BASE..HEAD").isEmpty());
            final var missingMergeBase = store.read(view, List.of("diff", "BASE...HEAD"));
            assertNotEquals(0, missingMergeBase.exitCode());
            assertEquals("", missingMergeBase.stdout()); // Never substitute a two-dot diff.

            final var nativeRepo = Files.createDirectory(temporary.resolve("native.git"));
            git(nativeRepo, "init", "--bare", "--template=");
            git(
                    nativeRepo,
                    "fetch",
                    "--no-tags",
                    "--depth=1",
                    "file://" + fixture.directory(),
                    fixture.head(),
                    fixture.unrelated());
            assertEquals(
                    Set.copyOf(Files.readAllLines(nativeRepo.resolve("shallow"))),
                    Set.copyOf(Files.readAllLines(view.directory().resolve("shallow"))));
            git(view.directory(), "fsck", "--full", "--strict", "--no-reflogs");
        }
    }

    @Test
    void nativeWorkerShallowCloneUsesBothApprovedRootsOverActualCgiGetAndPost() throws Exception {
        final var fixture = fixture();
        final var upstream = copyFixture(temporary.resolve("upstream")).directory();
        git(upstream, "checkout", "--detach", fixture.parent());
        Files.writeString(upstream.resolve("base-only"), "upstream change\n");
        git(upstream, "add", ".");
        git(upstream, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-m", "upstream base");
        final var base = git(upstream, "rev-parse", "HEAD").strip();
        try (final var store = new TrustedGitStore(
                        temporary.resolve("private"),
                        transport(fixture, upstream).toString(),
                        Duration.ofSeconds(15),
                        BUDGET,
                        67108864L,
                        () -> "host-secret");
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var view =
                    store.prepare(URL, fixture.head(), 2, false, base, "https://github.com/yupiik/upstream.git");
            final var reachable = Set.copyOf(
                    git(view.directory(), "rev-list", "--objects", "--no-object-names", "HEAD", "refs/heads/base")
                            .lines()
                            .toList());
            assertEquals(
                    reachable,
                    Set.copyOf(git(view.directory(), "cat-file", "--batch-all-objects", "--batch-check=%(objectname)")
                            .lines()
                            .toList()));
            assertEquals(
                    fixture.parent(),
                    Files.readString(view.directory().resolve("shallow")).strip());
            final var gets = new AtomicInteger();
            final var posts = new AtomicInteger();
            final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/repo.git/", exchange -> {
                try (exchange) {
                    try {
                        final var method = exchange.getRequestMethod();
                        if ("GET".equals(method)) {
                            gets.incrementAndGet();
                            assertEquals(
                                    "/repo.git/info/refs",
                                    exchange.getRequestURI().getPath());
                        } else {
                            posts.incrementAndGet();
                            assertEquals(
                                    "/repo.git/git-upload-pack",
                                    exchange.getRequestURI().getPath());
                        }
                        try (final var response = store.httpStream(
                                view,
                                method,
                                exchange.getRequestURI().getRawQuery(),
                                exchange.getRequestBody().readAllBytes(),
                                exchange.getRequestHeaders().getFirst("Git-Protocol"))) {
                            response.headers()
                                    .forEach((name, value) ->
                                            exchange.getResponseHeaders().set(name, value));
                            exchange.sendResponseHeaders(response.status(), response.length());
                            response.body().transferTo(exchange.getResponseBody());
                        }
                    } catch (final Exception error) {
                        exchange.sendResponseHeaders(500, -1);
                    }
                }
            });
            server.start();
            try {
                final var worker = temporary.resolve("worker.git");
                git(
                        temporary,
                        "clone",
                        "--bare",
                        "--depth=2",
                        "--no-single-branch",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/repo.git",
                        worker.toString());
                assertTrue(gets.get() > 0);
                assertTrue(posts.get() > 0);
                assertEquals(fixture.head(), git(worker, "rev-parse", "HEAD").strip());
                assertEquals(base, git(worker, "rev-parse", "refs/heads/base").strip());
                assertEquals(base, read(store, view, "rev-parse", "BASE").strip());
                final var diff = read(store, view, "diff", "BASE...HEAD");
                assertEquals(git(worker, "diff", "refs/heads/base...HEAD"), diff);
                assertFalse(diff.isEmpty());
                assertEquals("content-2", git(worker, "show", "HEAD:file"));
                assertEquals("content-1", git(worker, "show", "refs/heads/base:file"));
                assertEquals("unchanged content\n", git(worker, "show", "HEAD:unchanged/deep/shared"));
                assertTrue(Files.exists(worker.resolve("shallow")));
                git(worker, "fsck", "--full", "--strict");
                final var hiddenBlob = git(fixture.directory(), "rev-parse", fixture.old() + ":file")
                        .strip();
                for (final var version : List.of("0", "1", "2")) {
                    for (final var hidden : List.of(fixture.old(), fixture.unrelated(), fixture.tag(), hiddenBlob)) {
                        final var process = new ProcessBuilder(
                                        "/usr/bin/git",
                                        "-c",
                                        "protocol.version=" + version,
                                        "--git-dir=" + worker,
                                        "fetch",
                                        "--no-tags",
                                        "http://127.0.0.1:"
                                                + server.getAddress().getPort() + "/repo.git",
                                        hidden)
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .redirectError(ProcessBuilder.Redirect.DISCARD)
                                .start();
                        try {
                            process.getOutputStream().close();
                            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
                            assertNotEquals(
                                    0, process.exitValue(), "Unauthorized object fetched over protocol " + version);
                        } finally {
                            process.destroyForcibly();
                        }
                    }
                }
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void explicitForkBaseAuthorizesOldRootButNotIncidentalHistoryOrUpstreamTags() throws Exception {
        final var fixture = fixture();
        final var upstream = copyFixture(temporary.resolve("upstream")).directory();
        git(upstream, "tag", "upstream-only", fixture.old());
        try (final var store = new TrustedGitStore(
                temporary.resolve("private"),
                transport(fixture, upstream).toString(),
                Duration.ofSeconds(15),
                BUDGET,
                67108864L,
                null)) {
            final var view = store.prepare(
                    URL, fixture.head(), 1, true, fixture.old(), "https://github.com/yupiik/upstream.git");
            assertEquals(fixture.old(), read(store, view, "rev-parse", "BASE").strip());
            assertEquals("content-0", read(store, view, "show", "BASE:file"));
            assertEquals("", read(store, view, "tag"));
            assertNotEquals(
                    0,
                    store.read(view, List.of("cat-file", "-t", fixture.parent()))
                            .exitCode());
            assertNotEquals(
                    0,
                    store.read(view, List.of("cat-file", "-t", fixture.unrelated()))
                            .exitCode());
            assertNotEquals(0, store.read(view, List.of("diff", "BASE...HEAD")).exitCode());
            final var objects = Set.copyOf(
                    git(view.directory(), "rev-list", "--objects", "--no-object-names", "HEAD", "refs/heads/base")
                            .lines()
                            .toList());
            assertEquals(
                    objects,
                    Set.copyOf(git(view.directory(), "cat-file", "--batch-all-objects", "--batch-check=%(objectname)")
                            .lines()
                            .toList()));
            assertEquals(
                    Set.of(fixture.old(), fixture.head()),
                    Set.copyOf(Files.readAllLines(view.directory().resolve("shallow"))));
        }
    }

    private String read(final TrustedGitStore store, final TrustedGitStore.View view, final String... args)
            throws IOException {
        final var result = store.read(view, List.of(args));
        assertEquals(0, result.exitCode(), result.stdout());
        assertFalse(result.truncated());
        return result.stdout();
    }

    @Test
    void rejectsMovedPreviouslyEligibleTagWithoutChangingPublishedGeneration() throws Exception {
        final var fixture = fixture();
        try (final var store = new TrustedGitStore(
                temporary.resolve("private"),
                transport(fixture).toString(),
                Duration.ofSeconds(15),
                BUDGET,
                67108864L,
                null)) {
            final var original = store.prepare(URL, fixture.head(), 2, true);
            git(fixture.directory(), "tag", "-f", "-a", "inside", "-m", "rebound", fixture.head());
            final var failure = assertThrows(IOException.class, () -> store.prepare(URL, fixture.head(), 2, true));
            assertTrue(failure.getMessage().contains("tag has moved"));
            assertThrows(
                    IOException.class, () -> store.prepare(URL.replace("yupiik", "YupIik"), fixture.head(), 2, true));
            assertEquals(
                    fixture.tag(),
                    read(store, original, "rev-parse", "refs/tags/inside").strip());
            assertEquals(
                    fixture.head(),
                    read(store, store.prepare(URL, fixture.head(), 1, false), "rev-parse", "HEAD")
                            .strip());
        }
    }

    @Test
    void validatesHttpBufferCapBeforeCreatingPrivateStorage() throws Exception {
        final var root = temporary.resolve("private");
        for (final long cap : new long[] {Long.MIN_VALUE, -1, 0, Integer.MAX_VALUE - 7L, Long.MAX_VALUE}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new TrustedGitStore(root, "git", Duration.ofSeconds(1), BUDGET, cap, null));
        }
        for (final long cap : new long[] {1, 67108864L, Integer.MAX_VALUE - 8L}) {
            try (final var _ = new TrustedGitStore(root, "git", Duration.ofSeconds(1), BUDGET, cap, null)) {
                assertFalse(Files.exists(root)); // Validation does not allocate cap-sized buffers or storage.
            }
        }
    }

    @Test
    void acceptsExactHttpBufferBoundaryAndRejectsRequestOneByteOverForBothApis() throws Exception {
        final var fixture = fixture();
        final var root = temporary.resolve("private");
        final int cap = 32;
        try (final var store =
                new TrustedGitStore(root, transport(fixture).toString(), Duration.ofSeconds(15), BUDGET, cap, null)) {
            final var view = store.prepare(URL, fixture.head(), 1, false);
            final var payload = new byte[cap];
            Arrays.fill(payload, (byte) 0xa5);
            try (final var output = Files.newOutputStream(temporary.resolve("cgi-response"))) {
                output.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                output.write(payload);
            }
            final var response = store.http(view, "POST", "", payload, null);
            assertEquals(200, response.status());
            assertArrayEquals(payload, response.body());
            assertNull(spool(root));
            try (final var stream = store.httpStream(view, "POST", "", payload, null)) {
                assertEquals(cap, stream.length());
                assertArrayEquals(payload, stream.body().readAllBytes());
            }
            assertNull(spool(root));
            assertThrows(IllegalArgumentException.class, () -> store.http(view, "POST", "", new byte[cap + 1], null));
            assertThrows(
                    IllegalArgumentException.class, () -> store.httpStream(view, "POST", "", new byte[cap + 1], null));
            assertNull(spool(root));
        }
    }

    @Test
    void binarySpoolingExceedsConfiguredCompatibilityLimitAndCleansOnCloseOrRejection() throws Exception {
        final var fixture = fixture();
        final var root = temporary.resolve("private");
        final var executable = transport(fixture);
        final int cap = 32 * 1024;
        try (final var store =
                new TrustedGitStore(root, executable.toString(), Duration.ofSeconds(30), BUDGET, cap, null)) {
            final var view = store.prepare(URL, fixture.head(), 1, false);
            final long length = cap + 1;
            try (final var output = Files.newOutputStream(temporary.resolve("cgi-response"))) {
                output.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                final var buffer = new byte[8192];
                Arrays.fill(buffer, (byte) 0xa5);
                for (long written = 0; written < length; written += buffer.length) {
                    output.write(buffer, 0, (int) Math.min(buffer.length, length - written));
                }
            }
            try (final var response = store.httpStream(view, "GET", "service=git-upload-pack", new byte[0], null)) {
                assertEquals(200, response.status());
                assertEquals(length, response.length());
                final var spool = spool(root);
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(spool));
                assertEquals(0xa5, response.body().read());
                assertEquals(length - 1, response.body().transferTo(OutputStream.nullOutputStream()));
                assertTrue(Files.exists(spool));
            }
            assertNull(spool(root));
            final var failure = assertThrows(
                    IOException.class, () -> store.http(view, "GET", "service=git-upload-pack", new byte[0], null));
            assertEquals("Compatibility HTTP output exceeds limit; use httpStream", failure.getMessage());
            assertNull(spool(root));
            final var response = store.httpStream(view, "GET", "service=git-upload-pack", new byte[0], null);
            final var spool = spool(root);
            response.body().close(); // Closing the body alone also owns cleanup.
            response.close();
            assertFalse(Files.exists(spool));
        }
    }

    @Test
    void createsIndependentPackLargerThanConfiguredHttpBufferCap() throws Exception {
        final var fixture = fixture();
        // Cross the configured boundary, not the default 64 MiB cap or the JVM heap size.
        // The other pack/transport tests retain Git's normal compression settings.
        final int cap = 1024 * 1024;
        final long length = cap + 8192L;
        git(fixture.directory(), "config", "core.compression", "0");
        try (final var output = Files.newOutputStream(fixture.directory().resolve("binary"))) {
            final var buffer = new byte[8192];
            Arrays.fill(buffer, (byte) 0xa5);
            for (long written = 0; written < length; written += buffer.length) {
                output.write(buffer);
            }
        }
        git(fixture.directory(), "add", "binary");
        git(fixture.directory(), "commit", "-m", "large binary");
        final var head = git(fixture.directory(), "rev-parse", "HEAD").strip();
        final var executable = transport(fixture);
        Files.writeString(
                executable,
                Files.readString(executable)
                        .replace("exec /usr/bin/git \"$@\"", "exec /usr/bin/git -c core.compression=0 \"$@\""));
        try (final var store = new TrustedGitStore(
                temporary.resolve("private"), executable.toString(), Duration.ofSeconds(45), BUDGET, cap, null)) {
            final var view = store.prepare(URL, head, 1, false);
            assertEquals(
                    Long.toString(length),
                    read(store, view, "cat-file", "-s", "HEAD:binary").strip());
            git(view.directory(), "fsck", "--full", "--strict");
            try (final var paths = Files.list(view.directory().resolve("objects/pack"))) {
                assertTrue(paths.anyMatch(path -> {
                    try {
                        return path.toString().endsWith(".pack") && Files.size(path) > cap;
                    } catch (final IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }));
            }
        }
    }

    private Path spool(final Path root) throws IOException {
        try (final var paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".spool"))
                    .findFirst()
                    .orElse(null);
        }
    }

    @Test
    void pendingResponsesCountTowardDiskBudgetAndFailedSpoolsAreRemoved() throws Exception {
        final var fixture = fixture();
        final var root = temporary.resolve("private");
        try (final var store = new TrustedGitStore(
                root, transport(fixture).toString(), Duration.ofSeconds(15), BUDGET, 67108864L, null)) {
            final var first = store.prepare(URL, fixture.head(), 1, false);
            store.prepare(URL, fixture.head(), 2, true); // Old and current generations share the disk budget.
            try (final var output = Files.newOutputStream(temporary.resolve("cgi-response"))) {
                output.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                final var buffer = new byte[8192];
                for (int i = 0; i < 640; i++) {
                    output.write(buffer);
                }
            }
            try (final var response = store.httpStream(first, "GET", "service=git-upload-pack", new byte[0], null)) {
                final var retained = spool(root);
                assertThrows(
                        IOException.class,
                        () -> store.httpStream(first, "GET", "service=git-upload-pack", new byte[0], null));
                try (final var paths = Files.walk(root)) {
                    assertEquals(
                            List.of(retained),
                            paths.filter(path -> path.toString().endsWith(".spool"))
                                    .toList());
                }
                assertEquals(5L * 1024 * 1024, response.length());
            }
            assertNull(spool(root));
            final var response = store.httpStream(first, "GET", "service=git-upload-pack", new byte[0], null);
            try (response) {
                Assertions.assertDoesNotThrow(store::close);
                assertEquals(5L * 1024 * 1024, response.body().transferTo(OutputStream.nullOutputStream()));
            }
        }
    }

    @Test
    void boundsAnnotatedTagTextBeforePublication() throws Exception {
        final var fixture = fixture();
        final var message = temporary.resolve("message");
        Files.writeString(message, "x".repeat(65536));
        git(fixture.directory(), "tag", "-a", "large", "-F", message.toString(), fixture.head());
        final var root = temporary.resolve("private");
        try (final var store = new TrustedGitStore(
                root, transport(fixture).toString(), Duration.ofSeconds(15), BUDGET, 67108864L, null)) {
            assertThrows(IOException.class, () -> store.prepare(URL, fixture.head(), 2, true));
            try (final var paths = Files.walk(root)) {
                assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith("generation-")));
            }
            final var view = store.prepare(URL, fixture.head(), 1, false);
            assertEquals(fixture.head(), read(store, view, "rev-parse", "HEAD").strip());
        }
    }

    @Test
    void boundsTagRefCountBeforeWalkingTagChains() throws Exception {
        final var fixture = fixture();
        // Retain existing refs without a sorted header: the generated tag names are not sorted.
        final var existingRefs = git(fixture.directory(), "for-each-ref", "--format=%(objectname) %(refname)");
        try (final var output =
                Files.newBufferedWriter(fixture.directory().resolve(".git/packed-refs"), StandardCharsets.US_ASCII)) {
            output.write(existingRefs);
            for (int i = 0; i < 10001; i++) {
                output.write(fixture.head() + " refs/tags/many-" + i + "\n");
            }
        }
        try (final var store = new TrustedGitStore(
                temporary.resolve("private"),
                transport(fixture).toString(),
                Duration.ofSeconds(30),
                32L * 1024 * 1024,
                67108864L,
                null)) {
            final var error = assertThrows(IOException.class, () -> store.prepare(URL, fixture.head(), 1, true));
            assertEquals("Too many Git tags", error.getMessage());
        }
    }

    @Test
    void boundsObjectMetadataCountBeforePacking() throws Exception {
        final var fixture = fixture();
        final var executable = transport(fixture);
        final var metadata = temporary.resolve("object-metadata");
        try (final var output = Files.newBufferedWriter(metadata, StandardCharsets.US_ASCII)) {
            for (int i = 0; i < 100001; i++) {
                final var value = Integer.toHexString(i);
                output.write("0".repeat(40 - value.length()) + value + "\n");
            }
        }
        try (final var store = new TrustedGitStore(
                temporary.resolve("private"),
                executable.toString(),
                Duration.ofSeconds(15),
                32L * 1024 * 1024,
                67108864L,
                null)) {
            final var error = assertThrows(IOException.class, () -> store.prepare(URL, fixture.head(), 1, false));
            assertEquals("Invalid Git object metadata", error.getMessage());
            Files.delete(metadata);
            assertEquals(
                    fixture.head(),
                    read(store, store.prepare(URL, fixture.head(), 1, false), "rev-parse", "HEAD")
                            .strip());
        }
    }

    @Test
    void toleratesTransientSymlinkProbesWhileInitializing() throws Exception {
        final var fixture = fixture();
        final var executable = initializationSymlink(transport(fixture), true);
        try (final var store = new TrustedGitStore(
                temporary.resolve("private"), executable.toString(), Duration.ofSeconds(15), BUDGET, 67108864L, null)) {
            final var view = store.prepare(URL, fixture.head(), 1, false);
            assertEquals(fixture.head(), read(store, view, "rev-parse", "HEAD").strip());
            try (final var paths = Files.walk(view.directory())) {
                assertFalse(paths.anyMatch(Files::isSymbolicLink));
            }
        }
    }

    @Test
    void rejectsSymlinksRemainingAfterInitialization() throws Exception {
        final var fixture = fixture();
        final var executable = initializationSymlink(transport(fixture), false);
        final var root = temporary.resolve("private");
        try (final var store =
                new TrustedGitStore(root, executable.toString(), Duration.ofSeconds(15), BUDGET, 67108864L, null)) {
            final var error = assertThrows(IOException.class, () -> store.prepare(URL, fixture.head(), 1, false));
            assertEquals("Symbolic link in trusted Git storage", error.getMessage());
            try (final var paths = Files.walk(root)) {
                assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith("generation-")));
            }
        }
    }

    private Path initializationSymlink(final Path executable, final boolean transientLink) throws IOException {
        // Widen Git init's native symlink("testing", "tXXXXXX") probe so the quota sampler sees it.
        Files.writeString(executable, Files.readString(executable).replace("exec /usr/bin/git \"$@\"", """
                                initializing=false
                                for argument do
                                    if [ "$argument" = init ]; then
                                        initializing=true
                                    fi
                                    directory=$argument
                                done
                                if [ "$initializing" = true ]; then
                                    /usr/bin/git "$@" || exit 1
                                    /bin/ln -s testing "$directory/t-probe" || exit 1
                                    /bin/sleep 0.2
                                    %s
                                    exit 0
                                fi
                                exec /usr/bin/git "$@"
                                """.formatted(
                        transientLink ? "/bin/rm \"$directory/t-probe\"" : ":")));
        return executable;
    }

    private record Fixture(
            Path directory, String old, String parent, String head, String unrelated, String tag, String nested) {}

    private Fixture fixture() throws IOException {
        return copyFixture(temporary.resolve("fixture with 'quotes'"));
    }

    private static Fixture copyFixture(final Path directory) throws IOException {
        // Copy all worktree and Git metadata bytes; never clone, link, or share writable objects with
        // the seed.
        try (final var paths = Files.walk(seed.directory())) {
            for (final var source : paths.toList()) {
                final var target = directory.resolve(seed.directory().relativize(source));
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
        return new Fixture(
                directory, seed.old(), seed.parent(), seed.head(), seed.unrelated(), seed.tag(), seed.nested());
    }

    @Test
    void fixtureCopiesKeepAllMetadataAndObjectsIndependentAtQuotedPaths() throws Exception {
        final var first = fixture();
        final var second = copyFixture(temporary.resolve("second fixture with 'quotes'"));
        try (final var paths = Files.walk(seed.directory())) {
            for (final var source : paths.filter(Files::isRegularFile).toList()) {
                final var relative = seed.directory().relativize(source);
                final var original = Files.readAllBytes(source);
                final var firstFile = first.directory().resolve(relative);
                final var secondFile = second.directory().resolve(relative);
                assertFalse(Files.isSameFile(source, firstFile), relative.toString());
                assertFalse(Files.isSameFile(firstFile, secondFile), relative.toString());
                assertArrayEquals(original, Files.readAllBytes(firstFile), relative.toString());
                // Loose Git objects are read-only; make only this private copy writable before mutation.
                Files.setPosixFilePermissions(firstFile, PosixFilePermissions.fromString("rw-------"));
                Files.writeString(firstFile, "copy-only mutation\n");
                assertArrayEquals(original, Files.readAllBytes(source), relative.toString());
                assertArrayEquals(original, Files.readAllBytes(secondFile), relative.toString());
            }
        }
        assertFalse(Files.exists(second.directory().resolve(".git/objects/info/alternates")));
        assertEquals(seed.head(), git(second.directory(), "rev-parse", "HEAD").strip());
    }

    private static Fixture buildFixture(final Path temporary) throws Exception {
        final var repo = Files.createDirectory(temporary.resolve("fixture with 'quotes'"));
        git(repo, "init", "--template=");
        git(repo, "config", "user.name", "Test");
        git(repo, "config", "user.email", "test@example.invalid");
        final var shas = new ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            Files.createDirectories(repo.resolve("unchanged/deep"));
            Files.writeString(repo.resolve("unchanged/deep/shared"), "unchanged content\n");
            Files.writeString(repo.resolve("file"), "content-" + i);
            git(repo, "add", ".");
            git(repo, "commit", "-m", "commit-" + i);
            shas.add(git(repo, "rev-parse", "HEAD").strip());
        }
        git(repo, "tag", "-a", "inside", "-m", "original annotation", shas.get(1));
        git(repo, "tag", "-a", "nested", "-m", "nested annotation", "inside");
        final var tag = git(repo, "rev-parse", "refs/tags/inside").strip();
        final var nested = git(repo, "rev-parse", "refs/tags/nested").strip();
        git(repo, "checkout", "--orphan", "unrelated");
        Files.writeString(repo.resolve("file"), "private-unrelated");
        git(repo, "add", "file");
        git(repo, "commit", "-m", "unrelated");
        final var unrelated = git(repo, "rev-parse", "HEAD").strip();
        git(repo, "tag", "-a", "outside", "-m", "unrelated annotation");
        git(repo, "checkout", "--detach", shas.get(2));
        return new Fixture(repo, shas.get(0), shas.get(1), shas.get(2), unrelated, tag, nested);
    }

    /** Substitute transport in the trusted executable, not in the production store's API or config. */
    private Path transport(final Fixture fixture) throws IOException {
        return transport(fixture, fixture.directory());
    }

    private Path transport(final Fixture fixture, final Path upstream) throws IOException {
        final var wrapper = temporary.resolve("git-wrapper with 'quotes'");
        // Keep ordinary calls native: no JVM and no environment-dump subprocess outside HTTP serving.
        // Fixed test-only substitution; all other commands still exec real Git with their original
        // argv/stdin.
        Files.writeString(wrapper, """
            #!/bin/sh
            response=%s
            metadata=%s
            if [ -e "$response" ]; then
                exec /bin/cat "$response"
            fi
            fetch=false
            serving=false
            for argument do
                if [ "$argument" = rev-list ] && [ -e "$metadata" ]; then
                    exec /bin/cat "$metadata"
                fi
                if [ "$argument" = fetch ]; then
                    fetch=true
                fi
                if [ "$argument" = http-backend ]; then
                    serving=true
                fi
            done
            if [ "$fetch" = true ]; then
                if [ "${YCA_GIT_TOKEN+x}" = x ]; then
                    credential=$(printf 'protocol=https\\nhost=github.com\\n\\n' |
                        /usr/bin/git -c credential.helper= -c "credential.helper=$YCA_GIT_CREDENTIAL_HELPER" credential fill) || exit 1
                    case "$credential" in
                        *"password=$YCA_GIT_TOKEN"*) ;;
                        *) exit 1 ;;
                    esac
                fi
                for argument do
                    shift
                    case "$argument" in
                        https://github.com/yupiik/fixture.git) argument=%s ;;
                        https://github.com/yupiik/upstream.git) argument=%s ;;
                    esac
                    set -- "$@" "$argument"
                done
                printf '%%s' "${YCA_GIT_TOKEN-anonymous}" > %s
                set -- -c protocol.file.allow=always "$@"
            elif [ "$serving" = true ]; then
                /usr/bin/env > %s
            fi
            exec /usr/bin/git "$@"
            """.formatted(
                        shell(temporary.resolve("cgi-response").toString()),
                        shell(temporary.resolve("object-metadata").toString()),
                        shell(fixture.directory().toUri().toASCIIString()),
                        shell(upstream.toUri().toASCIIString()),
                        shell(temporary.resolve("fetch-auth").toString()),
                        shell(temporary.resolve("serving-environment").toString())));
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwx------"));
        return wrapper;
    }

    private static String shell(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String git(final Path directory, final String... args) throws Exception {
        final var command = new ArrayList<>(List.of("/usr/bin/git", "-c", "core.hooksPath=/dev/null"));
        if (Files.exists(directory.resolve("HEAD"))) {
            command.add("--git-dir=" + directory);
        }
        command.addAll(Arrays.asList(args));
        final var outputFile = Files.createTempFile("trusted-git-test-", ".output");
        final var builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectOutput(outputFile.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        Process process = null;
        try {
            process = builder.start();
            process.getOutputStream().close();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Git subprocess deadline: " + String.join(" ", command));
            final var output = Files.readString(outputFile, StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), String.join(" ", command) + "\n" + output);
            return output;
        } finally {
            try {
                if (process != null) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    boolean interrupted = false;
                    try {
                        while (true) {
                            try {
                                process.waitFor();
                                break;
                            } catch (final InterruptedException ignored) {
                                interrupted = true;
                            }
                        }
                    } finally {
                        if (interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            } finally {
                Files.deleteIfExists(outputFile);
            }
        }
    }
}
