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
package io.yupiik.ia.agent.core.shell;

import java.util.regex.Pattern;

/** Display-only projection of a persisted subagent delivery; never rewrites the underlying message. */
public record SubagentDeliveryView(String subagentId, String name, String state, String summary) {
    // Match only complete generated envelopes, not quoted/inline examples or partial messages.
    private static final Pattern ENVELOPE = Pattern.compile(
            "\\A<subagent sessionID=\"([^\"\\r\\n]+)\" state=\"(completed|failed|cancelled)\" description=\"([^\"\\r\\n]*)\">\\n(.*)\\n</subagent>\\z",
            Pattern.DOTALL);

    /** Returns the display metadata, or {@code null} when the text is not a complete delivery envelope. */
    public static SubagentDeliveryView parse(final String text) {
        if (text == null) {
            return null;
        }
        final var match = ENVELOPE.matcher(text);
        return match.matches()
                ? new SubagentDeliveryView(match.group(1), match.group(3), match.group(2), match.group(4))
                : null;
    }
}
