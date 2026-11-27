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
 * A webhook the agent calls when a task changes, i.e. the alternative to streaming for clients which cannot hold a
 * connection open.
 * <p>
 * The payload posted to {@code url} is a {@link StreamResponse} - the very same events a stream carries - sent with
 * the {@code application/a2a+json} media type.
 *
 * @param tenant         the opaque routing identifier of the {@link AgentInterface} the client selected.
 * @param id             the configuration identifier, the server generates one when it is not provided.
 * @param taskId         the task to watch, it is unset when the configuration is passed in
 *                       {@link SendMessageConfiguration#taskPushNotificationConfig()}.
 * @param url            where to POST the events.
 * @param token          an opaque token echoed in the {@code X-A2A-Notification-Token} header so the receiver can
 *                       check the notification is the one it asked for.
 * @param authentication how to authenticate the call.
 */
@JsonModel
public record TaskPushNotificationConfig(
        @Property(documentation = "The opaque routing identifier of the AgentInterface the client selected.")
        String tenant,

        @Property(documentation = "The configuration identifier, the server generates one when it is not provided.")
        String id,

        @Property(
                documentation =
                        "The task to watch, it is unset when the configuration is passed in SendMessageConfiguration#taskPushNotificationConfig().")
        String taskId,

        @Property(documentation = "Where to POST the events.")
        String url,

        @Property(
                documentation =
                        "An opaque token echoed in the X-A2A-Notification-Token header so the receiver can check the notification is the one it asked for.")
        String token,

        @Property(documentation = "How to authenticate the call.")
        AuthenticationInfo authentication) {
    public static TaskPushNotificationConfig of(final String url) {
        return new TaskPushNotificationConfig(null, null, null, url, null, null);
    }

    /**
     * @param id     the identifier to assign.
     * @param taskId the task to bind the configuration to.
     * @return a copy of this configuration identified and bound to a task.
     */
    public TaskPushNotificationConfig boundTo(final String id, final String taskId) {
        return new TaskPushNotificationConfig(tenant, id, taskId, url, token, authentication);
    }
}
