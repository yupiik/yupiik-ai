/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.sandbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.permissions.PermissionService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxHttpTransportTest {
    @TempDir
    Path root;

    @Test
    void connectsToTheValidatedConcreteAddressWithoutSecondResolution() throws Exception {
        final var policy = new Policy(root, List.of("first.test"));
        final var socket = new Wire("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello");
        final var resolutions = new AtomicInteger();
        try (final var transport = new Fixture(policy, () -> false, 100, List.of(socket)) {
            @Override
            protected InetAddress[] resolve(final String host) throws IOException {
                // A second resolution would rebind to localhost.
                return new InetAddress[] {
                    resolutions.incrementAndGet() == 1 ? publicAddress() : InetAddress.getLoopbackAddress()
                };
            }
        }) {
            final var response =
                    transport.send(get("http://first.test/path?q=1"), HttpResponse.BodyHandlers.ofString());
            assertEquals("hello", response.body());
            assertEquals(1, resolutions.get());
            assertFalse(socket.destination.isUnresolved());
            assertArrayEquals(
                    publicAddress().getAddress(),
                    socket.destination.getAddress().getAddress());
            assertEquals(80, socket.destination.getPort());
            assertTrue(socket.request().startsWith("GET /path?q=1 HTTP/1.1\r\nHost: first.test:80\r\n"));
            assertEquals(List.of("first.test"), policy.checks);
            assertTrue(socket.closed);
        }
    }

    @Test
    void everyDnsAnswerMustBePublicEvenWithAnExplicitGrant() throws Exception {
        final var policy = new Policy(root, List.of("first.test"));
        for (final var address :
                List.of("127.0.0.1", "10.1.2.3", "169.254.169.254", "100.64.0.1", "192.0.2.1", "::1", "fc00::1")) {
            final var socket = new Wire("");
            try (final var transport = new Fixture(policy, () -> false, 100, List.of(socket)) {
                @Override
                protected InetAddress[] resolve(final String host) throws IOException {
                    return new InetAddress[] {publicAddress(), InetAddress.getByName(address)};
                }
            }) {
                assertThrows(
                        SecurityException.class,
                        () -> transport.send(get("http://first.test/"), HttpResponse.BodyHandlers.ofString()));
                assertEquals(null, socket.destination);
            }
        }
    }

    @Test
    void policyDenialIsNeverConvertedToAnApprovalRequestOrConnection() {
        final var policy = new Policy(root, List.of());
        final var socket = new Wire("");
        try (final var transport = new Fixture(policy, () -> false, 100, List.of(socket))) {
            assertThrows(
                    SecurityException.class,
                    () -> transport.send(get("http://first.test/"), HttpResponse.BodyHandlers.ofString()));
            assertEquals(null, socket.destination);
            assertEquals(List.of("first.test"), policy.checks);
        }
    }

    @Test
    void redirectsRecheckPolicyBeforeOpeningAnotherSocket() {
        final var policy = new Policy(root, List.of("first.test"));
        final var first = redirect("http://denied.test/private", 302);
        final var second = new Wire("");
        try (final var transport = new Fixture(policy, () -> false, 100, List.of(first, second))) {
            assertThrows(
                    SecurityException.class,
                    () -> transport.send(get("http://first.test/"), HttpResponse.BodyHandlers.ofString()));
            assertEquals(List.of("first.test", "denied.test"), policy.checks);
            assertEquals(null, second.destination);
            assertTrue(first.closed);
        }
    }

    @Test
    void redirectsRecheckDnsEvenForTheSameOrigin() {
        final var policy = new Policy(root, List.of("first.test"));
        final var first = redirect("/next", 307);
        final var second = new Wire("");
        final var resolutions = new AtomicInteger();
        try (final var transport = new Fixture(policy, () -> false, 100, List.of(first, second)) {
            @Override
            protected InetAddress[] resolve(final String host) throws IOException {
                return new InetAddress[] {
                    resolutions.incrementAndGet() == 1 ? publicAddress() : InetAddress.getLoopbackAddress()
                };
            }
        }) {
            assertThrows(
                    SecurityException.class,
                    () -> transport.send(get("http://first.test/"), HttpResponse.BodyHandlers.ofString()));
            assertEquals(2, resolutions.get());
            assertEquals(null, second.destination);
        }
    }

    @Test
    void crossOriginGetRedirectStripsAllCallerHeaders() throws Exception {
        final var policy = new Policy(root, List.of("first.test", "second.test"));
        final var first = redirect("http://second.test/final", 302);
        final var second = new Wire("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok");
        try (final var transport = new Fixture(policy, () -> false, 100, List.of(first, second))) {
            final var request = HttpRequest.newBuilder(URI.create("http://first.test/"))
                    .header("Authorization", "Bearer secret")
                    .header("Mcp-Session-Id", "private-session")
                    .GET()
                    .build();
            final var response = transport.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals("ok", response.body());
            assertEquals(URI.create("http://second.test/final"), response.uri());
            assertTrue(first.request().contains("Bearer secret"));
            assertFalse(second.request().contains("secret"));
            assertFalse(second.request().contains("private-session"));
            assertTrue(second.request().contains("Host: second.test:80"));
        }
    }

    @Test
    void crossOriginRedirectCannotReplayPostBody() {
        final var policy = new Policy(root, List.of("first.test", "second.test"));
        final var first = redirect("http://second.test/", 307);
        final var second = new Wire("");
        try (final var transport = new Fixture(policy, () -> false, 100, List.of(first, second))) {
            final var request = HttpRequest.newBuilder(URI.create("http://first.test/"))
                    .POST(HttpRequest.BodyPublishers.ofString("secret query"))
                    .build();
            assertThrows(SecurityException.class, () -> transport.send(request, HttpResponse.BodyHandlers.ofString()));
            assertEquals(null, second.destination);
        }
    }

    @Test
    void sameOrigin307PreservesPostAndHeaders() throws Exception {
        final var policy = new Policy(root, List.of("first.test"));
        final var first = redirect("/next", 307);
        final var second = new Wire("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nok\r\n0\r\n\r\n");
        try (final var transport = new Fixture(policy, () -> false, 100, List.of(first, second))) {
            final var request = HttpRequest.newBuilder(URI.create("http://first.test/"))
                    .header("Mcp-Session-Id", "session")
                    .POST(HttpRequest.BodyPublishers.ofString("query"))
                    .build();
            assertEquals(
                    "ok",
                    transport
                            .send(request, HttpResponse.BodyHandlers.ofString())
                            .body());
            assertTrue(second.request().startsWith("POST /next HTTP/1.1"));
            assertTrue(second.request().contains("Mcp-Session-Id: session"));
            assertTrue(second.request().endsWith("\r\n\r\nquery"));
        }
    }

    @Test
    void responseBodiesAreBoundedAndFramingIsValidated() {
        for (final var response : List.of(
                "HTTP/1.1 200 OK\r\nContent-Length: 101\r\n\r\n",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n65\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nx",
                "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nx",
                "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n",
                "HTTP/1.1 200 OK\n\n")) {
            final var socket = new Wire(response);
            try (final var transport =
                    new Fixture(new Policy(root, List.of("first.test")), () -> false, 100, List.of(socket))) {
                assertThrows(
                        IOException.class,
                        () -> transport.send(get("http://first.test/"), HttpResponse.BodyHandlers.ofByteArray()));
                assertTrue(socket.closed);
            }
        }
    }

    @Test
    void responseLimitFailuresExplainTheConfiguredLimitForEveryFraming() {
        for (final var response : List.of(
                "HTTP/1.1 200 OK\r\nContent-Length: 101\r\n\r\n" + "x".repeat(101),
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n32\r\n" + "x".repeat(50) + "\r\n33\r\n"
                        + "x".repeat(51) + "\r\n0\r\n\r\n",
                "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n" + "x".repeat(101))) {
            final var socket = new Wire(response);
            try (final var transport =
                    new Fixture(new Policy(root, List.of("first.test")), () -> false, 100, List.of(socket))) {
                final var error = assertThrows(
                        IOException.class,
                        () -> transport.send(get("http://first.test/"), HttpResponse.BodyHandlers.ofByteArray()));
                assertTrue(error.getMessage().contains("exceeds limit of 100 bytes"), error::getMessage);
                assertTrue(socket.closed);
            }
        }
    }

    @Test
    void supportsStreamingBodiesWithBackpressure() throws Exception {
        final var content = "x".repeat(90_000);
        final var socket = new Wire("HTTP/1.1 200 OK\r\nContent-Length: 90000\r\n\r\n" + content);
        try (final var transport =
                new Fixture(new Policy(root, List.of("first.test")), () -> false, 100_000, List.of(socket))) {
            final var response = transport.send(get("http://first.test/"), HttpResponse.BodyHandlers.ofInputStream());
            try (final var body = response.body()) {
                assertEquals(content, new String(body.readAllBytes(), StandardCharsets.US_ASCII));
            }
        }
        assertTrue(socket.closed);
    }

    @Test
    void cancellationBeforeConnectDoesNotResolveOrSend() {
        final var socket = new Wire("");
        try (final var transport =
                new Fixture(new Policy(root, List.of("first.test")), () -> true, 100, List.of(socket))) {
            assertThrows(
                    IOException.class,
                    () -> transport.send(get("http://first.test/"), HttpResponse.BodyHandlers.ofString()));
            assertEquals(null, socket.destination);
            assertEquals("", socket.request());
        }
    }

    @Test
    void cancellationInterruptsBlockedNetworkPolicyBeforeConnect() throws Exception {
        for (final var cancelFuture : List.of(true, false)) {
            final var policy = new BlockingPolicy(root);
            final var socket = new Wire("");
            final var cancelled = new AtomicBoolean();
            try (final var transport = new Fixture(policy, cancelled::get, 100, List.of(socket))) {
                try {
                    final var future =
                            transport.sendAsync(get("http://first.test/"), HttpResponse.BodyHandlers.ofString());
                    assertTrue(policy.entered.await(5, TimeUnit.SECONDS), "Network policy was not entered");
                    if (cancelFuture) {
                        assertTrue(future.cancel(true));
                        assertThrows(CancellationException.class, () -> future.get(5, TimeUnit.SECONDS));
                    } else {
                        cancelled.set(true);
                        final var error = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
                        assertTrue(error.getCause() instanceof IOException);
                    }
                    assertTrue(
                            policy.interrupted.await(5, TimeUnit.SECONDS),
                            "Blocked network policy was not interrupted");
                    assertEquals(null, socket.destination);
                    assertEquals("", socket.request());
                } finally {
                    policy.release.countDown();
                }
            }
        }
    }

    @Test
    void timeoutInterruptsBlockedNetworkPolicyBeforeConnect() throws Exception {
        final var policy = new BlockingPolicy(root);
        final var socket = new Wire("");
        try (final var transport = new Fixture(policy, () -> false, 100, List.of(socket))) {
            try {
                final var request = HttpRequest.newBuilder(URI.create("http://first.test/"))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                final var future = transport.sendAsync(request, HttpResponse.BodyHandlers.ofString());
                assertTrue(policy.entered.await(5, TimeUnit.SECONDS), "Network policy was not entered");
                final var error = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
                assertTrue(error.getCause() instanceof IOException);
                assertTrue(error.getCause().getMessage().contains("timed out"));
                assertTrue(policy.interrupted.await(5, TimeUnit.SECONDS), "Blocked network policy was not interrupted");
                assertEquals(null, socket.destination);
                assertEquals("", socket.request());
            } finally {
                policy.release.countDown();
            }
        }
    }

    @Test
    void rejectsUserInfoAndNonHttpRedirects() {
        for (final var target : List.of("http://user:pass@second.test/", "file:///etc/passwd")) {
            final var first = redirect(target, 302);
            try (final var transport =
                    new Fixture(new Policy(root, List.of("first.test")), () -> false, 100, List.of(first))) {
                assertThrows(
                        SecurityException.class,
                        () -> transport.send(get("http://first.test/"), HttpResponse.BodyHandlers.ofString()));
            }
        }
    }

    private static HttpRequest get(final String uri) {
        return HttpRequest.newBuilder(URI.create(uri)).GET().build();
    }

    private static InetAddress publicAddress() throws IOException {
        return InetAddress.getByAddress(new byte[] {8, 8, 8, 8});
    }

    private static Wire redirect(final String location, final int status) {
        return new Wire("HTTP/1.1 " + status + " Redirect\r\nLocation: " + location + "\r\nContent-Length: 0\r\n\r\n");
    }

    private static class Fixture extends SandboxHttpTransport {
        private final Queue<Wire> wires;

        private Fixture(
                final Sandbox policy, final BooleanSupplier cancelled, final int limit, final List<Wire> wires) {
            super(policy, cancelled, limit);
            this.wires = new ArrayDeque<>(wires);
        }

        @Override
        protected InetAddress[] resolve(final String host) throws IOException {
            return new InetAddress[] {publicAddress()};
        }

        @Override
        protected Socket newSocket() {
            return wires.remove();
        }
    }

    private static final class Wire extends Socket {
        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private InetSocketAddress destination;
        private volatile boolean closed;

        private Wire(final String response) {
            input = new ByteArrayInputStream(response.getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public void connect(final SocketAddress endpoint, final int timeout) {
            destination = (InetSocketAddress) endpoint;
        }

        @Override
        public void setSoTimeout(final int timeout) {
            /* in-memory peer */
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public OutputStream getOutputStream() {
            return output;
        }

        @Override
        public void close() {
            closed = true;
        }

        private String request() {
            return output.toString(StandardCharsets.US_ASCII);
        }
    }

    private static class Policy implements Sandbox {
        private final Path tempDir;
        private final List<String> allowed;
        private final List<String> checks = new ArrayList<>();

        private Policy(final Path root, final List<String> allowed) {
            this.tempDir = root;
            this.allowed = allowed;
        }

        @Override
        public Path tempDir() {
            return tempDir;
        }

        @Override
        public Sandbox approvalView(final PermissionService permissions) {
            return this;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public void requireRead(final Path path) {
            throw new AssertionError();
        }

        @Override
        public void requireWrite(final Path path) {
            throw new AssertionError();
        }

        @Override
        public void requireNetwork(final URI uri) {
            checks.add(uri.getHost());
            if (!allowed.contains(uri.getHost())) {
                throw new SecurityException("Sandbox network access not granted; request with sandbox_access");
            }
        }

        @Override
        public void request(final String kind, final String target, final boolean write) {
            throw new AssertionError("No automatic grants");
        }

        @Override
        public Command launch(final ProcessBuilder builder) {
            throw new AssertionError();
        }

        @Override
        public void revoke() {
            /* no session processes */
        }

        @Override
        public void close() {
            /* no session resources */
        }
    }

    private static final class BlockingPolicy extends Policy {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingPolicy(final Path root) {
            super(root, List.of("first.test"));
        }

        @Override
        public void requireNetwork(final URI uri) {
            super.requireNetwork(uri);
            entered.countDown();
            try {
                release.await();
            } catch (final InterruptedException error) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Network policy interrupted", error);
            }
        }
    }
}
