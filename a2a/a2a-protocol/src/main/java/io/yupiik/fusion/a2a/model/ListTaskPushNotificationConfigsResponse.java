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
 * The result of {@code ListTaskPushNotificationConfigs}.
 *
 * @param configs       the webhooks registered on the task.
 * @param nextPageToken the cursor of the next page, empty on the last one.
 */
@JsonModel
public record ListTaskPushNotificationConfigsResponse(
        @Property(documentation = "The webhooks registered on the task.")
        List<TaskPushNotificationConfig> configs,

        @Property(documentation = "The cursor of the next page, empty on the last one.")
        String nextPageToken) {}
