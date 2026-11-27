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
package io.yupiik.ia.agent.slack.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.framework.build.api.json.JsonModel;

/** Worker-sanitized, credential-free configuration for the attempt's Slack context mailbox. */
@RootConfiguration("slackContext")
@JsonModel
public record AgentSlackContextConfiguration(
        @Property(defaultValue = "false", documentation = "Enable the attempt-scoped Slack context tool.")
        boolean enabled,

        @Property(
                defaultValue = "\"/attempt/storage/slack-context\"",
                documentation = "Host-prepared private mailbox directory mounted into this attempt.")
        String directory,

        @Property(
                defaultValue = "\"\"",
                documentation = "Trusted attempt channel ID; never selected by tool arguments.")
        String channel,

        @Property(
                defaultValue = "30",
                documentation = "Mailbox call timeout in seconds, including lock wait (1 to 300).")
        int timeoutSeconds) {}
