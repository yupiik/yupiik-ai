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
package io.yupiik.ia.agent.core.events.hooks;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.ia.agent.core.events.CompactionStartingEvent;
import io.yupiik.ia.agent.core.events.HookResponse;
import java.nio.file.Path;

/** Runs the {@code yca.hooks.CompactionStarting} callbacks and vetoes the compaction when any forbids it. */
@ApplicationScoped
public class CompactionStartingCallbackObserver {
    private final CallbackRunner runner;

    public CompactionStartingCallbackObserver(final CallbackRunner runner) {
        this.runner = runner;
    }

    public void onCompactionStarting(@OnEvent final CompactionStartingEvent event) {
        if (event.result().forbidden()) {
            return;
        }
        final var responses = runner.run(
                event.id(), event.payload(), 0, Path.of(event.eventContext().cwd()));
        for (final var response : responses) {
            if (response.decision() == HookResponse.HookResponseDecision.forbid) {
                event.result().forbid(response.reason() == null ? "forbidden by hook" : response.reason());
            }
        }
    }
}
