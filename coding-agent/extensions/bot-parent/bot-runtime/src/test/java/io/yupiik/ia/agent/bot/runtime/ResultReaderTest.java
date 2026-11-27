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
package io.yupiik.ia.agent.bot.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResultReaderTest {
    @TempDir
    Path home;

    @Test
    void readsAtTheExactByteLimitAndRejectsLargerResults() throws IOException {
        final var file = Files.writeString(home.resolve("result.json"), "{\"ok\":true}");
        assertEquals("{\"ok\":true}", ResultReader.read(file, 11));
        assertThrows(IOException.class, () -> ResultReader.read(file, 10));
        assertThrows(IllegalArgumentException.class, () -> ResultReader.read(file, 0));
        assertThrows(IllegalArgumentException.class, () -> ResultReader.read(file, Integer.MAX_VALUE));
    }

    @Test
    void rejectsDirectoriesMissingFilesAndMalformedUtf8() throws IOException {
        assertThrows(IOException.class, () -> ResultReader.read(home, 100));
        assertThrows(IOException.class, () -> ResultReader.read(home.resolve("missing"), 100));
        final var invalid = Files.write(home.resolve("invalid"), new byte[] {(byte) 0xc3, 0x28});
        assertThrows(IOException.class, () -> ResultReader.read(invalid, 100));
    }

    @Test
    void rejectsSymbolicFilesAndAncestors() throws IOException {
        final var directory = Files.createDirectory(home.resolve("real"));
        final var file = Files.writeString(directory.resolve("result.json"), "{}");
        final var link = Files.createSymbolicLink(home.resolve("file-link"), file);
        final var ancestor = Files.createSymbolicLink(home.resolve("directory-link"), directory);
        assertThrows(IOException.class, () -> ResultReader.read(link, 100));
        assertThrows(IOException.class, () -> ResultReader.read(ancestor.resolve("result.json"), 100));
    }
}
