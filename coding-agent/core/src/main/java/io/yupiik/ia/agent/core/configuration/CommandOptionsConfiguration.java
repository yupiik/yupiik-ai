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
package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

@JsonModel
public record CommandOptionsConfiguration(
        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Tokens of this option set, comma separated (eg. `git,status` for a command start or `-i,--in-place` for excluded options)."
                                + " Entries containing spaces are split on whitespace too, so `git status` and `git,status` are equivalent.")
        List<String> options) {
    private static final Pattern OPTION_SEPARATOR = Pattern.compile("[,\\s]+");

    /**
     * Flattened view used by the bash rule matcher: every entry is split on commas and whitespace and blanks
     * are dropped, so `git status`, `git,status` and the JSON array form all produce the `[git, status]` token
     * sequence (the flat configuration reader splits commas before this, direct record uses may not).
     */
    public List<String> normalizedOptions() {
        if (options == null || options.isEmpty()) {
            return List.of();
        }
        return options.stream()
                .filter(o -> o != null && !o.isBlank())
                .flatMap(o -> Arrays.stream(OPTION_SEPARATOR.split(o.strip())))
                .filter(o -> !o.isEmpty())
                .toList();
    }
}
