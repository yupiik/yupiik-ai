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

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirDeletionStrategy;
import org.junit.jupiter.api.io.TempDirFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

class UnlinkingTempDirDeletionStrategyTest {
    private static final String ROOT = "testing.fixture.root";

    @TempDir
    Path root;

    @ParameterizedTest
    @EnumSource(Scenario.class)
    void cleansUpThroughJupiterWithoutFollowingLinksOrChangingOtherTempDirs(final Scenario scenario) throws Exception {
        final var external = Files.createDirectory(root.resolve("external"));
        final var sentinel = Files.writeString(external.resolve("sentinel"), "preserve me");
        Files.writeString(root.resolve("scenario"), scenario.name());
        final var request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(CleanupFixture.class))
                .configurationParameter(ROOT, root.toString())
                .configurationParameter(
                        TempDir.DEFAULT_DELETION_STRATEGY_PROPERTY_NAME, RecordingDefaultStrategy.class.getName())
                .build();
        final var listener = new SummaryGeneratingListener();
        try (final var session = LauncherFactory.openSession()) {
            session.getLauncher().execute(request, listener);
        }
        final var summary = listener.getSummary();
        assertEquals(
                1, summary.getTestsSucceededCount(), () -> summary.getFailures().toString());
        assertEquals(
                0, summary.getTotalFailureCount(), () -> summary.getFailures().toString());
        assertFalse(Files.exists(root.resolve("custom"), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(root.resolve("ordinary"), LinkOption.NOFOLLOW_LINKS));
        assertEquals("preserve me", Files.readString(sentinel));
        assertTrue(Files.exists(root.resolve("default-ordinary")), "Unrelated @TempDir must use the default strategy");
        assertFalse(Files.exists(root.resolve("default-custom")), "Explicit opt-in must override the default");
    }

    private enum Scenario {
        REGULAR_FILES,
        EXTERNAL_FILE,
        EXTERNAL_DIRECTORY,
        RELATIVE_EXTERNAL_LINK,
        INTERNAL_LINKS,
        DANGLING_LINK,
        CYCLIC_LINKS,
        RESTRICTED_PERMISSIONS
    }

    // Explicitly selected by the nested launcher, not discovered as a standalone test class.
    public static class CleanupFixture {
        @TempDir(factory = FixtureDirectoryFactory.class, deletionStrategy = UnlinkingTempDirDeletionStrategy.class)
        Path custom;

        @TempDir(factory = FixtureDirectoryFactory.class)
        Path ordinary;

        @Test
        void populate() throws Exception {
            Files.writeString(ordinary.resolve("file"), "ordinary");
            final var nested = Files.createDirectory(custom.resolve("nested"));
            final var file = Files.writeString(nested.resolve("file"), "temporary");
            final var external = custom.getParent().resolve("external");
            final var scenario =
                    Scenario.valueOf(Files.readString(custom.getParent().resolve("scenario")));
            switch (scenario) {
                case REGULAR_FILES -> {
                    // Regular files and directories are left to Standard.
                }
                case EXTERNAL_FILE -> Files.createSymbolicLink(custom.resolve("link"), external.resolve("sentinel"));
                case EXTERNAL_DIRECTORY -> Files.createSymbolicLink(custom.resolve("link"), external);
                case RELATIVE_EXTERNAL_LINK ->
                    Files.createSymbolicLink(custom.resolve("link"), Path.of("../external/sentinel"));
                case INTERNAL_LINKS -> {
                    Files.createSymbolicLink(custom.resolve("file-link"), file);
                    Files.createSymbolicLink(custom.resolve("dir-link"), Path.of("nested"));
                }
                case DANGLING_LINK -> Files.createSymbolicLink(custom.resolve("link"), external.resolve("absent"));
                case CYCLIC_LINKS -> {
                    Files.createSymbolicLink(custom.resolve("self"), Path.of("self"));
                    Files.createSymbolicLink(custom.resolve("a"), Path.of("b"));
                    Files.createSymbolicLink(custom.resolve("b"), Path.of("a"));
                }
                case RESTRICTED_PERMISSIONS -> {
                    if (Files.getFileStore(nested).supportsFileAttributeView("posix")) {
                        // The pre-pass cannot unlink this as an unprivileged user. Standard
                        // must still get its chance to reset permissions and retry deletion.
                        Files.createSymbolicLink(nested.resolve("link"), Path.of("file"));
                        Files.setPosixFilePermissions(nested, PosixFilePermissions.fromString("r-x------"));
                        Files.setPosixFilePermissions(custom, PosixFilePermissions.fromString("---------"));
                    }
                }
            }
        }
    }

    public static class FixtureDirectoryFactory implements TempDirFactory {
        @Override
        public Path createTempDirectory(
                final AnnotatedElementContext elementContext, final ExtensionContext extensionContext)
                throws IOException {
            final var root =
                    Path.of(extensionContext.getConfigurationParameter(ROOT).orElseThrow());
            final var field = (Field) elementContext.getAnnotatedElement();
            return Files.createDirectory(root.resolve(field.getName()));
        }
    }

    public static class RecordingDefaultStrategy implements TempDirDeletionStrategy {
        @Override
        public DeletionResult delete(
                final Path tempDir,
                final AnnotatedElementContext elementContext,
                final ExtensionContext extensionContext)
                throws IOException {
            final var result = Standard.INSTANCE.delete(tempDir, elementContext, extensionContext);
            Files.writeString(tempDir.resolveSibling("default-" + tempDir.getFileName()), "deleted");
            return result;
        }
    }
}
