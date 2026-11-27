/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Per-command HTTPS CONNECT proxy. The private Unix endpoint is exposed only to the native bridge. */
public final class SandboxProxy implements AutoCloseable {
    private final Path path;
    private final SandboxDestinations destinations;
    private final ServerSocketChannel listener;
    private final Set<AutoCloseable> connections = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final java.util.concurrent.ScheduledExecutorService deadlines =
            Executors.newSingleThreadScheduledExecutor();
    private final Semaphore slots = new Semaphore(64);
    private final AtomicBoolean closed = new AtomicBoolean();

    public SandboxProxy(final Path path, final SandboxDestinations destinations) throws IOException {
        this.path = path;
        this.destinations = destinations;
        listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            listener.bind(UnixDomainSocketAddress.of(path));
            workers.submit(this::accept);
        } catch (final IOException | RuntimeException error) {
            close();
            throw error;
        }
    }

    private void accept() {
        while (!closed.get()) {
            try {
                final var channel = listener.accept();
                if (!slots.tryAcquire()) {
                    channel.close();
                    continue;
                }
                connections.add(channel);
                workers.submit(() -> tunnel(channel));
            } catch (final IOException error) {
                return;
            }
        }
    }

    private void tunnel(final SocketChannel client) {
        final var timeout = deadlines.schedule(() -> quietlyClose(client), 15, TimeUnit.SECONDS);
        try (client;
                final var upstream = new Socket()) {
            connections.add(upstream);
            try {
                final var header = readHeader(client);
                final var firstLine = header.substring(0, header.indexOf("\r\n"));
                final var words = firstLine.split(" ");
                if (words.length != 3 || !"CONNECT".equals(words[0]) || !words[2].startsWith("HTTP/1.")) {
                    throw new SecurityException("Only HTTPS CONNECT is supported by the sandbox proxy");
                }
                final var destination = URI.create("https://" + words[1]);
                if (!"".equals(destination.getRawPath())
                        || destination.getRawQuery() != null
                        || destination.getRawFragment() != null) {
                    throw new SecurityException("Invalid CONNECT authority");
                }
                final var addresses = destinations.resolve(destination);
                IOException last = null;
                for (final var address : addresses) {
                    try {
                        // No second DNS lookup: the validated concrete address is used for the socket.
                        upstream.connect(new InetSocketAddress(address, SandboxDestinations.port(destination)), 10_000);
                        last = null;
                        break;
                    } catch (final IOException error) {
                        last = error;
                        break; // A failed Socket must not be reused for another address.
                    }
                }
                if (last != null || !upstream.isConnected()) {
                    throw last == null ? new IOException("No public destination address") : last;
                }
                write(
                        client,
                        ByteBuffer.wrap(
                                "HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
                timeout.cancel(false);
                upstream.setSoTimeout(120_000);
                final var incoming = workers.submit(() -> {
                    try {
                        final var buffer = new byte[16_384];
                        int count;
                        while ((count = upstream.getInputStream().read(buffer)) >= 0) {
                            write(client, ByteBuffer.wrap(buffer, 0, count));
                        }
                    } catch (final IOException ignored) {
                        // Either peer closed the tunnel.
                    } finally {
                        quietlyClose(client);
                        quietlyClose(upstream);
                    }
                });
                try {
                    final var buffer = ByteBuffer.allocate(16_384);
                    while (client.read(buffer) >= 0) {
                        buffer.flip();
                        upstream.getOutputStream().write(buffer.array(), 0, buffer.remaining());
                        buffer.clear();
                    }
                } finally {
                    incoming.cancel(true);
                }
            } finally {
                connections.remove(upstream);
            }
        } catch (final IOException | RuntimeException denied) {
            // No raw destination/error data goes into controller logs or model/provider traffic.
            try {
                write(
                        client,
                        ByteBuffer.wrap("HTTP/1.1 403 Sandbox Denied\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                                .getBytes(StandardCharsets.US_ASCII)));
            } catch (final IOException ignored) {
                // The native bridge may already have closed the client.
            }
        } finally {
            timeout.cancel(false);
            connections.remove(client);
            slots.release();
        }
    }

    private static String readHeader(final SocketChannel channel) throws IOException {
        final var out = new ByteArrayOutputStream();
        final var one = ByteBuffer.allocate(1);
        int tail = 0;
        while (out.size() < 32_768) {
            one.clear();
            if (channel.read(one) < 0) {
                throw new IOException("Incomplete proxy request");
            }
            one.flip();
            final var b = one.get() & 255;
            out.write(b);
            tail = (tail << 8) | b;
            if (tail == 0x0d0a0d0a) {
                return out.toString(StandardCharsets.US_ASCII);
            }
        }
        throw new IOException("Proxy header too large");
    }

    private static void write(final SocketChannel channel, final ByteBuffer data) throws IOException {
        while (data.hasRemaining()) {
            channel.write(data);
        }
    }

    private static void quietlyClose(final AutoCloseable resource) {
        try {
            resource.close();
        } catch (final Exception ignored) {
            // Best effort cleanup after closing the owning process namespace.
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            quietlyClose(listener);
            connections.forEach(SandboxProxy::quietlyClose);
            workers.shutdownNow();
            deadlines.shutdownNow();
            try {
                Files.deleteIfExists(path);
            } catch (final IOException ignored) {
                // The session also removes its private control directory.
            }
        }
    }
}
