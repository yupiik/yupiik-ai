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
package io.yupiik.ia.agent.core.context;

/**
 * Best-effort text estimate with blended punctuation density. Capacity guards apply uncertainty
 * separately, so displayed estimates do not include an unconditional safety multiplier.
 */
public final class TokenEstimator {
    private static final double PROSE_RATE = 0.25;
    private static final double DENSE_RATE = 0.50;

    private TokenEstimator() {}

    public static int estimate(final String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        var tokens = 0.0;
        var codeChars = 0;
        for (var i = 0; i < text.length(); i++) {
            final var c = text.charAt(i);
            if (c == '{' || c == '}' || c == '(' || c == ')' || c == '=' || c == ';' || c == '<' || c == '>' || c == '_'
                    || c == '"' || c == '[' || c == ']' || c == ',' || c == ':' || c == '+' || c == '-' || c == '/'
                    || c == '\\' || c == '|' || c == '&' || c == '!') {
                codeChars++;
            }
        }
        final var density = Math.min(1.0, codeChars / (text.length() * 0.20));
        tokens += text.length() * (PROSE_RATE + (DENSE_RATE - PROSE_RATE) * density);
        return (int) Math.ceil(tokens);
    }
}
