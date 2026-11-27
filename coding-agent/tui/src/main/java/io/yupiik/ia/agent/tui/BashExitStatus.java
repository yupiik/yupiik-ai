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

import java.util.regex.Pattern;

/** Shared presentation-only classification for tool cards and captured output overlays. */
final class BashExitStatus {
    private static final Pattern FOOTER = Pattern.compile("(?:^|\\n)Exit code: (-?[0-9]+)(?:\\r?\\n)?\\z");

    private BashExitStatus() {}

    static boolean failed(final String name, final String output) {
        if (!"bash".equals(name) || output == null) {
            return false;
        }
        final var matcher = FOOTER.matcher(output);
        if (!matcher.find()) {
            return false;
        }
        try {
            return Integer.parseInt(matcher.group(1)) != 0;
        } catch (final NumberFormatException invalid) {
            return false;
        }
    }
}
