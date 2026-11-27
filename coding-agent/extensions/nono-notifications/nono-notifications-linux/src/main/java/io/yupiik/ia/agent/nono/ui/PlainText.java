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
package io.yupiik.ia.agent.nono.ui;

import java.util.Objects;

/** Display policy for untrusted text, independent of the desktop transport. */
final class PlainText {
    private static final int LIMIT = 4096;
    private static final String TRUNCATED = "... [truncated]";

    private PlainText() {
        // no-op
    }

    static String sanitize(final String text) {
        Objects.requireNonNull(text, "text");
        final var out = new StringBuilder(Math.min(text.length(), LIMIT));
        int offset = 0;
        while (offset < text.length()) {
            final var cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp == 0x1b || cp == 0x9b || cp == 0x9d) {
                offset = skipEscape(text, offset, cp);
                continue;
            }
            final var type = Character.getType(cp);
            final var replacement = cp == '\n'
                    ? cp
                    : type == Character.CONTROL
                                    || type == Character.FORMAT
                                    || type == Character.SURROGATE
                                    || type == Character.LINE_SEPARATOR
                                    || type == Character.PARAGRAPH_SEPARATOR
                            ? ' '
                            : cp;
            if (out.length() + Character.charCount(replacement) > LIMIT - TRUNCATED.length()) {
                out.append(TRUNCATED);
                return out.toString();
            }
            out.appendCodePoint(replacement);
        }
        return out.toString();
    }

    private static int skipEscape(final String text, int offset, final int introducer) {
        int kind = introducer;
        if (introducer == 0x1b && offset < text.length()) {
            kind = text.charAt(offset++);
        }
        if (kind == '[' || kind == 0x9b) {
            while (offset < text.length()) {
                final var c = text.charAt(offset++);
                if (c >= 0x40 && c <= 0x7e) {
                    break;
                }
            }
        } else if (kind == ']' || kind == 0x9d || kind == 'P' || kind == '^' || kind == '_') {
            while (offset < text.length()) {
                final var c = text.charAt(offset++);
                if (c == 7 || c == 0x9c) {
                    break;
                }
                if (c == 0x1b && offset < text.length() && text.charAt(offset) == '\\') {
                    offset++;
                    break;
                }
            }
        }
        return offset;
    }
}
