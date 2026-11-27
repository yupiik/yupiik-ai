/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.yupiik.ia.agent.core.tools.builtin;

import io.yupiik.ia.agent.core.tools.ToolContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Checks actual file-tool operations and filters denied recursive discovery entries. */
final class SandboxFiles {
    private SandboxFiles() {}

    static void requireRead(final ToolContext context, final Path path) {
        requireNormalized(context, path);
        context.sandbox().requireRead(path);
    }

    static void requireWrite(final ToolContext context, final Path path) {
        requireNormalized(context, path);
        context.sandbox().requireWrite(path);
    }

    static void requireNormalized(final ToolContext context, final Path path) {
        // An absolute symlink/../target can name a different file from its lexical normalization.
        // Do not let a policy checking normalized paths authorize an operation on the original spelling.
        if (context.sandbox().enabled() && !path.equals(path.normalize())) {
            throw new SecurityException(
                    "Sandbox restriction: use an absolute normalized path without '.' or '..': " + path);
        }
    }

    static void createParents(final ToolContext context, final Path path) throws IOException {
        for (var parent = path.getParent();
                parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS);
                parent = parent.getParent()) {
            requireWrite(context, parent);
        }
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
    }

    static boolean readable(final ToolContext context, final Path path) {
        try {
            requireRead(context, path);
            return true;
        } catch (final SecurityException denied) {
            return false;
        }
    }
}
