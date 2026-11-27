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
package io.yupiik.fusion.a2a.demo;

import io.yupiik.fusion.a2a.api.A2AExecutor;
import io.yupiik.fusion.a2a.api.AgentRequest;
import io.yupiik.fusion.a2a.api.TaskUpdater;
import io.yupiik.fusion.a2a.model.Artifact;
import io.yupiik.fusion.a2a.model.Message;
import io.yupiik.fusion.a2a.model.Part;
import io.yupiik.fusion.a2a.service.A2AThreads;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The imperative path: an executor which reports progress while it works, so a streaming client sees the task move.
 * <p>
 * It is also the example of a <em>blocking</em> agent: the work is handed off to a virtual thread with
 * {@link A2AThreads#async(Runnable)} rather than run on the thread the transport gave us, which is what keeps the server
 * from ever waiting on an agent.
 * <p>
 * Cancellation follows the same rule: {@code CancelTask} flips a flag the loop checks - the executor thread is never
 * interrupted from under it.
 */
@ApplicationScoped
public class DemoCountdownExecutor implements A2AExecutor {
    /**
     * The skill this executor answers, declared in the card by {@link DemoSkillDescriptors}.
     */
    public static final String SKILL_ID = "demo/countdown";

    private final Map<String, AtomicBoolean> canceled = new ConcurrentHashMap<>();
    private final A2AThreads threads;

    public DemoCountdownExecutor(final A2AThreads threads) {
        this.threads = threads;
    }

    @Override
    public boolean accept(final AgentRequest request) {
        return SKILL_ID.equals(request.skillId());
    }

    @Override
    public CompletionStage<Void> execute(final AgentRequest request, final TaskUpdater updater) {
        final var from = count(request);
        final var stop = canceled.computeIfAbsent(updater.taskId(), id -> new AtomicBoolean());
        return threads.async(() -> {
            try {
                updater.working(Message.agent("counting down from " + from));
                for (int i = from; i > 0; i--) {
                    if (stop.get()) {
                        return; // cancel() already concluded the task
                    }
                    // one artifact built chunk by chunk: same identifier, append, and the last one closes it
                    updater.artifact(chunk(i + "..."), i != from, false);
                    sleep();
                }
                updater.artifact(chunk("go!"), true, true);
                updater.complete();
            } finally {
                canceled.remove(updater.taskId());
            }
        });
    }

    @Override
    public void cancel(final AgentRequest request, final TaskUpdater updater) {
        final var stop = canceled.get(updater.taskId());
        if (stop != null) {
            stop.set(true);
        }
        updater.cancel();
    }

    private Artifact chunk(final String text) {
        return new Artifact("countdown", "countdown", null, List.of(Part.text(text)), null, null);
    }

    private int count(final AgentRequest request) {
        for (final var token : request.text().split("[^0-9]+")) {
            if (!token.isEmpty()) {
                return Math.min(10, Math.max(1, Integer.parseInt(token)));
            }
        }
        return 3;
    }

    private void sleep() {
        try {
            Thread.sleep(50);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
