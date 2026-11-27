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

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderBudgetConfiguration;
import io.yupiik.ia.agent.github.command.WorkerCommand.Options;
import io.yupiik.ia.agent.github.execution.cache.DependencyCacheProvisioning;
import io.yupiik.ia.agent.github.store.TaskStore;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@FusionSupport(modules = AttemptServicesTest.Source.class)
class AttemptServicesTest {
    @Fusion
    private Options injectedOptions;

    private static final Pattern COMPLETIONS_URL =
            Pattern.compile("https://attempt-services\\.yca\\.internal:8443/inference/[0-9a-f-]{36}/chat/completions");
    private static final String ROUTE = "/context/11111111-2222-3333-4444-555555555555/rpc";
    private static final Map<String, String> CONTEXT =
            Map.of("YCA_CONTEXT_TOKEN", "c".repeat(43), "YCA_CONTEXT_GIT_TOKEN", "g".repeat(43));

    @TempDir
    Path home;

    @TempDir
    static Path cacheHome;

    private final String privateSubnet = "10." + ThreadLocalRandom.current().nextInt(256) + "."
            + ThreadLocalRandom.current().nextInt(256);
    private final String providerBaseUrl = "http://" + privateSubnet + ".1:8000/v1";
    private final String contextBaseUrl = "http://" + privateSubnet + ".2:12345";

    @Test
    void forwardsOnlySelectedSecretsAndBudgetFromConfiguration() throws Exception {
        final var budgetRoot = Files.createTempDirectory(home.getParent(), "services-budget-");
        try {
            final var values = Map.of(
                    "YCA_SERVICES_KEYSTORE_PASSWORD", "configured-tls-secret",
                    "OPENAI_API_KEY", "configured-provider-secret",
                    "UNRELATED_HOST_SECRET", "never-forward");
            try (final var json = BotJsonMapper.create(key -> Optional.empty());
                    final var services = new AttemptServices(
                            minimalConfiguration(),
                            privateDirectory(),
                            json,
                            settings(),
                            contextBaseUrl + ROUTE,
                            "https://attempt-services.yca.internal:8443" + ROUTE,
                            CONTEXT,
                            Configuration.of(values),
                            new ProviderBudgetConfiguration(budgetRoot.toString(), "configured-budget", "2"),
                            Duration.ofSeconds(60))) {
                assertEquals("configured-tls-secret", services.credentials().get("YCA_SERVICES_KEYSTORE_PASSWORD"));
                assertEquals(
                        "configured-provider-secret", services.credentials().get("YCA_SERVICES_UPSTREAM_TOKEN"));
                assertEquals(budgetRoot.toString(), services.credentials().get("YCA_PROVIDER_BUDGET_ROOT"));
                assertEquals("configured-budget", services.credentials().get("YCA_PROVIDER_BUDGET_ID"));
                assertEquals("2", services.credentials().get("YCA_PROVIDER_BUDGET_SLOTS"));
                assertFalse(services.credentials().containsKey("UNRELATED_HOST_SECRET"));
                assertFalse(Files.readString(services.settings()).contains("configured-provider-secret"));
            }
        } finally {
            Files.delete(budgetRoot);
        }
    }

    @Test
    void retainsConfiguredRemoteMcpWithoutForwardingItsCredentialToServices() throws Exception {
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var mcp = Map.of(
                    "enabled",
                    true,
                    "servers",
                    List.of(Map.of(
                            "name", "documentation",
                            "url", "https://docs.example.test/mcp",
                            "apiKey", "agent-only-documentation-key",
                            "readonly", List.of("lookup"))));
            final var configuration = minimalConfiguration();
            final var document = TaskStore.map(json.fromString(Object.class, Files.readString(configuration)));
            final var yca = new LinkedHashMap<>(TaskStore.map(document.get("yca")));
            yca.put("mcp", mcp);
            Files.writeString(configuration, json.toString(Map.of("yca", yca)));
            try (final var services = new AttemptServices(
                    configuration,
                    privateDirectory(),
                    json,
                    settings(),
                    contextBaseUrl + ROUTE,
                    "https://attempt-services.yca.internal:8443" + ROUTE,
                    CONTEXT,
                    Configuration.of(Map.of("YCA_SERVICES_KEYSTORE_PASSWORD", "tls-secret")),
                    new ProviderBudgetConfiguration(null, null, null),
                    Duration.ofSeconds(60))) {
                final var rewritten = TaskStore.map(json.fromString(Object.class, Files.readString(configuration)));
                assertEquals(mcp, TaskStore.map(rewritten.get("yca")).get("mcp"));
                assertFalse(services.credentials().containsValue("agent-only-documentation-key"));
                assertFalse(Files.readString(services.settings()).contains("agent-only-documentation-key"));
            }
        }
    }

    @Test
    void disabledDependencyDownloadsDoNotStageMavenSettings() throws Exception {
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var configured = settings();
            final var disabled = new ExecutorConfiguration.Docker.Services(
                    configured.image(),
                    configured.controlNetwork(),
                    configured.keyStore(),
                    configured.keyStorePasswordEnv(),
                    configured.serverName(),
                    configured.providerTarget(),
                    new ExecutorConfiguration.Docker.Egress(null, List.of(), null, false));
            try (final var _ = new AttemptServices(
                    minimalConfiguration(),
                    privateDirectory(),
                    json,
                    disabled,
                    contextBaseUrl + ROUTE,
                    "https://attempt-services.yca.internal:8443" + ROUTE,
                    CONTEXT,
                    Configuration.of(Map.of("YCA_SERVICES_KEYSTORE_PASSWORD", "tls-secret")),
                    new ProviderBudgetConfiguration(null, null, null),
                    Duration.ofSeconds(60))) {
                assertFalse(Files.exists(home.resolve("storage/.m2/settings.xml")));
            }
        }
    }

    @Test
    void dependencySettingsRefuseSymbolicStorageWithoutWritingOutsideAttempt() throws Exception {
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var outside = Files.createDirectory(home.resolve("outside"));
            Files.createSymbolicLink(home.resolve("storage"), outside);
            final var configuration = minimalConfiguration();
            final var privateDirectory = privateDirectory();
            assertThrows(
                    IOException.class,
                    () -> new AttemptServices(
                            configuration,
                            privateDirectory,
                            json,
                            settings(),
                            contextBaseUrl + ROUTE,
                            "https://attempt-services.yca.internal:8443" + ROUTE,
                            CONTEXT,
                            Configuration.of(Map.of("YCA_SERVICES_KEYSTORE_PASSWORD", "tls-secret")),
                            new ProviderBudgetConfiguration(null, null, null),
                            Duration.ofSeconds(60)));
            assertFalse(Files.exists(outside.resolve(".m2")));
        }
    }

    @Test
    void launcherOwnedProxySettingsWinOverFreshDependencyCacheSettings() throws Exception {
        final var policy = injectedOptions.worker().dependencyCache();
        final var environment = DependencyCacheProvisioning.provision(policy, home, true);
        assertEquals("-Dmaven.repo.local=/attempt/storage/caches/maven/repository", environment.get("MAVEN_OPTS"));
        assertFalse(Files.readString(home.resolve("storage/.m2/settings.xml")).contains("<proxies>"));
        try (final var json = BotJsonMapper.create(key -> Optional.empty());
                final var _ = new AttemptServices(
                        minimalConfiguration(),
                        privateDirectory(),
                        json,
                        settings(),
                        contextBaseUrl + ROUTE,
                        "https://attempt-services.yca.internal:8443" + ROUTE,
                        CONTEXT,
                        Configuration.of(Map.of("YCA_SERVICES_KEYSTORE_PASSWORD", "tls-secret")),
                        new ProviderBudgetConfiguration(null, null, null),
                        Duration.ofSeconds(60))) {
            final var settings = Files.readString(home.resolve("storage/.m2/settings.xml"));
            assertTrue(settings.contains("<host>egress</host>"));
            assertTrue(settings.contains("<port>8080</port>"));
            assertFalse(settings.contains("tls-secret"));
        }
    }

    @Test
    void proxySettingsReplaceHardLinksWithoutChangingTheOriginal() throws Exception {
        final var maven = Files.createDirectories(home.resolve("storage/.m2"));
        final var original = Files.writeString(home.resolve("original-settings"), "keep original bytes");
        Files.createLink(maven.resolve("settings.xml"), original);
        try (final var json = BotJsonMapper.create(key -> Optional.empty());
                final var _ = new AttemptServices(
                        minimalConfiguration(),
                        privateDirectory(),
                        json,
                        settings(),
                        contextBaseUrl + ROUTE,
                        "https://attempt-services.yca.internal:8443" + ROUTE,
                        CONTEXT,
                        Configuration.of(Map.of("YCA_SERVICES_KEYSTORE_PASSWORD", "tls-secret")),
                        new ProviderBudgetConfiguration(null, null, null),
                        Duration.ofSeconds(60))) {
            assertEquals("keep original bytes", Files.readString(original));
            assertFalse(Files.isSameFile(original, maven.resolve("settings.xml")));
            assertTrue(Files.readString(maven.resolve("settings.xml")).contains("<host>egress</host>"));
        }
    }

    @Test
    void proxySettingsRejectSymbolicMavenDirectory() throws Exception {
        final var outside = Files.createDirectory(home.resolve("outside"));
        Files.createDirectory(home.resolve("storage"));
        Files.createSymbolicLink(home.resolve("storage/.m2"), outside);
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            assertThrows(
                    IOException.class,
                    () -> new AttemptServices(
                            minimalConfiguration(),
                            privateDirectory(),
                            json,
                            settings(),
                            contextBaseUrl + ROUTE,
                            "https://attempt-services.yca.internal:8443" + ROUTE,
                            CONTEXT,
                            Configuration.of(Map.of("YCA_SERVICES_KEYSTORE_PASSWORD", "tls-secret")),
                            new ProviderBudgetConfiguration(null, null, null),
                            Duration.ofSeconds(60)));
            assertFalse(Files.exists(outside.resolve("settings.xml")));
        }
    }

    @Test
    void proxySettingsRejectSymbolicSettingsFile() throws Exception {
        final var outside = Files.writeString(home.resolve("outside"), "unchanged");
        Files.createDirectories(home.resolve("storage/.m2"));
        Files.createSymbolicLink(home.resolve("storage/.m2/settings.xml"), outside);
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            assertThrows(
                    IOException.class,
                    () -> new AttemptServices(
                            minimalConfiguration(),
                            privateDirectory(),
                            json,
                            settings(),
                            contextBaseUrl + ROUTE,
                            "https://attempt-services.yca.internal:8443" + ROUTE,
                            CONTEXT,
                            Configuration.of(Map.of("YCA_SERVICES_KEYSTORE_PASSWORD", "tls-secret")),
                            new ProviderBudgetConfiguration(null, null, null),
                            Duration.ofSeconds(60)));
            assertEquals("unchanged", Files.readString(outside));
        }
    }

    private Path minimalConfiguration() throws Exception {
        return Files.writeString(home.resolve("configuration.json"), """
                {"yca":{"provider":{"baseUrl":"%s","model":"fixture"}}}
                """.formatted(providerBaseUrl));
    }

    @Test
    void selectedModernProviderIsRebuiltWithoutAnyUpstreamAuthenticationOrAlternateRoutes() throws Exception {
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var configuration = home.resolve("configuration.json");
            final var upstream = Map.<String, Object>of(
                    "id",
                    "local",
                    "baseUrl",
                    providerBaseUrl,
                    "apiKey",
                    "upstream-secret",
                    "model",
                    "test-model",
                    "api",
                    "COMPLETIONS",
                    "contextWindow",
                    32768,
                    "headers",
                    Map.of("Authorization", "other-secret"),
                    "dynamicHeaders",
                    Map.of("Authorization", "{{exec bad}}"));
            Files.writeString(
                    configuration,
                    json.toString(Map.of(
                            "yca",
                            Map.of(
                                    "provider",
                                    Map.of(
                                            "defaultProvider",
                                            "local",
                                            "providers",
                                            List.of(Map.of("id", "unused", "apiKey", "unused-secret"), upstream)),
                                    "model",
                                    Map.of("compact", "$unused/secret-model"),
                                    "permissions",
                                    Map.of("mode", "YOLO")))));
            final var privateDirectory = privateDirectory();
            try (final var services = new AttemptServices(
                    configuration,
                    privateDirectory,
                    json,
                    settings(),
                    contextBaseUrl + ROUTE,
                    "https://attempt-services.yca.internal:8443" + ROUTE,
                    CONTEXT,
                    Configuration.of(Map.of("YCA_SERVICES_KEYSTORE_PASSWORD", "tls-secret")),
                    new ProviderBudgetConfiguration(null, null, null),
                    Duration.ofSeconds(60))) {
                final var text = Files.readString(configuration);
                for (final var secret :
                        List.of("upstream-secret", "unused-secret", "other-secret", "tls-secret", "{{exec")) {
                    assertFalse(text.contains(secret), secret);
                    assertFalse(Files.readString(services.settings()).contains(secret), secret);
                }
                final var safe = TaskStore.map(
                        TaskStore.map(json.fromString(Object.class, text)).get("yca"));
                assertFalse(safe.containsKey("model"));
                final var provider = TaskStore.map(safe.get("provider"));
                assertEquals("local", provider.get("defaultProvider"));
                final var entries = (List<?>) provider.get("providers");
                assertEquals(1, entries.size());
                final var selected = TaskStore.map(entries.getFirst());
                assertEquals("test-model", selected.get("model"));
                assertEquals("COMPLETIONS", selected.get("api"));
                assertTrue(COMPLETIONS_URL
                        .matcher(selected.get("baseUrl").toString())
                        .matches());
                assertEquals(selected.get("apiKey"), services.credentials().get("YCA_SERVICES_PROVIDER_TOKEN"));
                assertEquals("upstream-secret", services.credentials().get("YCA_SERVICES_UPSTREAM_TOKEN"));
                assertEquals(
                        CONTEXT.get("YCA_CONTEXT_TOKEN"), services.credentials().get("YCA_CONTEXT_TOKEN"));
                assertEquals(
                        PosixFilePermissions.fromString("rw-------"),
                        Files.getPosixFilePermissions(services.settings()));
                final var mavenSettings = home.resolve("storage/.m2/settings.xml");
                final var proxy = Files.readString(mavenSettings);
                assertTrue(proxy.contains("<host>egress</host>"));
                assertTrue(proxy.contains("<port>8080</port>"));
                assertTrue(
                        proxy.contains(
                                "<nonProxyHosts>localhost|127.*|\\[::1\\]|::1|\\[0:0:0:0:0:0:0:1\\]|0:0:0:0:0:0:0:1|attempt-services.yca.internal</nonProxyHosts>"));
                assertTrue(proxy.contains("<localRepository>/attempt/storage/.m2/repository</localRepository>"));
                assertFalse(proxy.contains("<offline>"));
                assertFalse(proxy.contains("upstream-secret"));
                assertEquals(
                        PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(mavenSettings));
                Assertions.assertDoesNotThrow(services::close);
                assertEquals(Map.of(), services.credentials());
            }
        }
    }

    @Test
    void flatProviderUsesOnlyPrivateEnvironmentFallbackAndRequiresExactPinnedTarget() throws Exception {
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var configuration = home.resolve("configuration.json");
            Files.writeString(
                    configuration,
                    json.toString(Map.of(
                            "provider",
                            Map.of("baseUrl", providerBaseUrl + "/chat/completions", "model", "test-model"))));
            final var privateDirectory = privateDirectory();
            final var environment = Configuration.of(
                    Map.of("YCA_SERVICES_KEYSTORE_PASSWORD", "tls-secret", "OPENAI_API_KEY", "env-provider-secret"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AttemptServices(
                            configuration,
                            privateDirectory,
                            json,
                            settings(),
                            "http://127.0.0.1:12345" + ROUTE,
                            "https://attempt-services.yca.internal:8443" + ROUTE,
                            CONTEXT,
                            environment,
                            new ProviderBudgetConfiguration(null, null, null),
                            Duration.ofSeconds(60)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AttemptServices(
                            configuration,
                            privateDirectory,
                            json,
                            settings(),
                            contextBaseUrl + ROUTE,
                            "https://other.invalid:8443" + ROUTE,
                            CONTEXT,
                            environment,
                            new ProviderBudgetConfiguration(null, null, null),
                            Duration.ofSeconds(60)));
            try (final var services = new AttemptServices(
                    configuration,
                    privateDirectory,
                    json,
                    settings(),
                    contextBaseUrl + ROUTE,
                    "https://attempt-services.yca.internal:8443" + ROUTE,
                    CONTEXT,
                    environment,
                    new ProviderBudgetConfiguration(null, null, null),
                    Duration.ofSeconds(60))) {
                assertEquals("env-provider-secret", services.credentials().get("YCA_SERVICES_UPSTREAM_TOKEN"));
                assertFalse(Files.readString(configuration).contains("env-provider-secret"));
            }
        }
    }

    @Test
    void rewriteRetainsOnlyStringCorrectionPromptFromRootRunForWrappedAndFlatDocuments() throws Exception {
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var prompt = "Correct the result using {{literal}} and ${UNCHANGED}; do not interpolate.";
            final var runs = List.of(
                    Map.of("result-correction-prompt", prompt, "arbitrary-option", "discard-me"),
                    Map.of("result-correction-prompt", List.of("not-a-string"), "arbitrary-option", "discard-me"),
                    Map.of("arbitrary-option", "discard-me"),
                    "not-a-map");
            for (final var wrapped : List.of(false, true)) {
                for (int index = 0; index < runs.size(); index++) {
                    final var directory = Files.createDirectory(home.resolve(wrapped + "-" + index));
                    final var configuration = directory.resolve("configuration.json");
                    final var provider = Map.of("provider", Map.of("baseUrl", providerBaseUrl, "model", "fixture"));
                    final var document = new LinkedHashMap<String, Object>();
                    if (wrapped) {
                        document.put("yca", provider);
                    } else {
                        document.putAll(provider);
                    }
                    document.put("run", runs.get(index));
                    document.put("unrelated-root-option", "discard-me-too");
                    Files.writeString(configuration, json.toString(document));
                    final var privateDirectory = Files.createDirectory(
                            directory.resolve("private"),
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                    try (final var _ = new AttemptServices(
                            configuration,
                            privateDirectory,
                            json,
                            settings(),
                            contextBaseUrl + ROUTE,
                            "https://attempt-services.yca.internal:8443" + ROUTE,
                            CONTEXT,
                            Configuration.of(Map.of("YCA_SERVICES_KEYSTORE_PASSWORD", "tls-secret")),
                            new ProviderBudgetConfiguration(null, null, null),
                            Duration.ofSeconds(60))) {
                        final var rewritten =
                                TaskStore.map(json.fromString(Object.class, Files.readString(configuration)));
                        if (index == 0) {
                            assertEquals(Map.of("result-correction-prompt", prompt), rewritten.get("run"));
                        } else {
                            assertFalse(rewritten.containsKey("run"));
                        }
                        assertFalse(rewritten.containsKey("unrelated-root-option"));
                        assertFalse(TaskStore.map(rewritten.get("yca")).containsKey("run"));
                    }
                }
            }
        }
    }

    @Test
    void changedProviderTargetIsRejectedWithoutRewritingAgentConfiguration() throws Exception {
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var configuration = home.resolve("configuration.json");
            final var original = json.toString(
                    Map.of("provider", Map.of("baseUrl", "http://" + privateSubnet + ".3:8000/v1", "model", "test")));
            Files.writeString(configuration, original);
            final var privateDirectory = privateDirectory();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AttemptServices(
                            configuration,
                            privateDirectory,
                            json,
                            settings(),
                            contextBaseUrl + ROUTE,
                            "https://attempt-services.yca.internal:8443" + ROUTE,
                            CONTEXT,
                            Configuration.of(Map.of("YCA_SERVICES_KEYSTORE_PASSWORD", "tls-secret")),
                            new ProviderBudgetConfiguration(null, null, null),
                            Duration.ofSeconds(60)));
            assertEquals(original, Files.readString(configuration));
            assertFalse(Files.exists(privateDirectory.resolve("services.json")));
        }
    }

    private Path privateDirectory() throws Exception {
        return Files.createDirectory(
                home.resolve("private"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    private ExecutorConfiguration.Docker.Services settings() {
        return new ExecutorConfiguration.Docker.Services(
                "egress:test",
                "worker-control",
                home.resolve("tls.p12").toString(),
                "YCA_SERVICES_KEYSTORE_PASSWORD",
                "attempt-services.yca.internal",
                providerBaseUrl,
                null);
    }

    public static class Source extends GithubConfigurationTestModule {
        public Source() {
            super(Map.of(
                    "worker.dependencyCache.mode", "RESTORE_ONLY",
                    "worker.dependencyCache.root",
                            cacheHome.resolve("unused-store").toString(),
                    "worker.dependencyCache.presets", "MAVEN"));
        }
    }
}
