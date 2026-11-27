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
 * One acceptable way to authenticate: all the schemes it lists must be satisfied, with the listed scopes.
 * <p>
 * {@link AgentCard#securityRequirements()} being a list, satisfying <em>any</em> of its entries is enough.
 *
 * @param schemes the required {@link AgentCard#securitySchemes()} names and, for each, the required scopes.
 */
@JsonModel
public record SecurityRequirement(
        @Property(documentation = "The required AgentCard#securitySchemes() names and, for each, the required scopes.")
        Map<String, StringList> schemes) {
    public static SecurityRequirement of(final String scheme, final String... scopes) {
        return new SecurityRequirement(Map.of(scheme, StringList.of(scopes)));
    }
}
