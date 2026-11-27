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
package io.yupiik.ia.agent.core.tools.builtin;

import io.yupiik.ia.agent.core.tools.FileMutation;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shared file-tool implementation, scoped to one invocation, never a filesystem baseline/cache. */
final class FileMutationCapture {
    private static final int MAX_TEXT_LENGTH = 256 * 1024;
    private int remaining = 1024 * 1024;
    private final List<FileMutation> completed = new ArrayList<>();
    private final Set<Path> touched = new HashSet<>();
    private final Set<Object> touchedKeys = new HashSet<>();
    private boolean unknownOverwrite;

    Before before(final Path path, final byte[] bytes) {
        return new Before(key(path), text(bytes));
    }

    void changed(final Path path, final FileMutation.Kind kind, final Before before, final String after) {
        // Phase-one baselines become stale after another operation on the same file/hard link.
        completed(path, kind, knownBefore(path, before), after);
        if (before != null && before.key() != null) {
            touchedKeys.add(before.key());
        }
    }

    boolean unchanged(final Path path, final Before before, final String after) {
        final var oldText = knownBefore(path, before);
        return oldText != null && oldText.equals(after);
    }

    private String knownBefore(final Path path, final Before before) {
        return !unknownOverwrite
                        && before != null
                        && !touched.contains(path.toAbsolutePath().normalize())
                        && (before.key() == null ? touched.isEmpty() : !touchedKeys.contains(before.key()))
                ? before.text()
                : null;
    }

    String text(final String text) {
        if (text == null
                || text.length() > Math.min(MAX_TEXT_LENGTH, remaining)
                || text.chars().anyMatch(c -> Character.isISOControl(c) && c != '\r' && c != '\n' && c != '\t')) {
            return null;
        }
        remaining -= text.length();
        return text;
    }

    String text(final byte[] bytes) {
        // Bound temporary decoding too. Never accept replacement decoding of binary data.
        if (bytes.length > Math.min(MAX_TEXT_LENGTH, remaining)) {
            return null;
        }
        try {
            return text(StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes))
                    .toString());
        } catch (final CharacterCodingException invalidUtf8) {
            return null;
        }
    }

    void completed(final Path path, final FileMutation.Kind kind, final String before, final String after) {
        // Keep the actual authorized spelling: normalizing an absolute symlink/../path can name a different file.
        completed.add(new FileMutation(
                path.toAbsolutePath(),
                kind,
                after != null && (kind == FileMutation.Kind.CREATE || before != null)
                        ? new FileMutation.Content(before, after)
                        : null));
        touched.add(path.toAbsolutePath().normalize());
        if (kind == FileMutation.Kind.UPDATE && before == null) {
            // An unread overwrite could have changed a hard-link alias of any phase-one baseline.
            unknownOverwrite = true;
        }
    }

    private Object key(final Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class).fileKey();
        } catch (final IOException | SecurityException unavailable) {
            return null;
        }
    }

    List<FileMutation> mutations() {
        return List.copyOf(completed);
    }

    record Before(Object key, String text) {}
}
