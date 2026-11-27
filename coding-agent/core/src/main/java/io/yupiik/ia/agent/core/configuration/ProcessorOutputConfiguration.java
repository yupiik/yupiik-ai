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

/**
 * Per-tool output optimization tuning, one field per processor. Absent entries use
 * the built-in defaults and every processor can be individually disabled with its
 * own {@code enabled} flag (default {@code true}): a disabled processor behaves as
 * an identity (passthrough) implementation.
 */
@JsonModel
public record ProcessorOutputConfiguration(
        @Property(documentation = "Generic fallback processor tuning (head/tail truncation).")
        GenericProcessorOutputConfiguration generic,

        @Property(documentation = "Filesystem and search commands processor tuning (ls, find, grep, rg, ...).")
        FilesystemProcessorOutputConfiguration filesystem,

        @Property(documentation = "JavaScript/TypeScript tooling processor tuning (tsc, eslint, ...).")
        JsProcessorOutputConfiguration js,

        @Property(documentation = "JSON-aware processor tuning (kubectl, docker inspect, gh api, aws).")
        JsonProcessorOutputConfiguration json,

        @Property(documentation = "Kubernetes column-aware processor tuning (kubectl get/logs, ...).")
        KubectlProcessorOutputConfiguration kubectl,

        @Property(documentation = "Docker column-aware processor tuning (docker ps/images/logs, ...).")
        DockerProcessorOutputConfiguration docker,

        @Property(documentation = "Git output processor tuning (raw blob windowing, confirmations).")
        GitProcessorOutputConfiguration git,

        @Property(documentation = "Maven output processor tuning (surefire reports, stack traces).")
        MavenProcessorOutputConfiguration maven,

        @Property(documentation = "Gradle output processor tuning (warnings, tasks).")
        GradleProcessorOutputConfiguration gradle,

        @Property(documentation = "GitHub CLI processor tuning (pr view, run list, issue view).")
        GhProcessorOutputConfiguration gh,

        @Property(documentation = "npm/yarn/pnpm processor tuning.")
        NpmProcessorOutputConfiguration npm,

        @Property(documentation = "prettier processor tuning.")
        PrettierProcessorOutputConfiguration prettier) {}
