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
public record CommandStartMatcherConfiguration(
        @Property(
                documentation =
                        "Leading tokens a command segment must start with, eg. `[git,status]` or `[java,-version]`."
                                + " Matching is token based (like opencode arity), so `git diff` does not match `git difftool`.")
        CommandOptionsConfiguration commandStart,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation = "Option sets rejected even when `commandStart` matches, eg. `[-i]` rejects `sed -i`."
                        + " Single token entries are matched as a plain token set (long options also match their `=` forms,"
                        + " so `--output` rejects `--output=out.txt`), multi token entries must appear consecutively.")
        List<CommandOptionsConfiguration> excludeOptions) {}
