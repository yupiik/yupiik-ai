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
package io.yupiik.ia.agent.core.configuration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@FusionSupport(modules = ConfigurationTestModule.class)
class SandboxConfigurationInitializerTest {
    @Fusion
    CodingAgentConfiguration configuration;

    @TempDir
    Path temp;

    @BeforeEach
    void privateParentDirectory() throws IOException {
        if (temp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private Path privateFile(final Path path, final String content) throws IOException {
        Files.createFile(path, privateAttributes(path, "rw-------"));
        return Files.writeString(path, content);
    }

    private FileAttribute<?>[] privateAttributes(final Path path, final String permissions) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix")
                ? new FileAttribute<?>[] {
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions))
                }
                : new FileAttribute<?>[0];
    }

    @Test
    void previewDoesNotCreateAnything() throws IOException {
        try (final var mapper = new JsonMapperImpl(List.of(), ignored -> Optional.empty())) {
            final var path = temp.resolve("not-created/configuration.json");
            final var result = new SandboxConfigurationInitializer(mapper, configuration).initialize(path, false);
            assertTrue(result.changed());
            assertNull(result.backup());
            assertFalse(Files.exists(path.getParent()));
            assertTrue(result.defaultsJson().contains("${projectdir}"));
            assertTrue(result.defaultsJson().contains("repo.maven.apache.org:443"));
        }
    }

    @Test
    void applyCreatesPrivateConfigurationAndIsIdempotent() throws IOException {
        try (final var mapper = new JsonMapperImpl(List.of(), ignored -> Optional.empty())) {
            final var initializer = new SandboxConfigurationInitializer(mapper, configuration);
            final var path = temp.resolve("new/storage/configuration.json");
            final var result = initializer.initialize(path, true);
            assertTrue(result.changed());
            assertNull(result.backup());
            final var bytes = Files.readAllBytes(path);
            final var defaults = configuration.sandbox();
            final var root = (Map<?, ?>) mapper.fromString(Object.class, Files.readString(path));
            final var jsonSandbox = ((Map<?, ?>) root.get("yca")).get("sandbox");
            final var expected = Map.of(
                    "enabled", defaults.enabled(),
                    "helper", Map.of("executable", defaults.helper().executable()),
                    "macOs", Map.of("experimental", defaults.macOs().experimental()),
                    "environment",
                            Map.of(
                                    "inherit",
                                    defaults.environment().inherit(),
                                    "set",
                                    defaults.environment().set()),
                    "unix", Map.of("socketBridges", List.of()),
                    "filesystem",
                            Map.of(
                                    "allowRead",
                                    defaults.filesystem().allowRead(),
                                    "allowWrite",
                                    defaults.filesystem().allowWrite(),
                                    "denyRead",
                                    defaults.filesystem().denyRead(),
                                    "denyWrite",
                                    defaults.filesystem().denyWrite()),
                    "network", Map.of("allowedDomains", defaults.network().allowedDomains()),
                    "trustedIntegrations", defaults.trustedIntegrations());
            assertEquals(expected, jsonSandbox);
            assertFalse(initializer.initialize(path, true).changed());
            assertArrayEquals(bytes, Files.readAllBytes(path));
            try (final var entries = Files.list(path.getParent())) {
                assertEquals(List.of(path), entries.toList());
            }
            if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path));
                assertEquals(
                        PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(path.getParent()));
            }
        }
    }

    @Test
    void preservesUnknownJsonAndBacksUpExactOriginalBytes() throws IOException {
        final var original = "{\n"
                + " \"unknown\":{\"nested\":[1,true,\"hello\"]},\"yca\":{\"provider\":{\"model\":\"kept\"},\"permissions\":{\"mode\":\"auto_edit\",\"allow\":[\"read\"],\"deny\":[\"bash:rm"
                + " *\"]},\"sandbox\":{\"enabled\":true,\"futureSetting\":\"keep-me\",\"helper\":{\"futureHelper\":\"kept\"},\"macOs\":{\"futureMacOs\":true}}}}\n";
        final var path = privateFile(temp.resolve("configuration.json"), original);
        try (final var mapper = new JsonMapperImpl(List.of(), ignored -> Optional.empty())) {
            final var initializer = new SandboxConfigurationInitializer(mapper, configuration);
            assertTrue(initializer.initialize(path, false).changed());
            assertEquals(original, Files.readString(path));
            final var result = initializer.initialize(path, true);
            assertEquals(original, Files.readString(result.backup()));
            final var before = (Map<?, ?>) mapper.fromString(Object.class, original);
            final var after = (Map<?, ?>) mapper.fromString(Object.class, Files.readString(path));
            assertEquals(before.get("unknown"), after.get("unknown"));
            final var yca = (Map<?, ?>) after.get("yca");
            assertEquals(((Map<?, ?>) before.get("yca")).get("provider"), yca.get("provider"));
            assertEquals(((Map<?, ?>) before.get("yca")).get("permissions"), yca.get("permissions"));
            assertEquals("keep-me", ((Map<?, ?>) yca.get("sandbox")).get("futureSetting"));
            final var sandbox = (Map<?, ?>) yca.get("sandbox");
            assertEquals(Map.of("executable", "", "futureHelper", "kept"), sandbox.get("helper"));
            assertEquals(Map.of("experimental", false, "futureMacOs", true), sandbox.get("macOs"));
            assertFalse(initializer.initialize(path, true).changed());
            if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                assertEquals(
                        PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(result.backup()));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"yca\":{\"sandbox\":{\"enabled\":false}}}",
                "{\"yca\":{\"sandbox\":{\"helper\":{\"executable\":\"/other/helper\"}}}}",
                "{\"yca\":{\"sandbox\":{\"macOs\":{\"experimental\":true}}}}",
                "{\"yca\":{\"sandbox\":{\"helper\":\"/other/helper\"}}}",
                "{\"yca\":{\"sandbox\":{\"macOs\":true}}}",
                "{\"yca\":{\"sandbox\":{\"filesystem\":{\"allowWrite\":[\"/\"]}}}}",
                "{\"yca\":{\"sandbox\":{\"network\":{\"allowedDomains\":[\"other:443\"]}}}}",
                "{\"yca.sandbox.enabled\":false}",
                "{\"yca-sandbox-enabled\":false}",
                "{\"sandbox\":{\"enabled\":false}}",
                "{\"yca\":{\"sandbox\":{\"filesystem.allowRead\":[\"/\"]}}}",
                "{\"yca\":null}",
                "{\"yca\":{\"sandbox\":null}}",
                "[]",
                "not json"
            })
    void refusesConflictsOrInvalidJsonWithoutChangingTheFile(final String original) throws IOException {
        final var path = privateFile(temp.resolve("configuration.json"), original);
        try (final var mapper = new JsonMapperImpl(List.of(), ignored -> Optional.empty())) {
            final var initializer = new SandboxConfigurationInitializer(mapper, configuration);
            assertThrows(RuntimeException.class, () -> initializer.initialize(path, false));
            assertThrows(RuntimeException.class, () -> initializer.initialize(path, true));
            assertEquals(original, Files.readString(path));
            try (final var entries = Files.list(temp)) {
                assertEquals(List.of(path), entries.toList());
            }
        }
    }

    @Test
    void refusesSymlinkFileAndSymlinkAncestor() throws IOException {
        assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("unix"));
        final var original = privateFile(temp.resolve("original.json"), "{}");
        final var link = Files.createSymbolicLink(temp.resolve("link.json"), original);
        final var directory = Files.createDirectory(temp.resolve("directory"), privateAttributes(temp, "rwx------"));
        final var directoryLink = Files.createSymbolicLink(temp.resolve("linked-directory"), directory);
        try (final var mapper = new JsonMapperImpl(List.of(), ignored -> Optional.empty())) {
            final var initializer = new SandboxConfigurationInitializer(mapper, configuration);
            assertThrows(IOException.class, () -> initializer.initialize(link, true));
            assertThrows(
                    IOException.class, () -> initializer.initialize(directoryLink.resolve("configuration.json"), true));
            assertEquals("{}", Files.readString(original));
            assertFalse(Files.exists(directory.resolve("configuration.json")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rw-rw----", "rw-----w-", "rw-rw-rw-"})
    void refusesNonRegularFilesAndUnsafePermissions(final String permissions) throws IOException {
        assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("posix"));
        try (final var mapper = new JsonMapperImpl(List.of(), ignored -> Optional.empty())) {
            final var initializer = new SandboxConfigurationInitializer(mapper, configuration);
            final var directory =
                    Files.createDirectory(temp.resolve("directory"), privateAttributes(temp, "rwx------"));
            assertThrows(IOException.class, () -> initializer.initialize(directory, true));
            final var path = privateFile(temp.resolve("configuration.json"), "{}");
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
            assertThrows(IOException.class, () -> initializer.initialize(path, true));
            assertEquals("{}", Files.readString(path));
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));
            assertThrows(
                    IOException.class, () -> initializer.initialize(directory.resolve("configuration.json"), true));
        }
    }

    @Test
    void refusesHardlinksAndDoesNotDeleteAnotherInvocationsLock() throws IOException {
        assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("unix"));
        final var path = privateFile(temp.resolve("configuration.json"), "{}");
        final var hardlink = Files.createLink(temp.resolve("hardlink.json"), path);
        try (final var mapper = new JsonMapperImpl(List.of(), ignored -> Optional.empty())) {
            final var initializer = new SandboxConfigurationInitializer(mapper, configuration);
            assertThrows(IOException.class, () -> initializer.initialize(path, true));
            Files.delete(hardlink);
            final var lock = privateFile(temp.resolve("configuration.json.sandbox-init.lock"), "owned-by-another-init");
            assertThrows(IOException.class, () -> initializer.initialize(path, true));
            assertEquals("owned-by-another-init", Files.readString(lock));
            assertEquals("{}", Files.readString(path));
        }
    }
}
