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
import java.util.List;

/**
 * AWS / GCP / Azure credential-file secret harvesting: the well-known local credential files whose
 * long-lived credential values are redacted on the wire. Only static, long-lived secret values are
 * harvested (AWS secret access keys / session tokens, GCP OAuth client secret / refresh token);
 * short-lived token caches (AWS SSO cache, Azure MSAL/access-token JSON) are deliberately not
 * scanned by default - add their paths to the matching provider's {@code locations} to opt in.
 */
@JsonModel
public record CloudCredentialsSecretConfiguration(
        @Property(documentation = "AWS credential-file locations.")
        Aws aws,

        @Property(documentation = "Google Cloud (gcloud) credential-file locations.")
        Gcp gcp,

        @Property(documentation = "Azure credential-file locations.")
        Az az) {

    @JsonModel
    public record Aws(
            @Property(
                    defaultValue = "java.util.List.of(\"~/.aws/credentials\", \"~/.aws/config\")",
                    documentation = "AWS credential files (INI format) scanned for secrets: aws_secret_access_key and "
                            + "aws_session_token values are redacted. Short-lived SSO/CLI token caches are not "
                            + "included by default; add them (e.g. a cache dir) here to opt in. '~' expands to the "
                            + "user home.")
            List<String> locations) {}

    @JsonModel
    public record Gcp(
            @Property(
                    defaultValue = "java.util.List.of(\"~/.config/gcloud/application_default_credentials.json\")",
                    documentation =
                            "Google Cloud credential files scanned for secrets: an ADC JSON file's client_secret and "
                                    + "refresh_token values are redacted. '~' expands to the user home. The gcloud "
                                    + "credentials.db / access_tokens.db (SQLite, and short-lived) are not included by "
                                    + "default.")
            List<String> locations) {}

    @JsonModel
    public record Az(
            @Property(
                    defaultValue = "java.util.List.of()",
                    documentation =
                            "Azure credential files scanned for secrets. Empty by default: Azure CLI/MSAL store mostly "
                                    + "short-lived access/id tokens (not worth harvesting); add the path to a file that "
                                    + "holds a long-lived credential (e.g. a service-principal secret) to opt in. '~' "
                                    + "expands to the user home.")
            List<String> locations) {}
}
