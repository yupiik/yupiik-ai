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
package io.yupiik.ia.agent.bot.gateway;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Credential-free, bounded CONNECT destination proxy. Does not decrypt or inspect tunnel contents. */
public final class EgressGateway implements AutoCloseable {
    private static final Pattern HEADER_LINES = Pattern.compile("\r\n");
    private static final Set<String> COMMON_PUBLIC_SUFFIXES = Set.of(
            "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "co.jp", "co.nz", "com.br", "com.cn",
            "co.in", "co.za");

    private final ServerSocket listener;
    private final Set<String> hosts;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Semaphore slots = new Semaphore(64);
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1);
    private final Duration tunnelLifetime;

    public EgressGateway(final InetSocketAddress bind, final Collection<String> hosts, final Duration tunnelLifetime)
            throws IOException {
        this.hosts = validateHosts(hosts);
        if (tunnelLifetime.isNegative() || tunnelLifetime.isZero()) {
            throw new IllegalArgumentException("Positive tunnel lifetime required");
        }
        this.tunnelLifetime = tunnelLifetime;
        deadlines.setRemoveOnCancelPolicy(true);
        listener = new ServerSocket();
        listener.bind(bind, 64);
        Thread.ofVirtual().name("egress-accept").start(this::accept);
    }

    public int port() {
        return listener.getLocalPort();
    }

    /**
     * Validates exact DNS names and explicit {@code *.example.com} subdomain patterns, excluding the apex.
     * Wildcard suffixes require at least two labels and cannot be common public suffixes.
     * This is not a public suffix list: operators must select only operator-controlled or explicitly trusted domains.
     */
    public static Set<String> validateHosts(final Collection<String> hosts) {
        if (hosts == null || hosts.isEmpty() || hosts.size() > 256) {
            throw new IllegalArgumentException("Between 1 and 256 DNS hosts or subdomain patterns required");
        }
        final var result = new HashSet<String>();
        for (final var value : hosts) {
            final boolean wildcard = value != null && value.startsWith("*.");
            final var host = canonicalHost(wildcard ? value.substring(2) : value);
            if (wildcard && (host.length() > 251 || COMMON_PUBLIC_SUFFIXES.contains(host))) {
                throw new IllegalArgumentException("Wildcard requires a trusted DNS suffix below a public suffix");
            }
            result.add(wildcard ? "*." + host : host);
        }
        return Set.copyOf(result);
    }
    /** Tests the DNS allowlist only, without resolving; allowed names must still resolve exclusively to public IPs. */
    public boolean allowsHost(final String value) {
        final String host;
        try {
            host = canonicalHost(value);
        } catch (final IllegalArgumentException invalid) {
            return false;
        }
        if (hosts.contains(host)) {
            return true;
        }
        for (final var pattern : hosts) {
            if (pattern.startsWith("*.") && host.endsWith(pattern.substring(1))) {
                return true;
            }
        }
        return false;
    }

    private static String canonicalHost(final String value) {
        if (value == null
                || value.length() > 253
                || value.length() < 3
                || !value.chars().allMatch(c -> c < 128)) {
            throw new IllegalArgumentException("Invalid egress hostname");
        }
        final var host = value.toLowerCase(Locale.ROOT);
        final var labels = host.split("\\.", -1);
        if (labels.length < 2 || !labels[labels.length - 1].chars().allMatch(c -> c >= 'a' && c <= 'z')) {
            throw new IllegalArgumentException("DNS hostname required, not an IP or wildcard");
        }
        for (final var label : labels) {
            if (label.isEmpty()
                    || label.length() > 63
                    || label.charAt(0) == '-'
                    || label.charAt(label.length() - 1) == '-'
                    || !label.chars().allMatch(c -> c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-')) {
                throw new IllegalArgumentException("Invalid egress hostname");
            }
        }
        return host;
    }

    private void accept() {
        while (!listener.isClosed()) {
            try {
                final var client = listener.accept();
                if (!slots.tryAcquire()) {
                    client.close();
                    continue;
                }
                sockets.add(client);
                Thread.ofVirtual().start(() -> handle(client));
            } catch (final IOException e) {
                close();
            }
        }
    }

    private void handle(final Socket client) {
        final var upstream = new Socket();
        sockets.add(upstream);
        final var deadline = deadlines.schedule(
                () -> {
                    closeSocket(client);
                    closeSocket(upstream);
                },
                tunnelLifetime.toMillis(),
                TimeUnit.MILLISECONDS);
        try (client;
                upstream) {
            client.setSoTimeout(10_000);
            final var header = new StringBuilder();
            while (header.length() < 8192 && !header.toString().endsWith("\r\n\r\n")) {
                final int c = client.getInputStream().read();
                if (c < 0 || c > 127 || c == 0) {
                    throw new IOException("Invalid CONNECT header");
                }
                header.append((char) c);
            }
            final var lines = HEADER_LINES.split(header);
            final var request = lines[0].split(" ", -1);
            if (!header.toString().endsWith("\r\n\r\n")
                    || request.length != 3
                    || !request[0].equals("CONNECT")
                    || !request[2].equals("HTTP/1.1")
                    || !request[1].endsWith(":443")) {
                deny(client);
                return;
            }
            final var host = request[1].substring(0, request[1].length() - 4).toLowerCase(Locale.ROOT);
            if (!allowsHost(host)) {
                deny(client);
                return;
            }
            for (int i = 1; i < lines.length; i++) {
                final var line = lines[i].toLowerCase(Locale.ROOT);
                if (!line.startsWith("host:")
                        && !line.startsWith("user-agent:")
                        && !line.startsWith("proxy-connection:")) {
                    deny(client);
                    return;
                }
            }
            // Resolve once, reject the entire answer if any address is non-public, then connect numerically.
            final var addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(a -> !isPublicAddress(a))) {
                deny(client);
                return;
            }
            upstream.connect(new InetSocketAddress(addresses[0], 443), 5_000);
            upstream.setSoTimeout(60_000);
            client.setSoTimeout(60_000);
            client.getOutputStream()
                    .write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            final var reverse = Thread.ofVirtual().start(() -> copy(upstream, client));
            copy(client, upstream);
            closeSocket(upstream);
            closeSocket(client);
            reverse.join(1_000);
        } catch (final IOException ignored) {
            // No request/credential logging. Closing both sides is fail-closed.
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            deadline.cancel(false);
            closeSocket(client);
            closeSocket(upstream);
            sockets.remove(client);
            sockets.remove(upstream);
            slots.release();
        }
    }

    public static boolean isPublicAddress(final InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        final var bytes = address.getAddress();
        final int a = bytes[0] & 255;
        final int b = bytes[1] & 255;
        if (bytes.length == 16) {
            // Only global unicast; exclude special-purpose 2001::/23 and documentation 2001:db8::/32.
            return (a & 224) == 32
                    && !(a == 32
                            && b == 1
                            && ((bytes[2] & 255) < 2 || (bytes[2] & 255) == 13 && (bytes[3] & 255) == 184))
                    && !(a == 63 && b == 255); // 3fff::/20 documentation (conservatively /16).
        }
        final int c = bytes[2] & 255;
        return a != 0
                && a != 10
                && a != 127
                && a < 224
                && !(a == 100 && b >= 64 && b <= 127)
                && !(a == 169 && b == 254)
                && !(a == 172 && b >= 16 && b <= 31)
                && !(a == 192 && (b == 168 || b == 0 || b == 2))
                && !(a == 198 && (b == 18 || b == 19 || b == 51 && c == 100))
                && !(a == 203 && b == 0 && c == 113);
    }

    private static void copy(final Socket source, final Socket destination) {
        try {
            source.getInputStream().transferTo(destination.getOutputStream());
        } catch (final IOException ignored) {
            // The peer or deadline closed the tunnel.
        } finally {
            closeSocket(source);
            closeSocket(destination);
        }
    }

    private static void deny(final Socket socket) throws IOException {
        socket.getOutputStream()
                .write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
    }

    private static void closeSocket(final Socket socket) {
        try {
            socket.close();
        } catch (final IOException ignored) {
            // Best effort on an already closing socket.
        }
    }

    @Override
    public void close() {
        try {
            listener.close();
        } catch (final IOException ignored) {
            // Already closed.
        }
        sockets.forEach(EgressGateway::closeSocket);
        deadlines.shutdownNow();
    }

    /** Standalone entry point in the bot fatjar; deliberately does not bootstrap worker/DI/credentials. */
    public static void main(final String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--check")) {
            try (final var socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", 8080), 2_000);
            }
            return;
        }
        if (args.length < 3 || !args[0].equals("--lifetime-seconds")) {
            throw new IllegalArgumentException("--lifetime-seconds SECONDS HOST... required");
        }
        final long lifetime = Long.parseLong(args[1]);
        if (lifetime < 1 || lifetime > TimeUnit.DAYS.toSeconds(7) + 120) {
            throw new IllegalArgumentException("Gateway lifetime out of bounds");
        }
        try (final var gateway = new EgressGateway(
                new InetSocketAddress("0.0.0.0", 8080),
                Arrays.asList(args).subList(2, args.length),
                Duration.ofMinutes(10))) {
            Runtime.getRuntime().addShutdownHook(new Thread(gateway::close));
            new CountDownLatch(1).await(lifetime, TimeUnit.SECONDS);
        }
    }
}
