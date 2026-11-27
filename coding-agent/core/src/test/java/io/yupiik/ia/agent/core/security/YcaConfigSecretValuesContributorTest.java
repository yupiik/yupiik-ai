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
package io.yupiik.ia.agent.core.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies YCA configuration-file secret harvesting: API keys ({@code *apiKey*} leaves) and the
 * credential part of known secret HTTP headers (from {@code yca.secrets.http.headerNames}), read from
 * BOTH the project-local {@code .yca/configuration.json} and the global configuration file - even when
 * a local file would shadow the global one in the resolved configuration.
 */
class YcaConfigSecretValuesContributorTest {

    private static void writeCodexAuth(
            final Path file,
            final String access,
            final String refresh,
            final String id,
            final String apiKey,
            final String agentPrivateKey)
            throws Exception {
        Files.createDirectories(file.getParent());
        final var agent = agentPrivateKey == null ? "" : """
            ,"agent_identity":{"agent_runtime_id":"ar-1","agent_private_key":"%s","account_id":"account-id-1",
            "chatgpt_user_id":"u-1","email":"nobody@example.com","plan_type":"plus","task_id":"t-1"}
            """.formatted(agentPrivateKey);
        Files.writeString(file, """
            {"accessToken":"%s","refreshToken":"%s","idToken":"%s","openai_api_key":"%s",
            "accountId":"account-id-1","expiresAt":1793957639029,"issuer":"%s","client_id":"client-id-1",
            "last_refresh":1793093639029%s}
            """.formatted(access, refresh, id, apiKey, "issuer-id", agent));
    }

    @Nested
    @ExtendWith(TestJsonMapper.class)
    @FusionSupport(modules = ConfigFilesModule.class)
    class ConfigFiles {
        @TempDir
        static Path tmp;

        @Test
        void harvestsApiKeyLeavesAndSecretHeaderTokens(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration config) throws Exception {
            final var local = tmp.resolve(".yca").resolve("configuration.json");
            Files.createDirectories(local.getParent());
            Files.writeString(local, """
                {
                  "yca": {
                    "provider": {
                      "apiKey": "project-api-key-secret",
                      "providers": [ {"id": "p", "apiKey": "provider-key-secret"} ]
                    },
                    "tools": { "enabled": true }
                  }
                }
                """);
            final var global = tmp.resolve("global-config.json");
            Files.writeString(global, """
                {
                  "yca": {
                    "provider": {
                      "dynamicHeaders": {
                        "Authorization": "Bearer global-bearer-token",
                        "X-Custom": "not-a-secret-header"
                      }
                    }
                  }
                }
                """);

            final var rawConfig = Configuration.of(Map.of(
                    "yca.workingDir", tmp.toString(),
                    "yca.configuration.json", global.toString(),
                    "user.home", tmp.toString()));
            final var contributor = new YcaConfigSecretValuesContributor(config, rawConfig, mapper);
            final var values = Set.copyOf(contributor.values());

            // apiKey leaves (both files)
            assertTrue(values.contains("project-api-key-secret"), "project apiKey: " + values);
            assertTrue(values.contains("provider-key-secret"), "provider apiKey: " + values);
            // secret header: only the credential after the scheme is harvested
            assertTrue(values.contains("global-bearer-token"), "Authorization token: " + values);
            // the global file is harvested even though a local file exists (reads both files)
            assertTrue(values.contains("global-bearer-token"), "global header harvested despite local file: " + values);
            // non-secret header value must NOT be harvested
            assertFalse(values.contains("not-a-secret-header"), "non-secret header must be ignored: " + values);
            // scheme prefix alone must not be a secret
            assertFalse(values.contains("Bearer"), "scheme prefix must not be a secret: " + values);
        }
    }

    @Nested
    @ExtendWith(TestJsonMapper.class)
    @FusionSupport(modules = MissingFilesModule.class)
    class MissingFiles {
        @TempDir
        static Path tmp;

        @Test
        void missingFilesAreSkipped(final JsonMapper mapper, @Fusion final CodingAgentConfiguration config)
                throws Exception {
            final var rawConfig = Configuration.of(Map.of(
                    "yca.workingDir", tmp.resolve("nope").toString(),
                    "yca.configuration.json", tmp.resolve("missing.json").toString(),
                    "user.home", tmp.resolve("home").toString()));
            final var contributor = new YcaConfigSecretValuesContributor(config, rawConfig, mapper);
            assertTrue(contributor.values().isEmpty(), "no files -> no secrets: " + contributor.values());
        }
    }

    @Nested
    @ExtendWith(TestJsonMapper.class)
    @FusionSupport(modules = CodexTokensModule.class)
    class CodexTokens {
        @TempDir
        static Path tmp;

        @Test
        void harvestsCodexProviderAuthTokensFromEveryProviderDir(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration config) throws Exception {
            // Hermetic: storage home is pinned to the temp dir, so the real user home is never touched.
            final var storageHome = tmp.resolve("storage");
            writeCodexAuth(
                    storageHome.resolve("providers/codex/codex.auth.json"),
                    "eyJb2QyLTAx.eyJzdWIiOiJ1c2VyIn0.sig",
                    "rt.1.REFRESH_TOKEN_AAA",
                    "eyJpZDEtMDA.eyJzdWIiOiJ1c2VyIn0.sig",
                    "sk-codex-api-key-value",
                    null);
            writeCodexAuth(
                    storageHome.resolve("providers/codex-yupiik/codex.auth.json"),
                    "rsYIelHBNncjPjE19vbUl_RefViEZJtL5K_sig.sig.sig",
                    "rt.1.REFRESH_TOKEN_BBB",
                    "zneJpU28z9xD6xhTB3xspweNU9NC85GvyfZJrwE5dMfQVeDE_apiId.sig.sig",
                    "sk-codex-yupiik-api-key",
                    "AGENT_PRIVATE_KEY_ED25519_BASE64");

            final var rawConfig = Configuration.of(Map.of(
                    "yca.workingDir", tmp.resolve("workdir").toString(),
                    "user.home", tmp.resolve("home").toString()));
            final var contributor = new YcaConfigSecretValuesContributor(config, rawConfig, mapper);
            final var values = Set.copyOf(contributor.values());

            // token leaves from BOTH provider dirs are harvested
            assertTrue(values.contains("rt.1.REFRESH_TOKEN_AAA"), "refresh token: " + values);
            assertTrue(values.contains("rt.1.REFRESH_TOKEN_BBB"), "refresh token: " + values);
            assertTrue(values.contains("eyJb2QyLTAx.eyJzdWIiOiJ1c2VyIn0.sig"), "access token: " + values);
            assertTrue(values.contains("rsYIelHBNncjPjE19vbUl_RefViEZJtL5K_sig.sig.sig"), "access token: " + values);
            assertTrue(values.contains("eyJpZDEtMDA.eyJzdWIiOiJ1c2VyIn0.sig"), "id token: " + values);
            assertTrue(values.contains("sk-codex-api-key-value"), "openai api key: " + values);
            assertTrue(values.contains("sk-codex-yupiik-api-key"), "openai api key: " + values);
            assertTrue(values.contains("AGENT_PRIVATE_KEY_ED25519_BASE64"), "agent private key: " + values);

            // non-secret metadata must NOT be harvested
            assertFalse(values.contains("account-id-1"), "account id is not a secret: " + values);
            assertFalse(values.contains("issuer-id"), "issuer is not a secret: " + values);
            assertFalse(values.contains("client-id-1"), "client id is not a secret: " + values);
        }
    }

    @Nested
    @ExtendWith(TestJsonMapper.class)
    @FusionSupport(modules = ExplicitLocationsModule.class)
    class ExplicitLocations {
        @TempDir
        static Path tmp;

        @Test
        void explicitLocationsOverrideAutoComputation(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration config) throws Exception {
            final var onlyFile = tmp.resolve("only.json");
            Files.writeString(onlyFile, """
                {"Authorization": "Bearer custom-location-token-value", "apiKey": "custom-api-key"}
                """);

            // a rawConfiguration pointing elsewhere to prove the explicit locations win
            final var rawConfig = Configuration.of(Map.of(
                    "yca.workingDir", tmp.resolve("ignored-workdir").toString(),
                    "user.home", tmp.toString()));
            final var contributor = new YcaConfigSecretValuesContributor(config, rawConfig, mapper);
            final var values = Set.copyOf(contributor.values());

            assertTrue(values.contains("custom-location-token-value"), "Authorization token: " + values);
            assertTrue(values.contains("custom-api-key"), "apiKey: " + values);
        }
    }

    public static class ConfigFilesModule extends ConfigurationTestModule {
        public ConfigFilesModule() {
            super(Map.of("yca.storageHome", ConfigFiles.tmp.resolve("storage").toString()));
        }
    }

    public static class MissingFilesModule extends ConfigurationTestModule {
        public MissingFilesModule() {
            super(Map.of("yca.storageHome", MissingFiles.tmp.resolve("storage").toString()));
        }
    }

    public static class CodexTokensModule extends ConfigurationTestModule {
        public CodexTokensModule() {
            super(Map.of("yca.storageHome", CodexTokens.tmp.resolve("storage").toString()));
        }
    }

    public static class ExplicitLocationsModule extends ConfigurationTestModule {
        public ExplicitLocationsModule() {
            super(Map.of(
                    "yca.storageHome",
                    ExplicitLocations.tmp.resolve("storage").toString(),
                    "yca.secrets.ycaConfig.locations",
                    ExplicitLocations.tmp.resolve("only.json").toString()));
        }
    }
}
