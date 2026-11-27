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
 * The body of {@code POST /tasks/{id}:cancel} in the HTTP+JSON binding.
 * <p>
 * The JSON-RPC binding binds {@code params} to the arguments of
 * {@link io.yupiik.fusion.a2a.protocol.A2AJSONRPCProtocol#cancelTask} instead.
 *
 * @param tenant   the opaque routing identifier of the {@link AgentInterface} the client selected.
 * @param id       the task to cancel, it is also carried by the path.
 * @param metadata free form metadata.
 */
@JsonModel
public record CancelTaskRequest(
        @Property(documentation = "The opaque routing identifier of the AgentInterface the client selected.")
        String tenant,

        @Property(documentation = "The task to cancel, it is also carried by the path.")
        String id,

        @Property(documentation = "Free form metadata.") Map<String, Object> metadata) {}
