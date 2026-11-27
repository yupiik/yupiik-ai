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
package io.yupiik.ia.agent.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.http.server.spi.Endpoint;
import io.yupiik.fusion.httpserver.jre.JreHttpServer;
import io.yupiik.fusion.httpserver.jre.JreHttpServerConfiguration;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StaticAssetsExchangeHandlerTest {

    private static HttpClient client;

    private JreHttpServer server;

    @BeforeAll
    static void warm() {
        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterAll
    static void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private JreHttpServer start(final Endpoint... endpoints) {
        final var instance = new JreHttpServer(new JreHttpServerConfiguration(null, 0, true), List.of(endpoints));
        instance.start();
        server = instance;
        return instance;
    }

    private static String url(final JreHttpServer s, final String path) {
        return "http://127.0.0.1:" + s.port() + path;
    }

    private static HttpResponse<String> get(final String url, final String... headers) throws Exception {
        final var builder = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(10));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesAssetsWithImmutableCacheAndEtags() throws Exception {
        final var s = start(new StaticAssetsExchangeHandler());
        final var resp = get(url(s, "/static/index.a1b2c3d4e5.js"));
        assertEquals(200, resp.statusCode());
        assertEquals("js", resp.body());
        assertEquals(
                "public,max-age=31536000,immutable",
                resp.headers().firstValue("cache-control").orElse(""));
        assertTrue(resp.headers().firstValue("etag").isPresent());

        // conditional GET revalidates with 304
        final var etag = resp.headers().firstValue("etag").orElseThrow();
        final var conditional = get(url(s, "/static/index.a1b2c3d4e5.js"), "If-None-Match", etag);
        assertEquals(304, conditional.statusCode());
        assertEquals("", conditional.body());
        assertEquals(
                resp.headers().firstValue("cache-control"),
                conditional.headers().firstValue("cache-control"));
        assertEquals("Accept-Encoding", conditional.headers().firstValue("vary").orElseThrow());
    }

    @Test
    void nonFingerprintedAssetsAreNoCache() throws Exception {
        final var s = start(new StaticAssetsExchangeHandler());
        final var resp = get(url(s, "/index.html"));
        // no index.html in test classpath -> SPA fallback returns the same as 503? actually serves it
        // we assert the cache-control on a served asset without a hash
        assertEquals(200, resp.statusCode());
        assertEquals("no-cache", resp.headers().firstValue("cache-control").orElse(""));
    }

    @Test
    void pathEscapeIsRejected() throws Exception {
        final var s = start(new StaticAssetsExchangeHandler());
        final var resp = get(url(s, "/../../etc/passwd"));
        assertEquals(403, resp.statusCode());
    }

    @Test
    void headReturnsHeadersWithoutBody() throws Exception {
        final var s = start(new StaticAssetsExchangeHandler());
        final var request = HttpRequest.newBuilder(URI.create(url(s, "/static/index.a1b2c3d4e5.js")))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        final var resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertEquals("", resp.body());
        assertEquals("2", resp.headers().firstValue("content-length").orElseThrow());
        assertEquals("Accept-Encoding", resp.headers().firstValue("vary").orElseThrow());
        assertTrue(resp.headers().firstValue("content-encoding").isEmpty());
    }

    @Test
    void encodedTraversalIsRejected() throws Exception {
        final var s = start(new StaticAssetsExchangeHandler());
        // %2e%2e decodes to .. and /static/../../etc/passwd escapes the web root -> 403
        final var resp = get(url(s, "/static/%2e%2e/%2e%2e/etc/passwd"));
        assertEquals(403, resp.statusCode());
        // mixed-case hex, escaping the root -> 403
        final var resp2 = get(url(s, "/static/%2E%2E/%2E%2E/index.html"));
        assertEquals(403, resp2.statusCode());
        // encoded backslash traversal (windows-style separators) -> 403
        final var resp3 = get(url(s, "/static/%2e%2e%5c%2e%2e%5cetc"));
        assertEquals(403, resp3.statusCode());
        // a leading /.. escape is rejected too
        final var resp4 = get(url(s, "/..%2fetc/passwd"));
        assertEquals(403, resp4.statusCode());
        // ...while a normalization that STAYS inside the root is fine: /static/../index.html -> /index.html
        final var benign = get(url(s, "/static/%2E%2E/index.html"));
        assertEquals(200, benign.statusCode());
    }

    @Test
    void binaryAssetsAreServedByteFaithful() throws Exception {
        final var s = start(new StaticAssetsExchangeHandler());
        final var expected = new byte[] {0x00, 0x01, 0x00, 0x00, (byte) 0xff, (byte) 0xfe, (byte) 0x80, 0x7f};
        final var resp = client.send(
                HttpRequest.newBuilder(URI.create(url(s, "/static/font.a1b2c3d4e5.woff2")))
                        .GET()
                        .header("Accept-Encoding", "gzip, *;q=0.5")
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, resp.statusCode());
        assertEquals("font/woff2", resp.headers().firstValue("content-type").orElse(""));
        assertTrue(resp.headers().firstValue("content-encoding").isEmpty());
        assertArrayEquals(
                expected,
                resp.body(),
                "binary asset must be served byte-faithfully, got: " + Arrays.toString(resp.body()));
    }

    @ParameterizedTest
    @CsvSource({
        "gzip, gzip, 200",
        "GZip;Q=0.5, gzip, 200",
        "'br, gzip;q=0.8', gzip, 200",
        "gzip;q=0, identity, 200",
        "'gzip;q=0, *;q=1', identity, 200",
        "*;q=0.5, gzip, 200",
        "'*;q=0, gzip;q=1', gzip, 200",
        "'identity;q=0, gzip;q=0.1', gzip, 200",
        "'identity;q=1, gzip;q=0.5', identity, 200",
        "'identity;q=0.5, gzip;q=1', gzip, 200",
        "'*;q=0, identity;q=1', identity, 200",
        "'gzip ; q = 0.500', gzip, 200",
        "br, identity, 200",
        "'', identity, 200",
        "gzip;q=bogus, identity, 200",
        "gzip;q=1.1, identity, 200",
        "*;q=0, identity, 406",
        "'gzip;q=0, identity;q=0', identity, 406",
        "'gzip;q=1, gzip;q=0', identity, 200"
    })
    void negotiatesEncoding(final String acceptEncoding, final String encoding, final int status) throws Exception {
        final var s = start(new StaticAssetsExchangeHandler());
        final var response = bytes(url(s, "/static/index.a1b2c3d4e5.js"), "GET", "Accept-Encoding", acceptEncoding);
        assertEquals(status, response.statusCode());
        assertEquals("Accept-Encoding", response.headers().firstValue("vary").orElseThrow());
        if (status == 200) {
            assertEquals(
                    encoding, response.headers().firstValue("content-encoding").orElse("identity"));
            assertArrayEquals("js".getBytes(StandardCharsets.UTF_8), decoded(response));
        }
    }

    @Test
    void compressesTextAssetsWithoutChangingTheirMimeType() throws Exception {
        final var s = start(new StaticAssetsExchangeHandler());
        final var target = url(s, "/sample.txt");
        final var identity = bytes(target, "GET");
        final var compressed = bytes(target, "GET", "Accept-Encoding", "gzip");
        assertEquals(200, compressed.statusCode());
        assertEquals(
                "text/plain", compressed.headers().firstValue("content-type").orElseThrow());
        assertEquals("gzip", compressed.headers().firstValue("content-encoding").orElseThrow());
        assertArrayEquals(identity.body(), decoded(compressed));
    }

    @Test
    void gzipValidatorsHeadAndConditionalResponsesDescribeSelectedRepresentation() throws Exception {
        final var s = start(new StaticAssetsExchangeHandler());
        final var target = url(s, "/index.html");
        final var identity = bytes(target, "GET");
        final var compressed = bytes(target, "GET", "Accept-Encoding", "gzip");
        assertEquals(200, compressed.statusCode());
        assertArrayEquals(identity.body(), decoded(compressed));
        assertEquals("Accept-Encoding", identity.headers().firstValue("vary").orElseThrow());
        final var identityTag = identity.headers().firstValue("etag").orElseThrow();
        final var gzipTag = compressed.headers().firstValue("etag").orElseThrow();
        assertNotEquals(identityTag, gzipTag);
        assertEquals(
                '"'
                        + HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(compressed.body()))
                        + '"',
                gzipTag);

        final var head = bytes(target, "HEAD", "Accept-Encoding", "gzip");
        assertEquals(200, head.statusCode());
        assertEquals(0, head.body().length);
        for (final var header : List.of("content-encoding", "content-length", "etag", "vary", "cache-control")) {
            assertEquals(compressed.headers().firstValue(header), head.headers().firstValue(header), header);
        }
        for (final var method : List.of("GET", "HEAD")) {
            final var conditional =
                    bytes(target, method, "Accept-Encoding", "gzip", "If-None-Match", "\"other\", W/" + gzipTag);
            assertEquals(304, conditional.statusCode());
            assertEquals(0, conditional.body().length);
            for (final var header :
                    List.of("content-encoding", "etag", "vary", "cache-control", "content-security-policy")) {
                assertEquals(
                        compressed.headers().firstValue(header),
                        conditional.headers().firstValue(header),
                        header);
            }
            assertTrue(conditional.headers().firstValue("content-length").isEmpty());
        }
        assertEquals(
                200,
                bytes(target, "GET", "Accept-Encoding", "gzip", "If-None-Match", identityTag)
                        .statusCode());
        assertEquals(200, bytes(target, "GET", "If-None-Match", gzipTag).statusCode());
        assertEquals(304, bytes(target, "GET", "If-None-Match", "*").statusCode());
    }

    @Test
    void multipleEncodingHeadersAndBinaryRejection() throws Exception {
        final var s = start(new StaticAssetsExchangeHandler());
        final var response =
                bytes(url(s, "/static/index.a1b2c3d4e5.js"), "GET", "Accept-Encoding", "br", "Accept-Encoding", "GZIP");
        assertEquals("gzip", response.headers().firstValue("content-encoding").orElseThrow());
        final var binary =
                bytes(url(s, "/static/font.a1b2c3d4e5.woff2"), "GET", "Accept-Encoding", "gzip, identity;q=0");
        assertEquals(406, binary.statusCode());
        assertEquals("Accept-Encoding", binary.headers().firstValue("vary").orElseThrow());
        assertTrue(binary.headers().firstValue("content-encoding").isEmpty());
    }

    @Test
    void cachedGzipRemainsStableAcrossSpaAliasesAndResourceChangesWithoutLeakingStreams() throws Exception {
        final var content = new AtomicReference<>("initial content".getBytes(StandardCharsets.UTF_8));
        final var opens = new AtomicInteger();
        final var closes = new AtomicInteger();
        final var original = Thread.currentThread().getContextClassLoader();
        final var loader = new ClassLoader(original) {
            @Override
            public InputStream getResourceAsStream(final String name) {
                if (!name.equals("META-INF/resources/index.html")) {
                    return null;
                }
                opens.incrementAndGet();
                return new ByteArrayInputStream(content.get()) {
                    @Override
                    public void close() throws IOException {
                        closes.incrementAndGet();
                        super.close();
                    }
                };
            }
        };
        final var handler = new StaticAssetsExchangeHandler();
        final var s = start(Endpoint.of(handler::matches, request -> {
            final var thread = Thread.currentThread();
            final var previous = thread.getContextClassLoader();
            try {
                thread.setContextClassLoader(loader);
                return handler.handle(request);
            } finally {
                thread.setContextClassLoader(previous);
            }
        }));
        final var first = bytes(url(s, "/index.html"), "GET", "Accept-Encoding", "gzip");
        assertArrayEquals(content.get(), decoded(first));
        final var repeated = bytes(url(s, "/different-spa-link"), "GET", "Accept-Encoding", "gzip");
        assertArrayEquals(first.body(), repeated.body());
        assertEquals(first.headers().firstValue("etag"), repeated.headers().firstValue("etag"));
        // More than the cache's entry bound, then revisit the original content.
        for (int i = 0; i < 70; i++) {
            content.set(("updated content " + i).getBytes(StandardCharsets.UTF_8));
            final var changed = bytes(url(s, "/index.html"), "GET", "Accept-Encoding", "gzip");
            assertArrayEquals(content.get(), decoded(changed));
            assertNotEquals(
                    first.headers().firstValue("etag"), changed.headers().firstValue("etag"));
        }
        content.set("initial content".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(
                first.body(),
                bytes(url(s, "/index.html"), "GET", "Accept-Encoding", "gzip").body());
        assertTrue(opens.get() > 0);
        assertEquals(opens.get(), closes.get());
    }

    private static HttpResponse<byte[]> bytes(final String url, final String method, final String... headers)
            throws Exception {
        final var builder = HttpRequest.newBuilder(URI.create(url))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static byte[] decoded(final HttpResponse<byte[]> response) throws IOException {
        if (response.headers().firstValue("content-encoding").orElse("").equals("gzip")) {
            try (final var stream = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
                return stream.readAllBytes();
            }
        }
        return response.body();
    }

    @Test
    void securityHeadersAreSentOnStaticResponses() throws Exception {
        final var s = start(new StaticAssetsExchangeHandler());
        final var html = get(url(s, "/index.html")); // test classpath index.html (SPA shell)
        assertEquals(200, html.statusCode());
        assertEquals(
                "nosniff", html.headers().firstValue("x-content-type-options").orElse(""));
        assertEquals(
                "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:",
                html.headers().firstValue("content-security-policy").orElse(""));
        // non-HTML responses carry nosniff but no CSP
        final var js = get(url(s, "/static/index.a1b2c3d4e5.js"));
        assertEquals(200, js.statusCode());
        assertEquals(
                "nosniff", js.headers().firstValue("x-content-type-options").orElse(""));
        assertTrue(js.headers().firstValue("content-security-policy").isEmpty());
    }

    @Test
    void foreignHostAndOriginAreRejectedWith403() throws Exception {
        final var s = start(new StaticAssetsExchangeHandler());
        // java.net.http always sends Host; override it with an explicit foreign Host header
        final var foreign = get(url(s, "/index.html"), "Host", "evil.com");
        assertEquals(403, foreign.statusCode());
        // loopback Host with a foreign Origin is also rejected (CSRF)
        final var csrf = get(url(s, "/index.html"), "Origin", "http://evil.com");
        assertEquals(403, csrf.statusCode());
        // but a loopback Origin is fine
        final var ok = get(url(s, "/index.html"), "Origin", "http://localhost:6060");
        assertEquals(200, ok.statusCode());
    }
}
