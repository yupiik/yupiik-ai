package io.yupiik.fusion.a2a.protocol;

import static io.yupiik.fusion.a2a.protocol.A2AProtocol.*;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.yupiik.fusion.http.server.api.Request;
import java.net.URLDecoder;

/** HTTP binding utilities, deliberately not part of the shared wire protocol. */
public final class A2AHttp {
    private A2AHttp() {}
    /**
     * Whether a request carries a payload at all.
     * <p>
     * Reading the body of a request which has none never completes, so this is what the bindings have to decide on before
     * asking for it - the headers, and not the body itself.
     *
     * @param request the current request.
     * @return {@code true} when there is a body to read.
     */
    public static boolean hasBody(final Request request) {
        final var transferEncoding = request.header("transfer-encoding");
        if (transferEncoding != null && !transferEncoding.isBlank()) {
            return true;
        }
        final var length = request.header("content-length");
        if (length == null || length.isBlank()) {
            return false;
        }
        try {
            return Long.parseLong(length.strip()) > 0;
        } catch (final NumberFormatException nfe) {
            return false;
        }
    }

    /**
     * Reads the version service parameter of a request.
     * <p>
     * Section 3.6.1 lets a client send it "as a request parameter instead of a header", so both are looked up, the
     * header first. The query string is parsed here rather than through {@code Request#parameter} on purpose: asking a
     * servlet container for a parameter can make it parse the request body, and this runs on {@code POST /a2a} before
     * the JSON-RPC payload is read.
     *
     * @param request the current request.
     * @return the raw value, or {@code null} when the request carries none - which {@link #resolve(String)} then turns
     * into {@link #ASSUMED_VERSION}.
     */
    public static String version(final Request request) {
        final var header = request.header(VERSION_HEADER);
        if (header != null && !header.isBlank()) {
            return header;
        }
        return queryParameter(request.query(), VERSION_HEADER);
    }

    /**
     * @param query the raw query string, it can be {@code null}.
     * @param name  the parameter to look for, matched ignoring the case as service parameter keys are case insensitive.
     * @return the first matching value, URL decoded, or {@code null}.
     */
    private static String queryParameter(final String query, final String name) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (final var pair : query.split("&")) {
            final var separator = pair.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            if (pair.regionMatches(true, 0, name, 0, separator) && separator == name.length()) {
                final var value = URLDecoder.decode(pair.substring(separator + 1), UTF_8);
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }
}
