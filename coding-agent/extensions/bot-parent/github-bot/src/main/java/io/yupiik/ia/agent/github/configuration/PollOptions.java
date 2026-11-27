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
package io.yupiik.ia.agent.github.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;

public record PollOptions(
        @Property(
                defaultValue = "\"now\"",
                documentation = "Initial scan lower bound: now on first scan, or an ISO-8601 instant for backfill.")
        String since,

        @Property(
                defaultValue = "20",
                documentation = "Maximum new pages per endpoint per poll, plus one overlap page when resuming."
                        + " Incomplete scans never advance watermarks.")
        int pageBudget,

        @Property(documentation = "Native pull request review mention polling.")
        Reviews reviews,

        @Property(documentation = "Repository Discussion mention polling.")
        Discussions discussions,

        @Property(
                defaultValue = "4",
                documentation =
                        "Maximum concurrent repository scans or native-review GraphQL batches, from 1 (serial) to 16.")
        int parallelism,

        @Property(documentation = "Adaptive mention-search discovery with periodic direct reconciliation.")
        Search search) {
    public PollOptions {
        if (parallelism < 1 || parallelism > 16) {
            throw new IllegalArgumentException("Poll parallelism must be between 1 and 16");
        }
    }

    public record Search(
            @Property(
                    defaultValue = "true",
                    documentation =
                            "Use paginated mention search to select repositories between direct reconciliations.")
            boolean enabled,

            @Property(
                    defaultValue = "3600",
                    documentation =
                            "Seconds between completed direct reconciliations (1 to 604800); the first poll reconciles directly.")
            int reconciliationIntervalSeconds) {
        public Search {
            if (reconciliationIntervalSeconds < 1 || reconciliationIntervalSeconds > 604800) {
                throw new IllegalArgumentException(
                        "Search reconciliation interval must be between 1 and 604800 seconds");
            }
        }
    }

    public record Reviews(
            @Property(defaultValue = "false", documentation = "Poll submitted review bodies for mentions.")
            boolean enabled) {}

    public record Discussions(
            @Property(
                    defaultValue = "false",
                    documentation = "Poll Discussions, comments and replies for help or solve mentions.")
            boolean enabled) {}
}
