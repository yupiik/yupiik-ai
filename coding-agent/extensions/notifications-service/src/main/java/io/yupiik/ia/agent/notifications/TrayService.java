/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.ia.agent.notifications.tray.LinuxTray;
import io.yupiik.ia.agent.notifications.tray.MacTray;
import io.yupiik.ia.agent.notifications.tray.NativeTray;
import io.yupiik.ia.agent.notifications.tray.WindowsTray;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs entirely on the desktop's first thread, including native callbacks and resource cleanup. */
@DefaultScoped
public class TrayService {
    private final NotificationEndpoint endpoint;
    private final Clock clock;

    public TrayService(final NotificationEndpoint endpoint, final Clock clock) {
        this.endpoint = endpoint;
        this.clock = clock;
    }

    public void run(final ServiceConfiguration configuration) throws IOException, InterruptedException {
        final var unread = new UnreadNotifications();
        try (final var icon = new AppIcon();
                final var listener = endpoint.listen(configuration, unread)) {
            final var tray = tray(icon, configuration);
            System.out.println("YCA notifications tray initialized on loopback port " + listener.port()
                    + ". Visibility depends on the desktop tray host.");
            run(unread, tray, configuration.blink());
        }
    }

    public void run(final UnreadNotifications unread, final NativeTray tray, final boolean blink)
            throws InterruptedException {
        final var stopping = new AtomicBoolean();
        final var finished = new CountDownLatch(1);
        final var shutdown = new Thread(
                () -> {
                    stopping.set(true);
                    try {
                        tray.wakeup();
                    } finally {
                        try {
                            finished.await(2, TimeUnit.SECONDS);
                        } catch (final InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                },
                "yca-notifications-shutdown");
        try (tray) {
            Runtime.getRuntime().addShutdownHook(shutdown);
            unread.onChange(tray::wakeup);
            try {
                List<Notice> displayed = List.of();
                boolean first = true;
                boolean previousAttention = false;
                while (!stopping.get()
                        && !tray.closed()
                        && !Thread.currentThread().isInterrupted()) {
                    tray.poll();
                    if (tray.closed() || stopping.get()) {
                        break;
                    }
                    if (tray.acknowledged()) {
                        unread.acknowledge(displayed);
                    }
                    final var dismissed = tray.dismissed();
                    if (!dismissed.isEmpty()) {
                        unread.acknowledge(dismissed);
                    }
                    final var latest = unread.read();
                    final var now = clock.millis();
                    final var attention = !latest.isEmpty() && (!blink || Math.floorDiv(now, 1000) % 2 == 0);
                    if (first || !latest.equals(displayed)) {
                        tray.updateNotices(latest);
                    }
                    if (first || !latest.equals(displayed) || attention != previousAttention) {
                        tray.update(summary(latest), attention);
                        displayed = latest;
                        previousAttention = attention;
                        first = false;
                    }
                    if (!tray.closed() && !stopping.get()) {
                        tray.await(blink && !latest.isEmpty() ? 1000 - Math.floorMod(now, 1000) : 0);
                    }
                }
            } finally {
                unread.onChange(null);
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (final IllegalStateException ignored) {
                    // The shutdown hook is already waking the desktop thread and waiting for cleanup.
                }
            }
        } finally {
            finished.countDown();
        }
    }

    public static String summary(final List<Notice> notices) {
        return NotificationPresentation.summary(notices);
    }

    private NativeTray tray(final AppIcon icon, final ServiceConfiguration configuration) throws IOException {
        final var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) {
            return new LinuxTray(icon.png(), configuration.linuxBackend());
        }
        if (os.contains("windows")) {
            return new WindowsTray(icon.ico());
        }
        if (os.contains("mac")) {
            return new MacTray(icon.png());
        }
        throw new IllegalStateException("No native tray backend for " + os);
    }
}
