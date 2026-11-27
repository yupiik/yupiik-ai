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
import java.util.List;

/**
 * The optional features an agent supports.
 * <p>
 * A client must check them before using the matching operations: the server answers
 * {@code UnsupportedOperationError} or {@code PushNotificationNotSupportedError} otherwise, see section 3.3.4 of the
 * specification.
 *
 * @param streaming         whether {@code SendStreamingMessage} and {@code SubscribeToTask} can be used.
 * @param pushNotifications whether webhooks can be registered on a task.
 * @param extensions        the protocol extensions the agent understands.
 * @param extendedAgentCard whether {@code GetExtendedAgentCard} returns a richer card once authenticated.
 */
@JsonModel
public record AgentCapabilities(
        @Property(documentation = "Whether SendStreamingMessage and SubscribeToTask can be used.")
        Boolean streaming,

        @Property(documentation = "Whether webhooks can be registered on a task.")
        Boolean pushNotifications,

        @Property(documentation = "The protocol extensions the agent understands.")
        List<AgentExtension> extensions,

        @Property(documentation = "Whether GetExtendedAgentCard returns a richer card once authenticated.")
        Boolean extendedAgentCard) {}
