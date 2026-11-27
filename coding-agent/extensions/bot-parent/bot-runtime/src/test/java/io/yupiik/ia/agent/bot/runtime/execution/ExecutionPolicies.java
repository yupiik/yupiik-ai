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
package io.yupiik.ia.agent.bot.runtime.execution;

import io.yupiik.ia.agent.bot.runtime.ExecutionPolicy;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

final class ExecutionPolicies {
    static final ExecutionPolicy CONTEXT = new ExecutionPolicy(
            "fixture.context",
            new ExecutionPolicy.Gateway(
                    "io.yupiik.ia.agent.github.execution.RestrictedServicesGateway",
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
    static final ExecutionPolicy PROVIDER = new ExecutionPolicy(
            "fixture.provider",
            new ExecutionPolicy.Gateway(
                    "io.yupiik.ia.agent.bot.gateway.ProviderServicesGateway",
                    Set.of("YCA_SERVICES_PROVIDER_TOKEN", "YCA_SERVICES_UPSTREAM_TOKEN"),
                    Set.of("YCA_SERVICES_PROVIDER_TOKEN")));

    private ExecutionPolicies() {}

    static Stream<Arguments> runtimes() {
        return Stream.of(CONTEXT, PROVIDER)
                .flatMap(policy -> List.of("yca-arbitrary-42", "yca-slack-misleading", "yca-github-misleading").stream()
                        .map(runtime -> Arguments.of(policy, runtime)));
    }
}
