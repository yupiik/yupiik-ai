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

@JsonModel
public record ToolsConfiguration(
        @Property(documentation = "Embedded (built-in) tools settings.")
        BuiltinToolsConfiguration builtin,

        @Property(documentation = "Subagent catalog and model-routing allowlists.")
        SpawnSubagentToolConfiguration spawnSubagent,

        @Property(documentation = "Web search tool settings.")
        WebSearchConfiguration websearch,

        @Property(documentation = "manage_context tool settings (model-driven context management).")
        ManageContextToolConfiguration manageContext,

        @Property(
                defaultValue = "true",
                documentation =
                        "Run consecutive read-only tool calls of a single model reply concurrently on virtual threads"
                                + " (eg. parallel read/grep batches). State-changing tools stay sequential and the"
                                + " message order always follows the model's call order. Set to false for fully"
                                + " sequential tool execution.")
        boolean parallelReadonly,

        @Property(
                defaultValue = "65536",
                documentation =
                        "Maximum characters of a bash command output kept in the transcript (and returned to the model);"
                                + " beyond it the output is truncated with a marker. Mirrors opencode's ~50 KiB tool-output"
                                + " cap (64 KiB default keeps a little headroom) so a verbose test/build log cannot bloat"
                                + " every later LLM turn (see also optimize.logNoise for framework log filtering).")
        int maxOutputChars,

        @Property(
                defaultValue = "3",
                documentation =
                        "Doom-loop success threshold, for the models that do not set a per-model behavior or per-provider"
                                + " tuning repeatSuccessThreshold: the maximum number of identical tool calls (same tool + same"
                                + " normalized arguments) whose result succeeded AND carried no new information (identical"
                                + " output, ignoring embedded dates/timestamps) before the next one is refused with a"
                                + " strategy-change directive. A failing call is never counted (it is a legitimate retry), and an"
                                + " interleaved failure or productive round resets the streak. 0 disables the guard entirely.")
        int repeatSuccessThreshold,

        @Property(
                defaultValue = "false",
                documentation =
                        "When enabled, the code_resolve tool also appends its short Maven artifact-knowledge note"
                                + " (which dependency provides the symbol, read with maven_knowledge_content) when the"
                                + " symbol was found in the local code graph. Disabled by default: with graph hits the"
                                + " note is skipped to keep resolve output lean. Requires the Maven repository knowledge"
                                + " feature to be enabled.")
        boolean alwaysSuggestMavenKnowledge,

        @Property(
                documentation =
                        "Which shell binary the bash tool launches with `-c`. When unset or `auto` (default) it uses a"
                                + " real bash when one is found in the well-known locations (/bin/bash, /usr/bin/bash)"
                                + " so the model can rely on bash-isms (PIPESTATUS, ${VAR:-x}, [[ ]], =~ ...) that a"
                                + " minimal /bin/sh (dash on Debian/Ubuntu) would reject with 'Bad substitution'; it"
                                + " falls back to /bin/sh when no bash is present (alpine, Windows without Git Bash/WSL)."
                                + " Explicit `bash`, `sh` or `dash` pins the binary; when the pinned one cannot be found"
                                + " the tool falls back to /bin/sh and reports the deviation in the output.")
        String shell,

        @Property(documentation = "In-process reimplementation of a safe subset of read-only bash survey commands.")
        BashOverridesConfiguration bashOverrides) {}
