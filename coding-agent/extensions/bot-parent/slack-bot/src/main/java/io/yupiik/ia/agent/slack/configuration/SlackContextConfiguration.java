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
import java.util.List;
import java.util.regex.Pattern;

/** Controller-only, deny-by-default context policy. Never pass action tokens to an agent. */
@RootConfiguration("slack.context")
@JsonModel
public record SlackContextConfiguration(
        @Property(
                defaultValue = "false",
                documentation =
                        "Host opt-in to the attempt-scoped Slack context tool; does not enable search by itself.")
        boolean enabled,

        @Property(
                defaultValue = "true",
                documentation = "Allow minimal names for already granted raw member IDs when context is enabled.")
        boolean resolveMentions,

        @Property(
                defaultValue = "false",
                documentation =
                        "Separate opt-in to approved public-message search; requires context enabled and an approved"
                                + " originating source channel.")
        boolean searchEnabled,

        @Property(
                documentation =
                        "Exact independently verified public, unshared source channel IDs; absent or empty denies"
                                + " search. Search is restricted to the admitted originating channel, which must be on"
                                + " this list.")
        List<String> sourceChannels,

        @Property(defaultValue = "30", documentation = "Maximum search lookback in days.")
        int lookbackDays,

        @Property(defaultValue = "32", documentation = "Maximum member grants and cached names per attempt.")
        int maxMembers,

        @Property(defaultValue = "12", documentation = "Maximum upstream calls per attempt, including failures.")
        int maxRequests,

        @Property(
                defaultValue = "20",
                documentation =
                        "Cumulative search-result message budget per attempt, separate from the thread-message budget;"
                                + " not a per-page allowance.")
        int maxResults,

        @Property(
                defaultValue = "131072",
                documentation = "Maximum cumulative conservative JSON response bytes per attempt.")
        int maxResponseBytes,

        @Property(
                defaultValue = "20",
                documentation = "Maximum wait in seconds per upstream call; not a latency promise.")
        int timeoutSeconds,

        @Property(
                defaultValue = "2",
                documentation = "Maximum concurrent controller context calls across all attempts; does not replace"
                        + " controller/provider admission.")
        int maxConcurrent,

        @Property(
                defaultValue = "600",
                documentation =
                        "Absolute admission-to-expiry context lifetime in seconds; validated range 1-600. Queue wait"
                                + " consumes it, and Slack may revoke search authority earlier.")
        int actionRetentionSeconds,

        @Property(
                defaultValue = "false",
                documentation =
                        "Explicitly accept only missing tmpfs noswap for Slack context; context data may persist in"
                                + " host swap. All other storage, privacy and logging controls remain mandatory.")
        boolean allowSwapBackedTmpfs,

        @Property(documentation = "Deny-by-default bounded public-channel thread context.")
        SlackThreadConfiguration thread,

        @Property(
                defaultValue = "false",
                documentation =
                        "Explicitly accept Docker retaining agent stdout/stderr containing Slack context. Does not"
                                + " enable logging by itself; set slack.executor.docker.disableContainerLogs=false as"
                                + " well.")
        boolean allowContainerLogs) {
    private static final Pattern PUBLIC_CHANNEL = Pattern.compile("C[A-Z0-9]{1,63}");

    public SlackContextConfiguration {
        sourceChannels = sourceChannels == null ? List.of() : List.copyOf(sourceChannels);
        thread = thread == null ? new SlackThreadConfiguration(false, List.of(), 20, 100, 65536, 3, 24) : thread;
        if (lookbackDays < 1
                || lookbackDays > 365
                || maxMembers < 1
                || maxMembers > 1024
                || maxRequests < 1
                || maxRequests > 1000
                || maxResults < 1
                || maxResults > 1000
                || maxResponseBytes < 1024
                || maxResponseBytes > 16 * 1024 * 1024
                || timeoutSeconds < 1
                || timeoutSeconds > 120
                || maxConcurrent < 1
                || maxConcurrent > 32
                || actionRetentionSeconds < 1
                || actionRetentionSeconds > 600
                || sourceChannels.size() > 1024) {
            throw new IllegalArgumentException("Invalid Slack context limits");
        }
        for (final var channel : sourceChannels) {
            if (!PUBLIC_CHANNEL.matcher(channel).matches()) {
                throw new IllegalArgumentException("Slack context sources must be public channel IDs");
            }
        }
    }
}
