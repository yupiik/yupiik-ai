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
package io.yupiik.llm.client.opencode;

import static org.junit.jupiter.api.Assertions.*;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OpenCodeTlsConnectionTest {
    private static KeyStore trustStore;
    private static SSLContext serverContext;

    @BeforeAll
    static void initializeTlsFixtures() throws Exception {
        trustStore = KeyStore.getInstance("PKCS12");
        try (final var in = OpenCodeTlsConnectionTest.class.getResourceAsStream("localhost.p12")) {
            assertNotNull(in);
            trustStore.load(in, "changeit".toCharArray());
        }
        final var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(trustStore, "changeit".toCharArray());
        serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keys.getKeyManagers(), null, null);
    }

    @Test
    void x25519AllThreeSuitesFragmentationAndProgressiveStreams() throws Exception {
        for (final var suite : OpenCodeCipherProfile.DEFAULT.subList(0, 3)) {
            try (final var server = new Server("TLSv1.3", suite, false);
                    final var socket = server.connect();
                    final var tls = OpenCodeTlsConnection.connect(socket, "localhost", store(), List.of(suite))) {
                // The server waits for a request after sending one byte: buffering until EOF would deadlock.
                assertEquals(42, tls.input().read());
                final var payload = new byte[50001];
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = (byte) i;
                }
                tls.output().write(payload);
                assertArrayEquals(payload, tls.input().readNBytes(payload.length));
                assertEquals(-1, tls.input().read());
                assertEquals(-1, tls.input().read());
                // JSSE waits for the peer's close_notify on close. This transport closes TCP
                // instead, so close it before joining the server rather than paying its read timeout.
                cancel(socket);
                server.result.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void defaultProfileAndIpSan() throws Exception {
        try (final var server = new Server("TLSv1.3", null, false);
                final var socket = server.connect();
                final var tls = OpenCodeTlsConnection.connect(socket, "127.0.0.1", store(), null)) {
            assertEquals(42, tls.input().read());
        }
    }

    @Test
    void rejectsUntrustedAndWrongHostWithoutApplicationData() throws Exception {
        for (final var host : List.of("localhost", "wrong.localhost", "127.0.0.2", "bad..name")) {
            try (final var server = new Server("TLSv1.3", null, true);
                    final var socket = server.connect()) {
                final var trust = host.equals("localhost") ? KeyStore.getInstance("PKCS12") : store();
                if (host.equals("localhost")) {
                    trust.load(null, null);
                }
                assertThrows(IOException.class, () -> OpenCodeTlsConnection.connect(socket, host, trust, null));
                assertTrue(socket.isClosed());
                server.result.get(10, TimeUnit.SECONDS);
                assertFalse(server.applicationReceived.get());
            }
        }
    }

    @Test
    void rejectsTls12BeforeApplicationData() throws Exception {
        try (final var server = new Server("TLSv1.2", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", true);
                final var socket = server.connect()) {
            assertThrows(IOException.class, () -> OpenCodeTlsConnection.connect(socket, "localhost", store(), null));
            assertTrue(socket.isClosed());
            server.result.get(10, TimeUnit.SECONDS);
            assertFalse(server.applicationReceived.get());
        }
    }

    @Test
    void rejectsInvalidCipherProfilesAndClosesOwnedSocket() throws Exception {
        for (final var suites : List.of(
                List.<String>of(),
                List.of("invalid"),
                List.of(OpenCodeCipherProfile.DEFAULT.get(3)),
                List.of(OpenCodeCipherProfile.DEFAULT.getFirst(), OpenCodeCipherProfile.DEFAULT.getFirst()))) {
            try (final var socket = new Socket()) {
                assertThrows(
                        IOException.class, () -> OpenCodeTlsConnection.connect(socket, "localhost", store(), suites));
                assertTrue(socket.isClosed());
            }
        }
    }

    @Test
    void rejectsTruncatedTcpAndTamperedAuthenticatedRecords() throws Exception {
        for (final boolean tamper : List.of(false, true)) {
            try (final var server = new Server("TLSv1.3", null, false);
                    final var socket = new FaultSocket(server.port());
                    final var tls = OpenCodeTlsConnection.connect(socket, "localhost", store(), null)) {
                // Fault injection starts at an encrypted record boundary after the verified handshake.
                socket.fault = tamper ? 1 : 2;
                assertThrows(IOException.class, () -> tls.input().read());
                assertTrue(socket.isClosed());
            }
        }
    }

    @Test
    void invalidPeerAndExternalCancellationFailClosed() throws Exception {
        try (final var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final var peer = CompletableFuture.runAsync(() -> {
                try (final var accepted = listener.accept()) {
                    accepted.getInputStream().read();
                    accepted.getOutputStream().write(new byte[] {22, 3, 3, 0, 4, 2, 127, -1, -1});
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            try (final var socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort())) {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(5000);
                assertThrows(
                        IOException.class, () -> OpenCodeTlsConnection.connect(socket, "localhost", store(), null));
                assertTrue(socket.isClosed());
            }
            peer.get(10, TimeUnit.SECONDS);
        }
        try (final var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                final var socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
                final var peer = listener.accept()) {
            final var handshake = CompletableFuture.runAsync(() -> assertThrows(
                    IOException.class, () -> OpenCodeTlsConnection.connect(socket, "localhost", store(), null)));
            assertTrue(peer.getInputStream().read() >= 0);
            cancel(socket);
            handshake.get(10, TimeUnit.SECONDS);
        }
    }

    private static void cancel(final Socket socket) throws IOException {
        socket.close();
    }

    private static KeyStore store() {
        return trustStore;
    }

    private static final class FaultSocket extends Socket {
        private volatile int fault;

        private FaultSocket(final int port) throws IOException {
            super(InetAddress.getLoopbackAddress(), port);
            setTcpNoDelay(true);
            setSoTimeout(5000);
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return new FilterInputStream(super.getInputStream()) {
                private int untilCorruption = 5; // Preserve the record header, corrupt the ciphertext.

                @Override
                public int read(final byte[] bytes, final int offset, final int length) throws IOException {
                    if (fault == 2) {
                        return -1;
                    }
                    final int count = super.read(bytes, offset, length);
                    if (fault == 1 && count > 0) {
                        if (untilCorruption < count) {
                            bytes[offset + untilCorruption] ^= 1;
                            fault = 0;
                        } else {
                            untilCorruption -= count;
                        }
                    }
                    return count;
                }
            };
        }
    }

    private static final class Server implements AutoCloseable {
        private final SSLServerSocket listener;
        private final CompletableFuture<Void> result;
        private final AtomicBoolean applicationReceived = new AtomicBoolean();

        private Server(final String protocol, final String suite, final boolean rejection) throws Exception {
            listener = (SSLServerSocket)
                    serverContext.getServerSocketFactory().createServerSocket(0, 1, InetAddress.getLoopbackAddress());
            final var parameters = listener.getSSLParameters();
            parameters.setProtocols(new String[] {protocol});
            parameters.setNamedGroups(new String[] {"X25519"});
            parameters.setApplicationProtocols(new String[] {"http/1.1"});
            parameters.setMaximumPacketSize(512); // Fragment handshake messages and application data.
            if (suite != null) {
                parameters.setCipherSuites(new String[] {suite});
            }
            listener.setSSLParameters(parameters);
            result = CompletableFuture.runAsync(() -> {
                try (final var peer = (SSLSocket) listener.accept()) {
                    peer.setTcpNoDelay(true);
                    peer.setSoTimeout(5000);
                    peer.startHandshake();
                    if (rejection) {
                        applicationReceived.set(peer.getInputStream().read() >= 0);
                        return;
                    }
                    peer.getOutputStream().write(42);
                    peer.getOutputStream().flush();
                    final var request = peer.getInputStream().readNBytes(50001);
                    if (request.length > 0) {
                        applicationReceived.set(true);
                        peer.getOutputStream().write(request);
                        peer.getOutputStream().flush();
                    }
                } catch (final IOException e) {
                    if (!rejection) {
                        // Some tests deliberately cancel or corrupt the stream after handshake.
                    }
                }
            });
        }

        private int port() {
            return listener.getLocalPort();
        }

        private Socket connect() throws IOException {
            final var socket = new Socket(InetAddress.getLoopbackAddress(), port());
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(5000);
            return socket;
        }

        @Override
        public void close() throws IOException {
            listener.close();
            try {
                result.get(10, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            } catch (final ExecutionException | TimeoutException e) {
                throw new IOException(e);
            }
        }
    }
}
