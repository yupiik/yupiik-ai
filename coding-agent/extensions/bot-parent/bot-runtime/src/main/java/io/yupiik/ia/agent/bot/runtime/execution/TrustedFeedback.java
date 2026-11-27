/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.ia.agent.bot.runtime.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;

/** Shared validation of the trusted, private feedback channel used by execution backends. */
public final class TrustedFeedback {
    private TrustedFeedback() {}

    /** Returns zero when disabled, otherwise the validated feedback timeout in seconds. */
    public static int timeout(final Path directory) throws IOException {
        final var feedback = directory.resolve("feedback");
        if (Files.notExists(feedback, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        if (!directory.toRealPath().equals(directory.toAbsolutePath().normalize())
                || !Files.isDirectory(feedback, LinkOption.NOFOLLOW_LINKS)
                || !Files.getOwner(feedback, LinkOption.NOFOLLOW_LINKS).equals(Files.getOwner(directory))
                || !Files.getPosixFilePermissions(feedback, LinkOption.NOFOLLOW_LINKS)
                        .equals(PosixFilePermissions.fromString("rwx------"))) {
            throw new IllegalArgumentException("Feedback requires a private owned directory without symlinks");
        }
        final var timeout = directory.resolve(".feedback-timeout");
        if (Files.notExists(timeout, LinkOption.NOFOLLOW_LINKS)) {
            return 600;
        }
        final var permissions = Files.getPosixFilePermissions(timeout, LinkOption.NOFOLLOW_LINKS);
        if (!Files.isRegularFile(timeout, LinkOption.NOFOLLOW_LINKS)
                || !Files.getOwner(timeout, LinkOption.NOFOLLOW_LINKS).equals(Files.getOwner(directory))
                || permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
            throw new IllegalArgumentException("Invalid trusted feedback timeout file");
        }
        final byte[] bytes;
        try (final var input = Files.newInputStream(timeout, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(17);
        }
        final var value = new String(bytes, StandardCharsets.US_ASCII).strip();
        if (bytes.length > 16 || value.isEmpty() || value.length() > 4) {
            throw new IllegalArgumentException("Invalid trusted feedback timeout");
        }
        int seconds = 0;
        for (int i = 0; i < value.length(); i++) {
            final char digit = value.charAt(i);
            if (digit < '0' || digit > '9') {
                throw new IllegalArgumentException("Invalid trusted feedback timeout");
            }
            seconds = seconds * 10 + digit - '0';
        }
        if (seconds < 1 || seconds > 3600) {
            throw new IllegalArgumentException("Invalid trusted feedback timeout");
        }
        return seconds;
    }
}
