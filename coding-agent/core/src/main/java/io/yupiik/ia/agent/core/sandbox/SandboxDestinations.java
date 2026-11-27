/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.sandbox;

import java.io.IOException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/** Destination capabilities, with DNS answers validated before a pinned-address connect. */
public final class SandboxDestinations {
    private final List<String> domains;

    public SandboxDestinations(final List<String> domains) {
        this.domains = domains == null
                ? List.of()
                : domains.stream().map(SandboxDestinations::normalizeRule).toList();
    }

    public boolean allows(final URI uri) {
        final var host = host(uri);
        final var port = port(uri);
        return domains.stream().anyMatch(rule -> {
            final var colon = rule.lastIndexOf(':');
            final var name = rule.substring(0, colon);
            if (Integer.parseInt(rule.substring(colon + 1)) != port) {
                return false;
            }
            return name.startsWith("*.")
                    ? host.endsWith(name.substring(1)) && !host.equals(name.substring(2))
                    : host.equals(name);
        });
    }

    public InetAddress[] resolve(final URI uri) throws IOException {
        if (!allows(uri)) {
            throw new SecurityException("Sandbox network access not granted: " + host(uri) + ":" + port(uri)
                    + "; request this destination with sandbox_access");
        }
        final var addresses = InetAddress.getAllByName(host(uri));
        for (final var address : addresses) {
            if (!isPublic(address)) {
                throw new SecurityException("Sandbox rejects non-public destination: " + host(uri));
            }
        }
        return addresses;
    }

    public static String host(final URI uri) {
        if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getRawUserInfo() != null) {
            throw new SecurityException("Sandbox requires an HTTP(S) destination without user info");
        }
        var host = IDN.toASCII(uri.getHost()).toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host;
    }

    public static int port(final URI uri) {
        final var port = uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
        if (port < 1 || port > 65535) {
            throw new SecurityException("Invalid sandbox destination port");
        }
        return port;
    }

    public static String normalizeRule(final String rule) {
        if (rule == null || rule.isBlank() || rule.contains("/") || rule.contains("@")) {
            throw new IllegalArgumentException("Expected sandbox domain[:port]: " + rule);
        }
        final var colon = rule.lastIndexOf(':');
        final var name = colon < 0 ? rule : rule.substring(0, colon);
        final var wildcard = name.startsWith("*.");
        final var domain = IDN.toASCII(wildcard ? name.substring(2) : name).toLowerCase(Locale.ROOT);
        if (domain.isBlank() || domain.contains("*") || domain.endsWith(".")) {
            throw new IllegalArgumentException("Invalid sandbox domain: " + rule);
        }
        final var port = colon < 0 ? 443 : Integer.parseInt(rule.substring(colon + 1));
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid sandbox domain port: " + rule);
        }
        return (wildcard ? "*." : "") + domain + ":" + port;
    }

    public static boolean isPublic(final InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        final var bytes = address.getAddress();
        if (bytes.length == 4) {
            final var a = bytes[0] & 255;
            final var b = bytes[1] & 255;
            final var c = bytes[2] & 255;
            return a != 0
                    && a != 10
                    && a != 127
                    && a < 224
                    && !(a == 100 && b >= 64 && b <= 127)
                    && !(a == 169 && b == 254)
                    && !(a == 172 && b >= 16 && b <= 31)
                    && !(a == 192 && (b == 168 || b == 0 || (b == 2)))
                    && !(a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                    && !(a == 203 && b == 0 && c == 113);
        }
        // Only global unicast 2000::/3; exclude documentation and transition encodings.
        return (bytes[0] & 0xe0) == 0x20
                && !((bytes[0] & 255) == 0x20
                        && (bytes[1] & 255) == 1
                        && ((bytes[2] == 0 && (bytes[3] & 255) == 0)
                                || ((bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8)))
                && !((bytes[0] & 255) == 0x20 && (bytes[1] & 255) == 2);
    }
}
