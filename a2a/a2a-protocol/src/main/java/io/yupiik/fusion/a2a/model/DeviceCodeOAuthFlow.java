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
import java.util.Map;

/**
 * The OAuth 2.0 Device Code flow (RFC 8628) of an {@link OAuthFlows}, for devices which cannot open a browser.
 *
 * @param deviceAuthorizationUrl the device authorization endpoint.
 * @param tokenUrl               the token endpoint.
 * @param refreshUrl             where to refresh a token.
 * @param scopes                 the available scopes and their description.
 */
@JsonModel
public record DeviceCodeOAuthFlow(
        @Property(documentation = "The device authorization endpoint.")
        String deviceAuthorizationUrl,

        @Property(documentation = "The token endpoint.") String tokenUrl,

        @Property(documentation = "Where to refresh a token.")
        String refreshUrl,

        @Property(documentation = "The available scopes and their description.")
        Map<String, String> scopes) {}
