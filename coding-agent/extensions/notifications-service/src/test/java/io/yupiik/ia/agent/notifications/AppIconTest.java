/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AppIconTest {
    @Test
    void pngAccessCreatesOnlyThePngAndReusesIt() throws Exception {
        final Path png;
        try (final var icon = new AppIcon();
                final var source =
                        AppIcon.class.getResourceAsStream("/io/yupiik/ia/agent/core/notifications/yca.png")) {
            png = icon.png();
            assertEquals(png, icon.png());
            assertArrayEquals(source.readAllBytes(), Files.readAllBytes(png));
            assertFalse(Files.exists(png.resolveSibling("yca.ico")));
            try (final var files = Files.list(png.getParent())) {
                assertEquals(1, files.count());
            }
        }
        assertFalse(Files.exists(png));
        assertFalse(Files.exists(png.getParent()));
    }

    @Test
    void icoAccessCreatesOnlyTheWindowsContainerAndReusesIt() throws Exception {
        final Path ico;
        try (final var icon = new AppIcon();
                final var source =
                        AppIcon.class.getResourceAsStream("/io/yupiik/ia/agent/core/notifications/yca.png")) {
            ico = icon.ico();
            assertEquals(ico, icon.ico());
            assertFalse(Files.exists(ico.resolveSibling("yca.png")));
            final var bytes = source.readAllBytes();
            final var windows = Files.readAllBytes(ico);
            final var header = ByteBuffer.wrap(windows).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(0, header.getShort());
            assertEquals(1, header.getShort());
            assertEquals(1, header.getShort());
            assertEquals(64, Byte.toUnsignedInt(header.get()));
            assertEquals(64, Byte.toUnsignedInt(header.get()));
            header.position(14);
            assertEquals(bytes.length, header.getInt());
            assertEquals(22, header.getInt());
            assertArrayEquals(bytes, Arrays.copyOfRange(windows, 22, windows.length));
            try (final var files = Files.list(ico.getParent())) {
                assertEquals(1, files.count());
            }
        }
        assertFalse(Files.exists(ico));
        assertFalse(Files.exists(ico.getParent()));
    }

    @Test
    void closesWithoutRequestingAnyIcon() throws Exception {
        final var icon = new AppIcon();
        icon.close(); // No requested format, so there is nothing to materialize or delete.
    }
}
