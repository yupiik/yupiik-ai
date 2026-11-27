/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

@RootConfiguration("yca.notification")
public record NoticeConfiguration(
        @Property(documentation = "Session identifier for the reminder.")
        String session,

        @Property(defaultValue = "\"\"", documentation = "Session title for the reminder.")
        String title,

        @Property(defaultValue = "\"turn_end\"", documentation = "Notification event type.")
        String type,

        @Property(defaultValue = "\"main\"", documentation = "Notification event source.")
        String source,

        @Property(
                defaultValue = "null",
                documentation = "Event timestamp in epoch milliseconds; defaults to the publisher's clock.")
        Long timestamp) {}
