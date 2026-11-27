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
package io.yupiik.ia.agent.core.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.httpclient.core.listener.RequestListener;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

class OpenCodeTlsTest {
    @Test
    void tls12IsRejectedBeforeHttpRequest() throws Exception {
        exercise("TLSv1.2", false);
    }

    @Test
    void trustedTls13Succeeds() throws Exception {
        exercise("TLSv1.3", true);
    }

    @Test
    void untrustedTls13IsRejectedBeforeHttpRequest() throws Exception {
        exercise("TLSv1.3", false, false);
    }

    @Test
    void freshTls13ConnectionsWithSharedSessionContextSucceed() throws Exception {
        exercise("TLSv1.3", true, true, true);
    }

    @Test
    void defaultProfileCanBeRegisteredAndInvalidSuitesAreRejected() {
        try (final var transport = new OpenCodeHttpClient(HttpClient.newHttpClient())) {
            transport.profile(null);
            assertThrows(IllegalArgumentException.class, () -> transport.profile(List.of()));
            assertThrows(IllegalArgumentException.class, () -> transport.profile(List.of("invalid")));
        }
    }

    private void exercise(final String protocol, final boolean allowed) throws Exception {
        exercise(protocol, allowed, true);
    }

    private void exercise(final String protocol, final boolean allowed, final boolean trusted) throws Exception {
        exercise(protocol, allowed, trusted, false);
    }

    private void exercise(final String protocol, final boolean allowed, final boolean trusted, final boolean close)
            throws Exception {
        exercise(protocol, allowed, trusted, close, "localhost");
    }

    @Test
    void wrongHostnameIsRejectedBeforeHttpRequest() throws Exception {
        exercise("TLSv1.3", false, true, false, "127.0.0.2");
    }

    private void exercise(
            final String protocol,
            final boolean allowed,
            final boolean trusted,
            final boolean close,
            final String hostname)
            throws Exception {
        final var keyStore = KeyStore.getInstance("PKCS12");
        try (final var in =
                Files.newInputStream(Path.of("src/test/resources/io/yupiik/ia/agent/core/wiring/localhost.p12"))) {
            keyStore.load(in, "changeit".toCharArray());
        }
        final var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keyStore, "changeit".toCharArray());
        final var serverContext = SSLContext.getInstance("TLS");
        serverContext.init(managers.getKeyManagers(), null, null);
        final var server = HttpsServer.create(new InetSocketAddress(0), 0);
        final var received = new AtomicInteger();
        final var peers = ConcurrentHashMap.<InetSocketAddress>newKeySet();
        final var listenerCalls = new AtomicInteger();
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext) {
            @Override
            public void configure(final HttpsParameters parameters) {
                final var ssl = getSSLContext().getDefaultSSLParameters();
                ssl.setProtocols(new String[] {protocol});
                parameters.setSSLParameters(ssl);
            }
        });
        server.createContext("/", exchange -> {
            received.incrementAndGet();
            peers.add(exchange.getRemoteAddress());
            assertNull(exchange.getRequestHeaders().getFirst("x-yca-internal-opencode-transport"));
            if (close) {
                exchange.getResponseHeaders().set("Connection", "close");
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try (final var transport = new OpenCodeHttpClient(HttpClient.newHttpClient(), trusted ? keyStore : null);
                final var client = new ExtendedHttpClient(new ExtendedHttpClientConfiguration()
                        .setDelegate(transport)
                        .setRequestListeners(List.of(new RequestListener<Void>() {
                            @Override
                            public State<Void> before(final long count, final HttpRequest request) {
                                listenerCalls.incrementAndGet();
                                return new State<>(request, null);
                            }
                        })))) {
            final var builder = HttpRequest.newBuilder(
                    URI.create("https://" + hostname + ":" + server.getAddress().getPort() + "/"));
            transport
                    .profile(List.of("TLS_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"))
                    .accept(builder);
            final var request = builder.build();
            if (allowed) {
                assertEquals(
                        200,
                        client.send(request, HttpResponse.BodyHandlers.discarding())
                                .statusCode());
                assertEquals(
                        200,
                        client.send(request, HttpResponse.BodyHandlers.discarding())
                                .statusCode());
                assertEquals(2, received.get());
                assertEquals(2, listenerCalls.get(), "one shared listener callback per request");
                // The current socket implementation opens a separate connection per request.
                // This assertion does not establish equivalent pooling or session behavior in Bun.
                assertEquals(2, peers.size(), "dedicated connection per request");
            } else {
                assertThrows(Exception.class, () -> client.send(request, HttpResponse.BodyHandlers.discarding()));
                assertEquals(0, received.get());
            }
        } finally {
            server.stop(0);
        }
    }
}
