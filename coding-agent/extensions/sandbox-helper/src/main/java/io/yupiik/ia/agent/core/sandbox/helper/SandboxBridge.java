/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.sandbox.helper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** Trusted, separately launched TCP-to-controller bridge; never subject to the command's filter. */
public final class SandboxBridge {
    private SandboxBridge() {
        // No instances.
    }

    public static void run(final String socket, final long parentPid, final Map<Integer, String> bridges)
            throws Throwable {
        if (!Path.of(socket).isAbsolute()) {
            throw new IllegalArgumentException("Policy socket must be absolute");
        }
        for (final var entry : bridges.entrySet()) {
            if (entry.getKey() < 1024
                    || entry.getKey() > 65535
                    || entry.getKey() == 3128
                    || !Path.of(entry.getValue()).isAbsolute()) {
                throw new IllegalArgumentException("Invalid socket bridge: " + entry.getKey());
            }
        }
        LinuxNative.protectBridge(); // Must precede listener creation, including any readiness signal.
        final var parent = ProcessHandle.of(parentPid)
                .orElseThrow(() -> new IllegalStateException("Sandbox helper already exited"));
        Thread.ofPlatform().daemon().name("sandbox-bridge-owner").start(() -> {
            try {
                while (parent.isAlive()) {
                    Thread.sleep(500);
                }
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                System.exit(0);
            }
        });
        final var permits = new Semaphore(64);
        final var listeners = new ArrayList<ServerSocketChannel>();
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor();
                final var selector = Selector.open()) {
            try {
                listeners.add(listen(selector, 3128, socket));
                for (final var entry : bridges.entrySet()) {
                    listeners.add(listen(selector, entry.getKey(), entry.getValue()));
                }
                System.out.println("READY");
                System.out.flush();
                while (true) {
                    // Bound allocations before accept: at most 64 clients and 128 relay tasks.
                    permits.acquire();
                    SocketChannel client = null;
                    try {
                        while (selector.selectedKeys().isEmpty()) {
                            selector.select();
                        }
                        final var keys = selector.selectedKeys().iterator();
                        final var key = keys.next();
                        keys.remove();
                        client = ((ServerSocketChannel) key.channel()).accept();
                        if (client == null) {
                            permits.release();
                            continue;
                        }
                        client.configureBlocking(true);
                        final var accepted = client;
                        final var address = (UnixDomainSocketAddress) key.attachment();
                        executor.execute(() -> relay(accepted, address, executor, permits));
                    } catch (final Throwable failure) {
                        if (client != null) {
                            close(client);
                        }
                        permits.release();
                        throw failure;
                    }
                }
            } finally {
                for (final var listener : listeners) {
                    close(listener);
                }
            }
        }
    }

    private static ServerSocketChannel listen(final Selector selector, final int port, final String socket)
            throws IOException {
        final var listener = ServerSocketChannel.open(StandardProtocolFamily.INET);
        try {
            listener.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port), 32);
            listener.configureBlocking(false);
            listener.register(selector, SelectionKey.OP_ACCEPT, UnixDomainSocketAddress.of(socket));
            return listener;
        } catch (final IOException | RuntimeException failure) {
            listener.close();
            throw failure;
        }
    }

    private static void relay(
            final SocketChannel client,
            final UnixDomainSocketAddress address,
            final ExecutorService executor,
            final Semaphore permits) {
        try (client;
                final var upstream = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            upstream.connect(address);
            final var reverse = executor.submit(() -> pump(upstream, client));
            pump(client, upstream);
            reverse.get();
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (final Exception failure) {
            // A bad or disconnected client cannot terminate the trusted listener.
        } finally {
            permits.release();
        }
    }

    private static void pump(final SocketChannel source, final SocketChannel target) {
        // Direct channel reads/writes use independent read/write locks. Channels stream
        // adapters share blockingLock and can deadlock full-duplex HTTP CONNECT traffic.
        final var buffer = ByteBuffer.allocate(16384);
        try {
            while (source.read(buffer) >= 0) {
                buffer.flip();
                while (buffer.hasRemaining()) {
                    target.write(buffer);
                }
                buffer.clear();
            }
            target.shutdownOutput(); // Preserve the opposite direction after a half-close.
        } catch (final IOException failure) {
            close(source);
            close(target);
        }
    }

    private static void close(final Channel channel) {
        try {
            channel.close();
        } catch (final IOException ignored) {
            // Already disconnected.
        }
    }
}
