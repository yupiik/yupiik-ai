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
package io.yupiik.ia.agent.core.shell;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.ia.agent.core.agent.AgentEvent;

/**
 * Single owner of the shared conversation state: it folds agent events into
 * the {@link ConversationTranscript} and {@link TurnStats} whenever the
 * container dispatches them. Shell lifecycle events reset the state so a new
 * command run starts from a clean conversation.
 * <p>
 * Frontends never mutate the state: they only read it (directly or through
 * the registry forwarding) and draw.
 */
@ApplicationScoped
public class ConversationCoordinator {
    private final ConversationTranscript transcript = new ConversationTranscript();
    private final TurnStats stats = new TurnStats();

    public void onEvent(@OnEvent final AgentEvent event) {
        transcript.onEvent(event);
        if (event instanceof final AgentEvent.UsageUpdated usage) {
            stats.recordUsage(usage);
        } else if (event instanceof final AgentEvent.TurnMetrics metrics) {
            stats.recordTurnMetrics(metrics);
        } else if (event instanceof AgentEvent.ToolFinished) {
            stats.incToolsRun();
        }
    }

    public void onRegistered(@OnEvent final ShellRegistered event) {
        reset();
    }

    public void onUnregistered(@OnEvent final ShellUnregistered event) {
        reset();
    }

    /** Clears the transcript and every stat counter. */
    public void reset() {
        transcript.reset();
        stats.reset();
    }

    public ConversationTranscript transcript() {
        return transcript;
    }

    public TurnStats stats() {
        return stats;
    }
}
