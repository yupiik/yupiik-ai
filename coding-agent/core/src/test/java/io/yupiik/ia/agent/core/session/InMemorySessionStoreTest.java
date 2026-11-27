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
package io.yupiik.ia.agent.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.llm.Message;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InMemorySessionStoreTest {
    private final InMemorySessionStore store = new InMemorySessionStore(Clock.systemUTC());

    @Test
    void createAppendLoadReplaceAndDelete() {
        final var id = store.create();
        assertFalse(id.isBlank());

        store.append(id, SessionEntry.of(1, "user", "hello", null, null));
        store.append(id, SessionEntry.of(2, "assistant", "hi", null, null));
        assertEquals(2, store.load(id).size());
        assertEquals(2, store.size(id));

        store.replace(id, List.of(Message.user("only")));
        assertEquals(1, store.load(id).size());

        store.delete(id);
        assertTrue(store.load(id).isEmpty());
    }

    @Test
    void isEmptyOnlyForNoOrSingleUserMessage() {
        final var id = store.create();
        assertTrue(store.isEmpty(id));

        store.append(id, SessionEntry.of(1, "user", "prompt", null, null));
        assertTrue(store.isEmpty(id));

        store.append(id, SessionEntry.of(2, "assistant", "reply", null, null));
        assertFalse(store.isEmpty(id));
    }

    @Test
    void summaryUsesFirstUserPrompt() {
        final var id = store.create();
        store.append(id, SessionEntry.of(1, "system", "system prompt", null, null));
        store.append(id, SessionEntry.of(2, "user", "  first real prompt  ", null, null));
        store.append(id, SessionEntry.of(3, "user", "second prompt", null, null));
        assertEquals("first real prompt", store.summary(id));
    }

    @Test
    void concurrentAppendsToDifferentSessionsAreIsolated() throws Exception {
        final var threads = 8;
        final var appendsPerSession = 100;
        final var executor = Executors.newFixedThreadPool(threads);
        final var done = new CountDownLatch(threads);
        final var error = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                final var sessionId = store.create();
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < appendsPerSession; j++) {
                            store.append(sessionId, SessionEntry.of(j, "user", "m" + j, null, null));
                        }
                        if (store.size(sessionId) != appendsPerSession) {
                            error.incrementAndGet();
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(0, error.get());
            assertEquals(threads, store.listIds().size());
        } finally {
            executor.shutdownNow();
        }
    }
}
