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
package io.yupiik.ia.agent.core.tools;

import java.nio.file.Path;
import java.util.Objects;

/** A completed filesystem mutation, for authorized UI display only, never model context/history. */
public record FileMutation(Path path, Kind kind, Content content) {
    public FileMutation {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("mutation path must be absolute");
        }
        if (content != null && (kind == Kind.CREATE ? content.oldText() != null : content.oldText() == null)) {
            throw new IllegalArgumentException("null oldText is only valid for a confirmed creation");
        }
        if (kind == Kind.DELETE && content != null && !content.newText().isEmpty()) {
            throw new IllegalArgumentException("a deletion has empty newText");
        }
    }

    public enum Kind {
        CREATE,
        UPDATE,
        DELETE
    }

    /** Complete, exact UTF-8 text, including BOM/newlines. Null mutation content means unavailable. */
    public record Content(String oldText, String newText) {
        public Content {
            Objects.requireNonNull(newText, "newText");
        }
    }
}
