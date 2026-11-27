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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.Test;

class OpenCodeHttpClientTest {
    @Test
    void writesOrderedHeadersAndUtf8ContentLength() throws Exception {
        try (final var fixture = new Fixture();
                final var client = fixture.client()) {
            final var server = fixture.serve(socket -> {
                final var headers = readRequest(socket);
                assertTrue(headers.startsWith("POST / HTTP/1.1\r\nAuthorization: Bearer public\r\n"
                        + "Content-Type: application/json\r\nUser-Agent: test-client\r\n"
                        + "x-opencode-client: test\r\nx-opencode-project: project\r\n"
                        + "x-opencode-session: session\r\nx-opencode-session-id: session\r\n"
                        + "x-session-affinity: session\r\nx-session-id: session\r\n"
                        + "Connection: keep-alive\r\nAccept: */*\r\nHost: localhost:"));
                assertTrue(headers.endsWith("\r\nAccept-Encoding: gzip, deflate\r\nContent-Length: 2\r\n\r\n"));
                assertArrayEquals(
                        new byte[] {(byte) 0xc3, (byte) 0xa9},
                        socket.getInputStream().readNBytes(2));
                socket.getOutputStream()
                        .write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            });
            final var request = HttpRequest.newBuilder(fixture.request(Duration.ofSeconds(10)), (name, value) -> true)
                    .header("x-session-id", "session")
                    .header("x-session-affinity", "session")
                    .header("x-opencode-session-id", "session")
                    .header("x-opencode-session", "session")
                    .header("x-opencode-project", "project")
                    .header("x-opencode-client", "test")
                    .header("user-agent", "test-client")
                    .header("content-type", "application/json")
                    .header("authorization", "Bearer public")
                    .POST(HttpRequest.BodyPublishers.ofString("\u00e9"))
                    .build();
            assertEquals(
                    200,
                    client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
            server.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void publishesBeforeResponseCompletesAndHonorsDemand() throws Exception {
        try (final var fixture = new Fixture();
                final var client = fixture.client()) {
            final var first = new CountDownLatch(1);
            final var continueResponse = new CountDownLatch(1);
            final var server = fixture.serve(socket -> {
                readRequest(socket);
                socket.getOutputStream()
                        .write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\none\r\n"
                                .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                assertTrue(continueResponse.await(5, TimeUnit.SECONDS));
                socket.getOutputStream().write("3\r\ntwo\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            });
            final var response = client.sendAsync(
                            fixture.request(Duration.ofSeconds(10)), HttpResponse.BodyHandlers.ofPublisher())
                    .get(5, TimeUnit.SECONDS);
            final var subscription = new AtomicReference<Flow.Subscription>();
            final var result = new CompletableFuture<String>();
            final var bytes = new StringBuilder();
            response.body().subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(final Flow.Subscription value) {
                    subscription.set(value);
                    value.request(1);
                }

                @Override
                public void onNext(final List<ByteBuffer> items) {
                    for (final var item : items) {
                        bytes.append(StandardCharsets.UTF_8.decode(item));
                    }
                    first.countDown();
                }

                @Override
                public void onError(final Throwable error) {
                    result.completeExceptionally(error);
                }

                @Override
                public void onComplete() {
                    result.complete(bytes.toString());
                }
            });
            assertTrue(first.await(5, TimeUnit.SECONDS));
            assertEquals("one", bytes.toString());
            assertFalse(result.isDone());
            continueResponse.countDown();
            subscription.get().request(Long.MAX_VALUE);
            assertEquals("onetwo", result.get(5, TimeUnit.SECONDS));
            server.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void requestDeadlineClosesStalledConnection() throws Exception {
        try (final var fixture = new Fixture();
                final var client = fixture.client()) {
            final var server = fixture.serve(socket -> {
                readRequest(socket);
                assertEquals(-1, socket.getInputStream().read());
            });
            final var error = assertThrows(
                    ExecutionException.class,
                    () -> client.sendAsync(
                                    fixture.request(Duration.ofMillis(800)), HttpResponse.BodyHandlers.ofString())
                            .get(5, TimeUnit.SECONDS));
            assertInstanceOf(HttpTimeoutException.class, error.getCause());
            server.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void cancellationAndClientCloseReleaseConnections() throws Exception {
        try (final var fixture = new Fixture();
                final var client = fixture.client()) {
            final var connected = new CountDownLatch(1);
            final var server = fixture.serve(socket -> {
                readRequest(socket);
                socket.getOutputStream()
                        .write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                                .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                connected.countDown();
                assertEquals(-1, socket.getInputStream().read());
            });
            final var response = client.sendAsync(
                            fixture.request(Duration.ofSeconds(10)), HttpResponse.BodyHandlers.ofInputStream())
                    .get(5, TimeUnit.SECONDS);
            assertTrue(connected.await(5, TimeUnit.SECONDS));
            response.body().close();
            server.get(5, TimeUnit.SECONDS);
            client.shutdown();
            assertThrows(
                    ExecutionException.class,
                    () -> client.sendAsync(fixture.request(Duration.ofSeconds(1)), HttpResponse.BodyHandlers.ofString())
                            .get());
        }
    }

    private static String readRequest(final SSLSocket socket) throws IOException {
        final var bytes = new ByteArrayOutputStream();
        while (bytes.size() < 65536) {
            final int value = socket.getInputStream().read();
            if (value < 0) {
                throw new IOException("Request truncated");
            }
            bytes.write(value);
            if (bytes.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
                return bytes.toString(StandardCharsets.US_ASCII);
            }
        }
        throw new IOException("Request too large");
    }

    private interface ServerAction {
        void run(SSLSocket socket) throws Exception;
    }

    private static final class Fixture implements AutoCloseable {
        private final KeyStore store;
        private final SSLServerSocket listener;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        private Fixture() throws Exception {
            store = KeyStore.getInstance("PKCS12");
            try (final var input = OpenCodeHttpClientTest.class.getResourceAsStream("localhost.p12")) {
                store.load(input, "changeit".toCharArray());
            }
            final var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(store, "changeit".toCharArray());
            final var context = SSLContext.getInstance("TLS");
            context.init(kmf.getKeyManagers(), null, null);
            listener = (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
            listener.bind(new InetSocketAddress("localhost", 0));
            listener.setEnabledProtocols(new String[] {"TLSv1.3"});
            listener.setSoTimeout(5000);
        }

        private CompletableFuture<Void> serve(final ServerAction action) {
            return CompletableFuture.runAsync(
                    () -> {
                        try (final var socket = (SSLSocket) listener.accept()) {
                            socket.setSoTimeout(5000);
                            action.run(socket);
                        } catch (final Exception error) {
                            throw new CompletionException(error);
                        }
                    },
                    executor);
        }

        private OpenCodeHttpClient client() {
            return new OpenCodeHttpClient(Duration.ofSeconds(2), store, null, null);
        }

        private HttpRequest request(final Duration timeout) {
            return HttpRequest.newBuilder(URI.create("https://localhost:" + listener.getLocalPort() + "/"))
                    .timeout(timeout)
                    .GET()
                    .build();
        }

        @Override
        public void close() throws IOException {
            listener.close();
            executor.shutdownNow();
        }
    }
}
