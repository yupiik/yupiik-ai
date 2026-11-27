/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationPresentationTest {
    @Test
    void originIsDisplayedWithoutReplacingSideQuestionSource() {
        final var notice = new Notice(1, "s", "Title", "approval_required", "btw", 1, "SANDBOX");
        assertTrue(NotificationPresentation.preview(notice).startsWith("Sandbox approval requested"));
        assertTrue(NotificationPresentation.details(notice).contains("Source: btw"));
        assertTrue(NotificationPresentation.details(notice).contains("Origin: SANDBOX"));
    }

    @Test
    void summaryCountsDistinctSessionsWithoutExposingTitles() {
        final var first = notice("one", "Private title", "approval_required", "main");
        final var sameSession = notice("one", "Another title", "input_required", "btw");
        final var otherSession = notice("two", "Other title", "turn_end", "main");
        assertEquals("YCA - no unread activity", NotificationPresentation.summary(List.of()));
        assertEquals("YCA - 1 unread session", NotificationPresentation.summary(List.of(first, sameSession)));
        assertEquals(
                "YCA - 2 unread sessions", NotificationPresentation.summary(List.of(first, sameSession, otherSession)));
        assertEquals(NotificationPresentation.summary(List.of(first)), TrayService.summary(List.of(first)));
    }

    @Test
    void previewUsesReadableKindsAndFallsBackToSession() {
        assertEquals(
                "Approval requested - Title",
                NotificationPresentation.preview(notice("s", "Title", "approval_required", "main")));
        assertEquals(
                "Input requested - Title",
                NotificationPresentation.preview(notice("s", "Title", "input_required", "btw")));
        assertEquals("Turn ended - Title", NotificationPresentation.preview(notice("s", "Title", "turn_end", "main")));
        assertEquals(
                "Input requested - session-id",
                NotificationPresentation.preview(notice("session-id", " \t\n", "input_required", "main")));
        assertEquals(
                "Input requested - session-id",
                NotificationPresentation.preview(notice("session-id", "", "input_required", "main")));
    }

    @Test
    void previewNormalizesWhitespaceToASingleLine() {
        assertEquals(
                "Approval requested - First second third fourth",
                NotificationPresentation.preview(
                        notice("s", "  First\tsecond\r\nthird\nfourth  ", "approval_required", "main")));
        assertEquals(
                "Turn ended - First second",
                NotificationPresentation.preview(notice("s", "First\u0007second", "turn_end", "main")));
        assertEquals(
                "Turn ended - First second third",
                NotificationPresentation.preview(notice("s", "First\u2028second\u2029third", "turn_end", "main")));
        assertEquals("Turn ended - s", NotificationPresentation.preview(notice("s", "\u00a0", "turn_end", "main")));
    }

    @Test
    void previewTruncatesOnlyBeyondSixtyCodePointsIncludingSuffix() {
        final var prefix = "Turn ended - ";
        final var fitting = prefix + "x".repeat(60 - prefix.length());
        assertEquals(
                fitting,
                NotificationPresentation.preview(notice("s", "x".repeat(60 - prefix.length()), "turn_end", "main")));
        assertEquals(
                fitting.substring(0, 57) + "...",
                NotificationPresentation.preview(notice("s", "x".repeat(61 - prefix.length()), "turn_end", "main")));
    }

    @Test
    void previewDoesNotSplitSupplementaryUnicodeCharacters() {
        final var symbol = "\uD83D\uDE80";
        final var prefix = "Input requested - ";
        final var fitting = prefix + symbol.repeat(60 - prefix.length());
        assertEquals(
                fitting,
                NotificationPresentation.preview(
                        notice("s", symbol.repeat(60 - prefix.length()), "input_required", "main")));
        final var preview = NotificationPresentation.preview(notice("s", symbol.repeat(70), "input_required", "main"));
        assertEquals(prefix + symbol.repeat(57 - prefix.length()) + "...", preview);
        assertEquals(60, preview.codePointCount(0, preview.length()));
    }

    @Test
    void detailsRetainFullPlainTextFieldsAndInstantTimestamp() {
        final var title = "Full <title> & text " + "long ".repeat(30) + "\nSecond line";
        final var notice = notice("session-full-id", title, "approval_required", "btw");
        final var details = NotificationPresentation.details(notice);
        assertTrue(details.contains("Approval requested"), details);
        assertTrue(details.contains(title), details);
        assertTrue(details.contains(notice.session()), details);
        assertTrue(details.contains(notice.source()), details);
        assertTrue(details.contains(Instant.ofEpochMilli(notice.timestamp()).toString()), details);
    }

    private Notice notice(final String session, final String title, final String type, final String source) {
        return new Notice(1, session, title, type, source, 1_700_000_000_123L, null);
    }
}
