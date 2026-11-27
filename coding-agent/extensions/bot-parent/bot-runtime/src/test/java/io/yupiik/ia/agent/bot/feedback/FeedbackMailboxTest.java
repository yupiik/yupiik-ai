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
package io.yupiik.ia.agent.bot.feedback;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.llm.JsonMappers;
import io.yupiik.ia.agent.core.question.Question;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeedbackMailboxTest {
    @TempDir
    Path home;

    @Test
    void freeSingleAndMultipleRoundTrip() throws Exception {
        try (final var json = json();
                final var mailbox = new FeedbackMailbox(privateDirectory("box"), json)) {
            final var request = mailbox.request(
                    List.of(
                            new Question("text", "Explain", List.of(), false),
                            new Question("one", "Choose", List.of("a", "b"), false),
                            new Question("many", "Choose several", List.of("a", "b"), true)),
                    deadline());
            assertEquals(request, mailbox.pending());
            assertNull(mailbox.response(request.nonce()));
            final Map<String, Object> answers = Map.of("text", "hello", "one", "b", "many", List.of("a", "b"));
            mailbox.answer(request, answers, false);
            assertEquals(answers, mailbox.response(request.nonce()).answers());
            mailbox.clear();
            assertNull(mailbox.pending());
            assertNull(mailbox.response(request.nonce()));
        }
    }

    @Test
    void cancellationHasNoAnswers() throws Exception {
        try (final var json = json();
                final var mailbox = new FeedbackMailbox(privateDirectory("box"), json)) {
            final var request = mailbox.request(questions(), deadline());
            assertThrows(IllegalArgumentException.class, () -> mailbox.answer(request, Map.of("q", "hidden"), true));
            mailbox.answer(request, Map.of(), true);
            assertTrue(mailbox.response(request.nonce()).cancelled());
            assertEquals(Map.of(), mailbox.response(request.nonce()).answers());
        }
    }

    @Test
    void rejectsSymlinkRootAndAncestors() throws Exception {
        final var root = privateDirectory("box");
        final var link = Files.createSymbolicLink(home.resolve("link"), root);
        try (final var json = json()) {
            assertThrows(IOException.class, () -> new FeedbackMailbox(link, json));
            Files.createDirectory(
                    root.resolve("nested"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            assertThrows(IOException.class, () -> new FeedbackMailbox(link.resolve("nested"), json));
        }
    }

    @Test
    void requiresPrivateDirectory() throws Exception {
        final var root = privateDirectory("box");
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-x---"));
        try (final var json = json()) {
            assertThrows(IOException.class, () -> new FeedbackMailbox(root, json));
        }
    }

    @Test
    void replacementRootCannotRedirectPinnedHandle() throws Exception {
        final var root = privateDirectory("box");
        try (final var json = json();
                final var mailbox = new FeedbackMailbox(root, json)) {
            final var original = home.resolve("original");
            Files.move(root, original);
            final var hostile = privateDirectory("hostile");
            Files.createSymbolicLink(root, hostile);
            final var request = mailbox.request(questions(), deadline());
            assertTrue(Files.exists(original.resolve("request.json")));
            assertFalse(Files.exists(hostile.resolve("request.json")));
            assertEquals(request, mailbox.pending());
            mailbox.clear();
            assertFalse(Files.exists(original.resolve("request.json")));
        }
    }

    @Test
    void rejectsSymlinkDocumentsWithoutReadingTarget() throws Exception {
        final var root = privateDirectory("box");
        final var target = Files.writeString(home.resolve("secret"), "not a request");
        Files.createSymbolicLink(root.resolve("request.json"), target);
        try (final var json = json();
                final var mailbox = new FeedbackMailbox(root, json)) {
            assertThrows(IOException.class, mailbox::pending);
            mailbox.clear();
            assertEquals("not a request", Files.readString(target));
        }
    }

    @Test
    void rejectsOversizeEmptyAndDirectoryDocuments() throws Exception {
        final var root = privateDirectory("box");
        try (final var json = json();
                final var mailbox = new FeedbackMailbox(root, json)) {
            document(root, "x".repeat(65537));
            assertThrows(IOException.class, mailbox::pending);
            Files.writeString(root.resolve("request.json"), "");
            assertThrows(IOException.class, mailbox::pending);
            Files.delete(root.resolve("request.json"));
            Files.createDirectory(root.resolve("request.json"));
            assertThrows(IOException.class, mailbox::pending);
        }
    }

    @Test
    void rejectsPublicDocumentsAndMalformedUtf8() throws Exception {
        final var root = privateDirectory("box");
        try (final var json = json();
                final var mailbox = new FeedbackMailbox(root, json)) {
            mailbox.request(questions(), deadline());
            Files.setPosixFilePermissions(root.resolve("request.json"), PosixFilePermissions.fromString("rw-r--r--"));
            assertThrows(IOException.class, mailbox::pending);
            Files.setPosixFilePermissions(root.resolve("request.json"), PosixFilePermissions.fromString("rw-------"));
            Files.write(root.resolve("request.json"), new byte[] {(byte) 0xc3, (byte) 0x28});
            assertThrows(IOException.class, mailbox::pending);
        }
    }

    @Test
    void outboundLimitIsMeasuredInUtf8Bytes() throws Exception {
        final var root = privateDirectory("box");
        try (final var json = json();
                final var mailbox = new FeedbackMailbox(root, json)) {
            final var questions = IntStream.range(0, 8)
                    .mapToObj(i -> new Question("q" + i, "Prompt", List.of(), false))
                    .toList();
            final var request = mailbox.request(questions, deadline());
            final Map<String, Object> answers = new HashMap<>();
            for (final var question : questions) {
                answers.put(question.id(), "\u20ac".repeat(3000));
            }
            assertThrows(IOException.class, () -> mailbox.answer(request, answers, false));
            assertNull(mailbox.response(request.nonce()));
            try (final var files = Files.list(root)) {
                assertEquals(
                        List.of("request.json"),
                        files.map(p -> p.getFileName().toString()).toList());
            }
        }
    }

    @Test
    void rejectsFifoWithoutWaitingForProducer() throws Exception {
        final var root = privateDirectory("box");
        final var process =
                new ProcessBuilder("mkfifo", root.resolve("request.json").toString()).start();
        assertTrue(process.waitFor(200, TimeUnit.MILLISECONDS));
        assertEquals(0, process.exitValue());
        try (final var json = json();
                final var mailbox = new FeedbackMailbox(root, json)) {
            assertThrows(IOException.class, mailbox::pending);
        }
    }

    @Test
    void rejectsStaleRequestAndResponse() throws Exception {
        final var root = privateDirectory("box");
        try (final var json = json();
                final var mailbox = new FeedbackMailbox(root, json)) {
            final var old = mailbox.request(questions(), deadline());
            mailbox.clear();
            final var current = mailbox.request(questions(), deadline());
            assertThrows(IOException.class, () -> mailbox.answer(old, Map.of("q", "old"), false));
            mailbox.answer(current, Map.of("q", "new"), false);
            assertThrows(IOException.class, () -> mailbox.response(old.nonce()));
            final var expired = new FeedbackMailbox.Request(1, current.nonce(), 1, current.questions());
            Files.writeString(root.resolve("request.json"), json.toString(expired));
            assertThrows(IOException.class, mailbox::pending);
            assertThrows(IOException.class, () -> mailbox.response(current.nonce()));
            assertThrows(IOException.class, () -> mailbox.request(questions(), Long.MAX_VALUE));
        }
    }

    @Test
    void rejectsMalformedProtocolAndUntrustedResponseAnswers() throws Exception {
        final var root = privateDirectory("box");
        try (final var json = json();
                final var mailbox = new FeedbackMailbox(root, json)) {
            final var request = mailbox.request(questions(), deadline());
            final var valid = json.toString(request);
            Files.writeString(root.resolve("request.json"), valid.replace("\"version\":1", "\"version\":2"));
            assertThrows(RuntimeException.class, mailbox::pending);
            Files.writeString(
                    root.resolve("request.json"),
                    valid.replace(request.nonce(), "------------------------------------"));
            assertThrows(RuntimeException.class, mailbox::pending);
            Files.writeString(root.resolve("request.json"), valid);
            mailbox.answer(request, Map.of("q", "valid"), false);
            Files.writeString(
                    root.resolve("response.json"),
                    json.toString(new FeedbackMailbox.Response(1, request.nonce(), false, Map.of("other", "wrong"))));
            assertThrows(IllegalArgumentException.class, () -> mailbox.response(request.nonce()));
        }
    }

    @Test
    void validatesQuestionAndAnswerShapes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FeedbackMailbox.Item("q", "Prompt", List.of("a", "a"), false));
        assertThrows(IllegalArgumentException.class, () -> new FeedbackMailbox.Item("q", "Prompt", List.of(), true));
        final var free = request(new FeedbackMailbox.Item("q", "Prompt", List.of(), false));
        for (final var answers : List.<Map<String, Object>>of(
                Map.of(),
                Map.of("other", "x"),
                Map.of("q", " "),
                Map.of("q", "x".repeat(3001)),
                Map.of("q", List.of("x")),
                Map.of("q", "x", "extra", "x"))) {
            assertThrows(IllegalArgumentException.class, () -> FeedbackMailbox.validateAnswers(free, answers));
        }
        final var single = request(new FeedbackMailbox.Item("q", "Prompt", List.of("a", "b"), false));
        assertThrows(IllegalArgumentException.class, () -> FeedbackMailbox.validateAnswers(single, Map.of("q", "c")));
        final var multi = request(new FeedbackMailbox.Item("q", "Prompt", List.of("a", "b"), true));
        for (final var answer : List.of(List.of(), List.of("a", "a"), List.of("c"), "a")) {
            assertThrows(
                    IllegalArgumentException.class, () -> FeedbackMailbox.validateAnswers(multi, Map.of("q", answer)));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new FeedbackMailbox.Request(
                        1,
                        UUID.randomUUID().toString(),
                        deadline(),
                        List.of(free.questions().getFirst(), free.questions().getFirst())));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FeedbackMailbox.Response(1, UUID.randomUUID().toString(), true, Map.of("q", "x")));
    }

    @Test
    void concurrentMailboxesAreIsolated() throws Exception {
        final var firstRoot = privateDirectory("first");
        final var secondRoot = privateDirectory("second");
        try (final var json = json();
                final var first = new FeedbackMailbox(firstRoot, json);
                final var second = new FeedbackMailbox(secondRoot, json)) {
            final var one = first.request(questions(), deadline());
            final var two = second.request(questions(), deadline());
            final var writer = CompletableFuture.runAsync(() -> {
                try {
                    first.answer(one, Map.of(), true);
                    second.answer(two, Map.of("q", "second"), false);
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            writer.get(200, TimeUnit.MILLISECONDS);
            first.clear();
            assertEquals(Map.of("q", "second"), second.response(two.nonce()).answers());
            assertThrows(IOException.class, () -> second.response(one.nonce()));
        }
    }

    private static FeedbackMailbox.Request request(final FeedbackMailbox.Item item) {
        return new FeedbackMailbox.Request(1, UUID.randomUUID().toString(), deadline(), List.of(item));
    }

    static List<Question> questions() {
        return List.of(new Question("q", "Prompt", List.of(), false));
    }

    private static long deadline() {
        return System.currentTimeMillis() + 60000;
    }

    private Path privateDirectory(final String name) throws IOException {
        return Files.createDirectory(
                home.resolve(name), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    private static void document(final Path root, final String content) throws IOException {
        Files.writeString(root.resolve("request.json"), content);
        Files.setPosixFilePermissions(root.resolve("request.json"), PosixFilePermissions.fromString("rw-------"));
    }

    static JsonMapper json() {
        return JsonMappers.create(key -> Optional.empty());
    }
}
