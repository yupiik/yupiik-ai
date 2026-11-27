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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.fusion.framework.build.api.scanning.Bean;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Exposes the single application-wide {@link ExecutorService} (a virtual-thread-per-task executor) shared by all
 * consumers (agent turn loops, bridge-to-workspace, hook callbacks, memory compaction, ...). Consumers inject
 * {@link ExecutorService} and never shut it down — {@link Executors#newVirtualThreadPerTaskExecutor()} returns an
 * {@link AutoCloseable} executor, so the container releases the pool when the application stops.
 *
 * <p>The bean is destroyed <em>last</em> ({@code @Destroy(order = Integer.MAX_VALUE)}): the raw
 * {@link Executors#newVirtualThreadPerTaskExecutor()} executor's {@code close()} is a graceful, unbounded wait for
 * every in-flight virtual thread, so it must run only once all other beans have already released the resources those
 * tasks block on — {@code ProcessService} (kills tracked subprocesses, unblocking pipe drains), {@code McpManager}
 * (closes MCP clients, unblocking {@code readLine()}), {@code ChatClient} (closes the HTTP client) and the session
 * teardown (turns, /btw, sub-agents). With every order-0 bean closed first, the executor close is a fast no-op instead
 * of a deadlock-able stall at shutdown.</p>
 *
 * <p>The exposed executor is wrapped so the graceful {@code close()} logs its duration ({@code FINE}); a hang here,
 * after every bean logged its own teardown marker, pinpoints a still-parked virtual thread draining the pool.</p>
 */
@DefaultScoped
public class ExecutorServiceProducer {

    private static final Logger LOGGER = Logger.getLogger(ExecutorServiceProducer.class.getName());

    @Bean
    @ApplicationScoped
    @Destroy(order = Integer.MAX_VALUE)
    public ExecutorService executor() {
        return new TimingExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * Delegating {@link ExecutorService} that times its graceful {@link #close()}.
     */
    private record TimingExecutor(ExecutorService delegate) implements ExecutorService {

        @Override
        public void close() {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("[yca-shutdown] shared executor close: draining in-flight virtual threads...");
            }
            final var start = System.nanoTime();
            delegate.close();
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(() -> "[yca-shutdown] shared executor close: done ("
                        + (System.nanoTime() - start) / 1_000_000L + " ms)");
            }
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public <T> Future<T> submit(final Callable<T> task) {
            return delegate.submit(task);
        }

        @Override
        public <T> Future<T> submit(final Runnable task, final T result) {
            return delegate.submit(task, result);
        }

        @Override
        public Future<?> submit(final Runnable task) {
            return delegate.submit(task);
        }

        @Override
        public <T> List<Future<T>> invokeAll(final Collection<? extends Callable<T>> tasks)
                throws InterruptedException {
            return delegate.invokeAll(tasks);
        }

        @Override
        public <T> List<Future<T>> invokeAll(
                final Collection<? extends Callable<T>> tasks, final long timeout, final TimeUnit unit)
                throws InterruptedException {
            return delegate.invokeAll(tasks, timeout, unit);
        }

        @Override
        public <T> T invokeAny(final Collection<? extends Callable<T>> tasks)
                throws InterruptedException, ExecutionException {
            return delegate.invokeAny(tasks);
        }

        @Override
        public <T> T invokeAny(final Collection<? extends Callable<T>> tasks, final long timeout, final TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return delegate.invokeAny(tasks, timeout, unit);
        }

        @Override
        public void execute(final Runnable command) {
            delegate.execute(command);
        }
    }
}
