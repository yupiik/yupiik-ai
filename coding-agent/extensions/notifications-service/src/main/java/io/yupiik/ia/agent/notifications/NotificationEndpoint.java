/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Authenticated, bounded loopback IPC. No mailbox, endpoint files, or notification persistence. */
@DefaultScoped
public class NotificationEndpoint {
    /** ASCII YCA followed by protocol version 1. */
    private static final int PROTOCOL = 0x59434101;

    private static final int MAX_JSON_BYTES = 8192;
    private final JsonMapper json;

    public NotificationEndpoint(final JsonMapper json) {
        this.json = json;
    }

    public void publish(final ServiceConfiguration configuration, final Notice notice) throws IOException {
        final var token = token(configuration);
        if (configuration.port() < 1 || configuration.port() > 65535) {
            throw new IllegalArgumentException("A notification-service.port between 1 and 65535 is required.");
        }
        final var payload = json.toString(notice).getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_JSON_BYTES) {
            throw new IOException("Notification exceeds the IPC size limit.");
        }
        IOException failure = null;
        for (final var address : addresses(configuration.port())) {
            try (final var socket = new Socket()) {
                socket.connect(address, 1000);
                socket.setSoTimeout(2000);
                final var output = new DataOutputStream(socket.getOutputStream());
                output.writeInt(PROTOCOL);
                output.writeInt(token.length);
                output.write(token);
                output.writeInt(payload.length);
                output.write(payload);
                output.flush();
                if (socket.getInputStream().read() != 1) {
                    throw new IOException("Notification companion rejected the event.");
                }
                return;
            } catch (final IOException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        throw failure == null ? new IOException("No loopback address is available.") : failure;
    }

    public Listener listen(final ServiceConfiguration configuration, final UnreadNotifications unread)
            throws IOException {
        return new Listener(configuration, unread);
    }

    private static List<InetSocketAddress> addresses(final int port) throws IOException {
        final var addresses = new ArrayList<InetSocketAddress>(2);
        // Explicit loopback bindings, never a wildcard or a hostname which might resolve externally.
        addresses.add(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port));
        if (!Boolean.getBoolean("java.net.preferIPv4Stack")) {
            final var bytes = new byte[16];
            bytes[15] = 1;
            final var ipv6 = InetAddress.getByAddress(bytes);
            if (NetworkInterface.getByInetAddress(ipv6) != null) {
                addresses.add(new InetSocketAddress(ipv6, port));
            }
        }
        return List.copyOf(addresses);
    }

    private static byte[] token(final ServiceConfiguration configuration) {
        if (configuration.token() == null
                || configuration.token().length() < 32
                || configuration.token().length() > 256) {
            throw new IllegalArgumentException(
                    "Configure a shared notification-service.token of 32..256 characters in the service and YCA environments.");
        }
        return configuration.token().getBytes(StandardCharsets.UTF_8);
    }

    /** Owns one bounded connection-processing thread per loopback family; the desktop stays on its main OS thread. */
    public final class Listener implements AutoCloseable {
        private final List<ServerSocket> servers = new ArrayList<>(2);
        private final List<Thread> workers = new ArrayList<>(2);
        private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
        private final byte[] token;
        private final UnreadNotifications unread;
        private volatile boolean closed;

        private Listener(final ServiceConfiguration configuration, final UnreadNotifications unread)
                throws IOException {
            token = token(configuration);
            this.unread = unread;
            try {
                for (final var address : addresses(configuration.port())) {
                    final var server = new ServerSocket();
                    servers.add(server);
                    // When using an ephemeral port, share the first binding's port with the other family.
                    server.bind(
                            new InetSocketAddress(
                                    address.getAddress(), servers.size() == 1 ? configuration.port() : port()),
                            16);
                }
                for (final var server : servers) {
                    workers.add(Thread.ofPlatform()
                            .daemon()
                            .name("yca-notifications-ipc")
                            .start(() -> accept(server)));
                }
            } catch (final IOException | RuntimeException failure) {
                try {
                    close();
                } catch (final IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        public int port() {
            return servers.getFirst().getLocalPort();
        }

        private void accept(final ServerSocket server) {
            while (!closed) {
                try (final var socket = server.accept()) {
                    connections.add(socket);
                    try {
                        if (!closed) {
                            socket.setSoTimeout(1000);
                            // A timeout is per read, so enforce a total request budget as well.
                            final var deadline = Thread.ofVirtual().start(() -> expire(socket));
                            try {
                                receive(socket);
                            } finally {
                                deadline.interrupt();
                            }
                        }
                    } finally {
                        connections.remove(socket);
                    }
                } catch (final SocketException failure) {
                    if (server.isClosed()) {
                        return;
                    }
                } catch (final IOException | RuntimeException ignored) {
                    // Malformed, unauthorized or incomplete local requests do not stop the desktop companion.
                }
            }
        }

        private void expire(final Socket socket) {
            try {
                Thread.sleep(2000);
                socket.close();
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (final IOException ignored) {
                // Connection has already closed.
            }
        }

        private void receive(final Socket socket) throws IOException {
            final var input = new DataInputStream(socket.getInputStream());
            if (input.readInt() != PROTOCOL) {
                return;
            }
            final var supplied = read(input, 1024);
            if (!MessageDigest.isEqual(token, supplied)) {
                return;
            }
            final var payload = read(input, MAX_JSON_BYTES);
            unread.publish(json.fromString(Notice.class, new String(payload, StandardCharsets.UTF_8)));
            socket.getOutputStream().write(1);
        }

        private byte[] read(final DataInputStream input, final int maximum) throws IOException {
            final var length = input.readInt();
            if (length < 1 || length > maximum) {
                throw new IOException("Invalid notification frame size.");
            }
            final var data = new byte[length];
            input.readFully(data);
            return data;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            IOException failure = null;
            for (final var server : servers) {
                try {
                    server.close();
                } catch (final IOException error) {
                    if (failure == null) {
                        failure = error;
                    } else {
                        failure.addSuppressed(error);
                    }
                }
            }
            for (final var socket : connections) {
                try {
                    socket.close();
                } catch (final IOException error) {
                    if (failure == null) {
                        failure = error;
                    } else {
                        failure.addSuppressed(error);
                    }
                }
            }
            for (final var worker : workers) {
                try {
                    worker.join(2500);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
