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

import java.util.regex.Pattern;

/**
 * A {@link ShapePreservingSecretMatcher} for the common {@code $prefix$body} credential shape: a
 * literal prefix (e.g. {@code ghp_}, {@code AKIA}, {@code sk-ant-api03-}) followed by a body
 * described by a regex / length. The fake keeps the same {@code prefix} and a body of the same
 * length (shared by the base {@code replace}), so the LLM still treats it like a token of its
 * provider while never seeing the real value.
 *
 * <p>Subclasses are usually a one-liner naming their family and calling the super constructor with
 * the prefix and a body regex.</p>
 */
public abstract class PrefixSecretMatcher extends ShapePreservingSecretMatcher {

    protected final String prefix;
    protected final int minBodyLength;
    private final Pattern pattern;

    /**
     * @param prefix literal leading token, e.g. {@code ghp_}
     * @param bodyRegex regex matching the body (the part after {@code prefix}), e.g. {@code [A-Za-z0-9]{36}}
     */
    protected PrefixSecretMatcher(final String prefix, final String bodyRegex) {
        this(prefix, bodyRegex, DEFAULT_ALPHABET, 8);
    }

    protected PrefixSecretMatcher(
            final String prefix, final String bodyRegex, final String bodyAlphabet, final int minBodyLength) {
        super(bodyAlphabet);
        this.prefix = prefix;
        this.minBodyLength = minBodyLength;
        this.pattern = Pattern.compile("^" + Pattern.quote(prefix) + "(" + bodyRegex + ")$");
    }

    /**
     * Configured-prefix constructor: any value starting with {@code prefix} and at least
     * {@code minBodyLength} body characters (base64url-ish body) is a secret. The body regex is
     * derived here, so config-driven prefix families do not fight over an opaque body regex.
     */
    protected PrefixSecretMatcher(final String prefix, final int minBodyLength) {
        this(prefix, "[A-Za-z0-9_-]" + "{" + minBodyLength + ",}", DEFAULT_ALPHABET, minBodyLength);
    }

    @Override
    public boolean matches(final String value) {
        if (value == null || !value.startsWith(prefix)) {
            return false;
        }
        final var body = value.substring(prefix.length());
        if (body.length() < minBodyLength) {
            return false;
        }
        return pattern.matcher(value).matches();
    }

    @Override
    protected int bodyStart(final String value) {
        return prefix.length();
    }

    @Override
    public int order() {
        return prefix.length();
    }

    @Override
    public String label() {
        return prefix;
    }
}
