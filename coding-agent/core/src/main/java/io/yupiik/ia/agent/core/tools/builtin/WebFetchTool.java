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
package io.yupiik.ia.agent.core.tools.builtin;

import static io.yupiik.ia.agent.core.tools.ToolArgs.arg;
import static io.yupiik.ia.agent.core.tools.ToolArgs.optInt;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.htmltomarkdown.HtmlLightParser;
import io.yupiik.ia.agent.core.sandbox.SandboxHttpTransport;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

@ApplicationScoped
public class WebFetchTool implements EmbeddedTool {
    private static final Pattern CONTENT_TYPE_CHARSET = Pattern.compile("(?i).*charset\\s*=\\s*[\"']?([^\"';\\s]+).*");

    private static final int MAX_BYTES = 10_000_000;
    private static final int MAX_OUTPUT = 60_000;

    private final ExtendedHttpClient client;
    private final HtmlLightParser htmlParser;

    public WebFetchTool(final ExtendedHttpClient client, final HtmlLightParser htmlParser) {
        this.client = client;
        this.htmlParser = htmlParser;
    }

    @Override
    public String name() {
        return "webfetch";
    }

    @Override
    public String description(final Path workdir) {
        return "Fetch an HTTP(S) URL and return its content as plain text, markdown or raw html (default markdown). Use for documentation, API descriptions or any public web resource.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public boolean readonly() {
        return true;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(
                Map.of(
                        "url", Schemas.str("The URL to fetch."),
                        "format", Schemas.str("Output format: text, markdown or html. Default markdown."),
                        "timeout_seconds", Schemas.intProp("Request timeout in seconds, default 30.")),
                "url");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        final var url = arg(args, "url");
        if (url == null || url.isBlank()) {
            return ToolResult.error("url is required");
        }
        final var format = optFormat(arg(args, "format"));
        final var timeout = (int) Math.max(1, optInt(args, "timeout_seconds", 30));
        final URI uri;
        try {
            uri = URI.create(url);
        } catch (final IllegalArgumentException e) {
            return ToolResult.error("Invalid URL: " + url);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            return ToolResult.error("Only http/https URLs are supported");
        }
        final var ssrf = context.sandbox().enabled() ? null : ssrfError(uri);
        if (ssrf != null) {
            return ToolResult.error(ssrf);
        }
        final var request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(timeout))
                .header("accept", "text/html,application/xhtml+xml,text/plain,application/json,*/*;q=0.8")
                .GET()
                .build();
        try (final var transport = context.sandbox().enabled()
                ? new SandboxHttpTransport(
                        context.sandbox(), () -> context.isCancelled() || context.isCallCancelled(), MAX_BYTES)
                : null) {
            // the request is polled in small slices so a turn cancellation surfaces promptly
            // instead of waiting for the whole fetch (which can take up to the timeout)
            final var future = (transport == null ? client : transport)
                    .sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
            final var deadline = System.nanoTime() + Duration.ofSeconds(timeout).toNanos();
            while (true) {
                if (context.isCancelled() || context.isCallCancelled()) {
                    future.cancel(true);
                    return ToolResult.error("Interrupted while fetching " + url);
                }
                final var remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    future.cancel(true);
                    return ToolResult.error("Timed out fetching " + url);
                }
                try {
                    final var response =
                            future.get(Math.min(100, Math.max(1, remaining / 1_000_000)), TimeUnit.MILLISECONDS);
                    if (response.statusCode() >= 400) {
                        return ToolResult.error("HTTP " + response.statusCode() + " for " + url);
                    }
                    final var body = decode(response);
                    final var contentType =
                            response.headers().firstValue("content-type").orElse("text/html");
                    final String out;
                    if ("html".equals(format)) {
                        out = body;
                    } else if (!isHtml(contentType) && "text".equals(format)) {
                        out = body.strip();
                    } else if (!isHtml(contentType)) {
                        out = body.strip();
                    } else if ("text".equals(format)) {
                        out = htmlParser.toText(body);
                    } else {
                        out = htmlParser.toMarkdown(body);
                    }
                    return ToolResult.ok(cap(out, MAX_OUTPUT));
                } catch (final TimeoutException e) {
                    // keep polling the cancellation flag until the deadline
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Interrupted while fetching " + url);
        } catch (final Exception e) {
            if (context.isCancelled() || Thread.currentThread().isInterrupted()) {
                return ToolResult.error("Interrupted while fetching " + url);
            }
            final var failure = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            return ToolResult.error("Cannot fetch " + url + ": " + failure.getMessage());
        }
    }

    private static String optFormat(final String raw) {
        if (raw == null) {
            return "markdown";
        }
        final var lower = raw.trim().toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "text", "txt", "html", "htm" -> lower.startsWith("text") ? "text" : "html";
            default -> "markdown";
        };
    }

    /**
     * SSRF guard: rejects URLs whose host resolves (partly or fully) to a loopback, link-local,
     * private or otherwise non-routable address - the classic metadata-service (169.254.169.254)
     * and localhost pivot. This is only a best-effort check for unsandboxed use: the client may
     * resolve again or follow redirects. Sandboxed use uses SandboxHttpTransport instead.
     */
    static String ssrfError(final URI uri) {
        if (uri.getHost() == null) {
            return "URL has no host: " + uri;
        }
        final InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(uri.getHost());
        } catch (final UnknownHostException e) {
            return "Cannot resolve host " + uri.getHost();
        }
        for (final var address : resolved) {
            if (address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isAnyLocalAddress()) {
                return "Refusing to fetch non-routable address " + address.getHostAddress() + " for " + uri;
            }
        }
        return null;
    }

    private static boolean isHtml(final String contentType) {
        final var ct = contentType.toLowerCase(Locale.ROOT);
        return ct.contains("html") || ct.contains("xhtml");
    }

    private static String decode(final HttpResponse<byte[]> response) {
        final var charset = CONTENT_TYPE_CHARSET
                .matcher(response.headers().firstValue("content-type").orElse(""))
                .replaceAll("$1");
        if (charset != null
                && !charset.isEmpty()
                && !charset.equals(response.headers().firstValue("content-type").orElse(""))) {
            try {
                return new String(response.body(), Charset.forName(charset));
            } catch (final RuntimeException e) {
                // fall through
            }
        }
        final var raw = new String(response.body(), StandardCharsets.UTF_8);
        // crude UTF-8 BOM / meta detection fallback to latin1 if it looks binary
        return raw;
    }

    private static String cap(final String s, final int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "\n[... truncated ...]";
    }
}
