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
package io.yupiik.ia.agent.slack.context;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.llm.JsonMappers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(10)
class SlackContextMailboxTest {
    @TempDir
    Path home;

    @Test
    void correlatedRoundTripAndAutomaticCleanup() throws Exception {
        final var root = privateDirectory("box");
        try (final var json = json();
                final var controller = new SlackContextMailbox(root, json, "C1");
                final var agent = new SlackContextMailbox(root, json, "C1");
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            assertNull(controller.pending());
            final var result =
                    executor.submit(() -> agent.request(Map.of("operation", "summary"), deadline(), () -> false));
            final var request = await(controller);
            assertEquals("C1", request.channel());
            assertEquals(1, request.version());
            assertEquals(Map.of("operation", "summary"), request.arguments());
            assertEquals(UUID.fromString(request.id()).toString(), request.id());
            final Map<String, Object> body = Map.of("status", "ok", "messages", List.of(Map.of("text", "context")));
            controller.respond(request, body);
            assertEquals(body, result.get(2, TimeUnit.SECONDS));
            assertNull(controller.pending());
            assertFalse(Files.exists(root.resolve("response.json")));
            try (final var files = Files.list(root)) {
                assertEquals(0, files.count());
            }
        }
    }

    @Test
    void repeatedResponsePreservesThePublishedDocumentAndCanRetryAfterRemoval() throws Exception {
        final var root = privateDirectory("box");
        try (final var json = json();
                final var controller = new SlackContextMailbox(root, json, "C1")) {
            final var request = request("C1", deadline());
            document(root, "request.json", json.toString(request));
            controller.respond(request, Map.of("status", "ok", "text", "first"));
            final var original = Files.readString(root.resolve("response.json"));
            controller.respond(request, Map.of("status", "ok", "text", "replacement"));
            assertEquals(
                    original,
                    Files.readString(root.resolve("response.json")),
                    "A controller poll must not replace a response the client may already be consuming");

            Files.delete(root.resolve("response.json"));
            controller.respond(request, Map.of("status", "ok", "text", "first"));
            assertEquals(
                    original,
                    Files.readString(root.resolve("response.json")),
                    "A still-pending request can retry a removed response");

            // The client removes the request before removing its consumed response.
            controller.clear();
            assertThrows(IOException.class, () -> controller.respond(request, Map.of("status", "ok")));
            assertFalse(Files.exists(root.resolve("request.json")));
            assertFalse(Files.exists(root.resolve("response.json")));
        }
    }

    @Test
    void rejectsUntrustedDirectoryPathsAndPermissions() throws Exception {
        final var root = privateDirectory("box");
        final var nested = Files.createDirectory(
                root.resolve("nested"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        final var link = Files.createSymbolicLink(home.resolve("link"), root);
        try (final var json = json()) {
            assertThrows(IOException.class, () -> new SlackContextMailbox(link, json, "C1"));
            assertThrows(IOException.class, () -> new SlackContextMailbox(link.resolve("nested"), json, "C1"));
            assertThrows(IOException.class, () -> new SlackContextMailbox(nested.resolve(".."), json, "C1"));
            assertThrows(IOException.class, () -> new SlackContextMailbox(home.resolve("missing"), json, "C1"));
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-x---"));
            assertThrows(IOException.class, () -> new SlackContextMailbox(root, json, "C1"));
        }
    }

    @Test
    void replacedRootCannotRedirectPinnedHandle() throws Exception {
        final var root = privateDirectory("box");
        try (final var json = json();
                final var mailbox = new SlackContextMailbox(root, json, "C1");
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var original = Files.move(root, home.resolve("original"));
            final var hostile = privateDirectory("hostile");
            Files.createSymbolicLink(root, hostile);
            final var result =
                    executor.submit(() -> mailbox.request(Map.of("operation", "summary"), deadline(), () -> false));
            final var request = await(mailbox);
            assertTrue(Files.exists(original.resolve("request.json")));
            assertFalse(Files.exists(hostile.resolve("request.json")));
            mailbox.respond(request, Map.of("status", "ok"));
            assertEquals(Map.of("status", "ok"), result.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectsSymlinkDocumentsAndClearsWithoutFollowingThem() throws Exception {
        final var root = privateDirectory("box");
        final var target = Files.writeString(home.resolve("secret"), "private");
        Files.createSymbolicLink(root.resolve("request.json"), target);
        Files.createSymbolicLink(root.resolve("response.json"), target);
        try (final var json = json();
                final var mailbox = new SlackContextMailbox(root, json, "C1")) {
            assertThrows(IOException.class, mailbox::pending);
            mailbox.clear();
            assertEquals("private", Files.readString(target));
            assertNull(mailbox.pending());
        }
    }

    @Test
    void rejectsOversizedEmptyPublicDirectoryAndMalformedUtf8Requests() throws Exception {
        final var root = privateDirectory("box");
        try (final var json = json();
                final var mailbox = new SlackContextMailbox(root, json, "C1")) {
            document(root, "request.json", "x".repeat(16 * 1024 + 1));
            assertThrows(IOException.class, mailbox::pending);
            document(root, "request.json", "");
            assertThrows(IOException.class, mailbox::pending);
            document(root, "request.json", json.toString(request("C1", deadline())));
            Files.setPosixFilePermissions(root.resolve("request.json"), PosixFilePermissions.fromString("rw-r--r--"));
            assertThrows(IOException.class, mailbox::pending);
            Files.setPosixFilePermissions(root.resolve("request.json"), PosixFilePermissions.fromString("rw-------"));
            Files.write(root.resolve("request.json"), new byte[] {(byte) 0xc3, 0x28});
            assertThrows(IOException.class, mailbox::pending);
            Files.delete(root.resolve("request.json"));
            Files.createDirectory(root.resolve("request.json"));
            assertThrows(IOException.class, mailbox::pending);
        }
    }

    @Test
    void rejectsFifoWithoutWaitingForProducer() throws Exception {
        final var root = privateDirectory("box");
        final var process =
                new ProcessBuilder("mkfifo", root.resolve("request.json").toString()).start();
        assertTrue(process.waitFor(1, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        try (final var json = json();
                final var mailbox = new SlackContextMailbox(root, json, "C1")) {
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(IOException.class, mailbox::pending));
        }
    }

    @Test
    void bindsChannelNonceVersionAndDeadline() throws Exception {
        final var root = privateDirectory("box");
        try (final var json = json();
                final var mailbox = new SlackContextMailbox(root, json, "C1")) {
            for (final var request :
                    List.of(request("C2", deadline()), request("C1", 1), request("C1", Long.MAX_VALUE))) {
                document(root, "request.json", json.toString(request));
                assertThrows(IOException.class, mailbox::pending);
            }
            final var valid = request("C1", deadline());
            final var encoded = json.toString(valid);
            for (final var malformed : List.of(
                    encoded.replace("\"version\":1", "\"version\":2"),
                    encoded.replace(valid.id(), "------------------------------------"),
                    "null",
                    "{}")) {
                document(root, "request.json", malformed);
                assertThrows(IOException.class, mailbox::pending);
            }
            document(root, "request.json", encoded);
            assertEquals(valid, mailbox.pending());
            assertThrows(IOException.class, () -> mailbox.respond(request("C1", deadline()), Map.of("status", "ok")));
            assertThrows(IOException.class, () -> mailbox.respond(request("C2", deadline()), Map.of("status", "ok")));
        }
    }

    @Test
    void boundedRegexFastPathsRejectHugeIdentifiers() {
        assertTrue(SlackContextMailbox.validChannel("C1"));
        assertTrue(SlackContextMailbox.validChannel("GABC123"));
        for (final var invalid : List.of("", "C", "C.*", "c1", "C1\n", "C" + "A".repeat(100_000))) {
            assertFalse(SlackContextMailbox.validChannel(invalid));
        }
        assertTimeout(Duration.ofSeconds(1), () -> {
            for (var i = 0; i < 100; i++) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new SlackContextMailbox.Request(1, "C1", "a".repeat(100_000), deadline(), Map.of()));
            }
        });
    }

    @Test
    void utf8RequestAndResponsePublicationLimitsLeaveNoTemporaryFiles() throws Exception {
        final var root = privateDirectory("box");
        try (final var json = json();
                final var mailbox = new SlackContextMailbox(root, json, "C1")) {
            assertThrows(
                    IOException.class,
                    () -> mailbox.request(Map.of("terms", List.of("\u20ac".repeat(6000))), deadline(), () -> false));
            assertNull(mailbox.pending());
            final var request = request("C1", deadline());
            document(root, "request.json", json.toString(request));
            assertThrows(
                    IOException.class,
                    () -> mailbox.respond(request, Map.of("status", "ok", "text", "\u20ac".repeat(50_000))));
            try (final var files = Files.list(root)) {
                assertEquals(
                        List.of("request.json"),
                        files.map(p -> p.getFileName().toString()).toList());
            }
        }
    }

    @Test
    void rejectsStaleCrossChannelOversizedAndSymlinkResponses() throws Exception {
        final var root = privateDirectory("box");
        try (final var json = json();
                final var mailbox = new SlackContextMailbox(root, json, "C1");
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var variant = 0; variant < 4; variant++) {
                final var result = executor.submit(() -> {
                    assertThrows(
                            IOException.class,
                            () -> mailbox.request(Map.of("operation", "summary"), deadline(), () -> false));
                    return true;
                });
                final var request = await(mailbox);
                switch (variant) {
                    case 0 ->
                        document(
                                root,
                                "response.json",
                                json.toString(new SlackContextMailbox.Response(
                                        1, "C1", UUID.randomUUID().toString(), Map.of("status", "ok"))));
                    case 1 ->
                        document(
                                root,
                                "response.json",
                                json.toString(new SlackContextMailbox.Response(
                                        1, "C2", request.id(), Map.of("status", "ok"))));
                    case 2 -> document(root, "response.json", "x".repeat(128 * 1024 + 1));
                    case 3 -> Files.createSymbolicLink(root.resolve("response.json"), home.resolve("secret"));
                    default -> fail();
                }
                assertTrue(result.get(2, TimeUnit.SECONDS));
                assertNull(mailbox.pending());
            }
        }
    }

    @Test
    void cancellationTimeoutAndInterruptedWaitCleanUp() throws Exception {
        final var root = privateDirectory("box");
        try (final var json = json();
                final var mailbox = new SlackContextMailbox(root, json, "C1");
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThrows(IOException.class, () -> mailbox.request(Map.of(), deadline(), () -> true));
            assertNull(mailbox.pending());
            final var cancelled = new AtomicBoolean();
            final var result = executor.submit(() -> {
                assertThrows(IOException.class, () -> mailbox.request(Map.of(), deadline(), cancelled::get));
                return true;
            });
            await(mailbox);
            cancelled.set(true);
            assertTrue(result.get(2, TimeUnit.SECONDS));
            assertNull(mailbox.pending());
            assertThrows(
                    IOException.class, () -> mailbox.request(Map.of(), System.currentTimeMillis() + 100, () -> false));
            assertNull(mailbox.pending());
            final var interrupted = executor.submit(() -> {
                Thread.currentThread().interrupt();
                assertThrows(IOException.class, () -> mailbox.request(Map.of(), deadline(), () -> false));
                return Thread.currentThread().isInterrupted();
            });
            assertTrue(interrupted.get(2, TimeUnit.SECONDS));
            assertNull(mailbox.pending());
        }
    }

    @Test
    void callsAcrossInstancesSerializeAndLockWaitIsCancellableAndBounded() throws Exception {
        final var root = privateDirectory("box");
        try (final var json = json();
                final var first = new SlackContextMailbox(root, json, "C1");
                final var second = new SlackContextMailbox(root, json, "C1");
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var one =
                    executor.submit(() -> first.request(Map.of("operation", "summary"), deadline(), () -> false));
            final var original = await(first);
            final var cancelled = new AtomicBoolean();
            final var lockWaitEntered = new CountDownLatch(1);
            final var cancellationChecks = new AtomicInteger();
            final var waiting = executor.submit(() -> {
                final var failure = assertThrows(
                        IOException.class,
                        () -> second.request(Map.of("operation", "search"), deadline(), () -> {
                            // The second check follows a failed timed lock attempt while 'one' owns the stripe.
                            if (cancellationChecks.incrementAndGet() == 2) {
                                lockWaitEntered.countDown();
                            }
                            return cancelled.get();
                        }));
                assertEquals("Slack context request cancelled", failure.getMessage());
                return true;
            });
            assertTrue(lockWaitEntered.await(2, TimeUnit.SECONDS), "Second call must wait for the first call's lock");
            assertFalse(waiting.isDone());
            assertEquals(original, first.pending());
            cancelled.set(true);
            assertTrue(waiting.get(2, TimeUnit.SECONDS));
            assertTimeout(Duration.ofSeconds(1), () -> {
                assertThrows(
                        IOException.class,
                        () -> second.request(Map.of(), System.currentTimeMillis() + 100, () -> false));
            });
            assertEquals(original, first.pending());
            final var two =
                    executor.submit(() -> second.request(Map.of("operation", "search"), deadline(), () -> false));
            assertEquals(original, first.pending());
            first.respond(original, Map.of("status", "ok", "which", "one"));
            assertEquals("one", one.get(2, TimeUnit.SECONDS).get("which"));
            final var next = await(second);
            assertNotEquals(original.id(), next.id());
            assertEquals("search", next.arguments().get("operation"));
            assertThrows(IOException.class, () -> first.respond(original, Map.of("status", "ok")));
            second.respond(next, Map.of("status", "no_results"));
            assertEquals(Map.of("status", "no_results"), two.get(2, TimeUnit.SECONDS));
        }
    }

    private SlackContextMailbox.Request await(final SlackContextMailbox mailbox) throws Exception {
        final var until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < until) {
            final var request = mailbox.pending();
            if (request != null) {
                return request;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("No mailbox request published");
    }

    private static SlackContextMailbox.Request request(final String channel, final long expires) {
        return new SlackContextMailbox.Request(
                1, channel, UUID.randomUUID().toString(), expires, Map.of("operation", "summary"));
    }

    private static long deadline() {
        return System.currentTimeMillis() + 4000;
    }

    private Path privateDirectory(final String name) throws IOException {
        return Files.createDirectory(
                home.resolve(name), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    private static void document(final Path root, final String name, final String content) throws IOException {
        Files.writeString(root.resolve(name), content);
        Files.setPosixFilePermissions(root.resolve(name), PosixFilePermissions.fromString("rw-------"));
    }

    private static JsonMapper json() {
        return JsonMappers.create(ignored -> Optional.empty());
    }
}
