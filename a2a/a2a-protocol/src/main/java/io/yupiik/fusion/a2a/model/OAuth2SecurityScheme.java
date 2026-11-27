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
 * An OAuth 2.0 based {@link SecurityScheme}.
 *
 * @param description        an optional description.
 * @param flows              the supported flows.
 * @param oauth2MetadataUrl  the RFC 8414 authorization server metadata URL.
 */
@JsonModel
public record OAuth2SecurityScheme(
        @Property(documentation = "An optional description.")
        String description,

        @Property(documentation = "The supported flows.") OAuthFlows flows,

        @Property(documentation = "The RFC 8414 authorization server metadata URL.")
        String oauth2MetadataUrl) {}
