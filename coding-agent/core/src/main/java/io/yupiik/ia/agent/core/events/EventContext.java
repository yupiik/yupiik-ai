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
package io.yupiik.ia.agent.core.events;

import io.yupiik.fusion.framework.build.api.json.JsonModel;

/**
 * The shared context embedded in every hook input payload, serialized camelCase alongside the event-specific
 * fields. Producers build it from the session (or process) running the turn; sub-fields are {@code null} when
 * there is no reliable value (a standalone tool run has no model, a parent-turn event has no subagent, ...).
 *
 * <p>This is a {@code @JsonModel} record: every component is part of the on-the-wire input schema.</p>
 */
@JsonModel
public record EventContext(
        String sessionId,
        String subagentId,
        String subagentName,
        String cwd,
        String permissionMode,
        String agentMode,
        String model,
        String provider) {}
