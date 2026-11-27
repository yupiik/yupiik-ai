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
package io.yupiik.ia.agent.core.security;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * Matches a PEM private-key block ({@code -----BEGIN ... PRIVATE KEY----- ... -----END ...----})
 * and replaces the whole block with a fake of the same header/trailer and a redacted-looking body,
 * so the model sees a well-formed key shape but never the material.
 */
@ApplicationScoped
public class PemKeySecretMatcher implements SecretMatcher {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A private-key header (RSA / EC / DSA / OPENSSH / ENCRYPTED / PGP / bare PRIVATE KEY). */
    public static final Pattern HEADER = Pattern.compile("-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY(?: BLOCK)?-----");

    private static final Pattern BLOCK = Pattern.compile(
            "(-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY(?: BLOCK)?-----)([\\s\\S]*?)(-----END (?:[A-Z ]+ )?PRIVATE KEY(?: BLOCK)?-----)");

    @Override
    public boolean matches(final String value) {
        return value != null
                // quick exit
                && value.contains("-----BEGIN")
                && value.contains("PRIVATE KEY")
                // mor precise matching
                && HEADER.matcher(value).find()
                && BLOCK.matcher(value).matches();
    }

    @Override
    public String replace(final String value) {
        final var m = BLOCK.matcher(value);
        if (!m.matches()) {
            return value;
        }
        final var header = m.group(1);
        final var body = m.group(2);
        final var trailer = m.group(3);
        // preserve the general line-shape: same line count, fake base64 bodies of matching length
        return header + fakeBlock(body) + trailer;
    }

    /** Replaces the base64 body of a PEM block with a fake of matching line/length shape. */
    public static String fakeBlock(final String body) {
        final var lines = body.split("\n", -1);
        final var sb = new StringBuilder();
        for (final var line : lines) {
            final var t = line.trim();
            if (t.isEmpty()) {
                sb.append('\n');
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            // keep the leading structure on the first line, base64-looking fake after
            sb.append(fakeLine(t));
        }
        return sb.toString();
    }

    private static String fakeLine(final String line) {
        final char[] alnum = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=".toCharArray();
        final var out = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            final var c = line.charAt(i);
            out.append(
                    Character.isLetterOrDigit(c) || c == '/' || c == '+' || c == '='
                            ? alnum[RANDOM.nextInt(alnum.length - 1)]
                            : c);
        }
        return out.toString();
    }

    @Override
    public int order() {
        return 9_500; // beat token-level matchers: PEM is line-based and only this matcher handles it
    }

    @Override
    public String label() {
        return "pem-private-key";
    }
}
