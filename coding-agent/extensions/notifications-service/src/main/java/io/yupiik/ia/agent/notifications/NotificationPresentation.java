/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/** Plain-text presentation shared by the native desktop integrations. */
public final class NotificationPresentation {
    private static final Pattern WHITESPACE = Pattern.compile("[\\p{Cntrl}\\s]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final int PREVIEW_LENGTH = 60;

    private NotificationPresentation() {}

    public static String summary(final List<Notice> notices) {
        if (notices.isEmpty()) {
            return "YCA - no unread activity";
        }
        final var sessions = notices.stream().map(Notice::session).distinct().count();
        return "YCA - " + sessions + " unread session" + (sessions == 1 ? "" : "s");
    }

    public static String preview(final Notice notice) {
        final var title = WHITESPACE.matcher(notice.title()).replaceAll(" ").strip();
        final var text = kind(notice) + " - "
                + (title.isEmpty()
                        ? WHITESPACE.matcher(notice.session()).replaceAll(" ").strip()
                        : title);
        return text.codePointCount(0, text.length()) <= PREVIEW_LENGTH
                ? text
                : text.substring(0, text.offsetByCodePoints(0, PREVIEW_LENGTH - 3)) + "...";
    }

    public static String details(final Notice notice) {
        return kind(notice) + "\n\n" + notice.title() + "\n\nSession: " + notice.session()
                + "\nSource: " + notice.source() + (notice.origin() == null ? "" : "\nOrigin: " + notice.origin())
                + "\nTime: " + Instant.ofEpochMilli(notice.timestamp())
                + "\n\nThis is an activity reminder. Dismissing it does not answer a YCA prompt.";
    }

    private static String kind(final Notice notice) {
        return switch (notice.type()) {
            case "approval_required" ->
                "SANDBOX".equals(notice.origin())
                        ? "Sandbox approval requested"
                        : "PERMISSION".equals(notice.origin()) ? "Tool permission requested" : "Approval requested";
            case "input_required" -> "Input requested";
            default -> "Turn ended";
        };
    }
}
