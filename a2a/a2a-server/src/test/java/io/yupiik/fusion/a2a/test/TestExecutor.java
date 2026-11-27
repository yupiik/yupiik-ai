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
package io.yupiik.fusion.a2a.test;

import static java.util.concurrent.CompletableFuture.completedFuture;

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
 * The agent the transport tests drive: the text of the message picks the behavior, which keeps every test a single
 * request without any mocking.
 */
@ApplicationScoped
public class TestExecutor implements A2AExecutor {
    private final Map<String, AtomicBoolean> canceled = new ConcurrentHashMap<>();
    private final A2AThreads threads;

    public TestExecutor(final A2AThreads threads) {
        this.threads = threads;
    }

    @Override
    public CompletionStage<Void> execute(final AgentRequest request, final TaskUpdater updater) {
        final var text = request.text();
        if (text.startsWith("message:")) {
            updater.respond(Message.agent(text.substring("message:".length())));
            return completedFuture(null);
        }
        if (text.startsWith("input:")) {
            updater.inputRequired(Message.agent(text.substring("input:".length())));
            return completedFuture(null);
        }
        if (text.startsWith("fail:")) {
            throw new IllegalStateException(text.substring("fail:".length()));
        }
        if (text.startsWith("chunks:")) {
            final var count = Integer.parseInt(text.substring("chunks:".length()));
            updater.working();
            for (int i = 1; i <= count; i++) {
                updater.artifact(
                        new Artifact("chunked", "chunked", null, List.of(Part.text("chunk-" + i)), null, null),
                        i > 1,
                        i == count);
            }
            updater.complete();
            return completedFuture(null);
        }
        if ("slow".equals(text)) {
            // the flag must exist before the work starts, else a cancel arriving first would find nothing to signal
            final var stop = canceled.computeIfAbsent(updater.taskId(), id -> new AtomicBoolean());
            return threads.async(() -> slow(updater, stop));
        }
        updater.complete(Artifact.text("echo", text));
        return completedFuture(null);
    }

    @Override
    public void cancel(final AgentRequest request, final TaskUpdater updater) {
        final var stop = canceled.get(updater.taskId());
        if (stop != null) {
            stop.set(true);
        }
        updater.cancel();
    }

    private void slow(final TaskUpdater updater, final AtomicBoolean stop) {
        updater.working();
        for (int i = 0; i < 200 && !stop.get(); i++) {
            sleep();
        }
        if (!updater.isDone()) {
            updater.complete(Artifact.text("slow", "done"));
        }
        canceled.remove(updater.taskId());
    }

    private void sleep() {
        try {
            Thread.sleep(10);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
