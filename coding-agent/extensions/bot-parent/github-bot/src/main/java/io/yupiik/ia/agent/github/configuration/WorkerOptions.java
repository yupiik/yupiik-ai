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

public record WorkerOptions(
        @Property(
                defaultValue = "2",
                documentation = "Execution retries after initial attempt (2 means at most 3 attempts).")
        int maxRetries,

        @Property(
                defaultValue = "1",
                documentation = "Maximum concurrent worker processes sharing this queue, with durable task leases and"
                        + " fixed slot locks. Each invocation processes at most one task.")
        int parallelism,

        @Property(defaultValue = "\"\"", documentation = "Optional path to trusted agent configuration.")
        String agentConfiguration,

        @Property(
                defaultValue = "\"\"",
                documentation = "Required fork owner for solve tasks. Fork must be pre-provisioned unless organization"
                        + " provisioning is enabled.")
        String forkOwner,

        @Property(
                defaultValue = "true",
                documentation =
                        "Allow agent GitHub credentials. False withholds both agent and fork credentials; host"
                                + " publishes structured reviews and bounded strict-solve changes using host credentials."
                                + " Strict solve requires host contents-write access to the verified fork and PR-write access upstream.")
        boolean agentCommenting,

        @Property(
                defaultValue = "false",
                documentation = "Opt in to durable organization fork provisioning. Fork owner must be an organization"
                        + " and host credential must have required fork permissions.")
        boolean provisionFork,

        @Property(
                defaultValue = "\"\"",
                documentation = "Optional private host bare-clone cache for anonymous public github.com repositories."
                        + " Never mounted in agents; empty disables caching.")
        String cacheRoot,

        @Property(
                defaultValue = "\"git\"",
                documentation = "Trusted Git executable used by the optional host clone cache.")
        String cacheGit,

        @Property(
                defaultValue = "false",
                documentation =
                        "Stage a bounded, integrity-checked private source snapshot for strict reviews using host authentication. Requires repository contents read permission; no credentials enter the snapshot.")
        boolean privateSource,

        @Property(documentation = "Source preparation for strict solves; export remains the default.")
        Source source,

        @Property(documentation = "Repository-isolated dependency download cache policy.")
        DependencyCacheConfiguration dependencyCache) {
    public enum SourceMode {
        export,
        git,
        broker
    }

    public record Source(
            @Property(
                    defaultValue = "io.yupiik.ia.agent.github.configuration.WorkerOptions.SourceMode.export",
                    documentation =
                            "Source mode: export (default), agent-managed git, or credential-free worker broker. Broker requires agentCommenting=false.")
            SourceMode mode,

            @Property(
                    defaultValue = "\"\"",
                    documentation =
                            "Optional dedicated read-only source PAT environment variable; empty uses anonymous Git access.")
            String tokenEnv,

            @Property(defaultValue = "1000", documentation = "Maximum Git fetch depth, from 1 to 100000.")
            int maxDepth,

            @Property(defaultValue = "536870912L", documentation = "Maximum Git source bytes, from 1 MiB to 16 GiB.")
            long maxBytes,

            @Property(defaultValue = "120", documentation = "Git command timeout in seconds, from 1 to 3600.")
            int commandTimeoutSeconds) {
        public Source {
            if (mode == null || tokenEnv == null) {
                throw new IllegalArgumentException("Source mode and token environment must be configured");
            }
            if (mode != SourceMode.git && !tokenEnv.isEmpty()) {
                throw new IllegalArgumentException("Source token environment requires git mode");
            }
            if (maxDepth < 1 || maxDepth > 100000) {
                throw new IllegalArgumentException("Source maximum depth must be between 1 and 100000");
            }
            if (maxBytes < 1024L * 1024 || maxBytes > 16L * 1024 * 1024 * 1024) {
                throw new IllegalArgumentException("Source maximum bytes must be between 1 MiB and 16 GiB");
            }
            if (commandTimeoutSeconds < 1 || commandTimeoutSeconds > 3600) {
                throw new IllegalArgumentException("Source command timeout must be between 1 and 3600 seconds");
            }
        }
    }
}
