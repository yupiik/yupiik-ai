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

import static java.util.concurrent.CompletableFuture.completedFuture;

import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.http.server.api.Response;
import io.yupiik.fusion.http.server.spi.Endpoint;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * Serves the bundled browser UI from {@code META-INF/resources/**} with a
 * proper cache story (representation-specific strong ETags, immutable cache for
 * hash-fingerprinted assets, no-cache for the SPA shell), a path-escape guard,
 * negotiated gzip, HEAD support and an SPA fallback to {@code index.html} for bookmarkable
 * deep links.
 */
public class StaticAssetsExchangeHandler implements Endpoint {
    private static final String RESOURCES_PREFIX = "META-INF/resources/";
    /** Hardened security headers shipped on static responses. */
    private static final String X_CONTENT_TYPE_OPTIONS = "nosniff";

    /** Restrictive CSP for HTML responses (the SPA shell): only self, inline styles, self/data images. */
    private static final String CSP_HTML = "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:";

    private final String boundHost;
    // Cache only compressed representations, keyed by content, never attacker-controlled paths.
    // No classloader or open stream is retained; rereading the source also detects changed resources.
    private static final int MAX_GZIP_ENTRIES = 64;
    private static final int MAX_GZIP_BYTES = 8 * 1024 * 1024;
    private static final Pattern QUALITY = Pattern.compile("(?:0(?:\\.[0-9]{0,3})?|1(?:\\.0{0,3})?)");
    private final Map<String, Representation> gzipCache = new LinkedHashMap<>();
    private int gzipCacheBytes;

    public StaticAssetsExchangeHandler() {
        this(null);
    }

    /** @param boundHost the explicitly bound host ({@code --host}), accepted by the Host guard. */
    public StaticAssetsExchangeHandler(final String boundHost) {
        this.boundHost = boundHost;
    }

    /** A path segment that looks like a content hash (lowercase hex, 8+ chars) marks an immutable asset. */
    private static final Pattern HASH_SEGMENT = Pattern.compile("[0-9a-f]{8,}");

    private static final Map<String, String> EXTRA_MIME = Map.of(
            "js", "application/javascript",
            "mjs", "application/javascript",
            "css", "text/css",
            "html", "text/html",
            "json", "application/json",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon",
            "woff2", "font/woff2",
            "map", "application/json");

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean matches(final Request request) {
        if (request.path().startsWith("/api/")) {
            return false;
        }
        return "GET".equals(request.method()) || "HEAD".equals(request.method());
    }

    @Override
    public CompletionStage<Response> handle(final Request request) {
        final var rejected = HttpOriginGuard.rejectIfForeign(request, boundHost);
        if (rejected != null) {
            return completedFuture(rejected);
        }
        // normalize BEFORE the traversal guard: the JRE adapter exposes the URI path (already
        // percent-decoded by java.net.URI), so an encoded traversal (%2e%2e%2f) must not slip past
        // a literal ".." check. Only the traversal-relevant escapes are decoded here (whether or
        // not the server already decoded them, double-decoding arbitrary sequences would corrupt
        // asset names containing a literal '%' or '+'). A residual ".." component after resolution
        // is an escape attempt and rejected.
        final var path = normalizePath(decodeTraversalEscapes(request.path()));
        if (path == null || path.contains("..")) {
            return completedFuture(Response.of().status(403).body("forbidden").build());
        }
        final var resource = resourceFor(path);
        if (resource != null) {
            return serve(resource, request);
        }
        // SPA fallback: non-API GET miss -> serve index.html (200) so deep links work
        if ("/".equals(path) || hasNoExtension(path)) {
            return serveIndex(request);
        }
        return completedFuture(Response.of().status(404).body("Not Found").build());
    }

    private static String resourceFor(final String path) {
        final var relative = path.startsWith("/") ? path.substring(1) : path;
        if (relative.isBlank() || "/".equals(relative)) {
            return null;
        }
        final String candidate;
        if (relative.endsWith("/")) {
            candidate = relative + "index.html";
        } else {
            candidate = relative;
        }
        return RESOURCES_PREFIX + candidate;
    }

    private static boolean hasNoExtension(final String path) {
        final var lastSegment = path.indexOf('?') >= 0 ? path.substring(0, path.indexOf('?')) : path;
        final var idx = lastSegment.lastIndexOf('/');
        final var name = idx < 0 ? lastSegment : lastSegment.substring(idx + 1);
        return !name.contains(".");
    }

    private CompletionStage<Response> serve(final String resource, final Request request) {
        try (final var stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                // SPA fallback for deep links served on extension-less paths
                return hasNoExtension(request.path()) ? serveIndex(request) : notFound();
            }
            final var bytes = stream.readAllBytes();
            return responseFor(bytes, resource, request);
        } catch (final IOException e) {
            return serverError();
        }
    }

    private CompletionStage<Response> serveIndex(final Request request) {
        final var resource = RESOURCES_PREFIX + "index.html";
        try (final var stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                return completedFuture(
                        Response.of().status(503).body("web ui not built").build());
            }
            return responseFor(stream.readAllBytes(), resource, request);
        } catch (final IOException e) {
            return serverError();
        }
    }

    private static CompletionStage<Response> notFound() {
        return completedFuture(Response.of().status(404).body("Not Found").build());
    }

    private static CompletionStage<Response> serverError() {
        return completedFuture(Response.of().status(500).body("internal error").build());
    }

    private CompletionStage<Response> responseFor(final byte[] bytes, final String resource, final Request request)
            throws IOException {
        final var contentType = contentType(resource);
        final var encoding = encodingFor(request, isCompressible(contentType));
        if (encoding == null) {
            return completedFuture(
                    Response.of().status(406).header("vary", "Accept-Encoding").build());
        }
        final var identity = new Representation(bytes, etag(bytes));
        final var representation = "gzip".equals(encoding) ? gzip(identity) : identity;
        final var notModified = matchesEtag(request, representation.etag());
        final var builder = Response.of()
                .status(notModified ? 304 : 200)
                .header("content-type", contentType)
                .header("etag", representation.etag())
                .header("vary", "Accept-Encoding")
                .header("x-content-type-options", X_CONTENT_TYPE_OPTIONS);
        if ("gzip".equals(encoding)) {
            builder.header("content-encoding", "gzip");
        }
        if ("text/html".equals(contentType)) {
            builder.header("content-security-policy", CSP_HTML);
        }
        final var immutable = isImmutable(resource);
        builder.header("cache-control", immutable ? "public,max-age=31536000,immutable" : "no-cache");
        if (!notModified) {
            builder.header("content-length", representation.bytes().length);
        }
        if (!notModified && !"HEAD".equalsIgnoreCase(request.method())) {
            // byte-faithful: the body is served as raw bytes (no charset round-trip), so binary assets
            // (woff2, png, svg...) reach the browser exactly as stored. The JRE adapter aggregates
            // OutputStreamPublisher bodies into a counted response, so Content-Length is still sent.
            builder.bytesBody(out -> {
                try (out;
                        final var in = new ByteArrayInputStream(representation.bytes())) {
                    in.transferTo(out);
                }
            });
        }
        return completedFuture(builder.build());
    }

    private static boolean isCompressible(final String type) {
        return type.startsWith("text/")
                || "application/javascript".equals(type)
                || "application/json".equals(type)
                || "application/xml".equals(type)
                || "image/svg+xml".equals(type);
    }

    private static String encodingFor(final Request request, final boolean compressible) {
        double gzip = -1;
        double identity = -1;
        double wildcard = -1;
        for (final var entry : request.headers().entrySet()) {
            if (!"accept-encoding".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            for (final var value : entry.getValue()) {
                for (final var coding : value.split(",")) {
                    final var parts = coding.trim().split(";", -1);
                    final var name = parts[0].trim();
                    double quality = 1;
                    // Invalid weights are not permission to send an otherwise rejected coding.
                    if (parts.length > 1) {
                        final var parameter = parts[1].trim().split("=", -1);
                        quality = parts.length == 2
                                        && parameter.length == 2
                                        && "q".equalsIgnoreCase(parameter[0].trim())
                                        && QUALITY.matcher(parameter[1].trim()).matches()
                                ? Double.parseDouble(parameter[1].trim())
                                : 0;
                    }
                    if ("gzip".equalsIgnoreCase(name)) {
                        gzip = gzip < 0 ? quality : Math.min(gzip, quality);
                    } else if ("identity".equalsIgnoreCase(name)) {
                        identity = identity < 0 ? quality : Math.min(identity, quality);
                    } else if ("*".equals(name)) {
                        wildcard = wildcard < 0 ? quality : Math.min(wildcard, quality);
                    }
                }
            }
        }
        final var gzipQuality = gzip < 0 ? Math.max(0, wildcard) : gzip;
        // Implicit identity is a fallback, not a preference over an explicitly accepted coding.
        final var identityQuality = identity < 0 ? (wildcard == 0 ? 0 : 1) : identity;
        if (compressible && gzipQuality > 0 && (identity < 0 || gzipQuality >= identityQuality)) {
            return "gzip";
        }
        return identityQuality > 0 ? "identity" : null;
    }

    private static boolean matchesEtag(final Request request, final String etag) {
        for (final var entry : request.headers().entrySet()) {
            if ("if-none-match".equalsIgnoreCase(entry.getKey())) {
                for (final var value : entry.getValue()) {
                    for (final var candidate : value.split(",")) {
                        final var tag = candidate.trim();
                        if ("*".equals(tag) || etag.equals(tag) || ("W/" + etag).equals(tag)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private synchronized Representation gzip(final Representation identity) throws IOException {
        final var cached = gzipCache.get(identity.etag());
        if (cached != null) {
            return cached;
        }
        final var output = new ByteArrayOutputStream();
        try (final var gzip = new GZIPOutputStream(output)) {
            gzip.write(identity.bytes());
        }
        final var bytes = output.toByteArray();
        final var representation = new Representation(bytes, etag(bytes));
        if (bytes.length <= MAX_GZIP_BYTES) {
            while (gzipCache.size() >= MAX_GZIP_ENTRIES || gzipCacheBytes + bytes.length > MAX_GZIP_BYTES) {
                final var iterator = gzipCache.values().iterator();
                gzipCacheBytes -= iterator.next().bytes().length;
                iterator.remove();
            }
            gzipCache.put(identity.etag(), representation);
            gzipCacheBytes += bytes.length;
        }
        return representation;
    }

    private static String etag(final byte[] bytes) {
        return '"' + HexFormat.of().formatHex(digest(bytes)) + '"';
    }

    private record Representation(byte[] bytes, String etag) {}

    /**
     * Decodes only the escapes that matter for path traversal ({@code %2e} = '.', {@code %2f} = '/',
     * {@code %5c} = '\' - any case) and folds backslashes into forward slashes. Every other sequence
     * is left untouched, so an asset whose name contains a literal {@code %} or {@code +} round-trips
     * unchanged no matter how many times the path was already decoded by the server.
     */
    private static String decodeTraversalEscapes(final String raw) {
        if (raw == null || raw.isBlank() || (raw.indexOf('%') < 0 && raw.indexOf('\\') < 0)) {
            return raw;
        }
        final var sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            final var c = raw.charAt(i);
            if (c == '%' && i + 2 < raw.length()) {
                final var hex = raw.substring(i + 1, i + 3).toLowerCase(Locale.ROOT);
                if (hex.equals("2e") || hex.equals("2f") || hex.equals("5c")) {
                    sb.append(hex.equals("2e") ? '.' : hex.equals("2f") ? '/' : '\\');
                    i += 2;
                    continue;
                }
            }
            sb.append(c);
        }
        var out = sb.toString();
        if (out.indexOf('\\') >= 0) {
            out = out.replace('\\', '/');
        }
        return out;
    }

    /**
     * Resolves {@code .} and {@code ..} path segments to their canonical form: {@code /a/../b} becomes
     * {@code /b} (safe), while {@code /../../etc/passwd} becomes {@code /etc/passwd} whose leading
     * {@code /..} is preserved as a literal {@code ..} segment caught by the caller's check.
     * Returns {@code null} when the path escapes above the root ({@code /..}).
     */
    private static String normalizePath(final String decoded) {
        if (decoded == null || decoded.isBlank()) {
            return decoded;
        }
        final var segments = new ArrayDeque<String>();
        for (final var segment : decoded.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (segments.isEmpty()) {
                    return null; // /.. escapes above the root: reject
                }
                segments.pollLast();
            } else {
                segments.addLast(segment);
            }
        }
        return "/" + String.join("/", segments);
    }

    private static String contentType(final String resource) {
        final var dot = resource.lastIndexOf('.');
        if (dot < 0) {
            return "application/octet-stream";
        }
        final var ext = resource.substring(dot + 1).toLowerCase(Locale.ROOT);
        final var known = EXTRA_MIME.get(ext);
        if (known != null) {
            return known;
        }
        final var guessed = URLConnection.guessContentTypeFromName(resource);
        return guessed == null ? "application/octet-stream" : guessed;
    }

    private static boolean isImmutable(final String resource) {
        // a CONTENT_HASH-looking token anywhere in the last path segment (file name)
        // or any earlier segment marks the asset as content-addressable: cache it forever
        final var fileName = resource.substring(resource.lastIndexOf('/') + 1);
        if (HASH_SEGMENT.matcher(fileName).find()) {
            return true;
        }
        final var slash = resource.lastIndexOf('/');
        final var segment = slash < 0 ? resource : resource.substring(0, slash);
        return HASH_SEGMENT.matcher(segment).find();
    }

    private static byte[] digest(final byte[] bytes) {
        try {
            final var md = MessageDigest.getInstance("SHA-256");
            return md.digest(bytes);
        } catch (final NoSuchAlgorithmException e) {
            // SHA-256 is always present
            throw new IllegalStateException(e);
        }
    }
}
