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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class OpenCodeHttpResponseTest {
    @Test
    void framingAndInterim() throws Exception {
        final var parsed = parse(
                "HTTP/1.1 103 Early Hints\r\nLink: x\r\n\r\nHTTP/1.1 200 OK\r\n"
                        + "Transfer-Encoding: chunked\r\nContent-Length: 900\r\n\r\n3;extension=value\r\nabc\r\n0\r\nX-End: yes\r\n\r\n",
                "GET");
        assertEquals(200, parsed.info().statusCode());
        assertEquals("abc", new String(parsed.body().readAllBytes(), StandardCharsets.UTF_8));
        assertTrue(parsed.info().headers().firstValue("content-length").isEmpty());
        assertEquals(
                "3\r\nabc\r\n",
                new String(
                        parse("HTTP/1.1 200 OK\r\n\r\n3\r\nabc\r\n", "GET")
                                .body()
                                .readAllBytes(),
                        StandardCharsets.UTF_8));
    }

    @Test
    void bodylessIgnoresFraming() throws Exception {
        for (final var status : new int[] {204, 304}) {
            assertEquals(
                    0,
                    parse(
                                    "HTTP/1.1 " + status
                                            + " Empty\r\nContent-Length: bad\r\nTransfer-Encoding: bad\r\n\r\n",
                                    "GET")
                            .body()
                            .readAllBytes()
                            .length);
        }
        assertEquals(
                0,
                parse("HTTP/1.1 200 OK\r\nContent-Length: bad\r\n\r\n", "HEAD")
                        .body()
                        .readAllBytes()
                        .length);
    }

    @Test
    void malformedAndTruncatedBodiesFail() {
        for (final var response : new String[] {
            "HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nContent-Length: 4\r\n\r\nabc",
            "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nabc",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nxx\r\n",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabcX\n0\r\n\r\n",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n0\r\nX-Trailer: incomplete",
            "HTTP/1.1 200 OK\nContent-Length: 0\n\n"
        }) {
            assertThrows(IOException.class, () -> parse(response, "GET").body().readAllBytes());
        }
    }

    @Test
    void compressionClearsStaleMetadata() throws Exception {
        final var compressed = new ByteArrayOutputStream();
        try (final var gzip = new GZIPOutputStream(compressed)) {
            gzip.write("data: hello\n\n".getBytes(StandardCharsets.UTF_8));
        }
        final var response = new ByteArrayOutputStream();
        response.writeBytes(
                ("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: " + compressed.size() + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
        response.writeBytes(compressed.toByteArray());
        final var parsed = OpenCodeHttpResponse.read(new ByteArrayInputStream(response.toByteArray()), "GET");
        assertTrue(parsed.info().headers().firstValue("content-length").isEmpty());
        assertTrue(parsed.info().headers().firstValue("content-encoding").isEmpty());
        assertEquals("data: hello\n\n", new String(parsed.body().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void compressionDoesNotHideTruncatedFraming() throws Exception {
        final var compressed = new ByteArrayOutputStream();
        try (final var gzip = new GZIPOutputStream(compressed)) {
            gzip.write("hello".getBytes(StandardCharsets.UTF_8));
        }
        final var response = new ByteArrayOutputStream();
        response.writeBytes(
                ("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: " + (compressed.size() + 1) + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
        response.writeBytes(compressed.toByteArray());
        assertThrows(
                IOException.class,
                () -> OpenCodeHttpResponse.read(new ByteArrayInputStream(response.toByteArray()), "GET")
                        .body()
                        .readAllBytes());
    }

    private OpenCodeHttpResponse.Parsed parse(final String response, final String method) throws IOException {
        return OpenCodeHttpResponse.read(
                new ByteArrayInputStream(response.getBytes(StandardCharsets.ISO_8859_1)), method);
    }
}
