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

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.github.configuration.GithubExecutorConfiguration;
import io.yupiik.ia.agent.github.configuration.PromptConfiguration;
import io.yupiik.ia.agent.github.configuration.WorkerOptions.SourceMode;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PromptTemplatesTest {
    @Nested
    @FusionSupport(modules = ComponentsModule.class)
    class Components {
        @Fusion
        private PromptConfiguration prompts;

        @Fusion
        private GithubExecutorConfiguration executorRoot;

        @Test
        void everyModeUsesOnlyConfiguredComponentsAndLiteralJsonTaskData() {
            final var request = "\"<&' {{env SECRET}} {{{exec command}}} {{> main}}\n";
            try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
                final var preparation = new AttemptPreparation(json, prompts, executorRoot.shared());
                for (final var kind : List.of("review", "solve", "fix")) {
                    for (final var mode : SourceMode.values()) {
                        for (final var commenting : List.of(false, true)) {
                            final var task = TaskFixtures.task(Map.of(
                                    "id",
                                    "task-1",
                                    "repository",
                                    "owner/repo",
                                    "kind",
                                    kind,
                                    "number",
                                    7,
                                    "request",
                                    request));
                            final boolean valid = !(commenting && (mode != SourceMode.export || kind.equals("fix")))
                                    && !(mode == SourceMode.git && !kind.equals("solve"));
                            if (!valid) {
                                assertThrows(
                                        IllegalArgumentException.class,
                                        () -> preparation.prompt(task, "fork", commenting, mode));
                                continue;
                            }
                            final var result = preparation.prompt(task, "fork", commenting, mode);
                            final var source =
                                    switch (mode) {
                                        case export -> "exportSource";
                                        case git -> "gitSource";
                                        case broker -> "brokerSource";
                                    };
                            final var expectedPrefix = List.of(
                                            "commonPolicy",
                                            "headings",
                                            source,
                                            commenting ? "agentPublication" : "strictPublication",
                                            kind,
                                            "toolchain",
                                            "resultContract")
                                    .stream()
                                    .map(name -> "CUSTOM \"{" + name + "}\"")
                                    .collect(Collectors.joining("|"));
                            assertTrue(result.startsWith(expectedPrefix + "|"), result);
                            assertTrue(
                                    result.endsWith("|" + json.toString(new AttemptPreparation.UserRequest(request))),
                                    result);
                            final var taskData = result.substring(expectedPrefix.length() + 1, result.lastIndexOf('|'));
                            final var identity = (Map<?, ?>) json.fromString(Object.class, taskData);
                            assertEquals("owner/repo", identity.get("repository"));
                            assertEquals(kind, identity.get("kind"));
                            assertFalse(result.contains("&quot;"), result);
                            assertFalse(result.contains("untrusted task data"), result);
                        }
                    }
                }
            }
        }
    }

    @Nested
    @FusionSupport(modules = PartialsModule.class)
    class Partials {
        @Fusion
        private PromptConfiguration prompts;

        @Fusion
        private GithubExecutorConfiguration executorRoot;

        @Test
        void mainCanUseConfiguredPartialsWithoutEvaluatingInsertedData() {
            try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
                final var configuration = prompts;
                final var task = TaskFixtures.task(Map.of(
                        "id",
                        "task-1",
                        "repository",
                        "owner/repo",
                        "number",
                        7,
                        "kind",
                        "solve",
                        "request",
                        "{{> missing}} {{env SECRET}}"));
                assertEquals(
                        "DATA=" + json.toString(new AttemptPreparation.UserRequest(task.request())),
                        new AttemptPreparation(json, configuration, executorRoot.shared()).prompt(task, "fork", false));
            }
        }
    }

    @Nested
    @FusionSupport(modules = InvalidModule.class)
    class Invalid {
        @Fusion
        private PromptConfiguration prompts;

        @Fusion
        private GithubExecutorConfiguration executorRoot;

        @Test
        void malformedUnusedComponentFailsAtConstructionWithConfigurationKeyAndNoPromptLeak() {
            try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
                final var error = assertThrows(
                        IllegalArgumentException.class,
                        () -> new AttemptPreparation(json, prompts, executorRoot.shared()));
                assertTrue(error.getMessage().contains("github.prompts"));
                assertTrue(error.getMessage().contains("fix"));
                assertFalse(error.getMessage().contains("sensitive trusted instruction"));
                assertNull(error.getCause());
            }
        }
    }

    @Nested
    @FusionSupport(modules = GithubConfigurationTestModule.class)
    class DisabledGuidance {
        @Fusion
        private PromptConfiguration prompts;

        @Fusion
        private GithubExecutorConfiguration executorRoot;

        @Test
        void disabledDownloadsHaveNoDownloadGuidance() {
            try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
                final var disabled = new AttemptPreparation(json, prompts, executorRoot.shared())
                        .prompt(
                                TaskFixtures.task(Map.of(
                                        "id", "task-1", "repository", "owner/repo", "number", 7, "kind", "solve")),
                                "fork",
                                false);
                assertTrue(disabled.contains("downloads are disabled"));
                assertFalse(disabled.contains("If necessary download"));
            }
        }
    }

    @Nested
    @FusionSupport(modules = GuidanceModule.class)
    class Guidance {
        @Fusion
        private PromptConfiguration prompts;

        @Fusion
        private GithubExecutorConfiguration executorRoot;

        @Test
        void defaultGuidanceSeparatesProjectToolchainFromFixedHelperAndReportsEvidence() {
            final var configuration = prompts;
            try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
                final var preparation = new AttemptPreparation(json, configuration, executorRoot.shared());
                final var result = preparation.prompt(
                        TaskFixtures.task(
                                Map.of("id", "task-1", "repository", "owner/repo", "number", 7, "kind", "solve")),
                        "fork",
                        false,
                        SourceMode.broker);
                for (final var instruction : List.of(
                        "pom.xml",
                        ".mvn/",
                        "toolchains.xml",
                        "CI configuration",
                        "do not blindly use Java 25",
                        "java.home",
                        "per-command environment",
                        "attempt-local",
                        "SHA-256 or SHA-512",
                        "same official origins",
                        "Never disable TLS",
                        "sudo or global installs",
                        "https://api.azul.com/metadata/v1/docs/swagger",
                        "https://cdn.azul.com/zulu/bin/",
                        "https://maven.apache.org/download.cgi",
                        "https://archive.apache.org/dist/maven/",
                        "severity, path:line",
                        "Summary, Findings and Tests",
                        "failed/skipped",
                        "use [] if no checks ran")) {
                    assertTrue(result.contains(instruction), instruction);
                }
            }
        }
    }

    public static class ComponentsModule extends GithubConfigurationTestModule {
        public ComponentsModule() {
            super(key -> {
                if (key.equals("github.prompts.main")) {
                    return "{{{commonPolicy}}}|{{{headings}}}|{{{source}}}|{{{publication}}}|{{{taskKind}}}|{{{toolchain}}}|{{{resultContract}}}|{{{taskEnvelope}}}";
                }
                if (key.equals("github.prompts.taskEnvelope")) {
                    return "{{{taskJson}}}|{{{requestJson}}}";
                }
                if (key.startsWith("github.prompts.")) {
                    return "CUSTOM \"{" + key.substring("github.prompts.".length()) + "}\"";
                }
                return null;
            });
        }
    }

    public static class PartialsModule extends GithubConfigurationTestModule {
        public PartialsModule() {
            super(Map.of(
                    "github.prompts.main",
                    "{{> taskEnvelope}}",
                    "github.prompts.taskEnvelope",
                    "DATA={{{requestJson}}}"));
        }
    }

    public static class InvalidModule extends GithubConfigurationTestModule {
        public InvalidModule() {
            super(Map.of("github.prompts.fix", "sensitive trusted instruction {{broken"));
        }
    }

    public static class GuidanceModule extends GithubConfigurationTestModule {
        public GuidanceModule() {
            super(services(Map.of()));
        }
    }

    @Nested
    @FusionSupport(modules = Runtime0Module.class)
    class Runtime0 {
        @Test
        void runtimePolicyMatchesEffectiveEgress(
                @Fusion final PromptConfiguration prompts, @Fusion final GithubExecutorConfiguration executorRoot) {
            assertRuntimePolicy(prompts, executorRoot.shared());
        }
    }

    public static class Runtime0Module extends GithubConfigurationTestModule {
        public Runtime0Module() {
            super(runtimeValues(Map.of()));
        }
    }

    @Nested
    @FusionSupport(modules = Runtime1Module.class)
    class Runtime1 {
        @Test
        void runtimePolicyMatchesEffectiveEgress(
                @Fusion final PromptConfiguration prompts, @Fusion final GithubExecutorConfiguration executorRoot) {
            assertRuntimePolicy(prompts, executorRoot.shared());
        }
    }

    public static class Runtime1Module extends GithubConfigurationTestModule {
        public Runtime1Module() {
            super(runtimeValues(Map.of(
                    "github.executor.docker.egress.image",
                    "proxy",
                    "github.executor.docker.egress.allowedHosts",
                    "custom.example")));
        }
    }

    @Nested
    @FusionSupport(modules = Runtime2Module.class)
    class Runtime2 {
        @Test
        void runtimePolicyMatchesEffectiveEgress(
                @Fusion final PromptConfiguration prompts, @Fusion final GithubExecutorConfiguration executorRoot) {
            assertRuntimePolicy(prompts, executorRoot.shared());
        }
    }

    public static class Runtime2Module extends GithubConfigurationTestModule {
        public Runtime2Module() {
            super(runtimeValues(Map.of(
                    "github.executor.docker.egress.image",
                    "proxy",
                    "github.executor.docker.egress.allowedHosts",
                    "custom.example",
                    "github.executor.docker.egress.enabled",
                    "false")));
        }
    }

    @Nested
    @FusionSupport(modules = Runtime3Module.class)
    class Runtime3 {
        @Test
        void runtimePolicyMatchesEffectiveEgress(
                @Fusion final PromptConfiguration prompts, @Fusion final GithubExecutorConfiguration executorRoot) {
            assertRuntimePolicy(prompts, executorRoot.shared());
        }
    }

    public static class Runtime3Module extends GithubConfigurationTestModule {
        public Runtime3Module() {
            super(runtimeValues(services(Map.of())));
        }
    }

    @Nested
    @FusionSupport(modules = Runtime4Module.class)
    class Runtime4 {
        @Test
        void runtimePolicyMatchesEffectiveEgress(
                @Fusion final PromptConfiguration prompts, @Fusion final GithubExecutorConfiguration executorRoot) {
            assertRuntimePolicy(prompts, executorRoot.shared());
        }
    }

    public static class Runtime4Module extends GithubConfigurationTestModule {
        public Runtime4Module() {
            super(runtimeValues(services(Map.of("dependencyEgress.allowedHosts", "custom.example,*.example.org"))));
        }
    }

    @Nested
    @FusionSupport(modules = Runtime5Module.class)
    class Runtime5 {
        @Test
        void runtimePolicyMatchesEffectiveEgress(
                @Fusion final PromptConfiguration prompts, @Fusion final GithubExecutorConfiguration executorRoot) {
            assertRuntimePolicy(prompts, executorRoot.shared());
        }
    }

    public static class Runtime5Module extends GithubConfigurationTestModule {
        public Runtime5Module() {
            super(runtimeValues(services(Map.of("dependencyEgress.enabled", "false"))));
        }
    }

    @Nested
    @FusionSupport(modules = Runtime6Module.class)
    class Runtime6 {
        @Test
        void runtimePolicyMatchesEffectiveEgress(
                @Fusion final PromptConfiguration prompts, @Fusion final GithubExecutorConfiguration executorRoot) {
            assertRuntimePolicy(prompts, executorRoot.shared());
        }
    }

    public static class Runtime6Module extends GithubConfigurationTestModule {
        public Runtime6Module() {
            super(runtimeValues(services(Map.of("dependencyEgress.allowedHosts", ""))));
        }
    }

    private static Map<String, String> runtimeValues(final Map<String, String> executor) {
        final var values = new LinkedHashMap<>(executor);
        values.put("github.prompts.main", "{{dependencyDownloadsEnabled}}|{{{allowedDownloadHostsJson}}}");
        return values;
    }

    private void assertRuntimePolicy(final PromptConfiguration prompts, final ExecutorConfiguration executor) {
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var docker = executor.docker();
            final var effective = docker.services() == null
                    ? docker.egress()
                    : docker.services().dependencyEgress();
            final boolean enabled = effective != null
                    && effective.enabled()
                    && !effective.allowedHosts().isEmpty();
            final var hosts = enabled ? effective.allowedHosts() : List.of();
            assertEquals(
                    enabled + "|" + json.toString(hosts),
                    new AttemptPreparation(json, prompts, executor)
                            .prompt(
                                    TaskFixtures.task(Map.of(
                                            "id", "task-1", "repository", "owner/repo", "number", 7, "kind", "solve")),
                                    "fork",
                                    false));
        }
    }

    private static Map<String, String> services(final Map<String, String> overrides) {
        final var values = new LinkedHashMap<>(Map.of(
                "github.executor.docker.services.image", "trusted-services",
                "github.executor.docker.services.controlNetwork", "control",
                "github.executor.docker.services.keyStore", "/trusted/services.p12",
                "github.executor.docker.services.providerTarget", "http://172.20.0.1:8080/v1"));
        overrides.forEach((key, value) -> values.put("github.executor.docker.services." + key, value));
        return values;
    }
}
