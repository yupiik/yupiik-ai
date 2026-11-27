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

import io.yupiik.fusion.json.internal.JsonMapperImpl;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Test fixture only; not a sandbox launcher. Requires stock nono 0.81.0 and util-linux script. */
@EnabledIfSystemProperty(named = "nono.acceptance.artifact", matches = ".+")
class StockNonoAcceptanceTest {
    @Test
    void packagedCompanionWithStockNono() throws Exception {
        try (final var fixture = new Fixture();
                final var mapper = new JsonMapperImpl(List.of(), key -> Optional.empty())) {
            assertEquals("nono 0.81.0", fixture.command("nono", "--version").strip());
            final var realUi = Boolean.getBoolean("nono.acceptance.realUi");
            final var host = Boolean.getBoolean("nono.acceptance.ipv6") ? "::1" : "127.0.0.1";
            final var ui = System.getProperty("nono.acceptance.ui", "zenity");
            assertTrue(List.of("zenity", "notify-send").contains(ui), "Unsupported acceptance UI");
            final var fake = fixture.root.resolve(ui);
            Files.writeString(
                    fake, "notify-send".equals(ui) ? "#!/bin/sh\nprintf 'allow\\n'\n" : "#!/bin/sh\nexit 0\n");
            Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwx------"));
            final var launcher = new ArrayList<String>();
            if (!Boolean.getBoolean("nono.acceptance.native")) {
                launcher.add(fixture.java.toString());
                launcher.add("-jar");
            }
            launcher.add(Path.of(System.getProperty("nono.acceptance.artifact"))
                    .toRealPath()
                    .toString());
            launcher.addAll(List.of(
                    "nono",
                    "serve",
                    "--directory",
                    fixture.privateDir.toString(),
                    "--host",
                    host,
                    "--timeoutSeconds",
                    "10",
                    "--ui",
                    ui.toUpperCase(Locale.ROOT).replace('-', '_'),
                    "notify-send".equals(ui) ? "--notifySend" : "--zenity",
                    realUi ? "/usr/bin/" + ui : fake.toString()));
            fixture.helper = fixture.start(launcher);
            final var ready = fixture.privateDir.resolve("ready.json");
            final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.exists(ready) && fixture.helper.process.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(Files.exists(ready), () -> fixture.helper.output());
            final var backend = object(
                    mapper.fromString(Object.class, Files.readString(fixture.privateDir.resolve("backend.json"))));
            final var security = object(backend.get("security"));
            final var desktop = object(object(security.get("approval_backends")).get("desktop"));
            final var credential = URI.create((String) desktop.get("url")).getRawUserInfo();
            assertNotNull(credential);
            final var port = ((Number) object(mapper.fromString(Object.class, Files.readString(ready)))
                            .get("port"))
                    .intValue();
            final var authority = host.contains(":") ? "[" + host + "]" : host;
            try (final var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build()) {
                assertEquals(
                        401,
                        client.send(
                                        HttpRequest.newBuilder(
                                                        URI.create("http://" + authority + ":" + port + "/approve"))
                                                .timeout(Duration.ofSeconds(3))
                                                .header("Content-Type", "application/json")
                                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                                .build(),
                                        HttpResponse.BodyHandlers.discarding())
                                .statusCode());
            }
            final var mergedSecurity = new HashMap<>(security);
            mergedSecurity.put("capability_elevation", true);
            final var profile = fixture.privateDir.resolve("profile.json");
            Files.writeString(
                    profile,
                    mapper.toString(Map.of(
                            "extends", "default",
                            "groups", Map.of("exclude", List.of("system_write_linux")),
                            "security", mergedSecurity,
                            "filesystem",
                                    Map.of(
                                            "deny",
                                            List.of(fixture.privateDir.toString(), fake.toString()),
                                            "read",
                                            List.of(
                                                    fixture.java
                                                            .getParent()
                                                            .getParent()
                                                            .toString(),
                                                    fixture.work.toString())),
                            "network", Map.of("block", true),
                            "workdir", Map.of("access", "read"),
                            "environment", Map.of("allow_vars", List.of("PATH", "TERM", "HOME")))));
            Files.setPosixFilePermissions(profile, PosixFilePermissions.fromString("rw-------"));
            fixture.command("nono", "profile", "validate", profile.toString());
            final var target = fixture.root.resolve("outside/target");
            Files.writeString(target, "ELEVATED_CONTENT\n");
            final var binaryName = Child.class.getName();
            final var classFile = binaryName.replace('.', '/') + ".class";
            final var destination = fixture.work.resolve(classFile);
            Files.createDirectories(destination.getParent());
            try (final var bytes =
                    StockNonoAcceptanceTest.class.getClassLoader().getResourceAsStream(classFile)) {
                assertNotNull(bytes);
                Files.copy(bytes, destination);
            }
            fixture.launched = true; // Also recover by unique name if run succeeds but discovery fails.
            fixture.command(
                    "nono",
                    "run",
                    "--silent",
                    "--no-diagnostics",
                    "--no-rollback-prompt",
                    "--profile",
                    profile.toString(),
                    "--allow-cwd",
                    "--detached",
                    "--name",
                    fixture.name,
                    "--",
                    fixture.java.toString(),
                    "-cp",
                    fixture.work.toString(),
                    binaryName,
                    target.toString(),
                    fixture.privateDir.resolve("backend.json").toString());
            fixture.session = fixture.findSession(mapper);
            assertNotNull(fixture.session, "Detached session was not found by its unique name");
            final var attachLog = fixture.root.resolve("attach.log");
            fixture.attach = fixture.start(List.of(
                    "script",
                    "-q",
                    "-e",
                    "-c",
                    "nono attach " + quote(fixture.session) + " 2>" + quote(attachLog.toString()),
                    "/dev/null"));
            fixture.attach.until("APP_READY");
            if (realUi) {
                System.out.println("Click Allow in the Nono approval dialog (10 second budget).");
            }
            fixture.attach.send("go\n");
            fixture.attach.until("CHILD_STDERR");
            fixture.attach.send("done\n");
            fixture.attach.await(); // script can mirror the child exit code; inspect is authoritative.
            final var output = fixture.attach.output();
            assertTrue(output.contains("SECRET_DENIED"), output);
            assertTrue(output.contains("ELEVATED_CONTENT"), output);
            assertTrue(output.contains("\033[?1049h"), output);
            assertTrue(output.contains("\033[?1049l"), output);
            final var start = output.indexOf("PRE_APPROVAL");
            final var end = output.indexOf("CHILD_STDERR", start);
            assertTrue(start >= 0 && end > start, output);
            assertFalse(output.substring(start, end).contains("\033["), output);
            final var inspectDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            Map<String, Object> inspected;
            do {
                inspected = object(
                        mapper.fromString(Object.class, fixture.command("nono", "inspect", "--json", fixture.session)));
                if ("exited".equals(inspected.get("status"))) {
                    break;
                }
                Thread.sleep(50);
            } while (System.nanoTime() < inspectDeadline);
            assertEquals("exited", inspected.get("status"));
            assertEquals(7, ((Number) inspected.get("exit_code")).intValue(), output);
            try (final var files = Files.walk(fixture.root.resolve("state"))) {
                for (final var file : files.filter(Files::isRegularFile).toList()) {
                    assertFalse(
                            new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains(credential),
                            () -> "Credential leaked into " + file);
                }
            }
            final var audit = Files.readString(fixture.privateDir.resolve("decisions.jsonl"));
            assertTrue(audit.contains("granted"));
            assertFalse(audit.contains(credential));
            assertFalse(audit.contains(target.toString()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(final Object value) {
        return (Map<String, Object>) value;
    }

    private static String quote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static final class Fixture implements AutoCloseable {
        private final Path root;
        private final Path work;
        private final Path privateDir;
        private final Path java =
                Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
        private final String name = "companion-acceptance-" + UUID.randomUUID();
        private final ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
        private ManagedProcess helper;
        private ManagedProcess attach;
        private String session;
        private boolean launched;

        private Fixture() throws IOException {
            // /tmp is intentionally rejected by the production ancestor policy.
            root = TestTempDirs.create(Path.of(System.getProperty("user.home")), ".nono-acceptance-");
            work = root.resolve("work");
            privateDir = root.resolve("private");
            try {
                for (final var directory : List.of("config", "state", "data", "work", "outside")) {
                    Files.createDirectory(
                            root.resolve(directory),
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                }
            } catch (final IOException e) {
                try {
                    TestTempDirs.cleanup(root);
                } catch (final IOException cleanupFailure) { // keep the creation failure as the cause
                    e.addSuppressed(cleanupFailure);
                }
                throw e;
            }
        }

        private ManagedProcess start(final List<String> command) throws IOException {
            final var builder =
                    new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true);
            final var env = builder.environment();
            env.put("XDG_CONFIG_HOME", root.resolve("config").toString());
            env.put("XDG_STATE_HOME", root.resolve("state").toString());
            env.put("XDG_DATA_HOME", root.resolve("data").toString());
            env.put("NONO_NO_SAVE_PROMPT", "1");
            return new ManagedProcess(builder.start(), io);
        }

        private String command(final String... command) throws Exception {
            try (final var process = start(List.of(command))) {
                if (process.await() != 0) {
                    throw new IOException("Command failed: " + process.output());
                }
                return process.output();
            }
        }

        private String findSession(final JsonMapperImpl mapper) throws Exception {
            final var records = (List<?>) mapper.fromString(Object.class, command("nono", "ps", "--json"));
            return records.stream()
                    .map(StockNonoAcceptanceTest::object)
                    .filter(record -> name.equals(record.get("name")))
                    .map(record -> (String) record.get("session_id"))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public void close() throws IOException {
            // Each cleanup still runs when an earlier one fails; try-with-resources preserves the test failure.
            Exception failure = null;
            try {
                if (launched) {
                    if (session == null) {
                        try (final var mapper = new JsonMapperImpl(List.of(), key -> Optional.empty())) {
                            session = findSession(mapper);
                        }
                    }
                    if (session != null) {
                        try (final var stop = start(List.of("nono", "stop", "--timeout", "2", "--force", session))) {
                            stop.await();
                        }
                    }
                }
            } catch (final Exception e) {
                failure = e;
            }
            try {
                if (attach != null) {
                    attach.close();
                }
            } catch (final Exception e) {
                failure = combine(failure, e);
            }
            try {
                if (helper != null) {
                    helper.close();
                    if (Files.exists(privateDir)) {
                        throw new IOException("Private launch files survived graceful helper shutdown");
                    }
                }
            } catch (final Exception e) {
                failure = combine(failure, e);
            } finally {
                io.shutdownNow();
                try {
                    if (!io.awaitTermination(5, TimeUnit.SECONDS)) {
                        failure = combine(failure, new IOException("Subprocess I/O threads did not terminate"));
                    }
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failure = combine(failure, interrupted);
                }
                try {
                    TestTempDirs.cleanup(root);
                } catch (final Exception e) {
                    failure = combine(failure, e);
                }
            }
            if (failure != null) {
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("Failed to clean up stock nono fixture", failure);
            }
        }

        private static Exception combine(final Exception first, final Exception next) {
            if (first == null) {
                return next;
            }
            first.addSuppressed(next);
            return first;
        }
    }

    private static final class ManagedProcess implements AutoCloseable {
        private final Process process;
        private final ExecutorService io;
        private final StringBuffer text = new StringBuffer();
        private final Future<?> reader;

        private ManagedProcess(final Process process, final ExecutorService io) {
            this.process = process;
            this.io = io;
            reader = io.submit(() -> {
                try (final var input = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                    final var buffer = new char[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (text.length() + count > 1024 * 1024) {
                            throw new IOException("Subprocess output exceeded 1 MiB");
                        }
                        text.append(buffer, 0, count);
                    }
                }
                return null;
            });
        }

        private String output() {
            return text.toString();
        }

        private void until(final String marker) throws Exception {
            final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!output().contains(marker) && System.nanoTime() < deadline) {
                if (reader.isDone()) {
                    reader.get(1, TimeUnit.SECONDS);
                    break;
                }
                Thread.sleep(20);
            }
            assertTrue(output().contains(marker), () -> "Missing " + marker + ": " + output());
        }

        private void send(final String input) throws Exception {
            io.submit(() -> {
                        process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
                        process.getOutputStream().flush();
                        return null;
                    })
                    .get(3, TimeUnit.SECONDS);
        }

        private int await() throws Exception {
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                throw new IOException("Subprocess timed out: " + output());
            }
            reader.get(3, TimeUnit.SECONDS);
            return process.exitValue();
        }

        @Override
        public void close() throws IOException {
            try {
                if (process.isAlive()) {
                    process.destroy();
                    if (!process.waitFor(10, TimeUnit.SECONDS)) {
                        process.descendants().forEach(ProcessHandle::destroyForcibly);
                        process.destroyForcibly();
                        if (!process.waitFor(5, TimeUnit.SECONDS)) {
                            throw new IOException("Subprocess survived forced termination");
                        }
                    }
                }
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while stopping subprocess", interrupted);
            } finally {
                try (final var _ = process.getOutputStream();
                        final var _ = process.getInputStream();
                        final var _ = process.getErrorStream()) {
                    reader.cancel(true);
                }
            }
        }
    }

    public static final class Child {
        public static void main(final String[] args) throws Exception {
            // System.in is owned by the process, not by this reader.
            final var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            System.out.print("\033[?1049hAPP_READY\n");
            System.out.flush();
            if (!"go".equals(input.readLine())) {
                throw new IOException("Expected go input");
            }
            try {
                System.out.println(Files.readString(Path.of(args[1])));
            } catch (final IOException denied) {
                System.out.println("SECRET_DENIED");
            }
            System.out.println("PRE_APPROVAL");
            System.out.flush();
            try {
                System.out.println(Files.readString(Path.of(args[0])));
            } catch (final IOException denied) {
                System.out.println("ACCESS_DENIED");
            }
            System.out.flush();
            System.err.println("CHILD_STDERR");
            System.err.flush();
            if (!"done".equals(input.readLine())) {
                throw new IOException("Expected done input");
            }
            System.out.print("\033[?1049l");
            System.out.flush();
            System.exit(7);
        }
    }
}
