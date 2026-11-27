/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import java.io.IOException;

@DefaultScoped
@Command(name = "serve", description = "Run the notification service and desktop tray until Quit.")
public class ServeCommand implements Runnable {
    private final ServiceConfiguration configuration;
    private final TrayService service;

    public ServeCommand(final ServiceConfiguration configuration, final TrayService service) {
        this.configuration = configuration;
        this.service = service;
    }

    @Override
    public void run() {
        try {
            service.run(configuration);
        } catch (final IOException error) {
            throw new IllegalStateException("Cannot run the notification service.");
        } catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Notification service interrupted.");
        }
    }
}
