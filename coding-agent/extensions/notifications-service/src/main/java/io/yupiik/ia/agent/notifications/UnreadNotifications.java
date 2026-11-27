/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** Bounded process-local unread activity. Stopping the companion intentionally forgets all reminders. */
public final class UnreadNotifications {
    private static final int MAX_RETAINED = 256;
    private static final Runnable NO_CHANGE_LISTENER = () -> {};
    private static final Comparator<Notice> NEWEST = Comparator.comparingLong(Notice::timestamp)
            .reversed()
            .thenComparing(Notice::session)
            .thenComparing(Notice::source);
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Key, Notice> notices = new HashMap<>();
    // Oldest updated acknowledgement is evicted first; no unbounded session tombstones.
    private final LinkedHashMap<String, Long> acknowledgedSessions = new LinkedHashMap<>();
    private volatile Runnable changeListener = NO_CHANGE_LISTENER;

    /** Set the desktop owner's thread-safe wakeup, or null to detach it before disposing the native tray. */
    public void onChange(final Runnable listener) {
        changeListener = listener == null ? NO_CHANGE_LISTENER : listener;
    }

    public void publish(final Notice notice) {
        lock.lock();
        try {
            final var acknowledged = acknowledgedSessions.get(notice.session());
            if ("session_acknowledged".equals(notice.type())) {
                if (acknowledged != null && notice.timestamp() <= acknowledged) {
                    return;
                }
                acknowledgedSessions.remove(notice.session());
                acknowledgedSessions.put(notice.session(), notice.timestamp());
                if (acknowledgedSessions.size() > MAX_RETAINED) {
                    acknowledgedSessions.pollFirstEntry();
                }
                notices.values()
                        .removeIf(current -> current.session().equals(notice.session())
                                && current.timestamp() <= notice.timestamp());
            } else {
                if (acknowledged != null && notice.timestamp() <= acknowledged) {
                    return;
                }
                notices.merge(
                        new Key(notice.session(), notice.source()),
                        notice,
                        (old, next) -> old.timestamp() > next.timestamp() ? old : next);
                if (notices.size() > MAX_RETAINED) {
                    final var oldest = notices.values().stream().max(NEWEST).orElseThrow();
                    notices.remove(new Key(oldest.session(), oldest.source()));
                }
            }
        } finally {
            lock.unlock();
        }
        changeListener.run();
    }

    public List<Notice> read() {
        lock.lock();
        try {
            return notices.values().stream().sorted(NEWEST).toList();
        } finally {
            lock.unlock();
        }
    }

    public void acknowledge(final List<Notice> displayed) {
        lock.lock();
        try {
            for (final var notice : displayed) {
                notices.remove(new Key(notice.session(), notice.source()), notice);
            }
        } finally {
            lock.unlock();
        }
        changeListener.run();
    }

    private record Key(String session, String source) {}
}
