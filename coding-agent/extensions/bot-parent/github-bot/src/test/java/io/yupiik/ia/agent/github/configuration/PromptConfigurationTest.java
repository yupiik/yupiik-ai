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

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.github.configuration.WorkerOptions.SourceMode;
import io.yupiik.ia.agent.github.execution.AttemptPreparation;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PromptConfigurationTest {
    @Nested
    @FusionSupport(modules = GithubConfigurationTestModule.class)
    class Defaults {
        @TempDir
        Path home;

        @Test
        void generatedDefaultsSelectSourceTaskAndPublicationForEveryExecutionMode(
                @Fusion final PromptConfiguration prompts, @Fusion final GithubExecutorConfiguration executorRoot) {
            try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
                final var preparation = new AttemptPreparation(json, prompts, executorRoot.shared());
                assertThrows(
                        IllegalArgumentException.class,
                        () -> preparation.prompt(task("solve", "request"), "fork", false, null));
                for (final var kind : List.of("review", "solve", "fix")) {
                    for (final var mode : SourceMode.values()) {
                        for (final var commenting : List.of(false, true)) {
                            final var task = task(kind, "request");
                            if (!valid(kind, mode, commenting)) {
                                assertThrows(
                                        IllegalArgumentException.class,
                                        () -> preparation.prompt(task, "fork", commenting, mode));
                                continue;
                            }
                            final var output = preparation.prompt(task, "fork", commenting, mode);
                            assertTrue(output.contains("untrusted task data"), output);
                            assertTrue(output.contains("Summary, Findings and Tests"), output);
                            assertTrue(output.contains("/attempt/output/result.json"), output);
                            assertTrue(output.contains("USER REQUEST (data):"), output);
                            assertEquals(mode == SourceMode.git, output.contains("AGENT-MANAGED GIT SOURCE:"));
                            assertEquals(mode == SourceMode.broker, output.contains("BROKERED CONTEXT:"));
                            assertEquals(mode == SourceMode.export, output.contains("github-source.json"));
                            assertEquals(commenting, output.contains("You MAY publish a single native COMMENT review"));
                            assertEquals(!commenting, output.contains("STRICT MODE:"));
                            assertEquals(kind.equals("review"), output.contains("For review: obtain current head SHA"));
                            assertEquals(
                                    kind.equals("solve"), output.contains("Implement and test the requested change"));
                            assertEquals(kind.equals("fix"), output.contains("FIX ONLY:"));
                        }
                    }
                }
            }
        }

        @Test
        void eachGeneratedPropertyReplacesOnlyItsSelectedComponentAndRemovesOldParagraph(
                @Fusion final PromptConfiguration prompts, @Fusion final GithubExecutorConfiguration executorRoot) {
            final var paragraphs = Map.ofEntries(
                    Map.entry("main", "You are a GitHub development assistant"),
                    Map.entry("commonPolicy", "You are a GitHub development assistant"),
                    Map.entry("headings", "Presentation: use concise Summary"),
                    Map.entry("exportSource", "github-source.json"),
                    Map.entry("gitSource", "AGENT-MANAGED GIT SOURCE:"),
                    Map.entry("brokerSource", "BROKERED CONTEXT:"),
                    Map.entry("strictPublication", "STRICT MODE:"),
                    Map.entry("agentPublication", "Fetch context using gh"),
                    Map.entry("review", "For review: obtain current head SHA"),
                    Map.entry("solve", "Implement and test the requested change"),
                    Map.entry("fix", "FIX ONLY:"),
                    Map.entry("toolchain", "Toolchain: select the project's actual JDK"),
                    Map.entry("resultContract", "Write a single JSON object"),
                    Map.entry("taskEnvelope", "Task identity and metadata (JSON data):"));
            assertEquals(prompts.sources().keySet(), paragraphs.keySet());
            try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
                final var defaults = new AttemptPreparation(json, prompts, executorRoot.shared());
                for (final var entry : paragraphs.entrySet()) {
                    final var marker = "REPLACEMENT-" + entry.getKey();
                    final var configured = new AttemptPreparation(
                            json, withComponent(prompts, entry.getKey(), marker), executorRoot.shared());
                    for (final var kind : List.of("review", "solve", "fix")) {
                        for (final var mode : SourceMode.values()) {
                            for (final var commenting : List.of(false, true)) {
                                final var task = task(kind, "request");
                                if (!valid(kind, mode, commenting)) {
                                    assertThrows(
                                            IllegalArgumentException.class,
                                            () -> configured.prompt(task, "fork", commenting, mode));
                                    continue;
                                }
                                final var before = defaults.prompt(task, "fork", commenting, mode);
                                final var after = configured.prompt(task, "fork", commenting, mode);
                                if (!selected(entry.getKey(), kind, mode, commenting)) {
                                    assertEquals(before, after, entry.getKey());
                                    continue;
                                }
                                assertNotEquals(before, after, entry.getKey());
                                assertTrue(before.contains(entry.getValue()), entry.getKey());
                                assertTrue(after.contains(marker), entry.getKey());
                                assertFalse(after.contains(entry.getValue()), entry.getKey());
                                if (entry.getKey().equals("main")) {
                                    assertEquals(marker, after);
                                }
                            }
                        }
                    }
                }
            }
        }

        @Test
        void invalidConfiguredTemplatesFailInConstructorBeforeCreatingAnyAttempt(
                @Fusion final PromptConfiguration prompts, @Fusion final GithubExecutorConfiguration executorRoot)
                throws Exception {
            final var trusted = Files.writeString(home.resolve("trusted.json"), "{}");
            final var attempts = home.resolve("attempts");
            try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
                for (final var name : prompts.sources().keySet()) {
                    for (final var invalid : List.of("PRIVATE {{broken", "{{#if solve}}PRIVATE", "{{> missing}}")) {
                        final var error = assertThrows(
                                IllegalArgumentException.class,
                                () -> new AttemptPreparation(
                                                json, withComponent(prompts, name, invalid), executorRoot.shared())
                                        .prepare(trusted, attempts));
                        assertTrue(error.getMessage().contains("github.prompts"));
                        assertTrue(error.getMessage().contains(name));
                        assertFalse(error.getMessage().contains("PRIVATE"));
                        assertNull(error.getCause());
                    }
                }
                assertFalse(Files.exists(attempts));
                assertEquals("{}", Files.readString(trusted));
            }
        }

        @Test
        void defaultInvocationRemainsLiteralJson(
                @Fusion final PromptConfiguration prompts, @Fusion final GithubExecutorConfiguration executorRoot) {
            final var request = "{{> missing}} {{env SECRET}} {{{commonPolicy}}} \"quotes\" <tag>&\n";
            try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
                final var encoded = json.toString(new AttemptPreparation.UserRequest(request));
                final var output = new AttemptPreparation(json, prompts, executorRoot.shared())
                        .prompt(task("solve", request), "fork", false);
                assertTrue(output.endsWith("USER REQUEST (data): " + encoded), output);
            }
        }
    }

    @Nested
    @FusionSupport(modules = ConfiguredMainModule.class)
    class ConfiguredMain {
        @Test
        void bindsConfiguredComponent(@Fusion final PromptConfiguration prompts) {
            assertEquals("REPLACEMENT-main", prompts.sources().get("main"));
        }
    }

    public static class ConfiguredMainModule extends GithubConfigurationTestModule {
        public ConfiguredMainModule() {
            super(Map.ofEntries(Map.entry("github.prompts.main", "REPLACEMENT-main")));
        }
    }

    @Nested
    @FusionSupport(modules = ConfiguredCommonPolicyModule.class)
    class ConfiguredCommonPolicy {
        @Test
        void bindsConfiguredComponent(@Fusion final PromptConfiguration prompts) {
            assertEquals("REPLACEMENT-commonPolicy", prompts.sources().get("commonPolicy"));
        }
    }

    public static class ConfiguredCommonPolicyModule extends GithubConfigurationTestModule {
        public ConfiguredCommonPolicyModule() {
            super(Map.ofEntries(Map.entry("github.prompts.commonPolicy", "REPLACEMENT-commonPolicy")));
        }
    }

    @Nested
    @FusionSupport(modules = ConfiguredHeadingsModule.class)
    class ConfiguredHeadings {
        @Test
        void bindsConfiguredComponent(@Fusion final PromptConfiguration prompts) {
            assertEquals("REPLACEMENT-headings", prompts.sources().get("headings"));
        }
    }

    public static class ConfiguredHeadingsModule extends GithubConfigurationTestModule {
        public ConfiguredHeadingsModule() {
            super(Map.ofEntries(Map.entry("github.prompts.headings", "REPLACEMENT-headings")));
        }
    }

    @Nested
    @FusionSupport(modules = ConfiguredExportSourceModule.class)
    class ConfiguredExportSource {
        @Test
        void bindsConfiguredComponent(@Fusion final PromptConfiguration prompts) {
            assertEquals("REPLACEMENT-exportSource", prompts.sources().get("exportSource"));
        }
    }

    public static class ConfiguredExportSourceModule extends GithubConfigurationTestModule {
        public ConfiguredExportSourceModule() {
            super(Map.ofEntries(Map.entry("github.prompts.exportSource", "REPLACEMENT-exportSource")));
        }
    }

    @Nested
    @FusionSupport(modules = ConfiguredGitSourceModule.class)
    class ConfiguredGitSource {
        @Test
        void bindsConfiguredComponent(@Fusion final PromptConfiguration prompts) {
            assertEquals("REPLACEMENT-gitSource", prompts.sources().get("gitSource"));
        }
    }

    public static class ConfiguredGitSourceModule extends GithubConfigurationTestModule {
        public ConfiguredGitSourceModule() {
            super(Map.ofEntries(Map.entry("github.prompts.gitSource", "REPLACEMENT-gitSource")));
        }
    }

    @Nested
    @FusionSupport(modules = ConfiguredBrokerSourceModule.class)
    class ConfiguredBrokerSource {
        @Test
        void bindsConfiguredComponent(@Fusion final PromptConfiguration prompts) {
            assertEquals("REPLACEMENT-brokerSource", prompts.sources().get("brokerSource"));
        }
    }

    public static class ConfiguredBrokerSourceModule extends GithubConfigurationTestModule {
        public ConfiguredBrokerSourceModule() {
            super(Map.ofEntries(Map.entry("github.prompts.brokerSource", "REPLACEMENT-brokerSource")));
        }
    }

    @Nested
    @FusionSupport(modules = ConfiguredStrictPublicationModule.class)
    class ConfiguredStrictPublication {
        @Test
        void bindsConfiguredComponent(@Fusion final PromptConfiguration prompts) {
            assertEquals("REPLACEMENT-strictPublication", prompts.sources().get("strictPublication"));
        }
    }

    public static class ConfiguredStrictPublicationModule extends GithubConfigurationTestModule {
        public ConfiguredStrictPublicationModule() {
            super(Map.ofEntries(Map.entry("github.prompts.strictPublication", "REPLACEMENT-strictPublication")));
        }
    }

    @Nested
    @FusionSupport(modules = ConfiguredAgentPublicationModule.class)
    class ConfiguredAgentPublication {
        @Test
        void bindsConfiguredComponent(@Fusion final PromptConfiguration prompts) {
            assertEquals("REPLACEMENT-agentPublication", prompts.sources().get("agentPublication"));
        }
    }

    public static class ConfiguredAgentPublicationModule extends GithubConfigurationTestModule {
        public ConfiguredAgentPublicationModule() {
            super(Map.ofEntries(Map.entry("github.prompts.agentPublication", "REPLACEMENT-agentPublication")));
        }
    }

    @Nested
    @FusionSupport(modules = ConfiguredReviewModule.class)
    class ConfiguredReview {
        @Test
        void bindsConfiguredComponent(@Fusion final PromptConfiguration prompts) {
            assertEquals("REPLACEMENT-review", prompts.sources().get("review"));
        }
    }

    public static class ConfiguredReviewModule extends GithubConfigurationTestModule {
        public ConfiguredReviewModule() {
            super(Map.ofEntries(Map.entry("github.prompts.review", "REPLACEMENT-review")));
        }
    }

    @Nested
    @FusionSupport(modules = ConfiguredSolveModule.class)
    class ConfiguredSolve {
        @Test
        void bindsConfiguredComponent(@Fusion final PromptConfiguration prompts) {
            assertEquals("REPLACEMENT-solve", prompts.sources().get("solve"));
        }
    }

    public static class ConfiguredSolveModule extends GithubConfigurationTestModule {
        public ConfiguredSolveModule() {
            super(Map.ofEntries(Map.entry("github.prompts.solve", "REPLACEMENT-solve")));
        }
    }

    @Nested
    @FusionSupport(modules = ConfiguredFixModule.class)
    class ConfiguredFix {
        @Test
        void bindsConfiguredComponent(@Fusion final PromptConfiguration prompts) {
            assertEquals("REPLACEMENT-fix", prompts.sources().get("fix"));
        }
    }

    public static class ConfiguredFixModule extends GithubConfigurationTestModule {
        public ConfiguredFixModule() {
            super(Map.ofEntries(Map.entry("github.prompts.fix", "REPLACEMENT-fix")));
        }
    }

    @Nested
    @FusionSupport(modules = ConfiguredToolchainModule.class)
    class ConfiguredToolchain {
        @Test
        void bindsConfiguredComponent(@Fusion final PromptConfiguration prompts) {
            assertEquals("REPLACEMENT-toolchain", prompts.sources().get("toolchain"));
        }
    }

    public static class ConfiguredToolchainModule extends GithubConfigurationTestModule {
        public ConfiguredToolchainModule() {
            super(Map.ofEntries(Map.entry("github.prompts.toolchain", "REPLACEMENT-toolchain")));
        }
    }

    @Nested
    @FusionSupport(modules = ConfiguredResultContractModule.class)
    class ConfiguredResultContract {
        @Test
        void bindsConfiguredComponent(@Fusion final PromptConfiguration prompts) {
            assertEquals("REPLACEMENT-resultContract", prompts.sources().get("resultContract"));
        }
    }

    public static class ConfiguredResultContractModule extends GithubConfigurationTestModule {
        public ConfiguredResultContractModule() {
            super(Map.ofEntries(Map.entry("github.prompts.resultContract", "REPLACEMENT-resultContract")));
        }
    }

    @Nested
    @FusionSupport(modules = ConfiguredTaskEnvelopeModule.class)
    class ConfiguredTaskEnvelope {
        @Test
        void bindsConfiguredComponent(@Fusion final PromptConfiguration prompts) {
            assertEquals("REPLACEMENT-taskEnvelope", prompts.sources().get("taskEnvelope"));
        }
    }

    public static class ConfiguredTaskEnvelopeModule extends GithubConfigurationTestModule {
        public ConfiguredTaskEnvelopeModule() {
            super(Map.ofEntries(Map.entry("github.prompts.taskEnvelope", "REPLACEMENT-taskEnvelope")));
        }
    }

    @Nested
    @FusionSupport(modules = EgressCustomModule.class)
    class EgressCustom {
        @Test
        void bindsAndRenders(
                @Fusion final PromptConfiguration prompts, @Fusion final GithubExecutorConfiguration executorRoot) {
            try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
                final var output = new AttemptPreparation(json, prompts, executorRoot.shared())
                        .prompt(task("solve", "request"), "fork", false);
                assertEquals(
                        "allowed:[custom.example]\n[*.example.org]|[\"custom.example\",\"*.example.org\"]", output);
                assertFalse(output.contains("cdn.azul.com"));
                assertFalse(output.contains("repo.maven.apache.org"));
            }
        }
    }

    public static class EgressCustomModule extends GithubConfigurationTestModule {
        public EgressCustomModule() {
            super(Map.ofEntries(
                    Map.entry(
                            "github.prompts.main",
                            "{{#if dependencyDownloadsEnabled}}allowed:{{#each allowedDownloadHosts}}[{{{this}}}]{{/each}}{{else}}disabled{{/if}}|{{{allowedDownloadHostsJson}}}"),
                    Map.entry("github.executor.docker.egress.image", "proxy"),
                    Map.entry("github.executor.docker.egress.allowedHosts", "custom.example,*.example.org"),
                    Map.entry("github.executor.docker.egress.enabled", "true")));
        }
    }

    @Nested
    @FusionSupport(modules = EgressDisabledModule.class)
    class EgressDisabled {
        @Test
        void bindsAndRenders(
                @Fusion final PromptConfiguration prompts, @Fusion final GithubExecutorConfiguration executorRoot) {
            try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
                final var output = new AttemptPreparation(json, prompts, executorRoot.shared())
                        .prompt(task("solve", "request"), "fork", false);
                assertEquals("disabled|[]", output);
                assertFalse(output.contains("cdn.azul.com"));
                assertFalse(output.contains("repo.maven.apache.org"));
            }
        }
    }

    public static class EgressDisabledModule extends GithubConfigurationTestModule {
        public EgressDisabledModule() {
            super(Map.ofEntries(
                    Map.entry(
                            "github.prompts.main",
                            "{{#if dependencyDownloadsEnabled}}allowed:{{#each allowedDownloadHosts}}[{{{this}}}]{{/each}}{{else}}disabled{{/if}}|{{{allowedDownloadHostsJson}}}"),
                    Map.entry("github.executor.docker.egress.image", "proxy"),
                    Map.entry("github.executor.docker.egress.allowedHosts", "custom.example,*.example.org"),
                    Map.entry("github.executor.docker.egress.enabled", "false")));
        }
    }

    @Nested
    @FusionSupport(modules = EgressEmptyModule.class)
    class EgressEmpty {
        @Test
        void bindsAndRenders(
                @Fusion final PromptConfiguration prompts, @Fusion final GithubExecutorConfiguration executorRoot) {
            try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
                final var output = new AttemptPreparation(json, prompts, executorRoot.shared())
                        .prompt(task("solve", "request"), "fork", false);
                assertEquals("disabled|[]", output);
                assertFalse(output.contains("cdn.azul.com"));
                assertFalse(output.contains("repo.maven.apache.org"));
            }
        }
    }

    public static class EgressEmptyModule extends GithubConfigurationTestModule {
        public EgressEmptyModule() {
            super(Map.ofEntries(
                    Map.entry(
                            "github.prompts.main",
                            "{{#if dependencyDownloadsEnabled}}allowed:{{#each allowedDownloadHosts}}[{{{this}}}]{{/each}}{{else}}disabled{{/if}}|{{{allowedDownloadHostsJson}}}"),
                    Map.entry("github.executor.docker.egress.image", "proxy"),
                    Map.entry("github.executor.docker.egress.allowedHosts", ""),
                    Map.entry("github.executor.docker.egress.enabled", "true")));
        }
    }

    @Nested
    @FusionSupport(modules = ServicesCustomModule.class)
    class ServicesCustom {
        @Test
        void bindsAndRenders(
                @Fusion final PromptConfiguration prompts, @Fusion final GithubExecutorConfiguration executorRoot) {
            try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
                final var output = new AttemptPreparation(json, prompts, executorRoot.shared())
                        .prompt(task("solve", "request"), "fork", false);
                assertEquals(
                        "allowed:[custom.example]\n[*.example.org]|[\"custom.example\",\"*.example.org\"]", output);
                assertFalse(output.contains("cdn.azul.com"));
                assertFalse(output.contains("repo.maven.apache.org"));
            }
        }
    }

    public static class ServicesCustomModule extends GithubConfigurationTestModule {
        public ServicesCustomModule() {
            super(Map.ofEntries(
                    Map.entry("github.executor.docker.services.image", "trusted-services"),
                    Map.entry("github.executor.docker.services.controlNetwork", "control"),
                    Map.entry("github.executor.docker.services.keyStore", "/trusted/services.p12"),
                    Map.entry("github.executor.docker.services.providerTarget", "http://172.20.0.1:8080/v1"),
                    Map.entry(
                            "github.prompts.main",
                            "{{#if dependencyDownloadsEnabled}}allowed:{{#each allowedDownloadHosts}}[{{{this}}}]{{/each}}{{else}}disabled{{/if}}|{{{allowedDownloadHostsJson}}}"),
                    Map.entry("github.executor.docker.services.dependencyEgress.image", "proxy"),
                    Map.entry(
                            "github.executor.docker.services.dependencyEgress.allowedHosts",
                            "custom.example,*.example.org"),
                    Map.entry("github.executor.docker.services.dependencyEgress.enabled", "true")));
        }
    }

    @Nested
    @FusionSupport(modules = ServicesDisabledModule.class)
    class ServicesDisabled {
        @Test
        void bindsAndRenders(
                @Fusion final PromptConfiguration prompts, @Fusion final GithubExecutorConfiguration executorRoot) {
            try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
                final var output = new AttemptPreparation(json, prompts, executorRoot.shared())
                        .prompt(task("solve", "request"), "fork", false);
                assertEquals("disabled|[]", output);
                assertFalse(output.contains("cdn.azul.com"));
                assertFalse(output.contains("repo.maven.apache.org"));
            }
        }
    }

    public static class ServicesDisabledModule extends GithubConfigurationTestModule {
        public ServicesDisabledModule() {
            super(Map.ofEntries(
                    Map.entry("github.executor.docker.services.image", "trusted-services"),
                    Map.entry("github.executor.docker.services.controlNetwork", "control"),
                    Map.entry("github.executor.docker.services.keyStore", "/trusted/services.p12"),
                    Map.entry("github.executor.docker.services.providerTarget", "http://172.20.0.1:8080/v1"),
                    Map.entry(
                            "github.prompts.main",
                            "{{#if dependencyDownloadsEnabled}}allowed:{{#each allowedDownloadHosts}}[{{{this}}}]{{/each}}{{else}}disabled{{/if}}|{{{allowedDownloadHostsJson}}}"),
                    Map.entry("github.executor.docker.services.dependencyEgress.image", "proxy"),
                    Map.entry(
                            "github.executor.docker.services.dependencyEgress.allowedHosts",
                            "custom.example,*.example.org"),
                    Map.entry("github.executor.docker.services.dependencyEgress.enabled", "false")));
        }
    }

    @Nested
    @FusionSupport(modules = ServicesEmptyModule.class)
    class ServicesEmpty {
        @Test
        void bindsAndRenders(
                @Fusion final PromptConfiguration prompts, @Fusion final GithubExecutorConfiguration executorRoot) {
            try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
                final var output = new AttemptPreparation(json, prompts, executorRoot.shared())
                        .prompt(task("solve", "request"), "fork", false);
                assertEquals("disabled|[]", output);
                assertFalse(output.contains("cdn.azul.com"));
                assertFalse(output.contains("repo.maven.apache.org"));
            }
        }
    }

    public static class ServicesEmptyModule extends GithubConfigurationTestModule {
        public ServicesEmptyModule() {
            super(Map.ofEntries(
                    Map.entry("github.executor.docker.services.image", "trusted-services"),
                    Map.entry("github.executor.docker.services.controlNetwork", "control"),
                    Map.entry("github.executor.docker.services.keyStore", "/trusted/services.p12"),
                    Map.entry("github.executor.docker.services.providerTarget", "http://172.20.0.1:8080/v1"),
                    Map.entry(
                            "github.prompts.main",
                            "{{#if dependencyDownloadsEnabled}}allowed:{{#each allowedDownloadHosts}}[{{{this}}}]{{/each}}{{else}}disabled{{/if}}|{{{allowedDownloadHostsJson}}}"),
                    Map.entry("github.executor.docker.services.dependencyEgress.image", "proxy"),
                    Map.entry("github.executor.docker.services.dependencyEgress.allowedHosts", ""),
                    Map.entry("github.executor.docker.services.dependencyEgress.enabled", "true")));
        }
    }

    @Nested
    @FusionSupport(modules = ServicesDefaultHostsModule.class)
    class ServicesDefaultHosts {
        @Test
        void bindsAndRenders(
                @Fusion final PromptConfiguration prompts, @Fusion final GithubExecutorConfiguration executorRoot) {
            try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
                final var executor = executorRoot.shared();
                final var hosts =
                        executor.docker().services().dependencyEgress().allowedHosts();
                assertTrue(hosts.contains("cdn.azul.com"));
                final var expected = new StringBuilder("allowed:");
                expected.append(String.join(
                        "\n", hosts.stream().map(host -> "[" + host + "]").toList()));
                expected.append('|').append(json.toString(hosts));
                assertEquals(
                        expected.toString(),
                        new AttemptPreparation(json, prompts, executor)
                                .prompt(task("solve", "request"), "fork", false));
            }
        }
    }

    public static class ServicesDefaultHostsModule extends GithubConfigurationTestModule {
        public ServicesDefaultHostsModule() {
            super(
                    Map.ofEntries(
                            Map.entry("github.executor.docker.services.image", "trusted-services"),
                            Map.entry("github.executor.docker.services.controlNetwork", "control"),
                            Map.entry("github.executor.docker.services.keyStore", "/trusted/services.p12"),
                            Map.entry("github.executor.docker.services.providerTarget", "http://172.20.0.1:8080/v1"),
                            Map.entry(
                                    "github.prompts.main",
                                    "{{#if dependencyDownloadsEnabled}}allowed:{{#each allowedDownloadHosts}}[{{{this}}}]{{/each}}{{else}}disabled{{/if}}|{{{allowedDownloadHostsJson}}}")));
        }
    }

    @Nested
    @FusionSupport(modules = NoDownloadsModule.class)
    class NoDownloads {
        @Test
        void bindsAndRenders(
                @Fusion final PromptConfiguration prompts, @Fusion final GithubExecutorConfiguration executorRoot) {
            try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
                assertEquals(
                        "disabled|[]",
                        new AttemptPreparation(json, prompts, executorRoot.shared())
                                .prompt(task("solve", "request"), "fork", false));
            }
        }
    }

    public static class NoDownloadsModule extends GithubConfigurationTestModule {
        public NoDownloadsModule() {
            super(
                    Map.ofEntries(
                            Map.entry(
                                    "github.prompts.main",
                                    "{{#if dependencyDownloadsEnabled}}allowed:{{#each allowedDownloadHosts}}[{{{this}}}]{{/each}}{{else}}disabled{{/if}}|{{{allowedDownloadHostsJson}}}")));
        }
    }

    @Nested
    @FusionSupport(modules = LiteralInvocationModule.class)
    class LiteralInvocation {
        @Test
        void bindsAndRenders(
                @Fusion final PromptConfiguration prompts, @Fusion final GithubExecutorConfiguration executorRoot) {
            final var request = "{{> missing}} {{env SECRET}} {{{commonPolicy}}} \"quotes\" <tag>&\n";
            try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
                final var encoded = json.toString(new AttemptPreparation.UserRequest(request));
                final var output = new AttemptPreparation(json, prompts, executorRoot.shared())
                        .prompt(task("solve", request), "fork", false);
                assertEquals(encoded + "|" + encoded + "||||", output);
                assertFalse(output.contains("HOST-CONFIG-SECRET"));
                assertFalse(output.contains("&quot;"));
                assertFalse(output.contains("&lt;"));
                assertEquals(
                        request,
                        json.fromString(AttemptPreparation.UserRequest.class, encoded)
                                .request());
            }
        }
    }

    public static class LiteralInvocationModule extends GithubConfigurationTestModule {
        public LiteralInvocationModule() {
            super(Map.ofEntries(
                    Map.entry(
                            "github.prompts.main",
                            "{{{taskEnvelope}}}|{{{requestJson}}}|{{configuration}}|{{globalconfiguration}}|{{env}}|{{secrets}}"),
                    Map.entry("github.prompts.taskEnvelope", "{{{requestJson}}}"),
                    Map.entry("configuration", "HOST-CONFIG-SECRET"),
                    Map.entry("globalconfiguration", "HOST-CONFIG-SECRET"),
                    Map.entry("env", "HOST-CONFIG-SECRET"),
                    Map.entry("secrets", "HOST-CONFIG-SECRET"),
                    Map.entry("github.token", "HOST-CONFIG-SECRET")));
        }
    }

    private boolean valid(final String kind, final SourceMode mode, final boolean commenting) {
        return !(commenting && (mode != SourceMode.export || kind.equals("fix")))
                && !(mode == SourceMode.git && !kind.equals("solve"));
    }

    private boolean selected(
            final String component, final String kind, final SourceMode mode, final boolean commenting) {
        return switch (component) {
            case "exportSource" -> mode == SourceMode.export;
            case "gitSource" -> mode == SourceMode.git;
            case "brokerSource" -> mode == SourceMode.broker;
            case "strictPublication" -> !commenting;
            case "agentPublication" -> commenting;
            case "review", "solve", "fix" -> component.equals(kind);
            default -> true;
        };
    }

    private TaskDocument task(final String kind, final String request) {
        return TaskFixtures.task(
                Map.of("id", "task-1", "repository", "owner/repo", "number", 7, "kind", kind, "request", request));
    }

    // Rendering and template validation are business behavior: copy the injected baseline explicitly.
    // Binding of each property is covered independently by the configured nested variants above.
    private PromptConfiguration withComponent(
            final PromptConfiguration defaults, final String component, final String value) {
        assertTrue(defaults.sources().containsKey(component), component);
        return new PromptConfiguration(
                component.equals("main") ? value : defaults.main(),
                component.equals("commonPolicy") ? value : defaults.commonPolicy(),
                component.equals("headings") ? value : defaults.headings(),
                component.equals("exportSource") ? value : defaults.exportSource(),
                component.equals("gitSource") ? value : defaults.gitSource(),
                component.equals("brokerSource") ? value : defaults.brokerSource(),
                component.equals("strictPublication") ? value : defaults.strictPublication(),
                component.equals("agentPublication") ? value : defaults.agentPublication(),
                component.equals("review") ? value : defaults.review(),
                component.equals("solve") ? value : defaults.solve(),
                component.equals("fix") ? value : defaults.fix(),
                component.equals("toolchain") ? value : defaults.toolchain(),
                component.equals("resultContract") ? value : defaults.resultContract(),
                component.equals("taskEnvelope") ? value : defaults.taskEnvelope());
    }
}
