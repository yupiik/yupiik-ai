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
 * How to authenticate with the agent, mapped on the OpenAPI 3.2 Security Scheme Object.
 * <p>
 * It is a proto {@code oneof} so exactly one component is set - prefer the factory methods over the canonical
 * constructor.
 *
 * @param apiKeySecurityScheme         an API key in a header, a query parameter or a cookie.
 * @param httpAuthSecurityScheme       plain HTTP authentication: {@code Basic}, {@code Bearer}, ...
 * @param oauth2SecurityScheme         OAuth 2.0.
 * @param openIdConnectSecurityScheme  OpenID Connect.
 * @param mtlsSecurityScheme           mutual TLS.
 */
@JsonModel
public record SecurityScheme(
        @Property(documentation = "An API key in a header, a query parameter or a cookie.")
        APIKeySecurityScheme apiKeySecurityScheme,

        @Property(documentation = "Plain HTTP authentication: Basic, Bearer, .")
        HTTPAuthSecurityScheme httpAuthSecurityScheme,

        @Property(documentation = "OAuth 2.0.") OAuth2SecurityScheme oauth2SecurityScheme,
        @Property(documentation = "OpenID Connect.") OpenIdConnectSecurityScheme openIdConnectSecurityScheme,
        @Property(documentation = "Mutual TLS.") MutualTlsSecurityScheme mtlsSecurityScheme) {
    public static SecurityScheme apiKey(final APIKeySecurityScheme scheme) {
        return new SecurityScheme(scheme, null, null, null, null);
    }

    public static SecurityScheme httpAuth(final HTTPAuthSecurityScheme scheme) {
        return new SecurityScheme(null, scheme, null, null, null);
    }

    public static SecurityScheme oauth2(final OAuth2SecurityScheme scheme) {
        return new SecurityScheme(null, null, scheme, null, null);
    }

    public static SecurityScheme openIdConnect(final OpenIdConnectSecurityScheme scheme) {
        return new SecurityScheme(null, null, null, scheme, null);
    }

    public static SecurityScheme mutualTls(final MutualTlsSecurityScheme scheme) {
        return new SecurityScheme(null, null, null, null, scheme);
    }

    /**
     * @param bearerFormat a hint about the token format, {@code JWT} for example. It can be {@code null}.
     * @return the most common scheme: an {@code Authorization: Bearer ...} header.
     */
    public static SecurityScheme bearer(final String bearerFormat) {
        return httpAuth(new HTTPAuthSecurityScheme(null, "Bearer", bearerFormat));
    }
}
