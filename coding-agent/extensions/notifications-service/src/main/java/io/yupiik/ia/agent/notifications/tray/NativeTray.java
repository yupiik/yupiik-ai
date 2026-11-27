/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications.tray;

import io.yupiik.ia.agent.notifications.Notice;
import java.util.List;

/** Desktop-thread-confined tray integration. Acknowledging reminders never answers a YCA prompt. */
public interface NativeTray extends AutoCloseable {
    void update(String tooltip, boolean attention);

    /** Replace the menu snapshot, independently of attention/blink updates. */
    default void updateNotices(final List<Notice> notices) {}

    /** Consume exact notification snapshots dismissed from a details dialog. */
    default List<Notice> dismissed() {
        return List.of();
    }

    /** Dispatch native events without blocking. */
    void poll();

    /** Block for native events, an explicit wakeup, or timeoutMillis; zero means no timeout. */
    void await(long timeoutMillis);

    /** Thread-safe wakeup, including a wakeup issued just before await starts. */
    void wakeup();

    /** Consume the request to acknowledge all unread notifications. */
    boolean acknowledged();

    boolean closed();

    @Override
    void close();
}
