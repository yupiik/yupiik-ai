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
 * TLS trust configuration of the shared HTTP client: an optional base of the JVM
 * global trust store (cacerts or {@code javax.net.ssl.trustStore}) augmented with
 * explicit PEM certificates. Ignored when {@code disableTlsCheck} is enabled.
 */
@JsonModel
public record HttpClientCertificateConfiguration(
        @Property(
                defaultValue = "true",
                documentation =
                        "Seed the trust store with the JVM global trust store (cacerts, or javax.net.ssl.trustStore when set and loadable) before adding the custom certificates. Set false to trust only the certificates listed in custom.")
        boolean importGlobalCertificate,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Additional X.509 certificates (PEM: base64 between the BEGIN CERTIFICATE and END CERTIFICATE markers) trusted by the shared HTTP client, on top of importGlobalCertificate when enabled. Useful for private CAs / self-signed endpoints. A malformed PEM makes the client creation fail loudly.")
        List<CustomCertificateConfiguration> custom) {}
