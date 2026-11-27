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

/** Presentation-only split: never parses or executes YAML, and never consumes an unclosed header. */
record SkillDocument(String metadata, String body) {
    private static final Pattern FRONT_MATTER = Pattern.compile(
            "\\A\\uFEFF?---[ \\t]*\\r?\\n(.*?)^(?:---|\\.\\.\\.)[ \\t]*(?:\\r?\\n|\\z)",
            Pattern.DOTALL | Pattern.MULTILINE);

    static SkillDocument parse(final String source) {
        final var matcher = FRONT_MATTER.matcher(source);
        return matcher.find()
                ? new SkillDocument(matcher.group(1), source.substring(matcher.end()))
                : new SkillDocument(null, source);
    }
}
