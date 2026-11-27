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
import java.util.List;

/**
 * In-process, 1:1 reimplementation of a safe subset of read-only bash survey commands (cat, ls, head,
 * tail, echo, pwd, cd, sed range-print, wc, grep/find/rg via the dedicated search tools). When enabled,
 * {@code BashTool} recognizes a command made only of supported segments chained by {@code &&} or {@code ;}
 * (no pipe, no substitution, no write redirect, paths inside the project) and runs it through these Java
 * reimplementations instead of spawning a real shell (bash when available, /bin/sh otherwise): output is
 * byte-faithful to the real command, the only permitted deviation being cap/truncation. Anything not
 * confidently recognized falls back to the real shell.
 */
@JsonModel
public record BashOverridesConfiguration(
        @Property(
                defaultValue = "true",
                documentation =
                        "Master switch for the bash read-only override. When false no command is rewritten and every"
                                + " bash call runs through the real shell exactly as before.")
        boolean enabled,

        @Property(
                defaultValue = "0",
                documentation =
                        "Per-command output cap in characters (0 = use the tools.maxOutputChars cap). Each rewritten"
                                + " segment output is truncated at this budget so a survey cannot bloat the context;"
                                + " identical in spirit to the bash output cap.")
        int maxOutputChars,

        @Property(
                defaultValue =
                        "java.util.List.of(\"cat\",\"ls\",\"head\",\"tail\",\"echo\",\"pwd\",\"cd\",\"sed\",\"wc\",\"grep\",\"find\",\"rg\")",
                documentation =
                        "Which read-only commands are eligible for the in-process reimplementation. Any command not in"
                                + " this set (or used with an unsupported option / in a pipe / with a substitution) is"
                                + " executed in real bash.")
        List<String> commands) {

    public boolean keeps(final String name) {
        return enabled && (commands == null || commands.isEmpty() || commands.contains(name));
    }
}
