/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Lazily materializes the existing YCA icon without loading AWT. Owned by the foreground service. */
public final class AppIcon implements AutoCloseable {
    private Path directory;
    private Path png;
    private Path ico;

    public Path png() throws IOException {
        if (png == null) {
            png = write("yca.png", bytes());
        }
        return png;
    }

    public Path ico() throws IOException {
        if (ico == null) {
            final var bytes = bytes();
            // Windows Vista+ supports a PNG image inside the ICO container. The source icon is 64x64 RGBA.
            final var image = ByteBuffer.allocate(22 + bytes.length).order(ByteOrder.LITTLE_ENDIAN);
            image.putShort((short) 0).putShort((short) 1).putShort((short) 1);
            image.put((byte) 64).put((byte) 64).put((byte) 0).put((byte) 0);
            image.putShort((short) 1)
                    .putShort((short) 32)
                    .putInt(bytes.length)
                    .putInt(22)
                    .put(bytes);
            ico = write("yca.ico", image.array());
        }
        return ico;
    }

    private byte[] bytes() throws IOException {
        try (final var stream = AppIcon.class.getResourceAsStream("/io/yupiik/ia/agent/core/notifications/yca.png")) {
            if (stream == null) {
                throw new IOException("Bundled YCA icon is missing.");
            }
            return stream.readAllBytes();
        }
    }

    private Path write(final String name, final byte[] bytes) throws IOException {
        if (directory == null) {
            directory = Files.createTempDirectory("yca-notifications-icon-");
        }
        final var target = directory.resolve(name);
        try {
            return Files.write(target, bytes);
        } catch (final IOException failure) {
            try {
                Files.deleteIfExists(target);
            } catch (final IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @Override
    public void close() throws IOException {
        if (directory == null) {
            return;
        }
        try {
            if (ico != null) {
                Files.deleteIfExists(ico);
            }
        } finally {
            try {
                if (png != null) {
                    Files.deleteIfExists(png);
                }
            } finally {
                Files.deleteIfExists(directory);
            }
        }
    }
}
