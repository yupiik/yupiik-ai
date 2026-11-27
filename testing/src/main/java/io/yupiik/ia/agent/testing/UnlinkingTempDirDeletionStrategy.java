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
package io.yupiik.ia.agent.testing;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirDeletionStrategy;

/**
 * Opt-in cleanup for temporary directories containing intentional symbolic links.
 * Select with {@code @TempDir(deletionStrategy = UnlinkingTempDirDeletionStrategy.class)}.
 * Links are unlinked without following their targets, then JUnit performs normal cleanup.
 * This does not suppress cleanup failures or change the global deletion strategy.
 */
public final class UnlinkingTempDirDeletionStrategy implements TempDirDeletionStrategy {
    public UnlinkingTempDirDeletionStrategy() {}

    @Override
    public DeletionResult delete(
            final Path tempDir, final AnnotatedElementContext elementContext, final ExtensionContext extensionContext)
            throws IOException {
        try {
            // No FOLLOW_LINKS: directory links, dangling links and cycles are leaf entries.
            Files.walkFileTree(tempDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) {
                    if (attributes.isSymbolicLink()) {
                        try {
                            Files.deleteIfExists(file);
                        } catch (final IOException ignored) {
                            // Leave permission recovery and failure reporting to Standard.
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(final Path file, final IOException exception) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException ignored) {
            // Even a failed traversal must not prevent Standard's permission-reset retries.
        }
        return Standard.INSTANCE.delete(tempDir, elementContext, extensionContext);
    }
}
