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
 * Matches a JWT (three dot-separated base64url segments, typically starting with {@code eyJ}) and
 * rebuilds a fake token of the same structure (same segment lengths, base64url alphabet, same
 * leading characters) so the model still treats it as a JWT while never seeing the real signature
 * and payload.
 */
@ApplicationScoped
public class JwtSecretMatcher implements SecretMatcher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private static final Pattern JWT =
            Pattern.compile("^([A-Za-z0-9_-]{10,})\\.([A-Za-z0-9_-]{6,})\\.([A-Za-z0-9_-]{10,})$");

    @Override
    public boolean matches(final String value) {
        return value != null && value.startsWith("eyJ") && JWT.matcher(value).matches();
    }

    @Override
    public String replace(final String value) {
        final var m = JWT.matcher(value);
        if (!m.matches()) {
            return value;
        }
        return fakeSeg(m.group(1)) + "." + fakeSeg(m.group(2)) + "." + fakeSeg(m.group(3));
    }

    private static String fakeSeg(final String seg) {
        final var out = new StringBuilder(seg.length());
        for (int i = 0; i < seg.length(); i++) {
            out.append(B64URL.charAt(RANDOM.nextInt(B64URL.length())));
        }
        return out.toString();
    }

    @Override
    public int order() {
        return 9_100;
    }

    @Override
    public String label() {
        return "jwt";
    }
}
