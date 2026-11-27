/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class NoticeTest {
    @Test
    void normalizesConfiguredLabels() {
        final var clock = Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC);
        final var event = Notice.fromConfiguration(
                new NoticeConfiguration("session\r\n1", "  Test\t title  ", "approval_required", "btw", null),
                clock,
                null);
        assertEquals(new Notice(1, "session 1", "Test title", "approval_required", "btw", 1234, null), event);
    }

    @Test
    void boundsLabelsAndDoesNotSplitSurrogates() {
        final var event = Notice.fromConfiguration(
                new NoticeConfiguration("s", "a".repeat(255) + "\uD83D\uDE00", "turn_end", "main", null),
                Clock.systemUTC(),
                null);
        assertEquals(255, event.title().length());
    }

    @Test
    void usesProvidedTimestampIncludingZeroForAcknowledgements() {
        final var clock = Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC);
        for (final var timestamp : List.of(0L, 5678L)) {
            assertEquals(
                    new Notice(1, "s", "", "session_acknowledged", "main", timestamp, null),
                    Notice.fromConfiguration(
                            new NoticeConfiguration("s", "", "session_acknowledged", "main", timestamp), clock, null));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> Notice.fromConfiguration(new NoticeConfiguration("s", "", "turn_end", "main", -1L), clock, null));
    }

    @Test
    void rejectsUnsupportedProtocolTypesAndMissingSessions() {
        assertThrows(IllegalArgumentException.class, () -> new Notice(2, "s", "", "turn_end", "main", 1, null));
        assertThrows(IllegalArgumentException.class, () -> new Notice(1, "s", "", "approve", "main", 1, null));
        assertThrows(IllegalArgumentException.class, () -> new Notice(1, "", "", "turn_end", "main", 1, null));
        assertThrows(IllegalArgumentException.class, () -> new Notice(1, "s", "", "turn_end", "elsewhere", 1, null));
    }

    @Test
    void summaryReportsUnreadSessionsNotPendingApprovals() {
        final var main = new Notice(1, "s", "Title", "approval_required", "main", 1, null);
        final var btw = new Notice(1, "s", "Other", "input_required", "btw", 0, null);
        assertEquals("YCA - 1 unread session", TrayService.summary(List.of(main, btw)));
        assertEquals("YCA - no unread activity", TrayService.summary(List.of()));
    }
}
