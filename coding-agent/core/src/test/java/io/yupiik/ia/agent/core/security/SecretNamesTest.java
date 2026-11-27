/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.yupiik.ia.agent.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Covers name classification and the process-environment versus env-file harvesting split. */
class SecretNamesTest {
    @Nested
    @FusionSupport(modules = ConfigurationTestModule.class)
    class Defaults {
        @Test
        void knownSafeWorkingDirectoriesAreStillSecretIshButNeverHarvested(
                @Fusion final CodingAgentConfiguration config) {
            final var names = new SecretNames(config);
            assertTrue(names.isSecretName("PWD"));
            assertTrue(names.isSecretName("OLDPWD"));
            assertTrue(names.isKnownSafeName("PWD"));
            assertTrue(names.isKnownSafeName("OLDPWD"));
            assertFalse(names.isKnownSafeName("pwd"));
            assertTrue(names.isSecretName("pwd"));
            assertFalse(names.isKnownSafeName("Pwd"));
        }

        @Test
        void realCredentialNamesAreSecretsAndNotKnownSafe(@Fusion final CodingAgentConfiguration config) {
            final var names = new SecretNames(config);
            for (final var secret : List.of(
                    "DB_PASSWORD",
                    "PGPASSWORD",
                    "MY_APP_API_TOKEN",
                    "CLIENT_SECRET",
                    "ACCESS_TOKEN",
                    "HTTP_AUTHORIZATION")) {
                assertTrue(names.isSecretName(secret), secret + " must classify as secret");
                assertFalse(names.isKnownSafeName(secret), secret + " must not be known-safe");
            }
            assertFalse(names.isSecretName("HOME"));
            assertFalse(names.isSecretName("USER"));
        }
    }

    @Test
    void nullConfigIsAnInertMatcher() {
        final var names = new SecretNames(null);
        assertFalse(names.isKnownSafeName("PWD"));
        assertFalse(names.isSecretName("DB_PASSWORD"));
        assertFalse(names.isSecretName("HOME"));
    }

    @Nested
    @FusionSupport(modules = KeywordsModule.class)
    class CustomKeywords {
        @Test
        void configCanOverrideKeywordsAndEmptyTheSafeList(@Fusion final CodingAgentConfiguration config) {
            final var names = new SecretNames(config);
            assertTrue(names.isSecretName("MY_APP_MYTOK_XYZ"));
            assertFalse(names.isSecretName("PWD"));
            assertFalse(names.isKnownSafeName("PWD"));
            assertFalse(names.isKnownSafeName("OLDPWD"));
        }
    }

    @Nested
    @FusionSupport(modules = EnvFilesModule.class)
    class EnvFiles {
        @TempDir
        static Path tmp;

        @Test
        void envFileStillHarvestsPwdWhileProcessEnvSkipsIt(@Fusion final CodingAgentConfiguration config)
                throws IOException {
            Files.writeString(tmp.resolve(".env"), "PWD=/some/folder/that/is/not/a/secret\nDB_PASSWORD=sup3rs3cret\n");
            final var names = new SecretNames(config);
            assertTrue(names.isKnownSafeName("PWD"));
            final Collection<String> values = new EnvFileSecretValuesContributor(config, names).values();
            assertTrue(values.contains("/some/folder/that/is/not/a/secret"), "env file must harvest PWD value");
            assertEquals(2, values.size());
            assertTrue(values.stream().anyMatch(v -> !v.startsWith("/")), "env file must still harvest a real secret");
        }
    }

    public static class KeywordsModule extends ConfigurationTestModule {
        public KeywordsModule() {
            super(Map.of("yca.secrets.environment.keywords", "MYTOK", "yca.secrets.environment.knownSafeValues", ""));
        }
    }

    public static class EnvFilesModule extends ConfigurationTestModule {
        public EnvFilesModule() {
            super(Map.of("yca.secrets.envFiles", EnvFiles.tmp.resolve(".env").toString()));
        }
    }
}
