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
package io.yupiik.ia.agent.tui;

/**
 * Text truncation helpers shared by the TUI renderers.
 */
final class TuiTextRender {
    private TuiTextRender() {
        // utility
    }

    static String ellipsize(final String text, final int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return max <= 3 ? text.substring(0, Math.max(0, max)) : text.substring(0, max - 3) + "...";
    }

    /**
     * Keeps the trailing characters when the text is too long, truncating the head with "...":
     * used where the meaningful tail matters most (e.g. the cwd's deepest folder segments) instead
     * of the head-keeping {@link #ellipsize(String, int)} behaviour.
     */
    static String ellipsizeLeading(final String text, final int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return max <= 3 ? text.substring(text.length() - max) : "..." + text.substring(text.length() - max + 3);
    }
}
