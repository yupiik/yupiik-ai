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
package io.yupiik.ia.agent.github.execution.cache;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheManifestReferencesTest {
    @TempDir
    Path temporary;

    @Test
    void cursorResumesAcrossDirectoriesFilesAndLinksUsingTheSnapshotFormat() throws Exception {
        final var source = temporary.resolve("source");
        Files.createDirectories(source.resolve("directory"));
        Files.writeString(source.resolve("directory/one"), "one");
        Files.writeString(source.resolve("two"), "two");
        Files.createSymbolicLink(source.resolve("link"), Path.of("directory/one"));
        final var manifest = temporary.resolve("manifest");
        final var installed = new ArrayList<String>();
        new CacheSnapshot(new DependencyCacheConfiguration.Limits(100, 1000, 16000, 5, 1), Long.MAX_VALUE)
                .capture(
                        source,
                        new DependencyCacheConfiguration.Entry(
                                "tool", DependencyCacheConfiguration.Kind.GENERIC, "tool", List.of()),
                        manifest,
                        (digest, file, size) -> installed.add(digest));
        final var references = new ArrayList<String>();
        long offset = 0;
        var records = 0;
        while (true) {
            final var next = CacheManifestReferences.next(manifest, offset);
            assertTrue(next.offset() > offset);
            if (next.digest() != null) {
                references.add(next.digest());
            }
            if (next.done()) {
                assertEquals(Files.size(manifest), next.offset());
                break;
            }
            records++;
            offset = next.offset();
        }
        assertEquals(5, records); // Root, directory, two files, link.
        assertEquals(installed, references);
    }

    @Test
    void rejectsMalformedUtf8AndUnsafeRootWithoutScanningTheManifest() throws Exception {
        final var manifest = temporary.resolve("malformed");
        for (final var text : List.of(new byte[] {(byte) 0xff}, new byte[] {'.', '.'})) {
            try (final var out = new DataOutputStream(Files.newOutputStream(manifest))) {
                out.writeInt(0x59434143);
                out.writeInt(1);
                out.writeByte(1);
                out.writeInt(text.length);
                out.write(text);
                out.writeInt(0700);
            }
            assertThrows(IOException.class, () -> CacheManifestReferences.next(manifest, 0));
        }
    }

    @Test
    void unknownVersionsAndOversizedFieldsFailClosed() throws Exception {
        final var manifest = temporary.resolve("manifest");
        try (final var out = new DataOutputStream(Files.newOutputStream(manifest))) {
            out.writeInt(0x59434143);
            out.writeInt(2);
        }
        assertThrows(IOException.class, () -> CacheManifestReferences.next(manifest, 0));
        try (final var out = new DataOutputStream(Files.newOutputStream(manifest))) {
            out.writeInt(0x59434143);
            out.writeInt(1);
            out.writeByte(1);
            out.writeInt(Integer.MAX_VALUE);
        }
        assertThrows(IOException.class, () -> CacheManifestReferences.next(manifest, 0));
        assertThrows(IOException.class, () -> CacheManifestReferences.next(manifest, -1));
    }
}
