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
package io.yupiik.ia.agent.slack.model;

import io.yupiik.fusion.framework.build.api.json.JsonModel;

@JsonModel
public record SlackTask(
        String id,
        String workspace,
        String channel,
        String thread,
        String requester,
        String request,
        String conversation,
        TaskStatus status,
        long created,
        long updated,
        int attempt,
        String attemptPath,
        String runtimeId,
        String fingerprint,
        String answer,
        String messageTs,
        SlackQuestion question,
        boolean contextBearing,
        String publicationId) {
    public SlackTask {
        if (contextBearing && (answer != null || question != null)) {
            throw new IllegalArgumentException("Context-bearing tasks cannot retain answer or feedback bodies");
        }
    }

    public boolean terminal() {
        return status.terminal();
    }

    public SlackTask transition(final TaskStatus state, final long now) {
        return new SlackTask(
                id,
                workspace,
                channel,
                thread,
                requester,
                request,
                conversation,
                state,
                created,
                now,
                attempt,
                attemptPath,
                runtimeId,
                fingerprint,
                answer,
                messageTs,
                question,
                contextBearing,
                publicationId);
    }

    public SlackTask running(
            final String path,
            final String runtime,
            final String configHash,
            final boolean withContext,
            final long now) {
        return new SlackTask(
                id,
                workspace,
                channel,
                thread,
                requester,
                request,
                conversation,
                TaskStatus.running,
                created,
                now,
                attempt + 1,
                path,
                runtime,
                configHash,
                null,
                null,
                null,
                withContext,
                null);
    }

    public SlackTask publication(final String text, final String ts, final TaskStatus state, final long now) {
        return new SlackTask(
                id,
                workspace,
                channel,
                thread,
                requester,
                request,
                conversation,
                state,
                created,
                now,
                attempt,
                attemptPath,
                runtimeId,
                fingerprint,
                contextBearing ? null : text,
                ts,
                question,
                contextBearing,
                contextBearing && publicationId == null ? runtimeId + "-answer" : publicationId);
    }

    public SlackTask feedback(final SlackQuestion next, final TaskStatus state, final long now) {
        return new SlackTask(
                id,
                workspace,
                channel,
                thread,
                requester,
                request,
                conversation,
                state,
                created,
                now,
                attempt,
                attemptPath,
                runtimeId,
                fingerprint,
                answer,
                messageTs,
                next,
                contextBearing,
                publicationId);
    }
}
