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
package io.yupiik.ia.agent.core.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SchedulerProducerTest {

    @Nested
    @FusionSupport(modules = ConfigurationTestModule.class)
    class DefaultThreadsAreClampedToTheCpuCount {
        @Test
        void defaultThreadsAreClampedToTheCpuCount(@Fusion final CodingAgentConfiguration config) {
            assertEquals(Math.clamp(Runtime.getRuntime().availableProcessors(), 1, 2), config.schedulerThreads());
        }
    }

    @Nested
    @FusionSupport(modules = TwoThreads.class)
    class ConfiguredThreadCountIsHonoured {
        @Test
        void configuredThreadCountIsHonoured(@Fusion final CodingAgentConfiguration config) throws Exception {
            final var ses = new SchedulerProducer().ses(config);
            try {
                final var barrier = new CyclicBarrier(2);
                final var futures = new ArrayList<Future<?>>();
                for (int i = 0; i < 2; i++) {
                    futures.add(ses.submit(() -> {
                        try {
                            barrier.await(2, TimeUnit.SECONDS);
                        } catch (final Exception e) {
                            throw new IllegalStateException(e);
                        }
                        return null;
                    }));
                }
                for (final var future : futures) {
                    future.get(5, TimeUnit.SECONDS);
                }
            } finally {
                ses.shutdownNow();
            }
        }
    }

    @Nested
    @FusionSupport(modules = ConfigurationTestModule.class)
    class ThreadsAreNamedDaemonAndUseTheProducerClassLoader {
        @Test
        void threadsAreNamedDaemonAndUseTheProducerClassLoader(@Fusion final CodingAgentConfiguration config)
                throws Exception {
            final var ses = new SchedulerProducer().ses(config);
            final var thread = new AtomicReference<Thread>();
            final var done = new CountDownLatch(1);
            try {
                ses.execute(() -> {
                    thread.set(Thread.currentThread());
                    done.countDown();
                });
                assertTrue(done.await(5, TimeUnit.SECONDS), "task did not run");
                assertTrue(
                        thread.get().getName().startsWith("yca-scheduler-"),
                        () -> "unexpected name: " + thread.get().getName());
                assertTrue(thread.get().isDaemon(), "scheduler threads must be daemon");
                assertEquals(
                        SchedulerProducer.class.getClassLoader(), thread.get().getContextClassLoader());
            } finally {
                ses.shutdownNow();
            }
        }
    }

    public static class TwoThreads extends ConfigurationTestModule {
        public TwoThreads() {
            super(Map.of("yca.schedulerThreads", "2"));
        }
    }
}
