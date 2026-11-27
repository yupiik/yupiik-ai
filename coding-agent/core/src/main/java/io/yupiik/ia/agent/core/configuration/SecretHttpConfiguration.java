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
 * Shared HTTP-header secret harvesting rules, reused by every header-based secret source (Maven
 * settings {@code <server><httpHeaders>}, YCA configuration provider {@code headers}/
 * {@code dynamicHeaders}, ...).
 */
@JsonModel
public record SecretHttpConfiguration(
        @Property(
                defaultValue =
                        "java.util.List.of(\"Authorization\", \"Proxy-Authorization\", \"Api-Key\", \"X-Api-Key\", "
                                + "\"Cookie\")",
                documentation =
                        "Known secret HTTP header names whose credential value is treated as a secret and redacted on "
                                + "the wire wherever header values are harvested (Maven settings, YCA config). Matched "
                                + "case-insensitively, so \"authorization\" and \"Authorization\" both match. For a value "
                                + "with a scheme prefix ('Bearer xyz', 'Basic abc', 'token xyz') only the part after the "
                                + "first space is harvested; without a space the whole value is kept. Empty/absent uses "
                                + "this default.")
        List<String> headerNames) {}
