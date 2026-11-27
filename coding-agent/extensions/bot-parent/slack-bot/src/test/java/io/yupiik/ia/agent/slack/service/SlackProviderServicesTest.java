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
package io.yupiik.ia.agent.slack.service;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.bot.gateway.ProviderServicesGateway;
import io.yupiik.ia.agent.bot.runtime.model.AgentConfigurationDocument;
import io.yupiik.ia.agent.core.configuration.ProviderBudgetConfiguration;
import io.yupiik.ia.agent.core.llm.ApiFlavor;
import io.yupiik.ia.agent.core.llm.JsonMappers;
import io.yupiik.ia.agent.core.llm.UnixProviderProtocol;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SlackProviderServicesTest {
    private static final String TARGET = "http://172.20.0.1:8080/v1";
    private static final String PASSWORD_ENV = "YCA_SERVICES_KEYSTORE_PASSWORD";

    @TempDir
    Path root;

    @ParameterizedTest
    @EnumSource(
            value = ApiFlavor.class,
            names = {"COMPLETIONS", "RESPONSES"})
    void replacesSelectedInlineCredentialAndDropsAlternateRoutesForBothDocumentShapes(final ApiFlavor api)
            throws Exception {
        final var target = api == ApiFlavor.RESPONSES ? TARGET + "/responses" : TARGET;
        try (final var json = mapper()) {
            for (final var wrapped : List.of(false, true)) {
                final var directory = Files.createDirectory(root.resolve(wrapped ? "wrapped" : "plain"));
                final var selected = Map.<String, Object>of(
                        "id",
                        "selected",
                        "api",
                        api.name(),
                        "model",
                        "trusted-model",
                        "baseUrl",
                        target,
                        "apiKey",
                        "selected-inline-secret",
                        "headers",
                        Map.of("X-Secret", "selected-header-secret"),
                        "models",
                        Map.of("alias", Map.of("apiKey", "alias-secret")));
                final var trusted = Map.<String, Object>of(
                        "provider",
                                Map.of(
                                        "defaultProvider",
                                        "selected",
                                        "providers",
                                        List.of(
                                                Map.of(
                                                        "id",
                                                        "alternate",
                                                        "baseUrl",
                                                        "https://alternate.invalid",
                                                        "apiKey",
                                                        "alternate-secret"),
                                                selected)),
                        "model", Map.of("alias", "alternate/model"));
                final var configuration = Files.writeString(
                        directory.resolve("configuration.json"),
                        json.toString(wrapped ? Map.of("yca", trusted) : trusted));
                final var privateRoot = Files.createDirectory(directory.resolve("private"));
                final var services = new SlackProviderServices(
                        configuration,
                        privateRoot,
                        json,
                        services(target),
                        Configuration.of(
                                Map.of(PASSWORD_ENV, "private-tls-secret", "OPENAI_API_KEY", "fallback-secret")),
                        new ProviderBudgetConfiguration(null, null, null),
                        60);

                final var rewritten = Files.readString(configuration);
                final var manifestText = Files.readString(services.settings());
                final var document = json.fromString(AgentConfigurationDocument.class, rewritten);
                final var provider = (Map<?, ?>) document.configuration().get("provider");
                assertEquals("selected", provider.get("defaultProvider"));
                final var providers = (List<?>) provider.get("providers");
                assertEquals(1, providers.size());
                final var safe = (Map<?, ?>) providers.getFirst();
                final var capability = services.secrets().get("YCA_SERVICES_PROVIDER_TOKEN");
                assertNotNull(capability);
                assertFalse(capability.isBlank());
                assertEquals(capability, safe.get("apiKey"));
                assertEquals("trusted-model", safe.get("model"));
                assertEquals(api.name(), safe.get("api"));
                assertFalse(safe.containsKey("headers"));
                assertFalse(safe.containsKey("models"));
                assertFalse(document.configuration().containsKey("model"));
                assertEquals("selected-inline-secret", services.secrets().get("YCA_SERVICES_UPSTREAM_TOKEN"));
                assertEquals("private-tls-secret", services.secrets().get(PASSWORD_ENV));
                for (final var secret : List.of(
                        "selected-inline-secret",
                        "alternate-secret",
                        "selected-header-secret",
                        "alias-secret",
                        "private-tls-secret",
                        "fallback-secret")) {
                    assertFalse(rewritten.contains(secret), secret);
                    assertFalse(manifestText.contains(secret), secret);
                }
                assertFalse(manifestText.contains(capability));
                final var manifest = json.fromString(ProviderServicesGateway.Settings.class, manifestText);
                assertEquals(
                        TARGET + (api == ApiFlavor.RESPONSES ? "/responses" : "/chat/completions"),
                        manifest.inferenceTarget());
                assertEquals(
                        "https://attempt-services.yca.internal:8443" + manifest.inferencePath(), safe.get("baseUrl"));
            }
        }
    }

    @Test
    void forwardsTypedBudgetOnlyToPrivateServices() throws Exception {
        final var budgetRoot = Files.createTempDirectory(root.getParent(), "slack-services-budget-");
        try (final var json = mapper()) {
            final var configuration = Files.writeString(
                    root.resolve("configuration.json"),
                    json.toString(Map.of(
                            "provider", Map.of("baseUrl", TARGET, "model", "test", "apiKey", "upstream-secret"))));
            final var services = new SlackProviderServices(
                    configuration,
                    Files.createDirectory(root.resolve("private")),
                    json,
                    services(TARGET),
                    Configuration.of(Map.of(PASSWORD_ENV, "tls-secret", "UNRELATED_HOST_SECRET", "never-forward")),
                    new ProviderBudgetConfiguration(budgetRoot.toString(), "configured-budget", "2"),
                    60);
            assertEquals(budgetRoot.toString(), services.secrets().get("YCA_PROVIDER_BUDGET_ROOT"));
            assertEquals("configured-budget", services.secrets().get("YCA_PROVIDER_BUDGET_ID"));
            assertEquals("2", services.secrets().get("YCA_PROVIDER_BUDGET_SLOTS"));
            assertFalse(services.secrets().containsKey("UNRELATED_HOST_SECRET"));
            for (final var file : List.of(configuration, services.settings())) {
                final var text = Files.readString(file);
                assertFalse(text.contains(budgetRoot.toString()));
                assertFalse(text.contains("configured-budget"));
                assertFalse(text.contains("upstream-secret"));
            }
        } finally {
            Files.delete(budgetRoot);
        }
    }

    @Test
    void rejectsMismatchedEndpointWithoutWritingManifestOrRewritingConfiguration() throws Exception {
        try (final var json = mapper()) {
            final var original = json.toString(Map.of(
                    "provider",
                    Map.of(
                            "api",
                            "COMPLETIONS",
                            "model",
                            "model",
                            "baseUrl",
                            "http://172.20.0.2:8080/v1",
                            "apiKey",
                            "inline-secret")));
            final var configuration = Files.writeString(root.resolve("configuration.json"), original);
            final var privateRoot = Files.createDirectory(root.resolve("private"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new SlackProviderServices(
                            configuration,
                            privateRoot,
                            json,
                            services(TARGET),
                            Configuration.of(Map.of(PASSWORD_ENV, "tls-secret")),
                            new ProviderBudgetConfiguration(null, null, null),
                            60));
            assertEquals(original, Files.readString(configuration));
            assertFalse(Files.exists(privateRoot.resolve("services.json")));
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ApiFlavor.class,
            names = {"COMPLETIONS", "RESPONSES"})
    void nativeHandoffUsesOnlyPinnedUnixTransportCapability(final ApiFlavor api) throws Exception {
        final var target =
                "https://provider.example/v1" + (api == ApiFlavor.RESPONSES ? "/responses" : "/chat/completions");
        try (final var json = mapper()) {
            final var configuration = Files.writeString(
                    root.resolve("configuration.json"),
                    json.toString(Map.of(
                            "provider",
                            Map.of(
                                    "api",
                                    "AUTO",
                                    "model",
                                    "trusted-model",
                                    "baseUrl",
                                    target,
                                    "apiKey",
                                    "upstream-private-secret"))));
            final var services = new SlackProviderServices(
                    configuration,
                    Files.createDirectory(root.resolve("private")),
                    json,
                    new SlackProviderServices.Routing(
                            target, UnixProviderProtocol.ENDPOINT.toString(), "native.invalid", 0, "/unused", ""),
                    Configuration.of(Map.of()),
                    new ProviderBudgetConfiguration(null, null, null),
                    60);
            final var rewritten = Files.readString(configuration);
            assertTrue(rewritten.contains(UnixProviderProtocol.endpoint(api).toString()));
            assertEquals(api, services.api());
            assertFalse(rewritten.contains("upstream-private-secret"));
            assertFalse(rewritten.contains("https://provider.example"));
            assertEquals(target, services.upstream().toString());
            assertEquals("upstream-private-secret", services.secrets().get("YCA_SERVICES_UPSTREAM_TOKEN"));
            assertNull(services.manifest());
            assertFalse(Files.readString(services.settings()).contains("upstream-private-secret"));
        }
    }

    @Test
    void rejectsUnsupportedAndConflictingApisBeforeRewritingCredentials() throws Exception {
        try (final var json = mapper()) {
            final var configuration = root.resolve("configuration.json");
            final var privateRoot = Files.createDirectory(root.resolve("private"));
            for (final var api : List.of("COMPLETIONS", "ANTHROPIC", "CODEX", "OPENCODE_FREE", "invalid-api")) {
                final var original = json.toString(Map.of(
                        "provider",
                        Map.of(
                                "api",
                                api,
                                "model",
                                "test",
                                "baseUrl",
                                TARGET + "/responses",
                                "apiKey",
                                "private-key")));
                Files.writeString(configuration, original);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new SlackProviderServices(
                                configuration,
                                privateRoot,
                                json,
                                services(TARGET + "/responses"),
                                Configuration.of(Map.of(PASSWORD_ENV, "tls-secret")),
                                new ProviderBudgetConfiguration(null, null, null),
                                60));
                assertEquals(original, Files.readString(configuration));
                assertFalse(Files.exists(privateRoot.resolve("services.json")));
            }
        }
    }

    private JsonMapper mapper() {
        return JsonMappers.create(ignored -> Optional.empty());
    }

    private SlackProviderServices.Routing services(final String target) {
        return new SlackProviderServices.Routing(
                target,
                "https://attempt-services.yca.internal:8443",
                "attempt-services.yca.internal",
                8443,
                "/services/tls.p12",
                PASSWORD_ENV);
    }
}
