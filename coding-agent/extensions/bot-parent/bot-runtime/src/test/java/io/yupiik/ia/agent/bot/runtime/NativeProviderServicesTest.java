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

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.httpclient.core.DelegatingHttpClient;
import io.yupiik.ia.agent.core.llm.ApiFlavor;
import io.yupiik.ia.agent.core.llm.UnixProviderHttpClient;
import io.yupiik.ia.agent.core.llm.UnixProviderProtocol;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class NativeProviderServicesTest {
    private static final String CAPABILITY = "attempt-capability-0123456789abcdef";
    private static final URI UPSTREAM = URI.create("https://provider.invalid/v1/chat/completions");

    @ParameterizedTest
    @EnumSource(
            value = ApiFlavor.class,
            names = {"COMPLETIONS", "RESPONSES"})
    void fixedRouteStreamingSaturationAndBudgetLifetime(final ApiFlavor api) throws Exception {
        final var endpoint = URI.create(
                "https://provider.invalid" + UnixProviderProtocol.endpoint(api).getPath());
        final var directory = privateDirectory();
        final var socket = directory.resolve("p");
        final var calls = new AtomicInteger();
        final var acquired = new AtomicInteger();
        final var completed = new AtomicInteger();
        final var released = new CompletableFuture<Void>();
        final var stream = new GateStream();
        try (final var jdk = new NoNetworkClient(HttpClient.Redirect.NEVER);
                final var transport = new UnixProviderHttpClient(jdk, socket, api)) {
            final var upstream = new DelegatingHttpClient(jdk) {
                @Override
                @SuppressWarnings("unchecked")
                public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                        final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
                    calls.incrementAndGet();
                    assertEquals(endpoint, request.uri());
                    assertEquals(
                            "Bearer upstream-only-secret",
                            request.headers().firstValue("Authorization").orElseThrow());
                    assertFalse(request.headers().toString().contains(CAPABILITY));
                    assertEquals(
                            List.of("application/json, text/event-stream"),
                            request.headers().allValues("Accept"));
                    return CompletableFuture.completedFuture((HttpResponse<T>) new Response(request, stream));
                }
            };
            try (final var bridge = new NativeProviderServices(
                    socket,
                    upstream,
                    endpoint,
                    api,
                    "Bearer upstream-only-secret",
                    CAPABILITY,
                    Duration.ofMillis(400),
                    1,
                    () -> {
                        acquired.incrementAndGet();
                        return new NativeProviderServices.Reservation() {
                            public void dispatched() {
                                assertEquals(0, calls.get());
                            }

                            public void complete() {
                                completed.incrementAndGet();
                            }

                            public void close() {
                                released.complete(null);
                            }
                        };
                    })) {
                assertEquals(socket, bridge.socketPath());
                final var request = HttpRequest.newBuilder(UnixProviderProtocol.endpoint(api))
                        .header("Authorization", "Bearer " + CAPABILITY)
                        .header("X-Untrusted-Destination", "https://other.invalid")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build();
                final var response = transport.send(request, HttpResponse.BodyHandlers.ofInputStream());
                assertEquals(200, response.statusCode());
                assertEquals(
                        "text/event-stream",
                        response.headers().firstValue("content-type").orElseThrow());
                assertEquals('x', response.body().read());
                assertEquals(1, acquired.get());
                assertEquals(0, completed.get());
                assertFalse(released.isDone(), "permit must span streaming lifetime");
                assertThrows(IOException.class, () -> transport.send(request, HttpResponse.BodyHandlers.ofString()));
                assertEquals(1, acquired.get(), "local saturation must not acquire shared budget");
                stream.finish.complete(null);
                try (final var input = response.body()) {
                    assertEquals(-1, input.read());
                }
                released.get(1, TimeUnit.SECONDS);
                assertEquals(1, completed.get());
            }
            assertFalse(Files.exists(socket));
        } finally {
            stream.close();
            Files.deleteIfExists(socket);
            Files.delete(directory);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ApiFlavor.class,
            names = {"COMPLETIONS", "RESPONSES"})
    void rejectsInvalidAuthenticationSizeAndApiWithoutAcquiringBudget(final ApiFlavor api) throws Exception {
        final var directory = privateDirectory();
        final var socket = directory.resolve("p");
        final var acquired = new AtomicInteger();
        try (final var upstream = new NoNetworkClient(HttpClient.Redirect.NEVER)) {
            for (final int mode : new int[] {0, 1, 2}) {
                // Peer EOF precedes worker cleanup and slot release. Isolate each rejection so
                // the next connection cannot be rejected by saturation instead of validation.
                try (final var bridge = new NativeProviderServices(
                        socket,
                        upstream,
                        URI.create("https://provider.invalid"
                                + UnixProviderProtocol.endpoint(api).getPath()),
                        api,
                        "Bearer upstream-only-secret",
                        CAPABILITY,
                        Duration.ofMillis(400),
                        1,
                        () -> {
                            acquired.incrementAndGet();
                            throw new IllegalStateException("Invalid request must not acquire budget");
                        })) {
                    try (final var channel = SocketChannel.open(UnixDomainSocketAddress.of(bridge.socketPath()))) {
                        final var out = UnixProviderProtocol.output(channel);
                        out.writeInt(UnixProviderProtocol.MAGIC);
                        out.writeUTF(mode == 0 ? "invalid" : CAPABILITY);
                        if (mode != 0) {
                            out.writeUTF(
                                    mode == 2
                                            ? (api == ApiFlavor.RESPONSES ? "COMPLETIONS" : "RESPONSES")
                                            : api.name());
                        }
                        if (mode == 1) {
                            out.writeInt(UnixProviderProtocol.MAX_REQUEST + 1);
                        }
                        assertEquals(-1, UnixProviderProtocol.input(channel).read());
                    }
                }
                assertEquals(0, acquired.get());
                assertFalse(Files.exists(socket));
            }
        } finally {
            Files.deleteIfExists(socket);
            Files.delete(directory);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void disconnectAndDeadlineCancelStreamingRetainingUncertainDispatch(final boolean disconnect) throws Exception {
        final var directory = privateDirectory();
        final var socket = directory.resolve("p");
        try (final var jdk = new NoNetworkClient(HttpClient.Redirect.NEVER);
                final var transport = new UnixProviderHttpClient(jdk, socket, ApiFlavor.COMPLETIONS)) {
            final var stream = new GateStream();
            final var release = new CompletableFuture<Void>();
            final var complete = new AtomicInteger();
            final var upstream = new DelegatingHttpClient(jdk) {
                @Override
                @SuppressWarnings("unchecked")
                public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                        final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
                    return CompletableFuture.completedFuture((HttpResponse<T>) new Response(request, stream));
                }
            };
            try (final var bridge = new NativeProviderServices(
                    socket,
                    upstream,
                    UPSTREAM,
                    ApiFlavor.COMPLETIONS,
                    "Bearer secret",
                    CAPABILITY,
                    // Leave enough time for socket setup and the first frame under reactor load.
                    // The deadline case still blocks on the next frame until cancellation occurs.
                    Duration.ofSeconds(disconnect ? 30 : 2),
                    1,
                    () -> new NativeProviderServices.Reservation() {
                        public void dispatched() {}

                        public void complete() {
                            complete.incrementAndGet();
                        }

                        public void close() {
                            release.complete(null);
                        }
                    })) {
                assertEquals(socket, bridge.socketPath());
                final var response = transport.send(request(), HttpResponse.BodyHandlers.ofInputStream());
                assertEquals('x', response.body().read());
                if (disconnect) {
                    response.body().close();
                } else {
                    assertThrows(IOException.class, () -> response.body().read());
                    response.body().close();
                }
                release.get(1, TimeUnit.SECONDS);
                assertEquals(0, complete.get(), "local cancel is not evidence of upstream termination");
                assertTrue(stream.closed);
            } finally {
                stream.close();
            }
        } finally {
            Files.deleteIfExists(socket);
            Files.delete(directory);
        }
    }

    @Test
    void rejectsUnsafeParentExistingSocketAndRedirectingClient() throws Exception {
        final var directory = privateDirectory();
        final var socket = directory.resolve("p");
        final NativeProviderServices.Budget budget = () -> {
            throw new AssertionError("must not acquire");
        };
        try (final var client = new NoNetworkClient(HttpClient.Redirect.NEVER)) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new NativeProviderServices(
                            socket,
                            client,
                            UPSTREAM,
                            ApiFlavor.COMPLETIONS,
                            "Bearer secret",
                            CAPABILITY,
                            Duration.ofMillis(100),
                            1,
                            budget));
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            Files.writeString(socket, "do-not-adopt");
            assertThrows(
                    IOException.class,
                    () -> new NativeProviderServices(
                            socket,
                            client,
                            UPSTREAM,
                            ApiFlavor.COMPLETIONS,
                            "Bearer secret",
                            CAPABILITY,
                            Duration.ofMillis(100),
                            1,
                            budget));
            assertEquals("do-not-adopt", Files.readString(socket));
            try (final var redirect = new NoNetworkClient(HttpClient.Redirect.ALWAYS)) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new NativeProviderServices(
                                socket,
                                redirect,
                                UPSTREAM,
                                ApiFlavor.COMPLETIONS,
                                "Bearer secret",
                                CAPABILITY,
                                Duration.ofMillis(100),
                                1,
                                budget));
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new NativeProviderServices(
                            socket,
                            client,
                            URI.create("http://provider.invalid/v1/chat/completions"),
                            ApiFlavor.COMPLETIONS,
                            "Bearer secret",
                            CAPABILITY,
                            Duration.ofMillis(100),
                            1,
                            budget));
        } finally {
            Files.deleteIfExists(socket);
            Files.delete(directory);
        }
    }

    @Test
    void rejectsMismatchedUnsupportedAndNoncanonicalPinnedUpstreams() throws Exception {
        final var directory = privateDirectory();
        try (final var http = new NoNetworkClient(HttpClient.Redirect.NEVER)) {
            for (final var suffix : List.of(
                    "/chat/completions",
                    "/messages",
                    "/codex/responses",
                    "/responses/compact",
                    "/responses/id",
                    "/responses/",
                    "/%72esponses",
                    "/../v1/responses",
                    "/responses?target=other",
                    "/responses#fragment")) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new NativeProviderServices(
                                directory.resolve("p"),
                                http,
                                URI.create("https://provider.invalid/v1" + suffix),
                                ApiFlavor.RESPONSES,
                                "Bearer secret",
                                CAPABILITY,
                                Duration.ofMillis(100),
                                1,
                                () -> {
                                    throw new AssertionError("Invalid pin must not acquire budget");
                                }));
                assertFalse(Files.exists(directory.resolve("p")));
            }
            for (final var api :
                    List.of(ApiFlavor.AUTO, ApiFlavor.ANTHROPIC, ApiFlavor.CODEX, ApiFlavor.OPENCODE_FREE)) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new NativeProviderServices(
                                directory.resolve("p"),
                                http,
                                UPSTREAM,
                                api,
                                "Bearer secret",
                                CAPABILITY,
                                Duration.ofMillis(100),
                                1,
                                () -> {
                                    throw new AssertionError("Unsupported pin must not acquire budget");
                                }));
            }
        } finally {
            Files.delete(directory);
        }
    }

    private Path privateDirectory() throws IOException {
        return Files.createTempDirectory(
                "yca-native-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    private HttpRequest request() {
        return HttpRequest.newBuilder(UnixProviderProtocol.ENDPOINT)
                .header("Authorization", "Bearer " + CAPABILITY)
                .header("X-Untrusted-Destination", "https://other.invalid")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
    }

    /** Constructor validation must not initialize a real HTTP/TLS transport or dispatch requests. */
    private static final class NoNetworkClient extends HttpClient {
        private final Redirect redirects;

        private NoNetworkClient(final Redirect redirects) {
            this.redirects = redirects;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return redirects;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            throw new AssertionError("Unexpected TLS initialization");
        }

        @Override
        public SSLParameters sslParameters() {
            throw new AssertionError("Unexpected TLS initialization");
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
            throw new AssertionError("Unexpected network dispatch");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
            throw new AssertionError("Unexpected network dispatch");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                final HttpRequest request,
                final HttpResponse.BodyHandler<T> handler,
                final HttpResponse.PushPromiseHandler<T> push) {
            throw new AssertionError("Unexpected network dispatch");
        }
    }

    private static final class GateStream extends InputStream {
        private final CompletableFuture<Void> finish = new CompletableFuture<>();
        private boolean first = true;
        private volatile boolean closed;

        @Override
        public int read(final byte[] bytes, final int offset, final int length) throws IOException {
            if (first) {
                first = false;
                bytes[offset] = 'x';
                return 1;
            }
            try {
                finish.get(1, TimeUnit.SECONDS);
            } catch (final Exception e) {
                throw new IOException(e);
            }
            if (closed) {
                throw new IOException("closed");
            }
            return -1;
        }

        @Override
        public int read() throws IOException {
            final var bytes = new byte[1];
            return read(bytes, 0, 1) < 0 ? -1 : bytes[0];
        }

        @Override
        public void close() {
            closed = true;
            finish.complete(null);
        }
    }

    private record Response(HttpRequest request, InputStream body) implements HttpResponse<InputStream> {
        public int statusCode() {
            return 200;
        }

        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of("content-type", List.of("text/event-stream")), (a, b) -> true);
        }

        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        public URI uri() {
            return request.uri();
        }

        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
