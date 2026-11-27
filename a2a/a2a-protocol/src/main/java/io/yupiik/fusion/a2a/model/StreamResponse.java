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
package io.yupiik.fusion.a2a.model;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;

/**
 * What a stream - and a push notification webhook - carries: exactly one of a task, a message or an update event.
 * <p>
 * It is the proto {@code oneof} of {@code StreamResponse} so only one component is ever set.
 *
 * @param task           the initial - or current - state of a task.
 * @param message        a direct message answer, it closes the stream.
 * @param statusUpdate   a status change.
 * @param artifactUpdate an artifact which was produced or extended.
 */
@JsonModel
public record StreamResponse(
        @Property(documentation = "The initial - or current - state of a task.")
        Task task,

        @Property(documentation = "A direct message answer, it closes the stream.")
        Message message,

        @Property(documentation = "A status change.") TaskStatusUpdateEvent statusUpdate,

        @Property(documentation = "An artifact which was produced or extended.")
        TaskArtifactUpdateEvent artifactUpdate) {
    public static StreamResponse of(final Task task) {
        return new StreamResponse(task, null, null, null);
    }

    public static StreamResponse of(final Message message) {
        return new StreamResponse(null, message, null, null);
    }

    public static StreamResponse of(final TaskStatusUpdateEvent statusUpdate) {
        return new StreamResponse(null, null, statusUpdate, null);
    }

    public static StreamResponse of(final TaskArtifactUpdateEvent artifactUpdate) {
        return new StreamResponse(null, null, null, artifactUpdate);
    }

    /**
     * @return {@code true} when this event ends the stream: a direct message, or a status the task will not leave on
     * its own. The specification only requires closing on a <em>terminal</em> state but an <em>interrupted</em> one
     * cannot progress without a new request either, so keeping the stream open would just hang the caller.
     */
    public boolean isLast() {
        if (message != null) {
            return true;
        }
        if (statusUpdate != null
                && statusUpdate.status() != null
                && statusUpdate.status().state() != null) {
            return statusUpdate.status().state().isSettled();
        }
        return false;
    }
}
