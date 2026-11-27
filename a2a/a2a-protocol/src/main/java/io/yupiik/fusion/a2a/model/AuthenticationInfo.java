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
 * How the agent must authenticate when it calls a push notification webhook.
 *
 * @param scheme      an HTTP authentication scheme from the IANA registry: {@code Bearer}, {@code Basic}, ...
 * @param credentials the credentials, their format depends on {@code scheme} - a token for {@code Bearer}.
 */
@JsonModel
public record AuthenticationInfo(
        @Property(documentation = "An HTTP authentication scheme from the IANA registry: Bearer, Basic, .")
        String scheme,

        @Property(documentation = "The credentials, their format depends on scheme - a token for Bearer.")
        String credentials) {
    /**
     * @return the value to put in the {@code Authorization} header, {@code null} when there is nothing to send.
     */
    public String authorization() {
        if (scheme == null || scheme.isBlank()) {
            return null;
        }
        return credentials == null || credentials.isBlank() ? scheme : scheme + ' ' + credentials;
    }
}
