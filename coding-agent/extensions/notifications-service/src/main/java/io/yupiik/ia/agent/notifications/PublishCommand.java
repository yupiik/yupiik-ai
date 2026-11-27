/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import java.io.IOException;
import java.time.Clock;

@DefaultScoped
@Command(name = "publish", description = "Publish a YCA notification to an already running notification service.")
public class PublishCommand implements Runnable {
    private final ServiceConfiguration configuration;
    private final NoticeConfiguration notice;
    private final NotificationEndpoint endpoint;
    private final Clock clock;
    private final Configuration values;

    public PublishCommand(
            final ServiceConfiguration configuration,
            final NoticeConfiguration notice,
            final NotificationEndpoint endpoint,
            final Clock clock,
            final Configuration values) {
        this.configuration = configuration;
        this.notice = notice;
        this.endpoint = endpoint;
        this.clock = clock;
        this.values = values;
    }

    @Override
    public void run() {
        try {
            endpoint.publish(
                    configuration,
                    Notice.fromConfiguration(
                            notice, clock, values.get("yca.notification.origin").orElse(null)));
        } catch (final IOException error) {
            throw new IllegalStateException("Cannot publish notification; ensure the notification service is running.");
        }
    }
}
