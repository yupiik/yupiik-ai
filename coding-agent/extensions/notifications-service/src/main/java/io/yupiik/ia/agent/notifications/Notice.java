/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.time.Clock;
import java.util.Set;
import java.util.regex.Pattern;

/** Versioned, deliberately minimal local reminder. Never contains tool arguments or assistant reply excerpts. */
@JsonModel
public record Notice(
        int version, String session, String title, String type, String source, long timestamp, String origin) {
    private static final Set<String> TYPES =
            Set.of("turn_end", "input_required", "approval_required", "session_acknowledged");
    private static final Pattern CONTROLS = Pattern.compile("[\\p{Cntrl}\\s]+");

    public Notice {
        if (version != 1
                || session == null
                || session.isBlank()
                || session.length() > 256
                || title == null
                || title.length() > 256
                || type == null
                || !TYPES.contains(type)
                || !("main".equals(source) || "btw".equals(source))
                || timestamp < 0
                || (origin != null && !Set.of("PERMISSION", "SANDBOX").contains(origin))) {
            throw new IllegalArgumentException("Invalid notification envelope.");
        }
    }

    public static Notice fromConfiguration(
            final NoticeConfiguration configuration, final Clock clock, final String origin) {
        return new Notice(
                1,
                label(configuration.session()),
                label(configuration.title()),
                configuration.type(),
                configuration.source(),
                configuration.timestamp() == null ? clock.millis() : configuration.timestamp(),
                origin);
    }

    private static String label(final String text) {
        final var clean =
                CONTROLS.matcher(text == null ? "" : text).replaceAll(" ").strip();
        final var end = Math.min(256, clean.length());
        return clean.substring(0, end > 0 && Character.isHighSurrogate(clean.charAt(end - 1)) ? end - 1 : end);
    }
}
