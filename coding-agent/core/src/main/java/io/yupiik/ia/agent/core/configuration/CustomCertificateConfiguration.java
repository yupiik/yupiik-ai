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

/** A single PEM-encoded X.509 certificate (base64 between BEGIN CERTIFICATE / END CERTIFICATE markers). */
@JsonModel
public record CustomCertificateConfiguration(
        @Property(
                documentation =
                        "The PEM content of the certificate, e.g. -----BEGIN CERTIFICATE-----\\n...\\n-----END CERTIFICATE-----. Leading/trailing whitespace and surrounding text are tolerated; the certificate itself must be valid X.509.")
        String pem) {}
