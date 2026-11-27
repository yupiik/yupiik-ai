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
package io.yupiik.ia.agent.github.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

/** Worker-sanitized agent-side capability configuration. Never contains credentials. */
@RootConfiguration("githubContext")
public record AgentContextConfiguration(
        @Property(defaultValue = "false", documentation = "Enable the worker context capability.")
        boolean enabled,

        @Property(defaultValue = "\"\"", documentation = "Attempt-specific HTTPS RPC endpoint.")
        String endpoint,

        @Property(defaultValue = "\"YCA_CONTEXT_TOKEN\"", documentation = "Control token environment variable.")
        String tokenEnv,

        @Property(defaultValue = "\"YCA_CONTEXT_GIT_TOKEN\"", documentation = "Git token environment variable.")
        String gitTokenEnv,

        @Property(defaultValue = "\"\"", documentation = "Canonical primary repository.")
        String primaryRepository) {}
