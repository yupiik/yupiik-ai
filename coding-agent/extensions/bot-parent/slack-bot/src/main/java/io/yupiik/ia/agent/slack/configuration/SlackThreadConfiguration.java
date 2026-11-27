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
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.List;
import java.util.regex.Pattern;

/** Independent initial and cumulative thread budgets; no channel scope is inferred. */
@JsonModel
public record SlackThreadConfiguration(
        @Property(
                defaultValue = "false",
                documentation =
                        "Prefetch bounded current-thread context and enable explicit thread pagination; requires"
                                + " slack.context.enabled.")
        boolean enabled,

        @Property(
                documentation =
                        "Exact independently verified public, unshared originating channel IDs; absent or empty denies"
                                + " thread reads.")
        List<String> channels,

        @Property(
                defaultValue = "20",
                documentation = "Maximum initial snapshot messages, not the total attempt limit; validated range 1-20.")
        int initialMessages,

        @Property(
                defaultValue = "100",
                documentation = "Cumulative delivered thread-message limit across prefetch and explicit tool pages per"
                        + " attempt.")
        int maxMessages,

        @Property(
                defaultValue = "65536",
                documentation =
                        "Cumulative conservative JSON thread response-byte limit per attempt, also inside the overall"
                                + " slack.context.maxResponseBytes budget.")
        int maxBytes,

        @Property(
                defaultValue = "3",
                documentation =
                        "Thread upstream-call sublimit per attempt, including failures, shared by prefetch and tool"
                                + " pages and also inside the overall slack.context.maxRequests budget.")
        int maxRequests,

        @Property(
                defaultValue = "24",
                documentation =
                        "Recent-reply horizon in hours, bounded to one year; an older root may be included separately.")
        int lookbackHours) {
    private static final Pattern PUBLIC_CHANNEL = Pattern.compile("C[A-Z0-9]{1,63}");

    public SlackThreadConfiguration {
        channels = channels == null ? List.of() : List.copyOf(channels);
        if (initialMessages < 1
                || initialMessages > 20
                || maxMessages < initialMessages
                || maxMessages > 1000
                || maxBytes < 1024
                || maxBytes > 16 * 1024 * 1024
                || maxRequests < 1
                || maxRequests > 1000
                || lookbackHours < 1
                || lookbackHours > 8760
                || channels.size() > 1024) {
            throw new IllegalArgumentException("Invalid Slack thread limits");
        }
        for (final var channel : channels) {
            if (!PUBLIC_CHANNEL.matcher(channel).matches()) {
                throw new IllegalArgumentException("Slack thread channels must be public channel IDs");
            }
        }
    }
}
