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

@JsonModel
public record BashPermissionsConfiguration(
        @Property(
                defaultValue = "io.yupiik.ia.agent.core.configuration.CommandPermissionConfiguration.defaultRules()",
                documentation =
                        "Bash command rules evaluated for every `bash` tool call. Each rule matches command segments"
                                + " by their leading tokens (opencode arity style, eg. `git status`) and can exclude write"
                                + " option forms (eg. `sed` with `-i`). A rule resolving to `all` (yolo) for the active"
                                + " agent mode allows the command without prompting, any other value falls back to the"
                                + " normal ask/gate flow. Rules are evaluated per segment: every segment of a compound"
                                + " command must match an allowing rule, otherwise the whole command asks.")
        List<CommandPermissionConfiguration> rules,

        @Property(
                defaultValue =
                        "\"Plan-mode bash is limited to read-only command families (git status, git diff, git log, git show, ls, cat, grep, head, tail, unzip -l, jar tf, tar tf, ...); commands that mutate repository state such as git add are denied.\"",
                documentation = "Free text injected into the plan-mode system prompt summarizing which bash command"
                        + " families are treated as read-only (eg. \"git status/diff/log/show, ls, cat,"
                        + " grep, ...\"). Kept as curated prose rather than a dynamic render of `rules`"
                        + " for brevity and scalability: the full rule list is long and per-workspace,"
                        + " so the model only needs a compact, human-maintained summary. The rules"
                        + " remain the enforcement gate; this text only explains them to the model."
                        + " A blank value omits the sentence entirely.")
        String readOnlyPromptHints,

        @Property(
                defaultValue = "1024",
                documentation =
                        "Maximum number of compiled rule evaluations (configured rules merged with the session's"
                                + " dynamic overrides) kept in the application-scoped registry cache. When a session"
                                + " adds dynamic bash rules (per-session overrides cleared on switch back to plan and"
                                + " when the session closes), each distinct override set compiles once and is cached"
                                + " here; beyond this bound the whole combined cache is cleared and rebuilt on demand,"
                                + " so the memory footprint of dynamic overrides stays bounded. The configured rules"
                                + " themselves are compiled once for the app lifecycle and are not counted in this bound.")
        int maxCombined) {}
