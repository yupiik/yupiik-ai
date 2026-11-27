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

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** Bounded HTTP/1.1 framing, kept separate from TLS and Flow demand. */
final class OpenCodeHttpResponse {
    private static final Pattern STATUS_LINE = Pattern.compile("HTTP/1\\.[01] [1-5][0-9]{2}( .*)?");
    private static final Pattern CONTENT_LENGTH = Pattern.compile("[0-9]{1,18}");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern CHUNK_SIZE = Pattern.compile("[0-9A-Fa-f]{1,15}");

    private OpenCodeHttpResponse() {}

    record Parsed(HttpResponse.ResponseInfo info, InputStream body) {}

    static Parsed read(final InputStream input, final String method) throws IOException {
        for (int interim = 0; interim < 16; interim++) {
            final var status = line(input);
            if (status == null || !STATUS_LINE.matcher(status).matches()) {
                throw new IOException("Malformed HTTP status line");
            }
            final int code = Integer.parseInt(status.substring(9, 12));
            final var headers = headers(input);
            if (code == 101) {
                throw new IOException("HTTP upgrade unsupported");
            }
            if (code < 200) {
                continue;
            }
            final boolean bodyless = method.equals("HEAD") || code == 204 || code == 304;
            InputStream body;
            if (bodyless) {
                body = InputStream.nullInputStream();
            } else if (headers.containsKey("transfer-encoding")) {
                if (!String.join(",", headers.get("transfer-encoding")).equalsIgnoreCase("chunked")) {
                    throw new IOException("Unsupported HTTP transfer coding");
                }
                body = new Chunked(input);
                headers.remove("content-length");
            } else if (headers.containsKey("content-length")) {
                final var lengths = headers.get("content-length");
                if (lengths.size() != 1
                        || !CONTENT_LENGTH.matcher(lengths.getFirst()).matches()) {
                    throw new IOException("Invalid Content-Length");
                }
                try {
                    body = new Fixed(input, Long.parseLong(lengths.getFirst()));
                } catch (final NumberFormatException error) {
                    throw new IOException("Invalid Content-Length", error);
                }
            } else {
                body = input; // TLS stream rejects bare TCP EOF; authenticated close_notify terminates.
            }
            final var encodings = headers.get("content-encoding");
            if (!bodyless && encodings != null && !String.join(",", encodings).equalsIgnoreCase("identity")) {
                final var coding = String.join(",", encodings).toLowerCase(Locale.ROOT);
                final var framed = body;
                final var decoded =
                        switch (coding) {
                            case "gzip" -> new GZIPInputStream(body);
                            case "deflate" -> new InflaterInputStream(body);
                            default -> throw new IOException("Unsupported response content coding: " + coding);
                        };
                body = new FilterInputStream(decoded) {
                    private boolean complete;

                    @Override
                    public int read() throws IOException {
                        final var bytes = new byte[1];
                        return read(bytes, 0, 1) < 0 ? -1 : bytes[0] & 255;
                    }

                    @Override
                    public int read(final byte[] bytes, final int offset, final int length) throws IOException {
                        final int count = super.read(bytes, offset, length);
                        if (count < 0 && !complete) {
                            // A compression footer is not a substitute for HTTP framing completion.
                            // In particular, validate chunk trailers and reject truncated Content-Length.
                            while (framed.read(new byte[8192]) >= 0) {
                                // Drain the bounded framing wrapper, not a subsequent exchange.
                            }
                            complete = true;
                        }
                        return count;
                    }
                };
                headers.remove("content-encoding");
                headers.remove("content-length");
            }
            final var responseHeaders = HttpHeaders.of(headers, (name, value) -> true);
            return new Parsed(
                    new HttpResponse.ResponseInfo() {
                        @Override
                        public int statusCode() {
                            return code;
                        }

                        @Override
                        public HttpHeaders headers() {
                            return responseHeaders;
                        }

                        @Override
                        public HttpClient.Version version() {
                            return HttpClient.Version.HTTP_1_1;
                        }
                    },
                    body);
        }
        throw new IOException("Too many informational responses");
    }

    private static TreeMap<String, List<String>> headers(final InputStream input) throws IOException {
        final var headers = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
        int size = 0;
        for (int count = 0; count < 256; count++) {
            final var line = line(input);
            if (line == null) {
                throw new EOFException("Truncated HTTP headers");
            }
            size += line.length() + 2;
            if (size > 65536) {
                throw new IOException("HTTP headers too large");
            }
            if (line.isEmpty()) {
                return headers;
            }
            final int colon = line.indexOf(':');
            if (colon < 1
                    || !HEADER_NAME.matcher(line.substring(0, colon)).matches()
                    || line.substring(colon + 1).chars().anyMatch(c -> c < 32 && c != 9 || c == 127)) {
                throw new IOException("Malformed HTTP header");
            }
            headers.computeIfAbsent(line.substring(0, colon).toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                    .add(line.substring(colon + 1).trim());
        }
        throw new IOException("Too many HTTP headers");
    }

    private static String line(final InputStream input) throws IOException {
        final var bytes = new ByteArrayOutputStream();
        while (bytes.size() < 8192) {
            final int next = input.read();
            if (next < 0) {
                if (bytes.size() == 0) {
                    return null;
                }
                throw new EOFException("Truncated HTTP line");
            }
            if (next == '\r') {
                if (input.read() != '\n') {
                    throw new IOException("HTTP line requires CRLF");
                }
                return bytes.toString(StandardCharsets.ISO_8859_1);
            }
            if (next == '\n') {
                throw new IOException("HTTP line requires CRLF");
            }
            bytes.write(next);
        }
        throw new IOException("HTTP line too long");
    }

    private static final class Fixed extends InputStream {
        private final InputStream input;
        private long remaining;

        private Fixed(final InputStream input, final long remaining) {
            this.input = input;
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            final var bytes = new byte[1];
            return read(bytes, 0, 1) < 0 ? -1 : bytes[0] & 255;
        }

        @Override
        public int read(final byte[] bytes, final int offset, final int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            if (remaining == 0) {
                return -1;
            }
            final int count = input.read(bytes, offset, (int) Math.min(length, remaining));
            if (count < 0) {
                throw new EOFException("Truncated HTTP body");
            }
            remaining -= count;
            return count;
        }
    }

    private static final class Chunked extends InputStream {
        private final InputStream input;
        private long remaining;
        private boolean terminator;
        private boolean done;

        private Chunked(final InputStream input) {
            this.input = input;
        }

        @Override
        public int read() throws IOException {
            final var bytes = new byte[1];
            return read(bytes, 0, 1) < 0 ? -1 : bytes[0] & 255;
        }

        @Override
        public int read(final byte[] bytes, final int offset, final int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            if (done) {
                return -1;
            }
            if (remaining == 0) {
                if (terminator && (input.read() != '\r' || input.read() != '\n')) {
                    throw new IOException("Missing chunk-data CRLF");
                }
                final var line = line(input);
                if (line == null || !CHUNK_SIZE.matcher(line.split(";", 2)[0]).matches()) {
                    throw new IOException("Malformed chunk size");
                }
                remaining = Long.parseLong(line.split(";", 2)[0], 16);
                if (remaining == 0) {
                    final var trailers = headers(input);
                    if (trailers.containsKey("content-length") || trailers.containsKey("transfer-encoding")) {
                        throw new IOException("Forbidden framing trailer");
                    }
                    done = true;
                    return -1;
                }
                terminator = true;
            }
            final int count = input.read(bytes, offset, (int) Math.min(length, remaining));
            if (count < 0) {
                throw new EOFException("Truncated chunk data");
            }
            remaining -= count;
            return count;
        }
    }
}
