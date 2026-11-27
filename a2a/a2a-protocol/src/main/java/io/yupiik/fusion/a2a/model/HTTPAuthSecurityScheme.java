/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.fusion.a2a.model;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;

/**
 * An HTTP authentication based {@link SecurityScheme}.
 *
 * @param description   an optional description.
 * @param scheme        the RFC 7235 scheme sent in the {@code Authorization} header, {@code Bearer} for example.
 * @param bearerFormat  a hint about the token format, {@code JWT} for example. It is documentation only.
 */
@JsonModel
public record HTTPAuthSecurityScheme(
        @Property(documentation = "An optional description.")
        String description,

        @Property(documentation = "The RFC 7235 scheme sent in the Authorization header, Bearer for example.")
        String scheme,

        @Property(documentation = "A hint about the token format, JWT for example. It is documentation only.")
        String bearerFormat) {}
