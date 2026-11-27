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

import io.yupiik.fusion.framework.build.api.json.JsonModel;

/**
 * Sender of a {@link Message}.
 * <p>
 * IMPORTANT: the constant names are the proto ones because ProtoJSON serializes an enum with its proto name, see
 * {@link io.yupiik.fusion.a2a.model}.
 */
@JsonModel
public enum Role {
    /**
     * Unset, a client sending this is invalid but it is what an absent {@code role} deserializes to.
     */
    ROLE_UNSPECIFIED,

    /**
     * The message comes from the client, i.e. from the calling agent or its user.
     */
    ROLE_USER,

    /**
     * The message comes from this server, i.e. from the agent.
     */
    ROLE_AGENT
}
