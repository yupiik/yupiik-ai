/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.json.JsonMapper;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NotificationEndpointTest {
    private static final String TOKEN = "test-token-not-a-real-secret-0123456789";

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1"})
    void listenerAcceptsBothLoopbackFamilies(final String host) throws Exception {
        requireFamily(host);
        try (final var container = ConfiguringContainer.of().start();
                final var instance = container.lookup(NotificationEndpoint.class);
                final var mapper = container.lookup(JsonMapper.class)) {
            final var unread = new UnreadNotifications();
            try (final var listener =
                            instance.instance().listen(new ServiceConfiguration(0, TOKEN, true, null), unread);
                    final var socket = new Socket(InetAddress.getByName(host), listener.port())) {
                socket.setSoTimeout(3000);
                final var notice = new Notice(1, host, "Family " + host, "turn_end", "main", 1, null);
                final var output = new DataOutputStream(socket.getOutputStream());
                output.writeInt(0x59434101);
                final var token = TOKEN.getBytes(StandardCharsets.UTF_8);
                output.writeInt(token.length);
                output.write(token);
                final var json = mapper.instance().toString(notice).getBytes(StandardCharsets.UTF_8);
                output.writeInt(json.length);
                output.write(json);
                output.flush();
                assertEquals(1, socket.getInputStream().read());
                assertEquals(List.of(notice), unread.read());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1"})
    void publisherReachesSingleFamilyServices(final String host) throws Exception {
        requireFamily(host);
        try (final var container = ConfiguringContainer.of().start();
                final var instance = container.lookup(NotificationEndpoint.class);
                final var mapper = container.lookup(JsonMapper.class);
                final var server = new ServerSocket(0, 1, InetAddress.getByName(host));
                final var executor = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(3000);
            final var received = executor.submit(() -> {
                try (final var socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    final var input = new DataInputStream(socket.getInputStream());
                    assertEquals(0x59434101, input.readInt());
                    assertEquals(TOKEN, new String(input.readNBytes(input.readInt()), StandardCharsets.UTF_8));
                    final var notice = mapper.instance()
                            .fromString(
                                    Notice.class,
                                    new String(input.readNBytes(input.readInt()), StandardCharsets.UTF_8));
                    socket.getOutputStream().write(1);
                    return notice;
                }
            });
            final var notice = new Notice(1, host, "Single-family service", "turn_end", "main", 1, null);
            instance.instance().publish(new ServiceConfiguration(server.getLocalPort(), TOKEN, true, null), notice);
            assertEquals(notice, received.get(4, TimeUnit.SECONDS));
        }
    }

    private void requireFamily(final String host) throws Exception {
        if ("::1".equals(host)) {
            assumeTrue(
                    !Boolean.getBoolean("java.net.preferIPv4Stack")
                            && NetworkInterface.getByInetAddress(InetAddress.getByName(host)) != null,
                    "IPv6 loopback is not configured on this host");
        }
    }

    @Test
    void authenticatedIpcRoundTripsGeneratedJsonWithoutAFileStore() throws Exception {
        try (final var container = ConfiguringContainer.of().start();
                final var instance = container.lookup(NotificationEndpoint.class)) {
            final var endpoint = instance.instance();
            final var state = new UnreadNotifications();
            final var notice = new Notice(1, "session", "Test title", "approval_required", "btw", 1, null);
            final int port;
            try (final var listener = endpoint.listen(new ServiceConfiguration(0, TOKEN, true, null), state)) {
                port = listener.port();
                endpoint.publish(new ServiceConfiguration(port, TOKEN, true, null), notice);
                assertEquals(List.of(notice), state.read());
                assertThrows(
                        IOException.class,
                        () -> endpoint.publish(
                                new ServiceConfiguration(port, "a-different-token-01234567890123456789", true, null),
                                notice));
                assertEquals(List.of(notice), state.read());
            }
            assertThrows(
                    IOException.class,
                    () -> endpoint.publish(new ServiceConfiguration(port, TOKEN, true, null), notice));
            assertTrue(new UnreadNotifications().read().isEmpty());
        }
    }

    @Test
    void sessionAcknowledgementsRoundTripAndRejectDelayedEvents() throws Exception {
        try (final var container = ConfiguringContainer.of().start();
                final var instance = container.lookup(NotificationEndpoint.class)) {
            final var endpoint = instance.instance();
            final var state = new UnreadNotifications();
            try (final var listener = endpoint.listen(new ServiceConfiguration(0, TOKEN, true, null), state)) {
                final var configuration = new ServiceConfiguration(listener.port(), TOKEN, true, null);
                final var main = new Notice(1, "session", "Main", "approval_required", "main", 1, null);
                final var btw = new Notice(1, "session", "Btw", "input_required", "btw", 2, null);
                endpoint.publish(configuration, main);
                endpoint.publish(configuration, btw);
                endpoint.publish(configuration, new Notice(1, "session", "", "session_acknowledged", "main", 1, null));
                assertEquals(List.of(btw), state.read());
                endpoint.publish(configuration, main);
                assertEquals(List.of(btw), state.read());
                endpoint.publish(configuration, new Notice(1, "session", "", "session_acknowledged", "btw", 2, null));
                endpoint.publish(configuration, btw);
                assertTrue(state.read().isEmpty());
            }
        }
    }

    @Test
    void rejectsMalformedAndOversizedFramesWithoutStoppingListener() throws Exception {
        try (final var container = ConfiguringContainer.of().start();
                final var instance = container.lookup(NotificationEndpoint.class)) {
            final var endpoint = instance.instance();
            final var state = new UnreadNotifications();
            try (final var listener = endpoint.listen(new ServiceConfiguration(0, TOKEN, true, null), state)) {
                try (final var socket = new Socket("127.0.0.1", listener.port())) {
                    socket.setSoTimeout(3000);
                    final var output = new DataOutputStream(socket.getOutputStream());
                    output.writeInt(0x59434101);
                    output.writeInt(Integer.MAX_VALUE);
                    output.flush();
                    assertEquals(-1, socket.getInputStream().read());
                }
                assertTrue(state.read().isEmpty());
                final var notice = new Notice(1, "s", "After invalid frame", "turn_end", "main", 1, null);
                endpoint.publish(new ServiceConfiguration(listener.port(), TOKEN, true, null), notice);
                assertEquals(List.of(notice), state.read());
            }
        }
    }

    @Test
    void requiresSharedCredentialsEvenOnLoopback() throws Exception {
        try (final var container = ConfiguringContainer.of().start();
                final var instance = container.lookup(NotificationEndpoint.class)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> instance.instance()
                            .listen(new ServiceConfiguration(0, "", true, null), new UnreadNotifications()));
        }
    }
}
