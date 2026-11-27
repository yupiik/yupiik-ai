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

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.order.Order;
import io.yupiik.fusion.framework.build.api.scanning.Bean;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** Existing IoC contract override. Each sandbox process owns one fixed attempt; no session routing global. */
@DefaultScoped
public class BotFeedbackGate {
    @Bean
    @DefaultScoped
    @Order(2000)
    public FeedbackGate gate(final BotFeedbackConfiguration configuration, final JsonMapper json) {
        if (!configuration.enabled()) {
            return questions -> null;
        }
        final var waitSeconds = configuration.timeoutSeconds();
        final var configuredDirectory = configuration.directory() == null ? null : Path.of(configuration.directory());
        final var singleQuestion = new Semaphore(1);
        return questions -> {
            if (Thread.currentThread().isInterrupted() || !singleQuestion.tryAcquire()) {
                return null;
            }
            try (final var mailbox =
                    configuredDirectory == null ? openMailbox(json) : new FeedbackMailbox(configuredDirectory, json)) {
                try {
                    mailbox.clear();
                    final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds);
                    final var request = mailbox.request(questions, System.currentTimeMillis() + waitSeconds * 1000L);
                    while (!Thread.currentThread().isInterrupted() && System.nanoTime() < deadline) {
                        final var response = mailbox.response(request.nonce());
                        if (response != null) {
                            return response.cancelled() ? null : response.answers();
                        }
                        awaitPoll();
                    }
                    return null;
                } finally {
                    mailbox.clear();
                }
            } catch (final IOException | RuntimeException invalid) {
                return null; // Fail closed; never disclose host paths or question data in diagnostics.
            } finally {
                singleQuestion.release();
            }
        };
    }

    /** Override when embedding the gate with a separately prepared attempt directory. */
    protected FeedbackMailbox openMailbox(final JsonMapper json) throws IOException {
        return new FeedbackMailbox(Path.of("/attempt/feedback"), json);
    }

    /** Polling boundary; interruption is deliberately preserved for the calling session. */
    protected void awaitPoll() {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
    }
}
