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
 * Tool output optimization settings (rtk style token reduction).
 *
 * <p>Every processor can be individually disabled either globally ({@code disabled},
 * loose command-name matching) or per tool through {@code toolOutputs.<tool>.enabled}
 * (default {@code true}): a disabled processor behaves as an identity (passthrough)
 * implementation, so no processor error ever surfaces from a disabled path.</p>
 */
@JsonModel
public record OptimizeConfiguration(
        @Property(defaultValue = "true", documentation = "Enable the rtk style tool output optimization.")
        boolean enabled,

        @Property(
                defaultValue = "400",
                documentation = "Maximum number of lines kept by the generic fallback processor (head+tail).")
        int genericMaxLines,

        @Property(
                defaultValue = "20",
                documentation = "Maximum number of file/test entries listed by the processors before truncation.")
        int maxEntries,

        @Property(
                defaultValue = "10",
                documentation =
                        "Minimum size reduction, in percent, for an optimized tool output to replace the original one; smaller wins are ignored to avoid pointless rewrites.")
        int minReductionPercent,

        @Property(
                defaultValue = "true",
                documentation =
                        "Strip a leading well-known command wrapper before detecting the command head for output "
                                + "processors, so `timeout 120 mvn ...`, `sudo mvn ...`, `nohup git ...`, `env FOO=1 mvn ...`, "
                                + "`command mvn ...` or `sh -c \"mvn ...\"` are all matched and optimized as the inner command "
                                + "(e.g. `mvn ...`). Handled wrappers (chained and repeatable): sudo, nohup, timeout <seconds>, "
                                + "env, command, and sh/bash/zsh/dash -c. A multi-command script (containing `;`, `&&` or `|`) "
                                + "inside a -c string is left untouched for the generic fallback. Off disables the unwrapping.")
        boolean skipCommandWrapper,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Processor or tool names to disable. Processors: mvn, mvnd, mvnw, gradle, gradlew, git, gh, npm, pnpm, prettier, generic. Matched loosely: './' and path prefixes are dropped, only the file name is kept, known extensions (.cmd, .exe, .sh) are ignored and comparison is case insensitive.")
        List<String> disabled,

        @Property(
                documentation =
                        "Per-tool output tuning, one field per processor; every entry carries an `enabled` flag "
                                + "(true by default) to disable a single processor (identity passthrough). "
                                + "Absent entries use the built-in defaults.")
        ProcessorOutputConfiguration toolOutputs,

        @Property(
                documentation =
                        "Shared framework/test-runner log-noise filter (yupiik-logging-jul, Spring Boot, Tomcat, "
                                + "Jetty formats). `excludes` drops matching lines from the transcript; `includes` "
                                + "keeps a line even when it also matches an exclude (include wins). Used by the maven "
                                + "and gradle output processors (and reusable for spring-cli) so the massive runtime "
                                + "INFO/debug logs of a test run do not bloat every subsequent LLM turn. By default "
                                + "dropped levels are INFO/FINE/FINER/FINEST/CONFIG/DEBUG; WARNING/SEVERE/ERROR are "
                                + "never dropped.")
        LogNoiseConfiguration logNoise) {}
