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
import java.time.Instant;

/**
 * The current status of a {@link Task}.
 *
 * @param state     where the task stands.
 * @param message   an optional message explaining the status - what is missing for {@code INPUT_REQUIRED}, why it
 *                  failed for {@code FAILED}, ...
 * @param timestamp when the status was recorded, an ISO-8601 UTC string.
 */
@JsonModel
public record TaskStatus(
        @Property(documentation = "Where the task stands.") TaskState state,

        @Property(
                documentation =
                        "An optional message explaining the status - what is missing for INPUT_REQUIRED, why it failed for FAILED, .")
        Message message,

        @Property(documentation = "When the status was recorded, an ISO-8601 UTC string.")
        String timestamp) {
    /**
     * @param state the new state.
     * @return the status, timestamped at the supplied instant.
     */
    public static TaskStatus of(final TaskState state, final Instant instant) {
        return of(state, null, instant);
    }

    /**
     * @param state   the new state.
     * @param message the message to attach, it can be {@code null}.
     * @return the status, timestamped at the supplied instant.
     */
    public static TaskStatus of(final TaskState state, final Message message, final Instant instant) {
        return new TaskStatus(state, message, A2ATimestamps.format(instant));
    }
}
