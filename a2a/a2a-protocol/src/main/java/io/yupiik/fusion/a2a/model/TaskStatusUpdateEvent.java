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
import java.util.Map;

/**
 * Tells the client the status of a task changed.
 * <p>
 * NOTE: unlike the {@code 0.3} revision there is no {@code final} flag: a client knows the stream is over because
 * {@link TaskStatus#state()} {@link TaskState#isTerminal() is terminal}.
 *
 * @param taskId    the task which changed.
 * @param contextId the conversation the task belongs to.
 * @param status    the new status.
 * @param metadata  free form metadata about the update itself.
 */
@JsonModel
public record TaskStatusUpdateEvent(
        @Property(documentation = "The task which changed.") String taskId,

        @Property(documentation = "The conversation the task belongs to.")
        String contextId,

        @Property(documentation = "The new status.") TaskStatus status,

        @Property(documentation = "Free form metadata about the update itself.")
        Map<String, Object> metadata) {}
