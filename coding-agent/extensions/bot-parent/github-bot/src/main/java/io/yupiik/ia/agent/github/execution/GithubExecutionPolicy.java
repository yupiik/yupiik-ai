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
package io.yupiik.ia.agent.github.execution;

import io.yupiik.ia.agent.bot.runtime.ExecutionPolicy;
import java.util.Set;

/** Trusted GitHub adapter capability contract; not selected by runtime names. */
public final class GithubExecutionPolicy {
    public static final ExecutionPolicy POLICY = new ExecutionPolicy(
            "io.yupiik.yca.github-bot",
            new ExecutionPolicy.Gateway(
                    RestrictedServicesGateway.class.getName(),
                    Set.of(
                            "YCA_CONTEXT_TOKEN",
                            "YCA_CONTEXT_GIT_TOKEN",
                            "YCA_SERVICES_PROVIDER_TOKEN",
                            "YCA_SERVICES_UPSTREAM_TOKEN"),
                    Set.of(
                            "YCA_CONTEXT_TOKEN",
                            "YCA_CONTEXT_GIT_TOKEN",
                            "YCA_SERVICES_PROVIDER_TOKEN",
                            "YCA_SERVICES_UPSTREAM_TOKEN")));

    private GithubExecutionPolicy() {}
}
