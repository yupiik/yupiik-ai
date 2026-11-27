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

import java.security.SecureRandom;

/**
 * Shared base for matchers that replace a secret value with a format-preserving fake: the leading
 * part (a provider prefix, an assignment key, an authorization scheme) is kept verbatim and the
 * trailing body is regenerated character-by-character, keeping separators/hyphens and replacing
 * alphanumerics with random ones from the matcher's alphabet. The model therefore still sees a
 * well-formed token of the same family and length, never the real value.
 *
 * <p>Subclasses only provide {@link #matches} (the value-level predicate) and {@link #bodyStart} (the
 * offset in the value where the regenerable body begins). This removes the duplicate
 * length-preserving {@code replace} logic that used to live in {@link PrefixSecretMatcher} and
 * {@link RegexSecretMatcher}.</p>
 */
public abstract class ShapePreservingSecretMatcher implements SecretMatcher {

    /** Default token body alphabet (base64url-ish letters + digits, no separators). */
    public static final String DEFAULT_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String alphabet;

    protected ShapePreservingSecretMatcher(final String alphabet) {
        this.alphabet = alphabet == null || alphabet.isEmpty() ? DEFAULT_ALPHABET : alphabet;
    }

    protected ShapePreservingSecretMatcher() {
        this(DEFAULT_ALPHABET);
    }

    /**
     * @param value a value accepted by {@link #matches(String)}
     * @return the character offset in {@code value} where the regenerable body begins (0 = the whole
     *     value); everything before the offset is preserved verbatim by {@link #replace}.
     */
    protected int bodyStart(final String value) {
        return 0;
    }

    @Override
    public String replace(final String value) {
        final int start = Math.min(bodyStart(value), value.length());
        String out = value.substring(0, start) + regenerate(value.substring(start));
        return out;
    }

    /**
     * Builds a random format-preserving fake of the same length and shape as {@code value}: every
     * alphanumeric is replaced with a random one from {@link #DEFAULT_ALPHABET}, separators/hyphens are
     * kept. Used when a matcher produces the fake and as the shape-blind fallback of the redactor for
     * values no matcher recognizes (e.g. opaque exact-harvested values, custom {@code patterns}), so
     * two values of equal length get distinct fakes and never collide on the restore side. Recognized
     * prefix values always go through {@link #replace} so their leading prefix is preserved.
     */
    public static String regenerate(final String value) {
        if (value == null) {
            return null;
        }
        final var out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            final var c = value.charAt(i);
            // keep separators/hyphens positionally so the fake keeps the token shape
            out.append(
                    Character.isLetterOrDigit(c)
                            ? DEFAULT_ALPHABET.charAt(RANDOM.nextInt(DEFAULT_ALPHABET.length()))
                            : c);
        }
        return out.toString();
    }
}
