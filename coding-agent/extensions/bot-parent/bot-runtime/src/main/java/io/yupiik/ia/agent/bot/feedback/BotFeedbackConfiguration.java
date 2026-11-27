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
package io.yupiik.ia.agent.bot.feedback;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.framework.build.api.json.JsonModel;

@JsonModel
@RootConfiguration("yca.bot.feedback")
public record BotFeedbackConfiguration(
        @Property(defaultValue = "false", documentation = "Enable feedback through the attempt mailbox.")
        boolean enabled,

        @Property(defaultValue = "600", documentation = "Feedback timeout in seconds, from 1 to 3600 inclusive.")
        int timeoutSeconds,

        @Property(defaultValue = "null", documentation = "Optional mailbox directory; defaults to /attempt/feedback.")
        String directory) {
    public BotFeedbackConfiguration {
        if (timeoutSeconds < 1 || timeoutSeconds > 3600) {
            throw new IllegalArgumentException("yca.bot.feedback.timeoutSeconds must be between 1 and 3600 inclusive");
        }
    }
}
