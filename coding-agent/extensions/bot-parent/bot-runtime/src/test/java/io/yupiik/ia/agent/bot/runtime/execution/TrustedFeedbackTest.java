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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TrustedFeedbackTest {
    @TempDir
    Path directory;

    @Test
    void disabledDefaultAndExplicitTimeout() throws IOException {
        assertEquals(0, TrustedFeedback.timeout(directory));
        enable();
        assertEquals(600, TrustedFeedback.timeout(directory));
        writeTimeout(" 123\n");
        assertEquals(123, TrustedFeedback.timeout(directory));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "3601", "-1", "1.5", "abc", "99999999999999999"})
    void rejectsInvalidTimeout(final String value) throws IOException {
        enable();
        writeTimeout(value);
        assertThrows(IllegalArgumentException.class, () -> TrustedFeedback.timeout(directory));
    }

    @Test
    void rejectsWritableTimeoutAndSymlinks() throws IOException {
        enable();
        final var timeout = directory.resolve(".feedback-timeout");
        Files.writeString(timeout, "20");
        Files.setPosixFilePermissions(timeout, PosixFilePermissions.fromString("rw-rw----"));
        assertThrows(IllegalArgumentException.class, () -> TrustedFeedback.timeout(directory));
        Files.delete(timeout);
        final var target = Files.writeString(directory.resolve("target"), "20");
        Files.createSymbolicLink(timeout, target);
        assertThrows(IllegalArgumentException.class, () -> TrustedFeedback.timeout(directory));
    }

    @Test
    void rejectsPublicOrLinkedFeedbackDirectories() throws IOException {
        enable();
        final var feedback = directory.resolve("feedback");
        Files.setPosixFilePermissions(feedback, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThrows(IllegalArgumentException.class, () -> TrustedFeedback.timeout(directory));
        Files.delete(feedback);
        Files.createSymbolicLink(feedback, Files.createDirectory(directory.resolve("target")));
        assertThrows(IllegalArgumentException.class, () -> TrustedFeedback.timeout(directory));
    }

    private void writeTimeout(final String value) throws IOException {
        final var timeout = Files.writeString(directory.resolve(".feedback-timeout"), value);
        Files.setPosixFilePermissions(timeout, PosixFilePermissions.fromString("rw-------"));
    }

    private void enable() throws IOException {
        Files.createDirectory(
                directory.resolve("feedback"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }
}
