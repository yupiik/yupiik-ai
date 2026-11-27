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
package io.yupiik.ia.agent.core.shell.slash;

import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import java.util.List;

/**
 * The single registration unit of a slash command: its catalog entry
 * ({@link SlashCommandSpec}), its argument completion and its body. Declaring
 * the three together is what removes the former spec-catalog /
 * dispatch-table / completion-table drift.
 */
public record SlashCommand(SlashCommandSpec spec, ArgCompletion completion, SlashAction action) {

    public SlashCommand {
        if (spec == null) {
            throw new IllegalArgumentException("spec is required");
        }
        if (action == null) {
            throw new IllegalArgumentException("action is required for " + spec.name());
        }
    }

    /** A command without argument completion. */
    public static SlashCommand of(final SlashCommandSpec spec, final SlashAction action) {
        return new SlashCommand(spec, ArgCompletion.NONE, action);
    }

    /** A command completing against a closed set of options. */
    public static SlashCommand of(final SlashCommandSpec spec, final List<String> options, final SlashAction action) {
        return new SlashCommand(spec, ArgCompletion.options(options.toArray(String[]::new)), action);
    }

    /** A command with a custom argument completion. */
    public static SlashCommand of(
            final SlashCommandSpec spec, final ArgCompletion completion, final SlashAction action) {
        return new SlashCommand(spec, completion, action);
    }
}
