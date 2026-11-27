/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.yupiik.ia.agent.core.configuration.SandboxEnvironmentConfiguration;
import io.yupiik.ia.agent.core.configuration.SandboxUnixSocketBridgeConfiguration;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LinuxSandboxConfigurationTest {
    @TempDir
    Path root;

    private LinuxSandbox backend;
    private Path control;

    @BeforeEach
    void prepare() throws Exception {
        assumeTrue("Linux".equals(System.getProperty("os.name")));
        control = Files.createDirectory(root.resolve("control"));
        backend = new LinuxSandbox(control, "");
    }

    @Test
    void rejectsInvalidNamesInBothInheritanceAndOverrides() {
        for (final var name : List.of("", "1TOKEN", "A=B", "A-B", "A.B", "A B", "A\n", "\u00e9", "*", "${TOKEN}")) {
            assertRejected(new SandboxEnvironmentConfiguration(List.of(name), Map.of()));
            assertRejected(new SandboxEnvironmentConfiguration(List.of(), Map.of(name, "value")));
        }
        assertRejected(new SandboxEnvironmentConfiguration(List.of(), Map.of("TOKEN", "nul\0value")));
    }

    @Test
    void protectsLauncherOwnedVariablesEvenWhenAbsentFromAmbientEnvironment() {
        for (final var name : List.of(
                "PATH",
                "HOME",
                "TMPDIR",
                "TMP",
                "TEMP",
                "YCA_TEMPDIR",
                "XDG_CACHE_HOME",
                "GRADLE_USER_HOME",
                "npm_config_cache",
                "CARGO_HOME",
                "GOCACHE",
                "GOMODCACHE",
                "PIP_CACHE_DIR",
                "HTTPS_PROXY",
                "https_proxy",
                "HTTP_PROXY",
                "http_proxy",
                "ALL_PROXY",
                "all_proxy",
                "MAVEN_ARGS",
                "MAVEN_OPTS",
                "JAVA_HOME")) {
            assertRejected(new SandboxEnvironmentConfiguration(List.of(name), Map.of()));
            assertRejected(new SandboxEnvironmentConfiguration(List.of(), Map.of(name, "")));
        }
    }

    @Test
    void boundsEnvironmentCountAndEncodedSize() {
        final var entries = new LinkedHashMap<String, String>();
        IntStream.range(0, 4096).forEach(i -> entries.put("V" + i, ""));
        assertRejected(new SandboxEnvironmentConfiguration(List.of(), entries));
        entries.clear();
        IntStream.range(0, 17).forEach(i -> entries.put("V" + i, "v".repeat(65000)));
        assertRejected(new SandboxEnvironmentConfiguration(List.of(), entries));
    }

    @Test
    void rejectsInvalidBridgePortsBeforeTouchingTheSocket() {
        for (final var port : List.of(-1, 0, 80, 1023, 3128, 65536)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> launch(
                            new SandboxEnvironmentConfiguration(List.of(), Map.of()),
                            List.of(new SandboxUnixSocketBridgeConfiguration(
                                    root.resolve("missing").toString(), port))));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> launch(
                        new SandboxEnvironmentConfiguration(List.of(), Map.of()),
                        IntStream.range(0, 33)
                                .mapToObj(i -> new SandboxUnixSocketBridgeConfiguration("/missing" + i, 10000 + i))
                                .toList()));
    }

    @Test
    void rejectsDuplicatePortsAndPaths() throws Exception {
        final var path = root.resolve("peer.sock");
        try (final var socket = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            socket.bind(UnixDomainSocketAddress.of(path));
            for (final var second : List.of(
                    new SandboxUnixSocketBridgeConfiguration(
                            root.resolve("other").toString(), 12345),
                    new SandboxUnixSocketBridgeConfiguration(path.toString(), 12346))) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> launch(
                                new SandboxEnvironmentConfiguration(List.of(), Map.of()),
                                List.of(new SandboxUnixSocketBridgeConfiguration(path.toString(), 12345), second)));
            }
        }
    }

    @Test
    void rejectsMissingRegularRelativeAndAliasedSocketPaths() throws Exception {
        final var file = Files.writeString(root.resolve("file"), "not a socket");
        final var socketPath = root.resolve("peer.sock");
        try (final var socket = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            socket.bind(UnixDomainSocketAddress.of(socketPath));
            final var alias = Files.createSymbolicLink(root.resolve("alias"), socketPath);
            final var parentAlias = Files.createSymbolicLink(root.resolve("parent-alias"), root);
            for (final var path : List.of(
                    file,
                    alias,
                    parentAlias.resolve("peer.sock"),
                    Path.of("relative"),
                    root.resolve("a/../peer.sock"))) {
                assertThrows(
                        SecurityException.class,
                        () -> launch(
                                new SandboxEnvironmentConfiguration(List.of(), Map.of()),
                                List.of(new SandboxUnixSocketBridgeConfiguration(path.toString(), 12345))));
            }
            assertThrows(
                    IOException.class,
                    () -> launch(
                            new SandboxEnvironmentConfiguration(List.of(), Map.of()),
                            List.of(new SandboxUnixSocketBridgeConfiguration(
                                    root.resolve("missing").toString(), 12345))));
        }
    }

    @Test
    @SuppressWarnings("restricted")
    void rejectsDatagramSocketInodes() throws Throwable {
        final var linker = Linker.nativeLinker();
        final var symbols = linker.defaultLookup();
        final var socket = linker.downcallHandle(
                symbols.find("socket").orElseThrow(),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        final var bind = linker.downcallHandle(
                symbols.find("bind").orElseThrow(),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        final var close = linker.downcallHandle(
                symbols.find("close").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        final var path = root.resolve("datagram.sock");
        final int descriptor = (int) socket.invokeExact(1, 2, 0);
        if (descriptor < 0) {
            throw new IOException("Cannot create Unix datagram test endpoint");
        }
        try (final var arena = Arena.ofConfined()) {
            final var address = arena.allocate(110);
            address.set(ValueLayout.JAVA_SHORT, 0, (short) 1);
            final var bytes = path.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length >= 108) {
                throw new IOException("Unix test socket path is too long");
            }
            address.asSlice(2, bytes.length).copyFrom(MemorySegment.ofArray(bytes));
            assertEquals(0, (int) bind.invokeExact(descriptor, address, 110));
            assertThrows(
                    IOException.class,
                    () -> launch(
                            new SandboxEnvironmentConfiguration(List.of(), Map.of()),
                            List.of(new SandboxUnixSocketBridgeConfiguration(path.toString(), 12345))));
        } finally {
            assertEquals(0, (int) close.invokeExact(descriptor));
            Files.deleteIfExists(path);
        }
    }

    @Test
    void cleansPrivateEnvironmentAfterLaunchPreparationFailure() throws Exception {
        assertThrows(
                NullPointerException.class,
                () -> backend.launch(
                        new ProcessBuilder("/bin/true"),
                        new SessionSandbox.Snapshot(List.of(), List.of(root), List.of(), List.of(), List.of()),
                        root.resolve("scratch"),
                        () -> {}));
        try (final var files = Files.list(control)) {
            assertEquals(
                    List.of(),
                    files.filter(p -> p.getFileName().toString().startsWith("command-"))
                            .toList());
        }
    }

    private void assertRejected(final SandboxEnvironmentConfiguration environment) {
        assertThrows(IllegalArgumentException.class, () -> launch(environment, List.of()));
    }

    private Sandbox.Command launch(
            final SandboxEnvironmentConfiguration environment, final List<SandboxUnixSocketBridgeConfiguration> bridges)
            throws IOException {
        final var builder = new ProcessBuilder("/bin/true").directory(root.toFile());
        builder.environment().clear();
        return backend.launch(
                builder,
                new SessionSandbox.Snapshot(List.of(), List.of(root), List.of(), List.of(), List.of()),
                root.resolve("scratch"),
                () -> {},
                environment,
                bridges);
    }
}
