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
 * The OAuth 2.0 flow an {@link OAuth2SecurityScheme} supports.
 * <p>
 * It is a proto {@code oneof} so exactly one component is set.
 *
 * @param authorizationCode the Authorization Code flow, the one to use for a user facing client.
 * @param clientCredentials the Client Credentials flow, the one to use for an agent to agent call.
 * @param implicit          deprecated, use Authorization Code with PKCE instead.
 * @param password          deprecated, use Authorization Code with PKCE or Device Code instead.
 * @param deviceCode        the Device Code flow (RFC 8628), for input constrained devices.
 */
@JsonModel
public record OAuthFlows(
        @Property(documentation = "The Authorization Code flow, the one to use for a user facing client.")
        AuthorizationCodeOAuthFlow authorizationCode,

        @Property(documentation = "The Client Credentials flow, the one to use for an agent to agent call.")
        ClientCredentialsOAuthFlow clientCredentials,

        @Property(documentation = "Deprecated, use Authorization Code with PKCE instead.")
        ImplicitOAuthFlow implicit,

        @Property(documentation = "Deprecated, use Authorization Code with PKCE or Device Code instead.")
        PasswordOAuthFlow password,

        @Property(documentation = "The Device Code flow (RFC 8628), for input constrained devices.")
        DeviceCodeOAuthFlow deviceCode) {
    public static OAuthFlows authorizationCode(final AuthorizationCodeOAuthFlow flow) {
        return new OAuthFlows(flow, null, null, null, null);
    }

    public static OAuthFlows clientCredentials(final ClientCredentialsOAuthFlow flow) {
        return new OAuthFlows(null, flow, null, null, null);
    }

    public static OAuthFlows deviceCode(final DeviceCodeOAuthFlow flow) {
        return new OAuthFlows(null, null, null, null, flow);
    }
}
