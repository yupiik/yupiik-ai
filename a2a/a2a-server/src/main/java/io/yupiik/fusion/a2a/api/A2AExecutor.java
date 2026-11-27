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
package io.yupiik.fusion.a2a.api;

import java.util.concurrent.CompletionStage;

/**
 * What actually answers a message, i.e. the agent logic.
 * <p>
 * Any bean implementing this interface is picked up. They are visited in bean priority order ({@code @Order}) and the
 * first one whose {@link #accept(AgentRequest)} returns {@code true} handles the message, so a low order wins over
 * the built-in skill router - which is registered last and only accepts a message it can route to an
 * {@link A2ASkill} method:
 * <pre>{@code
 * @ApplicationScoped
 * public class MyAgent implements A2AExecutor {
 *     @Override
 *     public CompletionStage<Void> execute(final AgentRequest request, final TaskUpdater updater) {
 *         updater.working(Message.agent("looking at '" + request.text() + "'"));
 *         updater.complete(Artifact.text("answer", compute(request.text())));
 *         return completedFuture(null);
 *     }
 * }
 * }</pre>
 * It is asynchronous on purpose: the server never blocks a thread waiting for an agent. Reporting to the
 * {@link TaskUpdater} never blocks either, so a fast executor can do its job inline and return an already completed
 * stage as above. When the work <em>does</em> block - an LLM call, a query - hand it off to a virtual thread with
 * {@link io.yupiik.fusion.a2a.service.A2AThreads#async(Runnable)}:
 * <pre>{@code
 * return threads.async(() -> updater.complete(Artifact.text("answer", callTheModel(request.text()))));
 * }</pre>
 * A stage failing - or an exception thrown before it is even created - fails the task with its message, so a client
 * always gets an answer.
 * <p>
 * When no executor accepts a message the server answers {@code UnsupportedOperationError}.
 */
public interface A2AExecutor {
    /**
     * @param request the incoming message and its context.
     * @return {@code true} when this executor handles {@code request}. The default accepts everything.
     */
    default boolean accept(final AgentRequest request) {
        return true;
    }

    /**
     * Processes a message.
     *
     * @param request the incoming message and its context.
     * @param updater how to report the progress and the outcome, the task must be settled by the time the returned
     *                stage completes.
     * @return a stage completing when this executor is done with {@code request}.
     */
    CompletionStage<Void> execute(AgentRequest request, TaskUpdater updater);

    /**
     * Reacts to a {@code CancelTask} on a task this executor is running.
     * <p>
     * It is called on the caller thread - the executor thread is <b>not</b> interrupted - so it should only signal the
     * running work and return quickly. The default implementation just marks the task canceled.
     *
     * @param request the request which created the task.
     * @param updater the updater of the task to cancel.
     */
    default void cancel(final AgentRequest request, final TaskUpdater updater) {
        updater.cancel();
    }
}
