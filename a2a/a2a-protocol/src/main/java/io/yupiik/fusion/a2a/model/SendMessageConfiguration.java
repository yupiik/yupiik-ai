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
 * How the client wants {@code SendMessage}/{@code SendStreamingMessage} to behave.
 *
 * @param acceptedOutputModes        the media types the client can handle, the agent should tailor its output to
 *                                  them - an unsupported one is answered with {@code ContentTypeNotSupportedError}.
 * @param taskPushNotificationConfig a webhook to register on the task being created, its {@code taskId} must be unset.
 * @param historyLength              how many of the most recent messages to include in the returned task: unset means
 *                                  no limit, {@code 0} means none.
 * @param returnImmediately          when {@code true} the call returns as soon as the task exists instead of waiting
 *                                  for it to be settled. It has no effect on the streaming variant.
 */
@JsonModel
public record SendMessageConfiguration(
        @Property(
                documentation =
                        "The media types the client can handle, the agent should tailor its output to them - an unsupported one is answered with ContentTypeNotSupportedError.")
        List<String> acceptedOutputModes,

        @Property(documentation = "A webhook to register on the task being created, its taskId must be unset.")
        TaskPushNotificationConfig taskPushNotificationConfig,

        @Property(
                documentation =
                        "How many of the most recent messages to include in the returned task: unset means no limit, 0 means none.")
        Integer historyLength,

        @Property(
                documentation =
                        "When true the call returns as soon as the task exists instead of waiting for it to be settled. It has no effect on the streaming variant.")
        boolean returnImmediately) {}
