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
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.ia.agent.github.service.RepositorySelector;
import java.util.List;

@RootConfiguration("github")
public record GithubBotConfiguration(
        @Property(
                defaultValue = "\".yca-github\"",
                documentation = "Private host state directory, never mounted in executors.")
        String home,

        @Property(defaultValue = "\"https://api.github.com\"", documentation = "GitHub REST API base URL.")
        String api,

        @Property(defaultValue = "\"yca-bot\"", documentation = "Mention login without @.")
        String login,

        @Property(
                defaultValue = "0",
                documentation =
                        "Minimum interval in milliseconds between host REST request starts within one process (0"
                                + " disables pacing, maximum 60000). Not a cross-process quota.")
        int requestIntervalMillis,

        @Property(documentation = "Host and isolated agent authentication.")
        Auth auth,

        @Property(documentation = "Request authorization policy.")
        Authorization authorization,

        @Property(documentation = "Repository selection policy.")
        Repositories repositories) {
    @JsonModel
    public record Auth(
            @Property(
                    defaultValue = "io.yupiik.ia.agent.github.configuration.GithubBotConfiguration.AuthType.pat",
                    documentation = "Host authentication type: pat or app.")
            AuthType type,

            @Property(
                    defaultValue = "\"GH_TOKEN\"",
                    documentation = "Environment variable holding the host token, never passed to agents.")
            String tokenEnv,

            @Property(defaultValue = "\"\"", documentation = "GitHub App identifier for app authentication.")
            String appId,

            @Property(defaultValue = "0L", documentation = "Positive GitHub App installation identifier.")
            long installationId,

            @Property(
                    defaultValue = "\"\"",
                    documentation = "Path to a PEM PKCS#8 RSA private key for app authentication.")
            String privateKeyFile,

            @Property(
                    defaultValue = "\"GH_AGENT_TOKEN\"",
                    documentation = "Environment variable for the isolated agent credential, distinct from host"
                            + " authentication.")
            String agentTokenEnv) {}

    /** Lowercase constants preserve existing configuration and JSON spelling. */
    @JsonModel
    public enum AuthType {
        pat,
        app
    }

    public record Authorization(
            @Property(
                    defaultValue = "\"\"",
                    documentation = "Comma-separated immutable numeric GitHub user IDs, not usernames."
                            + " To find your own ID, open https://api.github.com/users/YOUR_USERNAME"
                            + " and copy the numeric 'id' field (not 'node_id')."
                            + " IDs remain stable when usernames change."
                            + " Listed IDs are an unconditional authorization override, independent of repository"
                            + " permissions and teams.")
            String userIds,

            @Property(
                    defaultValue = "\"\"",
                    documentation =
                            "Optional comma-separated org/team slugs restricting only the repository-writer path."
                                    + " Writers must be active members of at least one team; API errors fail closed.")
            String teams,

            @Property(
                    defaultValue = "true",
                    documentation = "Authorize actors with write permission on the target repository.")
            boolean allowRepositoryWriters,

            @Property(
                    defaultValue = "7200L",
                    documentation = "Repository permission cache TTL in seconds; zero disables caching.")
            long permissionsCacheTtlSeconds,

            @Property(
                    defaultValue = "4096",
                    documentation = "Maximum cached repository authorization decisions, from 1 to 65536.")
            int permissionsCacheMaxEntries) {
        public Authorization {
            if (permissionsCacheTtlSeconds < 0) {
                throw new IllegalArgumentException("permissionsCacheTtlSeconds must be nonnegative");
            }
            if (permissionsCacheMaxEntries < 1 || permissionsCacheMaxEntries > 65536) {
                throw new IllegalArgumentException("permissionsCacheMaxEntries must be between 1 and 65536");
            }
        }
    }

    public record Repositories(
            @Property(defaultValue = "\"\"", documentation = "Comma-separated explicit owner/repository names.")
            String explicit,

            @Property(
                    defaultValue = "\"\"",
                    documentation = "Comma-separated typed discovery sources, e.g. org:yupiik,user:alice.")
            String discovery,

            @Property(
                    defaultValue = "\"\"",
                    documentation = "Comma-separated repository includes, supporting owner/repository globs.")
            String includes,

            @Property(defaultValue = "\"\"", documentation = "Comma-separated repository exclusion globs.")
            String excludes,

            @Property(
                    defaultValue =
                            "java.util.List.of(io.yupiik.ia.agent.github.service.RepositorySelector.Visibility.PUBLIC, io.yupiik.ia.agent.github.service.RepositorySelector.Visibility.PRIVATE, io.yupiik.ia.agent.github.service.RepositorySelector.Visibility.INTERNAL)",
                    documentation =
                            "Allowed repository visibility values: PUBLIC, PRIVATE, INTERNAL (comma-separated).")
            List<RepositorySelector.Visibility> visibility,

            @Property(
                    defaultValue = "false",
                    documentation = "Exclude archived repositories from discovery and explicit selection.")
            boolean excludeArchived,

            @Property(defaultValue = "false", documentation = "Exclude forks from discovery and explicit selection.")
            boolean excludeForks) {}
}
