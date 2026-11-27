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
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SandboxBridgeTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(30)
    @ResourceLock("sandbox-bridge-loopback-3128")
    void boundsActiveRelaysAndResumesQueuedTrafficWithBidirectionalHalfClose(final boolean endpoint) throws Exception {
        assumeTrue("Linux".equals(System.getProperty("os.name")), "Linux helper only");
        assumeTrue(
                List.of("amd64", "x86_64", "aarch64").contains(System.getProperty("os.arch")),
                "helper requires x86_64 or aarch64");
        final var java = Path.of(System.getProperty("java.home")).toRealPath().resolve("bin/java");
        assumeTrue(Files.isExecutable(java), "current JDK required");
        final var socket = root.resolve("control.sock");
        final var endpointSocket = root.resolve("endpoint.sock");
        final var secondSocket = root.resolve("second.sock");
        final var errors = root.resolve("bridge.stderr");
        final var clients = new ArrayList<Socket>();
        final var controllers = new ArrayList<SocketChannel>();
        try (final var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                final var selector = Selector.open();
                final var endpointListener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                final var endpointSelector = Selector.open();
                final var secondListener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                final var secondSelector = Selector.open()) {
            listener.bind(UnixDomainSocketAddress.of(socket));
            listener.configureBlocking(false);
            listener.register(selector, SelectionKey.OP_ACCEPT);
            endpointListener.bind(UnixDomainSocketAddress.of(endpointSocket));
            endpointListener.configureBlocking(false);
            endpointListener.register(endpointSelector, SelectionKey.OP_ACCEPT);
            secondListener.bind(UnixDomainSocketAddress.of(secondSocket));
            secondListener.configureBlocking(false);
            secondListener.register(secondSelector, SelectionKey.OP_ACCEPT);
            final var command = new ArrayList<>(List.of(
                    java.toString(),
                    "--enable-native-access=ALL-UNNAMED",
                    "-cp",
                    Path.of(HelperMain.class
                                    .getProtectionDomain()
                                    .getCodeSource()
                                    .getLocation()
                                    .toURI())
                            .toString(),
                    HelperMain.class.getName(),
                    "--bridge",
                    socket.toString(),
                    Long.toString(ProcessHandle.current().pid())));
            if (endpoint) {
                command.addAll(List.of(
                        "--socket-bridge",
                        "18371",
                        endpointSocket.toString(),
                        "--socket-bridge",
                        "18372",
                        secondSocket.toString()));
            }
            final var builder = new ProcessBuilder(command);
            for (final var key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
                builder.environment().remove(key);
            }
            final var bridge = builder.redirectError(errors.toFile()).start();
            final var ready =
                    new BufferedReader(new InputStreamReader(bridge.getInputStream(), StandardCharsets.US_ASCII));
            try {
                assertTimeoutPreemptively(
                        Duration.ofSeconds(10),
                        () -> assertEquals("READY", ready.readLine(), Files.readString(errors)));
                assertTrue(bridge.isAlive(), Files.readString(errors));
                if (!endpoint) {
                    for (final var port : List.of(18371, 18372)) {
                        assertThrows(ConnectException.class, () -> {
                            try (final var unexpected = connect(port)) {
                                fail("Unconfigured endpoint must not be enabled by default: "
                                        + unexpected.getRemoteSocketAddress());
                            }
                        });
                    }
                }
                // Every accepted controller socket is an observable active relay, not a JVM thread or fork.
                for (var i = 0; i < 64; i++) {
                    final var explicit = endpoint && i % 2 == 0;
                    clients.add(connect(explicit ? 18371 : 3128));
                    controllers.add(
                            accept(explicit ? endpointListener : listener, explicit ? endpointSelector : selector));
                }
                final var queued = connect(endpoint ? 18372 : 3128);
                clients.add(queued);
                queued.getOutputStream().write("request".getBytes(StandardCharsets.US_ASCII));
                queued.shutdownOutput();
                // A bounded readiness wait, not a sleep: the 65th relay cannot open its controller yet.
                final var queuedSelector = endpoint ? secondSelector : selector;
                queuedSelector.selectedKeys().clear();
                assertEquals(0, queuedSelector.select(250), "all endpoints must share the 64-relay capacity");
                controllers.getFirst().close();
                clients.getFirst().close();
                final var resumed = accept(endpoint ? secondListener : listener, queuedSelector);
                controllers.add(resumed);
                final var request = ByteBuffer.allocate(7);
                while (request.hasRemaining()) {
                    assertTrue(resumed.read(request) > 0, "queued request ended early");
                }
                assertEquals("request", new String(request.array(), StandardCharsets.US_ASCII));
                assertEquals(-1, resumed.read(ByteBuffer.allocate(1)), "client half-close must reach controller");
                // The reverse direction must remain usable after client EOF.
                final var response = ByteBuffer.wrap("response".getBytes(StandardCharsets.US_ASCII));
                while (response.hasRemaining()) {
                    resumed.write(response);
                }
                resumed.shutdownOutput();
                assertEquals("response", new String(queued.getInputStream().readAllBytes(), StandardCharsets.US_ASCII));
                assertTrue(bridge.isAlive(), Files.readString(errors));
            } finally {
                bridge.destroyForcibly();
                assertTrue(bridge.waitFor(10, TimeUnit.SECONDS), "bridge did not terminate");
                ready.close();
                for (final var client : clients) {
                    client.close();
                }
                for (final var controller : controllers) {
                    controller.close();
                }
            }
        }
    }

    private Socket connect(final int port) throws Exception {
        final var client = new Socket();
        try {
            client.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            client.setSoTimeout(5000);
            return client;
        } catch (final Exception failure) {
            client.close();
            throw failure;
        }
    }

    private SocketChannel accept(final ServerSocketChannel listener, final Selector selector) throws Exception {
        selector.selectedKeys().clear();
        assertTrue(selector.select(5000) > 0, "bridge did not connect to Unix controller");
        final var accepted = listener.accept();
        assertNotNull(accepted);
        return accepted;
    }
}
