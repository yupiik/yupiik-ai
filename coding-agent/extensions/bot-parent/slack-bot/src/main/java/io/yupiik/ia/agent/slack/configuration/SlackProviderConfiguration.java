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
package io.yupiik.ia.agent.slack.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

/** Trusted backend-specific provider deployment; never derived from Slack or repository content. */
@RootConfiguration("slack.providerServices")
public record SlackProviderConfiguration(
        @Property(
                defaultValue = "\"\"",
                documentation = "Required selected provider upstream for Nono or Kubernetes, never supplied by a Slack"
                        + " message: HTTPS for Nono, canonical numerical private IPv4 HTTP with an explicit port"
                        + " for Kubernetes. Must match the selected trusted provider URL, appending"
                        + " /chat/completions for COMPLETIONS or /responses for RESPONSES when absent.")
        String upstream,

        @Property(documentation = "Qualified native socket deployment.")
        Native nativeBridge,

        @Property(documentation = "Qualified Kubernetes provider deployment.")
        Kubernetes kubernetes) {
    public record Native(
            @Property(
                    defaultValue = "\"\"",
                    documentation =
                            "Pre-provisioned private short absolute socket root, disjoint from runtime, attempts,"
                                    + " state and provider-budget roots.")
            String socketRoot,

            @Property(
                    defaultValue = "false",
                    documentation = "Required operator assertion of qualified Linux/Nono pathname mediation and"
                            + " credential-free runtime; not an automatic probe or certification.")
            boolean qualified,

            @Property(
                    defaultValue = "false",
                    documentation = "Required operator assertion of stronger outer isolation hiding unrelated host IPC"
                            + " endpoints; pathname mediation has a sockaddr race and alone is not a race-free"
                            + " hostile-workload boundary.")
            boolean outerIsolationQualified) {}

    public record Kubernetes(
            @Property(
                    defaultValue = "\"\"",
                    documentation =
                            "Trusted provider gateway image containing Java and the Jib application classpath file.")
            String image,

            @Property(defaultValue = "\"\"", documentation = "Private TLS Secret containing gateway.p12.")
            String tlsSecret,

            @Property(
                    defaultValue = "\"attempt-services.yca.internal\"",
                    documentation = "Pinned TLS identity whose public CA is already trusted by the agent image.")
            String serverName,

            @Property(
                    defaultValue = "\"\"",
                    documentation = "Qualified node containing the shared local budget filesystem.")
            String nodeName,

            @Property(
                    defaultValue = "\"\"",
                    documentation = "Private node-local provider-budget PVC, distinct from the attempt PVC in"
                            + " slack.executor.kubernetes.claim.")
            String budgetClaim,

            @Property(defaultValue = "\"default\"", documentation = "Gateway service account; no token mounted.")
            String serviceAccount,

            @Property(defaultValue = "8443", documentation = "Gateway TLS port; validated range 1024-65535.")
            int port,

            @Property(
                    defaultValue = "false",
                    documentation =
                            "Required operator assertion of enforcing CNI, no additive allow policies, node/metadata"
                                    + " isolation and qualified same-host local budget storage; not an automatic probe or"
                                    + " certification.")
            boolean qualified) {}
}
