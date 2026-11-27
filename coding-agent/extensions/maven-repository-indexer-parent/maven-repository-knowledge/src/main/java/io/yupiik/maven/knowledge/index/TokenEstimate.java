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
package io.yupiik.maven.knowledge.index;

/**
 * Conservative token estimate used to size entry-points batches to the model budget without a tokenizer
 * dependency (the knowledge module is framework-free). Mirrors the estimation performed on the yca side:
 * dense, code/JSON-heavy text is charged at a higher per-character rate and a safety margin is applied so
 * batches under-fill rather than overflow the context window.
 */
public final class TokenEstimate {
    private static final double PROSE_RATE = 0.30;
    private static final double DENSE_RATE = 0.50;
    private static final double SAFETY_MARGIN = 1.2;

    private TokenEstimate() {
        // utility
    }

    public static int of(final String text) {
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
        tokens += text.length() * (codeChars > 0 ? DENSE_RATE : PROSE_RATE);
        tokens *= SAFETY_MARGIN;
        return (int) Math.ceil(tokens);
    }
}
