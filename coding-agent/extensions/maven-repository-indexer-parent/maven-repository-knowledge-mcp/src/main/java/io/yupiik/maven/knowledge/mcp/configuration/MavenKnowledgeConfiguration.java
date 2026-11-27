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
package io.yupiik.maven.knowledge.mcp.configuration;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

/**
 * Configuration for the maven-repository-knowledge MCP server. All keys are prefixed with {@code yca.maven.}.
 */
@ApplicationScoped
@RootConfiguration("yca.maven")
public record MavenKnowledgeConfiguration(
        @Property(defaultValue = "\"~/.m2/repository\"", documentation = "Local Maven repository to index.")
        String localRepository,

        @Property(
                defaultValue = "\"\"",
                documentation = "Maven project directory used to resolve the project's effective dependencies "
                        + "(direct + transitive + build plugins/extensions). When blank, the server working "
                        + "directory ({@code user.dir}) is used; when no directory can be determined the project "
                        + "dependency resolve is skipped.")
        String projectDir,

        @Property(defaultValue = "4", documentation = "Maximum artifacts processed concurrently.")
        int concurrency,

        @Property(
                defaultValue = "256",
                documentation =
                        "Hard maximum GAVs per single LLM call (primary bulk-size lever; also bounds JSON output).")
        int batchSize,

        @Property(
                defaultValue = "-1",
                documentation = "Entry-points batch sizing: -1 suits each batch to the model budget when known and "
                        + "otherwise falls back to GAV-count batching; any positive value is a hard maximum of input "
                        + "content characters per batch. Uses batchSize as a hard upper bound on GAVs in all cases.")
        int maxBatchChars,

        @Property(
                defaultValue = "1048576",
                documentation = "Maximum size (in characters) of the content kept per artifact when extracting "
                        + "sources or Javadoc. Larger content is truncated to this budget. A value <= 0 disables "
                        + "the per-artifact cap.")
        int maxContentBytes,

        @Property(defaultValue = "true", documentation = "Write the remote -skills.md manifest per artifact.")
        boolean remoteManifest,

        @Property(defaultValue = "false", documentation = "Always regenerate, bypassing the immutable-skip rule.")
        boolean force,

        @Property(
                defaultValue = "false",
                documentation =
                        "When true, enrich per-artifact knowledge with an LLM-written skill file via the MCP sampling "
                                + "protocol (validated and discarded when not a plausible SKILL.md). Off by default.")
        boolean useLlm,

        @Property(
                defaultValue = "500",
                documentation = "Maximum source files scanned per artifact when building the sources content part.")
        int maxSourceFiles,

        @Property(
                defaultValue = "65536",
                documentation =
                        "Maximum characters kept per source file when building the sources content part; larger files "
                                + "are truncated.")
        int maxSourceFileChars,

        @Property(
                defaultValue = "500",
                documentation = "Maximum javadoc class pages rendered per artifact when building the javadoc parts.")
        int maxJavadocPages) {}
