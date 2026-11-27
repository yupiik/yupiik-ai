/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.fusion.a2a.service;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The single place where this module gets threads from: a virtual thread per task executor.
 * <p>
 * Nothing in the server blocks - the protocol, the streams and the webhook calls are all asynchronous - but an
 * application often has to: an LLM call, a query, a file to read. Inject this bean and hand that work off with
 * {@link #async(Runnable)} instead of blocking the thread the transport gave you:
 * <pre>{@code
 * @ApplicationScoped
 * public class MyAgent implements A2AExecutor {
 *     private final A2AThreads threads;
 *
 *     public MyAgent(final A2AThreads threads) {
 *         this.threads = threads;
 *     }
 *
 *     @Override
 *     public CompletionStage<Void> execute(final AgentRequest request, final TaskUpdater updater) {
 *         return threads.async(() -> updater.complete(Artifact.text("answer", callTheModel(request.text()))));
 *     }
 * }
 * }</pre>
 * It also backs the {@code HttpClient} the push notifications are sent with, so the whole module shares one pool which
 * is shut down with the container.
 */
@ApplicationScoped
public class A2AThreads {
    private final ExecutorService executor;

    public A2AThreads() {
        final var counter = new AtomicLong();
        final ThreadFactory factory = task -> Thread.ofVirtual()
                .name("fusion-a2a-" + counter.incrementAndGet())
                .unstarted(task);
        this.executor = Executors.newThreadPerTaskExecutor(factory);
    }

    /**
     * @return the executor, to pass to a {@code HttpClient} builder or to a {@code CompletableFuture} combinator.
     */
    public ExecutorService executor() {
        return executor;
    }

    /**
     * Runs a blocking task on a virtual thread.
     *
     * @param task what to run.
     * @return a stage completing when {@code task} returns, failing with whatever it threw.
     */
    public CompletionStage<Void> async(final Runnable task) {
        return CompletableFuture.runAsync(task, executor);
    }

    @Destroy
    public void close() {
        executor.close();
    }
}
