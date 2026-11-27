/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.sandbox.helper;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Timeout(30)
@ResourceLock("sandbox-bridge-loopback-3128")
class HelperMainTest {
    @TempDir
    Path root;

    @BeforeEach
    void linux() {
        assumeTrue("Linux".equals(System.getProperty("os.name")));
        assumeTrue(List.of("amd64", "x86_64", "aarch64").contains(System.getProperty("os.arch")));
    }

    @Test
    void finalEnvironmentNeverBecomesHelperOrBridgeStartupEnvironment() throws Exception {
        final var file = root.resolve("environment.bin");
        Files.write(
                file,
                environment(
                        5,
                        "JAVA_TOOL_OPTIONS",
                        "-XX:DefinitelyNotARealOption",
                        "JDK_JAVA_OPTIONS",
                        "--definitely-not-a-real-option",
                        "_JAVA_OPTIONS",
                        "-XX:DefinitelyNotARealOption",
                        "FINAL_ONLY",
                        "complete=value",
                        "UTF_VALUE",
                        "line1\nline2\u00e9"));
        final var result = execute(
                List.of(
                        root.resolve("policy.sock").toString(),
                        "--command-environment",
                        file.toString(),
                        "--",
                        "/usr/bin/env"),
                true);
        assertEquals(0, result, Files.readString(root.resolve("stderr")));
        final var output = Files.readString(root.resolve("stdout"));
        assertEquals(5, output.lines().filter(line -> line.contains("=")).count(), output);
        assertTrue(output.contains("FINAL_ONLY=complete=value\n"), output);
        assertTrue(output.contains("UTF_VALUE=line1\nline2\u00e9\n"), output);
        assertTrue(output.contains("JAVA_TOOL_OPTIONS=-XX:DefinitelyNotARealOption\n"), output);
        assertFalse(output.contains("INHERITED_ONLY="), output);
        // Both JVMs must have started successfully. If any final JVM options entered
        // the trusted bridge's environment, it would fail before READY and exec.
        assertFalse(Files.readString(root.resolve("stderr")).contains("Picked up"));
    }

    @Test
    void omittedFileUsesInheritedEnvironmentAndEmptyFileMeansEmptyEnvironment() throws Exception {
        assertEquals(0, execute(List.of(root.resolve("policy.sock").toString(), "--", "/usr/bin/env"), true));
        assertTrue(Files.readString(root.resolve("stdout")).contains("INHERITED_ONLY=present\n"));
        final var file = root.resolve("empty.bin");
        Files.write(file, environment(0));
        assertEquals(
                0,
                execute(
                        List.of(
                                root.resolve("policy.sock").toString(),
                                "--command-environment",
                                file.toString(),
                                "--",
                                "/usr/bin/env"),
                        true));
        assertEquals("", Files.readString(root.resolve("stdout")));
    }

    @Test
    void helperForwardsMultipleBridgesButNotEnvironmentFile() throws Exception {
        final var file = root.resolve("environment.bin");
        Files.write(file, environment(2, "FINAL_ONLY", "ready", "JAVA_TOOL_OPTIONS", "-XX:InvalidStartupOption"));
        final var firstSocket = root.resolve("first.sock");
        final var secondSocket = root.resolve("second.sock");
        try (final var first = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                final var second = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            first.bind(UnixDomainSocketAddress.of(firstSocket));
            second.bind(UnixDomainSocketAddress.of(secondSocket));
            final var process = process(
                            List.of(
                                    root.resolve("policy.sock").toString(),
                                    "--socket-bridge",
                                    "18371",
                                    firstSocket.toString(),
                                    "--socket-bridge",
                                    "18372",
                                    secondSocket.toString(),
                                    "--command-environment",
                                    file.toString(),
                                    "--",
                                    "/bin/sh",
                                    "-c",
                                    "printf '%s\\n' \"$FINAL_ONLY\"; read line"),
                            HelperMain.class)
                    .redirectError(root.resolve("stderr").toFile())
                    .start();
            try (final var reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertEquals("ready", reader.readLine()));
                // Readiness of the final command implies the bridge accepted its arguments.
                // Passing the environment-file option to --bridge would have failed startup.
                for (final var port : List.of(18371, 18372)) {
                    try (final var client = new Socket()) {
                        client.connect(new InetSocketAddress("127.0.0.1", port), 5000);
                        client.setSoTimeout(5000);
                        final var listener = port == 18371 ? first : second;
                        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                            try (final var upstream = listener.accept()) {
                                upstream.write(ByteBuffer.wrap(new byte[] {42}));
                                assertEquals(42, client.getInputStream().read());
                            }
                        });
                    }
                }
                process.getOutputStream().close();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            } finally {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
                awaitBridgeClosed();
            }
        }
    }

    @Test
    void acceptsMaximumEntryCount() throws Exception {
        final var pairs = new ArrayList<String>();
        for (var i = 0; i < 4096; i++) {
            pairs.add("KEY_" + i);
            pairs.add("value");
        }
        final var file = root.resolve("max.bin");
        Files.write(file, environment(4096, pairs.toArray(String[]::new)));
        assertEquals(
                0,
                execute(
                        List.of(
                                root.resolve("policy.sock").toString(),
                                "--command-environment",
                                file.toString(),
                                "--",
                                "/usr/bin/env"),
                        true));
        assertEquals(4096, Files.readAllLines(root.resolve("stdout")).size());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidEnvironments")
    void rejectsMalformedEnvironmentBeforeStartingCommand(final String name, final byte[] bytes) throws Exception {
        final var file = root.resolve(name + ".bin");
        Files.write(file, bytes);
        assertEquals(
                125,
                execute(
                        List.of(
                                root.resolve("policy.sock").toString(),
                                "--command-environment",
                                file.toString(),
                                "--",
                                "/usr/bin/env"),
                        false));
        assertEquals("", Files.readString(root.resolve("stdout")));
    }

    private static Stream<Arguments> invalidEnvironments() throws Exception {
        return Stream.of(
                Arguments.of("negative-count", environment(-1)),
                Arguments.of("excess-count", environment(4097)),
                Arguments.of("duplicate", environment(2, "A", "one", "A", "two")),
                Arguments.of("empty-key", environment(1, "", "value")),
                Arguments.of("equals-key", environment(1, "A=B", "value")),
                Arguments.of("nul-key", environment(1, "A\0B", "value")),
                Arguments.of("invalid-key", environment(1, "1BAD", "value")),
                Arguments.of("nul-value", environment(1, "A", "a\0b")),
                Arguments.of("trailing", new byte[] {0, 0, 0, 0, 1}),
                Arguments.of("truncated", environment(1, "A")),
                Arguments.of("truncated-count", new byte[] {0, 0}),
                Arguments.of("malformed-utf", new byte[] {0, 0, 0, 1, 0, 1, (byte) 0xff}),
                Arguments.of("oversize", new byte[1024 * 1024 + 1]));
    }

    @ParameterizedTest
    @MethodSource("invalidOptions")
    void rejectsInvalidOptions(final List<String> options) throws Exception {
        final var arguments =
                new ArrayList<>(List.of(root.resolve("policy.sock").toString()));
        arguments.addAll(options);
        arguments.addAll(List.of("--", "/usr/bin/env"));
        assertEquals(125, execute(arguments, false));
        final var bridgeArguments = new ArrayList<>(List.of(
                "--bridge",
                root.resolve("policy.sock").toString(),
                Long.toString(ProcessHandle.current().pid())));
        bridgeArguments.addAll(options);
        assertEquals(125, execute(bridgeArguments, false));
    }

    private static Stream<List<String>> invalidOptions() {
        return Stream.of(
                List.of("--socket-bridge", "1023", "/tmp/socket"),
                List.of("--socket-bridge", "65536", "/tmp/socket"),
                List.of("--socket-bridge", "3128", "/tmp/socket"),
                List.of("--socket-bridge", "not-a-port", "/tmp/socket"),
                List.of("--socket-bridge", "18371", "relative.sock"),
                List.of("--socket-bridge", "18371", "/tmp/a", "--socket-bridge", "18371", "/tmp/b"),
                List.of("--socket-bridge"),
                List.of("--socket-bridge", "18371"),
                List.of("--command-environment", "relative.bin"),
                List.of("--command-environment", "/tmp/never-forward-this.bin"));
    }

    @Test
    void rejectsRelativePolicySocket() throws Exception {
        assertEquals(125, execute(List.of("relative.sock", "--", "/usr/bin/env"), false));
        assertEquals(
                125,
                execute(
                        List.of(
                                "--bridge",
                                "relative.sock",
                                Long.toString(ProcessHandle.current().pid())),
                        false));
        assertThrows(
                IllegalArgumentException.class,
                () -> SandboxBridge.run("relative.sock", ProcessHandle.current().pid(), Map.of()));
    }

    @Test
    void directBridgeApiRejectsInvalidPortsAndPaths() {
        for (final var port : List.of(-1, 0, 1023, 3128, 65536)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SandboxBridge.run(
                            "/tmp/policy", ProcessHandle.current().pid(), Map.of(port, "/tmp/socket")));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> SandboxBridge.run("/tmp/policy", ProcessHandle.current().pid(), Map.of(18371, "relative")));
    }

    @Test
    void partialStartupFailureClosesEveryListenerBeforeReturningWithoutReady() throws Exception {
        try (final var occupied = ServerSocketChannel.open()) {
            occupied.bind(new InetSocketAddress("127.0.0.1", 18372));
            final var builder = process(List.of(), HelperMainTest.class);
            final var process =
                    builder.redirectError(root.resolve("stderr").toFile()).start();
            try (final var reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertEquals("FAILED", reader.readLine()));
                assertTrue(process.isAlive(), "probe must remain alive to prove explicit listener closure");
                try (final var policy = ServerSocketChannel.open();
                        final var first = ServerSocketChannel.open()) {
                    policy.bind(new InetSocketAddress("127.0.0.1", 3128));
                    first.bind(new InetSocketAddress("127.0.0.1", 18371));
                }
            } finally {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            }
        }
    }

    /** Separate JVM probe: keep the process alive after run fails to expose leaked listeners. */
    public static void main(final String[] args) throws Exception {
        final var bridges = new LinkedHashMap<Integer, String>();
        bridges.put(18371, "/tmp/first.sock");
        bridges.put(18372, "/tmp/second.sock");
        try {
            SandboxBridge.run("/tmp/policy.sock", ProcessHandle.current().pid(), bridges);
            throw new IllegalStateException("Expected occupied-port failure");
        } catch (final BindException expected) {
            System.out.println("FAILED");
            System.out.flush();
            System.in.read();
        } catch (final Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }

    private int execute(final List<String> arguments, final boolean waitForBridge) throws Exception {
        final var process = process(arguments, HelperMain.class)
                .redirectOutput(root.resolve("stdout").toFile())
                .redirectError(root.resolve("stderr").toFile())
                .start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "helper timed out");
            return process.exitValue();
        } finally {
            process.destroyForcibly();
            if (waitForBridge) {
                awaitBridgeClosed();
            }
        }
    }

    private void awaitBridgeClosed() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            while (true) {
                try (final var listener = ServerSocketChannel.open()) {
                    listener.bind(new InetSocketAddress("127.0.0.1", 3128));
                    break;
                } catch (final BindException stillRunning) {
                    Thread.sleep(25);
                }
            }
        });
    }

    private ProcessBuilder process(final List<String> arguments, final Class<?> entryPoint) throws Exception {
        final var command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                Path.of(HelperMain.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI())
                        + File.pathSeparator
                        + Path.of(HelperMainTest.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI()),
                entryPoint.getName()));
        command.addAll(arguments);
        final var builder = new ProcessBuilder(command);
        builder.environment().clear();
        builder.environment().put("PATH", "/usr/bin:/bin");
        builder.environment().put("INHERITED_ONLY", "present");
        return builder;
    }

    private static byte[] environment(final int count, final String... strings) throws Exception {
        final var bytes = new ByteArrayOutputStream();
        try (final var output = new DataOutputStream(bytes)) {
            output.writeInt(count);
            for (final var string : strings) {
                output.writeUTF(string);
            }
        }
        return bytes.toByteArray();
    }
}
