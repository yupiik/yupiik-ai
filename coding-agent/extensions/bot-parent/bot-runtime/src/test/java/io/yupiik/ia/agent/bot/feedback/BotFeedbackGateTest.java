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
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BotFeedbackGateTest {
    @TempDir
    Path home;

    abstract class Disabled {
        @Test
        void disabledByDefaultAndDoesNotOpenMailbox(@Fusion final BotFeedbackConfiguration configuration)
                throws Exception {
            try (final var json = FeedbackMailboxTest.json()) {
                final var producer = new BotFeedbackGate() {
                    @Override
                    protected FeedbackMailbox openMailbox(final JsonMapper mapper) {
                        fail("Disabled gate must not open a mailbox");
                        return null;
                    }
                };
                assertNull(producer.gate(configuration, json).ask(FeedbackMailboxTest.questions()));
            }
        }
    }

    @Nested
    @FusionSupport(modules = BotFeedbackTestModule.class)
    class Defaults extends Disabled {}

    @Nested
    @FusionSupport(modules = DisabledModule.class)
    class ExplicitlyDisabled extends Disabled {}

    @Nested
    @FusionSupport(modules = EnabledModule.class)
    class Enabled {
        @Fusion
        BotFeedbackConfiguration configuration;

        @Test
        void enabledGateReturnsAnswerAndClearsMailbox() throws Exception {
            final var root = privateDirectory("box");
            try (final var json = FeedbackMailboxTest.json()) {
                final var producer = producer(root, () -> {
                    try (final var mailbox = new FeedbackMailbox(root, json)) {
                        mailbox.answer(mailbox.pending(), Map.of("q", "answer"), false);
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
                assertEquals(
                        Map.of("q", "answer"),
                        producer.gate(configuration, json).ask(FeedbackMailboxTest.questions()));
                assertFalse(Files.exists(root.resolve("request.json")));
                assertFalse(Files.exists(root.resolve("response.json")));
            }
        }

        @Test
        void cancelledGateReturnsNullAndCanAskAgain() throws Exception {
            final var root = privateDirectory("box");
            try (final var json = FeedbackMailboxTest.json()) {
                final var producer = producer(root, () -> {
                    try (final var mailbox = new FeedbackMailbox(root, json)) {
                        mailbox.answer(mailbox.pending(), Map.of(), true);
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
                final var gate = producer.gate(configuration, json);
                assertNull(gate.ask(FeedbackMailboxTest.questions()));
                assertNull(gate.ask(FeedbackMailboxTest.questions()));
                assertFalse(Files.exists(root.resolve("request.json")));
            }
        }

        @Test
        void threadInterruptionCancelsOnlyThatCallAndPreservesInterrupt() throws Exception {
            final var root = privateDirectory("box");
            try (final var json = FeedbackMailboxTest.json()) {
                final var gate =
                        producer(root, () -> Thread.currentThread().interrupt()).gate(configuration, json);
                try {
                    assertNull(gate.ask(FeedbackMailboxTest.questions()));
                    assertTrue(Thread.currentThread().isInterrupted());
                    assertFalse(Files.exists(root.resolve("request.json")));
                    // Already cancelled callers do not publish another question.
                    assertNull(gate.ask(FeedbackMailboxTest.questions()));
                    assertFalse(Files.exists(root.resolve("request.json")));
                } finally {
                    Thread.interrupted();
                }
            }
        }

        @Test
        void concurrentCallerCannotClearOrCancelOutstandingQuestion() throws Exception {
            final var root = privateDirectory("box");
            final var waiting = new CountDownLatch(1);
            final var resume = new CountDownLatch(1);
            final var failure = new AtomicReference<Throwable>();
            try (final var json = FeedbackMailboxTest.json()) {
                final var gate = producer(root, () -> {
                            waiting.countDown();
                            try {
                                if (!resume.await(200, TimeUnit.MILLISECONDS)) {
                                    throw new IllegalStateException("Test did not resume question");
                                }
                            } catch (final InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        })
                        .gate(configuration, json);
                final var result = new AtomicReference<Map<String, Object>>();
                final var worker = Thread.ofPlatform().unstarted(() -> {
                    try {
                        result.set(gate.ask(FeedbackMailboxTest.questions()));
                    } catch (final Throwable e) {
                        failure.set(e);
                    }
                });
                worker.start();
                try {
                    assertTrue(waiting.await(200, TimeUnit.MILLISECONDS));
                    try (final var mailbox = new FeedbackMailbox(root, json)) {
                        final var pending = mailbox.pending();
                        assertNotNull(pending);
                        assertNull(gate.ask(FeedbackMailboxTest.questions()));
                        assertEquals(pending, mailbox.pending());
                        mailbox.answer(pending, Map.of("q", "owner"), false);
                    }
                } finally {
                    resume.countDown();
                    worker.join(200);
                    if (worker.isAlive()) {
                        worker.interrupt();
                    }
                }
                assertFalse(worker.isAlive());
                assertNull(failure.get());
                assertEquals(Map.of("q", "owner"), result.get());
            }
        }

        @Test
        void separateAttemptCancellationDoesNotAffectAnotherGate() throws Exception {
            final var cancelled = privateDirectory("cancelled");
            final var answered = privateDirectory("answered");
            try (final var json = FeedbackMailboxTest.json()) {
                final var first = producer(cancelled, () -> {
                            try (final var mailbox = new FeedbackMailbox(cancelled, json)) {
                                mailbox.answer(mailbox.pending(), Map.of(), true);
                            } catch (final IOException e) {
                                throw new IllegalStateException(e);
                            }
                        })
                        .gate(configuration, json);
                final var second = producer(answered, () -> {
                            assertNull(first.ask(FeedbackMailboxTest.questions()));
                            try (final var mailbox = new FeedbackMailbox(answered, json)) {
                                mailbox.answer(mailbox.pending(), Map.of("q", "independent"), false);
                            } catch (final IOException e) {
                                throw new IllegalStateException(e);
                            }
                        })
                        .gate(configuration, json);
                assertEquals(Map.of("q", "independent"), second.ask(FeedbackMailboxTest.questions()));
            }
        }

        @Test
        void configuredDirectoryReturnsAnswerWithoutOpeningDefaultMailbox() throws Exception {
            final var root = privateDirectory("configured");
            try (final var json = FeedbackMailboxTest.json()) {
                final var producer = new BotFeedbackGate() {
                    @Override
                    protected FeedbackMailbox openMailbox(final JsonMapper mapper) {
                        fail("Configured directory must bypass the default mailbox");
                        return null;
                    }

                    @Override
                    protected void awaitPoll() {
                        try (final var mailbox = new FeedbackMailbox(root, json)) {
                            mailbox.answer(mailbox.pending(), Map.of("q", "configured"), false);
                        } catch (final IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                };
                final var configuration = new BotFeedbackConfiguration(true, 600, root.toString());
                assertEquals(
                        Map.of("q", "configured"),
                        producer.gate(configuration, json).ask(FeedbackMailboxTest.questions()));
                assertFalse(Files.exists(root.resolve("request.json")));
                assertFalse(Files.exists(root.resolve("response.json")));
            }
        }

        @Test
        void missingMailboxFailsClosed() throws Exception {
            try (final var json = FeedbackMailboxTest.json()) {
                final var producer = producer(home.resolve("missing"), () -> fail("No polling without mailbox"));
                assertNull(producer.gate(configuration, json).ask(FeedbackMailboxTest.questions()));
            }
        }
    }

    private Path privateDirectory(final String name) throws IOException {
        return Files.createDirectory(
                home.resolve(name), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    public static class EnabledModule extends BotFeedbackTestModule {
        public EnabledModule() {
            super(Map.of("yca.bot.feedback.enabled", "true"));
        }
    }

    public static class DisabledModule extends BotFeedbackTestModule {
        public DisabledModule() {
            super(Map.of("yca.bot.feedback.enabled", "false"));
        }
    }

    private static BotFeedbackGate producer(final Path root, final Runnable poll) {
        return new BotFeedbackGate() {
            @Override
            protected FeedbackMailbox openMailbox(final JsonMapper json) throws IOException {
                return new FeedbackMailbox(root, json);
            }

            @Override
            protected void awaitPoll() {
                poll.run();
            }
        };
    }
}
