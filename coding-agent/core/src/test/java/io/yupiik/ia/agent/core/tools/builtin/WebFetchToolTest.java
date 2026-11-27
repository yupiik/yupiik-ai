/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.htmltomarkdown.HtmlLightParser;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.sandbox.Sandbox;
import io.yupiik.ia.agent.core.sandbox.SandboxHttpTransport;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebFetchToolTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(strings = {"markdown", "text"})
    void convertsHtmlLargerThanTheFormerRawBudget(final String format) throws Exception {
        final var html = "<html><head><script>" + "x".repeat(300_000)
                + "</script></head><body><h1>Documentation</h1><p>Content after the large script.</p></body></html>";
        final var result = fetch(html, format, "Content-Length: " + html.length());
        assertTrue(result.success(), result.output());
        assertEquals(
                ("markdown".equals(format) ? "# Documentation\n" : "Documentation\n")
                        + "Content after the large script.",
                result.output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"html", "text", "markdown"})
    void outputRemainsCappedAfterConversion(final String format) throws Exception {
        final var html = "<p>" + "x".repeat(60_001) + "</p>";
        final var result = fetch(html, format, "Content-Length: " + html.length());
        assertTrue(result.success(), result.output());
        assertEquals(
                ("html".equals(format) ? html.substring(0, 60_000) : "x".repeat(60_000)) + "\n[... truncated ...]",
                result.output());
    }

    @Test
    void outputAtTheCapIsNotTruncated() throws Exception {
        final var html = "<p>" + "x".repeat(60_000) + "</p>";
        final var result = fetch(html, "text", "Content-Length: " + html.length());
        assertTrue(result.success(), result.output());
        assertEquals("x".repeat(60_000), result.output());
    }

    @Test
    void acceptsExactlyTenMillionRawBytes() throws Exception {
        final var body = "x".repeat(10_000_000);
        final var result = fetch(body, "html", "Content-Length: 10000000");
        assertTrue(result.success(), result.output());
        assertEquals("x".repeat(60_000) + "\n[... truncated ...]", result.output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Content-Length: 10000001", "Transfer-Encoding: chunked", "Connection: close"})
    void rejectsMoreThanTenMillionRawBytes(final String framing) throws Exception {
        final var body = "x".repeat(10_000_001);
        final var wireBody = framing.startsWith("Transfer-Encoding") ? "989681\r\n" + body + "\r\n0\r\n\r\n" : body;
        final var result = fetch(wireBody, "html", framing);
        assertFalse(result.success());
        assertTrue(result.output().contains("exceeds limit of 10000000 bytes"), result.output());
        assertFalse(result.output().contains("java.io.IOException"), result.output());
    }

    private ToolResult fetch(final String body, final String format, final String framing) throws Exception {
        // Reuse the real bounded transport with an in-memory socket, as in SandboxHttpTransportTest.
        // Read the tool's budget without adding a production API solely for the fixture.
        final var budget = WebFetchTool.class.getDeclaredField("MAX_BYTES");
        budget.setAccessible(true);
        try (final var sandbox = new DisabledSandbox(root);
                final var transport = new WireTransport(
                        sandbox,
                        budget.getInt(null),
                        "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n" + framing + "\r\n\r\n" + body);
                final var client =
                        new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(transport))) {
            return new WebFetchTool(client, new HtmlLightParser())
                    .execute(
                            Map.of("url", "http://8.8.8.8/documentation", "format", format),
                            ToolContext.empty(root, sandbox));
        }
    }

    private static final class WireTransport extends SandboxHttpTransport {
        private final String response;

        private WireTransport(final Sandbox sandbox, final int budget, final String response) {
            super(sandbox, () -> false, budget);
            this.response = response;
        }

        @Override
        protected Socket newSocket() {
            return new Socket() {
                private final InputStream input = new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8));

                @Override
                public void connect(final SocketAddress destination, final int timeout) {
                    // No network access: exercise transport framing and byte accounting in memory.
                }

                @Override
                public void setSoTimeout(final int timeout) {}

                @Override
                public InputStream getInputStream() {
                    return input;
                }

                @Override
                public OutputStream getOutputStream() {
                    return OutputStream.nullOutputStream();
                }
            };
        }

        @Override
        protected InetAddress[] resolve(final String host) throws IOException {
            return new InetAddress[] {InetAddress.getByAddress(new byte[] {8, 8, 8, 8})};
        }
    }
}
