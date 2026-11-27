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
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Trusted controller configuration. Tokens never enter task documents or attempts. */
@RootConfiguration("slack")
@JsonModel
public record SlackConfiguration(
        @Property(
                defaultValue = "\"\"",
                documentation = "One exact T... workspace ID; required for installation validation.")
        String workspace,

        @Property(
                defaultValue = "\"\"",
                documentation = "Exact U... or W... bot user ID, not app ID or display name; required for installation"
                        + " validation.")
        String botUser,

        @Property(documentation = "Exact allowed C.../G... channel IDs; absent or empty denies all.")
        List<String> channels,

        @Property(
                documentation = "Exact allowed U.../W... requester IDs; absent or empty denies all unless"
                        + " slack.allowChannelMembers is enabled.")
        List<String> users,

        @Property(
                defaultValue = "false",
                documentation = "Opt in to requests from valid user IDs in exact allowed channels without a requester"
                        + " allowlist; bot/self events remain denied. Does not bypass workspace/channel checks or"
                        + " enable Slack Connect.")
        boolean allowChannelMembers,

        @Property(
                defaultValue = "false",
                documentation = "Explicitly authorize external/shared channel audiences; verify the channel audience"
                        + " independently.")
        boolean allowSlackConnect,

        @Property(
                defaultValue = "\"SLACK_APP_TOKEN\"",
                documentation = "Name of the controller environment variable holding the xapp- Socket Mode token.")
        String appTokenEnv,

        @Property(
                defaultValue = "\"SLACK_BOT_TOKEN\"",
                documentation = "Name of the controller environment variable holding the xoxb- API token.")
        String botTokenEnv,

        @Property(
                defaultValue = "\".yca-slack\"",
                documentation = "Private durable controller state root; never mount in agents.")
        String home,

        @Property(defaultValue = "\"\"", documentation = "Trusted operator-owned agent JSON configuration path.")
        String agentConfiguration,

        @Property(defaultValue = "100", documentation = "Maximum durable nonterminal tasks; validated range 1-10000.")
        int maxPending,

        @Property(
                defaultValue = "4",
                documentation =
                        "Maximum nonterminal tasks per requester; at least 1 and no greater than slack.maxPending.")
        int maxPendingPerUser,

        @Property(defaultValue = "16000", documentation = "Maximum mention input characters; validated range 1-100000.")
        int maxInputChars,

        @Property(
                defaultValue = "12000",
                documentation =
                        "Maximum structured answer characters; validated range 1-30000, not an LLM token budget.")
        int maxAnswerChars,

        @Property(
                defaultValue = "600",
                documentation =
                        "Maximum ask_user wait in seconds; validated range 1-3600. Waiting retains the worker slot.")
        int questionTimeoutSeconds,

        @Property(
                defaultValue = "2592000",
                documentation =
                        "Terminal task retention age in seconds; minimum 86400 (one day), not a filesystem quota.")
        int retentionSeconds) {
    private static final Pattern TOKEN_ENVIRONMENT_NAME = Pattern.compile("[A-Z_][A-Z_0-9]{0,127}");
    private static final Pattern CHANNEL_ID = Pattern.compile("[CG][A-Z0-9]{1,63}");
    private static final Pattern USER_ID = Pattern.compile("[UW][A-Z0-9]{1,63}");
    private static final Pattern WORKSPACE_ID = Pattern.compile("T[A-Z0-9]{1,63}");

    public SlackConfiguration {
        channels = channels == null ? List.of() : List.copyOf(channels);
        users = users == null ? List.of() : List.copyOf(users);
        if (maxPending < 1
                || maxPending > 10000
                || maxPendingPerUser < 1
                || maxPendingPerUser > maxPending
                || maxInputChars < 1
                || maxInputChars > 100000
                || maxAnswerChars < 1
                || maxAnswerChars > 30000
                || questionTimeoutSeconds < 1
                || questionTimeoutSeconds > 3600
                || retentionSeconds < 86400) {
            throw new IllegalArgumentException("Invalid Slack limits");
        }
        if (home == null
                || home.isBlank()
                || appTokenEnv == null
                || botTokenEnv == null
                || !TOKEN_ENVIRONMENT_NAME.matcher(appTokenEnv).matches()
                || !TOKEN_ENVIRONMENT_NAME.matcher(botTokenEnv).matches()) {
            throw new IllegalArgumentException("Invalid Slack state or token environment configuration");
        }
        for (final var id : channels) {
            if (!CHANNEL_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("Invalid allowed channel ID");
            }
        }
        for (final var id : users) {
            if (!USER_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("Invalid allowed user ID");
            }
        }
    }

    public Path stateRoot() {
        return Path.of(home).toAbsolutePath().normalize();
    }
    /** Requester policy only; callers must also enforce workspace, channel and event/task binding. */
    public boolean allowsRequester(final String user) {
        return user != null
                && USER_ID.matcher(user).matches()
                && !user.equals(botUser)
                && (allowChannelMembers || users.contains(user));
    }

    public void validateInstallation() {
        if (workspace == null
                || !WORKSPACE_ID.matcher(workspace).matches()
                || botUser == null
                || !USER_ID.matcher(botUser).matches()) {
            throw new IllegalArgumentException("Configure exact slack.workspace and slack.botUser IDs");
        }
    }
}
