/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.ia.agent.notifications.tray.NativeTray;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class TrayServiceTest {
    @Test
    void idleLoopBlocksWithoutTimerAndPublicationWakesIt() throws Exception {
        try (final var container = ConfiguringContainer.of().start();
                final var endpoint = container.lookup(NotificationEndpoint.class);
                final var executor = Executors.newSingleThreadExecutor()) {
            final var state = new UnreadNotifications();
            final var tray = new EventTray();
            final var loop = executor.submit(() -> {
                new TrayService(endpoint.instance(), Clock.systemUTC()).run(state, tray, false);
                return null;
            });
            try {
                assertTrue(tray.waiting.await(2, TimeUnit.SECONDS));
                assertEquals(0, tray.timeout);
                state.publish(new Notice(1, "s", "Reactive notification", "approval_required", "main", 1, null));
                loop.get(3, TimeUnit.SECONDS);
                assertEquals(1, tray.waits);
                assertEquals(2, tray.updates);
                assertEquals("YCA - 1 unread session", tray.title);
                assertTrue(tray.disposed);
            } finally {
                tray.stopped = true;
                tray.wakeup();
            }
        }
    }

    @Test
    void acknowledgmentClearsRemindersAndDisposesTray() throws Exception {
        final var clock = Clock.fixed(Instant.ofEpochMilli(2000), ZoneOffset.UTC);
        try (final var container = ConfiguringContainer.of().start();
                final var endpoint = container.lookup(NotificationEndpoint.class)) {
            final var state = new UnreadNotifications();
            state.publish(new Notice(1, "s", "Session", "approval_required", "main", 2000, null));
            final var tray = new FakeTray(true);
            new TrayService(endpoint.instance(), clock).run(state, tray, false);
            assertTrue(tray.disposed);
            assertTrue(tray.sawAttention);
            assertEquals("YCA - no unread activity", tray.title);
            assertTrue(state.read().isEmpty());
        }
    }

    @Test
    void stoppingWithoutAcknowledgmentDoesNotAcknowledgeYcaRequests() throws Exception {
        final var clock = Clock.fixed(Instant.ofEpochMilli(3000), ZoneOffset.UTC);
        try (final var container = ConfiguringContainer.of().start();
                final var endpoint = container.lookup(NotificationEndpoint.class)) {
            final var state = new UnreadNotifications();
            state.publish(new Notice(1, "s", "Session", "input_required", "main", 3000, null));
            final var tray = new FakeTray(false);
            new TrayService(endpoint.instance(), clock).run(state, tray, true);
            assertTrue(tray.disposed);
            assertFalse(tray.sawAttention); // odd second of the optional blink cycle
            assertEquals(1, state.read().size());
        }
    }

    @Test
    void publishesFirstEmptySnapshotOnlyOnceWhenNothingChanges() throws Exception {
        final var tray = new ScriptedTray(ignored -> {});
        run(new UnreadNotifications(), tray);
        assertEquals(List.of(List.of()), tray.snapshots);
        assertEquals(1, tray.titles.size());
        assertEquals("YCA - no unread activity", tray.titles.getFirst());
        assertTrue(tray.disposed);
    }

    @Test
    void blinkChangesAttentionWithoutRebuildingTheMenu() throws Exception {
        final var state = new UnreadNotifications();
        final var notice = new Notice(1, "one", "Blink", "turn_end", "main", 1, null);
        state.publish(notice);
        final var now = new AtomicLong();
        final var clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(final ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(now.get());
            }
        };
        final var tray = new ScriptedTray(current -> now.set(1000L * (current.polls - 1)));
        try (final var container = ConfiguringContainer.of().start();
                final var endpoint = container.lookup(NotificationEndpoint.class)) {
            new TrayService(endpoint.instance(), clock).run(state, tray, true);
        }
        assertEquals(List.of(List.of(notice)), tray.snapshots);
        assertEquals(List.of(true, false, true), tray.attentions);
    }

    @Test
    void dismissesOnlySelectedVersionsBeforePublishingTheLatestSnapshot() throws Exception {
        final var state = new UnreadNotifications();
        final var selected = new Notice(1, "one", "Selected", "approval_required", "main", 1, null);
        final var replaced = new Notice(1, "one", "Old", "input_required", "btw", 2, null);
        final var other = new Notice(1, "two", "Other", "turn_end", "main", 3, null);
        final var newer = new Notice(1, "one", "New", "input_required", "btw", 4, null);
        final var arrived = new Notice(1, "three", "Arrived", "turn_end", "main", 5, null);
        state.publish(selected);
        state.publish(replaced);
        state.publish(other);
        final var tray = new ScriptedTray(current -> {
            if (current.polls == 2) {
                state.publish(newer);
                state.publish(arrived);
                current.dismissals = List.of(selected, replaced);
            }
        });
        run(state, tray);
        final var remaining = List.of(arrived, newer, other);
        assertEquals(remaining, state.read());
        assertEquals(List.of(List.of(other, replaced, selected), remaining), tray.snapshots);
        assertEquals(List.of("YCA - 2 unread sessions", "YCA - 3 unread sessions"), tray.titles);
        assertTrue(tray.disposed);
    }

    @Test
    void dismissingOneMenuRowPreservesOtherRemindersInTheSameSession() throws Exception {
        final var state = new UnreadNotifications();
        final var selected = new Notice(1, "one", "Selected", "approval_required", "main", 1, null);
        final var other = new Notice(1, "one", "Other source", "input_required", "btw", 2, null);
        state.publish(selected);
        state.publish(other);
        final var tray = new ScriptedTray(current -> {
            if (current.polls == 2) {
                current.dismissals = List.of(selected);
            }
        });
        run(state, tray);
        assertEquals(List.of(other), state.read());
        assertEquals(List.of(List.of(other, selected), List.of(other)), tray.snapshots);
        assertEquals(List.of("YCA - 1 unread session", "YCA - 1 unread session"), tray.titles);
    }

    @Test
    void closingDetailsWithoutDismissalLeavesEveryNoticeUnread() throws Exception {
        final var state = new UnreadNotifications();
        final var notice = new Notice(1, "one", "Close details", "input_required", "main", 1, null);
        state.publish(notice);
        // Closing a details view returns no dismissals and does not close the tray.
        final var tray = new ScriptedTray(ignored -> {});
        run(state, tray);
        assertEquals(List.of(notice), state.read());
        assertEquals(List.of(List.of(notice)), tray.snapshots);
        assertEquals(List.of("YCA - 1 unread session"), tray.titles);
    }

    private void run(final UnreadNotifications state, final NativeTray tray) throws Exception {
        try (final var container = ConfiguringContainer.of().start();
                final var endpoint = container.lookup(NotificationEndpoint.class)) {
            new TrayService(endpoint.instance(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)).run(state, tray, false);
        }
    }

    private static final class ScriptedTray implements NativeTray {
        private final Consumer<ScriptedTray> onPoll;
        private final List<List<Notice>> snapshots = new ArrayList<>();
        private final List<String> titles = new ArrayList<>();
        private final List<Boolean> attentions = new ArrayList<>();
        private List<Notice> dismissals = List.of();
        private int polls;
        private boolean disposed;

        private ScriptedTray(final Consumer<ScriptedTray> onPoll) {
            this.onPoll = onPoll;
        }

        @Override
        public void update(final String tooltip, final boolean attention) {
            titles.add(tooltip);
            attentions.add(attention);
        }

        @Override
        public void updateNotices(final List<Notice> notices) {
            snapshots.add(List.copyOf(notices));
        }

        @Override
        public List<Notice> dismissed() {
            final var result = dismissals;
            dismissals = List.of();
            return result;
        }

        @Override
        public void poll() {
            polls++;
            onPoll.accept(this);
        }

        @Override
        public void await(final long timeoutMillis) {}

        @Override
        public void wakeup() {}

        @Override
        public boolean acknowledged() {
            return false;
        }

        @Override
        public boolean closed() {
            return polls >= 4;
        }

        @Override
        public void close() {
            disposed = true;
        }
    }

    private static final class FakeTray implements NativeTray {
        private final boolean acknowledge;
        private int updates;
        private int polls;
        private boolean disposed;
        private boolean sawAttention;
        private String title;

        private FakeTray(final boolean acknowledge) {
            this.acknowledge = acknowledge;
        }

        @Override
        public void update(final String tooltip, final boolean attention) {
            title = tooltip;
            sawAttention |= attention;
            updates++;
        }

        @Override
        public void poll() {
            polls++;
        }

        @Override
        public void await(final long timeoutMillis) {
            // The scripted menu event is ready for the next dispatch.
        }

        @Override
        public void wakeup() {
            // The scripted tray never blocks.
        }

        @Override
        public boolean acknowledged() {
            return acknowledge && updates == 1 && polls == 2;
        }

        @Override
        public boolean closed() {
            return acknowledge ? updates >= 2 : updates >= 1;
        }

        @Override
        public void close() {
            disposed = true;
        }
    }

    private static final class EventTray implements NativeTray {
        private final CountDownLatch waiting = new CountDownLatch(1);
        private final Semaphore events = new Semaphore(0);
        private volatile boolean stopped;
        private long timeout;
        private int waits;
        private int updates;
        private String title;
        private boolean disposed;

        @Override
        public void update(final String tooltip, final boolean attention) {
            title = tooltip;
            if (++updates == 2) {
                stopped = true;
            }
        }

        @Override
        public void poll() {}

        @Override
        public void await(final long timeoutMillis) {
            timeout = timeoutMillis;
            waits++;
            waiting.countDown();
            try {
                if (timeoutMillis == 0) {
                    events.acquire();
                } else {
                    events.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS);
                }
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void wakeup() {
            events.release();
        }

        @Override
        public boolean acknowledged() {
            return false;
        }

        @Override
        public boolean closed() {
            return stopped;
        }

        @Override
        public void close() {
            disposed = true;
        }
    }
}
