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
package io.yupiik.ia.agent.bot.runtime;

import io.yupiik.ia.agent.core.llm.ApiFlavor;
import io.yupiik.ia.agent.core.llm.ChatRoutes;
import io.yupiik.ia.agent.core.llm.UnixProviderProtocol;
import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Single-attempt, controller-owned provider bridge. The caller persists the socket identity before
 * construction, keeps its parent outside all agent filesystem grants, and closes ONLY after launcher
 * quiescence and confirmed agent termination. Existing socket names are never adopted or unlinked.
 *
 * The supplied HTTP client is borrowed and must enforce normal TLS validation, no redirects, no
 * cookies/authenticator and trusted proxy policy. Production upstream must be HTTPS with a pinned API.
 * This service does not own agent/session recovery: after a controller crash, retain budget uncertainty
 * and reconcile the durable Nono identity before deleting the private directory.
 */
public final class NativeProviderServices implements AutoCloseable {
    private static final Pattern UPSTREAM_PATH = Pattern.compile("(/[A-Za-z0-9_-]+)+");
    private final Path socketPath;
    private final Object socketKey;
    private final ServerSocketChannel listener;
    private final HttpClient http;
    private final URI upstream;
    private final ApiFlavor api;
    private final String authorization;
    private final byte[] capability;
    private final Duration deadline;
    private final Budget budget;
    private final Semaphore slots;
    private final Set<SocketChannel> active = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<Void>> workers = ConcurrentHashMap.newKeySet();
    private final Thread acceptor;
    private volatile boolean closed;

    /** A required trusted hook; disabled-budget policy must be chosen explicitly by the caller. */
    @FunctionalInterface
    public interface Budget {
        Reservation acquire() throws Exception;
    }

    /**
     * acquire must persist uncertainty before dispatch. dispatched runs before sendAsync; complete is
     * called only after upstream body EOF and close. close without complete must retain dispatch debt.
     * Authentication/parse failures and local saturation never acquire a reservation.
     */
    public interface Reservation extends AutoCloseable {
        void dispatched() throws Exception;

        void complete() throws Exception;

        @Override
        void close() throws Exception;
    }

    public NativeProviderServices(
            final Path socketPath,
            final HttpClient http,
            final URI upstream,
            final ApiFlavor api,
            final String upstreamAuthorization,
            final String attemptCapability,
            final Duration deadline,
            final int maxConcurrent,
            final Budget budget)
            throws IOException {
        if (http == null
                || upstream == null
                || budget == null
                || maxConcurrent < 1
                || maxConcurrent > 256
                || deadline == null
                || deadline.isNegative()
                || deadline.isZero()
                || deadline.compareTo(UnixProviderProtocol.MAX_DURATION) > 0
                || attemptCapability == null
                || attemptCapability.length() < 32
                || attemptCapability.length() > 256
                || !attemptCapability.chars().allMatch(c -> c >= 33 && c <= 126)
                || upstreamAuthorization == null
                || upstreamAuthorization.isBlank()
                || upstreamAuthorization.indexOf('\r') >= 0
                || upstreamAuthorization.indexOf('\n') >= 0
                || !"https".equals(upstream.getScheme())
                || upstream.getHost() == null
                || upstream.getRawUserInfo() != null
                || upstream.getRawQuery() != null
                || upstream.getRawFragment() != null
                || (api != ApiFlavor.COMPLETIONS && api != ApiFlavor.RESPONSES)
                || ChatRoutes.detectFlavor(upstream.toString()) != api
                || !UPSTREAM_PATH.matcher(upstream.getRawPath()).matches()
                || upstream.getPort() == 0
                || upstream.getPort() > 65535
                || http.followRedirects() != HttpClient.Redirect.NEVER
                || http.cookieHandler().isPresent()
                || http.authenticator().isPresent()) {
            throw new IllegalArgumentException(
                    "Fixed HTTPS provider, scoped capability and bounded trusted policy required");
        }
        validateSocketParent(socketPath);
        this.socketPath = socketPath;
        this.http = http;
        this.upstream = upstream;
        this.api = api;
        this.authorization = upstreamAuthorization;
        this.capability = attemptCapability.getBytes(StandardCharsets.US_ASCII);
        this.deadline = deadline;
        this.budget = budget;
        this.slots = new Semaphore(maxConcurrent);
        listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            listener.bind(UnixDomainSocketAddress.of(socketPath), maxConcurrent);
            Files.setPosixFilePermissions(socketPath, PosixFilePermissions.fromString("rw-------"));
            socketKey = Files.readAttributes(socketPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .fileKey();
        } catch (final IOException | RuntimeException e) {
            listener.close();
            // Do not remove an existing/preempting path on bind failure.
            throw e;
        }
        acceptor = Thread.ofVirtual().name("native-provider-accept").start(this::accept);
    }

    public Path socketPath() {
        return socketPath;
    }

    public static void validateSocketParent(final Path path) throws IOException {
        if (path == null
                || !path.isAbsolute()
                || !path.normalize().equals(path)
                || path.getParent() == null
                || path.toString().getBytes(StandardCharsets.UTF_8).length > 100) {
            throw new IllegalArgumentException("Short absolute private provider socket path required");
        }
        final var parent = path.getParent();
        if (!parent.toRealPath().equals(parent)
                || !Files.getPosixFilePermissions(parent).equals(PosixFilePermissions.fromString("rwx------"))
                || !Files.getOwner(parent).equals(Files.getOwner(Path.of("/proc/self")))) {
            throw new IllegalArgumentException(
                    "Provider socket parent must be private, controller-owned and symlink-free");
        }
    }

    private void accept() {
        try {
            while (!closed) {
                final var channel = listener.accept();
                if (closed) {
                    channel.close();
                    break;
                }
                if (!slots.tryAcquire()) {
                    // Immediate, bounded rejection. No queued worker or budget reservation.
                    channel.close();
                    continue;
                }
                active.add(channel);
                final var done = new CompletableFuture<Void>();
                workers.add(done);
                Thread.ofVirtual().name("native-provider-request").start(() -> {
                    try {
                        forward(channel);
                    } finally {
                        active.remove(channel);
                        slots.release();
                        workers.remove(done);
                        done.complete(null);
                    }
                });
            }
        } catch (final IOException e) {
            if (!closed) {
                closed = true;
                active.forEach(UnixProviderProtocol::close);
            }
        }
    }

    private void forward(final SocketChannel channel) {
        final var response = new AtomicReference<InputStream>();
        final var pending = new AtomicReference<CompletableFuture<HttpResponse<InputStream>>>();
        final Runnable cancel = () -> {
            UnixProviderProtocol.close(channel);
            final var sending = pending.get();
            if (sending != null) {
                sending.cancel(true);
            }
            UnixProviderProtocol.close(response.get());
        };
        final var timer =
                UnixProviderProtocol.deadline(deadline, () -> Thread.ofVirtual().start(cancel));
        try (channel) {
            final var in = UnixProviderProtocol.input(channel);
            if (in.readInt() != UnixProviderProtocol.MAGIC) {
                return;
            }
            final var token = in.readUTF();
            if (token.length() > 256 || !MessageDigest.isEqual(capability, token.getBytes(StandardCharsets.US_ASCII))) {
                return;
            }
            if (!api.name().equals(in.readUTF())) {
                return;
            }
            final int length = in.readInt();
            if (length < 1 || length > UnixProviderProtocol.MAX_REQUEST) {
                return;
            }
            final var body = new byte[length];
            in.readFully(body);
            // A connection is single-use. EOF or any trailing input means cancellation/protocol violation.
            final var watcher = Thread.ofVirtual()
                    .name("native-provider-disconnect")
                    .start(() -> {
                        try {
                            in.read();
                        } catch (final IOException ignored) {
                            // Disconnect and local shutdown have identical cancellation semantics.
                        } finally {
                            cancel.run();
                        }
                    });
            try (final var permit = budget.acquire()) {
                if (!channel.isOpen() || closed) {
                    return;
                }
                final var request = HttpRequest.newBuilder(upstream)
                        .timeout(deadline)
                        .header("Authorization", authorization)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
                permit.dispatched();
                final var sending = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
                pending.set(sending);
                if (!channel.isOpen()) {
                    sending.cancel(true);
                }
                final var received = sending.get(deadline.toNanos(), TimeUnit.NANOSECONDS);
                response.set(received.body());
                if (!channel.isOpen()) {
                    throw new IOException("Provider client disconnected");
                }
                final var out = UnixProviderProtocol.output(channel);
                out.writeInt(received.statusCode());
                final var contentType =
                        received.headers().firstValue("content-type").orElse("application/json");
                // No upstream headers, errors or redirects can introduce credentials or a new route.
                out.writeUTF(
                        contentType.toLowerCase(Locale.ROOT).startsWith("text/event-stream")
                                ? "text/event-stream"
                                : "application/json");
                long size = 0;
                final var bytes = new byte[UnixProviderProtocol.FRAME_SIZE];
                try (final var stream = received.body()) {
                    int count;
                    while ((count = stream.read(bytes)) >= 0) {
                        if (count == 0) {
                            continue;
                        }
                        size += count;
                        if (size > UnixProviderProtocol.MAX_RESPONSE) {
                            throw new IOException("Provider response limit");
                        }
                        out.writeInt(count);
                        out.write(bytes, 0, count);
                    }
                }
                if (!channel.isOpen() || closed) {
                    throw new IOException("Provider exchange termination uncertain");
                }
                permit.complete();
                out.writeInt(0);
            } finally {
                cancel.run();
                watcher.interrupt();
            }
        } catch (final Exception ignored) {
            // Capability, upstream secrets and body content must never enter diagnostics.
            // EOF/truncated frames are a transport failure, never a fabricated successful inference.
        } finally {
            timer.cancel(false);
            cancel.run();
        }
    }

    /** Bounded shutdown; failure leaves the durable socket identity for operator reconciliation. */
    @Override
    public void close() {
        closed = true;
        UnixProviderProtocol.close(listener);
        active.forEach(UnixProviderProtocol::close);
        final long end = System.nanoTime() + deadline.toNanos();
        try {
            acceptor.join(Duration.ofNanos(Math.max(1, end - System.nanoTime())));
            active.forEach(UnixProviderProtocol::close);
            for (final var worker : workers.toArray(CompletableFuture[]::new)) {
                worker.get(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
            }
            if (acceptor.isAlive() || !workers.isEmpty()) {
                throw new IllegalStateException("Native provider settlement unconfirmed; preserve private state");
            }
            if (Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS)) {
                final var current = Files.readAttributes(
                                socketPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                        .fileKey();
                if (socketKey == null || !socketKey.equals(current)) {
                    throw new IllegalStateException("Native provider socket identity changed; preserve private state");
                }
                Files.delete(socketPath);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Native provider settlement interrupted", e);
        } catch (final Exception e) {
            throw new IllegalStateException("Native provider settlement unconfirmed; preserve private state", e);
        }
    }
}
