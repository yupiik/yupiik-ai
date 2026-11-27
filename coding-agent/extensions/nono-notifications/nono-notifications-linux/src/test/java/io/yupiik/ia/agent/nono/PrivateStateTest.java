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
package io.yupiik.ia.agent.nono;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.json.JsonMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PrivateStateTest {
    private Path home;

    @BeforeEach
    void temporaryHome() throws Exception {
        // The checkout can have group-writable ancestors, which production correctly rejects.
        // Use only a fresh test-owned directory here, never existing companion state.
        home = TestTempDirs.create(Path.of(System.getProperty("user.home")), ".private-state-test-");
    }

    @AfterEach
    void removeTemporaryHome() throws Exception {
        TestTempDirs.cleanup(home);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "defaultResolutionPermissionsAndIdempotentClose,"
                        + "explicitPathIsManagedButMustBeFreshWithExistingParent,"
                        + "sameJvmConcurrentLaunchRemainsLive,"
                        + "concurrentStartupSerializesCreationAndRecovery,"
                        + "publishFailureCleansPartialSecretsAndReleasesResources",
                "rejectsInsecureParentsAndSymlinkAncestors,"
                        + "defaultDoesNotTakeOverExistingNonPrivateDirectory,"
                        + "startupNamesEachInsecureDefaultDirectoryAndPreservesCause,"
                        + "coordinationFailureDoesNotCreateLaunchOrFollowLink",
                "insecureGeneratedFilesBlockCleanupAndReleaseLiveLock",
                "realProcessLiveLockAndCrashRecovery",
                "unknownTreesAndSymlinksAreNeverDeletedOrFollowed"
            })
    void isolatedHomeScenario(final String scenario) throws Exception {
        final var output = privateFile(home.resolve("scenario.log"), "");
        final var process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin/java").toString(),
                        "-Duser.home=" + home.resolve(".private-state-test-home"),
                        "-cp",
                        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                        Scenario.class.getName(),
                        scenario)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        try {
            process.getOutputStream().close();
            assertTrue(
                    process.waitFor(30L * scenario.split(",").length, TimeUnit.SECONDS),
                    "Scenarios timed out: " + scenario);
            assertEquals(0, process.exitValue(), Files.readString(output));
        } finally {
            final var descendants = process.descendants().toList();
            for (final var descendant : descendants) {
                descendant.destroyForcibly();
            }
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Scenario process did not terminate");
            for (final var descendant : descendants) {
                descendant.onExit().get(10, TimeUnit.SECONDS);
            }
        }
    }

    public static class Scenario {
        private final Path home = Path.of(System.getProperty("user.home"));

        public static void main(final String[] args) throws Exception {
            final var scenario = new Scenario();
            if (!scenario.home.getFileName().toString().startsWith(".private-state-test-")) {
                throw new IllegalStateException("A fresh test-owned home is required");
            }
            // Share JVM startup, not storage. Never mutate user.home, even in this child.
            // Cases that intentionally retain state/resources or exercise process crashes run alone.
            for (final var name : args[0].split(",")) {
                Files.createDirectory(
                        scenario.home,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                System.out.println("Starting scenario: " + name);
                try {
                    Scenario.class.getDeclaredMethod(name).invoke(scenario);
                    System.out.println("Passed scenario: " + name);
                } finally {
                    removeTree(scenario.home);
                }
            }
        }

        private void defaultResolutionPermissionsAndIdempotentClose() throws Exception {
            try (final var mapper = mapper()) {
                final var state = new PrivateState(null, mapper);
                final var directory = state.directory();
                final var base = home.resolve(".yupiik/coding-agent/nono");
                assertEquals(base, directory.getParent());
                assertEquals(
                        "launch-"
                                + UUID.fromString(
                                        directory.getFileName().toString().substring(7)),
                        directory.getFileName().toString());
                state.publish("::1", 1234, 60);
                state.audit(
                        new ApprovalProtocol(mapper).parse(ApprovalProtocolTest.body("request", "session", "/secret")),
                        "denied");
                privateFile(directory.resolve("profile.json"), "{}");
                privateFile(directory.resolve("attach.log"), "log");
                final var audit = Files.readString(directory.resolve("decisions.jsonl"));
                assertFalse(audit.contains("/secret"));
                assertTrue(Files.readString(directory.resolve("backend.json")).contains("@[::1]:1234/approve"));
                for (var path = directory; !path.equals(home); path = path.getParent()) {
                    assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(path));
                }
                try (final var paths = Files.list(directory)) {
                    for (final var path : paths.toList()) {
                        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path));
                    }
                }
                state.close();
                state.close();
                assertFalse(Files.exists(directory));
                assertThrows(IOException.class, () -> state.publish("127.0.0.1", 1, 1));
                assertTrue(Files.isDirectory(base));
            }
        }

        private void explicitPathIsManagedButMustBeFreshWithExistingParent() throws Exception {
            try (final var mapper = mapper()) {
                final var path = home.resolve("explicit");
                try (final var state = new PrivateState(path, mapper)) {
                    assertEquals(path, state.directory());
                    assertThrows(FileAlreadyExistsException.class, () -> new PrivateState(path, mapper));
                    state.publish("127.0.0.1", 1234, 60);
                }
                assertFalse(Files.exists(path));
                assertThrows(IOException.class, () -> new PrivateState(home.resolve("absent/launch"), mapper));
                assertFalse(Files.exists(home.resolve("absent")));
            }
        }

        private void sameJvmConcurrentLaunchRemainsLive() throws Exception {
            try (final var mapper = mapper();
                    final var first = new PrivateState(null, mapper);
                    final var second = new PrivateState(null, mapper)) {
                assertNotEquals(first.directory(), second.directory());
                assertTrue(Files.isDirectory(first.directory()));
                first.publish("127.0.0.1", 1, 1);
            }
        }

        private void concurrentStartupSerializesCreationAndRecovery() throws Exception {
            try (final var mapper = mapper();
                    final var workers = Executors.newFixedThreadPool(6)) {
                final var start = new CountDownLatch(1);
                final var futures = IntStream.range(0, 6)
                        .mapToObj(index -> workers.submit(() -> {
                            assertTrue(start.await(10, TimeUnit.SECONDS));
                            return new PrivateState(null, mapper);
                        }))
                        .toList();
                start.countDown();
                final var states = new ArrayList<PrivateState>();
                try {
                    for (final var future : futures) {
                        states.add(future.get(10, TimeUnit.SECONDS));
                    }
                    assertEquals(
                            6,
                            states.stream()
                                    .map(PrivateState::directory)
                                    .distinct()
                                    .count());
                    for (final var state : states) {
                        assertTrue(Files.isDirectory(state.directory()));
                        state.publish("127.0.0.1", 1, 1);
                    }
                } finally {
                    for (final var state : states) {
                        state.close();
                    }
                }
            }
        }

        private void insecureGeneratedFilesBlockCleanupAndReleaseLiveLock() throws Exception {
            try (final var mapper = mapper()) {
                final var state = new PrivateState(null, mapper);
                state.publish("127.0.0.1", 1, 1);
                final var backend = state.directory().resolve("backend.json");
                Files.setPosixFilePermissions(backend, PosixFilePermissions.fromString("rw-rw----"));
                assertThrows(IOException.class, state::close);
                try (final var next = new PrivateState(null, mapper)) {
                    assertTrue(Files.exists(backend), "Recovery must not remove insecure content");
                    assertTrue(Files.exists(next.directory()));
                }
                Files.setPosixFilePermissions(backend, PosixFilePermissions.fromString("rw-------"));
                try (final var next = new PrivateState(null, mapper)) {
                    assertFalse(Files.exists(state.directory()), "Failed close must still release OS lock");
                    assertTrue(Files.exists(next.directory()));
                }
            }
        }

        private void realProcessLiveLockAndCrashRecovery() throws Exception {
            final var process = new ProcessBuilder(
                            Path.of(System.getProperty("java.home"), "bin/java").toString(),
                            "-Duser.home=" + home,
                            "-cp",
                            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                            CrashLaunch.class.getName())
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            try (final var output = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    final var mapper = mapper()) {
                final var line = assertTimeoutPreemptively(Duration.ofSeconds(20), output::readLine);
                assertNotNull(line, "Child must publish its launch path");
                final var crashed = Path.of(line);
                try (final var live = new PrivateState(null, mapper)) {
                    assertTrue(Files.exists(crashed.resolve("backend.json")), "OS lock must preserve live child");
                    process.destroyForcibly();
                    assertTrue(process.waitFor(10, TimeUnit.SECONDS));
                    try (final var recovered = new PrivateState(null, mapper)) {
                        assertFalse(Files.exists(crashed), "Next startup must recover crashed process state");
                        assertTrue(Files.isDirectory(live.directory()));
                        assertTrue(Files.isDirectory(recovered.directory()));
                    }
                }
            } finally {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            }
        }

        private void unknownTreesAndSymlinksAreNeverDeletedOrFollowed() throws Exception {
            try (final var mapper = mapper()) {
                final var state = new PrivateState(null, mapper);
                final var unknown = Files.createDirectory(state.directory().resolve("unknown"));
                final var sentinel = Files.writeString(unknown.resolve("sentinel"), "keep");
                assertThrows(IOException.class, state::close);
                state.close();
                final var external = privateFile(home.resolve("external"), "untouched");
                final var linkedState = new PrivateState(null, mapper);
                Files.createSymbolicLink(linkedState.directory().resolve("backend.json"), external);
                assertThrows(IOException.class, () -> linkedState.publish("127.0.0.1", 1, 1));
                final var base = state.directory().getParent();
                final var linkedDirectory = base.resolve("launch-" + UUID.randomUUID());
                Files.createSymbolicLink(linkedDirectory, unknown);
                final var unmarked = Files.createDirectory(
                        base.resolve("launch-" + UUID.randomUUID()),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                try (final var next = new PrivateState(null, mapper)) {
                    assertTrue(Files.isDirectory(next.directory()));
                    assertEquals("keep", Files.readString(sentinel));
                    assertEquals("untouched", Files.readString(external));
                    assertTrue(Files.isSymbolicLink(linkedDirectory));
                    assertTrue(Files.isDirectory(unmarked));
                    assertTrue(Files.isSymbolicLink(linkedState.directory().resolve("backend.json")));
                    assertTrue(Files.exists(state.directory().resolve(".nono-managed")));
                }
            }
        }

        private void rejectsInsecureParentsAndSymlinkAncestors() throws Exception {
            try (final var mapper = mapper()) {
                final var writable = Files.createDirectory(home.resolve("writable"));
                Files.setPosixFilePermissions(writable, PosixFilePermissions.fromString("rwxrwx---"));
                assertThrows(
                        IllegalArgumentException.class, () -> new PrivateState(writable.resolve("launch"), mapper));
                final var link = Files.createSymbolicLink(home.resolve("linked"), home);
                assertThrows(IllegalArgumentException.class, () -> new PrivateState(link.resolve("launch"), mapper));
                Files.createSymbolicLink(home.resolve(".yupiik"), home);
                assertThrows(IOException.class, () -> new PrivateState(null, mapper));
                assertFalse(Files.exists(home.resolve("coding-agent")));
            }
        }

        private void defaultDoesNotTakeOverExistingNonPrivateDirectory() throws Exception {
            Files.createDirectory(home.resolve(".yupiik"));
            Files.setPosixFilePermissions(home.resolve(".yupiik"), PosixFilePermissions.fromString("rwxr-xr-x"));
            try (final var mapper = mapper()) {
                assertThrows(IOException.class, () -> new PrivateState(null, mapper));
                assertEquals(
                        PosixFilePermissions.fromString("rwxr-xr-x"),
                        Files.getPosixFilePermissions(home.resolve(".yupiik")));
            }
        }

        private void startupNamesEachInsecureDefaultDirectoryAndPreservesCause() throws Exception {
            final var executable = privateFile(home.resolve("zenity"), "#!/bin/sh\nexit 1\n");
            Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
            try (final var mapper = mapper()) {
                for (final var path : List.of(
                        home.resolve(".yupiik"),
                        home.resolve(".yupiik/coding-agent"),
                        home.resolve(".yupiik/coding-agent/nono"))) {
                    Files.createDirectory(
                            path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxrwxr-x"));
                    final var error = assertThrows(
                            IllegalStateException.class,
                            () -> new ServeCommand(
                                            new ServeCommand.Conf(
                                                    "",
                                                    executable.toString(),
                                                    60,
                                                    "127.0.0.1",
                                                    ServeCommand.Ui.ZENITY,
                                                    "/usr/bin/notify-send"),
                                            mapper)
                                    .run());
                    assertTrue(error.getMessage().contains(path.toString()), error.getMessage());
                    assertTrue(error.getMessage().contains("permissions 700"), error.getMessage());
                    assertTrue(error.getMessage().contains("actual permissions: 775"), error.getMessage());
                    assertInstanceOf(IOException.class, error.getCause());
                    assertTrue(error.getCause().getMessage().contains(path.toString()));
                    assertEquals(
                            PosixFilePermissions.fromString("rwxrwxr-x"),
                            Files.getPosixFilePermissions(path),
                            "Startup must not silently change existing permissions");
                    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
                }
            }
        }

        private void publishFailureCleansPartialSecretsAndReleasesResources() throws Exception {
            try (final var mapper = mapper()) {
                final var state = new PrivateState(null, mapper);
                privateFile(state.directory().resolve("ready.json"), "collision");
                assertThrows(FileAlreadyExistsException.class, () -> state.publish("127.0.0.1", 1, 1));
                assertFalse(Files.exists(state.directory()));
                state.close();
                try (final var next = new PrivateState(null, mapper)) {
                    next.publish("127.0.0.1", 1, 1);
                    assertTrue(Files.exists(next.directory().resolve("backend.json")));
                }
            }
        }

        private void coordinationFailureDoesNotCreateLaunchOrFollowLink() throws Exception {
            final var path = home.resolve("explicit");
            final var sentinel = privateFile(home.resolve("sentinel"), "keep");
            Files.createSymbolicLink(home.resolve(".nono-lifecycle.lock"), sentinel);
            try (final var mapper = mapper()) {
                assertThrows(IOException.class, () -> new PrivateState(path, mapper));
                assertFalse(Files.exists(path, LinkOption.NOFOLLOW_LINKS));
                assertEquals("keep", Files.readString(sentinel));
                Files.delete(home.resolve(".nono-lifecycle.lock"));
                try (final var state = new PrivateState(path, mapper)) {
                    assertEquals(path, state.directory());
                }
            }
        }
    }

    private static JsonMapper mapper() {
        return ApprovalProtocolTest.mapper();
    }

    private static void removeTree(final Path directory) throws IOException {
        try (final var paths = Files.walk(directory)) {
            for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static Path privateFile(final Path path, final String value) throws Exception {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(path, value);
        return path;
    }

    public static class CrashLaunch {
        public static void main(final String[] args) throws Exception {
            try (final var mapper = mapper();
                    final var state = new PrivateState(null, mapper)) {
                state.publish("127.0.0.1", 1234, 60);
                privateFile(state.directory().resolve("profile.json"), "{}");
                privateFile(state.directory().resolve("attach.log"), "log");
                System.out.println(state.directory());
                System.out.flush();
                Thread.sleep(Duration.ofMinutes(2));
            }
        }
    }
}
