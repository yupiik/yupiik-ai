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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/** Verifies cloud credential harvesting without reading host credential locations. */
@ExtendWith(TestJsonMapper.class)
class CloudCredentialsSecretValuesContributorTest {
    private static final String AWS_PER_PROVIDER_VALUE = "aws-test-provider-secret-value";

    @Nested
    @FusionSupport(modules = UnicodeModule.class)
    class UnicodeLines {
        @TempDir
        static Path tmp;

        @Test
        void awsIniRecognizesAllRegexLineBreaks(final JsonMapper mapper, @Fusion final CodingAgentConfiguration config)
                throws Exception {
            Files.writeString(
                    tmp.resolve("unicode-lines.ini"),
                    "[default]\r\naws_secret_access_key=" + AWS_PER_PROVIDER_VALUE
                            + "\u0085aws_session_token=second-secret-value\u2028region=us-east-1\u2029");
            final var values = Set.copyOf(new CloudCredentialsSecretValuesContributor(config, mapper).values());
            assertTrue(values.contains(AWS_PER_PROVIDER_VALUE), values.toString());
            assertTrue(values.contains("second-secret-value"), values.toString());
            assertFalse(values.contains("us-east-1"), values.toString());
        }
    }

    @Nested
    @FusionSupport(modules = AwsModule.class)
    class Aws {
        @TempDir
        static Path tmp;

        @Test
        void awsIniHarvestsSecretKeysOnly(final JsonMapper mapper, @Fusion final CodingAgentConfiguration config)
                throws Exception {
            Files.writeString(tmp.resolve("credentials"), """
                    [default]
                    aws_access_key_id = AKIAEXAMPLE123
                    aws_secret_access_key = aws-secret-access-key-value
                    aws_session_token = aws-session-token-value
                    region = us-east-1
                    """);
            final var values = Set.copyOf(new CloudCredentialsSecretValuesContributor(config, mapper).values());
            assertTrue(values.contains("aws-secret-access-key-value"), "secret_access_key: " + values);
            assertTrue(values.contains("aws-session-token-value"), "session_token: " + values);
            assertFalse(values.contains("AKIAEXAMPLE123"), "access key id must not be a secret: " + values);
            assertFalse(values.contains("us-east-1"), "region must not be a secret: " + values);
        }
    }

    @Nested
    @FusionSupport(modules = GcpModule.class)
    class Gcp {
        @TempDir
        static Path tmp;

        @Test
        void gcpJsonHarvestsSecretKeysOnly(final JsonMapper mapper, @Fusion final CodingAgentConfiguration config)
                throws Exception {
            Files.writeString(tmp.resolve("application_default_credentials.json"), """
                    {
                      "type": "authorized_user",
                      "client_id": "1234567890.apps.googleusercontent.com",
                      "client_secret": "gcp-client-secret-value",
                      "refresh_token": "gcp-refresh-token-value",
                      "project_id": "my-project"
                    }
                    """);
            final var values = Set.copyOf(new CloudCredentialsSecretValuesContributor(config, mapper).values());
            assertTrue(values.contains("gcp-client-secret-value"), "client_secret: " + values);
            assertTrue(values.contains("gcp-refresh-token-value"), "refresh_token: " + values);
            assertFalse(values.contains("authorized_user"), "type must not be a secret: " + values);
            assertFalse(
                    values.contains("1234567890.apps.googleusercontent.com"),
                    "client_id must not be a secret: " + values);
            assertFalse(values.contains("my-project"), "project_id must not be a secret: " + values);
        }
    }

    @Nested
    @FusionSupport(modules = MissingModule.class)
    class Missing {
        @TempDir
        static Path tmp;

        @Test
        void missingFilesAndEmptyAzureListAreSkipped(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration config) {
            final var contributor = new CloudCredentialsSecretValuesContributor(config, mapper);
            assertTrue(
                    contributor.values().isEmpty(), "missing files + empty az -> no secrets: " + contributor.values());
        }
    }

    @Nested
    @FusionSupport(modules = OverrideModule.class)
    class OverrideLocation {
        @TempDir
        static Path tmp;

        @Test
        void perProviderLocationsOverride(final JsonMapper mapper, @Fusion final CodingAgentConfiguration config)
                throws Exception {
            Files.writeString(tmp.resolve("override.ini"), "aws_secret_access_key=" + AWS_PER_PROVIDER_VALUE + "\n");
            final var values = Set.copyOf(new CloudCredentialsSecretValuesContributor(config, mapper).values());
            assertTrue(values.contains(AWS_PER_PROVIDER_VALUE), "per-provider aws override: " + values);
        }
    }

    public static class UnicodeModule extends ConfigurationTestModule {
        public UnicodeModule() {
            super(locations(UnicodeLines.tmp.resolve("unicode-lines.ini"), null));
        }
    }

    public static class AwsModule extends ConfigurationTestModule {
        public AwsModule() {
            super(locations(Aws.tmp.resolve("credentials"), null));
        }
    }

    public static class GcpModule extends ConfigurationTestModule {
        public GcpModule() {
            super(locations(null, Gcp.tmp.resolve("application_default_credentials.json")));
        }
    }

    public static class MissingModule extends ConfigurationTestModule {
        public MissingModule() {
            super(locations(Missing.tmp.resolve("missing-aws"), Missing.tmp.resolve("missing-gcp")));
        }
    }

    public static class OverrideModule extends ConfigurationTestModule {
        public OverrideModule() {
            super(locations(OverrideLocation.tmp.resolve("override.ini"), null));
        }
    }

    private static Map<String, String> locations(final Path aws, final Path gcp) {
        return Map.of(
                "yca.secrets.cloudCredentials.aws.locations",
                aws == null ? "" : aws.toString(),
                "yca.secrets.cloudCredentials.gcp.locations",
                gcp == null ? "" : gcp.toString(),
                "yca.secrets.cloudCredentials.az.locations",
                "");
    }
}
